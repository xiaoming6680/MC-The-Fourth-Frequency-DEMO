package com.xm.thefourthfrequency.correction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewpointOrientationPolicyTest {
	/**
	 * A view that comes loose starts from where the player was already looking.
	 *
	 * <p>The pitch used to be thrown away and replaced with a flat zero, so separating while looking
	 * anywhere but dead level snapped the horizon. That is a cut, at the one moment the player is
	 * guaranteed to be paying attention to their own eyes, and it read as the anomaly starting a
	 * video rather than as their viewpoint detaching.
	 */
	@Test
	void separatedViewInheritsWhereThePlayerWasLooking() {
		var level = ViewpointOrientationPolicy.facePlayerForward(73.0F, 0.0F);
		assertEquals(73.0F, level.yaw());
		assertEquals(0.0F, level.pitch());

		var down = ViewpointOrientationPolicy.facePlayerForward(-120.0F, 22.0F);
		assertEquals(-120.0F, down.yaw());
		assertEquals(22.0F, down.pitch(), "an ordinary downward glance must survive intact");

		var up = ViewpointOrientationPolicy.facePlayerForward(5.0F, -31.0F);
		assertEquals(-31.0F, up.pitch(), "and so must an ordinary upward one");
	}

	/**
	 * The bound that replaced deleting the pitch: the body still has to be findable in frame.
	 *
	 * <p>This is the concern the old flat zero was protecting - a player staring at their own feet
	 * would otherwise leave the fixed camera pointing at the floor while their body walks out of
	 * shot. It is answered by clamping rather than by discarding, so the correction only touches the
	 * extremes it was written for.
	 */
	@Test
	void anExtremeStareIsSoftenedRatherThanFlattened() {
		var feet = ViewpointOrientationPolicy.facePlayerForward(0.0F, 90.0F);
		assertEquals(ViewpointOrientationPolicy.MAX_PITCH_DEGREES, feet.pitch());
		var sky = ViewpointOrientationPolicy.facePlayerForward(0.0F, -90.0F);
		assertEquals(-ViewpointOrientationPolicy.MAX_PITCH_DEGREES, sky.pitch());
		assertTrue(ViewpointOrientationPolicy.MAX_PITCH_DEGREES > 0.0F
						&& ViewpointOrientationPolicy.MAX_PITCH_DEGREES < 90.0F,
				"the bound must leave the ordinary head range alone without ever aiming at the floor");
	}

	/** A non-finite rotation must not reach the camera as a NaN it would render from. */
	@Test
	void nonFiniteRotationsFallBackToLevel() {
		var broken = ViewpointOrientationPolicy.facePlayerForward(Float.NaN, Float.NaN);
		assertEquals(0.0F, broken.yaw());
		assertEquals(0.0F, broken.pitch());
	}
}
