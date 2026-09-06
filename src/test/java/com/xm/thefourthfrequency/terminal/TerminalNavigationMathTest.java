package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalNavigationMathTest {
	@Test
	void computesNorthAndTargetNeedlesInPlayerSpace() {
		// The dial's top is north, so both needles read as compass bearings. Minecraft yaw is 0 at
		// south and grows towards west, which is why every expectation here is the yaw plus half a
		// circle rather than minus it - the old formula mirrored east and west onto each other.
		assertEquals(0.0D, TerminalNavigationMath.facingNeedleDegrees(180.0F), 0.0001D, "facing north");
		assertEquals(-180.0D, TerminalNavigationMath.facingNeedleDegrees(0.0F), 0.0001D, "facing south");
		assertEquals(90.0D, TerminalNavigationMath.facingNeedleDegrees(-90.0F), 0.0001D, "facing east");
		assertEquals(-90.0D, TerminalNavigationMath.facingNeedleDegrees(90.0F), 0.0001D, "facing west");
		// A target due north of the player, from a player who is facing anywhere at all.
		assertEquals(0.0D, TerminalNavigationMath.targetNeedleDegrees(0, -20), 0.0001D, "target north");
		assertEquals(-180.0D, TerminalNavigationMath.targetNeedleDegrees(0, 20), 0.0001D, "target south");
		assertEquals(90.0D, TerminalNavigationMath.targetNeedleDegrees(20, 0), 0.0001D, "target east");
		assertEquals(-90.0D, TerminalNavigationMath.targetNeedleDegrees(-20, 0), 0.0001D, "target west");
	}

	@Test
	void interpolationTakesTheShortPathAcrossTheWrapBoundary() {
		assertEquals(-180.0D, TerminalNavigationMath.interpolateDegrees(170.0D, -170.0D, 0.5D), 0.0001D);
		assertEquals(170.0D, TerminalNavigationMath.interpolateDegrees(170.0D, -170.0D, 0.0D), 0.0001D);
		assertEquals(-170.0D, TerminalNavigationMath.interpolateDegrees(170.0D, -170.0D, 1.0D), 0.0001D);
	}

	@Test
	void navigationStopsForEveryInvalidTargetCondition() {
		assertTrue(TerminalNavigationMath.navigable(1, false, true, true));
		assertFalse(TerminalNavigationMath.navigable(0, false, true, true));
		assertFalse(TerminalNavigationMath.navigable(1, true, true, true));
		assertFalse(TerminalNavigationMath.navigable(4, true, true, true));
		assertTrue(TerminalNavigationMath.navigable(7, false, true, true));
		assertFalse(TerminalNavigationMath.navigable(1, false, false, true));
		assertFalse(TerminalNavigationMath.navigable(1, false, true, false));
	}

	@Test
	void producesEightDirectionsAndPlanarDistance() {
		assertEquals("north", TerminalNavigationMath.direction(0, -8));
		assertEquals("northeast", TerminalNavigationMath.direction(8, -8));
		assertEquals("east", TerminalNavigationMath.direction(8, 0));
		assertEquals("southeast", TerminalNavigationMath.direction(8, 8));
		assertEquals("south", TerminalNavigationMath.direction(0, 8));
		assertEquals("southwest", TerminalNavigationMath.direction(-8, 8));
		assertEquals("west", TerminalNavigationMath.direction(-8, 0));
		assertEquals("northwest", TerminalNavigationMath.direction(-8, -8));
		assertEquals(5, TerminalNavigationMath.distance(3, 4));
	}

	@Test
	void completionDirectionUsesThePlayersFourRelativeSides() {
		assertEquals("ahead", TerminalNavigationMath.relativeDirectionId(
				TerminalNavigationMath.relativeDirection(0, 10, 0.0F)));
		assertEquals("left", TerminalNavigationMath.relativeDirectionId(
				TerminalNavigationMath.relativeDirection(10, 0, 0.0F)));
		assertEquals("right", TerminalNavigationMath.relativeDirectionId(
				TerminalNavigationMath.relativeDirection(-10, 0, 0.0F)));
		assertEquals("behind", TerminalNavigationMath.relativeDirectionId(
				TerminalNavigationMath.relativeDirection(0, -10, 0.0F)));
	}

	@Test
	void structureArrivalUsesAFiftyBlockHorizontalRadius() {
		assertTrue(TerminalNavigationMath.withinHorizontalRadius(10, -10, 40, 30, 50));
		assertTrue(TerminalNavigationMath.withinHorizontalRadius(10, -10, 60, -10, 50));
		assertFalse(TerminalNavigationMath.withinHorizontalRadius(10, -10, 61, -10, 50));
		assertFalse(TerminalNavigationMath.withinHorizontalRadius(10, -10, 46, 26, 50));
	}

	/**
	 * The two needles have to live in the same frame, or the dial means nothing.
	 *
	 * <p>Walking north towards a target that is north must line the needles up, whichever way the
	 * player happens to be looking while they do it. That is the whole interaction the compass
	 * offers, and it is exactly what a relative needle over an absolute rose could not deliver.
	 */
	@org.junit.jupiter.api.Test
	void bothNeedlesAgreeWhenTheTargetIsStraightAhead() {
		for (float yaw : new float[]{-180.0F, -90.0F, 0.0F, 90.0F, 179.0F}) {
			double facing = TerminalNavigationMath.facingNeedleDegrees(yaw);
			// The offset a player at this yaw would have to walk to move straight forwards.
			int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)) * 100.0D);
			int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)) * 100.0D);
			double target = TerminalNavigationMath.targetNeedleDegrees(dx, dz);
			double gap = Math.abs(TerminalNavigationMath.wrapDegrees(target - facing));
			org.junit.jupiter.api.Assertions.assertTrue(gap < 1.0D,
					"needles disagreed by " + gap + " degrees at yaw " + yaw);
		}
	}
}
