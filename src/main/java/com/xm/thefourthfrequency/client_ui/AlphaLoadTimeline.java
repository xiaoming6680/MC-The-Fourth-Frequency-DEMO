package com.xm.thefourthfrequency.client_ui;

import java.util.List;

public final class AlphaLoadTimeline {
	/*
	 * The real first-entry progress is already capped at 50% before the session is
	 * claimed. Keep only the one-second pause here instead of replaying 0 -> 50%.
	 */
	public static final int NORMAL_PROGRESS_END_TICK = 0;
	/**
	 * How long the screen is allowed to look like an ordinary loading screen.
	 *
	 * <p>This was one second, which is not long enough to be believed. The whole sequence depends
	 * on the player having settled into a normal wait before anything contradicts it, and a
	 * player who never settled reads the first glitch as a scripted effect rather than as
	 * something going wrong. Nearly three seconds is enough for the stalled bar below to become
	 * a conscious question - <em>why is this not moving?</em> - which is the thought the rest of
	 * the sequence answers.</p>
	 */
	public static final int NORMAL_PAUSE_TICKS = 56;
	public static final int GLITCH_START_TICK = NORMAL_PROGRESS_END_TICK + NORMAL_PAUSE_TICKS;
	public static final int FAILURE_TICK = GLITCH_START_TICK + 24;
	public static final int OBSERVER_MESSAGE_START_TICK = FAILURE_TICK + 24;
	public static final int OBSERVER_MESSAGE_END_TICK = FAILURE_TICK + 48;
	public static final int MAX_FAILURE_COPIES = 28;
	public static final int FLOOD_START_TICK = FAILURE_TICK + 52;
	public static final int ACTIVE_FAILURE_WALL_TICKS = 28;
	public static final int FROZEN_FAILURE_WALL_TICKS = 12;
	public static final int FLOOD_COMPLETE_TICK = FLOOD_START_TICK + ACTIVE_FAILURE_WALL_TICKS;
	public static final int FREEZE_START_TICK = FLOOD_COMPLETE_TICK;
	public static final int BLACKOUT_START_TICK =
			FREEZE_START_TICK + FROZEN_FAILURE_WALL_TICKS;
	/**
	 * Shortened from two seconds once the single-frame flashbacks were removed: dead air with
	 * nothing in it earns its length only up to a point, and the time is better spent on the
	 * prelude, where it buys belief instead of patience.
	 */
	public static final int BLACKOUT_TICKS = 28;
	public static final int LEGACY_RECOVERY_START_TICK = BLACKOUT_START_TICK + BLACKOUT_TICKS;
	public static final int MIN_LOADING_SCREEN_TICKS = LEGACY_RECOVERY_START_TICK + 50;
	public static final int MAX_RESOURCE_RELOAD_WAIT_TICKS = MIN_LOADING_SCREEN_TICKS + 64;
	private static final List<String> DOWNGRADE_VERSIONS = List.of(
			"1.20.1", "1.16.5", "1.12.2", "1.8.9", "1.7.3", "1.0.0");
	/**
	 * The tick each window-title version becomes current, indexed by stage.
	 *
	 * <p>An evenly spaced countdown would drift out of phase with what the screen is doing, and
	 * the arithmetic that produced it put the final step - the only one the whole sequence exists
	 * to deliver - inside the blackout, where nobody could read it. Every step is pinned to the
	 * event the player is already looking at instead, and 1.0.0 lands on the frame the picture
	 * comes back, so the title and the world agree about what happened at the same instant.</p>
	 */
	private static final int[] TITLE_STAGE_TICKS = {
			0,
			GLITCH_START_TICK,
			FAILURE_TICK,
			OBSERVER_MESSAGE_START_TICK,
			FLOOD_START_TICK,
			FREEZE_START_TICK,
			LEGACY_RECOVERY_START_TICK
	};

	private AlphaLoadTimeline() {
	}

	public static int versionStage(int screenTicks) {
		for (int stage = TITLE_STAGE_TICKS.length - 1; stage > 0; stage--) {
			if (screenTicks >= TITLE_STAGE_TICKS[stage]) return Math.min(stage, finalVersionStage());
		}
		return 0;
	}

