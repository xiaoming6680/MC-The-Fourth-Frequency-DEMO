package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.client_render.WorldInterfaceBeamBatchRenderer;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.ending.WorldInterfacePolicy;
import com.xm.thefourthfrequency.ending.WorldInterfaceSummonTimeline;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Owns deterministic attack overlays, ambient transitions, and gateway presentation registration. */
public final class WorldInterfacePresentationController {
	private static final int AMBIENT_FADE_IN_TICKS = 20;
	private static final int AMBIENT_FADE_OUT_TICKS = 16;
	private static final long DAMAGE_FLASH_TICKS = 5L;
	/**
	 * Horizontal reach of the erosion, and of the rebuild it needs.
	 *
	 * <p>Taken from the policy rather than chosen here. The server commits the same disc to the
	 * world when the fight ends, and a render that reached further simply un-drew itself the moment
	 * the encounter cleared - the island visibly healed the outer four fifths of its damage.</p>
	 */
	private static final int EROSION_RADIUS_BLOCKS = WorldInterfacePolicy.EROSION_RADIUS_BLOCKS;
	private static final int LOCK_ACCENT = 0x00C24BE0;
	private static final int LOCK_TEXT = 0x00F0D2FA;
	private static boolean initialized;
	private static UUID trackedEncounterId;
	private static UUID trackedBossId;
	private static float lastObservedHealth = Float.NaN;
	private static long damageFlashUntil = Long.MIN_VALUE;
	private static WorldInterfaceProtocol.GatewayState audibleGatewayState =
			WorldInterfaceProtocol.GatewayState.DORMANT;
	private static WorldInterfaceProtocol.Stage ambientStage;
	private static int failureErosionBucket = -1;
	private static AmbientLoop ambientLoop;
	private static AmbientLoop retiringAmbientLoop;

