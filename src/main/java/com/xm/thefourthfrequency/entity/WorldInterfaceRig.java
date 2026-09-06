package com.xm.thefourthfrequency.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interface's skeleton, posed, on either side of the network.
 *
 * <p><b>What this replaces.</b> The hit boxes used to stand on arithmetic that reproduced the
 * model's <em>bind</em> pose - where each bone would be if nothing were animating it - and that is
 * not where any of them are. Three separate things were moving the drawn bones and none of them
 * reached the boxes:
 *
 * <ul>
 *   <li>the authored clips, which swing the centre head through ninety-six degrees of yaw and drive
 *       the lance a further fifty-eight; at third form that is most of twenty blocks of skull;</li>
 *   <li>the per-tick procedural drift - the hover bob, the tracking wobble, the structural sag that
 *       grows as the pool drains - which never stops and gets worse as the fight goes on;</li>
 *   <li>the limbs, whose proxies orbited the body on a timer that the drawn tentacles, which are
 *       baked at fixed angles, have never once followed.</li>
 * </ul>
 *
 * <p>So the pose is computed here, from the shared clips in {@link WorldInterfaceClips} and one copy
 * of the procedural layer, and both sides read it: {@code WorldInterfacePartEntity} to place the
 * boxes and {@code WorldInterfaceModel} to pose the {@code ModelPart}s it draws. The client no longer
 * runs vanilla's animation system for this entity at all - not because vanilla's is wrong, but
 * because a second implementation is a second answer, and the entire point is that there is one.
 *
 * <p>Every input is something both sides already have: the form, the entity's own tick count, the
 * synchronised health fraction, and the action id and start tick the boss publishes. Nothing here
 * consults the level, allocates per bone per frame beyond one pose, or depends on the render thread.
 */
public final class WorldInterfaceRig {
	/** Vanilla drops every living model by this much before drawing it. */
	public static final double MODEL_ORIGIN_LIFT = 1.501D;
	public static final double UNITS_PER_BLOCK = 16.0D;
	/** The {@code hover} bone's offset inside the layer; every storm-body coordinate is under it. */
	public static final float HOVER_Y = 12.0F;
	public static final int TENDRIL_COUNT = WorldInterfaceClips.TENDRIL_COUNT;
	public static final String[] HEAD_PREFIX = WorldInterfaceClips.HEADS;

	public static final String ROOT = "root";
	public static final String HOVER = "hover";
	public static final String STORM_BODY = "storm_body";
	public static final String KERNEL = "interface_kernel";
	public static final String WEAPON = "weapon";

	/** Every clip telegraphs inside its first two seconds; the charge term tracks that window. */
	public static final long ACTION_CHARGE_MILLIS = 2_000L;
	/** {@code BossAction} MORPH_TO_SECOND / MORPH_TO_THIRD, and the window their pinch runs over. */
	private static final int MORPH_TO_SECOND_ACTION = 11;
	private static final int MORPH_TO_THIRD_ACTION = 12;
	private static final long MORPH_ACTION_MILLIS = 3_000L;
	private static final float MORPH_PINCH = 0.72F;

	/** Ticks of delay between one link of a neck and the next. */
	private static final float NECK_LAG_TICKS = 2.4F;
	private static final float LIMB_LAG_TICKS = 3.2F;

	// --------------------------------------------------------------------------------------
	// Bind pose. These are the numbers the model bakes into its PartDefinitions, and the model
	// reads them from here rather than restating them - the pose and the geometry it is applied
	// to have to be the same skeleton or none of the above is worth anything.
	// --------------------------------------------------------------------------------------

