package com.xm.thefourthfrequency.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every records line the code can file must have something to say in both languages.
 *
 * <p>Written after {@code world_interface_altar} reached a player's screen as the raw string
 * "terminal.thefourthfrequency.signal.event.world_interface_altar" - the altar being prepared is one
 * of the loudest beats in the run, and the terminal announced it by printing a translation key.
 *
 * <p>Nothing was going to catch that. Filing a record is one call in a service, adding the key is an
 * edit in two resource files, and no compiler, test or review step connected the two. It is also
 * invisible until the exact moment it fires, which for this one meant the End.
 *
 * <p>So the source is the expectation. The type strings are read back out of the calls that file
 * them, which means a new record line either has its text or fails here.
 */
final class SignalEventTextContractTest {
	private static final Path SOURCE = Path.of("src/main/java/com/xm/thefourthfrequency");
	private static final Path LANG = Path.of("src/main/resources/assets/thefourthfrequency/lang");
	private static final String PREFIX = "terminal.thefourthfrequency.signal.event.";

	/**
	 * Literal type strings, taken from the band argument that always precedes them.
	 *
	 * <p>Anchoring on {@code SignalBand.X} rather than on the method name catches both writers -
	 * {@code TerminalSignalService.record} and {@code TerminalSignalLog.append} - without having to
	 * know either name, and without matching the unrelated string literals around them.
	 */
	private static final Pattern RECORDED_TYPE =
			Pattern.compile("SignalBand\\.[A-Z_]+\\s*,\\s*\"([a-z0-9_]+)\"");

	/**
	 * Types assembled at runtime from a prefix and a number.
	 *
	 * <p>The regex sees the prefix and would demand a key for it, which does not and must not exist.
	 * Listed with the range each one is actually filed over, so the numbered keys are still checked
	 * rather than simply excused.
	 */
	private static final List<Numbered> NUMBERED = List.of(
			new Numbered("pursuit_warning_", 1, 5),
			new Numbered("fragment_near_", 1, 4),
			new Numbered("fragment_candidate_", 1, 4));

	private record Numbered(String prefix, int first, int last) {
	}

	@Test
	void everyRecordedSignalTypeHasTextInBothLanguages() throws IOException {
		JsonObject chinese = language("zh_cn");
		JsonObject english = language("en_us");
		Set<String> types = recordedTypes();
		assertFalse(types.isEmpty(), "parsed no signal types out of the sources");

		Set<String> missing = new LinkedHashSet<>();
		for (String type : types) {
			Numbered numbered = numberedFor(type);
			if (numbered == null) {
				check(chinese, english, type, missing);
				continue;
			}
			for (int index = numbered.first(); index <= numbered.last(); index++) {
				check(chinese, english, numbered.prefix() + index, missing);
			}
		}
		assertTrue(missing.isEmpty(),
				"records lines with no text - they render as the raw key on screen: " + missing);
	}

	private static void check(JsonObject chinese, JsonObject english, String type, Set<String> missing) {
		if (!chinese.has(PREFIX + type)) missing.add("zh_cn:" + type);
		if (!english.has(PREFIX + type)) missing.add("en_us:" + type);
	}

	private static Numbered numberedFor(String type) {
		for (Numbered numbered : NUMBERED) {
			// Exactly the bare prefix: "pursuit_warning_" is the literal in the source, while
			// "pursuit_warning_1" would be a real type that should be checked on its own.
			if (type.equals(numbered.prefix().substring(0, numbered.prefix().length() - 1))
					|| type.equals(numbered.prefix())) {
				return numbered;
			}
		}
		return null;
	}

	private static Set<String> recordedTypes() throws IOException {
		Set<String> types = new LinkedHashSet<>();
		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				Matcher matcher = RECORDED_TYPE.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (matcher.find()) types.add(matcher.group(1));
			}
		}
		return types;
	}

	private static JsonObject language(String code) throws IOException {
		return JsonParser.parseString(Files.readString(LANG.resolve(code + ".json"), StandardCharsets.UTF_8))
				.getAsJsonObject();
	}
}
