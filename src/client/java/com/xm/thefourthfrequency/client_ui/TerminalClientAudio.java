package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.networking.TerminalNoticePayload;
import com.xm.thefourthfrequency.terminal.TerminalContactVoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

public final class TerminalClientAudio {
	private static final RandomSource JITTER = RandomSource.create();
	private static TuningLoop tuningLoop;
	private static CarrierLoop carrierLoop;
	private static long nextContactTick;
	private static int loopStarts;
	private static int lockPlays;
	private static int noticeOpeningPlays;
	private static int noticeStablePlays;
	private static int volumePreviewPlays;
	private static SimpleSoundInstance volumePreviewInstance;
	private static long nextAttentionMillis;
	private static int attentionPlays;
	private static int signalSweepPlays;
	private static int detentPlays;
	private static int carrierStarts;
	private static int panelStage;

	private TerminalClientAudio() {
	}

	/**
	 * The lighter contact, for moving through a list rather than choosing something in it.
	 *
	 * <p>Every interaction used to fire the same click at the same weight, so scrolling a file
	 * list sounded exactly as consequential as committing a command. Splitting the two gives the
	 * panel a hierarchy the player can hear without having to look.</p>
	 */
	public static void keypress() {
		contact(TerminalContactVoice.MOVE);
	}

	/** Leaving one page for another. */
	public static void tab() {
		contact(TerminalContactVoice.TAB);
	}

	/** One level in: a tool detail, a file body. */
	public static void openLevel() {
		contact(TerminalContactVoice.OPEN);
	}

	/** One level back out. Pitched under {@link #openLevel()} so the pair reads as a direction. */
	public static void backLevel() {
		contact(TerminalContactVoice.BACK);
	}

	/**
	 * The player told the server to do something - start guiding, rescan, choose a destination.
	 *
	 * <p>The only voice with the bolt sample in it, and deliberately not routed through
	 * {@link #lock()}: that one is the receiver finding a band, it is counted by the first-run
	 * notice tests as a sound that must not play there, and it answers a different question.
	 */
	public static void commit() {
		contact(TerminalContactVoice.COMMIT);
	}

	/** An unread marker clearing. The lamp going out, not a button going down. */
	public static void acknowledge() {
		contact(TerminalContactVoice.ACKNOWLEDGE);
	}

	/** One notch of the dial. Rides on top of the sweep loop {@link #tuningInput()} maintains. */
	public static void detent() {
		detentPlays++;
		playContact(ModSounds.TERMINAL_DETENT, 0.98F, 0.26F);
	}

	/**
	 * One self-test line landing.
	 *
	 * <p>The pitch climbs a little with each check, which is what turns six identical clicks into a
	 * sequence going somewhere. Small steps: the point is that the machine is working through a
	 * list, not that it is playing a tune.
	 *
	 * @param line zero-based index of the line that just appeared
	 */
	public static void bootLine(int line) {
		playContact(ModSounds.TERMINAL_BOOT_LINE, 0.86F + 0.045F * Math.clamp(line, 0, 5), 0.40F);
	}

	/** The last check. It answers differently, because it is the one that says the device is up. */
	public static void bootComplete() {
		playContact(ModSounds.TERMINAL_BOOT_COMPLETE, 1.0F, 0.46F);
	}

