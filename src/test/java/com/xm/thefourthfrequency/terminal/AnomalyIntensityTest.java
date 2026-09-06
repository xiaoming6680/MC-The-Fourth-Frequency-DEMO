package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.pursuit.PursuitActivityProof;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AnomalyIntensityTest {
	@Test
	void heatReachesOneHundredAfterFifteenOnlineMinutes() {
		assertEquals(0, AnomalyIntensity.heatPercent(0));
		assertEquals(50, AnomalyIntensity.heatPercent(AnomalyIntensity.HEAT_RAMP_TICKS / 2));
		assertEquals(100, AnomalyIntensity.heatPercent(AnomalyIntensity.HEAT_RAMP_TICKS));
		assertEquals(100, AnomalyIntensity.heatPercent(Long.MAX_VALUE));
	}

	@Test
	void progressionCeilingUsesBroadRoutesAndMainlineMilestones() {
		assertEquals(0, AnomalyIntensity.progressionCeiling(false, 1, 0, 3,
				PursuitActivityProof.MINING.mask(), 0L, true));
		assertEquals(1, AnomalyIntensity.progressionCeiling(true, 0, 0, 0, 0, 0L, false));
		assertEquals(2, AnomalyIntensity.progressionCeiling(true, 0, 0, 0,
				PursuitActivityProof.EXPLORATION.mask(), 0L, false));
		assertEquals(3, AnomalyIntensity.progressionCeiling(true, 0,
				SurvivalMilestone.IRON.mask(), 0, 0, 0L, false));
		assertEquals(4, AnomalyIntensity.progressionCeiling(true, 0,
				SurvivalMilestone.RETURNED_NETHER.mask() | SurvivalMilestone.COLLECTED_BLAZE_RODS.mask(),
				0, 0, 0L, false));
		assertEquals(5, AnomalyIntensity.progressionCeiling(true, 0, 0, 1, 0, 0L, false));
	}

	@Test
	void stageCatchesUpOneStepOnlyAfterExposureAndTwoSuccesses() {
		assertEquals(1, AnomalyIntensity.progressedStage(0, 5, 0L, 0));
		assertEquals(4, AnomalyIntensity.progressedStage(4, 5,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS - 1, 2));
		assertEquals(4, AnomalyIntensity.progressedStage(4, 5,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS, 1));
		assertEquals(5, AnomalyIntensity.progressedStage(4, 5,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS, 2));
	}

	@Test
	void laggingStageUsesCompressedExposureUntilItCatchesUp() {
		assertEquals(AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS,
				AnomalyIntensity.requiredExposureTicks(4, 5));
		assertEquals(AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS / 2,
				AnomalyIntensity.requiredExposureTicks(1, 3));
		assertEquals(2, AnomalyIntensity.progressedStage(1, 5,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS / 2, 2));
		assertEquals(1, AnomalyIntensity.progressedStage(1, 5,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS / 2 - 1, 2));
		assertEquals(1, AnomalyIntensity.progressedStage(1, 2,
				AnomalyIntensity.MIN_STAGE_EXPOSURE_TICKS / 2, 2));
	}

	@Test
	void intervalsUseTheApprovedSlowBoundsWithoutHeatCompression() {
		assertEquals(5 * 60 * 20L, shortest(1, true));
		assertEquals(8 * 60 * 20L, longest(1, true));
		assertEquals(10 * 60 * 20L, shortest(1, false));
		assertEquals(16 * 60 * 20L, longest(1, false));
		assertEquals(9 * 60 * 20L, shortest(3, false));
		assertEquals(14 * 60 * 20L, longest(3, false));
		assertEquals(7 * 60 * 20L, shortest(5, false));
		assertEquals(12 * 60 * 20L, longest(5, false));
		// Heat is metered and displayed per player but deliberately does not compress the interval.
		assertEquals(AnomalyIntensity.intervalTicks(5, 0, 17, false),
				AnomalyIntensity.intervalTicks(5, 100, 17, false));
	}

	/**
	 * The endpoint the pacing complaint was actually about: however unlucky the roll, the world gets
	 * some minutes back. Asserted as a floor across every stage rather than only at stage 5, so a
	 * later re-tune cannot reintroduce a tight stage somewhere in the middle of the ramp.
	 */
	@Test
	void noStageEverSchedulesUnderSevenMinutes() {
		for (int stage = 1; stage <= 5; stage++) {
			assertTrue(shortest(stage, false) >= 7 * 60 * 20L, "stage " + stage);
		}
	}

	/** Later stages still arrive sooner than earlier ones - the ramp survives the lift. */
	@Test
	void intervalsStayMonotonicAcrossTheRamp() {
		for (int stage = 2; stage <= 5; stage++) {
			assertTrue(shortest(stage, false) <= shortest(stage - 1, false), "stage " + stage);
			assertTrue(longest(stage, false) <= longest(stage - 1, false), "stage " + stage);
		}
	}

	/**
	 * A grace period is a minimum, not a reschedule. Relogging or stepping through a nether portal
	 * used to overwrite a long pending interval with ninety seconds, which made travelling the
	 * fastest way to be visited.
	 */
	@Test
	void graceDefersButNeverAdvancesAScheduledAnomaly() {
		assertEquals(9_000L, AnomalyIntensity.graced(9_000L, 3_000L));
		assertEquals(9_000L, AnomalyIntensity.graced(9_000L, 9_000L));
		assertEquals(12_000L, AnomalyIntensity.graced(9_000L, 12_000L));
		assertEquals(0L, AnomalyIntensity.graced(0L, 12_000L));
		assertEquals(0L, AnomalyIntensity.graced(-1L, 12_000L));
	}

	/**
	 * The Nether is the pressure dimension, and the assertion is the relationship rather than the
	 * numbers: every stage arrives sooner there than it does in the overworld, and no stage there
	 * drops under four minutes. A trip for rods is short, and the overworld cadence spends most of
	 * one waiting.
	 */
	@Test
	void theNetherRunsShorterThanTheOverworldAtEveryStage() {
		for (int stage = 1; stage <= 5; stage++) {
			assertTrue(shortest(stage, false, true) < shortest(stage, false, false), "stage " + stage);
			assertTrue(longest(stage, false, true) < longest(stage, false, false), "stage " + stage);
			assertTrue(shortest(stage, false, true) >= 4 * 60 * 20L, "stage " + stage);
			assertTrue(longest(stage, false, true) <= 10 * 60 * 20L, "stage " + stage);
		}
	}

	/** The ramp is the same table moved down, not a flat number that flattens the progression. */
	@Test
	void theNetherKeepsTheStageRamp() {
		for (int stage = 2; stage <= 5; stage++) {
			assertTrue(shortest(stage, false, true) <= shortest(stage - 1, false, true), "stage " + stage);
			assertTrue(longest(stage, false, true) <= longest(stage - 1, false, true), "stage " + stage);
		}
	}

	/**
	 * The opening interval belongs to a player who has never had an anomaly, and where they happen
	 * to be standing at that moment is not a reason to give them a different one.
	 */
	@Test
	void theFirstIntervalIgnoresTheDimension() {
		assertEquals(shortest(3, true, false), shortest(3, true, true));
		assertEquals(longest(3, true, false), longest(3, true, true));
	}

	/** A refusal costs half a minute, the same figure a failed HIM placement costs. */
	@Test
	void aFailedDrawCostsThirtySeconds() {
		assertEquals(30L * 20L, AnomalyIntensity.FAILED_TRIGGER_RETRY_TICKS);
	}

	private static long shortest(int tier, boolean first) {
		return sweep(tier, first, true, false);
	}

	private static long longest(int tier, boolean first) {
		return sweep(tier, first, false, false);
	}

	private static long shortest(int tier, boolean first, boolean pressure) {
		return sweep(tier, first, true, pressure);
	}

	private static long longest(int tier, boolean first, boolean pressure) {
		return sweep(tier, first, false, pressure);
	}

	/**
	 * Walks a full hour of second-resolution offsets so the reported bound is the one the selector
	 * can actually produce, rather than whichever value a single hand-picked seed happened to land on.
	 */
	private static long sweep(int tier, boolean first, boolean wantShortest, boolean pressure) {
		long best = wantShortest ? Long.MAX_VALUE : Long.MIN_VALUE;
		for (int randomValue = 0; randomValue <= 60 * 60; randomValue++) {
			long ticks = AnomalyIntensity.intervalTicks(tier, 0, randomValue, first, pressure);
			best = wantShortest ? Math.min(best, ticks) : Math.max(best, ticks);
		}
		return best;
	}

	@Test
	void strongCooldownsStayBounded() {
		assertEquals(0L, AnomalyIntensity.strongCooldownTicks(3, 1));
		assertTrue(between(AnomalyIntensity.strongCooldownTicks(4, 2), 20 * 60, 30 * 60));
		assertTrue(between(AnomalyIntensity.strongCooldownTicks(5, 3), 20 * 60, 30 * 60));
	}

	private static boolean between(long ticks, int minimumSeconds, int maximumSeconds) {
		return ticks >= minimumSeconds * 20L && ticks <= maximumSeconds * 20L;
	}
}