	/** The tick at which {@code stage} becomes the current window title. */
	public static int versionStageTick(int stage) {
		return TITLE_STAGE_TICKS[Math.clamp(stage, 0, TITLE_STAGE_TICKS.length - 1)];
	}

	public static int finalVersionStage() {
		return DOWNGRADE_VERSIONS.size();
	}

	public static String versionAt(int stage, String launchedVersion) {
		int safeStage = Math.clamp(stage, 0, finalVersionStage());
		return safeStage == 0 ? launchedVersion : DOWNGRADE_VERSIONS.get(safeStage - 1);
	}

	public static String windowContextKey(boolean singleplayer) {
		return singleplayer
				? "window.thefourthfrequency.alpha_load.singleplayer"
				: "window.thefourthfrequency.alpha_load.multiplayer";
	}

	public static boolean initialNormalFrame(int screenTicks) {
		return screenTicks < GLITCH_START_TICK;
	}

	public static boolean observerMessageVisible(int screenTicks) {
		if (screenTicks < OBSERVER_MESSAGE_START_TICK
				|| screenTicks >= OBSERVER_MESSAGE_END_TICK) return false;
		int age = screenTicks - OBSERVER_MESSAGE_START_TICK;
		return age < 8 || Math.floorMod(age, 7) != 1;
	}

	/**
	 * The tick the progress bar stops being merely slow.
	 *
	 * <p>A bar that stalls is a bad connection and the player will wait it out. A bar that goes
	 * <em>backwards</em> is not something any amount of waiting fixes, and it is the first thing
	 * in the sequence that cannot be explained away - before a single pixel has visibly
	 * corrupted.</p>
	 */
	public static final int PROGRESS_REGRESSION_TICK = NORMAL_PAUSE_TICKS * 2 / 3;
	/**
	 * How long the bar takes to slide back down.
	 *
	 * <p>Snapping to the lower value reads as a rendering glitch - one frame was wrong - and a
	 * glitch is something a player forgives and forgets. Ground being given up steadily, slowly
	 * enough to watch happen, cannot be filed that way. It also leaves a few ticks of holding at
	 * the bottom before the first visible corruption, which is where the thought lands.</p>
	 */
	public static final int PROGRESS_REGRESSION_SLIDE_TICKS = 14;
	private static final float PROGRESS_CREEP_TOP = 0.62F;
	/**
	 * Exactly the value the corruption phase pins the bar to.
	 *
	 * <p>Retreating past it would be a stronger beat in isolation, but the prelude hands straight
	 * over to a phase that holds the bar at half, so any other landing point becomes a visible
	 * jump on that frame - and a jump is the one thing this whole slide exists to avoid. The
	 * ground given up is the climb, which is enough: the bar still ends up lower than it was a
	 * moment ago, and it still never recovers.</p>
	 */
	private static final float PROGRESS_AFTER_REGRESSION = 0.5F;

	public static float initialNormalProgress(int screenTicks) {
		// Creeping upward first is what makes the retreat register as a loss rather than a value.
		if (screenTicks <= PROGRESS_REGRESSION_TICK) {
			return 0.5F + (PROGRESS_CREEP_TOP - 0.5F)
					* ramp(screenTicks, 0, PROGRESS_REGRESSION_TICK);
		}
		float slide = Math.clamp((screenTicks - PROGRESS_REGRESSION_TICK)
				/ (float) PROGRESS_REGRESSION_SLIDE_TICKS, 0.0F, 1.0F);
		return lerp(PROGRESS_CREEP_TOP, PROGRESS_AFTER_REGRESSION, slide);
	}

	public static int copiedFailureLines(int screenTicks) {
		if (screenTicks < FAILURE_TICK) return 0;
		float progress = Math.clamp((screenTicks - FAILURE_TICK)
				/ (float) (FLOOD_START_TICK - FAILURE_TICK), 0.0F, 1.0F);
		return 1 + Math.round((float) Math.sqrt(progress) * (MAX_FAILURE_COPIES - 1));
	}

