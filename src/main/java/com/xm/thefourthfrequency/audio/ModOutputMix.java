package com.xm.thefourthfrequency.audio;

import java.util.Set;

/** Client output gain only: raw sound volume still defines its spatial reach. */
public final class ModOutputMix {
	public static final float EFFECT_GAIN = 1.8F;
	public static final float TERMINAL_GAIN = 1.25F;
	private static final Set<String> BEDS = Set.of("signal_carrier", "signal_static",
			"signal_tape_hiss", "signal_dead_air", "unrendered_layer_ambience", "terminal_carrier");

	private ModOutputMix() {}

	public static float apply(String namespace, String event, float original) {
		if (!"thefourthfrequency".equals(namespace)) return original;
		if (!Float.isFinite(original)) return 0;
		float gain = event.startsWith("music_") || BEDS.contains(event)
				|| event.startsWith("world_interface_ambient_") ? 1
				: event.startsWith("terminal_") ? TERMINAL_GAIN : EFFECT_GAIN;
		return Math.clamp(original * gain, 0, 1);
	}
}
