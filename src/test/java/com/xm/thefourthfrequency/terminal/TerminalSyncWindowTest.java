package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalSyncWindowTest {
	@Test void inputBurstsAreCoalescedWithoutSharingAnotherPlayersBudget() {
		var alice = new TerminalSyncWindow();
		var bob = new TerminalSyncWindow();
		assertTrue(alice.take(100));
		for (int i=0;i<100;i++) assertFalse(alice.take(100));
		assertTrue(bob.take(100));
		assertTrue(alice.take(101));
		assertTrue(bob.take(101));
	}
}
