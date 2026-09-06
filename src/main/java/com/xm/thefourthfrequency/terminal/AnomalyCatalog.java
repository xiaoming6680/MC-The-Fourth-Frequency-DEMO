package com.xm.thefourthfrequency.terminal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.xm.thefourthfrequency.terminal.AnomalyDefinition.Scope.PRIVATE;
import static com.xm.thefourthfrequency.terminal.AnomalyDefinition.Scope.SHARED;

public final class AnomalyCatalog {
	/**
	 * Every anomaly id this mod has ever shipped, in the order that fixes its bit position in
	 * {@code ANOMALY_SEEN_MASK}.
	 *
	 * <p>This list is frozen and append-only, and it is deliberately <em>not</em> the same list as
	 * {@link #DEFINITIONS}. The seen mask is persisted per player, so a bit position is a promise:
	 * renumbering one silently hands existing saves a seen history for anomalies their player never
	 * met, and a bitmask has no way to report that it now means something else. Retiring an entry
	 * therefore leaves its slot standing rather than closing the gap.
	 *
	 * <p>It is also what {@link #containsHistorical} answers from, because stores written before the
	 * merge below still hold retired ids: the quarantined anomaly log the records page backfills
	 * from, the ordinary signal log that prunes anomaly types out of itself, and the per-player
	 * recent-ids list. Those reads have to keep recognising what they were written with.
	 */
	private static final List<String> MASK_ORDER = List.of(
			"phantom_echo", "light_dropout", "surface_fracture", "silent_world",
			"peripheral_residue", "watcher_alignment", "dark_watcher", "action_echo",
			"organ_misread", "temporal_drift", "viewpoint_separation", "door_cascade",
			"experience_gap", "local_rule_collapse", "metric_drift", "red_horizon",
			"window_pulse", "channel_override", "desktop_presence", "luminance_fault",
			"unrendered_layer");
	/**
	 * Ids that are still read out of stored history but can never be drawn, started or logged again.
	 *
	 * <p>Each was merged into the surviving entry that already said most of the same thing, because
	 * two short halves of one idea land for less than one event that does both:
	 *
	 * <ul>
	 * <li>{@code surface_fracture} into {@code phantom_echo} - the digging is now heard <em>and</em>
	 *     found, instead of two anomalies that each told half of it and neither of which lasted long
	 *     enough to be traced to a wall.</li>
	 * <li>{@code temporal_drift} into {@code metric_drift} - one drifting frame of reference, with
	 *     the sky and the terminal's own numbers both off it, rather than two sustained anomalies
	 *     that each bent a single readout.</li>
	 * <li>{@code luminance_fault} into {@code local_rule_collapse} - one volume where the world stops
	 *     being solved correctly, losing its light and some of its textures at once, and where only
	 *     one of the two comes back when the player disturbs it.</li>
	 * </ul>
	 */
	private static final Set<String> RETIRED_IDS =
			Set.of("surface_fracture", "temporal_drift", "luminance_fault");
	/**
	 * Ordered by tier. Entries marked "sustained" hold for minutes at an intensity low enough to
	 * be doubted, rather than firing for a few seconds and letting the world snap back: the
	 * short events alone left the mod quiet for roughly 98% of a session, so unease had nowhere
	 * to live between them.
	 *
	 * <p>Nothing reads this list positionally - {@link #pool} selects on {@link
	 * AnomalyDefinition#tier()} and the seen mask reads {@link #MASK_ORDER} - so entries are filed
	 * under their tier here without any of them owing a position to a save file.
	 */
	private static final List<AnomalyDefinition> DEFINITIONS = List.of(
			new AnomalyDefinition("phantom_echo", 1, PRIVATE, false, false),
			new AnomalyDefinition("light_dropout", 1, SHARED, false, false),
			new AnomalyDefinition("silent_world", 1, PRIVATE, false, false),
			new AnomalyDefinition("peripheral_residue", 2, PRIVATE, false, false),
			new AnomalyDefinition("watcher_alignment", 2, SHARED, false, false),
			new AnomalyDefinition("dark_watcher", 2, SHARED, false, false),
			new AnomalyDefinition("action_echo", 2, PRIVATE, false, false),
			new AnomalyDefinition("organ_misread", 2, PRIVATE, false, false),
			new AnomalyDefinition("local_rule_collapse", 2, PRIVATE, false, false),
			new AnomalyDefinition("viewpoint_separation", 3, PRIVATE, false, false),
			new AnomalyDefinition("door_cascade", 3, SHARED, false, true),
			new AnomalyDefinition("experience_gap", 3, SHARED, false, false),
			new AnomalyDefinition("metric_drift", 3, PRIVATE, false, false),
			new AnomalyDefinition("red_horizon", 4, PRIVATE, false, false),
			new AnomalyDefinition("window_pulse", 4, PRIVATE, true, false),
			new AnomalyDefinition("channel_override", 5, PRIVATE, true, false),
			new AnomalyDefinition("desktop_presence", 5, PRIVATE, true, false),
			// Not destructive despite moving the player out of the world: nothing is placed, broken
			// or taken, and the return address is written before the teleport. What makes it strong
			// is that it is the only entry that stops the run for minutes at a time.
			new AnomalyDefinition("unrendered_layer", 5, PRIVATE, true, false));
	private static final Map<String, AnomalyDefinition> BY_ID = DEFINITIONS.stream()
			.collect(Collectors.toUnmodifiableMap(AnomalyDefinition::id, Function.identity()));

