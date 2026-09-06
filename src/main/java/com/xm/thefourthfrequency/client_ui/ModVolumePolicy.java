package com.xm.thefourthfrequency.client_ui;

/**
 * The numbers behind the mod-volume slider, kept away from the screen that draws it.
 *
 * <p>The slider hands out a continuous 0..1 from wherever the mouse happens to be; a stored volume
 * has to be something a player can read back off the panel and recognise in the config file. These
 * two rules - snap to a detent, report as whole percent - are what turn one into the other, and they
 * have to agree: a value that displays as 80% and persists as 0.7973 would make every later reading
 * of the file look like the setting had drifted on its own.
 */
public final class ModVolumePolicy {
	/** Twenty detents plus silence. Fine enough to find a level, coarse enough to land on one twice. */
	public static final double STEP = 0.05D;
	/** Matches {@code ModConfig.Meta.defaults()}; the page opens on whatever the file already says. */
	public static final double DEFAULT = 0.8D;

	private ModVolumePolicy() {
	}

	/** Clamps into range and lands on the nearest detent, so 0 and 1 stay exactly reachable. */
	public static double snap(double value) {
		if (!Double.isFinite(value)) return DEFAULT;
		double clamped = Math.clamp(value, 0.0D, 1.0D);
		return Math.clamp(Math.round(clamped / STEP) * STEP, 0.0D, 1.0D);
	}

	/** The number shown on the slider. Rounded from the snapped value, never from the raw one. */
	public static int percent(double value) {
		return (int) Math.round(snap(value) * 100.0D);
	}

	/**
	 * Whether the mod is silent at this setting.
	 *
	 * <p>Read off the snapped value rather than compared against zero directly: a slider dragged to
	 * the far left arrives as something like 3e-17, which is silent in every practical sense and
	 * would otherwise be labelled "0%" while claiming not to be muted.
	 */
	public static boolean muted(double value) {
		return snap(value) <= 0.0D;
	}
}