	public static final float KERNEL_Y = -13.0F;
	public static final float KERNEL_Z = -2.0F;
	/** Skull size relative to the centre head. The flanks are deliberately smaller. */
	private static final float[] HEAD_SCALE = {1.0F, 0.78F, 0.78F};
	/** Baked pitch putting the skull's face down toward the arena. */
	public static final float SKULL_PITCH = 0.14F;
	/** Where the jaw hangs under the skull, and the pitch it hangs at. */
	public static final float JAW_LIFT_UNITS = 3.4F;
	public static final float JAW_PITCH = 0.12F;
	/** Half-extent of the drawn skull cube. */
	private static final float SKULL_HALF_UNITS = 4.6F;
	/**
	 * Where the head's hit box is centred, in model units off the skull bone. Y points down.
	 *
	 * <p>This used to be {@code -0.2}, which is the centre of the <em>cranium cube</em> - and the
	 * cranium is not the head. {@code WorldInterfaceModel.buildHeadChain} hangs a jaw off the same
	 * bone at +3.4 that is another 2.8 units deep, so the drawn head reaches +6.2 while a box centred
	 * on the cranium stopped at +5.55. The visible mismatch was at the bottom, which is the end a
	 * player standing under the storm is swinging at: the jaw hanging into their face answered to
	 * nothing, and the air over the brow did.
	 *
	 * <p>Anchored on the <em>jaw</em> rather than on the middle of the drawn head, because the jaw is
	 * the end a player is swinging at. Centring on the drawn head - brow to jaw - was already better
	 * than centring on the cranium, and it still put the reachable bottom of the box most of a block
	 * higher than the thing the player can see hanging over them. The box is a cube grown from
	 * {@link WorldInterfaceAnatomy#HEAD_HIT_SLACK}, which was widened to keep the brow covered at the
	 * top while this drops the bottom onto the jaw.
	 *
	 * <p>The horns are deliberately left out: they are thin spikes reaching another five units up, and
	 * a cube that contained them would be half again as tall as the head for the sake of two prongs.
	 */
	private static final float SKULL_CENTRE_Y_UNITS = 1.45F;
	private static final float TENDRIL_THICKNESS_UNITS = 1.45F;

	private WorldInterfaceRig() {
	}

	public static float headScale(int head) {
		return HEAD_SCALE[Math.clamp(head, 0, WorldInterfaceAnatomy.HEAD_COUNT - 1)];
	}

	/** Distance from one neck bone to the next, in model units, before any stretch. */
	public static float neckLink(int head) {
		return (float) WorldInterfaceAnatomy.NECK_LINK_UNITS * headScale(head);
	}

	/** Which row of the underside a limb hangs off; limbs are paired front to back. */
	public static int tendrilRow(int index) {
		return index / 2;
	}

	public static float tendrilSide(int index) {
		return index % 2 == 0 ? 1.0F : -1.0F;
	}

	public static float tendrilLength(int index) {
		return 11.0F + tendrilRow(index) * 1.2F;
	}

	public static float tendrilThickness() {
		return TENDRIL_THICKNESS_UNITS;
	}

	/** {@code x, y, z, xRot, yRot, zRot} for a limb's root bone, off the storm body. */
	public static float[] tendrilRootPose(int index) {
		int row = tendrilRow(index);
		float side = tendrilSide(index);
		return new float[]{side * (3.6F + row * 0.9F), -4.0F + row * 1.7F, -4.4F + row * 2.8F,
				-0.16F + row * 0.09F, side * (0.12F + row * 0.13F), -side * (0.20F + row * 0.06F)};
	}

	public static float[] tendrilMidPose(int index) {
		float side = tendrilSide(index);
		return new float[]{0.0F, tendrilLength(index) - TENDRIL_THICKNESS_UNITS * 0.5F, 0.0F,
				0.40F, side * 0.16F, -side * 0.16F};
	}

	public static float[] tendrilTipPose(int index) {
		float side = tendrilSide(index);
		float midLength = tendrilLength(index) * 0.88F;
		float midThick = TENDRIL_THICKNESS_UNITS * 0.72F;
		return new float[]{0.0F, midLength - midThick * 0.5F, 0.0F, 0.62F, side * 0.24F, -side * 0.22F};
	}

	/** Length of a limb's last link, which is the stretch its hit box covers. */
	public static float tendrilTipLength(int index) {
		return tendrilLength(index) * 0.76F;
	}

	// --------------------------------------------------------------------------------------
	// Pose
	// --------------------------------------------------------------------------------------

	/** One bone: its bind pose, its parent, and whatever the clips and the drift have done to it. */
	public static final class Bone {
		public final String name;
		final Bone parent;
		private final float bindX;
		private final float bindY;
		private final float bindZ;
		private final float bindXRot;
		private final float bindYRot;
		private final float bindZRot;
		public float x;
		public float y;
		public float z;
		public float xRot;
		public float yRot;
		public float zRot;
		public float xScale = 1.0F;
		public float yScale = 1.0F;
		public float zScale = 1.0F;

