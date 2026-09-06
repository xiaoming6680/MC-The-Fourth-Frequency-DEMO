package com.xm.thefourthfrequency.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The one place that knows where the interface's parts actually sit in the world.
 *
 * <p>Everything the boss emits — the laser, the anchor tethers, the orb feed, the debris storm —
 * used to start at {@code getEyePosition()}, which is the middle of the collision box: several
 * blocks behind and below the glowing core the player is aiming at. At third form that is an
 * eleven-block error, and the beam visibly left the body out of empty plating.
 *
 * <p>The model bakes the core at a fixed offset, so the offset is published here instead of being
 * guessed independently on each side. Server damage geometry and client beam geometry then agree by
 * construction rather than by coincidence; if the model moves, this file moves with it and both
 * follow.
 */
public final class WorldInterfaceAnatomy {
	/**
	 * Per-form model scale. {@code WorldInterfaceRenderer} reads this rather than restating it.
	 *
	 * <p>The third form ran at sixteen, which put it thirty-three blocks across and some forty tall.
	 * At that size it stopped being a thing in the arena and became the arena's ceiling: it filled
	 * the screen from any distance a player could still see their own feet, and the parts of it a
	 * player could reach were a small fraction of what they were looking at. Twelve keeps it half
	 * again the second form and unmistakably the largest thing in the fight without taking the sky.
	 */
	public static final float[] FORM_SCALE = {5.8F, 10.0F, 12.0F};
	public static final int FORM_COUNT = FORM_SCALE.length;

	/** Vanilla drops every living model by this much before drawing it. */
	private static final double MODEL_ORIGIN_LIFT = 1.501D;
	private static final double UNITS_PER_BLOCK = 16.0D;
	/**
	 * Emission point in model units off the layer root: hover(0,12,0) plus interface_kernel(0,-13,-2).
	 *
	 * <p>The Z was -11 while the model carried a large eye standing well clear of the chest. That eye
	 * is gone - the only eyes are on the three heads now - and the kernel that replaced it is set
	 * <em>into</em> the mass rather than proud of it. Leaving the old offset here left every beam,
	 * tether and orb feed originating from a point some nine model units in front of the body, which
	 * at third form is over six blocks of open air.
	 */
	private static final double CORE_Y_UNITS = -1.0D;
	private static final double CORE_Z_UNITS = -2.0D;
	/** Half-width of the mass alone, tentacles excluded; the storm orbits this. */
	private static final double[] MASS_HALF_WIDTH_UNITS = {9.0D, 13.0D, 16.5D};
	/** Radius of the glowing disc itself, for muzzle bloom and orb feed anchoring. */
	private static final double[] CORE_RADIUS_UNITS = {4.2D, 6.2D, 8.4D};

	/** Limbs {@code WorldInterfaceModel} actually draws per form. The hit proxies stand on these. */
	private static final int[] TENTACLE_COUNT = {4, 6, 10};

	// ---------------------------------------------------------------------------------------
	// The three heads.
	//
	// The storm carries three block skulls on necks that grow out of the mass - a large centre
	// head and two smaller flanking ones - and those, not the body, are what a player standing
	// under it is looking at. Every number below is in model units off the layer root, and is
	// read by both WorldInterfaceModel (to place the bones) and WorldInterfacePartEntity (to
	// place the hit boxes). Neither side restates them, so the head a player can see and the
	// head a player can hit are the same head by construction rather than by maintenance.
	// ---------------------------------------------------------------------------------------

	public static final int HEAD_COUNT = 3;
	/**
	 * Mount points on the mass: {x, y, z} per head. Centre forward and high, flanks set back.
	 *
	 * <p>The flanks were mounted at 8.2, which put their neck roots 3.2 units either side of the
	 * centre line - closer together than the necks themselves are wide, so all three chains left the
	 * body through the same hole. Widening the mount is half of what stops them knotting; the other
	 * half is {@link #MOUNT_ROLL} pointing outward instead of in.
	 */
	private static final double[][] HEAD_MOUNT_UNITS = {
			{0.0D, -19.0D, -3.4D}, {12.5D, -15.6D, 2.2D}, {-12.5D, -15.6D, 2.2D},
	};
	/**
	 * How far each neck reaches from its mount, per form.
	 *
	 * <p>This is the growth, and it is also the melee contract. The body climbs as the storm grows -
	 * see {@link #COMBAT_MASS_CLEARANCE} - so holding the necks at one length would have carried the
	 * heads out of reach exactly as the fight got harder. These are solved backwards instead: each
	 * one is the reach that leaves the centre skull's hit box touching the arena floor at that form,
	 * which is asserted by {@code WorldInterfaceAnatomyTest}. The heads get further from the body at
	 * every stage and stay the same distance from the player.
	 *
	 * <p>Because the head chain is shared across all three forms rather than baked once per form,
	 * lengthening these is the whole of what a morph does to the heads.
	 */
	private static final double[] NECK_REACH_UNITS = {13.0D, 21.7D, 30.0D};

