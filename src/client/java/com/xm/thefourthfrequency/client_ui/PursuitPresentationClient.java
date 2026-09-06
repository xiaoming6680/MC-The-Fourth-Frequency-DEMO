package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.entity.ReworkEntity;
import com.xm.thefourthfrequency.networking.PursuitPresentationPayload;
import com.xm.thefourthfrequency.pursuit.PursuitDimensions;
import com.xm.thefourthfrequency.pursuit.PursuitPresentationTimeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Comparator;

/** Client-only pursuit warning, synthetic frame collapse, blackout, filter, and heartbeat. */
public final class PursuitPresentationClient {
	/** The mid band, and the chain installed before any corrector distance is known. */
	private static final Identifier PURSUIT_POST_EFFECT = Identifier.fromNamespaceAndPath(
			"thefourthfrequency", "pursuit_low_res");
	private static final Identifier PURSUIT_POST_EFFECT_DISTANT = Identifier.fromNamespaceAndPath(
			"thefourthfrequency", "pursuit_low_res_distant");
	private static final Identifier PURSUIT_POST_EFFECT_CLOSE = Identifier.fromNamespaceAndPath(
			"thefourthfrequency", "pursuit_low_res_close");
	private static final Identifier PURSUIT_POST_EFFECT_CONTACT = Identifier.fromNamespaceAndPath(
			"thefourthfrequency", "pursuit_low_res_contact");
	/**
	 * Settling ticks demanded once the destination's own chunks are in hand.
	 *
	 * <p>Two was chosen back when the loading screen going away was the only thing this waited on,
	 * and it is far too short for what it is now cushioning: the chunk data being present is not the
	 * same as the section renderer having compiled it, and the gap between the two is exactly the
	 * frame where terrain visibly assembles itself.</p>
	 */
	private static final int DESTINATION_STABLE_TICKS_AFTER_LOAD = 8;
	private static final int DESTINATION_FALLBACK_STABLE_TICKS = 12;
	/**
	 * Chunks that must exist client-side around the arrival point before the cover comes off.
	 *
	 * <p>Three, not the render distance. The point is that nothing assembles itself in front of the
	 * player, and what is close enough to be caught doing that is the immediate neighbourhood; a
	 * whole render distance would be a wait that scales with the player's video settings and, on a
	 * slow stream, would never end.</p>
	 */
	private static final int DESTINATION_CHUNK_RADIUS = 3;
	/**
	 * Ceiling on the whole wait, counted from the phase change.
	 *
	 * <p>The gate is a presentation nicety and the pursuit is not: a stalled or throttled chunk
	 * stream must never be able to leave someone sitting in front of a black screen. Ten seconds
	 * out, the cover comes off regardless and they see whatever is there. A brief pop-in is a
	 * blemish; a black screen with no way out is a broken game.</p>
	 */
	private static final int DESTINATION_READY_TIMEOUT_TICKS = 200;
	/** Four scans a second is well under the hysteresis band, so it cannot cause chain churn. */
	private static final int PROXIMITY_SCAN_INTERVAL_TICKS = 5;
	/**
	 * Above one on purpose. Minecraft derives a positional sound's audible radius from its volume -
	 * sixteen blocks times the volume squared, once the volume passes one - and then fades it
	 * linearly to nothing at that radius. This puts the edge just past the forty-eight blocks the
	 * heartbeat cadence is defined over, so the beat carries across the whole band the interval
	 * describes and gets quieter honestly on the way out.
	 */
	private static final float HEARTBEAT_VOLUME = 1.75F;
	/** How far out a corrector is still looked for; past this both spatial cues read as absent. */
	private static final double CORRECTOR_SCAN_RADIUS = 96.0D;
	private static Phase phase = Phase.IDLE;
	private static String sessionId = "";
	private static int form;
	private static int warningTicks;
	private static int heartbeatCooldown;
	private static int proximityScanCooldown;
	private static PursuitPresentationTimeline.ProximityGrade proximityGrade =
			PursuitPresentationTimeline.ProximityGrade.DISTANT;
	private static int resolutionTicks;
	private static long lastPresentedFrameNanos;
	private static boolean frameHeld;
	private static boolean freezeFramePending;
	private static boolean runningRequested;
	private static boolean clearRequested;
	private static boolean noticeQueueLocked;
	private static int destinationStableTicks;
	private static int destinationWaitTicks;
	private static boolean transitionLoadingObserved;
	private static boolean initialized;

