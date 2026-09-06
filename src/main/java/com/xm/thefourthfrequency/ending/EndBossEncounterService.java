package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.ResonanceCoreBlockEntity;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.entity.StabilityAnchorEntity;
import com.xm.thefourthfrequency.entity.StabilityAnchorGeometry;
import com.xm.thefourthfrequency.entity.WorldInterfaceAnatomy;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.entity.WorldInterfacePartEntity;
import com.xm.thefourthfrequency.networking.AltarActionC2S;
import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.PoemCompleteC2S;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.TerminalNoticePayload;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import com.xm.thefourthfrequency.terminal.TerminalSignalService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.WeakHashMap;

/**
 * Authoritative orchestrator for the dedicated final boss, its ritual and both endings.
 *
 * <p>This class is the only owner of encounter stage transitions, the virtual health pool and the collapse clock;
 * entities, blocks and packets are projections or narrowly validated inputs.</p>
 */
public final class EndBossEncounterService {
	private static final UUID NIL_UUID = new UUID(0L, 0L);
	/**
	 * The summon window, taken from the shared timeline rather than restated.
	 *
	 * <p>Was 100 ticks - five seconds of the boss standing still in silence before the fight began.
	 * The ceremony that replaced it is in {@link WorldInterfaceSummonTimeline}, which both this and
	 * the client read, so the two cannot disagree about how long the arrival takes.
	 */
	private static final int SUMMON_DURATION_TICKS = WorldInterfaceSummonTimeline.TOTAL_TICKS;
	/**
	 * The successful ending, in order: the body falls, the body goes, the dragon arrives, the dragon
	 * opens the way out.
	 *
	 * <p>These used to overlap. The dragon was spawned at tick eighty and spoke at a hundred and
	 * seventy, while the interface itself was not taken off the field until two hundred and sixty -
	 * so the thing the players had just killed was still lying in the sky, mid-collapse, when its
	 * replacement flew in and thanked them for killing it. The exit then arrived ninety ticks after
	 * the last line, from the altar, unattached to anything.</p>
	 *
	 * <p>Nothing overlaps now. Each beat waits for the one before it to be visibly finished.</p>
	 */
	private static final int ANCHORS_SKYWARD_TICKS = 20;
	/** The collapse and fade clips run 9.0s; the body is only discarded once they have played out. */
	private static final int BOSS_REMOVAL_TICKS = 180;
	/**
	 * Blocks per tick at the top of the death ascent.
	 *
	 * <p>Deliberately a fraction of {@link #FAILURE_ESCAPE_TOP_SPEED}. A loss is the interface
	 * getting away, so it leaves fast enough to be lost; a win is it coming apart on the way up, and
	 * every part of that has to stay readable from the altar for the whole nine seconds.</p>
	 */
	private static final double DEATH_ASCENT_TOP_SPEED = 0.42D;
	/** Ticks spent accelerating into the climb, so the body drifts loose before it rises. */
	private static final int DEATH_ASCENT_RAMP_TICKS = 70;
	/** Two seconds of empty sky, so the body going is its own beat and not a cut. */
	private static final int DRAGON_SUMMON_TICKS = 220;
	/**
	 * The call, and the longest single beat of the ending.
	 *
	 * <p>The dragon used to be added to the world twenty ticks after the body was discarded, with
	 * nothing in between and nothing announcing it: one tick there was empty sky and the next tick
	 * there was a dragon in it, at seventy-two blocks out, already flying. Whatever the ending was
	 * trying to say about what the players had just spared, "it was there all along" is not it.</p>
	 *
	 * <p>So the sky is opened first, and the opening takes long enough to be watched. Six seconds of
	 * the orbit being drawn in light before anything flies along it, which is also what makes the
	 * arrival land - the players know where to be looking by the time it happens.</p>
	 */
	private static final int DRAGON_SUMMON_DURATION_TICKS = 120;
	/** The dragon is added on the tick the summon peaks, never before it. */
	private static final int DRAGON_SPAWN_TICKS = DRAGON_SUMMON_TICKS + DRAGON_SUMMON_DURATION_TICKS;
	/**
	 * The eight seconds the dragon spends prising the altar open, from the tick it arrives.
	 *
	 * <p>Five was not enough to read as work. The whole descent from the resting orbit to the low
	 * circle happens inside this window, so at a hundred ticks the dragon was still straightening
	 * out of its dive when the exit finished opening underneath it.</p>
	 */
	private static final int DRAGON_PORTAL_WORK_TICKS = 160;
	private static final int SUCCESS_PORTAL_TICKS = DRAGON_SPAWN_TICKS + DRAGON_PORTAL_WORK_TICKS;
	/** First line while it is still working; the second lands on the tick the exit exists. */
	private static final int DRAGON_FIRST_LINE_TICKS = DRAGON_SPAWN_TICKS + 70;
	private static final int RESOLUTION_DURATION_TICKS = 260;
	private static final int SNAPSHOT_INTERVAL_TICKS = 10;
	private static final int HEAL_INTERVAL_TICKS = 20;
	private static final int MAX_MUTATION_RETRIES = 5;
	private static final double ENCOUNTER_VISIBILITY_RADIUS_SQR = 256.0D * 256.0D;
	/** Before the fight starts nothing has to be reachable, so the prelude stays purely a silhouette. */
	private static final double PRELUDE_HOVER_HEIGHT = 18.0D;
	/** Slow enough that the core's telegraph shapes stay readable while it tracks a strafing player. */
	private static final float BODY_TURN_DEGREES_PER_TICK = 2.2F;
	/**
	 * The morph is a flight: the storm leaves, changes where nobody can see it, and comes back.
	 *
	 * <p>It was played in place for a while, on the reasoning that four seconds with nothing in the
	 * arena to hit is four seconds the encounter has stopped. That is a real cost and it is being
	 * paid on purpose. A body twenty-five blocks across cannot credibly become a different body in
	 * front of you - the renderer's pinch shuts the shell and draws the next one out of it, which
	 * covers the swap frame but does not hide that a silhouette the player has spent two minutes
	 * learning has just been replaced in place. Taking it out of sight is what makes the next form an
	 * arrival rather than a substitution, and the two morphs are the beats the whole fight is shaped
	 * around.
	 *
	 * <p>Six seconds. It was four, and at that length the climb and the return were over before
	 * either read as travel - the storm hopped rather than left. Lengthening it also lines the apex
	 * up with the authored clip for the first time: {@code WorldInterfaceRig} plays the shell tear
	 * over three seconds, and the reveal now falls exactly there instead of a second before the
	 * animation has finished. Still inside the encounter's own rule that the player is never left
	 * with nothing to do for long.
	 */
	private static final int MORPH_FLIGHT_TICKS = 120;
	/** The apex: where the shell splits, the form is set, and the shockwave lands. */
	private static final int MORPH_REVEAL_TICKS = MORPH_FLIGHT_TICKS / 2;
	/** Ticks the body is locked in place while it anchors, before the climb starts. */
	private static final int MORPH_ANCHOR_TICKS = 12;
	/** How far the body sinks into its own crouch before it launches. */
	private static final double MORPH_INTAKE_DIP = 3.5D;
	/**
	 * Blocks above the arena floor the climb reaches, where the form is actually changed.
	 *
	 * <p>Just under the height the summon descends from, and for the same reason: it is far enough
	 * that the body reads as gone from anywhere on the island, and the renderer is pinching it to
	 * under a third of its size at exactly that moment.
	 */
	private static final double MORPH_ASCENT_HEIGHT = 110.0D;
	/**
	 * The interface's roar: the vanilla dragon growl and the wither's, layered and pitched down.
	 *
	 * <p>Borrowed rather than generated on purpose. Both halves are sounds the game has already
	 * taught the player, and neither one alone says the right thing. The dragon's growl is what the
	 * End means - a player who has fought it reads the shape before they have identified anything on
	 * screen - but on its own it says "the dragon", and this is not the dragon. The wither's is the
	 * other boss roar vanilla owns, and it brings the grain the growl has none of.
	 *
	 * <p>Stacked, with the wither under the growl and pitched further down, they stop being either
	 * one. The pitch falls as the body grows, so the same cue reports which form is out there from
	 * across the island.
	 */
	private static final float[] ROAR_PITCH_BY_FORM = {0.82F, 0.68F, 0.56F};
	/**
	 * The wither layer's pitch, lower again than the growl it sits under.
	 *
	 * <p>Deliberately not the same table. Two samples at one pitch read as one sample with a chorus
	 * on it; separated by roughly a fourth they read as one throat with two registers in it.
	 */
	private static final float[] ROAR_WITHER_PITCH_BY_FORM = {0.68F, 0.56F, 0.46F};
	/** How loud the wither layer sits under the growl. It is grain, not a second roar. */
	private static final float ROAR_WITHER_VOLUME = 0.62F;
	/**
	 * Ticks between idle roars, per form.
	 *
	 * <p>One flat interval meant the third form - the one that is supposed to be unbearable - roared
	 * exactly as often as the first. Tightening it per form makes the presence itself escalate, and
	 * the shortest of the three is still long enough not to become a metronome.
	 */
	private static final int[] ROAR_INTERVAL_BY_FORM = {200, 170, 140};
	/** Beat the interface holds over the ruin before it starts climbing away from a lost encounter. */
	private static final int FAILURE_ESCAPE_HOLD_TICKS = 30;
	/** Ticks the ascent takes to reach full speed. */
	private static final int FAILURE_ESCAPE_RAMP_TICKS = 55;
	/** Blocks per tick at the top of the climb: gone from the sky inside the resolution window. */
	private static final double FAILURE_ESCAPE_TOP_SPEED = 3.4D;
	/** Vanilla melee reach, used to place impact feedback where the player actually swung. */
	private static final double MELEE_CONTACT_REACH = 3.0D;
	private static final String PART_TAG_PREFIX = "thefourthfrequency.world_interface_part.";

	private static final Map<MinecraftServer, Map<UUID, Long>> CLIENT_SEQUENCES =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<MinecraftServer, Set<UUID>> ALTAR_VIEWERS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<MinecraftServer, Map<UUID, Long>> PROJECTILE_HITS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<MinecraftServer, Map<UUID, Long>> MELEE_HITS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Set<MinecraftServer> RECOVERED_SERVERS =
			Collections.newSetFromMap(new WeakHashMap<>());
	private static boolean initialized;

