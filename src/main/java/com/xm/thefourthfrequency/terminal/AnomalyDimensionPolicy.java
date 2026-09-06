package com.xm.thefourthfrequency.terminal;

/**
 * Which worlds the ambient director runs in, and how hard.
 *
 * <p>Replaces the old dimension grace, which was a ninety-second floor applied every time the
 * player's dimension differed from the one on their record. As a rule about doorways it was
 * reasonable; as a rule about <em>travel</em> it was a starvation path. Anyone hub-hopping through
 * the Nether crosses a portal every minute or two, and each crossing pushed the next anomaly to
 * "now plus ninety seconds" again, so the clock could never run out - the player travelling the
 * most saw the fewest anomalies, which is the exact opposite of what the pacing intends.
 *
 * <p>Three modes rather than a grace:
 *
 * <ul>
 *   <li>{@link Mode#NORMAL} - the overworld, the paced default.
 *   <li>{@link Mode#PRESSURE} - the Nether, which gets its own shorter interval. The place the
 *       mainline sends the player to is also where the world should feel least willing to hold
 *       still, and it is short enough per visit that the overworld cadence spends most of a trip
 *       waiting.
 *   <li>{@link Mode#EXCLUDED} - the End and everything else, where nothing is drawn at all. The
 *       End is the finale's own stage and ambient pressure there is noise across the one part of
 *       the run that is supposed to read clearly; third-party dimensions are excluded because this
 *       mod cannot make any claim about what is normal in them.
 * </ul>
 *
 * <p>An excluded dimension freezes the schedule rather than spending it: see {@link
 * #frozenRemaining} and {@link #thawedNext}. Time spent in the End is neither charged to the
 * player nor banked into an anomaly waiting on the doormat when they come back.
 */
public final class AnomalyDimensionPolicy {
	public static final String OVERWORLD_ID = "minecraft:overworld";
	public static final String NETHER_ID = "minecraft:the_nether";

	public enum Mode { NORMAL, PRESSURE, EXCLUDED }

	private AnomalyDimensionPolicy() { }

	public static Mode mode(String dimensionId) {
		if (OVERWORLD_ID.equals(dimensionId)) return Mode.NORMAL;
		if (NETHER_ID.equals(dimensionId)) return Mode.PRESSURE;
		return Mode.EXCLUDED;
	}

	public static boolean excluded(String dimensionId) { return mode(dimensionId) == Mode.EXCLUDED; }
	public static boolean pressure(String dimensionId) { return mode(dimensionId) == Mode.PRESSURE; }

	/**
	 * What is left of a schedule when the player steps into a dimension that does not run one.
	 *
	 * <p>Zero means "nothing to freeze", and that is deliberately what an unscheduled record
	 * (nextTick 0) yields: zero on the record means "never scheduled", and the opening-interval
	 * branch is the only path that gives a player their shorter first anomaly. Freezing it into a
	 * remainder would spend that.
	 *
	 * <p>An already-expired schedule freezes as one tick rather than as a negative number, so a
	 * player who was overdue when they entered the End is still overdue when they leave it.
	 */
	public static long frozenRemaining(long nextTick, long now) {
		if (nextTick <= 0L) return 0L;
		return Math.max(1L, nextTick - now);
	}

	/** The inverse: the remainder starts running again from the moment they are back. */
	public static long thawedNext(long now, long frozenRemaining) {
		return now + Math.max(1L, frozenRemaining);
	}

	/**
	 * Thawing, but never at the expense of a schedule somebody else wrote while it was frozen.
	 *
	 * <p>A private mirror freezes the clock like any other excluded dimension, and a chase ends by
	 * scheduling the next ordinary anomaly six and a half minutes out. Both are true at the same
	 * moment: the player leaves the mirror carrying a frozen remainder of maybe thirty seconds, and
	 * a fresh deliberate deadline further away. Resuming the remainder on top of that would quietly
	 * undo the post-chase quiet - the same mistake the old graces made in the other direction.
	 *
	 * <p>So the remainder may bring the next anomaly back, never forward.
	 */
	public static long resumedNext(long now, long frozenRemaining, long existingNext) {
		return Math.max(thawedNext(now, frozenRemaining), existingNext);
	}
}
