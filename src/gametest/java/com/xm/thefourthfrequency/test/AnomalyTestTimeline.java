package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.terminal.AnomalyCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GameTest-only accelerated waits. Production durations remain in {@code AnomalyTiming}. */
public final class AnomalyTestTimeline {
	private static final Map<String, Timeline> TIMELINES = timelines();

	private AnomalyTestTimeline() { }

	public static Timeline require(String id) {
		Timeline timeline = TIMELINES.get(id);
		if (timeline == null) throw new IllegalArgumentException("Missing anomaly test timeline: " + id);
		return timeline;
	}

	public static void assertCatalogCoverage() {
		// Reads the exemption from AnomalyClientScenario rather than keeping a second copy: two lists
		// of "what we do not test" drift apart, and the direction they drift is always towards
		// something being untested in one of them and unnoticed in both.
		List<String> catalog = AnomalyCatalog.definitions().stream().map(value -> value.id())
				.filter(id -> !AnomalyClientScenario.UNCOVERED.contains(id)).toList();
		if (!TIMELINES.keySet().equals(new java.util.LinkedHashSet<>(catalog)))
			throw new AssertionError("Anomaly test timelines differ from the catalog: " + TIMELINES.keySet() + " vs " + catalog);
		for (Timeline timeline : TIMELINES.values()) timeline.validate();
	}

	private static Map<String, Timeline> timelines() {
		Map<String, Timeline> values = new LinkedHashMap<>();
		add(values, "phantom_echo", 40, 20, "approach", "first_blow", "crack_peak", "restore");
		add(values, "light_dropout", 24, 10, "start", "server_extinguished", "restore");
		add(values, "silent_world", 32, 16, "start", "ambient_silenced", "restore");
		add(values, "peripheral_residue", 32, 27, "fast_enter", "corruption_impact_and_hands_removed", "restore");
		add(values, "watcher_alignment", 28, 12, "start", "aligned", "restore");
		add(values, "dark_watcher", 32, 12, "spawn", "visible", "remove");
		add(values, "action_echo", 84, 48, "history", "spawn", "replay", "restore");
		add(values, "viewpoint_separation", 28, 12, "capture_view", "fixed_camera_controllable_body", "restore");
		add(values, "door_cascade", 68, 42, "select", "break_sequence", "permanent", "cleanup");
		add(values, "organ_misread", 32, 14, "select", "replace", "restore");
		add(values, "experience_gap", 36, 18, "blackout", "safe_move", "restore");
		add(values, "local_rule_collapse", 80, 40,
				"select", "proxy", "lighting_unsolved", "persistent_trace", "restore");
		add(values, "metric_drift", 32, 16, "start", "sky_desynchronised", "readout_skewed", "restore");
		add(values, "red_horizon", 40, 18, "red_peak", "distance_limit", "fade", "restore");
		add(values, "window_pulse", 24, 12, "start", "fallback", "restore");
		add(values, "channel_override", 44, 24, "open", "script", "final_hold", "restore");
		add(values, "desktop_presence", 24, 12, "start", "fallback", "restore");
		return Map.copyOf(values);
	}

	private static void add(Map<String, Timeline> values, String id, int duration, int peak, String... phases) {
		if (values.put(id, new Timeline(duration, peak, List.of(phases))) != null)
			throw new IllegalStateException("Duplicate anomaly timeline: " + id);
	}

	public record Timeline(int acceleratedTicks, int peakTick, List<String> orderedPhases) {
		public Timeline { orderedPhases = List.copyOf(orderedPhases); }
		private void validate() {
			if (acceleratedTicks < 4 || peakTick < 2 || peakTick >= acceleratedTicks)
				throw new AssertionError("Invalid accelerated anomaly timeline: " + this);
			if (orderedPhases.size() < 3 || !orderedPhases.getLast().equals("restore")
					&& !orderedPhases.getLast().equals("cleanup") && !orderedPhases.getLast().equals("remove"))
				throw new AssertionError("Timeline must preserve an explicit cleanup tail: " + orderedPhases);
		}
	}
}
