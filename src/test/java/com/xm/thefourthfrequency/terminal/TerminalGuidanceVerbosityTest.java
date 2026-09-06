package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalGuidanceVerbosityTest {
	private static final int PRIOR_CONTACT = TerminalProfileQuestionnaire.questionCount() - 1;

	@Test
	void theSixthQuestionIsTheOneThisReads() {
		assertEquals("prior_contact", TerminalProfileQuestionnaire.questionId(PRIOR_CONTACT),
				"the verbosity policy indexes the profile by position, so the question must stay last");
	}

	@Test
	void sayingYouHaveNotMetItBeforeGetsExplanations() {
		assertEquals(TerminalGuidanceVerbosity.VERBOSE, TerminalGuidanceVerbosity.of(answers(0), true));
	}

	@Test
	void sayingYouHaveMetItBeforeDoesNot() {
		assertEquals(TerminalGuidanceVerbosity.TERSE, TerminalGuidanceVerbosity.of(answers(1), true));
	}

	/**
	 * Having watched somebody else play is not having used the dial. The cost of being wrong in this
	 * direction is half a line somebody skims; in the other it is a player who never finds out how to
	 * open the thing the reminder is telling them to read.
	 */
	@Test
	void unclearIsReadAsNoPriorContact() {
		assertEquals(TerminalGuidanceVerbosity.VERBOSE, TerminalGuidanceVerbosity.of(answers(2), true));
	}

	/**
	 * The two opposite meanings of an unanswered slot, which is the whole reason the fallback takes
	 * the taken latch as well as the answers.
	 */
	@Test
	void anUnansweredSlotFallsBackOnWhetherTheProfileWasEverTaken() {
		int[] blank = answers(TerminalProfileQuestionnaire.UNANSWERED);
		assertEquals(TerminalGuidanceVerbosity.TERSE, TerminalGuidanceVerbosity.of(blank, true),
				"a save written before this question existed belongs to somebody already playing");
		assertEquals(TerminalGuidanceVerbosity.VERBOSE, TerminalGuidanceVerbosity.of(blank, false),
				"a player who never reached the question must not be punished for the interruption");
	}

	@Test
	void aShortOrNullProfileIsNeverAnAnswer() {
		assertEquals(TerminalGuidanceVerbosity.TERSE, TerminalGuidanceVerbosity.of(null, true));
		assertEquals(TerminalGuidanceVerbosity.VERBOSE, TerminalGuidanceVerbosity.of(new int[0], false));
	}

	/**
	 * The line this preference may not cross, asserted rather than only documented.
	 *
	 * <p>Verbosity is allowed to change what the terminal says. If it ever gains a consumer that
	 * changes what the world does, this enum is the wrong place for it - and the cheapest guard
	 * available here is that the type stays a two-value presentation switch with no numbers on it.
	 */
	@Test
	void verbosityCarriesNoMagnitudes() {
		assertEquals(2, TerminalGuidanceVerbosity.values().length,
				"a third level would be a difficulty dial wearing a presentation label");
		assertTrue(TerminalGuidanceVerbosity.VERBOSE.explains());
		assertFalse(TerminalGuidanceVerbosity.TERSE.explains());
	}

	private static int[] answers(int priorContact) {
		int[] answers = new int[TerminalProfileQuestionnaire.questionCount()];
		java.util.Arrays.fill(answers, TerminalProfileQuestionnaire.UNANSWERED);
		answers[PRIOR_CONTACT] = priorContact;
		return answers;
	}
}