	private AnomalyCatalog() { }

	public static List<AnomalyDefinition> definitions() { return DEFINITIONS; }
	public static AnomalyDefinition require(String id) {
		AnomalyDefinition definition = BY_ID.get(id);
		if (definition == null) throw new IllegalArgumentException("Unknown anomaly id: " + id);
		return definition;
	}
	public static boolean contains(String id) { return BY_ID.containsKey(id); }
	/**
	 * Whether this id was ever an anomaly, retired ones included.
	 *
	 * <p>For reading stored history only. Every path that draws, starts, logs or lists an anomaly
	 * uses {@link #contains} instead, so a retired id can never come back as gameplay.
	 */
	public static boolean containsHistorical(String id) { return MASK_ORDER.contains(id); }
	public static boolean retired(String id) { return RETIRED_IDS.contains(id); }
	/** The retired ids, for the history-facing contracts that still have to account for them. */
	public static Set<String> retiredIds() { return RETIRED_IDS; }
	public static List<AnomalyDefinition> unlocked(int tier) {
		return pool(tier);
	}
	public static List<AnomalyDefinition> pool(int stage) {
		int clamped = Math.clamp(stage, 0, 5);
		if (clamped == 0) return List.of();
		return DEFINITIONS.stream().filter(value -> inPool(value, clamped)).toList();
	}
	public static List<AnomalyDefinition> weightedPool(int stage, Set<String> recentIds, boolean strongAllowed) {
		return weightedPool(stage, recentIds, strongAllowed, List.of());
	}

	/**
	 * The draw pool, optionally biased toward the entries a player's own profile makes apt.
	 *
	 * <p>{@code flavoured} adds weight, and that is all it is allowed to do. It cannot put an anomaly
	 * into a pool the stage did not already open, cannot take one out, and cannot change how often the
	 * pool is drawn from or how hard what comes out hits - so the profile decides which of several
	 * equally intense things arrives, never how much arrives. That distinction is the whole reason a
	 * first-minute question about how the player feels is allowed to be read at all: anything that
	 * scaled would be a difficulty setting chosen before the player knew what it meant, and one they
	 * could never revisit.
	 *
	 * <p>Additive rather than multiplicative so the existing freshness rule stays the louder of the
	 * two: an entry new to this stage still outweighs an old one the profile happens to favour.
	 */
	public static List<AnomalyDefinition> weightedPool(int stage, Set<String> recentIds,
			boolean strongAllowed, List<String> flavoured) {
		int clamped = Math.clamp(stage, 0, 5);
		Set<String> excluded = recentIds == null ? Set.of() : recentIds;
		List<AnomalyDefinition> base = pool(clamped).stream()
				.filter(value -> strongAllowed || !value.strong())
				.filter(value -> !excluded.contains(value.id()))
				.toList();
		if (base.isEmpty() && !excluded.isEmpty()) {
			base = pool(clamped).stream().filter(value -> strongAllowed || !value.strong()).toList();
		}
		java.util.ArrayList<AnomalyDefinition> weighted = new java.util.ArrayList<>();
		for (AnomalyDefinition definition : base) {
			int weight = definition.tier() == clamped ? 3 : 1;
			if (flavoured != null && flavoured.contains(definition.id())) weight += 2;
			for (int index = 0; index < weight; index++) weighted.add(definition);
		}
		return List.copyOf(weighted);
	}
	/** Bit position in {@code ANOMALY_SEEN_MASK}, or -1; see {@link #MASK_ORDER}. */
	public static int indexOf(String id) {
		return MASK_ORDER.indexOf(id);
	}
	private static boolean inPool(AnomalyDefinition definition, int stage) {
		return switch (stage) {
			case 1 -> definition.tier() == 1;
			case 2 -> definition.tier() == 1 || definition.tier() == 2;
			case 3 -> definition.tier() == 2 || definition.tier() == 3;
			// local_rule_collapse is named here for the same reason two entries are named in stage
			// 5. The merge gave it luminance_fault's tier - the earlier of the two, so the darkness
			// is not pushed out of the middle game - and it still has to carry what the tier-3
			// local_rule_collapse did, which stage 4 is half of. Naming it keeps the merged entry
			// reachable across the union of the two stage ranges it inherited rather than the half
			// its own tier grants.
			case 4 -> definition.tier() == 3 || definition.tier() == 4
					|| definition.id().equals("local_rule_collapse");
			case 5 -> definition.tier() >= 4
					|| definition.id().equals("experience_gap")
					|| definition.id().equals("local_rule_collapse");
			default -> false;
		};
	}
}
