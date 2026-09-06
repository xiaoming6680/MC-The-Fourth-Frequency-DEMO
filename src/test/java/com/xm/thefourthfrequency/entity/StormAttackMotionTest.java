package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StormAttackMotionTest {
	@Test void everyLashTipPeaksOnItsOwnServerDamageTick(){
		for(int strike=0;strike<WorldInterfaceProtocol.TENDRIL_STRIKE_COUNT;strike++){
			float hit=(WorldInterfaceProtocol.TENDRIL_WARNING_TICKS+WorldInterfaceProtocol.TENDRIL_STRIKE_TELEGRAPH_TICKS
					+strike*WorldInterfaceProtocol.TENDRIL_STRIKE_INTERVAL_TICKS)/20F;
			var track=WorldInterfaceClips.TENDRIL_LASH.tracks().stream().filter(t->t.bone().equals("tendril_"+strikeIndex(hit)+"_tip")).findFirst().orElseThrow();
			float largest=-Float.MAX_VALUE,peak=0;
			for(int frame=0;frame<track.channel().frameCount();frame++){
				float value=track.channel().value(frame,0);if(value>largest){largest=value;peak=track.channel().timestamp(frame);}
			}
			assertEquals(hit,peak,.001F,"Tip crack must agree with the damage tick");
			assertTrue(WorldInterfaceClips.TENDRIL_LASH.lengthSeconds()>hit+.5F,"No room for the last strike's recovery");
		}
	}
	private static int strikeIndex(float hit){return Math.round((hit*20-WorldInterfaceProtocol.TENDRIL_WARNING_TICKS-WorldInterfaceProtocol.TENDRIL_STRIKE_TELEGRAPH_TICKS)/WorldInterfaceProtocol.TENDRIL_STRIKE_INTERVAL_TICKS);}
	@Test void laserChargeKeepsBuildingUntilEmissionAndThenSettlesContinuously(){
		long fire=WorldInterfaceProtocol.LASER_WARNING_TICKS*50L;
		assertTrue(WorldInterfaceRig.actionCharge(1,fire-500)>WorldInterfaceRig.actionCharge(1,2000));
		assertEquals(1,WorldInterfaceRig.actionCharge(1,fire),1e-6);
		assertTrue(WorldInterfaceRig.actionCharge(1,fire+400)>0);
		assertEquals(-1,WorldInterfaceRig.actionCharge(1,fire+WorldInterfaceRig.ACTION_CHARGE_RELEASE_MILLIS));
	}
}
