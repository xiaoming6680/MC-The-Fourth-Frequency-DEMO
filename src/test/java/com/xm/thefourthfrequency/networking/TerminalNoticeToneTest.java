package com.xm.thefourthfrequency.networking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which notices are allowed to interrupt the World Interface encounter.
 *
 * <p>The tones already existed to give the fight its own voice; this is the other thing they are
 * good for. A line about a file turning up somewhere else, or a task reward, is noise laid across
 * the one stretch of the run where the world has stopped being ambiguous.</p>
 */
final class TerminalNoticeToneTest {
	@Test
	void theEncounterOnlyLetsItsOwnVoiceThrough() {
		assertTrue(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_ENCOUNTER));
		assertTrue(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_ANCHOR));
		assertTrue(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_DRAGON));

		assertFalse(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_NONE));
		assertFalse(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_UNREAD));
		assertFalse(TerminalNoticePayload.surfacesDuringEncounter(
				TerminalNoticePayload.TONE_TASK_COMPLETE));
		// Pursuits cannot start during the finale anyway, so this one is belt and braces - but a
		// pursuit warning arriving mid-fight would be the loudest possible wrong line.
		assertFalse(TerminalNoticePayload.surfacesDuringEncounter(
				TerminalNoticePayload.TONE_PURSUIT_WARNING));
	}

	/**
	 * A refusal is the answer to something the player just did, so it is never held back.
	 *
	 * <p>Holding it would turn "that item is not accepted" into an action that silently did nothing,
	 * which is the one failure mode the terminal is not allowed to have.</p>
	 */
	@Test
	void aRefusalStillAnswersDuringTheFight() {
		assertTrue(TerminalNoticePayload.surfacesDuringEncounter(TerminalNoticePayload.TONE_DENIED));
	}
}
