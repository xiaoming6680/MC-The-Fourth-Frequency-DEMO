package com.xm.thefourthfrequency.ending;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInterfaceActionSchedulerTest {
	@Test
	void deterministicShuffleNeverRepeatsAdjacentActions() {
		for (WorldInterfaceStage stage : List.of(
				WorldInterfaceStage.PHASE_1,
				WorldInterfaceStage.PHASE_2,
				WorldInterfaceStage.PHASE_3)) {
			WorldInterfaceAction previous = null;
			WorldInterfaceAction previousStateless = null;
			for (long sequence = 0; sequence < 200; sequence++) {
				WorldInterfaceAction selected = WorldInterfaceActionScheduler.nextAction(
						stage, 0x4F52_4947_494EL, sequence, previous);
				assertEquals(selected, WorldInterfaceActionScheduler.nextAction(
						stage, 0x4F52_4947_494EL, sequence, previous));
				if (previous != null) assertNotEquals(previous, selected);
				assertTrue(selected.isUnlockedAt(stage));
				previous = selected;
				WorldInterfaceAction stateless = WorldInterfaceActionScheduler.nextAction(
						stage, 0x4F52_4947_494EL, sequence);
				if (previousStateless != null) assertNotEquals(previousStateless, stateless);
				previousStateless = stateless;
			}
		}
	}

	/**
	 * Every phase keeps an action that needs no exclusive control, and no phase is only exclusives.
	 *
	 * <p>This is what makes the scheduler's scan able to find something on any tick. Exclusive
	 * controls put their target under six hundred ticks of strong-control immunity, and a solo table
	 * <em>is</em> the whole roster - so during that window every exclusive action has nobody it may be
	 * aimed at. If a phase ever unlocked only exclusives, the scan would run out of candidates and the
	 * encounter would go quiet until the immunity expired, which is precisely the stall that was
	 * reported from the second phase.
	 *
	 * <p>Also pins the shape that made the second phase the one it happened in: the first phase has no
	 * exclusive control at all, and the later phases do.
	 */
	@Test
	void everyPhaseKeepsAnActionThatNeedsNoExclusiveControl() {
		for (WorldInterfaceStage stage : WorldInterfaceStage.values()) {
			if (!stage.isCombat()) continue;
			List<WorldInterfaceAction> unlocked = WorldInterfaceAction.unlockedAt(stage);
			assertFalse(unlocked.isEmpty(), stage + " unlocks nothing");
			assertTrue(unlocked.stream().anyMatch(action -> !action.requiresExclusiveControl()),
					stage + " unlocks only exclusive controls, so a table under strong-control"
							+ " immunity has nothing that can be thrown at it");
		}
		assertTrue(WorldInterfaceAction.unlockedAt(WorldInterfaceStage.PHASE_1).stream()
						.noneMatch(WorldInterfaceAction::requiresExclusiveControl),
				"the first phase teaches the fight and takes nothing away");
		assertTrue(WorldInterfaceAction.unlockedAt(WorldInterfaceStage.PHASE_2).stream()
						.anyMatch(WorldInterfaceAction::requiresExclusiveControl),
				"the second phase is where dispossession starts");
	}

	@Test
	void phaseIntervalsStayInsideTheirExactInclusiveRanges() {
		// The escalation across the three phases is the point, and it is steep on purpose. The first
		// phase is where every one of these attacks is met for the first time and a telegraph only
		// teaches anything if there is quiet after it; the third runs a volley lane alongside its
		// schedule and is meant to be continuous. Checked here so neither end drifts by a stray edit.
		assertIntervals(WorldInterfaceStage.PHASE_1, 150, 200);
		assertIntervals(WorldInterfaceStage.PHASE_2, 75, 105);
		assertIntervals(WorldInterfaceStage.PHASE_3, 35, 60);
		// And it must be monotone: no phase may act less often than the one before it.
		for (WorldInterfaceStage stage : new WorldInterfaceStage[]{
				WorldInterfaceStage.PHASE_1, WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3}) {
			if (stage == WorldInterfaceStage.PHASE_1) continue;
			WorldInterfaceStage earlier = stage == WorldInterfaceStage.PHASE_2
					? WorldInterfaceStage.PHASE_1 : WorldInterfaceStage.PHASE_2;
			assertTrue(WorldInterfaceActionScheduler.intervalBounds(stage).maximumTicks()
							< WorldInterfaceActionScheduler.intervalBounds(earlier).minimumTicks(),
					stage + " must act strictly more often than " + earlier);
		}
		// The grab is the one action that cannot land while the storm is at its skyhold ceiling.
		for (WorldInterfaceAction action : WorldInterfaceAction.values()) {
			assertEquals(action != WorldInterfaceAction.GRAB_THROW,
					WorldInterfaceActionScheduler.canStartWhileAloft(action), action.toString());
		}

		int base = WorldInterfaceActionScheduler.baseIntervalTicks(WorldInterfaceStage.PHASE_2, 91L, 4L);
		// The multiplier endpoints track WorldInterfacePolicy: 1.15 with all anchors standing down
		// to 0.85 with all ten broken. Breaking anchors exposes the shell but raises its attack rate.
		assertEquals(Math.round(base * 1.15D),
				WorldInterfaceActionScheduler.scaledIntervalTicks(WorldInterfaceStage.PHASE_2, 91L, 4L, 0));
		assertEquals(Math.round(base * 0.85D),
				WorldInterfaceActionScheduler.scaledIntervalTicks(WorldInterfaceStage.PHASE_2, 91L, 4L, 10));
	}

	@Test
	void exclusiveControlsShareOneLaneAndRespectSixHundredTickTargetImmunity() {
		assertFalse(WorldInterfaceActionScheduler.canStartExclusiveControl(
				WorldInterfaceAction.GRAB_THROW, WorldInterfaceAction.CHARGE_WEAPON_STEAL));
		assertTrue(WorldInterfaceActionScheduler.canStartExclusiveControl(
				WorldInterfaceAction.LASER_SWEEP, WorldInterfaceAction.CHARGE_WEAPON_STEAL));
		assertTrue(WorldInterfaceActionScheduler.canStartAction(
				WorldInterfaceAction.GRAB_THROW, null, 999L, -1L));
		assertFalse(WorldInterfaceActionScheduler.canStartAction(
				WorldInterfaceAction.GRAB_THROW, null, 1_599L, 1_000L));
		assertTrue(WorldInterfaceActionScheduler.canStartAction(
				WorldInterfaceAction.GRAB_THROW, null, 1_600L, 1_000L));
		assertTrue(WorldInterfaceActionScheduler.isStrongControlTargetEligible(
				WorldInterfaceAction.SKY_LANCE, 1L, 1L));
	}

	@Test
	void forcedEvictionHasStrictCooldownAndNeverSelectsIntegratedHost() {
		assertFalse(WorldInterfaceActionScheduler.isForcedEvictionReady(1_000L, -1L, 2));
		assertTrue(WorldInterfaceActionScheduler.isForcedEvictionReady(1_000L, -1L, 3));
		assertFalse(WorldInterfaceActionScheduler.isForcedEvictionReady(4_599L, 1_000L, 8));
		assertTrue(WorldInterfaceActionScheduler.isForcedEvictionReady(4_600L, 1_000L, 8));

		List<UUID> roster = new ArrayList<>();
		for (int value = 1; value <= 8; value++) {
			roster.add(new UUID(0L, value));
		}
		UUID host = roster.get(3);
		List<UUID> first = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, host, 0x4556_4943_54L, 7L);
		Collections.reverse(roster);
		List<UUID> reordered = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, host, 0x4556_4943_54L, 7L);
		assertEquals(3, first.size());
		assertEquals(first, reordered);
		assertFalse(first.contains(host));
	}

	/**
	 * The roster compresses the schedule, monotonically, between stated bounds.
	 *
	 * <p>One attack names one player, so without this a table divides the same cadence among itself
	 * and each member is locked a fraction as often as a solo player would be. The solo case must
	 * come out exactly unchanged - it is the configuration every other number in the fight was tuned
	 * against - and the compression has to stop somewhere, or eight players would be answering a
	 * continuous stream with no quiet in it at all.
	 */
	@Test
	void rosterCompressesTheScheduleWithoutTouchingTheSoloCase() {
		assertEquals(1.0D, WorldInterfaceActionScheduler.rosterDensityMultiplier(1), 1.0E-9D);
		double previous = Double.MAX_VALUE;
		for (int roster = 1; roster <= WorldInterfacePolicy.MAX_ROSTER_SIZE; roster++) {
			double multiplier = WorldInterfaceActionScheduler.rosterDensityMultiplier(roster);
			assertTrue(multiplier <= previous, "a larger table must never wait longer");
			assertTrue(multiplier >= WorldInterfaceActionScheduler.MIN_DENSITY_MULTIPLIER);
			assertTrue(multiplier <= 1.0D);
			previous = multiplier;
		}
		assertEquals(WorldInterfaceActionScheduler.MIN_DENSITY_MULTIPLIER,
				WorldInterfaceActionScheduler.rosterDensityMultiplier(8), 1.0E-9D,
				"a full table is held at the floor rather than allowed to keep compressing");
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfaceActionScheduler.rosterDensityMultiplier(0));
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfaceActionScheduler.rosterDensityMultiplier(9));

		// The solo path is the old arithmetic exactly, so an existing fight is untouched.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.PHASE_1,
				WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3)) {
			for (long sequence = 0L; sequence < 120L; sequence++) {
				for (int anchors : new int[]{0, 5, 10}) {
					assertEquals(
							WorldInterfaceActionScheduler.scaledIntervalTicks(stage, 77L, sequence, anchors),
							WorldInterfaceActionScheduler.scaledIntervalTicks(stage, 77L, sequence, anchors, 1));
				}
			}
		}
	}

	/** However the multipliers stack, the gap never falls below the stated floor. */
	@Test
	void theCompressedScheduleKeepsAFloorUnderIt() {
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.PHASE_1,
				WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3)) {
			for (long sequence = 0L; sequence < 200L; sequence++) {
				int interval = WorldInterfaceActionScheduler.scaledIntervalTicks(
						stage, 0x5F5FL, sequence, WorldInterfacePolicy.TOTAL_ANCHORS, 8);
				assertTrue(interval >= WorldInterfaceActionScheduler.MIN_SCALED_INTERVAL_TICKS,
						stage + " fell to " + interval + " ticks between attacks");
				assertTrue(interval <= WorldInterfaceActionScheduler.scaledIntervalTicks(
								stage, 0x5F5FL, sequence, WorldInterfacePolicy.TOTAL_ANCHORS, 1),
						"a full table must not wait longer than a solo player");
			}
		}
	}

	/**
	 * The second lane widens with the roster and never narrows below what a solo player already got.
	 *
	 * <p>This is the half of the roster correction that adds pressure without taking any telegraph
	 * away: extra volley slots land on people the fight is not already aimed at, where compressing
	 * the schedule makes the same events arrive faster for everybody.
	 */
	@Test
	void theVolleyLaneWidensWithTheRoster() {
		int soloConcurrency = WorldInterfaceActionScheduler.volleyConcurrency(3, 1);
		assertEquals(3, soloConcurrency, "a solo third phase is unchanged");
		int previousConcurrency = soloConcurrency;
		int previousSize = 0;
		for (int roster = 1; roster <= WorldInterfacePolicy.MAX_ROSTER_SIZE; roster++) {
			int concurrency = WorldInterfaceActionScheduler.volleyConcurrency(3, roster);
			assertTrue(concurrency >= previousConcurrency, "concurrency must not fall as a table grows");
			assertTrue(concurrency >= soloConcurrency);
			previousConcurrency = concurrency;

			int smallest = Integer.MAX_VALUE;
			for (long tick = 0L; tick < 400L; tick++) {
				int size = WorldInterfaceActionScheduler.volleySize(0x5EEDL, tick, roster);
				assertTrue(size >= 1, "a volley that opens with nothing is not a volley");
				assertTrue(size <= concurrency + 1, "a volley may not exceed what the lane can hold by"
						+ " more than the roll's own spread");
				assertEquals(size, WorldInterfaceActionScheduler.volleySize(0x5EEDL, tick, roster));
				smallest = Math.min(smallest, size);
			}
			assertTrue(smallest >= previousSize, "the smallest volley must not shrink as a table grows");
			previousSize = smallest;
			// The one-argument form is the solo table, and must stay that way.
			assertEquals(WorldInterfaceActionScheduler.volleySize(0x5EEDL, 17L),
					WorldInterfaceActionScheduler.volleySize(0x5EEDL, 17L, 1));
		}
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfaceActionScheduler.volleySize(0x5EEDL, 1L, 9));
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfaceActionScheduler.volleyConcurrency(3, 0));
	}

	/**
	 * A second eviction reaches for somebody the encounter has not already thrown off the island.
	 *
	 * <p>Eviction is a real disconnect and its own cooldown is six times the strong-control immunity,
	 * so nothing else in the encounter remembers the first one by the time the second comes round. A
	 * table of eight has room for three evictions in a fight, and without this the same player could
	 * take two of them while somebody else took none.
	 */
	@Test
	void repeatEvictionsGoToTheBackOfTheQueue() {
		List<UUID> roster = new ArrayList<>();
		for (int value = 1; value <= 8; value++) roster.add(new UUID(0L, value));
		UUID host = roster.get(3);

		List<UUID> first = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, host, 0x4556_4943_54L, 7L);
		// An encounter that has evicted nobody selects exactly what it always did.
		assertEquals(first, WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, Set.of(), host, 0x4556_4943_54L, 7L));

		Set<UUID> evicted = new HashSet<>(first);
		List<UUID> second = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, evicted, host, 0x4556_4943_54L, 11L);
		assertEquals(3, second.size());
		assertFalse(second.contains(host), "the integrated-server host is never a target");
		assertTrue(Collections.disjoint(second, evicted),
				"a second eviction reused a player while untouched ones were available");

		// Keep going until the pool is exhausted; everyone eligible is taken before anyone repeats.
		evicted.addAll(second);
		List<UUID> third = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, evicted, host, 0x4556_4943_54L, 19L);
		assertEquals(1, third.stream().filter(id -> !evicted.contains(id)).count(),
				"the one remaining untouched player must be taken before any repeat");
		// Seven eligible players, three per eviction: the fourth has no choice but to repeat, and
		// still must not reach for the host.
		evicted.addAll(third);
		List<UUID> fourth = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
				roster, evicted, host, 0x4556_4943_54L, 23L);
		assertEquals(3, fourth.size());
		assertFalse(fourth.contains(host));
		assertEquals(3, new HashSet<>(fourth).size(), "a single eviction may not name anyone twice");
	}

	private static void assertIntervals(WorldInterfaceStage stage, int minimum, int maximum) {
		assertEquals(minimum, WorldInterfaceActionScheduler.intervalBounds(stage).minimumTicks());
		assertEquals(maximum, WorldInterfaceActionScheduler.intervalBounds(stage).maximumTicks());
		for (long sequence = 0L; sequence < 500L; sequence++) {
			int interval = WorldInterfaceActionScheduler.baseIntervalTicks(stage, 55L, sequence);
			assertTrue(interval >= minimum && interval <= maximum);
			assertEquals(interval, WorldInterfaceActionScheduler.baseIntervalTicks(stage, 55L, sequence));
		}
	}

	/**
	 * The gaze sweep is rationed per player, not per encounter.
	 *
	 * <p>Nine slots on the floor is a shock worth having once and a chore worth having never. Every
	 * exclusive action already grants six hundred ticks of strong-control immunity, which leaves room
	 * to do this to the same person ten times in one fight - so the sweep carries the eviction's
	 * three-minute cooldown, applied per player because that is who pays for it.
	 *
	 * <p>The frequency falls out of it rather than being tuned separately: the pick is a shuffled deck
	 * where every action comes up once per cycle, so there is no weight to lower, and a candidate the
	 * cooldown has left with nobody to aim at is skipped by the scan like any other ineligible one.
	 */
	@Test
	void theHotbarSweepIsRationedFarMoreTightlyThanOrdinaryExclusiveControl() {
		assertEquals(3_600, WorldInterfaceActionScheduler.HOTBAR_SWEEP_COOLDOWN_TICKS,
				"three minutes, matching the eviction it is paired with in cost");
		assertTrue(WorldInterfaceActionScheduler.HOTBAR_SWEEP_COOLDOWN_TICKS
						> WorldInterfaceActionScheduler.STRONG_CONTROL_IMMUNITY_TICKS,
				"the shared strong-control immunity is what was too short in the first place");
		// One fight is 12000 ticks, so the ceiling per player is under four - and in practice fewer,
		// because the deck has to land on it while somebody is off cooldown.
		assertTrue(WorldInterfacePolicy.COLLAPSE_DURATION_TICKS
						/ WorldInterfaceActionScheduler.HOTBAR_SWEEP_COOLDOWN_TICKS <= 4,
				"a whole fight must not have room to sweep one player more than a handful of times");
		// Still an exclusive control, so it keeps the ordinary immunity underneath the long cooldown.
		assertTrue(WorldInterfaceAction.GAZE_HOTBAR_CLEAR.requiresExclusiveControl());
	}
}
