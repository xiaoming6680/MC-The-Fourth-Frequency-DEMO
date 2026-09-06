package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline;
import com.xm.thefourthfrequency.client_ui.RedHorizonTimeline;

/**
 * What the weather tool looks like while the sky is being rewritten.
 *
 * <p>The weather tool was the one instrument in the terminal that never reacted to anything. Two
 * anomalies already repaint the sky - {@code red_horizon} tints the dome, the horizon band and the
 * fog, {@code metric_drift} rotates the sun, moon and stars away from the local clock - and a
 * player could stand under either of them, open the tool, and be told "clear, daytime" by a device
 * whose entire premise is that it reports the world honestly. This file is what closes that gap.</p>
 *
 * <p>Everything here is arithmetic over an anomaly id and a tick count. It decides <em>what a
 * given tick looks like</em> and never when an anomaly happens, what the sky actually is, or how
 * to draw any of it - the same split {@link RedHorizonTimeline} and {@link AlphaLoadTimeline}
 * already use, and the reason the whole presentation stays verifiable without a running client.</p>
 *
 * <h2>The two boundaries this file is responsible for</h2>
 *
 * <p><b>Actionable information is never quietly falsified.</b> The weather tool's own line carries
 * a number players plan around - how long until dark. Skewing it into a different but equally
 * believable number would be exactly the "false system error standing in for a rule prompt" the
 * world bible forbids, and it would get someone killed on the way home. So that line only ever
 * <em>visibly</em> fails: {@link #readoutLost} collapses it to dashes and it returns correct.
 * The instrument channels added alongside it carry no survival decision, so those are free to
 * saturate, drop out and disagree with each other.</p>
 *
 * <p><b>Nothing here may flicker faster than 3 Hz.</b> Every layer that changes visible state -
 * bursts, dropouts, tearing, the readings themselves - is quantised to a hold of at least
 * {@link #MIN_STATE_HOLD_TICKS} ticks. This is not a stylistic choice; high-contrast strobing is
 * the one class of effect that can hurt a player, and it is bounded here rather than in the
 * renderer so it is provable in a unit test.</p>
 */
public final class SkyInstrumentPolicy {
	/** The four things the instrument claims to measure about the sky. */
	public enum Channel {
		/** The dome directly overhead. Takes only {@code SKY_DOME_SHARE} of a red horizon. */
		ZENITH,
		/** The horizon band, where a red horizon lands at full strength. */
		HORIZON,
		/** Star brightness. Drained by a red horizon, forced up in daylight by temporal drift. */
		STARS,
		/** How far the celestial bodies sit from where the local clock says they should be. */
		PHASE
	}

	/** Full scale of a channel readout. Three digits reads as an instrument; more reads as a log. */
	public static final int FULL_SCALE = 999;
	/**
	 * The floor under every visible state change, in ticks.
	 *
	 * <p>20 tps / 7 ticks is a shade under 3 Hz. Photosensitive-seizure guidance puts the risk
	 * band above 3 Hz, so no layer in this presentation is allowed to alternate faster than this.
	 * Asserted directly by the unit tests rather than left as a comment.</p>
	 */
	public static final int MIN_STATE_HOLD_TICKS = 7;
	/** How many fault strings the flood draws from. */
	public static final int FAULT_MESSAGE_COUNT = 8;
	/** Rows the flood can reach at stage 3, which is what it takes to bury the readout card. */
	public static final int MAX_ERROR_LINES = 8;
	/** At stage 2 the flood is arriving, not finished; it stops well short of covering the card. */
	public static final int STAGE_TWO_MAX_ERROR_LINES = 3;
	/** One new fault line every half second. Faster reads as a scroll, slower as a list. */
	public static final int ERROR_LINE_ARRIVAL_TICKS = 10;

	private static final float STAGE_ONE = 0.15F;
	private static final float STAGE_TWO = 0.40F;
	private static final float STAGE_THREE = 0.70F;

	/**
	 * Where a sustained anomaly sits, deliberately below {@link #STAGE_TWO}.
	 *
	 * <p>{@code metric_drift} runs for three to five minutes and its whole design is to stay low
	 * enough to be doubted. Five unbroken minutes of tearing and flooding would convert it from
	 * "you are not sure anything happened" into "the screen is broken", which is a different and
	 * much cheaper feeling. Capping the envelope below the stage-2 threshold makes that structural
	 * rather than a matter of tuning.</p>
	 */
	private static final float SUSTAINED_PEAK = 0.34F;
	private static final int SUSTAINED_RAMP_TICKS = 200;

