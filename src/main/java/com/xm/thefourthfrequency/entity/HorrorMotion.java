package com.xm.thefourthfrequency.entity;

/** Continuous motion curves shared by the entity models and their behavioural tests. */
public final class HorrorMotion {
	private HorrorMotion() { }
	public static float ease(float x) {
		x = Math.clamp(x, 0, 1);
		return x * x * x * (x * (x * 6 - 15) + 10);
	}
	public static float envelope(float time, float start, float attack, float hold, float release) {
		return ease((time - start) / attack) * (1 - ease((time - start - attack - hold) / release));
	}
	/** A held curl with delayed release, instead of every finger oscillating in unison. */
	public static float digitCurl(float age, int index, float movement) {
		float cycle = (age + (index / 4) * 13.7F) % 173F;
		float curl = envelope(cycle, 32 + index % 4 * 2.6F, 14, 19, 30);
		return 0.09F + curl * (0.42F + movement * 0.3F);
	}
	/** A stance occupies 64% of the cycle. A swing lifts only its own foot. */
	public static LegPose spiderLeg(float phase, float amount, float bodyLift) {
		float cycle = (float) (phase / (Math.PI * 2));
		cycle -= (float) Math.floor(cycle);
		float swing = Math.clamp((cycle - .64F) / .36F, 0, 1);
		float lift = (float) Math.pow(Math.sin(swing * Math.PI), 2) * 3.8F * amount;
		float sweep = cycle < .64F ? 1 - 2 * ease(cycle / .64F) : -1 + 2 * ease(swing);
		float reach = 10.8F, drop = 9.5F + bodyLift - lift;
		float upper = 8, lower = 17.5F;
		float bend = (float) Math.acos(Math.clamp((reach * reach + drop * drop - upper * upper - lower * lower)
				/ (2 * upper * lower), -1, 1));
		float hip = (float) (Math.atan2(drop, reach) - Math.atan2(lower * Math.sin(bend), upper + lower * Math.cos(bend)));
		return new LegPose(hip, bend, sweep * .38F * amount, lift, cycle < .64F);
	}
	public record LegPose(float hip, float knee, float yaw, float lift, boolean planted) { }
}
