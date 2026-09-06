package com.xm.thefourthfrequency.entity;

import java.util.ArrayList;
import java.util.List;

import static com.xm.thefourthfrequency.entity.WorldInterfaceClip.rotation;
import static com.xm.thefourthfrequency.entity.WorldInterfaceClip.scale;
import static com.xm.thefourthfrequency.entity.WorldInterfaceClip.translation;

/**
 * Thirty-seven reusable native clips composed behind the thirteen live action wire ids.
 *
 * <p>Every clip addresses the shared skeleton rather than a per-form tree. The three heads are one
 * chain each - mount, two necks, skull, jaw - carried across all three forms, so a clip that leans
 * the centre head into a shot leans the same bone whichever form the fight is in. The old clips
 * drove {@code eye} and {@code ring}, an eyeball and a halo on the chest that no longer exist; what
 * they used to say about intent is now said by the heads and the jaws, which is where a player was
 * already looking.
 *
 * <p>Limbs are driven at all three links. {@code mid} and {@code tip} used to be baked geometry, so
 * a tentacle bent only at its root and read as a rod being waved; giving each link its own channel,
 * arriving a beat after the one above it, is what puts a whip in the lash.
 */
public final class WorldInterfaceClips {
	public static final int PROTOCOL_ACTION_COUNT = 13;
	/** Windup, release and - for the nine attacks - an authored recovery. */
	public static final int CLIPS_PER_ACTION = 3;
	public static final int AUTHORED_CLIP_COUNT = 37;
	/** Limb bones the model bakes; the clips below address every one of them individually. */
	public static final int TENDRIL_COUNT = 10;
	/** Head chains the model bakes. Clips address them by these prefixes. */
	public static final String[] HEADS = {"center", "left", "right"};

	public static final WorldInterfaceClip IDLE_BODY = WorldInterfaceClip.builder(4.0F).looping()
			.addAnimation("storm_body", rotation(0.0F, 0, 0, -2, 2.0F, 0, 4, 2,
					4.0F, 0, 0, -2)).build();
	/** The heads breathe out of phase with each other, so the three never read as one object. */
	public static final WorldInterfaceClip IDLE_HEADS = idleHeads();
	/** The buried kernel keeps its own slow clock, unrelated to anything the body is doing. */
	public static final WorldInterfaceClip IDLE_KERNEL = WorldInterfaceClip.builder(4.0F).looping()
			.addAnimation("interface_kernel", scale(0.0F, 1.0, 1.0, 1.0, 1.8F, 1.06, 1.06, 1.06,
					2.2F, 0.96, 1.03, 1.0, 4.0F, 1.0, 1.0, 1.0)).build();

