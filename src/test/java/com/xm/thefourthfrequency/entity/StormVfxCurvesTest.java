package com.xm.thefourthfrequency.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StormVfxCurvesTest {
	@Test void shockFrontExpandsWhileItsPresenceClosesAtBothEnds() {
		float previous=0;
		for(int frame=0;frame<=550;frame++) {
			float age=frame*.1F;
			float radius=StormVfxCurves.shockRadius(age,55,17);
			float alpha=StormVfxCurves.presence(age,55);
			assertTrue(radius>=previous && radius<=17);
			assertTrue(alpha>=0 && alpha<=1);
			previous=radius;
		}
		assertEquals(0,StormVfxCurves.presence(0,55));
		assertEquals(0,StormVfxCurves.presence(55,55));
		assertEquals(0,StormVfxCurves.presence(56,55));
		assertEquals(17,previous);
	}
	@Test void gatherStrandsHaveDistinctBoundedTravelPhases() {
		assertNotEquals(StormVfxCurves.strandPhase(40,0),StormVfxCurves.strandPhase(40,1));
		for(int i=0;i<12;i++)for(int t=0;t<1000;t++) {
			float phase=StormVfxCurves.strandPhase(t,i);
			assertTrue(phase>=0 && phase<1);
		}
	}
}