	private WorldInterfacePresentationController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		WorldInterfaceBeamBatchRenderer.initialize();
		ClientTickEvents.END_CLIENT_TICK.register(WorldInterfacePresentationController::tick);
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderOverlay(graphics));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> hardReset());
	}

	public static void resetForEnding() {
		hardReset();
	}

	/** Teardown that also snaps the atmosphere, for paths where no further frames will ease it out. */
	private static void hardReset() {
		resetPresentationState();
		WorldInterfaceAtmosphereController.reset();
	}

	/** Lets the entity renderer turn authoritative virtual-health deltas into a short hit-material flash. */
	public static boolean isDamageFlashActive(UUID bossId, long gameTime) {
		return bossId != null && bossId.equals(trackedBossId) && gameTime < damageFlashUntil;
	}

	/** Visual-only progressive replacement for the End island; no world block is mutated. */
	public static BlockState failureBlockReplacement(BlockPos pos, BlockState original) {
		// Read client.level exactly once: the main thread can null it out (disconnect, dimension
		// change) between two separate reads, and this method may run on a chunk-build worker
		// thread via the render mixins, so a re-read here was a real, if narrow, NPE race.
		// Erosion re-skins what is already there; it must never conjure a solid face out of air.
		if (original.isAir()) return original;
		// End stone only, which is the same rule the server commits on.
		//
		// This used to re-skin every solid block inside the disc, so the ten obsidian pillars, their
		// bedrock caps and the altar all dissolved into missing-texture along with the ground - while
		// commitErosion, which decides what a lost encounter actually leaves behind, has always been
		// end-stone-only. The island therefore showed a corruption it was never going to keep, and
		// the landmarks a player navigates the arena by disappeared into it. The pillars keep their
		// own material and stand out against the corrupted ground, which is what they are for.
		if (!original.is(Blocks.END_STONE)) return original;
		var level = Minecraft.getInstance().level;
		WorldInterfaceSnapshotS2C encounter = failureEncounter(level);
		if (encounter == null || level.dimension() != Level.END) return original;
		if (insideLiveStabilityField(encounter, pos)) return original;
		long dx = pos.getX() - encounter.center().getX();
		long dz = pos.getZ() - encounter.center().getZ();
		if (dx * dx + dz * dz > (long) EROSION_RADIUS_BLOCKS * EROSION_RADIUS_BLOCKS) return original;
		return WorldInterfacePolicy.erodesAt(encounter.encounterId(), pos.asLong(), encounter.failureProgress())
				? ModBlocks.MISSING_TEXTURE_PROXY.defaultBlockState() : original;
	}

	/** Mirrors the server's infinite-height stability cylinders for visual-only erosion. */
	private static boolean insideLiveStabilityField(WorldInterfaceSnapshotS2C encounter, BlockPos pos) {
		for (int index = 0; index < encounter.anchorPositions().size(); index++) {
			if ((encounter.anchorAliveMask() & (1 << index)) == 0) continue;
			BlockPos anchor = encounter.anchorPositions().get(index);
			if (WorldInterfacePolicy.insideStabilityField(
					pos.getX() + 0.5D, pos.getZ() + 0.5D,
					anchor.getX() + 0.5D, anchor.getZ() + 0.5D)) return true;
		}
		return false;
	}

	/** Entity and player textures flip deterministically as the same failure erosion advances. */
	public static boolean corruptEntityTexture(Identifier id) {
		var level = Minecraft.getInstance().level;
		WorldInterfaceSnapshotS2C encounter = failureEncounter(level);
		if (encounter == null || level.dimension() != Level.END) return false;
		String path = id.getPath();
		if (!(path.startsWith("textures/entity/") || path.startsWith("skins/")
				|| path.contains("player_skin"))) return false;
		long textureSeed = ((long) id.getNamespace().hashCode() << 32) ^ path.hashCode()
				^ encounter.encounterId().getLeastSignificantBits();
		return WorldInterfacePolicy.erosionThreshold(textureSeed) <= encounter.failureProgress();
	}

	/**
	 * Any encounter currently reporting visible erosion, not just a lost one. The server now drives
	 * this progress from the collapse timer during combat as well, so gating on
	 * FAILURE_RESOLUTION here would throw away everything except the final six seconds.
	 */
	private static WorldInterfaceSnapshotS2C failureEncounter(net.minecraft.client.multiplayer.ClientLevel level) {
		if (level == null) return null;
		WorldInterfaceSnapshotS2C encounter = WorldInterfaceClientState.snapshot().encounter();
		return encounter != null && encounter.failureProgress() > 0.0F ? encounter : null;
	}

	private static void resetPresentationState() {
		WorldInterfaceAudioWatchdog.reset();
		stopAmbientLoops();
		WorldInterfaceBeamBatchRenderer.resetSession();
		WorldInterfacePostEffectController.clearOwned(Minecraft.getInstance());
		trackedEncounterId = null;
		trackedBossId = null;
		lastObservedHealth = Float.NaN;
		damageFlashUntil = Long.MIN_VALUE;
		audibleGatewayState = WorldInterfaceProtocol.GatewayState.DORMANT;
		ambientStage = null;
		failureErosionBucket = -1;
		// A shake left running across a disconnect would resume mid-impulse in the next world, and
		// a hold left armed would freeze the first frame of it.
		ScreenShakeController.reset();
		WorldInterfaceHitStop.reset();
		lastAnchorMask = Integer.MIN_VALUE;
		lastActionBeat = -1L;
		lastHurtTime = 0;
		lastPlayerHealth = Float.NaN;
		hurtFlashUntil = Long.MIN_VALUE;
	}

	private static void tick(Minecraft client) {
		if (client.level == null || client.player == null || client.isPaused()) return;
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		// Both run before the early exit: the atmosphere so it eases back out after an encounter ends
		// instead of snapping the fog and sky back in a single frame, and the lock tone so a window
		// that disappears out from under it - a cancelled attack, a lost encounter - silences the
		// cadence rather than leaving it mid-count.
		WorldInterfaceAtmosphereController.tick(client, projection);
		WorldInterfaceLockToneController.tick(client, projection);
		WorldInterfaceSnapshotS2C encounter = projection.encounter();
		if (encounter == null) {
			if (trackedEncounterId != null) resetPresentationState();
			return;
		}
		long now = client.level.getGameTime();
		ScreenShakeController.tick();
		// Measures the sound-channel pool for as long as the fight runs. See the class for why a
		// measurement replaced the guess that rate-limiting the emitters would be enough.
		WorldInterfaceAudioWatchdog.tick(client);
		observeEncounter(client, encounter, now);
		tickImpactFeedback(client, projection, encounter, now);
		WorldInterfacePostEffectController.tick(client, projection);
	}

	/**
	 * Turns the action wire into camera shake and hit-stop.
	 *
	 * <p>Everything left here is derived from {@link BossActionS2C} - action id, start tick, duration,
	 * targets - and the snapshot's own fields, because for these beats the client already knows
	 * exactly when they land and a packet saying "shake now" would be a second, less reliable clock
	 * alongside one that works.
	 *
	 * <p><b>Detonations are no longer among them.</b> They are announced by
	 * {@code WorldInterfaceBlastS2C} instead, and the reason is that the derivation was wrong in three
	 * different ways at once. The lance was shaken at the lock plus {@code SKY_LANCE_FALL_TICKS} - a
	 * render-only constant describing the last three ticks of the charge - so the camera jolted a
	 * second and a half before the crater appeared. The lash was shaken every thirty ticks, while the
	 * limbs actually land at the warning plus a telegraph and then every forty-five, so the two never
	 * coincided at all. And the third phase throws bolts and lashes from a volley lane that the action
	 * envelope does not describe, so a whole second attack lane was silent on the camera by
	 * construction. Where a blast happens is something only the server knows; when a beat lands is
	 * something both sides know. Each is now carried by whichever of the two can actually answer.
	 */
	private static void tickImpactFeedback(Minecraft client,
			WorldInterfaceClientState.Projection projection, WorldInterfaceSnapshotS2C encounter,
			long now) {
		int hurtTime = client.player.hurtTime;
		if (hurtTime > lastHurtTime && now - lastHurtFlashTick >= HURT_FLASH_MIN_GAP_TICKS) {
			lastHurtFlashTick = now;
			hurtFlashUntil = now + HURT_FLASH_TICKS;
			ScreenShakeController.impulse(ScreenShakeController.Grade.MEDIUM);
			// A hit worth two percent of the pool earns a freeze as well as a shake.
			if (client.player.getMaxHealth() > 0.0F
					&& lastPlayerHealth - client.player.getHealth()
					>= client.player.getMaxHealth() * 0.02F) {
				WorldInterfaceHitStop.trigger(WorldInterfaceHitStop.MEDIUM_MILLIS,
						ScreenShakeController.Grade.MEDIUM);
			}
		}
		lastHurtTime = hurtTime;
		lastPlayerHealth = client.player.getHealth();

		// Anchors coming down are a world event now, shaken from the anchor itself by the server -
		// see EndBossEncounterService#emitAnchorCollapse. Tracked here only so the mask stays in step
		// for anything else that reads it.
		lastAnchorMask = encounter.anchorAliveMask();

		if (!projection.actionActive(now)) {
			lastActionBeat = -1L;
			return;
		}
		BossActionS2C action = projection.action();
		long elapsed = now - action.startTick();
		if (elapsed < 0L || elapsed == lastActionBeat) return;
		boolean targeted = projection.actionTargets(client.player.getUUID());
		switch (action.action()) {
			case SKY_LANCE -> {
				// The freeze, and only the freeze: the shake for this arrives with the blast, at the
				// impact, from the server. This is the one attack that earns a freeze on the player it
				// actually lands on, and the landing is the lock window plus the whole charge - not
				// plus SKY_LANCE_FALL_TICKS, which is how long the column spends falling and is a
				// render detail rather than a schedule.
				if (targeted && elapsed == WorldInterfaceProtocol.lockWarningTicks(action.action())
						+ WorldInterfaceProtocol.SKY_LANCE_CHARGE_TICKS) {
					WorldInterfaceHitStop.trigger(WorldInterfaceHitStop.HEAVY_MILLIS,
							ScreenShakeController.Grade.HEAVY);
				}
			}
			case MORPH_TO_SECOND, MORPH_TO_THIRD -> {
				// The shell splitting. Arena-wide, and paired with a freeze: this is the single
				// biggest beat in the fight and the two halves have to land on the same frame.
				if (elapsed == MORPH_REVEAL_SHAKE_TICK) {
					WorldInterfaceHitStop.trigger(WorldInterfaceHitStop.HEAVY_MILLIS,
							ScreenShakeController.Grade.CATACLYSM);
				}
			}
			case SUMMONING -> {
				if (elapsed == WorldInterfaceSummonTimeline.ROAR) {
					WorldInterfaceHitStop.trigger(WorldInterfaceHitStop.HEAVY_MILLIS,
							ScreenShakeController.Grade.CATACLYSM);
				} else if (elapsed == WorldInterfaceSummonTimeline.GROUND_BREAK
						|| elapsed == WorldInterfaceSummonTimeline.EYE_OPEN) {
					ScreenShakeController.impulse(ScreenShakeController.Grade.HEAVY);
				}
			}
			default -> {
			}
		}
		lastActionBeat = elapsed;
	}

	/** Where the morph's shell tear lands, mirroring the server's MORPH_REVEAL_TICKS. */
	private static final long MORPH_REVEAL_SHAKE_TICK = 40L;
	private static int lastAnchorMask = Integer.MIN_VALUE;
	private static long lastActionBeat = -1L;

	private static void observeEncounter(Minecraft client, WorldInterfaceSnapshotS2C encounter, long now) {
		if (!encounter.encounterId().equals(trackedEncounterId)) {
			trackedEncounterId = encounter.encounterId();
			trackedBossId = encounter.bossId();
			lastObservedHealth = encounter.currentHealth();
			damageFlashUntil = Long.MIN_VALUE;
			audibleGatewayState = WorldInterfaceProtocol.GatewayState.DORMANT;
			ambientStage = null;
		} else {
			trackedBossId = encounter.bossId();
			if (Float.isFinite(lastObservedHealth)
					&& encounter.currentHealth() + 0.001F < lastObservedHealth) {
				damageFlashUntil = now + DAMAGE_FLASH_TICKS;
				// The smallest grade there is. Landing a hit should be felt, not announced.
				ScreenShakeController.impulse(ScreenShakeController.Grade.LIGHT);
			}
			lastObservedHealth = encounter.currentHealth();
		}
		tickGatewayAudio(client, encounter);
		tickAmbientAudio(client, encounter);
		tickFailureErosion(client, encounter);
	}

	/**
	 * Erosion is baked into chunk meshes, so a changed progress value is invisible until the
	 * sections are rebuilt. This keys off the progress itself rather than the resolution stage:
	 * gating on FAILURE_RESOLUTION meant the collapse-driven erosion during combat was computed
	 * and then never actually shown, since nothing asked for a rebuild until the fight was over.
	 *
	 * <p>Twelve buckets keep the rebuild down to a handful of requests spread across the whole
	 * encounter instead of one per progress change.</p>
	 */
	private static void tickFailureErosion(Minecraft client, WorldInterfaceSnapshotS2C encounter) {
		int bucket = encounter.failureProgress() > 0.0F
				? Math.clamp((int) Math.ceil(encounter.failureProgress() * 12.0F), 0, 12) : -1;
		if (bucket == failureErosionBucket) return;
		failureErosionBucket = bucket;
		rebuildErodedSections(client, encounter);
	}

	/**
	 * Rebuilds only the cylinder {@link #failureBlockReplacement} can actually touch.
	 *
	 * <p>{@code allChanged()} does not merely re-mesh: it tears down and recreates the section render
	 * dispatcher and every render region. Calling it once per bucket meant twelve full renderer
	 * reloads during the exact seconds the frame budget matters most. Marking the affected section
	 * range dirty produces the same picture, and the meshes are rebuilt progressively.</p>
	 */
	private static void rebuildErodedSections(Minecraft client, WorldInterfaceSnapshotS2C encounter) {
		if (client.levelRenderer == null || client.level == null) return;
		BlockPos center = encounter.center();
		client.levelRenderer.setSectionRangeDirty(
				SectionPos.blockToSectionCoord(center.getX() - EROSION_RADIUS_BLOCKS),
				client.level.getMinSectionY(),
				SectionPos.blockToSectionCoord(center.getZ() - EROSION_RADIUS_BLOCKS),
				SectionPos.blockToSectionCoord(center.getX() + EROSION_RADIUS_BLOCKS),
				client.level.getMaxSectionY(),
				SectionPos.blockToSectionCoord(center.getZ() + EROSION_RADIUS_BLOCKS));
	}

	private static void tickGatewayAudio(Minecraft client, WorldInterfaceSnapshotS2C encounter) {
		WorldInterfaceProtocol.GatewayState current = encounter.gatewayState();
		if (current == audibleGatewayState) return;
		audibleGatewayState = current;
		SoundEvent cue = switch (current) {
			case PURPLE -> ModSounds.WORLD_INTERFACE_GATEWAY_PURPLE;
			case GOLD -> ModSounds.WORLD_INTERFACE_GATEWAY_GOLD;
			case RED -> ModSounds.WORLD_INTERFACE_GATEWAY_RED;
			case DORMANT -> null;
		};
		if (cue == null) return;
		BlockPos origin = encounter.gatewayPositions().stream().min((left, right) -> Double.compare(
				client.player.distanceToSqr(left.getCenter()), client.player.distanceToSqr(right.getCenter())))
				.orElse(encounter.center());
		playBoundedLocal(client, cue, origin.getX() + 0.5D, origin.getY() + 1.0D,
				origin.getZ() + 0.5D, 0.62F, current == WorldInterfaceProtocol.GatewayState.RED ? 0.72F : 1.0F);
	}

	private static void tickAmbientAudio(Minecraft client, WorldInterfaceSnapshotS2C encounter) {
		WorldInterfaceProtocol.Stage currentStage = encounter.stage();
		SoundEvent cue = ambientCue(currentStage);
		if (cue == null) {
			if (ambientLoop != null || retiringAmbientLoop != null) stopAmbientLoops();
			ambientStage = currentStage;
			return;
		}
		float relativeVolume = switch (currentStage) {
			case PHASE_1 -> 0.42F;
			case PHASE_2 -> 0.46F;
			case PHASE_3 -> 0.50F;
			default -> 0.0F;
		};
		if (currentStage != ambientStage || ambientLoop == null || ambientLoop.isStopped()) {
			transitionAmbientLoop(client, cue, relativeVolume);
			ambientStage = currentStage;
		}
		if (retiringAmbientLoop != null && retiringAmbientLoop.isStopped()) retiringAmbientLoop = null;
	}

	private static SoundEvent ambientCue(WorldInterfaceProtocol.Stage stage) {
		return switch (stage) {
			case PHASE_1 -> ModSounds.WORLD_INTERFACE_AMBIENT_1;
			case PHASE_2 -> ModSounds.WORLD_INTERFACE_AMBIENT_2;
			case PHASE_3 -> ModSounds.WORLD_INTERFACE_AMBIENT_3;
			default -> null;
		};
	}

	/**
	 * The client-side twin of {@code AudioService.playBounded}, down to the mix trim.
	 *
	 * <p>The trim has to be applied here too, or the half of the encounter's library that is played
	 * locally - the gateway changes and the ambient bed - would sit above the half the server sends,
	 * which is the balance the authored relative volumes were chosen against.
	 */
	private static void playBoundedLocal(Minecraft client, SoundEvent cue, double x, double y, double z,
			float relativeVolume, float pitch) {
		float volume = (float) Math.clamp(RuntimeServices.config().meta().peakVolume()
				* Math.clamp(relativeVolume, 0.0F, 1.0F) * AudioService.ENCOUNTER_MIX_TRIM, 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		client.level.playLocalSound(x, y, z, cue, SoundSource.HOSTILE, volume,
				Math.clamp(pitch, 0.5F, 2.0F), false);
	}

	private static void transitionAmbientLoop(Minecraft client, SoundEvent cue, float relativeVolume) {
		if (retiringAmbientLoop != null) retiringAmbientLoop.forceStop();
		retiringAmbientLoop = ambientLoop;
		if (retiringAmbientLoop != null) retiringAmbientLoop.fadeOut();
		ambientLoop = new AmbientLoop(cue, relativeVolume);
		client.getSoundManager().play(ambientLoop);
	}

	private static void stopAmbientLoops() {
		if (ambientLoop != null) ambientLoop.forceStop();
		if (retiringAmbientLoop != null) retiringAmbientLoop.forceStop();
		ambientLoop = null;
		retiringAmbientLoop = null;
	}

	/**
	 * Whether the local player is inside an attack's warning window right now.
	 *
	 * <p>Asked by {@link WorldInterfaceHitStop}, which must never fire during one. Every telegraphed
	 * attack gives the player a window to dodge in, and freezing the picture through it converts a
	 * dodge into a death they had no way to avoid.
	 */
	public static boolean isLocalPlayerLocked() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return false;
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		if (!projection.actionActive(client.level.getGameTime())
				|| !projection.actionTargets(client.player.getUUID())) return false;
		BossActionS2C action = projection.action();
		int warning = WorldInterfaceProtocol.lockWarningTicks(action.action());
		if (warning <= 0) return false;
		long elapsed = client.level.getGameTime() - action.startTick();
		return elapsed >= 0L && elapsed < warning;
	}

	private static void renderOverlay(GuiGraphics graphics) {
		Minecraft client = Minecraft.getInstance();
		// Same rule as the encounter HUD: the failure wash and the attack telegraphs describe the
		// arena, and the server keeps talking to frozen roster members after they have left it.
		if (client.level == null || client.player == null || client.options.hideGui
				|| client.level.dimension() != Level.END) return;
		renderImpactFlash(graphics, client);
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		WorldInterfaceSnapshotS2C encounter = projection.encounter();
		if (encounter != null
				&& encounter.stage() == WorldInterfaceProtocol.Stage.FAILURE_RESOLUTION
				&& encounter.outcome() == WorldInterfaceProtocol.Outcome.FAILURE
				&& encounter.failureProgress() > 0.0F) {
			renderFailureErosion(graphics, encounter.failureProgress(), encounter.encounterId().getLeastSignificantBits());
		}
		if (!projection.actionActive(client.level.getGameTime())
				|| !projection.actionTargets(client.player.getUUID())) return;
		BossActionS2C action = projection.action();
		long elapsed = client.level.getGameTime() - action.startTick();
		if (action.action() == WorldInterfaceProtocol.BossAction.FORCED_EXPULSION) {
			renderForcedExpulsion(graphics, action, elapsed);
			return;
		}
		renderLockWarning(graphics, action, elapsed);
	}

	/** How far in from each edge the lock vignette reaches, as a fraction of the screen. */
	private static final float LOCK_EDGE_DEPTH = 0.20F;
	/** Where the warning colour hands over to the committed one; mirrors the post chain. */
	private static final float LOCK_COMMIT_FRACTION = 0.66F;

	/**
	 * The HUD half of the lock treatment: a vignette that darkens the border and leaves the middle
	 * alone.
	 *
	 * <p>Deliberately duplicated in two places. The post chain does this better - it is a real radial
	 * mask rather than four gradient strips - but a hand-written {@code .fsh} only fails at runtime,
	 * on whatever driver the player happens to have, and a lock with no warning at all is a death
	 * they could not see coming. This costs a few dozen {@code fill} calls and cannot fail to
	 * compile, so it carries the guarantee and the shader is the upgrade on top.
	 *
	 * <p>The central 60% of the screen is never touched, by construction: {@code depth} is a fifth of
	 * each axis and the alpha is already zero by the time the innermost band is reached.
	 */
	private static void renderLockEdge(GuiGraphics graphics, float progress) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		// Violet while there is still a decision to make, red once the attack has committed.
		int colour = progress >= LOCK_COMMIT_FRACTION ? 0xE02838 : LOCK_ACCENT;
		float strength = 0.35F + progress * 0.65F;
		int horizontal = Math.max(1, Math.round(width * LOCK_EDGE_DEPTH));
		int vertical = Math.max(1, Math.round(height * LOCK_EDGE_DEPTH));

		for (int step = 0; step < vertical; step++) {
			// Quadratic falloff from the edge inward, so the band has no visible inner boundary.
			float fade = 1.0F - step / (float) vertical;
			int alpha = Math.round(0xB0 * fade * fade * strength);
			if (alpha <= 1) continue;
			int argb = (alpha << 24) | colour;
			graphics.fill(0, step, width, step + 1, argb);
			graphics.fill(0, height - step - 1, width, height - step, argb);
		}
		for (int step = 0; step < horizontal; step++) {
			float fade = 1.0F - step / (float) horizontal;
			int alpha = Math.round(0xB0 * fade * fade * strength);
			if (alpha <= 1) continue;
			int argb = (alpha << 24) | colour;
			graphics.fill(step, 0, step + 1, height, argb);
			graphics.fill(width - step - 1, 0, width - step, height, argb);
		}
	}

	/** Ticks a hit flash lasts: white when the player lands one, red when they take one. */
	private static final int HIT_FLASH_TICKS = 4;
	private static final int HURT_FLASH_TICKS = 6;
	private static final int HIT_FLASH_PEAK_ALPHA = 0x30;
	private static final int HURT_FLASH_PEAK_ALPHA = 0x48;
	/**
	 * Rising edge of the vanilla hurt timer, tracked on the client tick rather than in the render
	 * pass so a hit produces one flash and one shake regardless of frame rate.
	 */
	private static int lastHurtTime;
	private static float lastPlayerHealth = Float.NaN;
	private static long hurtFlashUntil = Long.MIN_VALUE;
	/**
	 * Minimum ticks between two hurt flashes.
	 *
	 * <p>Sustained damage must not re-arm the flash faster than it fades. The laser burns every five
	 * ticks and the flash lasts six, so without a floor here the red never actually goes out - it is
	 * pulled back to full strength every five ticks for the whole two-second sweep, which is a 4 Hz
	 * full-screen strobe rather than a series of hits. Fifteen ticks means continuous damage reads
	 * as an occasional pulse, and the flash always has time to decay to nothing in between.
	 */
	private static final long HURT_FLASH_MIN_GAP_TICKS = 15L;
	private static long lastHurtFlashTick = Long.MIN_VALUE;

	/**
	 * A brief wash over the whole screen on impact: white outward, red inward.
	 *
	 * <p>The cheapest possible confirmation, and the encounter badly needed one. The boss is a
	 * near-black silhouette against a near-black sky, so a hit that landed and a hit that missed
	 * looked identical - which is most of why the fight read as unresponsive rather than as hard.
	 *
	 * <p>{@code graphics.fill} rather than particles: the client fidelity contract forbids this
	 * class from spawning any, and a full-screen quad is the right primitive anyway.
	 */
	private static void renderImpactFlash(GuiGraphics graphics, Minecraft client) {
		if (!RuntimeServices.config().presentation().impactFlashEnabled()) return;
		long now = client.level.getGameTime();
		float hurt = decay(hurtFlashUntil, now, HURT_FLASH_TICKS);
		if (hurt > 0.0F) {
			int alpha = Math.round(HURT_FLASH_PEAK_ALPHA * hurt);
			if (alpha > 0) fillScreen(graphics, (alpha << 24) | 0xFF2030);
		}
		// Landing one: reuses the damage flash the renderer already tracks for the boss body.
		float hit = decay(damageFlashUntil, now, HIT_FLASH_TICKS);
		if (hit > 0.0F) {
			int alpha = Math.round(HIT_FLASH_PEAK_ALPHA * hit);
			if (alpha > 0) fillScreen(graphics, (alpha << 24) | 0xFFFFFF);
		}
	}

	/**
	 * Exponential falloff over the remaining ticks, so a flash fades rather than switching off.
	 *
	 * <p>Takes the deadline and the clock rather than their difference, and compares before it
	 * subtracts. Both deadlines are disarmed by parking them at {@link Long#MIN_VALUE}, and
	 * {@code Long.MIN_VALUE - now} does not evaluate to "long ago" - it overflows to a large
	 * positive number, which read as a flash that had only just started and pinned both overlays at
	 * full strength forever. The result was a permanent red wash over the whole screen from the
	 * moment the world loaded.
	 */
	private static float decay(long until, long now, int total) {
		if (until == Long.MIN_VALUE || now >= until || total <= 0) return 0.0F;
		long remaining = until - now;
		float progress = 1.0F - Math.min(1.0F, remaining / (float) total);
		return (float) Math.exp(-progress * 3.0D);
	}

	private static void fillScreen(GuiGraphics graphics, int argb) {
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), argb);
	}

	/**
	 * The screen half of a lock, for every action that has one.
	 *
	 * <p>These attacks are all dodgeable now - the laser trails its target, the lance falls on a
	 * fixed mark, the grab and the purge take a beat to close - and none of that is playable if
	 * being singled out is only legible from a particle on the ground behind you.</p>
	 *
	 * <p>Deliberately small. This started as a closing full-screen frame, which read as an
	 * interface failure rather than as a warning and covered the arena at the exact moment the
	 * player needs to see it. What is left is the smallest thing that answers the two questions
	 * being locked actually raises: <em>am I the one</em>, and <em>how long have I got</em>. Both
	 * live near the crosshair, where the player is already looking, and both run on the server's
	 * own warning clock so the screen never promises time the encounter will not give.</p>
	 */
	private static void renderLockWarning(GuiGraphics graphics, BossActionS2C action, long elapsed) {
		int warning = WorldInterfaceProtocol.lockWarningTicks(action.action());
		if (warning <= 0 || elapsed < 0L || elapsed >= warning) return;
		float progress = Math.clamp(elapsed / (float) warning, 0.0F, 1.0F);
		// Only when the shader chain is not already carrying the edge. Both drawing at once is not
		// a warning, it is a wall around the play area - the HUD band is the fallback for a driver
		// that will not compile the custom shader, not a second copy of it.
		if (!WorldInterfacePostEffectController.isLockChainActive(Minecraft.getInstance())) {
			renderLockEdge(graphics, progress);
		}
		int centerX = graphics.guiWidth() / 2;
		int centerY = graphics.guiHeight() / 2;
		// Fades up over the first few ticks instead of snapping on, so the lock arrives rather
		// than flashing.
		int alpha = Math.round(Mth.lerp(Math.min(1.0F, elapsed / 6.0F), 0.0F,
				Mth.lerp(progress, 130.0F, 220.0F)));
		if (alpha <= 2) return;
		int accent = (alpha << 24) | LOCK_ACCENT;

		// Four short ticks converging on the crosshair. No frame, no ring, nothing across the
		// middle: the closing gap alone carries how much of the window is left.
		int reach = Math.round(Mth.lerp(progress, 34.0F, 13.0F));
		int arm = 6;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sy = -1; sy <= 1; sy += 2) {
				int cornerX = centerX + sx * reach;
				int cornerY = centerY + sy * reach;
				graphics.fill(Math.min(cornerX, cornerX - sx * arm), cornerY,
						Math.max(cornerX, cornerX - sx * arm), cornerY + 1, accent);
				graphics.fill(cornerX, Math.min(cornerY, cornerY - sy * arm),
						cornerX + 1, Math.max(cornerY, cornerY - sy * arm), accent);
			}
		}

		// A thin bar under the reticle, and the attack's name under that. The bar is the answer to
		// "how long"; the name is only there so the first lock of a given kind teaches what it is.
		int barWidth = 54;
		int barLeft = centerX - barWidth / 2;
		int barTop = centerY + reach + 10;
		// The bar carries the progress and nothing else - no marker, no threshold drawn on it. The
		// moment the lock commits is said in words underneath instead, because a line on a bar has
		// to be learned before it means anything and the words do not.
		graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + 1, (alpha / 3) << 24);
		graphics.fill(barLeft, barTop, barLeft + Math.round(barWidth * (1.0F - progress)), barTop + 1,
				accent);
		// Only a real targeting lock ever claims to have locked. The confiscations telegraph on the
		// same clock but there is nothing aimed at anyone, and their own labels already say what is
		// about to be taken - which is the more useful sentence when moving is not the answer.
		boolean locked = WorldInterfaceProtocol.isTargetingLock(action.action())
				&& progress >= WorldInterfaceProtocol.LOCK_COMMIT_FRACTION;
		graphics.drawCenteredString(Minecraft.getInstance().font,
				Component.translatable(locked
						? "hud.thefourthfrequency.world_interface.lock.committed"
						: lockLabelKey(action.action())),
				centerX, barTop + 5, (alpha << 24) | (locked ? LOCK_ACCENT : LOCK_TEXT));
	}

	private static String lockLabelKey(WorldInterfaceProtocol.BossAction action) {
		return "hud.thefourthfrequency.world_interface.lock." + switch (action) {
			case LASER_SWEEP -> "laser";
			case SKY_LANCE -> "sky_lance";
			case GRAB_THROW -> "grab_throw";
			case WEAPON_CHARGE -> "weapon";
			case HOTBAR_PURGE -> "hotbar";
			case TENDRIL_LASH -> "tendril";
			default -> "generic";
		};
	}

	private static void renderForcedExpulsion(GuiGraphics graphics, BossActionS2C action, long elapsed) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		double progress = Math.clamp(elapsed / Math.max(1.0D, action.duration()), 0.0D, 1.0D);
		// Shares the screen with the expulsion post-effect chain, so the veil only has to carry the
		// final blackout rather than the whole colour treatment.
		int alpha = 22 + (int) Math.round(progress * 120.0D);
		graphics.fill(0, 0, width, height, (alpha << 24) | 0x00150008);
		int bands = 3 + (int) Math.round(progress * 14.0D);
		for (int index = 0; index < bands; index++) {
			long mixed = action.seed() ^ index * 0x9E3779B97F4A7C15L ^ elapsed / 3L;
			int y = Math.floorMod(Long.hashCode(mixed), Math.max(1, height));
			int bandHeight = 2 + Math.floorMod(Long.hashCode(mixed >>> 23), 9);
			graphics.fill(0, y, width, Math.min(height, y + bandHeight), 0xA04B0018);
		}
		if (elapsed >= Math.min(40L, action.duration() / 3L)) {
			graphics.drawCenteredString(Minecraft.getInstance().font,
					Component.translatable("hud.thefourthfrequency.world_interface.action.forced_expulsion"),
					width / 2, height / 2 - 4, 0xFFFF274D);
		}
	}

	private static void renderFailureErosion(GuiGraphics graphics, float progress, long seed) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		float amount = Math.clamp(progress, 0.0F, 1.0F);
		int veilAlpha = 12 + Math.round(amount * 166.0F);
		graphics.fill(0, 0, width, height, (veilAlpha << 24) | 0x00090010);
		int missingAlpha = Math.round(amount * 84.0F);
		graphics.fill(0, 0, width, height, (missingAlpha << 24) | 0x00290035);

		int tears = Math.clamp((int) Math.ceil(amount * 30.0F), 1, 30);
		for (int index = 0; index < tears; index++) {
			long mixed = seed ^ index * 0xA24BAED4963EE407L;
			int x = Math.floorMod(Long.hashCode(mixed), Math.max(1, width));
			int y = Math.floorMod(Long.hashCode(mixed >>> 21), Math.max(1, height));
			int tearWidth = 8 + Math.floorMod(Long.hashCode(mixed >>> 37),
					Math.max(9, Math.round(18.0F + amount * 72.0F)));
			int tearHeight = 1 + Math.floorMod(Long.hashCode(mixed >>> 49),
					Math.max(2, Math.round(2.0F + amount * 9.0F)));
			int alpha = 30 + Math.round(amount * 120.0F);
			graphics.fill(x, y, Math.min(width, x + tearWidth), Math.min(height, y + tearHeight),
					(alpha << 24) | (index % 2 == 0 ? 0x005B0866 : 0x00190024));
		}

		int bands = Math.clamp((int) Math.ceil(amount * 26.0F), 1, 26);
		for (int index = 0; index < bands; index++) {
			long mixed = seed ^ index * 0xD6E8FEB86659FD93L;
			int y = Math.floorMod(Long.hashCode(mixed), Math.max(1, height));
			int bandHeight = 1 + Math.floorMod(Long.hashCode(mixed >>> 19),
					Math.max(2, (int) (amount * 18.0F) + 2));
			int alpha = 20 + (int) (amount * 125.0F);
			graphics.fill(0, y, width, Math.min(height, y + bandHeight),
					(alpha << 24) | (index % 2 == 0 ? 0x0016001D : 0x003A073E));
		}
	}

	private static final class AmbientLoop extends AbstractTickableSoundInstance {
		/**
		 * Slow detune and gain sway, on two periods that are not multiples of each other.
		 *
		 * <p>A phase can run for minutes, and the bed under it is a fixed recording however long
		 * that recording is. Drifting the playback the way a transmitter drifts pushes the point
		 * where a listener can pick out the wrap far past the length of the fight.</p>
		 */
		private static final float PITCH_DRIFT_TICKS = 743.0F;
		private static final float SWAY_TICKS = 509.0F;
		private static final float PITCH_DRIFT_AMOUNT = 0.008F;
		private static final float SWAY_AMOUNT = 0.06F;

		private final float relativeVolume;
		private final float phase;
		private int age;
		private int fadeInAge;
		private int fadeOutAge = -1;

		private AmbientLoop(SoundEvent cue, float relativeVolume) {
			// HOSTILE, matching the attack cues this bed plays under. On AMBIENT it shared a
			// slider with cave and weather ambience - the one category a horror-mod player is
			// most likely to have already turned off - and turning it off took the entire
			// atmosphere of the finale with it while leaving the attacks at full volume.
			super(cue, SoundSource.HOSTILE, RandomSource.create());
			this.relativeVolume = Math.clamp(relativeVolume, 0.0F, 1.0F);
			this.phase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
			this.volume = 0.0F;
			this.pitch = 1.0F;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		private void fadeOut() {
			if (fadeOutAge < 0) fadeOutAge = 0;
		}

		private void forceStop() {
			stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			age++;
			float envelope;
			if (fadeOutAge >= 0) {
				fadeOutAge++;
				envelope = Math.clamp(1.0F - fadeOutAge / (float) AMBIENT_FADE_OUT_TICKS, 0.0F, 1.0F);
				if (fadeOutAge >= AMBIENT_FADE_OUT_TICKS) {
					stop();
					return;
				}
			} else {
				fadeInAge++;
				envelope = Math.clamp(fadeInAge / (float) AMBIENT_FADE_IN_TICKS, 0.0F, 1.0F);
			}
			pitch = 1.0F + PITCH_DRIFT_AMOUNT
					* (float) Math.sin(phase + age * (Math.PI * 2.0D) / PITCH_DRIFT_TICKS);
			float sway = 1.0F + SWAY_AMOUNT
					* (float) Math.sin(phase * 1.7D + age * (Math.PI * 2.0D) / SWAY_TICKS);
			// Trimmed with the rest of the encounter, and it is the cue that most needed it: this is
			// the only one of them that never stops. Attenuation.NONE and relative playback mean it
			// arrives at the same level wherever the player stands, for the whole length of a phase,
			// which is precisely the shape of thing that buries a track rather than punching through it.
			volume = (float) Math.clamp(RuntimeServices.config().meta().peakVolume()
					* relativeVolume * envelope * sway * AudioService.ENCOUNTER_MIX_TRIM
					* warningDuck(), 0.0D, 1.0D);
		}

		private float duck = 1.0F;
		private float warningDuck() {
			Minecraft client = Minecraft.getInstance();
			var projection = WorldInterfaceClientState.snapshot();
			var action = projection.action();
			boolean warning = client.level != null && action != null
					&& projection.actionActive(client.level.getGameTime())
					&& client.level.getGameTime() - action.startTick() < WorldInterfaceProtocol.lockWarningTicks(action.action());
			duck += ((warning ? .52F : 1.0F) - duck) * (warning ? .22F : .06F);
			return duck;
		}
	}
}