	// Every clip length below is the wire action's server duration in seconds. The laser sweeps for
	// two seconds after its lock, the grabs carry before they land, and the lance falls at 2.0s -
	// keeping the clips on those clocks is what makes the body read as driving the attack rather
	// than as playing over it.
	public static final WorldInterfaceClip LASER_AIM = headAim(6.5F, 58.0F);
	public static final WorldInterfaceClip LASER_APERTURE = jawOpen(6.5F, 3.575F, -46.0F);
	public static final WorldInterfaceClip ORB_CHARGE = coreCharge(3.0F, 0.5F, 24.0F);
	public static final WorldInterfaceClip ORB_RELEASE = headLunge(3.0F, 0.5F, 1.42);
	public static final WorldInterfaceClip LANCE_FOCUS = headHold(5.5F, 3.0F, -38.0F);
	public static final WorldInterfaceClip LANCE_DESCENT = lanceDescent(5.5F, 3.0F, 4.5F);
	public static final WorldInterfaceClip WEAPON_REACH = tendrilHold(10.8F, 2.75F, 74.0F);
	public static final WorldInterfaceClip WEAPON_HOLD = weaponHold(10.8F, 2.75F);
	public static final WorldInterfaceClip THROW_CAPTURE = grabCarry(4.5F, 2.5F, 3.2F, 3.7F, -38.0F);
	public static final WorldInterfaceClip THROW_RELEASE = tendrilCarryStrike(4.5F, 2.5F, 3.2F, 3.7F,
			-118.0F, true);
	public static final WorldInterfaceClip HOTBAR_GAZE = headHold(6.65F, 3.0F, 96.0F);
	public static final WorldInterfaceClip HOTBAR_PURGE = flankSweep(6.65F, 3.0F);
	public static final WorldInterfaceClip TENDRIL_REAR = tendrilRear(6.75F, 2.25F);
	public static final WorldInterfaceClip TENDRIL_LASH = tendrilFlurry(6.75F, 3.25F, 1.5F, 3);
	public static final WorldInterfaceClip EVICTION_CORRUPTION = headHold(6.0F, 2.0F, 180.0F);
	public static final WorldInterfaceClip FORCED_EXPULSION = expulsionPulse(6.0F, 1.72);
	public static final WorldInterfaceClip SUMMON_CORE = summonCore(5.0F, 3.8F);
	public static final WorldInterfaceClip SUMMON_LIMBS = limbMorph(5.0F, 3.8F, 1.08);
	// Four seconds, matching MORPH_FLIGHT_TICKS = 80. The morph is no longer a flight: the storm
	// stays where it is, hauls terrain into itself, splits its shell and pushes the necks out.
	public static final WorldInterfaceClip MORPH_SECOND_CORE = shellTear(4.0F, -34.0F, 1.28, false);
	public static final WorldInterfaceClip MORPH_SECOND_LIMBS = limbMorph(4.0F, 2.72F, 1.14);
	public static final WorldInterfaceClip MORPH_THIRD_CORE = shellTear(4.0F, 48.0F, 1.44, true);
	public static final WorldInterfaceClip MORPH_THIRD_LIMBS = limbMorph(4.0F, 2.72F, 1.24);
	// One authored recovery per attack. Each drives a bone its own pair leaves alone, so the settle
	// composes cleanly instead of fighting the release, and each overshoots past neutral before
	// damping - that overshoot is the whole reason a blow reads as having had mass behind it.
	//
	// The three body accents below were authored at a spacing no body this size can honour. The
	// laser's ran 6.20 -> 6.30 -> 6.41 -> 6.50, so its legs were two, two and one tick; the weapon's
	// were 0.08, 0.06 and 0.04 seconds, which is under a tick each. On a mob the size of a zombie
	// that is a punchy recoil. On `storm_body`, which every neck and every limb hangs off, it throws
	// the whole storm - the skulls covered better than four blocks in a single tick, and the accent
	// that was supposed to say "that blow had mass behind it" said "the model changed". The shape and
	// the overshoot are kept exactly; only the legs are opened out to a quarter of a second or more,
	// which is the shortest interval a body this wide can travel in and still be seen travelling.
	public static final WorldInterfaceClip LASER_RECOVER = WorldInterfaceClip.builder(6.5F)
			.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, 5.60F, 0, 0, 0,
					5.86F, 14, -9, 0, 6.16F, -5, 3, 0, 6.5F, 0, 0, 0)).build();
	public static final WorldInterfaceClip ORB_RECOVER = WorldInterfaceClip.builder(3.0F)
			.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0, 0.50F, -26, 0, 0,
					0.76F, 12, 0, 0, 1.20F, -6, 0, 0, 3.0F, 0, 0, 0)).build();
	public static final WorldInterfaceClip LANCE_RECOVER = WorldInterfaceClip.builder(5.5F)
			.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0, 4.44F, -34, 0, 0,
					4.60F, 15, 0, 0, 5.5F, 0, 0, 0)).build();
	public static final WorldInterfaceClip WEAPON_RECOVER = WorldInterfaceClip.builder(10.8F)
			.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, 9.86F, 0, 0, 0,
					10.14F, 21, 0, -12, 10.46F, -8, 0, 5, 10.8F, 0, 0, 0)).build();
	public static final WorldInterfaceClip THROW_RECOVER = WorldInterfaceClip.builder(4.5F)
			.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, 3.46F, 0, 0, 0,
					3.78F, -22, 46, 0, 4.10F, 6, -14, 0, 4.5F, 0, 0, 0)).build();
	public static final WorldInterfaceClip HOTBAR_RECOVER = WorldInterfaceClip.builder(6.65F)
			.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0, 6.50F, -20, 0, 0,
					6.56F, 9, 0, 0, 6.61F, -4, 0, 0, 6.65F, 0, 0, 0)).build();
	public static final WorldInterfaceClip TENDRIL_RECOVER = WorldInterfaceClip.builder(6.75F)
			.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0, 3.25F, -22, 0, 0,
					4.75F, -8, 0, 0, 6.25F, -22, 0, 0, 6.75F, 0, 0, 0)).build();
	public static final WorldInterfaceClip EXPULSION_RECOVER = WorldInterfaceClip.builder(6.0F)
			.addAnimation("center_neck_b", rotation(0.0F, 0, 0, 0, 3.30F, 0, 0, 0,
					4.10F, 0, -46, 0, 5.10F, 0, 18, 0, 6.0F, 0, 0, 0)).build();

	public static final WorldInterfaceClip SUCCESS_COLLAPSE = successCollapse();
	public static final WorldInterfaceClip SUCCESS_FADE = successFade();
	public static final WorldInterfaceClip FAILURE_BLACKEN = failureBlacken();
	public static final WorldInterfaceClip FAILURE_ESCAPE = failureEscape();

	private static final WorldInterfaceClip[] NO_CLIPS = new WorldInterfaceClip[0];
	private static final WorldInterfaceClip[] IDLE_CLIPS = {IDLE_BODY, IDLE_HEADS, IDLE_KERNEL};
	private static final WorldInterfaceClip[][] ACTION_CLIPS = {
			NO_CLIPS,
			{LASER_AIM, LASER_APERTURE, LASER_RECOVER},
			{ORB_CHARGE, ORB_RELEASE, ORB_RECOVER},
			// 3: the grab-slam is retired; the id keeps its slot so the table stays wire-indexed.
			NO_CLIPS,
			{LANCE_FOCUS, LANCE_DESCENT, LANCE_RECOVER},
			{WEAPON_REACH, WEAPON_HOLD, WEAPON_RECOVER},
			{THROW_CAPTURE, THROW_RELEASE, THROW_RECOVER},
			{HOTBAR_GAZE, HOTBAR_PURGE, HOTBAR_RECOVER},
			{TENDRIL_REAR, TENDRIL_LASH, TENDRIL_RECOVER},
			{EVICTION_CORRUPTION, FORCED_EXPULSION, EXPULSION_RECOVER},
			{SUMMON_CORE, SUMMON_LIMBS},
			{MORPH_SECOND_CORE, MORPH_SECOND_LIMBS},
			{MORPH_THIRD_CORE, MORPH_THIRD_LIMBS},
			{SUCCESS_COLLAPSE, SUCCESS_FADE},
			{FAILURE_BLACKEN, FAILURE_ESCAPE}
	};

	private WorldInterfaceClips() {
	}

	public static WorldInterfaceClip[] idleClips() {
		return IDLE_CLIPS.clone();
	}

	public static WorldInterfaceClip[] clipsForAction(int actionId) {
		if (actionId <= 0 || actionId >= ACTION_CLIPS.length) return NO_CLIPS;
		return ACTION_CLIPS[actionId].clone();
	}

	/** Compatibility accessor for callers that need the primary motion for a wire action. */
	public static WorldInterfaceClip forAction(int actionId) {
		WorldInterfaceClip[] clips = clipsForAction(actionId);
		return clips.length == 0 ? null : clips[0];
	}

	/**
	 * The three heads breathe a third of a cycle apart, so no two of them reach the same place at the
	 * same time.
	 *
	 * <p><b>Phase is a rotation of the values, not of the clock.</b> It used to be applied by wrapping
	 * each keyframe's <em>timestamp</em> around the loop, which left the second and third heads
	 * holding keyframe lists that were no longer in time order - {@code (0, 3.5, 5.0, 0.5, 6.0)} and
	 * {@code (0, 5.5, 1.0, 2.5, 6.0)}. Everything downstream assumes that list is sorted:
	 * {@code Mth.binarySearch} does, and {@link WorldInterfaceClip#apply} picks its Catmull-Rom
	 * neighbours by index. So those two heads spent the loop interpolating between keyframes that are
	 * not adjacent in time, and popped each time the search crossed one of the out-of-order entries.
	 *
	 * <p>It is not large - about a block and a third on a skull - but it fires three times every six
	 * seconds, forever, on the <em>idle</em> pose, which is the pose the storm is in for most of the
	 * fight. A boss that twitches while it is doing nothing is a boss that never reads as heavy.
	 */
	private static WorldInterfaceClip idleHeads() {
		// Local rather than fields: IDLE_HEADS is initialised in declaration order above, so a
		// constant declared after it would still be null when this runs.
		float[][] neckKeys = {{4, 7, -3}, {-3, -5, 2}, {5, 3, 3}};
		float[][] skullKeys = {{-5, 9, 0}, {3, -8, 0}};
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(6.0F).looping();
		for (int head = 0; head < HEADS.length; head++) {
			float[][] neck = phase(neckKeys, head);
			float[][] skull = phase(skullKeys, head);
			builder.addAnimation(HEADS[head] + "_neck_a", rotation(
					0.0F, 0, 0, 0,
					1.5F, neck[0][0], neck[0][1], neck[0][2],
					3.0F, neck[1][0], neck[1][1], neck[1][2],
					4.5F, neck[2][0], neck[2][1], neck[2][2],
					6.0F, 0, 0, 0));
			builder.addAnimation(HEADS[head] + "_skull", rotation(
					0.0F, 0, 0, 0,
					2.0F, skull[0][0], skull[0][1], skull[0][2],
					4.0F, skull[1][0], skull[1][1], skull[1][2],
					6.0F, 0, 0, 0));
		}
		return builder.build();
	}

	/** The same authored cycle, entered at a different point in it. */
	private static float[][] phase(float[][] keys, int head) {
		float[][] rotated = new float[keys.length][];
		for (int index = 0; index < keys.length; index++) {
			rotated[index] = keys[(index + head) % keys.length];
		}
		return rotated;
	}

	/**
	 * All three heads turn onto the shot, the centre one leading and the flanks trailing.
	 *
	 * <p>This is what {@code eyeAim} used to do with a disc on the chest. Aiming with the heads
	 * means the direction the attack is coming from is legible from the part of the model the
	 * player is already watching.
	 */
	private static WorldInterfaceClip headAim(float seconds, float yaw) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int head = 0; head < HEADS.length; head++) {
			float lead = head == 0 ? 1.0F : 0.66F;
			float delay = head == 0 ? 0.0F : 0.22F;
			// The wind-back is reached, not started from.
			//
			// This frame used to sit at zero seconds, which meant the necks were already a third of
			// the way <em>against</em> the aim on the tick the action was published - twenty degrees
			// of centre neck appearing between one frame and the next, with nothing before it. The
			// anticipation is the best beat in the clip and it was being spent as a pop; given its own
			// fifth of a second to arrive in, it reads as the head drawing back before it commits.
			float anticipation = Math.min(0.45F, seconds * 0.10F);
			builder.addAnimation(HEADS[head] + "_neck_a", rotation(
					0.0F, 0, 0, 0,
					anticipation, 0, -yaw * 0.35F * lead, 0,
					seconds * 0.55F + delay, 0, yaw * lead, 0,
					seconds, 0, 0, 0));
			builder.addAnimation(HEADS[head] + "_skull", rotation(
					0.0F, 0, 0, 0,
					seconds * 0.55F + delay, -8, yaw * 0.32F * lead, 0,
					seconds, 0, 0, 0));
		}
		return builder.build();
	}

	/**
	 * Degrees per second a bone is allowed to travel while it unwinds back to neutral.
	 *
	 * <p><b>This number is the difference between a large thing moving and a large thing blinking.</b>
	 * Every hold in this file used to end with a flat {@code seconds - 0.12F} release: whatever the
	 * bone was holding - twenty-six degrees or a hundred and eighty - was undone in 0.12 seconds, two
	 * and a half ticks. On the centre neck at third form the skull sits the better part of sixteen
	 * blocks out from its pivot, so the eviction's hundred and eighty degrees moved it something like
	 * twenty-five blocks inside two frames. There is no speed at which that reads as a head turning;
	 * it reads as a head that was somewhere else and is now here, which is exactly the "it teleports"
	 * report this replaces.
	 *
	 * <p>Ninety degrees a second is half the rate the gaze is allowed to travel at
	 * ({@code WorldInterfaceEntity.GAZE_TURN_PER_TICK}, about a hundred and eighty a second), and
	 * deliberately so: the gaze is a glance and this is a structure the size of a building putting
	 * itself back. The whole point of the boss is mass, and mass is communicated by how long it takes
	 * something to stop.
	 */
	private static final float SETTLE_DEGREES_PER_SECOND = 90.0F;

	/**
	 * The shortest unwind any bone gets, however small the angle.
	 *
	 * <p>Seven ticks. Below this even a modest correction lands inside the time a player needs to
	 * notice it happened at all, and a body that finishes its movements instantly is a body with no
	 * weight regardless of how far it actually travelled.
	 */
	private static final float MIN_SETTLE_SECONDS = 0.36F;

	/**
	 * The most of the post-peak window an unwind may spend.
	 *
	 * <p>The hold is the telegraph - it is what tells a player they are the one being aimed at - so
	 * the settle is not allowed to eat it. At most it takes a little under half of whatever is left
	 * after the pose has arrived, which leaves the majority of the window still reading as a hold.
	 */
	private static final float MAX_SETTLE_SHARE = 0.45F;

	/**
	 * When a hold ends and the unwind begins, given how far the bone has to come back.
	 *
	 * @param clipSeconds the clip's whole length, which is also the action's duration
	 * @param peakSeconds when the pose finishes arriving, so the hold cannot be shortened from the front
	 * @param amplitudeDegrees the largest angle being undone
	 */
	private static float releaseAt(float clipSeconds, float peakSeconds, float amplitudeDegrees) {
		float available = Math.max(0.0F, clipSeconds - peakSeconds);
		float wanted = Math.max(MIN_SETTLE_SECONDS,
				Math.abs(amplitudeDegrees) / SETTLE_DEGREES_PER_SECOND);
		return clipSeconds - Math.min(wanted, available * MAX_SETTLE_SHARE);
	}

	/** The centre head's jaw drops through the charge and closes under its own weight. */
	private static WorldInterfaceClip jawOpen(float seconds, float peakSeconds, float openDegrees) {
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0,
						peakSeconds, openDegrees, 0, 0,
						Math.max(peakSeconds, releaseAt(seconds, peakSeconds, openDegrees * 0.88F)),
						openDegrees * 0.88F, 0, 0,
						seconds, 0, 0, 0)).build();
	}

	private static WorldInterfaceClip coreCharge(float seconds, float chargeSeconds, float yaw) {
		float release = Math.max(chargeSeconds, releaseAt(seconds, chargeSeconds, yaw));
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, chargeSeconds, 0, yaw, 0,
						release, 0, yaw, 0, seconds, 0, 0, 0)).build();
	}

	/** The centre head rears back and drives forward: the bolt is spat, not emitted. */
	private static WorldInterfaceClip headLunge(float seconds, float chargeSeconds, double peakScale) {
		float release = Math.max(chargeSeconds, releaseAt(seconds, chargeSeconds, 26.0F));
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("center_neck_b", rotation(0.0F, 0, 0, 0,
						chargeSeconds, -34, 0, 0, release, 26, 0, 0, seconds, 0, 0, 0))
				.addAnimation("center_skull", scale(0.0F, 1, 1, 1, chargeSeconds,
						peakScale, peakScale, peakScale, release, peakScale, peakScale, peakScale,
						seconds, 1, 1, 1)).build();
	}

	/** A head locked onto one player and held there, which is what being singled out looks like. */
	private static WorldInterfaceClip headHold(float seconds, float warningSeconds, float yaw) {
		float release = releaseAt(seconds, warningSeconds, yaw);
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("center_neck_a", rotation(0.0F, 0, 0, 0, warningSeconds, 0, yaw, 0,
						release, 0, yaw, 0, seconds, 0, 0, 0))
				.addAnimation("center_skull", scale(0.0F, 1, 1, 1, warningSeconds, 1.22, 1.22, 1.22,
						release, 1.22, 1.22, 1.22, seconds, 1, 1, 1)).build();
	}

	/**
	 * The two flanking heads sweep outward while the centre holds: the purge, seen from below.
	 *
	 * <p>{@code outward} rather than {@code side}, and that is not cosmetic. Model space points Y
	 * down, so a chain hanging off a mount travels toward -X under a positive yaw or roll; driving
	 * these off the raw side swept the head mounted at +X <em>across</em> the centre neck instead of
	 * away from it, which is the same inversion the bind pose carried. See
	 * {@code WorldInterfaceAnatomy#mountRoll}.
	 */
	private static WorldInterfaceClip flankSweep(float seconds, float warningSeconds) {
		float release = releaseAt(seconds, warningSeconds, 78.0F);
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int head = 1; head < HEADS.length; head++) {
			float outward = head == 1 ? -1.0F : 1.0F;
			builder.addAnimation(HEADS[head] + "_neck_a", rotation(0.0F, 0, 0, 0,
					warningSeconds, -18, outward * 62, outward * 24,
					release, -12, outward * 78, outward * 30, seconds, 0, 0, 0));
			builder.addAnimation(HEADS[head] + "_jaw", rotation(0.0F, 0, 0, 0,
					warningSeconds, -38, 0, 0, release, -30, 0, 0, seconds, 0, 0, 0));
		}
		return builder.build();
	}

	private static WorldInterfaceClip tendrilHold(float seconds, float warningSeconds, float pitch) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		float release = releaseAt(seconds, warningSeconds, Math.max(Math.abs(pitch), 48.0F));
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, side * 18,
					warningSeconds, pitch, side * 18, side * 48,
					release, pitch, side * 18, side * 48, seconds, 0, 0, side * 18));
			// The mid link curls in behind the root, a beat later.
			builder.addAnimation("tendril_" + index + "_mid", rotation(0.0F, 0, 0, 0,
					warningSeconds + 0.12F, pitch * 0.42F, 0, side * 20,
					release, pitch * 0.42F, 0, side * 20, seconds, 0, 0, 0));
		}
		return builder.build();
	}

	private static WorldInterfaceClip weaponHold(float seconds, float warningSeconds) {
		float release = releaseAt(seconds, warningSeconds, 38.0F);
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("weapon", rotation(0.0F, 0, 0, 0,
						warningSeconds, -18, 0, 38, release, -18, 0, 38,
						seconds, 0, 0, 0))
				.addAnimation("weapon", scale(0.0F, 0.05, 0.05, 0.05,
						warningSeconds, 1, 1, 1, release, 1, 1, 1,
						seconds, 0.05, 0.05, 0.05)).build();
	}

	/**
	 * The body half of a grab: it leans in during the warning, hauls back as the victim is lifted,
	 * holds through the carry, and unwinds on release. The carry is a real interval on the server
	 * rather than a teleport, so the body has something to be doing for the whole of it.
	 */
	private static WorldInterfaceClip grabCarry(float seconds, float warningSeconds,
			float liftSeconds, float releaseSeconds, float yaw) {
		return WorldInterfaceClip.builder(seconds)
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0,
						warningSeconds, -8, yaw, 6,
						liftSeconds, -19, yaw * 1.25F, 12,
						releaseSeconds, -14, yaw * 1.1F, 9,
						seconds, 0, 0, 0))
				// The centre head follows what the limbs are carrying, which is what makes the
				// grab read as deliberate rather than as something the body happened to catch.
				.addAnimation("center_neck_a", rotation(0.0F, 0, 0, 0,
						warningSeconds, 14, -yaw * 0.3F, 0,
						liftSeconds, 26, -yaw * 0.4F, 0,
						releaseSeconds, 20, -yaw * 0.35F, 0,
						seconds, 0, 0, 0)).build();
	}

	/**
	 * The limb half of the same grab. The tendrils close at the warning, snap taut on the lift so
	 * the victim visibly comes up on them, and then either drive down or fling wide at release.
	 */
	private static WorldInterfaceClip tendrilCarryStrike(float seconds, float warningSeconds,
			float liftSeconds, float releaseSeconds, float pitch, boolean throwAway) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			float releasePitch = throwAway ? pitch * -0.55F : pitch * 1.28F;
			float releaseYaw = throwAway ? side * -86.0F : side * -34.0F;
			float releaseRoll = throwAway ? side * -38.0F : side * 66.0F;
			// The throw peaks with room left to come down from it.
			//
			// This frame used to sit at {@code seconds - 0.05F} - one tick before the clip ended - so
			// the limbs reached a pose eighty-six degrees off their rest and returned from it in a
			// single frame. It is the sharpest cliff in the file, and it fires on the attack that
			// already has the most going on. The peak keeps its value and moves earlier; the follow
			// through is still fast, because a throw should be, but it is now travelled.
			float throwAt = releaseAt(seconds, releaseSeconds, releaseYaw);
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, side * 18,
					warningSeconds, pitch * 0.45F, side * 30, side * 30,
					liftSeconds, pitch, side * 24, side * 42,
					releaseSeconds, pitch * 0.92F, side * 20, side * 46,
					throwAt, releasePitch, releaseYaw, releaseRoll,
					seconds, 0, 0, side * 18));
			// The tip closes last and opens first: the limb grips with its end.
			builder.addAnimation("tendril_" + index + "_tip", rotation(0.0F, 0, 0, 0,
					warningSeconds + 0.18F, 34, 0, side * -26,
					liftSeconds, 52, 0, side * -34,
					releaseSeconds, 48, 0, side * -30,
					throwAt, -18, 0, 0,
					seconds, 0, 0, 0));
		}
		return builder.build();
	}

	/**
	 * The lance: the centre head drives downward through the charge and the whole body slams past
	 * neutral at the strike. The column itself is drawn in the beam batch; this is what pushes it.
	 */
	private static WorldInterfaceClip lanceDescent(float seconds, float lockSeconds, float strikeSeconds) {
		return WorldInterfaceClip.builder(seconds)
				// Halved from 32/58 and 20/40. A centre neck already hangs straight down, so pitching
				// it swings the skull backward and upward rather than further down - and at the first
				// form, where the necks are shortest, fifty-eight degrees carried the centre skull
				// right onto the right-hand head. Eighteen and thirty still read as a heave without
				// putting one head inside another; the descent the attack is named for is the light
				// column and the body slam below, not the neck.
				.addAnimation("center_neck_a", rotation(0.0F, 0, 0, 0,
						lockSeconds, 18, 0, 0, strikeSeconds, 30, 0, 0, seconds, 0, 0, 0))
				.addAnimation("center_neck_b", rotation(0.0F, 0, 0, 0,
						lockSeconds, 12, 0, 0, strikeSeconds, 22, 0, 0, seconds, 0, 0, 0))
				.addAnimation("center_jaw", rotation(0.0F, 0, 0, 0,
						lockSeconds, -52, 0, 0, strikeSeconds, -8, 0, 0, seconds, 0, 0, 0))
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0,
						strikeSeconds - 0.1F, -9, 0, 0,
						strikeSeconds + 0.08F, 17, 0, 0,
						seconds, 0, 0, 0)).build();
	}

	/**
	 * The rear-up, staggered limb by limb rather than all ten at once.
	 *
	 * <p>Ten identical bones driven by one identical curve is not ten tentacles, it is one tentacle
	 * drawn ten times - which is exactly what the flurry looked like. Each limb now has its own
	 * arrival time, its own height and its own splay, derived from its index, so the mass rears in a
	 * ripple around the body.</p>
	 */
	private static WorldInterfaceClip tendrilRear(float seconds, float warningSeconds) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			// Phase runs around the ring, so the rear-up travels rather than snapping.
			float phase = index / (float) TENDRIL_COUNT;
			float arrival = Math.max(0.12F, warningSeconds * (0.45F + phase * 0.55F));
			float rise = -58.0F - phase * 34.0F;
			float splay = 8.0F + phase * 22.0F;
			float release = releaseAt(seconds, arrival, rise * 0.92F);
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, side * 18,
					arrival, rise, side * splay, side * (18.0F + phase * 16.0F),
					release, rise * 0.92F, side * (splay + 6.0F), side * 22,
					seconds, 0, 0, side * 18));
			builder.addAnimation("tendril_" + index + "_mid", rotation(0.0F, 0, 0, 0,
					arrival + 0.14F, rise * 0.34F, 0, side * 18,
					release, rise * 0.30F, 0, side * 16, seconds, 0, 0, 0));
		}
		return builder.build();
	}

	/**
	 * The flurry, authored per limb and per link.
	 *
	 * <p>Each strike is thrown by a distinct pair of tendrils - a lead limb that whips through and a
	 * partner that follows a beat behind - while the rest hold their reared pose and drift. The
	 * server picks its target per strike, so what the body owes the fight is that a viewer can see
	 * <em>which</em> limb committed to each one; ten bones moving in lockstep could not say that.</p>
	 *
	 * <p>The tip runs the same curve delayed, which is what turns each strike from a swing into a
	 * crack.</p>
	 */
	private static WorldInterfaceClip tendrilFlurry(float seconds, float firstStrikeSeconds,
			float intervalSeconds, int strikes) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			float phase = index / (float) TENDRIL_COUNT;
			// Which strike, if any, this limb leads; its partner is the next one round the ring.
			int lead = index % strikes;
			boolean partner = (index + 1) % strikes == lead % strikes && index % strikes != lead;
			List<float[]> frames = new ArrayList<>();
			List<float[]> tipFrames = new ArrayList<>();
			frames.add(new float[]{0.0F, -58.0F - phase * 34.0F, side * (8.0F + phase * 22.0F),
					side * (18.0F + phase * 16.0F)});
			tipFrames.add(new float[]{0.0F, 0.0F, 0.0F, 0.0F});
			for (int strike = 0; strike < strikes; strike++) {
				float at = firstStrikeSeconds + intervalSeconds * strike;
				boolean leads = strike == lead;
				// Every limb reacts to every strike; only the one that leads it actually commits.
				float windup = leads ? -78.0F : -62.0F - phase * 10.0F;
				float swingYaw = leads ? side * 34.0F : side * (12.0F + phase * 10.0F);
				float lash = leads ? 64.0F : 6.0F + phase * 10.0F;
				float lashYaw = leads ? side * -52.0F : side * (-8.0F - phase * 12.0F);
				float lashRoll = leads ? side * -58.0F : side * (14.0F + phase * 12.0F);
				// The partner trails the lead by a fraction of the interval, so a strike lands as a
				// pair of limbs arriving slightly apart rather than as a single flat swipe.
				float delay = partner ? intervalSeconds * 0.16F : 0.0F;
				frames.add(new float[]{Math.max(0.05F, at - 0.26F + delay), windup, swingYaw,
						side * (20.0F + phase * 14.0F)});
				frames.add(new float[]{at + delay, lash, lashYaw, lashRoll});
				// The tip lags the root by an eighth of a second, then overshoots it.
				tipFrames.add(new float[]{Math.max(0.06F, at - 0.14F + delay),
						leads ? -42.0F : -14.0F, 0.0F, side * (leads ? -24.0F : -8.0F)});
				tipFrames.add(new float[]{at + delay + 0.12F, leads ? 78.0F : 18.0F, 0.0F,
						side * (leads ? -46.0F : -12.0F)});
			}
			frames.add(new float[]{seconds, 0.0F, 0.0F, side * 18.0F});
			tipFrames.add(new float[]{seconds, 0.0F, 0.0F, 0.0F});
			builder.addAnimation("tendril_" + index, rotation(flatten(frames)));
			builder.addAnimation("tendril_" + index + "_tip", rotation(flatten(tipFrames)));
		}
		return builder.build();
	}

	private static float[] flatten(List<float[]> frames) {
		float[] flat = new float[frames.size() * 4];
		int cursor = 0;
		for (float[] frame : frames) {
			cursor = keyframe(flat, cursor, frame[0], frame[1], frame[2], frame[3]);
		}
		return flat;
	}

	private static int keyframe(float[] frames, int cursor, float time, float x, float y, float z) {
		frames[cursor] = time;
		frames[cursor + 1] = x;
		frames[cursor + 2] = y;
		frames[cursor + 3] = z;
		return cursor + 4;
	}

	private static WorldInterfaceClip expulsionPulse(float seconds, double peakScale) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds)
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, seconds * 0.55F, 0, 180, 0,
						seconds, 0, 0, 0))
				.addAnimation("storm_body", scale(0.0F, 1, 1, 1, seconds * 0.55F,
						peakScale, peakScale, peakScale, seconds, 1, 1, 1));
		// All three heads throw back at once - the only moment in the fight they act in unison.
		for (String head : HEADS) {
			builder.addAnimation(head + "_neck_a", rotation(0.0F, 0, 0, 0,
					seconds * 0.55F, -46, 0, 0, seconds, 0, 0, 0));
			builder.addAnimation(head + "_jaw", rotation(0.0F, 0, 0, 0,
					seconds * 0.5F, -62, 0, 0, seconds, 0, 0, 0));
		}
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, 0,
					seconds * 0.55F, side * 48, side * 90, side * 72,
					seconds, 0, 0, 0));
		}
		return builder.build();
	}

	/**
	 * The arrival: the storm swells in from nothing, the necks push out and the heads open.
	 *
	 * <p>Driven on {@code hover} and the head chains rather than on the body, so the descent this
	 * plays over - which the server drives on the entity - has the body free to be leaned by
	 * whatever else is running.
	 */
	private static WorldInterfaceClip summonCore(float seconds, float chargeSeconds) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds)
				.addAnimation("storm_body", scale(0.0F, 0.05, 0.05, 0.05, chargeSeconds,
						1.08, 1.08, 1.08, seconds, 1, 1, 1))
				.addAnimation("interface_kernel", scale(0.0F, 0.01, 0.01, 0.01, chargeSeconds,
						1.35, 1.35, 1.35, seconds, 1, 1, 1));
		for (int head = 0; head < HEADS.length; head++) {
			float delay = head * 0.28F;
			// Necks unfold one after another, so the three heads arrive as three events.
			builder.addAnimation(HEADS[head] + "_neck_a", scale(0.0F, 0.2, 0.06, 0.2,
					chargeSeconds * 0.7F + delay, 1.1, 1.16, 1.1, seconds, 1, 1, 1));
			builder.addAnimation(HEADS[head] + "_jaw", rotation(0.0F, 0, 0, 0,
					chargeSeconds * 0.82F + delay, -58, 0, 0, seconds, 0, 0, 0));
		}
		return builder.build();
	}

	/**
	 * A morph, in place.
	 *
	 * <p>This used to be a flight: the boss climbed out of reach, flattened, swapped models and came
	 * back, which cost the fight four seconds of nothing to hit at the exact moment it was supposed
	 * to feel biggest. What happens now happens where it stands - the shell splits, the body hauls
	 * terrain in and swells against it, the necks push out to their new length, and the heads open.
	 * The player can keep hitting it throughout.
	 */
	private static WorldInterfaceClip shellTear(float seconds, float yaw, double peakScale, boolean wide) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds)
				// A slow turn into the intake rather than a spin: it is pulling, not showing off.
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0,
						seconds * 0.45F, -7, yaw * 0.5F, 4,
						seconds * 0.78F, 5, yaw, -3, seconds, 0, 0, 0))
				// The swell is the shell coming apart and being packed back out wider.
				.addAnimation("storm_body", scale(0.0F, 1, 1, 1,
						seconds * 0.36F, 0.92, 1.06, 0.92,
						seconds * 0.68F, peakScale, peakScale * 0.94, peakScale,
						seconds, 1, 1, 1))
				.addAnimation("interface_kernel", scale(0.0F, 1, 1, 1,
						seconds * 0.5F, 1.5, 1.5, 1.5, seconds, 1, 1, 1));
		for (int head = 0; head < HEADS.length; head++) {
			float delay = head * 0.2F;
			// Signed so that positive means away from the centre line; see flankSweep.
			float outward = head == 0 ? 0.0F : head == 1 ? -1.0F : 1.0F;
			// Necks are hauled back, then pushed out further than they were - the growth, visible.
			builder.addAnimation(HEADS[head] + "_neck_a", rotation(0.0F, 0, 0, 0,
					seconds * 0.34F + delay, 26, outward * -18, 0,
					seconds * 0.72F + delay, -22, outward * 26, 0,
					seconds, 0, 0, 0));
			builder.addAnimation(HEADS[head] + "_jaw", rotation(0.0F, 0, 0, 0,
					seconds * 0.62F + delay, wide ? -66 : -44, 0, 0, seconds, 0, 0, 0));
		}
		return builder.build();
	}

	private static WorldInterfaceClip limbMorph(float seconds, float revealSeconds, double overshoot) {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(seconds);
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			// Staggered, so the new limbs are released one after another rather than appearing.
			float delay = index * 0.08F;
			builder.addAnimation("tendril_" + index, scale(0.0F, 0.06, 0.06, 0.06,
					Math.min(seconds - 0.05F, revealSeconds + delay), overshoot, overshoot, overshoot,
					seconds, 1, 1, 1));
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, side * 70,
					Math.min(seconds - 0.05F, revealSeconds + delay), side * -18, side * 28, side * 14,
					seconds, 0, 0, side * 18));
		}
		return builder.build();
	}

	/**
	 * The interface coming apart, nine seconds, played against the server-side death ascent.
	 *
	 * <p>This used to be a topple: the body rotated eighty-two degrees onto its side and the
	 * tendrils went with it, which reads as something that was knocked over. The ending is not that.
	 * The interface is not overpowered, it is ended, and what it does on the way out is let go - so
	 * the body rises (driven by the entity, not by this clip), loses its hold on level, swells as
	 * whatever was containing it stops, and the limbs release one after another and fall away from
	 * it. Nothing here rotates past the tilt of something that has simply stopped steering.</p>
	 *
	 * <p>The staggered limb release is mirrored by {@code emitLimbFailure} on the server, which
	 * bursts and sounds each limb on the tick this clip lets it go. The two have to agree: a burst
	 * on a limb still attached, or a limb detaching in silence, reads as two unrelated events.</p>
	 */
	private static WorldInterfaceClip successCollapse() {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(9.0F)
				// A shudder that becomes a drift. Never a topple.
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, 1.2F, -3, 6, 2, 2.4F, 2, -5, -3,
						3.6F, -6, 9, 4, 6.0F, -14, 26, -6, 9.0F, -22, 54, -9))
				// Swells as the containment goes, then there is nothing left holding a shape at all.
				.addAnimation("storm_body", scale(0.0F, 1, 1, 1, 4.4F, 1.14, 1.14, 1.14,
						6.6F, 0.86, 0.92, 0.86, 9.0F, 0.02, 0.02, 0.02));
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			// Evenly staggered across the first two thirds, matching the server's limb schedule.
			float release = 1.9F + index * 0.36F;
			double angle = Math.toRadians(index * 36.0D);
			float outX = (float) (Math.cos(angle) * 27.0D);
			float outZ = (float) (Math.sin(angle) * 27.0D);
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, 0,
					release, side * 8, side * 12, side * 10,
					release + 2.0F, side * 46, side * 130, side * -58,
					9.0F, side * 108, side * 286, side * -142));
			// Out and down, while the body they came off is going up.
			builder.addAnimation("tendril_" + index, translation(0.0F, 0, 0, 0,
					release, 0, 0, 0, release + 1.6F, outX * 0.42F, -4, outZ * 0.42F,
					9.0F, outX, -17, outZ));
			builder.addAnimation("tendril_" + index, scale(0.0F, 1, 1, 1,
					release, 1, 1, 1, release + 1.8F, 0.68, 0.68, 0.68,
					9.0F, 0.02, 0.02, 0.02));
		}
		return builder.build();
	}

	/**
	 * The lights going out, over the same nine seconds.
	 *
	 * <p>Ordered rather than simultaneous, because the order is the meaning: the heads die one at a
	 * time from the flanks inward, so the centre one - the thing that has been watching the player
	 * for the whole fight - is the last to stop. The jaws are let go rather than closed, and the
	 * buried kernel outlives all of them by a second, still running with nothing left to run.
	 */
	private static WorldInterfaceClip successFade() {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(9.0F);
		// Flanks first, centre last.
		float[] deaths = {4.6F, 2.2F, 3.1F};
		for (int head = 0; head < HEADS.length; head++) {
			float death = deaths[head];
			builder.addAnimation(HEADS[head] + "_skull", scale(0.0F, 1, 1, 1,
					Math.max(0.4F, death - 0.7F), 1.35, 1.35, 1.35,
					death, 0.9, 0.9, 0.9, 9.0F, 0.02, 0.02, 0.02));
			// The neck stops holding the head up before the head stops being a head.
			builder.addAnimation(HEADS[head] + "_neck_b", rotation(0.0F, 0, 0, 0,
					death, 18, 0, 0, death + 1.8F, 52, 0, 0, 9.0F, 74, 0, 0));
			builder.addAnimation(HEADS[head] + "_jaw", rotation(0.0F, 0, 0, 0,
					death, -34, 0, 0, 9.0F, -70, 12, -8));
		}
		return builder
				.addAnimation("interface_kernel", scale(0.0F, 1, 1, 1, 1.1F, 1.55, 1.55, 1.55,
						5.6F, 1.2, 1.2, 1.2, 7.4F, 0.3, 0.3, 0.3, 9.0F, 0.01, 0.01, 0.01))
				.build();
	}

	private static WorldInterfaceClip failureBlacken() {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(6.0F)
				.addAnimation("interface_kernel", scale(0.0F, 1, 1, 1, 3.0F, 0.55, 0.08, 0.55,
						4.5F, 1.4, 0.12, 1.4, 6.0F, 0.05, 0.05, 0.05));
		for (String head : HEADS) {
			builder.addAnimation(head + "_jaw", rotation(0.0F, 0, 0, 0, 3.0F, -18, 0, 0,
					4.5F, -52, 0, 0, 6.0F, -12, 0, 0));
		}
		return builder.build();
	}

	private static WorldInterfaceClip failureEscape() {
		WorldInterfaceClip.Builder builder = WorldInterfaceClip.builder(6.0F)
				.addAnimation("storm_body", rotation(0.0F, 0, 0, 0, 3.0F, -25, 180, -18,
						6.0F, 0, 360, 0))
				.addAnimation("storm_body", scale(0.0F, 1, 1, 1, 4.5F, 1.4, 0.22, 1.4,
						6.0F, 0.04, 0.04, 0.04));
		for (String head : HEADS) {
			builder.addAnimation(head + "_neck_a", rotation(0.0F, 0, 0, 0,
					4.5F, -38, 62, 0, 6.0F, -12, 140, 0));
		}
		for (int index = 0; index < TENDRIL_COUNT; index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			builder.addAnimation("tendril_" + index, rotation(0.0F, 0, 0, 0,
					3.0F, side * 48, side * 120, side * 72,
					6.0F, side * -80, side * 220, side * -110));
		}
		return builder.build();
	}
}
