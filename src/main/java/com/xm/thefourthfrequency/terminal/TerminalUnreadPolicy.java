package com.xm.thefourthfrequency.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Which unread entries the player is actually being shown, for the two pages that acknowledge
 * themselves.
 *
 * <p>Records and Files clear their own unread marker once what arrived is on screen, instead of
 * asking the player to click a tab whose contents are already in front of them. "On screen" has to
 * mean the scrolled viewport rather than the page: a log scrolled away from the new line would
 * otherwise mark it read without ever having shown it, which is the failure this whole rule
 * exists to avoid.</p>
 */
public final class TerminalUnreadPolicy {
	private TerminalUnreadPolicy() {
	}

	/**
	 * Whether a row index falls inside the viewport of {@code visibleRows} rows starting at
	 * {@code scrollRow}.
	 */
	public static boolean rowVisible(int row, int scrollRow, int visibleRows) {
		if (row < 0 || visibleRows <= 0) return false;
		int top = Math.max(0, scrollRow);
		return row >= top && row < top + visibleRows;
	}

	/**
	 * The directory indices the Files badge is counting.
	 *
	 * <p>That badge is a bare counter on the wire - the file payload carries no per-file unread
	 * flag - so which files it means has to be recovered rather than read. It can be: the counter
	 * only ever rises when a file is unlocked for the first time, and each file carries the game
	 * time it was unlocked at. The newest {@code unreadCount} unlock times are therefore exactly
	 * the files the badge is about, with no extra field on the protocol.</p>
	 *
	 * <p>Ties keep their directory order, so two files unlocked on the same tick are either both
	 * counted or - if the badge somehow claims fewer than were unlocked together - resolved
	 * towards the top of the list. Either way the answer stays inside the set that really is
	 * unread.</p>
	 *
	 * @param unlockedGameTimes one entry per directory row; negative where the file is still
	 *                          locked, since a locked file has never incremented the counter
	 * @param unreadCount       the badge value; zero or less means nothing is unread
	 */
	public static List<Integer> unreadFileRows(long[] unlockedGameTimes, int unreadCount) {
		if (unlockedGameTimes == null || unreadCount <= 0) return List.of();
		List<Integer> unlocked = new ArrayList<>();
		for (int index = 0; index < unlockedGameTimes.length; index++) {
			if (unlockedGameTimes[index] >= 0L) unlocked.add(index);
		}
		unlocked.sort((left, right) -> Long.compare(unlockedGameTimes[right], unlockedGameTimes[left]));
		return List.copyOf(unlocked.subList(0, Math.min(unreadCount, unlocked.size())));
	}
}
