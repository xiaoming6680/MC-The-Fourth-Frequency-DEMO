package com.xm.thefourthfrequency.terminal;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The locating rule the terminal narrows a position with.
 *
 * <p>What is pinned here is the shape rather than the constants: a fix must get sharper, it must
 * never get sharper for free, and it must never snap. The stronghold used to have two states - 512
 * blocks and 45 degrees below three throws, 128 and 22.5 at three - which failed all three: the
 * coarse state was unusable, the sharp state arrived as a jump, and neither looked at where the
 * throws were taken from, so three eyes from one doorway bought the tight answer.
 */
class NavigationConvergencePolicyTest {
	private static long[] vantages(int... xs) {
		long[] packed = new long[xs.length];
		for (int index = 0; index < xs.length; index++) packed[index] = new BlockPos(xs[index], 64, 0).asLong();
		return packed;
	}

	@Test
	void aFixNarrowsAndNeverWidensAsEvidenceArrives() {
		var none = NavigationConvergencePolicy.precision(0, 0);
		var one = NavigationConvergencePolicy.precision(1, 0);
		var sameSpot = NavigationConvergencePolicy.precision(3, 20);
		var baseline = NavigationConvergencePolicy.precision(2, 300);
		var good = NavigationConvergencePolicy.precision(4, 900);

		assertTrue(one.uncertaintyBlocks() < none.uncertaintyBlocks());
		assertTrue(sameSpot.uncertaintyBlocks() < one.uncertaintyBlocks());
		assertTrue(baseline.uncertaintyBlocks() < sameSpot.uncertaintyBlocks());
		assertTrue(good.uncertaintyBlocks() < baseline.uncertaintyBlocks());
		assertTrue(good.angleStepDegrees() < baseline.angleStepDegrees());
		assertTrue(baseline.angleStepDegrees() <= sameSpot.angleStepDegrees());
	}

	/**
	 * Standing still and throwing again is one observation recorded twice.
	 *
	 * <p>The whole reason the policy reads a baseline rather than a count. A player who throws four
	 * eyes from the same doorway must not end up better off than one who threw two from opposite
	 * sides of a valley - that is how the instrument would actually behave, and it is the thing the
	 * hint exists to tell them.
	 */
	@Test
	void throwingAgainFromTheSameSpotBuysAlmostNothing() {
		var stacked = NavigationConvergencePolicy.precision(5, 30);
		var spread = NavigationConvergencePolicy.precision(2, 500);
		assertEquals(NavigationConvergencePolicy.Level.COARSE, stacked.level());
		assertTrue(spread.uncertaintyBlocks() < stacked.uncertaintyBlocks(),
				"two throws with a real baseline must beat five from one spot");
		assertTrue(spread.angleStepDegrees() < stacked.angleStepDegrees());
	}

	/** The transition is a slope, not a step: neighbouring evidence gives neighbouring precision. */
	@Test
	void precisionMovesContinuouslyRatherThanSnapping() {
		int previous = Integer.MAX_VALUE;
		for (int spread = NavigationConvergencePolicy.MEANINGFUL_BASELINE_BLOCKS; spread <= 700; spread += 20) {
			int uncertainty = NavigationConvergencePolicy.precision(3, spread).uncertaintyBlocks();
			assertTrue(uncertainty <= previous, "a wider baseline must never widen the band");
			if (previous != Integer.MAX_VALUE) {
				assertTrue(previous - uncertainty < 200,
						"no single step of evidence may collapse the band: " + previous + " -> " + uncertainty);
			}
			previous = uncertainty;
		}
	}

	@Test
	void theBaselineIsTheWidestGapBetweenVantagePoints() {
		assertEquals(0, NavigationConvergencePolicy.spreadBlocks(null));
		assertEquals(0, NavigationConvergencePolicy.spreadBlocks(vantages(10)));
		assertEquals(400, NavigationConvergencePolicy.spreadBlocks(vantages(0, 400)));
		assertEquals(400, NavigationConvergencePolicy.spreadBlocks(vantages(200, 0, 400)),
				"the widest pair decides it, not the newest or the mean");
	}

	/** Every level below the sharpest must have something to ask the player for. */
	@Test
	void everyUnfinishedLevelAsksForSomething() {
		for (NavigationConvergencePolicy.Level level : NavigationConvergencePolicy.Level.values()) {
			if (level == NavigationConvergencePolicy.Level.RESOLVED) {
				assertNull(NavigationConvergencePolicy.hintId(level),
						"a resolved fix must stop nagging");
			} else {
				assertNotNull(NavigationConvergencePolicy.hintId(level), level + " has no hint");
			}
		}
	}
}
