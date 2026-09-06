package com.xm.thefourthfrequency.narrative;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.terminal.TerminalGuidancePolicy;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeviceManualPolicyTest {
	private static final Path LANG =
			Path.of("src/main/resources/assets/thefourthfrequency/lang");

	private static JsonObject lang(String file) throws Exception {
		return JsonParser.parseString(Files.readString(LANG.resolve(file), StandardCharsets.UTF_8))
				.getAsJsonObject();
	}

	@Test
	void everyPageIsARealCatalogueEntryWithBothLanguagesBehindIt() throws Exception {
		JsonObject zh = lang("zh_cn.json");
		JsonObject en = lang("en_us.json");
		for (String id : DeviceManualPolicy.ids()) {
			NarrativeFileCatalog.Definition definition = NarrativeFileCatalog.require(id);
			assertEquals(4, definition.lineKeys().size(), id + " must be four lines");
			List<String> keys = new java.util.ArrayList<>(definition.lineKeys());
			keys.add(definition.titleKey());
			for (String key : keys) {
				assertTrue(zh.has(key), "zh_cn is missing " + key);
				assertTrue(en.has(key), "en_us is missing " + key);
				assertFalse(zh.get(key).getAsString().isBlank(), key + " is blank in zh_cn");
				assertFalse(en.get(key).getAsString().isBlank(), key + " is blank in en_us");
			}
		}
	}

	/**
	 * The property the whole family rests on: the documents are older than the machine, and say so
	 * in a number the player can compare against the one in the status bar. If the declared revision
	 * ever catches up with the live protocol, four files stop being an out-of-date manual and become
	 * the terminal contradicting itself with no explanation on the page.
	 */
	@Test
	void theManualDeclaresItselfOlderThanTheDeviceInBothLanguages() throws Exception {
		assertTrue(DeviceManualPolicy.DOCUMENT_PROTOCOL < TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				"The manual pages must stay behind the protocol the status bar prints");
		String printed = String.format("%02d", DeviceManualPolicy.DOCUMENT_PROTOCOL);
		JsonObject zh = lang("zh_cn.json");
		JsonObject en = lang("en_us.json");
		for (String id : DeviceManualPolicy.ids()) {
			String footer = NarrativeFileCatalog.require(id).lineKeys().getLast();
			assertTrue(zh.get(footer).getAsString().contains(printed),
					footer + " must print the revision the manual claims");
			assertTrue(en.get(footer).getAsString().contains(printed),
					footer + " must print the revision the manual claims");
		}
	}

	@Test
	void aPageArrivesWithItsToolAndNeverBeforeIt() {
		assertTrue(DeviceManualPolicy.earned(0).isEmpty());
		for (TerminalTool tool : TerminalTool.values()) {
			String page = DeviceManualPolicy.pageFor(tool);
			List<String> alone = DeviceManualPolicy.earned(1 << tool.slot());
			if (page == null) {
				assertTrue(alone.isEmpty(), tool + " has no page and must not hand one out");
				continue;
			}
			assertEquals(List.of(page), alone, tool + " must hand out exactly its own page");
		}
		// Unlocking more tools never takes a page away, so a file cannot vanish from the list a
		// player has already read.
		int everything = 0;
		for (TerminalTool tool : TerminalTool.values()) everything |= 1 << tool.slot();
		assertEquals(DeviceManualPolicy.ids(), DeviceManualPolicy.earned(everything));
	}

	/**
	 * The factory mask - what a freshly bound terminal can already open - decides which pages are
	 * waiting the moment the file store opens. One is intended: the sky monitor page states its three
	 * channels long before the player has any reason to count the instrument's.
	 */
	@Test
	void exactlyOnePageIsWaitingAtBinding() {
		List<String> atBinding = DeviceManualPolicy.earned(
				TerminalGuidancePolicy.availableToolsMask(0, false, 0));
		assertEquals(List.of("manual_sky_monitor"), atBinding);
	}

	/**
	 * The four field records gate the complete journal, drive the title stage and set the read
	 * percentage. A manual page slipping into that list would change every one of those numbers.
	 */
	@Test
	void manualPagesAreNotPartOfTheInvestigation() {
		for (String id : DeviceManualPolicy.ids()) {
			assertFalse(HiddenFilePolicy.isHiddenFile(id), id + " must not count as an investigation file");
			assertFalse(id.equals(HiddenFilePolicy.COMPLETE_FILE_ID));
			assertFalse(id.equals(HiddenFilePolicy.RECOVERED_FILE_ID));
			assertTrue(DeviceManualPolicy.isManual(id));
		}
		assertFalse(DeviceManualPolicy.isManual("maintenance_handoff"));
		assertFalse(DeviceManualPolicy.isManual(null));
	}
}
