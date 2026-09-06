package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class LuminanceFaultPolicyTest {
	@Test
	void everySectionInTheBoxGetsExactlyOneIndex() {
		Set<Integer> seen = new HashSet<>();
		int radiusH = LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS;
		int radiusV = LuminanceFaultPolicy.VERTICAL_SECTION_RADIUS;
		for (int x = -radiusH; x <= radiusH; x++) {
			for (int y = -radiusV; y <= radiusV; y++) {
				for (int z = -radiusH; z <= radiusH; z++) {
					int index = LuminanceFaultPolicy.index(0, 0, 0, x, y, z);
					assertTrue(index >= 0 && index < LuminanceFaultPolicy.SECTION_COUNT,
							"out of array bounds at " + x + "," + y + "," + z);
					assertTrue(seen.add(index), "collision at " + x + "," + y + "," + z);
				}
			}
		}
		assertEquals(LuminanceFaultPolicy.SECTION_COUNT, seen.size());
	}

	/**
	 * The array is indexed by the return value, so an out-of-box section reporting anything but -1
	 * would either darken a section the anomaly never claimed or read past the end of the flags.
	 */
	@Test
	void sectionsOutsideTheBoxAreRejectedOnEveryAxis() {
		int radiusH = LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS;
		int radiusV = LuminanceFaultPolicy.VERTICAL_SECTION_RADIUS;
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, radiusH + 1, 0, 0));
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, -radiusH - 1, 0, 0));
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, 0, radiusV + 1, 0));
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, 0, -radiusV - 1, 0));
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, 0, 0, radiusH + 1));
		assertEquals(-1, LuminanceFaultPolicy.index(0, 0, 0, 0, 0, -radiusH - 1));
	}

	/** Section coordinates go negative and cross zero; the box has to travel with its origin. */
	@Test
	void theBoxIsRelativeToItsOriginAtNegativeCoordinates() {
		assertEquals(LuminanceFaultPolicy.index(0, 0, 0, 0, 0, 0),
				LuminanceFaultPolicy.index(-40, -3, 17, -40, -3, 17));
		assertEquals(-1, LuminanceFaultPolicy.index(-40, -3, 17, 0, 0, 0));
		assertTrue(LuminanceFaultPolicy.index(-40, -3, 17,
				-40 + LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS, -3, 17) >= 0);
	}

	/**
	 * The cost of the anomaly is two rebuilds of this many sections. Asserted as a ceiling because
	 * the number is a performance budget, not a preference - see the class javadoc.
	 */
	@Test
	void theDarkenedVolumeStaysWithinItsRebuildBudget() {
		assertEquals(147, LuminanceFaultPolicy.SECTION_COUNT);
		assertTrue(LuminanceFaultPolicy.VERTICAL_SECTION_RADIUS
				< LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS, "the box must stay wider than it is tall");
	}
}
