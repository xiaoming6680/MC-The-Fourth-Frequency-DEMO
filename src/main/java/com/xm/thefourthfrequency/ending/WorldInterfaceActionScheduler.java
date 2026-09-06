package com.xm.thefourthfrequency.ending;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic attack cadence, shuffle and strong-control admission policy. */
public final class WorldInterfaceActionScheduler {
	public static final int STRONG_CONTROL_IMMUNITY_TICKS = 600;
	public static final int FORCED_EVICTION_WARNING_TICKS = 120;
	public static final int FORCED_EVICTION_COOLDOWN_TICKS = 3_600;
	/**
	 * Per player, before the gaze may empty their hotbar again. Three minutes.
	 *
	 * <p>Every exclusive action already grants its target {@link #STRONG_CONTROL_IMMUNITY_TICKS},
	 * and for the sweep that is far too short: nine slots on the floor is a shock worth having once
	 * and a chore worth having never, and at six hundred ticks a ten-minute fight has room to do it
	 * to the same person ten times. This is the eviction's cooldown applied per player rather than
	 * per encounter, for the same reason - it is the other action whose cost lands after the attack
	 * is over.
	 *
	 * <p>It also thins the action out on its own. The pick is a shuffled deck in which every action
	 * comes up once per cycle, so there is no weight to lower; a candidate with nobody left to aim
	 * at is skipped by the scan, and on a solo table this turns "once per deck" into "at most once
	 * every three minutes".
	 */
	public static final int HOTBAR_SWEEP_COOLDOWN_TICKS = 3_600;
	public static final int RESTART_RECOVERY_TICKS = 40;

	private static final long SHUFFLE_GAMMA = 0x9E3779B97F4A7C15L;
	private static final long INTERVAL_SALT = 0x632BE59BD9B4E019L;

	private WorldInterfaceActionScheduler() {
	}

	public static WorldInterfaceAction nextAction(WorldInterfaceStage stage, long encounterSeed,
			long sequence) {
		return nextAction(stage, encounterSeed, sequence, null);
	}

	/**
	 * Chooses from a deterministic per-cycle shuffle. Supplying the previously emitted action makes
	 * phase boundaries and shuffle-cycle boundaries adjacent-repeat safe.
	 */
	public static WorldInterfaceAction nextAction(WorldInterfaceStage stage, long encounterSeed,
			long sequence, WorldInterfaceAction previousAction) {
		requireCombatStage(stage);
		requireSequence(sequence);
		List<WorldInterfaceAction> eligible = new ArrayList<>(WorldInterfaceAction.unlockedAt(stage));
		int size = eligible.size();
		long cycle = sequence / size;
		shuffle(eligible, mix64(encounterSeed ^ (stage.wireId() * SHUFFLE_GAMMA) ^ cycle));
		if (cycle > 0L && eligible.getFirst() == lastOfPreviousCycle(stage, encounterSeed, cycle, size)) {
			WorldInterfaceAction first = eligible.getFirst();
			eligible.set(0, eligible.get(1));
			eligible.set(1, first);
		}
		int index = (int) (sequence % size);
		WorldInterfaceAction selected = eligible.get(index);
		if (selected == previousAction && size > 1) {
			selected = eligible.get((index + 1) % size);
		}
		return selected;
	}

	private static WorldInterfaceAction lastOfPreviousCycle(WorldInterfaceStage stage, long encounterSeed,
			long cycle, int size) {
		List<WorldInterfaceAction> previous = new ArrayList<>(WorldInterfaceAction.unlockedAt(stage));
		shuffle(previous, mix64(encounterSeed ^ (stage.wireId() * SHUFFLE_GAMMA) ^ (cycle - 1L)));
		return previous.get(size - 1);
	}

	/** Inclusive, unscaled global attack interval for the current combat phase. */
	public static int baseIntervalTicks(WorldInterfaceStage stage, long encounterSeed, long sequence) {
		requireCombatStage(stage);
		requireSequence(sequence);
		IntervalBounds bounds = intervalBounds(stage);
		int span = bounds.maximumTicks - bounds.minimumTicks + 1;
		long value = mix64(encounterSeed ^ INTERVAL_SALT ^ (stage.wireId() * SHUFFLE_GAMMA)
				^ (sequence * 0xD1342543DE82EF95L));
		return bounds.minimumTicks + floorMod(value, span);
	}

	/** Applies the authoritative anchor cooldown multiplier to the deterministic base interval. */
	public static int scaledIntervalTicks(WorldInterfaceStage stage, long encounterSeed, long sequence,
			int destroyedAnchors) {
		return scaledIntervalTicks(stage, encounterSeed, sequence, destroyedAnchors, 1);
	}

