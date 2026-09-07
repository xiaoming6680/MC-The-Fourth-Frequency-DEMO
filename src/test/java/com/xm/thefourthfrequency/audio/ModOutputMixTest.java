package com.xm.thefourthfrequency.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ModOutputMixTest {
	@Test void effectsGainPresenceWithoutBringingBackLoudTerminalOrNoiseBeds() {
		assertEquals(.52F, ModOutputMix.apply("thefourthfrequency", "world_interface_laser", .2F), 1e-6);
		assertEquals(.25F, ModOutputMix.apply("thefourthfrequency", "terminal_click", .2F), 1e-6);
		assertEquals(.2F, ModOutputMix.apply("thefourthfrequency", "music_end", .2F), 1e-6);
		for (String event : new String[]{"signal_static", "world_interface_ambient_3", "terminal_carrier"})
			assertEquals(.17F, ModOutputMix.apply("thefourthfrequency", event, .2F), 1e-6);
		assertEquals(.16F, ModOutputMix.apply("thefourthfrequency", "unrendered_layer_ambience", .2F), 1e-6);
	}

	@Test void unrelatedAudioKeepsItsOriginalGain() {
		assertEquals(.2F, ModOutputMix.apply("minecraft", "entity.zombie.ambient", .2F));
		assertEquals(.2F, ModOutputMix.apply("othermod", "world_interface_laser", .2F));
	}

	@Test void muteAndOutputCeilingRemainEffective() {
		assertEquals(0, ModOutputMix.apply("thefourthfrequency", "world_interface_laser", 0));
		assertEquals(1, ModOutputMix.apply("thefourthfrequency", "world_interface_laser", .9F));
		assertEquals(0, ModOutputMix.apply("thefourthfrequency", "world_interface_laser", Float.NaN));
	}
}