	private PursuitPresentationClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(PursuitPresentationPayload.TYPE, (payload, context) ->
				context.client().execute(() -> accept(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(PursuitPresentationClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderOverlay(graphics));
	}

	/**
	 * Decides, once per frame, whether this frame is dropped - and must run before Minecraft
	 * clears the main render target.
	 *
	 * <p>Holding a frame is not the same as drawing nothing. {@code Minecraft.runTick} clears the
	 * main target to opaque black, then renders into it, then blits it to the screen; cancelling
	 * only the render therefore published a black frame, and the collapse ramp - which drops from
	 * sixty frames a second to four - published them alternating with live ones. That is a strobe,
	 * not a failing video feed, and the capture freeze that is supposed to leave the player staring
	 * at the last thing they saw went black instead. Skipping the clear as well leaves the previous
	 * frame sitting in the target, so the blit re-presents it and the picture genuinely holds.</p>
	 *
	 * @return true when the clear and the render must both be skipped this frame
	 */
	public static boolean beginFrame(long nowNanos) {
		frameHeld = evaluateFrameHold(nowNanos);
		return frameHeld;
	}

	/**
	 * Whether the pursuit is currently freezing the picture.
	 *
	 * <p>Read-only, and does not consume the flag the way {@link #skipRenderFrame()} does. The boss
	 * fight's own effects ask this so they can stand down: shaking the camera or starting a second
	 * freeze underneath a held frame does nothing except desynchronise the two.
	 */
	public static boolean isHoldingFrame() {
		return frameHeld;
	}

	/**
	 * Consumed by the render cancel, which disarms it: if the clear-site hook ever stops running,
	 * the worst case is a frame that draws normally rather than a screen frozen forever.
	 */
	public static boolean skipRenderFrame() {
		boolean held = frameHeld;
		frameHeld = false;
		return held;
	}

	private static boolean evaluateFrameHold(long nowNanos) {
		if (phase == Phase.CAPTURE_FREEZE) return true;
		if (phase != Phase.WARNING) return false;
		PursuitPresentationTimeline.Stage localStage = PursuitPresentationTimeline.stageAt(warningTicks);
		if (localStage == PursuitPresentationTimeline.Stage.TERMINAL_WARNING) return false;
		if (localStage == PursuitPresentationTimeline.Stage.FROZEN
				|| localStage == PursuitPresentationTimeline.Stage.BLACKOUT) {
			if (freezeFramePending) {
				freezeFramePending = false;
				lastPresentedFrameNanos = nowNanos;
				return false;
			}
			return true;
		}
		long interval = PursuitPresentationTimeline.frameIntervalNanos(warningTicks);
		if (lastPresentedFrameNanos == 0L || nowNanos - lastPresentedFrameNanos >= interval) {
			lastPresentedFrameNanos = nowNanos;
			return false;
		}
		return true;
	}

	public static boolean shouldCoverLoadingScreen() {
		return phase == Phase.BLACKOUT || clearRequested;
	}

	public static boolean blocksPlayerInput() {
		if (phase == Phase.BLACKOUT || phase == Phase.CAPTURE_FREEZE) return true;
		if (phase != Phase.WARNING) return false;
		PursuitPresentationTimeline.Stage localStage = PursuitPresentationTimeline.stageAt(warningTicks);
		return localStage == PursuitPresentationTimeline.Stage.FROZEN
				|| localStage == PursuitPresentationTimeline.Stage.BLACKOUT;
	}

	public static boolean pursuitHudActive() {
		return phase == Phase.RUNNING;
	}

	public static boolean escapeHudActive() {
		return phase == Phase.ESCAPE_RESOLUTION;
	}

	public static boolean holdsNoticeQueue() {
		return noticeQueueLocked;
	}

	public static boolean locksPauseExit() {
		return phase != Phase.IDLE || clearRequested;
	}

	/**
	 * True once the mirror has arrived and until the run is over: the pursuit carries its own theme,
	 * and it starts under the black screen rather than after it, so the track is already swelling by
	 * the time there is anything to see.
	 *
	 * <p>It waits for the level rather than merely for the blackout because a dimension change stops
	 * the music manager outright - {@code ClientPacketListener#handleRespawn} calls
	 * {@code stopPlaying()} unconditionally, as part of every transfer. A track started on the near
	 * side of the transfer is therefore cut mid-fade and restarted from the top the moment the
	 * mirror lands, which is audible as the theme stuttering back to its first bar. Starting on the
	 * far side costs nothing: the blackout covers the transfer and the loading screen alike, so the
	 * fade-in still happens with the screen black.</p>
	 */
	public static boolean scoresMusic() {
		if (clearRequested || phase != Phase.BLACKOUT && phase != Phase.RUNNING) return false;
		Minecraft client = Minecraft.getInstance();
		return client.level != null && PursuitDimensions.isMirror(client.level);
	}

	/**
	 * True for every part of a pursuit the theme does not cover: the warning, both resolutions, and
	 * the return trip. The warning is where whatever was playing has to start leaving, so that the
	 * blackout has nothing left of the overworld underneath it.
	 */
	public static boolean silencesMusic() {
		return (phase != Phase.IDLE || clearRequested) && !scoresMusic();
	}

	/**
	 * True at the two moments the feed is severed rather than faded: the blackout, and the capture.
	 * The warning ahead of the blackout lasts at least eight seconds, so the fade driven by
	 * {@link #silencesMusic()} has already carried the previous track most of the way down - the cut
	 * is what removes the remainder, so the pursuit theme starts from real silence.
	 */
	public static boolean cutsMusic() {
		return phase == Phase.BLACKOUT || phase == Phase.CAPTURE_FREEZE;
	}

	private static void accept(PursuitPresentationPayload payload) {
		switch (payload.phase()) {
			case PursuitPresentationPayload.WARNING -> beginWarning(payload);
			case PursuitPresentationPayload.BLACKOUT -> {
				if (!sameSession(payload.sessionId())) return;
				phase = Phase.BLACKOUT;
				warningTicks = PursuitPresentationTimeline.PRELUDE_TICKS;
				runningRequested = false;
				lastPresentedFrameNanos = 0L;
				resetTransitionGate();
				clearOwnedPostEffect(Minecraft.getInstance());
			}
			case PursuitPresentationPayload.RUNNING -> {
				if (!sameSession(payload.sessionId())) return;
				form = payload.form();
				phase = Phase.BLACKOUT;
				runningRequested = true;
				noticeQueueLocked = true;
				clearRequested = false;
				resetTransitionGate();
			}
			case PursuitPresentationPayload.CAPTURE_FREEZE -> {
				if (!sameSession(payload.sessionId())) return;
				phase = Phase.CAPTURE_FREEZE;
				resolutionTicks = 0;
				runningRequested = false;
				noticeQueueLocked = true;
				play(Minecraft.getInstance(), ModSounds.ALPHA_CORRUPTION_COLLAPSE, 0.66F, 0.96F);
				// Over the collapse rather than instead of it, and only on this phase. The blackout
				// that opens a pursuit gets no scream - that one is the mod taking the player
				// somewhere, and this one is the mod telling them it caught them.
				play(Minecraft.getInstance(), ModSounds.PURSUIT_CAPTURE_SCREAM, 1.0F, 0.85F);
			}
			case PursuitPresentationPayload.ESCAPE_RESOLUTION -> {
				if (!sameSession(payload.sessionId())) return;
				phase = Phase.ESCAPE_RESOLUTION;
				resolutionTicks = 0;
				runningRequested = false;
				noticeQueueLocked = true;
			}
			default -> {
				clearRequested = true;
				runningRequested = false;
				resetTransitionGate();
			}
		}
	}

	private static void beginWarning(PursuitPresentationPayload payload) {
		Minecraft client = Minecraft.getInstance();
		clearOwnedPostEffect(client);
		sessionId = payload.sessionId();
		form = payload.form();
		phase = Phase.WARNING;
		warningTicks = 0;
		heartbeatCooldown = 0;
		// Every pursuit opens at the calmest band instead of inheriting the previous one's, so the
		// first frames after the blackout are never already fully degraded.
		proximityScanCooldown = 0;
		proximityGrade = PursuitPresentationTimeline.ProximityGrade.DISTANT;
		resolutionTicks = 0;
		lastPresentedFrameNanos = 0L;
		freezeFramePending = false;
		runningRequested = false;
		clearRequested = false;
		noticeQueueLocked = false;
		resetTransitionGate();
		// The ten-second lead-in was carried entirely by terminal text. The two-tone attention
		// signal is the sound people already read as "stop what you are doing", which is exactly
		// the authority the warning wants before anything is visibly wrong.
		play(client, ModSounds.SIGNAL_ALERT, 1.0F, 0.85F);
	}

	private static boolean sameSession(String candidate) {
		return !sessionId.isBlank() && sessionId.equals(candidate);
	}

	private static void tick(Minecraft client) {
		if (phase == Phase.WARNING) {
			warningTicks++;
			if (warningTicks == PursuitPresentationTimeline.FREEZE_START_TICKS) {
				freezeFramePending = true;
				play(client, ModSounds.ALPHA_CORRUPTION_COLLAPSE, 0.72F, 0.92F);
			}
			if (warningTicks >= PursuitPresentationTimeline.FREEZE_START_TICKS
					&& warningTicks < PursuitPresentationTimeline.PRELUDE_TICKS
					&& (warningTicks - PursuitPresentationTimeline.FREEZE_START_TICKS) % 5 == 0) {
				float pitch = 0.50F + (warningTicks % 4) * 0.07F;
				play(client, ModSounds.ALPHA_CORRUPTION_WARNING, pitch, 0.30F);
			}
		}
		if (runningRequested && readyToRevealMirror(client)) {
			runningRequested = false;
			phase = Phase.RUNNING;
			heartbeatCooldown = 1;
			installPostEffect(client);
		}
		if (phase == Phase.RUNNING) {
			tickSpatialCues(client);
			installPostEffect(client);
		}
		if (phase == Phase.CAPTURE_FREEZE && resolutionTicks++ < 60
				&& resolutionTicks % 5 == 0) {
			float pitch = 0.48F + (resolutionTicks % 4) * 0.06F;
			play(client, ModSounds.ALPHA_CORRUPTION_WARNING, pitch, 0.34F);
		}
		if (clearRequested && readyToClear(client) && captureScreamFinished()) {
			reset(client);
		}
	}

	/**
	 * Whether the capture scream has had time to finish before the black screen is allowed to lift.
	 *
	 * <p>The freeze used to end the moment the server said the session was over, which is a second
	 * or two into a cue that runs nearly four - so the player was handed back to the world mid-shriek
	 * and the loudest thing in the mod was cut off by its own resolution. That reads as a bug, not as
	 * having been caught.
	 *
	 * <p>Sized to the asset rather than guessed: {@code client/pursuit/capture_scream.ogg} is 3.81
	 * seconds. Only ever delays a clear during the capture freeze; every other phase is untouched.
	 */
	private static boolean captureScreamFinished() {
		return phase != Phase.CAPTURE_FREEZE || resolutionTicks >= CAPTURE_SCREAM_HOLD_TICKS;
	}

	/** Ticks the capture freeze holds at minimum. The scream is 3.81 seconds; this is four. */
	private static final int CAPTURE_SCREAM_HOLD_TICKS = 80;

	private static boolean readyToRevealMirror(Minecraft client) {
		return destinationReady(client, true);
	}

	private static boolean readyToClear(Minecraft client) {
		return destinationReady(client, false);
	}

	/**
	 * Whether the cover can come off yet.
	 *
	 * <p>This used to ask only whether the loading screen had gone and the dimension was the right
	 * one, then wait two ticks. But the loading screen leaves as soon as vanilla considers the world
	 * entered, which is well before the terrain around the player has arrived - so a hundred
	 * milliseconds later the black came off and the player watched the world build itself. The
	 * transition existed precisely so that they would not.</p>
	 *
	 * <p>So the chunks themselves are now the gate, with the settling ticks demoted to covering the
	 * render compilation that follows the data. Everything is behind a hard timeout.</p>
	 */
	private static boolean destinationReady(Minecraft client, boolean mirrorDestination) {
		destinationWaitTicks++;
		if (worldLoadingVisible(client)) {
			transitionLoadingObserved = true;
			destinationStableTicks = 0;
			return false;
		}
		if (client.level == null || client.player == null
				|| PursuitDimensions.isMirror(client.level) != mirrorDestination) {
			destinationStableTicks = 0;
			return destinationWaitTicks >= DESTINATION_READY_TIMEOUT_TICKS;
		}
		if (!destinationChunksLoaded(client)) {
			destinationStableTicks = 0;
			return destinationWaitTicks >= DESTINATION_READY_TIMEOUT_TICKS;
		}
		destinationStableTicks++;
		int requiredStableTicks = transitionLoadingObserved
				? DESTINATION_STABLE_TICKS_AFTER_LOAD : DESTINATION_FALLBACK_STABLE_TICKS;
		return destinationStableTicks >= requiredStableTicks
				|| destinationWaitTicks >= DESTINATION_READY_TIMEOUT_TICKS;
	}

	/** Whether the client actually holds the chunks immediately around wherever the player landed. */
	private static boolean destinationChunksLoaded(Minecraft client) {
		int centerX = client.player.chunkPosition().x;
		int centerZ = client.player.chunkPosition().z;
		for (int dx = -DESTINATION_CHUNK_RADIUS; dx <= DESTINATION_CHUNK_RADIUS; dx++) {
			for (int dz = -DESTINATION_CHUNK_RADIUS; dz <= DESTINATION_CHUNK_RADIUS; dz++) {
				if (!client.level.getChunkSource().hasChunk(centerX + dx, centerZ + dz)) return false;
			}
		}
		return true;
	}

	private static boolean worldLoadingVisible(Minecraft client) {
		return client.screen instanceof LevelLoadingScreen || client.getOverlay() != null;
	}

	private static void resetTransitionGate() {
		destinationStableTicks = 0;
		destinationWaitTicks = 0;
		transitionLoadingObserved = false;
	}

	/**
	 * Drives both spatial channels - the heartbeat and the mosaic band - off one lookup, because
	 * both of them are asking the same question about the same entity. The scan is only paid for on
	 * the ticks one of them is actually due, which at every cadence this timeline produces is far
	 * less often than every tick.
	 */
	private static void tickSpatialCues(Minecraft client) {
		if (client.level == null || client.player == null) return;
		boolean heartbeatDue = heartbeatCooldown-- <= 0;
		boolean gradeDue = proximityScanCooldown-- <= 0;
		if (!heartbeatDue && !gradeDue) return;
		ReworkEntity corrector = closestCorrector(client);
		// Double.MAX_VALUE when nothing is in range, which grades as DISTANT.
		double distance = corrector == null ? Double.MAX_VALUE
				: Math.sqrt(corrector.distanceToSqr(client.player));
		if (gradeDue) {
			// Re-grades how degraded the picture should be from the nearest corrector's distance.
			// This is the only spatial cue that does not require hearing anything, so it matters for
			// players with sound off, and it fits the fiction: the closer the thing gets, the less
			// bandwidth the world has left to describe itself with.
			proximityScanCooldown = PROXIMITY_SCAN_INTERVAL_TICKS;
			proximityGrade = PursuitPresentationTimeline.proximityGrade(distance, proximityGrade);
		}
		if (heartbeatDue) playHeartbeat(client, corrector, distance);
	}

	/**
	 * Beats at the corrector's own position instead of inside the player's head. The interval
	 * already carries how close it is; putting the sound in the world is what carries *where* it is,
	 * and behind a black-and-white mosaic that has thrown most of the picture away, hearing is the
	 * only channel left that can say "not that way". The engine's own panning and falloff do the
	 * work, so nothing here fakes distance in the amplitude - only the pitch still tightens, which
	 * is tension rather than information.
	 */
	private static void playHeartbeat(Minecraft client, ReworkEntity corrector, double distance) {
		if (corrector == null) {
			heartbeatCooldown = 10;
			return;
		}
		heartbeatCooldown = PursuitPresentationTimeline.heartbeatIntervalTicks(distance);
		float proximity = (float) (1.0D - Math.clamp((distance - 3.0D) / 45.0D, 0.0D, 1.0D));
		client.level.playLocalSound(corrector.getX(), corrector.getEyeY(), corrector.getZ(),
				SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, HEARTBEAT_VOLUME,
				0.82F + proximity * 0.30F, false);
	}

	/** Null when no corrector is within {@link #CORRECTOR_SCAN_RADIUS}. */
	private static ReworkEntity closestCorrector(Minecraft client) {
		return client.level.getEntitiesOfClass(ReworkEntity.class,
						client.player.getBoundingBox().inflate(CORRECTOR_SCAN_RADIUS),
						ReworkEntity::isAlive)
				.stream().min(Comparator.comparingDouble(client.player::distanceToSqr)).orElse(null);
	}

	private static Identifier postEffectForGrade() {
		return switch (proximityGrade) {
			case DISTANT -> PURSUIT_POST_EFFECT_DISTANT;
			case CLOSE -> PURSUIT_POST_EFFECT_CLOSE;
			case CONTACT -> PURSUIT_POST_EFFECT_CONTACT;
			default -> PURSUIT_POST_EFFECT;
		};
	}

	private static void installPostEffect(Minecraft client) {
		PostEffectArbiter.claim(client, PostEffectArbiter.Owner.PURSUIT, postEffectForGrade());
	}

	private static void clearOwnedPostEffect(Minecraft client) {
		// Dropping the claim rather than clearing the slot. Which of the four proximity bands was up
		// stops mattering - the arbiter knows what it installed - and, more to the point, a chain
		// somebody else owns is no longer something this can take down by accident.
		PostEffectArbiter.release(client, PostEffectArbiter.Owner.PURSUIT);
	}

	private static void renderOverlay(GuiGraphics graphics) {
		if (phase == Phase.IDLE) return;
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		if (phase == Phase.BLACKOUT || clearRequested) {
			graphics.fill(0, 0, width, height, 0xFF000000);
			return;
		}
		if (phase == Phase.WARNING) return;
		// The dim, the scanlines, the interference bands and the border frame all used to be drawn
		// here as rectangles. Every one of them is a term in digital_corrupt.fsh now - ScanDepth and
		// Vignette were added to it for exactly these two - so the treatment reaches the picture
		// instead of covering it. renderInterference is kept below and no longer called.
	}

	private static int clientTick() {
		Minecraft client = Minecraft.getInstance();
		return client.player == null ? warningTicks : client.player.tickCount;
	}

	/**
	 * <b>Retired.</b> The pursuit's interference bands, superseded by the band displacement and signal loss digital_corrupt.fsh does to the picture itself.
	 *
	 * <p>Kept rather than deleted. It is the reference for what the shader term replacing it is
	 * meant to look like, and the fallback if a driver ever turns out not to compile the chain -
	 * a coloured band drawn on top of the picture is a poor version of the effect, but it is a
	 * version, and nothing calls it today.
	 */
	@SuppressWarnings("unused")
	private static void renderInterference(GuiGraphics graphics, int tick, int bands, int alpha) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		for (int index = 0; index < bands; index++) {
			int seed = scramble(tick * 0x1F123BB5 + index * 0x6D2B79F5 + form * 71);
			int y = Math.floorMod(seed, Math.max(1, height));
			int bandHeight = 1 + Math.floorMod(seed >>> 11, 5);
			int color = Math.clamp(alpha + Math.floorMod(seed >>> 19, 30), 0, 255) << 24
					| ((seed & 1) == 0 ? 0x000000 : 0x7A7577);
			graphics.fill(0, y, width, Math.min(height, y + bandHeight), color);
		}
	}

	private static int scramble(int value) {
		value ^= value >>> 16;
		value *= 0x7FEB352D;
		value ^= value >>> 15;
		value *= 0x846CA68B;
		return value ^ value >>> 16;
	}

	private static void play(Minecraft client, net.minecraft.sounds.SoundEvent sound,
			float pitch, float volume) {
		client.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	private static void reset(Minecraft client) {
		clearOwnedPostEffect(client);
		phase = Phase.IDLE;
		sessionId = "";
		form = 0;
		warningTicks = 0;
		heartbeatCooldown = 0;
		proximityScanCooldown = 0;
		proximityGrade = PursuitPresentationTimeline.ProximityGrade.DISTANT;
		resolutionTicks = 0;
		lastPresentedFrameNanos = 0L;
		frameHeld = false;
		freezeFramePending = false;
		runningRequested = false;
		clearRequested = false;
		noticeQueueLocked = false;
		resetTransitionGate();
	}

	private enum Phase {
		IDLE,
		WARNING,
		BLACKOUT,
		RUNNING,
		CAPTURE_FREEZE,
		ESCAPE_RESOLUTION
	}
}