	/**
	 * How much faster the schedule runs for every player past the first.
	 *
	 * <p>The cadence used to be written entirely in terms of the fight - phase, then anchors - and
	 * said nothing at all about how many people were standing in the arena. One attack names one
	 * player, so an eight-strong table divided the same schedule eight ways: each individual was
	 * locked one eighth as often as a solo player, while the pool they were chewing through grew by
	 * only half a player's worth per head. Put together, a full table finishes sooner <em>and</em>
	 * each of its members spends most of that time watching an attack happen to somebody else. The
	 * finale stops being a fight anyone is in and becomes a queue.
	 *
	 * <p>This is the correction, and it is deliberately partial. Dividing the interval by the roster
	 * outright would restore the solo attack rate per player and produce eight simultaneous locks a
	 * cycle, which is neither survivable nor readable; a gentle reciprocal keeps a full table
	 * meaningfully busier than a duo without pretending eight people are eight separate fights. The
	 * floor is what stops the curve running away at the top of the roster.
	 */
	public static final double DENSITY_GAIN_PER_PLAYER = 0.18D;

	/** The most the roster may compress the schedule, however many people turn up. */
	public static final double MIN_DENSITY_MULTIPLIER = 0.45D;

	/**
	 * A hard floor on the gap between scheduled attacks, after every multiplier has been applied.
	 *
	 * <p>Nothing breaks below it - the scheduled lane cannot start an attack while one is running,
	 * so a short interval only ever means "start the next as soon as this one ends" - but a second
	 * is the point at which the quiet between casts stops existing at all, and the quiet is where
	 * the telegraph gets read.
	 */
	public static final int MIN_SCALED_INTERVAL_TICKS = 20;

	public static double rosterDensityMultiplier(int arenaParticipants) {
		if (arenaParticipants < 1 || arenaParticipants > WorldInterfacePolicy.MAX_ROSTER_SIZE) {
			throw new IllegalArgumentException("Arena participant count must be between 1 and 8");
		}
		return Math.max(MIN_DENSITY_MULTIPLIER,
				1.0D / (1.0D + DENSITY_GAIN_PER_PLAYER * (arenaParticipants - 1)));
	}

	/**
	 * The interval the encounter actually waits, with both the anchor wall and the roster applied.
	 *
	 * @param arenaParticipants how many players an attack could presently be aimed at
	 */
	public static int scaledIntervalTicks(WorldInterfaceStage stage, long encounterSeed, long sequence,
			int destroyedAnchors, int arenaParticipants) {
		double scaled = baseIntervalTicks(stage, encounterSeed, sequence)
				* WorldInterfacePolicy.attackCooldownMultiplier(destroyedAnchors)
				* rosterDensityMultiplier(arenaParticipants);
		return Math.max(MIN_SCALED_INTERVAL_TICKS, (int) Math.round(scaled));
	}

	/**
	 * Gap between scheduled attacks, per phase.
	 *
	 * <p>Third phase is deliberately much tighter than the curve the first two describe. At seventy
	 * to a hundred and ten ticks it was one attack every four or five seconds - a slower cadence than
	 * most of the mobs the player fought to get there - and since only one action could ever be
	 * running, the final form of the thing eating the world spent most of the fight doing nothing.
	 * Halved here, and the volley lane runs alongside it, so the last phase is continuous rather than
	 * turn-based.</p>
	 *
	 * <p>The first phase moved the other way, and for the opposite reason. It is where a player meets
	 * every one of these attacks for the first time, and a telegraph only teaches anything if there is
	 * room after it to work out what just happened. At five to seven seconds the next lock opened
	 * while the last one was still being read; at seven and a half to ten there is a beat of quiet
	 * between them, which is where learning the fight actually happens. The escalation across the
	 * three phases is steeper for it, which is the shape this encounter wants anyway.</p>
	 */
	public static IntervalBounds intervalBounds(WorldInterfaceStage stage) {
		requireCombatStage(stage);
		return switch (stage) {
			case PHASE_1 -> new IntervalBounds(150, 200);
			case PHASE_2 -> new IntervalBounds(75, 105);
			case PHASE_3 -> new IntervalBounds(35, 60);
			default -> throw new IllegalArgumentException("Stage is not a combat phase: " + stage);
		};
	}

