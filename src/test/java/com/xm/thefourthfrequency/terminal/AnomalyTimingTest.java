package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.unrendered.UnrenderedAnomaly;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class AnomalyTimingTest {
	/** Anomalies meant to be lived through rather than witnessed; see AnomalyCatalog. */
	private static final Set<String> SUSTAINED = Set.of("silent_world", "metric_drift");
	private static final int MAX_EVENT_TICKS = 800;
	private static final int MIN_SUSTAINED_TICKS = 2_400;
	private static final int MAX_SUSTAINED_TICKS = 6_000;
	/**
	 * The sky anomaly sits between the two bands, on purpose.
	 *
	 * <p>It is not an event - forty seconds was not long enough to check the terminal, read the
	 * horizon channel and then still be standing under it - and it is not one of the sustained tier
	 * either, which exist to fill the gaps between events and run for minutes. One minute is its own
	 * thing, so it is asserted as its own thing rather than by widening a band it does not belong to.
	 */
	private static final int RED_HORIZON_TICKS = 20 * 60;

	@Test
	void everyCatalogEntryHasBoundedActiveTiming() {
		for (AnomalyDefinition definition : AnomalyCatalog.definitions()) {
			int duration = AnomalyTiming.durationTicks(definition.id(), 123456789L);
			if (definition.id().equals(UnrenderedAnomaly.ID)) {
				// Outside both bands, and deliberately. Every other entry decorates the world the
				// player is standing in, so its length is how long an effect is layered over that
				// world - which is what the sustained ceiling is a judgement about. This one moves
				// the player somewhere else, and its number is not a duration at all but the
				// backstop under an event that almost always ends sooner, at a hole in the floor or
				// at the entity. Held to an exact value rather than a band so a change to it is a
				// decision somebody made rather than a drift inside a range.
				assertEquals(UnrenderedAnomaly.DURATION_TICKS, duration, "the layer sets its own ceiling");
			} else if (definition.id().equals("red_horizon")) {
				assertEquals(RED_HORIZON_TICKS, duration, "red_horizon has its own length");
			} else if (SUSTAINED.contains(definition.id())) {
				// These exist specifically to outlast the short events. Holding them to the same
				// ceiling would reinstate the gap the sustained tier was added to close.
				assertTrue(duration >= MIN_SUSTAINED_TICKS && duration <= MAX_SUSTAINED_TICKS,
						definition.id() + " must stay in the sustained band, was " + duration);
			} else {
				assertTrue(duration >= 1 && duration <= MAX_EVENT_TICKS, definition.id());
			}
		}
		assertEquals(RED_HORIZON_TICKS, AnomalyTiming.durationTicks("red_horizon", 0L));
		assertEquals(300, AnomalyTiming.durationTicks("channel_override", 0L));
		assertEquals(240, AnomalyTiming.durationTicks("peripheral_residue", 0L));
		assertEquals(80, AnomalyTiming.durationTicks("window_pulse", 0L));
		assertEquals(100, AnomalyTiming.durationTicks("experience_gap", 0L));
		// Inherited from the unsolved lighting merged into it: the darkness needs a backstop long
		// enough to matter to a player with nothing to mine, and the eight seconds the missing
		// textures used to run for was not it.
		assertEquals(600, AnomalyTiming.durationTicks("local_rule_collapse", 0L));
		assertEquals(280, AnomalyTiming.durationTicks("phantom_echo", 0L));
	}

	@Test
	void sustainedAnomaliesOutlastEveryShortEventAcrossTheWholeSeedRange() {
		// The whole point of the sustained tier is duration, so no seed may let one of them come
		// in shorter than the longest ordinary event.
		for (long seed : new long[]{0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 987654321L}) {
			for (String id : SUSTAINED) {
				int duration = AnomalyTiming.durationTicks(id, seed);
				assertTrue(duration > MAX_EVENT_TICKS,
						id + " at seed " + seed + " was only " + duration + " ticks");
				assertTrue(duration <= MAX_SUSTAINED_TICKS,
						id + " at seed " + seed + " overran at " + duration + " ticks");
			}
		}
	}
}
