package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalRecordPolicyTest {
	/**
	 * The rule the unread badge has to agree with, because the badge is a promise about this list.
	 *
	 * <p>{@code visibleInRecords} lets every {@code _0} candidate through, and the page then applies
	 * a second filter that used to live only inside the client's list builder: no navigator, no
	 * candidates at all, and with one, a single entry per location. The badge counted through the
	 * first rule alone, so the tab said something was new and Records showed the list exactly as the
	 * player had left it. Both readers go through this method now, and the write path uses it to
	 * decide whether a candidate is worth marking unread in the first place.</p>
	 */
	@Test
	void theListedRuleIsWhatTheBadgeHasToPromise() {
		// Without the navigator the whole class is hidden - so nothing here may be counted as unread.
		Set<Integer> withoutTool = new HashSet<>();
		assertFalse(TerminalRecordPolicy.listedInRecords("fragment_candidate_1_0", 3, false, withoutTool));
		assertFalse(TerminalRecordPolicy.listedInRecords("fragment_candidate_2_0", 5, false, withoutTool));
		assertTrue(withoutTool.isEmpty(), "a hidden class must not consume a location slot either");

		// With it, one entry per location survives however many fragments point at the same group.
		Set<Integer> seen = new HashSet<>();
		assertTrue(TerminalRecordPolicy.listedInRecords("fragment_candidate_1_0", 3, true, seen));
		assertFalse(TerminalRecordPolicy.listedInRecords("fragment_candidate_2_0", 3, true, seen),
				"a second fragment at the same location is collapsed by the page");
		assertTrue(TerminalRecordPolicy.listedInRecords("fragment_candidate_3_0", 7, true, seen));

		// Slot filtering still applies before any of it.
		assertFalse(TerminalRecordPolicy.listedInRecords("fragment_candidate_1_1", 9, true, seen));
		// Ordinary story lines are unaffected by the tool and never consume a location.
		Set<Integer> untouched = new HashSet<>();
		assertTrue(TerminalRecordPolicy.listedInRecords("terminal_issued", 0, false, untouched));
		assertTrue(TerminalRecordPolicy.listedInRecords("pursuit_warning_1", 0, false, untouched));
		assertTrue(untouched.isEmpty());
		assertFalse(TerminalRecordPolicy.listedInRecords("fragment_marker_2", 0, true, untouched));
		assertFalse(TerminalRecordPolicy.listedInRecords("weather_changed", 0, true, untouched));

		assertTrue(TerminalRecordPolicy.isCandidate("fragment_candidate_4_0"));
		assertFalse(TerminalRecordPolicy.isCandidate("fragment_near_2"));
		assertFalse(TerminalRecordPolicy.isCandidate(null));
	}

	@Test
	void recordsKeepStoryEventsAndOnlyOneSummaryPerCandidateGroup() {
		assertTrue(TerminalRecordPolicy.visibleInRecords("terminal_issued"));
		assertTrue(TerminalRecordPolicy.visibleInRecords("fragment_shared_0"));
		assertTrue(TerminalRecordPolicy.visibleInRecords("fragment_candidate_2_0"));
		assertTrue(TerminalRecordPolicy.visibleInRecords("fragment_near_2"));
		assertTrue(TerminalRecordPolicy.visibleInRecords("pursuit_warning_1"));
		assertTrue(TerminalRecordPolicy.retainedInLog("fragment_candidate_2_1"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("fragment_candidate_2_1"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("fragment_candidate_2_2"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("fragment_marker_2"));
	}

	@Test
	void routineEnvironmentAndToolTelemetryStayOutOfRecords() {
		assertFalse(TerminalRecordPolicy.visibleInRecords("weather_changed"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("dimension_changed"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("resource_target_located"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("resource_advice_accepted"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("environment_initialized"));
		assertFalse(TerminalRecordPolicy.visibleInRecords("resource_monitor_initialized"));
		assertFalse(TerminalRecordPolicy.retainedInLog("weather_changed"));
		assertFalse(TerminalRecordPolicy.retainedInLog("resource_target_located"));
	}

	@Test
	void allCatalogAnomaliesStayOutOfTerminalRecordsAndStoredLogs() {
		for (AnomalyDefinition anomaly : AnomalyCatalog.definitions()) {
			assertFalse(TerminalRecordPolicy.visibleInRecords(anomaly.id()), anomaly.id());
			assertFalse(TerminalRecordPolicy.retainedInLog(anomaly.id()), anomaly.id());
		}
	}

	/**
	 * The encoding the records shortcut and {@code SELECT_FRAGMENT_TARGET} have to agree on.
	 *
	 * <p>Asserted against literal line types rather than against the formula, because the point of
	 * the pairing is that the client can read a server-written string and arrive at the number the
	 * server will accept. Restating {@code fragment * 3 + slot} on both sides of the assertion would
	 * test nothing.
	 */
	@Test
	void aCandidateLineDecodesToTheTargetTheServerWillAccept() {
		assertEquals(0, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_1_0"));
		assertEquals(2, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_1_2"));
		assertEquals(3, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_2_0"));
		assertEquals(11, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_4_2"));
		assertEquals(0, TerminalRecordPolicy.candidateFragment("fragment_candidate_1_0"));
		assertEquals(3, TerminalRecordPolicy.candidateFragment("fragment_candidate_4_2"));
	}

	/**
	 * Everything the shortcut must decline to aim at.
	 *
	 * <p>{@code SELECT_FRAGMENT_TARGET} rejects anything outside 0-11, so a malformed line that
	 * decoded to a plausible-looking number would be a shortcut pointing at another fragment's lead.
	 * Returning -1 is what keeps the shortcut off the row entirely.
	 */
	@Test
	void nonCandidateAndMalformedLinesDecodeToNothing() {
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_marker_1"));
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("continuity"));
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex(null));
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_"));
		// No slot at all: the marker lines share the prefix shape but not the encoding.
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_1"));
		// A slot the server would never write, and which would collide with the next fragment.
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_1_3"));
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_x_0"));
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_1_x"));
		// Zero-based line type: the server writes fragment+1, so a literal 0 is malformed.
		assertEquals(-1, TerminalRecordPolicy.candidateEncodedIndex("fragment_candidate_0_0"));
	}
}
