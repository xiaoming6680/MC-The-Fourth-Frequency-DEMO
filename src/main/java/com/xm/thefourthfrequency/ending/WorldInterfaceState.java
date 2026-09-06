package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Versioned, server-authoritative persistence for the World Interface ending.
 *
 * <p>This state intentionally lives beside, rather than inside, the retired
 * {@code ending} v3 payload. Invalid or unknown data is rejected as a whole;
 * no partial migration is attempted.</p>
 */
public final class WorldInterfaceState {
	public static final String ROOT_KEY = "world_interface";
	/**
	 * Bumped to 2 for the ritual window.
	 *
	 * <p>Version 1 froze the roster as "every online non-spectator at the moment of the first
	 * deposit" and had nothing to time. Version 2 grows the roster one depositor at a time and runs
	 * a deadline against it, which needs one persisted field ({@code ritual_deadline_tick}).
	 *
	 * <p>Reading version 1 is supported and the missing field decodes as "no window running". That
	 * leaves a v1 save that was mid-ritual in a shape v2 cannot otherwise produce - a non-empty
	 * roster with no deadline - which {@code WorldInterfaceRitualService} normalises by rolling the
	 * ritual back and returning the terminals, exactly as it already does for any other uncertain
	 * boundary. Nothing is lost: nobody had committed.</p>
	 */
	public static final int FORMAT_VERSION = 2;
	public static final int GATE_COUNT = 20;
	public static final int ANCHOR_COUNT = 10;
	public static final int MAX_ROSTER_SIZE = 8;
	public static final int MAX_ATTACK_TARGETS = 8;
	public static final int MAX_RECOVERY_ENTRIES = 64;
	public static final int MAX_TERRAIN_EDITS = 8_192;

	private WorldInterfaceState() {
	}

	// A single boss-fight tick calls snapshot() dozens of times across EndBossEncounterService,
	// WorldInterfaceAttackService and WorldInterfaceRitualService, and every one of those calls
	// used to pay for both a full CompoundTag deep copy (FrequencyWorldData.narrativeState()) and
	// a full decode() of ~10 nested collections. The cache below is keyed on
	// FrequencyWorldData.narrativeStateRevision(), a counter that instance bumps on every single
	// mutation regardless of who performs it (see its field doc) - notably including GameTest
	// fixtures that reset world_interface state directly via updateNarrativeState(root ->
	// root.remove(...)) rather than going through write()/clearInvalid() below. Keying only on
	// the FrequencyWorldData instance (without the revision) was tried first and is wrong: it
	// missed exactly those direct writers and served stale COMPLETE-stage snapshots across
	// GameTest method boundaries that share one server, breaking WorldInterfaceGameTests wholesale.
	// The pair is published through one volatile reference so a stale read is possible under
	// uncoordinated concurrent access but a torn (mismatched owner/revision/snapshot) read is not;
	// callers already treat a stale revision as an ordinary CAS race and retry through
	// mutate()/expectedRevision.
	private static volatile CacheEntry cache;

	private record CacheEntry(FrequencyWorldData owner, long dataRevision, Snapshot snapshot) {
	}

	public static Snapshot snapshot(MinecraftServer server) {
		return snapshot(FrequencyWorldData.get(server));
	}

	public static Snapshot get(MinecraftServer server) {
		return snapshot(server);
	}

	public static Snapshot snapshot(FrequencyWorldData data) {
		CacheEntry cached = cache;
		long dataRevision = data.narrativeStateRevision();
		if (cached != null && cached.owner() == data && cached.dataRevision() == dataRevision) {
			return cached.snapshot();
		}
		CompoundTag root = data.narrativeState();
		Snapshot result;
		if (!root.contains(ROOT_KEY)) {
			result = Snapshot.absent();
		} else {
			try {
				result = decode(root.getCompoundOrEmpty(ROOT_KEY));
			} catch (RuntimeException exception) {
				// A rejected() snapshot here makes every caller across ending/ and pursuit/ treat
				// the finale subsystem as inert: EndBossEncounterService.tickStart bails out
				// silently on this exact result, with nothing else ever surfacing the cause.
				// Without this log line a corrupted world_interface root turns the boss fight into
				// an unexplained stall.
				TheFourthFrequency.LOGGER.error("Failed to decode world_interface state; treating it as "
						+ "rejected until it is cleared or repaired", exception);
				result = Snapshot.rejected();
			}
		}
		cache = new CacheEntry(data, dataRevision, result);
		return result;
	}

	public static Snapshot get(FrequencyWorldData data) {
		return snapshot(data);
	}

	public static MutationResult initialize(MinecraftServer server, UUID encounterId, ArenaLayout arena, long seed) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(encounterId, "encounterId");
		Objects.requireNonNull(arena, "arena");
		Snapshot current = snapshot(server);
		if (current.valid() && current.present()) {
			return current.encounterId().filter(encounterId::equals).isPresent()
					? MutationResult.noop("already_initialized", current)
					: MutationResult.rejected("encounter_already_exists", current);
		}
		if (!current.valid()) return MutationResult.rejected("stored_state_invalid", current);

