package com.xm.thefourthfrequency.correction;

import net.minecraft.util.Mth;

/** Shared trigger-time orientation for every viewpoint/body separation presentation. */
public final class ViewpointOrientationPolicy {
	/**
	 * How far from level the separated view is allowed to start.
	 *
	 * <p>The pitch used to be discarded outright - the separated camera took the player's yaw and a
	 * flat zero - on the reasoning that a player looking up or down at the instant of separation
	 * would leave the fixed camera aimed away from the path their still-controllable body walks. The
	 * reasoning is sound and the cure was worse: a view that leaves the body is the one moment where
	 * the player is certain to notice a cut, and snapping the horizon level is a cut. It reads as the
	 * anomaly starting a video rather than as the player's own eyes coming loose.
	 *
	 * <p>So the pitch is inherited, and the original concern is answered by a bound instead of by
	 * deletion. Forty degrees keeps the body inside the frame from any starting angle while leaving
	 * every ordinary head position - which is most of the range a player actually holds - untouched.
	 * Only a deliberate stare at their own feet or straight up is corrected, and even then it is
	 * softened rather than flattened.
	 */
	public static final float MAX_PITCH_DEGREES = 40.0F;

	private ViewpointOrientationPolicy() {
	}

	/**
	 * The orientation a separated view starts at, given where the player was looking when it fired.
	 *
	 * @param playerPitch the player's own pitch, Minecraft-signed: negative looks up
	 */
	public static Orientation facePlayerForward(float playerYaw, float playerPitch) {
		float pitch = Float.isFinite(playerPitch) ? playerPitch : 0.0F;
		return new Orientation(Float.isFinite(playerYaw) ? playerYaw : 0.0F,
				Mth.clamp(pitch, -MAX_PITCH_DEGREES, MAX_PITCH_DEGREES));
	}

	public record Orientation(float yaw, float pitch) {
	}
}