	public static void tuningInput() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		if (tuningLoop == null || tuningLoop.isStopped()) {
			tuningLoop = new TuningLoop(baseVolume(0.20F));
			client.getSoundManager().play(tuningLoop);
			loopStarts++;
		}
		tuningLoop.requestInput();
	}

	public static void endTuningInput() {
		if (tuningLoop != null) tuningLoop.releaseInput();
	}

	public static void tick() {
		if (tuningLoop != null && tuningLoop.isStopped()) tuningLoop = null;
	}

	public static void lock() {
		lockPlays++;
		play(ModSounds.TERMINAL_LOCK, 0.82F, 0.48F);
	}

	/**
	 * Plays once when the near-field receiver becomes tunable for real, not while the dial is
	 * being dragged - {@link #tuningInput()} already owns that with a loop. This is the moment
	 * the band opens up, and the sweep is the sound of finding it occupied by several things
	 * that will not identify themselves.
	 */
	public static void signalSweep() {
		signalSweepPlays++;
		play(ModSounds.SIGNAL_TUNING_SWEEP, 1.0F, 0.44F);
	}

	public static void fault() {
		play(ModSounds.TERMINAL_FAULT, 0.64F, 0.42F);
	}

	/**
	 * The sky monitor admitting it has lost what it was measuring.
	 *
	 * <p>The carrier-loss cue rather than {@link #fault()} on purpose. A fault is the terminal
	 * having a problem with itself; a lost carrier is the thing it was listening to going away.
	 * On the weather page during a sky anomaly the second one is the true statement, and the
	 * player has already been taught what that sample means by the anomaly system itself.</p>
	 */
	public static void skyCarrierLost() {
		play(ModSounds.SIGNAL_CARRIER_LOST, 0.92F, 0.38F);
	}

	/**
	 * One-shot first-run notice startup; deliberately separate from terminal tuning statistics.
	 *
	 * <p>Pitched well below {@link #fault()} rather than a hair under it. Both used to resolve to
	 * the same sample within 0.06 of the same volume and pitch, so the comment claiming they were
	 * distinct was only true of the statistics counters - nothing about them was audibly
	 * different. This one has to read as the machine coming up for the first time, which is a
	 * slower and heavier gesture than something going wrong.</p>
	 */
	public static void noticeOpening() {
		noticeOpeningPlays++;
		play(ModSounds.TERMINAL_RAISE, 0.9F, 0.42F);
	}

	/**
	 * One-shot first-run notice lock; deliberately does not call {@link #lock()} - and now
	 * actually sounds unlike it, an octave-ish down and louder, because locking onto the first
	 * signal you ever find is not the same event as retuning onto another one later.
	 */
	public static void noticeStable() {
		noticeStablePlays++;
		play(ModSounds.TERMINAL_BOOT_COMPLETE, 1.0F, 0.46F);
	}

	/**
	 * The audition behind the mod-volume slider.
	 *
	 * <p>The band sweep, for three reasons. It is one of the mod's own recordings rather than a
	 * re-pitched vanilla sample, so it is what this mod actually sounds like. It runs three seconds,
	 * which is long enough to judge a level against - a 0.18-second lock tick is over before the ear
	 * has settled on it, and that is what this used to be. And it gives nothing away: it is the sound
	 * of a receiver looking for the fourth band, which is the premise, not a spoiler.
	 *
	 * <p>Played at the top of the range the mod's own cues occupy rather than at the sweep's authored
	 * level, so a volume that is comfortable here is one nothing later will overshoot.
	 *
	 * <p>A second press stops the first rather than stacking on it. A player comparing two settings
	 * will press this twice in a row on purpose, and on a screen whose whole job is to bound peak
	 * volume, "press it five times and it gets five times louder" is the one outcome it cannot have.
	 */
	public static void volumePreview() {
		volumePreviewPlays++;
		SoundManager manager = Minecraft.getInstance().getSoundManager();
		if (volumePreviewInstance != null) manager.stop(volumePreviewInstance);
		volumePreviewInstance = null;
		float volume = baseVolume(0.62F);
		if (volume <= 0.0F) return;
		volumePreviewInstance = SimpleSoundInstance.forUI(ModSounds.SIGNAL_TUNING_SWEEP, 1.0F, volume);
		manager.play(volumePreviewInstance);
	}

	public static void attention(int tone) {
		long now = Util.getMillis();
		if (now < nextAttentionMillis) return;
		nextAttentionMillis = now + 300L;
		attentionPlays++;
		if (tone == TerminalNoticePayload.TONE_PURSUIT_WARNING) {
			play(ModSounds.TERMINAL_ANOMALY, 0.72F, 0.66F);
		} else if (tone == TerminalNoticePayload.TONE_TASK_COMPLETE) {
			play(ModSounds.TERMINAL_BOOT_COMPLETE, 1.0F, 0.62F);
		} else if (tone == TerminalNoticePayload.TONE_DENIED) {
			// A refusal is a dull short fault, never the chime that reads as progress.
			play(ModSounds.TERMINAL_FAULT, 0.88F, 0.34F);
		} else if (tone == TerminalNoticePayload.TONE_ANCHOR) {
			// An anchor falling is the loudest thing the table can cause; it gets the resonant hit.
			play(ModSounds.WORLD_INTERFACE_ANCHOR, 1.0F, 0.52F);
		} else if (tone == TerminalNoticePayload.TONE_ENCOUNTER) {
			play(ModSounds.WORLD_INTERFACE_IMPACT, 0.70F, 0.30F);
		} else if (tone == TerminalNoticePayload.TONE_DRAGON) {
			// The dragon is the only friendly voice in the finale, so it does not share the boss cues.
			play(SoundEvents.ENDER_DRAGON_AMBIENT, 1.25F, 0.34F);
		} else {
			play(ModSounds.TERMINAL_LOCK, 1.08F, 0.48F);
		}
	}

	/**
	 * Plays one of the graded press voices at the wear the holder's stage has earned.
	 *
	 * <p>The table itself lives in {@link TerminalContactVoice}, in the common source set, where a
	 * plain JUnit test can hold the six voices apart from each other.
	 */
	private static void contact(TerminalContactVoice voice) {
		playContact(sampleFor(voice.sample()), voice.pitchAt(panelStage), voice.relativeVolume());
	}

	private static SoundEvent sampleFor(TerminalContactVoice.Sample sample) {
		return switch (sample) {
			case CONTACT -> ModSounds.TERMINAL_CLICK;
			case KEY -> ModSounds.TERMINAL_KEYPRESS;
			case NOTCH -> ModSounds.TERMINAL_DETENT;
			case BOLT -> ModSounds.TERMINAL_LOCK;
		};
	}

	/**
	 * Contact sounds are the ones that fire in bursts - a held dial, a run of keystrokes - so
	 * they are the ones that expose how few variants there are. Four samples at a fixed pitch
	 * start sounding like four samples very quickly; a little jitter on each hit is enough to
	 * keep a run of them sounding like a worn mechanism rather than a short playlist.
	 */
	private static void playContact(SoundEvent event, float pitch, float relativeVolume) {
		Minecraft client = Minecraft.getInstance();
		long now = client.level == null ? 0L : client.level.getGameTime();
		if (now < nextContactTick) return;
		nextContactTick = now + 1L;
		play(event, jitter(pitch, 0.05F), relativeVolume);
	}

	private static float jitter(float pitch, float amount) {
		return Math.clamp(pitch * (1.0F + (JITTER.nextFloat() * 2.0F - 1.0F) * amount), 0.5F, 2.0F);
	}

	private static void play(SoundEvent event, float pitch, float relativeVolume) {
		float volume = baseVolume(relativeVolume);
		if (volume <= 0.0F) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
	}

	private static float baseVolume(float relativeVolume) {
		return (float) Math.clamp(
				RuntimeServices.config().meta().peakVolume() * relativeVolume, 0.0D, 1.0D);
	}

	public static int loopStartsForTesting() { return loopStarts; }
	public static boolean loopActiveForTesting() { return tuningLoop != null && !tuningLoop.isStopped(); }
	public static float loopVolumeForTesting() { return tuningLoop == null ? 0.0F : tuningLoop.currentVolume(); }
	public static int lockPlaysForTesting() { return lockPlays; }
	public static int noticeOpeningPlaysForTesting() { return noticeOpeningPlays; }
	public static int noticeStablePlaysForTesting() { return noticeStablePlays; }
	public static int volumePreviewPlaysForTesting() { return volumePreviewPlays; }
	public static int attentionPlaysForTesting() { return attentionPlays; }
	public static int signalSweepPlaysForTesting() { return signalSweepPlays; }
	public static int detentPlaysForTesting() { return detentPlays; }
	public static int panelStageForTesting() { return panelStage; }
	public static void resetTuningForTesting() {
		if (tuningLoop != null) tuningLoop.forceStop();
		tuningLoop = null;
	}

	/**
	 * Starts, or retunes, the terminal's noise floor.
	 *
	 * <p>Called every time the screen learns its anomaly stage, which is most snapshots. Restarting
	 * the sample on each of those would be audible as a click and would also reset the loop phase, so
	 * an already-running carrier is only ever retuned in place.
	 *
	 * @param stage the holder's anomaly stage, 0-5
	 */
	public static void carrierOn(int stage) {
		// The one place the client is told its visual stage every tick, so it is also where the
		// press voices pick up how worn the panel should sound. Latched rather than cleared on
		// close: the stage only moves between sessions, and a stale value would only ever be the
		// stage this same player had a moment ago.
		panelStage = Math.clamp(stage, 0, TerminalContactVoice.MAX_STAGE);
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getSoundManager() == null) return;
		float pitch = CarrierLoop.pitchFor(stage);
		if (carrierLoop != null && !carrierLoop.isStopped()) {
			carrierLoop.retune(pitch);
			return;
		}
		carrierLoop = new CarrierLoop(pitch);
		carrierStarts++;
		client.getSoundManager().play(carrierLoop);
	}

	/**
	 * Stops the noise floor.
	 *
	 * <p>Hard stop, no fade. A fade would be the polite thing for every other loop in this mod and is
	 * exactly wrong here: the entire effect is that one time the room gets quieter while the terminal
	 * is still open, and a quarter-second ramp is the difference between noticing that and not.
	 */
	public static void carrierOff() {
		if (carrierLoop != null) carrierLoop.forceStop();
		carrierLoop = null;
	}

	public static boolean carrierActiveForTesting() {
		return carrierLoop != null && !carrierLoop.isStopped();
	}

	public static float carrierPitchForTesting() {
		return carrierLoop == null ? 0.0F : carrierLoop.getPitch();
	}

	public static int carrierStartsForTesting() { return carrierStarts; }

	public static void resetCarrierForTesting() {
		carrierOff();
		carrierStarts = 0;
	}

	/**
	 * The noise floor a powered device makes, pitched by how far along its holder is.
	 *
	 * <p>Relative and unattenuated, because it is the sound of the thing in the player's hands rather
	 * than a sound in the world. It never fades in or out on its own and has no schedule: it is on for
	 * exactly as long as the screen is, which is what makes its absence mean something.
	 *
	 * <h2>AMBIENT is load-bearing. Do not move it to MASTER.</h2>
	 *
	 * <p>{@code silent_world} zeroes AMBIENT, WEATHER, MUSIC, RECORDS, HOSTILE and NEUTRAL through
	 * {@code OptionsAnomalyMixin}, and it does it in {@code getFinalSoundSourceVolume}, which the
	 * engine re-asks for sounds that are already playing. So the carrier goes silent for the two to
	 * three minutes that anomaly runs, with the terminal still open in the player's hands.
	 *
	 * <p>That is the entire payoff of this sound, and it arrives for free. Nobody consciously hears a
	 * noise floor arrive; they hear one leave. {@code SignalBedController} deliberately sits on MASTER
	 * to escape exactly this mute - it needs to keep speaking when the world stops - and the carrier
	 * wants the opposite for the same reason. Moving it would look like a consistency fix and would
	 * quietly delete the feature.
	 */
	private static final class CarrierLoop extends AbstractTickableSoundInstance {
		/**
		 * Stage to pitch.
		 *
		 * <p>A little under a semitone per stage, downward. Small enough that nobody hears a stage
		 * change happen - there is no moment where it steps, because the stage only ever moves between
		 * sessions - and wide enough that stage five and stage one are not the same sound if anyone
		 * ever puts recordings of them side by side.
		 */
		private static float pitchFor(int stage) {
			return 1.0F - 0.05F * Math.clamp(stage, 0, 5);
		}

		private CarrierLoop(float pitch) {
			super(ModSounds.TERMINAL_CARRIER, SoundSource.AMBIENT, RandomSource.create());
			this.volume = 0.35F;
			this.pitch = pitch;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			// No delay. The terminal is already open by the time this is asked for.
			this.delay = 0;
		}

		private void retune(float value) {
			this.pitch = value;
		}

		private void forceStop() {
			stop();
		}

		@Override
		public void tick() {
			// Nothing to do. Tickable only because that is the only instance type that loops; the
			// sound has no envelope, no schedule and no reason to ever change on its own.
		}
	}

	private static final class TuningLoop extends AbstractTickableSoundInstance {
		private static final int RELEASE_TICKS = 4;
		private static final int FADE_TICKS = 4;
		private final float fullVolume;
		private int ticksSinceInput;
		private boolean released;

		private TuningLoop(float volume) {
			super(ModSounds.TERMINAL_TUNE, SoundSource.AMBIENT, RandomSource.create());
			this.fullVolume = volume;
			this.volume = volume;
			this.pitch = 0.82F;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		private void requestInput() {
			ticksSinceInput = 0;
			released = false;
			volume = fullVolume;
		}

		private void releaseInput() {
			released = true;
		}

		private float currentVolume() {
			return volume;
		}

		private void forceStop() {
			stop();
		}

		@Override
		public void tick() {
			ticksSinceInput++;
			if (!released && ticksSinceInput <= RELEASE_TICKS) return;
			int fadeAge = ticksSinceInput - RELEASE_TICKS;
			volume = fullVolume * Math.clamp(1.0F - fadeAge / (float) FADE_TICKS, 0.0F, 1.0F);
			if (fadeAge >= FADE_TICKS) stop();
		}
	}
}
