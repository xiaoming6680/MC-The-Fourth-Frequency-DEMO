package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AnomalyDimensionPolicyTest {
	@Test
	void onlyTheOverworldAndTheNetherRunADirector() {
		assertEquals(AnomalyDimensionPolicy.Mode.NORMAL,
				AnomalyDimensionPolicy.mode(AnomalyDimensionPolicy.OVERWORLD_ID));
		assertEquals(AnomalyDimensionPolicy.Mode.PRESSURE,
				AnomalyDimensionPolicy.mode(AnomalyDimensionPolicy.NETHER_ID));
		assertEquals(AnomalyDimensionPolicy.Mode.EXCLUDED,
				AnomalyDimensionPolicy.mode("minecraft:the_end"));
	}

	/**
	 * The product endpoint rather than the switch: anything this mod cannot make a claim about is
	 * silent. A default that fell through to NORMAL would put ambient anomalies in every dimension
	 * any other mod ever adds, which is somebody else's world being reinterpreted without asking.
	 */
	@Test
	void everythingElseIsExcludedIncludingThisModsOwnDimensions() {
		assertTrue(AnomalyDimensionPolicy.excluded("someothermod:crystal_realm"));
		assertTrue(AnomalyDimensionPolicy.excluded("thefourthfrequency:unrendered_layer"));
		assertTrue(AnomalyDimensionPolicy.excluded(""));
		assertTrue(AnomalyDimensionPolicy.excluded("minecraft:overworld_caves"));
	}

	@Test
	void anUnscheduledRecordHasNothingToFreeze() {
		assertEquals(0L, AnomalyDimensionPolicy.frozenRemaining(0L, 5_000L));
	}

	@Test
	void freezingKeepsTheRemainderAndThawingSpendsItFromTheReturn() {
		long now = 10_000L;
		long remaining = AnomalyDimensionPolicy.frozenRemaining(now + 4_000L, now);
		assertEquals(4_000L, remaining);
		// Twenty minutes in the End must cost the player none of it, and must not bank an anomaly
		// waiting on the doormat either.
		assertEquals(now + 24_000L + 4_000L, AnomalyDimensionPolicy.thawedNext(now + 24_000L, remaining));
	}

	/**
	 * The interaction that would otherwise undo the post-chase quiet: a mirror freezes a short
	 * remainder, the chase ends by scheduling six and a half minutes out, and the player walks back
	 * into the overworld holding both.
	 */
	@Test
	void resumingNeverPullsAScheduleWrittenDuringTheFreezeCloser() {
		long now = 50_000L;
		long postChase = now + 6L * 60L * 20L + 30L * 20L;
		assertEquals(postChase, AnomalyDimensionPolicy.resumedNext(now, 600L, postChase));
		// With nothing newer on the record, the remainder is the answer.
		assertEquals(now + 600L, AnomalyDimensionPolicy.resumedNext(now, 600L, 0L));
		assertEquals(now + 600L, AnomalyDimensionPolicy.resumedNext(now, 600L, now - 10_000L));
	}

	@Test
	void anOverdueScheduleStaysOverdueRatherThanGoingNegative() {
		long remaining = AnomalyDimensionPolicy.frozenRemaining(9_000L, 10_000L);
		assertEquals(1L, remaining);
		assertEquals(20_001L, AnomalyDimensionPolicy.thawedNext(20_000L, remaining));
		assertEquals(20_001L, AnomalyDimensionPolicy.thawedNext(20_000L, 0L));
	}
}
