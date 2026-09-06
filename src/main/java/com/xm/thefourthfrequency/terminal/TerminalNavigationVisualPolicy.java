package com.xm.thefourthfrequency.terminal;

import java.util.ArrayList;
import java.util.List;

/** Deterministic timing and text corruption rules for terminal navigation feedback. */
public final class TerminalNavigationVisualPolicy {
	/**
	 * Package-private rather than private so {@link SkyInstrumentPolicy} can mask with the same
	 * glyphs. A second copy would let the two surfaces drift apart, and a terminal that corrupts
	 * text two different ways reads as two different faults.
	 */
	static final int[] GLITCH_GLYPHS = {'\uFFFD', '\u2593', '\u256B', '\u00A4', '?'};

	private TerminalNavigationVisualPolicy() {
	}

	public static int animatedProbeDots(double ageTicks) {
		return 1 + Math.floorMod((int) Math.floor(ageTicks / 5.0D), 3);
	}

	public static boolean sideRouteGlitchActive(double ageTicks) {
		int tick = Math.max(0, (int) Math.floor(ageTicks));
		return tick >= 40 && Math.floorMod(tick, 40) < 10;
	}

	/**
	 * Corrupts a readout's words while leaving every digit intact.
	 *
	 * <p>For the one records line that reprints a reading the terminal is known to have got wrong.
	 * The corruption is what marks that half of the line as the untrustworthy half - but the numbers
	 * in it are the whole reason the line is worth keeping, because they are what the player compares
	 * against what they remember pressing the probe for. A line whose figures were eaten would be a
	 * mark the player cannot follow, which the records page is not allowed to make.
	 *
	 * <p>Same glyph pool as {@link #corruptNavigationName}, and deliberately not animated: this is
	 * damage that settled, not interference that is still happening. Callers seed it from the record
	 * entry so it looks identical every time the page is reopened.
	 */
	public static String corruptReadout(String text, long seed) {
		int[] codePoints = text.codePoints().toArray();
		List<Integer> candidates = new ArrayList<>();
		for (int index = 0; index < codePoints.length; index++) {
			if (Character.isLetter(codePoints[index])) candidates.add(index);
		}
		if (candidates.isEmpty()) return text;
		long mixed = seed * 0x9E3779B97F4A7C15L;
		int replacements = Math.min(candidates.size(), 2 + Math.floorMod((int) (mixed >> 11), 2));
		for (int offset = 0; offset < replacements; offset++) {
			int choice = Math.floorMod((int) (mixed >>> (offset * 7 + 3)) + offset * 17, candidates.size());
			int position = candidates.remove(choice);
			codePoints[position] = GLITCH_GLYPHS[Math.floorMod(
					(int) (mixed >>> (offset * 5 + 2)) + offset, GLITCH_GLYPHS.length)];
		}
		return new String(codePoints, 0, codePoints.length);
	}

	public static String corruptNavigationName(String name, int targetWireId, long cycle) {
		int[] codePoints = name.codePoints().toArray();
		List<Integer> candidates = new ArrayList<>();
		for (int index = 0; index < codePoints.length; index++) {
			if (Character.isLetterOrDigit(codePoints[index])) candidates.add(index);
		}
		if (candidates.isEmpty()) return name;
		long seed = cycle * 1_103_515_245L + targetWireId * 12_345L;
		int replacements = Math.min(candidates.size(), 1 + Math.floorMod((int) seed, 2));
		for (int offset = 0; offset < replacements; offset++) {
			int choice = Math.floorMod((int) (seed >>> (offset * 7)) + offset * 17, candidates.size());
			int position = candidates.remove(choice);
			codePoints[position] = GLITCH_GLYPHS[Math.floorMod(
					(int) (seed >>> (offset * 5)) + offset, GLITCH_GLYPHS.length)];
		}
		return new String(codePoints, 0, codePoints.length);
	}

	public static boolean navigationNeedleFlashVisible(double elapsedTicks) {
		return elapsedTicks < 0.0D || elapsedTicks >= 20.0D
				|| Math.floorMod((int) Math.floor(elapsedTicks / 2.0D), 2) == 0;
	}

	public static boolean navigationNeedleFlashActive(double elapsedTicks) {
		return elapsedTicks >= 0.0D && elapsedTicks < 20.0D;
	}

	public static boolean targetNeedleVisible(boolean guidanceActive, boolean navigable, double elapsedTicks) {
		return guidanceActive && (navigable || navigationNeedleFlashActive(elapsedTicks))
				&& navigationNeedleFlashVisible(elapsedTicks);
	}
}