	public static boolean fullScreenFailureWall(int screenTicks) {
		return screenTicks >= FLOOD_START_TICK
				&& screenTicks < BLACKOUT_START_TICK;
	}

	public static boolean frozenFailureFrame(int screenTicks) {
		return screenTicks >= FREEZE_START_TICK && screenTicks < BLACKOUT_START_TICK;
	}

	/** Ticks the frame is allowed to sit locked before the one thing that still moves moves. */
	public static final int FROZEN_OBSERVER_DELAY_TICKS = 5;

	/**
	 * The second and last time the recording admits it can see the player.
	 *
	 * <p>The first sighting is one line among many on a picture that is already coming apart, and
	 * it is easy to file as more noise. This one lands on a frame that has visibly stopped -
	 * every other layer is locked - so a line appearing here breaks the freeze's own rule. A
	 * still image with exactly one moving element is worse than any amount of motion.</p>
	 */
	public static boolean frozenObserverVisible(int screenTicks) {
		return frozenFailureFrame(screenTicks)
				&& screenTicks >= FREEZE_START_TICK + FROZEN_OBSERVER_DELAY_TICKS;
	}

	public static boolean blackoutFrame(int screenTicks) {
		return screenTicks >= BLACKOUT_START_TICK
				&& screenTicks < LEGACY_RECOVERY_START_TICK;
	}

	public static boolean legacyRecoveryFrame(int screenTicks) {
		return screenTicks >= LEGACY_RECOVERY_START_TICK;
	}

	public static int failureMotionTick(int screenTicks) {
		return Math.min(screenTicks, FREEZE_START_TICK);
	}

	/*
	 * ---------------------------------------------------------------------------------------
	 * The medium layer.
	 *
	 * Everything below describes the tape rather than the message: scanlines, chroma bleed, a
	 * tracking band, a timecode, dead air. They are pure functions of the screen tick so the
	 * whole sequence can be verified without a running client, and so a frozen frame is frozen
	 * in every layer at once rather than only in the one that remembered to check.
	 *
	 * Each layer ramps across a transition instead of switching on. A hard cut announces that
	 * something was drawn on top of the picture; a ramp reads as the picture itself degrading,
	 * which is the entire effect. The ramps deliberately overlap the events they belong to, so
	 * no two layers arrive on the same frame.
	 * ---------------------------------------------------------------------------------------
	 */

	/** How long a layer takes to reach full strength once its event fires. */
	public static final int LAYER_FADE_TICKS = 10;
	/**
	 * How long the noise floor takes to come up under the wall. Zero: it arrives with it.
	 *
	 * <p>This was six ticks, matched to a wipe that used to open the wall outward from the middle of
	 * the screen over exactly that long. The wall became a cut - it is simply there, on one frame,
	 * whole, because a wipe is a transition and a transition is something a piece of software chose
	 * to play - and this ramp was left behind pointing at an event that no longer happens.
	 *
	 * <p>The audible gap was much wider than six ticks, because the bed also eases toward whatever
	 * this asks for: on the frame the picture cut, the static was at zero, and it needed about a
	 * second to arrive. A picture that fails a second before its own noise floor does not read as one
	 * signal failing, it reads as a slideshow with a sound cue after it. So the ramp goes, and the
	 * bed is snapped rather than eased on this one transition - see {@code AlphaCorruptionAudio}.
	 *
	 * <p>Every other layer still ramps. This is the one beat in the sequence that is a cut, and it is
	 * a cut in both channels or in neither.
	 */
	public static final int FLOOD_STATIC_RISE_TICKS = 0;
	/** Old sets collapsed the picture to a bright line before losing it altogether. */
	public static final int BLACKOUT_COLLAPSE_TICKS = 5;
	/** After dead air the picture does not simply exist again; it has to find its lock. */
	public static final int RECOVERY_LOCK_TICKS = 24;
	public static final int SCANLINE_SPACING = 3;
	private static final int PEAK_SCANLINE_ALPHA = 66;
	private static final int GLITCH_SCANLINE_ALPHA = 28;
	private static final int RESIDUAL_SCANLINE_ALPHA = 16;
	/** Like the scanlines, the edges never come all the way back. */
	private static final float RESIDUAL_VIGNETTE = 0.3F;
	private static final float PEAK_CHROMA_OFFSET = 3.0F;
	private static final int TRACKING_BAND_SPEED = 3;
	private static final int TRACKING_BAND_MIN_HEIGHT = 5;
	private static final int TRACKING_BAND_MAX_HEIGHT = 19;
	private static final int TRACKING_BAND_MAX_SHIFT = 13;
	private static final int TICKS_PER_TIMECODE_SECOND = 20;
	private static final int TIMECODE_FRAMES_PER_SECOND = 30;

