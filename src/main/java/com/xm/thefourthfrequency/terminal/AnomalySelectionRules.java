package com.xm.thefourthfrequency.terminal;

/** Minecraft-free bounded selection helpers. */
public final class AnomalySelectionRules {
	private AnomalySelectionRules() { }

	public static int doorCount(int candidates, long seed) {
		if (candidates < 2) return 0;
		return Math.min(candidates, 6);
	}

	public static boolean caveLike(boolean directSky, int skyLight, int enclosedDirections) {
		return !directSky && skyLight <= 4 && enclosedDirections >= 4;
	}

	/**
	 * How many living mobs within range make "the animals all turn to look at you" a thing that
	 * happened.
	 *
	 * <p>Two, not one. The anomaly is a room full of heads turning in unison, and one sheep facing
	 * the player is what sheep do; a single-mob reading of it is indistinguishable from the world
	 * behaving normally, which is the one thing an anomaly may never be.
	 */
	public static final int ALIGNMENT_MINIMUM_MOBS = 2;
	/**
	 * How many occupied inventory slots the misread needs before it has something to misread.
	 *
	 * <p>Four, because the selection takes up to two slots per visual row and a player carrying one
	 * item gets a single eye in a corner of a screen they may not open. Below this the anomaly is a
	 * cost paid out of the interval for something nobody can be shown.
	 */
	public static final int MISREAD_MINIMUM_ITEMS = 4;
	/** How long after their last real action a player still counts as someone with a past. */
	public static final long ACTIVITY_WINDOW_TICKS = 100L;
	/** Horizontal blocks between two one-second samples that count as having moved. */
	public static final double MOVEMENT_SAMPLE_BLOCKS = 1.5D;

	/**
	 * Whether a replay would show anything.
	 *
	 * <p>{@code action_echo} plays back the last three seconds of the player: position, pose, swing,
	 * the block they were breaking. Recorded from someone standing still it is a motionless copy of
	 * a motionless player, which is not a quieter version of the anomaly - it is the anomaly not
	 * happening, charged at a full interval and a catalogue slot.
	 */
	public static boolean recentlyActive(long lastActiveTick, long now, long windowTicks) {
		return lastActiveTick > 0L && now - lastActiveTick <= windowTicks;
	}

	/** Whether one sample-to-sample step counts as movement. Horizontal only: falling is not acting. */
	public static boolean sampleMoved(double horizontalDistance) {
		return horizontalDistance >= MOVEMENT_SAMPLE_BLOCKS;
	}

	/**
	 * Night as the story already counts it in StoryProgressService: full dark, excluding the sunset
	 * and sunrise ramps where the sky still does the lighting.
	 */
	public static boolean night(long dayTime) {
		long day = Math.floorMod(dayTime, 24_000L);
		return day >= 13_000L && day <= 23_000L;
	}
}
