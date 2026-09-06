package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalUnreadPolicyTest {
	@Test
	void viewportCoversItsOwnRowsAndNothingElse() {
		assertTrue(TerminalUnreadPolicy.rowVisible(0, 0, 5), "The first row of an unscrolled list is visible");
		assertTrue(TerminalUnreadPolicy.rowVisible(4, 0, 5), "The last row of the viewport is visible");
		assertFalse(TerminalUnreadPolicy.rowVisible(5, 0, 5), "One row past the viewport is not visible");
		assertTrue(TerminalUnreadPolicy.rowVisible(5, 5, 5), "A scrolled viewport starts at its scroll row");
		assertFalse(TerminalUnreadPolicy.rowVisible(4, 5, 5), "A row scrolled off the top is not visible");
	}

	@Test
	void viewportRejectsImpossibleInput() {
		assertFalse(TerminalUnreadPolicy.rowVisible(-1, 0, 5), "A negative row is not visible");
		assertFalse(TerminalUnreadPolicy.rowVisible(0, 0, 0), "A viewport with no rows shows nothing");
		assertTrue(TerminalUnreadPolicy.rowVisible(0, -3, 5), "A negative scroll is clamped to the top");
	}

	@Test
	void unreadFilesAreTheMostRecentlyUnlockedOnes() {
		// Directory order is the catalogue's, so the newest unlock is not the last row.
		long[] unlocked = {100L, 400L, 200L, 300L};
		assertEquals(List.of(1), TerminalUnreadPolicy.unreadFileRows(unlocked, 1),
				"One unread file is the single newest unlock");
		assertEquals(List.of(1, 3), TerminalUnreadPolicy.unreadFileRows(unlocked, 2),
				"Two unread files are the two newest unlocks, newest first");
		assertEquals(List.of(1, 3, 2, 0), TerminalUnreadPolicy.unreadFileRows(unlocked, 9),
				"A badge larger than the directory cannot name more files than exist");
	}

	@Test
	void lockedFilesAreNeverCounted() {
		// A locked file has never incremented the badge, so it must not absorb one of its counts -
		// doing so would point the "is it on screen" check at a row that is not what arrived.
		long[] withLocked = {-1L, 500L, -1L};
		assertEquals(List.of(1), TerminalUnreadPolicy.unreadFileRows(withLocked, 1),
				"Only unlocked files can be unread");
		assertEquals(List.of(1), TerminalUnreadPolicy.unreadFileRows(withLocked, 3),
				"An over-large badge still cannot reach a locked file");
	}

	@Test
	void nothingUnreadNamesNoRows() {
		long[] unlocked = {100L, 200L};
		assertEquals(List.of(), TerminalUnreadPolicy.unreadFileRows(unlocked, 0),
				"A cleared badge names no files");
		assertEquals(List.of(), TerminalUnreadPolicy.unreadFileRows(unlocked, -1),
				"A negative badge names no files");
		assertEquals(List.of(), TerminalUnreadPolicy.unreadFileRows(new long[0], 1),
				"An empty directory names no files");
	}

	@Test
	void filesUnlockedOnTheSameTickStayInsideTheUnreadSet() {
		// Two files discovered by the same action share a game time. Whichever the tie-break picks,
		// it must be one of them - never a third, older file that is genuinely already read.
		long[] sameTick = {50L, 700L, 700L};
		assertEquals(List.of(1, 2), TerminalUnreadPolicy.unreadFileRows(sameTick, 2),
				"Both files from the same tick are counted");
		assertEquals(List.of(1), TerminalUnreadPolicy.unreadFileRows(sameTick, 1),
				"A single count resolves towards the top of the directory, still inside the tie");
	}
}