	/** 0 before {@code startTick}, 1 once {@code lengthTicks} have passed, smoothstep between. */
	public static float ramp(int screenTicks, int startTick, int lengthTicks) {
		if (lengthTicks <= 0) return screenTicks >= startTick ? 1.0F : 0.0F;
		float linear = Math.clamp((screenTicks - startTick) / (float) lengthTicks, 0.0F, 1.0F);
		return linear * linear * (3.0F - 2.0F * linear);
	}

	/** Deterministic per-tick noise shared by every layer, so one seed drives the whole frame. */
	public static int noise(int value) {
		value ^= value >>> 16;
		value *= 0x7FEB352D;
		value ^= value >>> 15;
		value *= 0x846CA68B;
		return value ^ value >>> 16;
	}

	public static int scanlineAlpha(int screenTicks) {
		if (screenTicks < GLITCH_START_TICK) return 0;
		if (screenTicks >= LEGACY_RECOVERY_START_TICK) {
			// The tape never becomes clean again; it settles onto a floor it keeps from here on.
			float settled = ramp(screenTicks, LEGACY_RECOVERY_START_TICK, RECOVERY_LOCK_TICKS);
			return Math.round(lerp(PEAK_SCANLINE_ALPHA, RESIDUAL_SCANLINE_ALPHA, settled));
		}
		if (screenTicks >= BLACKOUT_START_TICK) {
			return Math.round(PEAK_SCANLINE_ALPHA
					* (1.0F - ramp(screenTicks, BLACKOUT_START_TICK, BLACKOUT_COLLAPSE_TICKS)));
		}
		float arrival = ramp(screenTicks, GLITCH_START_TICK, LAYER_FADE_TICKS);
		float escalation = ramp(screenTicks, FAILURE_TICK, FLOOD_START_TICK - FAILURE_TICK);
		return Math.round(GLITCH_SCANLINE_ALPHA * arrival
				+ (PEAK_SCANLINE_ALPHA - GLITCH_SCANLINE_ALPHA) * escalation);
	}

	/**
	 * Which of the four analog-signal post chains the finished frame is filtered through, or 0.
	 *
	 * <p>The medium used to be drawn: scanlines were one-pixel rectangles, the vignette was sixteen
	 * nested ones per side, and snow was three hundred specks scattered over a viewport with a
	 * hundred thousand pixels in it. All three are the same limitation - a rectangle cannot bend,
	 * smear or resample what is under it, only cover it - and it is why the sequence read as damage
	 * drawn onto a screen rather than as a screen failing. The whole frame goes through
	 * {@code analog_signal.fsh} instead, including the failure text, which is where a recording of a
	 * failure belongs.
	 *
	 * <p>Quantised off {@link #scanlineAlpha} rather than given its own envelope, because the
	 * scanline ramp <em>is</em> the escalation of the medium: it arrives with the glitch, climbs
	 * through the failure, collapses into dead air, and settles onto a floor it never leaves. Deriving
	 * from it keeps the filter and the rest of the sequence on one clock, and keeps the residual
	 * step - the recovery is real, but it is a recovery to a worse baseline.
	 */
	public static int signalStep(int screenTicks) {
		int alpha = scanlineAlpha(screenTicks);
		if (alpha <= 0) return 0;
		if (alpha <= GLITCH_SCANLINE_ALPHA) return 1;
		if (alpha <= (GLITCH_SCANLINE_ALPHA + PEAK_SCANLINE_ALPHA) / 2) return 2;
		if (alpha < PEAK_SCANLINE_ALPHA) return 3;
		return 4;
	}

