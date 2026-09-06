package com.xm.thefourthfrequency.unrendered;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal's one readout down here, and the rule it exists to keep.
 *
 * <p>Coarse is allowed; wrong is not. A bearing that is occasionally, quietly incorrect would be a
 * better scare and would break the boundary the whole mod rests on - actionable information may fail
 * loudly, it may never become a believable wrong value. So the only thing to prove about this is
 * that its answers are right, and that its vagueness comes entirely from resolution.
 */
final class UnrenderedBearingPolicyTest {
	/** Facing due north in Minecraft's yaw frame, where zero faces south. */
	private static final float FACING_NORTH = 180.0F;
	private static final float FACING_SOUTH = 0.0F;
	private static final float FACING_EAST = -90.0F;
	private static final float FACING_WEST = 90.0F;

	@Test
	void theAbsoluteAngleUsesMinecraftAxes() {
		// -Z is north and +X is east. Getting this backwards is the one error that would be
		// self-consistent, survive every other test, and send every player exactly the wrong way.
		assertEquals(0, UnrenderedBearingPolicy.absoluteDegrees(0, -100), "north is -Z");
		assertEquals(90, UnrenderedBearingPolicy.absoluteDegrees(100, 0), "east is +X");
		assertEquals(180, UnrenderedBearingPolicy.absoluteDegrees(0, 100), "south is +Z");
		assertEquals(270, UnrenderedBearingPolicy.absoluteDegrees(-100, 0), "west is -X");
	}

	@Test
	void aTargetStraightAheadReadsAsAheadFromEveryFacing() {
		// The property that makes the readout usable at all: it is about the player, not the compass.
		// Down here there is no sun, no landmark and no map, so a cardinal bearing would be a fact
		// the player has to convert before they can act on it.
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(0, FACING_NORTH));
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(90, FACING_EAST));
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(180, FACING_SOUTH));
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(270, FACING_WEST));
		assertEquals("ahead", UnrenderedBearingPolicy.RELATIVE_KEYS[0]);
	}

	@Test
	void theSectorsRunClockwiseFromAhead() {
		// Facing north throughout, so the absolute angle is the relative one and the ordering of the
		// key list is what is being checked.
		assertEquals(1, UnrenderedBearingPolicy.relativeSector(45, FACING_NORTH), "ahead and right");
		assertEquals(2, UnrenderedBearingPolicy.relativeSector(90, FACING_NORTH), "right");
		assertEquals(3, UnrenderedBearingPolicy.relativeSector(135, FACING_NORTH), "behind and right");
		assertEquals(4, UnrenderedBearingPolicy.relativeSector(180, FACING_NORTH), "behind");
		assertEquals(5, UnrenderedBearingPolicy.relativeSector(225, FACING_NORTH), "behind and left");
		assertEquals(6, UnrenderedBearingPolicy.relativeSector(270, FACING_NORTH), "left");
		assertEquals(7, UnrenderedBearingPolicy.relativeSector(315, FACING_NORTH), "ahead and left");
		assertEquals("right", UnrenderedBearingPolicy.RELATIVE_KEYS[2]);
		assertEquals("behind", UnrenderedBearingPolicy.RELATIVE_KEYS[4]);
		assertEquals("left", UnrenderedBearingPolicy.RELATIVE_KEYS[6]);
	}

	@Test
	void everySectorIsTheWedgeCentredOnItsName() {
		// Each name has to cover 22.5 degrees either side of its own direction, not the 45 starting
		// at it. The off-by-half-a-sector version passes every straight-ahead test above and is wrong
		// across most of the circle.
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(20, FACING_NORTH), "just right of ahead");
		assertEquals(0, UnrenderedBearingPolicy.relativeSector(340, FACING_NORTH), "just left of ahead");
		assertEquals(2, UnrenderedBearingPolicy.relativeSector(70, FACING_NORTH), "just ahead of right");
		assertEquals(2, UnrenderedBearingPolicy.relativeSector(110, FACING_NORTH), "just behind right");
	}

	@Test
	void turningOnTheSpotWalksTheSectorAllTheWayRound() {
		// A player spinning in place with a fixed target must see every sector exactly once, in
		// order. This is the whole difference from the cardinal version, and the thing a stale cached
		// sector would break silently.
		int seen = 0;
		int previous = -1;
		for (int yaw = -180; yaw < 180; yaw++) {
			int sector = UnrenderedBearingPolicy.relativeSector(0, yaw);
			assertTrue(sector >= 0 && sector < UnrenderedBearingPolicy.RELATIVE_KEYS.length,
					"sector out of range at yaw " + yaw);
			if (sector != previous) {
				seen++;
				previous = sector;
			}
		}
		// Eight wedges over a full turn, with one of them straddling the wrap and so counted twice.
		assertEquals(UnrenderedBearingPolicy.RELATIVE_KEYS.length + 1, seen,
				"a full turn must pass through every sector in order");
	}

	@Test
	void standingOnTopOfItProducesNoBearingAtAll() {
		// Pointing at the player's own feet reads as the instrument having broken, so it says nothing
		// instead. The caller treats the negative as "stay quiet", not as a direction.
		assertTrue(UnrenderedBearingPolicy.absoluteDegrees(0, 0) < 0);
		assertTrue(UnrenderedBearingPolicy.absoluteDegrees(10, 10) < 0, "inside the minimum range");
		assertTrue(UnrenderedBearingPolicy.absoluteDegrees(0, UnrenderedBearingPolicy.MINIMUM_RANGE) >= 0,
				"exactly at the minimum range is already a bearing");
		assertEquals(-1, UnrenderedBearingPolicy.relativeSector(-1, FACING_NORTH),
				"no angle means no sector, whatever the player is facing");
	}
}
