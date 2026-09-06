package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalHandheldPoseTest {
	/**
	 * The device's own dimensions, in blocks, from the generated model.
	 *
	 * <p>Restated here rather than read from the JSON because what this file is checking is that
	 * the presentation stays inside the frame, and that is arithmetic on the size - if the model
	 * ever gets bigger, this test should fail rather than quietly follow it.</p>
	 */
	private static final float DEVICE_WIDTH = 15.0F / 16.0F;
	private static final float DEVICE_HEIGHT = 8.0F / 16.0F;
	/** How far apart vanilla's own map-hand pose puts the two arms, per side. */
	private static final float VANILLA_HAND_HALF_SPAN = 0.30F;
	/** The drop vanilla's map-hand pose applies after being given a position. */
	private static final float HAND_DROP_COMPENSATION = 0.60F;
	/** Minecraft's default vertical field of view. Held items are drawn at this, not at the option. */
	private static final double DEFAULT_FOV_DEGREES = 70.0D;
	/** The narrowest window a player is likely to use. Width is what binds a 2:1 device. */
	private static final double NARROW_ASPECT = 4.0D / 3.0D;

	/**
	 * At full open the CRT has the frame - and stops there.
	 *
	 * <p>Two terms are easy to leave out of this arithmetic and both were, once. The lean narrows
	 * the field of view, which magnifies everything at the item's depth by the same factor; and the
	 * device is twice as wide as it is tall, so the constraint that bites is the width of the
	 * narrowest window, not the height of the widest.</p>
	 */
	@Test
	void fullyOpenDeviceFillsTheFrameWithoutLeavingIt() {
		var open = TerminalHandheldPose.presentation(1.0D, 0L);
		assertEquals(0.0F, open.pitch(), 1.0E-6F, "an opened terminal must be square to the camera");
		assertTrue(open.fovScale() < 1.0F && open.fovScale() >= 0.8F,
				() -> "the lean must be mild and inward: " + open.fovScale());

		double leanedFov = DEFAULT_FOV_DEGREES * open.fovScale();
		double visibleHeight = 2.0D * Math.abs(open.z()) * Math.tan(Math.toRadians(leanedFov / 2.0D));
		double visibleWidth = visibleHeight * NARROW_ASPECT;
		double deviceHeight = DEVICE_HEIGHT * open.scale();
		double deviceWidth = DEVICE_WIDTH * open.scale();
		assertTrue(deviceWidth > visibleWidth * 0.75D,
				() -> "the CRT has to actually take the frame: " + deviceWidth + " of " + visibleWidth);
		// The failure this exists for: an earlier pass sized the device against the un-narrowed
		// frame and at a 16:9 aspect, and the brass rim went off all four edges of a real window.
		assertTrue(deviceWidth < visibleWidth,
				() -> "the device is wider than the frame: " + deviceWidth + " > " + visibleWidth);
		assertTrue(deviceHeight < visibleHeight,
				() -> "the device is taller than the frame: " + deviceHeight + " > " + visibleHeight);
		assertTrue(Math.abs(open.z()) > 0.4D,
				() -> "the device must stay well clear of the near plane: z=" + open.z());
	}

	/** At rest it is carried: low, tipped away, unmagnified, and not touching the camera. */
	@Test
	void restingPresentationIsCarriedLowButStaysOnScreen() {
		var rest = TerminalHandheldPose.presentation(0.0D, 0L);
		assertEquals(1.0F, rest.fovScale(), 1.0E-6F);
		// Negative: laid back so the face turns up toward a player looking down at it. The other
		// sign shows them the top edge of a closed box instead.
		assertTrue(rest.pitch() < -15.0F,
				() -> "a device held square-on at rest reads as already open: " + rest.pitch());
		assertTrue(rest.scale() < 1.0F, "a carried device must not be magnified");
		var open = TerminalHandheldPose.presentation(1.0D, 0L);
		// As a screen angle, not a world offset. The two ends sit at different depths, so the raw
		// heights understate the travel - which is what a hard world-space threshold here measured.
		double restAngle = rest.y() / Math.abs(rest.z());
		double openAngle = open.y() / Math.abs(open.z());
		assertTrue(openAngle - restAngle > 0.25D,
				() -> "the device must visibly travel upward: " + restAngle + " -> " + openAngle);

		// Low is not the same as gone. An earlier resting height put the whole device below the
		// bottom edge, so a player carrying a terminal could not see that they were holding one.
		double visibleHalfHeight = Math.abs(rest.z())
				* Math.tan(Math.toRadians(DEFAULT_FOV_DEGREES / 2.0D));
		double deviceTop = rest.y() + DEVICE_HEIGHT * rest.scale() / 2.0D;
		assertTrue(deviceTop < visibleHalfHeight,
				() -> "a carried terminal must not block the view: top=" + deviceTop);
		assertTrue(rest.y() + DEVICE_HEIGHT * rest.scale() / 2.0D > -visibleHalfHeight + 0.05D,
				() -> "a carried terminal must stay visible: top=" + deviceTop
						+ ", frame bottom=" + -visibleHalfHeight);
	}

	/**
	 * The hands line up with the device <em>on screen</em>, not in world space.
	 *
	 * <p>This is the one that a world-space offset gets wrong, and it is why the grip looked
	 * detached. The arms are life-sized geometry and stay at vanilla's depth; the device sits much
	 * nearer the camera, so it is drawn smaller to look the same size. A world offset covers a
	 * screen angle proportional to offset / depth, so the same number at two depths lands in two
	 * different places in the frame.</p>
	 *
	 * <p>Asserted as screen angles, because that is the only frame in which "the hands are holding
	 * the edges" is a statement about what the player sees.</p>
	 */
	@Test
	void handsLineUpWithTheDeviceEdgesInScreenSpace() {
		for (int step = 0; step <= 20; step++) {
			final double openness = step / 20.0D;
			assertHandsHoldTheEdges(TerminalHandheldPose.presentation(openness, 0L),
					"openness " + openness);
		}

		var rest = TerminalHandheldPose.presentation(0.0D, 0L);
		var open = TerminalHandheldPose.presentation(1.0D, 0L);
		assertTrue(open.handY() > rest.handY(), "the hands must come up with the device");
		assertTrue(open.handSpread() > rest.handSpread(), "the hands must open out as it grows");
		// Life-sized: the arms stay at vanilla's own depth however near the device is pulled.
		assertEquals(rest.handZ(), open.handZ(), 1.0E-6F);
		assertTrue(Math.abs(rest.handZ()) > Math.abs(rest.z()),
				"the device is drawn nearer the camera than the arms holding it");
	}

	/**
	 * The grip, as the player sees it: both hands under the casing's lower edge and outside its side
	 * walls, in screen angles rather than world offsets.
	 *
	 * <p>Angles because the arms and the device are drawn at different depths, which is the mistake
	 * that made the grip look detached: a world offset covers a screen angle proportional to
	 * offset / depth, so the same number at two depths lands in two different places in the frame.
	 * {@code HAND_DROP_COMPENSATION} is cancelled out again here because vanilla's own map-hand pose
	 * applies that drop after this point.</p>
	 */
	private static void assertHandsHoldTheEdges(TerminalHandheldPose.Presentation pose, String where) {
		final double deviceBottom = (pose.y() - DEVICE_HEIGHT * pose.scale() / 2.0F)
				/ Math.abs(pose.z());
		final double deviceSide = DEVICE_WIDTH * pose.scale() / 2.0F / Math.abs(pose.z());
		final double handGrip = (pose.handY() - HAND_DROP_COMPENSATION) / Math.abs(pose.handZ());
		final double handSide = (pose.handSpread() + VANILLA_HAND_HALF_SPAN) / Math.abs(pose.handZ());
		assertTrue(handGrip <= deviceBottom + 1.0E-4D,
				() -> "hands climbed above the device's lower edge at " + where
						+ ": " + handGrip + " vs " + deviceBottom);
		assertTrue(handSide >= deviceSide - 1.0E-4D,
				() -> "hands inside the side wall at " + where
						+ ": " + handSide + " vs " + deviceSide);
	}

	/**
	 * The device sits close enough to the camera to survive an ordinary room.
	 *
	 * <p>First-person hands are drawn against the world's depth buffer, so any surface nearer than
	 * the device removes it - and unlike vanilla's small corner-of-the-screen item, this one
	 * disappears whole. Looking up at a ceiling was enough.</p>
	 */
	@Test
	void deviceIsDrawnNearEnoughNotToBeEatenByNearbySurfaces() {
		for (int step = 0; step <= 20; step++) {
			final double openness = step / 20.0D;
			final var pose = TerminalHandheldPose.presentation(openness, 0L);
			assertTrue(Math.abs(pose.z()) < 0.5F,
					() -> "the device is far enough out to be occluded indoors: z=" + pose.z());
			// Still clear of the near plane, which vanilla puts at 0.05.
			assertTrue(Math.abs(pose.z()) > 0.2F,
					() -> "the device is inside the near plane: z=" + pose.z());
		}
	}

	@Test
	void opennessRunsBothWaysAndSaturatesAtItsEnds() {
		assertEquals(0.0D, TerminalHandheldPose.openness(true, 0L), 1.0E-9D);
		assertEquals(1.0D, TerminalHandheldPose.openness(true, TerminalHandheldPose.OPEN_MILLIS), 1.0E-9D);
		assertEquals(1.0D, TerminalHandheldPose.openness(true, TerminalHandheldPose.OPEN_MILLIS * 4), 1.0E-9D);
		assertEquals(1.0D, TerminalHandheldPose.openness(false, 0L), 1.0E-9D);
		assertEquals(0.0D, TerminalHandheldPose.openness(false, TerminalHandheldPose.CLOSE_MILLIS), 1.0E-9D);
		assertEquals(0.0D, TerminalHandheldPose.openness(false, TerminalHandheldPose.CLOSE_MILLIS * 4), 1.0E-9D);

		// Monotone in both directions, and eased rather than linear - the ease is what stops the
		// travel dead at the top instead of arriving at full speed.
		double previous = -1.0D;
		for (long elapsed = 0L; elapsed <= TerminalHandheldPose.OPEN_MILLIS; elapsed += 5L) {
			double value = TerminalHandheldPose.openness(true, elapsed);
			assertTrue(value >= previous, "opening must not go backwards");
			previous = value;
		}
		assertTrue(TerminalHandheldPose.openness(true, TerminalHandheldPose.OPEN_MILLIS / 2)
				> 0.5D, "easeOutQuad covers more than half the distance in half the time");
	}

	/**
	 * The whole performance is continuous.
	 *
	 * <p>Sampled at a rate far above any real frame rate: a jump between adjacent samples here is a
	 * jump the player would see as a snap, and the resting frame is on screen for the entire game.</p>
	 */
	@Test
	void presentationNeverJumpsBetweenAdjacentFrames() {
		var previous = TerminalHandheldPose.presentation(0.0D, 0L);
		for (int step = 1; step <= 1000; step++) {
			final double openness = step / 1000.0D;
			final var pose = TerminalHandheldPose.presentation(openness, 0L);
			final var last = previous;
			assertTrue(Math.abs(pose.y() - last.y()) < 0.01F
							&& Math.abs(pose.z() - last.z()) < 0.01F
							&& Math.abs(pose.handY() - last.handY()) < 0.01F,
					() -> "position stepped at openness " + openness);
			assertTrue(Math.abs(pose.pitch() - last.pitch()) < 1.0F,
					() -> "rotation stepped at openness " + openness);
			assertTrue(Math.abs(pose.scale() - last.scale()) < 0.05F
							&& Math.abs(pose.fovScale() - last.fovScale()) < 0.01F,
					() -> "scale or field of view stepped at openness " + openness);
			previous = pose;
		}
	}

	/**
	 * Hitting something shoves the whole rig forward and down, and it comes back on its own.
	 *
	 * <p>The regression this exists for is an absence rather than a wrong number: the two-handed
	 * pass replaces vanilla's entire first-person rendering, and vanilla's swing went with it. The
	 * terminal was the one held item in the game that did not move when the player mined, punched or
	 * opened anything - the world reacted and the device sat welded to the lens.</p>
	 *
	 * <p>Asserted as a shove rather than as vanilla's arm rotation because there is no free arm: both
	 * hands are on the casing, and rotating it about a shoulder would sweep a frame-wide CRT out of
	 * view and back.</p>
	 */
	@Test
	void swingShovesTheWholeRigForwardAndDownAndComesBack() {
		// Zero at both ends, so a hand that never swung and a hand that has finished are the same
		// pose and nothing has to be reset. Full travel a quarter of the way in - that early peak is
		// what makes a hit read as impact instead of as a wave.
		assertEquals(0.0F, TerminalHandheldPose.swingEnvelope(0.0D), 1.0E-6F);
		assertEquals(0.0F, TerminalHandheldPose.swingEnvelope(1.0D), 1.0E-6F);
		assertEquals(1.0F, TerminalHandheldPose.swingEnvelope(0.25D), 1.0E-6F);
		assertTrue(TerminalHandheldPose.swingEnvelope(0.6D) < TerminalHandheldPose.swingEnvelope(0.25D),
				"the second half of the swing has to be the recovery");
		// Clamped, because vanilla's counter briefly runs past its own end between ticks.
		assertEquals(0.0F, TerminalHandheldPose.swingEnvelope(-0.5D), 1.0E-6F);
		assertEquals(0.0F, TerminalHandheldPose.swingEnvelope(4.0D), 1.0E-6F);

		var still = TerminalHandheldPose.presentation(0.0D, 0L);
		var jab = TerminalHandheldPose.presentation(0.0D, 0L, 0.25D);
		assertTrue(jab.y() < still.y(), () -> "the shove has to go down: " + jab.y() + " vs " + still.y());
		assertTrue(Math.abs(jab.z()) > Math.abs(still.z()),
				() -> "and forward, away from the camera: " + jab.z() + " vs " + still.z());
		assertTrue(jab.pitch() > still.pitch(),
				() -> "and drive the far edge into the blow: " + jab.pitch() + " vs " + still.pitch());
		// The raise owns the size and the camera; a punch is not a zoom.
		assertEquals(still.scale(), jab.scale(), 1.0E-6F, "a swing must not magnify the device");
		assertEquals(still.fovScale(), jab.fovScale(), 1.0E-6F, "a swing must not move the camera");

		// The arms travel with the casing rather than the casing sliding out of them.
		assertTrue(jab.handY() < still.handY(), "the grip has to follow the shove down");
		assertEquals(still.handZ(), jab.handZ(), 1.0E-6F, "the arms stay life-sized through a swing");
		for (int step = 0; step <= 20; step++) {
			final double swing = step / 20.0D;
			assertHandsHoldTheEdges(TerminalHandheldPose.presentation(0.0D, 0L, swing), "swing " + swing);
		}

		// Still nearer the camera than vanilla's own item depth, so the shove cannot push the device
		// out into the range where an ordinary wall eats the whole of it.
		assertTrue(Math.abs(jab.z()) < Math.abs(jab.handZ()),
				() -> "the shove pushed the device past the arms holding it: z=" + jab.z());
		// And not off the bottom edge either: the drop is a jolt, not a stow.
		double visibleHalfHeight = Math.abs(jab.z())
				* Math.tan(Math.toRadians(DEFAULT_FOV_DEGREES / 2.0D));
		double deviceTop = jab.y() + DEVICE_HEIGHT * jab.scale() / 2.0D;
		assertTrue(deviceTop > -visibleHalfHeight + 0.05D,
				() -> "the shove put the device off the bottom of the frame: top=" + deviceTop);

		// It returns to exactly where it started, without anything clearing it.
		var recovered = TerminalHandheldPose.presentation(0.0D, 0L, 1.0D);
		assertEquals(still.y(), recovered.y(), 1.0E-6F);
		assertEquals(still.z(), recovered.z(), 1.0E-6F);
		assertEquals(still.pitch(), recovered.pitch(), 1.0E-6F);
		assertEquals(still.handY(), recovered.handY(), 1.0E-6F);
	}

	/**
	 * The raise and a swing never add up.
	 *
	 * <p>Opening the terminal <em>is</em> a swing as far as the client is concerned - the open
	 * request answers {@code InteractionResult.SUCCESS}, which carries {@code SwingSource.CLIENT},
	 * so {@code Minecraft.startUseItem} swings the hand on the same frame the raise begins. The
	 * animator refuses that one at source; this is the second half of the same rule, held here so
	 * that a device pressed against the lens cannot be punched out of the frame whatever drives it.
	 * </p>
	 */
	@Test
	void anOpenedTerminalIsBracedAndTakesNoSwingAtAll() {
		var open = TerminalHandheldPose.presentation(1.0D, 0L);
		for (int step = 0; step <= 20; step++) {
			final double swing = step / 20.0D;
			final var swung = TerminalHandheldPose.presentation(1.0D, 0L, swing);
			assertEquals(open.y(), swung.y(), 1.0E-6F, () -> "the page moved at swing " + swing);
			assertEquals(open.z(), swung.z(), 1.0E-6F, () -> "the page moved at swing " + swing);
			assertEquals(open.pitch(), swung.pitch(), 1.0E-6F, () -> "the page tipped at swing " + swing);
			assertEquals(open.handY(), swung.handY(), 1.0E-6F, () -> "the grip moved at swing " + swing);
		}

		// And it fades in on the way down rather than switching on at the bottom of the travel.
		double previous = 0.0D;
		for (int step = 20; step >= 0; step--) {
			final double openness = step / 20.0D;
			final double dropped = TerminalHandheldPose.presentation(openness, 0L).y()
					- TerminalHandheldPose.presentation(openness, 0L, 0.25D).y();
			assertTrue(dropped >= previous - 1.0E-6D,
					() -> "the swing must grow as the device comes down, not step in at openness "
							+ openness);
			previous = dropped;
		}
		assertTrue(previous > 0.0D, "a carried device has to take the full swing");
	}

	/** The off-hand fallback tilts the device without magnifying it or moving the camera. */
	@Test
	void oneHandedFallbackOnlyTiltsAndBreathes() {
		var right = TerminalHandheldPose.carried(0L, true);
		var left = TerminalHandheldPose.carried(0L, false);
		assertEquals(right.pitch(), left.pitch(), 1.0E-6F);
		assertEquals(right.roll(), -left.roll(), 1.0E-6F);
		assertTrue(right.pitch() > 0.0F && right.pitch() < 45.0F,
				() -> "a carried tilt of " + right.pitch() + " degrees is not a carry");
		assertTrue(Math.abs(right.lift()) < 0.02F, "only the idle breath may move a carried device");
	}

	/**
	 * The idle breath is small, slow, and gone once the terminal is up.
	 *
	 * <p>It modulates position rather than brightness, but it is on screen for the whole game, so
	 * it is held to the same 3 Hz standard as anything that does modulate brightness. A device
	 * pressed against the lens that is still bobbing reads as a camera fault, so it fades out with
	 * the travel.</p>
	 */
	@Test
	void breathIsSmallWellUnderThreeHertzAndFadesOutAsTheDeviceComesUp() {
		double frequency = 1000.0D / TerminalHandheldPose.BREATH_PERIOD_MILLIS;
		assertTrue(frequency < 0.5D, () -> "breath at " + frequency + " Hz is not a breath");

		float largest = 0.0F;
		for (long millis = 0L; millis < (long) TerminalHandheldPose.BREATH_PERIOD_MILLIS; millis += 5L) {
			largest = Math.max(largest, Math.abs(TerminalHandheldPose.breathOffset(millis)));
		}
		final float peak = largest;
		assertTrue(peak > 0.0F && peak < 0.02F, () -> "breath amplitude " + peak + " is not subtle");

		// A quarter of the way through the cycle is the top of the sine, so this is the largest the
		// breath ever is - and at full open it has to be gone entirely.
		long crest = (long) (TerminalHandheldPose.BREATH_PERIOD_MILLIS / 4.0D);
		assertTrue(TerminalHandheldPose.presentation(0.0D, crest).y()
				> TerminalHandheldPose.presentation(0.0D, 0L).y(),
				"the resting device must actually breathe");
		assertEquals(TerminalHandheldPose.presentation(1.0D, 0L).y(),
				TerminalHandheldPose.presentation(1.0D, crest).y(), 1.0E-6F,
				"an opened terminal must not bob against the lens");
	}
}