	/**
	 * How far one link of a neck advances, in model units, before the form's stretch is applied.
	 *
	 * <p>{@code WorldInterfaceModel} reads this rather than restating it: it is the {@code 6.4}
	 * segment length times the {@code 0.94} overlap the chain is built with, and both the drawn
	 * skull and the hit box that has to sit on it are derived from it. A chain has two of these -
	 * neck_a to neck_b, and neck_b to the skull.
	 */
	public static final double NECK_LINK_UNITS = 6.016D;

	/**
	 * The {@code yScale} the model puts on {@code neck_a}, per form. This is the growth.
	 *
	 * <p>Authored here rather than derived from {@link #NECK_REACH_UNITS}, because it is solved
	 * against a promise that the reach array cannot express: at every form the centre skull's hit
	 * box has to end up inside a swing from the arena floor - about two and a half, two, and one and
	 * a half blocks up, tightening as the storm climbs. Those three numbers are the melee contract,
	 * {@code WorldInterfaceAnatomyTest} asserts them, and they depend on the body's clearance and on
	 * the form scale as well as on the neck.
	 *
	 * <p>The values were previously {@code reach[form] / reach[0]}, which made the first form's
	 * stretch 1.0 by construction. That is what put the heads out of reach: one unstretched chain is
	 * twelve model units, and the head has to travel about twenty-three to come down to a player.
	 *
	 * <p><b>Re-solved against the raised body.</b> {@link #COMBAT_MASS_CLEARANCE} went up by two, four
	 * and four blocks, and the heads hang off the body - so left alone, these carried the centre skull
	 * up with it and out of every swing. Shortened so the skull lands a little under a swing's ceiling
	 * at each form, which is the same contract as before; what changed is that the head now has three
	 * or four blocks of air under it instead of sitting on the floor with its jaw through the island.
	 *
	 * <p>The necks got <em>longer</em>, not shorter. Raising the body without touching them would have
	 * carried the skulls up with it and ended melee at the second and third forms; lengthening the
	 * chain by roughly what the body gained lets the heads come back down to a swing while the storm
	 * they hang from sits four blocks higher. Solved against the ceiling of a swing rather than the
	 * middle of one, so the drawn skull - and the jaw under it, which is what was actually clipping -
	 * ends up three to four blocks further off the floor than it used to be.
	 */
	private static final double[] NECK_STRETCH = {1.91D, 2.17D, 2.53D};

