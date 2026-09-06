package com.xm.thefourthfrequency.unrendered;

import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;

/**
 * The catalogue identity of the unrendered layer, and the clocks the session runs on.
 *
 * <p>Kept here rather than spread across {@code AnomalyCatalog}, {@code AnomalyTiming} and the
 * session service, because these three numbers only make sense read together: the entity arrives one
 * minute in, and the timeout has to leave enough of the remaining time for being hunted to be
 * survivable rather than merely long.
 */
public final class UnrenderedAnomaly {
	public static final String ID = "unrendered_layer";

	/**
	 * The hard ceiling on a stay, not the intended length.
	 *
	 * <p>Almost every session should end at a hole in the floor or at the entity, both of which come
	 * sooner. Six minutes is what is left for somebody who found neither, and it is deliberately long
	 * enough to stop feeling like an event with a duration - which is the point of the place - while
	 * still being a number that exists, because a floor plan with no way out and no clock is a
	 * softlock however good it looks.
	 */
	public static final int DURATION_TICKS = 20 * 60 * 6;

	/**
	 * How long the player has the layer to themselves before the entity is placed.
	 *
	 * <p>One minute, and the order matters more than the number. Arriving to something already
	 * hunting makes the layer a combat encounter; arriving to nothing at all, walking far enough to
	 * accept that it is empty, and only then being given a reason to look behind is the whole shape
	 * of the event.
	 */
	public static final int ENTITY_DELAY_TICKS = 20 * 60;

	/**
	 * How long a player is left alone after one of these, matching a pursuit exactly.
	 *
	 * <p>The layer is ranked with the pursuit rather than with the anomalies it is catalogued
	 * alongside, and this is what that ranking is made of. A stage-five anomaly may fire every few
	 * minutes, which is right for four seconds of a window flickering and absurd for six minutes of
	 * being somewhere else - drawn from the ordinary pool it competed with {@code window_pulse} for
	 * the same slot on the same terms. Borrowed verbatim from
	 * {@code PursuitProgressPolicy.MIN/MAX_CHASE_GAP_TICKS} rather than restated, so the two ranks
	 * cannot drift apart into "about the same".
	 */
	public static long gapTicks(long seed) {
		long span = PursuitProgressPolicy.MAX_CHASE_GAP_TICKS - PursuitProgressPolicy.MIN_CHASE_GAP_TICKS;
		return PursuitProgressPolicy.MIN_CHASE_GAP_TICKS + Math.floorMod(seed, span + 1);
	}

	private UnrenderedAnomaly() {
	}
}
