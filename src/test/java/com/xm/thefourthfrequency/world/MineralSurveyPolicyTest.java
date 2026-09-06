package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.terminal.TerminalResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MineralSurveyPolicyTest {
	@Test
	void surveyRangeUsesARealFiveBlockSphere() {
		assertTrue(MineralSurveyPolicy.withinRange(5, 0, 0));
		assertTrue(MineralSurveyPolicy.withinRange(3, 4, 0));
		assertFalse(MineralSurveyPolicy.withinRange(5, 1, 0));
		assertFalse(MineralSurveyPolicy.withinRange(6, 0, 0));
	}

	@Test
	void theSurveyOnlyNoticesOreWorthInterruptingFor() {
		assertTrue(MineralSurveyPolicy.surveyable(TerminalResource.DIAMOND));
		assertTrue(MineralSurveyPolicy.surveyable(TerminalResource.EMERALD));
		assertFalse(MineralSurveyPolicy.surveyable(TerminalResource.IRON));
		assertFalse(MineralSurveyPolicy.surveyable(TerminalResource.COAL));
		assertFalse(MineralSurveyPolicy.surveyable(TerminalResource.GOLD));
		assertFalse(MineralSurveyPolicy.surveyable(TerminalResource.NONE));
	}

	@Test
	void emeraldOutranksEveryOtherReading() {
		for (TerminalResource other : TerminalResource.values()) {
			if (other == TerminalResource.EMERALD) continue;
			assertTrue(MineralSurveyPolicy.reportPriority(TerminalResource.EMERALD)
					> MineralSurveyPolicy.reportPriority(other), "emerald over " + other);
		}
	}

	@Test
	void mineralArrivalUsesARealOneBlockSphere() {
		assertTrue(MineralSurveyPolicy.arrived(1, 0, 0));
		assertTrue(MineralSurveyPolicy.arrived(0, -1, 0));
		assertFalse(MineralSurveyPolicy.arrived(1, 1, 0));
		assertFalse(MineralSurveyPolicy.arrived(0, 0, 2));
	}

	@Test
	void rarerOreMustBeCloserToBeHeard() {
		assertTrue(MineralSurveyPolicy.probeRadius(TerminalResource.COAL)
				> MineralSurveyPolicy.probeRadius(TerminalResource.IRON));
		assertTrue(MineralSurveyPolicy.probeRadius(TerminalResource.IRON)
				> MineralSurveyPolicy.probeRadius(TerminalResource.GOLD));
		assertTrue(MineralSurveyPolicy.probeRadius(TerminalResource.GOLD)
				> MineralSurveyPolicy.probeRadius(TerminalResource.DIAMOND));
		assertEquals(0, MineralSurveyPolicy.probeRadius(TerminalResource.NONE));
	}

	@Test
	void sweepCeilingNarrowsAsRarerFindingsRuleOutTheRest() {
		int all = mask(TerminalResource.COAL, TerminalResource.IRON,
				TerminalResource.GOLD, TerminalResource.DIAMOND);
		assertEquals(MineralSurveyPolicy.probeRadius(TerminalResource.COAL),
				MineralSurveyPolicy.rarerCeiling(all, TerminalResource.NONE));
		assertEquals(MineralSurveyPolicy.probeRadius(TerminalResource.GOLD),
				MineralSurveyPolicy.rarerCeiling(all, TerminalResource.IRON));
		assertEquals(MineralSurveyPolicy.probeRadius(TerminalResource.DIAMOND),
				MineralSurveyPolicy.rarerCeiling(all, TerminalResource.GOLD));
		assertEquals(0, MineralSurveyPolicy.rarerCeiling(all, TerminalResource.DIAMOND),
				"Nothing outranks diamond, so finding it ends the sweep immediately");
	}

	@Test
	void lockedOreNeverWidensTheSweep() {
		int early = mask(TerminalResource.COAL, TerminalResource.IRON);
		assertEquals(MineralSurveyPolicy.probeRadius(TerminalResource.COAL),
				MineralSurveyPolicy.rarerCeiling(early, TerminalResource.NONE));
		assertEquals(0, MineralSurveyPolicy.rarerCeiling(early, TerminalResource.IRON),
				"With gold and diamond still locked, iron is already the best possible reading");
		assertFalse(MineralSurveyPolicy.unlocked(early, TerminalResource.DIAMOND));
	}

	@Test
	void exactReadingRadiusScalesWithEachOresHearingRange() {
		assertEquals(19, MineralSurveyPolicy.exactReadingRadius(TerminalResource.COAL));
		assertEquals(16, MineralSurveyPolicy.exactReadingRadius(TerminalResource.IRON));
		assertEquals(12, MineralSurveyPolicy.exactReadingRadius(TerminalResource.GOLD));
		assertEquals(12, MineralSurveyPolicy.exactReadingRadius(TerminalResource.DIAMOND));
		assertEquals(12, MineralSurveyPolicy.exactReadingRadius(TerminalResource.EMERALD));
		assertEquals(0, MineralSurveyPolicy.exactReadingRadius(TerminalResource.NONE));
	}

	/**
	 * The floor is the whole reason this is a max() rather than a plain percentage: sixty percent of
	 * diamond's sixteen-block range is under twelve, so scaling alone would have made the rarest
	 * readings worse than the flat radius they replaced.
	 */
	@Test
	void noOreLosesGroundAgainstTheFlatRadiusItReplaced() {
		for (TerminalResource resource : TerminalResource.values()) {
			if (resource == TerminalResource.NONE) continue;
			assertTrue(MineralSurveyPolicy.exactReadingRadius(resource)
							>= MineralSurveyPolicy.MINIMUM_EXACT_READING_RADIUS,
					resource + " fell below the radius every ore used to get");
		}
	}

	/** An exact reading may never be promised past the distance the ore can be heard at all. */
	@Test
	void exactRadiusNeverExceedsTheProbeRadius() {
		for (TerminalResource resource : TerminalResource.values()) {
			assertTrue(MineralSurveyPolicy.exactReadingRadius(resource)
							<= MineralSurveyPolicy.probeRadius(resource),
					resource + " could report an exact hit it cannot hear");
		}
	}

	@Test
	void readingIsExactOnlyInsideThatOresRadius() {
		assertTrue(MineralSurveyPolicy.exactReading(TerminalResource.DIAMOND, 12, 0, 0));
		assertTrue(MineralSurveyPolicy.exactReading(TerminalResource.DIAMOND, 6, 6, 6));
		assertFalse(MineralSurveyPolicy.exactReading(TerminalResource.DIAMOND, 12, 1, 0));
		assertFalse(MineralSurveyPolicy.exactReading(TerminalResource.DIAMOND, 0, 0, 13));
		// The same offset that is only a bearing for diamond is an exact hit for coal.
		assertTrue(MineralSurveyPolicy.exactReading(TerminalResource.COAL, 0, 0, 13));
		assertTrue(MineralSurveyPolicy.exactReading(TerminalResource.COAL, 19, 0, 0));
		assertFalse(MineralSurveyPolicy.exactReading(TerminalResource.COAL, 20, 0, 0));
		assertFalse(MineralSurveyPolicy.exactReading(TerminalResource.NONE, 0, 0, 0));
	}

	@Test
	void distanceBandAlwaysBracketsTheTrueDistance() {
		for (int distance : new int[]{13, 20, 28, 32}) {
			int minimum = MineralSurveyPolicy.bandMinimum(distance);
			int maximum = MineralSurveyPolicy.bandMaximum(distance);
			assertTrue(minimum >= 1 && minimum <= distance, "band floor for " + distance);
			assertTrue(maximum > distance, "band ceiling for " + distance);
		}
		assertTrue(MineralSurveyPolicy.bandMaximum(0) > MineralSurveyPolicy.bandMinimum(0));
	}

	@Test
	void bearingSnapsToTheEightNamedCompassPoints() {
		MineralSurveyPolicy.Bearing east = MineralSurveyPolicy.quantizeBearing(30, 2);
		assertEquals(0, east.dz());
		assertTrue(east.dx() > 0);
		MineralSurveyPolicy.Bearing northeast = MineralSurveyPolicy.quantizeBearing(20, 19);
		assertEquals(northeast.dx(), northeast.dz(), "A 45 degree bearing must stay diagonal");
		assertEquals(0, MineralSurveyPolicy.quantizeBearing(0, 0).dx());
	}

	@Test
	void chargesRefillOneIntervalAtATimeAndStopAtTheCap() {
		MineralSurveyPolicy.ChargeState empty = new MineralSurveyPolicy.ChargeState(0, 1_000L);
		assertEquals(0, MineralSurveyPolicy.charges(empty.charges(), empty.nextRechargeTick(), 999L).charges());
		assertEquals(1, MineralSurveyPolicy.charges(empty.charges(), empty.nextRechargeTick(), 1_000L).charges());
		assertEquals(2, MineralSurveyPolicy.charges(empty.charges(), empty.nextRechargeTick(),
				1_000L + MineralSurveyPolicy.CHARGE_RECHARGE_TICKS).charges());
		MineralSurveyPolicy.ChargeState full = MineralSurveyPolicy.charges(0, 1_000L, 1_000_000L);
		assertEquals(MineralSurveyPolicy.MAX_PROBE_CHARGES, full.charges());
		assertEquals(0L, full.nextRechargeTick(), "A full bank has no charge in flight");
	}

	@Test
	void spendingStartsTheClockOnceAndNeverRestartsIt() {
		MineralSurveyPolicy.ChargeState full =
				new MineralSurveyPolicy.ChargeState(MineralSurveyPolicy.MAX_PROBE_CHARGES, 0L);
		MineralSurveyPolicy.ChargeState first = MineralSurveyPolicy.spend(full, 500L);
		assertEquals(MineralSurveyPolicy.MAX_PROBE_CHARGES - 1, first.charges());
		assertEquals(500L + MineralSurveyPolicy.CHARGE_RECHARGE_TICKS, first.nextRechargeTick());
		MineralSurveyPolicy.ChargeState second = MineralSurveyPolicy.spend(first, 800L);
		assertEquals(first.nextRechargeTick(), second.nextRechargeTick(),
				"Pressing again must not push back the charge already coming");
		assertEquals(0, MineralSurveyPolicy.spend(
				new MineralSurveyPolicy.ChargeState(0, 900L), 800L).charges());
	}

	@Test
	void aTimerFromAnotherClockIsRestartedRatherThanTrusted() {
		MineralSurveyPolicy.ChargeState restored = MineralSurveyPolicy.charges(0,
				10_000L + MineralSurveyPolicy.CHARGE_RECHARGE_TICKS * 5L, 10_000L);
		assertEquals(0, restored.charges());
		assertEquals(10_000L + MineralSurveyPolicy.CHARGE_RECHARGE_TICKS, restored.nextRechargeTick());
	}

	private static int mask(TerminalResource... resources) {
		int mask = 0;
		for (TerminalResource resource : resources) mask |= 1 << resource.wireId();
		return mask;
	}
}