	/**
	 * Baked pitch on the two neck links, in radians. Straight out of {@code buildHeadChain}.
	 *
	 * <p>The chain does not hang straight down - each link is tipped forward, which is what leans
	 * the heads out over the arena instead of stacking them under the body. It costs a couple of
	 * percent of vertical reach and buys a forward offset, and both have to be in the hit box or the
	 * box floats behind the skull by a third of a block per link at third form.
	 *
	 * <p><b>Leaned further forward than it was</b> (-0.16 / -0.12), and that is a positioning change as
	 * much as a shape one. The storm holds station by standing off from the player, and what has to be
	 * inside a swing is the head - so the head's forward reach is the budget the whole standoff is
	 * spent from. At the old lean the centre skull hung 2.8, 6.2 and 9.3 blocks in front of the body's
	 * own origin while the mass is 3.3, 8.1 and 12.4 blocks wide: the head never actually cleared the
	 * body's leading edge, so the closest the storm could stand while remaining hittable was one where
	 * its underside overhung the player by three blocks at every form. That is the "it is directly
	 * overhead" read, and no standoff could fix it, because moving the body back moved the head back
	 * with it. Reaching further out is what buys the distance.
	 */
	private static final double NECK_A_PITCH = -0.34D;
	private static final double NECK_B_PITCH = -0.29D;
	/**
	 * Baked yaw and roll, per side. Zero on the centre head; this is what splays the flanks.
	 *
	 * <p><b>Both run against the side, not with it, and that sign is the whole bug this file used to
	 * carry.</b> Model space points Y <em>down</em> and the model faces its own -Z, so a chain hanging
	 * off a mount is a vector along +Y leaning toward -Z - and for a vector shaped like that, a
	 * positive roll <em>and</em> a positive yaw both carry it toward -X. Writing {@code side * ROLL}
	 * therefore swung the head mounted at +X across the centre line and the head mounted at -X back
	 * the other way. The two flanking necks crossed each other and the centre one, and the three
	 * skulls ended up sharing the same few units of air: at the second form the flanks were drawn
	 * 0.63 units apart when their own boxes are 7.2 units across, and both sat 2.6 units from the
	 * centre neck's axis. The storm spent the middle of the fight tied in a knot.
	 *
	 * <p>The yaw is the worse of the two, because its contribution scales with how far forward the
	 * neck is leaning: a clip that pitches the necks out toward horizontal - the forced expulsion
	 * does exactly that, at forty-six degrees - turns the whole length of the chain into lever arm,
	 * and the flanks met in the middle.
	 *
	 * <p>Read the signed values through {@link #mountYaw}, {@link #mountRoll}, {@link #neckYaw} and
	 * {@link #neckRoll} rather than applying {@code side} at the call site. The model bakes these
	 * into its bind pose, {@code WorldInterfaceRig} walks the same chain to place the hit boxes, and
	 * there is exactly one place the convention can be stated: those four methods.
	 */
	private static final double MOUNT_YAW = 0.30D;
	private static final double MOUNT_ROLL = 0.20D;
	private static final double NECK_A_YAW = 0.10D;
	private static final double NECK_A_ROLL = 0.10D;
	private static final double NECK_B_YAW = 0.12D;
	private static final double NECK_B_ROLL = 0.07D;
	/** Half-width of {@code neck_a}'s box in model units, before the form scale. */
	private static final double NECK_HALF_UNITS = 2.5D;
	/** A neck column is this much wider than the drawn neck: it is thin, and it moves. */
	private static final double NECK_HIT_SLACK = 1.6D;

	/** Skull size relative to the centre head. The flanks are deliberately smaller. */
	private static final double[] HEAD_SCALE = {1.0D, 0.78D, 0.78D};
	/** Centre-head skull half-extent in model units, before {@link #HEAD_SCALE}. */
	private static final double SKULL_HALF_UNITS = 4.6D;
	/**
	 * How far the necks splay outward as they extend, per form. The flanking heads swing away from
	 * the centre line as they grow, which is what stops the three reading as one wide head.
	 */
	private static final double[] HEAD_SPLAY = {0.10D, 0.34D, 0.62D};
	/**
	 * How much of each neck's reach is spent going down rather than out.
	 *
	 * <p>The centre head hangs; the flanks are carried high and back. This is what keeps one skull
	 * inside a melee swing at every form - see {@link #headOrigin} - and it is also the read: three
	 * heads at three heights is an animal, three heads in a row is a totem.
	 *
	 * <p>The gap between the centre and the flanks has to be wide enough to overcome their mount
	 * offsets. The centre head is mounted 3.4 units higher up the mass, so at a short first-form
	 * neck a smaller gap left the flanks hanging <em>below</em> the head they are supposed to be
	 * framing.
	 */
	private static final double[] HEAD_DROP = {0.92D, 0.55D, 0.55D};
	/** Where the limbs leave the body: {@code buildTendrils} hangs them off body(12) at -4. */
	private static final double TENTACLE_ROOT_UNITS = 8.0D;
	/**
	 * How far down a limb is assumed to reach, in model units off the layer root.
	 *
	 * <p><b>An upper bound for ranges, not a measurement.</b> It reads as the sum of the three link
	 * lengths hanging straight down, and the chain does not hang straight down - it curls, and every
	 * link is pitched further forward than the last, so the drawn tip actually stops near 29 units.
	 * Nothing places geometry from this any more: {@code WorldInterfaceRig} walks the real bone chain
	 * for that, and the difference is why the limb hit boxes used to be clamped to the arena floor
	 * around tips that were still ten blocks over a player's head.
	 *
	 * <p>What is left reading it is {@link #tentacleDrop}, and the two callers of that want a
	 * generous reach for particles and for the "is anyone near the limbs" query. Overstating it there
	 * is safe in the direction that matters; understating it would not be.
	 */
	private static final double TENTACLE_TIP_UNITS = 44.5D;
	/** How far off the body axis the limbs hang. */
	private static final double TENTACLE_RADIUS_UNITS = 11.5D;
	/** Half-thickness of a limb's first link. */
	private static final double[] TENTACLE_THICKNESS_UNITS = {0.85D, 1.25D, 1.75D};
	/** A hit column is this much wider than the limb it stands on; see {@link #tentacleHitWidth}. */
	private static final double TENTACLE_HIT_SLACK = 2.5D;
	/**
	 * Blocks between a hunted player's feet and the underside of the drawn mass, per form.
	 *
	 * <p>This used to be stated as the altitude of the entity's own origin, which is also where the
	 * collision box started - and the drawn mass does not start there. It floats {@link
	 * #massBottomLift} blocks higher, eight and a half of them at third form, so the box claimed a
	 * storey of empty air under the body and the body itself sat that far above everything a player
	 * could see themselves hitting.</p>
	 *
	 * <p>Stated against the mass instead, the number means what it looks like: how far over your
	 * head the thing hanging in the sky actually hangs.</p>
	 *
	 * <p>No form keeps the body inside a melee swing any more, and that is the design rather than a
	 * regression: the storm flies. What a player on the ground swings at is the head that comes down
	 * to them, the neck carrying it and the limbs the storm is anchored by - see {@link #headOffset},
	 * {@link #neckSegmentOffset} and {@link #tentacleHitBottom}. The body is a target for anything
	 * ranged and for nothing else.
	 *
	 * <p><b>Raised at every form, and by four blocks at the second and third.</b> The clearance is
	 * stated against the underside of the <em>mass</em>, and the heads hang well below that - the
	 * centre skull is nearly six blocks across at the second form and almost seven at the third, so
	 * a head whose centre sat two blocks over the floor had its lower half inside the island. The
	 * storm read as resting on the ground rather than hanging over it, and the one part of it a
	 * player is meant to aim at was half buried. Lifting the whole body carries the necks and skulls
	 * with it, which is what puts the drawn head back in the air; the melee contract in
	 * {@code WorldInterfaceAnatomyTest} still holds because what has to stay inside a swing is the
	 * <em>bottom</em> of that box, and the bottom is exactly what was underground.
	 */
	private static final double[] COMBAT_MASS_CLEARANCE = {8.0D, 14.0D, 18.0D};

