package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.world.SurvivalMilestone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PursuitProgressPolicyTest {
	@Test
	void warningLeadsFormalPursuitByTenSeconds() {
		assertEquals(10L * 20L, PursuitProgressPolicy.WARNING_LEAD_TICKS);
	}

	@Test
	void formOneUsesMultipleActivityRoutesAndNeverDependsOnIron() {
		assertFalse(PursuitProgressPolicy.earlyFormEligible(false, 1,
				PursuitActivityProof.MINING.mask(), 0L));
		assertFalse(PursuitProgressPolicy.earlyFormEligible(true, 0,
				PursuitActivityProof.MINING.mask(), 0L));
		assertTrue(PursuitProgressPolicy.earlyFormEligible(true, 1,
				PursuitActivityProof.EXPLORATION.mask(), 0L));
		assertTrue(PursuitProgressPolicy.earlyFormEligible(true, 1, 0,
				PursuitProgressPolicy.FORM_ONE_ACTIVITY_FALLBACK_TICKS));
	}

	@Test
	void derivesFiveStoryPermissionsWithoutSkippingTheActualForm() {
		int enteredNether = SurvivalMilestone.ENTERED_NETHER.mask();
		int returnedWithRods = enteredNether
				| SurvivalMilestone.RETURNED_NETHER.mask()
				| SurvivalMilestone.COLLECTED_BLAZE_RODS.mask();
		assertEquals(1, PursuitProgressPolicy.allowedForm(0, 0, true));
		assertEquals(2, PursuitProgressPolicy.allowedForm(enteredNether, 0, true));
		assertEquals(3, PursuitProgressPolicy.allowedForm(returnedWithRods, 0, true));
		assertEquals(4, PursuitProgressPolicy.allowedForm(returnedWithRods, 3, true));
		assertEquals(5, PursuitProgressPolicy.allowedForm(
				returnedWithRods | SurvivalMilestone.FOUND_STRONGHOLD.mask(), 0, false));

		assertEquals(1, PursuitProgressPolicy.actualForm(0));
		assertEquals(2, PursuitProgressPolicy.actualForm(1));
		assertEquals(5, PursuitProgressPolicy.actualForm(4));
		assertEquals(5, PursuitProgressPolicy.actualForm(5));
	}

	@Test
	void aLargeStoryJumpCreatesOnePendingPursuitAndNoCatchUpDebt() {
		assertTrue(PursuitProgressPolicy.pendingAfterAllowedFormUpdate(false, 0, 4, 0));
		assertFalse(PursuitProgressPolicy.pendingAfterAllowedFormUpdate(false, 4, 4, 1));
		assertTrue(PursuitProgressPolicy.pendingAfterAllowedFormUpdate(true, 4, 5, 0));
		assertEquals(1, PursuitProgressPolicy.resolvedAfterSuccess(0));
		assertEquals(5, PursuitProgressPolicy.resolvedAfterSuccess(5));
		assertTrue(PursuitProgressPolicy.pendingAfterSuccess(1, 4));
		assertFalse(PursuitProgressPolicy.pendingAfterSuccess(1, 1));
		assertFalse(PursuitProgressPolicy.pendingAfterSuccess(5, 5));
	}

	@Test
	void formalStartRequiresPendingPermissionAndCooldown() {
		assertFalse(PursuitProgressPolicy.canStart(false, 1, 0, 100L, 0L));
		assertFalse(PursuitProgressPolicy.canStart(true, 0, 0, 100L, 0L));
		assertFalse(PursuitProgressPolicy.canStart(true, 1, 0, 99L, 100L));
		assertTrue(PursuitProgressPolicy.canStart(true, 1, 0, 100L, 100L));
		assertFalse(PursuitProgressPolicy.canStart(true, 5, 5, 100L, 0L));
	}

	/**
	 * The mercy that was deliberately removed: a form no longer has to be previewed to arrive.
	 *
	 * <p>Written as its own case rather than folded into the one above because the old signature
	 * would have made this exact call return false, and that difference is the product change. An
	 * un-demonstrated form with permission, a pending slot and an expired cooldown starts.
	 */
	@Test
	void anUndemonstratedFormMayArriveAsTheRealPursuit() {
		assertTrue(PursuitProgressPolicy.canStart(true, 1, 0, 100L, 100L),
				"the first encounter with a form is allowed to be the real thing");
		// The cooldown is still a floor: dropping the preview must not let two pursuits stack.
		assertFalse(PursuitProgressPolicy.canStart(true, 1, 0, 99L, 100L),
				"removing the preview gate must not remove the pacing floor");
	}

	/**
	 * The reliability the removed final-Eye gate used to stand in for.
	 *
	 * <p>Dropping that gate is only defensible if the first pursuit is something the early game
	 * hands out on its own. It is: being bound, one completed anomaly and any one of five activity
	 * proofs is the entire permission chain. Pinned here so a later tightening of any leg has to admit
	 * that it is also making the finale reachable later.</p>
	 */
	@Test
	void theFirstPursuitOpensOnBoundPlusOneAnomalyPlusAnyActivity() {
		for (PursuitActivityProof proof : PursuitActivityProof.values()) {
			assertTrue(PursuitProgressPolicy.earlyFormEligible(true, 1, proof.mask(), 0L),
					"Activity route " + proof + " must open form one on its own");
		}
		assertEquals(1, PursuitProgressPolicy.allowedForm(0, 0, true));
		assertEquals(1, PursuitProgressPolicy.actualForm(0));
		assertTrue(PursuitProgressPolicy.pendingAfterAllowedFormUpdate(false, 0, 1, 0));
		assertTrue(PursuitProgressPolicy.canStart(true, 1, 0, 0L, 0L));
	}

	@Test
	void encounteredChasesCountCapturesAsWellAsEscapes() {
		// A player caught on their first chase gains no resolved chase, so form progression stays
		// put - but the encounter still counts, which is what the record is for.
		assertEquals(1, PursuitProgressPolicy.encounteredAfterResolution(0));
		assertEquals(1, PursuitProgressPolicy.actualForm(0));

		assertEquals(1, PursuitProgressPolicy.encounteredAfterResolution(-3));
		assertEquals(PursuitProgressPolicy.MAX_TRACKED_ENCOUNTERS,
				PursuitProgressPolicy.encounteredAfterResolution(
						PursuitProgressPolicy.MAX_TRACKED_ENCOUNTERS));
	}

	@Test
	void terminalStagesUsePersonalPursuitAndLateStoryState() {
		assertEquals(0, PursuitProgressPolicy.terminalVisualStage(0, 5, 5));
		assertEquals(1, PursuitProgressPolicy.terminalVisualStage(1, 5, 5));
		assertEquals(1, PursuitProgressPolicy.terminalVisualStage(3, 3, 5));
		assertEquals(1, PursuitProgressPolicy.terminalVisualStage(3, 4, 3));
		assertEquals(2, PursuitProgressPolicy.terminalVisualStage(3, 4, 4));
	}
	@Test
	void captureMaxHealthPenaltyStopsAtTheSixHeartFloor() {
		assertEquals(-2.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(true, 20.0D));
		assertEquals(-2.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(true, 14.0D));
		assertEquals(0.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(true,
				PursuitProgressPolicy.CAPTURE_PENALTY_FLOOR_HEALTH));
		assertEquals(0.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(true, 4.0D));
		assertEquals(2.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(false, 4.0D));
		assertEquals(2.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(false, 40.0D));
	}
}
