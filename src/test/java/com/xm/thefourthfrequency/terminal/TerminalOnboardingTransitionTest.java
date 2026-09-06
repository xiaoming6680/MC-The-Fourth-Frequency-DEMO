package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalOnboardingTransitionTest {
	/**
	 * The bar must never teleport, which is the property that keeps it out of the 3 Hz rule.
	 *
	 * <p>The whole point of this curve is that it stutters, and the tempting way to write a stutter is
	 * discrete jumps. A jump is a visible state change and owes a seven-tick hold; the seam is a few
	 * hundred milliseconds long, so a compliant stepped bar would have about two steps. This asserts
	 * the alternative actually holds: sampled far finer than a frame, the fill never moves far in one
	 * sample, so there is no jump to owe a hold for.
	 */
	@Test
	void theBarStuttersWithoutEverJumping() {
		for (long seed = 0; seed < 40; seed++) {
			double previous = 0.0D;
			for (int step = 0; step <= 2000; step++) {
				double value = TerminalOnboardingTransition.stutteredProgress(step / 2000.0D, seed);
				assertTrue(value - previous < 0.02D,
						"seed " + seed + " jumped " + (value - previous) + " in one sample");
				previous = value;
			}
		}
	}

	/** A loading bar that loses ground reads as a bug, not as age. */
	@Test
	void theBarOnlyEverMovesForwardAndFinishesFull() {
		for (long seed = 0; seed < 40; seed++) {
			double previous = -1.0D;
			for (int step = 0; step <= 500; step++) {
				double value = TerminalOnboardingTransition.stutteredProgress(step / 500.0D, seed);
				assertTrue(value >= previous, "seed " + seed + " went backwards");
				assertTrue(value >= 0.0D && value <= 1.0D);
				previous = value;
			}
			assertEquals(0.0D, TerminalOnboardingTransition.stutteredProgress(0.0D, seed));
			assertEquals(1.0D, TerminalOnboardingTransition.stutteredProgress(1.0D, seed));
			// Out-of-range input cannot drive it past either end.
			assertEquals(0.0D, TerminalOnboardingTransition.stutteredProgress(-5.0D, seed));
			assertEquals(1.0D, TerminalOnboardingTransition.stutteredProgress(9.0D, seed));
		}
	}

	/**
	 * It has to actually hesitate, or it is just a linear bar with extra arithmetic.
	 *
	 * <p>Checked as the gap between the slowest stretch and the fastest: a linear fill has a ratio of
	 * one, and what makes this read as old hardware is that some stretch of the track crawls while
	 * another covers ground several times faster.
	 */
	@Test
	void someStretchesCrawlAndOthersSurge() {
		int stalled = 0;
		for (long seed = 0; seed < 40; seed++) {
			double slowest = Double.MAX_VALUE;
			double fastest = 0.0D;
			for (int quarter = 0; quarter < 4; quarter++) {
				double from = TerminalOnboardingTransition.stutteredProgress(quarter / 4.0D, seed);
				double to = TerminalOnboardingTransition.stutteredProgress((quarter + 1) / 4.0D, seed);
				double covered = to - from;
				slowest = Math.min(slowest, covered);
				fastest = Math.max(fastest, covered);
			}
			assertTrue(fastest > slowest, "seed " + seed + " filled evenly");
			if (slowest < 0.10D) stalled++;
		}
		assertTrue(stalled > 20, "most seeds should produce a stretch that visibly crawls");
	}

	/** Two different scenes must not stutter identically, or the shape becomes a signature. */
	@Test
	void differentScenesStutterDifferently() {
		assertNotEquals(TerminalOnboardingTransition.stutteredProgress(0.5D, 11L),
				TerminalOnboardingTransition.stutteredProgress(0.5D, 12L));
		// And one scene is stable across frames: the same input is always the same output.
		assertEquals(TerminalOnboardingTransition.stutteredProgress(0.37D, 99L),
				TerminalOnboardingTransition.stutteredProgress(0.37D, 99L));
	}

	/**
	 * The blank, then the lines, in that order and only once.
	 *
	 * <p>The gap is what stops a scene change from reading as a dropped frame, so nothing may be drawn
	 * during it - and the first line may not start arriving until it is over.
	 */
	@Test
	void nothingArrivesUntilTheBlankIsOver() {
		assertTrue(TerminalOnboardingTransition.blank(0L));
		assertTrue(TerminalOnboardingTransition.blank(TerminalOnboardingTransition.BLANK_MILLIS - 1L));
		assertFalse(TerminalOnboardingTransition.blank(TerminalOnboardingTransition.BLANK_MILLIS));
		assertEquals(0.0D, TerminalOnboardingTransition.rowProgress(
				TerminalOnboardingTransition.BLANK_MILLIS, 0));
		assertTrue(TerminalOnboardingTransition.rowProgress(
				TerminalOnboardingTransition.BLANK_MILLIS + 1L, 0) > 0.0D);
		// Later rows lag, and the last one finishes exactly when the scene is declared settled.
		assertEquals(0.0D, TerminalOnboardingTransition.rowProgress(
				TerminalOnboardingTransition.BLANK_MILLIS + 1L, 3));
		int rows = 6;
		assertEquals(1.0D, TerminalOnboardingTransition.rowProgress(
				TerminalOnboardingTransition.totalMillis(rows), rows - 1));
		assertTrue(TerminalOnboardingTransition.settled(
				TerminalOnboardingTransition.totalMillis(rows), rows));
		assertFalse(TerminalOnboardingTransition.settled(
				TerminalOnboardingTransition.totalMillis(rows) - 1L, rows));
	}

	/**
	 * The scene key changes exactly once per step, and for nothing else.
	 *
	 * <p>It is what restarts the transition clock, so a key that missed a step would hard-cut that one
	 * change, and a key that changed spuriously would restart the animation under a screen that had
	 * not moved.
	 */
	@Test
	void everyStepOfTheStartupChainIsItsOwnScene() {
		java.util.Set<Integer> keys = new java.util.HashSet<>();
		keys.add(TerminalOnboardingTransition.sceneKey(TerminalOnboardingPolicy.Phase.BOOT, -1));
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			assertTrue(keys.add(TerminalOnboardingTransition.sceneKey(
					TerminalOnboardingPolicy.Phase.PROFILE, question)),
					"question " + question + " must be its own scene");
		}
		// The acknowledgement is a scene of its own, distinct from every question.
		assertTrue(keys.add(TerminalOnboardingTransition.sceneKey(
				TerminalOnboardingPolicy.Phase.PROFILE, -1)));
		for (TerminalOnboardingPolicy.Phase phase : new TerminalOnboardingPolicy.Phase[]{
				TerminalOnboardingPolicy.Phase.STEP_1, TerminalOnboardingPolicy.Phase.STEP_2,
				TerminalOnboardingPolicy.Phase.STEP_3, TerminalOnboardingPolicy.Phase.STEP_4}) {
			assertTrue(keys.add(TerminalOnboardingTransition.sceneKey(phase, -1)), phase.name());
		}
		// Stable: asking twice about the same screen must not restart anything.
		assertEquals(TerminalOnboardingTransition.sceneKey(TerminalOnboardingPolicy.Phase.PROFILE, 2),
				TerminalOnboardingTransition.sceneKey(TerminalOnboardingPolicy.Phase.PROFILE, 2));
		assertEquals(TerminalOnboardingTransition.sceneKey(TerminalOnboardingPolicy.Phase.DONE, -1),
				TerminalOnboardingTransition.sceneKey(TerminalOnboardingPolicy.Phase.RELEASED, -1));
	}
}
