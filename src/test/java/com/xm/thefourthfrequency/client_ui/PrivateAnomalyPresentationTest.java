package com.xm.thefourthfrequency.client_ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateAnomalyPresentationTest {
	@Test void slowTerrainLoadingCannotConsumeThePrivateEvent() {
		var scene = new PrivateAnomalyPresentation();
		scene.accept("continuity", -1);
		for (int i = 0; i < 600; i++) scene.advance(false);
		assertEquals(100, scene.remaining());
		assertEquals("continuity", scene.id());
		assertEquals(3, scene.variant());
		for (int i = 0; i < 99; i++) scene.advance(true);
		assertEquals("continuity", scene.id());
		scene.advance(true);
		assertEquals("none", scene.id());
		assertEquals(0, scene.remaining());
	}

	@Test void leavingTheServerCannotCarryAnEventIntoAnotherSession() {
		var scene = new PrivateAnomalyPresentation();
		scene.accept("continuity", 2);
		scene.clear();
		scene.advance(true);
		assertEquals("none", scene.id());
		assertEquals(0, scene.remaining());
		assertEquals(0, scene.variant());
		scene.accept("continuity", 1);
		assertEquals(100, scene.remaining());
	}
}
