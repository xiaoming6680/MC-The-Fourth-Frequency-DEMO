package com.xm.thefourthfrequency.terminal;

/**
 * Text that arrives out of noise and settles into being readable.
 *
 * <p>Two callers drive this from opposite ends. The records backfill drives {@code progress} from a
 * millisecond clock, so a line resolves on its own over about a second. The first-boot questionnaire
 * drives it from receiver signal strength, so an option resolves because the player tuned onto it.
 * Same vocabulary either way, which is the point: one is the world doing it to you and one is you
 * doing it to the world, and they should look like the same phenomenon.
 *
 * <p>Pure, on the common side, no allocation per glyph. The drawing lives in the client; nothing
 * here knows what a pixel is.
 *
 * <h2>Why this cannot strobe</h2>
 *
 * <p>Two separate decisions, and only one of them is allowed to move fast:
 *
 * <ul>
 * <li><b>Whether a character is resolved yet</b> comes from a per-character noise threshold that is
 * fixed for the life of the line. As {@code progress} rises, characters resolve in a stable order
 * and never go back. There is no flicker in this decision at any frame rate, because there is no
 * time term in it at all.</li>
 * <li><b>Which junk character an unresolved position shows</b> is rerolled per bucket, and a bucket
 * is {@link #GLYPH_HOLD_MILLIS} long. That is the only moving part, and it is quantised to the 3 Hz
 * ceiling by construction rather than by a caller remembering to rate-limit.</li>
 * </ul>
 *
 * <p>A caller driving {@code progress} from a player-held control still owes it a low pass - a hand
 * on the slider can cross a threshold back and forth faster than 3 Hz, and the fixed resolve order
 * makes that read as one character blinking rather than as noise. {@code TuningTransition} is the
 * intended filter; it was built to be retargeted mid-flight, which is exactly this case.
 */
public final class TerminalGlyphSettle {
	/**
	 * How long one junk character is held before it is rerolled, in milliseconds.
	 *
	 * <p>350 ms is 7 ticks, the same minimum hold the digital-corruption chain uses for every
	 * discrete quantity it owns. 3 Hz is 6.67 ticks and this rounds up, not down.
	 */
	public static final long GLYPH_HOLD_MILLIS = 350L;

	/** How long one line takes to go from unreadable to clean, in milliseconds. */
	public static final long ROW_SETTLE_MILLIS = 1_050L;

	/**
	 * How far each successive line is delayed behind the one above it, in milliseconds.
	 *
	 * <p>Small enough that a screenful still feels like one event, large enough that the lines do not
	 * resolve in lockstep. Lockstep reads as a single fade, and a fade is what a UI does; staggered
	 * resolution reads as a page being received.
	 */
	public static final long ROW_STAGGER_MILLIS = 120L;

	/**
	 * The junk alphabet.
	 *
	 * <p>Deliberately narrow and deliberately monospace-ish in the pixel font: the replacement has to
	 * be the same width as what it stands in for, or the line reflows as it settles and the eye reads
	 * movement instead of resolution. No letters or digits - a junk glyph that could be mistaken for
	 * content makes a half-settled line look like it says something it does not.
	 */
	private static final char[] JUNK = {
			'#', '%', '&', '@', '$', '*', '+', '=', '~', '/', '\\', '|', '<', '>', '?', ':', ';', '!'
	};

	private TerminalGlyphSettle() {
	}

	/**
	 * The settle progress of one line under a millisecond clock.
	 *
	 * @param elapsedMillis time since the whole block started arriving
	 * @param rowIndex      zero-based line index within the block
	 * @return 0 when the line is pure noise, 1 when it is clean
	 */
	public static double rowProgress(long elapsedMillis, int rowIndex) {
		long start = Math.max(0, rowIndex) * ROW_STAGGER_MILLIS;
		if (elapsedMillis <= start) return 0.0D;
		return Math.clamp((elapsedMillis - start) / (double) ROW_SETTLE_MILLIS, 0.0D, 1.0D);
	}

	/** How long a block of {@code rowCount} lines takes to finish arriving, in milliseconds. */
	public static long blockSettleMillis(int rowCount) {
		return Math.max(0, rowCount - 1) * ROW_STAGGER_MILLIS + ROW_SETTLE_MILLIS;
	}

	/** The reroll bucket a moment falls in. Junk within one bucket is identical. */
	public static long bucket(long nowMillis) {
		return Math.floorDiv(nowMillis, GLYPH_HOLD_MILLIS);
	}

	/**
	 * Whether the character at this position has resolved yet.
	 *
	 * <p>No time term: monotonic in {@code progress} and stable for a given line, so characters
	 * resolve in a fixed order and never unresolve on their own.
	 */
	public static boolean resolved(long seed, int rowIndex, int charIndex, double progress) {
		if (progress >= 1.0D) return true;
		if (progress <= 0.0D) return false;
		return threshold(seed, rowIndex, charIndex) < progress;
	}

	/**
	 * The character to draw at this position.
	 *
	 * <p>Whitespace is never replaced. Word shape survives the whole settle, so an unresolved line
	 * reads as text that has not arrived rather than as a block of noise - and on the questionnaire
	 * it lets a player see that there is an option there before they can see what it says.
	 */
	public static char glyphAt(char original, long seed, int rowIndex, int charIndex,
			double progress, long bucket) {
		if (original == ' ' || original == '\t') return original;
		if (resolved(seed, rowIndex, charIndex, progress)) return original;
		int pick = (int) Math.floorMod(hash(seed, rowIndex, charIndex, bucket), JUNK.length);
		return JUNK[pick];
	}

	/** Convenience for callers that draw a whole line at once. */
	public static String apply(String line, long seed, int rowIndex, double progress, long bucket) {
		if (line == null || line.isEmpty()) return line;
		if (progress >= 1.0D) return line;
		char[] out = line.toCharArray();
		for (int index = 0; index < out.length; index++) {
			out[index] = glyphAt(out[index], seed, rowIndex, index, progress, bucket);
		}
		return new String(out);
	}

	/**
	 * The fixed resolve threshold of one position, in {@code [0, 1)}.
	 *
	 * <p>Derived from the line seed and the position only - never from the clock.
	 */
	private static double threshold(long seed, int rowIndex, int charIndex) {
		long mixed = hash(seed, rowIndex, charIndex, 0L);
		return (mixed >>> 11) / (double) (1L << 53);
	}

	/** SplitMix64 finalizer over the four inputs. Deterministic across client and server. */
	private static long hash(long seed, int rowIndex, int charIndex, long bucket) {
		long value = seed;
		value = value * 0x9E3779B97F4A7C15L + rowIndex * 0xBF58476D1CE4E5B9L;
		value = value * 0x9E3779B97F4A7C15L + charIndex * 0x94D049BB133111EBL;
		value = value * 0x9E3779B97F4A7C15L + bucket * 0xD6E8FEB86659FD93L;
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return value;
	}
}
