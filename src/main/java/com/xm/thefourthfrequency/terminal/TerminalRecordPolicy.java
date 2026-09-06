package com.xm.thefourthfrequency.terminal;

import java.util.Set;

/** Keeps operational telemetry in its dedicated tool instead of flooding the player-facing record page. */
public final class TerminalRecordPolicy {
	/**
	 * Where a listed record line came from.
	 *
	 * <p>The page used to have exactly one source, so nothing needed to say so. It now has three, and
	 * they are stored apart on purpose: {@link #STORY} lines live in {@code SIGNAL_EVENTS} and are
	 * subject to {@link #retainedInLog} and per-band trimming, while {@link #ANOMALY_BACKFILL} lines
	 * live in the quarantined {@code ANOMALY_LOGS} store precisely so that neither the prune nor the
	 * trim can reach them - an anomaly that fell out of the log before the backfill fired would be a
	 * gap in the one list whose whole point is that nothing was missed.
	 *
	 * <p>The wire id is positional and must stay stable; append, never reorder.
	 */
	public enum Source {
		/** Ordinary terminal records: milestones, fragments, pursuit warnings. */
		STORY,
		/** An anomaly the player lived through, released in bulk once the backfill latch is set. */
		ANOMALY_BACKFILL,
		/** A line that reached this terminal from another one, delayed and unattributed. */
		RELAY;

		public int wireId() {
			return ordinal();
		}

		public static Source fromWire(int wireId) {
			Source[] values = values();
			return wireId >= 0 && wireId < values.length ? values[wireId] : STORY;
		}

		/**
		 * Whether the records page may point the navigator at this line.
		 *
		 * <p>Only story lines. Backfilled anomalies carry a real position, so leaving them eligible
		 * would let a player tap a line about last Tuesday and watch the navigator retarget. The
		 * records shortcut now aims per row via {@link #candidateEncodedIndex}, which already excludes
		 * anomaly ids; this stays as the independent second reason.
		 */
		public boolean navigable() {
			return this == STORY;
		}

		/** Whether the line arrives through the glyph settle rather than simply being drawn. */
		public boolean settlesIn() {
			return this != STORY;
		}
	}

	private static final String FRAGMENT_CANDIDATE_PREFIX = "fragment_candidate_";

	/**
	 * How many candidate locations one fragment may offer at once.
	 *
	 * <p>Lives here rather than staying private to the investigation service because the candidate
	 * encoding is no longer server-only: the records page derives a row's encoded index from that
	 * row's own line type, so its navigation shortcut can aim at <em>that</em> lead instead of the
	 * nearest one. Two copies of the multiplier would be two copies of a wire format.
	 */
	public static final int MAX_CANDIDATES_PER_FRAGMENT = 3;
	private static final Set<String> HIDDEN_TELEMETRY = Set.of(
			"environment_initialized",
			"resource_monitor_initialized",
			"weather_changed",
			"dimension_changed");

	private TerminalRecordPolicy() { }

	/**
	 * Historical rather than current, because this rule is applied to entries already on disk. A
	 * save from before three anomalies were merged away still holds their ids in the ordinary signal
	 * log, and asking the live catalogue about them would answer no - which would stop pruning them
	 * and let a long-running world sprout anomaly rows in the records page that were deliberately
	 * kept out of it.
	 */
	public static boolean retainedInLog(String type) {
		if (type == null || type.isBlank() || HIDDEN_TELEMETRY.contains(type)
				|| AnomalyCatalog.containsHistorical(type)) return false;
		return !type.startsWith("resource_");
	}

	public static boolean visibleInRecords(String type) {
		if (!retainedInLog(type) || type.startsWith("fragment_marker_")) return false;
		if (!type.startsWith(FRAGMENT_CANDIDATE_PREFIX)) return true;
		String candidate = type.substring(FRAGMENT_CANDIDATE_PREFIX.length());
		int slotSeparator = candidate.indexOf('_');
		return slotSeparator < 0 || candidate.substring(slotSeparator + 1).equals("0");
	}

	public static boolean isCandidate(String type) {
		return type != null && type.startsWith(FRAGMENT_CANDIDATE_PREFIX);
	}

	/**
	 * Which fragment a {@code fragment_candidate_<fragment+1>_<slot>} line belongs to, or {@code -1}.
	 *
	 * <p>The line type is one-based because it doubles as a translation key stem; everything
	 * downstream of here is zero-based.
	 */
	public static int candidateFragment(String type) {
		String[] parts = candidateParts(type);
		if (parts == null) return -1;
		try {
			int fragment = Integer.parseInt(parts[0]) - 1;
			return fragment < 0 ? -1 : fragment;
		} catch (NumberFormatException malformed) {
			return -1;
		}
	}

	/**
	 * The value {@code SELECT_FRAGMENT_TARGET} expects for this line, or {@code -1} when the type is
	 * not a candidate line or carries no slot.
	 *
	 * <p>This is what lets the records shortcut aim at the row it is drawn on. Before it existed the
	 * shortcut only opened the navigation tool, and the player then had to press that tool's own
	 * "unstable signal" button - which targets the <em>nearest</em> lead rather than the one whose
	 * line they just clicked. With leads in two directions those are different places, and nothing on
	 * screen said so.
	 */
	public static int candidateEncodedIndex(String type) {
		String[] parts = candidateParts(type);
		int fragment = candidateFragment(type);
		if (parts == null || fragment < 0 || parts.length < 2) return -1;
		try {
			int slot = Integer.parseInt(parts[1]);
			if (slot < 0 || slot >= MAX_CANDIDATES_PER_FRAGMENT) return -1;
			return fragment * MAX_CANDIDATES_PER_FRAGMENT + slot;
		} catch (NumberFormatException malformed) {
			return -1;
		}
	}

	private static String[] candidateParts(String type) {
		if (!isCandidate(type)) return null;
		String remainder = type.substring(FRAGMENT_CANDIDATE_PREFIX.length());
		return remainder.isEmpty() ? null : remainder.split("_", 2);
	}

	/**
	 * Whether the Records page will actually put this entry on screen.
	 *
	 * <p>{@link #visibleInRecords} is only half the answer. Candidate lines are meaningless without
	 * the navigator to point at them, and several fragments can share one location - so the page
	 * hides the whole class without the tool and keeps one entry per location with it. That second
	 * half used to live inline in the client's list builder, where nothing else could see it: the
	 * home card's "most recent" line disagreed with the page until it was threaded through, and the
	 * unread badge disagreed with both for longer, promising something new and then showing the list
	 * exactly as the player left it.
	 *
	 * <p>One rule, three readers: the page, the home card, and the write path that decides whether a
	 * candidate is worth marking unread at all.
	 *
	 * @param seenLocations locations already listed; this method adds to it, so callers walk their
	 *                      entries in display order and pass the same set through
	 */
	public static boolean listedInRecords(String type, int variant, boolean navigator,
			Set<Integer> seenLocations) {
		if (!visibleInRecords(type)) return false;
		if (!isCandidate(type)) return true;
		return navigator && seenLocations.add(variant);
	}
}
