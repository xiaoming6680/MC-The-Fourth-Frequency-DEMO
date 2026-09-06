package com.xm.thefourthfrequency.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every profile question and option must have text in both languages.
 *
 * <p>This has to exist as its own test. The repository's general "every translation key resolves"
 * contract only recognises literal arguments to {@code Component.translatable}, and the profile
 * builds its keys by concatenating a question or option id onto a stem - which is precisely the
 * shape that walks past that check. The self-test lines were written out longhand to stay inside it;
 * the profile cannot be, because the ids come from an array.
 *
 * <p>The failure this prevents is not subtle: a missing key puts a raw
 * {@code terminal.thefourthfrequency.profile.option....} on screen during the first minute of the
 * mod, inside a sequence the player is not allowed to leave.
 */
final class TerminalProfileTextContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static final String STEM = "terminal.thefourthfrequency.profile.";

	@Test
	void everyQuestionAndOptionHasTextInBothLanguages() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		for (String key : expectedKeys()) {
			assertTrue(zh.has(key), "missing zh_cn: " + key);
			assertTrue(en.has(key), "missing en_us: " + key);
			assertFalse(zh.get(key).getAsString().isBlank(), "blank zh_cn: " + key);
			assertFalse(en.get(key).getAsString().isBlank(), "blank en_us: " + key);
		}
	}

	/**
	 * The chrome around the questions, including the line the whole sequence is built to deliver.
	 *
	 * <p>{@code profile.recorded} is the payoff - five questions, three of them about how the player
	 * feels, answered with nothing but an acknowledgement that the file has been written. Losing it
	 * to a missing key would end the sequence on blank glass.
	 */
	@Test
	void theFramingLinesExistInBothLanguages() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		for (String suffix : List.of("title", "hint", "recorded", "incomplete")) {
			assertTrue(zh.has(STEM + suffix), "missing zh_cn: " + STEM + suffix);
			assertTrue(en.has(STEM + suffix), "missing en_us: " + STEM + suffix);
		}
	}

	/**
	 * No stray copy for questions that no longer exist.
	 *
	 * <p>The other direction of the same contract. A question removed from the array leaves its lines
	 * behind, and the next person to read the language file cannot tell which of them are live - the
	 * anomaly log strings sat in exactly that state for years, with six ids no catalogue still had.
	 */
	@Test
	void noProfileKeyIsOrphaned() throws Exception {
		List<String> expected = expectedKeys();
		expected.addAll(List.of(STEM + "title", STEM + "hint", STEM + "recorded", STEM + "incomplete"));
		JsonObject zh = lang("zh_cn");
		for (String key : zh.keySet()) {
			if (!key.startsWith(STEM)) continue;
			assertTrue(expected.contains(key), "orphaned profile string: " + key);
		}
	}

	private static List<String> expectedKeys() {
		List<String> keys = new ArrayList<>();
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			String questionId = TerminalProfileQuestionnaire.questionId(question);
			keys.add(STEM + "question." + questionId);
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				keys.add(STEM + "option." + questionId + "."
						+ TerminalProfileQuestionnaire.optionId(question, option));
			}
		}
		return keys;
	}

	private static JsonObject lang(String code) throws Exception {
		return JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/" + code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