	private static final int BURST_PERIOD_LOOSE = 40;
	private static final int BURST_PERIOD_TIGHT = 28;
	private static final int BURST_WINDOW_MIN = 10;
	private static final int BURST_WINDOW_MAX = 11;
	private static final int LOST_PERIOD_LOOSE = 60;
	private static final int LOST_PERIOD_TIGHT = 30;
	private static final int LOST_WINDOW_TICKS = 8;
	private static final int TEAR_HOLD_TICKS = MIN_STATE_HOLD_TICKS;
	private static final int READING_HOLD_TICKS = MIN_STATE_HOLD_TICKS;
	private static final int TEAR_PERCENT_STAGE_TWO = 22;
	private static final int TEAR_PERCENT_STAGE_THREE = 55;
	private static final float TEAR_SPAN_STAGE_TWO = 0.08F;
	private static final float TEAR_SPAN_STAGE_THREE = 0.22F;
	private static final int[] SCANLINE_ALPHA = {0, 26, 40, 58};
	private static final float[] CHROMA_OFFSET = {0.0F, 1.0F, 2.0F, 4.0F};
	/** Above this fraction of full scale a channel is reported as saturated rather than as a number. */
	private static final float SATURATION_POINT = 0.96F;

	private SkyInstrumentPolicy() {
	}

	/**
	 * The master envelope, 0 to 1, for how badly the instrument is losing the sky.
	 *
	 * <p>{@code red_horizon} reuses its own timeline rather than restating it: the tool has to
	 * agree with what the sky renderer is actually doing, and two hand-kept copies of the same
	 * curve would disagree the first time either was tuned. Anything that is not a sky anomaly
	 * returns zero, so an unrelated anomaly never disturbs this page.</p>
	 */
	public static float instability(String anomalyId, int elapsedTicks, int remainingTicks, int durationTicks) {
		if (anomalyId == null || durationTicks <= 0) return 0.0F;
		return switch (anomalyId) {
			case "red_horizon" ->
					RedHorizonTimeline.horizonStrength(elapsedTicks, remainingTicks, durationTicks);
			case "metric_drift" -> sustained(elapsedTicks, remainingTicks, durationTicks);
			default -> 0.0F;
		};
	}

	/** True when this anomaly id is one the weather tool reacts to at all. */
	public static boolean isSkyAnomaly(String anomalyId) {
		return "red_horizon".equals(anomalyId) || "metric_drift".equals(anomalyId);
	}

	/**
	 * The presentation stage, 0 through 3.
	 *
	 * <p>Note what happens past {@link #STAGE_THREE}: the page alternates between 3 and 2, never
	 * back to clean. A peak that dropped to nothing between bursts would tell the player the
	 * anomaly had ended and then take it back, twice a second.</p>
	 */
	public static int stage(float instability, double ageTicks) {
		if (instability < STAGE_ONE) return 0;
		if (instability < STAGE_TWO) return 1;
		if (instability < STAGE_THREE) return 2;
		return burstActive(ageTicks, instability) ? 3 : 2;
	}

	/**
	 * Whether the worst of it is on screen this tick.
	 *
	 * <p>Held to short passes rather than left on for the whole peak. {@code red_horizon} sits
	 * above the stage-3 threshold for roughly twenty-five seconds, and twenty-five unbroken
	 * seconds of a full-card fault flood is both exhausting and equivalent to deleting the tool.
	 * A burst that crosses the picture and leaves reads as damage passing through the medium,
	 * which is the thing being imitated, and it gives the readout back in between.</p>
	 */
	public static boolean burstActive(double ageTicks, float instability) {
		if (instability < STAGE_THREE) return false;
		int period = burstPeriod(instability);
		return Math.floorMod(tickOf(ageTicks), period) < burstWindow(instability);
	}

	/**
	 * Whether the tool's own actionable line is unreadable this tick.
	 *
	 * <p>Returns false for anything below {@link #STAGE_ONE}, which includes an instability of
	 * exactly zero - under a normal sky the terminal never blinks at all. That property is the
	 * whole reason a player believes it when it does.</p>
	 */
	public static boolean readoutLost(double ageTicks, float instability) {
		if (instability < STAGE_ONE) return false;
		return Math.floorMod(tickOf(ageTicks), lostPeriod(instability)) < LOST_WINDOW_TICKS;
	}

