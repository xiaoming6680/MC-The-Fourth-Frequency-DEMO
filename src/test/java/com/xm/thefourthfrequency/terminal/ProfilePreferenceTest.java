package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProfilePreferenceTest {
	private static int[] answers(int... values) {
		int[] full = new int[TerminalProfileQuestionnaire.questionCount()];
		java.util.Arrays.fill(full, TerminalProfileQuestionnaire.UNANSWERED);
		System.arraycopy(values, 0, full, 0, Math.min(values.length, full.length));
		return full;
	}

	/**
	 * The one that would fail silently.
	 *
	 * <p>Flavour ids are matched against the catalogue by string. A typo does not throw, does not log
	 * and does not show up in play - the weight simply never applies, and the feature quietly is not
	 * there. Nothing else in this file can catch that.
	 */
	@Test
	void everyFlavouredIdIsARealAnomaly() {
		for (ProfilePreference.Solitude ignored : ProfilePreference.Solitude.values()) {
			// Drive through real answers rather than the enum, so the mapping is exercised too.
		}
		List<int[]> profiles = List.of(
				answers(1, 0, 0, 1, 0),
				answers(1, 0, 1, 2, 0),
				answers(1, 0, 2, 0, 0),
				answers(TerminalProfileQuestionnaire.UNANSWERED));
		for (int[] profile : profiles) {
			for (String id : ProfilePreference.flavouredAnomalies(profile)) {
				assertTrue(AnomalyCatalog.contains(id), "not a catalogue anomaly: " + id);
			}
		}
	}

	/**
	 * A gap is not an answer.
	 *
	 * <p>The damage failsafe and a dropped connection both leave real gaps, and the profile is
	 * one-shot so they are never filled in later. Every consumer has to have a neutral reading, or a
	 * player who was interrupted would be acted on according to a preference they never gave.
	 */
	@Test
	void anUnansweredProfileLeavesEveryConsumerNeutral() {
		int[] blank = answers();
		assertEquals(ProfilePreference.PlayStyle.UNSTATED, ProfilePreference.playStyle(blank));
		assertEquals(ProfilePreference.Animal.UNSTATED, ProfilePreference.animal(blank));
		assertEquals(ProfilePreference.Solitude.UNSTATED, ProfilePreference.solitude(blank));
		assertEquals(ProfilePreference.Trust.UNSTATED, ProfilePreference.trust(blank));
		assertEquals(List.of(), ProfilePreference.flavouredAnomalies(blank));
		assertEquals(ProfilePreference.PlayStyle.UNSTATED, ProfilePreference.playStyle(null));
		assertEquals(ProfilePreference.Trust.UNSTATED, ProfilePreference.trust(new int[0]));
	}

	/**
	 * Silence is not consent, and the relay is the one preference with a second person in it.
	 *
	 * <p>Outbound defaults to off for an unstated answer: the lines a terminal passes on are about
	 * its holder, and nobody who was never asked has agreed to that. Inbound defaults to on, because
	 * receiving costs the player nothing and an explicit "no" is still honoured.
	 */
	@Test
	void relayConsentFailsClosedOutboundAndOpenInbound() {
		assertTrue(ProfilePreference.relaysOut(answers(1, 0, 0, 1, 0)));
		assertTrue(ProfilePreference.relaysIn(answers(1, 0, 0, 1, 0)));
		assertFalse(ProfilePreference.relaysOut(answers(1, 0, 0, 1, 1)));
		assertFalse(ProfilePreference.relaysIn(answers(1, 0, 0, 1, 1)));
		assertFalse(ProfilePreference.relaysOut(answers()), "never asked is not a yes");
		assertTrue(ProfilePreference.relaysIn(answers()));
	}

	/** Out-of-range values in a hand-edited save read as unanswered, not as whatever they index. */
	@Test
	void illegalStoredValuesDegradeToUnanswered() {
		int[] rubbish = answers(99, -5, 3, 7, 42);
		assertEquals(ProfilePreference.PlayStyle.UNSTATED, ProfilePreference.playStyle(rubbish));
		assertEquals(ProfilePreference.Animal.UNSTATED, ProfilePreference.animal(rubbish));
		assertEquals(ProfilePreference.Solitude.UNSTATED, ProfilePreference.solitude(rubbish));
		assertEquals(ProfilePreference.Trust.UNSTATED, ProfilePreference.trust(rubbish));
	}

	@Test
	void eachStatedAnswerReadsBackAsItself() {
		assertEquals(ProfilePreference.PlayStyle.SPEEDRUN, ProfilePreference.playStyle(answers(0)));
		assertEquals(ProfilePreference.PlayStyle.NORMAL, ProfilePreference.playStyle(answers(1)));
		assertEquals(ProfilePreference.PlayStyle.CASUAL, ProfilePreference.playStyle(answers(2)));
		assertEquals(ProfilePreference.Animal.CAT, ProfilePreference.animal(answers(1, 0)));
		assertEquals(ProfilePreference.Animal.DOG, ProfilePreference.animal(answers(1, 1)));
		assertEquals(ProfilePreference.Animal.NEITHER, ProfilePreference.animal(answers(1, 2)));
	}

	/**
	 * The two solitude questions are one axis, and the extremes have to land on the extremes.
	 *
	 * <p>"Frightened alone" plus "often uneasy underground" is the same claim twice and must read as
	 * troubled; "not frightened" plus "never uneasy" must read as untroubled. What sits between them
	 * only has to be between them.
	 */
	@Test
	void solitudeCombinesBothSelfReports() {
		assertEquals(ProfilePreference.Solitude.TROUBLED,
				ProfilePreference.solitude(answers(1, 0, 0, 1)));
		assertEquals(ProfilePreference.Solitude.UNTROUBLED,
				ProfilePreference.solitude(answers(1, 0, 1, 2)));
		assertEquals(ProfilePreference.Solitude.SOMETIMES,
				ProfilePreference.solitude(answers(1, 0, 2, 2)));
		// One half answered is still an answer - but only half of one. A single self-report cannot
		// reach the far end of the axis on its own; the extremes require the two to corroborate each
		// other, which is what keeps one hurried click during the first minute from deciding the
		// flavour of an entire playthrough.
		assertEquals(ProfilePreference.Solitude.SOMETIMES,
				ProfilePreference.solitude(answers(1, 0, 0, TerminalProfileQuestionnaire.UNANSWERED)));
		assertEquals(ProfilePreference.Solitude.SOMETIMES,
				ProfilePreference.solitude(answers(1, 0, TerminalProfileQuestionnaire.UNANSWERED, 1)));
	}

	/**
	 * The profile may pick which anomaly arrives. It may never pick how many.
	 *
	 * <p>Asserted where it is enforced: the flavour list only ever adds weight inside a pool the stage
	 * already assembled, so every id it names must already be reachable at some stage rather than
	 * being smuggled in by the preference.
	 */
	@Test
	void flavourOnlyReachesAnomaliesTheStagesAlreadyOffer() {
		int[] troubled = answers(1, 0, 0, 1, 0);
		for (String id : ProfilePreference.flavouredAnomalies(troubled)) {
			AnomalyDefinition definition = AnomalyCatalog.require(id);
			assertTrue(AnomalyCatalog.pool(definition.tier()).contains(definition),
					id + " is not reachable from its own stage");
		}
	}
}
