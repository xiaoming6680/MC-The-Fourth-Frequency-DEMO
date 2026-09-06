package com.xm.thefourthfrequency;

import com.xm.thefourthfrequency.test.AnomalyClientScenario;
import com.xm.thefourthfrequency.test.AnomalyTestTimeline;
import com.xm.thefourthfrequency.test.ClientGameTestSelection;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnomalyClientAutomationContractTest {
	@Test
	void clientScenarioRegistryExactlyMatchesCatalogInOrder() {
		AnomalyClientScenario.assertCatalogCoverage();
		List<String> catalog = AnomalyCatalog.definitions().stream().map(value -> value.id()).toList();
		List<String> scenarios = AnomalyClientScenario.definitions().stream().map(value -> value.id()).toList();
		// The catalogue is eighteen; seventeen of them are driven here. The eighteenth is exempt by
		// name in AnomalyClientScenario.UNCOVERED, which is asserted rather than assumed: an anomaly
		// must not be able to lose its client coverage by being forgotten, only by being excused in
		// writing.
		List<String> covered = catalog.stream()
				.filter(id -> !AnomalyClientScenario.UNCOVERED.contains(id)).toList();
		assertEquals(Set.of("unrendered_layer"), AnomalyClientScenario.UNCOVERED);
		assertEquals(18, catalog.size());
		assertEquals(17, scenarios.size());
		assertEquals(covered, scenarios);
		assertEquals(17, scenarios.stream().distinct().count());
		assertEquals(17, AnomalyClientScenario.definitions().stream().map(value -> value.seed()).distinct().count());
	}

	@Test
	void acceleratedTimelinesPreserveOrderedPeakAndCleanup() {
		AnomalyTestTimeline.assertCatalogCoverage();
		for (AnomalyClientScenario scenario : AnomalyClientScenario.definitions()) {
			var timeline = scenario.timeline();
			assertTrue(timeline.acceleratedTicks() >= 4, scenario.id());
			assertTrue(timeline.peakTick() >= 2 && timeline.peakTick() < timeline.acceleratedTicks(), scenario.id());
			assertTrue(timeline.orderedPhases().size() >= 3, scenario.id());
			assertTrue(Set.of("restore", "cleanup", "remove").contains(timeline.orderedPhases().getLast()), scenario.id());
			assertEquals(timeline.orderedPhases().size(), timeline.orderedPhases().stream().distinct().count(), scenario.id());
		}
	}

	@Test
	void suiteAndSingleAnomalyFiltersAreStrict() {
		var defaults = ClientGameTestSelection.parse("all", "");
		assertTrue(defaults.runsMainline());
		assertTrue(defaults.runsAnomalies());
		assertFalse(defaults.runsAlphaRelaunch());
		assertFalse(defaults.runsMetaSmoke());
		assertTrue(defaults.runsReworkForms());
		assertTrue(defaults.runsWatcherModel());
		assertTrue(defaults.runsToolsUi());
		assertFalse(defaults.runsNoticeEntry());
		assertTrue(defaults.runsWorldInterface());
		// The unfiltered run must not stop at the tools-UI checks. It used to, because the early
		// return was guarded by runsToolsUi(), which is true here as well - so everything after it
		// (bands, damaged files, the diary, Nether continuity, the capability model) was skipped and
		// the run still went green.
		assertFalse(defaults.stopsAfterToolsUi());

		var noticeEntry = ClientGameTestSelection.parse("notice-entry", "");
		assertTrue(noticeEntry.runsNoticeEntry());
		assertFalse(noticeEntry.runsMainline());
		assertFalse(noticeEntry.runsAnomalies());

		var relaunch = ClientGameTestSelection.parse("alpha-relaunch", "");
		assertTrue(relaunch.runsAlphaRelaunch());
		assertFalse(relaunch.runsMainline());
		assertFalse(relaunch.runsAnomalies());

		var single = ClientGameTestSelection.parse("anomalies", "phantom_echo");
		assertFalse(single.runsMainline());
		assertTrue(single.runsAnomalies());
		assertEquals("phantom_echo", single.anomalyId().orElseThrow());

		var smoke = ClientGameTestSelection.parse("anomaly-meta-smoke", "");
		assertTrue(smoke.runsMetaSmoke());
		assertFalse(smoke.runsAnomalies());
		var reworkForms = ClientGameTestSelection.parse("rework-forms", "");
		assertTrue(reworkForms.runsReworkForms());
		assertFalse(reworkForms.runsMainline());
		assertFalse(reworkForms.runsAnomalies());
		var toolsUi = ClientGameTestSelection.parse("tools-ui", "");
		assertTrue(toolsUi.runsMainline());
		assertTrue(toolsUi.runsToolsUi());
		assertFalse(toolsUi.runsAnomalies());
		assertTrue(toolsUi.stopsAfterToolsUi());

		var mainlineOnly = ClientGameTestSelection.parse("mainline", "");
		assertTrue(mainlineOnly.runsMainline());
		assertFalse(mainlineOnly.runsToolsUi());
		assertFalse(mainlineOnly.stopsAfterToolsUi());
		var watcherModel = ClientGameTestSelection.parse("watcher-model", "");
		assertTrue(watcherModel.runsWatcherModel());
		assertFalse(watcherModel.runsMainline());
		assertFalse(watcherModel.runsAnomalies());
		assertFalse(watcherModel.runsReworkForms());
		var worldInterface = ClientGameTestSelection.parse("world-interface", "");
		assertTrue(worldInterface.runsWorldInterface());
		assertFalse(worldInterface.runsMainline());
		assertFalse(worldInterface.runsAnomalies());
		// The screen filters are in the full run as well as on their own: a chain that will not
		// compile installs nothing and logs nothing a player would find, so it must not be left to
		// a targeted suite somebody remembers to ask for.
		assertTrue(defaults.runsScreenFilters());
		var screenFilters = ClientGameTestSelection.parse("screen-filters", "");
		assertTrue(screenFilters.runsScreenFilters());
		assertFalse(screenFilters.runsMainline());
		assertFalse(screenFilters.runsAnomalies());
		assertThrows(IllegalArgumentException.class,
				() -> ClientGameTestSelection.parse("mainline", "phantom_echo"));
		assertThrows(IllegalArgumentException.class,
				() -> ClientGameTestSelection.parse("watcher-model", "dark_watcher"));
		assertThrows(IllegalArgumentException.class,
				() -> ClientGameTestSelection.parse("anomalies", "not_a_stable_id"));
		assertThrows(IllegalArgumentException.class,
				() -> ClientGameTestSelection.parse("unknown", ""));
	}
}
