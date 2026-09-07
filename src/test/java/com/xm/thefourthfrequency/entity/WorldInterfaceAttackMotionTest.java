package com.xm.thefourthfrequency.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldInterfaceAttackMotionTest {
	@Test void beamDamageEndsBeforeTheRecoveryPose() {
		assertEquals(1, WorldInterfaceAttackMotion.laserPhase(89));
		assertEquals(2, WorldInterfaceAttackMotion.laserPhase(90));
		assertEquals(2, WorldInterfaceAttackMotion.laserPhase(129));
		assertEquals(3, WorldInterfaceAttackMotion.laserPhase(130));
		assertEquals(3, WorldInterfaceAttackMotion.laserPhase(147));
		assertEquals(0, WorldInterfaceAttackMotion.laserPhase(148));
		assertEquals(0, WorldInterfaceAttackMotion.laserPhase(-1));
	}

	@Test void everyFiringJawStaysOpenUntilBeamCutoffThenSettles() {
		for (int form=0; form<3; form++) {
			for (String head : WorldInterfaceRig.HEAD_PREFIX) {
				float idle=WorldInterfaceRig.pose(form,100,1,0,0).bone(head+"_jaw").xRot;
				for (long time=4500;time<=6500;time+=50) {
					float jaw=WorldInterfaceRig.pose(form,100,1,1,time).bone(head+"_jaw").xRot;
					assertTrue(jaw-idle>.7,"Firing jaw prematurely closed: "+form+"/"+head+"/"+time);
				}
				float recovered=WorldInterfaceRig.pose(form,100,1,1,7400).bone(head+"_jaw").xRot;
				assertEquals(idle,recovered,1e-5);
			}
		}
	}

	@Test void aimAndApertureUseTheCompleteActionLength() {
		for (var clip : WorldInterfaceClips.clipsForAction(1))
			assertEquals(WorldInterfaceAttackMotion.LASER_DURATION_TICKS/20.0F,clip.lengthSeconds(),1e-5);
		assertEquals(1,WorldInterfaceAttackMotion.laserCharge(6499),1e-5);
		assertTrue(WorldInterfaceAttackMotion.laserCharge(7000)>0);
		assertEquals(-1,WorldInterfaceAttackMotion.laserCharge(7400));
	}

	@Test void distalLimbArrivesOnEachStrikeAndProximalJointsLeadIt() {
		for (int strike=0;strike<3;strike++) {
			float at=WorldInterfaceAttackMotion.tendrilStrikeTick(strike)/20.0F;
			assertEquals(at,WorldInterfaceAttackMotion.tendrilJointContactSeconds(strike,15,15),1e-5);
			assertTrue(WorldInterfaceAttackMotion.tendrilJointContactSeconds(strike,1,15)<at);
		}
	}
}
