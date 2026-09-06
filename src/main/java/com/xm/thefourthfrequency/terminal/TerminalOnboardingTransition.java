package com.xm.thefourthfrequency.terminal;

/**
 * The seam between one first-boot scene and the next.
 *
 * <p>The startup chain - self test, profile, question after question, the acknowledgement, then the
 * tab walkthrough - used to hard-cut. Every other change of scale in this terminal has a movement
 * attached to it, so those cuts read as frames the device dropped rather than as steps it took.
 *
 * <h2>Why this is not the page slide</h2>
 *
 * <p>Tabs slide sideways because that is lateral navigation: four pages of one instrument, side by
 * side, and the wipe edge says which way you went. The startup chain is not navigation. Nothing is
 * beside anything; the device is changing what this screen is <em>for</em>. Reusing the slide would
 * quietly claim the profile is a fifth tab.
 *
 * <p>So the seam is a blank instead. The screen stops showing the old scene, holds empty for a beat,
 * and the new one arrives a line at a time. That is the same grammar the boot self test already
 * uses, and it is what a device re-reading its own display looks like.
 *
 * <h2>The blank is drawn by not drawing</h2>
 *
 * <p>{@code TerminalUiLayout}'s standing rule: to clear a region, do not render it - never cover it.
 * An opaque plate over the page reads as a sticker on the device rather than as the device's own
 * screen, and this whole surface has been walked back from that once already. So the blank here is
 * an instruction to skip the content, not a rectangle to paint.
 *
 * <p>Pure and on the common side. Nothing here knows what a pixel is; the overlay asks it how far
 * along a given line is and draws accordingly.
 */
public final class TerminalOnboardingTransition {
	/**
	 * How long the screen stays empty between scenes, in milliseconds.
	 *
	 * <p>Long enough to read as a gap rather than a flicker, short enough that nobody waits through
	 * it. Below about eighty this stops registering as a pause at all and the change goes back to
	 * looking like a dropped frame.
	 */
	public static final long BLANK_MILLIS = 110L;

	/**
	 * How far each successive line lags the one above it as the new scene arrives.
	 *
	 * <p>Slower than the records backfill's stagger, which is spreading dozens of rows and has to
	 * finish before the player scrolls. This is four or five lines and wants to be read as the device
	 * writing them out, so it can afford the extra beat per line.
	 */
	public static final long ROW_STAGGER_MILLIS = 90L;

	/** How long one line takes to resolve once it starts. */
	public static final long ROW_SETTLE_MILLIS = 220L;

	private TerminalOnboardingTransition() {
	}

	/** Whether the screen is still holding empty. */
	public static boolean blank(long elapsedMillis) {
		return elapsedMillis < BLANK_MILLIS;
	}

	/**
	 * How far one line of the arriving scene has resolved, 0 to 1.
	 *
	 * @param elapsedMillis since the scene changed, including the blank
	 * @param rowIndex      zero-based line within the arriving scene
	 */
	public static double rowProgress(long elapsedMillis, int rowIndex) {
		long afterBlank = elapsedMillis - BLANK_MILLIS - Math.max(0, rowIndex) * ROW_STAGGER_MILLIS;
		if (afterBlank <= 0L) return 0.0D;
		return Math.clamp(afterBlank / (double) ROW_SETTLE_MILLIS, 0.0D, 1.0D);
	}

	/** How long a scene of this many lines takes to finish arriving, blank included. */
	public static long totalMillis(int rowCount) {
		return BLANK_MILLIS + Math.max(0, rowCount - 1) * ROW_STAGGER_MILLIS + ROW_SETTLE_MILLIS;
	}

	/** Whether everything has arrived and the scene can be drawn plainly. */
	public static boolean settled(long elapsedMillis, int rowCount) {
		return elapsedMillis >= totalMillis(rowCount);
	}

	/**
	 * How many knots the loading bar is allowed to stall and surge between.
	 *
	 * <p>Five gives four intervals: enough for the bar to hesitate more than once, few enough that
	 * the whole shape is legible inside a few hundred milliseconds.
	 */
	private static final int STUTTER_KNOTS = 5;

