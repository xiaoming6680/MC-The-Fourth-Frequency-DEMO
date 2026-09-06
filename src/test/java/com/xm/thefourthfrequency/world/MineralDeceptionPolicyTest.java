package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single forged mineral reading, and the guarantees that keep it from being a bug.
 *
 * <p>This is the only place in the mod where an actionable readout is allowed to be wrong. The
 * boundary that permits it also prices it: the lie must leave a contradiction the player can catch
 * afterwards, and it must not announce itself. Both halves are asserted here.
 */
final class MineralDeceptionPolicyTest {
	@Test
	void itOnlyHappensLateOnceAndOnlyInPlaceOfAnEmptyResult() {
		assertFalse(MineralDeceptionPolicy.eligible(3, false, true), "tier three is still too early");
		assertTrue(MineralDeceptionPolicy.eligible(4, false, true));
		assertTrue(MineralDeceptionPolicy.eligible(5, false, true));
		assertFalse(MineralDeceptionPolicy.eligible(5, true, true), "the latch makes it once per save");
		// Overwriting a real hit would take an ore away from the player, and would make the trace
		// incoherent: its whole content is that the log found nothing.
		assertFalse(MineralDeceptionPolicy.eligible(5, false, false));
	}

	/**
	 * The forgery has to be indistinguishable from an honest out-of-radius answer.
	 *
	 * <p>Band width is the giveaway that matters: an honest band is derived from one distance by
	 * {@link MineralSurveyPolicy}, so a forged pair invented independently would be a tell the player
	 * could learn without ever digging.
	 */
	@Test
	void aForgedReadingHasTheSameShapeAsAnHonestOne() {
		for (long seed = 0L; seed < 500L; seed++) {
			MineralDeceptionPolicy.Forgery forgery = MineralDeceptionPolicy.forge(seed);
			assertTrue(forgery.octant() >= 0 && forgery.octant() < MineralDeceptionPolicy.OCTANTS);
			assertEquals(MineralSurveyPolicy.bandMinimum(forgery.distance()), forgery.bandMinimum());
			assertEquals(MineralSurveyPolicy.bandMaximum(forgery.distance()), forgery.bandMaximum());
			assertTrue(forgery.bandMaximum() > forgery.bandMinimum());
			// Worth walking for, or the lie costs nothing and is not remembered.
			assertTrue(forgery.resource() == TerminalResource.DIAMOND
					|| forgery.resource() == TerminalResource.EMERALD, forgery.resource().toString());
			// The bearing must land on one of the eight names the terminal can print.
			assertNotEquals("", TerminalNavigationMath.direction(
					forgery.bearing().dx(), forgery.bearing().dz()));
		}
	}

	@Test
	void theSameSeedAlwaysForgesTheSameReading() {
		assertEquals(MineralDeceptionPolicy.forge(12_345L), MineralDeceptionPolicy.forge(12_345L));
		Set<String> distinct = new LinkedHashSet<>();
		for (long seed = 0L; seed < 64L; seed++) distinct.add(MineralDeceptionPolicy.forge(seed).toString());
		assertTrue(distinct.size() > 8, "the forgery must not collapse onto one reading");
	}

	/**
	 * The records line reprints the reading after the tool has cleared it, so the pack has to survive
	 * the one spare integer a log entry carries.
	 */
	@Test
	void bearingAndDistanceSurviveTheRecordsLine() {
		for (int octant = 0; octant < MineralDeceptionPolicy.OCTANTS; octant++) {
			for (int distance : new int[] {0, 1, 24, 72, 999}) {
				int packed = MineralDeceptionPolicy.packTrace(octant, distance);
				assertEquals(octant, MineralDeceptionPolicy.unpackOctant(packed));
				assertEquals(distance, MineralDeceptionPolicy.unpackDistance(packed));
			}
		}
	}

	/**
	 * The corruption marks which half of the line is untrustworthy without eating the evidence.
	 *
	 * <p>The figures are the entire reason the line is worth scrolling back to: they are what the
	 * player compares against what they remember reading off the tool. A line whose numbers were
	 * glitched out would be a mark they cannot follow.
	 */
	@Test
	void corruptionEatsWordsAndNeverDigits() {
		String source = "显示 钻石矿 东北 约 30-40 格";
		for (long seed = 0L; seed < 200L; seed++) {
			String corrupted = TerminalNavigationVisualPolicy.corruptReadout(source, seed);
			assertEquals(digitsOf(source), digitsOf(corrupted), "seed " + seed + " ate a figure");
			assertNotEquals(source, corrupted, "seed " + seed + " left the readout clean");
			assertEquals(source.length(), corrupted.length(), "corruption must not reflow the line");
		}
		assertEquals(TerminalNavigationVisualPolicy.corruptReadout(source, 7L),
				TerminalNavigationVisualPolicy.corruptReadout(source, 7L),
				"reopening the page must not reshuffle settled damage");
	}

	/**
	 * The lie and its trace are one write, and the trace does not announce itself.
	 *
	 * <p>Asserted against the source because it is an ordering property, not a value: there must be
	 * no state in which a forged reading has been shown and no contradiction exists behind it, and
	 * the terminal must not put a badge on the one line that gives away its own error. A badge would
	 * make the player told rather than letting them find out, which is the difference between a
	 * scare and dread.
	 */
	@Test
	void theTraceIsFiledWithTheLieAndIsNeverMarkedUnread() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/ResourceGuidanceService.java"),
				StandardCharsets.UTF_8);
		int start = source.indexOf("private static boolean forgeEmptyResult");
		assertTrue(start > 0, "the forgery must live in one named method");
		int end = source.indexOf("\n\t}", source.indexOf("return true;", start));
		String body = source.substring(start, end);
		assertTrue(body.contains("MINERAL_READING_FORGED, true"), "the latch must be set in the same body");
		assertTrue(body.contains("\"mineral_reading_forged\""), "the trace must be filed in the same body");
		int append = body.indexOf("TerminalSignalLog.append");
		assertTrue(append > body.indexOf("MINERAL_READING_KIND, READING_BEARING"),
				"the trace must be filed alongside the reading it contradicts");
		assertTrue(body.substring(append).contains("false);"),
				"the contradiction line must be filed read, so it carries no badge");
	}

	private static String digitsOf(String text) {
		StringBuilder digits = new StringBuilder();
		text.codePoints().filter(Character::isDigit).forEach(digits::appendCodePoint);
		return digits.toString();
	}
}
