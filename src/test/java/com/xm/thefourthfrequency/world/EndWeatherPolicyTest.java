package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The End's weather, asserted at the product endpoints rather than at its formulas.
 *
 * <p>What is pinned here is what a player would notice going wrong: rain heavy enough to fight
 * through, a swell slow enough not to read as a flicker, and thunder that is neither a metronome
 * nor a double clap.
 */
final class EndWeatherPolicyTest {
	/** A full in-game day, which is long enough for every slot pattern to have shown itself. */
	private static final long DAY_TICKS = 24_000L;

	@Test
	void rainArrivesOverTheFadeAndThenStays() {
		// Nothing on the frame the player lands in the End. Weather that is simply already at full
		// strength on arrival reads as a setting being applied, not as walking into a storm.
		assertEquals(0.0F, EndWeatherPolicy.rainLevel(0L, 0L), 1.0E-6F);
		float previous = -1.0F;
		for (long tick = 0L; tick <= EndWeatherPolicy.FADE_IN_TICKS; tick++) {
			// Held on one game time so the fade is measured on its own, without the breath moving
			// underneath it.
			float level = EndWeatherPolicy.rainLevel(tick, 0L);
			assertTrue(level >= previous, "the fade in must never go backwards, at tick " + tick);
			previous = level;
		}
		assertEquals(EndWeatherPolicy.breathing(0L),
				EndWeatherPolicy.rainLevel(EndWeatherPolicy.FADE_IN_TICKS, 0L), 1.0E-6F);
		// And it stays there rather than climbing further the longer somebody stands in the End.
		assertEquals(EndWeatherPolicy.breathing(0L), EndWeatherPolicy.rainLevel(DAY_TICKS, 0L), 1.0E-6F);
	}

	/**
	 * The rain is weather, not a wall.
	 *
	 * <p>The End is where the finale is fought, through a screen that already carries a HUD, a lock
	 * treatment and a dispersion filter on the frames a beam lands. A rain level creeping toward
	 * vanilla's thunderstorm 1.0 would be one more thing between the player and the arena, and it is
	 * the kind of number that gets nudged up once for atmosphere and never nudged back.
	 */
	@Test
	void theSwellStaysInsideAReadableBand() {
		for (long tick = 0L; tick < DAY_TICKS; tick++) {
			float level = EndWeatherPolicy.breathing(tick);
			assertTrue(level > 0.0F && level <= 0.7F,
					"the End's rain reached " + level + " at tick " + tick);
		}
	}

	/**
	 * The swell may not read as a flicker.
	 *
	 * <p>The world bible caps coherent change at 3 Hz, and rain intensity is about as coherent a
	 * change as a frame can carry - it moves every pixel of the sky at once. This is the continuous
	 * form of that rule: the level may drift, but it may not step.
	 */
	@Test
	void theSwellNeverStepsHardEnoughToRead() {
		for (long tick = 1L; tick < DAY_TICKS; tick++) {
			float delta = Math.abs(EndWeatherPolicy.breathing(tick) - EndWeatherPolicy.breathing(tick - 1L));
			assertTrue(delta < 0.005F, "the rain level jumped by " + delta + " in one tick at " + tick);
		}
	}

	/** Thunder is weather, so it is irregular - but it is never a metronome and never a double clap. */
	@Test
	void thunderIsIrregularWithoutEverDoublingUp() {
		long previous = Long.MIN_VALUE;
		long claps = 0L;
		long shortestGap = Long.MAX_VALUE;
		long longestGap = 0L;
		for (long tick = 0L; tick < DAY_TICKS * 5L; tick++) {
			if (!EndWeatherPolicy.isThunderTick(tick)) continue;
			claps++;
			if (previous != Long.MIN_VALUE) {
				long gap = tick - previous;
				shortestGap = Math.min(shortestGap, gap);
				longestGap = Math.max(longestGap, gap);
			}
			previous = tick;
		}
		assertTrue(claps > 0L, "it never thundered at all");
		// Two claps inside four seconds is a bug, not weather. The slot guard exists for this.
		assertTrue(shortestGap >= 80L, "two claps landed " + shortestGap + " ticks apart");
		// And it must not go silent for minutes on end either, or the storm stops being one.
		assertTrue(longestGap <= 3_000L, "the sky went quiet for " + longestGap + " ticks");
		// Roughly one clap every twenty to sixty seconds across the whole sample. "Now and then":
		// often enough to be a storm, rare enough that it is never the thing you are listening to.
		double averageGap = DAY_TICKS * 5.0D / claps;
		assertTrue(averageGap >= 400.0D && averageGap <= 1_200.0D,
				"thunder averages one clap every " + averageGap + " ticks");
	}

	/** Every clap is the same clap for every client, because the schedule is the world clock. */
	@Test
	void theScheduleIsPurelyDerivedFromTheClock() {
		for (long tick = 0L; tick < 5_000L; tick++) {
			assertEquals(EndWeatherPolicy.isThunderTick(tick), EndWeatherPolicy.isThunderTick(tick),
					"the schedule is not stable at tick " + tick);
			assertEquals(EndWeatherPolicy.thunderPitch(tick), EndWeatherPolicy.thunderPitch(tick));
			assertEquals(EndWeatherPolicy.thunderBearing(tick), EndWeatherPolicy.thunderBearing(tick));
		}
		// A world that has not started yet has no weather. Guarded because floorDiv and floorMod are
		// perfectly happy with negative ticks and would schedule claps into a clock that runs
		// backwards - which is what a fresh level looks like for exactly one tick.
		assertFalse(EndWeatherPolicy.isThunderTick(-1L));
	}

	/** Pitch and bearing stay inside the ranges the sound protocol and a circle actually have. */
	@Test
	void everyClapIsPlayableAndPointsSomewhere() {
		boolean pitchVaried = false;
		float firstPitch = EndWeatherPolicy.thunderPitch(0L);
		for (long tick = 0L; tick < DAY_TICKS; tick += EndWeatherPolicy.THUNDER_SLOT_TICKS) {
			float pitch = EndWeatherPolicy.thunderPitch(tick);
			assertTrue(pitch >= 0.5F && pitch <= 2.0F, "unplayable thunder pitch " + pitch);
			float bearing = EndWeatherPolicy.thunderBearing(tick);
			assertTrue(bearing >= 0.0F && bearing < (float) (Math.PI * 2.0D) + 1.0E-4F,
					"thunder bearing outside the circle: " + bearing);
			if (Math.abs(pitch - firstPitch) > 1.0E-4F) pitchVaried = true;
		}
		// One sample at one pitch fifteen times over a ten-minute fight is a loop the player can
		// hear repeating, which is the opposite of weather.
		assertTrue(pitchVaried, "every clap of thunder is pitched identically");
	}
}
