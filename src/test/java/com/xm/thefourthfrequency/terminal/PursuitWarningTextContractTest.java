package com.xm.thefourthfrequency.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five corrector forms must not say the same thing.
 *
 * <p>This exists because they did. {@code pursuit_warning_1} through {@code _5} shipped with five
 * identical strings, and nobody noticed for a long time for a good reason: the records page threw
 * the form away and rendered a shared line, so the per-form keys were never read by anything. The
 * duplication was invisible from inside the game.
 *
 * <p>It matters because the forms do not agree on how they track. Form one only corrects while the
 * player is making noise, three arrives ahead of where they are going, four keeps returning behind
 * them - so a player who runs from form one is doing the single worst available thing, and a warning
 * that says the same words to all five cannot tell them apart. Distinctness is the property that
 * makes the line worth reading at all, so it is asserted rather than trusted.
 */
final class PursuitWarningTextContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static final String RECORD = "terminal.thefourthfrequency.signal.event.pursuit_warning_";

	@Test
	void everyFormHasItsOwnWarningInBothLanguages() throws Exception {
		for (String code : new String[]{"zh_cn", "en_us"}) {
			JsonObject lang = lang(code);
			for (int form = 1; form <= PursuitProgressPolicy.FORM_COUNT; form++) {
				String key = RECORD + form;
				assertTrue(lang.has(key), "missing " + code + ": " + key);
				assertFalse(lang.get(key).getAsString().isBlank(), "blank " + code + ": " + key);
			}
		}
	}

	@Test
	void noTwoFormsShareAWarning() throws Exception {
		for (String code : new String[]{"zh_cn", "en_us"}) {
			JsonObject lang = lang(code);
			Set<String> seen = new HashSet<>();
			for (int form = 1; form <= PursuitProgressPolicy.FORM_COUNT; form++) {
				String text = lang.get(RECORD + form).getAsString();
				assertTrue(seen.add(text), code + " reuses one line for two forms: " + text);
			}
			assertEquals(PursuitProgressPolicy.FORM_COUNT, seen.size());
		}
	}

	/**
	 * The shared line stays, and stays reachable.
	 *
	 * <p>Both readers fall back to it outside 1..5 - the notice for a form the progress policy has
	 * not heard of, the records page for an entry written by a build that stored no form. Deleting it
	 * as "unused" would put a raw translation key on screen mid-chase.
	 */
	@Test
	void theSharedFallbackSurvives() throws Exception {
		for (String code : new String[]{"zh_cn", "en_us"}) {
			JsonObject lang = lang(code);
			assertTrue(lang.has("message.thefourthfrequency.pursuit.warning"));
			assertTrue(lang.has("terminal.thefourthfrequency.signal.event.pursuit_warning.approaching"));
			assertTrue(lang.has("terminal.thefourthfrequency.signal.event.pursuit_warning.prepare"));
		}
	}

	/**
	 * The records page must read the form off the entry rather than rendering a shared line, which is
	 * the defect that hid the duplication in the first place.
	 */
	@Test
	void theRecordsPageSelectsByForm() throws Exception {
		String snapshot = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalSnapshot.java"),
				StandardCharsets.UTF_8);
		assertTrue(snapshot.contains("signal.event.pursuit_warning_\" + form"),
				"the records page must build the per-form key instead of a shared one");
		assertTrue(snapshot.contains("entry.variant()"),
				"the form has to come off the stored entry, not be guessed on the client");
	}

	/**
	 * The immediate warning stays undivided, and the explanation stays behind it.
	 *
	 * <p>The shake is what the player gets during the event; which corrector this is belongs to the
	 * records page the terminal force-opens afterwards. A per-form variant of the notice key would
	 * move the explanation in front of the event, which is the ordering the world bible fixes.
	 */
	@Test
	void theImmediateWarningIsNotSplitByForm() throws Exception {
		for (String code : new String[]{"zh_cn", "en_us"}) {
			JsonObject lang = lang(code);
			for (int form = 1; form <= PursuitProgressPolicy.FORM_COUNT; form++) {
				assertFalse(lang.has("message.thefourthfrequency.pursuit.warning." + form),
						code + " splits the in-event warning by form; the form belongs on the records page");
			}
		}
		String session = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		assertTrue(session.contains("PURSUIT_WARNING_RECORDS_REDIRECT, true"),
				"the records page is where the form is read, so the redirect must still be armed");
	}

	private static JsonObject lang(String code) throws Exception {
		return JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/" + code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
