package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * The continuous noise floor under the first-entry corruption.
 *
 * <p>Before this existed the sequence had two one-shots and silence between them, which is the
 * sound of a cutscene. What sells a tape is the medium being audible the entire time: hiss that
 * was always there, static that joins when the picture goes, dead air that is unmistakably an
 * open channel with nothing on it.</p>
 *
 * <p>Separate from {@link SignalBedController} on purpose - that one requires a live level and
 * player, and stops itself the moment either is missing, which is exactly the situation the
 * loading screen is. These beds are owned by the loading screen and die with it.</p>
 */
public final class AlphaCorruptionAudio {
	private static final float HISS_VOLUME = 0.42F;
	private static final float STATIC_VOLUME = 0.50F;
	private static final float DEAD_AIR_VOLUME = 0.66F;
	private static final float CARRIER_LOST_VOLUME = 0.62F;
	private static final float VOLUME_EASE = 0.12F;
	/** About a second and a half of tail - long enough that no frame is the one it stopped on. */
	private static final float FADE_OUT_EASE = 0.055F;

	private static final Map<Bed, TapeLoop> ACTIVE = new EnumMap<>(Bed.class);
	private static boolean carrierLostPlayed;

	private AlphaCorruptionAudio() {
	}

	/**
	 * Drives every bed from the screen tick, so the mix cannot drift out of step with the
	 * picture even if a frame is dropped.
	 */
	public static void tick(Minecraft client, int screenTicks) {
		setTarget(client, Bed.TAPE_HISS, hissTarget(screenTicks), false);
		// The wall is the one beat in the sequence that cuts rather than ramps, so its noise floor
		// cuts with it. Eased like everything else, the static was a full second behind the frame the
		// picture died on, which reads as a sound cue played after a slide rather than as one signal
		// going. Only this tick snaps; from the next one the bed eases normally again.
		setTarget(client, Bed.STATIC, staticTarget(screenTicks),
				screenTicks == AlphaLoadTimeline.FLOOD_START_TICK);
		setTarget(client, Bed.DEAD_AIR, deadAirTarget(screenTicks), false);
		if (!carrierLostPlayed && screenTicks >= AlphaLoadTimeline.LEGACY_RECOVERY_START_TICK) {
			carrierLostPlayed = true;
			client.getSoundManager().play(SimpleSoundInstance.forUI(
					ModSounds.SIGNAL_CARRIER_LOST, 1.0F, CARRIER_LOST_VOLUME));
		}
	}

	/**
	 * Releases every bed with a tail. Must run when the loading screen goes away by any route - a
	 * bed that outlives its screen would follow the player into a world that has no idea why it
	 * is hissing.
	 *
	 * <p>Cutting them dead on the frame the world appears is its own kind of announcement: the
	 * silence arrives on a hard edge and marks exactly where the sequence ended, which invites
	 * the player to file everything before that edge as a cutscene. Letting the noise floor recede
	 * over a second or so means there is no frame at which it stopped - the world simply comes up
	 * underneath it. The loops keep ticking on the sound engine after the screen is gone, so the
	 * handles can be dropped here; each one stops itself once it is inaudible.</p>
	 */
	public static void fadeOutAll() {
		for (TapeLoop loop : ACTIVE.values()) loop.fadeOut();
		ACTIVE.clear();
		carrierLostPlayed = false;
	}

	/** Immediate silence, for teardown and for replaying the sequence in tests. */
	public static void stopAll() {
		for (TapeLoop loop : ACTIVE.values()) loop.forceStop();
		ACTIVE.clear();
		carrierLostPlayed = false;
	}

	private static float hissTarget(int screenTicks) {
		if (screenTicks < AlphaLoadTimeline.GLITCH_START_TICK) return 0.0F;
		// Survives the blackout and the recovery: the tape is still running the whole time.
		float arrival = AlphaLoadTimeline.ramp(screenTicks, AlphaLoadTimeline.GLITCH_START_TICK,
				AlphaLoadTimeline.LAYER_FADE_TICKS);
		return HISS_VOLUME * arrival;
	}

	private static float staticTarget(int screenTicks) {
		if (screenTicks >= AlphaLoadTimeline.BLACKOUT_START_TICK) {
			return STATIC_VOLUME * (1.0F - AlphaLoadTimeline.ramp(screenTicks,
					AlphaLoadTimeline.BLACKOUT_START_TICK,
					AlphaLoadTimeline.BLACKOUT_COLLAPSE_TICKS));
		}
		return STATIC_VOLUME * AlphaLoadTimeline.ramp(screenTicks,
				AlphaLoadTimeline.FLOOD_START_TICK, AlphaLoadTimeline.FLOOD_STATIC_RISE_TICKS);
	}

	private static float deadAirTarget(int screenTicks) {
		if (!AlphaLoadTimeline.blackoutFrame(screenTicks)) return 0.0F;
		return DEAD_AIR_VOLUME * AlphaLoadTimeline.ramp(screenTicks,
				AlphaLoadTimeline.BLACKOUT_START_TICK, AlphaLoadTimeline.BLACKOUT_COLLAPSE_TICKS);
	}

	/**
	 * Points a bed at a level, easing toward it - or arriving on it outright when {@code cut}.
	 *
	 * <p>A cut has to be applied before {@code play}, not after: the engine reads the instance's
	 * volume when it takes it, so a loop handed over at zero and corrected a moment later is audibly
	 * a fade whatever the target said.</p>
	 */
	private static void setTarget(Minecraft client, Bed bed, float target, boolean cut) {
		TapeLoop loop = ACTIVE.get(bed);
		if (loop != null && loop.isStopped()) {
			ACTIVE.remove(bed);
			loop = null;
		}
		if (loop == null) {
			if (target <= 0.0F) return;
			loop = new TapeLoop(bed.cue());
			loop.target(target);
			if (cut) loop.snap();
			ACTIVE.put(bed, loop);
			client.getSoundManager().play(loop);
			return;
		}
		loop.target(target);
		if (cut) loop.snap();
	}

	private enum Bed {
		TAPE_HISS,
		STATIC,
		DEAD_AIR;

		SoundEvent cue() {
			return switch (this) {
				case TAPE_HISS -> ModSounds.SIGNAL_TAPE_HISS;
				case STATIC -> ModSounds.SIGNAL_STATIC;
				case DEAD_AIR -> ModSounds.SIGNAL_DEAD_AIR;
			};
		}
	}

	private static final class TapeLoop extends AbstractTickableSoundInstance {
		private float target;
		private boolean fading;

		private TapeLoop(SoundEvent cue) {
			// MASTER, matching the signal beds: this noise is not part of the world, and must not
			// be silenced by a category that governs the world.
			super(cue, SoundSource.MASTER, RandomSource.create());
			this.volume = 0.0F;
			this.pitch = 1.0F;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		private void target(float value) {
			target = Math.clamp(value, 0.0F, 1.0F);
		}

		private void fadeOut() {
			target = 0.0F;
			fading = true;
		}

		/** Arrives on the current target this instant instead of easing toward it. */
		private void snap() {
			volume = scaledTarget();
		}

		private void forceStop() {
			stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		private float scaledTarget() {
			return (float) (target * RuntimeServices.config().meta().effectiveBedVolume());
		}

		@Override
		public void tick() {
			float scaled = scaledTarget();
			// Eased rather than stepped, so a bed arriving cannot be heard arriving, and the
			// release is slower still so it cannot be heard leaving either.
			volume += (scaled - volume) * (fading ? FADE_OUT_EASE : VOLUME_EASE);
			if (target <= 0.0F && volume < 0.004F) stop();
		}
	}
}
