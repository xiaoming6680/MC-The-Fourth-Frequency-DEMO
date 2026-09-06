package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.networking.UnrenderedPhasePayload;
import com.xm.thefourthfrequency.unrendered.UnrenderedBearingPolicy;
import com.xm.thefourthfrequency.unrendered.UnrenderedDimensions;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The whole client side of the unrendered layer: the cover over both teleports, the scream, and the
 * loop that is the only thing audible down there.
 *
 * <p>Almost none of this needs telling. Being in the layer is a fact the client already holds - it
 * knows which dimension it is in - so the ambience, the suppressed score and the locked view
 * distance are all decided locally and cost no packets at all. The two things the server has to
 * send are the two it cannot see coming: a blackout has to be up <em>before</em> a teleport, not
 * after it, or the player watches the destination assemble itself.
 */
public final class UnrenderedLayerClient {
	/**
	 * Ticks the cover takes to lift once its hold expires. Zero: it is cut, not faded.
	 *
	 * <p>A fade reads as a transition somebody wrote - the screen easing you back in, which is a
	 * reassuring thing for a screen to do. A cut reads as the frame simply being different from the
	 * one before it, and that is what the layer is: nothing eased the player into it either.
	 */
	private static final int RELEASE_TICKS = 0;
	/**
	 * The layer bed's one and only mix control.
	 *
	 * <p>Quiet because of how long it runs, not because it does not matter. The bed is the only thing
	 * audible in the layer and it plays for up to six unbroken minutes, which is an argument for it
	 * being low rather than for it being present - it had been mixed as though it were a cue. At full
	 * it also buried the two sounds down here that carry information: the entity's approach, which is
	 * the only way to tell where the thing is, and the player's own footsteps changing as they cross
	 * a false floor.
	 *
	 * <p><b>The file stays at the -20 LUFS reference and every adjustment happens here.</b> That rule
	 * exists because it was broken: the shipped level was lowered and this constant was lowered, by
	 * two passes that could not see each other, and the two reductions multiplied into a bed that was
	 * effectively gone. One knob is the fix, and it is this one - the beds in {@code
	 * SignalBedController} carry their mix the same way, in a relative volume beside the playback
	 * rather than baked into the asset.
	 *
	 * <p>0.11 against a -20 LUFS file is about -39 LUFS in play: it was 1.0, then 0.2, and 0.2 was
	 * still reported as too loud.
	 */
	private static final float AMBIENCE_VOLUME = 0.11F;
	private static final int AMBIENCE_FADE_TICKS = 40;
	/**
	 * How far the camera sinks while the floor is giving way, in blocks, and over how long.
	 *
	 * <p>Three blocks in ten ticks, on an accelerating curve, so it reads as falling rather than as
	 * an elevator. Deep enough that the camera is well inside the ground by the time the cover comes
	 * up - the last thing on screen is the underside of the world going past, not the room the player
	 * was standing in.
	 */
	private static final double ENTRY_SINK_BLOCKS = 3.0D;
	private static final int ENTRY_SINK_TICKS = 10;
	/**
	 * The other end of the same fall: the head still in the ceiling when the cover lifts.
	 *
	 * <p>The server places an arriving player two blocks up, so their eyes sit just under the ceiling
	 * slab. This lifts the camera the last three quarters of a block into it and holds it there for
	 * three ticks before easing out over eight, which is the moment of being stuck in the roof of the
	 * room - and then the fall the server already started carries the rest.
	 *
	 * <p>Camera only, exactly like the sink. The body is in open air the whole time, so nothing
	 * suffocates, nothing is pushed, and the arrival still costs the player nothing.
	 */
	private static final double ARRIVAL_CEILING_BLOCKS = 0.75D;
	private static final int ARRIVAL_HOLD_TICKS = 3;
	private static final int ARRIVAL_EASE_TICKS = 8;
	/**
	 * Ticks between rebuilding the heartbeat's position, and how far off the entity is still audible.
	 *
	 * <p>The loop is re-aimed every tick from the tracked entity, which is what makes it a bearing
	 * rather than a stamp on the floor where the thing used to be.
	 */
	private static final double HEARTBEAT_RANGE_BLOCKS = 64.0D;
	/**
	 * How the beat changes as it closes, in ticks between beats and in volume.
	 *
	 * <p>Both curves run the same way and that is deliberate: a cue that got louder without getting
	 * faster would read as the player's own hearing improving, and one that got faster without
	 * getting louder would read as a timer. Together they read as something approaching.
	 *
	 * <p>Thirty ticks at the edge of hearing is a slow, deniable knock about every second and a half;
	 * seven at contact is a fast pulse. The asset itself is one second long, so at the fast end the
	 * beats overlap slightly, which is what makes the last few metres feel like panic rather than
	 * like a metronome speeding up.
	 */
	private static final float HEARTBEAT_SLOW_TICKS = 30.0F;
	private static final float HEARTBEAT_FAST_TICKS = 7.0F;
	private static final float HEARTBEAT_MIN_VOLUME = 0.35F;
	private static final float HEARTBEAT_MAX_VOLUME = 1.0F;

