package com.xm.thefourthfrequency.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The background score must play everything it has before it plays anything twice. */
final class MusicRotationPolicyTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");

	@BeforeEach
	void forget() {
		MusicRotationPolicy.forgetAll();
	}

	@Test
	void onlyTheMultiTrackScoreEventsRotate() {
		assertTrue(MusicRotationPolicy.rotates("music_game"));
		assertTrue(MusicRotationPolicy.rotates("music_menu"));
		// One-track events: "not the last one" cannot be honoured and must not be attempted.
		assertFalse(MusicRotationPolicy.rotates("music_pursuit"));
		assertFalse(MusicRotationPolicy.rotates("music_encounter_phase_1"));
		assertFalse(MusicRotationPolicy.rotates("music_encounter_final"));
		// The attack cues hold three variants precisely so they can repeat freely.
		assertFalse(MusicRotationPolicy.rotates("world_interface_laser"));
		assertFalse(MusicRotationPolicy.rotates(null));
	}

	@Test
	void aTrackIsOutForTheRestOfItsPassAndOnlyForItsOwnEvent() {
		MusicRotationPolicy.remember("music_game", "thefourthfrequency:music/game/hi");
		assertTrue(MusicRotationPolicy.playedThisPass("music_game", "thefourthfrequency:music/game/hi"));
		assertFalse(MusicRotationPolicy.playedThisPass("music_game", "thefourthfrequency:music/game/tenshi"));
		// The two playlists keep separate histories, or the menu theme would constrain the game one.
		assertFalse(MusicRotationPolicy.playedThisPass("music_menu", "thefourthfrequency:music/game/hi"));
		assertFalse(MusicRotationPolicy.playedThisPass("music_game", null));
	}

	/** A pass accumulates rather than replacing, which is what makes it a pass and not a memory of one. */
	@Test
	void thePassRemembersEveryTrackUntilItIsReset() {
		MusicRotationPolicy.remember("music_game", "a");
		MusicRotationPolicy.remember("music_game", "b");
		MusicRotationPolicy.remember("music_game", "c");
		assertEquals(3, MusicRotationPolicy.passSize("music_game"));
		assertTrue(MusicRotationPolicy.playedThisPass("music_game", "a"));
		assertTrue(MusicRotationPolicy.followsItself("music_game", "c"));
		assertFalse(MusicRotationPolicy.followsItself("music_game", "a"));

		MusicRotationPolicy.startNewPass("music_game", "d");
		assertEquals(1, MusicRotationPolicy.passSize("music_game"));
		assertFalse(MusicRotationPolicy.playedThisPass("music_game", "a"),
				"a new pass must make the whole pool eligible again");
		assertTrue(MusicRotationPolicy.playedThisPass("music_game", "d"));
	}

	/**
	 * The exact loop the mixin runs, against a source that would otherwise stutter badly.
	 *
	 * <p>The draw is deliberately hostile: it returns each track twice before moving on. What is
	 * asserted is what the player actually gets - the pool is consumed in complete passes, and no
	 * two adjacent tracks are ever the same, including across the seam between two passes.
	 */
	@Test
	void everyTrackPlaysOncePerPassAndNoTwoAdjacentTracksRepeat() {
		List<String> pool = List.of("a", "b", "c", "d", "e", "f", "g");
		int[] cursor = {0};
		Supplier<String> draw = stutteringDraw(pool, cursor);
		List<String> played = new ArrayList<>();
		for (int round = 0; round < 210; round++) played.add(drawLikeTheMixin(pool, draw));

		for (int index = 1; index < played.size(); index++) {
			assertDiffersFromPredecessor(played.get(index - 1), played.get(index), index, played);
		}
		// Consumed in whole passes: every window of pool.size() consecutive picks is the entire pool.
		for (int start = 0; start + pool.size() <= played.size(); start += pool.size()) {
			assertEquals(new LinkedHashSet<>(pool),
					new LinkedHashSet<>(played.subList(start, start + pool.size())),
					"pass starting at " + start + " did not use every track: " + played);
		}
	}

	/**
	 * The shipped pool sizes, drawn from at random, must still consume complete passes.
	 *
	 * <p>The deterministic source above cannot see the failure this guards. That one hands back
	 * every track in turn, so the search for an unplayed one always succeeds quickly; a real pool is
	 * drawn from uniformly, and finding the <em>last</em> unplayed track of a nine-track pass rejects
	 * eight draws in nine. The previous implementation gave up after a fixed thirty-two attempts and
	 * treated that as the pass being finished, which silently dropped that last track about one pass
	 * in thirty - invisible to a deterministic test and audible to a player as "I never hear that
	 * one". Ending a pass on a count instead makes the guarantee exact, and this asserts it at the
	 * sizes actually shipped rather than at a convenient one.
	 */
	@Test
	void randomDrawsStillConsumeWholePassesAtTheShippedPoolSizes() {
		for (int size : new int[]{4, 9}) {
			List<String> pool = new ArrayList<>();
			for (int index = 0; index < size; index++) pool.add("track" + index);
			// Fixed seed: a rotation that only usually works must fail every run, not one in thirty.
			Random random = new Random(20260829L + size);
			MusicRotationPolicy.forgetAll();
			List<String> played = new ArrayList<>();
			// Four hundred passes, not a handful. The old implementation ended a nine-track pass
			// early about once in thirty-five, so a short run would have passed most seeds and this
			// would have been a test that only usually noticed.
			int passes = 400;
			for (int round = 0; round < size * passes; round++) {
				played.add(drawLikeTheMixin(pool, () -> pool.get(random.nextInt(pool.size()))));
			}
			for (int start = 0; start + size <= played.size(); start += size) {
				assertEquals(new LinkedHashSet<>(pool),
						new LinkedHashSet<>(played.subList(start, start + size)),
						"pool of " + size + ": pass at " + start + " did not use every track: "
								+ played.subList(start, start + size));
			}
			for (int index = 1; index < played.size(); index++) {
				assertDiffersFromPredecessor(played.get(index - 1), played.get(index), index, played);
			}
		}
	}

	/**
	 * The exact old failure, made deterministic: exhausting the re-draws must not end a pass.
	 *
	 * <p>The random test above would catch this eventually; this one catches it every run and
	 * without a die roll. The draw here never offers anything new, which is precisely the state the
	 * old implementation read as "the pool is spent" - it would call {@code startNewPass} and throw
	 * away the two tracks the pass had not reached. The pass must instead stay exactly as it was,
	 * still owing them.
	 */
	@Test
	void exhaustingTheRedrawsRepeatsATrackButDoesNotEndThePass() {
		List<String> pool = List.of("a", "b", "c", "d", "e");
		MusicRotationPolicy.remember("music_game", "a");
		MusicRotationPolicy.remember("music_game", "b");
		MusicRotationPolicy.remember("music_game", "c");

		// A pool that will only ever hand back something already used.
		String repeated = drawLikeTheMixin(pool, () -> "a");

		assertEquals("a", repeated, "with nothing else on offer the draw has to return the repeat");
		assertEquals(3, MusicRotationPolicy.passSize("music_game"),
				"a repeat must not grow the pass, and must not reset it either");
		for (String owed : List.of("d", "e")) {
			assertFalse(MusicRotationPolicy.playedThisPass("music_game", owed),
					owed + " was never played and must still be owed to the player");
		}
	}

	/**
	 * A pass ends by counting the pool, not by running out of attempts.
	 *
	 * <p>Stated directly because it is the whole fix: a pass with tracks still owed stays open no
	 * matter how many draws come back used, and an unknown pool size never ends one.
	 */
	/** The per-track ceiling scales with the pool but cannot scale into a stall. */
	@Test
	void theRerollCeilingScalesWithThePoolAndThenStops() {
		assertEquals(32 * 10, MusicRotationPolicy.rerollCeiling(10));
		assertEquals(32 * 5, MusicRotationPolicy.rerollCeiling(5));
		// A pack could redefine the event with any number of entries; the search must stay bounded.
		assertEquals(MusicRotationPolicy.MAX_REROLL_CEILING, MusicRotationPolicy.rerollCeiling(100_000));
		// A degenerate size must still allow one look rather than none.
		assertEquals(32, MusicRotationPolicy.rerollCeiling(0));
	}

	@Test
	void aPassEndsOnlyWhenTheCountSaysThePoolIsSpent() {
		MusicRotationPolicy.remember("music_game", "a");
		MusicRotationPolicy.remember("music_game", "b");
		assertFalse(MusicRotationPolicy.passComplete("music_game", 3),
				"two of three played is not a finished pass");
		assertTrue(MusicRotationPolicy.passComplete("music_game", 2));
		// A pool that shrank underneath the pass still ends it rather than stranding it.
		assertTrue(MusicRotationPolicy.passComplete("music_game", 1));
		// No size means no evidence, so the pass is left alone.
		assertFalse(MusicRotationPolicy.passComplete("music_game", 0));
		assertFalse(MusicRotationPolicy.passComplete("music_game", -1));
	}

	/**
	 * A pool of one must terminate rather than spin, and must not be able to stall the seam loop
	 * either - it has nothing else to offer and the ceiling is what says so.
	 */
	@Test
	void aSingleTrackPoolTerminatesInsteadOfSpinning() {
		List<String> pool = List.of("only");
		int[] cursor = {0};
		Supplier<String> draw = stutteringDraw(pool, cursor);
		for (int round = 0; round < 20; round++) {
			assertEquals("only", drawLikeTheMixin(pool, draw));
		}
		assertEquals(1, MusicRotationPolicy.passSize("music_game"));
	}

	/**
	 * Mirrors {@code AbstractSoundInstanceRotationMixin} exactly; the loops are the contract.
	 *
	 * <p>{@code draw} stands in for {@code WeighedSoundEvents#getSound}, so a test can hand this
	 * either a hostile deterministic source or a genuinely random one and get the same code path.
	 */
	private static String drawLikeTheMixin(List<String> pool, Supplier<String> draw) {
		String pick = draw.get();
		if (MusicRotationPolicy.passComplete("music_game", pool.size())) {
			for (int attempt = 0; attempt < MusicRotationPolicy.SEAM_REROLLS
					&& MusicRotationPolicy.followsItself("music_game", pick); attempt++) {
				pick = draw.get();
			}
			MusicRotationPolicy.startNewPass("music_game", pick);
			return pick;
		}
		for (int attempt = 0; attempt < MusicRotationPolicy.rerollCeiling(pool.size())
				&& MusicRotationPolicy.playedThisPass("music_game", pick); attempt++) {
			pick = draw.get();
		}
		MusicRotationPolicy.remember("music_game", pick);
		return pick;
	}

	/** The original hostile source: every track comes back twice before the cursor moves on. */
	private static Supplier<String> stutteringDraw(List<String> pool, int[] cursor) {
		return () -> pool.get(cursor[0]++ / 2 % pool.size());
	}

	private static void assertDiffersFromPredecessor(String previous, String current, int index,
			List<String> played) {
		assertFalse(previous.equals(current),
				"track " + index + " repeats the one before it in " + played);
	}

	/**
	 * The declared list and the actual pools have to agree, in both directions.
	 *
	 * <p>This is the half that will still be true in a year. Adding an eighth game track is a one
	 * line change in {@code sounds.json} and nothing about it suggests that a policy elsewhere needs
	 * looking at; adding a whole new score event even less so. Deriving the expectation from the
	 * resource means the omission fails here instead of being noticed by ear.
	 */
	@Test
	void everyMultiTrackScoreEventIsDeclaredAndEveryDeclaredOneExists() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		Set<String> multiTrack = new LinkedHashSet<>();
		Set<String> singleTrack = new LinkedHashSet<>();
		for (String event : sounds.keySet()) {
			if (!event.startsWith("music_")) continue;
			JsonArray pool = sounds.getAsJsonObject(event).getAsJsonArray("sounds");
			(pool.size() > 1 ? multiTrack : singleTrack).add(event);
		}
		assertFalse(multiTrack.isEmpty(), "parsed no score events out of sounds.json");
		assertEquals(multiTrack, new LinkedHashSet<>(MusicRotationPolicy.rotatingEvents()),
				"the rotating list and the multi-track score events in sounds.json disagree");
		for (String event : singleTrack) {
			assertFalse(MusicRotationPolicy.rotates(event),
					event + " holds one track; rotating it would only cost re-draws");
		}
	}
}
