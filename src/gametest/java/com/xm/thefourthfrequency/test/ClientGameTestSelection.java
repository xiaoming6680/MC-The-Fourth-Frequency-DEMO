package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.terminal.AnomalyCatalog;

import java.util.Optional;

/** Strict parser for the Gradle-to-client-GameTest selection contract. */
public record ClientGameTestSelection(Suite suite, Optional<String> anomalyId) {
	public enum Suite { ALL, MAINLINE, TOOLS_UI, NOTICE_ENTRY, ALPHA_RELAUNCH, ANOMALIES, ANOMALY_META_SMOKE, REWORK_FORMS, WATCHER_MODEL, WORLD_INTERFACE, TERMINAL_HANDHELD, SCREEN_FILTERS }

	public ClientGameTestSelection {
		anomalyId = anomalyId == null ? Optional.empty() : anomalyId;
		if (anomalyId.isPresent() && suite != Suite.ANOMALIES)
			throw new IllegalArgumentException("tffAnomaly requires the anomalies suite");
		anomalyId.ifPresent(id -> AnomalyCatalog.require(id));
	}

	public static ClientGameTestSelection current() {
		return parse(System.getProperty("thefourthfrequency.clientTestSuite", "all"),
				System.getProperty("thefourthfrequency.anomaly", ""));
	}

	public static ClientGameTestSelection parse(String suiteValue, String anomalyValue) {
		Suite suite = switch (suiteValue == null ? "all" : suiteValue.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "all", "default" -> Suite.ALL;
			case "mainline" -> Suite.MAINLINE;
			case "tools-ui" -> Suite.TOOLS_UI;
			case "notice-entry" -> Suite.NOTICE_ENTRY;
			case "alpha-relaunch" -> Suite.ALPHA_RELAUNCH;
			case "anomalies" -> Suite.ANOMALIES;
			case "anomaly-meta-smoke" -> Suite.ANOMALY_META_SMOKE;
			case "rework-forms" -> Suite.REWORK_FORMS;
			case "watcher-model" -> Suite.WATCHER_MODEL;
			case "world-interface" -> Suite.WORLD_INTERFACE;
			case "terminal-3d" -> Suite.TERMINAL_HANDHELD;
			case "screen-filters" -> Suite.SCREEN_FILTERS;
			default -> throw new IllegalArgumentException("Unknown client test suite: " + suiteValue);
		};
		String normalized = anomalyValue == null ? "" : anomalyValue.trim();
		return new ClientGameTestSelection(suite, normalized.isEmpty() ? Optional.empty() : Optional.of(normalized));
	}

	public boolean runsMainline() { return suite == Suite.ALL || suite == Suite.MAINLINE || suite == Suite.TOOLS_UI; }
	public boolean runsToolsUi() { return suite == Suite.ALL || suite == Suite.TOOLS_UI; }

	/**
	 * Whether the mainline run is finished once the tools-UI checks are done.
	 *
	 * <p>Only the focused suite stops there. {@code all} must keep going, and this predicate exists
	 * because it did not: the early return sat inside {@code runsToolsUi()}, which is true for both
	 * suites, so the unfiltered run silently dropped the entire second half of the mainline - band
	 * progression, the four damaged files, the diary unlock, Nether continuity, the capability model
	 * and the alpha main-menu stamp - and still reported success. A suite that is documented as the
	 * superset has to actually be one, and a gap that reports green is worse than no suite at all.
	 */
	public boolean stopsAfterToolsUi() { return suite == Suite.TOOLS_UI; }
	public boolean runsNoticeEntry() { return suite == Suite.NOTICE_ENTRY; }
	public boolean runsAlphaRelaunch() { return suite == Suite.ALPHA_RELAUNCH; }
	public boolean runsAnomalies() { return suite == Suite.ALL || suite == Suite.ANOMALIES; }
	public boolean runsMetaSmoke() { return suite == Suite.ANOMALY_META_SMOKE; }
	public boolean runsReworkForms() { return suite == Suite.ALL || suite == Suite.REWORK_FORMS; }
	public boolean runsWatcherModel() { return suite == Suite.ALL || suite == Suite.WATCHER_MODEL; }
	public boolean runsWorldInterface() { return suite == Suite.ALL || suite == Suite.WORLD_INTERFACE; }
	/**
	 * The screen filters are in the full run as well as on their own.
	 *
	 * <p>A post-effect chain that fails to compile does not crash and does not stop anything - the
	 * chain simply never installs, and the treatment is silently missing. That failure mode is why
	 * this cannot be left to a targeted suite somebody remembers to run.
	 */
	public boolean runsScreenFilters() {
		return suite == Suite.ALL || suite == Suite.SCREEN_FILTERS;
	}

	public boolean runsTerminalHandheld() {
		return suite == Suite.ALL || suite == Suite.TERMINAL_HANDHELD;
	}
}
