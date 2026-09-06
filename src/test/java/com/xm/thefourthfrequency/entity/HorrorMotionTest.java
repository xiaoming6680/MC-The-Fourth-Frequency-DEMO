package com.xm.thefourthfrequency.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HorrorMotionTest {
	@Test
	void spiderFeetRemainOnTheirSupportPlaneThroughoutStance() {
		for (int frame = 0; frame < 1200; frame++) {
			float phase = frame * (float) Math.PI / 600;
			var pose = HorrorMotion.spiderLeg(phase, 1, .35F);
			double x = 8 * Math.cos(pose.hip()) + 17.5 * Math.cos(pose.hip() + pose.knee());
			double y = 8 * Math.sin(pose.hip()) + 17.5 * Math.sin(pose.hip() + pose.knee());
			assertEquals(10.8, x, 1e-4);
			assertEquals(9.85 - pose.lift(), y, 1e-4);
			if (pose.planted()) assertEquals(0, pose.lift(), 1e-5);
		}
	}
	@Test
	void everyGaitPhaseRetainsSupportOnBothSidesAndHasNoLoopJump() {
		for (int frame = 0; frame < 720; frame++) {
			int left = 0, right = 0;
			for (int leg = 0; leg < 8; leg++) {
				boolean r = leg % 2 == 0;
				float phase = frame * .01F + ((leg / 2 + (r ? 0 : 2)) % 4) * (float) Math.PI / 2;
				if (HorrorMotion.spiderLeg(phase, 1, 0).planted()) { if (r) right++; else left++; }
			}
			assertTrue(left >= 2 && right >= 2, "unsupported gait at frame " + frame);
		}
		var before = HorrorMotion.spiderLeg((float) (Math.PI * 2 - .0001), 1, 0);
		var after = HorrorMotion.spiderLeg(.0001F, 1, 0);
		assertEquals(before.hip(), after.hip(), 1e-4);
		assertEquals(before.knee(), after.knee(), 1e-4);
		assertEquals(before.yaw(), after.yaw(), 1e-4);
	}
	@Test
	void fingersHoldTheirCurlAndReturnWithoutASnap() {
		assertTrue(HorrorMotion.digitCurl(65,0,1) > HorrorMotion.digitCurl(12,0,1) + .6F);
		for (int i = 0; i < 48; i++) for (int tick = 0; tick < 400; tick++) {
			assertTrue(Math.abs(HorrorMotion.digitCurl(tick + .1F,i,1) - HorrorMotion.digitCurl(tick,i,1)) < .02F);
		}
	}
}