		private Bone(String name, Bone parent, float[] pose) {
			this.name = name;
			this.parent = parent;
			bindX = pose[0];
			bindY = pose[1];
			bindZ = pose[2];
			bindXRot = pose[3];
			bindYRot = pose[4];
			bindZRot = pose[5];
			reset();
		}

		void reset() {
			x = bindX;
			y = bindY;
			z = bindZ;
			xRot = bindXRot;
			yRot = bindYRot;
			zRot = bindZRot;
			xScale = yScale = zScale = 1.0F;
		}

		public void offsetPosition(float dx, float dy, float dz) {
			x += dx;
			y += dy;
			z += dz;
		}

		public void offsetRotation(float dx, float dy, float dz) {
			xRot += dx;
			yRot += dy;
			zRot += dz;
		}

		public void offsetScale(float dx, float dy, float dz) {
			xScale += dx;
			yScale += dy;
			zScale += dz;
		}

		/** {@code ModelPart.translateAndRotate}, as a matrix: translate, then ZYX, then scale. */
		void compose(Matrix4f into) {
			into.translate(x / (float) UNITS_PER_BLOCK, y / (float) UNITS_PER_BLOCK,
					z / (float) UNITS_PER_BLOCK);
			if (xRot != 0.0F || yRot != 0.0F || zRot != 0.0F) into.rotateZYX(zRot, yRot, xRot);
			if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) into.scale(xScale, yScale, zScale);
		}
	}

	/**
	 * The whole skeleton at one instant, plus the form it was posed for.
	 *
	 * <p>Bones are stored parent-first, so composing a chain is a walk up the parent links rather
	 * than a traversal. A pose is built once per boss per tick and read by every proxy.
	 */
	public static final class Pose {
		private final Map<String, Bone> bones;
		private final int form;
		private final float renderScale;
		/** Composed transforms, memoised: twenty proxies ask for a dozen bones off one pose. */
		private final Map<String, Matrix4f> transforms = new LinkedHashMap<>(16);

		private Pose(Map<String, Bone> bones, int form, float renderScale) {
			this.bones = bones;
			this.form = form;
			this.renderScale = renderScale;
		}

		public int form() {
			return form;
		}

		/** The scale the renderer is actually drawing at, morph pinch included. */
		public float renderScale() {
			return renderScale;
		}

		public Bone bone(String name) {
			return bones.get(name);
		}

		public Iterable<Bone> bones() {
			return bones.values();
		}

		/**
		 * Model-space transform of a bone, in blocks, relative to the layer root.
		 *
		 * <p>Only valid once the pose is finished - {@code WorldInterfaceRig.pose} returns it that
		 * way and nothing mutates it afterwards, which is what makes the memoisation safe.
		 */
		public Matrix4f transform(String name) {
			Matrix4f cached = transforms.get(name);
			if (cached != null) return cached;
			Bone bone = bones.get(name);
			if (bone == null) throw new IllegalArgumentException("no such bone: " + name);
			List<Bone> chain = new ArrayList<>(6);
			for (Bone cursor = bone; cursor != null; cursor = cursor.parent) chain.add(cursor);
			Matrix4f matrix = new Matrix4f();
			for (int index = chain.size() - 1; index >= 0; index--) chain.get(index).compose(matrix);
			transforms.put(name, matrix);
			return matrix;
		}

		/**
		 * Offset from the entity's position to a point on a bone, in blocks, before the body facing
		 * is applied.
		 *
		 * <p>The three corrections vanilla's renderer applies between a model coordinate and a world
		 * one all live here: the {@link #MODEL_ORIGIN_LIFT} drop, the form scale, and the axis flip.
		 *
		 * <p><b>The Z negation is a fix, not a formality.</b> A living model is drawn under
		 * {@code scale(-1, -1, 1)} and a half turn, which together map the model's own -Z - the way it
		 * faces - onto the entity's forward. The arithmetic this replaces negated Y and left Z alone,
		 * so every head and neck box was placed the same distance <em>behind</em> the storm as the
		 * head it stood for was in front of it: at third form the centre skull is thirteen model units
		 * forward, and its box was sitting nearly twenty blocks out the back.
		 */
		public Vec3 offset(String name, double localX, double localY, double localZ) {
			Vector3f point = transform(name).transformPosition(new Vector3f(
					(float) (localX / UNITS_PER_BLOCK), (float) (localY / UNITS_PER_BLOCK),
					(float) (localZ / UNITS_PER_BLOCK)));
			return new Vec3(renderScale * point.x(), renderScale * (MODEL_ORIGIN_LIFT - point.y()),
					-renderScale * point.z());
		}

		public Vec3 headOffset(int index) {
			int head = Math.clamp(index, 0, WorldInterfaceAnatomy.HEAD_COUNT - 1);
			return offset(HEAD_PREFIX[head] + "_skull", 0.0D,
					SKULL_CENTRE_Y_UNITS * headScale(head), 0.0D);
		}

		/** Where a neck link starts, so a proxy can be hung on the drawn bone rather than near it. */
		public Vec3 neckJointOffset(int index, int joint) {
			int head = Math.clamp(index, 0, WorldInterfaceAnatomy.HEAD_COUNT - 1);
			String bone = switch (Math.clamp(joint, 0, 2)) {
				case 0 -> HEAD_PREFIX[head] + "_neck_a";
				case 1 -> HEAD_PREFIX[head] + "_neck_b";
				default -> HEAD_PREFIX[head] + "_skull";
			};
			return offset(bone, 0.0D, 0.0D, 0.0D);
		}

		/** Both ends of a limb's last link: the stretch that actually reaches the floor. */
		public Vec3 tendrilTipOffset(int index, boolean far) {
			int limb = Math.clamp(index, 0, TENDRIL_COUNT - 1);
			return offset("tendril_" + limb + "_tip", 0.0D, far ? tendrilTipLength(limb) : 0.0D, 0.0D);
		}
	}

	// --------------------------------------------------------------------------------------
	// Construction
	// --------------------------------------------------------------------------------------

	private static Pose skeleton(int form, float renderScale) {
		Map<String, Bone> bones = new LinkedHashMap<>(64);
		Bone root = add(bones, ROOT, null, 0, 0, 0, 0, 0, 0);
		Bone hover = add(bones, HOVER, root, 0, HOVER_Y, 0, 0, 0, 0);
		Bone body = add(bones, STORM_BODY, hover, 0, 0, 0, 0, 0, 0);
		add(bones, KERNEL, body, 0, KERNEL_Y, KERNEL_Z, 0, 0, 0);
		add(bones, WEAPON, body, 6.0F, -7.0F, -2.0F, -0.35F, 0.0F, -0.65F);
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			String prefix = HEAD_PREFIX[head];
			double[] mount = WorldInterfaceAnatomy.headLocalUnits(0, head);
			float scale = headScale(head);
			Bone attachment = add(bones, prefix + "_head_mount", body, 0, 0, 0,
					0.0F, WorldInterfaceAnatomy.mountYaw(head), WorldInterfaceAnatomy.mountRoll(head));
			Bone neckA = add(bones, prefix + "_neck_a", attachment,
					(float) mount[0] * 0.34F, (float) mount[1] * 0.30F, (float) mount[2] * 0.34F,
					WorldInterfaceAnatomy.neckPitch(0), WorldInterfaceAnatomy.neckYaw(head, 0),
					WorldInterfaceAnatomy.neckRoll(head, 0));
			Bone neckB = add(bones, prefix + "_neck_b", neckA, 0, neckLink(head), 0,
					WorldInterfaceAnatomy.neckPitch(1), WorldInterfaceAnatomy.neckYaw(head, 1),
					WorldInterfaceAnatomy.neckRoll(head, 1));
			Bone skull = add(bones, prefix + "_skull", neckB, 0, neckLink(head), 0, SKULL_PITCH, 0, 0);
			add(bones, prefix + "_jaw", skull, 0, JAW_LIFT_UNITS * scale, 0, JAW_PITCH, 0, 0);
		}
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			Bone limb = add(bones, "tendril_" + index, body, tendrilRootPose(index));
			Bone mid = add(bones, "tendril_" + index + "_mid", limb, tendrilMidPose(index));
			add(bones, "tendril_" + index + "_tip", mid, tendrilTipPose(index));
		}
		return new Pose(bones, form, renderScale);
	}

	private static Bone add(Map<String, Bone> bones, String name, Bone parent, float x, float y, float z,
			float xRot, float yRot, float zRot) {
		return add(bones, name, parent, new float[]{x, y, z, xRot, yRot, zRot});
	}

	private static Bone add(Map<String, Bone> bones, String name, Bone parent, float[] pose) {
		Bone bone = new Bone(name, parent, pose);
		if (bones.put(name, bone) != null) throw new IllegalStateException("duplicate bone " + name);
		return bone;
	}

	/**
	 * The interface's pose at one instant, with the heads looking straight ahead.
	 *
	 * @param ageInTicks      the entity's own age; the client passes it with its partial tick
	 * @param healthFraction  1 at full pool, 0 at none - drives how far the shell has come apart
	 * @param actionId        the published wire action, or 0 when idle
	 * @param actionAgeMillis how far into that action the presentation is
	 */
	public static Pose pose(int form, float ageInTicks, float healthFraction, int actionId,
			long actionAgeMillis) {
		return pose(form, ageInTicks, healthFraction, actionId, actionAgeMillis, 0.0F, 0.0F);
	}

	/**
	 * The interface's pose at one instant, including where the three heads are looking.
	 *
	 * @param gazeYaw   bearing of what the storm is watching, in radians, relative to the body's own
	 *                  facing and signed like Minecraft's yaw: positive turns to the boss's right
	 * @param gazePitch elevation of the same, in radians, signed like Minecraft's pitch: positive
	 *                  looks down, which is where the players are
	 */
	public static Pose pose(int form, float ageInTicks, float healthFraction, int actionId,
			long actionAgeMillis, float gazeYaw, float gazePitch) {
		int clamped = Math.clamp(form, 0, WorldInterfaceAnatomy.FORM_COUNT - 1);
		Pose pose = skeleton(clamped, renderScale(clamped, actionId, actionAgeMillis));
		long idleAgeMillis = (long) (ageInTicks * 50.0F);
		for (WorldInterfaceClip clip : WorldInterfaceClips.idleClips()) clip.apply(pose, idleAgeMillis);
		for (WorldInterfaceClip clip : WorldInterfaceClips.clipsForAction(actionId)) {
			clip.apply(pose, actionAgeMillis);
		}
		procedural(pose, clamped, ageInTicks, healthFraction, actionCharge(actionId, actionAgeMillis));
		headGaze(pose, gazeYaw, gazePitch);
		return pose;
	}

	/**
	 * The skeleton as the form shapes it, with no clock and no clip on it.
	 *
	 * <p>Distinct from {@code pose(form, 0, 1, 0, 0)}, and deliberately: at tick zero the looping
	 * idle clips and the tracking drift are already a few degrees into their cycles, and neither is
	 * symmetric - the three heads breathe a third of a cycle apart on purpose, so no two of them ever
	 * reach the same place at the same time. That is right for a body and wrong for a specification.
	 *
	 * <p>What the design contracts are about - does the storm clear the floor, does a head stay inside
	 * a melee swing, do the three necks pass each other - are properties of the shape, so they are
	 * stated against the shape. The neck growth is included because it <em>is</em> the form: a morph
	 * lengthening the necks is not an animation, it is what the second and third forms are.
	 */
	public static Pose restPose(int form) {
		int clamped = Math.clamp(form, 0, WorldInterfaceAnatomy.FORM_COUNT - 1);
		Pose pose = skeleton(clamped, WorldInterfaceAnatomy.formScale(clamped));
		neckGrowth(pose, clamped);
		return pose;
	}

	/**
	 * How long the charge takes to come back off once the telegraph window has passed.
	 *
	 * <p>Without this the term fell off a cliff. It ramped zero to one across
	 * {@link #ACTION_CHARGE_MILLIS} and then, on the very next tick, returned the idle sentinel -
	 * which every consumer reads as zero. {@link #headTracking} turns it into how far the three heads
	 * lean toward what the storm is about to do, so at two seconds into <em>every single action</em>
	 * the entire lean was undone between one frame and the next: at third form that is the skulls
	 * crossing several blocks in fifty milliseconds, on every attack the fight throws. It is the
	 * single largest reason the boss reads as blinking rather than moving, and it is not in any clip
	 * - which is why it survived every pass over the authored animation.
	 *
	 * <p>Nine hundred milliseconds is a little under half the build-up. The heads lean in over two
	 * seconds while the attack is announced and settle back over the following second as it lands,
	 * which is the shape the term was always describing.
	 */
	public static final long ACTION_CHARGE_RELEASE_MILLIS = 900L;

	/**
	 * 0..1 through the telegraph window of the current action, easing back to the -1 idle sentinel
	 * across {@link #ACTION_CHARGE_RELEASE_MILLIS} once that window has passed.
	 *
	 * <p>The sentinel is kept rather than replaced with a plain zero because several consumers
	 * distinguish "no action" from "an action at zero charge", and because the value is published to
	 * the render state. What changed is only that the fall to it is travelled rather than jumped.
	 */
	public static float actionCharge(int actionId, long actionAgeMillis) {
		if (actionId <= 0 || actionAgeMillis < 0L) return -1.0F;
		if (actionAgeMillis <= ACTION_CHARGE_MILLIS) {
			return Mth.clamp(actionAgeMillis / (float) ACTION_CHARGE_MILLIS, 0.0F, 1.0F);
		}
		long since = actionAgeMillis - ACTION_CHARGE_MILLIS;
		if (since >= ACTION_CHARGE_RELEASE_MILLIS) return -1.0F;
		return 1.0F - since / (float) ACTION_CHARGE_RELEASE_MILLIS;
	}

	/**
	 * The scale the storm is drawn at, morph pinch included.
	 *
	 * <p>The server flips the form on one tick, so the renderer pinches the body shut at the midpoint
	 * of a morph and draws the new one back out of it. That is four seconds of the fight during which
	 * the drawn boss is up to seventy-two percent smaller than its form says, and the hit boxes have
	 * to shrink with it or they stand in air around a body that is no longer filling them.
	 */
	public static float renderScale(int form, int actionId, long actionAgeMillis) {
		int clamped = Math.clamp(form, 0, WorldInterfaceAnatomy.FORM_COUNT - 1);
		float scale = WorldInterfaceAnatomy.formScale(clamped);
		if (actionId != MORPH_TO_SECOND_ACTION && actionId != MORPH_TO_THIRD_ACTION) return scale;
		float morph = actionAgeMillis / (float) MORPH_ACTION_MILLIS;
		if (morph < 0.0F || morph > 1.0F) return scale;
		float previous = WorldInterfaceAnatomy.formScale(Math.max(0, clamped - 1));
		float pinch = 1.0F - MORPH_PINCH * Mth.sin(morph * Mth.PI);
		float eased = morph * morph * (3.0F - 2.0F * morph);
		return (previous + (scale - previous) * eased) * pinch;
	}

	// --------------------------------------------------------------------------------------
	// The procedural layer: everything that moves the storm without being keyframed.
	//
	// Authored once, here, and applied by both sides. It used to live in the model's setupAnim,
	// where the server could not reach it, and it is not a small term: the structural sag alone
	// puts a third of a radian into every neck by the time the pool is empty.
	// --------------------------------------------------------------------------------------

	private static void procedural(Pose pose, int form, float ageInTicks, float healthFraction,
			float actionCharge) {
		float formBreath = 0.015F + form * 0.009F;
		Bone hover = pose.bone(HOVER);
		Bone body = pose.bone(STORM_BODY);
		hover.y += Mth.sin(ageInTicks * 0.055F) * (0.35F + form * 0.15F);
		// Assignment, not an offset: this deliberately overrides whatever a clip put on the body's
		// horizontal scale, and reproducing that faithfully matters more than tidying it.
		body.xScale = body.zScale = 1.0F + Mth.sin(ageInTicks * 0.08F) * formBreath;
		neckGrowth(pose, form);
		headTracking(pose, ageInTicks, healthFraction, actionCharge);
		limbFollow(pose, form, ageInTicks);
		structuralDamage(pose, ageInTicks, healthFraction);
	}

	/**
	 * The necks lengthen with the form: the whole of what a morph does to the heads.
	 *
	 * <p>Scale the parent once - {@code neck_b} inherits it, so scaling both links would square the
	 * growth at the skull - and counter-scale the skull so only the two neck segments lengthen.
	 */
	private static void neckGrowth(Pose pose, int form) {
		float stretch = WorldInterfaceAnatomy.neckLengthScale(form);
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			double[] target = WorldInterfaceAnatomy.headLocalUnits(form, head);
			double[] base = WorldInterfaceAnatomy.headLocalUnits(0, head);
			pose.bone(HEAD_PREFIX[head] + "_neck_a").yScale *= stretch;
			pose.bone(HEAD_PREFIX[head] + "_skull").yScale /= stretch;
			Bone mount = pose.bone(HEAD_PREFIX[head] + "_head_mount");
			mount.x += (float) (target[0] - base[0]) * 0.5F;
			mount.z += (float) (target[2] - base[2]) * 0.5F;
		}
	}

	/** Heads converge on what the storm is about to do, each link lagging the one above it. */
	private static void headTracking(Pose pose, float time, float healthFraction, float actionCharge) {
		float reach = actionCharge < 0.0F ? 0.0F : Mth.sin(Math.min(1.0F, actionCharge) * Mth.HALF_PI);
		float wear = 1.0F - Mth.clamp(healthFraction, 0.0F, 1.0F);
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			float phase = head * 2.1F;
			float side = WorldInterfaceAnatomy.headSide(head);
			float mountPitch = Mth.sin(time * 0.062F + phase) * 0.10F;
			float neckPitch = Mth.sin((time - NECK_LAG_TICKS) * 0.062F + phase) * 0.13F;
			float skullPitch = Mth.sin((time - NECK_LAG_TICKS * 2.0F) * 0.062F + phase) * 0.16F;
			Bone mount = pose.bone(HEAD_PREFIX[head] + "_head_mount");
			Bone neckA = pose.bone(HEAD_PREFIX[head] + "_neck_a");
			Bone neckB = pose.bone(HEAD_PREFIX[head] + "_neck_b");
			Bone skull = pose.bone(HEAD_PREFIX[head] + "_skull");
			Bone jaw = pose.bone(HEAD_PREFIX[head] + "_jaw");
			mount.xRot += mountPitch - reach * 0.14F;
			mount.yRot += Mth.cos(time * 0.048F + phase) * 0.09F + side * reach * 0.12F;
			neckA.xRot += neckPitch - reach * 0.18F;
			neckA.yRot += Mth.cos((time - NECK_LAG_TICKS) * 0.048F + phase) * 0.11F
					+ side * reach * 0.14F;
			neckB.xRot += skullPitch - reach * 0.20F;
			neckB.zRot += Mth.sin(time * 0.037F + phase * 1.7F) * (0.06F + wear * 0.20F);
			skull.xRot += Mth.sin((time - NECK_LAG_TICKS * 3.0F) * 0.062F + phase) * 0.09F
					- reach * 0.16F;
			skull.yRot += side * reach * 0.16F;
			jaw.xRot += reach * (head == 0 ? 0.62F : 0.42F) + wear * 0.16F;
		}
	}

	/**
	 * Radians of yaw and pitch a skull may take from the gaze.
	 *
	 * <p>Sixty degrees each. The yaw limit is about where a head starts looking over its own shoulder
	 * and the storm stops reading as three necks with heads on them.
	 *
	 * <p><b>The pitch limit was the reason the heads never actually looked at anybody.</b> It was
	 * thirty degrees, and the geometry of this fight does not fit inside thirty degrees: the body
	 * holds station one body radius plus a swing away horizontally while hanging eight to eighteen
	 * blocks up, so a player under it is somewhere around fifty degrees below the head - and a limit
	 * of thirty clamps every single frame of that. The heads were permanently pointed at a spot short
	 * of the player and would not move further however close they got.
	 */
	private static final float GAZE_SKULL_YAW = 1.05F;
	private static final float GAZE_SKULL_PITCH = 1.05F;
	/**
	 * How much of the turn is taken by the lower neck link rather than by the skull.
	 *
	 * <p>Split rather than added, so the total the head ends up facing is exactly the gaze: a third of
	 * it twists the neck and the skull carries the rest. Neither term moves anything - both are
	 * rotations about the chain's own axis, and a rotation about the axis a bone hangs along leaves
	 * every joint below it exactly where it was.
	 */
	private static final float GAZE_NECK_SHARE = 0.32F;
	/**
	 * How far the lower neck leans in the direction of the look.
	 *
	 * <p><b>The only part of the look-at that moves a skull rather than merely turning it, which is
	 * why the number is small and why the sweep in {@code WorldInterfaceRigTest} exists.</b> A roll on
	 * the last link swings the skull on a lever eleven blocks long at the third form, and the flanks
	 * start closing on the centre - the knot this model has already been untangled from once, in
	 * {@code WorldInterfaceAnatomy.MOUNT_YAW}. It buys the read that the whole neck is leaning into
	 * the look instead of a head swivelling on a stick, and it cannot be raised without the
	 * non-intersection contract failing.
	 *
	 * <p>Signed with the yaw for the same reason the mount roll is signed against its side: model
	 * space points Y down and the chain leans toward -Z, so a positive roll carries the tip toward -X,
	 * which is where a positive Minecraft yaw is already pointing the face.
	 */
	private static final float GAZE_NECK_LEAN = 0.20F;
	/**
	 * How much of the gaze each head takes. The flanks follow the centre rather than matching it -
	 * three heads snapping to the same bearing is one head drawn three times.
	 */
	private static final float[] GAZE_WEIGHT = {1.0F, 0.72F, 0.72F};

	/**
	 * The three heads turn toward what the storm is watching.
	 *
	 * <p>Applied last, on top of the clips and the drift, so a clip that swings a head somewhere
	 * specific still wins the argument about where the head <em>is</em> and this only adds where it
	 * is pointing. Both terms are clamped before they are weighted, so an absurd gaze - a player
	 * directly underneath, a target behind the body - produces a head at the limit rather than a head
	 * wrapped around its own neck.
	 *
	 * <p>The pitch stays on the skull alone. Putting any of it on a neck would drop the head as well
	 * as tilt it, and the heads are already the part of this model that had to be lifted out of the
	 * arena floor - see {@code WorldInterfaceAnatomy.COMBAT_MASS_CLEARANCE}. A face turned down is a
	 * look; a neck driven down is a body change, and the fight already has clips for those.
	 */
	private static void headGaze(Pose pose, float gazeYaw, float gazePitch) {
		if (!Float.isFinite(gazeYaw) || !Float.isFinite(gazePitch)) return;
		float yaw = Mth.clamp(gazeYaw, -GAZE_SKULL_YAW, GAZE_SKULL_YAW);
		float pitch = Mth.clamp(gazePitch, -GAZE_SKULL_PITCH, GAZE_SKULL_PITCH);
		if (yaw == 0.0F && pitch == 0.0F) return;
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			float turn = yaw * GAZE_WEIGHT[head];
			Bone skull = pose.bone(HEAD_PREFIX[head] + "_skull");
			skull.yRot += turn * (1.0F - GAZE_NECK_SHARE);
			skull.xRot += pitch * GAZE_WEIGHT[head];
			Bone neckB = pose.bone(HEAD_PREFIX[head] + "_neck_b");
			neckB.yRot += turn * GAZE_NECK_SHARE;
			neckB.zRot += turn * GAZE_NECK_LEAN;
		}
	}

	/** Limbs follow their own root with a phase delay per link: a whip, rather than a rod. */
	private static void limbFollow(Pose pose, int form, float time) {
		int limbs = WorldInterfaceAnatomy.tentacleCount(form);
		for (int index = 0; index < limbs && index < TENDRIL_COUNT; index++) {
			float phase = index * 1.93F;
			float rate = 0.075F + index * 0.009F;
			Bone mid = pose.bone("tendril_" + index + "_mid");
			Bone tip = pose.bone("tendril_" + index + "_tip");
			mid.xRot += Mth.sin((time - LIMB_LAG_TICKS) * rate + phase) * 0.20F;
			mid.zRot += Mth.cos((time - LIMB_LAG_TICKS) * rate * 0.8F + phase) * 0.14F;
			tip.xRot += Mth.sin((time - LIMB_LAG_TICKS * 2.0F) * rate + phase) * 0.30F;
			tip.zRot += Mth.cos((time - LIMB_LAG_TICKS * 2.0F) * rate * 0.8F + phase) * 0.22F;
		}
	}

	/** The shell remembers what it has taken: the necks stop returning to true between breaths. */
	private static void structuralDamage(Pose pose, float time, float healthFraction) {
		float wear = 1.0F - Mth.clamp(healthFraction, 0.0F, 1.0F);
		if (wear <= 0.001F) return;
		Bone body = pose.bone(STORM_BODY);
		body.zRot += Mth.sin(time * 0.13F) * wear * 0.16F;
		body.xRot += Mth.cos(time * 0.097F) * wear * 0.12F;
		pose.bone(HOVER).y += Mth.sin(time * 0.071F) * wear * 1.4F;
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			float droop = wear * (head == 0 ? 0.34F : 0.24F);
			pose.bone(HEAD_PREFIX[head] + "_neck_b").xRot += droop;
			pose.bone(HEAD_PREFIX[head] + "_skull").zRot += Mth.sin(time * 0.11F + head) * wear * 0.14F;
		}
	}

	/** Half-extent of the drawn skull cube in model units, before the form scale. */
	public static float skullHalfUnits(int head) {
		return SKULL_HALF_UNITS * headScale(head);
	}
}
