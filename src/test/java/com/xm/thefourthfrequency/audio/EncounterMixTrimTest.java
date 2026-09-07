package com.xm.thefourthfrequency.audio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The encounter is mixed against one headroom figure, in every place it is mixed.
 *
 * <p>The fight's cues are authored one at a time against silence and the score is baked at a
 * fraction of full scale long before the game sees it, so nothing in the pipeline ever compares the
 * two. What the player reports is not "the fight is loud", it is "the music is gone".
 * {@link AudioService#ENCOUNTER_MIX_TRIM} is the single number that answers "how loud is the fight",
 * and this exists because it has to be applied in more than one place to mean anything: the
 * encounter's library is played half from the server and half from the client, and a trim on one
 * half is not a mix, it is a new imbalance.
 *
 * <p>Asserted against the sources, because a volume is not something a unit test can hear. Brace
 * matching is enough to slice these particular method bodies out: none of them contains a string or
 * character literal.
 */
final class EncounterMixTrimTest {
	private static final Path SERVER_MIXER =
			Path.of("src/main/java/com/xm/thefourthfrequency/audio/AudioService.java");
	private static final Path CLIENT_MIXER = Path.of(
			"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfacePresentationController.java");
	private static final String TRIM = "ENCOUNTER_MIX_TRIM";

	/** Comments stripped: these files document the trim, and the prose naturally names it. */
	private static String code(Path path) throws Exception {
		return Files.readString(path, StandardCharsets.UTF_8)
				.replaceAll("(?s)/\\*.*?\\*/", " ")
				.replaceAll("//[^\\n]*", " ");
	}

	private static String body(String code, String signature) {
		int start = code.indexOf(signature);
		assertTrue(start >= 0, "the mixer no longer declares " + signature);
		int open = code.indexOf('{', start + signature.length());
		assertTrue(open > start, signature + " has no body");
		int depth = 0;
		for (int index = open; index < code.length(); index++) {
			char character = code.charAt(index);
			if (character == '{') depth++;
			else if (character == '}' && --depth == 0) return code.substring(open, index + 1);
		}
		return fail(signature + " has an unterminated body");
	}

	@Test
	void theTrimActuallyTrims() {
		assertTrue(AudioService.ENCOUNTER_MIX_TRIM > 0.0F,
				"a trim of zero is not a mix, it is a mute on the whole encounter");
		assertTrue(AudioService.ENCOUNTER_MIX_TRIM < 1.0F,
				"a trim of one leaves the fight where it was, which is on top of the score");
		// A trim this deep stops being headroom and starts being a fight nobody can hear. Wide on
		// purpose: this refuses a mistake, it does not pin a mixing decision.
		assertTrue(AudioService.ENCOUNTER_MIX_TRIM > 0.25F,
				"the fight still has to be the loudest thing in the room");
	}

	@Test
	void everyAuthoredEncounterCuePassesThroughTheTrim() throws Exception {
		String server = code(SERVER_MIXER);
		assertTrue(body(server, "public static void playBounded(").contains("Vec3.atCenterOf"));
		assertTrue(body(server, "public static void playBounded(ServerLevel level, Vec3").contains(TRIM),
				"the server's encounter cues must be mixed into the encounter's headroom");

		String client = code(CLIENT_MIXER);
		assertTrue(body(client, "private static void playBoundedLocal(").contains(TRIM),
				"the client plays half the encounter's library - the gateway changes among them - and"
						+ " a half that skips the trim sits above the half that does not");
		String ambientLoop = body(client, "private static final class AmbientLoop");
		assertTrue(body(ambientLoop, "public void tick()").contains(TRIM),
				"the ambient bed is the one encounter cue that never stops, plays at a fixed level"
						+ " wherever the player stands, and therefore buries a track rather than"
						+ " punching through it: it cannot be the one that skips the trim");
	}

	/**
	 * And the one family it deliberately skips, so the omission is not mistaken for one.
	 *
	 * <p>{@code playWithReach} states a radius and converts it to the volume field, because that field
	 * is the only reach control the sound protocol has. Any radius past sixteen blocks needs a volume
	 * above one, and the engine clamps the gain of anything above one to exactly one - so those cues
	 * are already at full gain and multiplying the figure by a trim moves nothing but the radius,
	 * which is the fault that method exists to fix. They are trimmed by their reach instead.
	 */
	@Test
	void theReachHelperIsLeftOutOfTheTrimOnPurpose() throws Exception {
		assertFalse(body(code(SERVER_MIXER), "public static void playWithReach(").contains(TRIM),
				"a trim inside playWithReach is inert - the gain it would scale is already clamped to"
						+ " one by the reach conversion - and applying it there only shrinks the radius");
	}
}
