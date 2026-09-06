package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInterfacePolicyTest {
	private static final double EPSILON = 0.000_000_1D;

	/**
	 * The pool per roster size, spelled out rather than recomputed. Restating the formula here would
	 * only assert that the policy agrees with itself; these eight numbers are the balance decision.
	 */
	private static final double[] EXPECTED_HEALTH_BY_ROSTER_SIZE = {
			600.0D, 900.0D, 1_200.0D, 1_500.0D, 1_800.0D, 2_100.0D, 2_400.0D, 2_700.0D
	};

	@Test
	void frozenRosterAddsHalfABasePoolPerPlayerPastTheFirst() {
		for (int players = 1; players <= 8; players++) {
			assertEquals(EXPECTED_HEALTH_BY_ROSTER_SIZE[players - 1],
					WorldInterfacePolicy.maxHealth(players), EPSILON);
		}
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.maxHealth(0));
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.maxHealth(9));
	}

	@Test
	void authoritativeAnchorFormulasMatchEveryEndpoint() {
		double maximumHealth = WorldInterfacePolicy.maxHealth(8);
		for (int destroyed = 0; destroyed <= 10; destroyed++) {
			assertEquals(0.60D + 0.04D * destroyed,
					WorldInterfacePolicy.damageTakenMultiplier(destroyed), EPSILON);
			assertEquals(1.15D - 0.03D * destroyed,
					WorldInterfacePolicy.attackCooldownMultiplier(destroyed), EPSILON);
		}
		assertEquals(maximumHealth * 0.00020D * 10 / 20.0D,
				WorldInterfacePolicy.healingPerTick(maximumHealth, 10), EPSILON);
		assertEquals(0.0D, WorldInterfacePolicy.healingPerTick(maximumHealth, 0), EPSILON);
		assertEquals(60.0D, WorldInterfacePolicy.adjustedIncomingDamage(100.0D, 0), EPSILON);
		assertEquals(100.0D, WorldInterfacePolicy.adjustedIncomingDamage(100.0D, 10), EPSILON);
		assertEquals(1.0D, WorldInterfacePolicy.damageTakenMultiplier(10), EPSILON);
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfacePolicy.damageTakenMultiplier(11));
	}

	/**
	 * An arrow is worth two and a half times its own roll, and the anchor wall still applies to it.
	 *
	 * <p>The arena keeps the body eight to sixteen blocks over a player's head on purpose, so a bow
	 * is the only weapon that reaches most of the interface - and it was being paid the melee rate.
	 * Against an eight-player pool with every anchor standing the regeneration is above five
	 * points a second, which is most of what a bow could put out; the multiplier is what makes
	 * shooting the thing a way of killing it rather than a way of keeping up with it.
	 *
	 * <p>Order matters and is pinned here: the weapon multiplier goes on first and the anchor wall
	 * scales the result, so tearing anchors down is worth the same proportion to an archer as to
	 * anyone else.
	 */
	@Test
	void arrowsAreWorthMoreThanMeleeAndTheAnchorWallStillApplies() {
		assertEquals(2.5D, WorldInterfacePolicy.arrowDamageMultiplier(), EPSILON);
		assertEquals(6.0D, WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 0, false), EPSILON);
		assertEquals(15.0D, WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 0, true), EPSILON);
		// With every anchor down both damage types reach their full, pre-wall values.
		assertEquals(10.0D, WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 10, false), EPSILON);
		assertEquals(25.0D, WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 10, true), EPSILON);
		// The two-argument form is melee, so no existing caller silently gains the bonus.
		assertEquals(WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 3, false),
				WorldInterfacePolicy.adjustedIncomingDamage(10.0D, 3), EPSILON);
		// A single Power V arrow has to outrun a full board of anchors healing an eight-player pool.
		double poolHealingPerSecond = WorldInterfacePolicy.healingPerTick(
				WorldInterfacePolicy.maxHealth(8), 10) * 20.0D;
		assertTrue(WorldInterfacePolicy.adjustedIncomingDamage(9.0D, 0, true) > poolHealingPerSecond,
				"an arrow must be worth more than a second of regeneration");
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfacePolicy.adjustedIncomingDamage(-1.0D, 0, true));
	}

	@Test
	void liveAnchorsProjectInclusiveEightBlockDamageShelters() {
		assertTrue(WorldInterfacePolicy.insideStabilityField(8.0D, 0.0D, 0.0D, 0.0D));
		assertTrue(WorldInterfacePolicy.insideStabilityField(3.0D, 4.0D, 0.0D, 0.0D));
		assertFalse(WorldInterfacePolicy.insideStabilityField(8.000_001D, 0.0D, 0.0D, 0.0D));
		assertEquals(8.0F, WorldInterfacePolicy.adjustedPlayerDamage(10.0F, true), EPSILON);
		assertEquals(10.0F, WorldInterfacePolicy.adjustedPlayerDamage(10.0F, false), EPSILON);
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfacePolicy.insideStabilityField(Double.NaN, 0.0D, 0.0D, 0.0D));
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfacePolicy.adjustedPlayerDamage(-1.0F, true));
	}

	@Test
	void theCollapseClockIsTenFixedMinutesThatNoAnchorCanShorten() {
		assertEquals(12_000, WorldInterfacePolicy.COLLAPSE_DURATION_TICKS);
		assertEquals(0.0D, WorldInterfacePolicy.collapseProgress(0L), EPSILON);
		assertEquals(0.50D, WorldInterfacePolicy.collapseProgress(6_000L), EPSILON);
		assertEquals(1.0D, WorldInterfacePolicy.collapseProgress(12_000L), EPSILON);
		assertEquals(1.0D, WorldInterfacePolicy.collapseProgress(15_000L), EPSILON);
		assertEquals(12_000, WorldInterfacePolicy.remainingCollapseTicks(0L));
		assertEquals(0, WorldInterfacePolicy.remainingCollapseTicks(12_000L));
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.collapseProgress(-1L));
	}

	/**
	 * Three minutes, and the altar snapshot's bound is the same number.
	 *
	 * <p>The payload range-checks the remainder it carries against this constant, so a window
	 * lengthened here without the payload agreeing would start throwing on the wire the first time a
	 * ritual was opened - at the altar, in front of everybody, rather than in a test.</p>
	 */
	@Test
	void theRitualWindowIsThreeMinutesAndTheWireAgrees() {
		assertEquals(3 * 60 * 20, WorldInterfacePolicy.RITUAL_WINDOW_TICKS);
		assertEquals(WorldInterfacePolicy.RITUAL_WINDOW_TICKS,
				new AltarSnapshotS2C(WorldInterfaceProtocol.VERSION, java.util.UUID.randomUUID(), 0L, 1L,
						WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(), BlockPos.ZERO,
						java.util.List.of(), java.util.List.of(), 0, false,
						WorldInterfaceProtocol.AltarStatus.WAITING.wireId(),
						WorldInterfacePolicy.RITUAL_WINDOW_TICKS, false).windowRemainingTicks());
		assertThrows(IllegalArgumentException.class, () -> new AltarSnapshotS2C(
				WorldInterfaceProtocol.VERSION, java.util.UUID.randomUUID(), 0L, 1L,
				WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(), BlockPos.ZERO,
				java.util.List.of(), java.util.List.of(), 0, false,
				WorldInterfaceProtocol.AltarStatus.WAITING.wireId(),
				WorldInterfacePolicy.RITUAL_WINDOW_TICKS + 1, false));
	}

	/**
	 * The client draws the countdown from its own copy of this figure, so a change made on one side
	 * only would leave the HUD clock disagreeing with the tick that actually ends the fight - and the
	 * disagreement grows with the size of the edit, so it is exactly the kind that ships.
	 */
	@Test
	void theClientAndServerCollapseClocksAreTheSameLength() {
		assertEquals(WorldInterfacePolicy.COLLAPSE_DURATION_TICKS,
				WorldInterfaceProtocol.COLLAPSE_DURATION_TICKS);
	}

	@Test
	void combatErosionStaysCleanEarlyThenAcceleratesWithTheCollapseTimer() {
		// Nothing outside the fight erodes, and the opening minutes stay clean so the island only
		// starts failing once the deadline is genuinely close.
		// Expressed as fractions of the clock rather than as raw tick counts, so lengthening the
		// encounter moves these with it instead of silently re-pointing them at different moments.
		long full = WorldInterfacePolicy.COLLAPSE_DURATION_TICKS;
		long beforeStart = Math.round(full * (WorldInterfacePolicy.EROSION_START_COLLAPSE - 0.1D));
		assertEquals(0.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.SUMMONING, beforeStart, -1L, 0L, 120), EPSILON);
		assertEquals(0.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.PHASE_1, 0L, -1L, 0L, 120), EPSILON);
		assertEquals(0.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.PHASE_1, beforeStart, -1L, 0L, 120), EPSILON);

		// Past the start point it climbs, and is still climbing right up to the deadline.
		float quarter = WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.PHASE_2, Math.round(full * 0.60D), -1L, 0L, 120);
		float late = WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.PHASE_3, Math.round(full * 0.90D), -1L, 0L, 120);
		assertTrue(quarter > 0.0F);
		assertTrue(late > quarter);
		assertEquals(WorldInterfacePolicy.COMBAT_EROSION_CEILING,
				WorldInterfacePolicy.presentationErosionProgress(
						WorldInterfaceStage.PHASE_3, full, -1L, 0L, 120), EPSILON);
	}

	@Test
	void losingContinuesFromTheCombatCeilingWhileWinningRestoresTheWorld() {
		long full = WorldInterfacePolicy.COLLAPSE_DURATION_TICKS;
		// Failure picks up where combat left off rather than snapping back to zero first.
		assertEquals(WorldInterfacePolicy.COMBAT_EROSION_CEILING,
				WorldInterfacePolicy.presentationErosionProgress(
						WorldInterfaceStage.FAILURE_RESOLUTION, full, 1_000L, 1_000L, 120), EPSILON);
		assertEquals(WorldInterfacePolicy.COMBAT_EROSION_CEILING,
				WorldInterfacePolicy.presentationErosionProgress(
						WorldInterfaceStage.FAILURE_RESOLUTION, full, -1L, 1_120L, 120), EPSILON);
		float half = WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.FAILURE_RESOLUTION, full, 1_000L, 1_060L, 120);
		assertTrue(half > WorldInterfacePolicy.COMBAT_EROSION_CEILING && half < 1.0F);
		assertEquals(1.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.FAILURE_RESOLUTION, full, 1_000L, 1_120L, 120), EPSILON);
		assertEquals(1.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.FAILURE_RESOLUTION, full, 1_000L, 2_000L, 120), EPSILON);

		// A win reads the same combat curve, because the caller hands it a clock it has already wound
		// back. So the erosion the players are standing in lifts at the rate the repair advances
		// rather than blinking clean, and it is genuinely gone once the clock reaches zero.
		assertEquals(WorldInterfacePolicy.COMBAT_EROSION_CEILING,
				WorldInterfacePolicy.presentationErosionProgress(
						WorldInterfaceStage.SUCCESS_RESOLUTION, full, 1_000L, 1_000L, 120), EPSILON);
		assertEquals(0.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.SUCCESS_RESOLUTION, 0L, 1_000L, 1_120L, 120), EPSILON);
		// Once the exit is open the repair is behind us either way.
		assertEquals(0.0F, WorldInterfacePolicy.presentationErosionProgress(
				WorldInterfaceStage.PORTAL_OPEN, full, 1_000L, 1_120L, 120), EPSILON);
	}

	/**
	 * The repair is one fraction driving three things - the terrain sweep, the rail running
	 * backwards, and the erosion lifting. If it is not monotonic and does not actually reach zero,
	 * the island keeps a permanent stripe of corruption the players cannot do anything about.
	 */
	@Test
	void theRepairRunsTheCollapseClockBackToZeroAndStaysThere() {
		long defeat = WorldInterfacePolicy.COLLAPSE_DURATION_TICKS;
		assertEquals(0.0D, WorldInterfacePolicy.repairFraction(-5L), EPSILON);
		assertEquals(0.0D, WorldInterfacePolicy.repairFraction(0L), EPSILON);
		assertEquals(1.0D, WorldInterfacePolicy.repairFraction(
				WorldInterfacePolicy.REPAIR_DURATION_TICKS), EPSILON);
		assertEquals(1.0D, WorldInterfacePolicy.repairFraction(
				WorldInterfacePolicy.REPAIR_DURATION_TICKS * 4L), EPSILON);

		assertEquals(defeat, WorldInterfacePolicy.repairedElapsedTicks(defeat, 0L));
		assertEquals(0L, WorldInterfacePolicy.repairedElapsedTicks(defeat,
				WorldInterfacePolicy.REPAIR_DURATION_TICKS));
		assertEquals(0L, WorldInterfacePolicy.repairedElapsedTicks(defeat,
				WorldInterfacePolicy.REPAIR_DURATION_TICKS * 10L),
				"a resolution that overruns must not drive the rail negative");

		long previous = Long.MAX_VALUE;
		for (long age = 0L; age <= WorldInterfacePolicy.REPAIR_DURATION_TICKS; age += 5L) {
			long now = WorldInterfacePolicy.repairedElapsedTicks(defeat, age);
			assertTrue(now <= previous, "the rail must never run forwards again at age " + age);
			previous = now;
		}

		// A table that won early has less to undo, and still ends at nothing left.
		long early = Math.round(defeat * 0.30D);
		assertEquals(early, WorldInterfacePolicy.repairedElapsedTicks(early, 0L));
		assertEquals(0L, WorldInterfacePolicy.repairedElapsedTicks(early,
				WorldInterfacePolicy.REPAIR_DURATION_TICKS));
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfacePolicy.repairedElapsedTicks(-1L, 0L));
	}

	/**
	 * The wind-back covers the whole tail of a won encounter, not the one stage it starts in.
	 *
	 * <p>The test above pinned the curve and the curve was never wrong. What was wrong was where it
	 * got applied: only at {@code SUCCESS_RESOLUTION}. {@link WorldInterfacePolicy#REPAIR_DURATION_TICKS}
	 * is 500 and the exit opens on resolution tick 500, so the stage advanced to {@code PORTAL_OPEN}
	 * on exactly the tick the repair reached zero - and the projection fell straight back to the raw
	 * stored clock. The rail undid the damage and then re-applied all of it in one frame.</p>
	 */
	@Test
	void theRepairedReadoutSurvivesTheExitOpening() {
		// Where it must be showing the repair.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.SUCCESS_RESOLUTION,
				WorldInterfaceStage.PORTAL_OPEN, WorldInterfaceStage.COMPLETE)) {
			assertTrue(WorldInterfacePolicy.repairsCollapseReadout(stage, true),
					() -> "a won encounter must keep the repaired readout at " + stage);
		}
		// A loss reaches PORTAL_OPEN too, and keeps the island the countdown left it.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.FAILURE_RESOLUTION,
				WorldInterfaceStage.PORTAL_OPEN, WorldInterfaceStage.COMPLETE)) {
			assertFalse(WorldInterfacePolicy.repairsCollapseReadout(stage, false),
					() -> "a lost encounter must not repair its readout at " + stage);
		}
		// Nothing before the resolution: during the fight the rail is a deadline, not a repair.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.UNPREPARED,
				WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS,
				WorldInterfaceStage.SUMMONING, WorldInterfaceStage.PHASE_1,
				WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3)) {
			assertFalse(WorldInterfacePolicy.repairsCollapseReadout(stage, true),
					() -> "the rail must still be a deadline at " + stage);
		}
		assertFalse(WorldInterfacePolicy.repairsCollapseReadout(null, true));

		// The seam itself: the readout at the tick the exit opens must not be higher than the tick
		// before it. This is the assertion the old shape could not have passed.
		long defeat = WorldInterfacePolicy.COLLAPSE_DURATION_TICKS;
		long atRepairEnd = projectedReadout(WorldInterfaceStage.SUCCESS_RESOLUTION, defeat,
				WorldInterfacePolicy.REPAIR_DURATION_TICKS - 1L);
		long afterExitOpens = projectedReadout(WorldInterfaceStage.PORTAL_OPEN, defeat,
				WorldInterfacePolicy.REPAIR_DURATION_TICKS);
		assertTrue(afterExitOpens <= atRepairEnd,
				() -> "the rail jumped back to " + afterExitOpens + " when the exit opened");
		assertEquals(0L, afterExitOpens, "the repair has finished by the time the exit opens");
	}

	/** The projection the encounter puts on the wire, in the same shape the service builds it. */
	private static long projectedReadout(WorldInterfaceStage stage, long elapsed, long resolutionAge) {
		return WorldInterfacePolicy.repairsCollapseReadout(stage, true)
				? WorldInterfacePolicy.repairedElapsedTicks(elapsed, resolutionAge)
				: elapsed;
	}

	@Test
	void timerOnlyAdvancesWhenAFrozenRosterMemberIsOnlineAnywhere() {
		assertFalse(WorldInterfacePolicy.timerAdvances(8, 0));
		assertTrue(WorldInterfacePolicy.timerAdvances(8, 1));
		assertTrue(WorldInterfacePolicy.timerAdvances(8, 8));
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.timerAdvances(3, 4));
	}

	@Test
	void timeoutWinsBeforeLethalDamageOnTheSameTick() {
		// The boundary itself, not the number it currently sits at: what is being pinned is that the
		// last tick before the deadline can still be won and the deadline tick cannot.
		long deadline = WorldInterfacePolicy.COLLAPSE_DURATION_TICKS;
		assertEquals(WorldInterfacePolicy.TickVerdict.ONGOING,
				WorldInterfacePolicy.resolveTick(deadline - 1L, false));
		assertEquals(WorldInterfacePolicy.TickVerdict.SUCCESS,
				WorldInterfacePolicy.resolveTick(deadline - 1L, true));
		assertEquals(WorldInterfacePolicy.TickVerdict.FAILURE,
				WorldInterfacePolicy.resolveTick(deadline, false));
		assertEquals(WorldInterfacePolicy.TickVerdict.FAILURE,
				WorldInterfacePolicy.resolveTick(deadline, true));
	}

	@Test
	void forcedEvictionAndTerrainBudgetsUseExactCeilAndCaps() {
		assertEquals(0, WorldInterfacePolicy.forcedEvictionTargetCount(0));
		assertEquals(0, WorldInterfacePolicy.forcedEvictionTargetCount(2));
		assertEquals(1, WorldInterfacePolicy.forcedEvictionTargetCount(3));
		assertEquals(2, WorldInterfacePolicy.forcedEvictionTargetCount(4));
		assertEquals(3, WorldInterfacePolicy.forcedEvictionTargetCount(8));
		assertEquals(32, WorldInterfacePolicy.terrainEditBudgetThisTick(0));
		assertEquals(3, WorldInterfacePolicy.terrainEditBudgetThisTick(8_189));
		assertEquals(0, WorldInterfacePolicy.terrainEditBudgetThisTick(8_192));
	}

	/**
	 * The chase answers the table, not whoever happens to be closest to it.
	 *
	 * <p>A solo player has to come out exactly where they stand - that is the case the standoff, the
	 * hover heights and the turn rate were all tuned against, and none of them may shift because a
	 * multiplayer rule was added underneath.
	 */
	@Test
	void attentionCentroidFollowsOnePlayerExactlyAndSpreadsAcrossSeveral() {
		assertArrayEquals(new double[]{12.0D, -30.0D},
				WorldInterfacePolicy.attentionCentroid(new double[]{12.0D}, new double[]{-30.0D},
						0.0D, 0.0D),
				EPSILON, "a solo table is followed exactly as before");

		// Two players either side of the origin, equally far: the station lands between them.
		assertArrayEquals(new double[]{0.0D, 0.0D},
				WorldInterfacePolicy.attentionCentroid(new double[]{-20.0D, 20.0D},
						new double[]{0.0D, 0.0D}, 0.0D, 0.0D), EPSILON);

		// A player at melee range and a sniper sixty blocks out. The body stays with the near one,
		// but is visibly pulled - which is the whole point: it is no longer parked on one head.
		double[] mixed = WorldInterfacePolicy.attentionCentroid(new double[]{0.0D, 60.0D},
				new double[]{0.0D, 0.0D}, 0.0D, 0.0D);
		assertTrue(mixed[0] > 0.0D, "the far player must move the station at all");
		assertTrue(mixed[0] < 30.0D, "the near player must still dominate");
		assertEquals(0.0D, mixed[1], EPSILON);

		// Weighting is by distance from the storm, so the same table pulls differently depending on
		// where the body already is.
		double[] fromFar = WorldInterfacePolicy.attentionCentroid(new double[]{0.0D, 60.0D},
				new double[]{0.0D, 0.0D}, 60.0D, 0.0D);
		assertTrue(fromFar[0] > mixed[0],
				"approaching from the far player's side must weight that player more");

		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.attentionCentroid(
				new double[]{1.0D, 2.0D}, new double[]{1.0D}, 0.0D, 0.0D));
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.attentionCentroid(
				new double[0], new double[0], 0.0D, 0.0D));
		assertThrows(IllegalArgumentException.class, () -> WorldInterfacePolicy.attentionCentroid(
				new double[]{Double.NaN}, new double[]{0.0D}, 0.0D, 0.0D));
	}
}
