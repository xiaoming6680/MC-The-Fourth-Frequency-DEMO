package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalProfileQuestionnaireTest {
	/**
	 * The dial must not open already sitting on an answer.
	 *
	 * <p>This is the bug this test was written after, not before. The receiver rests at
	 * {@code DEFAULT_TUNING}, and three of the five questions had an option inside the lock radius of
	 * it - so the hold started the instant the question appeared and committed a second later. Three
	 * questions answered themselves before the player touched anything, and every other test in this
	 * file passed the whole time.
	 *
	 * <p>What they checked was the shape of the band: no dead zones, peaks far enough apart, the lock
	 * window behaving. None of them asked the one question that mattered - where does the player
	 * <em>start</em>, and is that somewhere already meaningful. A layout can be perfectly spaced and
	 * still be wrong because of where it is entered from.
	 */
	@Test
	void noQuestionOpensWithTheDialAlreadyOnAnAnswer() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			assertEquals(-1, TerminalProfileQuestionnaire.lockedOption(
					question, TerminalControlPolicy.DEFAULT_TUNING),
					"question " + question + " is already answered when it appears");
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				int distance = Math.abs(TerminalProfileQuestionnaire.optionTuning(question, option)
						- TerminalControlPolicy.DEFAULT_TUNING);
				assertTrue(distance >= TerminalProfileQuestionnaire.MIN_DEFAULT_CLEARANCE,
						"question " + question + " option " + option + " sits " + distance
								+ " from the resting dial, inside the required clearance");
			}
		}
	}

	/**
	 * Clearance is the arrangement; this is the rule.
	 *
	 * <p>Spacing the options away from the resting position fixes the arrangement that shipped, and
	 * nothing more - the next question somebody adds could put one back. The commit path additionally
	 * refuses to fire until the dial has moved, so a profile cannot record an answer the player never
	 * gave however the band is laid out.
	 */
	@Test
	void anUntouchedDialIsNotAllowedToCommit() {
		assertTrue(TerminalProfileQuestionnaire.requiresMovement(),
				"a profile must not be able to contain an answer nobody chose");
	}

	/**
	 * The one that would ruin the opening minute if it broke.
	 *
	 * <p>The player is sweeping a band with nothing to go on but the strength meter. If any position
	 * on any question reads zero, the needle is dead there and the only way out is to guess a
	 * direction - on the very first screen of the mod, while the exit is held. Every option pair is
	 * spaced against the strength curve so that never happens, and this asserts the outcome rather
	 * than the spacing, because the spacing is the implementation of it.
	 */
	@Test
	void everyTuningPositionLeavesTheNeedleSomethingToClimb() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			for (int tuning = 0; tuning <= 100; tuning++) {
				assertTrue(TerminalProfileQuestionnaire.strength(question, tuning) > 0,
						"dead zone at question " + question + " tuning " + tuning);
			}
			// Uphill has to actually lead somewhere: strength never rises as you walk away from the
			// option you are nearest to. A needle that is merely non-zero but flat is still a dead end.
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				int centre = TerminalProfileQuestionnaire.optionTuning(question, option);
				for (int step = 1; step <= 20; step++) {
					int nearer = TerminalProfileQuestionnaire.strength(question, centre + step - 1);
					int farther = TerminalProfileQuestionnaire.strength(question, centre + step);
					if (TerminalProfileQuestionnaire.nearestOption(question, centre + step) == option) {
						assertTrue(farther <= nearer, "question " + question + " rises while moving away");
					}
				}
			}
		}
	}

	/**
	 * Full legibility belongs to the lock, and to nothing else.
	 *
	 * <p>Text and needle run on different curves on purpose. The needle is allowed to acknowledge a
	 * station from across the band, because it is the only thing keeping the player oriented while
	 * the exit is held. The text is not: if option text tracked the same floor, every option would be
	 * faintly legible from anywhere and the slider would degrade into a decorated radio button.
	 *
	 * <p>Partial emergence on approach is wanted - watching an answer assemble as you close on it is
	 * the mechanic, and the reveal range is deliberately much wider than the meter's so an option
	 * starts forming well before the dial arrives.
	 *
	 * <p>This used to demand fewer than half the glyphs at a midpoint, which was a stricter rule than
	 * the design ever had and it came from nowhere but this test. In play it was the wrong strictness:
	 * options stayed pure noise until the dial was almost on them, so sweeping felt like hunting in
	 * the dark rather than closing on something. The contract that actually matters is the jump to
	 * <em>full</em> legibility, and that still happens at the lock and nowhere else.
	 */
	@Test
	void onlyALockedOptionIsFullyLegible() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			int options = TerminalProfileQuestionnaire.optionCount(question);
			for (int option = 1; option < options; option++) {
				int between = (TerminalProfileQuestionnaire.optionTuning(question, option - 1)
						+ TerminalProfileQuestionnaire.optionTuning(question, option)) / 2;
				assertTrue(TerminalProfileQuestionnaire.strength(question, between) > 0,
						"the needle still has to work between stations");
				assertEquals(-1, TerminalProfileQuestionnaire.lockedOption(question, between));
				for (int candidate = 0; candidate < options; candidate++) {
					double progress = TerminalProfileQuestionnaire.settleProgress(question, between, candidate);
					assertTrue(progress < 1.0D, "question " + question + " option " + candidate
							+ " is fully legible from between stations");
				}
			}
		}
	}

	/**
	 * Full legibility is a cliff at the lock radius, not a slope that happens to reach the top.
	 *
	 * <p>The reveal is wide on purpose so answers form as you approach, which makes this the rule
	 * doing the actual work: one step outside the radius must already be short of complete. Without
	 * that, widening the reveal a little further would quietly make options readable from between two
	 * stations and the dial would stop being how you find them.
	 */
	@Test
	void fullLegibilityBeginsExactlyAtTheLock() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				int centre = TerminalProfileQuestionnaire.optionTuning(question, option);
				// Probed toward the middle of the band. Stepping outward from an option near an end
				// runs off 0-100 and the clamp brings the position back, so the distance under test
				// would not be the distance intended - which is what an earlier draft of this got
				// wrong.
				int direction = centre <= 50 ? 1 : -1;
				assertEquals(1.0D, TerminalProfileQuestionnaire.settleProgress(question, centre, option));
				assertEquals(1.0D, TerminalProfileQuestionnaire.settleProgress(question,
						centre + direction * TerminalProfileQuestionnaire.LOCK_RADIUS, option));
				assertTrue(TerminalProfileQuestionnaire.settleProgress(question,
						centre + direction * (TerminalProfileQuestionnaire.LOCK_RADIUS + 1), option) < 1.0D,
						"question " + question + " option " + option + " is complete outside the lock");
				// And it does start forming from a long way off, which is the point of the wide range.
				assertTrue(TerminalProfileQuestionnaire.settleProgress(question,
						centre + direction * (TerminalProfileQuestionnaire.REVEAL_RANGE - 1), option) > 0.0D,
						"an option must be assembling before the dial reaches it");
				assertEquals(0.0D, TerminalProfileQuestionnaire.settleProgress(question,
						centre + direction * TerminalProfileQuestionnaire.REVEAL_RANGE, option));
			}
		}
	}

	/** The hold is long enough to read what you have landed on before it is taken as your answer. */
	@Test
	void theHoldLeavesTimeToRead() {
		assertTrue(TerminalProfileQuestionnaire.COMMIT_HOLD_TICKS >= 30,
				"a second is barely time to finish reading the option before it commits");
	}

	/**
	 * Sweeping the band must produce one clear peak per option, not a plateau.
	 *
	 * <p>Three peaks rising and falling is the entire tutorial for how a receiver works, and the
	 * player has to get it without being told. Two options close enough for their skirts to overlap
	 * into one broad hump would teach the opposite.
	 */
	@Test
	void optionsAreFarEnoughApartToReadAsSeparateStations() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			int options = TerminalProfileQuestionnaire.optionCount(question);
			assertTrue(options >= 2, "question " + question + " needs at least two options");
			for (int option = 1; option < options; option++) {
				int gap = TerminalProfileQuestionnaire.optionTuning(question, option)
						- TerminalProfileQuestionnaire.optionTuning(question, option - 1);
				assertTrue(gap >= 29, "question " + question + " options " + (option - 1)
						+ " and " + option + " are only " + gap + " apart");
			}
		}
	}

	/** Tuning onto an option locks it; the space between two of them locks nothing. */
	@Test
	void lockFollowsTheRadiusAndNothingElse() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				int centre = TerminalProfileQuestionnaire.optionTuning(question, option);
				assertEquals(option, TerminalProfileQuestionnaire.lockedOption(question, centre));
				assertEquals(option, TerminalProfileQuestionnaire.lockedOption(question,
						centre + TerminalProfileQuestionnaire.LOCK_RADIUS));
				assertEquals(option, TerminalProfileQuestionnaire.lockedOption(question,
						centre - TerminalProfileQuestionnaire.LOCK_RADIUS));
				assertEquals(1.0D, TerminalProfileQuestionnaire.settleProgress(question, centre, option));
			}
		}
		// Halfway between the first two options of the first question is nobody's station.
		int between = (TerminalProfileQuestionnaire.optionTuning(0, 0)
				+ TerminalProfileQuestionnaire.optionTuning(0, 1)) / 2;
		assertEquals(-1, TerminalProfileQuestionnaire.lockedOption(0, between));
		assertTrue(TerminalProfileQuestionnaire.settleProgress(0, between, 0) < 1.0D);
		assertTrue(TerminalProfileQuestionnaire.settleProgress(0, between, 1) < 1.0D);
	}

	/**
	 * The server's guard against a forged answer.
	 *
	 * <p>Any legal option is a legal answer, so there is nothing to exploit by choosing one - but an
	 * index outside the question's range would index into whatever the storage happened to hold, and
	 * the whole point of a one-shot latch is that there is no second chance to fix it.
	 */
	@Test
	void outOfRangeAnswersAreRejected() {
		assertFalse(TerminalProfileQuestionnaire.validAnswer(0, -1));
		assertFalse(TerminalProfileQuestionnaire.validAnswer(0, TerminalProfileQuestionnaire.optionCount(0)));
		assertFalse(TerminalProfileQuestionnaire.validAnswer(-1, 0));
		assertFalse(TerminalProfileQuestionnaire.validAnswer(
				TerminalProfileQuestionnaire.questionCount(), 0));
		assertTrue(TerminalProfileQuestionnaire.validAnswer(0, 0));
	}

	/**
	 * An interrupted profile stays interrupted.
	 *
	 * <p>The damage failsafe can release the walkthrough mid-question, and the terminal has to say so
	 * rather than quietly filling the gap with a default. Silently defaulting would be the exact
	 * failure the safety rules forbid: an operational value that is wrong but plausible.
	 */
	@Test
	void oneUnansweredQuestionMeansIncomplete() {
		int count = TerminalProfileQuestionnaire.questionCount();
		int[] answers = new int[count];
		assertTrue(TerminalProfileQuestionnaire.complete(answers));
		answers[count - 1] = TerminalProfileQuestionnaire.UNANSWERED;
		assertFalse(TerminalProfileQuestionnaire.complete(answers));
		assertFalse(TerminalProfileQuestionnaire.complete(null));
		assertFalse(TerminalProfileQuestionnaire.complete(new int[count - 1]));
	}

	/** Holding commits; sweeping across does not. */
	@Test
	void commitNeedsTheFullHold() {
		assertFalse(TerminalProfileQuestionnaire.commits(0));
		assertFalse(TerminalProfileQuestionnaire.commits(
				TerminalProfileQuestionnaire.COMMIT_HOLD_TICKS - 1));
		assertTrue(TerminalProfileQuestionnaire.commits(
				TerminalProfileQuestionnaire.COMMIT_HOLD_TICKS));
	}

	/**
	 * No question may be a dead end.
	 *
	 * <p>The walkthrough is only allowed to hold the exit because it has a definite end. A player who
	 * cannot find a peak gets the device sweeping for them - but only if they have genuinely never
	 * locked anything, so a player who is deliberating over an option they already found is left
	 * alone.
	 */
	@Test
	void theAssistSweepIsForPlayersWhoFoundNothing() {
		assertFalse(TerminalProfileQuestionnaire.assistDue(0L, false));
		assertTrue(TerminalProfileQuestionnaire.assistDue(
				TerminalProfileQuestionnaire.ASSIST_SWEEP_MILLIS, false));
		assertFalse(TerminalProfileQuestionnaire.assistDue(
				TerminalProfileQuestionnaire.ASSIST_SWEEP_MILLIS * 4, true));
	}

	/** Ids are what the language files key off, so a duplicate would silently show the wrong line. */
	@Test
	void everyQuestionAndOptionIdIsDistinct() {
		Set<String> questionIds = new HashSet<>();
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			String id = TerminalProfileQuestionnaire.questionId(question);
			assertFalse(id.isBlank(), "question " + question + " has no id");
			assertTrue(questionIds.add(id), "duplicate question id " + id);
			Set<String> optionIds = new HashSet<>();
			for (int option = 0; option < TerminalProfileQuestionnaire.optionCount(question); option++) {
				String optionId = TerminalProfileQuestionnaire.optionId(question, option);
				assertFalse(optionId.isBlank(), id + " option " + option + " has no id");
				assertTrue(optionIds.add(optionId), "duplicate option id " + id + "." + optionId);
			}
		}
	}

	/** The same slider position must always mean the same answer, including on a tie. */
	@Test
	void tiesBreakLowSoTheAnswerIsAFunctionOfTuningAlone() {
		int low = TerminalProfileQuestionnaire.optionTuning(0, 0);
		int high = TerminalProfileQuestionnaire.optionTuning(0, 1);
		if ((low + high) % 2 != 0) return; // no exact midpoint to tie on
		assertEquals(0, TerminalProfileQuestionnaire.nearestOption(0, (low + high) / 2));
	}
}