		if (arena.gates().stream().anyMatch(value -> value.state() != WorldInterfaceGatewayState.DORMANT)
				|| arena.anchors().stream().anyMatch(value -> value.destroyed()
						|| value.anchorEntityUuid().isEmpty())) {
			return MutationResult.rejected("initial_layout_not_pristine", current);
		}
		MutableState mutable = MutableState.initial(encounterId, arena, seed);
		Snapshot created;
		try {
			created = mutable.freeze(true, true);
		} catch (IllegalArgumentException exception) {
			return MutationResult.rejected("invalid_initial_state:" + exception.getMessage(), current);
		}
		write(FrequencyWorldData.get(server), created);
		return MutationResult.applied(created);
	}

	/**
	 * Compare-and-set mutation. The update is applied to a detached copy and is
	 * written only after the entire v1 schema validates.
	 */
	public static MutationResult mutate(MinecraftServer server, UUID expectedEncounterId, long expectedRevision,
			Consumer<MutableState> update) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(expectedEncounterId, "expectedEncounterId");
		Objects.requireNonNull(update, "update");
		FrequencyWorldData data = FrequencyWorldData.get(server);
		Snapshot before = snapshot(data);
		if (!before.valid() || !before.present()) return MutationResult.rejected("state_unavailable", before);
		if (before.encounterId().filter(expectedEncounterId::equals).isEmpty()) {
			return MutationResult.rejected("encounter_mismatch", before);
		}
		if (before.revision() != expectedRevision) return MutationResult.rejected("revision_mismatch", before);

		MutableState mutable = new MutableState(before);
		try {
			update.accept(mutable);
			Snapshot after = mutable.freeze(true, false);
			write(data, after);
			return MutationResult.applied(after);
		} catch (RuntimeException exception) {
			// Unlike the revision-mismatch rejection above (a routine, expected CAS race that
			// callers retry on), reaching this catch means the mutation itself violated an
			// invariant - e.g. an illegal stage transition or a schema check in freeze(). Callers
			// generally give up silently on any non-revision_mismatch reason, so this is the only
			// place left to record what invariant broke and why.
			TheFourthFrequency.LOGGER.warn("Rejected world_interface mutation: {}", exception.toString());
			return MutationResult.rejected("invalid_mutation:" + exception.getMessage(), before);
		}
	}

	public static MutationResult transition(MinecraftServer server, UUID encounterId, long expectedRevision,
			WorldInterfaceStage expectedStage, WorldInterfaceStage nextStage) {
		Objects.requireNonNull(expectedStage, "expectedStage");
		Objects.requireNonNull(nextStage, "nextStage");
		return mutate(server, encounterId, expectedRevision, state -> {
			if (state.stage() != expectedStage) throw new IllegalStateException("stage_mismatch");
			state.transitionTo(nextStage);
		});
	}

	/** Removes only a malformed/unknown world-interface payload. */
	public static boolean clearInvalid(MinecraftServer server) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		Snapshot current = snapshot(data);
		if (current.valid() || !current.present()) return false;
		data.updateNarrativeState(root -> root.remove(ROOT_KEY));
		cache = new CacheEntry(data, data.narrativeStateRevision(), Snapshot.absent());
		return true;
	}

	private static void write(FrequencyWorldData data, Snapshot snapshot) {
		CompoundTag encoded = encode(snapshot);
		data.updateNarrativeState(root -> root.put(ROOT_KEY, encoded));
		cache = new CacheEntry(data, data.narrativeStateRevision(), snapshot);
	}

	private static Snapshot decode(CompoundTag tag) {
		int formatVersion = tag.getIntOr("format_version", -1);
		if (formatVersion < 1 || formatVersion > FORMAT_VERSION) throw invalid("unsupported_version");
		UUID encounterId = parseUuid(tag.getStringOr("encounter_id", ""), "encounter_id");
		long revision = positive(tag.getLongOr("revision", -1L), "revision");
		WorldInterfaceStage stage = WorldInterfaceStage.fromWireId(tag.getIntOr("stage", -1));
		Outcome outcome = Outcome.fromSerializedName(tag.getStringOr("outcome", "none"));
		int arenaVersion = positiveInt(tag.getIntOr("arena_version", -1), "arena_version");
		String dimension = boundedString(tag.getStringOr("arena_dimension", ""), 128, "arena_dimension");
		BlockPos arenaCenter = BlockPos.of(tag.getLongOr("arena_center", 0L));
		BlockPos altarCenter = BlockPos.of(tag.getLongOr("altar_center", 0L));
		BlockPos safeSpawn = BlockPos.of(tag.getLongOr("safe_spawn", 0L));
		int arenaBuildCursor = Math.clamp(tag.getIntOr("arena_build_cursor", 0), 0, 32_768);

		List<Gate> gates = decodeGates(tag.getListOrEmpty("gates"));
		List<Anchor> anchors = decodeAnchors(tag.getListOrEmpty("anchors"));
		Set<UUID> roster = decodeUuidSet(tag.getListOrEmpty("frozen_roster"), MAX_ROSTER_SIZE, "player");
		Map<UUID, TerminalTransaction> transactions = decodeTransactions(tag.getListOrEmpty("terminal_transactions"));

		double maxHealth = finiteNonNegative(tag.getDoubleOr("max_virtual_health", 0.0D), "max_virtual_health");
		double health = finiteNonNegative(tag.getDoubleOr("virtual_health", 0.0D), "virtual_health");
		if (health > maxHealth) throw invalid("virtual_health_exceeds_max");
		Optional<UUID> bossUuid = optionalUuid(tag.getStringOr("boss_uuid", ""));
		long activeTicks = nonNegative(tag.getLongOr("active_ticks", 0L), "active_ticks");
		long runningSince = tag.getLongOr("running_since_game_time", -1L);
		if (runningSince < -1L) throw invalid("running_since_game_time");
		long seed = tag.getLongOr("deterministic_seed", 0L);
		Optional<AttackEnvelope> attack = decodeAttack(tag.getCompoundOrEmpty("current_attack"));
		long stageStarted = nonNegative(tag.getLongOr("stage_started_active_tick", 0L), "stage_started_active_tick");
		long actionSequence = nonNegative(tag.getLongOr("action_sequence", 0L), "action_sequence");
		int lastAction = boundedInt(tag.getIntOr("last_action", 0), 0, 9, "last_action");
		long nextAction = nonNegative(tag.getLongOr("next_action_active_tick", 0L), "next_action_active_tick");
		long lastEviction = tag.getLongOr("last_forced_eviction_tick", -1L);
		if (lastEviction < -1L) throw invalid("last_forced_eviction_tick");
		Map<UUID, Long> controlCooldowns = decodeTickMap(tag.getListOrEmpty("control_cooldowns"),
				"player", "until", MAX_RECOVERY_ENTRIES);
		int recoveryGrace = boundedInt(tag.getIntOr("recovery_grace_ticks", 0), 0, 40,
				"recovery_grace_ticks");
		int terrainUsed = boundedInt(tag.getIntOr("terrain_edits_used", 0), 0, MAX_TERRAIN_EDITS,
				"terrain_edits_used");

		Map<UUID, RespawnLedgerEntry> respawns = decodeRespawns(tag.getListOrEmpty("respawn_ledger"));
		Map<UUID, PoemLedgerEntry> poem = decodePoem(tag.getListOrEmpty("poem_ledger"));
		List<RecoveryEntry> recovery = decodeRecovery(tag.getListOrEmpty("recovery_ledger"));
		Optional<UUID> dragonId = optionalUuid(tag.getStringOr("friendly_dragon_uuid", ""));
		BlockPos exitPosition = BlockPos.of(tag.getLongOr("exit_position", altarCenter.asLong()));
		boolean exitOpen = tag.getBooleanOr("exit_open", false);
		int resolutionStep = boundedInt(tag.getIntOr("resolution_step", 0), 0, 64, "resolution_step");
		long resolutionTick = tag.getLongOr("resolution_tick", -1L);
		if (resolutionTick < -1L) throw invalid("resolution_tick");
		boolean sacrificeCommitted = tag.getBooleanOr("sacrifice_committed", false);
		// Absent in format 1, and its absence is meaningful rather than a default: a v1 ritual had no
		// window at all. See FORMAT_VERSION for what happens to a v1 save caught mid-ritual.
		long ritualDeadline = tag.getLongOr("ritual_deadline_tick", -1L);
		if (ritualDeadline < -1L) throw invalid("ritual_deadline_tick");

		Snapshot snapshot = new Snapshot(true, true, Optional.of(encounterId), revision, stage, outcome,
				arenaVersion, dimension, arenaCenter, altarCenter, safeSpawn, arenaBuildCursor,
				gates, anchors, roster, transactions, sacrificeCommitted, bossUuid, maxHealth, health,
				activeTicks, runningSince, seed, attack, stageStarted, actionSequence,
				lastAction, nextAction, lastEviction, controlCooldowns, recoveryGrace, terrainUsed,
				respawns, poem, recovery, dragonId, exitPosition, exitOpen, resolutionStep, resolutionTick,
				ritualDeadline);
		validate(snapshot, false);
		return snapshot;
	}

	private static CompoundTag encode(Snapshot state) {
		validate(state, false);
		CompoundTag tag = new CompoundTag();
		tag.putInt("format_version", FORMAT_VERSION);
		tag.putString("encounter_id", state.encounterId().orElseThrow().toString());
		tag.putLong("revision", state.revision());
		tag.putInt("stage", state.stage().wireId());
		tag.putString("outcome", state.outcome().serializedName());
		tag.putInt("arena_version", state.arenaVersion());
		tag.putString("arena_dimension", state.arenaDimension());
		tag.putLong("arena_center", state.arenaCenter().asLong());
		tag.putLong("altar_center", state.altarCenter().asLong());
		tag.putLong("safe_spawn", state.safeSpawn().asLong());
		tag.putInt("arena_build_cursor", state.arenaBuildCursor());
		tag.put("gates", encodeGates(state.gates()));
		tag.put("anchors", encodeAnchors(state.anchors()));
		tag.put("frozen_roster", encodeUuidSet(state.frozenRoster(), "player"));
		tag.put("terminal_transactions", encodeTransactions(state.terminalTransactions()));
		tag.putBoolean("sacrifice_committed", state.sacrificeCommitted());
		state.bossUuid().ifPresent(value -> tag.putString("boss_uuid", value.toString()));
		tag.putDouble("max_virtual_health", state.maxVirtualHealth());
		tag.putDouble("virtual_health", state.virtualHealth());
		tag.putLong("active_ticks", state.activeTicks());
		tag.putLong("running_since_game_time", state.runningSinceGameTime());
		tag.putLong("deterministic_seed", state.deterministicSeed());
		state.currentAttack().ifPresent(value -> tag.put("current_attack", encodeAttack(value)));
		tag.putLong("stage_started_active_tick", state.stageStartedActiveTick());
		tag.putLong("action_sequence", state.actionSequence());
		tag.putInt("last_action", state.lastActionWireId());
		tag.putLong("next_action_active_tick", state.nextActionActiveTick());
		tag.putLong("last_forced_eviction_tick", state.lastForcedEvictionTick());
		tag.put("control_cooldowns", encodeTickMap(state.controlCooldowns(), "player", "until"));
		tag.putInt("recovery_grace_ticks", state.recoveryGraceTicks());
		tag.putInt("terrain_edits_used", state.terrainEditsUsed());
		tag.put("respawn_ledger", encodeRespawns(state.respawnLedger()));
		tag.put("poem_ledger", encodePoem(state.poemLedger()));
		tag.put("recovery_ledger", encodeRecovery(state.recoveryLedger()));
		state.friendlyDragonUuid().ifPresent(value -> tag.putString("friendly_dragon_uuid", value.toString()));
		tag.putLong("exit_position", state.exitPosition().asLong());
		tag.putBoolean("exit_open", state.exitOpen());
		tag.putInt("resolution_step", state.resolutionStep());
		tag.putLong("resolution_tick", state.resolutionTick());
		tag.putLong("ritual_deadline_tick", state.ritualDeadlineTick());
		return tag;
	}

	private static void validate(Snapshot state, boolean allowInitialRevision) {
		if (!state.present() || !state.valid() || state.encounterId().isEmpty()) throw invalid("not_persistable");
		if (state.revision() < (allowInitialRevision ? 0L : 1L)) throw invalid("revision");
		if (state.stage() == WorldInterfaceStage.UNPREPARED) throw invalid("persisted_unprepared_stage");
		if (state.arenaVersion() < 1 || !"minecraft:the_end".equals(state.arenaDimension())) throw invalid("arena");
		if (state.gates().size() != GATE_COUNT || state.anchors().size() != ANCHOR_COUNT) throw invalid("layout_size");
		validateIndexedPositions(state.gates().stream().map(value -> new IndexedPos(value.index(), value.position())).toList(),
				GATE_COUNT, "gates");
		validateIndexedPositions(state.anchors().stream().map(value -> new IndexedPos(value.index(), value.position())).toList(),
				ANCHOR_COUNT, "anchors");
		if (state.frozenRoster().size() > MAX_ROSTER_SIZE || state.terminalTransactions().size() > MAX_ROSTER_SIZE
				|| !state.frozenRoster().containsAll(state.terminalTransactions().keySet())) throw invalid("ritual_membership");
		if (!Double.isFinite(state.maxVirtualHealth()) || !Double.isFinite(state.virtualHealth())
				|| state.maxVirtualHealth() < 0.0D || state.virtualHealth() < 0.0D
				|| state.virtualHealth() > state.maxVirtualHealth()) throw invalid("virtual_health");
		if (state.activeTicks() < 0L || state.runningSinceGameTime() < -1L
				|| state.recoveryGraceTicks() < 0 || state.recoveryGraceTicks() > 40
				|| state.terrainEditsUsed() < 0 || state.terrainEditsUsed() > MAX_TERRAIN_EDITS) throw invalid("combat_limits");
		if (state.respawnLedger().size() > MAX_ROSTER_SIZE || state.poemLedger().size() > MAX_ROSTER_SIZE
				|| state.controlCooldowns().size() > MAX_RECOVERY_ENTRIES
				|| state.recoveryLedger().size() > MAX_RECOVERY_ENTRIES) throw invalid("ledger_limits");
		if (state.recoveryLedger().stream().map(RecoveryEntry::id).distinct().count()
				!= state.recoveryLedger().size()) throw invalid("recovery_duplicate");
		if (state.controlCooldowns().values().stream().anyMatch(value -> value < 0L)
				|| state.stageStartedActiveTick() < 0L || state.stageStartedActiveTick() > state.activeTicks()
				|| state.actionSequence() < 0L || state.lastActionWireId() < 0 || state.lastActionWireId() > 9
				|| state.nextActionActiveTick() < 0L || state.lastForcedEvictionTick() < -1L) {
			throw invalid("scheduler_state");
		}
		if (state.resolutionStep() < 0 || state.resolutionStep() > 64 || state.resolutionTick() < -1L) {
			throw invalid("resolution_state");
		}
		// A running window is only meaningful while the altar is still collecting terminals, and it
		// must be gone the instant the sacrifice commits - otherwise a restart mid-summon would find a
		// deadline still counting against a fight that has already started.
		if (state.ritualDeadlineTick() < -1L) throw invalid("ritual_deadline");
		if (state.ritualDeadlineTick() >= 0L
				&& (state.sacrificeCommitted() || state.stage() != WorldInterfaceStage.WAITING_TERMINALS)) {
			throw invalid("ritual_window_stage");
		}
		boolean hasCommittedTransaction = state.terminalTransactions().values().stream()
				.anyMatch(value -> value.state() == TerminalTransactionState.COMMITTED);
		if (state.sacrificeCommitted() && (state.frozenRoster().isEmpty()
				|| state.terminalTransactions().size() != state.frozenRoster().size()
				|| state.terminalTransactions().values().stream()
				.anyMatch(value -> value.state() != TerminalTransactionState.COMMITTED))) throw invalid("partial_commit");
		if (!state.sacrificeCommitted() && hasCommittedTransaction) throw invalid("unlocked_committed_transaction");
		// The partial_commit check above guarantees a non-empty roster whenever the sacrifice is
		// committed, and ritual_membership caps it at MAX_ROSTER_SIZE, so the policy's 1..8
		// precondition holds. Deferring to the policy keeps this invariant from having to be
		// re-derived by hand every time the pool's shape changes.
		if (state.sacrificeCommitted()
				&& Math.abs(state.maxVirtualHealth()
						- WorldInterfacePolicy.maxHealth(state.frozenRoster().size())) > 0.000_001D) {
			throw invalid("maximum_health_roster_mismatch");
		}
		if (state.stage().wireId() >= WorldInterfaceStage.SUMMONING.wireId()
				&& (!state.sacrificeCommitted() || state.maxVirtualHealth() <= 0.0D)) {
			throw invalid("combat_without_sacrifice");
		}
		if (state.stage().wireId() <= WorldInterfaceStage.PHASE_3.wireId()
				&& state.outcome() != Outcome.NONE) throw invalid("premature_outcome_lock");
		if (state.stage() == WorldInterfaceStage.SUCCESS_RESOLUTION && state.outcome() != Outcome.SUCCESS) {
			throw invalid("success_outcome_missing");
		}
		if (state.stage() == WorldInterfaceStage.FAILURE_RESOLUTION && state.outcome() != Outcome.FAILURE) {
			throw invalid("failure_outcome_missing");
		}
		if ((state.stage() == WorldInterfaceStage.PORTAL_OPEN || state.stage() == WorldInterfaceStage.COMPLETE)
				&& state.outcome() == Outcome.NONE) throw invalid("resolution_outcome_missing");
		if (state.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()
				&& state.bossUuid().isPresent()) throw invalid("finished_boss_identity");
	}

	private static void validateIndexedPositions(List<IndexedPos> values, int expected, String name) {
		Set<Integer> indices = new LinkedHashSet<>();
		Set<Long> positions = new LinkedHashSet<>();
		for (IndexedPos value : values) {
			if (value.index() < 0 || value.index() >= expected || !indices.add(value.index())
					|| !positions.add(value.position().asLong())) throw invalid(name + "_duplicate");
		}
	}

	private static List<Gate> decodeGates(ListTag list) {
		if (list.size() != GATE_COUNT) throw invalid("gate_count");
		List<Gate> result = new ArrayList<>(GATE_COUNT);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			result.add(new Gate(entry.getIntOr("index", -1), BlockPos.of(entry.getLongOr("position", 0L)),
					gatewayStateFromSerializedName(entry.getStringOr("state", ""))));
		}
		result.sort(Comparator.comparingInt(Gate::index));
		return List.copyOf(result);
	}

	private static ListTag encodeGates(List<Gate> values) {
		ListTag list = new ListTag();
		for (Gate value : values) {
			CompoundTag entry = new CompoundTag();
			entry.putInt("index", value.index());
			entry.putLong("position", value.position().asLong());
			entry.putString("state", value.state().serializedName());
			list.add(entry);
		}
		return list;
	}

	private static List<Anchor> decodeAnchors(ListTag list) {
		if (list.size() != ANCHOR_COUNT) throw invalid("anchor_count");
		List<Anchor> result = new ArrayList<>(ANCHOR_COUNT);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			// Disk key deliberately unchanged; see Anchor for why the Java name no longer says crystal.
			result.add(new Anchor(entry.getIntOr("index", -1), BlockPos.of(entry.getLongOr("position", 0L)),
					optionalUuid(entry.getStringOr("crystal_uuid", "")), entry.getBooleanOr("destroyed", false)));
		}
		result.sort(Comparator.comparingInt(Anchor::index));
		return List.copyOf(result);
	}

	private static ListTag encodeAnchors(List<Anchor> values) {
		ListTag list = new ListTag();
		for (Anchor value : values) {
			CompoundTag entry = new CompoundTag();
			entry.putInt("index", value.index());
			entry.putLong("position", value.position().asLong());
			value.anchorEntityUuid().ifPresent(uuid -> entry.putString("crystal_uuid", uuid.toString()));
			entry.putBoolean("destroyed", value.destroyed());
			list.add(entry);
		}
		return list;
	}

	private static Set<UUID> decodeUuidSet(ListTag list, int maximum, String key) {
		if (list.size() > maximum) throw invalid(key + "_count");
		Set<UUID> result = new LinkedHashSet<>();
		for (int i = 0; i < list.size(); i++) {
			UUID value = parseUuid(list.getCompoundOrEmpty(i).getStringOr(key, ""), key);
			if (!result.add(value)) throw invalid(key + "_duplicate");
		}
		return Collections.unmodifiableSet(result);
	}

	private static ListTag encodeUuidSet(Set<UUID> values, String key) {
		ListTag list = new ListTag();
		for (UUID value : values) {
			CompoundTag entry = new CompoundTag();
			entry.putString(key, value.toString());
			list.add(entry);
		}
		return list;
	}

	private static Map<UUID, Long> decodeTickMap(ListTag list, String uuidKey, String tickKey, int maximum) {
		if (list.size() > maximum) throw invalid(uuidKey + "_tick_count");
		Map<UUID, Long> result = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			UUID id = parseUuid(entry.getStringOr(uuidKey, ""), uuidKey);
			long tick = nonNegative(entry.getLongOr(tickKey, -1L), tickKey);
			if (result.putIfAbsent(id, tick) != null) throw invalid(uuidKey + "_tick_duplicate");
		}
		return Collections.unmodifiableMap(result);
	}

	private static ListTag encodeTickMap(Map<UUID, Long> values, String uuidKey, String tickKey) {
		ListTag list = new ListTag();
		for (Map.Entry<UUID, Long> value : values.entrySet()) {
			CompoundTag entry = new CompoundTag();
			entry.putString(uuidKey, value.getKey().toString());
			entry.putLong(tickKey, value.getValue());
			list.add(entry);
		}
		return list;
	}

	private static Map<UUID, TerminalTransaction> decodeTransactions(ListTag list) {
		if (list.size() > MAX_ROSTER_SIZE) throw invalid("transaction_count");
		Map<UUID, TerminalTransaction> result = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			UUID playerId = parseUuid(entry.getStringOr("player", ""), "transaction_player");
			TerminalTransaction value = new TerminalTransaction(playerId,
					boundedString(entry.getStringOr("terminal_id", ""), 64, "terminal_id"),
					nonNegativeInt(entry.getIntOr("generation", -1), "generation"),
					TerminalTransactionState.fromSerializedName(entry.getStringOr("state", "")),
					entry.getLongOr("prepared_tick", 0L), entry.getCompoundOrEmpty("terminal_snapshot").copy());
			if (value.terminalId().isBlank() || result.putIfAbsent(playerId, value) != null) {
				throw invalid("transaction_duplicate");
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private static ListTag encodeTransactions(Map<UUID, TerminalTransaction> values) {
		ListTag list = new ListTag();
		for (TerminalTransaction value : values.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putString("player", value.playerId().toString());
			entry.putString("terminal_id", value.terminalId());
			entry.putInt("generation", value.generation());
			entry.putString("state", value.state().serializedName());
			entry.putLong("prepared_tick", value.preparedTick());
			entry.put("terminal_snapshot", value.terminalSnapshot().copy());
			list.add(entry);
		}
		return list;
	}

	private static Optional<AttackEnvelope> decodeAttack(CompoundTag tag) {
		if (tag.isEmpty()) return Optional.empty();
		int action = boundedInt(tag.getIntOr("action", 0), 1, 9, "attack_action");
		Set<UUID> targets = decodeUuidSet(tag.getListOrEmpty("targets"), MAX_ATTACK_TARGETS, "target");
		return Optional.of(new AttackEnvelope(action, nonNegative(tag.getLongOr("sequence", 0L), "attack_sequence"),
				nonNegative(tag.getLongOr("started_active_tick", 0L), "attack_started"),
				nonNegative(tag.getLongOr("due_active_tick", 0L), "attack_due"), tag.getLongOr("seed", 0L),
				BlockPos.of(tag.getLongOr("origin", 0L)), targets,
				nonNegativeInt(tag.getIntOr("cursor", 0), "attack_cursor"),
				tag.getBooleanOr("damage_applied", false)));
	}

	private static CompoundTag encodeAttack(AttackEnvelope value) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("action", value.actionWireId());
		tag.putLong("sequence", value.sequence());
		tag.putLong("started_active_tick", value.startedActiveTick());
		tag.putLong("due_active_tick", value.dueActiveTick());
		tag.putLong("seed", value.seed());
		tag.putLong("origin", value.origin().asLong());
		tag.put("targets", encodeUuidSet(value.targets(), "target"));
		tag.putInt("cursor", value.cursor());
		tag.putBoolean("damage_applied", value.damageApplied());
		return tag;
	}

	private static Map<UUID, RespawnLedgerEntry> decodeRespawns(ListTag list) {
		if (list.size() > MAX_ROSTER_SIZE) throw invalid("respawn_count");
		Map<UUID, RespawnLedgerEntry> result = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			UUID player = parseUuid(entry.getStringOr("player", ""), "respawn_player");
			boolean hasOriginal = entry.getBooleanOr("has_original", false);
			RespawnLedgerEntry value = new RespawnLedgerEntry(player, hasOriginal,
					entry.getStringOr("dimension", ""),
					BlockPos.of(entry.getLongOr("position", 0L)), entry.getFloatOr("yaw", 0.0F),
					entry.getFloatOr("pitch", 0.0F), entry.getBooleanOr("forced", false),
					entry.getBooleanOr("restored", false));
			if (result.putIfAbsent(player, value) != null) throw invalid("respawn_duplicate");
		}
		return Collections.unmodifiableMap(result);
	}

	private static ListTag encodeRespawns(Map<UUID, RespawnLedgerEntry> values) {
		ListTag list = new ListTag();
		for (RespawnLedgerEntry value : values.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putString("player", value.playerId().toString());
			entry.putBoolean("has_original", value.hasOriginal());
			entry.putString("dimension", value.dimension());
			entry.putLong("position", value.position().asLong());
			entry.putFloat("yaw", value.yaw());
			entry.putFloat("pitch", value.pitch());
			entry.putBoolean("forced", value.forced());
			entry.putBoolean("restored", value.restored());
			list.add(entry);
		}
		return list;
	}

	private static Map<UUID, PoemLedgerEntry> decodePoem(ListTag list) {
		if (list.size() > MAX_ROSTER_SIZE) throw invalid("poem_count");
		Map<UUID, PoemLedgerEntry> result = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			UUID player = parseUuid(entry.getStringOr("player", ""), "poem_player");
			PoemLedgerEntry value = new PoemLedgerEntry(player,
					nonNegative(entry.getLongOr("sequence", 0L), "poem_sequence"),
					entry.getBooleanOr("started", false), entry.getBooleanOr("acked", false),
					entry.getBooleanOr("meta_complete", false));
			if (result.putIfAbsent(player, value) != null) throw invalid("poem_duplicate");
		}
		return Collections.unmodifiableMap(result);
	}

	private static ListTag encodePoem(Map<UUID, PoemLedgerEntry> values) {
		ListTag list = new ListTag();
		for (PoemLedgerEntry value : values.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putString("player", value.playerId().toString());
			entry.putLong("sequence", value.sequence());
			entry.putBoolean("started", value.started());
			entry.putBoolean("acked", value.acked());
			entry.putBoolean("meta_complete", value.metaComplete());
			list.add(entry);
		}
		return list;
	}

	private static List<RecoveryEntry> decodeRecovery(ListTag list) {
		if (list.size() > MAX_RECOVERY_ENTRIES) throw invalid("recovery_count");
		List<RecoveryEntry> result = new ArrayList<>();
		Set<UUID> ids = new LinkedHashSet<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			UUID id = parseUuid(entry.getStringOr("id", ""), "recovery_id");
			if (!ids.add(id)) throw invalid("recovery_duplicate");
			result.add(new RecoveryEntry(id, parseUuid(entry.getStringOr("owner", ""), "recovery_owner"),
					boundedString(entry.getStringOr("kind", ""), 32, "recovery_kind"),
					entry.getCompoundOrEmpty("payload").copy(), entry.getBooleanOr("resolved", false)));
		}
		return List.copyOf(result);
	}

	private static ListTag encodeRecovery(List<RecoveryEntry> values) {
		ListTag list = new ListTag();
		for (RecoveryEntry value : values) {
			CompoundTag entry = new CompoundTag();
			entry.putString("id", value.id().toString());
			entry.putString("owner", value.ownerId().toString());
			entry.putString("kind", value.kind());
			entry.put("payload", value.payload().copy());
			entry.putBoolean("resolved", value.resolved());
			list.add(entry);
		}
		return list;
	}

	private static UUID parseUuid(String value, String name) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw invalid(name);
		}
	}

	private static Optional<UUID> optionalUuid(String value) {
		return value.isBlank() ? Optional.empty() : Optional.of(parseUuid(value, "uuid"));
	}

	private static WorldInterfaceGatewayState gatewayStateFromSerializedName(String value) {
		for (WorldInterfaceGatewayState state : WorldInterfaceGatewayState.values()) {
			if (state.serializedName().equals(value)) return state;
		}
		throw invalid("gateway_state");
	}

	private static long positive(long value, String name) {
		if (value < 1L) throw invalid(name);
		return value;
	}

	private static long nonNegative(long value, String name) {
		if (value < 0L) throw invalid(name);
		return value;
	}

	private static int positiveInt(int value, String name) {
		if (value < 1) throw invalid(name);
		return value;
	}

	private static int nonNegativeInt(int value, String name) {
		if (value < 0) throw invalid(name);
		return value;
	}

	private static int boundedInt(int value, int minimum, int maximum, String name) {
		if (value < minimum || value > maximum) throw invalid(name);
		return value;
	}

	private static double finiteNonNegative(double value, String name) {
		if (!Double.isFinite(value) || value < 0.0D) throw invalid(name);
		return value;
	}

	private static String boundedString(String value, int maximum, String name) {
		if (value.isBlank() || value.length() > maximum) throw invalid(name);
		return value;
	}

	private static IllegalArgumentException invalid(String reason) {
		return new IllegalArgumentException(reason);
	}

	public enum Outcome {
		NONE("none"), SUCCESS("success"), FAILURE("failure");

		private final String serializedName;

		Outcome(String serializedName) {
			this.serializedName = serializedName;
		}

		public String serializedName() {
			return serializedName;
		}

		public static Outcome fromSerializedName(String value) {
			for (Outcome outcome : values()) if (outcome.serializedName.equals(value)) return outcome;
			throw invalid("outcome");
		}
	}

	public enum TerminalTransactionState {
		PREPARED("prepared"), REMOVED("removed"), RETURN_PENDING("return_pending"), COMMITTED("committed");

		private final String serializedName;

		TerminalTransactionState(String serializedName) {
			this.serializedName = serializedName;
		}

		public String serializedName() {
			return serializedName;
		}

		public static TerminalTransactionState fromSerializedName(String value) {
			for (TerminalTransactionState state : values()) if (state.serializedName.equals(value)) return state;
			throw invalid("terminal_transaction_state");
		}
	}

	public record Gate(int index, BlockPos position, WorldInterfaceGatewayState state) {
		public Gate {
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(state, "state");
		}
	}

	/**
	 * One arena anchor slot.
	 *
	 * <p>{@code anchorEntityUuid} is the identity of whatever entity currently stands in the slot.
	 * It used to be called {@code crystalUuid}, back when that entity was always a tagged
	 * {@link net.minecraft.world.entity.boss.enderdragon.EndCrystal}; the disk key is still
	 * {@code crystal_uuid} on purpose, so a world saved before the bespoke anchor entity existed
	 * decodes without a migration step. Only the Java name moved.</p>
	 */
	public record Anchor(int index, BlockPos position, Optional<UUID> anchorEntityUuid, boolean destroyed) {
		public Anchor {
			Objects.requireNonNull(position, "position");
			anchorEntityUuid = anchorEntityUuid == null ? Optional.empty() : anchorEntityUuid;
		}
	}

	public record ArenaLayout(int version, String dimension, BlockPos arenaCenter, BlockPos altarCenter,
			BlockPos safeSpawn, List<Gate> gates, List<Anchor> anchors) {
		public ArenaLayout {
			if (version < 1) throw invalid("arena_version");
			dimension = boundedString(dimension, 128, "arena_dimension");
			Objects.requireNonNull(arenaCenter, "arenaCenter");
			Objects.requireNonNull(altarCenter, "altarCenter");
			Objects.requireNonNull(safeSpawn, "safeSpawn");
			gates = gates.stream().sorted(Comparator.comparingInt(Gate::index)).toList();
			anchors = anchors.stream().sorted(Comparator.comparingInt(Anchor::index)).toList();
			if (gates.size() != GATE_COUNT || anchors.size() != ANCHOR_COUNT) throw invalid("layout_size");
			validateIndexedPositions(gates.stream().map(value -> new IndexedPos(value.index(), value.position())).toList(),
					GATE_COUNT, "gates");
			validateIndexedPositions(anchors.stream().map(value -> new IndexedPos(value.index(), value.position())).toList(),
					ANCHOR_COUNT, "anchors");
		}
	}

	public record TerminalTransaction(UUID playerId, String terminalId, int generation,
			TerminalTransactionState state, long preparedTick, CompoundTag terminalSnapshot) {
		public TerminalTransaction {
			Objects.requireNonNull(playerId, "playerId");
			terminalId = boundedString(terminalId, 64, "terminalId");
			if (generation < 0) throw invalid("generation");
			Objects.requireNonNull(state, "state");
			if (preparedTick < 0L) throw invalid("preparedTick");
			terminalSnapshot = terminalSnapshot == null ? new CompoundTag() : terminalSnapshot.copy();
			if (terminalSnapshot.sizeInBytes() > 262_144) throw invalid("terminal_snapshot_too_large");
			if (!playerId.toString().equals(terminalSnapshot.getStringOr("owner_id", ""))
					|| !terminalId.equals(terminalSnapshot.getStringOr("terminal_id", ""))
					|| generation != terminalSnapshot.getIntOr("copy_generation", -1)) {
				throw invalid("terminal_snapshot_identity");
			}
		}

		@Override
		public CompoundTag terminalSnapshot() {
			return terminalSnapshot.copy();
		}

		public TerminalTransaction withState(TerminalTransactionState next) {
			return new TerminalTransaction(playerId, terminalId, generation, next, preparedTick, terminalSnapshot);
		}
	}

	public record AttackEnvelope(int actionWireId, long sequence, long startedActiveTick, long dueActiveTick,
			long seed, BlockPos origin, Set<UUID> targets, int cursor, boolean damageApplied) {
		public AttackEnvelope {
			if (actionWireId < 1 || actionWireId > 9 || sequence < 0L || startedActiveTick < 0L
					|| dueActiveTick < startedActiveTick || cursor < 0) throw invalid("attack_envelope");
			Objects.requireNonNull(origin, "origin");
			targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
			if (targets.size() > MAX_ATTACK_TARGETS) throw invalid("attack_targets");
		}
	}

	public record RespawnLedgerEntry(UUID playerId, boolean hasOriginal, String dimension, BlockPos position,
			float yaw, float pitch, boolean forced, boolean restored) {
		public RespawnLedgerEntry {
			Objects.requireNonNull(playerId, "playerId");
			dimension = dimension == null ? "" : dimension;
			if (hasOriginal) dimension = boundedString(dimension, 128, "dimension");
			else if (dimension.length() > 128) throw invalid("dimension");
			Objects.requireNonNull(position, "position");
			if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw invalid("respawn_rotation");
		}
	}

	public record PoemLedgerEntry(UUID playerId, long sequence, boolean started, boolean acked,
			boolean metaComplete) {
		public PoemLedgerEntry {
			Objects.requireNonNull(playerId, "playerId");
			if (sequence < 0L) throw invalid("poem_sequence");
		}
	}

	/** Generic persisted custody ledger for weapon/drop recovery services. */
	public record RecoveryEntry(UUID id, UUID ownerId, String kind, CompoundTag payload, boolean resolved) {
		public RecoveryEntry {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(ownerId, "ownerId");
			kind = boundedString(kind, 32, "kind");
			payload = payload == null ? new CompoundTag() : payload.copy();
			if (payload.sizeInBytes() > 1_048_576) throw invalid("recovery_payload_too_large");
		}

		@Override
		public CompoundTag payload() {
			return payload.copy();
		}
	}

	public record Snapshot(boolean present, boolean valid, Optional<UUID> encounterId, long revision,
			WorldInterfaceStage stage, Outcome outcome, int arenaVersion, String arenaDimension,
			BlockPos arenaCenter, BlockPos altarCenter, BlockPos safeSpawn, int arenaBuildCursor,
			List<Gate> gates, List<Anchor> anchors, Set<UUID> frozenRoster,
			Map<UUID, TerminalTransaction> terminalTransactions, boolean sacrificeCommitted, Optional<UUID> bossUuid,
			double maxVirtualHealth, double virtualHealth, long activeTicks, long runningSinceGameTime,
			long deterministicSeed, Optional<AttackEnvelope> currentAttack,
			long stageStartedActiveTick, long actionSequence, int lastActionWireId, long nextActionActiveTick,
			long lastForcedEvictionTick, Map<UUID, Long> controlCooldowns,
			int recoveryGraceTicks, int terrainEditsUsed, Map<UUID, RespawnLedgerEntry> respawnLedger,
			Map<UUID, PoemLedgerEntry> poemLedger, List<RecoveryEntry> recoveryLedger,
			Optional<UUID> friendlyDragonUuid, BlockPos exitPosition, boolean exitOpen,
			int resolutionStep, long resolutionTick, long ritualDeadlineTick) {
		public Snapshot {
			encounterId = encounterId == null ? Optional.empty() : encounterId;
			Objects.requireNonNull(stage, "stage");
			Objects.requireNonNull(outcome, "outcome");
			arenaDimension = arenaDimension == null ? "" : arenaDimension;
			Objects.requireNonNull(arenaCenter, "arenaCenter");
			Objects.requireNonNull(altarCenter, "altarCenter");
			Objects.requireNonNull(safeSpawn, "safeSpawn");
			gates = List.copyOf(gates);
			anchors = List.copyOf(anchors);
			frozenRoster = Collections.unmodifiableSet(new LinkedHashSet<>(frozenRoster));
			terminalTransactions = Collections.unmodifiableMap(new LinkedHashMap<>(terminalTransactions));
			bossUuid = bossUuid == null ? Optional.empty() : bossUuid;
			currentAttack = currentAttack == null ? Optional.empty() : currentAttack;
			controlCooldowns = Collections.unmodifiableMap(new LinkedHashMap<>(controlCooldowns));
			respawnLedger = Collections.unmodifiableMap(new LinkedHashMap<>(respawnLedger));
			poemLedger = Collections.unmodifiableMap(new LinkedHashMap<>(poemLedger));
			recoveryLedger = List.copyOf(recoveryLedger);
			friendlyDragonUuid = friendlyDragonUuid == null ? Optional.empty() : friendlyDragonUuid;
			Objects.requireNonNull(exitPosition, "exitPosition");
		}

		public int destroyedAnchorCount() {
			return (int) anchors.stream().filter(Anchor::destroyed).count();
		}

		public int aliveAnchorCount() {
			return anchors.size() - destroyedAnchorCount();
		}

		public int depositedTerminalCount() {
			return (int) terminalTransactions.values().stream()
					.filter(value -> value.state() == TerminalTransactionState.REMOVED
							|| value.state() == TerminalTransactionState.COMMITTED).count();
		}

		public Optional<Anchor> anchorForEntity(UUID anchorEntityId) {
			return anchors.stream()
					.filter(value -> value.anchorEntityUuid().filter(anchorEntityId::equals).isPresent())
					.findFirst();
		}

		private static Snapshot absent() {
			return empty(false, true);
		}

		private static Snapshot rejected() {
			return empty(true, false);
		}

		private static Snapshot empty(boolean present, boolean valid) {
			return new Snapshot(present, valid, Optional.empty(), 0L, WorldInterfaceStage.UNPREPARED,
					Outcome.NONE, 0, "", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, 0,
					List.of(), List.of(), Set.of(), Map.of(), false, Optional.empty(),
					0.0D, 0.0D, 0L, -1L, 0L, Optional.empty(),
					0L, 0L, 0, 0L, -1L, Map.of(), 0, 0, Map.of(), Map.of(), List.of(),
					Optional.empty(), BlockPos.ZERO, false, 0, -1L, -1L);
		}

		/** Whether the ritual window is counting down right now. */
		public boolean ritualWindowRunning() {
			return ritualDeadlineTick >= 0L;
		}

		/** Ticks left in the ritual window, or 0 when none is running or it has already lapsed. */
		public long ritualWindowRemaining(long gameTime) {
			return ritualDeadlineTick < 0L ? 0L : Math.max(0L, ritualDeadlineTick - gameTime);
		}
	}

	public static final class MutableState {
		private UUID encounterId;
		private long revision;
		private WorldInterfaceStage stage;
		private Outcome outcome;
		private int arenaVersion;
		private String arenaDimension;
		private BlockPos arenaCenter;
		private BlockPos altarCenter;
		private BlockPos safeSpawn;
		private int arenaBuildCursor;
		private final List<Gate> gates;
		private final List<Anchor> anchors;
		private final Set<UUID> frozenRoster;
		private final Map<UUID, TerminalTransaction> terminalTransactions;
		private boolean sacrificeCommitted;
		private UUID bossUuid;
		private double maxVirtualHealth;
		private double virtualHealth;
		private long activeTicks;
		private long runningSinceGameTime;
		private long deterministicSeed;
		private AttackEnvelope currentAttack;
		private long stageStartedActiveTick;
		private long actionSequence;
		private int lastActionWireId;
		private long nextActionActiveTick;
		private long lastForcedEvictionTick;
		private final Map<UUID, Long> controlCooldowns;
		private int recoveryGraceTicks;
		private int terrainEditsUsed;
		private final Map<UUID, RespawnLedgerEntry> respawnLedger;
		private final Map<UUID, PoemLedgerEntry> poemLedger;
		private final List<RecoveryEntry> recoveryLedger;
		private UUID friendlyDragonUuid;
		private BlockPos exitPosition;
		private boolean exitOpen;
		private int resolutionStep;
		private long resolutionTick;
		private long ritualDeadlineTick;

		private MutableState(Snapshot source) {
			this.encounterId = source.encounterId().orElseThrow();
			this.revision = source.revision();
			this.stage = source.stage();
			this.outcome = source.outcome();
			this.arenaVersion = source.arenaVersion();
			this.arenaDimension = source.arenaDimension();
			this.arenaCenter = source.arenaCenter();
			this.altarCenter = source.altarCenter();
			this.safeSpawn = source.safeSpawn();
			this.arenaBuildCursor = source.arenaBuildCursor();
			this.gates = new ArrayList<>(source.gates());
			this.anchors = new ArrayList<>(source.anchors());
			this.frozenRoster = new LinkedHashSet<>(source.frozenRoster());
			this.terminalTransactions = new LinkedHashMap<>(source.terminalTransactions());
			this.sacrificeCommitted = source.sacrificeCommitted();
			this.bossUuid = source.bossUuid().orElse(null);
			this.maxVirtualHealth = source.maxVirtualHealth();
			this.virtualHealth = source.virtualHealth();
			this.activeTicks = source.activeTicks();
			this.runningSinceGameTime = source.runningSinceGameTime();
			this.deterministicSeed = source.deterministicSeed();
			this.currentAttack = source.currentAttack().orElse(null);
			this.stageStartedActiveTick = source.stageStartedActiveTick();
			this.actionSequence = source.actionSequence();
			this.lastActionWireId = source.lastActionWireId();
			this.nextActionActiveTick = source.nextActionActiveTick();
			this.lastForcedEvictionTick = source.lastForcedEvictionTick();
			this.controlCooldowns = new LinkedHashMap<>(source.controlCooldowns());
			this.recoveryGraceTicks = source.recoveryGraceTicks();
			this.terrainEditsUsed = source.terrainEditsUsed();
			this.respawnLedger = new LinkedHashMap<>(source.respawnLedger());
			this.poemLedger = new LinkedHashMap<>(source.poemLedger());
			this.recoveryLedger = new ArrayList<>(source.recoveryLedger());
			this.friendlyDragonUuid = source.friendlyDragonUuid().orElse(null);
			this.exitPosition = source.exitPosition();
			this.exitOpen = source.exitOpen();
			this.resolutionStep = source.resolutionStep();
			this.resolutionTick = source.resolutionTick();
			this.ritualDeadlineTick = source.ritualDeadlineTick();
		}

		private static MutableState initial(UUID encounterId, ArenaLayout arena, long seed) {
			Snapshot seedState = new Snapshot(true, true, Optional.of(encounterId), 0L,
					WorldInterfaceStage.ARENA_READY, Outcome.NONE, arena.version(), arena.dimension(),
					arena.arenaCenter(), arena.altarCenter(), arena.safeSpawn(), 0,
					arena.gates(), arena.anchors(), Set.of(), Map.of(), false, Optional.empty(),
					0.0D, 0.0D, 0L, -1L, seed, Optional.empty(),
					0L, 0L, 0, 0L, -1L, Map.of(), 0, 0,
					Map.of(), Map.of(), List.of(), Optional.empty(), arena.altarCenter(), false, 0, -1L, -1L);
			return new MutableState(seedState);
		}

		public UUID encounterId() { return encounterId; }
		public long revision() { return revision; }
		public WorldInterfaceStage stage() { return stage; }
		public Outcome outcome() { return outcome; }
		public int arenaVersion() { return arenaVersion; }
		public String arenaDimension() { return arenaDimension; }
		public BlockPos arenaCenter() { return arenaCenter; }
		public BlockPos altarCenter() { return altarCenter; }
		public BlockPos safeSpawn() { return safeSpawn; }
		public int arenaBuildCursor() { return arenaBuildCursor; }
		public List<Gate> gates() { return List.copyOf(gates); }
		public List<Anchor> anchors() { return List.copyOf(anchors); }
		public Set<UUID> frozenRoster() { return Set.copyOf(frozenRoster); }
		public Map<UUID, TerminalTransaction> terminalTransactions() { return Map.copyOf(terminalTransactions); }
		public boolean sacrificeCommitted() { return sacrificeCommitted; }
		public Optional<UUID> bossUuid() { return Optional.ofNullable(bossUuid); }
		public double maxVirtualHealth() { return maxVirtualHealth; }
		public double virtualHealth() { return virtualHealth; }
		public long activeTicks() { return activeTicks; }
		public long runningSinceGameTime() { return runningSinceGameTime; }
		public long deterministicSeed() { return deterministicSeed; }
		public Optional<AttackEnvelope> currentAttack() { return Optional.ofNullable(currentAttack); }
		public long stageStartedActiveTick() { return stageStartedActiveTick; }
		public long actionSequence() { return actionSequence; }
		public int lastActionWireId() { return lastActionWireId; }
		public long nextActionActiveTick() { return nextActionActiveTick; }
		public long lastForcedEvictionTick() { return lastForcedEvictionTick; }
		public Map<UUID, Long> controlCooldowns() { return Map.copyOf(controlCooldowns); }
		public int recoveryGraceTicks() { return recoveryGraceTicks; }
		public int terrainEditsUsed() { return terrainEditsUsed; }
		public Map<UUID, RespawnLedgerEntry> respawnLedger() { return Map.copyOf(respawnLedger); }
		public Map<UUID, PoemLedgerEntry> poemLedger() { return Map.copyOf(poemLedger); }
		public List<RecoveryEntry> recoveryLedger() { return List.copyOf(recoveryLedger); }
		public Optional<UUID> friendlyDragonUuid() { return Optional.ofNullable(friendlyDragonUuid); }
		public BlockPos exitPosition() { return exitPosition; }
		public boolean exitOpen() { return exitOpen; }
		public int resolutionStep() { return resolutionStep; }
		public long resolutionTick() { return resolutionTick; }
		public long ritualDeadlineTick() { return ritualDeadlineTick; }

		public void transitionTo(WorldInterfaceStage next) {
			Objects.requireNonNull(next, "next");
			if (!stage.canTransitionTo(next)) throw new IllegalStateException("illegal_stage_transition");
			if (next == WorldInterfaceStage.SUCCESS_RESOLUTION) setOutcome(Outcome.SUCCESS);
			if (next == WorldInterfaceStage.FAILURE_RESOLUTION) setOutcome(Outcome.FAILURE);
			stage = next;
			stageStartedActiveTick = activeTicks;
		}

		public void setOutcome(Outcome outcome) {
			Objects.requireNonNull(outcome, "outcome");
			if (this.outcome != Outcome.NONE && this.outcome != outcome) {
				throw new IllegalStateException("outcome_already_locked");
			}
			this.outcome = outcome;
		}
		public void setArenaBuildCursor(int value) { arenaBuildCursor = Math.clamp(value, 0, 32_768); }

		public void setGateState(WorldInterfaceGatewayState value) {
			for (int i = 0; i < gates.size(); i++) {
				Gate gate = gates.get(i);
				gates.set(i, new Gate(gate.index(), gate.position(), value));
			}
		}

		public void bindAnchorEntity(int index, UUID anchorEntityUuid) {
			Anchor anchor = anchorAt(index);
			anchors.set(index, new Anchor(index, anchor.position(), Optional.of(anchorEntityUuid),
					anchor.destroyed()));
		}

		public boolean markAnchorDestroyed(int index) {
			Anchor anchor = anchorAt(index);
			if (anchor.destroyed()) return false;
			anchors.set(index, new Anchor(index, anchor.position(), anchor.anchorEntityUuid(), true));
			return true;
		}

		private Anchor anchorAt(int index) {
			if (index < 0 || index >= anchors.size() || anchors.get(index).index() != index) {
				throw invalid("anchor_index");
			}
			return anchors.get(index);
		}

		/**
		 * Adds one depositor to the roster.
		 *
		 * <p>The roster used to be a snapshot of everyone online, taken once, at the first deposit.
		 * That made three unrelated things into one number: who was playing, who was participating,
		 * and how big the fight should be. A ninth person logging in anywhere in the world made the
		 * ritual impossible; anyone joining or leaving invalidated it; and someone who had never
		 * touched the story still had to walk to the End and hand over a terminal or nobody could
		 * start. The roster is now built out of the only evidence that means anything here - a
		 * terminal actually placed in the core - and grows one person at a time.</p>
		 */
		public void joinRoster(UUID playerId) {
			Objects.requireNonNull(playerId, "playerId");
			if (sacrificeCommitted) throw new IllegalStateException("sacrifice_committed");
			if (frozenRoster.contains(playerId)) return;
			if (frozenRoster.size() >= MAX_ROSTER_SIZE) throw new IllegalStateException("roster_full");
			frozenRoster.add(playerId);
		}

		public void clearFrozenRoster() {
			if (sacrificeCommitted) throw new IllegalStateException("sacrifice_committed");
			frozenRoster.clear();
			ritualDeadlineTick = -1L;
		}

		/**
		 * Arms, or disarms with -1, the deadline the ritual has to be summoned within.
		 *
		 * <p>Stored as an absolute game time rather than a remaining count so it survives a restart
		 * without anyone having to remember to tick it down, and so the client can render a countdown
		 * from one number it already receives.</p>
		 */
		public void setRitualDeadline(long gameTime) {
			if (gameTime < -1L) throw invalid("ritualDeadlineTick");
			if (sacrificeCommitted && gameTime >= 0L) throw new IllegalStateException("sacrifice_committed");
			ritualDeadlineTick = gameTime;
		}

		public void putTerminalTransaction(TerminalTransaction transaction) {
			if (!frozenRoster.contains(transaction.playerId())) throw new IllegalStateException("player_not_frozen");
			TerminalTransaction before = terminalTransactions.get(transaction.playerId());
			if (before != null && (!before.terminalId().equals(transaction.terminalId())
					|| before.generation() != transaction.generation())) throw new IllegalStateException("terminal_changed");
			terminalTransactions.put(transaction.playerId(), transaction);
		}

		/**
		 * Drops one escrow entry, and with it that player's place on the roster.
		 *
		 * <p>The two are the same fact now: the roster <em>is</em> the set of people whose terminal is
		 * in the core. Leaving a name behind after its terminal went home would make the fight scale
		 * for someone who is not in it, and would let {@code readyToSummon} count a participant who
		 * has already walked away.</p>
		 */
		public void removeTerminalTransaction(UUID playerId) {
			terminalTransactions.remove(playerId);
			if (!sacrificeCommitted) frozenRoster.remove(playerId);
		}

		public void commitSacrifice(double maximumHealth) {
			if (sacrificeCommitted || frozenRoster.isEmpty()
					|| terminalTransactions.size() != frozenRoster.size()
					|| terminalTransactions.values().stream().anyMatch(value -> value.state() != TerminalTransactionState.REMOVED)) {
				throw new IllegalStateException("sacrifice_not_ready");
			}
			terminalTransactions.replaceAll((id, value) -> value.withState(TerminalTransactionState.COMMITTED));
			sacrificeCommitted = true;
			// The window existed to bound the wait for this moment; past it there is nothing to wait
			// for, and a deadline still ticking against a committed sacrifice is a state the validator
			// refuses outright.
			ritualDeadlineTick = -1L;
			maxVirtualHealth = finiteNonNegative(maximumHealth, "maxVirtualHealth");
			if (maxVirtualHealth <= 0.0D) throw invalid("maxVirtualHealth");
			virtualHealth = maxVirtualHealth;
			setGateState(WorldInterfaceGatewayState.PURPLE);
			transitionTo(WorldInterfaceStage.SUMMONING);
		}

		public void setVirtualHealth(double maximum, double current) {
			maxVirtualHealth = finiteNonNegative(maximum, "maximum");
			virtualHealth = finiteNonNegative(current, "current");
			if (virtualHealth > maxVirtualHealth) throw invalid("health_exceeds_maximum");
		}

		public void setBossUuid(UUID value) { bossUuid = Objects.requireNonNull(value, "value"); }
		public void clearBossUuid() { bossUuid = null; }

		public void setClock(long elapsed, long runningSince) {
			activeTicks = nonNegative(elapsed, "elapsed");
			if (runningSince < -1L) throw invalid("runningSince");
			runningSinceGameTime = runningSince;
		}

		public void setCurrentAttack(AttackEnvelope value) { currentAttack = value; }
		public void clearCurrentAttack() { currentAttack = null; }
		public void setActionSchedule(long sequence, int lastAction, long nextActionTick) {
			actionSequence = nonNegative(sequence, "actionSequence");
			lastActionWireId = boundedInt(lastAction, 0, 9, "lastAction");
			nextActionActiveTick = nonNegative(nextActionTick, "nextActionTick");
		}
		public void setLastForcedEvictionTick(long value) {
			if (value < -1L) throw invalid("lastForcedEvictionTick");
			lastForcedEvictionTick = value;
		}
		public void putControlCooldown(UUID playerId, long untilActiveTick) {
			Objects.requireNonNull(playerId, "playerId");
			controlCooldowns.put(playerId, nonNegative(untilActiveTick, "untilActiveTick"));
		}
		public void removeControlCooldown(UUID playerId) { controlCooldowns.remove(playerId); }
		public void clearControlCooldowns() { controlCooldowns.clear(); }
		public void setRecoveryGraceTicks(int value) { recoveryGraceTicks = Math.clamp(value, 0, 40); }
		public void setTerrainEditsUsed(int value) { terrainEditsUsed = Math.clamp(value, 0, MAX_TERRAIN_EDITS); }
		public void putRespawn(RespawnLedgerEntry value) { respawnLedger.put(value.playerId(), value); }
		public void removeRespawn(UUID playerId) { respawnLedger.remove(playerId); }
		public void putPoem(PoemLedgerEntry value) { poemLedger.put(value.playerId(), value); }
		public void addRecovery(RecoveryEntry value) {
			if (recoveryLedger.stream().anyMatch(existing -> existing.id().equals(value.id()))) {
				throw new IllegalStateException("recovery_duplicate");
			}
			recoveryLedger.add(value);
		}
		public void removeRecovery(UUID id) { recoveryLedger.removeIf(value -> value.id().equals(id)); }
		public void setFriendlyDragonUuid(UUID value) { friendlyDragonUuid = value; }
		public void setExit(BlockPos position, boolean open) { exitPosition = Objects.requireNonNull(position); exitOpen = open; }
		public void setResolution(int step, long tick) {
			resolutionStep = boundedInt(step, 0, 64, "resolutionStep");
			if (tick < -1L) throw invalid("resolutionTick");
			resolutionTick = tick;
		}

		private Snapshot freeze(boolean incrementRevision, boolean initial) {
			long nextRevision = incrementRevision ? Math.addExact(revision, 1L) : revision;
			Snapshot frozen = new Snapshot(true, true, Optional.of(encounterId), nextRevision, stage, outcome,
					arenaVersion, arenaDimension, arenaCenter, altarCenter, safeSpawn, arenaBuildCursor,
					List.copyOf(gates), List.copyOf(anchors), Set.copyOf(frozenRoster),
					Map.copyOf(terminalTransactions), sacrificeCommitted, Optional.ofNullable(bossUuid),
					maxVirtualHealth, virtualHealth,
					activeTicks, runningSinceGameTime, deterministicSeed,
					Optional.ofNullable(currentAttack), stageStartedActiveTick, actionSequence, lastActionWireId,
					nextActionActiveTick, lastForcedEvictionTick, Map.copyOf(controlCooldowns),
					recoveryGraceTicks, terrainEditsUsed,
					Map.copyOf(respawnLedger), Map.copyOf(poemLedger), List.copyOf(recoveryLedger),
					Optional.ofNullable(friendlyDragonUuid), exitPosition, exitOpen, resolutionStep, resolutionTick,
					ritualDeadlineTick);
			validate(frozen, initial);
			return frozen;
		}
	}

	public record MutationResult(boolean applied, boolean idempotent, String reason, Snapshot snapshot) {
		private static MutationResult applied(Snapshot snapshot) {
			return new MutationResult(true, false, "applied", snapshot);
		}

		private static MutationResult noop(String reason, Snapshot snapshot) {
			return new MutationResult(true, true, reason, snapshot);
		}

		private static MutationResult rejected(String reason, Snapshot snapshot) {
			return new MutationResult(false, false, reason, snapshot);
		}
	}

	private record IndexedPos(int index, BlockPos position) {
	}
}
