package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.state.AnomalyState;
import com.xm.thefourthfrequency.state.StoryState;
import com.xm.thefourthfrequency.world.PrivateDimensions;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Server-authoritative single-anomaly scheduler. The historical class name is retained for compatibility. */
public final class AmbientAnomalyService {
	public static final String[] TYPES = AnomalyCatalog.definitions().stream().map(AnomalyDefinition::id)
			.toArray(String[]::new);
	private static final int CHECK_INTERVAL = 20;
	private static boolean initialized;

	private AmbientAnomalyService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(AmbientAnomalyService::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			FrequencyWorldData data = FrequencyWorldData.get(server);
			if (data.terminalRecord(player.getUUID()).isPresent()) data.updateTerminalRecord(player.getUUID(), tag -> {
				tag.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK,
						AnomalyIntensity.graced(tag.getLongOr(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, 0L),
								player.level().getGameTime() + AnomalyIntensity.LOGIN_GRACE_TICKS));
				tag.putString(TerminalData.LAST_AMBIENT_DIMENSION, player.level().dimension().identifier().toString());
			});
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				PlayerMotionTracker.forget(handler.getPlayer().getUUID()));
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		long now = server.overworld().getGameTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Before the eligibility gates rather than after: the sample is what "this player has a
			// past" is built from, and a player who spent the last minute inside a terminal or a
			// private dimension has still been somewhere.
			PlayerMotionTracker.sample(player, now);
			updatePlayer(player, data);
		}
	}

	private static void updatePlayer(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return;
		// Private dimensions are excluded dimensions, and they freeze the clock for the same reason
		// the End does: a mirror is at most 110 seconds and a full layer at most six minutes, and
		// spending that wait means leaving one lands the player in an anomaly they were owed for
		// time they had no say over. Frozen here rather than in the mode switch below because the
		// stage ramp must not run in there at all - corridors in the layer are not somewhere the
		// player chose to go.
		if (PrivateDimensions.isPrivate(player.level())
				|| record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) {
			freeze(data, player, record, player.level().getGameTime());
			return;
		}
		StoryState story = StoryState.read(record);
		AnomalyState anomaly = AnomalyState.read(record);
		// ambientPressureAllowed rather than backgroundSystemsAllowed: the latter stays open through
		// the whole encounter, and silent_world landing on a boss fight takes MUSIC, AMBIENT and
		// HOSTILE with it for minutes. See FinaleRuntimePolicy.
		if (!story.bound() || !FinaleRuntimePolicy.ambientPressureAllowed(data, player)
				|| anomaly.suspended()) return;
		long now = player.level().getGameTime();
		// Always false from here on, and deliberately written as the constant rather than left as a
		// call that reads as live: the gate above returns whenever pressure is active, which is the
		// only condition under which this was ever true. Nothing is lost - the finale-pressure route
		// to the tier-5 ceiling was already redundant, because reaching the finale at all requires
		// three recorded eye bearings and the stronghold, and either of those raises it on its own.
		boolean endingActive = false;
		int ceiling = AnomalyIntensity.progressionCeiling(story.bound(), story.bandStage(),
				record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0),
				record.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0),
				record.getIntOr(TerminalData.PURSUIT_ACTIVITY_PROOF_MASK, 0),
				record.getLongOr(TerminalData.PURSUIT_EFFECTIVE_ACTIVITY_TICKS, 0L), endingActive);
		int oldTier = anomaly.tier();
		boolean activityCounts = effectiveActivity(player, record);
		long exposedTicks = anomaly.tierOnlineTicks() + (activityCounts ? CHECK_INTERVAL : 0L);
		int stageSuccesses = record.getIntOr(TerminalData.ANOMALY_STAGE_SUCCESSES, 0);
		// A player who said they speedrun gets the existing catch-up one level of lag earlier. It moves
		// nothing but the metering: same ceiling, same catalogue, same intensity.
		int tier = AnomalyIntensity.progressedStage(oldTier, ceiling, exposedTicks, stageSuccesses,
				ProfilePreference.playStyle(TerminalData.profileAnswers(record))
						== ProfilePreference.PlayStyle.SPEEDRUN);
		boolean stageAdvanced = tier != oldTier;
		long tierTicks = stageAdvanced ? 0L : exposedTicks;
		int heat = AnomalyIntensity.heatPercent(tierTicks);
		data.updateTerminalRecord(player.getUUID(), tag -> {
			new AnomalyState(tier, ceiling, tierTicks, heat, anomaly.nextAmbientTick(),
					anomaly.suspended(), anomaly.activeId(), anomaly.activeUntil()).writeTo(tag);
			if (stageAdvanced) tag.putInt(TerminalData.ANOMALY_STAGE_SUCCESSES, 0);
			tag.putBoolean(TerminalData.ANOMALY_LEGACY_RAMP, false);
		});

		String dimension = player.level().dimension().identifier().toString();
		if (!dimension.equals(record.getStringOr(TerminalData.LAST_AMBIENT_DIMENSION, ""))) {
			data.updateTerminalRecord(player.getUUID(), tag ->
					tag.putString(TerminalData.LAST_AMBIENT_DIMENSION, dimension));
		}
		// The dimension no longer buys the player a delay. It decides whether there is a schedule
		// running at all, and how fast. See AnomalyDimensionPolicy for why the old ninety-second
		// grace had to go: applied per crossing rather than per arrival, it let a player travelling
		// through portals outrun their own director indefinitely.
		AnomalyDimensionPolicy.Mode mode = AnomalyDimensionPolicy.mode(dimension);
		long next = anomaly.nextAmbientTick();
		long frozen = record.getLongOr(TerminalData.ANOMALY_FROZEN_REMAINING, 0L);
		if (mode == AnomalyDimensionPolicy.Mode.EXCLUDED) {
			freeze(data, player, record, now);
			return;
		}
		if (frozen > 0L) {
			// Back somewhere that runs one: the remainder starts again from here, so a long stay in
			// the End is neither charged to the player nor banked into an anomaly on the doormat.
			// resumedNext rather than thawedNext: a chase that ended while this was frozen wrote its
			// own six-and-a-half-minute quiet, and a thirty-second remainder must not cut it short.
			long resumed = AnomalyDimensionPolicy.resumedNext(now, frozen, next);
			data.updateTerminalRecord(player.getUUID(), tag -> {
				tag.putLong(TerminalData.ANOMALY_FROZEN_REMAINING, 0L);
				AnomalyState.read(tag).scheduled(resumed).writeTo(tag);
			});
			next = resumed;
		}
		boolean pressure = mode == AnomalyDimensionPolicy.Mode.PRESSURE;
		if (next <= 0L || oldTier == 0 && tier >= 1) {
			schedule(data, player, now, Math.max(1, tier), heat, true, pressure);
			return;
		}
		if (now < next) return;
		if (!eligible(player, record)) {
			data.updateTerminalRecord(player.getUUID(), tag ->
					tag.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, now + 60L * 20L));
			return;
		}
		boolean signature = record.getBooleanOr(TerminalData.SIGNATURE_ANOMALY_PENDING, false);
		int selectionTier = signature ? Math.max(Math.max(1, tier), ceiling) : Math.max(1, tier);
		boolean started = triggerSelected(player, record, selectionTier, now);
		if (signature && started) {
			data.updateTerminalRecord(player.getUUID(), tag ->
					tag.putBoolean(TerminalData.SIGNATURE_ANOMALY_PENDING, false));
		}
		// A draw where every candidate refused its own preflight is not an anomaly that happened,
		// and is not charged as one. See AnomalyIntensity.FAILED_TRIGGER_RETRY_TICKS.
		if (started) schedule(data, player, now, Math.max(1, tier), heat, false, pressure);
		else scheduleRetry(data, player, now);
	}

	/**
	 * Mainline beats (first Nether entry, first eye throw) pull the next anomaly forward and lift its
	 * selection to the story ceiling so the player meets unseen high-stage content at the moment the
	 * world "loses its vocabulary", instead of only after the slow stage ramp. The pending flag
	 * survives a failed trigger: the next scheduled anomaly then carries the signature instead.
	 */
	public static void scheduleSignature(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !StoryState.read(record).bound()
				|| record.getBooleanOr(TerminalData.SIGNATURE_ANOMALY_PENDING, false)) return;
		long target = player.level().getGameTime() + AnomalyIntensity.SIGNATURE_LEAD_TICKS;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.SIGNATURE_ANOMALY_PENDING, true);
			AnomalyState state = AnomalyState.read(tag);
			if (state.nextAmbientTick() <= 0L || state.nextAmbientTick() > target) {
				state.scheduled(target).writeTo(tag);
			}
		});
	}

	/**
	 * The pool a draw at {@code now} would choose from, after every preference the director applies.
	 *
	 * <p>Extracted so the debug status can report what the next draw is actually choosing between
	 * rather than re-deriving a second, quietly different answer. The list is weighted - an entry
	 * appears once per unit of weight - because the draw index below is taken against its size;
	 * callers that want the distinct set must reduce it themselves.</p>
	 *
	 * <p>What this cannot tell anyone is which entry wins. The seed is mixed with {@code now}, so the
	 * winner is decided on the tick it fires, and the loop below walks past any candidate whose own
	 * preflight refuses. A pool is honest; a name would not be.</p>
	 */
	public static List<AnomalyDefinition> candidatePool(ServerPlayer player, CompoundTag record, int tier, long now) {
		boolean strongAllowed = now >= record.getLongOr(TerminalData.NEXT_STRONG_ANOMALY_TICK, 0L);
		// The profile biases which entry is drawn, never how many or how hard. See weightedPool.
		List<AnomalyDefinition> candidates = AnomalyCatalog.weightedPool(tier, recentIds(record),
				strongAllowed, ProfilePreference.flavouredAnomalies(TerminalData.profileAnswers(record)));
		if (candidates.isEmpty()) return List.of();
		// Anything this player has never met outranks anything they have, on every draw.
		//
		// This used to apply only to the two signature beats. Everywhere else the pool was drawn from
		// freely, and a nineteen-entry catalogue drawn freely is a catalogue a player meets maybe two
		// thirds of: the recent-three rule stops the same anomaly coming back immediately and does
		// nothing at all about one that has been seen five times while another has never fired. The
		// stage weighting made it worse rather than better, because a tier-matched entry is three
		// times as likely every single draw.
		//
		// A preference, not a filter, and the distinction is the whole safety of it: when the stage
		// has nothing new left it falls straight back to the full pool, so this can never starve a
		// player of anomalies - only reorder which one arrives. The signature path keeps its own
		// flag because it also raises the selection tier, which this does not touch.
		long seenMask = record.getLongOr(TerminalData.ANOMALY_SEEN_MASK, 0L);
		List<AnomalyDefinition> unseen = candidates.stream()
				.filter(value -> (seenMask & 1L << AnomalyCatalog.indexOf(value.id())) == 0L).toList();
		if (!unseen.isEmpty()) candidates = unseen;
		// Ordered after the unseen preference so it narrows whatever that left, and applied as a
		// preference rather than a filter: if the only thing this stage can still offer is shared,
		// company is not a reason to stop having anomalies.
		return preferPersonal(player, candidates);
	}

	/**
	 * The same pool with both freshness preferences dropped, for a draw that had nothing left to try.
	 *
	 * <p>{@link #candidatePool} narrows twice - the recent-ids exclusion inside {@link
	 * AnomalyCatalog#weightedPool}, then the unseen preference - and both were written as though a
	 * narrowed pool always contains something startable. It does not. Every entry can refuse its own
	 * preflight, and a stage-1 player standing in the open in daylight refuses two of three: {@code
	 * phantom_echo} wants a surface and {@code light_dropout} wants night. One successful {@code
	 * silent_world} is then enough to strand them - it is excluded as recent and seen, the two that
	 * remain cannot start, and the thirty-second retry re-derives the identical impossible pool until
	 * nightfall or a cave. That is the whole of "you see no anomalies early unless you go mining".
	 *
	 * <p>So freshness is downgraded here from a rule to a first choice: it still decides the draw
	 * whenever anything in it can actually happen, and only stops mattering once nothing can.
	 *
	 * <p>The strong cooldown and the solitude preference are deliberately kept. Neither is about
	 * which anomaly is freshest - one is a pacing floor on the heaviest entries, the other is about
	 * not visiting a shared effect on a bystander - and a draw that has run out of options is not a
	 * reason to break either.
	 */
	private static List<AnomalyDefinition> fallbackPool(ServerPlayer player, CompoundTag record, int tier, long now) {
		boolean strongAllowed = now >= record.getLongOr(TerminalData.NEXT_STRONG_ANOMALY_TICK, 0L);
		return preferPersonal(player, AnomalyCatalog.weightedPool(tier, Set.of(), strongAllowed,
				ProfilePreference.flavouredAnomalies(TerminalData.profileAnswers(record))));
	}

	private static List<AnomalyDefinition> preferPersonal(ServerPlayer player, List<AnomalyDefinition> candidates) {
		if (aloneEnoughForSharedEffects(player)) return candidates;
		List<AnomalyDefinition> personal = candidates.stream()
				.filter(value -> value.scope() == AnomalyDefinition.Scope.PRIVATE).toList();
		return personal.isEmpty() ? candidates : personal;
	}

	/**
	 * The tier a draw right now would select at, including the signature lift.
	 *
	 * <p>Read from the record rather than recomputed: {@code updatePlayer} has already persisted both
	 * the ramped tier and the story ceiling, so recomputing here would only create a second formula
	 * able to disagree with the one that actually schedules.</p>
	 */
	public static int selectionTier(CompoundTag record) {
		int tier = Math.max(1, record.getIntOr(TerminalData.ANOMALY_TIER, 0));
		return record.getBooleanOr(TerminalData.SIGNATURE_ANOMALY_PENDING, false)
				? Math.max(tier, record.getIntOr(TerminalData.ANOMALY_STORY_CEILING, 0))
				: tier;
	}

	/** Package-private so the GameTest bridge can drive one draw without waiting out an interval. */
	static boolean triggerSelected(ServerPlayer player, CompoundTag record, int tier, long now) {
		long baseSeed = record.getLongOr(TerminalData.PERSONALITY_SEED, 0L) ^ now
				^ record.getIntOr(TerminalData.ANOMALY_LOG_SEQUENCE, 0);
		// One attempted set across both passes. A preflight refusal is a statement about where the
		// player is standing on this tick, so an id the preferred pool already asked has nothing new
		// to say to the fallback and is skipped rather than re-tested.
		Set<String> attempted = new HashSet<>();
		return attemptFrom(player, candidatePool(player, record, tier, now), baseSeed, attempted, tier, now)
				|| attemptFrom(player, fallbackPool(player, record, tier, now), baseSeed, attempted, tier, now);
	}

	private static boolean attemptFrom(ServerPlayer player, List<AnomalyDefinition> candidates, long baseSeed,
			Set<String> attempted, int tier, long now) {
		if (candidates.isEmpty()) return false;
		int start = Math.floorMod((int) baseSeed, candidates.size());
		for (int offset = 0; offset < candidates.size(); offset++) {
			AnomalyDefinition selected = candidates.get((start + offset) % candidates.size());
			if (!attempted.add(selected.id())) continue;
			long seed = baseSeed + offset * 0x9E3779B97F4A7C15L;
			if (!trigger(player, selected.id(), false, seed)) continue;
			if (selected.strong()) {
				FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), tag ->
						tag.putLong(TerminalData.NEXT_STRONG_ANOMALY_TICK,
								now + AnomalyIntensity.strongCooldownTicks(tier, (int) (seed >>> 32))));
			}
			return true;
		}
		return false;
	}

	public static boolean trigger(ServerPlayer player, String id, boolean maximum) {
		return triggerDetailed(player, id, maximum).started();
	}

	public static TriggerResult triggerDetailed(ServerPlayer player, String id, boolean maximum) {
		long seed = player.getUUID().getMostSignificantBits() ^ player.level().getGameTime() ^ id.hashCode();
		return attempt(player, id, maximum, seed, null);
	}

	private static boolean trigger(ServerPlayer player, String id, boolean maximum, long seed) {
		return attempt(player, id, maximum, seed, null).started();
	}

	/** Package-private by design: the GameTest source-set bridge is the only external caller. */
	static boolean triggerForGameTest(ServerPlayer player, String id, long seed, int acceleratedDurationTicks) {
		if (acceleratedDurationTicks < 4) throw new IllegalArgumentException("GameTest duration must render at least two frames");
		return attempt(player, id, false, seed, acceleratedDurationTicks).started();
	}

	private static TriggerResult attempt(ServerPlayer player, String id, boolean maximum, long seed,
			Integer durationOverride) {
		AnomalyDefinition definition = AnomalyCatalog.require(id);
		if (AnomalyRuntimeService.active(player) != null) return TriggerResult.rejected(TriggerFailure.ALREADY_ACTIVE);
		AnomalyConditions.Prepared prepared = AnomalyConditions.prepare(player, definition, seed);
		if (prepared == null) return TriggerResult.rejected(TriggerFailure.PRECONDITION_UNMET);
		int variant = maximum ? 7 : Math.floorMod((int) seed, 7);
		int duration = durationOverride == null ? AnomalyTiming.durationTicks(id, seed) : durationOverride;
		AnomalyServerEffects.EffectLease effect = AnomalyServerEffects.begin(player, definition, duration,
				seed, prepared.anchor());
		if (effect == null) return TriggerResult.rejected(TriggerFailure.EFFECT_UNAVAILABLE);
		boolean started = AnomalyRuntimeService.start(player, definition, variant, seed, duration,
				prepared.anchor(), effect::cleanup);
		if (!started) effect.cleanup();
		return started ? TriggerResult.STARTED : TriggerResult.rejected(TriggerFailure.RUNTIME_REJECTED);
	}

	public enum TriggerFailure {
		NONE,
		ALREADY_ACTIVE,
		PRECONDITION_UNMET,
		EFFECT_UNAVAILABLE,
		RUNTIME_REJECTED
	}

	public record TriggerResult(boolean started, TriggerFailure failure) {
		private static final TriggerResult STARTED = new TriggerResult(true, TriggerFailure.NONE);
		private static TriggerResult rejected(TriggerFailure failure) {
			return new TriggerResult(false, failure);
		}
	}

	public static void stop(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		AnomalyRuntimeService.interrupt(player, true);
		data.updateTerminalRecord(player.getUUID(), tag -> AnomalyState.read(tag).suspended(true, 0L).writeTo(tag));
	}

	public static void resume(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		data.updateTerminalRecord(player.getUUID(), tag -> AnomalyState.read(tag)
				.suspended(false, player.level().getGameTime() + AnomalyIntensity.LOGIN_GRACE_TICKS).writeTo(tag));
	}

	/** How far another bound player has to be before a shared effect stops being somebody else's. */
	private static final double SOLITUDE_RADIUS = 32.0D;

	/**
	 * Whether an effect that changes the world can be aimed here without it landing on a bystander.
	 *
	 * <p>The four shared anomalies do real things to shared space: they put out the lights in a
	 * sixteen-block radius, force every door within twenty, turn every mob within thirty to face one
	 * person, and move that person somewhere else. Aimed at somebody standing alone in the dark those
	 * are the mod working exactly as intended. Aimed at somebody standing in a group's base they are
	 * a nuisance being visited on four people who did not roll for it, and - worse for the fiction -
	 * they are proof to everyone watching that the thing is a system with a radius rather than an
	 * attention with a subject.
	 *
	 * <p>So a target with company gets a personal anomaly instead. This is not only harm reduction:
	 * the whole escalation is about being singled out, and the effects that read that way loudest are
	 * exactly the ones nobody else can see.
	 *
	 * <p><b>Company is anybody, not anybody bound.</b> This used to ignore a nearby player who had no
	 * terminal, which gets both halves of the reason wrong. The nuisance half is obvious - their doors
	 * are still their doors, and someone who joined after the finale closed the frequency will never
	 * be bound at all. The fiction half is worse: being singled out is a claim about who is watching,
	 * and a witness who has never seen a terminal is the most damaging witness there is, because
	 * nothing about the mod explains to them what they just saw happen to the lights.
	 */
	private static boolean aloneEnoughForSharedEffects(ServerPlayer player) {
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			if (other == player || other.isSpectator() || other.level() != player.level()) continue;
			if (other.distanceToSqr(player) <= SOLITUDE_RADIUS * SOLITUDE_RADIUS) return false;
		}
		return true;
	}

	private static boolean eligible(ServerPlayer player, CompoundTag record) {
		return player.isAlive() && !player.isSpectator() && !player.isSleeping()
				&& !TerminalRuntimeService.isOpen(player)
				&& !record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false)
				&& AnomalyRuntimeService.active(player) == null;
	}

	private static boolean effectiveActivity(ServerPlayer player, CompoundTag record) {
		return player.isAlive() && !player.isSpectator() && !player.isSleeping()
				&& !TerminalRuntimeService.isOpen(player)
				&& !record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false)
				&& !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false);
	}

	private static Set<String> recentIds(CompoundTag record) {
		ListTag recent = record.getListOrEmpty(TerminalData.ANOMALY_RECENT_IDS);
		Set<String> result = new HashSet<>();
		for (int index = 0; index < recent.size(); index++) {
			String id = recent.getString(index).orElse("");
			if (AnomalyCatalog.contains(id)) result.add(id);
		}
		return result;
	}

	private static void schedule(FrequencyWorldData data, ServerPlayer player, long now, int tier, int heat,
			boolean first, boolean pressure) {
		long delay = RuntimeServices.config().pacing().developerAcceleration() ? (first ? 100L : 200L)
				: AnomalyIntensity.intervalTicks(tier, heat, player.getRandom().nextInt(), first, pressure);
		data.updateTerminalRecord(player.getUUID(), tag -> AnomalyState.read(tag).scheduled(now + delay).writeTo(tag));
	}

	/** Stores what is left of the wait, once, for a player somewhere that does not run one. */
	private static void freeze(FrequencyWorldData data, ServerPlayer player, CompoundTag record, long now) {
		if (record.getLongOr(TerminalData.ANOMALY_FROZEN_REMAINING, 0L) > 0L) return;
		long remaining = AnomalyDimensionPolicy.frozenRemaining(
				record.getLongOr(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, 0L), now);
		if (remaining <= 0L) return;
		data.updateTerminalRecord(player.getUUID(), tag ->
				tag.putLong(TerminalData.ANOMALY_FROZEN_REMAINING, remaining));
	}

	private static void scheduleRetry(FrequencyWorldData data, ServerPlayer player, long now) {
		long delay = RuntimeServices.config().pacing().developerAcceleration()
				? 100L : AnomalyIntensity.FAILED_TRIGGER_RETRY_TICKS;
		data.updateTerminalRecord(player.getUUID(), tag -> AnomalyState.read(tag).scheduled(now + delay).writeTo(tag));
	}

}
