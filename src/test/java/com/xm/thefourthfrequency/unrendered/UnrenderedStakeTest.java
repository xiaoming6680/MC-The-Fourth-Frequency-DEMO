package com.xm.thefourthfrequency.unrendered;

import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import com.xm.thefourthfrequency.world.MaximumHealthAdjustment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the layer costs and what it pays, and the rank it was moved to.
 *
 * <p>Both are stated by borrowing the pursuit's own numbers rather than by restating them, and that
 * borrowing is the thing worth protecting: two "about the same" definitions of what losing costs
 * would be invisible in review and would be felt by every player who is already losing.
 */
final class UnrenderedStakeTest {
	@Test
	void gettingOutIsWorthAHeartAndNotGettingOutCostsOne() {
		// The same rule the pursuit resolves on, called with the same arguments. Asserted here so a
		// future edit that gives the layer its own numbers has to delete this test to do it.
		assertEquals(PursuitProgressPolicy.HEART_HEALTH_POINTS,
				PursuitProgressPolicy.resolutionMaxHealthDelta(false, 20.0D),
				"escaping restores exactly one heart");
		assertEquals(-PursuitProgressPolicy.HEART_HEALTH_POINTS,
				PursuitProgressPolicy.resolutionMaxHealthDelta(true, 20.0D),
				"failing costs exactly one heart");
	}

	@Test
	void failingStopsCostingAnythingAtTheFloor() {
		// A player who is already down to six hearts is the one the mod least wants to grind, and
		// they are exactly the player most likely to keep failing. Below the floor a loss is free.
		assertEquals(0.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(
				true, PursuitProgressPolicy.CAPTURE_PENALTY_FLOOR_HEALTH),
				"at the floor a failure costs nothing further");
		assertEquals(0.0D, PursuitProgressPolicy.resolutionMaxHealthDelta(true, 4.0D),
				"below the floor a failure still costs nothing");
		// Escaping keeps paying at any health, which is what makes the floor a floor rather than a
		// trap: somebody who bottomed out can climb back.
		assertEquals(PursuitProgressPolicy.HEART_HEALTH_POINTS,
				PursuitProgressPolicy.resolutionMaxHealthDelta(false, 2.0D),
				"escaping restores even from the very bottom");
	}

	@Test
	void theAdjustmentIsClampedAtBothEndsInOnePlace() {
		// The clamp is the half that is easy to get subtly different in a second copy. One heart is
		// the floor because zero is a death nothing chose; forty is the ceiling because a repeatedly
		// rewarded run should still be able to die.
		assertEquals(2.0D, MaximumHealthAdjustment.MIN_PLAYER_MAX_HEALTH);
		assertEquals(40.0D, MaximumHealthAdjustment.MAX_PLAYER_MAX_HEALTH);
		assertTrue(MaximumHealthAdjustment.MIN_PLAYER_MAX_HEALTH
						< PursuitProgressPolicy.CAPTURE_PENALTY_FLOOR_HEALTH,
				"the capture floor has to sit above the hard clamp or it never takes effect");
	}

	@Test
	void theLayerIsSpacedLikeAPursuitRatherThanLikeAnAnomaly() {
		// The whole of what "ranked with the pursuit" means. An ordinary stage-five anomaly may fire
		// every few minutes, which is right for four seconds of a window flickering and absurd for
		// six minutes of being somewhere else.
		for (long seed = -1_000L; seed <= 1_000L; seed += 37L) {
			long gap = UnrenderedAnomaly.gapTicks(seed);
			assertTrue(gap >= PursuitProgressPolicy.MIN_CHASE_GAP_TICKS
							&& gap <= PursuitProgressPolicy.MAX_CHASE_GAP_TICKS,
					"gap outside the pursuit band for seed " + seed + ": " + gap);
		}
		assertEquals(20L * 60L * 20L, PursuitProgressPolicy.MIN_CHASE_GAP_TICKS, "twenty minutes");
		assertEquals(30L * 60L * 20L, PursuitProgressPolicy.MAX_CHASE_GAP_TICKS, "thirty minutes");
		// Long enough that it cannot be the session's timeout in disguise.
		assertTrue(PursuitProgressPolicy.MIN_CHASE_GAP_TICKS > UnrenderedAnomaly.DURATION_TICKS * 3L,
				"the gap must be much longer than one session, or it is not a rank at all");
	}
}
