package com.xm.thefourthfrequency.terminal;

/**
 * The short power-on check the terminal runs every time it is opened.
 *
 * <p>Distinct from the first-boot self test in {@link TerminalOnboardingPolicy}, which is one-shot,
 * six lines long, holds the exit and is part of the walkthrough. This one is four lines, half a
 * second, holds nothing, and plays for the rest of the run.
 *
 * <h2>Every line is a measurement, not a mood</h2>
 *
 * <p>The device reports on itself, and what it reports has to be literally true - the mod does not
 * ship invented faults. Three of the four lines restate numbers the player can already see
 * elsewhere on the panel, which is the point: they are the control group. The fourth is the one
 * worth reading.
 *
 * <p>{@link Line#BASELINE} prints the contact baseline, and the number it prints is not decorative.
 * It is {@code TerminalContactVoice.WEAR_PER_STAGE} - the same constant that pitches every button
 * on the device down as the holder's stage rises. So the panel has been answering a touch a little
 * duller for hours, under the threshold where anyone notices a sound changing, and then one day the
 * machine states the figure. The reading is old news that has never been said out loud.
 *
 * <p>At stage zero it reads as calibrated, because at stage zero it is.
 *
 * <h2>What it must never do</h2>
 *
 * <p>It does not lock the exit. The first-boot walkthrough is the only thing in this mod allowed to
 * do that, and it is allowed because it is one-shot; a check that runs on every open and took the
 * exit with it would take it hundreds of times. {@link #skippable()} is a contract, not a
 * convenience: the first input ends it.
 */
public final class TerminalSelfTest {
	/** The four checks, in the order they print. */
	public enum Line {
		/** The device is on. Nothing else can be reported if this is not true. */
		POWER,
		/** How much the machine is holding: records plus files. */
		STORE,
		/** The authorised band stage, the same pair the status bar prints. */
		LINK,
		/** How far the panel's own contact baseline has drifted from the factory figure. */
		BASELINE
	}

	public static final int LINE_COUNT = 4;
	/**
	 * One line every 150 ms.
	 *
	 * <p>Four fifths of the first-boot cadence. That one is a ceremony played once and is allowed to
	 * take its time; this is a check the player will sit through hundreds of times, and the whole
	 * budget is the single number to tune if it ever starts to feel like a wait.
	 */
	public static final long LINE_MILLIS = 150L;
	public static final long TOTAL_MILLIS = LINE_COUNT * LINE_MILLIS;
	/** Faster per character than the first boot. Six lines is a report; four is a glance. */
	public static final long CHAR_MILLIS = 9L;

	private TerminalSelfTest() {
	}

	/**
	 * Whether the short check plays at all on this open.
	 *
	 * <p>The walkthrough owns the screen while it runs, and it opens with a self test of its own.
	 * Running both would put two power-on checks back to back on the one boot that already has one.
	 *
	 * @param walkthroughRunning whether {@code TerminalOnboardingPolicy.locksExit} holds
	 */
	public static boolean playsOnOpen(boolean walkthroughRunning) {
		return !walkthroughRunning;
	}

	/**
	 * The first input ends it, always.
	 *
	 * <p>Stated as a method rather than left implicit so the property has somewhere to be asserted.
	 * A player who opened the terminal to read a pursuit warning must never be made to watch a
	 * status line finish printing first.
	 */
	public static boolean skippable() {
		return true;
	}

	public static boolean finished(long elapsedMillis) {
		return elapsedMillis >= TOTAL_MILLIS;
	}

	/** How many lines have started printing. */
	public static int visibleLines(long elapsedMillis) {
		if (elapsedMillis < 0L) return 0;
		return (int) Math.clamp(elapsedMillis / LINE_MILLIS + 1L, 0L, LINE_COUNT);
	}

	/** Code points printed so far on the given line. */
	public static int typedCharacters(int totalCodePoints, long elapsedMillis, int lineIndex) {
		return TerminalMotion.typedCharacters(totalCodePoints,
				elapsedMillis - lineIndex * LINE_MILLIS, CHAR_MILLIS);
	}

	/**
	 * The contact baseline offset for a holder at this stage, as a whole percent.
	 *
	 * <p>Derived from the pitch table rather than restated, so the figure on screen cannot drift
	 * away from the figure in the speakers. Zero at stage zero, which is the honest answer there.
	 */
	public static int baselineDriftPercent(int stage) {
		int safe = Math.clamp(stage, 0, TerminalContactVoice.MAX_STAGE);
		return Math.round(TerminalContactVoice.WEAR_PER_STAGE * safe * 100.0F);
	}

	public static boolean baselineCalibrated(int stage) {
		return baselineDriftPercent(stage) <= 0;
	}
}