	/**
	 * Whether an action can actually land while the interface is at its skyhold ceiling.
	 *
	 * <p>Only the grab cannot. It closes tendrils on a player within {@code GRAB_REACH}, and at the
	 * ceiling the body is more than forty blocks over the arena - so the attack telegraphed for two
	 * and a half seconds, reached, and came back empty, every single time. That is not a dodge the
	 * player earned, it is the schedule spending a slot on nothing, and during a window where the
	 * fight is already down to ranged answers it is the most expensive slot there is.
	 *
	 * <p>Everything else works from altitude by construction: the laser, the bolt and the lance are
	 * aimed at the ground, the lash commits to a spot on it, and the two confiscations do not care
	 * where the body is.
	 */
	public static boolean canStartWhileAloft(WorldInterfaceAction action) {
		return action != WorldInterfaceAction.GRAB_THROW;
	}

	/**
	 * Ticks between third-phase volleys: the extra attacks that run alongside the scheduled one.
	 *
	 * <p>Stateless on purpose. The encounter's own active-tick counter is the clock, so a volley is
	 * due whenever that counter lands on a multiple of this - no field to persist, no cursor to keep
	 * in step with a restart, and a replayed encounter fires them on exactly the same ticks.</p>
	 */
	public static final int VOLLEY_INTERVAL_TICKS = 40;

	/** How many extra attacks a single volley opens with, before the concurrency cap trims it. */
	public static int volleySize(long encounterSeed, long elapsedTicks) {
		return volleySize(encounterSeed, elapsedTicks, 1);
	}

	/**
	 * The same, widened by the roster.
	 *
	 * <p>The second lane is the safer half of the roster correction and the reason
	 * {@link #DENSITY_GAIN_PER_PLAYER} does not have to carry all of it. Extra volley slots do not
	 * compress the schedule or shorten anybody's telegraph; paired with the mutual exclusion in
	 * {@code WorldInterfaceAttackService}, they land on players the fight is <em>not</em> currently
	 * aimed at. So a bigger table gets more going on at once rather than the same events arriving
	 * faster, which is the difference between a busy finale and an unreadable one.
	 */
	public static int volleySize(long encounterSeed, long elapsedTicks, int arenaParticipants) {
		requireArenaParticipants(arenaParticipants);
		return 1 + (int) Math.floorMod(mix64(encounterSeed ^ (elapsedTicks * SHUFFLE_GAMMA)) >>> 11, 2L)
				+ (arenaParticipants - 1) / 3;
	}

	/**
	 * How many volley attacks may be in the air at once for a table of this size.
	 *
	 * <p>Never below {@code baseConcurrency}: a solo player's third phase is unchanged by every rule
	 * in this group, which is what keeps the one configuration the fight was tuned against honest.
	 */
	public static int volleyConcurrency(int baseConcurrency, int arenaParticipants) {
		if (baseConcurrency < 0) throw new IllegalArgumentException("Base concurrency cannot be negative");
		requireArenaParticipants(arenaParticipants);
		return baseConcurrency + (arenaParticipants - 1) / 2;
	}

	private static void requireArenaParticipants(int arenaParticipants) {
		if (arenaParticipants < 1 || arenaParticipants > WorldInterfacePolicy.MAX_ROSTER_SIZE) {
			throw new IllegalArgumentException("Arena participant count must be between 1 and 8");
		}
	}

	/** Exclusive controls share one global lane, while ordinary attacks do not consume it. */
	public static boolean canStartExclusiveControl(WorldInterfaceAction candidate,
			WorldInterfaceAction activeExclusiveAction) {
		Objects.requireNonNull(candidate, "candidate");
		if (!candidate.requiresExclusiveControl()) return true;
		return activeExclusiveAction == null || !activeExclusiveAction.requiresExclusiveControl();
	}

	/** A player cannot be selected for another strong control until 600 ticks have elapsed. */
	public static boolean isStrongControlTargetEligible(WorldInterfaceAction candidate, long currentTick,
			long targetLastControlledTick) {
		Objects.requireNonNull(candidate, "candidate");
		if (currentTick < 0L) throw new IllegalArgumentException("Current tick cannot be negative");
		if (!candidate.requiresExclusiveControl() || targetLastControlledTick < 0L) return true;
		if (targetLastControlledTick > currentTick) return false;
		return currentTick - targetLastControlledTick >= STRONG_CONTROL_IMMUNITY_TICKS;
	}

	public static boolean canStartAction(WorldInterfaceAction candidate,
			WorldInterfaceAction activeExclusiveAction, long currentTick, long targetLastControlledTick) {
		return canStartExclusiveControl(candidate, activeExclusiveAction)
				&& isStrongControlTargetEligible(candidate, currentTick, targetLastControlledTick);
	}

