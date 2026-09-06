package com.xm.thefourthfrequency.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The posed skeleton, checked where a screenshot cannot check it.
 *
 * <p>Two separate promises live here and they fail in opposite directions.
 *
 * <p><b>The three necks must not knot.</b> Model space points Y down, so a chain hanging off a mount
 * swings toward -X under a positive roll - which meant {@code side * ROLL} rolled the head mounted at
 * +X across the centre line, and both flanks through the centre neck. At the second form the two
 * flanking skulls were drawn 0.63 model units apart when their own boxes are 7.2 across. Nothing
 * caught it, because every existing test asked where a head was rather than what else was there.
 *
 * <p><b>The boxes must follow the bones.</b> They used to stand on a static reproduction of the bind
 * pose, and three separate things move the drawn bones off it: the clips, the per-tick drift, and the
 * structural sag. What is asserted is not a coordinate - coordinates here are the rig's to decide -
 * but that a change in the animation moves the answer, and that the answer stays attached to the
 * storm while it does.
 */
final class WorldInterfaceRigTest {
	/** Model units per block, for stating clearances in the units the geometry is authored in. */
	private static final double UNITS = WorldInterfaceRig.UNITS_PER_BLOCK;

	private static WorldInterfaceRig.Pose rest(int form) {
		return WorldInterfaceRig.restPose(form);
	}

