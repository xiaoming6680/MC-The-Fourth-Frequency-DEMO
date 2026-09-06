package com.xm.thefourthfrequency.client_ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModVolumePolicyTest {
	@Test
	void snappingKeepsBothEndsOfTheSliderReachable() {
		assertEquals(0.0D, ModVolumePolicy.snap(0.0D));
		assertEquals(1.0D, ModVolumePolicy.snap(1.0D));
		// A slider dragged past either end is still a value the player asked for.
		assertEquals(0.0D, ModVolumePolicy.snap(-4.0D));
		assertEquals(1.0D, ModVolumePolicy.snap(8.0D));
	}

	@Test
	void everyDetentIsLandedOnExactly() {
		for (int step = 0; step <= 20; step++) {
			double detent = step * ModVolumePolicy.STEP;
			// Approached from either side, a detent has to resolve to the same stored number, or the
			// config file records a different volume depending on which way the handle was moving.
			assertEquals(ModVolumePolicy.percent(detent),
					ModVolumePolicy.percent(detent - ModVolumePolicy.STEP * 0.4D));
			assertEquals(ModVolumePolicy.percent(detent),
					ModVolumePolicy.percent(detent + ModVolumePolicy.STEP * 0.4D));
			assertEquals(step * 5, ModVolumePolicy.percent(detent));
		}
	}

	@Test
	void percentIsReadOffTheStoredValueRatherThanTheRawOne() {
		// The label and the file must agree: 0.77 stores as 0.75 and therefore has to read as 75%.
		assertEquals(75, ModVolumePolicy.percent(0.77D));
		assertEquals(0.75D, ModVolumePolicy.snap(0.77D), 1.0E-9D);
		assertEquals(80, ModVolumePolicy.percent(ModVolumePolicy.DEFAULT));
	}

	@Test
	void mutedIsDecidedAfterSnappingSoTheFarLeftCountsAsSilence() {
		assertTrue(ModVolumePolicy.muted(0.0D));
		assertTrue(ModVolumePolicy.muted(3.0E-17D));
		assertTrue(ModVolumePolicy.muted(0.02D));
		assertFalse(ModVolumePolicy.muted(0.05D));
		assertFalse(ModVolumePolicy.muted(ModVolumePolicy.DEFAULT));
	}

	@Test
	void anUnreadableValueFallsBackToTheDefaultRatherThanToSilence() {
		assertEquals(ModVolumePolicy.DEFAULT, ModVolumePolicy.snap(Double.NaN));
		assertEquals(ModVolumePolicy.DEFAULT, ModVolumePolicy.snap(Double.POSITIVE_INFINITY));
	}
}
