package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FragmentInvestigationPolicyTest {
	@Test
	void theOfferNeedsABandStageAndSomethingToInvestigate() {
		assertFalse(FragmentInvestigationPolicy.offered(0, false, true),
				"a terminal that has never resolved a band has nothing to offer");
		assertFalse(FragmentInvestigationPolicy.offered(1, false, false),
				"there has to be an unresolved lead");
		assertTrue(FragmentInvestigationPolicy.offered(1, false, true));
	}

	/**
	 * A route already in progress stands on its own.
	 *
	 * <p>Otherwise resolving the last <em>other</em> lead would retract the navigation the player is
	 * currently walking, which is the same class of fault as the hint tier: the offer disappearing
	 * for a reason that has nothing to do with the thing being offered.
	 */
	@Test
	void anAlreadySelectedFragmentKeepsTheOfferStanding() {
		assertTrue(FragmentInvestigationPolicy.offered(1, true, false));
		assertTrue(FragmentInvestigationPolicy.offered(3, true, true));
		assertFalse(FragmentInvestigationPolicy.offered(0, true, true),
				"band stage is still the floor - nothing is offered before the receiver works");
	}

	/**
	 * The records page files a lead as soon as one exists, and nothing else may gate the offer.
	 *
	 * <p>The gate used to also require the Nether milestone. A lead is filed on the first day of a
	 * normal run, so the log carried "optional investigation at X - [open navigation]" while both the
	 * shortcut and the navigator's option were refused. A destination the terminal names but will not
	 * route to is exactly what the records page must never draw, so the only inputs left here are the
	 * two the log itself already depends on.
	 */
	@Test
	void aFiledLeadIsOfferedWithoutAnyFurtherMainlineProgress() {
		assertTrue(FragmentInvestigationPolicy.offered(1, false, true),
				"a lead the records page has already announced must be navigable");
	}

	/**
	 * The regression this policy exists for.
	 *
	 * <p>Every input is monotone, so once the answer is yes it may only become no by the lead being
	 * resolved. The old gate consulted {@code guidanceHintTier}, which any objective progress resets,
	 * so the option blinked out whenever the player did something - while the records page went on
	 * announcing it. Making progress must never take the offer away.
	 */
	@Test
	void progressCanNeverWithdrawAnOfferThatWasAlreadyMade() {
		for (int bandStage = 1; bandStage <= 3; bandStage++) {
			for (boolean selected : new boolean[]{false, true}) {
				if (!FragmentInvestigationPolicy.offered(bandStage, selected, true)) continue;
				// Everything that can advance while the lead is still open.
				assertTrue(FragmentInvestigationPolicy.offered(bandStage + 1, selected, true),
						"a higher band stage withdrew the offer");
				assertTrue(FragmentInvestigationPolicy.offered(bandStage, true, true),
						"selecting the fragment withdrew the offer");
			}
		}
	}
}
