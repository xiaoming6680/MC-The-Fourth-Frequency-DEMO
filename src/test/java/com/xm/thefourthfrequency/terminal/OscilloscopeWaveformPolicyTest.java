package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OscilloscopeWaveformPolicyTest {
	/**
	 * The scope has to be quiet almost all of the time, or it is not reporting anything.
	 *
	 * <p>An instrument that is always slightly agitated is an instrument that is always normal. The
	 * whole value of this reading is that a player who has watched a calm trace for hours notices the
	 * half minute where it is not.
	 */
	@Test
	void nothingScheduledAndNothingSoonReadAsCalm() {
		assertEquals(0, OscilloscopeWaveformPolicy.approach(0L, 1_000L));
		assertEquals(0, OscilloscopeWaveformPolicy.approach(-50L, 1_000L));
		assertEquals(0, OscilloscopeWaveformPolicy.approach(100_000L, 1_000L));
		// Exactly at the window edge is still calm; inside it is not.
		long now = 1_000L;
		assertEquals(0, OscilloscopeWaveformPolicy.approach(
				now + OscilloscopeWaveformPolicy.APPROACH_WINDOW_TICKS, now));
		assertTrue(OscilloscopeWaveformPolicy.approach(
				now + OscilloscopeWaveformPolicy.APPROACH_WINDOW_TICKS - 1, now) >= 0);
	}

	/** Closer is never calmer, and the reading stays inside the range the wire promises. */
	@Test
	void approachRisesMonotonicallyAndStaysInRange() {
		long now = 10_000L;
		int previous = -1;
		for (long remaining = OscilloscopeWaveformPolicy.APPROACH_WINDOW_TICKS; remaining >= 1; remaining--) {
			int approach = OscilloscopeWaveformPolicy.approach(now + remaining, now);
			assertTrue(approach >= previous, "approach fell while the anomaly got closer");
			assertTrue(approach >= 0 && approach <= OscilloscopeWaveformPolicy.MAX_APPROACH);
			previous = approach;
		}
		assertTrue(previous >= 99, "the last tick before it fires should be unmistakable");
	}

	/**
	 * A schedule that has already passed reads as calm, not as maximum.
	 *
	 * <p>The scheduler defers whenever the player is asleep, in the terminal, mid-anomaly or being
	 * chased, which leaves the stored tick in the past for as long as the deferral lasts. Treating
	 * that as imminent would pin the trace at full for minutes and turn the one signal this
	 * instrument has into permanent noise.
	 */
	@Test
	void anOverdueScheduleReadsAsCalm() {
		assertEquals(0, OscilloscopeWaveformPolicy.approach(500L, 900L));
		assertEquals(0, OscilloscopeWaveformPolicy.approach(500L, 500L));
	}

	/**
	 * The ramp has to spend its first half doing almost nothing.
	 *
	 * <p>Quadratic, not linear. A linear ramp looks slightly wrong for thirty seconds, and slightly
	 * wrong for thirty seconds is indistinguishable from normal - it would train players to ignore
	 * the very thing it exists to show them.
	 */
	@Test
	void disturbanceStaysNearlyInvisibleUntilLate() {
		assertEquals(0.0D, OscilloscopeWaveformPolicy.disturbance(0));
		assertTrue(OscilloscopeWaveformPolicy.disturbance(50) <= 0.30D,
				"halfway through the window should still be barely anything");
		assertTrue(OscilloscopeWaveformPolicy.disturbance(90) >= 0.75D);
		assertEquals(1.0D, OscilloscopeWaveformPolicy.disturbance(OscilloscopeWaveformPolicy.MAX_APPROACH));
		assertEquals(1.0D, OscilloscopeWaveformPolicy.activeDisturbance());
		// Out-of-range input from a hostile or stale packet cannot drive the trace past full scale.
		assertEquals(1.0D, OscilloscopeWaveformPolicy.disturbance(9_999));
		assertEquals(0.0D, OscilloscopeWaveformPolicy.disturbance(-9_999));
	}

	/**
	 * The window is short enough that knowing does not help.
	 *
	 * <p>This is the line between an instrument that rewards attention and one that turns the ambient
	 * anomalies into a scheduled event the player can prepare for.
	 */
	@Test
	void theWarningWindowIsTooShortToPlanAround() {
		assertTrue(OscilloscopeWaveformPolicy.APPROACH_WINDOW_TICKS <= 20L * 45L,
				"more than about forty-five seconds of warning stops being a hint");
	}
}