	/**
	 * The loading bar's fill, deliberately not linear.
	 *
	 * <p>A bar that rises at a constant rate is a modern easing curve wearing a retro skin - it reads
	 * as software being polite about a wait. Old hardware does not do that. It sits at some arbitrary
	 * fraction for an uncomfortable beat while something behind it blocks, then covers half the track
	 * in a moment when the block clears.
	 *
	 * <h2>Uneven rate, not discrete steps</h2>
	 *
	 * <p>The obvious way to write this is to quantise the fill into a handful of jumps. That is
	 * rejected on purpose: a bar that teleports is a visible state change, and the standing rule for
	 * those is a seven-tick hold. The whole seam here is a few hundred milliseconds, so honouring that
	 * would leave room for about two steps - a two-step bar, which is not a stutter, it is a bar that
	 * is broken.
	 *
	 * <p>So the movement stays continuous and only its <em>speed</em> varies. Nothing ever jumps,
	 * there is no hold to owe, and it happens to be the more convincing imitation anyway: real
	 * devices stall, they do not skip.
	 *
	 * <p>Monotone by construction - the knot heights are sorted before use - so the bar can never
	 * appear to lose ground, which no loading bar has ever done and which would read as a bug rather
	 * than as age.
	 *
	 * @param linearProgress how far through the transition, 0 to 1
	 * @param seed           stable per scene, so one transition always stutters the same way and two
	 *                       different ones do not stutter identically
	 */
	public static double stutteredProgress(double linearProgress, long seed) {
		double t = Math.clamp(linearProgress, 0.0D, 1.0D);
		if (t <= 0.0D) return 0.0D;
		if (t >= 1.0D) return 1.0D;
		double[] heights = knotHeights(seed);
		int intervals = heights.length - 1;
		double scaled = t * intervals;
		int index = Math.min((int) scaled, intervals - 1);
		double within = scaled - index;
		return heights[index] + (heights[index + 1] - heights[index]) * within;
	}

	/**
	 * Knot heights across the track, sorted so the bar only ever moves forward.
	 *
	 * <p>The gaps between them are what the eye reads: two knots close together is a stall, two far
	 * apart is a surge. The ends are pinned to 0 and 1 so the bar starts empty and finishes full
	 * however the middle happened to fall out.
	 */
	private static double[] knotHeights(long seed) {
		double[] heights = new double[STUTTER_KNOTS];
		heights[0] = 0.0D;
		heights[STUTTER_KNOTS - 1] = 1.0D;
		for (int index = 1; index < STUTTER_KNOTS - 1; index++) {
			heights[index] = fraction(seed, index);
		}
		java.util.Arrays.sort(heights);
		return heights;
	}

	/** SplitMix64 finalizer, folded to {@code [0, 1)}. Deterministic across client and server. */
	private static double fraction(long seed, int index) {
		long value = seed + index * 0x9E3779B97F4A7C15L;
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return (value >>> 11) / (double) (1L << 53);
	}

	/**
	 * A stable identifier for whichever first-boot scene is on screen.
	 *
	 * <p>The transition clock restarts whenever this changes, so it has to change exactly once per
	 * step and never for anything else. Question index is folded in because moving between two
	 * questions is a scene change even though the phase does not move.
	 *
	 * @param phase           the walkthrough phase
	 * @param profileQuestion the question being asked, or negative when none is
	 */
	public static int sceneKey(TerminalOnboardingPolicy.Phase phase, int profileQuestion) {
		if (phase == null) return -1;
		return switch (phase) {
			case BOOT -> 1;
			// The acknowledgement is its own scene: the questions are gone and one line is left.
			case PROFILE -> profileQuestion < 0 ? 2 : 100 + profileQuestion;
			case STEP_1, STEP_2, STEP_3, STEP_4 -> 200 + TerminalOnboardingPolicy.stepIndex(phase);
			case RELEASED, DONE -> 300;
		};
	}
}
