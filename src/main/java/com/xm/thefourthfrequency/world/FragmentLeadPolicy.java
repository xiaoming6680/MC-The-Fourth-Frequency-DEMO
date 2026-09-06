package com.xm.thefourthfrequency.world;

/**
 * When the terminal picks up a lead, how far it can hear, and when it stops being fussy about what
 * kind of place it is listening for.
 *
 * <p>Pure and directly unit-testable, like the other {@code *Policy} classes: the runtime service
 * owns the chunks, the registry and the clock, and asks this for every number.</p>
 *
 * <h2>Why leads come from exploring rather than from the station</h2>
 *
 * <p>They used to be worked out once, at the start, by searching outward from Relay Station Zero with
 * {@code findNearestMapStructure} over 256 chunks. Three things were wrong with that and they were all
 * the same thing: the terminal was <em>looking</em> rather than <em>listening</em>. It knew four
 * coordinates before the player had left the building, some of them four thousand blocks away; the
 * coordinate it knew was {@code getLocatePos}, the corner of a structure's starting chunk rather than
 * the structure; and none of it had anything to do with where the player actually went.</p>
 *
 * <p>A lead is now something overheard while walking around: the service reads the structure
 * references already recorded in the chunks loaded around the player, which costs a map lookup per
 * chunk and no search at all, and hands back the real {@code StructureStart}. That is both cheaper and
 * more accurate than the old search - the position stored is the structure's own centre, so the
 * navigator points at the thing rather than at a chunk corner with Y pinned to zero.</p>
 *
 * <p>Loaded chunks reach out to the view distance, so this is not a diary of places the player has
 * already been standing in. A mineshaft two hundred blocks away through solid stone is in a loaded
 * chunk and has never been seen, which is exactly the lead worth having.</p>
 */
public final class FragmentLeadPolicy {
	/**
	 * How far out, in chunks, a scan reads structure references.
	 *
	 * <p>Inside the ordinary server view distance on purpose. Anything further is not loaded, and the
	 * one thing this must never do is ask for a chunk that is not there - that would generate terrain
	 * ahead of the player, at a cost with no ceiling, to answer a question nobody asked.</p>
	 */
	public static final int SCAN_CHUNK_RADIUS = 8;

	/**
	 * Shortest and longest gap between two scans for one player.
	 *
	 * <p>Randomised rather than fixed because the terminal is a receiver, not a radar. A signal that
	 * arrives on a predictable clock stops being a signal and becomes a status bar; one that arrives
	 * while the player is halfway up a hill doing something else is the thing that makes the device
	 * feel like it is listening on its own.</p>
	 */
	public static final int MIN_SCAN_INTERVAL_TICKS = 1_200;
	public static final int MAX_SCAN_INTERVAL_TICKS = 3_600;

	/**
	 * The same gap, for a player who has nothing to go on yet.
	 *
	 * <p>One to three minutes is the right cadence for a device that occasionally notices something.
	 * It is the wrong cadence for the stretch before the Nether, when the player has no lead at all
	 * and is covering ground fast: at a sprint they cross five hundred blocks between two scans, so
	 * the eight-chunk window is not sweeping their route, it is sampling four points along it. Whole
	 * villages go past in the gaps, and the terminal - which is meant to be the thing that helps -
	 * says nothing for the entire first act.
	 *
	 * <p>So the interval is a function of whether the player is being served. With no open lead it
	 * runs at twenty to forty-five seconds, which actually overlaps the windows of a moving player;
	 * once they have somewhere to go it backs off to the ordinary rate, because a second lead
	 * arriving while they walk to the first one is not help, it is a queue.
	 *
	 * <p>Costed deliberately: a scan reads structure references from already-loaded chunks and the
	 * scheduler still services at most one player every ten ticks, so the ceiling on server work is
	 * unchanged - what changes is how that fixed budget is shared out, in favour of the players who
	 * have nothing.
	 */
	public static final int UNSERVED_MIN_SCAN_INTERVAL_TICKS = 400;
	public static final int UNSERVED_MAX_SCAN_INTERVAL_TICKS = 900;

	/**
	 * How many scans a fragment may spend finding nothing in its own pool before anything will do.
	 *
	 * <p>Each fragment is associated with four kinds of place, which is what makes the four hidden
	 * files feel like they come from four kinds of somewhere. But the pool is a preference, not a
	 * requirement the world is obliged to satisfy: a player who settles in an ocean, or a flat world,
	 * or simply an unlucky stretch of terrain, can go a very long time without ever loading a chunk
	 * containing one of a given fragment's four groups. Left strict, that fragment's hidden file is
	 * unobtainable and the complete journal - which is gated behind all four - is locked for the life
	 * of the save.
	 *
	 * <p>So patience runs out. After this many fruitless scans <em>for that fragment</em>, the nearest
	 * structure of any kind can carry it. The counter is per fragment and persisted, so it survives a
	 * restart and a fragment that is doing fine never spends another fragment's patience.</p>
	 */
	public static final int POOL_PATIENCE_SCANS = 6;

	private FragmentLeadPolicy() {
	}

	/**
	 * Ticks until this player's next scan.
	 *
	 * @param roll uniform 0..1. Clamped, so a caller handing over a raw random of any range cannot
	 *             produce an interval outside the two bounds
	 */
	/**
	 * Ticks until this player's next scan.
	 *
	 * @param roll uniform 0..1. Clamped, so a caller handing over a raw random of any range cannot
	 *             produce an interval outside the two bounds
	 * @param hasOpenLead whether the player already has somewhere the terminal has pointed them
	 */
	public static int scanIntervalTicks(double roll, boolean hasOpenLead) {
		double clamped = Math.clamp(roll, 0.0D, 1.0D);
		int minimum = hasOpenLead ? MIN_SCAN_INTERVAL_TICKS : UNSERVED_MIN_SCAN_INTERVAL_TICKS;
		int maximum = hasOpenLead ? MAX_SCAN_INTERVAL_TICKS : UNSERVED_MAX_SCAN_INTERVAL_TICKS;
		return minimum + (int) Math.round(clamped * (maximum - minimum));
	}

	/**
	 * Whether this fragment still insists on its own four groups.
	 *
	 * @param scansWithoutLead scans this fragment has spent with nothing in its pool nearby
	 */
	public static boolean poolStillBinding(int scansWithoutLead) {
		return scansWithoutLead < POOL_PATIENCE_SCANS;
	}
}