	/**
	 * Where the drawn shell actually ends, in model units off the layer root. Y points down, so the
	 * bottom is the larger number.
	 *
	 * <p>These replace an assumption that was quietly wrong at every form. {@link #massBottomLift}
	 * used to derive the underside as {@code coreLift - massRadius}: the kernel's own height, minus
	 * the mass's <em>half-width</em>. Neither term is the bottom of anything. The kernel sits high in
	 * the shell rather than at its centre, and the storm is a stack of slabs half again as tall as it
	 * is wide, so the result overstated the clearance under the body by five to six and a half
	 * blocks - and because the entity is placed at {@code clearance - massBottomLift}, every one of
	 * those blocks went into the floor. The body spent the whole fight partly buried, and the summon
	 * descent, which lands on the same number, drove it further in.
	 *
	 * <p>Read off {@code WorldInterfaceModel.createLayer}: the {@code hover} bone sits at +12 and
	 * every shell builder is stated against it. The bottom is the lowest slab of {@code base_mass}
	 * (top -21, 14 steps of 2.3, half-height 1.426) at all three forms, except at the third where
	 * {@code p3_plate} (top -29, span 40, half-height 1.344) reaches further. The top is the highest
	 * plating of whichever accretion layers that form draws. The trailing roots hang below the
	 * bottom on purpose and are deliberately excluded: they are torn wisps, and measuring clearance
	 * to them would push the whole storm up by their length for no gain.
	 */
	private static final double[] MASS_BOTTOM_UNITS = {22.4D, 22.4D, 24.4D};
	private static final double[] MASS_TOP_UNITS = {-10.6D, -15.0D, -18.3D};

	private WorldInterfaceAnatomy() {
	}

	public static int tentacleCount(int form) {
		return TENTACLE_COUNT[Math.clamp(form, 0, FORM_COUNT - 1)];
	}

	/** Blocks between the entity position and the tentacle tips. Always positive: the limbs hang. */
	public static double tentacleDrop(int form) {
		return formScale(form) * (TENTACLE_TIP_UNITS / UNITS_PER_BLOCK - MODEL_ORIGIN_LIFT);
	}

	/** Blocks between the entity position and where the limbs leave the body. */
	public static double tentacleRootLift(int form) {
		return formScale(form) * (MODEL_ORIGIN_LIFT - TENTACLE_ROOT_UNITS / UNITS_PER_BLOCK);
	}

