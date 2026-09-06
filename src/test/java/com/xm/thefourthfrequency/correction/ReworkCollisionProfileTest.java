package com.xm.thefourthfrequency.correction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReworkCollisionProfileTest {
	@Test
	void threeProfilesMatchTheRenderedHeightsAndStaySlender() {
		float[] widths = {0.72F, 0.76F, 0.80F};
		float[] heights = {2.45F, 2.75F, 3.15F};
		float[] eyes = {2.23F, 2.53F, 2.93F};
		for (int stage = 1; stage <= 3; stage++) {
			ReworkCollisionProfile profile = ReworkCollisionProfile.forStage(stage);
			assertEquals(widths[stage - 1], profile.width());
			assertEquals(heights[stage - 1], profile.height());
			assertEquals(eyes[stage - 1], profile.eyeHeight());
			assertTrue(profile.width() < 1.0F);
			assertTrue(profile.eyeHeight() < profile.height());
		}
	}

	@Test
	void stagesClampAtBothEnds() {
		assertEquals(ReworkCollisionProfile.forStage(1), ReworkCollisionProfile.forStage(Integer.MIN_VALUE));
		assertEquals(ReworkCollisionProfile.forStage(3), ReworkCollisionProfile.forStage(Integer.MAX_VALUE));
	}
}
