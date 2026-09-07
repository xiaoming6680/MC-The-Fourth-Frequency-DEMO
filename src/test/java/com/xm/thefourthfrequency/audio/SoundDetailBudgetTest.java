package com.xm.thefourthfrequency.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SoundDetailBudgetTest {
	@Test void coalescesNearbyRepetitionsWithoutDroppingAnotherSource() {
		var budget = new SoundDetailBudget(3);
		assertTrue(budget.admit(100, "beam:cell1", 6));
		assertFalse(budget.admit(100, "beam:cell1", 6));
		assertFalse(budget.admit(105, "beam:cell1", 6));
		assertTrue(budget.admit(105, "beam:cell2", 6));
		assertTrue(budget.admit(106, "beam:cell1", 6));
	}
	@Test void enforcesPerTickCapAndRestoresItNextTick() {
		var budget = new SoundDetailBudget(2);
		assertTrue(budget.admit(1, "a", 1)); assertTrue(budget.admit(1, "b", 1));
		assertFalse(budget.admit(1, "c", 1)); assertTrue(budget.admit(2, "c", 1));
	}
	@Test void worldsAreIndependentAndClockReversalClearsCooldowns() {
		var first = new SoundDetailBudget(1); var second = new SoundDetailBudget(1);
		assertTrue(first.admit(100, "a", 6)); assertTrue(second.admit(100, "a", 6));
		assertTrue(first.admit(0, "a", 6)); first.clear(); assertTrue(first.admit(0, "a", 6));
	}
	@Test void anUnboundedEmitterCannotGrowTheHistoryForever() {
		var budget = new SoundDetailBudget(9999);
		for (int i=0;i<512;i++) assertTrue(budget.admit(1,"source"+i,1));
		assertFalse(budget.admit(1,"overflow",1));
		assertTrue(budget.admit(202,"overflow",1));
	}
}
