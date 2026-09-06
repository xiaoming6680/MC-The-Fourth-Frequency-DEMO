package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.WorldInterfaceState.MutationResult;
import com.xm.thefourthfrequency.ending.WorldInterfaceState.Snapshot;
import com.xm.thefourthfrequency.ending.WorldInterfaceState.TerminalTransaction;
import com.xm.thefourthfrequency.ending.WorldInterfaceState.TerminalTransactionState;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-side journal and recovery loop for terminal sacrifice at the resonance core. */
public final class WorldInterfaceRitualService {
	private static final double MAX_INTERACTION_DISTANCE_SQUARED = 8.0D * 8.0D;
	/** How close a player has to be to the core before it speaks to them. */
	private static final double REMINDER_RADIUS_SQUARED = 14.0D * 14.0D;
	private static final int REMINDER_SCAN_INTERVAL_TICKS = 20;
	private static final int REMINDER_COOLDOWN_TICKS = 400;
	private static final Map<UUID, Long> REMINDED_AT = new java.util.concurrent.ConcurrentHashMap<>();
	private static AltarOpenHandler altarOpenHandler = (player, position, status) -> false;

	private WorldInterfaceRitualService() {
	}

	public static void initialize() {
		ServerPlayerEvents.JOIN.register(WorldInterfaceRitualService::reconcilePlayer);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> reconcilePlayer(newPlayer));
		ServerTickEvents.END_SERVER_TICK.register(WorldInterfaceRitualService::tick);
	}

	/**
	 * The encounter orchestrator installs its screen-opening callback here. This
	 * keeps the block entity a proxy and avoids giving it ending authority.
	 */
	public static void registerAltarOpenHandler(AltarOpenHandler handler) {
		altarOpenHandler = Objects.requireNonNull(handler, "handler");
	}

	public static boolean openAltar(ServerPlayer player, BlockPos corePosition) {
		return openAltar(player, corePosition, WorldInterfaceProtocol.AltarStatus.READY);
	}

	/**
	 * Opens the altar screen with the answer to whatever the player just did already on it.
	 *
	 * <p>The status matters because the screen is now opened by two different things. Right-clicking
	 * the core with an empty hand is a question, and {@code READY} is the answer to it. Right-clicking
	 * it with a terminal is an act, and the screen that comes up afterwards has to be showing what
	 * became of it rather than the neutral line an onlooker gets.</p>
	 */
	private static boolean openAltar(ServerPlayer player, BlockPos corePosition,
			WorldInterfaceProtocol.AltarStatus status) {
		Snapshot snapshot = WorldInterfaceState.snapshot(player.level().getServer());
		if (!actionContextValid(player, snapshot, snapshot.encounterId().orElse(null), snapshot.revision(),
				corePosition, false)) return false;
		return altarOpenHandler.open(player, corePosition, status);
	}

	/**
	 * The held-terminal path, driven by right-clicking the core rather than by a screen button.
	 *
	 * <p>Reads the encounter's own revision instead of taking one from the caller: a block
	 * interaction has no screen behind it holding a revision to check against, and the deposit
	 * below still rejects anything that has moved on underneath it.</p>
	 *
	 * @return whether the interaction was consumed, so a terminal that is simply not part of this
	 *         ritual falls through to whatever else right-clicking would have done
	 */
	public static boolean insertHeldTerminal(ServerPlayer player, BlockPos corePosition) {
		MinecraftServer server = player.level().getServer();
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!actionContextValid(player, snapshot, snapshot.encounterId().orElse(null), snapshot.revision(),
				corePosition, false)) return false;
		RitualResult result = deposit(player, snapshot.encounterId().orElse(null), snapshot.revision());
		WorldInterfaceProtocol.AltarStatus status = WorldInterfaceProtocol.AltarStatus.fromReason(result.reason());
		// Without a screen open there is nothing to show the outcome, so the result is spoken on the
		// notice stack - a refusal has to say why, or right-clicking an altar that rejects you is
		// indistinguishable from right-clicking a block that does nothing.
		Component message = Component.translatable(status.translationKey());
		if (result.applied()) TerminalNoticeService.encounter(player, message);
		else TerminalNoticeService.denied(player, message);
		if (result.applied()) {
			// Insertion is the one moment the player is guaranteed to be standing at the core, and
			// it is also the moment the encounter stops moving on its own: nothing else happens until
			// somebody presses summon. Depositing used to leave them facing a block that had visibly
			// taken their terminal and then done nothing, with the button that starts the fight behind
			// a second right-click nobody had been told to make. So the screen carrying that button
			// comes up by itself, with the answer to what they just did already on it.
			openAltar(player, corePosition, status);
			if (!result.idempotent()) announceInsertion(server, player, result.snapshot());
		}
		return true;
	}

	/**
	 * Tells the rest of the End that somebody just handed a terminal over.
	 *
	 * <p>The altar screen is the only place the roster exists, and it is a screen one player has open
	 * while the others are standing outside it - so without this the fourth person to walk up has no
	 * way of knowing three terminals are already in, and nobody watching has any reason to expect a
	 * summon to be pressed. Both halves are in the line: how many the altar holds, and that it is
	 * waiting on a person rather than on a timer.</p>
	 *
	 * <p>Sent to everyone in the End rather than to the depositors, spectators included: a player who
	 * died on the way to the altar is watching this decision being made about them.</p>
	 */
	private static void announceInsertion(MinecraftServer server, ServerPlayer actor, Snapshot snapshot) {
		long held = snapshot.terminalTransactions().values().stream()
				.filter(value -> value.state() == TerminalTransactionState.REMOVED
						|| value.state() == TerminalTransactionState.COMMITTED)
				.count();
		Component message = Component.translatable(
				"message.thefourthfrequency.world_interface.altar_inserted_by",
				actor.getGameProfile().name(), held);
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(actor.getUUID()) || other.level().dimension() != Level.END) continue;
			TerminalNoticeService.encounter(other, message);
		}
	}

	/**
	 * Escrows one player's terminal, adding them to the roster and arming the window if needed.
	 *
	 * <p>Depositing no longer starts the fight. Under the old rules the roster was every online
	 * non-spectator, so "everyone on the roster has deposited" was a condition the last depositor
	 * satisfied on behalf of the whole server, and the summon happened underneath them. Now the
	 * roster is the depositors themselves, that condition is true from the first terminal onwards
	 * and means nothing - so starting is its own act, and it is {@link #summon}.</p>
	 */
	public static RitualResult deposit(ServerPlayer player, UUID encounterId, long expectedRevision) {
		MinecraftServer server = player.level().getServer();
		Snapshot before = WorldInterfaceState.snapshot(server);
		if (!actionContextValid(player, before, encounterId, expectedRevision, before.altarCenter(), false)) {
			return reject(before, "invalid_context");
		}
		TerminalTransaction existing = before.terminalTransactions().get(player.getUUID());
		if (existing != null && existing.state() == TerminalTransactionState.COMMITTED) {
			return RitualResult.idempotent(before, "already_deposited");
		}
		if (before.stage() != WorldInterfaceStage.WAITING_TERMINALS || before.sacrificeCommitted()) {
			return reject(before, "ritual_not_waiting");
		}
		if (existing != null && existing.state() == TerminalTransactionState.RETURN_PENDING) {
			return reject(before, "rollback_pending");
		}
		if (existing != null && existing.state() == TerminalTransactionState.REMOVED) {
			return RitualResult.idempotent(before, "already_deposited");
		}
		// The ceiling is now a queue-length refusal aimed at one person, rather than the old
		// whole-server headcount that refused everybody. A ninth player at the altar is turned away;
		// the eight already holding places keep them.
		if (!before.frozenRoster().contains(player.getUUID())
				&& before.frozenRoster().size() >= WorldInterfaceState.MAX_ROSTER_SIZE) {
			return reject(before, "roster_full");
		}
		// A client claiming to be ahead of the server is nonsense; a client behind it is just someone
		// else having deposited a moment ago, which is now an ordinary thing rather than grounds to
		// invalidate the ritual.
		if (expectedRevision > before.revision()) return reject(before, "revision_mismatch");

		FrequencyWorldData data = FrequencyWorldData.get(server);
		LocatedTerminal located = findValidBoundTerminal(player, data);
		if (located == null) return reject(before, "valid_bound_terminal_missing");
		String terminalId = TerminalData.terminalId(located.stack());
		int generation = TerminalData.copyGeneration(located.stack());
		if (existing != null && (!existing.terminalId().equals(terminalId)
				|| existing.generation() != generation)) return reject(before, "terminal_mismatch");

		// The first terminal in starts the clock; every later one joins the window already running,
		// so a straggler cannot extend it and nobody can hold the altar open by shuffling in and out.
		long deadline = before.ritualWindowRunning() ? before.ritualDeadlineTick()
				: gameTime(server) + WorldInterfacePolicy.RITUAL_WINDOW_TICKS;

		Snapshot prepared = before;
		if (existing == null) {
			TerminalTransaction transaction = new TerminalTransaction(player.getUUID(), terminalId, generation,
					TerminalTransactionState.PREPARED, Math.max(0L, gameTime(server)),
					TerminalData.copyTag(located.stack()));
			MutationResult journal = WorldInterfaceState.mutate(server, encounterId, before.revision(), state -> {
				if (state.stage() != WorldInterfaceStage.WAITING_TERMINALS) {
					throw new IllegalStateException("ritual_not_waiting");
				}
				state.joinRoster(player.getUUID());
				state.putTerminalTransaction(transaction);
				state.setRitualDeadline(deadline);
				state.setGateState(WorldInterfaceGatewayState.PURPLE);
			});
			if (!journal.applied()) return reject(journal.snapshot(), journal.reason());
			prepared = journal.snapshot();
		}

		// Re-resolve the slot after the durable PREPARED write. No client slot is trusted.
		located = findMatchingTerminal(player, data, terminalId, generation);
		if (located == null) {
			return markReturnPending(server, prepared, player.getUUID(), "terminal_disappeared");
		}
		removeMatchingTerminals(player, data, terminalId, generation);
		TerminalLifecycleService.clearTransientRecovery(player.getUUID());

		MutationResult removed = WorldInterfaceState.mutate(server, encounterId, prepared.revision(), state -> {
			TerminalTransaction transaction = state.terminalTransactions().get(player.getUUID());
			if (transaction == null || transaction.state() != TerminalTransactionState.PREPARED) {
				throw new IllegalStateException("prepared_transaction_missing");
			}
			state.putTerminalTransaction(transaction.withState(TerminalTransactionState.REMOVED));
		});
		if (!removed.applied()) {
			// PREPARED remains a durable return entitlement if the second write failed.
			return reject(removed.snapshot(), removed.reason());
		}
		return RitualResult.applied(removed.snapshot(), "terminal_deposited");
	}

	/**
	 * Starts the encounter, on purpose, by somebody who is in it.
	 *
	 * <p>Requiring a deposit of whoever presses it is the whole point: the summon is the moment the
	 * party stops being able to change its mind, and the person taking that decision has to be one
	 * of the people it is taken about. Anyone standing at the altar without having given anything up
	 * can watch, and cannot start.</p>
	 */
	public static RitualResult summon(ServerPlayer player, UUID encounterId, long expectedRevision) {
		MinecraftServer server = player.level().getServer();
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!actionContextValid(player, snapshot, encounterId, expectedRevision, snapshot.altarCenter(), false)) {
			return reject(snapshot, "invalid_context");
		}
		if (snapshot.sacrificeCommitted()) return RitualResult.idempotent(snapshot, "already_committed");
		if (snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS) {
			return reject(snapshot, "ritual_not_waiting");
		}
		TerminalTransaction own = snapshot.terminalTransactions().get(player.getUUID());
		if (own == null || own.state() != TerminalTransactionState.REMOVED) {
			return reject(snapshot, "summon_requires_deposit");
		}
		if (!readyToSummon(snapshot)) return reject(snapshot, "summon_not_ready");
		return commitReady(server, snapshot);
	}

	public static RitualResult withdraw(ServerPlayer player, UUID encounterId, long expectedRevision) {
		MinecraftServer server = player.level().getServer();
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!actionContextValid(player, snapshot, encounterId, expectedRevision, snapshot.altarCenter(), false)) {
			return reject(snapshot, "invalid_context");
		}
		TerminalTransaction transaction = snapshot.terminalTransactions().get(player.getUUID());
		if (transaction == null) return RitualResult.idempotent(snapshot, "nothing_deposited");
		if (snapshot.revision() != expectedRevision) return reject(snapshot, "revision_mismatch");
		if (snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS || snapshot.sacrificeCommitted()) {
			return reject(snapshot, "ritual_not_waiting");
		}
		if (transaction.state() == TerminalTransactionState.COMMITTED) return reject(snapshot, "already_committed");
		RitualResult pending = markReturnPending(server, snapshot, player.getUUID(), "withdrawn");
		processReturns(server);
		return pending.withSnapshot(WorldInterfaceState.snapshot(server));
	}

	public static RitualResult cancel(ServerPlayer player, UUID encounterId, long expectedRevision) {
		MinecraftServer server = player.level().getServer();
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!actionContextValid(player, snapshot, encounterId, expectedRevision, snapshot.altarCenter(), false)) {
			return reject(snapshot, "invalid_context");
		}
		if (snapshot.frozenRoster().isEmpty()) return RitualResult.idempotent(snapshot, "ritual_already_empty");
		if (!snapshot.terminalTransactions().isEmpty() && snapshot.terminalTransactions().values().stream()
				.allMatch(value -> value.state() == TerminalTransactionState.RETURN_PENDING)) {
			return RitualResult.idempotent(snapshot, "rollback_already_pending");
		}
		if (snapshot.revision() != expectedRevision) return reject(snapshot, "revision_mismatch");
		if (snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS || snapshot.sacrificeCommitted()) {
			return reject(snapshot, "ritual_not_waiting");
		}
		if (!snapshot.frozenRoster().contains(player.getUUID())) return reject(snapshot, "not_in_frozen_roster");
		return rollback(server, snapshot, "cancelled");
	}

	/** True while the normal terminal lifecycle must not create another copy. */
	public static boolean terminalRecoverySuppressed(MinecraftServer server, UUID playerId) {
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()) return false;
		TerminalTransaction transaction = snapshot.terminalTransactions().get(playerId);
		return transaction != null || snapshot.sacrificeCommitted() && snapshot.frozenRoster().contains(playerId);
	}

	private static void tick(MinecraftServer server) {
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()) return;
		if (snapshot.sacrificeCommitted()) {
			if (server.getTickCount() % 20 == 0) reconcileCommittedProjection(server, snapshot);
			return;
		}
		if (snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS) return;
		remindNearbyPlayers(server, snapshot);
		if (snapshot.terminalTransactions().values().stream()
				.anyMatch(value -> value.state() == TerminalTransactionState.RETURN_PENDING)) {
			processReturns(server);
			return;
		}
		if (snapshot.terminalTransactions().values().stream()
				.anyMatch(value -> value.state() == TerminalTransactionState.PREPARED)) {
			// PREPARED is an uncertain crash boundary. Abort it into the recoverable path.
			rollback(server, snapshot, "prepared_recovery");
			return;
		}
		if (snapshot.frozenRoster().isEmpty()) return;
		// A roster with no window is a shape these rules cannot produce. It is what a format-1 save
		// caught mid-ritual decodes to, and it is treated the same way as every other uncertain
		// boundary here: give the terminals back and let the party start again knowing where it is.
		if (!snapshot.ritualWindowRunning()) {
			rollback(server, snapshot, "ritual_window_missing");
			return;
		}
		if (gameTime(server) >= snapshot.ritualDeadlineTick()) {
			rollback(server, snapshot, "ritual_window_expired");
		}
	}

	/** Whether every escrowed terminal is durably removed, which is what {@link #summon} needs. */
	private static boolean readyToSummon(Snapshot snapshot) {
		return !snapshot.frozenRoster().isEmpty()
				&& snapshot.frozenRoster().size() <= WorldInterfaceState.MAX_ROSTER_SIZE
				&& snapshot.terminalTransactions().size() == snapshot.frozenRoster().size()
				&& snapshot.terminalTransactions().values().stream()
				.allMatch(value -> value.state() == TerminalTransactionState.REMOVED);
	}

	/**
	 * The one clock the window is measured against.
	 *
	 * <p>The Overworld's, deliberately: {@code ServerLevelData} holds a single game time shared by
	 * every dimension, so this is the same number the End would give, and reading it from a level
	 * that is guaranteed to exist means the deadline does not depend on the End being loaded.</p>
	 */
	private static long gameTime(MinecraftServer server) {
		return server.overworld().getGameTime();
	}

	/** Rolls a fully removed journal forward to its one atomic sacrifice commit. */
	private static RitualResult commitReady(MinecraftServer server, Snapshot initial) {
		Snapshot snapshot = initial;
		for (int attempt = 0; attempt < 5; attempt++) {
			if (snapshot.sacrificeCommitted()) {
				return RitualResult.idempotent(snapshot, "already_deposited");
			}
			if (snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS || !readyToSummon(snapshot)) {
				return reject(snapshot, "sacrifice_not_ready");
			}
			// readyToCommit() above already proved the roster is non-empty, and the deposit paths
			// cap it at MAX_ROSTER_SIZE, so the policy's 1..8 precondition holds here.
			double maximumHealth = WorldInterfacePolicy.maxHealth(snapshot.frozenRoster().size());
			MutationResult committed = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(),
					state -> state.commitSacrifice(maximumHealth));
			if (committed.applied()) {
				reconcileCommittedProjection(server, committed.snapshot());
				return RitualResult.applied(committed.snapshot(), "sacrifice_committed");
			}
			if (!"revision_mismatch".equals(committed.reason())) {
				return reject(committed.snapshot(), committed.reason());
			}
			snapshot = committed.snapshot();
		}
		return reject(snapshot, "revision_mismatch");
	}

	private static RitualResult rollback(MinecraftServer server, Snapshot snapshot, String reason) {
		if (!snapshot.valid() || !snapshot.present() || snapshot.encounterId().isEmpty()) return reject(snapshot, reason);
		if (snapshot.terminalTransactions().isEmpty()) {
			MutationResult cleared = WorldInterfaceState.mutate(server, snapshot.encounterId().orElseThrow(),
					snapshot.revision(), state -> {
						state.clearFrozenRoster();
						state.setGateState(WorldInterfaceGatewayState.DORMANT);
					});
			return cleared.applied() ? RitualResult.applied(cleared.snapshot(), reason)
					: reject(cleared.snapshot(), cleared.reason());
		}
		MutationResult pending = WorldInterfaceState.mutate(server, snapshot.encounterId().orElseThrow(),
				snapshot.revision(), state -> {
					for (Map.Entry<UUID, TerminalTransaction> entry : state.terminalTransactions().entrySet()) {
						TerminalTransaction value = entry.getValue();
						if (value.state() != TerminalTransactionState.COMMITTED) {
							state.putTerminalTransaction(value.withState(TerminalTransactionState.RETURN_PENDING));
						}
					}
					// Disarmed with the first write of the rollback rather than when the last terminal
					// finally lands back in an inventory: an expired deadline that stays armed while the
					// returns drain would re-fire this whole path on every tick in between.
					state.setRitualDeadline(-1L);
					state.setGateState(WorldInterfaceGatewayState.DORMANT);
				});
		if (!pending.applied()) return reject(pending.snapshot(), pending.reason());
		processReturns(server);
		return RitualResult.applied(WorldInterfaceState.snapshot(server), reason);
	}

	private static RitualResult markReturnPending(MinecraftServer server, Snapshot snapshot, UUID playerId,
			String reason) {
		MutationResult pending = WorldInterfaceState.mutate(server, snapshot.encounterId().orElseThrow(),
				snapshot.revision(), state -> {
					TerminalTransaction value = state.terminalTransactions().get(playerId);
					if (value == null) throw new IllegalStateException("transaction_missing");
					state.putTerminalTransaction(value.withState(TerminalTransactionState.RETURN_PENDING));
				});
		return pending.applied() ? RitualResult.applied(pending.snapshot(), reason)
				: reject(pending.snapshot(), pending.reason());
	}

	/**
	 * Tells anyone standing at a waiting altar what it wants from them.
	 *
	 * <p>Insertion is a held-item interaction at a block, which is a thing a player has to be told
	 * about once: an altar that is silently waiting for a specific item in a specific hand is
	 * indistinguishable from scenery. The reminder is addressed to the individual rather than
	 * broadcast, names the actual next step, and changes with what they are holding - someone
	 * already gripping their terminal is told to right-click, not told to go find it.</p>
	 *
	 * <p>Rate-limited per player rather than per tick, and never sent to someone who has already
	 * inserted: a prompt that repeats at something already done is noise, and noise here trains
	 * players to ignore the one channel the encounter speaks through.</p>
	 */
	private static void remindNearbyPlayers(MinecraftServer server, Snapshot snapshot) {
		if (server.getTickCount() % REMINDER_SCAN_INTERVAL_TICKS != 0) return;
		BlockPos core = AltarShape.corePosition(snapshot.altarCenter());
		long now = server.getTickCount();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || player.level().dimension() != Level.END) continue;
			if (player.distanceToSqr(core.getCenter()) > REMINDER_RADIUS_SQUARED) continue;
			TerminalTransaction existing = snapshot.terminalTransactions().get(player.getUUID());
			if (existing != null && existing.state() != TerminalTransactionState.RETURN_PENDING) continue;
			Long last = REMINDED_AT.get(player.getUUID());
			if (last != null && now - last < REMINDER_COOLDOWN_TICKS) continue;
			REMINDED_AT.put(player.getUUID(), now);
			boolean holding = findValidBoundTerminal(player, data) != null;
			TerminalNoticeService.encounter(player, Component.translatable(holding
					? "message.thefourthfrequency.world_interface.altar_insert_now"
					: "message.thefourthfrequency.world_interface.altar_take_terminal"));
		}
	}

	private static void processReturns(MinecraftServer server) {
		while (true) {
			Snapshot snapshot = WorldInterfaceState.snapshot(server);
			if (!snapshot.valid() || !snapshot.present() || snapshot.sacrificeCommitted()) return;
			TerminalTransaction deliverable = snapshot.terminalTransactions().values().stream()
					.filter(value -> value.state() == TerminalTransactionState.RETURN_PENDING)
					.filter(value -> server.getPlayerList().getPlayer(value.playerId()) != null)
					.findFirst().orElse(null);
			if (deliverable == null) return;
			ServerPlayer player = server.getPlayerList().getPlayer(deliverable.playerId());
			FrequencyWorldData data = FrequencyWorldData.get(server);
			boolean alreadyCarried = findMatchingTerminal(player, data, deliverable.terminalId(),
					deliverable.generation()) != null;
			if (!alreadyCarried) {
				ItemStack returned = TerminalData.stackFromRecord(deliverable.terminalSnapshot());
				if (!TerminalLifecycleService.insertAuthoritativeReturn(player, returned)) return;
			}
			MutationResult consumed = WorldInterfaceState.mutate(server, snapshot.encounterId().orElseThrow(),
					snapshot.revision(), state -> {
						state.removeTerminalTransaction(deliverable.playerId());
						if (state.terminalTransactions().isEmpty()) {
							state.clearFrozenRoster();
							state.setGateState(WorldInterfaceGatewayState.DORMANT);
						}
					});
			if (!consumed.applied()) return;
		}
	}

	private static void reconcilePlayer(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Snapshot snapshot = WorldInterfaceState.snapshot(server);
		TerminalTransaction transaction = snapshot.terminalTransactions().get(player.getUUID());
		if (transaction != null && transaction.state() == TerminalTransactionState.RETURN_PENDING) {
			processReturns(server);
		}
		if (snapshot.sacrificeCommitted()) reconcileCommittedProjection(server, snapshot);
	}

	private static void reconcileCommittedProjection(MinecraftServer server, Snapshot snapshot) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (UUID playerId : snapshot.frozenRoster()) {
			data.terminalRecord(playerId).ifPresent(record -> {
				if (record.getBooleanOr(TerminalData.TERMINAL_CAPTURED, false)) return;
				data.updateTerminalRecord(playerId, value -> {
					value.putBoolean(TerminalData.TERMINAL_CAPTURED, true);
					value.putLong(TerminalData.TERMINAL_CAPTURED_TICK,
							Math.max(0L, server.overworld().getGameTime()));
				});
			});
			TerminalLifecycleService.clearTransientRecovery(playerId);
		}
	}

	private static boolean actionContextValid(ServerPlayer player, Snapshot snapshot, UUID encounterId,
			long expectedRevision, BlockPos corePosition, boolean exactRevision) {
		if (!snapshot.valid() || !snapshot.present() || encounterId == null
				|| snapshot.encounterId().filter(encounterId::equals).isEmpty()) return false;
		if (exactRevision && snapshot.revision() != expectedRevision) return false;
		if (player.level().dimension() != Level.END || isSpectator(player)) return false;
		if (!snapshot.altarCenter().equals(corePosition)
				|| player.distanceToSqr(corePosition.getX() + 0.5D, corePosition.getY() + 0.5D,
				corePosition.getZ() + 0.5D) > MAX_INTERACTION_DISTANCE_SQUARED) return false;
		return player.level().getBlockState(corePosition).is(ModBlocks.RESONANCE_CORE);
	}

	/**
	 * Who is standing at the altar right now, for the screen's benefit only.
	 *
	 * <p>Nothing authoritative reads this. It replaced {@code eligiblePlayers}, which answered "every
	 * non-spectator on the server" and was the source of every multiplayer failure the roster had:
	 * eligibility is now decided one interaction at a time by {@link #actionContextValid} plus a
	 * terminal actually being in hand, which is a question about a person at a place rather than
	 * about a headcount. What remains is the list an empty altar shows so it does not look broken.</p>
	 */
	public static List<UUID> altarCandidates(MinecraftServer server, Snapshot snapshot) {
		BlockPos core = AltarShape.corePosition(snapshot.altarCenter());
		FrequencyWorldData data = FrequencyWorldData.get(server);
		return server.getPlayerList().getPlayers().stream()
				.filter(player -> !isSpectator(player) && player.level().dimension() == Level.END)
				.filter(player -> player.distanceToSqr(core.getCenter()) <= REMINDER_RADIUS_SQUARED)
				.filter(player -> data.terminalRecord(player.getUUID())
						.map(record -> record.getBooleanOr(TerminalData.BOUND, false)).orElse(false))
				.map(ServerPlayer::getUUID)
				.sorted(Comparator.comparing(UUID::toString))
				.limit(WorldInterfaceState.MAX_ROSTER_SIZE)
				.toList();
	}

	private static boolean isSpectator(ServerPlayer player) {
		return player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
	}

	/**
	 * The terminal the player is actually holding, and only that one.
	 *
	 * <p>This used to sweep the whole inventory, which meant the sacrifice was a button that
	 * quietly reached into your bag and took the thing the entire mod is about. Requiring it in
	 * hand makes the surrender an act rather than a confirmation: you take out the terminal you
	 * have been carrying since the first night, and you put it into the altar.</p>
	 */
	private static LocatedTerminal findValidBoundTerminal(ServerPlayer player, FrequencyWorldData data) {
		if (!data.terminalRecord(player.getUUID())
				.map(record -> record.getBooleanOr(TerminalData.BOUND, false)).orElse(false)) return null;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (!data.isValidTerminal(stack, player.getUUID()) || !TerminalData.isBound(stack)) continue;
			int slot = hand == InteractionHand.MAIN_HAND
					? player.getInventory().getSelectedSlot()
					: player.getInventory().getContainerSize() - 1;
			// The offhand is addressed by its own index rather than by a scan, so the removal below
			// targets the exact stack that was held.
			if (hand == InteractionHand.OFF_HAND) {
				for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
					if (player.getInventory().getItem(index) == stack) {
						slot = index;
						break;
					}
				}
			}
			return new LocatedTerminal(slot, stack);
		}
		return null;
	}

	private static LocatedTerminal findMatchingTerminal(ServerPlayer player, FrequencyWorldData data,
			String terminalId, int generation) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (data.isValidTerminal(stack, player.getUUID())
					&& terminalId.equals(TerminalData.terminalId(stack))
					&& generation == TerminalData.copyGeneration(stack)) return new LocatedTerminal(slot, stack);
		}
		return null;
	}

	private static void removeMatchingTerminals(ServerPlayer player, FrequencyWorldData data,
			String terminalId, int generation) {
		boolean changed = false;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (data.isValidTerminal(stack, player.getUUID())
					&& terminalId.equals(TerminalData.terminalId(stack))
					&& generation == TerminalData.copyGeneration(stack)) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
				changed = true;
			}
		}
		if (changed) player.getInventory().setChanged();
	}

	private static RitualResult reject(Snapshot snapshot, String reason) {
		return new RitualResult(false, false, reason, snapshot);
	}

	@FunctionalInterface
	public interface AltarOpenHandler {
		boolean open(ServerPlayer player, BlockPos corePosition, WorldInterfaceProtocol.AltarStatus status);
	}

	public record RitualResult(boolean applied, boolean idempotent, String reason, Snapshot snapshot) {
		private static RitualResult applied(Snapshot snapshot, String reason) {
			return new RitualResult(true, false, reason, snapshot);
		}

		private static RitualResult idempotent(Snapshot snapshot, String reason) {
			return new RitualResult(true, true, reason, snapshot);
		}

		private RitualResult withSnapshot(Snapshot value) {
			return new RitualResult(applied, idempotent, reason, value);
		}
	}

	private record LocatedTerminal(int slot, ItemStack stack) {
	}
}