	/**
	 * Edge falloff, 0 to 1.
	 *
	 * <p>The layer that makes the other layers agree with each other. Scanlines, chroma bleed and
	 * a tracking band are each individually convincing and collectively read as three effects
	 * stacked on a game; darkening the edges asserts that all of them are happening to one
	 * surface, and that the surface is being looked at rather than looked through.</p>
	 *
	 * <p>Unlike every other layer this one survives dead air. A powered screen receiving nothing
	 * still has edges - it is the picture that was lost, not the tube - and cutting the vignette
	 * with the picture would quietly admit the whole thing was drawn.</p>
	 */
	public static float vignetteStrength(int screenTicks) {
		if (screenTicks < GLITCH_START_TICK) return 0.0F;
		if (screenTicks >= LEGACY_RECOVERY_START_TICK) {
			float settled = ramp(screenTicks, LEGACY_RECOVERY_START_TICK, RECOVERY_LOCK_TICKS);
			return lerp(1.0F, RESIDUAL_VIGNETTE, settled);
		}
		float arrival = ramp(screenTicks, GLITCH_START_TICK, LAYER_FADE_TICKS);
		float escalation = ramp(screenTicks, FAILURE_TICK, FLOOD_START_TICK - FAILURE_TICK);
		return Math.clamp(0.38F * arrival + 0.62F * escalation, 0.0F, 1.0F);
	}

	/** Horizontal separation, in pixels, between the red and cyan ghosts of any drawn text. */
	public static float chromaOffset(int screenTicks) {
		if (screenTicks < GLITCH_START_TICK) return 0.0F;
		if (screenTicks >= LEGACY_RECOVERY_START_TICK) {
			return PEAK_CHROMA_OFFSET * 0.25F
					* (1.0F - ramp(screenTicks, LEGACY_RECOVERY_START_TICK, RECOVERY_LOCK_TICKS));
		}
		if (screenTicks >= BLACKOUT_START_TICK) return 0.0F;
		float arrival = ramp(screenTicks, GLITCH_START_TICK, LAYER_FADE_TICKS);
		float escalation = ramp(screenTicks, FAILURE_TICK, FLOOD_START_TICK - FAILURE_TICK);
		return PEAK_CHROMA_OFFSET * (0.34F * arrival + 0.66F * escalation);
	}

	public static boolean trackingBandVisible(int screenTicks) {
		return screenTicks >= GLITCH_START_TICK && screenTicks < BLACKOUT_START_TICK;
	}

	public static int trackingBandHeight(int screenTicks) {
		float growth = ramp(screenTicks, GLITCH_START_TICK, FLOOD_START_TICK - GLITCH_START_TICK);
		return Math.round(lerp(TRACKING_BAND_MIN_HEIGHT, TRACKING_BAND_MAX_HEIGHT, growth));
	}

	/**
	 * Top edge of the band, scrolling upward. Uses the frozen motion tick so a locked frame locks
	 * the band with it - a tracking error that kept moving over a still picture would read as an
	 * overlay rather than as damage to the picture.
	 */
	public static int trackingBandTop(int screenTicks, int viewportHeight) {
		if (!trackingBandVisible(screenTicks) || viewportHeight <= 0) return Integer.MIN_VALUE;
		int travel = Math.max(0, failureMotionTick(screenTicks) - GLITCH_START_TICK)
				* TRACKING_BAND_SPEED;
		int span = viewportHeight + TRACKING_BAND_MAX_HEIGHT;
		return viewportHeight - Math.floorMod(travel, span);
	}

	/** How far the picture inside the band is dragged sideways. */
	public static int trackingBandShift(int screenTicks) {
		if (!trackingBandVisible(screenTicks)) return 0;
		int motion = failureMotionTick(screenTicks);
		float strength = ramp(screenTicks, GLITCH_START_TICK, FLOOD_START_TICK - GLITCH_START_TICK);
		int wobble = Math.floorMod(noise(motion / 2 + 0x2545F491), 2 * TRACKING_BAND_MAX_SHIFT + 1)
				- TRACKING_BAND_MAX_SHIFT;
		return Math.round(wobble * strength);
	}

