package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalSelfTestTest {
	@Test
	void theCheckIsShortEnoughToSitThroughHundredsOfTimes() {
		assertEquals(TerminalSelfTest.LINE_COUNT * TerminalSelfTest.LINE_MILLIS,
				TerminalSelfTest.TOTAL_MILLIS);
		// The budget the whole design rests on. Past about a second this stops reading as a device
		// waking up and starts reading as the terminal being slow to open, several hundred times a
		// run - and it must stay comfortably under the first-boot ceremony it is not trying to be.
		assertTrue(TerminalSelfTest.TOTAL_MILLIS <= 1_000L,
				"A check that runs on every open cannot cost a second");
		assertTrue(TerminalSelfTest.TOTAL_MILLIS < TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS,
				"The per-open check must stay shorter than the one-shot first boot");
		assertTrue(TerminalSelfTest.CHAR_MILLIS < TerminalOnboardingPolicy.BOOT_CHAR_MILLIS,
				"Four lines is a glance; it prints faster than the six-line report");
	}

	@Test
	void linesArriveOneAtATimeAndStopAtTheLast() {
		assertEquals(0, TerminalSelfTest.visibleLines(-1L));
		assertEquals(1, TerminalSelfTest.visibleLines(0L));
		for (int line = 0; line < TerminalSelfTest.LINE_COUNT; line++) {
			assertEquals(line + 1, TerminalSelfTest.visibleLines(line * TerminalSelfTest.LINE_MILLIS));
		}
		assertEquals(TerminalSelfTest.LINE_COUNT,
				TerminalSelfTest.visibleLines(TerminalSelfTest.TOTAL_MILLIS * 9));
		assertFalse(TerminalSelfTest.finished(TerminalSelfTest.TOTAL_MILLIS - 1L));
		assertTrue(TerminalSelfTest.finished(TerminalSelfTest.TOTAL_MILLIS));
	}

	@Test
	void aLineStartsBlankAndFinishesPrinted() {
		int codePoints = 12;
		assertEquals(0, TerminalSelfTest.typedCharacters(codePoints, 0L, 1),
				"A line that has not started must show nothing");
		assertEquals(codePoints, TerminalSelfTest.typedCharacters(codePoints,
				TerminalSelfTest.LINE_MILLIS + codePoints * TerminalSelfTest.CHAR_MILLIS, 1));
		// Every line has to finish printing inside the budget, or the check ends mid-character.
		assertEquals(codePoints, TerminalSelfTest.typedCharacters(codePoints,
				TerminalSelfTest.TOTAL_MILLIS, TerminalSelfTest.LINE_COUNT - 1));
	}

	/**
	 * The baseline reading is the only line that is not a restatement of something already on the
	 * panel, and its value has to be the real one - it is read off the same constant that pitches
	 * every press. A figure invented here would be a fault the mod made up, which is the one thing
	 * the terminal is not allowed to do.
	 */
	@Test
	void theBaselineReadingIsThePitchTablesOwnNumber() {
		assertTrue(TerminalSelfTest.baselineCalibrated(0));
		assertEquals(0, TerminalSelfTest.baselineDriftPercent(0));
		for (int stage = 1; stage <= TerminalContactVoice.MAX_STAGE; stage++) {
			assertFalse(TerminalSelfTest.baselineCalibrated(stage),
					"A stage that pitches the panel down must not report as calibrated");
			int expected = Math.round(TerminalContactVoice.WEAR_PER_STAGE * stage * 100.0F);
			assertEquals(expected, TerminalSelfTest.baselineDriftPercent(stage));
			// The same figure, arrived at from the other side: what the player actually hears.
			float voiced = TerminalContactVoice.MOVE.pitch() - TerminalContactVoice.MOVE.pitchAt(stage);
			assertEquals(expected, Math.round(voiced / TerminalContactVoice.MOVE.pitch() * 100.0F),
					"The printed drift must be the drift in the speakers");
		}
		assertEquals(TerminalSelfTest.baselineDriftPercent(TerminalContactVoice.MAX_STAGE),
				TerminalSelfTest.baselineDriftPercent(99), "Stages past the top clamp, not overflow");
		assertEquals(0, TerminalSelfTest.baselineDriftPercent(-4));
	}

	@Test
	void theCheckStandsDownForTheWalkthroughAndAlwaysGivesWayToInput() {
		assertFalse(TerminalSelfTest.playsOnOpen(true),
				"The first boot has its own self test; two in a row is one too many");
		assertTrue(TerminalSelfTest.playsOnOpen(false));
		assertTrue(TerminalSelfTest.skippable(),
				"Nothing that runs on every open may hold the player for even half a second");
	}
}
