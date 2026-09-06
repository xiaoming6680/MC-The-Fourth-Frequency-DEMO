package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.world.phys.Vec3;

/**
 * What each of the three bodies does differently with the same two weapons.
 *
 * <p>The forms used to differ in health, size, damage and how often they acted, and in nothing about
 * the attacks themselves: the same lance landed in the same circle at the same rate whether it was
 * thrown by the embryo or by the interface. That made the escalation a matter of arithmetic - more
 * of the same, more often - and the last phase felt like the first phase with a smaller health bar.
 *
 * <p>So the two weapons a player spends the whole fight reading now change shape between forms:
 *
 * <ul>
 *   <li><b>First form: unchanged.</b> It is the one configuration the fight was tuned against, and
 *       it is what teaches both attacks. Every number here returns the old constant for it.</li>
 *   <li><b>Second form:</b> the lance widens, the beam walks its contact across the floor faster,
 *       and the beam follows its target more tightly.</li>
 *   <li><b>Third form:</b> the lance widens again, the beam keeps the second form's rate, the beam
 *       splits in two, and both halves are drawn and burn at nearly twice the first form's width.</li>
 * </ul>
 *
 * <p><b>The lock readout is never taken away.</b> It was, briefly, for the third form's two aimed
 * weapons - the idea being that losing the countdown was an escalation the arena would have to
 * carry instead. It was reverted at the user's request on 2026-08-29: at the point in the fight
 * where two beams and a twenty-block lance circle are on screen at once, the crosshair readout is
 * the only thing telling a player which of them is theirs.
 *
 * <p>Pure and keyed on the form ordinal, so every value is directly testable.
 */
public final class WorldInterfacePhasePressure {
	/**
	 * How much wider the lance's circle is per form.
	 *
	 * <p>Multiplied against {@link #LANCE_BASE_RADIUS} rather than stacked on top of the generic
	 * {@code formRadius} scale, so the lance's size has exactly one source. At the third form the
	 * marked circle is about twenty-one blocks across, against a telegraph of ninety ticks - four
	 * and a half seconds, which at sprint speed is a little over twice what is needed to leave it.
	 * Tight enough to demand the move, loose enough that the move exists.
	 */
	private static final double[] LANCE_RADIUS_SCALE = {1.0D, 1.8D, 2.9D};
	/** The first form's lance radius, in blocks. */
	public static final double LANCE_BASE_RADIUS = 3.6D;

	/**
	 * Ticks between two damage applications along the sweeping beam, per form.
	 *
	 * <p>This is what "the beam hits the ground faster" actually means: the contact point is drawn
	 * every tick either way, and what changes is how often standing in it costs something.
	 */
	private static final int[] LASER_BURN_INTERVAL = {5, 3, 3};
	/**
	 * Ticks between two scars burned into the floor by the sweeping beam, per form.
	 *
	 * <p>The visible half of the same escalation. Doubling the rate doubles the terrain spend, which
	 * the arena's own permanent-edit budget already caps; past that ceiling the beam simply stops
	 * marking the floor rather than the encounter misbehaving.
	 */
	private static final int[] LASER_SCAR_INTERVAL = {2, 1, 1};
	/**
	 * How far behind its target the beam is drawn, in ticks, per form.
	 *
	 * <p>The dodge window, and the one number in this class that is a nerf to the player rather than
	 * a widening of an area. Ten ticks of running covers about two and a half blocks, which is
	 * comfortably more than the beam's own burn radius - that margin is what makes running work at
	 * all. Six ticks covers about one and a half, which is under it: from the second form on,
	 * running in a straight line no longer breaks the lock and the player has to turn.
	 */
	private static final int[] LASER_TRACKING_LAG = {WorldInterfaceProtocol.LASER_TRACKING_LAG_TICKS, 6, 6};

