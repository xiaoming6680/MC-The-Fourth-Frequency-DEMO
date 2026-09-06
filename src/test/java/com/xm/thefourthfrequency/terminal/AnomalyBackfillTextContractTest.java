package com.xm.thefourthfrequency.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every anomaly the backfill can list has to have a line to list it with.
 *
 * <p>The {@code log.type} strings were authored years before anything drew them - the client used to
 * carry a comment saying the surface they belonged to was never built. Now the records backfill
 * draws them, which turns a missing key from dead copy into a raw identifier on screen in the middle
 * of the one page whose entire job is to read as an account somebody kept.
 *
 * <p>Both languages, because a Chinese-only key would ship an English player a page of
 * {@code terminal.thefourthfrequency.log.type.local_rule_collapse}.
 */
final class AnomalyBackfillTextContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");

	/**
	 * Retired ids are still on disk, so they still need their lines.
	 *
	 * <p>Three anomalies were merged into the entries that already said most of what they said. The
	 * merge removed them from the draw, not from anybody's save: a world that ran before it has their
	 * ids sitting in the quarantined store, and the backfill lists that store verbatim. Deleting the
	 * copy along with the entry would print raw identifiers into the page for exactly the long-running
	 * players the backfill was written for.
	 */
	@Test
	void retiredAnomaliesKeepTheirRecordLinesForSavesThatStillHoldThem() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		for (String id : AnomalyCatalog.retiredIds()) {
			String key = "terminal.thefourthfrequency.log.type." + id;
			assertTrue(zh.has(key), "missing zh_cn record line for retired anomaly: " + key);
			assertTrue(en.has(key), "missing en_us record line for retired anomaly: " + key);
			assertFalse(AnomalyCatalog.contains(id), id + " must not be drawable again");
			assertTrue(AnomalyCatalog.containsHistorical(id), id + " must stay readable from history");
		}
	}

	@Test
	void everyCatalogAnomalyHasARecordLineInBothLanguages() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		for (AnomalyDefinition anomaly : AnomalyCatalog.definitions()) {
			String key = "terminal.thefourthfrequency.log.type." + anomaly.id();
			assertTrue(zh.has(key), "missing zh_cn record line: " + key);
			assertTrue(en.has(key), "missing en_us record line: " + key);
			assertFalse(zh.get(key).getAsString().isBlank(), "blank zh_cn record line: " + key);
			assertFalse(en.get(key).getAsString().isBlank(), "blank en_us record line: " + key);
		}
	}

	/**
	 * The line the unread badge rides in on.
	 *
	 * <p>The quarantined store has its own unread counter that nothing reads, so the release writes
	 * one ordinary record entry to carry the badge. Without that key the page grows by eighty entries
	 * behind a line of raw identifier.
	 */
	@Test
	void theReleaseAnnouncementHasALineInBothLanguages() throws Exception {
		String key = "terminal.thefourthfrequency.signal.event.anomaly_archive_released";
		assertTrue(lang("zh_cn").has(key), "missing zh_cn: " + key);
		assertTrue(lang("en_us").has(key), "missing en_us: " + key);
	}

	/**
	 * The release line has to survive the record policy that hides operational telemetry.
	 *
	 * <p>It is an ordinary story entry and has to stay one. A type that happened to collide with the
	 * {@code resource_} prefix or the hidden-telemetry set would be pruned on write, and the backfill
	 * would open silently - which is the one failure mode that leaves no trace to debug from.
	 */
	@Test
	void theReleaseAnnouncementIsNotPrunedAsTelemetry() {
		assertTrue(TerminalRecordPolicy.retainedInLog("anomaly_archive_released"));
		assertTrue(TerminalRecordPolicy.visibleInRecords("anomaly_archive_released"));
	}

	/**
	 * The backfill's own lines must never become navigation targets.
	 *
	 * <p>They carry a real position, so a backfilled line that qualified would let a player tap
	 * something that happened twenty hours ago and watch the navigator retarget. The records shortcut
	 * now aims per row, which narrows the risk without removing it: the shortcut is drawn only where
	 * {@code candidateEncodedIndex} resolves, and this flag is the second, independent reason a
	 * backfilled line can never become a destination.
	 */
	@Test
	void backfilledLinesAreNeverNavigable() {
		assertTrue(TerminalRecordPolicy.Source.STORY.navigable());
		assertFalse(TerminalRecordPolicy.Source.ANOMALY_BACKFILL.navigable());
		assertFalse(TerminalRecordPolicy.Source.RELAY.navigable());
		assertFalse(TerminalRecordPolicy.Source.STORY.settlesIn());
		assertTrue(TerminalRecordPolicy.Source.ANOMALY_BACKFILL.settlesIn());
	}

	/** Wire ids are positional and stored in payloads; reordering would relabel every sent line. */
	@Test
	void sourceWireIdsAreStable() {
		assertTrue(TerminalRecordPolicy.Source.STORY.wireId() == 0);
		assertTrue(TerminalRecordPolicy.Source.ANOMALY_BACKFILL.wireId() == 1);
		assertTrue(TerminalRecordPolicy.Source.RELAY.wireId() == 2);
		assertTrue(TerminalRecordPolicy.Source.fromWire(99) == TerminalRecordPolicy.Source.STORY,
				"an unknown source must degrade to the one that is always safe to draw");
	}

	private static JsonObject lang(String code) throws Exception {
		return JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/" + code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
