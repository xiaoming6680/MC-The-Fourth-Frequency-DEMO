package com.xm.thefourthfrequency.terminal;

public final class TerminalNavigationMath {
	private TerminalNavigationMath() { }

	public static boolean navigable(int targetKind, boolean toolsDisabled, boolean located, boolean sameDimension) {
		return targetKind > 0 && !toolsDisabled && located && sameDimension;
	}

	/**
	 * Where the player is looking, as a screen angle on a dial whose top is north.
	 *
	 * <p>The dial has fixed 北/东/南/西 labels drawn at fixed screen positions, so the top of it means
	 * north and nothing else. Both needles are therefore absolute bearings.
	 *
	 * <p>They used to be relative ones. This method returned {@code 180 - yaw}, which is where world
	 * north sits <em>relative to the player's facing</em> - correct for a dial whose top means "ahead"
	 * and wrong for the one actually being drawn. The instrument was carrying two coordinate systems
	 * at once: a needle in the player's frame over a rose in the world's. Turning east swung the
	 * needle to 西, because in the old frame that is where north was.
	 *
	 * <p>Minecraft yaw is 0 at south and increases towards west, so a compass bearing is the yaw
	 * turned half a circle.
	 */
	public static double facingNeedleDegrees(float playerYaw) {
		return wrapDegrees(playerYaw + 180.0D);
	}

	/**
	 * Where the target is, on the same north-up dial.
	 *
	 * <p>Absolute for the same reason, and it has to change with {@link #facingNeedleDegrees} rather
	 * than after it: two needles on one face in two different frames is worse than either frame
	 * chosen consistently. Reading it is now the ordinary compass action - turn until the facing
	 * needle lines up with this one.
	 */
	public static double targetNeedleDegrees(int dx, int dz) {
		return wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) + 180.0D);
	}

	public static double interpolateDegrees(double current, double target, double amount) {
		return wrapDegrees(current + wrapDegrees(target - current) * Math.clamp(amount, 0.0D, 1.0D));
	}

	public static double wrapDegrees(double degrees) {
		double wrapped = degrees % 360.0D;
		if (wrapped >= 180.0D) wrapped -= 360.0D;
		if (wrapped < -180.0D) wrapped += 360.0D;
		return wrapped;
	}

	/**
	 * How far the player would have to turn to face the target, negative for left.
	 *
	 * <p>Written as the difference between the two needles rather than as its own formula, because
	 * that is what it is - and because a second independent expression of the same geometry is how
	 * the dial ended up with a needle in one frame and a rose in another in the first place.
	 *
	 * <p>The "ahead / left / right / behind" readouts want this and not the absolute bearing: those
	 * are instructions to the player, and an instruction is relative to where they are looking.
	 */
	public static double targetOffsetDegrees(int dx, int dz, float playerYaw) {
		return wrapDegrees(targetNeedleDegrees(dx, dz) - facingNeedleDegrees(playerYaw));
	}

	public static String direction(int dx, int dz) {
		if (Math.abs(dx) <= Math.max(2, Math.abs(dz) / 2)) return dz >= 0 ? "south" : "north";
		if (Math.abs(dz) <= Math.max(2, Math.abs(dx) / 2)) return dx >= 0 ? "east" : "west";
		return (dz >= 0 ? "south" : "north") + (dx >= 0 ? "east" : "west");
	}

	public static int relativeDirection(int dx, int dz, float playerYaw) {
		double angle = targetOffsetDegrees(dx, dz, playerYaw);
		if (Math.abs(angle) <= 45.0D) return 0;
		if (angle > 45.0D && angle < 135.0D) return 1;
		if (angle < -45.0D && angle > -135.0D) return 3;
		return 2;
	}

	public static String relativeDirectionId(int direction) {
		return switch (Math.clamp(direction, 0, 3)) {
			case 1 -> "right";
			case 2 -> "behind";
			case 3 -> "left";
			default -> "ahead";
		};
	}

	/**
	 * Which of eight sectors around the player's own facing the target sits in, 0 being straight
	 * ahead and counting clockwise.
	 *
	 * <p>Held apart from {@link #relativeDirection} rather than replacing it. That one answers a
	 * four-way question for the structure-arrival hint, where "ahead / left / right / behind" is the
	 * whole of what needs saying; this one is for the HUD readout a player steers by continuously,
	 * where four sectors means a ninety-degree band all reading "ahead" and no way to tell a small
	 * drift from being on course.
	 */
	public static int relativeOctant(int dx, int dz, float playerYaw) {
		double angle = targetOffsetDegrees(dx, dz, playerYaw);
		return Math.floorMod((int) Math.round(angle / 45.0D), 8);
	}

	public static String relativeOctantId(int octant) {
		return switch (Math.floorMod(octant, 8)) {
			case 1 -> "ahead_right";
			case 2 -> "right";
			case 3 -> "behind_right";
			case 4 -> "behind";
			case 5 -> "behind_left";
			case 6 -> "left";
			case 7 -> "ahead_left";
			default -> "ahead";
		};
	}

	public static int distance(int dx, int dz) {
		return (int) Math.round(Math.hypot(dx, dz));
	}

	public static boolean withinHorizontalRadius(int playerX, int playerZ, int targetX, int targetZ, int radius) {
		long dx = (long) targetX - playerX;
		long dz = (long) targetZ - playerZ;
		long safeRadius = Math.max(0, radius);
		return dx * dx + dz * dz <= safeRadius * safeRadius;
	}
}
