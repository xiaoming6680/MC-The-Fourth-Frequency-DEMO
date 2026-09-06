package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.ending.WorldInterfacePhasePressure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldInterfaceMouthTest {
	@Test
	void movingOnlyTheJawMovesTheMouthWithoutMovingTheSkull() {
		for (int form = 0; form < 3; form++) {
			for (int head = 0; head < 3; head++) {
				var before = WorldInterfaceRig.restPose(form);
				var after = WorldInterfaceRig.restPose(form);
				after.bone(WorldInterfaceRig.HEAD_PREFIX[head] + "_jaw").xRot += 0.5F;
				assertEquals(0, before.headOffset(head).distanceTo(after.headOffset(head)), 1e-6);
				assertTrue(before.mouthOffset(head).distanceTo(after.mouthOffset(head)) > 0.2,
						"The muzzle must move when the jaw opens even if the skull holds still");
				for (int other = 0; other < 3; other++) {
					if (other != head) assertEquals(0,
							before.mouthOffset(other).distanceTo(after.mouthOffset(other)), 1e-6);
				}
			}
		}
	}

	@Test
	void orbReleaseOpensTheCentralMouthAtLaunch() {
		long launch = com.xm.thefourthfrequency.networking.WorldInterfaceProtocol.ORB_WARNING_TICKS * 50L;
		var idle = WorldInterfaceRig.pose(1, 80, 1, 0, launch);
		var firing = WorldInterfaceRig.pose(1, 80, 1, 2, launch);
		assertTrue(firing.bone("center_jaw").xRot > idle.bone("center_jaw").xRot + 0.4);
	}

	@Test
	void laserOpensAllThreeJawsAndUsesSeparateFlankingMouths() {
		var idle = WorldInterfaceRig.pose(2, 160, 1, 0, 4500);
		var firing = WorldInterfaceRig.pose(2, 160, 1, 1, 4500);
		for (String head : WorldInterfaceRig.HEAD_PREFIX) {
			assertTrue(firing.bone(head + "_jaw").xRot > idle.bone(head + "_jaw").xRot + 0.4,
					"Y-down model space requires positive pitch to lower the mandible");
		}
		assertTrue(firing.mouthOffset(1).distanceTo(firing.mouthOffset(2)) > 1);
		assertEquals(0, WorldInterfacePhasePressure.laserHead(0, 0));
		assertEquals(0, WorldInterfacePhasePressure.laserHead(1, 0));
		assertEquals(1, WorldInterfacePhasePressure.laserHead(2, 0));
		assertEquals(2, WorldInterfacePhasePressure.laserHead(2, 1));
	}
}
