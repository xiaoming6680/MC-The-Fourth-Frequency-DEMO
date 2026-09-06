package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class AnomalyCatalogTest {
	@Test
	void catalogContainsEighteenStableUniqueIdsInFiveTiers() {
		assertEquals(18, AnomalyCatalog.definitions().size());
		assertEquals(18, AnomalyCatalog.definitions().stream().map(AnomalyDefinition::id).distinct().count());
		assertEquals(3, AnomalyCatalog.definitions().stream().filter(value -> value.tier() == 1).count());
		assertEquals(6, AnomalyCatalog.definitions().stream().filter(value -> value.tier() == 2).count());
		assertEquals(4, AnomalyCatalog.definitions().stream().filter(value -> value.tier() == 3).count());
		assertEquals(2, AnomalyCatalog.definitions().stream().filter(value -> value.tier() == 4).count());
		assertEquals(3, AnomalyCatalog.definitions().stream().filter(value -> value.tier() == 5).count());
		assertTrue(AnomalyCatalog.require("door_cascade").destructive());
		assertEquals(AnomalyDefinition.Scope.SHARED, AnomalyCatalog.require("light_dropout").scope());
		assertFalse(AnomalyCatalog.require("local_rule_collapse").destructive());
		assertFalse(AnomalyCatalog.contains("rework_probe"));
		assertFalse(AnomalyCatalog.contains("surface_fracture"));
		assertFalse(AnomalyCatalog.contains("temporal_drift"));
		assertFalse(AnomalyCatalog.contains("luminance_fault"));
		assertFalse(AnomalyCatalog.contains("hostile_echo"));
		assertFalse(AnomalyCatalog.contains("disconnected_base"));
		assertFalse(AnomalyCatalog.contains("watcher_orbit"));
		assertThrows(IllegalArgumentException.class, () -> AnomalyCatalog.require("arbitrary_command"));
	}

	/**
	 * indexOf() is the bit position an anomaly occupies in {@code ANOMALY_SEEN_MASK}, which is
	 * persisted per player. Renumbering an existing id - which is what inserting a new entry above it
	 * does - would hand every existing save a seen history for anomalies that player never met, and
	 * would do it silently, because a bitmask has no way to report that it means something else now.
	 *
	 * <p>So new entries are appended and these indices are frozen - and, since three ids were merged
	 * away, a retired entry keeps its slot rather than letting everything after it slide down by one.
	 * That is the whole reason the bit order is its own list rather than the catalogue order.
	 */
	@Test
	void seenMaskIndicesAreFrozenAcrossRetirementsSoBitsNeverShift() {
		assertEquals(0, AnomalyCatalog.indexOf("phantom_echo"));
		assertEquals(1, AnomalyCatalog.indexOf("light_dropout"));
		assertEquals(2, AnomalyCatalog.indexOf("surface_fracture"));
		assertEquals(3, AnomalyCatalog.indexOf("silent_world"));
		assertEquals(9, AnomalyCatalog.indexOf("temporal_drift"));
		assertEquals(10, AnomalyCatalog.indexOf("viewpoint_separation"));
		assertEquals(13, AnomalyCatalog.indexOf("local_rule_collapse"));
		assertEquals(17, AnomalyCatalog.indexOf("channel_override"));
		assertEquals(18, AnomalyCatalog.indexOf("desktop_presence"));
		assertEquals(19, AnomalyCatalog.indexOf("luminance_fault"));
		assertEquals(-1, AnomalyCatalog.indexOf("never_shipped"));
		assertTrue(AnomalyCatalog.definitions().size() <= 64,
				"the seen mask is a long - a 65th anomaly needs a wider field and a migration");
	}

	/**
	 * A retired id is history, never gameplay.
	 *
	 * <p>The distinction is the entire safety of retiring one: stored logs and recent-id lists still
	 * hold them and have to keep resolving, while nothing may ever draw, start, time or list one
	 * again. {@code require} and {@code durationTicks} throwing is what makes an accidental
	 * re-introduction loud instead of silent.
	 */
	@Test
	void retiredIdsAreReadableFromHistoryAndUnreachableAsGameplay() {
		assertEquals(Set.of("surface_fracture", "temporal_drift", "luminance_fault"),
				AnomalyCatalog.retiredIds());
		for (String id : AnomalyCatalog.retiredIds()) {
			assertTrue(AnomalyCatalog.retired(id));
			assertTrue(AnomalyCatalog.containsHistorical(id));
			assertTrue(AnomalyCatalog.indexOf(id) >= 0, id + " must keep its seen-mask bit");
			assertFalse(AnomalyCatalog.contains(id));
			assertThrows(IllegalArgumentException.class, () -> AnomalyCatalog.require(id));
			assertThrows(IllegalArgumentException.class, () -> AnomalyTiming.durationTicks(id, 1L));
			for (int stage = 1; stage <= 5; stage++) {
				int frozen = stage;
				assertFalse(AnomalyCatalog.pool(frozen).stream().anyMatch(value -> value.id().equals(id)),
						id + " must not be drawable at stage " + frozen);
			}
		}
		for (AnomalyDefinition definition : AnomalyCatalog.definitions()) {
			assertTrue(AnomalyCatalog.containsHistorical(definition.id()),
					definition.id() + " is missing from the frozen bit order");
		}
	}

	/**
	 * A client-side illusion. It renders light and some textures wrongly and changes nothing else, so
	 * it must never be classed as touching shared space or the world.
	 *
	 * <p>Its availability is the union of the two entries merged into it - tier 2 from the unsolved
	 * lighting, stages 4 and 5 from the missing textures - so the merge cannot become a quiet way of
	 * deleting half of a stage of content.
	 */
	@Test
	void localRuleCollapseIsPrivateNonDestructiveAndSpansBothInheritedStageRanges() {
		AnomalyDefinition collapse = AnomalyCatalog.require("local_rule_collapse");
		assertEquals(2, collapse.tier());
		assertEquals(AnomalyDefinition.Scope.PRIVATE, collapse.scope());
		assertFalse(collapse.destructive());
		assertFalse(collapse.strong());
		assertFalse(AnomalyCatalog.unlocked(1).contains(collapse));
		assertTrue(AnomalyCatalog.unlocked(2).contains(collapse));
		assertTrue(AnomalyCatalog.unlocked(3).contains(collapse));
		assertTrue(AnomalyCatalog.unlocked(4).contains(collapse));
		assertTrue(AnomalyCatalog.unlocked(5).contains(collapse));
	}

	@Test
	void sustainedAnomaliesAreSpreadAcrossTheEarlyAndMiddleTiers() {
		// One long-form anomaly at each end of the pre-endgame range, so the quiet stretches between
		// the short events have something to fill them from the first stage through to stage 4.
		assertEquals(1, AnomalyCatalog.require("silent_world").tier());
		assertEquals(3, AnomalyCatalog.require("metric_drift").tier());
		for (String id : new String[]{"silent_world", "metric_drift"}) {
			assertFalse(AnomalyCatalog.require(id).strong(),
					id + " must not consume the strong-interface cooldown");
			assertFalse(AnomalyCatalog.require(id).destructive(), id + " must not alter the world");
			assertEquals(AnomalyDefinition.Scope.PRIVATE, AnomalyCatalog.require(id).scope());
		}
	}

	@Test
	void slidingPoolsRetireOldContentWithoutShrinkingTheMiddleGame() {
		assertEquals(0, AnomalyCatalog.unlocked(0).size());
		assertEquals(3, AnomalyCatalog.unlocked(1).size());
		assertEquals(9, AnomalyCatalog.unlocked(2).size());
		assertEquals(10, AnomalyCatalog.unlocked(3).size());
		assertEquals(7, AnomalyCatalog.unlocked(4).size());
		// Seven, not six: the unrendered layer is the third tier-5 entry, and stage five is the
		// only stage that can draw it.
		assertEquals(7, AnomalyCatalog.unlocked(5).size());
		assertFalse(AnomalyCatalog.unlocked(3).stream().anyMatch(value -> value.id().equals("phantom_echo")));
		assertTrue(AnomalyCatalog.unlocked(5).stream().anyMatch(value -> value.id().equals("experience_gap")));
	}

	@Test
	void recentThreeAreExcludedAndNewStageContentHasTripleWeight() {
		var weighted = AnomalyCatalog.weightedPool(2,
				Set.of("phantom_echo", "light_dropout", "action_echo"), true);
		// Six survivors after the recent-three exclusion: silent_world at weight 1 (tier below the
		// requested stage) plus five tier-2 entries at the triple new-content weight.
		assertEquals(16, weighted.size());
		assertEquals(3, weighted.stream().filter(value -> value.id().equals("organ_misread")).count());
		assertFalse(weighted.stream().anyMatch(value -> value.id().equals("phantom_echo")));
	}
}