	/** How many fault lines have piled up. {@code floodTicks} counts from when stage 2 was entered. */
	public static int errorLineCount(int stage, double floodTicks) {
		if (stage < 2) return 0;
		int grown = 1 + tickOf(floodTicks) / ERROR_LINE_ARRIVAL_TICKS;
		return Math.min(stage >= 3 ? MAX_ERROR_LINES : STAGE_TWO_MAX_ERROR_LINES, grown);
	}

	/**
	 * Which fault string sits on a given row, counting row 0 as the oldest still visible.
	 *
	 * <p>Keyed by which line this <em>is</em> in the sequence of lines produced, not by its row,
	 * so a line keeps its text as it is pushed up the card. Re-rolling per row would make every
	 * line change every time a new one arrived, and a flood whose contents rewrite themselves
	 * reads as noise rather than as a device repeating itself.</p>
	 */
	public static int errorLineIndex(int row, long seed, double floodTicks) {
		return Math.floorMod(AlphaLoadTimeline.noise(
				(int) (producedIndex(row, floodTicks) * 0x9E3779B9L + seed)), FAULT_MESSAGE_COUNT);
	}

	/** The channel number printed alongside a fault line, so repeats are visibly repeats. */
	public static int errorChannel(int row, long seed, double floodTicks) {
		return Math.floorMod(AlphaLoadTimeline.noise(
				(int) (producedIndex(row, floodTicks) * 0x85EBCA6BL + seed * 31L)), 256);
	}

	/**
	 * How far one row of the card is dragged sideways, signed, or zero if that row is intact.
	 *
	 * <p>Seeded by the row plus a held time bucket rather than by the frame. Re-seeding every
	 * frame makes each row jump somewhere new sixty times a second, which the eye reads as
	 * flicker laid over the picture instead of as one piece of damage sitting on it - the same
	 * trap the tracking band in the loading-screen corruption documents.</p>
	 */
	public static int tornRowShift(int row, double ageTicks, int stage, int width) {
		if (stage < 2 || width <= 0) return 0;
		int bucket = tickOf(ageTicks) / TEAR_HOLD_TICKS;
		int seed = AlphaLoadTimeline.noise(row * 0x9E3779B9 + bucket * 0x85EBCA6B);
		int torn = stage >= 3 ? TEAR_PERCENT_STAGE_THREE : TEAR_PERCENT_STAGE_TWO;
		if (Math.floorMod(seed >>> 7, 100) >= torn) return 0;
		int span = Math.max(2, Math.round(width
				* (stage >= 3 ? TEAR_SPAN_STAGE_THREE : TEAR_SPAN_STAGE_TWO)));
		return Math.floorMod(seed, span * 2 + 1) - span;
	}

	/** Whether a snow speck lands on this cell of the card. Stage 3 only. */
	public static boolean snowSpeck(int index, double ageTicks, int stage) {
		if (stage < 3) return false;
		int bucket = tickOf(ageTicks) / TEAR_HOLD_TICKS;
		return Math.floorMod(AlphaLoadTimeline.noise(
				index * 0x27D4EB2F + bucket * 0x165667B1) >>> 6, 100) < 34;
	}

	public static int scanlineAlpha(int stage) {
		return SCANLINE_ALPHA[Math.clamp(stage, 0, 3)];
	}

	public static float chromaOffset(int stage) {
		return CHROMA_OFFSET[Math.clamp(stage, 0, 3)];
	}

	/**
	 * Top of the mistracked band sweeping up the card, or {@link Integer#MIN_VALUE} when there is
	 * no band to draw. Stage 2 and above.
	 */
	public static int rollBarTop(double ageTicks, int height, int stage) {
		if (stage < 2 || height <= 0) return Integer.MIN_VALUE;
		int band = rollBarHeight(height);
		int span = height + band;
		int speed = Math.max(2, height / 12);
		return height - Math.floorMod(tickOf(ageTicks) * speed, span);
	}

	public static int rollBarHeight(int height) {
		return Math.max(2, height / 14);
	}