	/**
	 * How wide the beam is drawn and how far from its axis it burns, per form.
	 *
	 * <p>One scale for both, because they are the same statement: a beam that looks twice as thick
	 * and burns at the old radius is a lie the player finds out about by standing in the visible
	 * part of it and taking nothing. The renderer multiplies its shell widths by this and the
	 * server multiplies its burn radius by it, so the drawn edge and the dangerous edge stay the
	 * same edge.
	 */
	private static final double[] BEAM_SCALE = {1.0D, 1.45D, 1.9D};
	/** The first form's burn radius, in blocks. */
	public static final double LASER_BASE_BURN_RADIUS = 2.2D;
	/**
	 * How many beams the sweep fires at once, per form.
	 *
	 * <p>The third form splits. Two beams at nine tenths of the pair's angle apart sweep as a V that
	 * opens across the island, so the covered ground grows without either half becoming a wall - and
	 * the gap between them is a real place to stand, which is what keeps the split an escalation
	 * rather than a wider unavoidable bar.
	 */
	private static final int[] BEAM_COUNT = {1, 1, 2};
	/** Half-angle between the split beams, in radians. About six degrees each side of the aim. */
	private static final double BEAM_SPLIT_HALF_ANGLE = 0.105D;

	private WorldInterfacePhasePressure() {
	}

	/** Multiplier on both the drawn width and the burn radius of the sweeping beam. */
	public static double beamScale(int form) {
		return BEAM_SCALE[clamp(form)];
	}

	/** How far from its axis the sweeping beam burns, in blocks. */
	public static double laserBurnRadius(int form) {
		return LASER_BASE_BURN_RADIUS * beamScale(form);
	}

	/** How many beams the sweep fires at once. */
	public static int laserBeamCount(int form) {
		return BEAM_COUNT[clamp(form)];
	}

	/** One central mouth in forms 1/2, the two flanking mouths in form 3. */
	public static int laserHead(int form, int beam) {
		return laserBeamCount(form) == 1 ? 0 : 1 + Math.clamp(beam, 0, 1);
	}

	/**
	 * The yaw this beam is rotated by around the core, in radians.
	 *
	 * <p>Symmetric about the aim and centred on zero, so a single beam is exactly the old one and a
	 * pair straddles the point the old one would have hit. Both ends of the split are derived from
	 * the same aim, so the server and the renderer agree without anything being sent.
	 */
	public static double laserBeamYawOffset(int form, int index) {
		int count = laserBeamCount(form);
		if (count <= 1 || index <= 0 && count == 1) return 0.0D;
		int clampedIndex = Math.clamp(index, 0, count - 1);
		// Spread evenly across [-half, +half]; with two beams that is exactly the two ends.
		return -BEAM_SPLIT_HALF_ANGLE + 2.0D * BEAM_SPLIT_HALF_ANGLE * clampedIndex / (count - 1);
	}

	/** The radius the lance marks and damages, in blocks. */
	public static double lanceRadius(int form) {
		return LANCE_BASE_RADIUS * LANCE_RADIUS_SCALE[clamp(form)];
	}

	/** Ticks between two damage applications along the sweeping beam. */
	public static int laserBurnIntervalTicks(int form) {
		return LASER_BURN_INTERVAL[clamp(form)];
	}

	/** Ticks between two scars burned into the floor by the sweeping beam. */
	public static int laserScarIntervalTicks(int form) {
		return LASER_SCAR_INTERVAL[clamp(form)];
	}

	/** How far behind its target the beam is drawn, in ticks. */
	public static int laserTrackingLagTicks(int form) {
		return LASER_TRACKING_LAG[clamp(form)];
	}

	/**
	 * Swings {@code point} around the vertical axis through {@code origin}.
	 *
	 * <p>Shared by the server and the renderer so a split beam's two halves are derived rather than
	 * sent: both sides start from the same aim point and apply the same offsets, which is the same
	 * "derive the beats, notify the blasts" rule the rest of the encounter's presentation follows.
	 * Height is untouched - the split fans across the ground, it does not tilt.
	 */
	public static Vec3 swingAroundY(Vec3 origin, Vec3 point, double radians) {
		if (radians == 0.0D || origin == null || point == null) return point;
		double dx = point.x - origin.x;
		double dz = point.z - origin.z;
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3(origin.x + dx * cos - dz * sin, point.y, origin.z + dx * sin + dz * cos);
	}

	private static int clamp(int form) {
		return Math.clamp(form, WorldInterfaceEntity.FORM_LISTENING, WorldInterfaceEntity.FORM_INTERFACE);
	}
}
