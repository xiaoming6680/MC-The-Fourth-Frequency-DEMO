package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.terminal.AnomalyIntensity;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Converts personal mainline transitions into one pending, tutorial-gated pursuit. */
public final class PursuitDirector {
	private static final int CHECK_TICKS = 20;
	private static boolean initialized;

	private PursuitDirector() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(PursuitDirector::tick);
	}

	public static DebugStartResult debugStart(ServerPlayer player, int requestedForm) {
		if (requestedForm < 1 || requestedForm > PursuitProgressPolicy.FORM_COUNT) return DebugStartResult.INVALID_FORM;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return DebugStartResult.NO_TERMINAL_RECORD;
		if (record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
				|| PursuitDimensions.isMirror(player.level())) return DebugStartResult.ALREADY_ACTIVE;
		var family = PursuitDimensions.sourceFamily(player.level().dimension()).orElse(null);
		if (family == null || family == PursuitDimensions.Family.END) {
			return DebugStartResult.UNSUPPORTED_DIMENSION;
		}
		if (!PursuitSafetyPolicy.canBeginForDebug(player, record)) return DebugStartResult.UNSAFE;
		var lease = PursuitSlotManager.acquire(player.level().getServer(), player.getUUID(), family).orElse(null);
		if (lease == null) return DebugStartResult.NO_SLOT;
		if (!PursuitSessionService.enterEmptyMirror(player, lease, requestedForm, true)) {
			PursuitSlotManager.release(player.getUUID());
			return DebugStartResult.TRANSFER_REJECTED;
		}
		return DebugStartResult.STARTED;
	}

	/**
	 * Advances everyone's permission, then starts at most one of them.
	 *
	 * <p>Selection is separated from eligibility because there is only one chase server-wide. Left as
	 * a straight loop the slot went to whoever the player list happened to name first, which is
	 * stable - so on a busy server the same person could win it over and over while somebody else
	 * waited indefinitely, and neither of them would ever be told that was what was happening. The
	 * queue is now ordered by how long each player has been owed one, and everyone who loses it is
	 * told once.</p>
	 */
	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_TICKS != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		List<ServerPlayer> ready = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (update(player, data)) ready.add(player);
		}
		if (ready.isEmpty()) return;
		ready.sort(Comparator.comparingLong(player -> data.terminalRecord(player.getUUID())
				.map(record -> record.getLongOr(TerminalData.PURSUIT_PENDING_SINCE, 0L)).orElse(0L)));
		ServerPlayer chosen = ready.getFirst();
		boolean started = begin(chosen, data);
		for (ServerPlayer waiting : ready) {
			if (waiting != chosen || !started) noteQueued(waiting, data);
		}
	}

	/** Takes the one free slot and enters the mirror, or gives the slot straight back. */
	private static boolean begin(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		var family = PursuitDimensions.sourceFamily(player.level().dimension()).orElse(null);
		if (family == null) return false;
		var lease = PursuitSlotManager.acquire(player.level().getServer(), player.getUUID(), family).orElse(null);
		if (lease == null) return false;
		int form = PursuitProgressPolicy.actualForm(record.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, 0));
		if (!PursuitSessionService.enterEmptyMirror(player, lease, form)) {
			PursuitSlotManager.release(player.getUUID());
			return false;
		}
		long now = player.level().getGameTime();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.PURSUIT_QUEUE_NOTED, false);
			tag.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK,
					now + AnomalyIntensity.DIMENSION_GRACE_TICKS + 5L * 60L * 20L);
		});
		return true;
	}

	/**
	 * Says once, on the one channel the story uses, that the wait is the queue rather than a fault.
	 *
	 * <p>A player who has met every condition and is simply behind somebody else used to see nothing
	 * at all - the terminal held "pursuit pending" indefinitely and never distinguished that from
	 * being broken. Written to RECORDS rather than sent as a notice because it is a fact about the
	 * world, not an instruction, and it is latched so a long queue is one line instead of one a
	 * second. The latch clears when the chase finally starts.
	 */
	private static void noteQueued(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || record.getBooleanOr(TerminalData.PURSUIT_QUEUE_NOTED, false)) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.PURSUIT_QUEUE_NOTED, true);
			TerminalSignalLog.append(tag, SignalBand.UNKNOWN, "pursuit_queued",
					player.level().getGameTime(), player.level().getDayTime(),
					player.level().dimension().identifier().toString(),
					player.blockPosition().asLong(), 0, 1, true);
		});
		TerminalRuntimeService.synchronizeAttentionProjection(player, data);
		TerminalRuntimeService.refresh(player);
	}

	/** @return whether this player has cleared every gate and is only waiting for the slot. */
	private static boolean update(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !record.getBooleanOr(TerminalData.BOUND, false)
				|| record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) return false;
		// Past the finale there is no more mainline to escalate, so a pursuit left pending by an
		// earlier story gate is retired rather than kept waiting for the player to walk back into a
		// dimension it can start in. Without this the exit portal delivered them into one.
		if (FinaleRuntimePolicy.concluded(data)) {
			if (record.getBooleanOr(TerminalData.PURSUIT_PENDING, false)) {
				data.updateTerminalRecord(player.getUUID(), tag -> {
					tag.putBoolean(TerminalData.PURSUIT_PENDING, false);
					tag.putBoolean(TerminalData.PURSUIT_QUEUE_NOTED, false);
				});
				TerminalRuntimeService.synchronizeAttentionProjection(player, data);
			}
			return false;
		}
		long now = player.level().getGameTime();
		boolean early = PursuitProgressPolicy.earlyFormEligible(true,
				record.getLongOr(TerminalData.ANOMALY_SEEN_MASK, 0L) == 0L ? 0 : 1,
				record.getIntOr(TerminalData.PURSUIT_ACTIVITY_PROOF_MASK, 0),
				record.getLongOr(TerminalData.PURSUIT_EFFECTIVE_ACTIVITY_TICKS, 0L));
		int previousAllowed = record.getIntOr(TerminalData.PURSUIT_ALLOWED_FORM, 0);
		int resolved = record.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, 0);
		int allowed = PursuitProgressPolicy.allowedForm(
				record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0),
				record.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0), early);
		boolean pending = PursuitProgressPolicy.pendingAfterAllowedFormUpdate(
				record.getBooleanOr(TerminalData.PURSUIT_PENDING, false),
				previousAllowed, allowed, resolved);
		boolean wasPending = record.getBooleanOr(TerminalData.PURSUIT_PENDING, false);
		if (allowed != previousAllowed || pending != wasPending) {
			data.updateTerminalRecord(player.getUUID(), tag -> {
				tag.putInt(TerminalData.PURSUIT_ALLOWED_FORM, allowed);
				tag.putBoolean(TerminalData.PURSUIT_PENDING, pending);
				// Stamped on the transition into pending, not on every pass, or the wait would keep
				// resetting and the longest-waiting player would never actually be the oldest stamp.
				if (pending && !wasPending) tag.putLong(TerminalData.PURSUIT_PENDING_SINCE, now);
				if (!pending) tag.putBoolean(TerminalData.PURSUIT_QUEUE_NOTED, false);
			});
			TerminalRuntimeService.synchronizeAttentionProjection(player, data);
		}
		if (!pending || PursuitProgressPolicy.complete(resolved)) return false;
		// No demonstration gate: a form may arrive having never been previewed. The demo mask is still
		// written by AnomalyHistory - it is what the archive and the files read - it just no longer
		// decides whether the pursuit is allowed to begin.
		if (!PursuitProgressPolicy.canStart(pending, allowed, resolved, now,
				record.getLongOr(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK, 0L))) return false;
		if (!PursuitSafetyPolicy.canBegin(player, record, data)) return false;
		return PursuitDimensions.sourceFamily(player.level().dimension()).isPresent();
	}

	public enum DebugStartResult {
		STARTED,
		INVALID_FORM,
		NO_TERMINAL_RECORD,
		ALREADY_ACTIVE,
		UNSUPPORTED_DIMENSION,
		UNSAFE,
		NO_SLOT,
		TRANSFER_REJECTED
	}

}
