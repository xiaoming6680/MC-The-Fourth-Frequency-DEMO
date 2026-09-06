package com.xm.thefourthfrequency.narrative;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fragment a previous playthrough leaves behind, and the counts it must not disturb.
 */
final class RecoveredFragmentContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static final String STEM = "terminal.thefourthfrequency.file.recovered_predecessor_record.";

	/**
	 * It is a file the terminal serves and nothing counts.
	 *
	 * <p>The four investigation records gate the complete journal, drive the title stage and set the
	 * read percentage. A fifth entry that appeared only for players on a second run would quietly make
	 * every one of those numbers mean something different depending on whether the person had finished
	 * the mod before - which is the kind of bug that is invisible until somebody's journal refuses to
	 * unlock.
	 */
	@Test
	void theRecoveredFragmentIsNotOneOfTheFourInvestigationFiles() {
		assertFalse(HiddenFilePolicy.FILE_IDS.contains(HiddenFilePolicy.RECOVERED_FILE_ID));
		assertFalse(HiddenFilePolicy.isHiddenFile(HiddenFilePolicy.RECOVERED_FILE_ID));
		assertEquals(4, HiddenFilePolicy.FILE_COUNT);
		assertFalse(HiddenFilePolicy.RECOVERED_FILE_ID.equals(HiddenFilePolicy.COMPLETE_FILE_ID));
	}

	/** It is still a catalogue entry, so the terminal can serve it like any other. */
	@Test
	void theCatalogueKnowsIt() {
		NarrativeFileCatalog.Definition definition =
				NarrativeFileCatalog.require(HiddenFilePolicy.RECOVERED_FILE_ID);
		assertFalse(definition.lineKeys().isEmpty());
		assertEquals(STEM + "title", definition.titleKey());
		assertEquals(8, NarrativeFileCatalog.definitions().size());
	}

	/**
	 * Every key the body can reach, in both languages.
	 *
	 * <p>The client picks between outcome variants and between an answered and an unanswered
	 * companions line, so half of these keys never appear in the catalogue's own list - which is
	 * exactly the shape the repository's general translation contract cannot see.
	 */
	@Test
	void everyLineTheBodyCanReachExistsInBothLanguages() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		List<String> keys = List.of("title", "line1", "line2", "line3", "line3.success",
				"line3.failure", "line4", "line5", "line5.unanswered", "line6");
		for (String suffix : keys) {
			assertTrue(zh.has(STEM + suffix), "missing zh_cn: " + STEM + suffix);
			assertTrue(en.has(STEM + suffix), "missing en_us: " + STEM + suffix);
			assertFalse(zh.get(STEM + suffix).getAsString().isBlank(), "blank zh_cn: " + suffix);
			assertFalse(en.get(STEM + suffix).getAsString().isBlank(), "blank en_us: " + suffix);
		}
	}

	/**
	 * The lines that quote a figure must have somewhere to put it.
	 *
	 * <p>Every number in this file has to be the number that happened - that is the rule the whole
	 * FILES page rests on. A translation that dropped its placeholder would print a sentence with the
	 * figure silently missing, which reads as fine and is the exact failure that turns the page from
	 * evidence into decoration.
	 */
	@Test
	void theLinesThatQuoteFiguresKeepTheirPlaceholders() throws Exception {
		for (String code : List.of("zh_cn", "en_us")) {
			JsonObject lang = lang(code);
			for (String suffix : List.of("line2", "line3", "line3.success", "line3.failure", "line5")) {
				assertTrue(lang.get(STEM + suffix).getAsString().contains("%s"),
						code + " lost its placeholder on " + suffix);
			}
			assertFalse(lang.get(STEM + "line5.unanswered").getAsString().contains("%s"),
					code + ": the unanswered line has nothing to quote");
		}
	}

	private static JsonObject lang(String code) throws Exception {
		return JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/" + code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
