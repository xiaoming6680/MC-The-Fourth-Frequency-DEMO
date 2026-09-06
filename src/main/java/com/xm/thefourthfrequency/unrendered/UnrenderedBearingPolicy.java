package com.xm.thefourthfrequency.unrendered;

/**
 * Turns a direction into the coarsest thing the terminal is willing to say about it.
 *
 * <p>Eight sectors, no distance, and <b>never a wrong one</b>. Vagueness here is entirely a matter
 * of resolution, which matters because the alternative was tempting and is forbidden: an instrument
 * that is occasionally, quietly wrong would be a much better scare and would break the one rule the
 * terminal exists to keep - actionable information may fail loudly, it may never turn into a
 * believable wrong value. A player who stops trusting the readout has lost the only stable thing in
 * the mod.
 *
 * <p><b>Stated relative to where the player is looking, not to the compass.</b> A cardinal bearing
 * is a fact about the world that has to be converted before it can be acted on, and down here there
 * is nothing to convert it against: no sun, no landmark, no map, and a floor plan with no north.
 * "Ahead and to the left" is a thing a player can simply walk; "north-west" is a thing they have to
 * work out first, in a place whose whole design is that working things out is hard.
 *
 * <p>That is also why the server sends an angle rather than a sector. The sector has to be recomputed
 * against the player's own facing every frame, so bucketing on the server would round twice - once
 * into a compass point and again into a relative one - and the answer would visibly lag the mouse.
 */
public final class UnrenderedBearingPolicy {
	/**
	 * Clockwise from straight ahead. Indexes into the language keys of the same order.
	 *
	 * <p>Eight is the resolution that survives being wrong about your own facing by a few degrees
	 * while still narrowing a floor plan to a direction. Four would make every answer a shrug;
	 * sixteen would imply a precision the instrument does not have.
	 */
	public static final String[] RELATIVE_KEYS = {
			"ahead", "ahead_right", "right", "behind_right",
			"behind", "behind_left", "left", "ahead_left" };

	private static final double SECTOR_DEGREES = 360.0D / 8.0D;

	private UnrenderedBearingPolicy() {
	}

	/**
	 * Absolute bearing to a target, in degrees clockwise from north, using Minecraft's axes.
	 *
	 * <p>-Z is north and +X is east. Getting that backwards is the one error that would be
	 * self-consistent, survive every other check, and send every player in exactly the wrong
	 * direction.
	 *
	 * <p>Returns -1 when the target is close enough that a direction would be noise rather than
	 * information - standing on top of something is not a bearing, and pointing at your own feet
	 * reads as the instrument being broken.
	 */
	public static int absoluteDegrees(int deltaX, int deltaZ) {
		if (deltaX * deltaX + deltaZ * deltaZ < MINIMUM_RANGE * MINIMUM_RANGE) return -1;
		double degrees = Math.toDegrees(Math.atan2(deltaX, -deltaZ));
		return (int) Math.round(Math.floorMod((long) Math.round(degrees), 360L));
	}

	/**
	 * Sector index for an absolute bearing seen by a player facing {@code yaw}.
	 *
	 * <p>Minecraft's yaw is zero facing south and increases clockwise, so it is the same frame as
	 * {@link #absoluteDegrees} rotated by half a turn - hence the 180. The extra half-sector before
	 * the divide makes each name cover the wedge <em>centred</em> on it rather than the wedge
	 * starting at it; the off-by-half version passes every straight-ahead test and is wrong across
	 * most of the circle.
	 */
	public static int relativeSector(int absoluteDegrees, float playerYaw) {
		if (absoluteDegrees < 0) return -1;
		double relative = absoluteDegrees - (playerYaw + 180.0D);
		double shifted = relative + SECTOR_DEGREES / 2.0D;
		return (int) Math.floorMod((long) Math.floor(shifted / SECTOR_DEGREES), (long) RELATIVE_KEYS.length);
	}

	/**
	 * Closer than this and the terminal says nothing.
	 *
	 * <p>An exit region is fifteen blocks across, so inside twenty the player is either looking at it
	 * or standing in the wall of it. Either way the bearing has stopped being the useful part.
	 */
	public static final int MINIMUM_RANGE = 20;
}
