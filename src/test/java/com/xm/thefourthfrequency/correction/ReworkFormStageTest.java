package com.xm.thefourthfrequency.correction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReworkFormStageTest {
	@Test
	void personalPursuitProgressUsesThreeMonotonicForms() {
		assertEquals(1, ReworkFormStage.forResolvedChases(0));
		assertEquals(2, ReworkFormStage.forResolvedChases(1));
		assertEquals(3, ReworkFormStage.forResolvedChases(2));
		assertEquals(3, ReworkFormStage.forResolvedChases(3));
		assertEquals(3, ReworkFormStage.forResolvedChases(4));
		assertEquals(3, ReworkFormStage.forResolvedChases(99));
	}
}
