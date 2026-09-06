package com.xm.thefourthfrequency.pursuit;

/** Three progressively more human-looking personal Corrector forms. */
public final class PursuitFormPolicy {
	private static final Form[] FORMS = {
			new Form(1, "soundseeker", 60 * 20, "silence_crouch_line_of_sight"),
			new Form(2, "interceptor", 85 * 20, "backtrack_turn_change_elevation"),
			new Form(3, "interface_corrector", 110 * 20, "trust_continuous_waveform")
	};

	private PursuitFormPolicy() {
	}

	public static Form forForm(int form) {
		return FORMS[Math.clamp(form, 1, PursuitProgressPolicy.FORM_COUNT) - 1];
	}

	public record Form(int number, String id, int durationTicks, String counterplay) {
	}
}