	private EndBossEncounterService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		WorldInterfaceRitualService.registerAltarOpenHandler((player, position, status) -> openAltar(player, status));
		ServerTickEvents.START_SERVER_TICK.register(EndBossEncounterService::tickStart);
		ServerLifecycleEvents.SERVER_STARTED.register(EndBossEncounterService::recoverAfterRestart);
		ServerLifecycleEvents.SERVER_STOPPING.register(EndBossEncounterService::pauseForStop);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CLIENT_SEQUENCES.remove(server);
			ALTAR_VIEWERS.remove(server);
			PROJECTILE_HITS.remove(server);
			MELEE_HITS.remove(server);
			RECOVERED_SERVERS.remove(server);
		});
		ServerPlayerEvents.JOIN.register(EndBossEncounterService::reconcilePlayer);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			WorldInterfaceState.snapshot(newPlayer.level().getServer()).encounterId()
					.ifPresent(id -> WorldInterfaceAttackService.onDeath(newPlayer, id));
			reconcilePlayer(newPlayer);
		});
	}

	/** Called only after vanilla successfully places the twelfth eye. */
	public static void prepareFromActivatedPortal(ServerLevel sourceLevel, BlockPos portalCenter,
			ServerPlayer activator) {
		if (sourceLevel.dimension() == Level.END) return;
		MinecraftServer server = sourceLevel.getServer();
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) return;

		WorldInterfaceState.Snapshot existing = WorldInterfaceState.snapshot(server);
		if (!existing.valid()) {
			WorldInterfaceState.clearInvalid(server);
			existing = WorldInterfaceState.snapshot(server);
		}
		if (!existing.present()) {
			EndBossArenaService.PreparedArena prepared = EndBossArenaService.prepare(end);
			WorldInterfaceState.ArenaLayout layout = new WorldInterfaceState.ArenaLayout(1,
					Level.END.identifier().toString(), prepared.center(), prepared.altar(), prepared.safeSpawn(),
					indexedGates(prepared), indexedAnchors(prepared));
			UUID encounterId = UUID.randomUUID();
			long seed = end.getSeed() ^ encounterId.getMostSignificantBits()
					^ encounterId.getLeastSignificantBits() ^ end.getGameTime();
			WorldInterfaceState.MutationResult initializedState =
					WorldInterfaceState.initialize(server, encounterId, layout, seed);
			if (!initializedState.applied()) return;
			existing = initializedState.snapshot();
		}
		if (existing.stage() == WorldInterfaceStage.ARENA_READY) {
			WorldInterfaceState.transition(server, existing.encounterId().orElseThrow(), existing.revision(),
					WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS);
		}
		bindCore(end, WorldInterfaceState.snapshot(server));
		recordAltarOpening(activator, sourceLevel, portalCenter);
		AudioService.playBounded(sourceLevel, portalCenter, ModSounds.WORLD_INTERFACE_ALTAR,
				SoundSource.AMBIENT, 0.82F, 1.0F);
	}

	/**
	 * Debug-only jump straight into the encounter, skipping the twelve eyes and the walk to the
	 * altar. Everything after the arrival is the real path: the arena is built the way the portal
	 * builds it, and the terminal is really sacrificed, because the committed sacrifice is what the
	 * stage invariants are written against and faking it would test a state the game cannot reach.
	 */
	public static DebugBossResult debugStartEncounter(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) return DebugBossResult.END_MISSING;

		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid()) {
			WorldInterfaceState.clearInvalid(server);
			snapshot = WorldInterfaceState.snapshot(server);
		}
		if (snapshot.present() && snapshot.stage().wireId() >= WorldInterfaceStage.SUMMONING.wireId()) {
			return DebugBossResult.ALREADY_RUNNING;
		}
		if (!snapshot.present()) {
			EndBossArenaService.PreparedArena prepared = EndBossArenaService.prepare(end);
			WorldInterfaceState.ArenaLayout layout = new WorldInterfaceState.ArenaLayout(1,
					Level.END.identifier().toString(), prepared.center(), prepared.altar(), prepared.safeSpawn(),
					indexedGates(prepared), indexedAnchors(prepared));
			UUID encounterId = UUID.randomUUID();
			long seed = end.getSeed() ^ encounterId.getMostSignificantBits()
					^ encounterId.getLeastSignificantBits() ^ end.getGameTime();
			WorldInterfaceState.MutationResult initialized =
					WorldInterfaceState.initialize(server, encounterId, layout, seed);
			if (!initialized.applied()) return DebugBossResult.ARENA_FAILED;
			snapshot = initialized.snapshot();
		}
		if (snapshot.stage() == WorldInterfaceStage.ARENA_READY) {
			WorldInterfaceState.MutationResult opened = WorldInterfaceState.transition(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(),
					WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS);
			if (!opened.applied()) return DebugBossResult.ARENA_FAILED;
			snapshot = opened.snapshot();
		}
		bindCore(end, snapshot);

		// The sacrifice needs a real bound terminal in hand; a tester who jumped the mainline will
		// not have one, and issuing it here is the same recovery the mod already performs on join.
		TerminalLifecycleService.ensureCarried(player, true);
		if (!rememberAndOverrideRespawn(player, snapshot)) return DebugBossResult.ARENA_FAILED;
		snapshot = WorldInterfaceState.snapshot(server);
		BlockPos altar = snapshot.altarCenter();
		if (!player.teleportTo(end, altar.getX() + 0.5D, altar.getY() + 1.0D, altar.getZ() + 2.5D,
				Set.of(), 180.0F, 0.0F, true)) return DebugBossResult.ARENA_FAILED;
		SurvivalProgressService.mark(player, SurvivalMilestone.ENTERED_END);

		WorldInterfaceRitualService.RitualResult deposited = WorldInterfaceRitualService.deposit(player,
				snapshot.encounterId().orElseThrow(), snapshot.revision());
		sendAltarSnapshots(server, deposited.snapshot(), altarStatus(deposited.reason()));
		if (deposited.snapshot().stage() == WorldInterfaceStage.SUMMONING) {
			sendEncounterSnapshots(server, true);
			return DebugBossResult.STARTED;
		}
		// The roster is every non-spectator online, so on a shared server the remaining players
		// still have to deposit; the arena and the altar are ready and waiting for them.
		return deposited.applied() ? DebugBossResult.WAITING_FOR_OTHERS : DebugBossResult.DEPOSIT_REJECTED;
	}

	public enum DebugBossResult {
		STARTED,
		WAITING_FOR_OTHERS,
		ALREADY_RUNNING,
		END_MISSING,
		ARENA_FAILED,
		DEPOSIT_REJECTED
	}

	/** Overrides only End-portal travel while a prepared world-interface encounter exists. */
	public static Optional<TeleportTransition> createPortalTransition(ServerLevel sourceLevel, Entity entity,
			BlockPos portalPosition) {
		if (sourceLevel.dimension() == Level.END || !(entity instanceof ServerPlayer player)
				|| player.isSpectator()) return Optional.empty();
		MinecraftServer server = sourceLevel.getServer();
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present() || snapshot.stage() == WorldInterfaceStage.COMPLETE) {
			return Optional.empty();
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) return Optional.empty();
		// Arrival only. Overriding the respawn point used to happen here, to everybody who used an End
		// portal for as long as an encounter existed - so a player who had never heard of the ritual
		// walked through a portal and silently had their spawn moved to an altar in another dimension,
		// and the eight-entry ledger filled up with people who were not going to fight. The override
		// belongs to the sacrifice, which is the moment somebody actually became a participant; see
		// ensureRosterRespawnOverrides.
		SurvivalProgressService.mark(player, SurvivalMilestone.ENTERED_END);
		Vec3 arrival = Vec3.atBottomCenterOf(snapshot.safeSpawn());
		return Optional.of(new TeleportTransition(end, arrival, Vec3.ZERO, 180.0F, 0.0F,
				TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
	}

	/** Entity AI callback: movement and rendering projection only; attacks remain server-tick owned. */
	public static void tickBossEntity(ServerLevel level, WorldInterfaceEntity boss) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(level.getServer());
		if (!snapshot.valid() || !snapshot.present()
				|| snapshot.encounterId().filter(id -> id.equals(boss.encounterId())).isEmpty()
				|| snapshot.bossUuid().filter(id -> id.equals(boss.getUUID())).isEmpty()) {
			boss.discard();
			return;
		}
		if (snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()) {
			boss.discard();
			return;
		}
		// Published before anything else can return early: the pose the hit boxes stand on reads it,
		// so a stale fraction is a set of boxes sagging by the wrong amount rather than a cosmetic
		// lag in the bar.
		boss.setHealthFraction(snapshot.maxVirtualHealth() <= 0.0D ? 1.0F
				: (float) (snapshot.virtualHealth() / snapshot.maxVirtualHealth()));
		int form = formForStage(snapshot.stage());
		// Until it reaches the top of the morph climb it is still the old body, so the form is not
		// pushed here: driveMorphFlight owns it for the length of the flight.
		if (driveMorphFlight(level, boss, snapshot, form)) return;
		boss.setForm(form);
		if (snapshot.stage() == WorldInterfaceStage.FAILURE_RESOLUTION) {
			driveFailureEscape(level, boss, snapshot);
			return;
		}
		// A win climbs too, and it has to climb from here for the same reason the loss does. This is
		// the entity's own AI step, which is where the move itself happens; a second tick source
		// writing velocity from the service tick lands on either side of that move depending on
		// registration order, so the body took some ticks off and jumped through others.
		if (snapshot.stage().isResolution()) {
			driveDeathAscent(level, boss, snapshot);
			return;
		}
		if (snapshot.stage() == WorldInterfaceStage.PORTAL_OPEN) {
			boss.setDeltaMovement(Vec3.ZERO);
			return;
		}
		if (!snapshot.stage().isCombat()) {
			hoverAt(boss, snapshot.arenaCenter(), PRELUDE_HOVER_HEIGHT, level.getGameTime());
			// Watching before it is fighting. The body holds its ceremonial station and does not turn,
			// but the heads track whoever is down there - which is most of what the arrival is for.
			nearestArenaParticipant(level, snapshot, boss.position())
					.ifPresentOrElse(player -> aimGaze(boss, player.getEyePosition()),
							() -> relaxGaze(boss));
			return;
		}
		// Third form used to be excluded from the chase and left orbiting the arena centre thirty-four
		// blocks up, which put it out of reach of everything except a bow for the whole final phase.
		//
		// The climb is now a window rather than a permanent state: WorldInterfaceSkyholdPolicy owns
		// when the second and third bodies leave every swing, and caps how much of the fight that is.
		long activeTick = effectiveActiveTicks(snapshot, level.getGameTime());
		double lift = WorldInterfaceSkyholdPolicy.lift(snapshot.stage(), activeTick);
		if (WorldInterfaceSkyholdPolicy.isAscentTick(snapshot.stage(), activeTick)) {
			// Announced, not discovered. A boss that silently rises out of reach reads as a bug; the
			// same boss with a cue on the tick it leaves reads as an instruction to change weapon.
			AudioService.playBounded(level, BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss)),
					ModSounds.WORLD_INTERFACE_FLIGHT, SoundSource.HOSTILE, 1.0F, 0.88F);
			roar(level, boss, 0.85F);
		}
		double hover = WorldInterfaceAnatomy.combatHoverHeight(form) + lift;
		ServerPlayer target = nearestArenaParticipant(level, snapshot, boss.position()).orElse(null);
		if (target == null) {
			hoverAt(boss, snapshot.arenaCenter(), hover, level.getGameTime());
			relaxGaze(boss);
			return;
		}
		// Stand off rather than sit on top of them. The chase used to aim at the player's own
		// column, so the interface parked directly overhead and a body twenty-five blocks across
		// simply contained them: nothing to face, nothing to back away from, and the core -- which
		// hangs off the front of the body -- pointing somewhere they were not. Held one body radius
		// plus a swing away, the near face lands at melee reach and the thing is in front of them.
		//
		// The standoff is a floor on how close it will <em>come</em>, never a distance it keeps.
		// Written as a setpoint it was both, and the difference is the whole feel of the chase: a
		// player who ran at the storm was running at a station that slid away from them at the same
		// moment, so closing the gap took as long as the storm wanted it to and catching it never
		// happened. It now stops when it is close enough and lets the player walk the rest of the
		// way in - which is also what makes the head coming down to meet them mean anything.
		// Horizontally the storm answers the whole table, weighted toward whoever is close, rather
		// than locking onto the single nearest player - see WorldInterfacePolicy#attentionCentroid.
		// Vertically it still follows the nearest, because the vertical rule is about the spikes and
		// the anchors on them, and a weighted average of one climber and three players on the floor
		// would answer none of them.
		Vec3 focus = attentionPoint(level, snapshot, boss.position(), target);
		Vec3 flat = boss.position().subtract(focus).multiply(1.0D, 0.0D, 1.0D);
		double gap = flat.length();
		double standoff = WorldInterfaceAnatomy.combatStandoff(form);
		Vec3 station;
		if (gap > standoff) {
			Vec3 approach = gap < 1.0E-4D ? new Vec3(0.0D, 0.0D, -1.0D) : flat.scale(1.0D / gap);
			station = focus.add(approach.scale(standoff));
		} else {
			station = boss.position();
		}
		// Height comes off the island, not off the player - see WorldInterfacePolicy#MAX_VERTICAL_FOLLOW.
		// Following their Y outright turned the body into a ceiling pinned above their head, which is
		// only ever felt on the spikes, and the spikes are where the anchors are.
		steerTo(boss, new Vec3(station.x, WorldInterfacePolicy.combatStationY(
				snapshot.arenaCenter().getY(), target.position().y, hover), station.z));
		faceTarget(boss, focus);
	}

	/**
	 * The horizontal point the body steers to and faces, with the nearest player's height kept.
	 *
	 * <p>Collapses to the nearest player's own position on a solo table, which is what every number
	 * in the chase was tuned against.
	 */
	private static Vec3 attentionPoint(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			Vec3 from, ServerPlayer nearest) {
		List<ServerPlayer> participants = arenaParticipants(level, snapshot);
		if (participants.size() <= 1) return nearest.position();
		double[] xs = new double[participants.size()];
		double[] zs = new double[participants.size()];
		for (int index = 0; index < participants.size(); index++) {
			ServerPlayer participant = participants.get(index);
			xs[index] = participant.getX();
			zs[index] = participant.getZ();
		}
		double[] centroid = WorldInterfacePolicy.attentionCentroid(xs, zs, from.x, from.z);
		return new Vec3(centroid[0], nearest.getY(), centroid[1]);
	}

	/**
	 * Blocks per tick the storm closes horizontally.
	 *
	 * <p>Was 0.11 - two and a fifth blocks a second - against a walking player's four and a third.
	 * <b>It could not keep up with someone walking away from it, let alone sprinting.</b> Every number
	 * in {@link WorldInterfaceAnatomy#combatStandoff} describes where the body sits <em>once it has
	 * arrived</em>, and against anything but a stationary player it never arrived: the station kept
	 * receding faster than the storm closed, so the head a player is meant to swing at spent the fight
	 * further away than the geometry says it is. That is most of why "I cannot hit it" kept coming back
	 * however the head was moved.
	 *
	 * <p>0.22 is a walk. It holds station against a player crossing the arena and is still comfortably
	 * outrun by a sprint, which keeps the one thing the slow chase was protecting - that a player can
	 * always break contact - while ending the case where the storm simply never catches anybody.
	 */
	private static final double CHASE_SPEED = 0.22D;
	/**
	 * Blocks per tick the storm climbs or descends.
	 *
	 * <p>Held apart from {@link #CHASE_SPEED} rather than sharing one figure with it, because the two
	 * legs are asking for different things. The climb has to cover {@link
	 * WorldInterfaceSkyholdPolicy#CEILING_LIFT} inside {@link WorldInterfaceSkyholdPolicy#TRANSIT_TICKS}
	 * or the body is still rising when the window it was rising for has closed; the chase has to stay
	 * slow enough to be circled. A single speed fast enough for the first turns the second into a
	 * thirty-three-block body skating across the island at eleven blocks a second.
	 */
	private static final double CLIMB_SPEED = 0.70D;

	/** Moves the body toward a station with the vertical and horizontal legs budgeted separately. */
	private static void steerTo(WorldInterfaceEntity boss, Vec3 desired) {
		Vec3 delta = desired.subtract(boss.position());
		Vec3 flat = delta.multiply(1.0D, 0.0D, 1.0D);
		double horizontal = flat.length();
		Vec3 step = horizontal < 1.0D ? Vec3.ZERO
				: flat.scale(Math.min(CHASE_SPEED, horizontal) / horizontal);
		double vertical = Math.abs(delta.y) < 0.05D ? 0.0D
				: Math.copySign(Math.min(CLIMB_SPEED, Math.abs(delta.y)), delta.y);
		boss.setDeltaMovement(step.x, vertical, step.z);
	}

	/**
	 * Roar, from the core rather than from the entity position.
	 *
	 * <p>The position matters as much as the sound: the interface's origin is a bookkeeping point
	 * under the arena floor, and at third form that is some nineteen blocks below the body. Emitted
	 * there, a roar from the thing overhead came out of the ground.
	 */
	private static void roar(ServerLevel level, WorldInterfaceEntity boss, float volume) {
		int form = Math.clamp(boss.form(), 0, ROAR_PITCH_BY_FORM.length - 1);
		BlockPos origin = BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss));
		// Through playWithReach, not playBounded. Both halves are borrowed vanilla cues, which carry
		// no attenuation distance of their own and therefore fade out over sixteen blocks - and this
		// is emitted from a core that hangs sixteen to thirty-seven blocks over the arena floor. The
		// roar has been inaudible from the second form onward for as long as the body has been
		// climbing; it was never a mixing problem, it was a radius.
		AudioService.playWithReach(level, origin, SoundEvents.ENDER_DRAGON_GROWL,
				SoundSource.HOSTILE, volume, ROAR_PITCH_BY_FORM[form], ROAR_REACH_BLOCKS);
		// The second throat. Emitted from the same point on the same tick so the two arrive as one
		// sound rather than as a growl with an echo behind it.
		AudioService.playWithReach(level, origin, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE,
				volume * ROAR_WITHER_VOLUME, ROAR_WITHER_PITCH_BY_FORM[form], ROAR_REACH_BLOCKS);
	}

	/** The roar is an arena event: it reports which body is out there from across the island. */
	private static final float ROAR_REACH_BLOCKS = 96.0F;

	/** Whether the interface is currently away on its morph flight, and so owed no other orders. */
	private static boolean isMorphing(ServerLevel level, WorldInterfaceEntity boss) {
		int action = boss.actionId();
		if (action != WorldInterfaceProtocol.BossAction.MORPH_TO_SECOND.wireId()
				&& action != WorldInterfaceProtocol.BossAction.MORPH_TO_THIRD.wireId()) return false;
		long elapsed = level.getGameTime() - boss.actionStartTick();
		return elapsed >= 0L && elapsed < MORPH_FLIGHT_TICKS;
	}

	/**
	 * Brace, climb out of the sky, change up there, and come back down as the next form.
	 *
	 * <p>Returns whether the morph is currently steering the body. Four beats: it braces where it
	 * stands, it climbs to {@link #MORPH_ASCENT_HEIGHT}, at {@link #MORPH_REVEAL_TICKS} - which is the
	 * top of the climb, and out of sight - the shell opens and the new form is set, and then the new
	 * body comes back down onto its station.
	 *
	 * <p>The form is set at the apex rather than at the start so the growth is never seen happening.
	 * A body twenty-five blocks across cannot credibly become a different body in front of you, and
	 * the renderer's pinch - which shuts the shell and draws the next one out of it - is a cover for
	 * the swap rather than a substitute for hiding it. Four seconds with nothing in the arena is the
	 * price, and it is deliberate: the morph is the beat the fight is built around, and what it is
	 * supposed to say is that the thing left.
	 *
	 * <p>Position is written with {@code snapTo} rather than as a velocity, the same way
	 * {@link #driveSummonDescent} does, so the flight path is exact on both sides and a body that is
	 * mid-climb when the server saves resumes at the height its own clock says.
	 */
	private static boolean driveMorphFlight(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, int targetForm) {
		if (!isMorphing(level, boss)) return false;
		long elapsed = level.getGameTime() - boss.actionStartTick();
		boss.setNoGravity(true);
		boss.setDeltaMovement(Vec3.ZERO);
		if (elapsed < MORPH_ANCHOR_TICKS) {
			// Braced. It dips into its own intake before it goes, so the climb reads as a launch.
			double crouch = -MORPH_INTAKE_DIP * (elapsed / (double) MORPH_ANCHOR_TICKS);
			Vec3 held = boss.position().multiply(1.0D, 0.0D, 1.0D)
					.add(0.0D, morphFloor(level, snapshot, boss.form()) + crouch, 0.0D);
			boss.snapTo(held, boss.getYRot(), boss.getXRot());
			emitMorphIntake(level, boss, snapshot, elapsed);
			return true;
		}
		if (elapsed >= MORPH_REVEAL_TICKS) boss.setForm(targetForm);
		int station = elapsed >= MORPH_REVEAL_TICKS ? targetForm : boss.form();
		double ceiling = snapshot.arenaCenter().getY() + MORPH_ASCENT_HEIGHT;
		double height;
		if (elapsed < MORPH_REVEAL_TICKS) {
			// Squared: it detaches, then leaves. A linear climb reads as an elevator.
			double progress = (elapsed - MORPH_ANCHOR_TICKS)
					/ (double) Math.max(1, MORPH_REVEAL_TICKS - MORPH_ANCHOR_TICKS);
			double base = morphFloor(level, snapshot, boss.form()) - MORPH_INTAKE_DIP;
			height = Mth.lerp(progress * progress, base, ceiling);
		} else {
			// Eased on arrival, so the new body settles onto its station instead of hitting it.
			double progress = (elapsed - MORPH_REVEAL_TICKS)
					/ (double) Math.max(1, MORPH_FLIGHT_TICKS - MORPH_REVEAL_TICKS);
			double eased = 1.0D - (1.0D - progress) * (1.0D - progress);
			height = Mth.lerp(eased, ceiling, morphFloor(level, snapshot, station));
		}
		Vec3 desired = morphStation(level, boss, snapshot, station);
		boss.snapTo(new Vec3(desired.x, height, desired.z), boss.getYRot(), boss.getXRot());
		emitMorphFlight(level, boss, snapshot, elapsed, height);
		// The arrival. The departure already has a shockwave - it is emitted the tick the phase turns
		// over, in phaseChanged - and without one at the other end the new body simply appeared back
		// in the arena, which is the half of "it left and something else came back" that carries the
		// beat. Guarded on the exact last tick, so it happens once however the flight is resumed.
		if (elapsed == MORPH_FLIGHT_TICKS - 1) {
			WorldInterfaceShockwaveService.emit(level, new Vec3(desired.x,
							snapshot.arenaCenter().getY(), desired.z),
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
					WorldInterfaceShockwaveService.MORPH_MAX_RADIUS);
			roar(level, boss, 1.0F);
			emitMorphTouchdown(level, boss, new Vec3(desired.x,
					snapshot.arenaCenter().getY() + 0.3D, desired.z));
		}
		// Keeps facing whoever it is doing this in front of - the table, not one of them.
		nearestArenaParticipant(level, snapshot, boss.position())
				.ifPresent(player -> faceTarget(boss, attentionPoint(level, snapshot, boss.position(), player)));
		return true;
	}

	/**
	 * The intake: the arena being pulled into the thing that is about to leave it.
	 *
	 * <p>The brace was twelve ticks of the body dipping and nothing else, which from any distance
	 * was the body doing nothing. What it needs is a direction, and the direction is inward - the
	 * beat only works if the departure looks like it was paid for out of the ground it leaves from.
	 * Rings on the floor closing on the point it is standing over, and light running up into it.</p>
	 */
	private static void emitMorphIntake(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long elapsed) {
		double progress = Math.clamp(elapsed / (double) MORPH_ANCHOR_TICKS, 0.0D, 1.0D);
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		Vec3 floor = new Vec3(boss.getX(), snapshot.arenaCenter().getY() + 0.2D, boss.getZ());
		double reach = WorldInterfaceAnatomy.massRadius(boss.form()) * (2.4D - progress * 1.5D);
		// The circle it is standing in closes on it, and the column it leaves along is already lit.
		WorldInterfaceVfx.runeCircle(level, floor, reach, elapsed * 0.25D, 44);
		// Drawn in rather than thrown out: negative outward speed on a ring is the whole tell.
		WorldInterfaceVfx.shockRing(level, WorldInterfaceVfx.core(), floor, reach * 1.35D, 30,
				-0.55D, 0.22D);
		WorldInterfaceVfx.beacon(level, floor, core.y - floor.y,
				WorldInterfaceAnatomy.coreRadius(boss.form()) * 0.9D, elapsed * 0.4D, 30);
	}

	/**
	 * The climb, and the storm it drags with it.
	 *
	 * <p>Every tick of the flight rather than a burst at each end: the middle of the morph is the
	 * stretch where the arena has nothing in it at all, and a trail is what keeps the thing that
	 * left present while it is away. The column reaches back down to the floor it launched from, so
	 * the players standing there can see how far up it has got without looking for it.</p>
	 */
	private static void emitMorphFlight(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long elapsed, double height) {
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		double shell = WorldInterfaceAnatomy.massRadius(boss.form());
		Vec3 floor = new Vec3(boss.getX(), snapshot.arenaCenter().getY() + 0.2D, boss.getZ());
		// The wake, wound around the line it is travelling along.
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.core(), floor, core,
				shell * 0.75D, MORPH_WAKE_SAMPLES, 3, (core.y - floor.y) * 0.035D, elapsed * 0.3D);
		WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), core,
				new Vec3(0.0D, 1.0D, 0.0D), shell * 1.2D, 26, elapsed * 0.22D, 0.0D);
		// The turn itself: at the reveal tick the old body is gone and the new one is not yet
		// anywhere, and that is the one frame the whole two forms of this fight hinge on.
		if (elapsed == MORPH_REVEAL_TICKS) {
			// The one frame the whole two-form structure of this fight hinges on, so it gets the
			// full detonation and then four leaning rings on top of it.
			WorldInterfaceVfx.detonation(level, core, shell * 1.4D, 3);
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.violet(), core, shell * 1.8D, 200, 0.7D);
			for (int axis = 0; axis < 4; axis++) {
				double lean = axis * (Math.PI / 4.0D);
				WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.core(), core,
						new Vec3(Math.sin(lean), Math.cos(lean * 1.3D), Math.cos(lean)),
						shell * (1.4D + axis * 0.4D), 48, axis * 0.3D, 0.0D);
			}
		}
	}

	/** The new body arriving on the floor, drawn as a landing rather than as a reappearance. */
	private static void emitMorphTouchdown(ServerLevel level, WorldInterfaceEntity boss, Vec3 floor) {
		double shell = WorldInterfaceAnatomy.massRadius(boss.form());
		WorldInterfaceVfx.detonation(level, floor, shell * 1.6D, 3);
		WorldInterfaceVfx.runeCircle(level, floor.add(0.0D, 0.1D, 0.0D), shell * 2.2D, 0.0D, 48);
		WorldInterfaceVfx.beacon(level, floor,
				WorldInterfaceAnatomy.coreOrigin(boss).y - floor.y, shell * 0.5D, 0.0D, 40);
	}

	/** Samples in the wake wound behind the body during the morph climb. */
	private static final int MORPH_WAKE_SAMPLES = 34;

	/**
	 * The altitude the morph leaves from and returns to, in world Y.
	 *
	 * <p>Includes whatever the skyhold window is asking for. A phase boundary can land anywhere in the
	 * encounter clock, so a morph can perfectly well finish while the interface is supposed to be
	 * aloft; without this the new body would descend all the way to its ground station and then
	 * immediately climb back out of reach, which reads as the descent having been a mistake.
	 */
	private static double morphFloor(WorldInterfaceState.Snapshot snapshot, int form) {
		return snapshot.arenaCenter().getY() + WorldInterfaceAnatomy.combatHoverHeight(form);
	}

	/** The same, offset by the climb the skyhold window wants at this point in the encounter. */
	private static double morphFloor(ServerLevel level, WorldInterfaceState.Snapshot snapshot, int form) {
		return morphFloor(snapshot, form) + WorldInterfaceSkyholdPolicy.lift(snapshot.stage(),
				effectiveActiveTicks(snapshot, level.getGameTime()));
	}

	/**
	 * Where the body sits horizontally during the morph: over its ordinary combat station.
	 *
	 * <p>Only the X and Z of this are used while the flight is running - {@link #driveMorphFlight}
	 * owns the altitude - so the storm leaves from over the fight and comes back down onto it rather
	 * than reappearing at the middle of the island.
	 */
	private static Vec3 morphStation(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, int form) {
		double hover = WorldInterfaceAnatomy.combatHoverHeight(form);
		ServerPlayer target = nearestArenaParticipant(level, snapshot, boss.position()).orElse(null);
		if (target == null) return snapshot.arenaCenter().getCenter().add(0.0D, hover, 0.0D);
		// Same station rule the chase uses, so the new body comes down onto the fight rather than
		// onto whichever single player happened to be closest when it left.
		Vec3 focus = attentionPoint(level, snapshot, boss.position(), target);
		Vec3 flat = boss.position().subtract(focus).multiply(1.0D, 0.0D, 1.0D);
		Vec3 approach = flat.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, -1.0D) : flat.normalize();
		return focus
				.add(approach.scale(WorldInterfaceAnatomy.combatStandoff(form)))
				.add(0.0D, hover, 0.0D);
	}

	/**
	 * Turn the body toward whoever it is hunting.
	 *
	 * <p>Nothing ever set the interface's yaw. It kept whatever facing it span up with for the whole
	 * encounter, which meant it never looked at anybody, and {@link WorldInterfaceAnatomy#coreOrigin}
	 * -- which places the core off the front of the body by that yaw -- aimed the core, the laser
	 * origin and the orb feed at a fixed compass direction instead of at the fight.
	 *
	 * <p>Turned slowly on purpose: the telegraph shapes are read off the core, and a body that
	 * snapped to face a strafing player would swing them across the screen faster than they can be
	 * read. Slow enough to circle, fast enough that it is plainly tracking you.
	 */
	private static void faceTarget(WorldInterfaceEntity boss, Vec3 target) {
		Vec3 delta = target.subtract(boss.position());
		if (delta.x * delta.x + delta.z * delta.z < 1.0E-4D) return;
		float wanted = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
		float turned = Mth.approachDegrees(boss.getYRot(), wanted, BODY_TURN_DEGREES_PER_TICK);
		boss.setYRot(turned);
		boss.yBodyRot = turned;
		boss.yHeadRot = turned;
		boss.setYHeadRot(turned);
		// Aimed at the eyes rather than the feet. The heads hang over a player's head and look down at
		// them; the difference is small in yaw and not small at all in pitch when the body is close.
		aimGaze(boss, target.add(0.0D, 1.62D, 0.0D));
	}

	/**
	 * Points the three heads at a world position, on top of whatever the body is doing.
	 *
	 * <p>The body turns at {@link #BODY_TURN_DEGREES_PER_TICK} and it is twenty-five to thirty-three
	 * blocks across, so a player who circles it is looked at by a thing that takes several seconds to
	 * come round. The heads are the fast part: they lead the turn, they carry on tracking after the
	 * body has stopped, and they are what makes the storm read as watching somebody rather than as
	 * facing a compass direction.
	 *
	 * <p>Measured from the heads' own mount rather than from the entity position, which at the third
	 * form is twenty blocks below the body: aiming from there put the elevation a storey out.
	 */
	private static void aimGaze(WorldInterfaceEntity boss, Vec3 target) {
		Vec3 from = WorldInterfaceAnatomy.coreOrigin(boss);
		Vec3 delta = target.subtract(from);
		double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (flat < 1.0E-4D) {
			// Directly underneath: there is no bearing, only a look straight down.
			boss.setGaze(boss.gazeYaw(), (float) Math.copySign(Mth.HALF_PI, -delta.y));
			return;
		}
		float wantedYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
		float relative = Mth.wrapDegrees(wantedYaw - boss.yBodyRot) * Mth.DEG_TO_RAD;
		boss.setGaze(relative, (float) Mth.atan2(-delta.y, flat));
	}

	/** Lets the heads unwind to centre when the storm has nobody to watch. */
	private static void relaxGaze(WorldInterfaceEntity boss) {
		boss.setGaze(0.0F, 0.0F);
	}

	/**
	 * The interface's exit on a loss: it climbs out of the world rather than standing still.
	 *
	 * <p>A won encounter has a death to play - the body comes apart over the altar and the dragon
	 * arrives. A lost one had nothing. The thing that had just beaten a table simply froze where it
	 * was for eight and a half seconds and then blinked out of existence, which reads as the fight
	 * being switched off rather than as the interface being finished with them.</p>
	 *
	 * <p>So it leaves. It holds for a beat over the ruin it made, then rises - slowly at first, then
	 * faster than anything on the island can follow - straight up, until it is a point of light and
	 * then nothing. Nobody drove it off and it is not hurt; it is done looking, which is the worse
	 * reading and the correct one. The removal at the end of the resolution window still happens on
	 * exactly the same tick it always did, hundreds of blocks above anyone's head by then.</p>
	 */
	/**
	 * The death climb, owned by the entity's own step so nothing else writes its velocity.
	 *
	 * <p>Sibling of {@link #driveFailureEscape}, and read off the same resolution clock, so a body
	 * that is mid-ascent when the server saves resumes at the height the clock says rather than
	 * restarting the climb. It goes up because a body that falls has been beaten, and that is not
	 * what this ending is: the interface is not overpowered, it is ended, and what it does on the
	 * way out is let go. The squared ramp makes the first seconds a body drifting loose from where
	 * it was holding station, and only then a climb.</p>
	 *
	 * <p>Deliberately a fraction of the failure escape's speed. A loss leaves fast enough to be
	 * lost; a win comes apart on the way up, and all of that has to stay readable from the altar.</p>
	 */
	private static void driveDeathAscent(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot) {
		long started = snapshot.resolutionTick();
		long age = started < 0L ? 0L : level.getGameTime() - started;
		if (age < 0L || age >= BOSS_REMOVAL_TICKS) {
			boss.setDeltaMovement(Vec3.ZERO);
			return;
		}
		double ramp = Math.min(1.0D, age / (double) DEATH_ASCENT_RAMP_TICKS);
		boss.setDeltaMovement(0.0D, DEATH_ASCENT_TOP_SPEED * ramp * ramp, 0.0D);
	}

	private static void driveFailureEscape(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot) {
		long started = snapshot.resolutionTick();
		long age = started < 0L ? 0L : level.getGameTime() - started;
		long climb = age - FAILURE_ESCAPE_HOLD_TICKS;
		if (climb <= 0L) {
			boss.setDeltaMovement(Vec3.ZERO);
			return;
		}
		// Squared ramp: the first second is a body detaching itself, the last is an ascent.
		double ramp = Math.min(1.0D, climb / (double) FAILURE_ESCAPE_RAMP_TICKS);
		boss.setDeltaMovement(0.0D, FAILURE_ESCAPE_TOP_SPEED * ramp * ramp, 0.0D);
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		double spread = WorldInterfaceAnatomy.massRadius(boss.form()) * 0.5D;
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, core.x, core.y, core.z,
				40, spread, 1.4D, spread, 0.18D);
		level.sendParticles(ParticleTypes.END_ROD, core.x, core.y - spread, core.z,
				24, spread * 0.7D, 0.8D, spread * 0.7D, 0.22D);
		if (climb % 10L == 0L) {
			// Fades with the climb, so the last thing anyone hears of it is already distant.
			AudioService.playBounded(level, BlockPos.containing(core), ModSounds.WORLD_INTERFACE_MORPH,
					SoundSource.HOSTILE, (float) (0.9D - ramp * 0.65D), (float) (0.6D + ramp * 0.55D));
		}
	}

	/** Routes an accepted player-authored hit into the persisted virtual health pool. */
	public static boolean applyVirtualDamage(WorldInterfaceEntity boss, DamageSource source, float rawAmount) {
		if (!(boss.level() instanceof ServerLevel level) || rawAmount <= 0.0F || !Float.isFinite(rawAmount)
				|| !(source.getEntity() instanceof ServerPlayer attacker)) return false;
		MinecraftServer server = level.getServer();
		Entity direct = source.getDirectEntity();
		if (direct != null && direct != attacker && duplicateProjectileHit(server, direct.getUUID(), level.getGameTime())) {
			return false;
		}
		boolean directMelee = direct == null || direct == attacker;
		// Arrows and tridents specifically, not "anything that is not a sword": the multiplier pays
		// for having to hit a body the arena deliberately keeps out of melee range, and a thrown
		// potion or a firework is neither aimed at it nor short of damage.
		boolean arrow = direct instanceof AbstractArrow;
		boolean meleeClaimed = false;

		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(server);
			if (!bossMatches(before, boss) || !before.stage().isCombat()
					|| attacker.isSpectator()) return false;
			long elapsed = effectiveActiveTicks(before, level.getGameTime());
			if (WorldInterfacePolicy.resolveTick(elapsed, false)
					== WorldInterfacePolicy.TickVerdict.FAILURE) {
				lockFailure(level, before);
				return false;
			}
			if (directMelee && !meleeClaimed) {
				if (duplicateMeleeHit(server, attacker.getUUID(), level.getGameTime())) return false;
				meleeClaimed = true;
			}
			double adjusted = WorldInterfacePolicy.adjustedIncomingDamage(rawAmount,
					before.destroyedAnchorCount(), arrow);
			double remaining = Math.max(0.0D, before.virtualHealth() - adjusted);
			boolean lethal = remaining <= 0.0D;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
					before.encounterId().orElseThrow(), before.revision(), state -> {
						long nowElapsed = effectiveActiveTicks(before, level.getGameTime());
						state.setClock(nowElapsed, before.runningSinceGameTime() >= 0L
								? level.getGameTime() : -1L);
						state.setVirtualHealth(before.maxVirtualHealth(), remaining);
						advanceToHealthStage(state, remaining / Math.max(1.0D, before.maxVirtualHealth()));
						if (lethal) {
							state.setClock(nowElapsed, -1L);
							advanceToPhaseThree(state);
							state.clearCurrentAttack();
							state.clearControlCooldowns();
							state.setGateState(WorldInterfaceGatewayState.GOLD);
							state.transitionTo(WorldInterfaceStage.SUCCESS_RESOLUTION);
							state.setResolution(0, level.getGameTime());
						}
					});
			if (!result.applied()) {
				if ("revision_mismatch".equals(result.reason())) continue;
				return false;
			}
			WorldInterfaceState.Snapshot after = result.snapshot();
			emitImpactBurst(level, boss, attacker, adjusted, directMelee);
			if (lethal) beginResolution(level, after, true);
			else if (after.stage() != before.stage()) phaseChanged(level, before, after);
			return true;
		}
		return false;
	}

	/**
	 * The one entry point for damage aimed at a stability anchor.
	 *
	 * <p>Returns empty when the entity is not a slot this encounter owns, which is how a stray
	 * anchor left over from a cleared save stays inert rather than being silently adopted.
	 *
	 * <p>The first valid positive player strike destroys an intact anchor immediately. Destruction is
	 * committed to the world state on the same tick the blow lands; the sixteen ticks of geometry
	 * coming apart afterwards are presentation on an outcome that has already been decided.</p>
	 */
	public static Optional<Boolean> handleAnchorDamage(ServerLevel level, StabilityAnchorEntity anchorEntity,
			DamageSource source, float amount) {
		WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(level.getServer());
		if (!before.valid() || !before.present()) return Optional.empty();
		WorldInterfaceState.Anchor anchor = before.anchorForEntity(anchorEntity.getUUID()).orElse(null);
		if (anchor == null) return Optional.empty();
		if (amount <= 0.0F || !Float.isFinite(amount) || !before.stage().isCombat()
				|| !(source.getEntity() instanceof ServerPlayer player)
				|| player.isSpectator()) return Optional.of(false);
		if (anchor.destroyed()) {
			anchorEntity.beginCollapse();
			return Optional.of(false);
		}
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			before = WorldInterfaceState.snapshot(level.getServer());
			anchor = before.anchorForEntity(anchorEntity.getUUID()).orElse(null);
			if (anchor == null) return Optional.empty();
			if (!before.stage().isCombat()) return Optional.of(false);
			if (anchor.destroyed()) {
				anchorEntity.beginCollapse();
				return Optional.of(false);
			}
			int index = anchor.index();
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(level.getServer(),
					before.encounterId().orElseThrow(), before.revision(), state -> state.markAnchorDestroyed(index));
			if (!result.applied()) {
				if ("revision_mismatch".equals(result.reason())) continue;
				// Anything else silently ate a player's swing. Whatever invariant rejected it, the
				// player just watched a hit do nothing for a reason nobody can see, so it is worth
				// a line in the log rather than another mystery about how many hits an anchor takes.
				TheFourthFrequency.LOGGER.warn("Anchor {} refused destruction: {}",
						anchor.index(), result.reason());
				return Optional.of(false);
			}
			anchorEntity.beginCollapse();
			emitAnchorCollapse(level, before.encounterId().orElseThrow(), anchor.position());
			broadcast(level.getServer(), TerminalNoticePayload.TONE_ANCHOR,
					"message.thefourthfrequency.world_interface.anchor_destroyed",
					result.snapshot().aliveAnchorCount());
			if (WorldInterfacePolicy.hasTimedOut(effectiveActiveTicks(result.snapshot(),
					level.getGameTime()))) lockFailure(level, result.snapshot());
			sendEncounterSnapshots(level.getServer(), true);
			return Optional.of(true);
		}
		return Optional.of(false);
	}

	/**
	 * The server's share of the destruction: two small, bounded bursts pinned to the two cores.
	 *
	 * <p>Deliberately modest. The old single seventy-two particle cloud was the entire effect, and
	 * it had to be, because the crystal vanished on the same tick. The structure now stays for
	 * sixteen ticks and folds in on itself, so this only has to seed the implosion - the rest is
	 * drawn from the entity's own synched collapse clock, which costs no bandwidth per mote.</p>
	 *
	 * <p><b>That reasoning was right about the cost and wrong about the beat.</b> Cutting an anchor is
	 * the one thing the whole encounter is built around a player choosing to do - it is what opens the
	 * interface up, and it is what the terminal spends the fight asking for - and what it looked like
	 * was a structure quietly folding up, with nothing to feel and nothing to hear over the fight
	 * already going on around it.
	 *
	 * <p>So it detonates first and folds afterwards. The tether it was holding goes off at the relay
	 * core, rings of blast front step outward across the cap, the released light fires a column
	 * straight up - the same shape the summon's anchor chain used, which closes the loop on what those
	 * ten columns were for - and a shockwave leaves the spike. The entity's implosion still runs
	 * underneath all of it, so the structure is still visibly taken back rather than merely blown
	 * apart; what changed is that the taking now starts with something coming out.
	 *
	 * <p>Everything here is bounded and counted. The rings are a fixed sample count, the shockwave
	 * answers to its own concurrency cap, and the client-side motes are budgeted per anchor and per
	 * tick in {@link StabilityAnchorGeometry} - ten of these can happen inside a couple of seconds.
	 */
	private static void emitAnchorCollapse(ServerLevel level, UUID encounterId, BlockPos position) {
		Vec3 relay = StabilityAnchorGeometry.relayCore(position);
		Vec3 chest = StabilityAnchorGeometry.chestCore(position);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, relay.x, relay.y, relay.z,
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		level.sendParticles(ParticleTypes.EXPLOSION, relay.x, relay.y, relay.z,
				8, 0.9D, 0.9D, 0.9D, 0.04D);
		// A front rather than a puff: rings stepping outward off the cap, each thrown outward so the
		// wave keeps travelling after it is drawn. Ordered inside-out, the way a blast reads.
		for (int ring = 1; ring <= ANCHOR_BLAST_RINGS; ring++) {
			double radius = ANCHOR_BLAST_STEP * ring;
			int samples = 8 + ring * 4;
			for (int index = 0; index < samples; index++) {
				double angle = Math.PI * 2.0D * index / samples + ring * 0.4D;
				double x = relay.x + Math.cos(angle) * radius;
				double z = relay.z + Math.sin(angle) * radius;
				level.sendParticles(ParticleTypes.EXPLOSION, x, chest.y, z,
						1, 0.35D, 0.35D, 0.35D, 0.02D);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, chest.y, z,
						0, Math.cos(angle), 0.22D, Math.sin(angle), 0.34D + ring * 0.08D);
			}
		}
		// The light it was holding, let go straight up.
		level.sendParticles(ParticleTypes.END_ROD, relay.x, relay.y, relay.z,
				90, 0.35D, 5.0D, 0.35D, 0.55D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, chest.x, chest.y, chest.z,
				110, 0.75D, 1.4D, 0.75D, 0.24D);
		WorldInterfaceShockwaveService.emit(level, relay,
				WorldInterfaceShockwaveService.MORPH_DURATION_TICKS, ANCHOR_SHOCKWAVE_RADIUS);
		// Two layers, because one of them is vanilla's own explosion - the sound every player already
		// reads as "that just came apart" - and the other is the anchor's authored voice. The pair is
		// what makes it a structure being destroyed rather than a generic bang or a distant chime.
		AudioService.playWithReach(level, position, SoundEvents.GENERIC_EXPLODE.value(),
				SoundSource.HOSTILE, 1.0F, 0.68F, AudioService.BLAST_REACH_BLOCKS);
		AudioService.playBounded(level, position, ModSounds.WORLD_INTERFACE_ANCHOR,
				SoundSource.HOSTILE, 1.0F, 0.62F);
		WorldInterfaceBlastService.emit(level, encounterId, relay, ANCHOR_SHAKE_RADIUS,
				WorldInterfaceProtocol.BlastGrade.HEAVY);
	}

	/** Rings of blast front stepping off the cap, and the blocks between them. */
	private static final int ANCHOR_BLAST_RINGS = 3;
	private static final double ANCHOR_BLAST_STEP = 2.2D;
	/** Reach of the ring the destruction sends across the island. */
	private static final double ANCHOR_SHOCKWAVE_RADIUS = 26.0D;
	/**
	 * How far the destruction is felt.
	 *
	 * <p>Heavier than it was, and measured from the right place. The client used to answer an anchor
	 * falling with the lightest impulse there is, emitted at the <em>arena centre</em> with a
	 * ninety-six block falloff - so the player standing at the anchor they had just cut felt less of
	 * it than a bystander in the middle of the island.
	 */
	private static final double ANCHOR_SHAKE_RADIUS = 80.0D;

	public static void handleAltarAction(ServerPlayer player, AltarActionC2S payload) {
		WorldInterfaceRitualService.RitualResult result = switch (payload.action()) {
			case DEPOSIT -> WorldInterfaceRitualService.deposit(player, payload.encounterId(), payload.expectedRevision());
			case WITHDRAW -> WorldInterfaceRitualService.withdraw(player, payload.encounterId(), payload.expectedRevision());
			case CANCEL -> WorldInterfaceRitualService.cancel(player, payload.encounterId(), payload.expectedRevision());
			case SUMMON -> WorldInterfaceRitualService.summon(player, payload.encounterId(), payload.expectedRevision());
		};
		if (result.applied() && payload.action() == WorldInterfaceProtocol.AltarAction.DEPOSIT) {
			ServerLevel end = player.level().getServer().getLevel(Level.END);
			if (end != null) {
				for (BlockPos gate : result.snapshot().gates().stream().map(WorldInterfaceState.Gate::position).toList()) {
					end.sendParticles(ParticleTypes.PORTAL, gate.getX() + 0.5D, gate.getY() + 0.5D,
							gate.getZ() + 0.5D, 12, 0.7D, 2.0D, 0.7D, 0.05D);
				}
				AudioService.playBounded(end, result.snapshot().altarCenter(), ModSounds.WORLD_INTERFACE_TERMINAL,
						SoundSource.AMBIENT, 0.85F, result.snapshot().sacrificeCommitted() ? 0.72F : 1.0F);
			}
		}
		sendAltarSnapshots(player.level().getServer(), result.snapshot(), altarStatus(result.reason()));
		if (result.snapshot().stage() == WorldInterfaceStage.SUMMONING) {
			sendEncounterSnapshots(player.level().getServer(), true);
		}
	}

	public static void handlePoemComplete(ServerPlayer player, PoemCompleteC2S payload) {
		MinecraftServer server = player.level().getServer();
		WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(server);
		// COMPLETE is accepted alongside PORTAL_OPEN. Refusing it there would have left an
		// acknowledgement arriving after the stage settled with nowhere to land, so the entry would
		// stay unacked and the poem would be re-delivered on every subsequent login, forever.
		if (!before.valid() || !before.present()
				|| before.encounterId().filter(payload.encounterId()::equals).isEmpty()
				|| (before.stage() != WorldInterfaceStage.PORTAL_OPEN
						&& before.stage() != WorldInterfaceStage.COMPLETE)) return;
		WorldInterfaceState.PoemLedgerEntry poem = before.poemLedger().get(player.getUUID());
		if (poem == null || !poem.started() || poem.acked() || poem.sequence() != payload.sequence()) return;
		WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
				payload.encounterId(), before.revision(), state -> state.putPoem(new WorldInterfaceState.PoemLedgerEntry(
						player.getUUID(), poem.sequence(), true, true, true)));
		if (!result.applied()) return;
		prepareVanillaEndReturn(player, result.snapshot());
		completeIfAllPoemsAcknowledged(server);
	}

	/** Opens the dedicated altar UI through a complete server snapshot. */
	public static boolean openAltar(ServerPlayer player) {
		return openAltar(player, WorldInterfaceProtocol.AltarStatus.READY);
	}

	/**
	 * Opens the altar UI carrying one specific status line.
	 *
	 * <p>{@code status} is what the screen says the moment it appears. An altar opened by curiosity
	 * gets {@code READY}; one opened by the insertion that just happened gets the outcome of that
	 * insertion, so the first thing the player reads is about the thing they did.</p>
	 *
	 * <p>The other viewers are re-sent too, because a deposit changed the roster they are looking at
	 * and the once-a-second refresh would otherwise be the first they hear of it.</p>
	 */
	public static boolean openAltar(ServerPlayer player, WorldInterfaceProtocol.AltarStatus status) {
		MinecraftServer server = player.level().getServer();
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()
				|| snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS) return false;
		ALTAR_VIEWERS.computeIfAbsent(server, ignored -> new HashSet<>()).add(player.getUUID());
		sendAltarSnapshots(server, snapshot, WorldInterfaceProtocol.AltarStatus.WAITING);
		sendAltarSnapshot(player, snapshot, status);
		return true;
	}

	/** Arms one authored poem immediately before the exit invokes vanilla End credits. */
	public static void startPoem(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(server);
			if (!before.valid() || !before.present()
					|| (before.stage() != WorldInterfaceStage.PORTAL_OPEN
							&& before.stage() != WorldInterfaceStage.COMPLETE)
					|| before.outcome() == WorldInterfaceState.Outcome.NONE) return;
			WorldInterfaceState.PoemLedgerEntry existing = before.poemLedger().get(player.getUUID());
			prepareVanillaEndReturn(player, before);
			// COMPLETE is no longer a refusal. The stage can now settle without a member who was
			// simply offline at the time, so "the exit stage is over" and "this player has had their
			// ending" stopped being the same statement - and the person walking into the exit portal
			// a moment too late is precisely the one for whom they differ.
			if (!before.frozenRoster().contains(player.getUUID())
					|| existing != null && existing.acked()) {
				if (before.stage() == WorldInterfaceStage.PORTAL_OPEN) {
					completeIfAllPoemsAcknowledged(server);
				}
				return;
			}
			long sequence;
			if (existing != null && existing.started()) {
				sequence = existing.sequence();
			} else {
				sequence = nextClientSequence(player);
				WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
						before.encounterId().orElseThrow(), before.revision(), state -> state.putPoem(
								new WorldInterfaceState.PoemLedgerEntry(player.getUUID(), sequence, true, false, false)));
				if (!result.applied()) {
					if ("revision_mismatch".equals(result.reason())) continue;
					return;
				}
				before = result.snapshot();
			}
			rememberClientSequence(player, sequence);
			ServerPlayNetworking.send(player, new PoemStartS2C(before.encounterId().orElseThrow(), sequence,
					outcomeWire(before.outcome()), FrequencyWorldData.get(server).worldId(),
					before.destroyedAnchorCount()));
			return;
		}
	}

	private static void tickStart(MinecraftServer server) {
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()) return;
		if (!RECOVERED_SERVERS.contains(server)) {
			recoverAfterRestart(server);
			snapshot = WorldInterfaceState.snapshot(server);
		}
		EndBossArenaService.suppressVanillaFight(level);
		ensureRosterRespawnOverrides(server, snapshot);
		WorldInterfaceShockwaveService.tick(level);
		if (snapshot.friendlyDragonUuid().isPresent()) {
			UUID dragonId = snapshot.friendlyDragonUuid().orElseThrow();
			// While the exit is being opened the dragon flies a low, fast circle over the altar; once
			// the way out exists it climbs back to the resting orbit and the island is theirs again.
			long resolutionAge = snapshot.resolutionTick() < 0L
					? 0L : level.getGameTime() - snapshot.resolutionTick();
			double approach = dragonApproach(snapshot, resolutionAge);
			if (!FriendlyDragonService.tick(level, dragonId, snapshot.arenaCenter(), approach)
					&& snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()
					&& snapshot.outcome() == WorldInterfaceState.Outcome.SUCCESS) {
				FriendlyDragonService.spawn(level, snapshot.arenaCenter(), dragonId);
			}
		}
		if (snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()) {
			removeBossProjection(level, snapshot);
			snapshot = clearFinishedBossIdentity(server, snapshot);
		}

		switch (snapshot.stage()) {
			case SUMMONING -> tickSummoning(level, snapshot);
			case PHASE_1, PHASE_2, PHASE_3 -> {
				tickCombat(level, snapshot);
				// Written as the fight happens, not afterwards: what the collapse timer has already
				// taken is real ground by the time anyone looks at it.
				commitErosion(level, snapshot);
			}
			case SUCCESS_RESOLUTION, FAILURE_RESOLUTION -> tickResolution(level, snapshot);
			case PORTAL_OPEN -> {
				ensureExitOpen(level, snapshot);
				// On a tick rather than only on the three player-driven paths that used to call it.
				// With offline members excused, the completion condition can now become true because
				// somebody *left*, and there is no event for that to hang off.
				if (server.getTickCount() % 20 == 0) settlePortalOpen(level, snapshot);
			}
			default -> { }
		}
		if (server.getTickCount() % SNAPSHOT_INTERVAL_TICKS == 0) {
			sendEncounterSnapshots(server, false);
			pruneAltarViewers(server);
			// The altar screen now shows a countdown and a summon button, both of which are functions
			// of state that can change without this viewer doing anything - somebody else depositing,
			// or the window simply lapsing. Pushed on the same cadence as everything else rather than
			// left to the action handlers, which only fire for whoever acted. The client holds
			// action feedback on its own timer so this steady WAITING does not wipe a refusal.
			if (snapshot.stage() == WorldInterfaceStage.WAITING_TERMINALS) {
				sendAltarSnapshots(server, WorldInterfaceState.snapshot(server),
						WorldInterfaceProtocol.AltarStatus.WAITING);
			}
		}
	}

	/**
	 * The thirteen-second arrival, as a step table.
	 *
	 * <p>Structurally identical to {@link #tickResolution}: each beat is guarded on
	 * {@code age >= X && resolutionStep() < N} and claims its step on the way through. That is what
	 * makes the ceremony restart-safe. {@code resolutionStep} is already persisted in
	 * {@link WorldInterfaceState} and {@code validate} only range-checks it - it is not coupled to
	 * the stage - so a player who saves and quits partway through the entrance resumes at the beat
	 * they left rather than watching the whole thing again, and none of it needs a schema change.
	 *
	 * <p>What this replaces was five seconds of a boss standing still in silence.
	 */
	private static void tickSummoning(ServerLevel level, WorldInterfaceState.Snapshot before) {
		WorldInterfaceEntity boss = ensureBoss(level, before);
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(level.getServer());
		MinecraftServer server = level.getServer();
		Vec3 centre = snapshot.arenaCenter().getCenter();

		// Beat 1 - the descent begins. The rise cue starts here and its downbeat is timed to land
		// on GROUND_BREAK; see WorldInterfaceSummonTimeline.
		if (snapshot.resolutionTick() < 0L) {
			WorldInterfaceState.MutationResult started = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state ->
						state.setResolution(1, level.getGameTime()));
			if (started.applied()) snapshot = started.snapshot();
			showAction(level, snapshot, WorldInterfaceProtocol.BossAction.SUMMONING,
					WorldInterfaceSummonTimeline.TOTAL_TICKS, List.of(), snapshot.deterministicSeed());
			AudioService.playBounded(level, snapshot.arenaCenter(), ModSounds.WORLD_INTERFACE_SUMMON,
					SoundSource.HOSTILE, 1.0F, 1.0F);
			if (boss != null) {
				boss.setForm(WorldInterfaceEntity.FORM_LISTENING);
				boss.setNoGravity(true);
				boss.snapTo(centre.add(0.0D, SUMMON_DESCENT_START_HEIGHT, 0.0D),
						boss.getYRot(), boss.getXRot());
			}
			return;
		}
		long age = level.getGameTime() - snapshot.resolutionTick();
		if (boss != null) {
			boss.setForm(WorldInterfaceEntity.FORM_LISTENING);
			driveSummonDescent(level, boss, snapshot, age);
		}

		// Beat 2 - the anchor chain fires skyward, one every six ticks, rising in pitch.
		if (age >= WorldInterfaceSummonTimeline.ANCHOR_CHAIN_START && snapshot.resolutionStep() < 2) {
			snapshot = claimSummonStep(server, snapshot, 2);
		}
		if (snapshot.resolutionStep() >= 2 && age >= WorldInterfaceSummonTimeline.ANCHOR_CHAIN_START
				&& age <= WorldInterfaceSummonTimeline.anchorChainEnd()
				&& (age - WorldInterfaceSummonTimeline.ANCHOR_CHAIN_START)
						% WorldInterfaceSummonTimeline.ANCHOR_CHAIN_STEP == 0L) {
			int index = (int) ((age - WorldInterfaceSummonTimeline.ANCHOR_CHAIN_START)
					/ WorldInterfaceSummonTimeline.ANCHOR_CHAIN_STEP);
			emitAnchorIgnition(level, snapshot, index);
		}

		// Beat 3 - the ground breaks. First shockwave, and the tick the rise cue peaks on.
		if (age >= WorldInterfaceSummonTimeline.GROUND_BREAK && snapshot.resolutionStep() < 3) {
			snapshot = claimSummonStep(server, snapshot, 3);
			WorldInterfaceShockwaveService.emit(level, centre,
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
					WorldInterfaceShockwaveService.MORPH_MAX_RADIUS);
		}

		// Beat 4 - the mass resolves out of the sky and the shell settles.
		if (age >= WorldInterfaceSummonTimeline.BODY_REVEAL && snapshot.resolutionStep() < 4) {
			snapshot = claimSummonStep(server, snapshot, 4);
			AudioService.playBounded(level, snapshot.arenaCenter(),
					ModSounds.WORLD_INTERFACE_FORM_SHIFT, SoundSource.HOSTILE, 1.0F, 0.9F);
		}

		// Beat 5 - the three apertures open. Second shockwave.
		if (age >= WorldInterfaceSummonTimeline.EYE_OPEN && snapshot.resolutionStep() < 5) {
			snapshot = claimSummonStep(server, snapshot, 5);
			WorldInterfaceShockwaveService.emit(level, centre.add(0.0D, 12.0D, 0.0D),
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
					WorldInterfaceShockwaveService.MORPH_MAX_RADIUS * 0.8D);
		}

		// Beat 6 - the roar, and the largest shockwave. The client puts its own hit-stop, flash and
		// cataclysm-grade shake on this tick off the action clock, so nothing has to be sent.
		if (age >= WorldInterfaceSummonTimeline.ROAR && snapshot.resolutionStep() < 6) {
			snapshot = claimSummonStep(server, snapshot, 6);
			if (boss != null) roar(level, boss, 1.0F);
			WorldInterfaceShockwaveService.emit(level, centre,
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
					WorldInterfaceShockwaveService.MORPH_MAX_RADIUS * 1.5D);
		}

		// Beat 7 - combat.
		if (age >= WorldInterfaceSummonTimeline.COMBAT && snapshot.resolutionStep() < 7) {
			boolean running = onlineFrozenCount(server, snapshot) > 0;
			WorldInterfaceState.MutationResult transitioned = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> {
						state.transitionTo(WorldInterfaceStage.PHASE_1);
						state.setClock(0L, running ? level.getGameTime() : -1L);
						state.setActionSchedule(0L, 0, 40L);
						state.setResolution(0, -1L);
					});
			if (transitioned.applied()) {
				EndBossArenaService.setAnchorsInvulnerable(level, EndBossArenaService.prepare(level), false);
				if (boss != null) {
					boss.clearAction();
					boss.setNoGravity(true);
				}
				AudioService.playBounded(level, snapshot.arenaCenter(),
						ModSounds.WORLD_INTERFACE_COMBAT_START, SoundSource.HOSTILE, 1.0F, 1.0F);
				broadcast(server, TerminalNoticePayload.TONE_ENCOUNTER,
						"message.thefourthfrequency.world_interface.combat_started");
				sendEncounterSnapshots(server, true);
			}
		}
	}

	/** Where the body starts its fall, in blocks above the arena floor. */
	private static final double SUMMON_DESCENT_START_HEIGHT = 120.0D;
	/**
	 * Blocks above the first form's combat station that the arrival actually stops at.
	 *
	 * <p>The descent used to land exactly on the station, which meant the ceremony ended with the
	 * body already at the height it would spend the whole first phase at - the arrival and the fight
	 * were the same picture, and the last thing the thirteen-second entrance did was nothing. It now
	 * halts high and holds there; the chase in {@link #tickBossEntity} brings it down over the first
	 * few seconds of combat, so the storm visibly comes to meet the players rather than being found
	 * already in position.
	 */
	private static final double SUMMON_ARRIVAL_LIFT = 12.0D;

	private static WorldInterfaceState.Snapshot claimSummonStep(MinecraftServer server,
			WorldInterfaceState.Snapshot snapshot, int step) {
		long started = snapshot.resolutionTick();
		WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
				snapshot.encounterId().orElseThrow(), snapshot.revision(),
				state -> state.setResolution(step, started));
		return result.applied() ? result.snapshot() : snapshot;
	}

	/**
	 * Drives the body down from its entry altitude to its combat station.
	 *
	 * <p>Sits next to {@link #driveMorphFlight} and behaves the same way: it owns the boss's
	 * position for the ticks it covers. Eased rather than linear - the descent slows as it arrives,
	 * so the last few blocks take as long as the first thirty and the body settles instead of
	 * stopping dead.
	 */
	private static void driveSummonDescent(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long age) {
		float progress = WorldInterfaceSummonTimeline.descentProgress(age);
		double station = WorldInterfaceAnatomy.combatHoverHeight(WorldInterfaceEntity.FORM_LISTENING)
				+ SUMMON_ARRIVAL_LIFT;
		double height = Mth.lerp(progress, SUMMON_DESCENT_START_HEIGHT, station);
		Vec3 centre = snapshot.arenaCenter().getCenter();
		boss.snapTo(new Vec3(centre.x, snapshot.arenaCenter().getY() + height, centre.z),
				boss.getYRot(), boss.getXRot());
		boss.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * One anchor firing a column skyward, pitched by its place in the chain.
	 *
	 * <p>Ten of these in sequence is what turns the arena itself into part of the arrival: the
	 * structures the player spent the ritual building are visibly conscripted, in order.
	 */
	private static void emitAnchorIgnition(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			int index) {
		var anchors = snapshot.anchors();
		if (anchors.isEmpty()) return;
		WorldInterfaceState.Anchor anchor = anchors.get(Math.clamp(index, 0, anchors.size() - 1));
		BlockPos pos = anchor.position();
		// Rising semitone-ish per step, so the chain reads as a countdown rather than as ten copies.
		float pitch = 0.72F + index * 0.06F;
		AudioService.playBounded(level, pos, ModSounds.WORLD_INTERFACE_ANCHOR,
				SoundSource.HOSTILE, 0.9F, pitch);
		level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5D, pos.getY() + 1.0D,
				pos.getZ() + 0.5D, 80, 0.3D, 6.0D, 0.3D, 0.42D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5D, pos.getY() + 1.0D,
				pos.getZ() + 0.5D, 60, 0.4D, 4.0D, 0.4D, 0.3D);
	}

	private static void tickCombat(ServerLevel level, WorldInterfaceState.Snapshot initial) {
		WorldInterfaceState.Snapshot snapshot = reconcileClock(level, initial);
		long elapsed = effectiveActiveTicks(snapshot, level.getGameTime());
		if (WorldInterfacePolicy.resolveTick(elapsed, false)
				== WorldInterfacePolicy.TickVerdict.FAILURE) {
			lockFailure(level, snapshot);
			return;
		}
		WorldInterfaceEntity boss = ensureBoss(level, snapshot);
		if (boss == null) return;
		boolean running = snapshot.runningSinceGameTime() >= 0L;
		if (running && snapshot.recoveryGraceTicks() > 0) {
			int graceBefore = snapshot.recoveryGraceTicks();
			WorldInterfaceState.MutationResult grace = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state ->
						state.setRecoveryGraceTicks(graceBefore - 1));
			if (grace.applied()) snapshot = grace.snapshot();
		}
		if (running && level.getServer().getTickCount() % HEAL_INTERVAL_TICKS == 0
				&& snapshot.virtualHealth() > 0.0D && snapshot.aliveAnchorCount() > 0) {
			healBoss(level, snapshot);
			snapshot = WorldInterfaceState.snapshot(level.getServer());
		}
		if (level.getServer().getTickCount() % HEAL_INTERVAL_TICKS == 0) {
			persistTerrainBudget(level, snapshot);
		}
		// Runs whether or not the attack lane does. A boss holding still through a recovery grace or
		// between two scheduled actions is still the thing the arena is about, and until now it was
		// the thing the arena said nothing about: every particle the encounter owned belonged to an
		// attack, so between attacks the interface was a silent shape hanging in a clean sky.
		emitBossPresence(level, boss, level.getGameTime(), snapshot.arenaCenter().getY() + 1.0D);
		// The concrete nine-action executor is integrated below this invariant gate.
		if (running && snapshot.recoveryGraceTicks() == 0) tickAttacks(level, boss, snapshot, elapsed);
		reportCueRate(level, elapsed);
	}

	/**
	 * How often the body's own storm is emitted. Every tick.
	 *
	 * <p>Was every other tick, on a particle budget. The budget was lifted deliberately - see
	 * {@link WorldInterfaceVfx} - and this is the emitter it mattered most for: the rings below are
	 * structure rather than a cloud, and structure sampled on alternate ticks is one that stutters.
	 */
	private static final long PRESENCE_INTERVAL_TICKS = 1L;
	/** How often it sheds, which is slower and heavier than the rest. */
	private static final long PRESENCE_SHED_INTERVAL_TICKS = 6L;
	/** How often the heads arc. Second and third form only; the first has nothing to arc with. */
	private static final long PRESENCE_ARC_INTERVAL_TICKS = 4L;
	/** Points on the core's corona per emission. */
	private static final int PRESENCE_CORONA_SAMPLES = 3;
	/** Rings in the gyroscope around the core, and points on each of them. */
	private static final int PRESENCE_GYRO_RINGS = 3;
	private static final int PRESENCE_GYRO_POINTS = 14;
	/** Ticks between two shell pulses at full health; it quickens as the pool drains. */
	private static final long PRESENCE_PULSE_TICKS = 34L;
	private static final int PRESENCE_PULSE_POINTS = 70;
	/** How often the footprint on the floor beneath the body is redrawn, and how fine it is. */
	private static final long PRESENCE_SHADOW_INTERVAL_TICKS = 3L;
	private static final int PRESENCE_SHADOW_POINTS = 26;
	/** Segments in one head-to-core bolt. Enough to bend, few enough to stay a bolt. */
	private static final int PRESENCE_ARC_SEGMENTS = 9;

	/**
	 * The interface's own weather: what the body does when it is not doing anything.
	 *
	 * <p>Deterministic on the world clock, with no randomness and no state, so every client is shown
	 * the same storm and a restart resumes it rather than restarting it. Nothing here is
	 * authoritative and losing all of it changes no outcome.</p>
	 *
	 * <p>It gets worse as the fight does, and that is the only information in it. The corona tightens
	 * and the shed thickens as the virtual pool drains, so a table that has taken two thirds of the
	 * health off can see that in the sky as well as on the bar - which matters most in the stretch
	 * where the bar is the only thing saying the fight is being won.</p>
	 *
	 * <p>Budgeted rather than generous. Averaged over its three intervals this is about three
	 * emissions a tick, which is where the attack lane's own quieter moments sit; the fight already
	 * has a measured audio channel problem and the particle channel is the same kind of shared,
	 * silently-dropping resource. Everything diffuse is one boxed emission with a count rather than
	 * a loop, and only the corona - which has to be a ring, and a ring is positions - costs a packet
	 * per point.</p>
	 */
	private static void emitBossPresence(ServerLevel level, WorldInterfaceEntity boss, long gameTime,
			double groundY) {
		if (gameTime % PRESENCE_INTERVAL_TICKS != 0L) return;
		int form = boss.form();
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		double coreRadius = WorldInterfaceAnatomy.coreRadius(form);
		double massRadius = WorldInterfaceAnatomy.massRadius(form);
		// 0 at full health, 1 at the point of death. Everything below reads this and nothing else.
		float strain = Math.clamp(1.0F - boss.healthFraction(), 0.0F, 1.0F);
		double spin = gameTime * 0.09D;

		// The corona: a ring turning around the core, tightening onto it as the pool drains.
		double coronaRadius = coreRadius * (1.45D - strain * 0.35D);
		for (int index = 0; index < PRESENCE_CORONA_SAMPLES; index++) {
			double angle = spin + Math.PI * 2.0D * index / PRESENCE_CORONA_SAMPLES;
			ArenaParticles.emit(level, ParticleTypes.END_ROD,
					core.x + Math.cos(angle) * coronaRadius,
					core.y + Math.sin(angle * 1.6D + spin) * coreRadius * 0.45D,
					core.z + Math.sin(angle) * coronaRadius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}

		// Gyroscope rings: three of them, on axes that do not share a plane and that precess at
		// their own rates, counter-rotating in pairs. This is the single change that makes the body
		// read as machinery rather than as a lit shape - a cloud has no axis, and a thing with three
		// visible axes is oriented, therefore aimed, therefore about to do something.
		for (int layer = 0; layer < PRESENCE_GYRO_RINGS; layer++) {
			double precession = gameTime * (0.011D + layer * 0.004D) + layer * (Math.PI / 3.0D);
			Vec3 axis = new Vec3(Math.sin(precession),
					Math.cos(precession * 0.63D) * 0.85D, Math.cos(precession));
			double radius = coreRadius * (1.5D + layer * 0.6D) + massRadius * 0.15D * strain;
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), core, axis, radius,
					PRESENCE_GYRO_POINTS + form * 4,
					(layer % 2 == 0 ? spin : -spin) * (1.0D + layer * 0.35D), 0.0D);
		}

		// The core breathing: a hollow shell thrown outward on a slow pulse, quicker as it fails.
		// A shell rather than a burst, so what leaves reads as a surface and not as smoke.
		long pulseInterval = Math.max(10L, Math.round(PRESENCE_PULSE_TICKS * (1.0D - strain * 0.55D)));
		if (gameTime % pulseInterval == 0L) {
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.core(), core, coreRadius * 1.05D,
					PRESENCE_PULSE_POINTS + form * 30, 0.30D + strain * 0.45D);
			ArenaParticles.emit(level, WorldInterfaceVfx.flash(), core.x, core.y, core.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}

		// Where it is standing, written on the floor it is standing over. The interface hovers, and
		// a hovering thing has no footprint - so eight players on a hundred-and-sixty block island
		// had nothing on the ground telling them where it was. Two counter-rotating rings and a set
		// of spokes: the rings say how wide, the spokes say where the middle is.
		if (gameTime % PRESENCE_SHADOW_INTERVAL_TICKS == 0L && core.y - groundY > 1.0D) {
			// Deliberately NOT the sigil circle the attacks use.
			//
			// This was a rune circle for one build and it was a mistake: the lance's ground mark is
			// also a rune circle, so the arena floor ended up with two violet figures that meant
			// opposite things - one saying "it is standing over you" and one saying "this is about
			// to be hit" - and a player had to work out which was which while running.
			//
			// The footprint is now a different language on purpose. Straight edges instead of a
			// circle, white instead of violet, and no runes at all: a hexagon reads as a marker at a
			// glance and cannot be confused with any of the telegraphs, all of which are round. It
			// grows with the body, so it also says which form is overhead.
			Vec3 footprint = new Vec3(core.x, groundY + 0.15D, core.z);
			WorldInterfaceVfx.polygon(level, ParticleTypes.END_ROD, footprint, massRadius * 0.95D,
					6, 5 + form, spin * 0.35D);
			WorldInterfaceVfx.polygon(level, ParticleTypes.END_ROD, footprint, massRadius * 0.5D,
					6, 4, -spin * 0.5D);
			// A short cross through the middle, so the exact point it is hanging over is stated
			// rather than left to the centre of a shape.
			WorldInterfaceVfx.spokes(level, ParticleTypes.END_ROD, footprint, massRadius * 0.22D,
					4, 3, spin * 0.35D, 0.0D);
		}
		// The mass's own field, boxed over the whole silhouette so a bigger form is visibly a bigger
		// disturbance rather than the same cloud around a bigger shape.
		ArenaParticles.emit(level, ParticleTypes.REVERSE_PORTAL, core.x, core.y, core.z,
				3 + form, massRadius * 0.8D, massRadius * 0.35D, massRadius * 0.8D, 0.02D);

		// What it is shedding. Falls out from under the body, and there is more of it the less body
		// there is left - the same image the death ascent is built on, arriving early and quietly.
		if (gameTime % PRESENCE_SHED_INTERVAL_TICKS == 0L) {
			int ash = 2 + Math.round(strain * 7.0F) + form;
			ArenaParticles.emit(level, ParticleTypes.ASH,
					core.x, core.y - massRadius * 0.5D, core.z,
					ash, massRadius * 0.7D, massRadius * 0.25D, massRadius * 0.7D, 0.01D);
			if (strain > 0.5F) {
				ArenaParticles.emit(level, ParticleTypes.LARGE_SMOKE,
						core.x, core.y - massRadius * 0.7D, core.z,
						1 + Math.round((strain - 0.5F) * 8.0F),
						massRadius * 0.6D, massRadius * 0.2D, massRadius * 0.6D, 0.01D);
			}
		}

		// The heads. Only from the second form on: the first is the listening embryo, and giving it
		// the same discharge as the thing it grows into would spend the escalation before it starts.
		if (form <= WorldInterfaceEntity.FORM_LISTENING
				|| gameTime % PRESENCE_ARC_INTERVAL_TICKS != 0L) return;
		for (int index = 0; index < WorldInterfaceAnatomy.headCount(form); index++) {
			Vec3 head = boss.position().add(
					WorldInterfaceAnatomy.rotate(WorldInterfaceAnatomy.headOffset(form, index),
							boss.yBodyRot));
			double headRadius = WorldInterfaceAnatomy.headRadius(form, index);
			ArenaParticles.emit(level, ParticleTypes.ELECTRIC_SPARK, head.x, head.y, head.z,
					1 + Math.round(strain * 3.0F), headRadius * 0.8D, headRadius * 0.8D,
					headRadius * 0.8D, 0.08D);
			// And the head wired back to the core it grew off. Sparks around a head say the head is
			// charged; a bolt between head and core says where the charge comes from, which is the
			// only version of this that tells the player anything about the body.
			WorldInterfaceVfx.arc(level, ParticleTypes.ELECTRIC_SPARK, core, head,
					PRESENCE_ARC_SEGMENTS, headRadius * (0.5D + strain * 0.7D),
					gameTime * 31L + index * 977L);
			// A collar around the head itself, so a head is a socket rather than a lump.
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), head,
					head.subtract(core), headRadius * 1.15D, 8, spin * 1.7D + index, 0.0D);
		}
	}

	/** How often the encounter says how many cues it has emitted. Thirty seconds. */
	private static final int CUE_RATE_INTERVAL_TICKS = 600;

	/**
	 * Says how many authored cues the server actually handed to the broadcast, every thirty seconds.
	 *
	 * <p>The other half of the client-side channel watchdog, and the pair is the point: a fight that
	 * has gone quiet is either one the server stopped scoring or one the client stopped playing, and
	 * those two have nothing in common except how they sound. Neither side can tell them apart alone.
	 * Read together - "the server emitted forty cues in the last thirty seconds" against "the client
	 * is holding one channel" - they do.
	 */
	private static void reportCueRate(ServerLevel level, long elapsed) {
		if (elapsed <= 0L || elapsed % CUE_RATE_INTERVAL_TICKS != 0L) return;
		TheFourthFrequency.LOGGER.info("World-interface emitted {} audio cues in the last {} ticks: {}",
				AudioService.takeBoundedCueCount(), CUE_RATE_INTERVAL_TICKS,
				AudioService.takeBoundedCueBreakdown());
	}

	private static final int FAILURE_EROSION_RADIUS = WorldInterfacePolicy.EROSION_RADIUS_BLOCKS;
	private static final int FAILURE_EROSION_DEPTH = WorldInterfacePolicy.EROSION_DEPTH;
	/**
	 * Sweep rate and ceiling.
	 *
	 * <p>A ninety-six block disc is about twenty-nine thousand columns, so the sweep has to move an
	 * order of magnitude faster than it did over forty or it will not finish inside the resolution
	 * at all. The block ceiling rises with it: at four thousand the commit ran out partway through
	 * and left a small patch of damage in the middle of a clean island, which is the exact failure
	 * this path exists to avoid.</p>
	 */
	/**
	 * Columns examined per tick, and the ceiling on committed blocks.
	 *
	 * <p>The commit runs across the whole fight now rather than being crammed into the resolution,
	 * which is the only way a hundred-and-sixty block disc is affordable at all: the same quarter of
	 * a million blocks that would need sixteen hundred writes a tick over the resolution needs about
	 * forty across the ten minutes of combat the collapse clock allows. The sweep loops - each pass
	 * re-examines columns it has
	 * already walked, because the threshold rises with the collapse timer and ground that survived
	 * one pass is eligible on the next.</p>
	 */
	private static final int FAILURE_EROSION_COLUMNS_PER_TICK = 160;
	private static final int FAILURE_EROSION_MAX_BLOCKS = 300_000;
	/** Columns healed per tick when a win reverses the erosion; deliberately far faster. */
	private static final int EROSION_HEAL_COLUMNS_PER_TICK = 900;
	/** Sweep cursor and spend, per encounter. Transient: a restart simply resumes from the start. */
	private static final Map<UUID, int[]> FAILURE_EROSION_PROGRESS = new ConcurrentHashMap<>();
	private static final Map<UUID, int[]> EROSION_HEAL_PROGRESS = new ConcurrentHashMap<>();

	/**
	 * Writes the failure erosion into the world for real.
	 *
	 * <p>The erosion was render-only: every missing-texture block a losing table watched spread
	 * across the island existed solely in that client's chunk meshes, and the moment the encounter
	 * cleared, the End snapped back to being pristine. Losing left no mark, which is the opposite of
	 * what losing to this thing is supposed to mean.</p>
	 *
	 * <p>Committed on the same threshold the client renders from, so the blocks that turn permanent
	 * are exactly the blocks that were already shown as gone - the world does not rearrange itself
	 * at the moment of failure. Bounded three ways: a forty-block radius, four surface layers per
	 * column, and a hard ceiling on total blocks; and it never touches anything
	 * {@link EndBossArenaService#canDestroy} protects, so bedrock, the altar, the exit and the
	 * anchors survive intact.</p>
	 */
	private static void commitErosion(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (encounterId == null) return;
		long elapsed = effectiveActiveTicks(snapshot, level.getGameTime());
		// What the collapse timer alone had already eaten by the time the fight ended.
		//
		// A won encounter returns its presentation erosion to zero on the spot, so reading the
		// live progress here would commit nothing at all on a win - and the damage a full collapse
		// clock did to the island would evaporate the instant it was survived. The island wears
		// what the clock did to it either way; only a loss goes on past that to the full wash.
		float failureProgress = WorldInterfacePolicy.presentationErosionProgress(snapshot.stage(),
				elapsed, snapshot.resolutionTick(), level.getGameTime(), RESOLUTION_DURATION_TICKS);
		if (failureProgress <= 0.0F) return;
		int[] progress = FAILURE_EROSION_PROGRESS.computeIfAbsent(encounterId, ignored -> new int[2]);
		int span = FAILURE_EROSION_RADIUS * 2 + 1;
		if (progress[1] >= FAILURE_EROSION_MAX_BLOCKS) return;
		// The sweep wraps instead of stopping: the threshold climbs with the collapse timer, so a
		// column that was still intact on the last pass may not be on the next one.
		if (progress[0] >= span * span) progress[0] = 0;
		BlockPos center = snapshot.arenaCenter();
		BlockState proxy = ModBlocks.MISSING_TEXTURE_PROXY.defaultBlockState();
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		int examined = 0;
		while (examined < FAILURE_EROSION_COLUMNS_PER_TICK && progress[0] < span * span
				&& progress[1] < FAILURE_EROSION_MAX_BLOCKS) {
			int index = progress[0]++;
			examined++;
			int dx = index % span - FAILURE_EROSION_RADIUS;
			int dz = index / span - FAILURE_EROSION_RADIUS;
			if (dx * dx + dz * dz > FAILURE_EROSION_RADIUS * FAILURE_EROSION_RADIUS) continue;
			int x = center.getX() + dx;
			int z = center.getZ() + dz;
			if (!level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) continue;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			for (int depth = 0; depth < FAILURE_EROSION_DEPTH; depth++) {
				BlockPos position = new BlockPos(x, surface - depth, z);
				if (position.getY() <= level.getMinY()) break;
				// End stone only, and that is what makes a win able to undo this.
				//
				// Recording the original state of a quarter of a million positions so they could be
				// put back costs megabytes and does not survive a restart. Restricting the erosion
				// to the one block the island is actually made of means the reversal needs no record
				// at all: every proxy inside the disc was end stone, so healing is a single
				// substitution. The obsidian pillars keep their own material and stand out against
				// the corrupted ground rather than dissolving into it.
				BlockState state = level.getBlockState(position);
				if (!state.is(Blocks.END_STONE)) continue;
				if (!WorldInterfacePolicy.erodesAt(encounterId, position.asLong(),
						failureProgress)) continue;
				if (!EndBossArenaService.canDestroy(level, position, state)) continue;
				level.setBlock(position, proxy, flags);
				progress[1]++;
			}
		}
	}

	/**
	 * The elapsed count as the wire should carry it: unchanged during the fight, running backwards
	 * once a won encounter starts repairing itself.
	 *
	 * <p>Only the projection moves. The authority is what the encounter was decided on.</p>
	 */
	private static long repairProjectedElapsedTicks(WorldInterfaceState.Snapshot snapshot, long gameTime) {
		long elapsed = effectiveActiveTicks(snapshot, gameTime);
		if (!WorldInterfacePolicy.repairsCollapseReadout(snapshot.stage(),
				FinaleRuntimePolicy.succeeded(snapshot))) return elapsed;
		return WorldInterfacePolicy.repairedElapsedTicks(elapsed,
				gameTime - snapshot.resolutionTick());
	}

	/**
	 * Runs the erosion backwards after a win.
	 *
	 * <p>A losing table keeps the island the countdown left them. A winning one gets it back - but
	 * not by having it blink: the heal sweeps outward from the altar as a front, so cutting the
	 * interface visibly restores the world's materials.</p>
	 *
	 * <p>Paced by {@link WorldInterfacePolicy#repairFraction} rather than by a fixed columns-per-tick
	 * rate, because it is no longer the only thing describing the repair. The collapse rail unwinds
	 * and the material erosion lifts off the same fraction, and a sweep running at its own speed
	 * would finish while the readout still claimed there was work left - or the reverse. It is also
	 * much slower than it was: at nine hundred columns a tick the whole disc healed in under six
	 * seconds, which is less time than it takes to notice the ground changing.</p>
	 *
	 * <p>Every proxy inside the disc was end stone before the erosion touched it, so this needs no
	 * record of what it is undoing.</p>
	 */
	private static void healErosion(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (encounterId == null) return;
		int[] progress = EROSION_HEAL_PROGRESS.computeIfAbsent(encounterId, ignored -> new int[1]);
		int span = FAILURE_EROSION_RADIUS * 2 + 1;
		if (progress[0] >= span * span) return;
		// How far down the column list the repair fraction says we should be by now. The ceiling is
		// what paces the sweep; the per-tick cap below still bounds a single tick's work, so a long
		// pause or a restart catches up over several ticks instead of in one enormous batch.
		long target = Math.round(WorldInterfacePolicy.repairFraction(
				level.getGameTime() - snapshot.resolutionTick()) * span * span);
		if (progress[0] >= target) return;
		BlockPos center = snapshot.arenaCenter();
		BlockState endStone = Blocks.END_STONE.defaultBlockState();
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		int examined = 0;
		while (examined < EROSION_HEAL_COLUMNS_PER_TICK && progress[0] < target) {
			int index = progress[0]++;
			examined++;
			int dx = index % span - FAILURE_EROSION_RADIUS;
			int dz = index / span - FAILURE_EROSION_RADIUS;
			if (dx * dx + dz * dz > FAILURE_EROSION_RADIUS * FAILURE_EROSION_RADIUS) continue;
			int x = center.getX() + dx;
			int z = center.getZ() + dz;
			if (!level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) continue;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			for (int depth = 0; depth < FAILURE_EROSION_DEPTH; depth++) {
				BlockPos position = new BlockPos(x, surface - depth, z);
				if (position.getY() <= level.getMinY()) break;
				if (!level.getBlockState(position).is(ModBlocks.MISSING_TEXTURE_PROXY)) continue;
				level.setBlock(position, endStone, flags);
			}
		}
	}

	private static void tickResolution(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		if (snapshot.stage() == WorldInterfaceStage.FAILURE_RESOLUTION) commitErosion(level, snapshot);
		else healErosion(level, snapshot);
		WorldInterfaceEntity boss = ensureBoss(level, snapshot);
		if (boss != null) {
			boss.setForm(WorldInterfaceEntity.FORM_INTERFACE);
			// Neither resolution stands still any more: a loss climbs out of the world under
			// driveFailureEscape and a win climbs under driveDeathAscent. Both live on the entity's
			// own step, and pinning the body here would fight them from a second tick source with
			// the winner decided by registration order.
		}
		long started = snapshot.resolutionTick() < 0L ? level.getGameTime() : snapshot.resolutionTick();
		if (snapshot.resolutionTick() < 0L) {
			WorldInterfaceState.MutationResult repaired = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> state.setResolution(0, started));
			if (!repaired.applied()) return;
			snapshot = repaired.snapshot();
		}
		synchronizeResolutionAction(level, snapshot, boss, false);
		long age = level.getGameTime() - started;
		boolean success = snapshot.outcome() == WorldInterfaceState.Outcome.SUCCESS;
		if (age >= ANCHORS_SKYWARD_TICKS && snapshot.resolutionStep() < 1) {
			if (success) pointLivingAnchorsSkyward(level, snapshot);
			WorldInterfaceState.MutationResult step = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> state.setResolution(1, started));
			if (step.applied()) snapshot = step.snapshot();
		}
		// The death itself, for exactly as long as the collapse and fade clips are running.
		if (success && boss != null && age < BOSS_REMOVAL_TICKS) {
			emitBossDeath(level, boss, age);
		}
		// The body goes before anything replaces it. Discarding the projection here rather than at
		// the end of the window is the whole reordering: the collapse and fade clips have finished
		// by now, so what the players watch is the thing they killed stopping, and then an empty sky.
		if (success && age >= BOSS_REMOVAL_TICKS && snapshot.resolutionStep() < 2) {
			// Read before the mutation: the projection is discarded inside the branch below, and the
			// departure has to be thrown from where the body actually was rather than from the altar.
			Vec3 departure = boss == null ? null : WorldInterfaceAnatomy.coreOrigin(boss);
			double shell = boss == null ? 0.0D : WorldInterfaceAnatomy.massRadius(boss.form());
			WorldInterfaceState.MutationResult step = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> {
						state.clearBossUuid();
						state.setResolution(2, started);
					});
			if (step.applied()) {
				snapshot = step.snapshot();
				removeBossProjection(level, snapshot);
				if (departure != null) emitBossDeparture(level, departure, shell);
				sendEncounterSnapshots(level.getServer(), true);
			}
		}
		if (!success && age >= 80L && snapshot.resolutionStep() < 2) {
			WorldInterfaceState.MutationResult step = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> state.setResolution(2, started));
			if (step.applied()) snapshot = step.snapshot();
		}
		// The sky is opened before anything comes through it. Deliberately not a resolution step of
		// its own: it writes no state, so a restart part-way through simply resumes the call wherever
		// the resolution clock has got to, and a client that joins late picks it up mid-flight.
		if (success && age >= DRAGON_SUMMON_TICKS && age < DRAGON_SPAWN_TICKS) {
			emitDragonSummon(level, snapshot, age);
		}
		if (success && age >= DRAGON_SPAWN_TICKS && snapshot.resolutionStep() < 3) {
			UUID dragonId = snapshot.friendlyDragonUuid().orElse(deterministicEntityUuid(
					"friendly_dragon", snapshot.encounterId().orElseThrow()));
			WorldInterfaceState.MutationResult step = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> {
						state.setFriendlyDragonUuid(dragonId);
						state.setResolution(3, started);
					});
			if (step.applied()) {
				snapshot = step.snapshot();
				// Spawn only. Speaking on the same tick puts the words on screen before the dragon
				// has been sent to a single client, so the line reads as coming from nothing.
				EnderDragon dragon = FriendlyDragonService.spawn(level, snapshot.arenaCenter(), dragonId);
				// The arrival burst is placed on the body rather than on the arena centre: the orbit
				// phase is seeded from the dragon's UUID, so where it enters the ring is not knowable
				// until it exists, and a burst anywhere else would be the sky tearing open next to it.
				emitDragonArrival(level, dragon.position());
			}
		}
		if (success && age >= DRAGON_FIRST_LINE_TICKS && snapshot.resolutionStep() < 4) {
			WorldInterfaceState.MutationResult step = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(),
					state -> state.setResolution(4, started));
			if (step.applied()) {
				snapshot = step.snapshot();
				broadcast(level.getServer(), TerminalNoticePayload.TONE_DRAGON,
						"message.thefourthfrequency.world_interface.dragon.unsealed");
			}
		}
		// The exit is opened, not switched on - and it is opened by the dragon, not by the altar.
		// The particles and the sound are emitted along the line between the two, so the way out is
		// visibly something the thing they spared is doing for them.
		if (success && age >= DRAGON_SPAWN_TICKS && age < SUCCESS_PORTAL_TICKS) {
			emitPortalOpening(level, snapshot, age);
		}
		int finalTick = success ? SUCCESS_PORTAL_TICKS : RESOLUTION_DURATION_TICKS;
		if (age >= finalTick && snapshot.resolutionStep() < 6) {
			removeBossProjection(level, snapshot);
			BlockPos exitPosition = snapshot.altarCenter();
			placeExit(level, exitPosition);
			if (success) emitPortalBurst(level, AltarShape.exitPortalCenter(exitPosition));
			WorldInterfaceState.MutationResult opened = WorldInterfaceState.mutate(level.getServer(),
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> {
						state.clearBossUuid();
						state.setExit(exitPosition, true);
						state.setResolution(6, started);
						state.transitionTo(WorldInterfaceStage.PORTAL_OPEN);
					});
			if (opened.applied()) {
				// The second line lands on the tick the exit exists, so "go back" is said to people
				// who can already see the way back.
				if (success) {
					broadcast(level.getServer(), TerminalNoticePayload.TONE_DRAGON,
							"message.thefourthfrequency.world_interface.dragon.return");
				}
				sendEncounterSnapshots(level.getServer(), true);
			}
		}
	}

	/**
	 * How far the dragon has come down onto the altar, in [0, 1].
	 *
	 * <p>Gated on the outcome and on nothing else. It used to be gated on the stage as well - the
	 * descent only counted while the encounter was still in its success resolution - and the exit
	 * opening is the tick that leaves that stage. So on the tick the way out appeared, the approach
	 * fell from one to zero and the dragon was moved from the low working circle to the resting orbit
	 * between two ticks, some sixty blocks in one step. {@link FriendlyDragonService#approach} carries
	 * the whole descent-and-return schedule instead, in the dragon's own age, which no stage
	 * transition can step.</p>
	 */
	private static double dragonApproach(WorldInterfaceState.Snapshot snapshot, long age) {
		if (snapshot.outcome() != WorldInterfaceState.Outcome.SUCCESS) return 0.0D;
		return FriendlyDragonService.approach(age - DRAGON_SPAWN_TICKS, DRAGON_PORTAL_WORK_TICKS,
				FriendlyDragonService.RETURN_TICKS);
	}

	/**
	 * The dragon prising the exit open: a thread drawn from wherever it currently is down to the
	 * altar, and a ring that tightens on the altar as it works.
	 *
	 * <p>The ring alone used to be the whole effect, anchored on the altar and running on its own
	 * clock. That is a portal switching itself on next to a dragon. Sourcing the particles at the
	 * dragon's actual position each tick is what makes it the dragon's doing - and because the
	 * dragon is descending across the same window, the thread shortens as the ring closes.</p>
	 */
	/**
	 * The interface coming apart, across the six seconds its collapse and fade clips are playing.
	 *
	 * <p>The model always had a death animation - the body rotates over onto its side while the eye
	 * and the ring shrink out of existence - but nothing outside the model ever acknowledged it. Two
	 * sounds fired on the tick the virtual pool hit zero, and then a thirty-three block body lay in
	 * the sky in silence until it stopped being there between one frame and the next. For the thing
	 * the entire mod is built toward, the arena said nothing about it dying.</p>
	 *
	 * <p>This is that missing half: light bleeding out of the shell for the whole fall, structural
	 * failures that arrive faster and lower-pitched the further the collapse gets, and - in
	 * {@link #emitBossDeparture} - an actual departure on the tick the body goes, instead of a
	 * disappearance.</p>
	 *
	 * <p>Derived entirely from the resolution clock, with no randomness and no state of its own.
	 * Every client is shown the same death, a restart part-way through resumes it rather than
	 * replaying it, and losing it entirely can never desynchronise the encounter.</p>
	 */
	private static void emitBossDeath(ServerLevel level, WorldInterfaceEntity boss, long age) {
		double progress = Math.clamp(age / (double) BOSS_REMOVAL_TICKS, 0.0D, 1.0D);
		// Presentation only. The ascent itself belongs to driveDeathAscent, on the entity's own step:
		// velocity written from here would land on the wrong side of the entity's move on some ticks
		// and be overwritten on others, and the body climbed in jumps instead of a climb.
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		double shell = WorldInterfaceAnatomy.massRadius(boss.form());
		if (age == 0L) {
			// One hard release, sized off the body rather than off a constant, so it reads as this
			// thing failing rather than as a generic effect played somewhere near it.
			WorldInterfaceShockwaveService.emit(level, core,
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS, shell * 2.5D);
			level.sendParticles(ParticleTypes.END_ROD, core.x, core.y, core.z,
					220, shell * 0.5D, shell * 0.4D, shell * 0.5D, 0.55D);
		}
		emitDeathAsh(level, core, shell, progress);
		emitLimbFailure(level, boss, core, shell, age, progress);
		// The scream, breaking up as it goes: quieter, lower and further apart each time, so the last
		// one is barely the same voice as the first. Started on the tick of death and then answering
		// itself roughly every two seconds for as long as there is a body to scream with.
		if (age % 38L != 0L) return;
		AudioService.playBounded(level, BlockPos.containing(core), SoundEvents.ENDER_DRAGON_GROWL,
				SoundSource.HOSTILE, (float) (1.0D - 0.45D * progress),
				(float) (ROAR_PITCH_BY_FORM[ROAR_PITCH_BY_FORM.length - 1] - 0.2D * progress));
	}

	/**
	 * What the body turns into on the way up: ash, and more of it the less body there is left.
	 *
	 * <p>The ash falls while the interface rises, which is the whole image - the thing climbs and
	 * what it is made of does not go with it.</p>
	 */
	private static void emitDeathAsh(ServerLevel level, Vec3 core, double shell, double progress) {
		int ash = 6 + (int) Math.round(30.0D * progress);
		level.sendParticles(ParticleTypes.ASH, core.x, core.y, core.z,
				ash, shell * 0.8D, shell * 0.55D, shell * 0.8D, 0.015D);
		level.sendParticles(ParticleTypes.WHITE_ASH, core.x, core.y - shell * 0.35D, core.z,
				ash / 2, shell * 0.7D, shell * 0.45D, shell * 0.7D, 0.012D);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, core.x, core.y - shell * 0.7D, core.z,
				3 + (int) Math.round(9.0D * progress), shell * 0.6D, shell * 0.3D, shell * 0.6D, 0.01D);
		// Light still running out of the shell underneath the ash, wound around the body so the loss
		// has a direction. A symmetric cloud cannot show which way anything is going.
		for (int index = 0; index < 5; index++) {
			double angle = progress * 42.0D + index * (Math.PI * 2.0D / 5.0D);
			double lift = Math.cos(progress * 14.0D + index) * shell * 0.45D;
			level.sendParticles(ParticleTypes.END_ROD,
					core.x + Math.cos(angle) * shell * 0.85D, core.y + lift,
					core.z + Math.sin(angle) * shell * 0.85D, 2, 0.5D, 0.5D, 0.5D, 0.02D);
		}
	}

	/**
	 * The limbs letting go, one at a time.
	 *
	 * <p>Spread across the first two thirds of the window and staggered evenly, which is the same
	 * shape the client's collapse clip releases its tendrils on. They have to agree: the burst is
	 * what makes the drawn limb's departure land, and a burst on a limb that is still attached - or
	 * a limb that comes off in silence - reads as two unrelated things happening nearby.</p>
	 */
	private static void emitLimbFailure(ServerLevel level, WorldInterfaceEntity boss, Vec3 core,
			double shell, long age, double progress) {
		int limbs = WorldInterfaceAnatomy.tentacleCount(boss.form());
		if (limbs <= 0) return;
		long span = Math.round(BOSS_REMOVAL_TICKS * 0.62D);
		int index = (int) Math.round(age * (limbs + 1.0D) / span) - 1;
		if (index < 0 || index >= limbs
				|| age != Math.round((index + 1) * span / (double) (limbs + 1))) return;
		double radius = WorldInterfaceAnatomy.tentacleRadius(boss.form());
		double angle = index * (Math.PI * 2.0D / limbs) + Math.toRadians(boss.yBodyRot);
		Vec3 root = boss.position().add(Math.cos(angle) * radius,
				WorldInterfaceAnatomy.tentacleRootLift(boss.form()), Math.sin(angle) * radius);
		level.sendParticles(ParticleTypes.EXPLOSION, root.x, root.y, root.z,
				2, shell * 0.1D, shell * 0.1D, shell * 0.1D, 0.0D);
		level.sendParticles(ParticleTypes.END_ROD, root.x, root.y, root.z,
				34, 1.3D, 1.3D, 1.3D, 0.3D);
		level.sendParticles(ParticleTypes.ASH, root.x, root.y, root.z,
				48, 1.6D, 1.6D, 1.6D, 0.06D);
		AudioService.playBounded(level, BlockPos.containing(root), ModSounds.WORLD_INTERFACE_HURT,
				SoundSource.HOSTILE, 0.72F, (float) (1.15D - 0.55D * progress));
	}

	/** The tick the body stops existing. What is left of it finishes going to ash. */
	private static void emitBossDeparture(ServerLevel level, Vec3 core, double shell) {
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, core.x, core.y, core.z,
				3, shell * 0.35D, shell * 0.25D, shell * 0.35D, 0.0D);
		level.sendParticles(ParticleTypes.ASH, core.x, core.y, core.z,
				400, shell * 0.8D, shell * 0.6D, shell * 0.8D, 0.08D);
		level.sendParticles(ParticleTypes.WHITE_ASH, core.x, core.y, core.z,
				260, shell * 0.7D, shell * 0.55D, shell * 0.7D, 0.06D);
		level.sendParticles(ParticleTypes.END_ROD, core.x, core.y, core.z,
				240, shell * 0.6D, shell * 0.5D, shell * 0.6D, 0.55D);
		WorldInterfaceShockwaveService.emit(level, core,
				WorldInterfaceShockwaveService.MORPH_DURATION_TICKS, shell * 3.0D);
		// The last of the scream, at the bottom of the range and cut off rather than finished.
		AudioService.playBounded(level, BlockPos.containing(core), SoundEvents.ENDER_DRAGON_GROWL,
				SoundSource.HOSTILE, 0.5F, 0.5F);
		// The sound the body used for leaving the field between forms, dropped as low as it goes:
		// the same departure, except this time it does not come back.
		AudioService.playBounded(level, BlockPos.containing(core), ModSounds.WORLD_INTERFACE_MORPH,
				SoundSource.HOSTILE, 1.0F, 0.5F);
	}

	/**
	 * The call that brings the dragon in, drawn as its own orbit being traced in light.
	 *
	 * <p>The shape is chosen to be readable from the altar without anyone being told where to look:
	 * a single bright point runs the full seventy-two block circle the dragon is about to fly, at
	 * the height it is about to fly it, leaving a trail that fills in behind it. By the time the
	 * point closes the loop the whole flight path is lit, and the dragon enters along it.</p>
	 *
	 * <p>Pure presentation - no authoritative state, no persistence. Every value is derived from the
	 * resolution clock, so this is safe to re-enter on any tick and safe to lose entirely.</p>
	 */
	private static void emitDragonSummon(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			long age) {
		BlockPos arena = snapshot.arenaCenter();
		long elapsed = age - DRAGON_SUMMON_TICKS;
		double progress = Math.clamp(elapsed / (double) DRAGON_SUMMON_DURATION_TICKS, 0.0D, 1.0D);
		double centreX = arena.getX() + 0.5D;
		double centreZ = arena.getZ() + 0.5D;
		double ringY = arena.getY() + FriendlyDragonService.ORBIT_HEIGHT;
		if (elapsed == 0L) {
			// A whole octave under the boss's own summon. The same instrument answering in a lower
			// register is what makes this read as a reply to the fight rather than a repeat of it.
			AudioService.playBounded(level, arena, ModSounds.WORLD_INTERFACE_SUMMON,
					SoundSource.AMBIENT, 0.95F, 0.4F);
			WorldInterfaceShockwaveService.emit(level, new Vec3(centreX, ringY, centreZ),
					WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
					FriendlyDragonService.ORBIT_RADIUS);
		}
		// The leading point, and the arc it has already drawn. Sampling the trail across the whole
		// swept arc rather than emitting one particle per tick and letting it expire means the ring
		// is equally bright along its length however far round the point has got.
		double head = progress * Math.PI * 2.0D;
		for (int index = 0; index < 5; index++) {
			double angle = head - index * 0.035D;
			level.sendParticles(ParticleTypes.END_ROD,
					centreX + Math.cos(angle) * FriendlyDragonService.ORBIT_RADIUS, ringY,
					centreZ + Math.sin(angle) * FriendlyDragonService.ORBIT_RADIUS,
					2, 0.35D, 0.35D, 0.35D, 0.0D);
		}
		for (int index = 0; index < 10; index++) {
			double angle = head * ((index + (elapsed % 5L) * 0.2D) / 10.0D);
			level.sendParticles(ParticleTypes.END_ROD,
					centreX + Math.cos(angle) * FriendlyDragonService.ORBIT_RADIUS, ringY,
					centreZ + Math.sin(angle) * FriendlyDragonService.ORBIT_RADIUS,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		// The column feeding it, rising out of the altar the players are standing on so the call has
		// a visible source and is not simply weather.
		for (int index = 0; index < 4; index++) {
			double height = (index + (elapsed % 8L) * 0.125D) / 4.0D * FriendlyDragonService.ORBIT_HEIGHT;
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, centreX,
					arena.getY() + 1.0D + height * progress, centreZ, 2, 0.6D, 0.4D, 0.6D, 0.02D);
		}
		// Pulses that close up as the point comes round: twenty ticks apart at the start, five at the
		// end, so the last few seconds audibly run out of room before the arrival lands on them.
		long interval = Math.max(5L, Math.round(20.0D - 15.0D * progress));
		if (elapsed % interval == 0L) {
			AudioService.playBounded(level, arena, ModSounds.WORLD_INTERFACE_GATEWAY_PURPLE,
					SoundSource.AMBIENT, 0.55F, (float) (0.6D + 0.8D * progress));
		}
	}

	/**
	 * The dragon arriving: a seal coming apart, not a door opening.
	 *
	 * <p>The fiction is that the interface had it held under this sky, and the fight is what took the
	 * lock apart - so the burst is built to read as something breaking outward from the body rather
	 * than something arriving at it. The flash and the ruptured shell land on the same tick, the
	 * spokes are thrown along sixteen fixed bearings so the shell has visible seams, and the vanilla
	 * growl is what a player already reads as "the dragon is here".
	 *
	 * <p>Every count here is a one-off on a single tick, not a per-tick emitter. That is what keeps a
	 * burst this size inside the tick budget the rest of the encounter is written to.
	 */
	private static void emitDragonArrival(ServerLevel level, Vec3 position) {
		// The break itself. FLASH is a single bright frame with no lifetime, so it reads as the
		// instant rather than as an effect that is still running afterwards. It is tinted rather than
		// plain white: FLASH carries a colour in this version, and the End's own violet keeps the
		// break inside the palette the rest of the arena is lit in.
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0.72F, 0.44F, 0.95F),
				position.x, position.y, position.z, 4, 0.6D, 0.6D, 0.6D, 0.0D);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, position.x, position.y, position.z,
				1, 0.0D, 0.0D, 0.0D, 0.0D);

		// The shell it was held in, thrown outward. Sixteen bearings rather than a spherical spray:
		// a sphere of particles is a cloud, and spokes are pieces of something that used to be solid.
		for (int spoke = 0; spoke < 16; spoke++) {
			double angle = spoke / 16.0D * Math.PI * 2.0D;
			double lift = spoke % 2 == 0 ? 0.45D : -0.30D;
			level.sendParticles(ParticleTypes.END_ROD,
					position.x + Math.cos(angle) * 2.0D, position.y + lift,
					position.z + Math.sin(angle) * 2.0D,
					14, 0.25D, 0.25D, 0.25D, 0.62D);
		}

		level.sendParticles(ParticleTypes.END_ROD, position.x, position.y, position.z,
				190, 3.4D, 2.4D, 3.4D, 0.42D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y, position.z,
				220, 4.5D, 3.0D, 4.5D, 0.26D);
		// The dragon's own breath, so the thing that got out is unmistakably this one.
		level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 0.5F),
				position.x, position.y, position.z, 90, 3.0D, 1.6D, 3.0D, 0.08D);

		// Two rings rather than one: the second is wider and lands on the same tick, which reads as
		// the break having a front rather than a single edge.
		WorldInterfaceShockwaveService.emit(level, position,
				WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
				WorldInterfaceShockwaveService.MORPH_MAX_RADIUS);
		WorldInterfaceShockwaveService.emit(level, position.add(0.0D, -2.0D, 0.0D),
				WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
				WorldInterfaceShockwaveService.MORPH_MAX_RADIUS * 1.6D);

		BlockPos at = BlockPos.containing(position);
		AudioService.playBounded(level, at,
				ModSounds.WORLD_INTERFACE_GATEWAY_GOLD, SoundSource.AMBIENT, 1.0F, 0.85F);
		level.playSound(null, at, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 4.0F, 0.72F);
	}

	private static void emitPortalOpening(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			long age) {
		BlockPos portal = AltarShape.exitPortalCenter(snapshot.altarCenter());
		float progress = Math.clamp((age - DRAGON_SPAWN_TICKS)
				/ (float) DRAGON_PORTAL_WORK_TICKS, 0.0F, 1.0F);
		double radius = 0.7D + 6.5D * (1.0D - progress);
		double height = portal.getY() + 0.2D + 3.4D * (1.0D - progress);
		for (int point = 0; point < 14; point++) {
			double angle = point / 14.0D * Math.PI * 2.0D + age * 0.13D;
			level.sendParticles(ParticleTypes.PORTAL,
					portal.getX() + 0.5D + Math.cos(angle) * radius, height,
					portal.getZ() + 0.5D + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		Vec3 source = snapshot.friendlyDragonUuid()
				.flatMap(id -> FriendlyDragonService.recover(level, id))
				.map(dragon -> dragon.position().add(0.0D, -1.0D, 0.0D))
				.orElse(null);
		if (source != null) {
			Vec3 target = new Vec3(portal.getX() + 0.5D, portal.getY() + 0.2D, portal.getZ() + 0.5D);
			Vec3 span = target.subtract(source);
			for (int step = 0; step < 10; step++) {
				double along = (step + (age % 4L) * 0.25D) / 10.0D;
				Vec3 point = source.add(span.scale(along));
				level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z,
						1, 0.10D, 0.10D, 0.10D, 0.0D);
			}
		}
		if (age % 12L == 0L) {
			AudioService.playBounded(level, portal, ModSounds.WORLD_INTERFACE_GATEWAY_PURPLE,
					SoundSource.AMBIENT, 0.5F, 0.7F + 0.5F * progress);
		}
	}

	private static void emitPortalBurst(ServerLevel level, BlockPos exit) {
		level.sendParticles(ParticleTypes.END_ROD, exit.getX() + 0.5D, exit.getY() + 1.0D,
				exit.getZ() + 0.5D, 120, 1.6D, 0.6D, 1.6D, 0.32D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, exit.getX() + 0.5D, exit.getY() + 0.6D,
				exit.getZ() + 0.5D, 160, 2.2D, 1.4D, 2.2D, 0.24D);
		AudioService.playBounded(level, exit, ModSounds.WORLD_INTERFACE_GATEWAY_GOLD,
				SoundSource.AMBIENT, 0.95F, 1.0F);
	}

	private static void tickAttacks(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long elapsed) {
		MinecraftServer server = level.getServer();
		List<ServerPlayer> participants = arenaParticipants(level, snapshot);
		// Nothing is thrown while it is away changing. The phase change already cancels whatever was
		// running; without this the scheduler would simply open the next attack on the following tick
		// and fire it out of an empty sky from a hundred blocks up.
		if (isMorphing(level, boss)) return;
		// Idle roar. Off the encounter clock rather than a timer of its own so it stays in step with
		// everything else the fight schedules, and skipped above while it is away changing.
		int roarInterval = ROAR_INTERVAL_BY_FORM[Math.clamp(boss.form(), 0,
				ROAR_INTERVAL_BY_FORM.length - 1)];
		if (elapsed > 0L && elapsed % roarInterval == 0L) roar(level, boss, 0.85F);
		// The third form's second lane, driven whether or not a scheduled attack is running: it is
		// what makes the last phase continuous instead of a turn order. Ticked before the scheduled
		// lane so a volley opened this tick gets its first frame on this tick.
		if (snapshot.stage() == WorldInterfaceStage.PHASE_3
				&& elapsed % WorldInterfaceActionScheduler.VOLLEY_INTERVAL_TICKS == 0L) {
			WorldInterfaceAttackService.beginVolley(level, boss, snapshot, participants, elapsed,
					snapshot.actionSequence());
		}
		WorldInterfaceAttackService.tickVolley(level, boss, snapshot, elapsed);
		if (snapshot.currentAttack().isPresent()) {
			WorldInterfaceState.AttackEnvelope active = snapshot.currentAttack().orElseThrow();
			WorldInterfaceAttackService.AttackTick tick =
					WorldInterfaceAttackService.tick(level, boss, snapshot, elapsed);
			WorldInterfaceState.Snapshot latest = WorldInterfaceState.snapshot(server);
			if (!latest.stage().isCombat() || latest.encounterId().isEmpty()) return;
			for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
				latest = WorldInterfaceState.snapshot(server);
				WorldInterfaceState.AttackEnvelope stored = latest.currentAttack().orElse(null);
				if (stored == null || stored.sequence() != active.sequence()) return;
				WorldInterfaceState.Snapshot captured = latest;
				WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
						latest.encounterId().orElseThrow(), latest.revision(), state -> {
							switch (tick.status()) {
								case CONTINUE -> state.setCurrentAttack(tick.replacementEnvelope().orElseThrow());
								case COMPLETE -> state.clearCurrentAttack();
								case CANCELLED_RESTART -> {
									state.clearCurrentAttack();
									state.setRecoveryGraceTicks(WorldInterfaceActionScheduler.RESTART_RECOVERY_TICKS);
									state.setActionSchedule(captured.actionSequence(), captured.lastActionWireId(),
											Math.max(captured.nextActionActiveTick(),
													elapsed + WorldInterfaceActionScheduler.RESTART_RECOVERY_TICKS));
								}
							}
						});
				if (result.applied()) {
					if (tick.status() == WorldInterfaceAttackService.AttackStatus.CANCELLED_RESTART) {
						WorldInterfaceAttackService.cancelAndRestore(server, latest.encounterId().orElseThrow());
					}
					return;
				}
				if (!"revision_mismatch".equals(result.reason())) return;
			}
			return;
		}
		// Nothing is stored, so nothing may still be running. A transient runtime that outlives the
		// envelope it was mirrored from is a permanent, silent stall: the scheduler below calls
		// begin(), begin() refuses because the runtime is still in the map, the refusal is an
		// exception, and the exception is caught and dropped - on this tick and on every tick after
		// it. The fight simply stops attacking, with no line in any log to say why. The transient
		// side is presentation and the persisted side is the truth, so the truth wins.
		WorldInterfaceAttackService.discardOrphanedRuntime(server, snapshot.encounterId().orElseThrow());
		reportSchedulerStall(snapshot, elapsed);
		if (elapsed < snapshot.nextActionActiveTick()) return;
		if (participants.isEmpty()) return;

		WorldInterfaceAction previous = WorldInterfaceAction
				.fromWireIdOrEmpty(snapshot.lastActionWireId()).orElse(null);
		long choiceSequence = snapshot.actionSequence();
		WorldInterfaceAction selected = null;
		List<ServerPlayer> targets = List.of();
		// The climb does not stop it attacking; it only changes what it can attack with. See
		// WorldInterfaceActionScheduler#canStartWhileAloft.
		boolean aloft = WorldInterfaceSkyholdPolicy.aloft(snapshot.stage(), elapsed);
		for (int scan = 0; scan < WorldInterfaceAction.values().length * 2; scan++, choiceSequence++) {
			WorldInterfaceAction candidate = WorldInterfaceActionScheduler.nextAction(snapshot.stage(),
					snapshot.deterministicSeed(), choiceSequence, previous);
			if (aloft && !WorldInterfaceActionScheduler.canStartWhileAloft(candidate)) continue;
			if (candidate == WorldInterfaceAction.FORCED_EVICTION
					&& !WorldInterfaceActionScheduler.isForcedEvictionReady(elapsed,
							snapshot.lastForcedEvictionTick(), participants.size())) continue;
			// An action with nobody it may be aimed at has to be skipped like any other ineligible
			// candidate, not chosen and then abandoned.
			//
			// This is the stall players have been reporting as "the boss stops making any noise after
			// a while". The scan used to commit to a candidate and only afterwards ask who it could be
			// aimed at; if the answer was nobody it returned, and because the pick is a pure function
			// of (stage, seed, sequence, previous) - none of which change while no attack starts - the
			// next tick made the identical pick and returned again. The encounter simply stopped
			// attacking until whatever made the target ineligible expired on its own.
			//
			// Three of the six actions unlocked at the second phase take exclusive control, and every
			// one of those puts its target under six hundred ticks of strong-control immunity. On a
			// solo table that is the entire roster, so any pick landing on one of the three inside that
			// window froze the fight for up to thirty seconds - repeatedly, and only from the second
			// phase onwards, because the first phase unlocks no exclusive control at all. That is
			// exactly where it was reported from.
			List<ServerPlayer> candidateTargets = attackTargets(server, snapshot, participants,
					candidate, choiceSequence, elapsed);
			if (candidate != WorldInterfaceAction.TENDRIL_LASH && candidateTargets.isEmpty()) continue;
			selected = candidate;
			targets = candidateTargets;
			break;
		}
		if (selected == null) return;

		WorldInterfaceAttackService.AttackStart started;
		try {
			started = WorldInterfaceAttackService.begin(level, boss, snapshot, selected, targets,
					elapsed, choiceSequence);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			// Logged rather than swallowed. Every reason begin() refuses is a reason the encounter
			// throws nothing this tick, and most of them do not clear themselves - so a bare catch
			// here turns any one of them into a boss that has silently stopped fighting. Whatever
			// this is, it costs one line and it is the only evidence that will exist.
			TheFourthFrequency.LOGGER.warn("World-interface refused to start {} at tick {}: {}",
					selected, elapsed, exception.toString());
			return;
		}

		WorldInterfaceState.Snapshot latest = WorldInterfaceState.snapshot(server);
		// The roster is part of the cadence now: one attack names one player, so a schedule written
		// only in terms of the fight divides itself among however many people are standing in it.
		// See WorldInterfaceActionScheduler#DENSITY_GAIN_PER_PLAYER.
		long nextTick = elapsed + WorldInterfaceActionScheduler.scaledIntervalTicks(latest.stage(),
				latest.deterministicSeed(), choiceSequence, latest.destroyedAnchorCount(),
				Math.clamp(participants.size(), 1, WorldInterfacePolicy.MAX_ROSTER_SIZE));
		WorldInterfaceAction selectedAction = selected;
		long persistedSequence = choiceSequence;
		WorldInterfaceState.Snapshot captured = latest;
		WorldInterfaceState.MutationResult stored = WorldInterfaceState.mutate(server,
				latest.encounterId().orElseThrow(), latest.revision(), state -> {
					if (state.currentAttack().isPresent()) throw new IllegalStateException("attack_already_stored");
					state.setCurrentAttack(started.envelope());
					state.setActionSchedule(persistedSequence + 1L, selectedAction.wireId(), nextTick);
					if (selectedAction.requiresExclusiveControl()) {
						for (Map.Entry<UUID, Long> cooldown : captured.controlCooldowns().entrySet()) {
							if (cooldown.getValue() <= elapsed) {
								state.removeControlCooldown(cooldown.getKey());
							}
						}
						for (UUID target : started.targets()) {
							state.putControlCooldown(target,
									elapsed + WorldInterfaceActionScheduler.STRONG_CONTROL_IMMUNITY_TICKS);
						}
					}
					if (selectedAction == WorldInterfaceAction.FORCED_EVICTION) {
						state.setLastForcedEvictionTick(elapsed);
					}
				});
		if (!stored.applied()) {
			WorldInterfaceAttackService.cancelAndRestore(server, latest.encounterId().orElseThrow());
			return;
		}
		// Committed, so it counts. An action aimed at the whole arena is not attention on anybody in
		// particular and is deliberately not recorded - otherwise a lash would raise every weight at
		// once, which changes nothing about who is picked next and only dilutes the memory of who was.
		if (started.targets().size() < participants.size()) {
			WorldInterfaceAttackService.recordTargeting(latest.encounterId().orElseThrow(),
					started.targets(), elapsed);
		}
		showAction(level, stored.snapshot(), bossActionWire(selectedAction), started.durationTicks(),
				started.targets().stream().sorted(Comparator.comparing(UUID::toString)).toList(), started.seed());
	}

	/**
	 * Everyone inside the arena an attack may be aimed at, in a stable order.
	 *
	 * <p>The frozen roster is the first filter, and it was missing. Without it this answered "whoever
	 * is standing in the End", so a player who joined after the sacrifice and walked over to look
	 * could be locked on, have their weapon confiscated, have their hotbar emptied and be
	 * disconnected outright by the forced eviction - by a fight they never entered and cannot end.
	 * Ordinary area damage still reaches anyone who stands in an explosion, which is correct; being
	 * <em>chosen</em> is what the roster gates.
	 *
	 * <p>The limit is now an assertion rather than a truncation: the roster is capped at
	 * {@code MAX_ROSTER_SIZE} when it is built, so this can no longer silently drop a participant
	 * because their UUID sorted late.
	 */
	private static List<ServerPlayer> arenaParticipants(ServerLevel level,
			WorldInterfaceState.Snapshot snapshot) {
		return level.players().stream()
				.filter(player -> snapshot.frozenRoster().contains(player.getUUID()))
				.filter(player -> player.isAlive() && !player.isSpectator())
				.filter(player -> player.distanceToSqr(snapshot.arenaCenter().getCenter())
						<= ENCOUNTER_VISIBILITY_RADIUS_SQR)
				.sorted(Comparator.comparing(player -> player.getUUID().toString()))
				.limit(WorldInterfacePolicy.MAX_ROSTER_SIZE).toList();
	}

	/**
	 * Ticks past due an attack may be before the schedule is treated as stuck rather than slow.
	 *
	 * <p>Comfortably past the longest legal interval at the slowest phase and the softest anchor
	 * multiplier, so a healthy fight can never reach it.
	 */
	private static final long SCHEDULER_STALL_TICKS = 300L;
	/** Last stall already reported, per encounter, so one stall is one line rather than one a tick. */
	private static final Map<UUID, Long> REPORTED_STALLS = new ConcurrentHashMap<>();

	/**
	 * Says so, once, when the encounter has stopped throwing anything.
	 *
	 * <p>Players have reported the fight going quiet after a while in one phase - no attack cues, the
	 * music and the ambient bed carrying on - and the first two attempts to explain it were wrong,
	 * because both were reasoned from the code rather than measured. The client-side channel counter
	 * killed the first (the sound pool peaks around fifteen of two hundred and forty-seven, so nothing
	 * is being dropped for want of a channel); what is left is that the sounds are never made, which
	 * means the attacks are never started. This is the instrument for that half.
	 *
	 * <p>It reports the whole scheduling state at the moment it notices, because the interesting
	 * question is <em>which</em> gate is shut: the clock not advancing, an envelope that never
	 * cleared, a recovery grace that never expired, or a due tick sitting in the future.
	 */
	private static void reportSchedulerStall(WorldInterfaceState.Snapshot snapshot, long elapsed) {
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (encounterId == null) return;
		long overdue = elapsed - snapshot.nextActionActiveTick();
		if (overdue < SCHEDULER_STALL_TICKS) {
			REPORTED_STALLS.remove(encounterId);
			return;
		}
		Long reported = REPORTED_STALLS.get(encounterId);
		// One line per stall episode, then one more for every further ten seconds it persists.
		if (reported != null && elapsed - reported < SCHEDULER_STALL_TICKS) return;
		REPORTED_STALLS.put(encounterId, elapsed);
		TheFourthFrequency.LOGGER.warn(
				"World-interface scheduler is {} ticks overdue: stage={} elapsed={} due={} running={}"
						+ " grace={} storedAttack={} lastAction={} sequence={} anchors={}",
				overdue, snapshot.stage(), elapsed, snapshot.nextActionActiveTick(),
				snapshot.runningSinceGameTime() >= 0L, snapshot.recoveryGraceTicks(),
				snapshot.currentAttack().map(envelope -> envelope.actionWireId() + "#" + envelope.sequence())
						.orElse("none"),
				snapshot.lastActionWireId(), snapshot.actionSequence(), snapshot.aliveAnchorCount());
	}

	/**
	 * Who one candidate action would be aimed at, without committing to it.
	 *
	 * <p>Pure with respect to the attention ledger: the scan calls this for several candidates before
	 * it settles on one, so recording a pick here would count actions that were never thrown. The
	 * committed pick is recorded in {@link #tickAttacks} once its envelope is stored.
	 */
	private static List<ServerPlayer> attackTargets(MinecraftServer server,
			WorldInterfaceState.Snapshot snapshot, List<ServerPlayer> participants,
			WorldInterfaceAction action, long sequence, long elapsed) {
		if (action == WorldInterfaceAction.TENDRIL_LASH) return participants;
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (action == WorldInterfaceAction.FORCED_EVICTION) {
			UUID host = participants.stream().filter(player -> server.isSingleplayerOwner(player.nameAndId()))
					.map(ServerPlayer::getUUID).findFirst().orElse(null);
			List<UUID> selected = WorldInterfaceActionScheduler.selectForcedEvictionTargets(
					participants.stream().map(ServerPlayer::getUUID).toList(),
					WorldInterfaceAttackService.evictedThisEncounter(encounterId), host,
					snapshot.deterministicSeed(), sequence);
			return selected.stream().map(server.getPlayerList()::getPlayer).filter(java.util.Objects::nonNull).toList();
		}
		List<ServerPlayer> eligible = participants;
		if (action.requiresExclusiveControl()) {
			eligible = participants.stream().filter(player ->
					elapsed >= snapshot.controlCooldowns().getOrDefault(player.getUUID(), 0L)).toList();
		}
		// The sweep carries its own, much longer per-player cooldown on top of that. Applied here
		// rather than as a scan-level gate so it narrows the roster instead of the action: with one
		// player still off cooldown the sweep can still land, and only when nobody is does the scan
		// find an empty target list and skip the candidate the way it skips any other ineligible one.
		if (action == WorldInterfaceAction.GAZE_HOTBAR_CLEAR) {
			eligible = eligible.stream().filter(player -> WorldInterfaceAttackService.hotbarSweepReady(
					encounterId, player.getUUID(), elapsed)).toList();
		}
		if (eligible.isEmpty()) return List.of();
		// One name, weighted away from whoever the fight has been looking at lately.
		//
		// The grab used to be the exception here and returned a pair, which was the last trace of the
		// retired grab-slam. Nothing has consumed the second name since: tickGrabThrow seizes the
		// first target and no other, and begin() re-sorts the pair by UUID before it gets there - so
		// the player with the higher UUID of any pair could never be the one taken, while the second
		// name still collected six hundred ticks of strong-control immunity and, because the client
		// draws its lock treatment from the same target list, a full lock warning for an attack that
		// was never going to arrive.
		List<UUID> candidates = eligible.stream().map(ServerPlayer::getUUID).toList();
		return List.of(eligible.get(WorldInterfaceTargetPolicy.selectIndex(
				WorldInterfaceAttackService.recentPicks(encounterId, candidates, elapsed),
				WorldInterfaceAttackService.lastPickTicks(encounterId, candidates, elapsed),
				snapshot.deterministicSeed(), sequence)));
	}

	private static WorldInterfaceProtocol.BossAction bossActionWire(WorldInterfaceAction action) {
		return WorldInterfaceProtocol.BossAction.fromWireId(action.wireId());
	}

	private static void healBoss(ServerLevel level, WorldInterfaceState.Snapshot before) {
		double healing = WorldInterfacePolicy.healingPerTick(before.maxVirtualHealth(), before.aliveAnchorCount())
				* HEAL_INTERVAL_TICKS;
		double healed = Math.min(before.maxVirtualHealth(), before.virtualHealth() + healing);
		if (healed <= before.virtualHealth()) return;
		WorldInterfaceState.mutate(level.getServer(), before.encounterId().orElseThrow(), before.revision(),
				state -> state.setVirtualHealth(before.maxVirtualHealth(), healed));
	}

	private static WorldInterfaceState.Snapshot reconcileClock(ServerLevel level,
			WorldInterfaceState.Snapshot before) {
		boolean shouldRun = onlineFrozenCount(level.getServer(), before) > 0;
		if (shouldRun == (before.runningSinceGameTime() >= 0L)) return before;
		long elapsed = effectiveActiveTicks(before, level.getGameTime());
		WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(level.getServer(),
				before.encounterId().orElseThrow(), before.revision(), state ->
						state.setClock(elapsed, shouldRun ? level.getGameTime() : -1L));
		return result.applied() ? result.snapshot() : WorldInterfaceState.snapshot(level.getServer());
	}

	private static void lockFailure(ServerLevel level, WorldInterfaceState.Snapshot before) {
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			before = WorldInterfaceState.snapshot(level.getServer());
			if (!before.stage().isCombat()) return;
			long elapsed = effectiveActiveTicks(before, level.getGameTime());
			if (!WorldInterfacePolicy.hasTimedOut(elapsed)) return;
			WorldInterfaceState.Snapshot captured = before;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(level.getServer(),
					before.encounterId().orElseThrow(), before.revision(), state -> {
						state.setClock(elapsed, -1L);
						advanceToPhaseThree(state);
						state.clearCurrentAttack();
						state.clearControlCooldowns();
						state.setGateState(WorldInterfaceGatewayState.RED);
						state.transitionTo(WorldInterfaceStage.FAILURE_RESOLUTION);
						state.setResolution(0, level.getGameTime());
					});
			if (!result.applied()) {
				if ("revision_mismatch".equals(result.reason())) continue;
				return;
			}
			beginResolution(level, result.snapshot(), false);
			return;
		}
	}

	private static void beginResolution(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			boolean success) {
		// Only a win stops the world being torn. Cancelling the queue on a loss threw away the
		// damage the interface was in the middle of doing, so the island a player was evicted from
		// ended up tidier than the one they were still fighting on. On failure the pending scars
		// keep draining through the ordinary tick until the whole budget has landed, and every
		// committed edit is permanent either way.
		if (success) EndBossArenaService.cancelQueuedScars(level);
		WorldInterfaceAttackService.cancelAndRestore(level.getServer(), snapshot.encounterId().orElseThrow());
		// The fight is over either way, so the transient attention and eviction ledgers go with it
		// rather than sitting on a finished encounter id until the server stops.
		WorldInterfaceAttackService.clearLedgers(snapshot.encounterId().orElseThrow());
		WorldInterfaceEntity boss = findBoss(level, snapshot).orElse(null);
		if (boss != null) {
			boss.setForm(WorldInterfaceEntity.FORM_INTERFACE);
			boss.showAction(success ? WorldInterfaceProtocol.BossAction.SUCCESS_DEATH.wireId()
					: WorldInterfaceProtocol.BossAction.FAILURE_ESCAPE.wireId(),
					level.getGameTime(), RESOLUTION_DURATION_TICKS);
			// The death cue was declared, registered, shipped - and never played. The boss dies by
			// its virtual pool reaching zero, which never routes through LivingEntity#die, so the
			// getDeathSound() this entity returns was unreachable. Fired here, at the body, which
			// is the moment it actually dies as far as the encounter is concerned.
			if (success) {
				AudioService.playBounded(level, BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss)),
						ModSounds.WORLD_INTERFACE_DEATH, SoundSource.HOSTILE, 1.0F, 1.0F);
			}
			boss.setDeltaMovement(Vec3.ZERO);
		}
		showAction(level, snapshot, success ? WorldInterfaceProtocol.BossAction.SUCCESS_DEATH
				: WorldInterfaceProtocol.BossAction.FAILURE_ESCAPE, RESOLUTION_DURATION_TICKS,
				List.of(), snapshot.deterministicSeed() ^ snapshot.actionSequence());
		AudioService.playBounded(level, snapshot.arenaCenter(), success
				? ModSounds.WORLD_INTERFACE_SUCCESS : ModSounds.WORLD_INTERFACE_FAILURE,
				SoundSource.HOSTILE, 1.0F, success ? 1.0F : 0.62F);
		broadcast(level.getServer(), TerminalNoticePayload.TONE_ENCOUNTER,
				success ? "message.thefourthfrequency.world_interface.success_locked"
						: "message.thefourthfrequency.world_interface.failure_locked");
		if (success) markDefeatedMilestone(level.getServer(), snapshot.frozenRoster());
		sendEncounterSnapshots(level.getServer(), true);
	}

	private static void synchronizeResolutionAction(ServerLevel level,
			WorldInterfaceState.Snapshot snapshot, WorldInterfaceEntity boss, boolean broadcast) {
		if (snapshot.stage() != WorldInterfaceStage.SUCCESS_RESOLUTION
				&& snapshot.stage() != WorldInterfaceStage.FAILURE_RESOLUTION) return;
		WorldInterfaceProtocol.BossAction action = snapshot.stage() == WorldInterfaceStage.SUCCESS_RESOLUTION
				? WorldInterfaceProtocol.BossAction.SUCCESS_DEATH
				: WorldInterfaceProtocol.BossAction.FAILURE_ESCAPE;
		long started = snapshot.resolutionTick() < 0L ? level.getGameTime() : snapshot.resolutionTick();
		if (boss != null && (boss.actionId() != action.wireId()
				|| boss.actionStartTick() != started || boss.actionDuration() != RESOLUTION_DURATION_TICKS)) {
			boss.showAction(action.wireId(), started, RESOLUTION_DURATION_TICKS);
		}
		if (!broadcast) return;
		for (ServerPlayer player : encounterRecipients(level.getServer(), snapshot)) {
			sendResolutionAction(player, snapshot, action, started);
		}
	}

	private static void sendResolutionAction(ServerPlayer player, WorldInterfaceState.Snapshot snapshot,
			WorldInterfaceProtocol.BossAction action, long started) {
		ServerPlayNetworking.send(player, new BossActionS2C(snapshot.encounterId().orElseThrow(),
				nextClientSequence(player), action.wireId(), started, RESOLUTION_DURATION_TICKS,
				List.of(), snapshot.deterministicSeed() ^ snapshot.actionSequence(), 0));
	}

	/**
	 * Server-authored contact spray. The virtual-health model means a landed hit produces no vanilla
	 * knockback or hurt animation, so without this the only feedback was a five-tick texture swap
	 * driven off a snapshot diff - which a low snapshot rate could drop entirely.
	 */
	private static void emitImpactBurst(ServerLevel level, WorldInterfaceEntity boss,
			ServerPlayer attacker, double adjusted, boolean melee) {
		// The box no longer stands on the entity position - it is lifted to hug the drawn mass - so
		// the centre of the body is the centre of the box, not half a box-height off the origin.
		Vec3 centre = boss.getBoundingBox().getCenter();
		Vec3 eye = attacker.getEyePosition();
		// A melee hit lands on a limb at head height, not on the mass overhead. Spraying at the body
		// centre put the only confirmation a player gets tens of blocks above where they swung.
		Vec3 contact;
		if (melee) {
			contact = eye.add(attacker.getLookAngle().scale(MELEE_CONTACT_REACH));
		} else {
			Vec3 toward = eye.subtract(centre);
			double length = toward.length();
			contact = length < 1.0E-3D ? centre
					: centre.add(toward.scale(Math.min(1.0D, boss.getBbWidth() * 0.5D / length)));
		}
		int count = Math.clamp(6 + (int) Math.round(adjusted * 1.5D), 6, 28);
		level.sendParticles(ParticleTypes.CRIT, contact.x, contact.y, contact.z,
				count, 0.55D, 0.55D, 0.55D, 0.22D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, contact.x, contact.y, contact.z,
				count / 2, 0.45D, 0.45D, 0.45D, 0.08D);
		// The shell answering where it was struck. Crits at the contact are the player's own hit
		// marker and say nothing about the thing that was hit; a ring lying flat against the surface
		// at the point of impact, with the splash thrown back out along the normal, is the body
		// reacting - which at the range this fight is mostly fought from is the only way a landed
		// blow is legible at all.
		Vec3 normal = contact.subtract(centre);
		if (normal.lengthSqr() > 1.0E-6D) {
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.core(), contact, normal,
					0.55D + adjusted * 0.05D, 16, level.getGameTime() * 0.4D, 0.0D);
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.violet(), contact,
					0.35D + adjusted * 0.03D, 20 + count, 0.35D);
			// A bolt running from the wound back to the core, so a hit is something the whole body
			// felt rather than a spark on its skin.
			WorldInterfaceVfx.arc(level, ParticleTypes.ELECTRIC_SPARK, contact, centre,
					10, 0.8D, level.getGameTime() * 7919L + Double.doubleToLongBits(contact.x));
		}

		// The boss had a hurt sound declared and never once played it.
		//
		// WorldInterfaceEntity#hurtServer is overridden to route everything into the virtual health
		// pool and never calls super, so LivingEntity's own "play getHurtSound()" branch is dead
		// code for this entity. A thousand points of damage landed in silence, and at any range
		// where the health bar is the only other feedback a landed hit was indistinguishable from
		// a missed one. Played at the contact rather than at the entity position, which for the
		// third form is twenty-five blocks below the mass.
		//
		// Pitch falls as the hit gets heavier, so a charged blow is audibly worth more than a poke.
		//
		// Throttled because a table of eight swinging and shooting continuously can otherwise produce
		// fifteen to twenty fresh instances a second. The long-phase silence was measured at a peak of
		// only fifteen channels and came from a scheduler stall, not from these cues exhausting the
		// mixer; five a second is still the clearer hit texture and leaves room for the attacks.
		if (WorldInterfaceBlastService.allows(level, HURT_CUE_SOURCE, HURT_CUE_MIN_GAP_TICKS)) {
			AudioService.playBounded(level, BlockPos.containing(contact), ModSounds.WORLD_INTERFACE_HURT,
					SoundSource.HOSTILE, 0.85F, 1.12F - (float) Math.min(0.30D, adjusted * 0.02D));
		}
	}

	/** The interface's hit cue, and the floor between two of them. Particles are not throttled. */
	private static final String HURT_CUE_SOURCE = "boss_hurt";
	private static final int HURT_CUE_MIN_GAP_TICKS = 4;

	private static void phaseChanged(ServerLevel level, WorldInterfaceState.Snapshot before,
			WorldInterfaceState.Snapshot after) {
		WorldInterfaceAttackService.cancelAndRestore(level.getServer(), after.encounterId().orElseThrow());
		WorldInterfaceEntity boss = findBoss(level, after).orElse(null);
		WorldInterfaceProtocol.BossAction action = after.stage() == WorldInterfaceStage.PHASE_2
				? WorldInterfaceProtocol.BossAction.MORPH_TO_SECOND
				: WorldInterfaceProtocol.BossAction.MORPH_TO_THIRD;
		if (boss != null) {
			// The form itself is left alone here. driveMorphFlight changes it at the apex of the
			// climb, so the body that leaves the ground is the one the players were just fighting.
			boss.showAction(action.wireId(), level.getGameTime(), MORPH_FLIGHT_TICKS);
		}
		showAction(level, after, action, MORPH_FLIGHT_TICKS, List.of(),
				after.deterministicSeed() ^ after.stage().wireId());
		// Played at the interface, not at the arena centre. The third form hunts, so by the time it
		// morphs it can be a hundred blocks from the middle of the island - and both of these cues
		// were being emitted at a point nobody was standing near, which is why a transformation
		// that visibly tears the body apart happened in silence.
		BlockPos morphAt = boss == null ? after.arenaCenter()
				: BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss));
		AudioService.playBounded(level, morphAt, ModSounds.WORLD_INTERFACE_MORPH,
				SoundSource.HOSTILE, 0.92F, after.stage() == WorldInterfaceStage.PHASE_2 ? 0.86F : 0.68F);
		// Layered under the morph cue: the shell coming apart and closing again, which is the part
		// the renderer's pinch is actually showing. Particles fill the gap the body vacates.
		AudioService.playBounded(level, morphAt, ModSounds.WORLD_INTERFACE_FORM_SHIFT,
				SoundSource.HOSTILE, 0.88F, after.stage() == WorldInterfaceStage.PHASE_2 ? 1.0F : 0.82F);
		// Roared at full volume as it goes up, still in the old body's register: the last thing
		// heard from the form that just lost, before it comes back down as something else.
		if (boss != null) roar(level, boss, 1.0F);
		if (boss != null) {
			Vec3 body = boss.getBoundingBox().getCenter();
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, body.x, body.y, body.z,
					220, boss.getBbWidth() * 0.6D, boss.getBbHeight() * 0.35D,
					boss.getBbWidth() * 0.6D, 0.22D);
		}
		// A morph is the only moment the encounter changes its own rules; give it a world event
		// rather than leaving the phase change legible only through the HUD label.
		WorldInterfaceShockwaveService.emit(level, boss == null ? after.arenaCenter().getCenter()
						: boss.getBoundingBox().getCenter(),
				WorldInterfaceShockwaveService.MORPH_DURATION_TICKS,
				WorldInterfaceShockwaveService.MORPH_MAX_RADIUS);
		broadcast(level.getServer(), TerminalNoticePayload.TONE_ENCOUNTER,
				"message.thefourthfrequency.world_interface.phase." + after.stage().serializedName());
		sendEncounterSnapshots(level.getServer(), true);
	}

	private static WorldInterfaceEntity ensureBoss(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		WorldInterfaceEntity found = findBoss(level, snapshot).orElse(null);
		if (found != null) {
			found.bindEncounter(snapshot.encounterId().orElseThrow());
			if (snapshot.bossUuid().filter(found.getUUID()::equals).isEmpty()) {
				WorldInterfaceState.mutate(level.getServer(), snapshot.encounterId().orElseThrow(), snapshot.revision(),
						state -> state.setBossUuid(found.getUUID()));
			}
			ensureParts(level, found);
			return found;
		}
		if (snapshot.stage().wireId() < WorldInterfaceStage.SUMMONING.wireId()
				|| snapshot.stage().wireId() > WorldInterfaceStage.FAILURE_RESOLUTION.wireId()) return null;
		// Never rebuild a body during a resolution. A resolution is the interface leaving, and the
		// success path deliberately discards the projection part-way through it - so from that tick
		// on, "there is no boss entity" is the correct state and not a fault to repair. Rebuilding
		// anyway spawned a fresh body at the arena centre, eighteen blocks up: it read as the corpse
		// teleporting back down out of the ascent it had just finished, it re-registered itself as
		// the encounter's boss, and it then sat in the sky through the dragon's whole arrival.
		if (snapshot.stage().isResolution()) return null;
		level.getChunkAt(snapshot.arenaCenter());
		WorldInterfaceEntity boss = ModEntities.WORLD_INTERFACE.create(level, EntitySpawnReason.EVENT);
		if (boss == null) return null;
		UUID requested = snapshot.bossUuid().orElseGet(() -> deterministicEntityUuid(
				"boss", snapshot.encounterId().orElseThrow()));
		Entity collision = level.getEntity(requested);
		if (collision != null) return null;
		boss.setUUID(requested);
		boss.bindEncounter(snapshot.encounterId().orElseThrow());
		boss.setForm(formForStage(snapshot.stage()));
		boss.snapTo(snapshot.arenaCenter().getX() + 0.5D, snapshot.arenaCenter().getY() + 18.0D,
				snapshot.arenaCenter().getZ() + 0.5D, 0.0F, 0.0F);
		if (!level.addFreshEntity(boss)) return null;
		if (snapshot.bossUuid().filter(boss.getUUID()::equals).isEmpty()) {
			WorldInterfaceState.mutate(level.getServer(), snapshot.encounterId().orElseThrow(), snapshot.revision(),
					state -> state.setBossUuid(boss.getUUID()));
		}
		ensureParts(level, boss);
		return boss;
	}

	private static Optional<WorldInterfaceEntity> findBoss(ServerLevel level,
			WorldInterfaceState.Snapshot snapshot) {
		if (snapshot.bossUuid().isPresent()) {
			Entity direct = level.getEntity(snapshot.bossUuid().orElseThrow());
			if (direct instanceof WorldInterfaceEntity boss && boss.isAlive()) return Optional.of(boss);
		}
		AABB bounds = new AABB(snapshot.arenaCenter()).inflate(192.0D, 128.0D, 192.0D);
		return level.getEntitiesOfClass(WorldInterfaceEntity.class, bounds, Entity::isAlive).stream()
				.filter(entity -> snapshot.encounterId().filter(id -> id.equals(entity.encounterId())).isPresent())
				.findFirst();
	}

	private static void ensureParts(ServerLevel level, WorldInterfaceEntity boss) {
		String tag = PART_TAG_PREFIX + boss.getUUID();
		// The limb proxies hang below the box rather than sitting inside it, so the search has to
		// clear the full drop or reconciliation keeps re-creating parts it simply failed to see.
		double reach = WorldInterfaceAnatomy.tentacleDrop(boss.form()) + 24.0D;
		AABB bounds = boss.getBoundingBox().inflate(reach, reach, reach);
		Set<Integer> present = new HashSet<>();
		for (WorldInterfacePartEntity part : level.getEntitiesOfClass(WorldInterfacePartEntity.class, bounds,
				entity -> entity.getTags().contains(tag))) present.add(part.partIndex());
		for (int index = 0; index < WorldInterfacePartEntity.PART_COUNT; index++) {
			if (present.contains(index)) continue;
			WorldInterfacePartEntity part = ModEntities.WORLD_INTERFACE_PART.create(level, EntitySpawnReason.EVENT);
			if (part == null) continue;
			part.addTag(tag);
			part.attach(boss, index);
			level.addFreshEntity(part);
		}
	}

	private static void hoverAt(WorldInterfaceEntity boss, BlockPos center, double height, long tick) {
		double angle = tick * 0.006D;
		Vec3 desired = new Vec3(center.getX() + 0.5D + Math.cos(angle) * 7.0D,
				center.getY() + height + Math.sin(angle * 1.7D) * 1.5D,
				center.getZ() + 0.5D + Math.sin(angle) * 7.0D);
		boss.setDeltaMovement(desired.subtract(boss.position()).scale(0.08D));
	}

	private static void showAction(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			WorldInterfaceProtocol.BossAction action, int duration, List<UUID> targets, long seed) {
		WorldInterfaceEntity boss = findBoss(level, snapshot).orElse(null);
		if (boss != null) boss.showAction(action.wireId(), level.getGameTime(), duration);
		for (ServerPlayer player : encounterRecipients(level.getServer(), snapshot)) {
			ServerPlayNetworking.send(player, new BossActionS2C(snapshot.encounterId().orElseThrow(),
					nextClientSequence(player), action.wireId(), level.getGameTime(), duration,
					targets, seed, 0));
		}
	}

	private static void sendEncounterSnapshots(MinecraftServer server, boolean immediate) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()) return;
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		WorldInterfaceEntity boss = findBoss(level, snapshot).orElse(null);
		int form = boss == null ? formForStage(snapshot.stage()) + 1 : boss.form() + 1;
		if (snapshot.stage().wireId() < WorldInterfaceStage.SUMMONING.wireId()
				|| snapshot.stage() == WorldInterfaceStage.COMPLETE) form = WorldInterfaceProtocol.Form.NONE.wireId();
		// Wound back while a won encounter is repairing itself, so the rail and the material erosion
		// both run backwards off one number. The stored timer is untouched: this is the projection.
		long elapsed = repairProjectedElapsedTicks(snapshot, level.getGameTime());
		int anchors = 0;
		for (WorldInterfaceState.Anchor anchor : snapshot.anchors()) if (!anchor.destroyed()) anchors |= 1 << anchor.index();
		int gatewayState = snapshot.gates().isEmpty() ? WorldInterfaceGatewayState.DORMANT.wireId()
				: snapshot.gates().getFirst().state().wireId();
		float progress = WorldInterfacePolicy.presentationErosionProgress(snapshot.stage(),
				elapsed, snapshot.resolutionTick(), level.getGameTime(), RESOLUTION_DURATION_TICKS);
		for (ServerPlayer player : encounterRecipients(server, snapshot)) {
			ServerPlayNetworking.send(player, new WorldInterfaceSnapshotS2C(WorldInterfaceProtocol.VERSION,
					snapshot.encounterId().orElseThrow(), nextClientSequence(player), snapshot.stage().wireId(), form,
					boss == null ? NIL_UUID : boss.getUUID(), snapshot.arenaCenter(),
					(float) snapshot.maxVirtualHealth(), (float) snapshot.virtualHealth(), anchors,
					elapsed, snapshot.runningSinceGameTime() < 0L,
					Math.max(0L, level.getGameTime()), gatewayState,
					snapshot.gates().stream().map(WorldInterfaceState.Gate::position).toList(),
					snapshot.anchors().stream().map(WorldInterfaceState.Anchor::position).toList(),
					outcomeWire(snapshot.outcome()), progress));
		}
	}

	private static void sendAltarSnapshots(MinecraftServer server, WorldInterfaceState.Snapshot snapshot,
			WorldInterfaceProtocol.AltarStatus status) {
		Set<UUID> viewers = ALTAR_VIEWERS.getOrDefault(server, Set.of());
		for (UUID viewerId : List.copyOf(viewers)) {
			ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
			if (viewer != null) sendAltarSnapshot(viewer, snapshot, status);
		}
	}

	private static void sendAltarSnapshot(ServerPlayer viewer, WorldInterfaceState.Snapshot snapshot,
			WorldInterfaceProtocol.AltarStatus status) {
		MinecraftServer server = viewer.level().getServer();
		List<UUID> roster = altarRoster(server, snapshot);
		List<String> names = roster.stream().map(id -> {
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			return online == null ? id.toString().substring(0, 8) : online.getGameProfile().name();
		}).toList();
		int mask = 0;
		for (int index = 0; index < roster.size(); index++) {
			WorldInterfaceState.TerminalTransaction transaction = snapshot.terminalTransactions().get(roster.get(index));
			if (transaction != null && (transaction.state() == WorldInterfaceState.TerminalTransactionState.REMOVED
					|| transaction.state() == WorldInterfaceState.TerminalTransactionState.COMMITTED)) mask |= 1 << index;
		}
		int deposited = Integer.bitCount(mask);
		boolean everyoneIn = !roster.isEmpty() && deposited == roster.size()
				&& roster.size() == snapshot.frozenRoster().size();
		WorldInterfaceState.TerminalTransaction own = snapshot.terminalTransactions().get(viewer.getUUID());
		boolean ownEscrowed = own != null
				&& own.state() == WorldInterfaceState.TerminalTransactionState.REMOVED;
		long remaining = Math.min(snapshot.ritualWindowRemaining(server.overworld().getGameTime()),
				WorldInterfacePolicy.RITUAL_WINDOW_TICKS);
		ServerPlayNetworking.send(viewer, new AltarSnapshotS2C(WorldInterfaceProtocol.VERSION,
				snapshot.encounterId().orElseThrow(), nextClientSequence(viewer), snapshot.revision(),
				snapshot.stage().wireId(), snapshot.altarCenter(), roster, names, mask,
				roster.contains(viewer.getUUID()) && !viewer.isSpectator(), status.wireId(),
				(int) remaining,
				ownEscrowed && everyoneIn && !viewer.isSpectator() && !snapshot.sacrificeCommitted()
						&& snapshot.stage() == WorldInterfaceStage.WAITING_TERMINALS));
	}

	/**
	 * The names the altar screen lists: the depositors, or - before anyone has deposited - whoever is
	 * standing there. The second half exists only so an untouched altar reads as waiting for the
	 * people in front of it rather than as empty and broken; nothing authoritative consults it.
	 */
	private static List<UUID> altarRoster(MinecraftServer server, WorldInterfaceState.Snapshot snapshot) {
		if (!snapshot.frozenRoster().isEmpty()) return snapshot.frozenRoster().stream()
				.sorted(Comparator.comparing(UUID::toString)).toList();
		return WorldInterfaceRitualService.altarCandidates(server, snapshot);
	}

	private static List<ServerPlayer> encounterRecipients(MinecraftServer server,
			WorldInterfaceState.Snapshot snapshot) {
		LinkedHashSet<ServerPlayer> recipients = new LinkedHashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (snapshot.frozenRoster().contains(player.getUUID()) || player.level().dimension() == Level.END) {
				recipients.add(player);
			}
		}
		return List.copyOf(recipients);
	}

	private static long nextClientSequence(ServerPlayer player) {
		Map<UUID, Long> sequences = CLIENT_SEQUENCES.computeIfAbsent(player.level().getServer(),
				ignored -> new HashMap<>());
		long next = Math.incrementExact(sequences.getOrDefault(player.getUUID(), -1L));
		sequences.put(player.getUUID(), next);
		return next;
	}

	private static void rememberClientSequence(ServerPlayer player, long sequence) {
		if (sequence < 0L) throw new IllegalArgumentException("Sequence must be non-negative");
		CLIENT_SEQUENCES.computeIfAbsent(player.level().getServer(), ignored -> new HashMap<>())
				.merge(player.getUUID(), sequence, Math::max);
	}

	private static void pruneAltarViewers(MinecraftServer server) {
		Set<UUID> viewers = ALTAR_VIEWERS.get(server);
		if (viewers == null) return;
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		viewers.removeIf(id -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			return player == null || snapshot.stage() != WorldInterfaceStage.WAITING_TERMINALS
					|| player.level().dimension() != Level.END
					|| player.distanceToSqr(snapshot.altarCenter().getCenter()) > 10.0D * 10.0D;
		});
	}

	private static void recoverAfterRestart(MinecraftServer server) {
		if (!RECOVERED_SERVERS.add(server)) return;
		ServerLevel level = server.getLevel(Level.END);
		WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(server);
		if (level == null || !before.valid() || !before.present()) return;
		EndBossArenaService.PreparedArena arena = EndBossArenaService.prepare(level);
		WorldInterfaceShockwaveService.clear(level);
		EndBossArenaService.restoreAuthoritativeAnchors(level, before, !before.stage().isCombat());
		EndBossArenaService.restoreTerrainEditCount(level, before.terrainEditsUsed());
		bindCore(level, before);
		ensureExitOpen(level, before);
		if (before.stage().isCombat()) {
			WorldInterfaceAttackService.onRestart(server, before.encounterId().orElseThrow());
			before = WorldInterfaceState.snapshot(server);
			long recoveredElapsed = effectiveActiveTicks(before, level.getGameTime());
			WorldInterfaceState.Snapshot captured = before;
			WorldInterfaceState.MutationResult recovered = WorldInterfaceState.mutate(server,
					before.encounterId().orElseThrow(), before.revision(), state -> {
						state.setClock(recoveredElapsed, -1L);
						state.clearCurrentAttack();
						state.setRecoveryGraceTicks(WorldInterfaceActionScheduler.RESTART_RECOVERY_TICKS);
						state.setActionSchedule(captured.actionSequence(), captured.lastActionWireId(),
								Math.max(captured.nextActionActiveTick(), recoveredElapsed
										+ WorldInterfaceActionScheduler.RESTART_RECOVERY_TICKS));
					});
			if (recovered.applied()) before = recovered.snapshot();
		}
		if (before.stage().wireId() >= WorldInterfaceStage.SUMMONING.wireId()
				&& before.stage().wireId() <= WorldInterfaceStage.FAILURE_RESOLUTION.wireId()) {
			WorldInterfaceEntity recoveredBoss = ensureBoss(level, before);
			synchronizeResolutionAction(level, before, recoveredBoss, true);
		}
		if (before.friendlyDragonUuid().isPresent()
				&& FriendlyDragonService.recover(level, before.friendlyDragonUuid().orElseThrow()).isEmpty()) {
			FriendlyDragonService.spawn(level, before.arenaCenter(), before.friendlyDragonUuid().orElseThrow());
		}
	}

	private static void pauseForStop(MinecraftServer server) {
		WorldInterfaceState.Snapshot before = WorldInterfaceState.snapshot(server);
		if (!before.valid() || !before.present() || !before.stage().isCombat()
				|| before.runningSinceGameTime() < 0L) return;
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		long elapsed = effectiveActiveTicks(before, level.getGameTime());
		WorldInterfaceState.mutate(server, before.encounterId().orElseThrow(), before.revision(),
				state -> state.setClock(elapsed, -1L));
	}

	private static void reconcilePlayer(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || !snapshot.present()) return;
		snapshot.encounterId().ifPresent(id -> WorldInterfaceAttackService.restorePendingFor(player, id));
		if (snapshot.stage() == WorldInterfaceStage.SUCCESS_RESOLUTION
				|| snapshot.stage() == WorldInterfaceStage.FAILURE_RESOLUTION) {
			WorldInterfaceProtocol.BossAction action = snapshot.stage() == WorldInterfaceStage.SUCCESS_RESOLUTION
					? WorldInterfaceProtocol.BossAction.SUCCESS_DEATH
					: WorldInterfaceProtocol.BossAction.FAILURE_ESCAPE;
			long started = snapshot.resolutionTick() < 0L
					? player.level().getGameTime() : snapshot.resolutionTick();
			sendResolutionAction(player, snapshot, action, started);
		}
		WorldInterfaceState.PoemLedgerEntry poem = snapshot.poemLedger().get(player.getUUID());
		WorldInterfaceState.RespawnLedgerEntry respawn = snapshot.respawnLedger().get(player.getUUID());
		if (respawn != null && !respawn.restored() && player.level().dimension() != Level.END
				&& snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()) {
			restoreRespawnAfterVanillaReturn(player, snapshot);
			snapshot = WorldInterfaceState.snapshot(server);
			poem = snapshot.poemLedger().get(player.getUUID());
			respawn = snapshot.respawnLedger().get(player.getUUID());
		}
		if (snapshot.stage() == WorldInterfaceStage.COMPLETE) {
			// Mirrors the PORTAL_OPEN branch below, and exists for the case that branch cannot see:
			// the stage settling while this player was away is exactly what leaves a started, never
			// acknowledged poem behind. The ending is theirs; the world moving on does not spend it.
			if (poem != null && poem.started() && !poem.acked()) {
				rememberClientSequence(player, poem.sequence());
				ServerPlayNetworking.send(player, new PoemStartS2C(snapshot.encounterId().orElseThrow(),
						poem.sequence(), outcomeWire(snapshot.outcome()),
						FrequencyWorldData.get(server).worldId(), snapshot.destroyedAnchorCount()));
			}
			if (respawn != null && !respawn.restored()) restoreRespawnAndReturn(player, snapshot);
			return;
		}
		if (snapshot.stage() == WorldInterfaceStage.PORTAL_OPEN && poem != null && poem.acked()) {
			if (respawn != null && !respawn.restored()) restoreRespawnAndReturn(player, snapshot);
			completeIfAllPoemsAcknowledged(server);
			return;
		}
		if (snapshot.stage() == WorldInterfaceStage.PORTAL_OPEN && poem != null && poem.started() && !poem.acked()) {
			rememberClientSequence(player, poem.sequence());
			ServerPlayNetworking.send(player, new PoemStartS2C(snapshot.encounterId().orElseThrow(),
					poem.sequence(), outcomeWire(snapshot.outcome()), FrequencyWorldData.get(server).worldId(),
					snapshot.destroyedAnchorCount()));
			prepareVanillaEndReturn(player, snapshot);
			if (player.level().dimension() == Level.END && !player.wonGame) {
				// Reopen the real credits after a disconnect that interrupted an unacknowledged poem.
				player.showEndCredits();
			}
			return;
		}
		if (respawn != null && !respawn.restored()) overrideRespawn(player, snapshot.safeSpawn());
	}

	/**
	 * Gives every committed participant a respawn point on the island, and nobody else one.
	 *
	 * <p>Run from the tick rather than hung off the commit, because the set it has to cover is not
	 * "who was standing there when the sacrifice landed": a participant can log in mid-fight, and the
	 * debug entry point commits by a different route. Idempotent, so a pass that finds everything
	 * already in place costs one map lookup each.
	 *
	 * <p>The ledger is now exactly the roster, which is capped at eight when it is built - so the
	 * eight-entry limit inside {@link #rememberAndOverrideRespawn} can no longer be reached by
	 * ordinary play, and the participant who used to be silently refused an override because eight
	 * bystanders had walked through a portal first does not exist any more.
	 */
	private static void ensureRosterRespawnOverrides(MinecraftServer server,
			WorldInterfaceState.Snapshot snapshot) {
		if (!snapshot.sacrificeCommitted()
				|| snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()) return;
		for (UUID playerId : snapshot.frozenRoster()) {
			if (snapshot.respawnLedger().containsKey(playerId)) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) rememberAndOverrideRespawn(player, snapshot);
		}
	}

	private static boolean rememberAndOverrideRespawn(ServerPlayer player,
			WorldInterfaceState.Snapshot snapshot) {
		MinecraftServer server = player.level().getServer();
		WorldInterfaceState.RespawnLedgerEntry existing = snapshot.respawnLedger().get(player.getUUID());
		if (existing == null) {
			if (snapshot.respawnLedger().size() >= WorldInterfaceState.MAX_ROSTER_SIZE) {
				// Unreachable while the ledger tracks the roster, and kept as the assertion that it
				// does: reaching it means something outside the ritual started writing entries again.
				TheFourthFrequency.LOGGER.error("World interface respawn ledger is full for a roster of {}",
						snapshot.frozenRoster().size());
				return false;
			}
			ServerPlayer.RespawnConfig original = player.getRespawnConfig();
			WorldInterfaceState.RespawnLedgerEntry entry = original == null
					? new WorldInterfaceState.RespawnLedgerEntry(player.getUUID(), false, "", BlockPos.ZERO,
							0.0F, 0.0F, false, false)
					: new WorldInterfaceState.RespawnLedgerEntry(player.getUUID(), true,
							original.respawnData().dimension().identifier().toString(),
							original.respawnData().pos(), original.respawnData().yaw(), original.respawnData().pitch(),
							original.forced(), false);
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> state.putRespawn(entry));
			if (!result.applied()) return false;
		} else if (existing.restored()) {
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), state -> state.putRespawn(
						new WorldInterfaceState.RespawnLedgerEntry(existing.playerId(), existing.hasOriginal(),
								existing.dimension(), existing.position(), existing.yaw(), existing.pitch(),
								existing.forced(), false)));
			if (!result.applied()) return false;
		}
		overrideRespawn(player, snapshot.safeSpawn());
		return true;
	}

	private static void overrideRespawn(ServerPlayer player, BlockPos safeSpawn) {
		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(Level.END, safeSpawn, 180.0F, 0.0F), true), false);
	}

	private static void prepareVanillaEndReturn(ServerPlayer player,
			WorldInterfaceState.Snapshot snapshot) {
		WorldInterfaceState.RespawnLedgerEntry entry = snapshot.respawnLedger().get(player.getUUID());
		if (entry != null && !entry.restored()) {
			// Vanilla PERFORM_RESPAWN now resolves to the Overworld default instead of the temporary End altar.
			player.setRespawnPosition(null, false);
		}
	}

	private static void restoreRespawnAfterVanillaReturn(ServerPlayer player,
			WorldInterfaceState.Snapshot snapshot) {
		WorldInterfaceState.RespawnLedgerEntry entry = snapshot.respawnLedger().get(player.getUUID());
		if (entry == null || entry.restored()) return;
		restoreRespawnConfiguration(player, entry);
		markRespawnRestored(player.level().getServer(), entry.playerId());
	}

	private static void restoreRespawnAndReturn(ServerPlayer player,
			WorldInterfaceState.Snapshot snapshot) {
		WorldInterfaceState.RespawnLedgerEntry entry = snapshot.respawnLedger().get(player.getUUID());
		if (entry != null) restoreRespawnConfiguration(player, entry);
		MinecraftServer server = player.level().getServer();
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getRespawnData().pos();
		Entity teleported = player.teleport(new TeleportTransition(overworld, Vec3.atBottomCenterOf(spawn), Vec3.ZERO,
				player.getYRot(), player.getXRot(),
				TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
		if (teleported != null && entry != null && !entry.restored()) markRespawnRestored(server, entry.playerId());
	}

	private static void restoreRespawnConfiguration(ServerPlayer player,
			WorldInterfaceState.RespawnLedgerEntry entry) {
		if (!entry.hasOriginal()) {
			player.setRespawnPosition(null, false);
			return;
		}
		try {
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
					Identifier.parse(entry.dimension()));
			player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(dimension,
					entry.position(), entry.yaw(), entry.pitch()), entry.forced()), false);
		} catch (RuntimeException exception) {
			player.setRespawnPosition(null, false);
		}
	}

	private static void markRespawnRestored(MinecraftServer server, UUID playerId) {
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			WorldInterfaceState.Snapshot latest = WorldInterfaceState.snapshot(server);
			if (!latest.valid() || !latest.present() || latest.encounterId().isEmpty()) return;
			WorldInterfaceState.RespawnLedgerEntry current = latest.respawnLedger().get(playerId);
			if (current == null || current.restored()) return;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
					latest.encounterId().orElseThrow(), latest.revision(), state -> state.putRespawn(
							new WorldInterfaceState.RespawnLedgerEntry(current.playerId(), current.hasOriginal(),
									current.dimension(), current.position(), current.yaw(), current.pitch(),
									current.forced(), true)));
			if (result.applied() || !"revision_mismatch".equals(result.reason())) return;
		}
	}

	/**
	 * How long {@link WorldInterfaceStage#PORTAL_OPEN} may wait on people before it stops waiting.
	 *
	 * <p>Measured from the resolution rather than from the stage, because the resolution tick is
	 * already persisted and a restart therefore cannot reset the grace period by forgetting when the
	 * waiting started. Twenty minutes is far past any honest poem: the authored text runs under two,
	 * and the vanilla credits it hands off to can be skipped at any point.</p>
	 */
	private static final long PORTAL_OPEN_GRACE_TICKS = 20L * 60L * 20L;

	/**
	 * Settles the stage once everyone who is actually here has read their poem.
	 *
	 * <p>This used to require the whole frozen roster, which made one player who never came back the
	 * end of the encounter for everybody else - and not only cosmetically. {@code PORTAL_OPEN} is
	 * what {@link #createPortalTransition} checks: while the stage is held open, every End portal in
	 * the world keeps delivering into the arena and every arrival keeps having its respawn point
	 * overwritten. A player quitting for good left that in place permanently.
	 *
	 * <p>Offline members are excused rather than resolved. Their poem and respawn entitlements stay
	 * in the ledgers exactly as they were, so {@link #reconcilePlayer} still returns them to the
	 * Overworld and {@link #startPoem} still owes them their ending whenever they next log in - see
	 * the {@code COMPLETE} handling in both. What changes is only that the world stops waiting.
	 */
	private static void completeIfAllPoemsAcknowledged(MinecraftServer server) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (snapshot.stage() != WorldInterfaceStage.PORTAL_OPEN || snapshot.frozenRoster().isEmpty()) return;
		boolean settled = snapshot.frozenRoster().stream()
				.filter(id -> server.getPlayerList().getPlayer(id) != null)
				.allMatch(id -> Optional.ofNullable(snapshot.poemLedger().get(id))
						.map(WorldInterfaceState.PoemLedgerEntry::acked).orElse(false)
						&& Optional.ofNullable(snapshot.respawnLedger().get(id))
						.map(WorldInterfaceState.RespawnLedgerEntry::restored).orElse(false));
		if (settled) transitionToComplete(server);
	}

	/**
	 * The last-resort settle, for a wait no departure will ever end.
	 *
	 * <p>Everything above depends on ledger entries reaching a particular shape. This does not: past
	 * the grace period the stage closes whatever the ledgers say, because the cost of being wrong
	 * here is one player watching their poem on a later login, and the cost of waiting forever is a
	 * dimension whose portals and respawn points never come back.
	 */
	private static void settlePortalOpen(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		completeIfAllPoemsAcknowledged(level.getServer());
		if (WorldInterfaceState.snapshot(level.getServer()).stage() != WorldInterfaceStage.PORTAL_OPEN) return;
		if (snapshot.resolutionTick() < 0L
				|| level.getGameTime() - snapshot.resolutionTick() < PORTAL_OPEN_GRACE_TICKS) return;
		List<UUID> unsettled = snapshot.frozenRoster().stream()
				.filter(id -> !Optional.ofNullable(snapshot.poemLedger().get(id))
						.map(WorldInterfaceState.PoemLedgerEntry::acked).orElse(false))
				.toList();
		if (transitionToComplete(level.getServer()) && !unsettled.isEmpty()) {
			TheFourthFrequency.LOGGER.info("World interface closed its exit stage after the grace period with "
					+ "{} unread poem(s); those entitlements remain and are delivered on next login", unsettled.size());
		}
	}

	private static boolean transitionToComplete(MinecraftServer server) {
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES; attempt++) {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			if (snapshot.stage() != WorldInterfaceStage.PORTAL_OPEN) return false;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.transition(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), WorldInterfaceStage.PORTAL_OPEN,
					WorldInterfaceStage.COMPLETE);
			if (result.applied()) {
				sendEncounterSnapshots(server, true);
				return true;
			}
			if (!"revision_mismatch".equals(result.reason())) return false;
		}
		return false;
	}

	private static void ensureExitOpen(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		if (snapshot.exitOpen() && snapshot.stage().wireId() >= WorldInterfaceStage.PORTAL_OPEN.wireId()) {
			// Derived from the altar core rather than the old exit ledger position, so saves made by the
			// short-lived raised-terrace layout are repaired back into the original altar on recovery.
			placeExit(level, snapshot.altarCenter());
		}
	}

	/**
	 * Opens the way out by turning the altar's existing top platform into the portal.
	 *
	 * <p>The altar remains the object the player has used throughout the encounter. Its central
	 * three-by-three top course becomes the exit, the resonance core above it is removed, and one
	 * same-height block ring hides the portal's single-sided edge. That ring rests on the existing
	 * middle step; the surrounding steps and pillars remain the altar rather than becoming a second
	 * platform. Rebuilding the authored shape here also migrates worlds that briefly received a
	 * raised exit terrace above the altar.</p>
	 */
	private static void placeExit(ServerLevel level, BlockPos corePosition) {
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		BlockPos floor = AltarShape.centerFromCore(corePosition);
		BlockPos portal = AltarShape.exitPortalCenter(corePosition);
		if (exitPortalComplete(level, portal, floor)) return;

		for (int x = -AltarShape.RADIUS; x <= AltarShape.RADIUS; x++) {
			for (int z = -AltarShape.RADIUS; z <= AltarShape.RADIUS; z++) {
				int top = AltarShape.topOffset(x, z);
				for (int y = 0; y <= AltarShape.HEADROOM; y++) {
					BlockPos position = floor.offset(x, y, z);
					BlockState desired;
					if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && position.getY() == portal.getY()) {
						desired = ModBlocks.WORLD_INTERFACE_EXIT_PORTAL.defaultBlockState();
					} else if (AltarShape.isExitFrame(x, z) && position.getY() == portal.getY()) {
						desired = AltarShape.exitFrameState();
					} else if (y <= top) {
						desired = AltarShape.state(x, y, z, top);
					} else {
						desired = Blocks.AIR.defaultBlockState();
					}
					if (!level.getBlockState(position).equals(desired)) {
						level.setBlock(position, desired, flags);
					}
				}
			}
		}
	}

	/** The portal is durable, so a complete centre lets the per-tick recovery path become read-only. */
	private static boolean exitPortalComplete(ServerLevel level, BlockPos portal, BlockPos floor) {
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				BlockPos position = portal.offset(x, 0, z);
				if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
					if (!level.getBlockState(position).is(ModBlocks.WORLD_INTERFACE_EXIT_PORTAL)) return false;
				} else if (AltarShape.isExitFrame(x, z)) {
					if (!level.getBlockState(position).equals(AltarShape.exitFrameState())
							|| level.getBlockState(position.below()).isAir()) return false;
				}
			}
		}
		// The former core and the obsolete raised terrace must both be gone.
		return level.getBlockState(corePositionAbovePortal(portal)).isAir()
				&& level.getBlockState(floor.offset(2, 3, 0)).isAir();
	}

	private static BlockPos corePositionAbovePortal(BlockPos portal) {
		return portal.above();
	}

	private static void removeBossProjection(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		AABB bounds = new AABB(snapshot.arenaCenter()).inflate(256.0D, 192.0D, 256.0D);
		for (WorldInterfaceEntity entity : level.getEntitiesOfClass(WorldInterfaceEntity.class, bounds,
				candidate -> snapshot.encounterId().filter(id -> id.equals(candidate.encounterId())).isPresent())) {
			entity.discard();
		}
		for (WorldInterfacePartEntity part : level.getEntitiesOfClass(WorldInterfacePartEntity.class, bounds,
				Entity::isAlive)) {
			part.discard();
		}
	}

	private static WorldInterfaceState.Snapshot clearFinishedBossIdentity(MinecraftServer server,
			WorldInterfaceState.Snapshot initial) {
		WorldInterfaceState.Snapshot snapshot = initial;
		for (int attempt = 0; attempt < MAX_MUTATION_RETRIES && snapshot.bossUuid().isPresent(); attempt++) {
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server,
					snapshot.encounterId().orElseThrow(), snapshot.revision(), WorldInterfaceState.MutableState::clearBossUuid);
			if (result.applied()) return result.snapshot();
			if (!"revision_mismatch".equals(result.reason())) return snapshot;
			snapshot = result.snapshot();
		}
		return snapshot;
	}

	/**
	 * Every anchor that survived releases its hold upward once the interface is gone.
	 *
	 * <p>This used to set a vanilla crystal's beam target, which the bespoke anchor has no
	 * equivalent of - and should not, because its tether is drawn from the encounter snapshot
	 * rather than from a per-entity target. The beat is kept as a bounded column of light off each
	 * standing relay core, which is the same statement without a second, competing beam system.</p>
	 */
	private static void pointLivingAnchorsSkyward(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		for (WorldInterfaceState.Anchor anchor : snapshot.anchors()) {
			if (anchor.destroyed() || anchor.anchorEntityUuid().isEmpty()) continue;
			Entity entity = level.getEntity(anchor.anchorEntityUuid().orElseThrow());
			if (entity instanceof StabilityAnchorEntity standing) standing.setInvulnerable(true);
			Vec3 relay = StabilityAnchorGeometry.relayCore(anchor.position());
			level.sendParticles(ParticleTypes.END_ROD, relay.x, relay.y, relay.z,
					40, 0.22D, 5.0D, 0.22D, 0.34D);
		}
	}

	private static void persistTerrainBudget(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		int actual = EndBossArenaService.permanentTerrainEdits(level);
		if (actual == snapshot.terrainEditsUsed()) return;
		WorldInterfaceState.mutate(level.getServer(), snapshot.encounterId().orElseThrow(), snapshot.revision(),
				state -> state.setTerrainEditsUsed(actual));
	}

	/**
	 * The nearest participant, for the body's vertical follow and the three heads' gaze.
	 *
	 * <p>Roster-filtered for the same reason {@link #arenaParticipants} is: a bystander must not be
	 * able to lead the body around the island or take its stare off the people actually fighting.
	 */
	private static Optional<ServerPlayer> nearestArenaParticipant(ServerLevel level,
			WorldInterfaceState.Snapshot snapshot, Vec3 origin) {
		return level.players().stream()
				.filter(player -> snapshot.frozenRoster().contains(player.getUUID()))
				.filter(player -> player.isAlive() && !player.isSpectator())
				.filter(player -> player.distanceToSqr(snapshot.arenaCenter().getCenter())
						<= ENCOUNTER_VISIBILITY_RADIUS_SQR)
				.min(Comparator.comparingDouble(player -> player.distanceToSqr(origin)));
	}

	private static int onlineFrozenCount(MinecraftServer server, WorldInterfaceState.Snapshot snapshot) {
		int online = 0;
		for (UUID playerId : snapshot.frozenRoster()) {
			if (server.getPlayerList().getPlayer(playerId) != null) online++;
		}
		return online;
	}

	private static long effectiveActiveTicks(WorldInterfaceState.Snapshot snapshot, long gameTime) {
		if (snapshot.runningSinceGameTime() < 0L) return snapshot.activeTicks();
		long delta = Math.max(0L, gameTime - snapshot.runningSinceGameTime());
		return snapshot.activeTicks() > Long.MAX_VALUE - delta ? Long.MAX_VALUE
				: snapshot.activeTicks() + delta;
	}

	private static void advanceToHealthStage(WorldInterfaceState.MutableState state, double healthRatio) {
		WorldInterfaceStage startingStage = state.stage();
		WorldInterfaceStage desired = WorldInterfaceStage.advanceCombatStage(state.stage(), healthRatio);
		while (state.stage().wireId() < desired.wireId()) state.transitionTo(nextCombatStage(state.stage()));
		if (desired != state.stage()) throw new IllegalStateException("phase_advance_failed");
		if (state.stage() != startingStage) {
			state.clearCurrentAttack();
			state.setRecoveryGraceTicks(40);
			state.setActionSchedule(state.actionSequence(), state.lastActionWireId(), state.activeTicks() + 40L);
		}
	}

	private static void advanceToPhaseThree(WorldInterfaceState.MutableState state) {
		while (state.stage().wireId() < WorldInterfaceStage.PHASE_3.wireId()) {
			state.transitionTo(nextCombatStage(state.stage()));
		}
	}

	private static WorldInterfaceStage nextCombatStage(WorldInterfaceStage stage) {
		return switch (stage) {
			case PHASE_1 -> WorldInterfaceStage.PHASE_2;
			case PHASE_2 -> WorldInterfaceStage.PHASE_3;
			default -> throw new IllegalStateException("not_advancing_combat");
		};
	}

	private static boolean bossMatches(WorldInterfaceState.Snapshot snapshot, WorldInterfaceEntity boss) {
		return snapshot.valid() && snapshot.present()
				&& snapshot.encounterId().filter(id -> id.equals(boss.encounterId())).isPresent()
				&& snapshot.bossUuid().filter(id -> id.equals(boss.getUUID())).isPresent();
	}

	private static boolean duplicateProjectileHit(MinecraftServer server, UUID projectileId, long tick) {
		Map<UUID, Long> hits = PROJECTILE_HITS.computeIfAbsent(server, ignored -> new HashMap<>());
		Long previous = hits.get(projectileId);
		if (previous != null && previous + 40L >= tick) return true;
		hits.put(projectileId, tick);
		if (hits.size() > 256) hits.entrySet().removeIf(entry -> entry.getValue() + 40L < tick);
		return false;
	}

	private static boolean duplicateMeleeHit(MinecraftServer server, UUID attackerId, long tick) {
		Map<UUID, Long> hits = MELEE_HITS.computeIfAbsent(server, ignored -> new HashMap<>());
		Long previous = hits.put(attackerId, tick);
		if (hits.size() > 256) hits.entrySet().removeIf(entry -> entry.getValue() < tick);
		return previous != null && previous == tick;
	}

	private static UUID deterministicEntityUuid(String role, UUID encounterId) {
		return UUID.nameUUIDFromBytes(("thefourthfrequency:world_interface:" + role + ":" + encounterId)
				.getBytes(StandardCharsets.UTF_8));
	}

	private static int formForStage(WorldInterfaceStage stage) {
		return switch (stage) {
			case PHASE_2 -> WorldInterfaceEntity.FORM_CONSUMING;
			case PHASE_3, SUCCESS_RESOLUTION, FAILURE_RESOLUTION, PORTAL_OPEN, COMPLETE ->
					WorldInterfaceEntity.FORM_INTERFACE;
			default -> WorldInterfaceEntity.FORM_LISTENING;
		};
	}

	private static int outcomeWire(WorldInterfaceState.Outcome outcome) {
		return switch (outcome) {
			case NONE -> WorldInterfaceProtocol.Outcome.NONE.wireId();
			case SUCCESS -> WorldInterfaceProtocol.Outcome.SUCCESS.wireId();
			case FAILURE -> WorldInterfaceProtocol.Outcome.FAILURE.wireId();
		};
	}

	private static List<WorldInterfaceState.Gate> indexedGates(EndBossArenaService.PreparedArena arena) {
		List<WorldInterfaceState.Gate> gates = new ArrayList<>(EndBossArenaService.GATEWAY_COUNT);
		for (int index = 0; index < arena.gatewayCorePositions().size(); index++) {
			gates.add(new WorldInterfaceState.Gate(index, arena.gatewayCorePositions().get(index),
					WorldInterfaceGatewayState.DORMANT));
		}
		return List.copyOf(gates);
	}

	private static List<WorldInterfaceState.Anchor> indexedAnchors(EndBossArenaService.PreparedArena arena) {
		return arena.anchors().stream().sorted(Comparator.comparingInt(EndBossArenaService.AnchorSlot::index))
				.map(anchor -> new WorldInterfaceState.Anchor(anchor.index(), anchor.position(),
						Optional.of(anchor.anchorEntityUuid()), false)).toList();
	}

	private static void bindCore(ServerLevel level, WorldInterfaceState.Snapshot snapshot) {
		if (snapshot.encounterId().isPresent()
				&& level.getBlockEntity(snapshot.altarCenter()) instanceof ResonanceCoreBlockEntity core) {
			core.bind(snapshot.encounterId().orElseThrow(), snapshot.revision());
		}
	}

	private static WorldInterfaceProtocol.AltarStatus altarStatus(String reason) {
		return WorldInterfaceProtocol.AltarStatus.fromReason(reason);
	}

	private static void recordAltarOpening(ServerPlayer player, ServerLevel level, BlockPos center) {
		FrequencyWorldData data = FrequencyWorldData.get(level.getServer());
		if (data.terminalRecord(player.getUUID()).isPresent()) {
			data.updateTerminalRecord(player.getUUID(), record -> {
				record.putBoolean(TerminalData.PORTAL_ROOM_FOUND, true);
				record.putLong(TerminalData.PORTAL_ROOM_POSITION, center.asLong());
				record.putString(TerminalData.PORTAL_ROOM_DIMENSION, level.dimension().identifier().toString());
				record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
						record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0)
								| SurvivalMilestone.FOUND_STRONGHOLD.mask());
			});
		}
		TerminalSignalService.record(player, SignalBand.UNKNOWN, "world_interface_altar", 0, 2, true);
		TerminalNoticeService.encounter(player, Component.translatable(
				"message.thefourthfrequency.world_interface.altar_prepared"));
		announceAltarPrepared(level.getServer(), player);
	}

	/**
	 * Tells the rest of the world that the End has changed, because it has.
	 *
	 * <p>The twelfth Eye is placed by one person and its consequences are not theirs: the main island
	 * is rebuilt, the native fight is retired and every End portal from here on delivers into the
	 * arena. Everyone else used to learn all of that by walking into it. Whoever was not holding the
	 * Eye is exactly the player who most needs the operable half of the sentence - something happened,
	 * it happened in the End - and they get it on the same channel and in the same record the rest of
	 * the story arrives on, rather than in chat.
	 *
	 * <p>Impersonal on purpose. It names no coordinates and no player: what the altar is for is
	 * already written in {@code FILES}, and a line that reads like a quest marker would answer a
	 * question the ritual is supposed to make the party ask each other.
	 */
	private static void announceAltarPrepared(MinecraftServer server, ServerPlayer activator) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (online.getUUID().equals(activator.getUUID())
					|| data.terminalRecord(online.getUUID()).isEmpty()) continue;
			TerminalSignalService.record(online, SignalBand.UNKNOWN, "world_interface_altar", 0, 2, true);
			TerminalNoticeService.encounter(online, Component.translatable(
					"message.thefourthfrequency.world_interface.altar_prepared_elsewhere"));
		}
	}

	private static void markDefeatedMilestone(MinecraftServer server, Set<UUID> roster) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (UUID playerId : roster) {
			if (data.terminalRecord(playerId).isEmpty()) continue;
			data.updateTerminalRecord(playerId, record -> record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
					record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0)
							| SurvivalMilestone.DEFEATED_BOSS.mask()));
		}
	}

	/**
	 * The finale's narration goes to the mod's own notice stack rather than to chat.
	 *
	 * <p>In the chat log every one of these lines - the fight starting, an anchor falling, the
	 * dragon speaking, the phase turning over - was the same white text in the same scroll as death
	 * messages and player conversation, at the exact moment the player has the least attention to
	 * spend parsing it. The stack gives each channel its own colour and holds it above the hotbar
	 * where the rest of the encounter's feedback already lives.</p>
	 *
	 * <p><b>"Broadcast" means the encounter, not the server.</b> It used to mean every player online,
	 * which is the same mistake {@code arenaParticipants} and {@code encounterRecipients} were each
	 * built to correct and this one channel never got: someone mining in the Overworld, who is not on
	 * the roster and cannot see the End, received the fight starting, every anchor falling, every line
	 * the dragon says and every phase turning over. On a populated server that is a running commentary
	 * on somebody else's ten minutes, arriving on the same stack the terminal uses to tell them things
	 * that are about them. The recipients are the ones already defined as inside it - the frozen
	 * roster, plus anyone standing in the End, who is inside it whether or not they committed.
	 */
	private static void broadcast(MinecraftServer server, int tone, String key, Object... arguments) {
		Component message = Component.translatable(key, arguments);
		for (ServerPlayer player : encounterRecipients(server, WorldInterfaceState.snapshot(server))) {
			switch (tone) {
				case TerminalNoticePayload.TONE_ANCHOR -> TerminalNoticeService.anchor(player, message);
				case TerminalNoticePayload.TONE_DRAGON -> TerminalNoticeService.dragon(player, message);
				default -> TerminalNoticeService.encounter(player, message);
			}
		}
	}

}