	public static double tentacleRadius(int form) {
		return formScale(form) * TENTACLE_RADIUS_UNITS / UNITS_PER_BLOCK;
	}

	/**
	 * Width of a limb's hit column, deliberately wider than the drawn limb. The chain curls and
	 * swings as it descends, so a column matched to the bare thickness would be a moving needle.
	 */
	public static double tentacleHitWidth(int form) {
		return Math.max(2.0D, formScale(form) * TENTACLE_THICKNESS_UNITS[Math.clamp(form, 0, FORM_COUNT - 1)]
				* 2.0D / UNITS_PER_BLOCK * TENTACLE_HIT_SLACK);
	}

	/**
	 * Offset from a hunted player's feet to the interface's own origin.
	 *
	 * <p>Derived from {@link #COMBAT_MASS_CLEARANCE} rather than stated, so the clearance a designer
	 * reads is the clearance the player sees. Negative at every form above the first: the origin is
	 * a bookkeeping point under the arena floor and the body it carries is overhead.</p>
	 */
	public static double combatHoverHeight(int form) {
		return COMBAT_MASS_CLEARANCE[Math.clamp(form, 0, FORM_COUNT - 1)] - massBottomLift(form);
	}

	public static float formScale(int form) {
		return FORM_SCALE[Math.clamp(form, 0, FORM_COUNT - 1)];
	}

	/** Server-side core position, using the boss's settled body facing. */
	public static Vec3 coreOrigin(WorldInterfaceEntity boss) {
		return coreOrigin(boss.position(), boss.form(), boss.yBodyRot);
	}

	/** Server emission and damage share the same animated mouth socket. */
	public static Vec3 mouthOrigin(WorldInterfaceEntity boss, int head) {
		return boss.position().add(rotate(boss.rigPose().mouthOffset(head), boss.yBodyRot));
	}

	/** Charge glow fits inside the cheek spacing instead of using the much larger torso core. */
	public static double mouthRadius(int form, int head) {
		return formScale(form) * WorldInterfaceRig.headScale(head) * 3.4D / UNITS_PER_BLOCK;
	}

	/** Interpolated presentation of that socket, using the model's own pose inputs. */
	public static Vec3 mouthOrigin(WorldInterfaceEntity boss, int head, float partialTick) {
		long age = (long) (Math.max(0.0D,
				boss.level().getGameTime() - boss.actionStartTick() + partialTick) * 50.0D);
		WorldInterfaceRig.Pose pose = WorldInterfaceRig.pose(boss.form(), boss.poseClock() + partialTick,
				boss.healthFraction(), boss.actionId(), age,
				boss.renderGazeYaw(partialTick), boss.renderGazePitch(partialTick));
		return boss.getPosition(partialTick).add(rotate(pose.mouthOffset(head),
				Mth.rotLerp(partialTick, boss.yBodyRotO, boss.yBodyRot)));
	}

	/**
	 * Core position for an arbitrary foot position and body facing. The model faces its own -Z, so
	 * the forward term rides the body yaw rather than the head yaw: the core is part of the torso and
	 * does not swing when the interface merely looks somewhere.
	 */
	public static Vec3 coreOrigin(Vec3 feet, int form, float bodyYawDegrees) {
		float scale = formScale(form);
		double lift = coreLift(form);
		double forward = scale * (-CORE_Z_UNITS / UNITS_PER_BLOCK);
		float radians = bodyYawDegrees * Mth.DEG_TO_RAD;
		return new Vec3(feet.x - Mth.sin(radians) * forward, feet.y + lift,
				feet.z + Mth.cos(radians) * forward);
	}

	/** Radius of the glowing disc in blocks. */
	public static double coreRadius(int form) {
		return formScale(form) * CORE_RADIUS_UNITS[Math.clamp(form, 0, FORM_COUNT - 1)] / UNITS_PER_BLOCK;
	}

	/** Blocks between the entity position and the centre of the drawn mass. */
	public static double coreLift(int form) {
		return formScale(form) * (MODEL_ORIGIN_LIFT - CORE_Y_UNITS / UNITS_PER_BLOCK);
	}

	/**
	 * Width of the collision box, in blocks: the drawn mass, and nothing else.
	 *
	 * <p>The box used to be three hand-written numbers that had drifted badly out of step with the
	 * model — 10 blocks against a sixteen-block-wide second form, 16 against a thirty-three-block
	 * third form. Half the interface a player could see was not there to hit, and shots that
	 * visibly connected passed through it. Derived from the same half-widths the debris storm
	 * orbits, so the box and the silhouette can no longer disagree.</p>
	 */
	public static float hitboxWidth(int form) {
		return (float) (massRadius(form) * 2.0D);
	}

