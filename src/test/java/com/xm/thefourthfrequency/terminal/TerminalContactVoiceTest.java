package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of the voice table is that a player can tell the presses apart with their eyes on the
 * readout. Every assertion here is that property, not a copy of the numbers: a table that drifts
 * back into six near-identical clicks compiles, runs, and sounds exactly like the single click this
 * replaced, so nothing but a test catches it.
 */
final class TerminalContactVoiceTest {
	/** Anything outside this is either inaudible or clamped away by the engine. */
	private static final float MIN_PITCH = 0.5F;
	private static final float MAX_PITCH = 2.0F;

	@Test
	void everyVoiceStaysInsideTheEnginesPitchRangeAtEveryStage() {
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			for (int stage = 0; stage <= TerminalContactVoice.MAX_STAGE; stage++) {
				float pitch = voice.pitchAt(stage);
				assertTrue(pitch >= MIN_PITCH && pitch <= MAX_PITCH,
						voice + " leaves the audible pitch range at stage " + stage + ": " + pitch);
			}
			assertTrue(voice.relativeVolume() > 0.0F && voice.relativeVolume() <= 1.0F,
					voice + " has a relative volume outside (0, 1]");
		}
	}

	@Test
	void stageWearOnlyEverDarkensAndIsSubtleEnoughToMiss() {
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			assertEquals(voice.pitch(), voice.pitchAt(0), 1e-6F, voice + " must be unworn at stage 0");
			float previous = voice.pitchAt(0);
			for (int stage = 1; stage <= TerminalContactVoice.MAX_STAGE; stage++) {
				float current = voice.pitchAt(stage);
				assertTrue(current < previous, voice + " must keep darkening through stage " + stage);
				previous = current;
			}
			// The whole drift, end to end, stays under a tone and a half. Wide enough to survive a
			// side-by-side recording, narrow enough that no single session hears the panel change.
			float total = voice.pitch() - voice.pitchAt(TerminalContactVoice.MAX_STAGE);
			assertTrue(total / voice.pitch() < 0.15F, voice + " wears audibly within one session");
		}
	}

	@Test
	void stagesAboveAndBelowTheDeclaredRangeClampInsteadOfSilencingThePanel() {
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			assertEquals(voice.pitchAt(0), voice.pitchAt(-3), 1e-6F);
			assertEquals(voice.pitchAt(TerminalContactVoice.MAX_STAGE), voice.pitchAt(99), 1e-6F);
		}
	}

	/**
	 * Two voices sharing a recording have nothing but pitch and weight to tell them apart, so the
	 * gap between them has to be a real interval rather than a rounding difference. Voices built
	 * from different recordings are already unmistakable and are not held to this.
	 */
	@Test
	void voicesSharingARecordingAreSeparatedByAnAudibleInterval() {
		TerminalContactVoice[] voices = TerminalContactVoice.values();
		for (int i = 0; i < voices.length; i++) {
			for (int j = i + 1; j < voices.length; j++) {
				if (voices[i].sample() != voices[j].sample()) continue;
				float gap = Math.abs(voices[i].pitch() - voices[j].pitch());
				assertTrue(gap >= 0.10F, voices[i] + " and " + voices[j]
						+ " share " + voices[i].sample() + " and are only " + gap + " apart in pitch");
			}
		}
	}

	@Test
	void theHierarchyThePlayerIsSupposedToHear() {
		// Moving a highlight is the lightest thing the panel does; ordering the server about is the
		// heaviest. Everything the player can press sits between the two.
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			if (voice == TerminalContactVoice.MOVE || voice == TerminalContactVoice.ACKNOWLEDGE) continue;
			assertTrue(voice.relativeVolume() > TerminalContactVoice.MOVE.relativeVolume(),
					voice + " must outweigh a highlight moving");
			assertTrue(voice.relativeVolume() <= TerminalContactVoice.COMMIT.relativeVolume(),
					voice + " must not outweigh committing to the server");
		}
		// Going in and coming back out are a direction, not two spellings of the same click.
		assertTrue(TerminalContactVoice.OPEN.pitch() > TerminalContactVoice.BACK.pitch());
		assertTrue(TerminalContactVoice.OPEN.relativeVolume() > TerminalContactVoice.BACK.relativeVolume());
		// The lamp going out is the machine finishing, not the player starting: quieter and lower
		// than anything a press produces.
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			if (voice == TerminalContactVoice.ACKNOWLEDGE) continue;
			assertTrue(TerminalContactVoice.ACKNOWLEDGE.relativeVolume() < voice.relativeVolume(),
					"the unread lamp must stay under " + voice);
			assertTrue(TerminalContactVoice.ACKNOWLEDGE.pitch() < voice.pitch(),
					"the unread lamp must stay under " + voice + " in pitch");
		}
		// The bolt is reserved. If a second voice ever borrows it, the one sound in the panel that
		// means "the server was told" stops meaning it.
		for (TerminalContactVoice voice : TerminalContactVoice.values()) {
			if (voice == TerminalContactVoice.COMMIT) continue;
			assertNotEquals(TerminalContactVoice.Sample.BOLT, voice.sample(),
					voice + " must not borrow the recording that means a committed order");
		}
	}
}
