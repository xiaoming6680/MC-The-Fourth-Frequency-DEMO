package com.xm.thefourthfrequency.terminal;

/**
 * How disturbed the oscilloscope trace is, and why it is allowed to be.
 *
 * <p>The scope on the hardware column has always drawn a waveform driven by the tuning dial and
 * nothing else, which made it decoration with a mechanism. This gives it something to report: how
 * close the next ambient anomaly is. An attentive player learns to read the trace before the
 * terminal has any words for what is coming, and that is the only place in this mod where paying
 * close attention to an instrument is rewarded with foreknowledge.
 *
 * <h2>Why this does not break the operational-information rule</h2>
 *
 * <p>The safety rules say operational information must fail visibly and must never be quietly
 * swapped for a plausible wrong value - and they say so about readings a player makes survival
 * decisions on, like the time until nightfall. They also say, in the same breath, that instrument
 * readouts with nothing hanging off them are free to swing wildly, precisely because nobody is
 * walking home by them.
 *
 * <p>This is the second kind, and it is kept that way deliberately:
 *
 * <ul>
 * <li><b>It is never labelled.</b> No number, no countdown, no word. A trace that gets restless.</li>
 * <li><b>It is allowed to be wrong.</b> The schedule it reads is deferred whenever the player is
 * asleep, in the terminal, mid-anomaly or in a pursuit, so the trace can climb and then have nothing
 * happen. That is not a defect to be fixed. An instrument that occasionally cries wolf cannot become
 * an oracle, and the anomalies keep the "did I imagine that" quality the whole design rests on.</li>
 * <li><b>The window is short.</b> Half a minute is enough to be noticed in hindsight and far too
 * little to plan around.</li>
 * </ul>
 */
public final class OscilloscopeWaveformPolicy {
	/**
	 * How long before a scheduled anomaly the trace starts to notice, in ticks.
	 *
	 * <p>Thirty seconds. Long enough that a player who happens to have the terminal open sees it
	 * build, short enough that it cannot be used to schedule anything - by the time it means
	 * something there is no time left to act on it.
	 */
	public static final long APPROACH_WINDOW_TICKS = 600L;

	/** Reported as 0-100 so it costs one varint and cannot carry a precise countdown. */
	public static final int MAX_APPROACH = 100;

	private OscilloscopeWaveformPolicy() {
	}

	/**
	 * How near the next scheduled anomaly is, 0-100.
	 *
	 * @param nextAnomalyTick when the scheduler currently intends to fire; zero or past means nothing
	 *                        is scheduled, which reads as calm rather than as imminent
	 * @param now             the current game time
	 */
	public static int approach(long nextAnomalyTick, long now) {
		if (nextAnomalyTick <= 0L) return 0;
		long remaining = nextAnomalyTick - now;
		if (remaining <= 0L) return 0;
		if (remaining >= APPROACH_WINDOW_TICKS) return 0;
		return (int) Math.clamp(
				Math.round((1.0D - remaining / (double) APPROACH_WINDOW_TICKS) * MAX_APPROACH),
				0L, (long) MAX_APPROACH);
	}

	/**
	 * Extra trace instability, as a multiplier on the waveform's existing amplitude.
	 *
	 * <p>Quadratic rather than linear, so the first two thirds of the window are almost nothing and
	 * the last few seconds are unmistakable. A linear ramp spends the whole half minute looking
	 * slightly wrong, which is the same as looking normal.
	 */
	public static double disturbance(int approach) {
		double normalised = Math.clamp(approach, 0, MAX_APPROACH) / (double) MAX_APPROACH;
		return normalised * normalised;
	}

	/**
	 * The trace while an anomaly is actually running.
	 *
	 * <p>Pinned at full rather than continuing to climb. Once the thing has arrived the scope has
	 * nothing left to predict, and a needle that kept rising during the event would be reporting
	 * intensity - which is information the player is not supposed to be handed.
	 */
	public static double activeDisturbance() {
		return 1.0D;
	}
}