	private static boolean initialized;
	private static int holdTicks;
	private static int releaseTicks;
	private static boolean descending;
	/** Last compass index the server sent, or -1 for "say nothing". Redrawn every frame from this. */
	private static int bearing = -1;
	private static boolean wasInLayer;
	private static AmbienceLoop ambience;
	private static int heartbeatCountdown;
	/**
	 * Whether the entity has been placed in this session, latched once true.
	 *
	 * <p>Latched rather than live, because it drives the score and the score must not stutter. The
	 * entity leaves and re-enters the client's tracking range constantly during a chase, and a track
	 * that started and stopped with it would be commentary on the player's own distance rather than
	 * on the situation. Cleared with everything else on leaving the layer.
	 */
	private static boolean hunted;
	private static int sinkAge = -1;
	private static int arrivalAge = -1;

	private UnrenderedLayerClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(UnrenderedPhasePayload.TYPE,
				(payload, context) -> context.client().execute(() -> accept(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(UnrenderedLayerClient::tick);
		HudRenderCallback.EVENT.register((graphics, delta) -> {
			int alpha = coverAlpha();
			if (alpha <= 0) return;
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
		});
	}

	/** Whether the layer currently owns the screen, for anything else that needs to stand down. */
	public static boolean covering() {
		return holdTicks > 0 || releaseTicks > 0;
	}

	/**
	 * Whether the local player is still falling out of the sky after leaving the layer.
	 *
	 * <p>Ended by the client rather than by the server, because the question is "is this body in the
	 * air", which the client can see every frame and the server would have to be asked about over a
	 * packet that could arrive a tick late - and a tick late here is the render distance snapping
	 * back while the ground is still coming up.
	 */
	public static boolean descending() {
		return descending;
	}

	/**
	 * The standing bearing line, or null when there is nothing to say.
	 *
	 * <p>Permanent rather than queued, which is why it is a value the HUD reads rather than a notice
	 * the HUD is handed. A bearing that expired after a few seconds was something the player had to
	 * memorise between showings; one that stays on screen is something they can walk by, and walking
	 * by it is the entire use of it.
	 *
	 * <p>Guarded on actually being in the layer as well as on having a value: a stale direction
	 * surviving the return teleport for even a frame would be the readout pointing at somewhere that
	 * is not in this world.
	 */
	public static Component bearingLine() {
		Minecraft client = Minecraft.getInstance();
		if (bearing < 0 || client.player == null || !inLayer()) return null;
		// Recomputed here rather than cached, because it depends on where the player is looking and
		// that changes every frame. The HUD only rebuilds its entry when the sentence actually
		// changes, so the cost of asking every frame is one integer divide.
		int sector = UnrenderedBearingPolicy.relativeSector(bearing, client.player.getYRot());
		if (sector < 0) return null;
		return Component.translatable("message.thefourthfrequency.unrendered.overworld_signal",
				Component.translatable("message.thefourthfrequency.unrendered.relative."
						+ UnrenderedBearingPolicy.RELATIVE_KEYS[sector]));
	}

	/** Whether the entity has turned up in this session. The score waits for it. */
	public static boolean hunted() {
		return hunted && inLayer();
	}

	/** Whether the local player is in the layer. The music director and the fog both ask. */
	public static boolean inLayer() {
		Minecraft client = Minecraft.getInstance();
		return client.level != null && UnrenderedDimensions.isUnrendered(client.level);
	}

	private static void accept(UnrenderedPhasePayload payload) {
		switch (payload.phase()) {
			// The floor giving way. Nothing is covered yet and nothing has moved: the camera goes
			// down through the ground on its own while the player is still standing on it.
			case UnrenderedPhasePayload.FALL -> {
				sinkAge = 0;
				arrivalAge = -1;
			}
			case UnrenderedPhasePayload.ENTER -> holdTicks = Math.max(holdTicks, payload.holdTicks());
			case UnrenderedPhasePayload.CAPTURE -> {
				holdTicks = Math.max(holdTicks, payload.holdTicks());
				// Played rather than requested from the server so it survives the return teleport.
				// A positional sound is dropped the instant the client changes level, and the whole
				// point of this cue is that it carries across exactly that moment.
				play(ModSounds.UNRENDERED_CAPTURE_SCREAM, 1.0F, 1.0F);
			}
			case UnrenderedPhasePayload.EXIT -> {
				holdTicks = Math.max(holdTicks, payload.holdTicks());
				// Raised here rather than when the cover lifts: the render distance has to already be
				// sixteen while the chunks are streaming in behind the black screen, or the player
				// watches them arrive after it.
				descending = true;
			}
			case UnrenderedPhasePayload.BEARING -> bearing = payload.holdTicks();
			case UnrenderedPhasePayload.CLEAR -> {
				bearing = -1;
				if (holdTicks > 0) releaseTicks = RELEASE_TICKS;
				holdTicks = 0;
			}
			default -> { }
		}
	}

	private static void tick(Minecraft client) {
		// The cover's clock stops while the loading screen is up. It used to run through it, so a
		// hold sized for the transition was spent on the load instead and expired the moment the
		// world appeared - handing the player the last half second of terrain streaming in, which is
		// the one thing the blackout exists to take away. Now the hold measures a second of black
		// the player actually sees, however long the load took to get there.
		boolean loading = client.screen instanceof LevelLoadingScreen;
		if (!loading) {
			if (holdTicks > 0 && --holdTicks == 0) releaseTicks = RELEASE_TICKS;
			else if (releaseTicks > 0) releaseTicks--;
		}
		if (sinkAge >= 0) sinkAge++;
		if (arrivalAge >= 0 && ++arrivalAge >= ARRIVAL_HOLD_TICKS + ARRIVAL_EASE_TICKS) arrivalAge = -1;

		if (client.level == null || client.player == null) {
			stopAmbience();
			stopHeartbeat();
			// Leaving the world with the cover still up would carry it onto the title screen.
			holdTicks = 0;
			releaseTicks = 0;
			descending = false;
			bearing = -1;
			hunted = false;
			heartbeatCountdown = 0;
			sinkAge = -1;
			arrivalAge = -1;
			return;
		}
		// The descent ends when the body stops falling, however that happens - landing, water, or
		// dying on the way to something else. Held past the cover deliberately: the fall is six
		// seconds and the black screen is two.
		if (descending && (client.player.onGround() || !client.player.isAlive())) descending = false;
		boolean inLayer = inLayer();
		// The tick the layer becomes the current level is the tick the player came through its
		// ceiling. Detected here rather than announced, for the same reason the ambience is: the
		// client already knows which dimension it is in, and a packet could only arrive later.
		if (inLayer && !wasInLayer) {
			sinkAge = -1;
			arrivalAge = 0;
		}
		wasInLayer = inLayer;
		if (inLayer) {
			startAmbience(client);
			tickHeartbeat(client);
		} else {
			stopAmbience();
			stopHeartbeat();
		}
	}

	/**
	 * How far the camera is displaced this frame, in blocks. Positive is up.
	 *
	 * <p>Read by {@code CameraUnrenderedEntryMixin} and by nothing else. It moves the picture and
	 * never the player: aim, collision, reach and every ray trace are exactly what they would be with
	 * this returning zero, which is the same rule the encounter's camera shake keeps.
	 */
	public static double cameraOffset(float partialTick) {
		if (sinkAge >= 0) {
			float age = Math.min(ENTRY_SINK_TICKS, sinkAge + partialTick);
			float progress = age / ENTRY_SINK_TICKS;
			// Squared, so it starts where the player was standing and gathers speed downward.
			return -ENTRY_SINK_BLOCKS * progress * progress;
		}
		if (arrivalAge < 0) return 0.0D;
		float age = arrivalAge + partialTick;
		if (age <= ARRIVAL_HOLD_TICKS) return ARRIVAL_CEILING_BLOCKS;
		float eased = Math.clamp((age - ARRIVAL_HOLD_TICKS) / ARRIVAL_EASE_TICKS, 0.0F, 1.0F);
		// Cubic ease-out: the head comes loose quickly and settles, rather than sliding down evenly.
		float remaining = 1.0F - eased;
		return ARRIVAL_CEILING_BLOCKS * remaining * remaining * remaining;
	}

	private static int coverAlpha() {
		if (holdTicks > 0) return 255;
		// Guarded rather than assumed: RELEASE_TICKS is zero for the cut, and the ramp below would
		// divide by it. Keeping the ramp means a fade can be restored by changing one constant.
		if (releaseTicks <= 0 || RELEASE_TICKS <= 0) return 0;
		return Mth.clamp(Math.round(255.0F * releaseTicks / RELEASE_TICKS), 0, 255);
	}

	private static void startAmbience(Minecraft client) {
		if (ambience != null && !ambience.isStopped()) return;
		ambience = new AmbienceLoop();
		client.getSoundManager().play(ambience);
	}

	private static void stopAmbience() {
		if (ambience == null) return;
		ambience.fadeOut();
		ambience = null;
	}

	/**
	 * Keeps one heartbeat playing on whichever entity is currently hunting this player.
	 *
	 * <p>Driven entirely from what the client can already see. The entity is a tracked entity in a
	 * dimension holding exactly one player, so "the thing in here" is unambiguous without a packet,
	 * and the loop simply follows its position - which is what turns the cue into a bearing instead
	 * of a marker on the spot it was placed.
	 *
	 * <p>Stops when the entity leaves the client's tracking range as well as when it is removed. A
	 * heartbeat that carried on from the last place it was seen would be worse than none: it would be
	 * a wrong bearing, and this mod does not ship instruments that lie.
	 */
	private static void tickHeartbeat(Minecraft client) {
		Entity source = nearestStalker(client);
		if (source == null) {
			heartbeatCountdown = 0;
			return;
		}
		hunted = true;
		if (--heartbeatCountdown > 0) return;
		double distance = Math.sqrt(source.distanceToSqr(client.player));
		// Nought at the player, one at the edge of hearing. Everything below is stated in terms of
		// closeness so the two curves cannot end up pointing opposite ways.
		float far = (float) Math.clamp(distance / HEARTBEAT_RANGE_BLOCKS, 0.0D, 1.0D);
		float near = 1.0F - far;
		heartbeatCountdown = Math.round(Mth.lerp(near, HEARTBEAT_SLOW_TICKS, HEARTBEAT_FAST_TICKS));
		float volume = Mth.lerp(near, HEARTBEAT_MIN_VOLUME, HEARTBEAT_MAX_VOLUME);
		// Played at the entity rather than at the ear, so vanilla's own attenuation and panning give
		// it a direction. That is the whole reason this is the layer's second instrument: the readout
		// says where the way out is, and this says where the thing is.
		client.getSoundManager().play(new SimpleSoundInstance(
				ModSounds.UNRENDERED_HEARTBEAT, SoundSource.HOSTILE, volume, 1.0F,
				RandomSource.create(), source.getX(),
				source.getY() + source.getBbHeight() * 0.6D, source.getZ()));
	}

	private static Entity nearestStalker(Minecraft client) {
		if (client.level == null || client.player == null) return null;
		Entity best = null;
		double bestDistance = HEARTBEAT_RANGE_BLOCKS * HEARTBEAT_RANGE_BLOCKS;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.getType() != ModEntities.BACTERIA || entity.isRemoved()) continue;
			double distance = entity.distanceToSqr(client.player);
			if (distance >= bestDistance) continue;
			bestDistance = distance;
			best = entity;
		}
		return best;
	}

