package com.xm.thefourthfrequency.content;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every key in the terminal record has both a writer and a reader, bar three named records.
 *
 * <p>A key that is written and never read is almost always a consumer that was deleted or renamed
 * during a refactor, and it is the quietest failure this record has: the server keeps maintaining a
 * value, the save keeps carrying it, and the behaviour that used to depend on it is simply gone with
 * nothing on screen or in a log to say so. Nothing else in the suite would notice - the writer still
 * compiles, the schema still round-trips, and the tests that cover the feature were written against
 * the writer.
 *
 * <p>The reverse direction is not asserted because it cannot go quiet: a key that is read and never
 * written just reads as its default, which shows up as behaviour that never triggers.
 */
final class TerminalDataKeyContractTest {
	/**
	 * The keys that are deliberately written and never read.
	 *
	 * <p>Each one sits beside the flag that actually drives the behaviour and exists so a save can be
	 * read back by a human. Their declarations in {@code TerminalData} say so; this list is the
	 * enforcement, and a fourth entry appearing here should be treated as a missing consumer until
	 * somebody proves otherwise.
	 */
	private static final Set<String> DELIBERATE_RECORDS = Set.of(
			"EMPTY_SEGMENT_EVENT", "LAST_PORTAL_ORIGIN", "TERMINAL_CAPTURED_TICK");

	private static final Path TERMINAL_DATA =
			Path.of("src/main/java/com/xm/thefourthfrequency/content/TerminalData.java");
	private static final List<Path> SOURCE_ROOTS = List.of(
			Path.of("src/main/java"), Path.of("src/client/java"),
			Path.of("src/gametest/java"), Path.of("src/test/java"));

	@Test
	void everyRecordKeyThatIsWrittenIsAlsoReadSomewhere() throws IOException {
		String declarations = Files.readString(TERMINAL_DATA, StandardCharsets.UTF_8);
		String everything = readAllSources();
		Matcher constants = Pattern.compile(
				"public static final String ([A-Z_0-9]+)\\s*=\\s*\"[^\"]+\"").matcher(declarations);
		List<String> writtenNeverRead = new ArrayList<>();
		int total = 0;
		while (constants.find()) {
			String name = constants.group(1);
			total++;
			int writes = count(everything, "put\\w*\\(\\s*(?:TerminalData\\.)?" + name + "\\b");
			int reads = count(everything,
					"(?:get\\w*|contains|remove)\\(\\s*(?:TerminalData\\.)?" + name + "\\b");
			if (writes > 0 && reads == 0) writtenNeverRead.add(name);
		}
		assertTrue(total > 100, "the key scan matched almost nothing, so it is testing itself");
		assertEquals(new LinkedHashSet<>(DELIBERATE_RECORDS), new LinkedHashSet<>(writtenNeverRead),
				"terminal record keys that are written but never read again - a new one here is a "
						+ "dropped consumer until proven otherwise");
	}

	private static int count(String haystack, String regex) {
		Matcher matcher = Pattern.compile(regex).matcher(haystack);
		int found = 0;
		while (matcher.find()) found++;
		return found;
	}

	private static String readAllSources() throws IOException {
		StringBuilder joined = new StringBuilder();
		for (Path root : SOURCE_ROOTS) {
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					joined.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
				}
			}
		}
		return joined.toString();
	}
}
