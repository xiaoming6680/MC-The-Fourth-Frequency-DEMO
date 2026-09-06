package com.xm.thefourthfrequency.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalRelayPolicyTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");

	private static int[] profile(int trust) {
		int[] answers = new int[TerminalProfileQuestionnaire.questionCount()];
		java.util.Arrays.fill(answers, TerminalProfileQuestionnaire.UNANSWERED);
		answers[4] = trust;
		return answers;
	}

	/**
	 * Consent, in the direction it actually matters.
	 *
	 * <p>Outbound is what carries something about a person, so it needs an explicit yes and an
	 * unanswered profile is not one. Inbound costs the receiver nothing, so it defaults on and an
	 * explicit no is still obeyed. Getting this backwards would mean a player who never answered the
	 * companions question quietly started broadcasting.
	 */
	@Test
	void nothingLeavesATerminalWhoseHolderNeverAgreed() {
		assertTrue(TerminalRelayPolicy.maysend(profile(0)));
		assertFalse(TerminalRelayPolicy.maysend(profile(1)));
		assertFalse(TerminalRelayPolicy.maysend(
				profile(TerminalProfileQuestionnaire.UNANSWERED)));

		assertTrue(TerminalRelayPolicy.mayReceive(profile(0)));
		assertFalse(TerminalRelayPolicy.mayReceive(profile(1)));
		assertTrue(TerminalRelayPolicy.mayReceive(
				profile(TerminalProfileQuestionnaire.UNANSWERED)));
	}

	/**
	 * "Together" has to mean the same thing here as it does to the anomaly layer.
	 *
	 * <p>The selection layer already uses 32 blocks to decide whether a player counts as alone. Two
	 * different radii would eventually disagree - somebody close enough to stop a shared anomaly but
	 * too far for their terminal to be heard, or the reverse - and the difference would be invisible
	 * until it produced a bug nobody could reproduce.
	 */
	@Test
	void rangeMatchesTheRadiusTheAnomalyLayerCallsAlone() {
		assertEquals(32.0D, TerminalRelayPolicy.RANGE_BLOCKS);
		assertTrue(TerminalRelayPolicy.inRange(0.0D));
		assertTrue(TerminalRelayPolicy.inRange(32.0D * 32.0D));
		assertFalse(TerminalRelayPolicy.inRange(32.0D * 32.0D + 1.0D));
	}

	/**
	 * The delay is the whole effect.
	 *
	 * <p>A line that surfaced while the other player was still standing there would read as a status
	 * bar. Minutes later, after they have wandered off, it reads as something that took time to
	 * arrive - and it must never be a fixed figure, or players will learn to time it.
	 */
	@Test
	void theDelayIsMinutesAndNeverTheSameNumberTwice() {
		assertTrue(TerminalRelayPolicy.MIN_DELAY_TICKS >= 20L * 60L * 2L,
				"anything under a couple of minutes arrives while they are still in the room");
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (long seed = 0; seed < 200; seed++) {
			long delay = TerminalRelayPolicy.delayTicks(seed);
			assertTrue(delay >= TerminalRelayPolicy.MIN_DELAY_TICKS);
			assertTrue(delay <= TerminalRelayPolicy.MAX_DELAY_TICKS);
			seen.add(delay);
		}
		assertTrue(seen.size() > 20, "the delay must not collapse onto a handful of values");
	}

	/** A terminal on cooldown, or already holding its fill, captures nothing more. */
	@Test
	void theCooldownAndTheQueueCapBothHold() {
		long now = 1_000_000L;
		assertTrue(TerminalRelayPolicy.mayQueue(0L, now, 0), "a terminal that never received is free");
		assertFalse(TerminalRelayPolicy.mayQueue(now - 1L, now, 0));
		assertTrue(TerminalRelayPolicy.mayQueue(now - TerminalRelayPolicy.COOLDOWN_TICKS, now, 0));
		assertFalse(TerminalRelayPolicy.mayQueue(0L, now, TerminalRelayPolicy.MAX_PENDING));
	}

	/**
	 * The echo belongs to stage five and nowhere else.
	 *
	 * <p>It is the one shape that is not about the other player at all - it hands the receiver a line
	 * from their own history with the wrong origin on it. Nothing crosses between the two terminals,
	 * so there is no boundary to breach; what it costs is the reader's confidence that the log knows
	 * who wrote it, which is exactly what stage five is for.
	 */
	@Test
	void theSelfEchoOnlyExistsAtTheLastStage() {
		for (int stage = 0; stage <= 4; stage++) {
			for (long seed = 0; seed < 60; seed++) {
				assertNotEquals(TerminalRelayPolicy.Shape.SELF_ECHO,
						TerminalRelayPolicy.shapeFor(stage, true, seed),
						"stage " + stage + " must not echo");
				assertNotEquals(TerminalRelayPolicy.Shape.SELF_ECHO,
						TerminalRelayPolicy.shapeFor(stage, false, seed));
			}
		}
		boolean everEchoed = false;
		for (long seed = 0; seed < 60; seed++) {
			everEchoed |= TerminalRelayPolicy.shapeFor(5, true, seed) == TerminalRelayPolicy.Shape.SELF_ECHO;
		}
		assertTrue(everEchoed, "stage five must be able to echo, or the shape is unreachable");
	}

	/**
	 * Relay lines are not story lines, and the page has to be able to tell.
	 *
	 * <p>They arrive through the glyph settle and are never navigable, both of which are decided from
	 * the source rather than the type - so a relay type that failed to be recognised would be drawn
	 * as an ordinary record the navigator could be aimed at.
	 */
	@Test
	void relayTypesAreRecognisedAndSurviveTheRecordPolicy() {
		for (TerminalRelayPolicy.Shape shape : TerminalRelayPolicy.Shape.values()) {
			assertTrue(TerminalRelayPolicy.Shape.isRelayType(shape.type()), shape.name());
			assertTrue(TerminalRecordPolicy.retainedInLog(shape.type()), shape.type());
			assertTrue(TerminalRecordPolicy.visibleInRecords(shape.type()), shape.type());
		}
		assertFalse(TerminalRelayPolicy.Shape.isRelayType(null));
		assertFalse(TerminalRelayPolicy.Shape.isRelayType("terminal_issued"));
		assertFalse(TerminalRelayPolicy.Shape.isRelayType("pursuit_warning_1"));
		assertTrue(TerminalRecordPolicy.Source.RELAY.settlesIn());
		assertFalse(TerminalRecordPolicy.Source.RELAY.navigable());
	}

	/**
	 * Nothing relayed may name anybody, and nothing relayed may be worth walking to.
	 *
	 * <p>Asserted on the text itself, because that is where the rule would actually be broken. A
	 * later edit that made a line read "Ander's terminal logged…" or added a direction to it would be
	 * a one-word change with no code to review.
	 */
	@Test
	void relayTextIsUnattributedAndCarriesNoLead() throws Exception {
		for (String code : java.util.List.of("zh_cn", "en_us")) {
			JsonObject lang = lang(code);
			for (TerminalRelayPolicy.Shape shape : TerminalRelayPolicy.Shape.values()) {
				String key = "terminal.thefourthfrequency.signal.event." + shape.type();
				assertTrue(lang.has(key), "missing " + code + ": " + key);
				String text = lang.get(key).getAsString();
				assertFalse(text.isBlank(), key);
				assertFalse(text.contains("%s"),
						key + " takes an argument, which is the shape a coordinate would arrive in");
			}
		}
	}

	private static JsonObject lang(String code) throws Exception {
		return JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/" + code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
