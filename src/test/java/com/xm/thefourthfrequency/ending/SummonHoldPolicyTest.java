package com.xm.thefourthfrequency.ending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SummonHoldPolicyTest {
	@Test
	void theTravelIsLongerThanAClickAndShorterThanAnInterrogation() {
		// Below about half a second a hold is producible by an ordinary click, which puts the
		// party's only irreversible press back where it started.
		assertTrue(SummonHoldPolicy.HOLD_MILLIS >= 500L);
		// Past about two seconds it stops reading as a heavy switch and starts reading as the game
		// asking whether they are sure - which is the confirmation dialog this exists to avoid.
		assertTrue(SummonHoldPolicy.HOLD_MILLIS <= 2_000L);
	}

	@Test
	void theePlateFillsFromEmptyToFullAndStaysThere() {
		assertEquals(0.0F, SummonHoldPolicy.progress(0L), 1e-6F);
		assertEquals(0.0F, SummonHoldPolicy.progress(-500L), 1e-6F, "A hold cannot start ahead of itself");
		assertEquals(0.5F, SummonHoldPolicy.progress(SummonHoldPolicy.HOLD_MILLIS / 2), 1e-3F);
		assertEquals(1.0F, SummonHoldPolicy.progress(SummonHoldPolicy.HOLD_MILLIS), 1e-6F);
		assertEquals(1.0F, SummonHoldPolicy.progress(SummonHoldPolicy.HOLD_MILLIS * 10), 1e-6F,
				"A plate held past full must not overflow its own track");
		float previous = -1.0F;
		for (long held = 0L; held <= SummonHoldPolicy.HOLD_MILLIS; held += 20L) {
			float now = SummonHoldPolicy.progress(held);
			assertTrue(now >= previous, "The fill must never travel backwards");
			previous = now;
		}
	}

	@Test
	void nothingCommitsBeforeTheTravelIsDone() {
		assertFalse(SummonHoldPolicy.complete(0L));
		assertFalse(SummonHoldPolicy.complete(SummonHoldPolicy.HOLD_MILLIS - 1L));
		assertTrue(SummonHoldPolicy.complete(SummonHoldPolicy.HOLD_MILLIS));
		// Letting go early cancels and sends nothing. This is the property that makes the hold a
		// guard rather than a delay, and it is one refactor away from silently inverting.
		assertFalse(SummonHoldPolicy.releaseCommits());
	}
}
