package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.AnomalyDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete data registry for the nineteen isolated client anomaly fixtures. */
public record AnomalyClientScenario(int catalogNumber, String id, int tier, long seed, Fixture fixture,
		Set<String> peakOverlays, int minimumDedicatedSounds, int minimumAmbientSounds,
		IntRange hiddenLights, IntRange misreadItems, int temporaryEntities, boolean cameraSeparated,
		boolean inputLocked, boolean audioLocked, boolean metaDegraded, Completion completion,
		Cleanup cleanup, AnomalyTestTimeline.Timeline timeline) {
	public enum Fixture {
		CAVE, LIGHTS, WALL, EMPTY, MOBS, WATCHER_CONE, ACTION_HISTORY, CAMERA, DOORS, INVENTORY,
		SAFE_PATH, WORLD_INVARIANTS, HORIZON, META_FALLBACK, CHANNEL
	}
	public enum Completion { CLIENT_RESTORE_REPORT, CLIENT_RESTORE_WITH_PERSISTENT_TRACE }
	public enum Cleanup { FULL_RESTORE, PERMANENT_DOOR_ONLY, PERSISTENT_TRACE_ONLY, EXACT_CAMERA_RESTORE }

	/**
	 * Catalogue entries this harness deliberately does not drive, and the reason it cannot.
	 *
	 * <p>Every scenario below is an instruction to {@code AnomalyPresentationController}: put the
	 * player in a fixture, run the anomaly, assert the named overlays at the peak tick, assert the
	 * world is restored afterwards. The unrendered layer has none of those parts. Its presentation
	 * is owned by {@code UnrenderedLayerClient} instead, because the controller tears itself down
	 * the moment {@code client.level} changes and the layer's whole first second <em>is</em> a level
	 * change; there is no fixture, because the player is not in the world the fixture would build;
	 * and there is no restore to assert, because what ends it is a teleport rather than an overlay
	 * being lifted.
	 *
	 * <p>Listed rather than quietly skipped. The coverage check below is the thing that stops an
	 * anomaly shipping with no client test at all, and the way to keep it doing that job is to make
	 * the one exception cost an edit here and a sentence explaining it. Anything added to this set
	 * needs its own coverage somewhere else - the layer's lives in the unrendered GameTest suite.
	 */
	public static final java.util.Set<String> UNCOVERED = java.util.Set.of("unrendered_layer");

	private static final List<AnomalyClientScenario> DEFINITIONS = create();

	public AnomalyClientScenario {
		peakOverlays = Set.copyOf(peakOverlays);
	}

	public static List<AnomalyClientScenario> definitions() { return DEFINITIONS; }

	public static AnomalyClientScenario require(String id) {
		return DEFINITIONS.stream().filter(value -> value.id.equals(id)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown anomaly client scenario: " + id));
	}

	public static void assertCatalogCoverage() {
		List<String> catalog = AnomalyCatalog.definitions().stream().map(AnomalyDefinition::id)
				.filter(id -> !UNCOVERED.contains(id)).toList();
		List<String> scenarios = DEFINITIONS.stream().map(AnomalyClientScenario::id).toList();
		if (!catalog.equals(scenarios)) throw new AssertionError("Anomaly client scenarios differ from catalog: "
				+ scenarios + " vs " + catalog);
		// No second count written down here. The list equality above already proves the scenarios and
		// the catalog match; a literal beside it can only ever go stale and start failing for a
		// number rather than for a missing scenario, which is what "sixteen" did in the runner.
		if (DEFINITIONS.isEmpty()) throw new AssertionError("Anomaly client scenarios must not be empty");
		AnomalyTestTimeline.assertCatalogCoverage();
	}

	private static List<AnomalyClientScenario> create() {
		Map<String, Builder> values = new LinkedHashMap<>();
		put(values, "phantom_echo", 0x1100A11L, Fixture.CAVE)
				.overlays("surface_fracture", "glitch_impact").sounds(1, 1);
		put(values, "light_dropout", 0x2200B22L, Fixture.LIGHTS);
		put(values, "silent_world", 0x404D404L, Fixture.EMPTY).overlays("ambient_silenced");
		put(values, "peripheral_residue", 0x4400D44L, Fixture.EMPTY)
				.overlays("glitch_impact").sounds(1, 0);
		put(values, "watcher_alignment", 0x5500E55L, Fixture.MOBS);
		put(values, "dark_watcher", 0x6600F66L, Fixture.WATCHER_CONE).sounds(0, 1);
		put(values, "action_echo", 0x7701077L, Fixture.ACTION_HISTORY)
				.overlays("action_echo_replay", "action_echo_animation").temporaryEntities(1);
		put(values, "viewpoint_separation", 0x8802088L, Fixture.CAMERA)
				.overlays("second_person_camera", "trigger_view_camera_fixed", "second_person_body_proxy",
						"first_person_hands_hidden")
				.temporaryEntities(2).camera().input(false)
				.cleanup(Cleanup.EXACT_CAMERA_RESTORE);
		put(values, "door_cascade", 900_001L, Fixture.DOORS).cleanup(Cleanup.PERMANENT_DOOR_ONLY);
		put(values, "organ_misread", 0xAA040AAL, Fixture.INVENTORY).misread(8, 8);
		put(values, "experience_gap", 0xBB050BBL, Fixture.SAFE_PATH).overlays("blackout").locks(true, true);
		put(values, "local_rule_collapse", 0xDD070DDL, Fixture.WORLD_INVARIANTS)
				.overlays("missing_texture_proxies", "missing_texture_proxies_rendered", "lighting_unsolved")
				.completion(Completion.CLIENT_RESTORE_WITH_PERSISTENT_TRACE).cleanup(Cleanup.PERSISTENT_TRACE_ONLY);
		put(values, "metric_drift", 0x606F606L, Fixture.HORIZON)
				.overlays("readout_skewed", "sky_desynchronised");
		put(values, "red_horizon", 0xEE080EEL, Fixture.HORIZON).overlays("red_horizon", "red_world_fog");
		put(values, "window_pulse", 0x101A101L, Fixture.META_FALLBACK).overlays("window_fallback").metaDegraded();
		put(values, "channel_override", 0x202B202L, Fixture.CHANNEL).overlays("channel_override").input(true);
		put(values, "desktop_presence", 0x303C303L, Fixture.META_FALLBACK).overlays("notepad_fallback").metaDegraded();

		// Numbered against the full catalogue rather than against the filtered list, so an exempt
		// entry does not renumber the scenarios after it. The ordinal ends up in screenshot
		// filenames, and a baseline comparison is only worth anything if the same anomaly keeps the
		// same number across the change that exempted a different one.
		List<AnomalyDefinition> catalog = AnomalyCatalog.definitions();
		return java.util.stream.IntStream.range(0, catalog.size()).mapToObj(index -> {
			AnomalyDefinition definition = catalog.get(index);
			if (UNCOVERED.contains(definition.id())) return null;
			Builder builder = values.get(definition.id());
			if (builder == null) throw new IllegalStateException("Missing anomaly scenario builder: " + definition.id());
			return builder.build(index + 1, definition);
		}).filter(java.util.Objects::nonNull).toList();
	}

	private static Builder put(Map<String, Builder> values, String id, long seed, Fixture fixture) {
		Builder builder = new Builder(id, seed, fixture);
		if (values.put(id, builder) != null) throw new IllegalStateException("Duplicate anomaly scenario: " + id);
		return builder;
	}

	public record IntRange(int minimum, int maximum) {
		public IntRange {
			if (minimum < 0 || maximum < minimum) throw new IllegalArgumentException("Invalid range");
		}
		public boolean contains(int value) { return value >= minimum && value <= maximum; }
	}

	private static final class Builder {
		private final String id; private final long seed; private final Fixture fixture;
		private Set<String> overlays = Set.of(); private int dedicatedSounds; private int ambientSounds;
		private IntRange hidden = new IntRange(0, 0); private IntRange misread = new IntRange(0, 0);
		private int temporaryEntities; private boolean camera; private boolean input; private boolean audio;
		private boolean metaDegraded; private Completion completion = Completion.CLIENT_RESTORE_REPORT;
		private Cleanup cleanup = Cleanup.FULL_RESTORE;
		private Builder(String id, long seed, Fixture fixture) { this.id = id; this.seed = seed; this.fixture = fixture; }
		private Builder overlays(String... value) { overlays = Set.of(value); return this; }
		private Builder sounds(int dedicated, int ambient) { dedicatedSounds = dedicated; ambientSounds = ambient; return this; }
		private Builder hidden(int minimum, int maximum) { hidden = new IntRange(minimum, maximum); return this; }
		private Builder misread(int minimum, int maximum) { misread = new IntRange(minimum, maximum); return this; }
		private Builder temporaryEntities(int value) { temporaryEntities = value; return this; }
		private Builder camera() { camera = true; return this; }
		private Builder locks(boolean inputValue, boolean audioValue) { input = inputValue; audio = audioValue; return this; }
		private Builder input(boolean value) { input = value; return this; }
		private Builder audio(boolean value) { audio = value; return this; }
		private Builder metaDegraded() { metaDegraded = true; return this; }
		private Builder completion(Completion value) { completion = value; return this; }
		private Builder cleanup(Cleanup value) { cleanup = value; return this; }
		private AnomalyClientScenario build(int number, AnomalyDefinition definition) {
			return new AnomalyClientScenario(number, id, definition.tier(), seed, fixture, overlays,
					dedicatedSounds, ambientSounds, hidden, misread, temporaryEntities, camera, input, audio,
					metaDegraded, completion, cleanup, AnomalyTestTimeline.require(id));
		}
	}
}
