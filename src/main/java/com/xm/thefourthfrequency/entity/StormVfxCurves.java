package com.xm.thefourthfrequency.entity;

/** Time envelopes for expanding pressure fronts and inward gathering strands. */
public final class StormVfxCurves {
	private StormVfxCurves() { }
	public static float presence(float age, float duration) {
		if (duration <= 0 || age <= 0 || age >= duration) return 0;
		return HorrorMotion.ease(age / Math.min(5, duration * .15F))
				* (1 - HorrorMotion.ease((age - duration * .65F) / (duration * .35F)));
	}
	public static float shockRadius(float age, float duration, float radius) {
		float t = Math.clamp(age / Math.max(1, duration), 0, 1);
		return radius * (1 - (1-t)*(1-t)*(1-t));
	}
	public static float strandPhase(float age, int strand) {
		float phase = age * .021F + strand * .381966F;
		return phase - (float) Math.floor(phase);
	}
}