	/**
	 * No two heads occupy the same air, and neither flank passes through the centre neck.
	 *
	 * <p>Measured in model units so the clearance means the same thing at every form: the skulls are
	 * 9.2 and 7.2 units across and the necks 5 and 3.9, and a gap smaller than the sum of two radii is
	 * geometry intersecting geometry.
	 */
	@Test
	void theThreeHeadsAndNecksNeverIntersect() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			WorldInterfaceRig.Pose pose = rest(form);
			double scale = pose.renderScale();
			for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
				for (int other = head + 1; other < WorldInterfaceAnatomy.HEAD_COUNT; other++) {
					double gap = pose.headOffset(head).distanceTo(pose.headOffset(other)) * UNITS / scale;
					double needed = WorldInterfaceRig.skullHalfUnits(head)
							+ WorldInterfaceRig.skullHalfUnits(other);
					assertTrue(gap > needed, "form " + form + ": skulls " + head + " and " + other
							+ " are " + gap + " units apart and need " + needed);
				}
			}
			// And the flanks clear the centre chain, which is the thing they used to be threaded
			// through: the centre neck runs from its own root to its own skull.
			Vec3 centreRoot = pose.neckJointOffset(0, 0);
			Vec3 centreSkull = pose.headOffset(0);
			for (int head = 1; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
				double gap = distanceToSegment(pose.headOffset(head), centreRoot, centreSkull)
						* UNITS / scale;
				double needed = WorldInterfaceRig.skullHalfUnits(head) + 2.5D;
				assertTrue(gap > needed, "form " + form + ": flank skull " + head + " is " + gap
						+ " units from the centre neck and needs " + needed);
			}
			// The two flanking chains must not cross each other either.
			double flankGap = distanceToSegment(pose.headOffset(1), pose.neckJointOffset(2, 0),
					pose.headOffset(2)) * UNITS / scale;
			assertTrue(flankGap > WorldInterfaceRig.skullHalfUnits(1) + 2.0D,
					"form " + form + ": the flanking chains cross at " + flankGap + " units");
		}
	}

	/**
	 * And they stay apart while animating, across every live action.
	 *
	 * <p>Looser than the shape contract above on purpose. The heads are meant to move independently -
	 * they breathe a third of a cycle apart, they converge on a target during a windup, and the purge
	 * sweeps the flanks out - so a frame in which two skulls have closed on each other is a frame, not
	 * a fault. What must never happen is what the wrong roll sign used to produce for the whole
	 * fight: two heads occupying the same air.
	 */
	@Test
	void theHeadsStayApartThroughEveryAction() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			for (int action = 0; action <= WorldInterfaceClips.PROTOCOL_ACTION_COUNT; action++) {
				for (long millis = 0L; millis <= 11_000L; millis += 250L) {
					WorldInterfaceRig.Pose pose =
							WorldInterfaceRig.pose(form, millis / 50.0F, 0.5F, action, millis);
					double scale = pose.renderScale();
					for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
						for (int other = head + 1; other < WorldInterfaceAnatomy.HEAD_COUNT; other++) {
							double gap = pose.headOffset(head).distanceTo(pose.headOffset(other))
									* UNITS / scale;
							double needed = Math.max(WorldInterfaceRig.skullHalfUnits(head),
									WorldInterfaceRig.skullHalfUnits(other));
							assertTrue(gap > needed, "form " + form + " action " + action + " at "
									+ millis + "ms: skulls " + head + " and " + other
									+ " are inside each other at " + gap + " units");
						}
					}
				}
			}
		}
	}

	/**
	 * The heads may look wherever they like and still not knot.
	 *
	 * <p>The look-at is the one thing in the rig that is driven by the fight rather than by a clock,
	 * so it is the one thing that can be handed a value nobody anticipated: a player standing directly
	 * underneath, or behind the body, at the moment a clip has already swung the flanks inward. Swept
	 * over the whole yaw and pitch range against the same non-intersection contract the rest pose is
	 * held to, and across the actions, because the failure being guarded against - two skulls in the
	 * same air - is what the mount-roll sign bug produced and what any gaze term put on a neck will
	 * produce again if it is given enough leverage.
	 */
	@Test
	void theHeadsNeverKnotHoweverTheyAreLooking() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			for (int action : new int[]{0, 1, 4, 7, 8, 9}) {
				for (int yawStep = -6; yawStep <= 6; yawStep++) {
					for (int pitchStep = -4; pitchStep <= 4; pitchStep++) {
						float yaw = yawStep * 0.30F;
						float pitch = pitchStep * 0.30F;
						for (long millis : new long[]{0L, 1_500L, 3_000L, 6_000L}) {
							WorldInterfaceRig.Pose pose = WorldInterfaceRig.pose(form,
									millis / 50.0F, 0.0F, action, millis, yaw, pitch);
							assertHeadsApart(pose, form, "gaze " + yaw + "/" + pitch
									+ " action " + action + " at " + millis + "ms");
						}
					}
				}
			}
		}
	}

	/**
	 * A gaze has to actually turn the heads, or the storm is still looking through the player.
	 *
	 * <p>Measured on the face rather than on the skull's centre. Most of the look-at is a rotation
	 * about the chain's own axis - which is precisely what makes it safe - and a rotation about that
	 * axis leaves the centre of the head almost exactly where it was. What moves is where the head is
	 * <em>pointing</em>, so that is what is asserted: the eye, five units off the bone.
	 */
	@Test
	void theGazeTurnsTheHeads() {
		Vec3 ahead = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, 0.0F, 0.0F));
		Vec3 aside = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, 0.8F, 0.0F));
		Vec3 down = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, 0.0F, 0.5F));
		assertTrue(ahead.distanceTo(aside) > 1.0D, "a yaw must turn the centre head: "
				+ ahead.distanceTo(aside));
		assertTrue(ahead.distanceTo(down) > 0.5D, "a pitch must tilt the centre head: "
				+ ahead.distanceTo(down));
		// Looking left and looking right turn the face to opposite sides of straight ahead. Only the
		// direction is asserted: the drift the gaze is applied on top of is deliberately not
		// symmetric, so the two displacements are opposed rather than equal.
		Vec3 other = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, -0.8F, 0.0F));
		assertTrue((aside.x - ahead.x) * (other.x - ahead.x) < 0.0D,
				"opposite gazes must turn the head opposite ways");
		// An absurd gaze is clamped rather than wrapped, so a target behind the body cannot spin a
		// head around its own neck.
		Vec3 extreme = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, 9.0F, 9.0F));
		Vec3 limit = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, 3.0F, 3.0F));
		assertEquals(0.0D, extreme.distanceTo(limit), 1.0E-6D,
				"past the limit the gaze must stop turning the head");
		// And a non-finite gaze is ignored outright rather than producing a NaN skeleton.
		Vec3 broken = faceOf(WorldInterfaceRig.pose(2, 20.0F, 1.0F, 0, 0L, Float.NaN, Float.NaN));
		assertEquals(0.0D, ahead.distanceTo(broken), 1.0E-6D);
	}

	/** Where the centre head's eye sits, in blocks off the entity position. */
	private static Vec3 faceOf(WorldInterfaceRig.Pose pose) {
		return pose.offset(WorldInterfaceRig.HEAD_PREFIX[0] + "_skull", 0.0D, -1.2D, -5.0D);
	}

	private static void assertHeadsApart(WorldInterfaceRig.Pose pose, int form, String label) {
		double scale = pose.renderScale();
		for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
			for (int other = head + 1; other < WorldInterfaceAnatomy.HEAD_COUNT; other++) {
				double gap = pose.headOffset(head).distanceTo(pose.headOffset(other)) * UNITS / scale;
				double needed = Math.max(WorldInterfaceRig.skullHalfUnits(head),
						WorldInterfaceRig.skullHalfUnits(other));
				assertTrue(gap > needed, "form " + form + " " + label + ": skulls " + head + " and "
						+ other + " are inside each other at " + gap + " units");
			}
		}
	}

	/** The flanks hang outward from the centre line, which is the whole read of three heads. */
	@Test
	void theFlanksSplayAwayFromTheCentreLine() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			WorldInterfaceRig.Pose pose = rest(form);
			double left = pose.headOffset(1).x;
			double right = pose.headOffset(2).x;
			assertTrue(left > 0.0D, "form " + form + ": the left head crossed to " + left);
			assertTrue(right < 0.0D, "form " + form + ": the right head crossed to " + right);
			assertEquals(left, -right, 1.0E-4D, "the flanks must mirror each other");
			// Wider than where they are mounted: the chain leans out, it does not merely hang.
			double mountX = WorldInterfaceAnatomy.headLocalUnits(0, 1)[0] * 0.34D
					* pose.renderScale() / UNITS;
			assertTrue(left > mountX, "form " + form + ": the left chain leans inward from its mount");
		}
	}

	/**
	 * The heads are in front of the storm, not behind it.
	 *
	 * <p>This is the axis correction. A living model is drawn under {@code scale(-1, -1, 1)} and a
	 * half turn, which maps the model's own -Z onto the entity's forward; the arithmetic this
	 * replaces negated Y and left Z alone, so every head box sat as far behind the body as the head
	 * it stood for was in front of it - close to twenty blocks at third form.
	 */
	@Test
	void headsSitInFrontOfTheBodyTheyHangFrom() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			WorldInterfaceRig.Pose pose = rest(form);
			for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
				assertTrue(pose.headOffset(head).z > 0.0D,
						"form " + form + " head " + head + " is behind the storm at "
								+ pose.headOffset(head).z);
			}
			// The necks lean forward along their length, so the skull leads its own root.
			assertTrue(pose.headOffset(0).z > pose.neckJointOffset(0, 0).z,
					"form " + form + ": the centre neck must lean forward, not back");
		}
	}

	/** An action moves the bones, and the rig is what says so on both sides of the network. */
	@Test
	void clipsMoveThePosedSkeleton() {
		// Wire id 7 is the hotbar gaze: the centre head locks onto one player and holds there.
		Vec3 idle = rest(2).headOffset(0);
		Vec3 gazing = WorldInterfaceRig.pose(2, 0.0F, 1.0F, 7, 3_000L).headOffset(0);
		assertTrue(idle.distanceTo(gazing) > 2.0D,
				"an action clip must move the head the boxes stand on: " + idle.distanceTo(gazing));
		// The lance drives the centre neck down through fifty-eight degrees.
		Vec3 lancing = WorldInterfaceRig.pose(2, 0.0F, 1.0F, 4, 4_500L).headOffset(0);
		assertTrue(idle.distanceTo(lancing) > 2.0D, "the lance must move the centre head");
		// And the drift alone moves it, without any action at all.
		Vec3 later = WorldInterfaceRig.pose(2, 60.0F, 1.0F, 0, 0L).headOffset(0);
		assertTrue(idle.distanceTo(later) > 0.25D, "the idle drift must reach the boxes too");
	}

	/** A drained pool sags the necks, and the boxes have to sag with them. */
	@Test
	void structuralWearMovesTheHeads() {
		Vec3 healthy = WorldInterfaceRig.pose(2, 40.0F, 1.0F, 0, 0L).headOffset(0);
		Vec3 spent = WorldInterfaceRig.pose(2, 40.0F, 0.0F, 0, 0L).headOffset(0);
		assertTrue(healthy.distanceTo(spent) > 1.0D,
				"the structural sag must reach the hit geometry: " + healthy.distanceTo(spent));
	}

	/**
	 * However the storm is posed, its parts stay attached to it.
	 *
	 * <p>The looser half of the contract, and the important one: a rig bug that flings a box across
	 * the arena is worse than the bind-pose boxes it replaces. Swept across every form, every live
	 * action, and a spread of clocks and health values.
	 */
	@Test
	void everyPosedPartStaysWithinReachOfTheStorm() {
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			// Generous: a lash throws a limb well past where it hangs, and the point of this test is
			// to catch a part that has left the arena, not to police choreography.
			double ceiling = WorldInterfaceAnatomy.tentacleDrop(form) * 1.6D + 24.0D;
			for (int action = 0; action <= WorldInterfaceClips.PROTOCOL_ACTION_COUNT + 1; action++) {
				for (long millis : new long[]{0L, 700L, 2_000L, 4_500L, 9_000L, 20_000L}) {
					for (float health : new float[]{1.0F, 0.5F, 0.0F}) {
						WorldInterfaceRig.Pose pose =
								WorldInterfaceRig.pose(form, millis / 50.0F, health, action, millis);
						for (int head = 0; head < WorldInterfaceAnatomy.HEAD_COUNT; head++) {
							assertFinite(pose.headOffset(head), ceiling, form, action, "head " + head);
							assertFinite(pose.neckJointOffset(head, 0), ceiling, form, action, "neck");
							assertFinite(pose.neckJointOffset(head, 1), ceiling, form, action, "neck");
						}
						for (int limb = 0; limb < WorldInterfaceAnatomy.tentacleCount(form); limb++) {
							assertFinite(pose.tendrilTipOffset(limb, true), ceiling, form, action, "limb");
						}
					}
				}
			}
		}
	}

	/**
	 * No bone jumps: nothing on the storm moves in one tick in a way its neighbouring ticks do not
	 * explain.
	 *
	 * <p>The complaint this exists to catch is "the boss teleports, especially the head", and it was
	 * not a rendering problem - three separate authored things produced it, none of them visible in
	 * a screenshot. {@code WorldInterfaceRig.actionCharge} fell from one to its idle sentinel in a
	 * single tick at two seconds into <em>every</em> action, undoing the whole of the three heads'
	 * lean between two frames. Every hold in {@code WorldInterfaceClips} unwound through a flat
	 * {@code seconds - 0.12F} release, so a hundred and eighty degrees of centre neck came off in two
	 * and a half ticks. And the recovery accents spaced their overshoot frames 0.04 to 0.11 seconds
	 * apart on {@code storm_body}, which every neck and limb hangs off.
	 *
	 * <p><b>Stated as a discontinuity, not as a speed limit.</b> That distinction is the whole test.
	 * A first attempt capped blocks-per-tick and immediately failed the grab, which hauls a skull
	 * across two and a half blocks a tick for half a second - and that is not a defect, it is a lunge,
	 * and the fight would be worse without it. What a player reads as a teleport is a single frame
	 * that does not belong to the frames around it, so what is measured is each step against the
	 * average of its neighbours. Sustained fast motion passes, however fast; one frame out of
	 * character fails, however small.
	 *
	 * <p>Swept per tick, which is the resolution the server publishes poses at. The neighbouring
	 * tests sample every 250ms and would step clean over every one of the three faults above.
	 */
	@Test
	void noBoneJumpsBetweenTwoTicks() {
		List<String> jumps = new ArrayList<>();
		for (int form = 0; form < WorldInterfaceAnatomy.FORM_COUNT; form++) {
			for (int action = 0; action <= WorldInterfaceClips.PROTOCOL_ACTION_COUNT + 1; action++) {
				int heads = WorldInterfaceAnatomy.HEAD_COUNT;
				int limbs = WorldInterfaceAnatomy.tentacleCount(form);
				int tracked = heads * 3 + limbs;
				double[][] steps = new double[tracked][SWEEP_TICKS];
				Vec3[] previous = null;
				for (int tick = 0; tick <= SWEEP_TICKS; tick++) {
					WorldInterfaceRig.Pose pose =
							WorldInterfaceRig.pose(form, tick, 0.5F, action, tick * 50L);
					Vec3[] now = new Vec3[tracked];
					for (int head = 0; head < heads; head++) {
						now[head] = pose.headOffset(head);
						now[heads + head * 2] = pose.neckJointOffset(head, 0);
						now[heads + head * 2 + 1] = pose.neckJointOffset(head, 1);
					}
					for (int limb = 0; limb < limbs; limb++) {
						now[heads * 3 + limb] = pose.tendrilTipOffset(limb, true);
					}
					if (previous != null) {
						for (int bone = 0; bone < tracked; bone++) {
							steps[bone][tick - 1] = now[bone].distanceTo(previous[bone]);
						}
					}
					previous = now;
				}
				for (int bone = 0; bone < tracked; bone++) {
					collectJumps(steps[bone], form, action, boneName(bone, heads), jumps);
				}
			}
		}
		assertTrue(jumps.isEmpty(), jumps.size() + " single-tick jumps - a frame that does not belong"
				+ " to the frames around it is what a player reads as a teleport: "
				+ jumps.stream().limit(20).toList());
	}

	/** Ticks swept per action; long enough to run past the longest clip and its held tail. */
	private static final int SWEEP_TICKS = 240;

	/**
	 * Below this a step is too small to be seen as a jump whatever its neighbours are doing, and
	 * flagging it would turn the test into a detector of floating-point noise in a still pose.
	 */
	private static final double JUMP_FLOOR_BLOCKS = 1.0D;

	/**
	 * How many times its neighbourhood a step may be before it stops reading as continuous motion.
	 *
	 * <p>Four is deliberately forgiving. Every fault this test was written against exceeded its
	 * neighbours by more than an order of magnitude - the head lean came off from a standing start -
	 * so the bar does not need to be tight to catch them, and a tight bar would start policing
	 * choreography instead.
	 */
	private static final double JUMP_RATIO = 4.0D;

	/** Ticks either side of a step that count as its neighbourhood. */
	private static final int JUMP_WINDOW = 4;

	private static void collectJumps(double[] steps, int form, int action, String bone,
			List<String> jumps) {
		for (int tick = 0; tick < steps.length; tick++) {
			double step = steps[tick];
			if (step < JUMP_FLOOR_BLOCKS) continue;
			double sum = 0.0D;
			int counted = 0;
			for (int near = tick - JUMP_WINDOW; near <= tick + JUMP_WINDOW; near++) {
				if (near == tick || near < 0 || near >= steps.length) continue;
				sum += steps[near];
				counted++;
			}
			double local = counted == 0 ? 0.0D : sum / counted;
			if (step > JUMP_RATIO * local) {
				jumps.add("form " + form + " action " + action + " tick " + tick + " " + bone + " moved "
						+ String.format("%.2f", step) + " against a neighbourhood of "
						+ String.format("%.2f", local));
			}
		}
	}

	private static String boneName(int bone, int heads) {
		if (bone < heads) return "skull " + bone;
		if (bone < heads * 3) return "neck joint " + (bone - heads);
		return "limb tip " + (bone - heads * 3);
	}

	/** Every bone the clips address must exist in the rig, or the server is posing a shorter storm. */
	@Test
	void theRigCarriesEveryBoneTheClipsDrive() {
		WorldInterfaceRig.Pose pose = rest(2);
		for (int action = 0; action <= WorldInterfaceClips.PROTOCOL_ACTION_COUNT + 1; action++) {
			for (WorldInterfaceClip clip : WorldInterfaceClips.clipsForAction(action)) {
				for (WorldInterfaceClip.Track track : clip.tracks()) {
					assertNotNull(pose.bone(track.bone()),
							"clip for action " + action + " drives a bone the rig does not carry: "
									+ track.bone());
				}
			}
		}
		for (WorldInterfaceClip clip : WorldInterfaceClips.idleClips()) {
			for (WorldInterfaceClip.Track track : clip.tracks()) {
				assertNotNull(pose.bone(track.bone()), "idle clip drives an unknown bone: " + track.bone());
			}
		}
	}

	/** A morph pinches the drawn body shut; the boxes have to pinch with it. */
	@Test
	void theMorphPinchReachesTheHitGeometry() {
		float full = WorldInterfaceRig.renderScale(1, 0, 0L);
		float pinched = WorldInterfaceRig.renderScale(1, 11, 1_500L);
		assertTrue(pinched < full * 0.6F,
				"the morph midpoint must shrink the storm: " + pinched + " against " + full);
		assertEquals(full, WorldInterfaceRig.renderScale(1, 11, 9_000L), 1.0E-6F,
				"past the morph window the scale returns to the form's own");
	}

	private static void assertFinite(Vec3 offset, double ceiling, int form, int action, String what) {
		assertTrue(Double.isFinite(offset.x) && Double.isFinite(offset.y) && Double.isFinite(offset.z),
				"form " + form + " action " + action + " " + what + " is not finite: " + offset);
		assertTrue(offset.length() < ceiling, "form " + form + " action " + action + " " + what
				+ " is " + offset.length() + " blocks from the storm, past " + ceiling);
	}

	private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
		Vec3 span = to.subtract(from);
		double lengthSquared = span.lengthSqr();
		if (lengthSquared < 1.0E-9D) return point.distanceTo(from);
		double along = Math.clamp(point.subtract(from).dot(span) / lengthSquared, 0.0D, 1.0D);
		return point.distanceTo(from.add(span.scale(along)));
	}
}