	public static boolean isForcedEvictionReady(long currentTick, long lastForcedEvictionTick,
			int islandParticipantCount) {
		if (currentTick < 0L) throw new IllegalArgumentException("Current tick cannot be negative");
		if (WorldInterfacePolicy.forcedEvictionTargetCount(islandParticipantCount) == 0) return false;
		if (lastForcedEvictionTick < 0L) return true;
		if (lastForcedEvictionTick > currentTick) return false;
		return currentTick - lastForcedEvictionTick >= FORCED_EVICTION_COOLDOWN_TICKS;
	}

	/**
	 * Selects stable real-disconnect targets. The integrated-server host participates in the count
	 * threshold but is never returned as a target.
	 */
	public static List<UUID> selectForcedEvictionTargets(List<UUID> islandParticipants,
			UUID integratedServerHost, long encounterSeed, long sequence) {
		return selectForcedEvictionTargets(islandParticipants, Set.of(), integratedServerHost,
				encounterSeed, sequence);
	}

	/**
	 * The same selection, with anyone this encounter has already thrown off the island moved to the
	 * back of the queue.
	 *
	 * <p>Eviction is the heaviest thing the fight does to a person: it is a real disconnect, and the
	 * player it lands on loses the arena, the timer and whatever they were in the middle of. The
	 * cooldown between evictions is three thousand six hundred ticks, six times the strong-control
	 * immunity a target picks up, so by the time the second one comes round nothing remembered that
	 * the first had happened - and a ten-minute fight has room for three. A table could watch one
	 * person get kicked twice while somebody else was never touched.
	 *
	 * <p>A back of the queue rather than an exclusion. The roster shrinks as people are evicted and
	 * as people die, and a rule that refused to reuse a name would eventually have no names left;
	 * repeats are allowed, they just have to wait for everyone else's turn.
	 *
	 * @param alreadyEvicted everyone this encounter has evicted so far, in any order
	 */
	public static List<UUID> selectForcedEvictionTargets(List<UUID> islandParticipants,
			Set<UUID> alreadyEvicted, UUID integratedServerHost, long encounterSeed, long sequence) {
		Objects.requireNonNull(islandParticipants, "islandParticipants");
		Objects.requireNonNull(alreadyEvicted, "alreadyEvicted");
		requireSequence(sequence);
		int requested = WorldInterfacePolicy.forcedEvictionTargetCount(islandParticipants.size());
		if (new HashSet<>(islandParticipants).size() != islandParticipants.size()
				|| islandParticipants.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Island participant roster must contain unique UUIDs");
		}
		if (requested == 0) return List.of();

		List<UUID> eligible = islandParticipants.stream()
				.filter(id -> !id.equals(integratedServerHost))
				.sorted(Comparator.comparing(UUID::toString))
				.toList();
		List<UUID> fresh = eligible.stream().filter(id -> !alreadyEvicted.contains(id))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		List<UUID> repeat = eligible.stream().filter(alreadyEvicted::contains)
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		// The fresh bucket keeps the original key, so an encounter that has evicted nobody yet
		// selects exactly what it always did.
		shuffle(fresh, mix64(encounterSeed ^ (sequence * SHUFFLE_GAMMA) ^ 0xA0761D6478BD642FL));
		shuffle(repeat, mix64(encounterSeed ^ (sequence * SHUFFLE_GAMMA) ^ 0xE7037ED1A0B428DBL));
		List<UUID> queue = new ArrayList<>(fresh);
		queue.addAll(repeat);
		return List.copyOf(queue.subList(0, Math.min(requested, queue.size())));
	}

	private static <T> void shuffle(List<T> values, long key) {
		for (int index = values.size() - 1; index > 0; index--) {
			int swapIndex = floorMod(mix64(key + index * SHUFFLE_GAMMA), index + 1);
			T current = values.get(index);
			values.set(index, values.get(swapIndex));
			values.set(swapIndex, current);
		}
	}

	private static int floorMod(long value, int bound) {
		return (int) Math.floorMod(value, (long) bound);
	}

	/**
	 * Package-private rather than private so {@link WorldInterfaceTargetPolicy} can roll against the
	 * same mixer. Two copies of one hash is two things to keep in step for no benefit, and the
	 * target policy's picks have to be reproducible alongside the schedule's, not merely alongside
	 * themselves.
	 */
	static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	private static void requireCombatStage(WorldInterfaceStage stage) {
		if (stage == null || !stage.isCombat()) {
			throw new IllegalArgumentException("Stage must be a combat phase");
		}
	}

	private static void requireSequence(long sequence) {
		if (sequence < 0L) throw new IllegalArgumentException("Sequence cannot be negative");
	}

	public record IntervalBounds(int minimumTicks, int maximumTicks) {
		public IntervalBounds {
			if (minimumTicks < 1 || maximumTicks < minimumTicks) {
				throw new IllegalArgumentException("Invalid attack interval bounds");
			}
		}
	}
}
