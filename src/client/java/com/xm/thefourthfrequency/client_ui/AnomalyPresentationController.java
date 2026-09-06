package com.xm.thefourthfrequency.client_ui;

import com.mojang.authlib.GameProfile;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.correction.ViewpointOrientationPolicy;
import com.xm.thefourthfrequency.meta_api.MetaController;
import com.xm.thefourthfrequency.networking.AnomalyCompleteC2S;
import com.xm.thefourthfrequency.networking.AnomalyPhaseS2C;
import com.xm.thefourthfrequency.networking.AnomalyStartS2C;
import com.xm.thefourthfrequency.terminal.AnomalyCompletionStatus;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Single owner for camera, input, audio, overlay, distance, entities, light, item and trace leases. */
public final class AnomalyPresentationController {
	private static final Identifier HANDS = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID,
			"textures/gui/anomaly/peripheral_hand.png");
	private static final int HAND_TEXTURE_WIDTH = 512;
	private static final int HAND_TEXTURE_HEIGHT = 256;
	private static final float PERIPHERAL_HAND_ENTER_FRACTION = 0.42F;
	/** How much further in the palms travel after the slide, as a fraction of their own width. */
	private static final float PERIPHERAL_HAND_CREEP_FRACTION = 0.09F;
	// The impact palette is the mod's own tape palette, not a set of one-off neon values: an
	// off-white that is never quite white, and the same red and cyan the loading-screen
	// corruption bleeds. A burst assembled from colours nothing else in the mod uses reads as a
	// different piece of software having a rendering fault.
	private static final int GLITCH_BLEACH_COLOR = 0x00E6E2D8;
	private static final int GLITCH_STREAK_COLOR = 0x00C8C4BC;
	private static final int GLITCH_CHROMA_RED = 0x00FF2A2A;
	private static final int GLITCH_CHROMA_CYAN = 0x0022E0FF;
	/**
	 * The four analog-signal chains, weakest first, indexed by {@link GlitchImpactTimeline#signalStep}.
	 *
	 * <p>Four rather than one that ramps, because a post chain's uniforms are fixed when it loads.
	 * The motion inside each step is not fixed - the shader reads {@code GameTime} out of the
	 * Globals block - so the steps only quantise how hard the picture is bent, never how alive it is.
	 */
	private static final Identifier[] SIGNAL_CHAINS = {
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_1"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_2"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_3"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_4")
	};
	private static final int LOCAL_RULE_FRAGMENT_LIMIT = 24;
	private static final double LOCAL_RULE_MIN_SPACING_SQR = 9.0D;
	private static final int HISTORY_TICKS = 60;
	private static final int ACTION_ECHO_ENTITY_ID = -0x4543484F;
	private static final int SECOND_PERSON_CAMERA_ID = -0x53454350;
	private static final int SECOND_PERSON_BODY_ID = -0x53454342;
	private static final int[][] INVENTORY_VISUAL_ROWS = {
			{9, 18}, {18, 27}, {27, 36}, {0, 9}
	};
	private static final List<EquipmentSlot> PLAYER_EQUIPMENT_SLOTS = List.of(
			EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
			EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
	private static final Deque<PlayerFrame> HISTORY = new ArrayDeque<>();
	private static final Set<Integer> MISREAD_SLOTS = new HashSet<>();
	// visualReplacement() below is reached from RenderSectionRegionAnomalyMixin, which runs on
	// chunk-build worker threads, while addPurpleTraces()/restore() mutate this set from the main
	// client thread. A plain HashSet under that access pattern can corrupt its internal table
	// mid-resize and hang the worker thread; it must stay a concurrent set.
	private static final Set<TracePosition> PURPLE_TRACES = ConcurrentHashMap.newKeySet();
	private static final Set<BlockPos> CURRENT_RULE_FRAGMENTS = new HashSet<>();
	private static final Set<BlockPos> RENDERED_TRACE_POSITIONS = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> WATCHERS_HEARD = ConcurrentHashMap.newKeySet();

	private static UUID instanceId;
	private static String anomalyId = "none";
	private static long seed;
	private static int totalTicks;
	private static int remainingTicks;
	private static int lastPhaseSequence;
	private static boolean phaseBlackout;
	private static boolean nearBlindness;
	private static BlockPos fracturePos;
	private static ClientLevel activeLevel;
	private static ArmorStand cameraAnchor;
	private static RemotePlayer secondPersonBody;
	private static Entity previousCameraEntity;
	private static CameraType previousCameraType;
	private static float lockedPlayerYaw;
	private static float lockedPlayerPitch;
	private static double fixedCameraX;
	private static double fixedCameraY;
	private static double fixedCameraZ;
	private static RemotePlayer actionEcho;
	private static List<PlayerFrame> echoFrames = List.of();
	private static BlockPos echoCrack;
	private static int soundDelay;
	private static int phantomBurstRemaining;
	private static boolean phantomMiningBurst;
	private static double phantomSoundX;
	private static double phantomSoundY;
	private static double phantomSoundZ;
	private static double phantomWalkX;
	private static double phantomWalkZ;
	private static double phantomApproachDistance;
	private static int phantomDigStartTick = -1;
	private static BlockState phantomSoundMaterial;
	private static int dedicatedSoundCount;
	private static int ambientSoundCount;
	private static int fractureStage = -1;
	private static int glitchImpactTicks;
	private static boolean glitchTriggered;
	private static boolean attackWasDown;
	private static String currentPhase = "idle";
	private static boolean simulatedWindow;
	private static boolean simulatedNotepad;
	private static boolean initialized;
	private static boolean restoring;

	private AnomalyPresentationController() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(AnomalyStartS2C.TYPE, (payload, context) ->
				context.client().execute(() -> start(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(AnomalyPhaseS2C.TYPE, (payload, context) ->
				context.client().execute(() -> phase(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(AnomalyPresentationController::tick);
		HudRenderCallback.EVENT.register((graphics, delta) -> renderOverlay(graphics));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			restore(client, false, AnomalyCompletionStatus.INTERRUPTED);
			WATCHERS_HEARD.clear();
			// These previously survived a disconnect: rejoining a different server (or the same
			// one) kept stale trace coordinates around, both leaking memory across sessions and
			// letting missing-texture proxies from the old session bleed into the new one.
			PURPLE_TRACES.clear();
			RENDERED_TRACE_POSITIONS.clear();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			restore(client, false, AnomalyCompletionStatus.INTERRUPTED);
			PURPLE_TRACES.clear();
			RENDERED_TRACE_POSITIONS.clear();
			HISTORY.clear();
			WATCHERS_HEARD.clear();
		});
	}

	private static void start(Minecraft client, AnomalyStartS2C payload) {
		if (client.player == null || client.level == null) return;
		if (instanceId != null) restore(client, true, AnomalyCompletionStatus.INTERRUPTED);
		instanceId = payload.instanceId();
		anomalyId = payload.anomalyId();
		seed = payload.seed();
		totalTicks = Math.max(1, payload.expectedDurationTicks());
		remainingTicks = totalTicks;
		activeLevel = client.level;
		lastPhaseSequence = 0;
		currentPhase = "running";
		phaseBlackout = false;
		nearBlindness = false;
		soundDelay = 0;
		phantomBurstRemaining = 0;
		phantomSoundMaterial = null;
		phantomApproachDistance = 0.0D;
		phantomMiningBurst = false;
		phantomDigStartTick = -1;
		dedicatedSoundCount = 0;
		ambientSoundCount = 0;
		fractureStage = -1;
		glitchImpactTicks = 0;
		glitchTriggered = false;
		attackWasDown = false;
		playTriggerCue(client, payload);

		switch (anomalyId) {
			// The anchor is learned here and nothing is done to it yet. The crack used to open on this
			// tick, on the reasoning that a player who turns around immediately should find it
			// already there rather than watch it appear - which was right while the digging also
			// started here. It is exactly wrong now that the digging is deliberately held back:
			// a crack that is already in the wall while the only thing audible is somebody walking
			// says the hole came first and the person second, and it hands the player the answer
			// before the anomaly has asked anything. See {@link #tickPhantomEcho} - the first blow
			// opens it, so what the player turns around to find is a wall that is still intact.
			case "phantom_echo" -> {
				if (!payload.hasAnchor()) { fail(client); return; }
				fracturePos = BlockPos.of(payload.anchorPosition());
			}
			case "organ_misread" -> selectMisreadItems(client, seed);
			case "peripheral_residue" -> { }
			case "viewpoint_separation" -> beginFixedCamera(client);
			case "action_echo" -> beginActionEcho(client);
			// Two failures of the same solver in one volume, and they end differently on purpose.
			// The lighting is released section by section by any block update, so the player repairs
			// it themselves by doing the first thing they will try; the missing textures stay for the
			// rest of the session. What comes back and what does not is the anomaly.
			//
			// No cue, by omission rather than by exception: CUED_ANOMALIES is a whitelist of four.
			// A sound would be the wrong instrument twice over - this is not somewhere to be pointed
			// at, it is already everywhere the player can see, and announcing it turns a light that
			// stopped working into a thing that was done to them.
			case "local_rule_collapse" -> {
				addPurpleTraces(client, seed);
				// A full one-shot rebuild also works when the anomaly was requested while a GUI covered
				// the world. It runs before the fault is published rather than after: a reload that
				// happens to route through setSectionDirty would otherwise release the very region it
				// had just been handed, and this ordering is correct whether it routes there or not.
				client.levelRenderer.allChanged();
				LuminanceFaultClient.begin(client);
			}
			case "red_horizon" -> {
				// The pursuit's own warning, deliberately. This is not something to be pointed at -
				// it is the whole sky - so the directional cave cue does nothing for it, and it is
				// now excluded from that set. What it needs instead is the sound the player has
				// already learned means the mod is about to do something to them; borrowing it here
				// puts a sky going red in the same category, before they look up and find out.
				playSignalCue(client, ModSounds.SIGNAL_ALERT, 0.85F);
				dedicatedSoundCount++;
			}
			case "channel_override" -> client.setScreen(new ChannelOverrideScreen());
			case "window_pulse" -> simulatedWindow = !MetaController.startWindowPulse();
			case "desktop_presence" -> simulatedNotepad = !MetaController.startDesktopPresence();
			default -> { }
		}
	}

	private static void phase(AnomalyPhaseS2C payload) {
		if (instanceId == null || !instanceId.equals(payload.instanceId())
				|| payload.sequence() <= lastPhaseSequence) return;
		lastPhaseSequence = payload.sequence();
		// The server has ended this instance and is telling us to stop. Nothing else can: the client
		// otherwise runs its own copy of the clock to the end, and the line below deliberately takes
		// the larger of the two remaining counts, so a shortened one cannot arrive this way. Reported
		// as false because the instance the report would name is already gone.
		if (AnomalyRuntimeService.INTERRUPTED_PHASE.equals(payload.phase())) {
			restore(Minecraft.getInstance(), false, AnomalyCompletionStatus.INTERRUPTED);
			return;
		}
		currentPhase = payload.phase();
		remainingTicks = Math.max(remainingTicks, payload.remainingTicks());
		phaseBlackout = payload.blackout();
	}

	private static void tick(Minecraft client) {
		recordHistory(client);
		if (instanceId == null) return;
		if (client.level != activeLevel || client.player == null || !client.player.isAlive()) {
			restore(client, true, AnomalyCompletionStatus.INTERRUPTED);
			return;
		}
		int elapsed = totalTicks - remainingTicks;
		switch (anomalyId) {
			case "phantom_echo" -> {
				tickPhantomEcho(client, elapsed);
				tickSurfaceFracture(client);
			}
			case "peripheral_residue" -> {
				int impactAt = Math.max(2, Math.round(totalTicks * 0.72F));
				if (!glitchTriggered && elapsed >= impactAt) triggerGlitchImpact(client);
			}
			case "action_echo" -> {
				try { tickActionEcho(client, elapsed); }
				catch (RuntimeException failure) {
					TheFourthFrequency.LOGGER.warn("Action echo presentation failed safely", failure);
					fail(client);
					return;
				}
			}
			case "viewpoint_separation" -> {
				maintainFixedCamera(client);
				syncSecondPersonBody(client);
			}
			case "experience_gap", "channel_override" -> releaseAllInput(client);
			default -> { }
		}
		if (glitchImpactTicks > 0) {
			tickGlitchAudio(client, GlitchImpactTimeline.IMPACT_TICKS - glitchImpactTicks);
			glitchImpactTicks--;
		}
		if (anomalyId.equals("channel_override") && !(client.screen instanceof ChannelOverrideScreen))
			client.setScreen(new ChannelOverrideScreen());
		remainingTicks--;
		if (fracturePos != null && fractureStage >= 0 && phantomDigStartTick >= 0) {
			// Spread across the digging act, not the whole instance. It was proportional to the
			// instance while the crack existed for all of it; now the wall is intact until somebody
			// starts hitting it, and a ramp still measured from tick zero would open the crack at
			// stage four - already half broken by the first blow that made it.
			int elapsedTicks = Math.max(0, totalTicks - Math.max(0, remainingTicks));
			int span = Math.max(1, totalTicks - phantomDigStartTick);
			int stage = Math.clamp((elapsedTicks - phantomDigStartTick) * 9 / span, 0, 9);
			fractureStage = stage;
			activeLevel.destroyBlockProgress(fractureBreakerId(), fracturePos, stage);
		}
		if (remainingTicks <= 0) restore(client, true, AnomalyCompletionStatus.COMPLETED);
	}

	/**
	 * The strike on the fracture, and the reason it only lands once.
	 *
	 * <p>{@code glitchTriggered} was previously only read by peripheral_residue. It matters here now
	 * that the fracture lives inside a fourteen-to-twenty second anomaly rather than a five second
	 * one: a player who works out that hitting it does something has time to hit it eight more times,
	 * and a burst that answers on demand is a button, not a thing that happened to them.
	 *
	 * <p>{@code fractureStage} rather than {@code fracturePos}, because those two now answer
	 * different questions. The anchor is known from the first tick and the crack is not in the wall
	 * until the digging opens it, so keying off the position would let a player who happens to swing
	 * at that block during the approach collect the payoff of a fracture that does not exist yet -
	 * and spend it, since it only lands once.
	 */
	private static void tickSurfaceFracture(Minecraft client) {
		boolean attackDown = client.options.keyAttack.isDown();
		if (attackDown && !attackWasDown && !glitchTriggered && fracturePos != null && fractureStage >= 0
				&& client.hitResult instanceof BlockHitResult block && block.getBlockPos().equals(fracturePos))
			triggerGlitchImpact(client);
		attackWasDown = attackDown;
	}

	/**
	 * Puts the burst on the frame rather than only over it.
	 *
	 * <p>The overlay draws what is <em>in front of</em> the picture - the hands being shredded, the
	 * dark the carrier drops into, the line it closes on. This is the picture itself coming apart:
	 * rows wobbling, colour separating outward, the tube blooming, a mistracked bar climbing through
	 * it. One is meaningless without the other, which is why they share
	 * {@link GlitchImpactTimeline}'s beats down to the tick.
	 *
	 * <p>Through {@link ScreenFilterDriver} rather than the level's own post-effect slot, so the HUD
	 * and this overlay are <em>inside</em> the damage instead of floating on top of it. A burst that
	 * leaves the hotbar pristine is a filter over the world; a burst that takes the hotbar with it is
	 * the screen failing, and the second one is the whole idea.
	 *
	 * <p>Asked from the render path, once per frame, because the driver's requests last exactly one
	 * frame. There is no path out of an anomaly - completed, interrupted, failed, disconnected -
	 * that can leave this switched on.
	 */
	private static void requestSignalFilter() {
		if (glitchImpactTicks <= 0) return;
		int step = GlitchImpactTimeline.signalStep(
				GlitchImpactTimeline.IMPACT_TICKS - glitchImpactTicks);
		if (step <= 0) return;
		ScreenFilterDriver.request(ScreenFilterDriver.Owner.ANOMALY,
				SIGNAL_CHAINS[Math.min(step, SIGNAL_CHAINS.length) - 1]);
	}

	private static void triggerGlitchImpact(Minecraft client) {
		glitchTriggered = true;
		glitchImpactTicks = GlitchImpactTimeline.IMPACT_TICKS;
		if (client.player != null && client.level != null) {
			client.level.playLocalSound(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
					ModSounds.WINDOW_GLITCH, SoundSource.MASTER, 1.35F, 0.58F, false);
			// The captioned cue redirects to a beacon deactivate, which on its own is a soft
			// electronic sigh under a screen that is visibly being torn. The uncaptioned layer
			// underneath it supplies the break; it stays subtitle-less for the reason every
			// LAYER_* event exists - a captioned player must not be told the thing they just
			// heard was ordinary stone.
			client.level.playLocalSound(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
					ModSounds.LAYER_DEEPSLATE_BREAK, SoundSource.MASTER, 0.95F, 0.52F, false);
			dedicatedSoundCount += 2;
		}
	}

	/**
	 * Keeps the sound breaking on the same frames as the picture.
	 *
	 * <p>A burst with four visual beats and one audio hit desynchronises immediately: the ear
	 * hears one event finish while the eye is still watching three more happen. The second strike
	 * and the closing are the two beats that need a voice of their own.</p>
	 */
	private static void tickGlitchAudio(Minecraft client, int glitchTick) {
		if (client.player == null || client.level == null) return;
		if (glitchTick == GlitchImpactTimeline.SECOND_HIT_TICK) {
			client.level.playLocalSound(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
					ModSounds.LAYER_DEEPSLATE_BREAK, SoundSource.MASTER, 0.62F, 0.42F, false);
			dedicatedSoundCount++;
		}
	}

	private static void recordHistory(Minecraft client) {
		if (client.player == null || client.level == null) { HISTORY.clear(); return; }
		List<ItemStack> equipment = new ArrayList<>(PLAYER_EQUIPMENT_SLOTS.size());
		for (EquipmentSlot slot : PLAYER_EQUIPMENT_SLOTS)
			equipment.add(client.player.getItemBySlot(slot).copy());
		BlockPos digging = client.options.keyAttack.isDown() && client.hitResult instanceof BlockHitResult block
				? block.getBlockPos().immutable() : null;
		HISTORY.addLast(new PlayerFrame(client.player.getX(), client.player.getY(), client.player.getZ(),
				client.player.getYRot(), client.player.getXRot(), client.player.getPose(),
				client.player.walkAnimation.speed(), client.player.swinging, client.player.swingTime,
				client.player.oAttackAnim, client.player.attackAnim, client.player.swingingArm,
				client.player.isSprinting(), client.player.isShiftKeyDown(), client.player.isSwimming(),
				client.player.isUsingItem() ? client.player.getUsedItemHand() : null, equipment, digging));
		while (HISTORY.size() > HISTORY_TICKS) HISTORY.removeFirst();
	}

	private static void beginActionEcho(Minecraft client) {
		if (HISTORY.size() < HISTORY_TICKS) { fail(client); return; }
		echoFrames = List.copyOf(HISTORY);
		PlayerFrame first = echoFrames.get(0);
		client.level.playLocalSound(first.x, first.y, first.z, ModSounds.ANOMALY_ECHO,
				SoundSource.AMBIENT, 0.82F, 0.78F, false);
	}

	private static void tickActionEcho(Minecraft client, int elapsed) {
		if (elapsed < 20 || echoFrames.isEmpty()) return;
		int frameIndex = elapsed - 20;
		if (frameIndex >= echoFrames.size()) return;
		if (actionEcho == null) {
			GameProfile copy = new GameProfile(UUID.randomUUID(), "echo");
			actionEcho = new ActionEchoPlayer(client.level, copy, client.player.getSkin());
			actionEcho.setId(ACTION_ECHO_ENTITY_ID);
			actionEcho.setInvulnerable(true);
			actionEcho.setCustomNameVisible(false);
			client.level.addEntity(actionEcho);
		}
		PlayerFrame frame = echoFrames.get(frameIndex);
		actionEcho.snapTo(frame.x, frame.y, frame.z, frame.yaw, frame.pitch);
		PlayerFrame previous = frameIndex > 0 ? echoFrames.get(frameIndex - 1) : frame;
		actionEcho.xo = previous.x; actionEcho.yo = previous.y; actionEcho.zo = previous.z;
		actionEcho.xOld = previous.x; actionEcho.yOld = previous.y; actionEcho.zOld = previous.z;
		actionEcho.yRotO = previous.yaw; actionEcho.xRotO = previous.pitch;
		actionEcho.setPose(frame.pose);
		actionEcho.setYBodyRot(frame.yaw);
		actionEcho.setYHeadRot(frame.yaw);
		actionEcho.setShiftKeyDown(frame.shiftKeyDown);
		actionEcho.setSprinting(frame.sprinting);
		actionEcho.setSwimming(frame.swimming);
		actionEcho.walkAnimation.update(frame.walkSpeed, 1.0F, 1.0F);
		actionEcho.swinging = frame.swinging;
		actionEcho.swingTime = frame.swingTime;
		actionEcho.oAttackAnim = frame.previousAttackAnim;
		actionEcho.attackAnim = frame.attackAnim;
		actionEcho.swingingArm = frame.swingingArm;
		for (int index = 0; index < PLAYER_EQUIPMENT_SLOTS.size(); index++)
			actionEcho.setItemSlot(PLAYER_EQUIPMENT_SLOTS.get(index), frame.equipment.get(index).copy());
		if (frame.usingHand != null) {
			if (!actionEcho.isUsingItem() || actionEcho.getUsedItemHand() != frame.usingHand)
				actionEcho.startUsingItem(frame.usingHand);
		} else if (actionEcho.isUsingItem()) actionEcho.stopUsingItem();
		if (echoCrack != null && !echoCrack.equals(frame.digging))
			client.level.destroyBlockProgress(echoBreakerId(), echoCrack, -1);
		echoCrack = frame.digging;
		if (echoCrack != null) client.level.destroyBlockProgress(echoBreakerId(), echoCrack,
				Math.clamp(frameIndex / 6, 0, 9));
	}

	/**
	 * How much of the instance is spent walking up to the wall before the first blow lands.
	 *
	 * <p>A fraction rather than a fixed count of ticks, because the client GameTests run the whole
	 * anomaly in forty ticks and a constant sized for a sixteen-second instance would put the entire
	 * digging act past the end of the accelerated one - the crack would never open and the test
	 * would be asserting a presentation nobody plays.
	 */
	private static int phantomApproachTicks() {
		return Math.max(1, totalTicks * 2 / 5);
	}

	/**
	 * The two acts: somebody walks up to the wall, then they start digging through it.
	 *
	 * <p>Which act was playing used to be {@code random.nextBoolean()} on every burst, so the merged
	 * anomaly delivered digging and footsteps shuffled together while a crack that had been in the
	 * wall since the first tick deepened on a clock of its own. Every individual part was there and
	 * the sequence they describe was not: a hole that predates the person, blows that stop for a
	 * walk and resume, and no moment where anything begins.
	 *
	 * <p>So the order is the anomaly now rather than an outcome of it. The approach is walked in a
	 * straight line from beyond the anchor down to it, positioned from {@code elapsed} rather than
	 * integrated from a velocity, so the footsteps provably arrive at the wall at the moment the
	 * digging starts instead of merely tending that way. Then the walking is cut off mid-burst, one
	 * short beat of nothing, and the first blow - which is also what opens the crack, so the wall the
	 * player has been listening to stays intact until somebody is actually hitting it.
	 */
	private static void tickPhantomEcho(Minecraft client, int elapsed) {
		boolean approaching = elapsed < phantomApproachTicks();
		// Cut the approach off on the tick it ends rather than at the end of whatever gap the
		// footsteps had already queued: the pause between the last step and the first blow is the
		// hinge of the whole thing and must not be however long the walking burst had left to run.
		if (!approaching && !phantomMiningBurst && phantomSoundMaterial != null) {
			phantomBurstRemaining = 0;
			phantomSoundMaterial = null;
			phantomApproachDistance = 0.0D;
			// Scaled for the same reason the approach is. About two thirds of a second in play; one
			// tick in an accelerated test, where a fixed beat would eat most of the digging act.
			soundDelay = Math.clamp(totalTicks / 24, 1, 13);
			return;
		}
		if (soundDelay-- > 0) return;
		RandomSource random = RandomSource.create(seed ^ elapsed * 0x9E3779B97F4A7C15L);
		if (phantomBurstRemaining <= 0 || phantomSoundMaterial == null) {
			phantomMiningBurst = !approaching;
			phantomBurstRemaining = phantomMiningBurst ? 3 + random.nextInt(3) : 2 + random.nextInt(5);
			// A digging burst sounds from the block that is visibly cracking, a walking one closes on
			// it. Before the merge every burst was placed on its own random bearing, which was the
			// only option while there was nothing in the world to point at; now there is, and having
			// either act come from anywhere but the anchor would say the two are unrelated.
			if (phantomMiningBurst && fracturePos != null) {
				Vec3 anchor = Vec3.atCenterOf(fracturePos);
				phantomSoundX = anchor.x;
				phantomSoundY = anchor.y;
				phantomSoundZ = anchor.z;
				phantomWalkX = 0.0D;
				phantomWalkZ = 0.0D;
				phantomApproachDistance = 0.0D;
				phantomSoundMaterial = client.level.getBlockState(fracturePos);
				openPhantomFracture(client, elapsed, random);
			} else if (fracturePos != null && armPhantomApproach(client)) {
				phantomSoundMaterial = phantomStepMaterial(client);
			} else {
				// No anchor to walk towards. Unreachable from the server path, which refuses the whole
				// anomaly without one, and kept because it is what makes this method safe to call at
				// all if an anchor ever stops being guaranteed.
				double angle = Math.toRadians(client.player.getYRot() + 50.0D + random.nextDouble() * 260.0D);
				double distance = 1.7D + random.nextDouble() * 3.0D;
				phantomSoundX = client.player.getX() - Math.sin(angle) * distance;
				phantomSoundY = client.player.getY();
				phantomSoundZ = client.player.getZ() + Math.cos(angle) * distance;
				double walkAngle = angle + (random.nextBoolean() ? Math.PI / 2.0D : -Math.PI / 2.0D);
				phantomWalkX = Math.cos(walkAngle) * (0.28D + random.nextDouble() * 0.16D);
				phantomWalkZ = Math.sin(walkAngle) * (0.28D + random.nextDouble() * 0.16D);
				phantomApproachDistance = 0.0D;
				phantomSoundMaterial = phantomStepMaterial(client);
			}
			// The signature echo is a near-subliminal tell at 0.16, and vanilla derives a sound's
			// audible range from its volume - about two and a half blocks at that level. Played from
			// the walker's own position it would simply not exist across an approach that starts five
			// blocks out, so during the approach it is placed just ahead of the player on the same
			// bearing. What has to survive the distance is which direction it comes from; the
			// distance itself is already being carried by the footsteps, which are loud enough to.
			double cueX = phantomSoundX;
			double cueY = phantomSoundY;
			double cueZ = phantomSoundZ;
			if (phantomApproachDistance > 0.0D) {
				cueX = client.player.getX() + phantomWalkX * 1.8D;
				cueY = client.player.getY();
				cueZ = client.player.getZ() + phantomWalkZ * 1.8D;
			}
			client.level.playLocalSound(cueX, cueY, cueZ, ModSounds.ANOMALY_ECHO,
					SoundSource.AMBIENT, 0.16F, 0.72F + random.nextFloat() * 0.08F, false);
			dedicatedSoundCount++;
		}
		// Placed from elapsed on every step rather than advanced by phantomWalkX/Z, so the walk lands
		// at the wall exactly when the approach runs out however the burst lengths happened to fall.
		if (phantomApproachDistance > 0.0D) {
			Vec3 anchor = Vec3.atCenterOf(fracturePos);
			double remaining = phantomApproachDistance
					* (1.0D - Math.clamp(elapsed / (double) phantomApproachTicks(), 0.0D, 1.0D));
			phantomSoundX = anchor.x + phantomWalkX * remaining;
			phantomSoundZ = anchor.z + phantomWalkZ * remaining;
			phantomSoundY = client.player.getY();
			phantomSoundMaterial = phantomStepMaterial(client);
		}
		SoundEvent humanSound = phantomMiningBurst ? phantomSoundMaterial.getSoundType().getHitSound()
				: phantomSoundMaterial.getSoundType().getStepSound();
		client.level.playLocalSound(phantomSoundX, phantomSoundY, phantomSoundZ, humanSound,
				SoundSource.AMBIENT, phantomMiningBurst ? 0.82F : 0.72F,
				(phantomMiningBurst ? 0.72F : 0.88F) + random.nextFloat() * 0.12F, false);
		ambientSoundCount++;
		phantomBurstRemaining--;
		if (!phantomMiningBurst && phantomApproachDistance <= 0.0D) {
			phantomSoundX += phantomWalkX;
			phantomSoundZ += phantomWalkZ;
		}
		soundDelay = phantomMiningBurst ? 5 + random.nextInt(4) : 7 + random.nextInt(5);
		// The gap between bursts, except during the approach: somebody who leaves for a second and
		// comes back is two people, and the act is meant to read as one who never stops getting
		// closer. A short catch of breath is as much as it may have.
		if (phantomBurstRemaining == 0) soundDelay += phantomMiningBurst ? 14 + random.nextInt(18)
				: phantomApproachDistance > 0.0D ? 3 + random.nextInt(4) : 20 + random.nextInt(26);
	}

	/**
	 * Aims the approach: a straight line inward from beyond the anchor, on the far side of the wall.
	 *
	 * <p>{@code phantomWalkX/Z} carry the unit vector from the anchor back out to where the walker
	 * starts and {@code phantomApproachDistance} how far out that is. Together they are the line;
	 * the position along it is a function of {@code elapsed}, which is also what makes a non-zero
	 * distance the flag for "an approach is running".
	 *
	 * <p>Returns false when the anchor is not somewhere a direction can be taken from, leaving the
	 * wandering fallback to place the burst.
	 */
	private static boolean armPhantomApproach(Minecraft client) {
		Vec3 anchor = Vec3.atCenterOf(fracturePos);
		double dx = anchor.x - client.player.getX();
		double dz = anchor.z - client.player.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.05D) return false;
		phantomWalkX = dx / length;
		phantomWalkZ = dz / length;
		phantomApproachDistance = 5.0D;
		phantomSoundY = client.player.getY();
		phantomSoundX = anchor.x + phantomWalkX * phantomApproachDistance;
		phantomSoundZ = anchor.z + phantomWalkZ * phantomApproachDistance;
		return true;
	}

	/** What the walker is standing on, sampled where the step is heard rather than at the anchor. */
	private static BlockState phantomStepMaterial(Minecraft client) {
		BlockState state = client.level.getBlockState(
				BlockPos.containing(phantomSoundX, phantomSoundY - 1.0D, phantomSoundZ));
		// Steps taken over a hole are silent, and silence in the middle of an approach reads as the
		// walker having stopped. Borrow the player's own floor instead.
		return state.isAir() ? client.level.getBlockState(BlockPos.containing(
				client.player.getX(), client.player.getY() - 1.0D, client.player.getZ())) : state;
	}

	/**
	 * The first blow, which is also what puts the crack in the wall.
	 *
	 * <p>Opened from here rather than from a second timer that happens to agree with this one. The
	 * crack is caused by the digging; two clocks that both claim to know when the digging starts is
	 * how they end up disagreeing by the one tick a player can see.
	 */
	private static void openPhantomFracture(Minecraft client, int elapsed, RandomSource random) {
		if (fractureStage >= 0 || activeLevel == null) return;
		phantomDigStartTick = elapsed;
		fractureStage = 0;
		activeLevel.destroyBlockProgress(fractureBreakerId(), fracturePos, 0);
		Vec3 anchor = Vec3.atCenterOf(fracturePos);
		client.level.playLocalSound(anchor.x, anchor.y, anchor.z,
				activeLevel.getBlockState(fracturePos).getSoundType().getHitSound(),
				SoundSource.BLOCKS, 0.95F, 0.70F + random.nextFloat() * 0.06F, false);
		ambientSoundCount++;
	}

	private static void beginFixedCamera(Minecraft client) {
		previousCameraEntity = client.getCameraEntity();
		previousCameraType = client.options.getCameraType();
		var triggeringCamera = client.gameRenderer.getMainCamera();
		Vec3 triggeringView = triggeringCamera.position();
		fixedCameraX = triggeringView.x;
		fixedCameraY = triggeringView.y;
		fixedCameraZ = triggeringView.z;
		// The separated view follows the player's forward heading at the instant of
		// separation. Looking up or down must not leave the fixed camera aimed away
		// from the path the still-controllable body takes.
		var forward = ViewpointOrientationPolicy.facePlayerForward(client.player.getYRot(), client.player.getXRot());
		lockedPlayerYaw = forward.yaw();
		lockedPlayerPitch = forward.pitch();
		GameProfile bodyProfile = new GameProfile(UUID.randomUUID(), "\u200B");
		secondPersonBody = new ActionEchoPlayer(client.level, bodyProfile, client.player.getSkin());
		secondPersonBody.setId(SECOND_PERSON_BODY_ID);
		secondPersonBody.setInvulnerable(true);
		secondPersonBody.noPhysics = true;
		secondPersonBody.setCustomNameVisible(false);
		client.level.addEntity(secondPersonBody);
		syncSecondPersonBody(client);
		cameraAnchor = new ArmorStand(client.level, fixedCameraX, fixedCameraY, fixedCameraZ);
		cameraAnchor.setId(SECOND_PERSON_CAMERA_ID);
		cameraAnchor.setInvisible(true);
		cameraAnchor.setNoGravity(true);
		cameraAnchor.noPhysics = true;
		fixedCameraY -= cameraAnchor.getEyeHeight();
		maintainFixedCamera(client);
		client.level.addEntity(cameraAnchor);
		client.options.setCameraType(CameraType.FIRST_PERSON);
		client.setCameraEntity(cameraAnchor);
	}

	private static void maintainFixedCamera(Minecraft client) {
		if (cameraAnchor == null) return;
		cameraAnchor.snapTo(fixedCameraX, fixedCameraY, fixedCameraZ, lockedPlayerYaw, lockedPlayerPitch);
		cameraAnchor.setDeltaMovement(Vec3.ZERO);
	}

	private static void syncSecondPersonBody(Minecraft client) {
		if (client.player == null || secondPersonBody == null) return;
		secondPersonBody.snapTo(client.player.getX(), client.player.getY(), client.player.getZ(),
				client.player.getYRot(), client.player.getXRot());
		secondPersonBody.setYHeadRot(client.player.getYHeadRot());
		secondPersonBody.setYBodyRot(client.player.yBodyRot);
		secondPersonBody.setPose(client.player.getPose());
		secondPersonBody.setShiftKeyDown(client.player.isShiftKeyDown());
		secondPersonBody.setSprinting(client.player.isSprinting());
		secondPersonBody.setSwimming(client.player.isSwimming());
		secondPersonBody.walkAnimation.update(client.player.walkAnimation.speed(), 1.0F, 1.0F);
		secondPersonBody.swinging = client.player.swinging;
		secondPersonBody.swingTime = client.player.swingTime;
		secondPersonBody.oAttackAnim = client.player.oAttackAnim;
		secondPersonBody.attackAnim = client.player.attackAnim;
		secondPersonBody.swingingArm = client.player.swingingArm;
		for (EquipmentSlot slot : PLAYER_EQUIPMENT_SLOTS)
			secondPersonBody.setItemSlot(slot, client.player.getItemBySlot(slot).copy());
	}

	private static void selectMisreadItems(Minecraft client, long stableSeed) {
		MISREAD_SLOTS.clear();
		int slots = Math.min(36, client.player.getInventory().getContainerSize());
		java.util.Random random = new java.util.Random(stableSeed);
		for (int[] row : INVENTORY_VISUAL_ROWS) {
			List<Integer> available = new ArrayList<>();
			for (int slot = row[0]; slot < Math.min(row[1], slots); slot++)
				if (!client.player.getInventory().getItem(slot).isEmpty()) available.add(slot);
			if (available.isEmpty()) continue;

			Collections.shuffle(available, random);
			int first = available.getFirst();
			MISREAD_SLOTS.add(first);
			if (available.size() == 1) continue;

			int second = available.get(1);
			int widestGap = Math.abs(second - first);
			for (int index = 2; index < available.size(); index++) {
				int candidate = available.get(index);
				int gap = Math.abs(candidate - first);
				if (gap > widestGap) {
					second = candidate;
					widestGap = gap;
				}
			}
			MISREAD_SLOTS.add(second);
		}
	}

	private static void addPurpleTraces(Minecraft client, long stableSeed) {
		RENDERED_TRACE_POSITIONS.clear();
		CURRENT_RULE_FRAGMENTS.clear();
		ResourceKey<Level> dimension = client.level.dimension();
		BlockPos origin = client.player.blockPosition();
		net.minecraft.world.phys.Vec3 eye = client.player.getEyePosition();
		net.minecraft.world.phys.Vec3 view = client.player.getViewVector(1.0F);
		List<BlockPos> exposed = new ArrayList<>();
		for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-12, -4, -12), origin.offset(12, 5, 12))) {
			BlockPos pos = cursor.immutable();
			if (!client.level.hasChunkAt(pos)) continue;
			BlockState state = client.level.getBlockState(pos);
			if (state.isAir() || state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) continue;
			boolean hasExposedFace = false;
			for (Direction direction : Direction.values()) if (client.level.getBlockState(pos.relative(direction)).isAir()) {
				hasExposedFace = true;
				break;
			}
			if (hasExposedFace) exposed.add(pos);
		}
		exposed.sort(java.util.Comparator
				.comparing((BlockPos pos) -> !isInViewCone(pos, eye, view))
				.thenComparingLong(pos -> mixTraceOrder(pos.asLong(), stableSeed))
				.thenComparingDouble(pos -> pos.distSqr(origin)));
		int added = 0;
		for (BlockPos pos : exposed) {
			if (added >= LOCAL_RULE_FRAGMENT_LIMIT || PURPLE_TRACES.size() >= 512) break;
			if (!separatedFromExistingTraces(dimension, pos)) continue;
			if (PURPLE_TRACES.add(new TracePosition(dimension, pos.immutable()))) {
				CURRENT_RULE_FRAGMENTS.add(pos.immutable());
				added++;
			}
		}
	}

	private static boolean separatedFromExistingTraces(ResourceKey<Level> dimension, BlockPos candidate) {
		for (TracePosition trace : PURPLE_TRACES) {
			if (trace.dimension().equals(dimension)
					&& trace.position().distSqr(candidate) < LOCAL_RULE_MIN_SPACING_SQR) return false;
		}
		return true;
	}

	private static boolean isInViewCone(BlockPos pos, net.minecraft.world.phys.Vec3 eye,
			net.minecraft.world.phys.Vec3 view) {
		net.minecraft.world.phys.Vec3 delta = net.minecraft.world.phys.Vec3.atCenterOf(pos).subtract(eye);
		return delta.lengthSqr() > 0.01D && delta.normalize().dot(view) > 0.55D;
	}

	private static long mixTraceOrder(long position, long stableSeed) {
		long value = position ^ stableSeed;
		value ^= value >>> 33;
		value *= 0xff51afd7ed558ccdl;
		value ^= value >>> 33;
		return value;
	}

	private static void renderOverlay(GuiGraphics graphics) {
		if (instanceId == null) return;
		Minecraft client = Minecraft.getInstance();
		int width = graphics.guiWidth(), height = graphics.guiHeight();
		if (isFullBlackout()) graphics.fill(0, 0, width, height, 0xFF000000);
		else if (nearBlindness) {
			int clearW = Math.max(72, width / 5), clearH = Math.max(54, height / 5);
			int left = (width - clearW) / 2, top = (height - clearH) / 2;
			graphics.fill(0, 0, width, top, 0xF8000000);
			graphics.fill(0, top + clearH, width, height, 0xF8000000);
			graphics.fill(0, top, left, top + clearH, 0xF8000000);
			graphics.fill(left + clearW, top, width, top + clearH, 0xF8000000);
		}
		if (anomalyId.equals("peripheral_residue") && !glitchTriggered) {
			HandLayout hands = peripheralHandLayout(width, height, totalTicks - remainingTicks, totalTicks);
			drawPeripheralHand(graphics, hands.leftX(), hands.leftY(), hands.width(), hands.height(), false, 255);
			// One palm texture is reused; only X is mirrored so both palms keep facing the viewer.
			drawPeripheralHand(graphics, hands.rightX(), hands.rightY(), hands.width(), hands.height(), true, 255);
		}
		if (glitchImpactTicks > 0) {
			requestSignalFilter();
			renderGlitchImpact(graphics, width, height);
		}
		if (simulatedWindow) {
			int elapsed = totalTicks - remainingTicks;
			int insetX = 12 + Math.floorMod(elapsed * 7, Math.max(13, width / 8));
			int insetY = 10 + Math.floorMod(elapsed * 5, Math.max(11, height / 8));
			graphics.fill(insetX, insetY, width - insetX, insetY + 3, 0xFFE8E8E8);
			graphics.fill(insetX, height - insetY - 3, width - insetX, height - insetY, 0xFFE8E8E8);
			graphics.fill(insetX, insetY, insetX + 3, height - insetY, 0xFFE8E8E8);
			graphics.fill(width - insetX - 3, insetY, width - insetX, height - insetY, 0xFFE8E8E8);
		}
		if (simulatedNotepad) {
			int left = width / 7, right = width - left, top = height / 8, bottom = height - top;
			graphics.fill(left, top, right, bottom, 0xFFF1F1ED);
			graphics.fill(left, top, right, top + 14, 0xFFD5D5D0);
			Component fallbackLine = Component.translatable(
					"message.thefourthfrequency.anomaly.desktop_presence.fallback_text");
			for (int row = 0; row < 10; row++)
				graphics.drawString(client.font, fallbackLine,
						left + 8, top + 22 + row * 11, 0xFF141414, false);
		}
	}

	private static float peripheralHandSlide(int elapsed, int duration) {
		float life = Math.max(0.0F, Math.min(1.0F, elapsed / (float) Math.max(1, duration)));
		if (life >= PERIPHERAL_HAND_ENTER_FRACTION) return 1.0F;
		float phase = life / PERIPHERAL_HAND_ENTER_FRACTION;
		phase = Math.max(0.0F, Math.min(1.0F, phase));
		return phase * phase * (3.0F - 2.0F * phase);
	}

	/**
	 * How much further in the palms have reached since the slide finished, 0 to 1.
	 *
	 * <p>Without this the hands arrive at 42% and then hold one absolutely still frame for the
	 * remaining seven seconds, which reads as a decal stuck over the game rather than as
	 * something entering it. The creep is a couple of pixels per second - below the speed at
	 * which the eye catches motion - so what the player registers is that the hands are nearer
	 * than they were, never that they saw them move.</p>
	 */
	private static float peripheralHandCreep(int elapsed, int duration) {
		float life = Math.max(0.0F, Math.min(1.0F, elapsed / (float) Math.max(1, duration)));
		if (life <= PERIPHERAL_HAND_ENTER_FRACTION) return 0.0F;
		return (life - PERIPHERAL_HAND_ENTER_FRACTION) / (1.0F - PERIPHERAL_HAND_ENTER_FRACTION);
	}

	/** A single pixel of unsteadiness, held for four ticks at a time so it never reads as flicker. */
	private static int peripheralHandTremor(int elapsed) {
		return Math.floorMod(AlphaLoadTimeline.noise(elapsed / 4), 3) - 1;
	}

	private static HandLayout peripheralHandLayout(int width, int height, int elapsed, int duration) {
		float slide = peripheralHandSlide(elapsed, duration);
		int drawWidth = Math.min(Math.max(280, Math.round(width * 0.58F)),
				Math.max(280, Math.round(height * 1.06F)));
		int drawHeight = drawWidth / 2;
		int visibleWidth = Math.min(Math.round(drawWidth * 0.78F), Math.round(width * 0.42F));
		int reach = Math.round(drawWidth * PERIPHERAL_HAND_CREEP_FRACTION
				* peripheralHandCreep(elapsed, duration));
		int tremor = peripheralHandTremor(elapsed);
		int leftFinalX = visibleWidth - drawWidth + reach;
		int rightFinalX = width - visibleWidth - reach;
		int leftX = Math.round(Mth.lerp(slide, -drawWidth - 4, leftFinalX)) + tremor;
		int rightX = Math.round(Mth.lerp(slide, width + 4, rightFinalX)) - tremor;
		// The two palms breathe in opposite phase. Locked together they read as one image cut in
		// half; opposed, they read as two arms belonging to something that is not one shape.
		int drift = Math.round(Mth.sin(elapsed * 0.055F) * 2.0F);
		int baseY = (height - drawHeight) / 2;
		return new HandLayout(leftX, rightX, baseY + drift, baseY - drift, drawWidth, drawHeight);
	}

	/**
	 * The burst that ends a corruption impact, drawn in the order the layers physically stack:
	 * the flash, the dark it leaves, whatever the tear is currently eating, the tear itself, the
	 * mistracked band on the picture that comes back, and the frame it all closes on.
	 * {@link GlitchImpactTimeline} owns every number.
	 *
	 * <p>The medium showing through is no longer here. Scanlines and grain used to be drawn into this
	 * overlay, and they are the two layers that most obviously belong to the <em>screen</em> rather
	 * than to anything on it - {@code analog_signal.fsh} now lays both over the finished frame, so
	 * they fall across this overlay and the HUD instead of stopping underneath them.
	 */
	private static void renderGlitchImpact(GuiGraphics graphics, int width, int height) {
		int tick = GlitchImpactTimeline.IMPACT_TICKS - glitchImpactTicks;
		if (!GlitchImpactTimeline.active(tick) || width <= 0 || height <= 0) return;
		int bleach = GlitchImpactTimeline.bleachAlpha(tick);
		if (bleach > 0) graphics.fill(0, 0, width, height, bleach << 24 | GLITCH_BLEACH_COLOR);
		int dropout = GlitchImpactTimeline.dropoutAlpha(tick);
		if (dropout > 0) graphics.fill(0, 0, width, height, dropout << 24);
		renderGlitchDebris(graphics, tick, width, height);
		// The tear and the mistracked band are the shader's now: analog_signal.fsh drags whole bands
		// of the picture sideways and drops the line out of some of them, which is the thing the two
		// methods below could only ever imitate with rectangles. Both are kept and neither is called.
		renderGlitchCollapse(graphics, tick, width, height);
	}

	/**
	 * The palms being torn apart by the burst instead of being switched off by it.
	 *
	 * <p>They used to vanish on the frame before the corruption started, so the two events the
	 * anomaly is built out of never actually met - the hands left, and then separately the screen
	 * broke. Shredding them across the same displaced bands as the tear, for the three ticks the
	 * tear is at full strength, is what makes the corruption read as the thing that took them.</p>
	 */
	private static void renderGlitchDebris(GuiGraphics graphics, int tick, int width, int height) {
		if (!anomalyId.equals("peripheral_residue")) return;
		float strength = GlitchImpactTimeline.debrisStrength(tick);
		if (strength <= 0.0F) return;
		HandLayout hands = peripheralHandLayout(width, height, totalTicks - remainingTicks, totalTicks);
		int alpha = Math.clamp(Math.round(255 * strength), 0, 255);
		for (int slice = 0; slice < GlitchImpactTimeline.SLICES; slice++) {
			int top = GlitchImpactTimeline.sliceTop(slice, height);
			int bottom = GlitchImpactTimeline.sliceTop(slice + 1, height);
			if (bottom <= top || GlitchImpactTimeline.sliceLost(slice, tick, strength)) continue;
			int shift = GlitchImpactTimeline.sliceShift(slice, tick, width, strength);
			graphics.enableScissor(0, top, width, bottom);
			drawPeripheralHand(graphics, hands.leftX() + shift, hands.leftY(),
					hands.width(), hands.height(), false, alpha);
			drawPeripheralHand(graphics, hands.rightX() - shift, hands.rightY(),
					hands.width(), hands.height(), true, alpha);
			graphics.disableScissor();
		}
	}

	/**
	 * The picture torn into bands: some dragged sideways with a red and cyan ghost either side of
	 * them, some carrying nothing at all.
	 *
	 * <p>Displaced streaks rather than a re-render of the shifted picture, for the same reason
	 * {@link AlphaCorruptionRenderer#drawTrackingBand} draws them that way - the read is identical
	 * at a fraction of the cost.</p>
	 */
	/**
	 * <b>Retired.</b> The burst's torn bands, superseded by the TearBands stage of analog_signal.fsh, which drags the picture rather than covering it.
	 *
	 * <p>Kept rather than deleted. It is the reference for what the shader term replacing it is
	 * meant to look like, and the fallback if a driver ever turns out not to compile the chain -
	 * a coloured band drawn on top of the picture is a poor version of the effect, but it is a
	 * version, and nothing calls it today.
	 */
	@SuppressWarnings("unused")
	private static void renderTornPicture(GuiGraphics graphics, int tick, int width, int height) {
		float strength = GlitchImpactTimeline.tearStrength(tick);
		if (strength <= 0.0F) return;
		int chroma = Math.round(GlitchImpactTimeline.chromaOffset(tick));
		// Quieter than it was, because it is no longer the whole effect. The world underneath is
		// being displaced by analog_signal.fsh now, so this layer's job has changed from faking a
		// tear to carrying what a tear leaves behind. Painted at the old weight on top of a picture
		// that is already moving, it simply hid it.
		int streakAlpha = Math.round(104 * strength);
		int ghostAlpha = Math.round(78 * strength);
		for (int slice = 0; slice < GlitchImpactTimeline.SLICES; slice++) {
			int top = GlitchImpactTimeline.sliceTop(slice, height);
			int bottom = GlitchImpactTimeline.sliceTop(slice + 1, height);
			if (bottom <= top) continue;
			if (GlitchImpactTimeline.sliceLost(slice, tick, strength)) {
				graphics.fill(0, top, width, bottom, Math.round(212 * strength) << 24);
				graphics.fill(0, top, width, top + 1,
						Math.round(150 * strength) << 24 | GLITCH_CHROMA_CYAN);
				continue;
			}
			int shift = GlitchImpactTimeline.sliceShift(slice, tick, width, strength);
			int left = GlitchImpactTimeline.sliceStreakLeft(slice, tick, width) + shift;
			int right = left + GlitchImpactTimeline.sliceStreakWidth(slice, tick, width);
			if (chroma > 0) {
				softStreak(graphics, left - chroma, top, right - chroma, bottom,
						ghostAlpha, GLITCH_CHROMA_RED, width);
				softStreak(graphics, left + chroma, top, right + chroma, bottom,
						ghostAlpha, GLITCH_CHROMA_CYAN, width);
			}
			softStreak(graphics, left, top, right, bottom, streakAlpha, GLITCH_STREAK_COLOR, width);
		}
	}

	/**
	 * One displaced streak, with its ends faded rather than cut.
	 *
	 * <p>A flat rectangle has four hard edges, and the two vertical ones are the giveaway - nothing
	 * in a signal stops at a column. Drawn as a bright core with a faint shoulder either side, the
	 * same streak reads as a piece of a line that arrived at the wrong place instead of as a box.
	 */
	private static void softStreak(GuiGraphics graphics, int left, int top, int right, int bottom,
			int alpha, int color, int width) {
		if (alpha <= 0 || right <= left) return;
		int shoulder = Math.max(2, (right - left) / 5);
		fillClamped(graphics, left - shoulder, top, left, bottom, alpha / 3 << 24 | color, width);
		fillClamped(graphics, left, top, right, bottom, alpha << 24 | color, width);
		fillClamped(graphics, right, top, right + shoulder, bottom, alpha / 3 << 24 | color, width);
	}

	/**
	 * <b>Retired.</b> The burst's mistracked band, superseded by the shader's own roll bar.
	 *
	 * <p>Kept rather than deleted. It is the reference for what the shader term replacing it is
	 * meant to look like, and the fallback if a driver ever turns out not to compile the chain -
	 * a coloured band drawn on top of the picture is a poor version of the effect, but it is a
	 * version, and nothing calls it today.
	 */
	@SuppressWarnings("unused")
	private static void renderMistrackedBand(GuiGraphics graphics, int tick, int width, int height) {
		int top = GlitchImpactTimeline.rollBandTop(tick, height);
		if (top == Integer.MIN_VALUE) return;
		int bandHeight = GlitchImpactTimeline.rollBandHeight(height);
		if (top + bandHeight <= 0 || top >= height) return;
		AnalogFilter.rollBar(graphics, top, bandHeight, 1.0F);
	}

	private static void renderGlitchCollapse(GuiGraphics graphics, int tick, int width, int height) {
		if (!GlitchImpactTimeline.collapsing(tick)) return;
		int halfHeight = GlitchImpactTimeline.collapseHalfHeight(tick, height);
		int inset = GlitchImpactTimeline.collapseInset(tick, width);
		int alpha = GlitchImpactTimeline.collapseAlpha(tick);
		int centerY = height / 2;
		if (width - inset <= inset) return;
		int pinch = GlitchImpactTimeline.collapsePinchHeight(tick, height);
		graphics.fill(0, centerY - halfHeight - pinch, width, centerY - halfHeight, 0xB4000000);
		graphics.fill(0, centerY + halfHeight, width, centerY + halfHeight + pinch, 0xB4000000);
		graphics.fill(inset, centerY - halfHeight, width - inset, centerY + halfHeight,
				alpha << 24 | GLITCH_BLEACH_COLOR);
	}

	/** {@link GuiGraphics#fill} draws nothing at all when handed a reversed or off-screen span. */
	private static void fillClamped(GuiGraphics graphics, int left, int top, int right, int bottom,
			int color, int width) {
		int clampedLeft = Math.max(0, Math.min(left, width));
		int clampedRight = Math.max(0, Math.min(right, width));
		if (clampedRight <= clampedLeft) return;
		graphics.fill(clampedLeft, top, clampedRight, bottom, color);
	}

	private static void drawPeripheralHand(GuiGraphics graphics, int x, int y, int width, int height,
			boolean mirrorHorizontally, int alpha) {
		int tint = Math.clamp(alpha, 0, 255) << 24 | 0x00FFFFFF;
		if (!mirrorHorizontally) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, HANDS, x, y, 0.0F, 0.0F,
					width, height, HAND_TEXTURE_WIDTH, HAND_TEXTURE_HEIGHT,
					HAND_TEXTURE_WIDTH, HAND_TEXTURE_HEIGHT, tint);
			return;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, HANDS, x, y, HAND_TEXTURE_WIDTH, 0.0F,
				width, height, -HAND_TEXTURE_WIDTH, HAND_TEXTURE_HEIGHT,
				HAND_TEXTURE_WIDTH, HAND_TEXTURE_HEIGHT, tint);
	}

	private static void releaseAllInput(Minecraft client) {
		client.options.keyUp.setDown(false); client.options.keyDown.setDown(false);
		client.options.keyLeft.setDown(false); client.options.keyRight.setDown(false);
		client.options.keyJump.setDown(false); client.options.keyShift.setDown(false);
		client.options.keyAttack.setDown(false); client.options.keyUse.setDown(false);
		client.options.keyInventory.setDown(false); client.options.keyDrop.setDown(false);
		client.options.keySwapOffhand.setDown(false);
	}

	private static void fail(Minecraft client) {
		restore(client, true, AnomalyCompletionStatus.FAILED);
	}

	/** Idempotent recovery used by timeout, death, dimension change, disconnect, stop and F8. */
	public static void restore(Minecraft client, boolean report, AnomalyCompletionStatus status) {
		if (restoring) return;
		restoring = true;
		try {
			UUID completed = instanceId;
			// No closing cue. A sustained anomaly used to end on a carrier-lost tone, on the reasoning
			// that a transmission stopping reads as worse than one starting - but in play it only
			// announced that the thing was over, which is the one fact these are built not to give.
			// Silence returning is the ending.
			if (fracturePos != null && activeLevel != null)
				activeLevel.destroyBlockProgress(fractureBreakerId(), fracturePos, -1);
			if (echoCrack != null && activeLevel != null)
				activeLevel.destroyBlockProgress(echoBreakerId(), echoCrack, -1);
			if (actionEcho != null && activeLevel != null)
				activeLevel.removeEntity(actionEcho.getId(), Entity.RemovalReason.DISCARDED);
			if (cameraAnchor != null && activeLevel != null)
				activeLevel.removeEntity(cameraAnchor.getId(), Entity.RemovalReason.DISCARDED);
			if (secondPersonBody != null && activeLevel != null)
				activeLevel.removeEntity(secondPersonBody.getId(), Entity.RemovalReason.DISCARDED);
			if (client.getCameraEntity() == cameraAnchor)
				client.setCameraEntity(previousCameraEntity != null ? previousCameraEntity : client.player);
			if (previousCameraType != null) client.options.setCameraType(previousCameraType);
			if (client.screen instanceof ChannelOverrideScreen) client.setScreen(null);
			// Unconditional and idempotent: this is the timeout backstop for the unsolved lighting
			// in local_rule_collapse, and it is also the path a disconnect, a death and a dimension
			// change all arrive on. The missing textures in the same volume are deliberately not
			// cleared here - they outlive the anomaly.
			LuminanceFaultClient.end(client);
			if (anomalyId.equals("window_pulse") || anomalyId.equals("desktop_presence"))
				MetaController.finishAnomaly(status != AnomalyCompletionStatus.COMPLETED);
			fracturePos = null; echoCrack = null; actionEcho = null; cameraAnchor = null; secondPersonBody = null;
			previousCameraEntity = null;
			previousCameraType = null;
			echoFrames = List.of(); MISREAD_SLOTS.clear(); CURRENT_RULE_FRAGMENTS.clear();
			instanceId = null; anomalyId = "none"; seed = 0L; totalTicks = 0; remainingTicks = 0;
			lastPhaseSequence = 0; currentPhase = "idle"; phaseBlackout = false; nearBlindness = false;
			dedicatedSoundCount = 0; ambientSoundCount = 0; fractureStage = -1;
			phantomApproachDistance = 0.0D; phantomMiningBurst = false; phantomDigStartTick = -1;
			glitchImpactTicks = 0; glitchTriggered = false; attackWasDown = false;
			phantomBurstRemaining = 0; phantomSoundMaterial = null;
			simulatedWindow = false; simulatedNotepad = false; activeLevel = null;
			if (report && completed != null && ClientPlayNetworking.canSend(AnomalyCompleteC2S.TYPE))
				ClientPlayNetworking.send(new AnomalyCompleteC2S(completed, status));
		} finally { restoring = false; }
	}

	public static void restoreForMetaToggle() {
		restore(Minecraft.getInstance(), true, AnomalyCompletionStatus.INTERRUPTED);
	}

	public static boolean isInputLocked() {
		return instanceId != null && (anomalyId.equals("experience_gap")
				|| anomalyId.equals("channel_override"));
	}
	public static boolean isFirstPersonHandHidden() {
		return instanceId != null && anomalyId.equals("viewpoint_separation");
	}
	public static boolean shouldControlSeparatedPlayer() {
		return instanceId != null && anomalyId.equals("viewpoint_separation");
	}
	public static boolean isAudioMuted() {
		return instanceId != null && anomalyId.equals("experience_gap");
	}
	/**
	 * silent_world removes the world's own voice while leaving the player's intact: footsteps,
	 * mining and hits still answer, but ambience, weather and every creature go quiet. The point
	 * is that nothing announces itself - the player is left unsure whether anything is even
	 * happening, which is why this runs for minutes rather than seconds.
	 */
	public static boolean isSilentWorldActive() {
		return instanceId != null && anomalyId.equals("silent_world");
	}
	/**
	 * The anomalies long enough that a closing cue reads as an ending rather than as a stutter.
	 *
	 * <p>Two of these run for minutes; red_horizon runs for one. All three earn the tail for the
	 * same reason: they are transmissions the player has been living inside long enough to notice
	 * the moment one stops.</p>
	 */
	private static boolean isSustainedAnomaly(String id) {
		return id.equals("silent_world") || id.equals("metric_drift") || id.equals("red_horizon");
	}
	/** How far out an anchorless anomaly's opening cue is placed, in blocks. */
	private static final double ANCHORLESS_CUE_DISTANCE = 9.0D;

	/**
	 * The one cue every anomaly opens on: vanilla's own cave ambience, placed in the world.
	 *
	 * <p>Deliberately a sound the player has already heard a thousand times and already has an
	 * instinct about. A bespoke synthetic cue announces "the mod is doing something"; the cave
	 * ambience means "something is here that you did not put here", which is what an anomaly is,
	 * and it means that before the player has consciously identified the sound at all.</p>
	 *
	 * <p>Positioned rather than played into the player's ears, so it carries a bearing: the anomaly
	 * comes from somewhere, and turning to look is a thing the player can do about it. An anomaly
	 * with an anchor sounds from the anchor, because that is genuinely where it is happening; one
	 * without gets a direction derived from its own seed, which keeps the bearing stable for the
	 * whole instance and identical on every client rather than re-rolled per lookup.</p>
	 */
	/**
	 * The anomalies that announce themselves.
	 *
	 * <p>The cue used to fire for all nineteen, which made every anomaly arrive the same way: a
	 * sound from a direction, and then something to find. That is the wrong instrument for most of
	 * them. These four are the ones a player can miss entirely - a herd turning its head, an echo of
	 * what they just did, an item wearing the wrong face, a wall that stopped having a texture - and
	 * each is somewhere specific, so a bearing is genuinely information. The rest either fill the
	 * screen or change a rule; those do not need to be pointed at, and pointing at them turns
	 * "something is wrong here" into a notification.
	 */
	private static final Set<String> CUED_ANOMALIES = Set.of(
			"watcher_alignment", "action_echo", "organ_misread", "peripheral_residue");

	private static void playTriggerCue(Minecraft client, AnomalyStartS2C payload) {
		if (client.player == null || client.level == null) return;
		if (!CUED_ANOMALIES.contains(payload.anomalyId())) return;
		Vec3 source = triggerSource(client, payload);
		client.level.playLocalSound(source.x, source.y, source.z, SoundEvents.AMBIENT_CAVE.value(),
				SoundSource.AMBIENT, 1.0F, 0.72F, false);
		ambientSoundCount++;
	}

	private static Vec3 triggerSource(Minecraft client, AnomalyStartS2C payload) {
		if (payload.hasAnchor()) return Vec3.atCenterOf(BlockPos.of(payload.anchorPosition()));
		// Level with the player's eyes rather than offset vertically: the horizontal bearing is the
		// whole point of positioning it, and a height difference only muddies it.
		double angle = Math.toRadians(Math.floorMod(payload.seed(), 360L));
		return client.player.getEyePosition().add(Math.cos(angle) * ANCHORLESS_CUE_DISTANCE, 0.0D,
				Math.sin(angle) * ANCHORLESS_CUE_DISTANCE);
	}

	/**
	 * Signal cues play as UI sound rather than through the level: restore() runs on disconnect and
	 * dimension changes where {@code client.level} may already be gone, and the signal is not a
	 * thing positioned in the world anyway.
	 */
	private static void playSignalCue(Minecraft client, SoundEvent cue, float volume) {
		client.getSoundManager().play(
				net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(cue, 1.0F, volume));
	}
	public static boolean isAmbientSourceSilenced(net.minecraft.sounds.SoundSource source) {
		if (!isSilentWorldActive() || source == null) return false;
		return switch (source) {
			case AMBIENT, WEATHER, MUSIC, RECORDS, HOSTILE, NEUTRAL -> true;
			default -> false;
		};
	}
	/**
	 * The sky half of metric_drift: the celestial bodies desynchronise from the actual world time.
	 * Lighting, mob spawning and every game rule keep obeying the real clock; only the sun, moon and
	 * stars move to the wrong place. The result is a sky that contradicts everything else the player
	 * can verify - stars out at noon, the sun below the horizon while the ground stays lit - which is
	 * far stranger than simply forcing night.
	 *
	 * <p>Merged into the same anomaly as the terminal's bent distances rather than kept as its own,
	 * because they are one statement: the frame everything is measured against has drifted, and both
	 * the instrument and the sky are reading off it. Two separate sustained anomalies each bending
	 * one dial said it twice, more weakly, and never at the same time.
	 */
	public static boolean isTemporalDriftActive() {
		return isMetricDriftActive();
	}
	/**
	 * How far the sky has been rotated away from the local clock, as a fraction of a full turn.
	 *
	 * <p>Zero unless metric_drift is running. Exposed because the weather tool's phase channel
	 * reports exactly this quantity: the terminal reads the real server clock while the sky above
	 * it does not, and that disagreement is the whole point of the anomaly. Derived here rather
	 * than re-computed by the instrument so the number the tool prints and the rotation the sky
	 * renderer applies can never come apart.</p>
	 */
	public static float celestialPhaseError() {
		if (!isTemporalDriftActive()) return 0.0F;
		return 0.30F + Math.floorMod(seed, 1_000L) / 1_000.0F * 0.40F;
	}
	/** Rotates a celestial angle (radians) by a stable per-session fraction of a full turn. */
	public static float driftedCelestialAngle(float original) {
		if (!isTemporalDriftActive()) return original;
		float turn = celestialPhaseError();
		return (float) Math.floorMod((long) ((original + turn * Mth.TWO_PI) * 1_000.0F),
				(long) (Mth.TWO_PI * 1_000.0F)) / 1_000.0F;
	}
	/** Keeps the stars visible even when the real clock says it is daytime. */
	public static float driftedStarBrightness(float original) {
		return isTemporalDriftActive() ? Math.max(original, 0.55F) : original;
	}
	/**
	 * metric_drift bends what is measured rather than what the world does. Distances and coordinates
	 * read slightly wrong for minutes at a time - and the sky is off its own clock for the same
	 * minutes, see {@link #isTemporalDriftActive} - so the instrument the player has been taught to
	 * rely on becomes the thing they cannot verify, while navigation and arrival keep using the real
	 * position underneath.
	 */
	public static boolean isMetricDriftActive() {
		return instanceId != null && anomalyId.equals("metric_drift");
	}
	/** Deterministic per-session skew so a re-read of the same value stays consistently wrong. */
	public static int driftedMetric(int original) {
		if (!isMetricDriftActive()) return original;
		int magnitude = 1 + (int) Math.floorMod(seed >>> 3, 3L);
		return original + (Math.floorMod(seed + original, 2L) == 0L ? magnitude : -magnitude);
	}
	/**
	 * The sky dome overhead, which takes only a fraction of the horizon's strength.
	 *
	 * <p>Vanilla blends the dome colour into the fog colour towards the horizon, so tinting the
	 * two by different amounts buys a genuine vertical gradient out of two flat uniforms. That
	 * is what makes the anomaly a horizon rather than a filter: the worst of it sits where the
	 * player is already looking for distance, and directly overhead is nearly untouched.</p>
	 */
	public static int redSkyShaderColor(int original) {
		float strength = redSkyDomeStrength();
		if (strength <= 0.0F) return original;
		return tintRed(original, strength);
	}
	/** The horizon band and the fog that carries it, at full strength. */
	public static int redHorizonShaderColor(int original) {
		float strength = redHorizonStrength();
		if (strength <= 0.0F) return original;
		return tintRed(original, strength);
	}
	public static boolean isRedHorizonActive() {
		return instanceId != null && anomalyId.equals("red_horizon");
	}
	public static float redHorizonStrength() {
		if (!isRedHorizonActive()) return 0.0F;
		return RedHorizonTimeline.horizonStrength(totalTicks - remainingTicks, remainingTicks, totalTicks);
	}
	public static float redSkyDomeStrength() {
		if (!isRedHorizonActive()) return 0.0F;
		return RedHorizonTimeline.skyDomeStrength(totalTicks - remainingTicks, remainingTicks, totalTicks);
	}
	/** How far the world has closed in. Trails the colour - see {@link RedHorizonTimeline}. */
	public static float redFogTightness() {
		if (!isRedHorizonActive()) return 0.0F;
		return RedHorizonTimeline.fogTightness(totalTicks - remainingTicks, remainingTicks, totalTicks);
	}
	private static int tintRed(int original, float strength) {
		int alpha = original >>> 24;
		if (alpha == 0) alpha = 255;
		int red = mix((original >> 16) & 255, 176, strength);
		int green = mix((original >> 8) & 255, 12, strength);
		int blue = mix(original & 255, 22, strength);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}
	/**
	 * Whether this particular stack is one of the ones being misread.
	 *
	 * <p>The empty check is not a shortcut, it is the correctness of the whole predicate. Slots are
	 * matched by identity against the live inventory, and <b>every</b> empty slot in the game holds
	 * the same {@code ItemStack.EMPTY} singleton. The moment a player picks up a misread item -
	 * dragging it, or any click that moves it to the cursor - that slot becomes EMPTY, and from
	 * then on every empty stack in existence matched it. The mixin injects at the head of
	 * {@code renderItem}, ahead of vanilla's own empty check, and {@code renderSlot} calls that
	 * method for empty slots too, so the result was an inventory screen where every vacant square
	 * was an eye.
	 */
	public static boolean isMisread(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return false;
		for (int slot : MISREAD_SLOTS)
			if (slot < client.player.getInventory().getContainerSize()
					&& client.player.getInventory().getItem(slot) == stack) return true;
		return false;
	}
	public static Set<Integer> misreadSlotsForTesting() { return Set.copyOf(MISREAD_SLOTS); }
	public static BlockState visualReplacement(BlockPos pos, BlockState original) {
		// Substituting air paints a solid proxy where nothing exists, so the player sees a block
		// that cannot be mined, has no drops and no hardness - there is nothing there to break.
		// Skipping air also short-circuits the most common state in any chunk build before any work.
		if (original.isAir()) return original;
		BlockState endingReplacement = WorldInterfacePresentationController.failureBlockReplacement(pos, original);
		if (endingReplacement != original) return endingReplacement;
		// This runs on chunk-build worker threads for every queried block (10^5-10^6 calls per
		// section rebuild), and PURPLE_TRACES is empty until the session's first local_rule_collapse.
		// Short-circuiting here skips the per-call TracePosition allocation and set lookup for the
		// overwhelming majority of calls instead of paying for both every time.
		if (PURPLE_TRACES.isEmpty()) return original;
		var level = Minecraft.getInstance().level;
		if (level == null) return original;
		TracePosition trace = new TracePosition(level.dimension(), pos);
		return PURPLE_TRACES.contains(trace) ? ModBlocks.MISSING_TEXTURE_PROXY.defaultBlockState() : original;
	}
	public static int purpleTraceCount() { return PURPLE_TRACES.size(); }
	public static Set<BlockPos> currentRuleFragmentsForTesting() {
		return Set.copyOf(CURRENT_RULE_FRAGMENTS);
	}
	public static float fixedCameraYawForTesting() { return lockedPlayerYaw; }
	public static float fixedCameraPitchForTesting() { return lockedPlayerPitch; }
	public static void onWatcherVisible(int entityId, double x, double y, double z) {
		if (WATCHERS_HEARD.size() > 192) WATCHERS_HEARD.clear();
		if (!WATCHERS_HEARD.add(entityId)) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		client.level.playLocalSound(x, y, z, SoundEvents.AMBIENT_CAVE.value(),
				SoundSource.AMBIENT, 1.0F, 0.72F, false);
		if (instanceId != null && anomalyId.equals("dark_watcher")) ambientSoundCount++;
	}
	public static void markTraceRendered(BlockPos pos) { RENDERED_TRACE_POSITIONS.add(pos.immutable()); }
	public static boolean isAnonymousProxy(Entity entity) {
		return entity != null && (entity == actionEcho || entity == secondPersonBody);
	}
	public static String activeId() { return anomalyId; }
	public static int remainingTicks() { return remainingTicks; }
	/** Total scheduled length of the running anomaly, so envelopes can be positioned within it. */
	public static int totalTicks() { return totalTicks; }
	public static AnomalyTestSnapshot testSnapshot() {
		Set<String> overlays = new java.util.LinkedHashSet<>();
		boolean echoRegistered = actionEcho != null && activeLevel != null
				&& activeLevel.getEntity(actionEcho.getId()) == actionEcho;
		boolean cameraRegistered = cameraAnchor != null && activeLevel != null
				&& activeLevel.getEntity(cameraAnchor.getId()) == cameraAnchor
				&& Minecraft.getInstance().getCameraEntity() == cameraAnchor;
		boolean secondPersonBodyRegistered = secondPersonBody != null && activeLevel != null
				&& activeLevel.getEntity(secondPersonBody.getId()) == secondPersonBody;
		if (isFullBlackout()) overlays.add("blackout");
		if (nearBlindness) overlays.add("near_blindness");
		if (echoRegistered) overlays.add("action_echo_replay");
		if (echoRegistered && (actionEcho.walkAnimation.isMoving() || actionEcho.swinging
				|| actionEcho.attackAnim > 0.0F || actionEcho.getPose() != Pose.STANDING))
			overlays.add("action_echo_animation");
		if (cameraRegistered) overlays.add("second_person_camera");
		if (cameraRegistered && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON
				&& cameraAnchor.distanceToSqr(fixedCameraX, fixedCameraY, fixedCameraZ) < 0.0001D)
			overlays.add("trigger_view_camera_fixed");
		if (secondPersonBodyRegistered) overlays.add("second_person_body_proxy");
		if (isFirstPersonHandHidden()) overlays.add("first_person_hands_hidden");
		if (instanceId != null && anomalyId.equals("local_rule_collapse") && !PURPLE_TRACES.isEmpty())
			overlays.add("missing_texture_proxies");
		if (instanceId != null && anomalyId.equals("local_rule_collapse") && !RENDERED_TRACE_POSITIONS.isEmpty())
			overlays.add("missing_texture_proxies_rendered");
		if (instanceId != null && anomalyId.equals("red_horizon")) {
			overlays.add("red_horizon");
			overlays.add("red_world_fog");
		}
		if (instanceId != null && anomalyId.equals("peripheral_residue") && !glitchTriggered)
			overlays.add("peripheral_hand_instances");
		// The sustained anomalies have no screen overlay of their own - that is the point - so
		// they publish a marker here instead, giving the client GameTests something observable.
		if (instanceId != null && anomalyId.equals("silent_world")) overlays.add("ambient_silenced");
		if (isTemporalDriftActive()) overlays.add("sky_desynchronised");
		if (isMetricDriftActive()) overlays.add("readout_skewed");
		// Same reason as the sustained markers above: the fault lives in baked chunk meshes, so there
		// is no screen layer for a test to look at. It publishes whether the region is still held.
		if (LuminanceFaultClient.active()) overlays.add("lighting_unsolved");
		if (glitchImpactTicks > 0) overlays.add("glitch_impact");
		if (fractureStage >= 0) overlays.add("surface_fracture");
		if (simulatedWindow) overlays.add("window_fallback");
		if (simulatedNotepad) overlays.add("notepad_fallback");
		if (instanceId != null && anomalyId.equals("channel_override")) overlays.add("channel_override");
		return new AnomalyTestSnapshot(instanceId, anomalyId, currentPhase, remainingTicks, overlays,
				dedicatedSoundCount, ambientSoundCount, 0, MISREAD_SLOTS.size(), fractureStage,
				cameraRegistered,
				isInputLocked(), isAudioMuted(), (actionEcho == null ? 0 : 1)
						+ (cameraRegistered ? 1 : 0) + (secondPersonBodyRegistered ? 1 : 0),
				PURPLE_TRACES.size(), simulatedWindow || simulatedNotepad);
	}
	private static int mix(int from, int to, float amount) {
		return Math.clamp(Math.round(from + (to - from) * amount), 0, 255);
	}
	private static boolean isFullBlackout() {
		return instanceId != null && anomalyId.equals("experience_gap");
	}
	private static int fractureBreakerId() { return -0x4F465246; }
	private static int echoBreakerId() { return -0x4543484F; }

	private record PlayerFrame(double x, double y, double z, float yaw, float pitch, Pose pose,
			float walkSpeed, boolean swinging, int swingTime, float previousAttackAnim, float attackAnim,
			InteractionHand swingingArm, boolean sprinting, boolean shiftKeyDown, boolean swimming,
			InteractionHand usingHand, List<ItemStack> equipment, BlockPos digging) { }
	private record TracePosition(ResourceKey<Level> dimension, BlockPos position) { }
	/** Both palms for one tick. Their two Y values differ by the opposed breathing drift. */
	private record HandLayout(int leftX, int rightX, int leftY, int rightY, int width, int height) { }

	private static final class ActionEchoPlayer extends RemotePlayer {
		private final PlayerSkin skin;
		private ActionEchoPlayer(ClientLevel level, GameProfile profile, PlayerSkin skin) {
			super(level, profile);
			this.skin = skin;
		}
		@Override public PlayerSkin getSkin() { return skin; }
		@Override public boolean isPushable() { return false; }
		@Override public boolean isPickable() { return false; }
	}
}