	/**
	 * Height of the collision box, in blocks: the drawn mass, top to bottom, and nothing else.
	 *
	 * <p>The box used to grow upward from the entity position, which meant it also swallowed the
	 * {@link #massBottomLift} blocks of empty air between that position and the underside of the
	 * body. {@link WorldInterfaceEntity} lifts the box off the position by exactly that much, so what
	 * is left here is the body's own extent. The limbs hanging below are covered by their own hit
	 * proxies rather than by this.</p>
	 *
	 * <p>Measured between {@link #MASS_TOP_UNITS} and {@link #MASS_BOTTOM_UNITS} rather than assumed
	 * equal to the width. The storm is a stack of slabs and is roughly half again as tall as it is
	 * across, so treating it as a cube left the upper third of a body a player could plainly see
	 * unhittable by anything ranged.</p>
	 */
	public static float hitboxHeight(int form) {
		int clamped = Math.clamp(form, 0, FORM_COUNT - 1);
		return (float) (formScale(form)
				* (MASS_BOTTOM_UNITS[clamped] - MASS_TOP_UNITS[clamped]) / UNITS_PER_BLOCK);
	}

	/**
	 * Blocks between the entity position and the underside of the drawn mass.
	 *
	 * <p>The vertical offset the collision box is lifted by, and the term that turns a designed
	 * clearance into an actual altitude. See {@link #MASS_BOTTOM_UNITS} for what it used to be
	 * derived from and why that put the body underground.</p>
	 */
	public static double massBottomLift(int form) {
		return formScale(form)
				* (MODEL_ORIGIN_LIFT - MASS_BOTTOM_UNITS[Math.clamp(form, 0, FORM_COUNT - 1)] / UNITS_PER_BLOCK);
	}

	/**
	 * How far in front of a hunted player the storm holds its own origin, in blocks.
	 *
	 * <p>Solved from the <em>head</em> rather than from the body, which is the whole change. The
	 * standoff used to be "one body radius plus a swing", so the mass's leading edge sat exactly three
	 * blocks from the player at every form - a body twenty-five blocks across, eighteen blocks up,
	 * with its underside starting three blocks in front of you. It read as being stood on.
	 *
	 * <p>But the body is not what a player on the ground swings at. The centre head is, and the head
	 * hangs off the front of the body - so the distance that actually has to be right is from the
	 * player to the head's hit box, and everything else follows from it. Stated that way the body ends
	 * up wherever the neck's reach puts it, which is what {@link #NECK_A_PITCH} was leaned forward to
	 * make somewhere sensible: past the leading edge, so the storm hangs over its own head rather than
	 * over the player.
	 */
	public static double combatStandoff(int form) {
		return headOffset(form, 0).z + headHitRadius(form, 0) + HEAD_FACE_GAP;
	}

	/**
	 * Blocks between the player's own column and the near face of the centre head's hit box.
	 *
	 * <p>One, so the head is unambiguously inside a swing without being inside the player. The reach
	 * itself is three, and the remaining two are what a player spends stepping around a head that is
	 * also moving.
	 */
	private static final double HEAD_FACE_GAP = 1.0D;

	/** Half-width of the mass in blocks, excluding tentacles. */
	public static double massRadius(int form) {
		return formScale(form) * MASS_HALF_WIDTH_UNITS[Math.clamp(form, 0, FORM_COUNT - 1)]
				/ UNITS_PER_BLOCK;
	}

	// ---------------------------------------------------------------------------------------
	// Head and limb geometry, shared by the model and the hit proxies.
	// ---------------------------------------------------------------------------------------

	/** Head bones the model draws per form. Always three: the silhouette is built on them. */
	public static int headCount(int form) {
		return HEAD_COUNT;
	}

	/** Which way head {@code index} leans: nothing for the centre head, mirrored for the flanks. */
	public static float headSide(int index) {
		int head = Math.clamp(index, 0, HEAD_COUNT - 1);
		return head == 0 ? 0.0F : (head == 1 ? 1.0F : -1.0F);
	}

	/**
	 * Signed yaw the model bakes onto head {@code index}'s mount, in radians.
	 *
	 * <p>Published rather than restated in the model, because the sign is not something either side
	 * can be trusted to work out again - see {@link #MOUNT_ROLL}.
	 */
	public static float mountYaw(int index) {
		return (float) (-headSide(index) * MOUNT_YAW);
	}