	public static boolean timecodeVisible(int screenTicks) {
		return screenTicks >= GLITCH_START_TICK && screenTicks < BLACKOUT_START_TICK;
	}

	/** Frames elapsed on the counter, at a tape frame rate rather than the game's tick rate. */
	public static int timecodeFrames(int screenTicks) {
		int motion = Math.max(0, failureMotionTick(screenTicks));
		return motion * TIMECODE_FRAMES_PER_SECOND / TICKS_PER_TIMECODE_SECOND;
	}

	public static String timecodeText(int screenTicks) {
		int frames = timecodeFrames(screenTicks);
		int seconds = frames / TIMECODE_FRAMES_PER_SECOND;
		return String.format("%02d:%02d:%02d", seconds / 60, seconds % 60,
				frames % TIMECODE_FRAMES_PER_SECOND);
	}

	/** The counter stops trusting itself once the wall is up, and starts dropping digits. */
	public static boolean timecodeCorrupted(int screenTicks) {
		return screenTicks >= FLOOD_START_TICK
				&& Math.floorMod(noise(failureMotionTick(screenTicks) / 2), 5) == 0;
	}

	/** 1 while the picture is still collapsing toward a line, 0 once it is gone. */
	public static float blackoutCollapseProgress(int screenTicks) {
		if (!blackoutFrame(screenTicks)) return 0.0F;
		return 1.0F - ramp(screenTicks, BLACKOUT_START_TICK, BLACKOUT_COLLAPSE_TICKS);
	}

	public static int deadAirNoiseAlpha(int screenTicks) {
		if (!blackoutFrame(screenTicks)) return 0;
		// Dead air is not an absence of picture, it is a picture of nothing. Never fully still.
		float settled = ramp(screenTicks, BLACKOUT_START_TICK, BLACKOUT_COLLAPSE_TICKS);
		return Math.round((9 + Math.floorMod(noise(screenTicks), 12)) * settled);
	}

	/**
	 * The window title has its own channel, and dead air is the only stretch where it is the
	 * <em>only</em> channel: the picture is gone, but the title bar the operating system draws
	 * is not. For those ticks the title stops naming a version, because nothing is left running
	 * that could name one.
	 */
	public static boolean deadAirWindowTitle(int screenTicks) {
		return blackoutFrame(screenTicks);
	}

	/** How long after the picture returns the recovered progress bar gives itself away. */
	private static final int RECOVERY_FAULT_DELAY_TICKS = 34;

	public static int recoveryFaultTick() {
		return LEGACY_RECOVERY_START_TICK + RECOVERY_FAULT_DELAY_TICKS;
	}

	/**
	 * Fires once, after the recovered screen has had long enough to look trustworthy again.
	 *
	 * <p>Landing it after {@link #RECOVERY_LOCK_TICKS} is the point: the tracking has settled,
	 * the bar is filling normally, the player has been given permission to relax - and then the
	 * bar loses ground. What is being taken back is not progress, it is the conclusion that the
	 * worst is over.</p>
	 */
	public static boolean recoveryProgressFault(int screenTicks) {
		return screenTicks >= recoveryFaultTick();
	}

	/** 1 on the frame the picture returns, decaying to 0 as the tracking finds its lock. */
	public static float recoveryLockStrength(int screenTicks) {
		if (screenTicks < LEGACY_RECOVERY_START_TICK) return 0.0F;
		return 1.0F - ramp(screenTicks, LEGACY_RECOVERY_START_TICK, RECOVERY_LOCK_TICKS);
	}

	private static float lerp(float from, float to, float progress) {
		return from + (to - from) * Math.clamp(progress, 0.0F, 1.0F);
	}

	public static boolean mayCloseLoadingScreen(int screenTicks, boolean resourceReloadFinished,
			boolean viewportFlooded) {
		if (screenTicks < MIN_LOADING_SCREEN_TICKS) return false;
		if (!viewportFlooded && screenTicks < MAX_RESOURCE_RELOAD_WAIT_TICKS) return false;
		return resourceReloadFinished || screenTicks >= MAX_RESOURCE_RELOAD_WAIT_TICKS;
	}
}
