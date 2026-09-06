package com.xm.thefourthfrequency.terminal;

/** Canonical active presentation durations. Persistent client traces are deliberately excluded. */
public final class AnomalyTiming {
	private AnomalyTiming() { }

	public static int durationTicks(String id, long seed) {
		return switch (id) {
			// Two acts, not one, which is what the extra four seconds buy. The merged anomaly used
			// to open with the crack already in the wall and then alternate digging and footsteps at
			// random, so the one thing the pair actually describes - somebody walking up to a wall
			// and starting to dig through it - was the one reading it never gave. The first two
			// fifths are now the approach and nothing is in the wall yet; the crack opens on the
			// first blow. The digging act alone therefore still runs about as long as the whole
			// anomaly used to, which is the length the player needs to hear it, find it and look at
			// it - the approach is added in front of that rather than taken out of it.
			case "phantom_echo" -> 280 + Math.floorMod((int) seed, 121);
			case "light_dropout" -> 100 + Math.floorMod((int) (seed >>> 8), 301);
			case "peripheral_residue" -> 240;
			case "watcher_alignment", "dark_watcher" -> 400;
			case "action_echo" -> 80;
			case "viewpoint_separation" -> 100;
			case "door_cascade" -> 80;
			case "organ_misread" -> 240;
			case "experience_gap" -> 100;
			// The ceiling on a darkness that normally ends long before it, not the intended length.
			// The unsolved lighting is released section by section as the player disturbs the world,
			// which is the exit almost everyone takes within seconds; this is what is left for
			// somebody crossing open water with nothing to mine, and thirty to forty seconds is long
			// enough to be endured and short enough that enduring it is never the plan. The missing
			// textures in the same volume are not on this clock at all - they are the half that does
			// not come back.
			case "local_rule_collapse" -> 600 + Math.floorMod((int) (seed >>> 40), 201);
			// One minute. It was forty seconds, which is not long enough for a sky anomaly to be
			// looked at twice - the point of this one is that the player checks the terminal, finds
			// the horizon channel already climbing, and then has to keep standing under it.
			case "red_horizon" -> 20 * 60;
			case "window_pulse" -> 80;
			case "desktop_presence" -> 160;
			case "channel_override" -> 300;
			// Sustained anomalies run for minutes, not seconds. They are meant to be lived
			// through and doubted rather than witnessed, so they are deliberately an order of
			// magnitude longer than everything above.
			case "silent_world" -> 2_400 + Math.floorMod((int) (seed >>> 16), 1_201);
			// The longer of the two drifts it was merged from, because it now carries both channels:
			// a reference frame that is off has to stay off long enough to be checked twice.
			case "metric_drift" -> 3_600 + Math.floorMod((int) (seed >>> 24), 2_401);
			// A ceiling rather than a length: the session almost always ends at a hole in the floor
			// or at the entity, and this is what is left for somebody who found neither. Fixed, not
			// seeded - the one number a player could otherwise learn to wait out.
			case "unrendered_layer" -> com.xm.thefourthfrequency.unrendered.UnrenderedAnomaly.DURATION_TICKS;
			default -> throw new IllegalArgumentException("Unknown anomaly timing: " + id);
		};
	}
}
