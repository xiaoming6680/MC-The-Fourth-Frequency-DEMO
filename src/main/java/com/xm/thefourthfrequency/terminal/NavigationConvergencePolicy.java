package com.xm.thefourthfrequency.terminal;

import net.minecraft.core.BlockPos;

/**
 * How precisely the terminal is willing to state a location, given how much it has to go on.
 *
 * <p>Shared by every target the terminal cannot simply know outright. The rule is the same in each
 * case and it is the opposite of a switch: a reading does not flip from "somewhere over there" to a
 * waypoint the moment a threshold is crossed. It narrows. The player watches the same sentence get
 * more specific, which is both the honest presentation of an instrument accumulating evidence and
 * the thing that makes gathering more of it feel like progress rather than like waiting for a gate.
 *
 * <p><b>Why spread and not just count.</b> The stronghold is found the way it is found in vanilla -
 * by throwing an eye and watching where it goes - and two throws from the same spot are one
 * observation recorded twice. Real triangulation needs a baseline: the further apart the two
 * observations, the tighter the crossing. Counting throws alone would let a player stand still,
 * spam four eyes and be handed a coordinate, which is both wrong about how the instrument works and
 * strictly worse as play. So the policy reads the widest gap between observations, and a second
 * throw taken a few steps from the first genuinely buys almost nothing - which is exactly what the
 * hint should be telling them to fix.
 */
public final class NavigationConvergencePolicy {
	/**
	 * How the terminal is currently able to speak about a target.
	 *
	 * <p>Ordered from vaguest to sharpest, and the wording each level maps to escalates with it.
	 * {@code UNKNOWN} is not "no data" - it is "a direction and nothing else", which is what a single
	 * observation honestly supports.
	 */
	public enum Level {
		/** One observation: a bearing, no useful range. */
		BEARING,
		/** Observations that agree but share almost the same vantage: bearing plus a wide band. */
		COARSE,
		/** A real baseline: bearing plus a band a player can act on. */
		NARROW,
		/** Enough crossings that the remaining error is smaller than the structure itself. */
		RESOLVED
	}

	/**
	 * The blur applied to a known position before it is shown.
	 *
	 * @param angleStepDegrees the bearing is reported to the nearest multiple of this
	 * @param uncertaintyBlocks the distance is reported as a band this wide either side
	 */
	public record Precision(Level level, double angleStepDegrees, int uncertaintyBlocks) {
	}

	/**
	 * The shortest baseline that counts as a genuinely different vantage point.
	 *
	 * <p>Below this, two throws are the same observation twice: the angle between the lines they
	 * describe is too small to narrow anything, so the policy declines to pretend otherwise.
	 */
	public static final int MEANINGFUL_BASELINE_BLOCKS = 96;

	/** The baseline past which extra separation stops buying much, so the curve flattens. */
	public static final int SATURATED_BASELINE_BLOCKS = 640;

	private NavigationConvergencePolicy() {
	}

	/**
	 * @param observations how many separate readings have been taken
	 * @param spreadBlocks the widest gap between any two of them, which is the triangulation baseline
	 */
	public static Precision precision(int observations, int spreadBlocks) {
		if (observations <= 0) return new Precision(Level.BEARING, 45.0D, 4_096);
		if (observations == 1) return new Precision(Level.BEARING, 45.0D, 1_024);
		double baseline = Math.clamp(spreadBlocks, 0, SATURATED_BASELINE_BLOCKS);
		if (baseline < MEANINGFUL_BASELINE_BLOCKS) {
			// Repeated observations from one place. They confirm the bearing and nothing else, so the
			// angle tightens a little and the range does not.
			return new Precision(Level.COARSE, 22.5D, 768);
		}
		// Past the baseline the band closes smoothly with both the separation and the count, so the
		// third throw from a new place is a visible improvement rather than a threshold being met.
		double reach = (baseline - MEANINGFUL_BASELINE_BLOCKS)
				/ (double) (SATURATED_BASELINE_BLOCKS - MEANINGFUL_BASELINE_BLOCKS);
		double extra = Math.min(1.0D, (observations - 2) / 2.0D);
		double quality = Math.clamp(reach * 0.7D + extra * 0.3D, 0.0D, 1.0D);
		int uncertainty = (int) Math.round(512 - quality * 480);
		double angle = 22.5D - quality * 17.5D;
		return new Precision(levelForUncertainty(uncertainty), angle, uncertainty);
	}

	/** Band at or under which the remaining error is smaller than the thing being looked for. */
	public static final int RESOLVED_UNCERTAINTY_BLOCKS = 160;

	/**
	 * The level a band of this width represents.
	 *
	 * <p>The level is derived from the uncertainty rather than decided alongside it, so the number
	 * the player is shown and the sentence they are told about it cannot disagree. It also lets the
	 * client name the level from the distance band it already receives, which is why the hint needs
	 * no protocol field of its own.
	 */
	public static Level levelForUncertainty(int uncertaintyBlocks) {
		if (uncertaintyBlocks >= 1_024) return Level.BEARING;
		if (uncertaintyBlocks >= 768) return Level.COARSE;
		if (uncertaintyBlocks > RESOLVED_UNCERTAINTY_BLOCKS) return Level.NARROW;
		return Level.RESOLVED;
	}

	/**
	 * The widest gap between any two observations, which is the baseline they triangulate over.
	 *
	 * @param packed observation positions, as {@code BlockPos.asLong()} values
	 */
	public static int spreadBlocks(long[] packed) {
		if (packed == null || packed.length < 2) return 0;
		double widest = 0.0D;
		for (int first = 0; first < packed.length; first++) {
			BlockPos one = BlockPos.of(packed[first]);
			for (int second = first + 1; second < packed.length; second++) {
				BlockPos other = BlockPos.of(packed[second]);
				double dx = one.getX() - other.getX();
				double dz = one.getZ() - other.getZ();
				widest = Math.max(widest, Math.sqrt(dx * dx + dz * dz));
			}
		}
		return (int) Math.round(widest);
	}

	/**
	 * What the terminal should ask for next, or null once there is nothing worth asking for.
	 *
	 * <p>The hint is the half of this that makes it a mechanic rather than a number quietly getting
	 * smaller. A player who has thrown three eyes from the same doorway needs to be told that the
	 * problem is where they are standing, not how many they have left.
	 */
	public static String hintId(Level level) {
		return switch (level) {
			case BEARING -> "another_throw";
			case COARSE -> "different_vantage";
			case NARROW -> "one_more_crossing";
			case RESOLVED -> null;
		};
	}

}