	/**
	 * A channel's displayed value, 0 to {@link #FULL_SCALE}.
	 *
	 * <p>The skew is held for {@link #READING_HOLD_TICKS} at a time. A number re-rolled every
	 * frame is unreadable, and an unreadable number carries no information about how wrong it is -
	 * the player has to be able to see the reading settle somewhere implausible.</p>
	 */
	public static int reading(Channel channel, float instability, float sample, double ageTicks, long seed) {
		int base = Math.round(Math.clamp(sample, 0.0F, 1.0F) * FULL_SCALE);
		if (instability <= 0.0F || channel == null) return base;
		int magnitude = Math.round(instability * instability * FULL_SCALE * 0.35F);
		if (magnitude <= 0) return base;
		int bucket = tickOf(ageTicks) / READING_HOLD_TICKS;
		int noise = AlphaLoadTimeline.noise(
				(int) (bucket * 0x1B873593L + channel.ordinal() * 0x9E3779B9L + seed));
		return Math.clamp(base + Math.floorMod(noise, magnitude * 2 + 1) - magnitude, 0, FULL_SCALE);
	}

	/** Channels this far up the scale print as saturated instead of as a number. */
	public static boolean saturated(float sample) {
		return sample >= SATURATION_POINT;
	}

	/**
	 * What the horizon channel reads during a red horizon, 0 to 1.
	 *
	 * <p>Split from {@link #zenithShare} on purpose. Vanilla blends the dome colour toward the fog
	 * colour near the horizon, so the anomaly puts its full strength into the horizon band and only
	 * {@code SKY_DOME_SHARE} of it overhead. That is what makes the tool an early warning rather
	 * than a second opinion: the horizon channel is already swinging across a quarter of its scale
	 * while the dome overhead - the part of the sky a player standing in a forest actually sees -
	 * has barely moved.</p>
	 */
	public static float horizonShare(int elapsedTicks, int remainingTicks, int durationTicks) {
		return RedHorizonTimeline.horizonStrength(elapsedTicks, remainingTicks, durationTicks);
	}

	/** What the zenith channel reads during a red horizon. Always the smaller of the pair. */
	public static float zenithShare(int elapsedTicks, int remainingTicks, int durationTicks) {
		return RedHorizonTimeline.skyDomeStrength(elapsedTicks, remainingTicks, durationTicks);
	}

	/** Replaces a code point when a masked reading is drawn. Shares the terminal's glitch glyphs. */
	public static char maskGlyph(long seed) {
		int[] glyphs = TerminalNavigationVisualPolicy.GLITCH_GLYPHS;
		return (char) glyphs[Math.floorMod(AlphaLoadTimeline.noise((int) seed), glyphs.length)];
	}

	private static float sustained(int elapsedTicks, int remainingTicks, int durationTicks) {
		int ramp = Math.max(1, Math.min(SUSTAINED_RAMP_TICKS, durationTicks / 6));
		float arrival = smoothstep(Math.max(0, elapsedTicks) / (float) ramp);
		float departure = smoothstep(Math.max(0, remainingTicks) / (float) ramp);
		float breath = RedHorizonTimeline.breathing(elapsedTicks, remainingTicks, durationTicks);
		return Math.clamp(SUSTAINED_PEAK * Math.min(arrival, departure) * breath, 0.0F, 1.0F);
	}

	private static int burstPeriod(float instability) {
		return Math.round(BURST_PERIOD_LOOSE
				- (BURST_PERIOD_LOOSE - BURST_PERIOD_TIGHT) * peakPhase(instability));
	}

	private static int burstWindow(float instability) {
		return Math.round(BURST_WINDOW_MIN
				+ (BURST_WINDOW_MAX - BURST_WINDOW_MIN) * peakPhase(instability));
	}

	private static int lostPeriod(float instability) {
		float phase = Math.clamp((instability - STAGE_ONE) / (1.0F - STAGE_ONE), 0.0F, 1.0F);
		return Math.round(LOST_PERIOD_LOOSE - (LOST_PERIOD_LOOSE - LOST_PERIOD_TIGHT) * phase);
	}

	private static float peakPhase(float instability) {
		return Math.clamp((instability - STAGE_THREE) / (1.0F - STAGE_THREE), 0.0F, 1.0F);
	}

	private static long producedIndex(int row, double floodTicks) {
		return (long) tickOf(floodTicks) / ERROR_LINE_ARRIVAL_TICKS + Math.max(0, row);
	}

	private static int tickOf(double ageTicks) {
		return (int) Math.floor(Math.max(0.0D, ageTicks));
	}

	private static float smoothstep(float phase) {
		float clamped = Math.clamp(phase, 0.0F, 1.0F);
		return clamped * clamped * (3.0F - 2.0F * clamped);
	}
}