	/** Signed roll on head {@code index}'s mount. Negative against the side: the flanks splay out. */
	public static float mountRoll(int index) {
		return (float) (-headSide(index) * MOUNT_ROLL);
	}

	/** Baked pitch on neck link {@code link}, where 0 is {@code neck_a} and 1 is {@code neck_b}. */
	public static float neckPitch(int link) {
		return (float) (link == 0 ? NECK_A_PITCH : NECK_B_PITCH);
	}

	/** Signed yaw on one neck link of head {@code index}, running outward like the mount's. */
	public static float neckYaw(int index, int link) {
		return (float) (-headSide(index) * (link == 0 ? NECK_A_YAW : NECK_B_YAW));
	}

	/** Signed roll on one neck link of head {@code index}, running outward like the mount's. */
	public static float neckRoll(int index, int link) {
		return (float) (-headSide(index) * (link == 0 ? NECK_A_ROLL : NECK_B_ROLL));
	}

	/** Hit proxies carried by each neck. Two is enough to answer along the whole chain. */
	public static final int NECK_SEGMENTS_PER_HEAD = 2;

	/**
	 * Damageable parts standing on visible geometry, per form: the mass itself, three heads, the two
	 * proxies on each of their necks, and one per drawn limb. Fourteen, sixteen and twenty.
	 *
	 * <p>The necks are new here. They are the longest thing the storm lowers into reach and they
	 * were not a damage surface at all, so a player swinging at the one part of the boss that had
	 * come down to meet them hit nothing.
	 */
	public static int hitPartCount(int form) {
		return 1 + HEAD_COUNT + HEAD_COUNT * NECK_SEGMENTS_PER_HEAD + tentacleCount(form);
	}

	/**
	 * Where head {@code index} sits, in model units off the layer root.
	 *
	 * <p>{@code WorldInterfaceModel} positions the bone chain with this and the proxies box it with
	 * the same call, which is what makes the heads hittable exactly where they are drawn. The Y
	 * axis points down, as everywhere else in model space.
	 */
	public static double[] headLocalUnits(int form, int index) {
		int clamped = Math.clamp(form, 0, FORM_COUNT - 1);
		int head = Math.clamp(index, 0, HEAD_COUNT - 1);
		double[] mount = HEAD_MOUNT_UNITS[head];
		double reach = NECK_REACH_UNITS[clamped];
		double side = head == 0 ? 0.0D : (head == 1 ? 1.0D : -1.0D);
		return new double[]{
				mount[0] + side * reach * HEAD_SPLAY[clamped],
				mount[1] + reach * HEAD_DROP[head],
				// Necks lean forward as they extend, so the heads end up over the arena rather
				// than over the middle of the body.
				mount[2] - reach * 0.42D,
		};
	}

	/**
	 * Positive scale the renderer puts on {@code neck_a} to lengthen the shared chain for a form.
	 *
	 * @see #NECK_STRETCH
	 */
	public static float neckLengthScale(int form) {
		return (float) NECK_STRETCH[Math.clamp(form, 0, FORM_COUNT - 1)];
	}

	/** Half-extent of head {@code index} in blocks. */
	public static double headRadius(int form, int index) {
		return formScale(form) * SKULL_HALF_UNITS * HEAD_SCALE[Math.clamp(index, 0, HEAD_COUNT - 1)]
				/ UNITS_PER_BLOCK;
	}

	/**
	 * A head's hit column is this much wider than the drawn skull.
	 *
	 * <p>Published rather than left private to the proxy, because the melee contract is stated
	 * against the box a player can actually swing at and not against the cube the renderer draws.
	 * While the number lived only in {@code WorldInterfacePartEntity} the geometry tests measured the
	 * bare skull, which is a fifth smaller - so they were pinning a promise a quarter of a block
	 * stricter than the fight keeps, and a body raise failed them before it failed the player.
	 *
	 * <p>Widened from a quarter to nearly a half, which is what lets the box be anchored low enough to
	 * sit on the jaw without uncovering the brow at the top. A head is a moving target on a chain that
	 * swings, and the two ends of it are the parts a player is trying to hit: the jaw hanging into
	 * their face, and the crown when they get above it. A box tight enough to describe only the
	 * cranium leaves both of those answering to nothing.
	 */
	public static final double HEAD_HIT_SLACK = 1.45D;

	/** Half-extent of head {@code index}'s hit box in blocks: the drawn skull plus its slack. */
	public static double headHitRadius(int form, int index) {
		return headRadius(form, index) * HEAD_HIT_SLACK;
	}

