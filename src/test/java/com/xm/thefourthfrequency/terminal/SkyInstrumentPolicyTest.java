package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkyInstrumentPolicyTest {
	private static final int RED_HORIZON_TICKS = 800;
	private static final int METRIC_DRIFT_TICKS = 3_600;

	@Test
	void onlyTheTwoSkyAnomaliesDisturbThisPage() {
		assertTrue(SkyInstrumentPolicy.isSkyAnomaly("red_horizon"));
		assertTrue(SkyInstrumentPolicy.isSkyAnomaly("metric_drift"));
		assertFalse(SkyInstrumentPolicy.isSkyAnomaly("silent_world"));
		assertFalse(SkyInstrumentPolicy.isSkyAnomaly(null));
		// An unrelated anomaly must leave the weather tool completely alone.
		assertEquals(0.0F, SkyInstrumentPolicy.instability("door_cascade", 40, 40, 80));
		assertEquals(0.0F, SkyInstrumentPolicy.instability(null, 40, 40, 80));
		assertEquals(0.0F, SkyInstrumentPolicy.instability("red_horizon", 0, 0, 0));
	}

	@Test
	void bothEndsOfAnAnomalyReturnTheInstrumentToClean() {
		assertEquals(0.0F, SkyInstrumentPolicy.instability(
				"red_horizon", 0, RED_HORIZON_TICKS, RED_HORIZON_TICKS));
		assertEquals(0.0F, SkyInstrumentPolicy.instability(
				"red_horizon", RED_HORIZON_TICKS, 0, RED_HORIZON_TICKS));
		assertEquals(0, SkyInstrumentPolicy.stage(0.0F, 0.0D));
	}

	/**
	 * Under a normal sky the terminal never blinks. This is the property the whole presentation
	 * borrows its credibility from: a dropout means something precisely because it cannot happen
	 * on its own.
	 */
	@Test
	void aCleanSkyNeverBlinksTheActionableLine() {
		for (int tick = 0; tick < 2_000; tick++) {
			assertFalse(SkyInstrumentPolicy.readoutLost(tick, 0.0F),
					"the weather line dropped out with no anomaly running, at tick " + tick);
		}
		assertFalse(SkyInstrumentPolicy.burstActive(37.0D, 0.0F));
		assertEquals(0, SkyInstrumentPolicy.errorLineCount(SkyInstrumentPolicy.stage(0.0F, 0.0D), 900.0D));
		assertEquals(0, SkyInstrumentPolicy.tornRowShift(3, 44.0D, 0, 304));
	}

	/**
	 * The horizon channel is the early warning: it is already swinging across a fifth of its scale
	 * while the dome overhead - the part of the sky someone standing in a forest actually sees -
	 * has barely moved.
	 */
	@Test
	void theHorizonChannelLeadsTheDomeThroughoutARedHorizon() {
		boolean foundLead = false;
		for (int elapsed = 1; elapsed < RED_HORIZON_TICKS; elapsed++) {
			int remaining = RED_HORIZON_TICKS - elapsed;
			float horizon = SkyInstrumentPolicy.horizonShare(elapsed, remaining, RED_HORIZON_TICKS);
			float zenith = SkyInstrumentPolicy.zenithShare(elapsed, remaining, RED_HORIZON_TICKS);
			assertTrue(zenith < horizon,
					"the dome matched or beat the horizon at tick " + elapsed);
			if (horizon >= 0.20F && zenith <= 0.12F) foundLead = true;
		}
		assertTrue(foundLead, "no tick where the horizon reads high while the dome is still quiet");
	}

	/**
	 * metric_drift runs for minutes and is meant to stay deniable. Capping its envelope below
	 * the stage-2 threshold makes "no tearing, no flood, ever" structural rather than a tuning
	 * choice someone can undo by nudging a constant.
	 */
	@Test
	void aSustainedAnomalyNeverTearsOrFloods() {
		for (int elapsed = 0; elapsed <= METRIC_DRIFT_TICKS; elapsed += 7) {
			int remaining = METRIC_DRIFT_TICKS - elapsed;
			float instability = SkyInstrumentPolicy.instability(
					"metric_drift", elapsed, remaining, METRIC_DRIFT_TICKS);
			assertTrue(instability < 0.40F,
					"metric_drift reached " + instability + " at tick " + elapsed);
			int stage = SkyInstrumentPolicy.stage(instability, elapsed);
			assertTrue(stage <= 1, "metric_drift reached stage " + stage + " at tick " + elapsed);
			assertEquals(0, SkyInstrumentPolicy.errorLineCount(stage, elapsed));
			assertEquals(Integer.MIN_VALUE, SkyInstrumentPolicy.rollBarTop(elapsed, 100, stage));
		}
	}

	/** The peak alternates between 3 and 2 and never falls back to clean mid-anomaly. */
	@Test
	void thePeakNeverPretendsTheAnomalyEnded() {
		for (int tick = 0; tick < 400; tick++) {
			assertTrue(SkyInstrumentPolicy.stage(0.95F, tick) >= 2);
		}
	}

	/**
	 * No layer of this presentation may alternate faster than 3 Hz, and the worst of it may not
	 * be on screen more than it is off. Both bounds are here rather than in the renderer so they
	 * survive anyone retuning the look.
	 */
	@Test
	void everyFlickeringLayerStaysUnderThreeHertzAndBelowFortyPercentDuty() {
		for (float instability = 0.70F; instability <= 1.0F; instability += 0.02F) {
			float value = instability;
			assertRhythmIsSafe("burst at " + value,
					tick -> SkyInstrumentPolicy.burstActive(tick, value), 40);
		}
		for (float instability = 0.15F; instability <= 1.0F; instability += 0.02F) {
			float value = instability;
			assertRhythmIsSafe("dropout at " + value,
					tick -> SkyInstrumentPolicy.readoutLost(tick, value), 40);
		}
	}

	/** Torn rows hold their displacement long enough to read as damage rather than as flicker. */
	@Test
	void tornRowsHoldTheirDisplacement() {
		for (int row = 0; row < 8; row++) {
			int held = SkyInstrumentPolicy.tornRowShift(row, 0.0D, 3, 304);
			for (int tick = 1; tick < SkyInstrumentPolicy.MIN_STATE_HOLD_TICKS; tick++) {
				assertEquals(held, SkyInstrumentPolicy.tornRowShift(row, tick, 3, 304),
						"row " + row + " moved within its hold window at tick " + tick);
			}
		}
	}

	/**
	 * A fault line keeps its text as the flood pushes it up the card. Re-rolling per row would
	 * rewrite every visible line each time a new one arrived, which reads as noise rather than as
	 * a device repeating itself.
	 */
	@Test
	void faultLinesKeepTheirTextAsTheFloodPushesThemUp() {
		long seed = 0x4EE7L;
		int arrival = SkyInstrumentPolicy.ERROR_LINE_ARRIVAL_TICKS;
		for (int row = 1; row < 6; row++) {
			assertEquals(SkyInstrumentPolicy.errorLineIndex(row, seed, 100.0D),
					SkyInstrumentPolicy.errorLineIndex(row - 1, seed, 100.0D + arrival));
			assertEquals(SkyInstrumentPolicy.errorChannel(row, seed, 100.0D),
					SkyInstrumentPolicy.errorChannel(row - 1, seed, 100.0D + arrival));
		}
	}

	@Test
	void theFloodFillsTheCardOnlyAtTheWorstOfIt() {
		assertEquals(1, SkyInstrumentPolicy.errorLineCount(2, 0.0D));
		assertEquals(SkyInstrumentPolicy.STAGE_TWO_MAX_ERROR_LINES,
				SkyInstrumentPolicy.errorLineCount(2, 10_000.0D));
		assertEquals(SkyInstrumentPolicy.MAX_ERROR_LINES,
				SkyInstrumentPolicy.errorLineCount(3, 10_000.0D));
	}

	/** Readings stay inside the dial no matter how hard the skew pushes. */
	@Test
	void readingsNeverLeaveTheDial() {
		for (SkyInstrumentPolicy.Channel channel : SkyInstrumentPolicy.Channel.values()) {
			for (int tick = 0; tick < 200; tick++) {
				for (float sample : new float[]{0.0F, 0.5F, 1.0F}) {
					int value = SkyInstrumentPolicy.reading(channel, 1.0F, sample, tick, 0x51L);
					assertTrue(value >= 0 && value <= SkyInstrumentPolicy.FULL_SCALE,
							"reading " + value + " left the dial");
				}
			}
			// With nothing wrong, the channel reports the sample and nothing else.
			assertEquals(SkyInstrumentPolicy.FULL_SCALE,
					SkyInstrumentPolicy.reading(channel, 0.0F, 1.0F, 17.0D, 0x51L));
			assertEquals(0, SkyInstrumentPolicy.reading(channel, 0.0F, 0.0F, 17.0D, 0x51L));
		}
	}

	/**
	 * Scans a two-state rhythm and asserts no run is shorter than the 3 Hz floor and the "on"
	 * state never occupies more than {@code maxDutyPercent} of the window.
	 */
	private static void assertRhythmIsSafe(String what, IntPredicate on, int maxDutyPercent) {
		int window = 1_200;
		int onTicks = 0;
		for (int tick = 0; tick < window; tick++) {
			if (on.test(tick)) onTicks++;
		}
		assertTrue(onTicks * 100 <= window * maxDutyPercent,
				what + " was on for " + onTicks + " of " + window + " ticks");

		// Only runs that both start and end inside the window carry information about the rhythm.
		// The run straddling either edge is cut short by the scan rather than by the policy, and
		// judging it fails the moment the window length is not a multiple of the period.
		int runStart = 0;
		boolean previous = on.test(0);
		int judged = 0;
		for (int tick = 1; tick < window; tick++) {
			boolean current = on.test(tick);
			if (current == previous) continue;
			if (runStart > 0) {
				int run = tick - runStart;
				assertTrue(run >= SkyInstrumentPolicy.MIN_STATE_HOLD_TICKS,
						what + " held " + previous + " for only " + run + " ticks");
				judged++;
			}
			runStart = tick;
			previous = current;
		}
		assertTrue(judged >= 8, what + " only produced " + judged + " complete runs to judge");
	}
}
