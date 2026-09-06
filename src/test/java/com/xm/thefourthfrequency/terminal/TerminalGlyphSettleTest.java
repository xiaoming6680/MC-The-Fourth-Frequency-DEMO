package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalGlyphSettleTest {
	private static final String LINE = "SIGNAL RECOVERED AT 0412";
	private static final long SEED = 0x5EEDL;

	/**
	 * The line has to end up readable, and stay readable.
	 *
	 * <p>Everything else here is about the arrival; this is about the destination. A settle that left
	 * one character permanently scrambled would put a standing corruption on the records page, and
	 * the records page is the surface the player is supposed to be able to trust to be legible even
	 * when they cannot trust what it says.
	 */
	@Test
	void aFinishedLineIsExactlyTheOriginal() {
		assertEquals(LINE, TerminalGlyphSettle.apply(LINE, SEED, 0, 1.0D, 0L));
		assertEquals(LINE, TerminalGlyphSettle.apply(LINE, SEED, 0, 1.0D, 999L));
		assertEquals(LINE, TerminalGlyphSettle.apply(LINE, SEED, 3, 4.0D, 17L));
	}

	/** At zero progress nothing has arrived yet, but the shape of the text has. */
	@Test
	void anUnstartedLineIsFullyJunkExceptItsSpacing() {
		String junk = TerminalGlyphSettle.apply(LINE, SEED, 0, 0.0D, 0L);
		assertEquals(LINE.length(), junk.length());
		for (int index = 0; index < LINE.length(); index++) {
			char original = LINE.charAt(index);
			char shown = junk.charAt(index);
			if (original == ' ') {
				assertEquals(' ', shown, "spacing must survive so the line still reads as text");
			} else {
				assertNotEquals(original, shown, "index " + index + " should not have arrived yet");
				assertFalse(Character.isLetterOrDigit(shown),
						"junk must not be mistakable for content: " + shown);
			}
		}
	}

	/**
	 * The reason this cannot strobe.
	 *
	 * <p>Whether a character has resolved is a pure function of the seed, the position and the
	 * progress - there is no time term in it at all. So characters resolve in a fixed order, never
	 * un-resolve as progress rises, and cannot flicker at any frame rate. The questionnaire leans on
	 * the same property from the other direction: a player easing the slider onto a station watches
	 * the option assemble in a stable order rather than shimmer.
	 */
	@Test
	void resolutionIsMonotonicAndHasNoClockInIt() {
		for (int index = 0; index < LINE.length(); index++) {
			boolean seenResolved = false;
			for (int step = 0; step <= 100; step++) {
				double progress = step / 100.0D;
				boolean resolved = TerminalGlyphSettle.resolved(SEED, 0, index, progress);
				if (seenResolved) {
					assertTrue(resolved, "index " + index + " un-resolved at progress " + progress);
				}
				seenResolved |= resolved;
				// Same answer whatever the clock says.
				assertEquals(resolved, TerminalGlyphSettle.resolved(SEED, 0, index, progress));
			}
			assertTrue(seenResolved, "index " + index + " never resolves");
		}
	}

	/**
	 * The 3 Hz ceiling, asserted where it is actually enforced.
	 *
	 * <p>7 ticks is 350 ms and 3 Hz is 6.67 ticks, so the hold rounds up rather than down. Junk is
	 * identical inside one bucket, which is what makes the reroll rate a property of the class
	 * instead of a rule every caller has to remember.
	 */
	@Test
	void junkIsHeldForAtLeastSevenTicks() {
		assertTrue(TerminalGlyphSettle.GLYPH_HOLD_MILLIS >= 350L,
				"7 ticks at 20 tps is the 3 Hz ceiling");
		long start = TerminalGlyphSettle.GLYPH_HOLD_MILLIS * 12;
		String first = TerminalGlyphSettle.apply(LINE, SEED, 0, 0.0D, TerminalGlyphSettle.bucket(start));
		String sameBucket = TerminalGlyphSettle.apply(LINE, SEED, 0, 0.0D,
				TerminalGlyphSettle.bucket(start + TerminalGlyphSettle.GLYPH_HOLD_MILLIS - 1));
		assertEquals(first, sameBucket, "junk must not change inside one hold");
		String nextBucket = TerminalGlyphSettle.apply(LINE, SEED, 0, 0.0D,
				TerminalGlyphSettle.bucket(start + TerminalGlyphSettle.GLYPH_HOLD_MILLIS));
		assertNotEquals(first, nextBucket, "junk should churn across holds");
	}

	/**
	 * Lines arrive one after another, not as a single fade.
	 *
	 * <p>A fade is what a user interface does. Staggered resolution is what a page being received
	 * does, and the backfill is supposed to read as the second thing.
	 */
	@Test
	void laterRowsLagEarlierOnes() {
		long midway = TerminalGlyphSettle.ROW_SETTLE_MILLIS / 2;
		assertTrue(TerminalGlyphSettle.rowProgress(midway, 0)
				> TerminalGlyphSettle.rowProgress(midway, 1));
		assertEquals(0.0D, TerminalGlyphSettle.rowProgress(0L, 4));
		// The block is not finished until its last row is.
		int rows = 8;
		long block = TerminalGlyphSettle.blockSettleMillis(rows);
		assertEquals(1.0D, TerminalGlyphSettle.rowProgress(block, rows - 1));
	}

	/** Different lines get different noise, or a screenful would resolve in identical patterns. */
	@Test
	void rowsDoNotShareAPattern() {
		assertNotEquals(
				TerminalGlyphSettle.apply(LINE, SEED, 0, 0.5D, 3L),
				TerminalGlyphSettle.apply(LINE, SEED, 1, 0.5D, 3L));
	}
}
