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
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

public final class TerminalClientAudio {
	private static final RandomSource JITTER = RandomSource.create();
	private static TuningLoop tuningLoop;
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

	/** Confirms receiver availability with a dry detent. */
	public static void signalSweep() {
		signalSweepPlays++;
		play(ModSounds.TERMINAL_DETENT, 1.0F, 0.24F);
	}

	public static void fault() {
		play(ModSounds.TERMINAL_FAULT, 0.64F, 0.42F);
	}

	/** A short device fault reports lost measurements without a noise burst. */
	public static void skyCarrierLost() {
		play(ModSounds.TERMINAL_FAULT, 0.92F, 0.30F);
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

	/** Confirms first-run readiness with the normal completion cue. */
	public static void noticeStable() {
		noticeStablePlays++;
		play(ModSounds.TERMINAL_BOOT_COMPLETE, 1.0F, 0.46F);
	}

	/** Auditions clean device feedback; repeated presses replace the previous preview. */
	public static void volumePreview() {
		volumePreviewPlays++;
		SoundManager manager = Minecraft.getInstance().getSoundManager();
		if (volumePreviewInstance != null) manager.stop(volumePreviewInstance);
		volumePreviewInstance = null;
		float volume = baseVolume(0.62F);
		if (volume <= 0.0F) return;
		volumePreviewInstance = SimpleSoundInstance.forUI(ModSounds.TERMINAL_BOOT_COMPLETE, 1.0F, volume);
		manager.play(volumePreviewInstance);
	}

	public static void attention(int tone) {
		if (!audibleNotice(tone)) return;
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
			// UI notifications stay within the device palette.
			play(ModSounds.TERMINAL_LOCK, 0.90F, 0.40F);
		} else if (tone == TerminalNoticePayload.TONE_ENCOUNTER) {
			play(ModSounds.TERMINAL_ANOMALY, 1.0F, 0.36F);
		} else if (tone == TerminalNoticePayload.TONE_DRAGON) {
			// A friendly report uses the completion tone.
			play(ModSounds.TERMINAL_BOOT_COMPLETE, 0.95F, 0.32F);
		} else {
			play(ModSounds.TERMINAL_LOCK, 1.08F, 0.48F);
		}
	}

	/** Automatic guidance stays silent; only actionable warnings and results ask for attention. */
	public static boolean audibleNotice(int tone) {
		return tone == TerminalNoticePayload.TONE_PURSUIT_WARNING
				|| tone == TerminalNoticePayload.TONE_TASK_COMPLETE
				|| tone == TerminalNoticePayload.TONE_DENIED
				|| tone == TerminalNoticePayload.TONE_ANCHOR;
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
		nextContactTick = now + 2L;
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
				RuntimeServices.config().meta().peakVolume() * relativeVolume * 0.55F, 0.0D, 1.0D);
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

	/** Stage changes alter controls; opening a reading screen must not start a sound bed. */
	public static void updatePanelStage(int stage) {
		panelStage = Math.clamp(stage, 0, TerminalContactVoice.MAX_STAGE);
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