	/**
	 * The interface standing perfectly still: form scale, bind pose, neck growth, nothing else.
	 *
	 * <p>Every design contract this file states - what clears the floor, what stays inside a melee
	 * swing, whether the three heads pass each other - is a statement about the storm's shape, and
	 * this is what that shape is. It is one evaluation of {@link WorldInterfaceRig}, not a second
	 * implementation of it: the live proxies pose the same skeleton with the live clock, the live
	 * pool and the live action, so a change to the rig moves the boxes and moves these numbers
	 * together.
	 *
	 * <p>Cached because the resting pose cannot change; a lost race only recomputes an identical
	 * value, and nothing mutates a pose after {@link WorldInterfaceRig#restPose} returns it.
	 */
	private static final WorldInterfaceRig.Pose[] REST_POSES = new WorldInterfaceRig.Pose[FORM_COUNT];

	private static WorldInterfaceRig.Pose rest(int form) {
		int clamped = Math.clamp(form, 0, FORM_COUNT - 1);
		WorldInterfaceRig.Pose cached = REST_POSES[clamped];
		if (cached == null) {
			cached = WorldInterfaceRig.restPose(clamped);
			REST_POSES[clamped] = cached;
		}
		return cached;
	}

	/** Offset from the entity position to the centre of head {@code index} at rest, in blocks. */
	public static Vec3 headOffset(int form, int index) {
		return rest(form).headOffset(index);
	}

	/**
	 * Offset from the entity position to the centre of one neck segment at rest, in blocks.
	 *
	 * <p>The necks carry the heads down to the player and were, until recently, decoration: three
	 * chains tens of blocks long that a sword passed straight through. One proxy per drawn neck bone
	 * is enough to make the whole length answer without turning a thin moving limb into a wall, and
	 * anchoring each on the midpoint of the bone it stands for - rather than on a fraction of the
	 * straight line from root to skull - is what keeps them on the chain when a clip bends it.
	 */
	public static Vec3 neckSegmentOffset(int form, int index, int segment) {
		return neckSegmentOffset(rest(form), index, segment);
	}

	/** The same, for an arbitrary posed skeleton. Segment 0 is {@code neck_a}, 1 is {@code neck_b}. */
	public static Vec3 neckSegmentOffset(WorldInterfaceRig.Pose pose, int index, int segment) {
		int joint = Math.clamp(segment, 0, NECK_SEGMENTS_PER_HEAD - 1);
		return pose.neckJointOffset(index, joint).add(pose.neckJointOffset(index, joint + 1))
				.scale(0.5D);
	}

	/** Half-width of a neck's hit column in blocks, deliberately wider than the drawn neck. */
	public static double neckSegmentRadius(int form, int index) {
		return formScale(form) * NECK_HALF_UNITS * HEAD_SCALE[Math.clamp(index, 0, HEAD_COUNT - 1)]
				* NECK_HIT_SLACK / UNITS_PER_BLOCK;
	}

	/** Height of one neck segment's hit box at rest: the bone it stands on, with a little overlap. */
	public static double neckSegmentHeight(int form, int index) {
		return neckSegmentHeight(rest(form), index, 0);
	}

	/** Length of one neck bone in a posed skeleton, with the overlap the boxes are given. */
	public static double neckSegmentHeight(WorldInterfaceRig.Pose pose, int index, int segment) {
		int joint = Math.clamp(segment, 0, NECK_SEGMENTS_PER_HEAD - 1);
		return Math.max(1.5D, pose.neckJointOffset(index, joint)
				.distanceTo(pose.neckJointOffset(index, joint + 1)) * 1.15D);
	}

	/**
	 * Rotates a body-local offset onto the boss's facing. The model faces its own -Z.
	 *
	 * <p>Public because the hit proxies apply it to live posed offsets from {@link WorldInterfaceRig}
	 * rather than to a stored resting one. It used to be wrapped in a family of {@code ...Origin}
	 * helpers that each took a resting offset and a yaw; those are gone, because a resting offset is
	 * exactly what the boxes must no longer be placed from.
	 */
	public static Vec3 rotate(Vec3 offset, float bodyYawDegrees) {
		float radians = bodyYawDegrees * Mth.DEG_TO_RAD;
		double sin = Mth.sin(radians);
		double cos = Mth.cos(radians);
		// -Z forward: a local +Z moves the part backwards along the facing.
		return new Vec3(offset.x * cos - offset.z * sin, offset.y,
				offset.x * sin + offset.z * cos);
	}
}