	/**
	 * Drops the beat schedule. Nothing to stop: each beat is a one-shot that ends on its own.
	 *
	 * <p>It used to be a looping instance held open for the whole session, which is what made the cue
	 * a flat repeating tone at a fixed rate - a loop can be moved and faded but it cannot be made to
	 * beat faster, and speeding up as it closes is the entire point of a heartbeat.
	 */
	private static void stopHeartbeat() {
		heartbeatCountdown = 0;
	}

	private static void play(SoundEvent cue, float volume, float pitch) {
		Minecraft client = Minecraft.getInstance();
		if (client.getSoundManager() == null) return;
		client.getSoundManager().play(SimpleSoundInstance.forUI(cue, pitch, volume));
	}

	/**
	 * The twenty-second bed, faded rather than cut at both ends.
	 *
	 * <p>{@link SoundSource#AMBIENT} rather than MASTER, unlike the signal beds this is modelled on.
	 * Those belong to the transmission and deliberately survive an anomaly that silences the world;
	 * this one <em>is</em> the world the player is standing in, so a player who has turned ambient
	 * sound down has said something about exactly this and should be listened to.
	 */
	/**
	 * The entity's heartbeat, positioned on it every tick.
	 *
	 * <p>{@link SoundSource#HOSTILE} rather than AMBIENT: this is a creature, not the room, and a
	 * player who has turned ambient sound down to live with the bed must not thereby lose the one
	 * cue that says where the thing is.
	 *
	 * <p>Its own volume, unlike the bed's, is flat. Distance is the engine's job here - the event is
	 * registered at a fixed sixty-four block radius precisely so the falloff carries the information
	 * - and scaling it here as well would be attenuating twice and reporting a distance nothing else
	 * agrees with.
	 */
	private static final class AmbienceLoop extends AbstractTickableSoundInstance {
		private int age;
		private int fadeOutAge = -1;

		private AmbienceLoop() {
			super(ModSounds.UNRENDERED_LAYER_AMBIENCE, SoundSource.AMBIENT, RandomSource.create());
			this.volume = 0.0F;
			this.pitch = 1.0F;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		private void fadeOut() {
			if (fadeOutAge < 0) fadeOutAge = 0;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			age++;
			if (fadeOutAge >= 0) {
				fadeOutAge++;
				float remaining = 1.0F - fadeOutAge / (float) AMBIENCE_FADE_TICKS;
				if (remaining <= 0.0F) {
					volume = 0.0F;
					stop();
					return;
				}
				volume = AMBIENCE_VOLUME * remaining;
				return;
			}
			volume = AMBIENCE_VOLUME * Mth.clamp(age / (float) AMBIENCE_FADE_TICKS, 0.0F, 1.0F);
		}
	}
}
