package com.xm.thefourthfrequency.ending;

import com.mojang.serialization.DynamicOps;
import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.entity.WorldInterfaceAnatomy;
import com.xm.thefourthfrequency.entity.WorldInterfaceEnergyOrbEntity;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-authoritative transient executor for the nine world-interface actions. */
public final class WorldInterfaceAttackService {
	private static final int LASER_WARNING_TICKS = WorldInterfaceProtocol.LASER_WARNING_TICKS;
	private static final int LASER_SWEEP_TICKS = WorldInterfaceProtocol.LASER_SWEEP_TICKS;
	private static final int LASER_TRACKING_LAG_TICKS = WorldInterfaceProtocol.LASER_TRACKING_LAG_TICKS;
	/**
	 * How close the beam has to pass to burn. Wide enough that standing still is fatal, tight enough
	 * that the lag the beam is drawn with is genuinely enough to walk out of - it has to stay under
	 * what {@link WorldInterfaceProtocol#LASER_TRACKING_LAG_TICKS} of running actually covers, or
	 * the trailing beam catches up with a moving player anyway and the mechanic is a lie.
	 */
	private static final double LASER_BURN_RADIUS = 2.2D;
	private static final float LASER_BURN_DAMAGE = 3.0F;
	/**
	 * First-form values only; the live intervals come from {@link WorldInterfacePhasePressure}.
	 *
	 * <p>Kept as the documented baseline the policy's first entry has to agree with, which
	 * {@code WorldInterfacePhasePressureTest} asserts directly rather than by eye.
	 */
	static final int LASER_BURN_INTERVAL_TICKS = 5;
	static final int LASER_SCAR_INTERVAL_TICKS = 2;
	private static final int LASER_SCAR_RADIUS = 2;
	private static final int LASER_SCAR_EDITS = 6;
	/**
	 * How often the shaft sheds, and how many points along it shed when it does.
	 *
	 * <p>Eight points every other tick, whatever the beam's length: a shot across the island and a
	 * shot at somebody standing under the core cost the same, and the spacing between the beads is
	 * what carries the distance instead. Sampling at a fixed interval in blocks would have made a
	 * long shot forty packets a tick, which is the whole particle budget of the fight spent on one
	 * attack.</p>
	 */
	private static final int LASER_SHAFT_INTERVAL_TICKS = 2;
	private static final int LASER_SHAFT_SAMPLES = 8;
	/** How far down the shaft the muzzle discharge reaches, in blocks. */
	private static final double LASER_MUZZLE_REACH = 12.0D;
	/** Rings stacked across the line of fire on the frame the beam leaves the core. */
	private static final int LASER_MUZZLE_RINGS = 3;
	/** The two threads wound around the shaft: how wide, how finely sampled, how tight. */
	private static final double LASER_HELIX_RADIUS = 0.75D;
	private static final int LASER_HELIX_SAMPLES = 44;
	/** Rings travelling down the shaft, so the sleeve of light has a cross-section. */
	private static final int LASER_COLLARS = 4;
	private static final double LASER_HELIX_TURNS_PER_BLOCK = 0.06D;
	/** Discharge leaving the shaft sideways: how often, how many, how far. */
	private static final long LASER_FORK_INTERVAL_TICKS = 3L;
	private static final int LASER_FORKS = 3;
	private static final double LASER_FORK_REACH = 4.5D;
	/**
	 * How far the beam's contact detonation is felt, in blocks.
	 *
	 * <p>Smaller than the shots that land once. The contact is a burn walking across the floor rather
	 * than an arrival, so it should be felt by whoever it is walking toward and by nobody else.
	 */
	private static final double LASER_CONTACT_SHAKE_RADIUS = 42.0D;
	/** The lance is the encounter's heaviest single impact; it is felt from most of the island. */
	private static final double SKY_LANCE_SHAKE_RADIUS = 72.0D;
	/** One limb coming down. Local, and there are three of them a flurry. */
	private static final double TENDRIL_SHAKE_RADIUS = 34.0D;
	/** Ticks between the ticking that counts a lash down. */
	/** The limb's descent, drawn from the core to the mark: how wide, how finely, how jagged. */
	private static final double TENDRIL_TRACE_RADIUS = 1.5D;
	private static final int TENDRIL_TRACE_SAMPLES = 26;
	private static final int TENDRIL_TRACE_SEGMENTS = 10;
	private static final int ORB_WARNING_TICKS = WorldInterfaceProtocol.ORB_WARNING_TICKS;
	private static final int ORB_TRACKING_TICKS = WorldInterfaceEnergyOrbEntity.MAX_FLIGHT_TICKS;
	private static final int GRAB_WARNING_TICKS = WorldInterfaceProtocol.GRAB_WARNING_TICKS;
	/** Carried off the ground rather than teleported: the lift is an interpolated arc, not a jump. */
	private static final int GRAB_LIFT_TICKS = WorldInterfaceProtocol.GRAB_LIFT_TICKS;
	/**
	 * Ticks the tendrils spend dragging the victim out from under the body before letting go.
	 *
	 * <p>Longer than it was, because it now has somewhere to take them. See {@link #THROW_CLEARANCE}.
	 */
	private static final int THROW_WINDUP_TICKS = 16;
	/** Ticks the envelope stays open after the launch, purely so the release animation can play out. */
	private static final int THROW_RELEASE_TICKS = 16;
	/**
	 * How far past the edge of the body the wind-up carries the victim before the launch.
	 *
	 * <p>The throw used to release from directly under the interface and fling the victim upward
	 * through it. At third form that is a thirty-three-block-wide mass overhead, and no launch
	 * impulse survives contact with it: a player thrown hard enough to clear the silhouette
	 * horizontally has already risen into it, and one thrown gently enough not to has not gone
	 * anywhere. The fix is not a bigger number, it is a different release point - the tendrils swing
	 * them out past the rim first, and the launch happens in open air.</p>
	 */
	private static final double THROW_CLEARANCE = 5.0D;
	/** Blocks below the underside of the mass that a carried player is held at. */
	private static final double CARRY_HOLD_DROP = 1.5D;
	/** A carry always lifts the victim at least this far, however low the body is hanging. */
	private static final double CARRY_MIN_LIFT = 2.5D;
	/**
	 * How far the tendrils can actually reach when they close, measured from the interface.
	 *
	 * <p>Generous - the third form is thirty-three blocks across - but finite, which is the point:
	 * it is the number that makes the grab's lock window worth reacting to.
	 */
	private static final double GRAB_REACH = 26.0D;
	/**
	 * Launch velocity, in blocks per tick. Under vanilla gravity this peaks around thirty blocks up,
	 * which is high enough that the fall is lethal without a clutch and long enough that there is
	 * time to make one.
	 */
	private static final double THROW_LAUNCH_SPEED = 2.5D;
	/**
	 * Outward speed of the throw, in blocks per tick.
	 *
	 * <p>Paired with the upward launch to make an arc rather than a column. Player horizontal motion
	 * decays by about nine percent a tick in the air, so the total outward travel is roughly eleven
	 * times this - the old 0.62 was worth under seven blocks, which alongside a thirty-block climb
	 * read as a vertical column and, at third form, was less than half the body's own radius. At
	 * this rate the victim covers something like twenty blocks away from the interface over the
	 * flight that takes them thirty blocks up: far enough that landing is a journey back, close
	 * enough that the arena still catches them.</p>
	 */
	private static final double THROW_LAUNCH_DRIFT = 1.85D;
	private static final int SKY_LANCE_LOCK_TICKS = WorldInterfaceProtocol.SKY_LANCE_LOCK_TICKS;
	private static final int SKY_LANCE_CHARGE_TICKS = WorldInterfaceProtocol.SKY_LANCE_CHARGE_TICKS;
	private static final int SKY_LANCE_STRIKE_TICKS = WorldInterfaceProtocol.SKY_LANCE_STRIKE_TICKS;
	/** First-form value only; the live radius comes from {@link WorldInterfacePhasePressure}. */
	static final double SKY_LANCE_RADIUS = 3.6D;
	private static final float SKY_LANCE_DAMAGE = 15.0F;
	/** The lance is the encounter's one true crater; it is allowed to take a bite out of the island. */
	private static final int SKY_LANCE_SCAR_RADIUS = 7;
	private static final int SKY_LANCE_SCAR_EDITS = 90;
	/** Blocks around the crater rim left as missing-texture proxies rather than removed. */
	/** Rings of the outward blast front. Each steps one lance radius further out. */
	private static final int SKY_LANCE_SHOCK_RINGS = 4;
	/**
	 * Ticks before the strike that the column starts filling in above the mark.
	 *
	 * <p>Half the charge. The whole charge would have been a pillar of light standing over the
	 * player for a second and a half, which is a landmark rather than a warning; a third of it is
	 * gone before it has been noticed. This is the beat where a player who has read the mark and
	 * decided to stay finds out they were wrong.</p>
	 */
	private static final long SKY_LANCE_GATHER_TICKS = SKY_LANCE_CHARGE_TICKS / 2;
	/** Points lit along the column per emission, whatever its height. */
	private static final int SKY_LANCE_COLUMN_SAMPLES = 9;
	/** The threads wound down the lance column while it fills. */
	private static final double SKY_LANCE_HELIX_RADIUS = 1.1D;
	private static final int SKY_LANCE_HELIX_SAMPLES = 40;
	private static final int SKY_LANCE_CORRUPTION_RADIUS = 9;
	private static final int SKY_LANCE_CORRUPTION_EDITS = 34;
	private static final int WEAPON_WARNING_TICKS = WorldInterfaceProtocol.WEAPON_WARNING_TICKS;
	private static final int WEAPON_CUSTODY_TICKS = 160;
	private static final int HOTBAR_WARNING_TICKS = WorldInterfaceProtocol.HOTBAR_WARNING_TICKS;
	private static final int HOTBAR_STEP_TICKS = 8;
	private static final int HOTBAR_SLOTS = 9;
	private static final int TENDRIL_WARNING_TICKS = WorldInterfaceProtocol.TENDRIL_WARNING_TICKS;
	private static final int TENDRIL_STRIKE_INTERVAL_TICKS =
			WorldInterfaceProtocol.TENDRIL_STRIKE_INTERVAL_TICKS;
	private static final int TENDRIL_STRIKE_TELEGRAPH_TICKS =
			WorldInterfaceProtocol.TENDRIL_STRIKE_TELEGRAPH_TICKS;
	private static final int TENDRIL_STRIKE_COUNT = WorldInterfaceProtocol.TENDRIL_STRIKE_COUNT;
	/**
	 * Radius of one lash. Small enough that the telegraph is worth reacting to: a sprint covers
	 * about five and a half blocks in the second the mark is on the ground, so leaving is possible
	 * from the centre of it and standing still is not survivable.
	 */
	private static final double TENDRIL_REACH = 5.0D;
	private static final float TENDRIL_DAMAGE = 8.0F;
	private static final int TENDRIL_SCAR_RADIUS = 3;
	private static final int TENDRIL_SCAR_EDITS = 12;
	/**
	 * Points on the landing ring, once per lash.
	 *
	 * <p>Eight, and only on the frame the limb lands. Three lashes a flurry and up to two flurries
	 * running at once in the third phase's volley lane, so this is one of the few emitters in the
	 * fight that can genuinely be multiplied by six - which is why it is a single frame each and not
	 * a state that lasts.</p>
	 */
	private static final int TENDRIL_LANDING_SAMPLES = 8;
	/** Dragon breath is a powered particle, so the option carries the drift rather than the type. */
	private static final PowerParticleOption CHARGE_PARTICLE =
			PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 0.4F);
	private static final Component EVICTION_REASON = Component.translatable(
			"hud.thefourthfrequency.world_interface.action.forced_expulsion");

	private static final Map<UUID, AttackRuntime> ACTIVE = new ConcurrentHashMap<>();
	/**
	 * The third form's second lane: attacks running alongside the scheduled one.
	 *
	 * <p>Transient by design, where {@link #ACTIVE} is mirrored into persisted encounter state. The
	 * persisted envelope is what lets a restart resume or cancel the scheduled attack cleanly and
	 * what the client draws its beam and lance geometry from, and it holds exactly one action - so
	 * making the volley persistent would mean a schema change, a migration and a protocol change for
	 * something a restart already throws away. A restart cancels the scheduled attack outright and
	 * grants recovery grace; the volley simply going quiet at the same moment is the same promise.
	 * Nothing in this lane touches the recovery ledger, so nothing in it can be lost that way.</p>
	 */
	private static final Map<UUID, List<AttackRuntime>> VOLLEY = new ConcurrentHashMap<>();
	/**
	 * What the volley is allowed to throw.
	 *
	 * <p>Two hard limits pick this set. Nothing that takes exclusive control of a player may run
	 * here - the grab, the confiscation, the purge and the eviction all write to the durable
	 * recovery ledger and share one global lane precisely so a player cannot be seized twice at
	 * once. And nothing whose presentation is drawn from the action envelope may run here either:
	 * the laser's shaft and the lance's column are resolved client-side from the one envelope the
	 * protocol carries, so a second one would burn along a beam nobody could see. What is left is
	 * the two attacks the server draws itself, which is enough for the sky to be full.</p>
	 */
	/**
	 * What the third form's second lane may open, on top of whatever the schedule is running.
	 *
	 * <p>The two aimed weapons were deliberately absent and are now deliberately present. Keeping
	 * them out made the last phase's extra lane a bolt-and-lash lane: it added pressure without ever
	 * adding a second thing to <em>read</em>, so the fight never actually asked the player to answer
	 * two different attacks at once. That is the one escalation the third form has left.
	 *
	 * <p>Listed twice each for the orb, so the barrage stays the most common thing the lane throws
	 * and the heavy weapons remain punctuation rather than the beat.
	 */
	private static final List<WorldInterfaceAction> VOLLEY_ACTIONS = List.of(
			WorldInterfaceAction.ENERGY_ORB, WorldInterfaceAction.ENERGY_ORB,
			WorldInterfaceAction.TENDRIL_LASH, WorldInterfaceAction.SKY_LANCE,
			WorldInterfaceAction.LASER_SWEEP);
	/** Extra attacks that may be in flight at once, on top of the scheduled one, for a solo table. */
	public static final int MAX_VOLLEY = 3;
	/**
	 * When each player was last singled out, per encounter, newest last.
	 *
	 * <p>What {@link WorldInterfaceTargetPolicy} weighs its picks against, and the only memory the
	 * fight has of who it has been paying attention to.
	 *
	 * <p>Transient, for the same reason {@link #VOLLEY} is. This decides who the <em>next</em> attack
	 * names and nothing else: no damage, no item, no persisted state depends on it, and an encounter
	 * that comes back from a restart with an empty ledger simply picks uniformly for the next thirty
	 * seconds until it has learned the table again. Persisting it would mean a schema version, a
	 * migration and a state-validator rule to protect a preference. A restart already cancels the
	 * running attack and grants recovery grace, so the fight forgetting who it was looking at in the
	 * same moment is the same promise it already makes.
	 */
	private static final Map<UUID, Map<UUID, ConcurrentLinkedDeque<Long>>> PRESSURE =
			new ConcurrentHashMap<>();
	/**
	 * Everyone an encounter has actually disconnected, so a second eviction reaches for somebody new.
	 *
	 * <p>Transient like the pressure ledger, and harmless in the same way: losing it on a restart
	 * returns the selection to the plain shuffle it used to be, never to something incorrect.
	 */
	private static final Map<UUID, Set<UUID>> EVICTED = new ConcurrentHashMap<>();
	/**
	 * When each player may have their hotbar swept again, by encounter.
	 *
	 * <p>The sweep is the least dodgeable thing the fight does that the player then has to clean up
	 * after: nine slots on the floor, mid-fight, with the collapse clock running. It is fair as a
	 * once-a-fight shock and oppressive as a recurring one, and the six-hundred-tick strong-control
	 * immunity every exclusive action shares was never long enough to keep it to the former.
	 *
	 * <p>Transient like the two ledgers above, and acceptable for the same reason: a restart already
	 * cancels the running attack and grants a recovery grace period, so the worst a lost cooldown
	 * costs is one extra sweep in a ten-minute fight. Persisting it would mean a format version and a
	 * migration for a number that only shapes pacing.
	 */
	private static final Map<UUID, Map<UUID, Long>> HOTBAR_SWEEPS = new ConcurrentHashMap<>();
	private static boolean initialized;

	private WorldInterfaceAttackService() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		initialized = true;
		ConfiscationService.initialize();
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			snapshot.encounterId().ifPresent(id -> onDisconnect(handler.player, id));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			snapshot.encounterId().ifPresent(id -> cancelAndRestore(server, id));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			VOLLEY.clear();
			PRESSURE.clear();
			EVICTED.clear();
			HOTBAR_SWEEPS.clear();
		});
	}

	/**
	 * How often each candidate has been singled out inside the policy's memory window.
	 *
	 * <p>Read-only and cheap: the scheduler calls this once per candidate action while it scans for
	 * something it can throw, so it must not write anything. Recording happens once, after an attack
	 * has actually been committed - see {@link #recordTargeting}.
	 *
	 * @param activeTick the encounter's own elapsed tick, which is the clock the window is measured on
	 * @return one count per candidate, in the order they were supplied
	 */
	public static int[] recentPicks(UUID encounterId, List<UUID> candidates, long activeTick) {
		Objects.requireNonNull(candidates, "candidates");
		int[] counts = new int[candidates.size()];
		Map<UUID, ConcurrentLinkedDeque<Long>> ledger = encounterId == null ? null : PRESSURE.get(encounterId);
		if (ledger == null) return counts;
		long horizon = activeTick - WorldInterfaceTargetPolicy.PRESSURE_MEMORY_TICKS;
		for (int index = 0; index < counts.length; index++) {
			ConcurrentLinkedDeque<Long> picks = ledger.get(candidates.get(index));
			if (picks == null) continue;
			prune(picks, horizon);
			counts[index] = picks.size();
		}
		return counts;
	}

	/**
	 * When each candidate was last singled out, {@link WorldInterfaceTargetPolicy#NEVER_PICKED} if
	 * this encounter has not named them inside the memory window.
	 *
	 * <p>Kept apart from {@link #recentPicks} rather than returned alongside it because the two
	 * answer different questions - how heavily the fight has leaned on someone, and whether it just
	 * did - and only the second one is a hard exclusion.
	 */
	public static long[] lastPickTicks(UUID encounterId, List<UUID> candidates, long activeTick) {
		Objects.requireNonNull(candidates, "candidates");
		long[] stamps = new long[candidates.size()];
		Arrays.fill(stamps, WorldInterfaceTargetPolicy.NEVER_PICKED);
		Map<UUID, ConcurrentLinkedDeque<Long>> ledger = encounterId == null ? null : PRESSURE.get(encounterId);
		if (ledger == null) return stamps;
		long horizon = activeTick - WorldInterfaceTargetPolicy.PRESSURE_MEMORY_TICKS;
		for (int index = 0; index < stamps.length; index++) {
			ConcurrentLinkedDeque<Long> picks = ledger.get(candidates.get(index));
			if (picks == null) continue;
			prune(picks, horizon);
			Long newest = picks.peekLast();
			if (newest != null) stamps[index] = newest;
		}
		return stamps;
	}

	/**
	 * Notes that an attack has been committed against these players.
	 *
	 * <p>Called once the envelope is stored rather than at selection time, because the scheduler's
	 * scan asks about several candidate actions before committing to one and only the committed pick
	 * is attention anybody in the arena can feel.
	 */
	public static void recordTargeting(UUID encounterId, Collection<UUID> targets, long activeTick) {
		if (encounterId == null || targets == null || targets.isEmpty()) return;
		Map<UUID, ConcurrentLinkedDeque<Long>> ledger =
				PRESSURE.computeIfAbsent(encounterId, ignored -> new ConcurrentHashMap<>());
		long horizon = activeTick - WorldInterfaceTargetPolicy.PRESSURE_MEMORY_TICKS;
		for (UUID target : targets) {
			if (target == null) continue;
			ConcurrentLinkedDeque<Long> picks =
					ledger.computeIfAbsent(target, ignored -> new ConcurrentLinkedDeque<>());
			picks.addLast(activeTick);
			prune(picks, horizon);
			// Bounded regardless of the window, so a stalled clock cannot grow this without limit.
			while (picks.size() > WorldInterfaceTargetPolicy.MAX_TRACKED_PICKS + 1) picks.pollFirst();
		}
	}

	private static void prune(ConcurrentLinkedDeque<Long> picks, long horizon) {
		Long oldest;
		while ((oldest = picks.peekFirst()) != null && oldest < horizon) picks.pollFirst();
	}

	/** Everyone this encounter has already thrown off the island; never null. */
	public static Set<UUID> evictedThisEncounter(UUID encounterId) {
		if (encounterId == null) return Set.of();
		Set<UUID> evicted = EVICTED.get(encounterId);
		return evicted == null ? Set.of() : Set.copyOf(evicted);
	}

	/** Drops the transient ledgers for an encounter that is over or restarting. */
	public static void clearLedgers(UUID encounterId) {
		if (encounterId == null) return;
		PRESSURE.remove(encounterId);
		EVICTED.remove(encounterId);
		HOTBAR_SWEEPS.remove(encounterId);
	}

	/** Whether this player's hotbar may be swept again yet. */
	public static boolean hotbarSweepReady(UUID encounterId, UUID playerId, long elapsed) {
		if (encounterId == null || playerId == null) return true;
		Map<UUID, Long> sweeps = HOTBAR_SWEEPS.get(encounterId);
		if (sweeps == null) return true;
		Long until = sweeps.get(playerId);
		return until == null || elapsed >= until;
	}

	/** Starts this player's sweep cooldown. Called once the sweep is actually committed. */
	public static void recordHotbarSweep(UUID encounterId, UUID playerId, long elapsed) {
		if (encounterId == null || playerId == null) return;
		HOTBAR_SWEEPS.computeIfAbsent(encounterId, ignored -> new ConcurrentHashMap<>())
				.put(playerId, Math.max(0L, elapsed)
						+ WorldInterfaceActionScheduler.HOTBAR_SWEEP_COOLDOWN_TICKS);
	}

	public static AttackStart begin(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, WorldInterfaceAction action,
			List<ServerPlayer> proposedTargets, long activeTick, long actionSequence) {
		initialize();
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(boss, "boss");
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(proposedTargets, "proposedTargets");
		if (activeTick < 0L || actionSequence < 0L) throw new IllegalArgumentException("Negative attack clock");
		if (!snapshot.present() || !snapshot.valid() || !action.isUnlockedAt(snapshot.stage())) {
			throw new IllegalArgumentException("Action is not valid for the current encounter stage");
		}
		UUID encounterId = snapshot.encounterId().orElseThrow();
		if (boss.encounterId() == null || !encounterId.equals(boss.encounterId())) {
			throw new IllegalArgumentException("Boss is not bound to this encounter");
		}
		if (ACTIVE.containsKey(encounterId)) throw new IllegalStateException("An encounter action is already active");

		List<UUID> targets = proposedTargets.stream()
				.filter(Objects::nonNull)
				.filter(player -> player.isAlive() && !player.isSpectator() && player.level() == level)
				.map(ServerPlayer::getUUID)
				.distinct()
				.sorted(Comparator.comparing(UUID::toString))
				.toList();
		if (requiresTarget(action) && targets.isEmpty()) {
			throw new IllegalArgumentException("Action requires at least one live target");
		}

		long seed = mix(snapshot.deterministicSeed() ^ actionSequence * 0x9E3779B97F4A7C15L
				^ ((long) action.wireId() << 56));
		int duration = durationTicks(action);
		AttackRuntime runtime = new AttackRuntime(encounterId, boss.getUUID(), action, activeTick,
				activeTick + duration, actionSequence, seed, targets, boss.blockPosition(),
				snapshot.arenaCenter(), snapshot.safeSpawn());
		// The bolt is no longer born here. It spawns at the end of its charge, in tickOrb, so that
		// the warning is a warning rather than a caption on a shot that has already been fired.
		if (ACTIVE.putIfAbsent(encounterId, runtime) != null) {
			throw new IllegalStateException("An encounter action started concurrently");
		}

		boss.showAction(action.wireId(), level.getGameTime(), duration);
		// At the core, not at the entity position - which is a bookkeeping point that now sits at or
		// below the arena floor, so a cast announced there was announced from inside the ground.
		playActionCue(level, BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss)), action,
				0.65F, 0.82F);
		WorldInterfaceState.AttackEnvelope envelope = runtime.envelope();
		return new AttackStart(envelope, action, duration, Set.copyOf(targets), seed);
	}

	public static AttackTick tick(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long activeTick) {
		Objects.requireNonNull(snapshot, "snapshot");
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (encounterId == null) return AttackTick.cancelledRestart();
		AttackRuntime runtime = ACTIVE.get(encounterId);
		if (runtime == null) {
			boss.clearAction();
			return AttackTick.cancelledRestart();
		}
		Optional<WorldInterfaceState.AttackEnvelope> stored = snapshot.currentAttack();
		if (stored.isEmpty() || stored.get().sequence() != runtime.sequence
				|| stored.get().actionWireId() != runtime.action.wireId()
				|| !runtime.bossId.equals(boss.getUUID())) {
			cancelRuntime(level.getServer(), runtime, true);
			return AttackTick.cancelledRestart();
		}
		invalidateUnavailableTargets(level, runtime);
		long elapsed = Math.max(0L, activeTick - runtime.startedActiveTick);
		boolean complete = switch (runtime.action) {
			case LASER_SWEEP -> tickLaser(level, boss, runtime, elapsed);
			case ENERGY_ORB -> tickOrb(level, boss, runtime, elapsed);
			case SKY_LANCE -> tickSkyLance(level, boss, runtime, elapsed);
			case CHARGE_WEAPON_STEAL -> tickWeaponSteal(level, boss, snapshot, runtime, elapsed);
			case GRAB_THROW -> tickGrabThrow(level, boss, runtime, elapsed);
			case GAZE_HOTBAR_CLEAR -> tickHotbar(level, runtime, elapsed);
			case TENDRIL_LASH -> tickTendrilLash(level, boss, runtime, elapsed);
			case FORCED_EVICTION -> tickEviction(level, runtime, elapsed);
		};
		WorldInterfaceState.AttackEnvelope replacement = runtime.envelope();
		if (!complete && activeTick < runtime.dueActiveTick) {
			return new AttackTick(AttackStatus.CONTINUE, Optional.of(replacement));
		}
		finishRuntime(level.getServer(), runtime, false);
		boss.clearAction();
		return new AttackTick(AttackStatus.COMPLETE, Optional.of(replacement));
	}

	/**
	 * Opens a third-phase volley: extra attacks started outside the scheduled lane and outside the
	 * persisted envelope, so several of them can be in the air at once and the scheduled attack does
	 * not have to wait for any of them.
	 *
	 * <p>Returns how many actually started. Refuses everything outside third phase, and trims itself
	 * against {@link #MAX_VOLLEY} so a stalled encounter cannot accumulate a wall of them.</p>
	 */
	public static int beginVolley(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, List<ServerPlayer> participants, long activeTick,
			long sequence) {
		initialize();
		Objects.requireNonNull(participants, "participants");
		if (snapshot == null || snapshot.stage() != WorldInterfaceStage.PHASE_3 || participants.isEmpty()) {
			return 0;
		}
		UUID encounterId = snapshot.encounterId().orElse(null);
		if (encounterId == null || !encounterId.equals(boss.encounterId())) return 0;
		List<AttackRuntime> lane = VOLLEY.computeIfAbsent(encounterId,
				ignored -> new CopyOnWriteArrayList<>());
		int roster = Math.clamp(participants.size(), 1, WorldInterfacePolicy.MAX_ROSTER_SIZE);
		int room = WorldInterfaceActionScheduler.volleyConcurrency(MAX_VOLLEY, roster) - lane.size();
		if (room <= 0) return 0;

		WorldInterfaceAction scheduled = snapshot.currentAttack()
				.flatMap(envelope -> WorldInterfaceAction.fromWireIdOrEmpty(envelope.actionWireId()))
				.orElse(null);
		// Everyone the encounter is already aiming at this instant: the scheduled attack's own
		// targets, plus whatever the rest of this lane is mid-flight against.
		//
		// This is the difference between a third phase that is busy and one that is a pile-on. The
		// lane used to roll its own seed with no knowledge of the scheduled attack at all, so a
		// player could be locked by the schedule and picked by three volley slots on the same tick -
		// four telegraphs, one person, and nothing any of the others had to answer for.
		//
		// A whole-roster action claims nobody. The lash names everyone by construction, so counting
		// its targets here would empty the pool on the spot and hand every later slot the fallback -
		// which is the exclusion silently doing nothing, in exactly the phase it matters most. Only
		// an attack that singles somebody out is holding them.
		Set<UUID> pressured = new HashSet<>();
		snapshot.currentAttack()
				.filter(envelope -> singlesSomebodyOut(envelope.targets().size(), participants.size()))
				.ifPresent(envelope -> pressured.addAll(envelope.targets()));
		for (AttackRuntime running : lane) {
			if (singlesSomebodyOut(running.targets.size(), participants.size())) {
				pressured.addAll(running.targets);
			}
		}
		int wanted = Math.min(room, WorldInterfaceActionScheduler.volleySize(
				snapshot.deterministicSeed(), activeTick, roster));
		int started = 0;
		for (int slot = 0; slot < wanted; slot++) {
			long seed = mix(snapshot.deterministicSeed() ^ (activeTick * 0x9E3779B97F4A7C15L)
					^ ((long) slot << 40));
			WorldInterfaceAction action = VOLLEY_ACTIONS.get(
					(int) Math.floorMod(seed >>> 16, VOLLEY_ACTIONS.size()));
			// Bolts stack happily - a barrage from three directions is the point. A second flurry of
			// lashes on top of a running one would just be the same three impacts drawn twice.
			//
			// Lances stack too, and are meant to: several marked circles opening across the island
			// at once is the third form's signature, and each one is still a place you can simply
			// not be standing.
			//
			// Beams do not. One sweeping beam already reaches the whole island and is drawn across
			// everybody's screen; two at once is not twice the threat, it is an unreadable frame
			// with damage in it. So the lane will only ever add a beam when there is not one
			// already, the schedule included.
			if ((action == WorldInterfaceAction.TENDRIL_LASH || action == WorldInterfaceAction.LASER_SWEEP)
					&& (scheduled == action || laneHolds(lane, action))) {
				action = WorldInterfaceAction.ENERGY_ORB;
			}
			List<UUID> targets = volleyTargets(encounterId, action, participants, pressured, seed,
					activeTick);
			if (targets.isEmpty() && requiresTarget(action)) continue;
			AttackRuntime runtime = new AttackRuntime(encounterId, boss.getUUID(), action, activeTick,
					activeTick + durationTicks(action), sequence, seed, targets, boss.blockPosition(),
					snapshot.arenaCenter(), snapshot.safeSpawn());
			lane.add(runtime);
			// A whole-roster action is not attention on anybody in particular, so it neither claims
			// the roster against the next slot nor counts against anyone in the ledger.
			if (singlesSomebodyOut(targets.size(), participants.size())) {
				pressured.addAll(targets);
				recordTargeting(encounterId, targets, activeTick);
			}
			started++;
		}
		return started;
	}

	/**
	 * Advances every volley attack and retires the finished ones. Separate from {@link #tick} because
	 * these own no persisted envelope: there is nothing to reconcile, only work to run.
	 */
	public static void tickVolley(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, long activeTick) {
		UUID encounterId = snapshot == null ? null : snapshot.encounterId().orElse(null);
		if (encounterId == null) return;
		List<AttackRuntime> lane = VOLLEY.get(encounterId);
		if (lane == null || lane.isEmpty()) return;
		if (!snapshot.stage().isCombat() || !encounterId.equals(boss.encounterId())) {
			clearVolley(level.getServer(), encounterId);
			return;
		}
		for (AttackRuntime runtime : lane) {
			invalidateUnavailableTargets(level, runtime);
			long elapsed = Math.max(0L, activeTick - runtime.startedActiveTick);
			boolean complete = switch (runtime.action) {
				case ENERGY_ORB -> tickOrb(level, boss, runtime, elapsed);
				case TENDRIL_LASH -> tickTendrilLash(level, boss, runtime, elapsed);
				// Unreachable while VOLLEY_ACTIONS holds only the two above, and a safe stop if it
				// ever grows: an action this lane cannot drive is retired rather than left running.
				default -> true;
			};
			if (complete || activeTick >= runtime.dueActiveTick) {
				discardVolleyOrb(level.getServer(), runtime);
				lane.remove(runtime);
			}
		}
	}

	/**
	 * Whether an attack is attention on particular people rather than on the arena.
	 *
	 * <p>The one rule both the exclusion and the attention ledger are built on. An action aimed at
	 * everybody present says nothing about who the fight is leaning on, and treating it as though it
	 * did is the difference between a working preference and one that quietly always yields.
	 */
	private static boolean singlesSomebodyOut(int targetCount, int participantCount) {
		return targetCount > 0 && targetCount < participantCount;
	}

	private static boolean laneHolds(List<AttackRuntime> lane, WorldInterfaceAction action) {
		for (AttackRuntime runtime : lane) if (runtime.action == action) return true;
		return false;
	}

	/**
	 * A lash swings at whoever is nearest when it lands, so it takes the whole island. Everything
	 * else in this lane picks one player, and picks them from whoever the fight is not already
	 * aiming at.
	 *
	 * <p>The exclusion is a preference rather than a rule: if every candidate is already under
	 * something, the bolt is thrown at one of them anyway. A volley slot that declined to fire would
	 * make a table of two quieter than a table of one, which is the opposite of what this lane is
	 * for.</p>
	 */
	private static List<UUID> volleyTargets(UUID encounterId, WorldInterfaceAction action,
			List<ServerPlayer> participants, Set<UUID> pressured, long seed, long activeTick) {
		List<UUID> roster = participants.stream()
				.filter(player -> player.isAlive() && !player.isSpectator())
				.map(ServerPlayer::getUUID)
				.sorted(Comparator.comparing(UUID::toString))
				.toList();
		if (roster.isEmpty()) return List.of();
		if (action == WorldInterfaceAction.TENDRIL_LASH) return roster;
		List<UUID> free = roster.stream().filter(id -> !pressured.contains(id)).toList();
		List<UUID> pool = free.isEmpty() ? roster : free;
		return List.of(pool.get(WorldInterfaceTargetPolicy.selectIndex(
				recentPicks(encounterId, pool, activeTick),
				lastPickTicks(encounterId, pool, activeTick), seed, activeTick)));
	}

	private static void clearVolley(MinecraftServer server, UUID encounterId) {
		List<AttackRuntime> lane = VOLLEY.remove(encounterId);
		if (lane == null) return;
		for (AttackRuntime runtime : lane) discardVolleyOrb(server, runtime);
	}

	private static void discardVolleyOrb(MinecraftServer server, AttackRuntime runtime) {
		if (runtime.orbId == null) return;
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		Entity orb = level.getEntity(runtime.orbId);
		if (orb != null) orb.discard();
	}

	/**
	 * Drops a transient runtime that no longer has a persisted envelope behind it.
	 *
	 * <p>The two are meant to be mirrors: {@link #ACTIVE} holds the live executor, the encounter state
	 * holds the record a restart can resume from, and {@link #tick} is what keeps them in step. But
	 * {@code tick} is only ever called while the <em>envelope</em> exists, so the one direction it
	 * cannot repair is a runtime that outlives its envelope. In that state the scheduler asks
	 * {@link #begin} for the next attack, {@code begin} refuses because an action is already active,
	 * and the refusal repeats forever - the encounter stops attacking and says nothing at all.
	 *
	 * <p>Called from the scheduler on the path where the envelope is known to be absent, so it costs a
	 * map lookup on a fight that is behaving and repairs one that is not. The runtime is cancelled
	 * rather than dropped, so anything it was holding - a seized weapon, a controlled player, a bolt
	 * in the air - goes back through the same teardown a normal cancellation uses.
	 *
	 * @return whether an orphan was actually found
	 */
	public static boolean discardOrphanedRuntime(MinecraftServer server, UUID encounterId) {
		AttackRuntime orphan = ACTIVE.remove(encounterId);
		if (orphan == null) return false;
		TheFourthFrequency.LOGGER.warn(
				"World-interface discarded an orphaned {} runtime (sequence {}): it outlived its"
						+ " persisted envelope and would have blocked every later attack",
				orphan.action, orphan.sequence);
		cancelRuntime(server, orphan, true);
		return true;
	}

	public static int cancelAndRestore(MinecraftServer server, UUID encounterId) {
		AttackRuntime runtime = ACTIVE.remove(encounterId);
		if (runtime != null) cancelRuntime(server, runtime, true);
		clearVolley(server, encounterId);
		return restoreRecoveryEntries(server, encounterId, null);
	}

	public static void onDisconnect(ServerPlayer player, UUID encounterId) {
		MinecraftServer server = Objects.requireNonNull(player.level().getServer(), "server");
		markVolleyTargetGone(encounterId, player.getUUID());
		AttackRuntime runtime = ACTIVE.get(encounterId);
		if (runtime != null) {
			runtime.unavailableTargets.add(player.getUUID());
			releasePlayerControl(server, runtime, player.getUUID(), true);
			restoreForcedHotbarSlot(runtime, player);
			if (runtime.weapon != null && runtime.weapon.playerId.equals(player.getUUID())) runtime.weapon = null;
		}
		restoreRecoveryEntries(server, encounterId, player.getUUID());
	}

	public static void onDeath(ServerPlayer player, UUID encounterId) {
		MinecraftServer server = Objects.requireNonNull(player.level().getServer(), "server");
		markVolleyTargetGone(encounterId, player.getUUID());
		AttackRuntime runtime = ACTIVE.get(encounterId);
		if (runtime != null) {
			runtime.unavailableTargets.add(player.getUUID());
			releasePlayerControl(server, runtime, player.getUUID(), false);
			restoreForcedHotbarSlot(runtime, player);
			if (runtime.weapon != null && runtime.weapon.playerId.equals(player.getUUID())) runtime.weapon = null;
		}
		restoreRecoveryEntries(server, encounterId, player.getUUID());
	}

	/** Keeps a volley from keeping a player it can no longer see on its books. */
	private static void markVolleyTargetGone(UUID encounterId, UUID playerId) {
		List<AttackRuntime> lane = VOLLEY.get(encounterId);
		if (lane == null) return;
		for (AttackRuntime runtime : lane) runtime.unavailableTargets.add(playerId);
	}

	public static int onRestart(MinecraftServer server, UUID encounterId) {
		AttackRuntime runtime = ACTIVE.remove(encounterId);
		if (runtime != null) cancelRuntime(server, runtime, true);
		clearVolley(server, encounterId);
		// The attention ledger is a preference, not state to be recovered. A restart hands the fight
		// back a table it has not looked at yet, which is the same thing it hands back everywhere else.
		clearLedgers(encounterId);
		clearRestartTransients(server, encounterId);
		return restoreRecoveryEntries(server, encounterId, null);
	}

	private static void clearRestartTransients(MinecraftServer server, UUID encounterId) {
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		List<Entity> stale = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof WorldInterfaceEnergyOrbEntity orb
					&& encounterId.equals(orb.encounterId())) {
				stale.add(orb);
			}
		}
		stale.forEach(Entity::discard);
		// A restart must not leave a player holding a placeholder for a custody that no longer exists.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ConfiscationService.clearPlaceholders(player);
		}
	}

	public static int restorePendingFor(ServerPlayer player, UUID encounterId) {
		return restoreRecoveryEntries(Objects.requireNonNull(player.level().getServer(), "server"),
				encounterId, player.getUUID());
	}

	/**
	 * The laser no longer resolves as a single shot down a frozen line. It locks a player, warns
	 * them, and then sweeps: every tick the beam is aimed at where the target was
	 * {@link WorldInterfaceProtocol#LASER_TRACKING_LAG_TICKS} ticks ago. Standing still puts the
	 * player exactly where the beam is about to be; running puts the beam behind them. The client
	 * resolves the same lagged aim point, so the shaft that is drawn is the shaft that burns.
	 */
	private static boolean tickLaser(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, long elapsed) {
		ServerPlayer target = onlineTarget(level, runtime, 0);
		if (target != null) runtime.recordAim(target.getEyePosition());
		if (elapsed < LASER_WARNING_TICKS) {
			announceLock(level, runtime, target, elapsed, LASER_WARNING_TICKS);
			// Each firing mouth gathers light through the warning, before its beam can burn.
			for (int beam = 0; beam < WorldInterfacePhasePressure.laserBeamCount(boss.form()); beam++) {
				chargeMouth(level, boss, elapsed, LASER_WARNING_TICKS,
						WorldInterfacePhasePressure.laserHead(boss.form(), beam));
			}
			return false;
		}
		Vec3 start = WorldInterfaceAnatomy.mouthOrigin(boss, 0);
		Vec3 aim = runtime.laggedAim(WorldInterfacePhasePressure.laserTrackingLagTicks(boss.form()));
		if (aim == null) {
			aim = target != null ? target.getEyePosition()
					: start.add(boss.getLookAngle().scale(48.0D));
		}
		Vec3 primaryEnd = groundUnder(level, aim);
		long sweptTicks = elapsed - LASER_WARNING_TICKS;
		int beams = WorldInterfacePhasePressure.laserBeamCount(boss.form());
		if (sweptTicks == 0L) {
			// One cue however many beams leave, because it is one shot. Two samples fired on the
			// same tick from the same core is a doubled sample, not a bigger gun.
			AudioService.playBounded(level, BlockPos.containing(start),
					ModSounds.WORLD_INTERFACE_LASER_FIRE, SoundSource.HOSTILE, 1.0F, 1.0F);
			runtime.damageApplied = true;
		}
		// Damage is deduplicated across the beams: standing where a split crosses is already worse
		// because both halves sweep over you in turn, and paying twice on the tick they overlap is
		// a spike nothing on the screen explains.
		Set<UUID> burned = new HashSet<>();
		for (int index = 0; index < beams; index++) {
			Vec3 mouth = WorldInterfaceAnatomy.mouthOrigin(boss,
					WorldInterfacePhasePressure.laserHead(boss.form(), index));
			Vec3 end = groundUnder(level, WorldInterfacePhasePressure.swingAroundY(start, primaryEnd,
					WorldInterfacePhasePressure.laserBeamYawOffset(boss.form(), index)));
			sweepBeam(level, boss, runtime, mouth, end, sweptTicks, index, burned);
		}
		return elapsed >= LASER_WARNING_TICKS + LASER_SWEEP_TICKS;
	}

	/**
	 * One beam of the sweep, from the muzzle to the floor.
	 *
	 * <p>Split out when the third form gained a second beam. Everything here is per-beam - the
	 * discharge, the shaft, the contact and the burn - and everything that is per-<em>shot</em> (the
	 * firing cue, the aim, the damage ledger) stays with the caller. That division is what keeps a
	 * two-beam sweep one attack rather than two attacks that happen to start together.
	 *
	 * @param index  which beam this is, used to scatter its seeds so the pair does not fork
	 *               identically
	 * @param burned who has already taken this tick's burn, shared across the beams
	 */
	private static void sweepBeam(ServerLevel level, WorldInterfaceEntity boss, AttackRuntime runtime,
			Vec3 start, Vec3 end, long sweptTicks, int index, Set<UUID> burned) {
		double burnRadius = WorldInterfacePhasePressure.laserBurnRadius(boss.form());
		if (sweptTicks == 0L) {
			// Fired from both ends: the muzzle says who shot, the impact says where it landed.
			emitLaserDischarge(level, boss, start, end);
		}
		// Light coming off the shaft for the whole sweep. The beam itself is drawn client-side, so
		// this is not what makes it visible - it is what stops it looking drawn: a clean shaft of
		// geometry with nothing shedding off it reads as a decal laid over the world, and the beads
		// travelling down it are the only thing in the shot that says which way the energy is going.
		if (sweptTicks % LASER_SHAFT_INTERVAL_TICKS == 0L) {
			emitLaserShaft(level, start, end, sweptTicks, burnRadius, index);
		}
		if (sweptTicks % WorldInterfacePhasePressure.laserBurnIntervalTicks(boss.form()) == 0L) {
			AABB sweepBounds = new AABB(start, end).inflate(burnRadius + 1.0D);
			double burnSqr = burnRadius * burnRadius;
			for (ServerPlayer nearby : level.getEntitiesOfClass(ServerPlayer.class, sweepBounds,
					candidate -> candidate.isAlive() && !candidate.isSpectator())) {
				if (!burned.add(nearby.getUUID())) continue;
				if (distanceToSegmentSqr(nearby.getEyePosition(), start, end) <= burnSqr) {
					damage(level, boss, nearby, formDamage(boss, LASER_BURN_DAMAGE));
				} else {
					// Not hit by this beam; leave them available to the next one.
					burned.remove(nearby.getUUID());
				}
			}
		}
		// Where the beam meets the floor it detonates, every tick, for as long as it is sweeping.
		// The contact point is the only part of the shot that is at the player's own eye level, so
		// it is what actually communicates where the beam is - a scar appearing behind you after
		// the fact does not.
		BlockPos impact = BlockPos.containing(end);
		emitLaserContact(level, boss, end, burnRadius, sweptTicks);
		if (sweptTicks % WorldInterfacePhasePressure.laserScarIntervalTicks(boss.form()) == 0L) {
			// The impact walks with the beam, so the arena ends up wearing the whole sweep path
			// instead of a single line drawn in one frame.
			runtime.cursor += EndBossArenaService.queueExplosionScar(level, impact,
					LASER_SCAR_RADIUS + boss.form(),
					LASER_SCAR_EDITS + LASER_SCAR_EDITS * boss.form() / 2,
					runtime.seed ^ impact.asLong() ^ (index * 0x9E3779B9L));
			// The full emitter, sparingly: one per burst reads as a detonation walking across the
			// island, where one every tick would just be a wall of smoke nobody can see through.
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, end.x, end.y + 0.4D, end.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		// The audible half of the contact runs on its own, slower clock.
		//
		// The scar walks every other tick because that is what draws a continuous burn line; the
		// sound and the shake used to ride the same interval, which meant one sweep spent twenty
		// explosion samples and twenty camera impulses in two seconds. Both are throttled to one
		// source now - see WorldInterfaceBlastService for why the mixer, and not just the mix, cares.
		if (WorldInterfaceBlastService.allows(level, WorldInterfaceBlastService.SOURCE_LASER)) {
			AudioService.playDetail(level, impact, ModSounds.WORLD_INTERFACE_LASER_IMPACT,
					SoundSource.HOSTILE, 0.55F, 1.18F);
			// The contact detonates, so it sounds like a detonation: vanilla's own explosion, the
			// one every player already reads as "that just blew a hole in something".
			//
			// Pitched off the impact position rather than drawn from a random source. Several of
			// these land during a single sweep, and at a fixed pitch that reads as one sample on
			// loop; the scatter hides the repetition while keeping a replayed encounter identical
			// to the one that was recorded, which the rest of this fight is built on.
			float scatter = 1.0F
					+ ((mix(impact.asLong()) >>> 40) / (float) 0xFFFFFF * 2.0F - 1.0F) * 0.16F;
			AudioService.playDetail(level, impact, ModSounds.WORLD_INTERFACE_BLAST,
				SoundSource.HOSTILE, 0.75F, scatter);
			WorldInterfaceBlastService.emit(level, runtime.encounterId, end,
					LASER_CONTACT_SHAKE_RADIUS, WorldInterfaceProtocol.BlastGrade.MEDIUM);
		}
	}

	/**
	 * The contact, at the rate this form burns at.
	 *
	 * <p>The rate was invisible before this. Both intervals the forms change are damage and terrain,
	 * neither of which the eye reads as a rate - so a second-form beam chewing through the floor
	 * twice as fast looked exactly like a first-form one. The burst here is scaled by the same
	 * interval, so a faster beam visibly throws more per second as well as taking more.
	 */
	private static void emitLaserContact(ServerLevel level, WorldInterfaceEntity boss, Vec3 end,
			double burnRadius, long sweptTicks) {
		int form = boss.form();
		// Inverted: a shorter interval is a hotter contact.
		double rate = WorldInterfaceAttackService.LASER_SCAR_INTERVAL_TICKS
				/ (double) WorldInterfacePhasePressure.laserScarIntervalTicks(form);
		double spread = burnRadius * 0.32D;
		level.sendParticles(ParticleTypes.EXPLOSION, end.x, end.y + 0.25D, end.z,
				2 + (int) Math.round(3.0D * rate), spread, 0.25D, spread, 0.03D);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, end.x, end.y + 0.35D, end.z,
				4 + (int) Math.round(6.0D * rate), spread, 0.30D, spread, 0.05D);
		level.sendParticles(ParticleTypes.LAVA, end.x, end.y + 0.2D, end.z,
				2 + (int) Math.round(4.0D * rate), spread * 0.8D, 0.10D, spread * 0.8D, 0.06D);
		// The contact wearing the floor: a ring thrown outward at the radius that actually burns,
		// and spokes leaving the middle of it. Both scale with the rate, so the second form's
		// contact is visibly a harder one rather than the same one landing more damage.
		WorldInterfaceVfx.shockRing(level, WorldInterfaceVfx.core(), end.add(0.0D, 0.15D, 0.0D),
				burnRadius * 0.5D, 14 + (int) Math.round(10.0D * rate), 0.42D, 0.16D);
		WorldInterfaceVfx.spokes(level, WorldInterfaceVfx.violet(), end.add(0.0D, 0.12D, 0.0D),
				burnRadius * 1.15D, 4 + (int) Math.round(3.0D * rate), 4, sweptTicks * 0.11D, 0.35D);
		// A sigil circle at the burn radius on the beat the form burns on, so the dangerous edge is
		// drawn on the floor rather than left to be inferred from the width of the shaft.
		if (sweptTicks % Math.max(2L, WorldInterfacePhasePressure.laserScarIntervalTicks(form) * 4L) == 0L) {
			WorldInterfaceVfx.runeCircle(level, end.add(0.0D, 0.1D, 0.0D), burnRadius,
					sweptTicks * 0.2D, 18 + form * 6);
		}
	}

	/**
	 * The instant the beam leaves the core.
	 *
	 * <p>One frame's worth, and the most expensive single frame in the attack, because it is the
	 * only one that says <em>fired</em> rather than <em>firing</em>. Everything else about the laser
	 * - the charge before it, the shaft during it, the contact walking across the floor - is a state
	 * that lasts; without a discharge the transition between them is a shaft that was simply already
	 * there on the tick you next looked at it.</p>
	 */
	private static void emitLaserDischarge(ServerLevel level, WorldInterfaceEntity boss, Vec3 start,
			Vec3 end) {
		double coreRadius = WorldInterfaceAnatomy.mouthRadius(boss.form(),
				WorldInterfacePhasePressure.laserHead(boss.form(), 0));
		Vec3 bearing = end.subtract(start);
		double length = bearing.length();
		Vec3 forward = length < 1.0E-4D ? new Vec3(0.0D, -1.0D, 0.0D) : bearing.scale(1.0D / length);
		// The muzzle itself: a white flash sized on the core, so a bigger form fires a bigger shot.
		ArenaParticles.emit(level, ColorParticleOption.create(ParticleTypes.FLASH, 0.86F, 0.58F, 1.0F),
				start.x, start.y, start.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		ArenaParticles.emit(level, ParticleTypes.EXPLOSION, start.x, start.y, start.z,
				4, coreRadius * 0.6D, coreRadius * 0.6D, coreRadius * 0.6D, 0.0D);
		// Blow-back around the core, thrown against the shot rather than with it. A gun that only
		// ever throws things forward has no recoil in it.
		ArenaParticles.emit(level, ParticleTypes.END_ROD, start.x, start.y, start.z,
				40, coreRadius * 0.9D, coreRadius * 0.9D, coreRadius * 0.9D, 0.45D);
		ArenaParticles.emit(level, ParticleTypes.REVERSE_PORTAL, start.x, start.y, start.z,
				30, coreRadius, coreRadius, coreRadius, 0.35D);
		// And the first dozen blocks of the shaft lit at once, so the beam arrives along its whole
		// near length instead of appearing at both ends.
		double reach = Math.min(LASER_MUZZLE_REACH, length);
		for (int index = 1; index <= 4; index++) {
			Vec3 point = start.add(forward.scale(reach * index / 4.0D));
			ArenaParticles.emit(level, ParticleTypes.END_ROD, point.x, point.y, point.z,
					6, 0.25D, 0.25D, 0.25D, 0.12D);
		}
		// The muzzle blast, as a ring standing square across the line of fire rather than as more
		// spray around the core. A ring that faces the shot is the one shape that says which way
		// this went to a player standing anywhere except in front of it.
		for (int index = 0; index < LASER_MUZZLE_RINGS; index++) {
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.core(),
					start.add(forward.scale(coreRadius * (0.6D + index * 0.9D))), forward,
					coreRadius * (0.9D + index * 0.55D), 20 + index * 8, index * 0.2D, 0.0D);
		}
		// A hollow shell off the core, which is the recoil the spray above was standing in for.
		WorldInterfaceVfx.shell(level, WorldInterfaceVfx.violet(), start, coreRadius * 1.15D,
				110, 0.55D);
		// And the first stretch of the beam already wound, so it arrives spinning rather than lit.
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.core(), start,
				start.add(forward.scale(reach)), coreRadius * 0.55D, 26, 3, 2.5D, 0.0D);
	}

	/**
	 * Beads of light travelling down the shaft while it burns.
	 *
	 * <p>The phase term is the whole point: the sample positions move along the beam every emission,
	 * so what a player sees is energy running from the interface to the ground rather than a static
	 * dotted line. A fixed spacing would have been a ladder drawn on the shot.</p>
	 */
	private static void emitLaserShaft(ServerLevel level, Vec3 start, Vec3 end, long sweptTicks,
			double burnRadius, int beamIndex) {
		Vec3 bearing = end.subtract(start);
		double length = bearing.length();
		if (length < 1.0E-4D) return;
		// Wraps every LASER_SHAFT_SAMPLES emissions, so the beads run the gap and then repeat rather
		// than drifting off the end of the beam.
		double phase = (sweptTicks / (double) LASER_SHAFT_INTERVAL_TICKS % LASER_SHAFT_SAMPLES)
				/ LASER_SHAFT_SAMPLES;
		for (int index = 0; index < LASER_SHAFT_SAMPLES; index++) {
			double along = (index + phase) / LASER_SHAFT_SAMPLES;
			Vec3 point = start.add(bearing.scale(along));
			// Thrown outward off the beam: light coming apart in the air is what a beam this size
			// would actually do to the air, and it is also what stops the shaft reading as a solid.
			ArenaParticles.emit(level, ParticleTypes.END_ROD, point.x, point.y, point.z,
					2, 0.18D, 0.18D, 0.18D, 0.05D);
		}
		// Wound *around* the beam rather than inside it.
		//
		// The threads used to run at a fixed 0.75 blocks from the axis, which at the beam's own
		// scale is inside the white-hot core - so from any distance the particles were not visibly
		// attached to the shot at all, they were a faint dotted line the beam was drawn over. They
		// are now pinned to the burn radius: the inner pair rides the edge of the core and the outer
		// pair rides the edge that actually burns, so the sleeve of light around the beam is the
		// same width as the danger. Counter-wound, because two helices turning against each other
		// read as a shaft with something spinning around it and two turning together read as one
		// thicker helix.
		double sleeve = Math.max(LASER_HELIX_RADIUS, burnRadius);
		double turns = length * LASER_HELIX_TURNS_PER_BLOCK;
		double spin = phase * Math.PI * 2.0D + beamIndex;
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.core(), start, end,
				sleeve * 0.45D, LASER_HELIX_SAMPLES, 2, turns, spin);
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.violet(), start, end,
				sleeve, LASER_HELIX_SAMPLES, 2, -turns * 0.7D, -spin);
		// Collars stepping down the shaft, so the sleeve has a cross-section instead of only a
		// silhouette. They travel with the same phase, which is what makes the beam read as
		// something being pushed down a pipe rather than a lit line.
		for (int index = 0; index < LASER_COLLARS; index++) {
			double along = ((index + phase) % LASER_COLLARS) / LASER_COLLARS;
			WorldInterfaceVfx.orientedRing(level, ParticleTypes.END_ROD,
					start.add(bearing.scale(along)), bearing, sleeve, 12, spin, 0.0D);
		}
		// Discharge jumping off the shaft to the air beside it, on its own slower clock so the beam
		// is not permanently fringed. Deterministic on the sweep tick: same bolt on every client.
		if (sweptTicks % LASER_FORK_INTERVAL_TICKS == 0L) {
			Vec3 forward = bearing.scale(1.0D / length);
			for (int index = 0; index < LASER_FORKS; index++) {
				long seed = sweptTicks * 8191L + index * 1543L;
				double along = 0.2D + ((seed >>> 12) & 0xFFFFL) / (double) 0xFFFF * 0.7D;
				Vec3 root = start.add(bearing.scale(along));
				double angle = ((seed >>> 28) & 0xFFFFL) / (double) 0xFFFF * Math.PI * 2.0D;
				Vec3 side = forward.cross(new Vec3(Math.cos(angle), 0.35D, Math.sin(angle)));
				if (side.lengthSqr() < 1.0E-6D) continue;
				WorldInterfaceVfx.arc(level, ParticleTypes.ELECTRIC_SPARK, root,
						root.add(side.normalize().scale(LASER_FORK_REACH)), 8, 0.9D, seed);
			}
		}
	}

	/**
	 * Charges, fires once, then gets out of the way.
	 *
	 * <p>The core spins up for {@link WorldInterfaceProtocol#ORB_WARNING_TICKS} with the marked
	 * player ringed by the shared lock tell, so being the one it has picked is knowable before the
	 * bolt exists. After that the bolt owns its own flight and impact, and this only holds the action
	 * envelope open for as long as it is in the air; the shot is aimed at launch, so the target
	 * leaving no longer unmakes it.</p>
	 */
	private static boolean tickOrb(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, long elapsed) {
		if (elapsed < ORB_WARNING_TICKS) {
			ServerPlayer marked = onlineTarget(level, runtime, 0);
			announceLock(level, runtime, marked, elapsed, ORB_WARNING_TICKS);
			chargeMouth(level, boss, elapsed, ORB_WARNING_TICKS, 0);
			// The charge's own voice, which used to live inside chargeMouth and now cannot: the laser
			// shares those particles and must not share this cue.
			if (elapsed == 0L) {
				float progress = elapsed / (float) ORB_WARNING_TICKS;
				AudioService.playBounded(level, BlockPos.containing(WorldInterfaceAnatomy.mouthOrigin(boss, 0)),
						ModSounds.WORLD_INTERFACE_ORB, SoundSource.HOSTILE,
						0.45F + progress * 0.4F, 0.72F + progress * 0.55F);
			}
			return false;
		}
		if (runtime.orbId == null && !runtime.damageApplied) {
			runtime.damageApplied = true;
			ServerPlayer target = onlineTarget(level, runtime, 0);
			// Nobody left to shoot at, and the bolt is aimed once - so there is nothing to aim it at.
			if (target == null) return true;
			WorldInterfaceEnergyOrbEntity orb = WorldInterfaceEnergyOrbEntity.create(level, boss,
					runtime.encounterId, target);
			runtime.orbId = orb.getUUID();
		}
		Entity orb = runtime.orbId == null ? null : level.getEntity(runtime.orbId);
		if (orb == null || orb.isRemoved()) return true;
		if (elapsed >= ORB_WARNING_TICKS + ORB_TRACKING_TICKS) {
			orb.discard();
			return true;
		}
		return false;
	}

	/**
	 * The other half of a ranged weapon's tell, drawn at the interface rather than at the victim:
	 * the mouth gathers visibly before it fires, so the rest of the table can see the shot coming as
	 * well as the person it is coming for.
	 *
	 * <p>Shared by the orb and the laser. It used to belong to the orb alone, which left the laser -
	 * the longest telegraph in the fight at ninety ticks - with nothing at the muzzle end at all:
	 * the target saw a ring closing on their own feet and everybody else saw a hovering shape that
	 * had not moved. A ninety-tick wind-up that only the person being aimed at can perceive is not a
	 * telegraph, it is a private countdown.</p>
	 *
	 * <p>Takes the raw clock rather than a fraction because the emission is throttled on it: every
	 * other tick, which is under the flicker ceiling for the ring's own rotation and halves what a
	 * ninety-tick charge costs the particle channel. Audio is each caller's own, on each caller's
	 * own cadence - two attacks whose charges sound alike would be two attacks that cannot be told
	 * apart before they land, which is exactly the thing the charge exists to prevent.</p>
	 */
	private static void chargeMouth(ServerLevel level, WorldInterfaceEntity boss, long elapsed,
			int windowTicks, int head) {
		if (elapsed % 2L != 0L) return;
		float progress = Math.clamp(elapsed / (float) Math.max(1, windowTicks), 0.0F, 1.0F);
		Vec3 core = WorldInterfaceAnatomy.mouthOrigin(boss, head);
		double radius = WorldInterfaceAnatomy.mouthRadius(boss.form(), head) * (1.35D - progress * 0.85D);
		int samples = 6 + Math.round(progress * 10.0F);
		for (int index = 0; index < samples; index++) {
			double angle = Math.PI * 2.0D * index / samples + progress * 6.0D;
			ArenaParticles.emit(level, CHARGE_PARTICLE,
					core.x + Math.cos(angle) * radius, core.y + Math.sin(angle * 1.7D) * radius * 0.4D,
					core.z + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		// Drawn inward as well as around: the ring says the core is spinning up, the infall says the
		// energy is coming from the body rather than appearing at the muzzle. One boxed emission, so
		// the whole inward half of the tell costs a single packet.
		ArenaParticles.emit(level, ParticleTypes.REVERSE_PORTAL, core.x, core.y, core.z,
				2 + Math.round(progress * 6.0F), radius * 1.6D, radius * 1.2D, radius * 1.6D,
				0.02D + progress * 0.06D);
		// The last fifth is the part that has to be unmistakable from across the island.
		if (progress >= 0.8F) {
			ArenaParticles.emit(level, ParticleTypes.END_ROD, core.x, core.y, core.z,
					3, radius * 0.9D, radius * 0.9D, radius * 0.9D, 0.05D);
		}
		// A cage closing on the core. Three rings on axes that are not each other's, all collapsing
		// on the same schedule the ring above tightens on: the tell used to be one flat circle,
		// which from below or from behind was a line. A cage is the same statement from every seat
		// in the arena, and the fact that it shrinks is what says how long is left.
		double cage = WorldInterfaceAnatomy.mouthRadius(boss.form(), head) * (2.6D - progress * 2.0D);
		for (int axis = 0; axis < 3; axis++) {
			double lean = elapsed * 0.05D + axis * (Math.PI / 3.0D);
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), core,
					new Vec3(Math.sin(lean), axis == 1 ? 0.9D : Math.cos(lean * 0.8D), Math.cos(lean)),
					cage, 10 + Math.round(progress * 8.0F),
					(axis % 2 == 0 ? 1.0D : -1.0D) * elapsed * 0.12D, 0.0D);
		}
		// And the body feeding it: bolts from the mass into the core, more of them the closer the
		// shot is. Sparks around the core say it is hot; bolts arriving say where the heat is from.
		if (progress >= 0.35F) {
			int bolts = 1 + Math.round(progress * 3.0F);
			double reach = WorldInterfaceAnatomy.massRadius(boss.form()) * 0.85D;
			for (int index = 0; index < bolts; index++) {
				double angle = elapsed * 0.21D + index * (Math.PI * 2.0D / bolts);
				Vec3 from = core.add(Math.cos(angle) * reach,
						Math.sin(angle * 1.7D) * reach * 0.4D, Math.sin(angle) * reach);
				WorldInterfaceVfx.arc(level, ParticleTypes.ELECTRIC_SPARK, from, core,
						7, radius * 0.6D, elapsed * 131L + index * 613L);
			}
		}
	}

	/**
	 * Replaces the old mental assault, which was an unavoidable flat hit dressed as a screen effect.
	 * The lance picks a player, tracks their feet while it locks on, freezes its impact when the lock
	 * resolves, charges for half a second on that fixed spot, and only then falls. Everything after
	 * the freeze is the player's to dodge.
	 */
	private static boolean tickSkyLance(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, long elapsed) {
		long chargeStart = SKY_LANCE_LOCK_TICKS;
		long strikeTick = chargeStart + SKY_LANCE_CHARGE_TICKS;
		ServerPlayer target = onlineTarget(level, runtime, 0);
		if (elapsed < chargeStart) {
			if (target != null) runtime.lanceImpact = groundUnder(level, target.position());
			announceLock(level, runtime, target, elapsed, SKY_LANCE_LOCK_TICKS);
			markLanceImpact(level, boss, runtime, 0.35F);
			return false;
		}
		if (runtime.lanceImpact == null) {
			runtime.lanceImpact = target != null ? groundUnder(level, target.position())
					: runtime.safeSpawn.getCenter();
		}
		if (elapsed < strikeTick) {
			// The mark stops moving here. Escalating cue and ring so the last half second reads as a
			// countdown on a fixed place rather than as continued tracking.
			float charge = (elapsed - chargeStart + 1) / (float) SKY_LANCE_CHARGE_TICKS;
			markLanceImpact(level, boss, runtime, 0.55F + charge * 0.75F);
			// The other end of the same warning. The mark on the floor says where; this says what is
			// coming, and until now nothing above head height acknowledged the lance at all until it
			// had already landed - the column is drawn client-side and drawn geometry alone reads as
			// a decal. Held to the last few ticks of the charge is deliberate: the sky filling up is
			// the final beat of the countdown, not the whole of it.
			long untilStrike = strikeTick - elapsed;
			if (untilStrike <= SKY_LANCE_GATHER_TICKS && elapsed % 2L == 0L) {
				emitLanceColumn(level, runtime.lanceImpact,
						1.0F - untilStrike / (float) SKY_LANCE_GATHER_TICKS);
			}
			if ((elapsed - chargeStart) % 3L == 0L) {
				AudioService.playBounded(level, BlockPos.containing(runtime.lanceImpact),
						ModSounds.WORLD_INTERFACE_LANCE, SoundSource.HOSTILE,
						0.55F + charge * 0.35F, 0.85F + charge * 0.5F);
			}
			return false;
		}
		if (!runtime.damageApplied) {
			Vec3 impact = runtime.lanceImpact;
			double lanceRadius = WorldInterfacePhasePressure.lanceRadius(boss.form());
			double radiusSqr = lanceRadius * lanceRadius;
			for (ServerPlayer nearby : level.getEntitiesOfClass(ServerPlayer.class,
					new AABB(impact, impact).inflate(lanceRadius + 1.0D),
					candidate -> candidate.isAlive() && !candidate.isSpectator())) {
				if (nearby.position().distanceToSqr(impact) <= radiusSqr) {
					damage(level, boss, nearby, formDamage(boss, SKY_LANCE_DAMAGE));
				}
			}
			// Vanilla's own detonation, in the shape a player already reads as "that was an
			// explosion": the emitter plus the sound TNT makes, at the point of contact.
			BlockPos impactPos = BlockPos.containing(impact);
			// The landing has its own cue, arena-wide. The charge above is a telegraph the player
			// dodges by; this is the confirmation it arrived, and it has to carry to whoever was not
			// the one being aimed at.
			AudioService.playBounded(level, impactPos, ModSounds.WORLD_INTERFACE_LANCE_IMPACT,
					SoundSource.HOSTILE, 1.0F, 0.94F);
			// A detonation that travels outward rather than a puff at a point.
			//
			// Emitters at the centre alone read as one small explosion no matter how big the crater
			// is. This is a front: a tight core, then rings of TNT bursts stepping outward across the
			// whole radius, each one seeded a little further and thrown outward so the wave keeps
			// moving after it is drawn. Ordered inside-out, which is the direction a blast reads in.
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 0.5D, impact.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
			// The column arriving, on the frame it arrives. Everything below this is the blast
			// spreading outward across the ground; this is the thing that came down to cause it,
			// and without it the crater simply happens with nothing having reached it.
			level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0.9F, 0.68F, 1.0F),
					impact.x, impact.y + 1.0D, impact.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			emitLanceColumn(level, impact, 1.0F);
			for (int ring = 1; ring <= SKY_LANCE_SHOCK_RINGS; ring++) {
				double radius = lanceRadius * 0.9D * ring;
				int samples = 5 + ring * 4;
				for (int index = 0; index < samples; index++) {
					double angle = Math.PI * 2.0D * index / samples + ring * 0.35D;
					double x = impact.x + Math.cos(angle) * radius;
					double z = impact.z + Math.sin(angle) * radius;
					// The full emitter only on the inner rings; further out it would be a smoke wall.
					if (ring <= 2 && index % 3 == 0) {
						level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, impact.y + 0.5D, z,
								1, 0.0D, 0.0D, 0.0D, 0.0D);
					}
					level.sendParticles(ParticleTypes.EXPLOSION, x, impact.y + 0.4D, z,
							2, 0.5D, 0.3D, 0.5D, 0.02D);
					// Velocity pointed outward along the ring, so the front keeps expanding.
					level.sendParticles(ParticleTypes.LARGE_SMOKE, x, impact.y + 0.5D, z,
							0, Math.cos(angle), 0.18D, Math.sin(angle), 0.34D + ring * 0.06D);
					level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, impact.y + 0.3D, z,
							0, Math.cos(angle), 0.05D, Math.sin(angle), 0.5D);
				}
			}
			level.sendParticles(ParticleTypes.END_ROD, impact.x, impact.y + 1.0D, impact.z,
					90, 0.6D, 3.5D, 0.6D, 0.28D);
			// The encounter's heaviest single impact gets the whole vocabulary at once: the sonic
			// front and the flash for the far side of the island, the emitters and the firework
			// shell for whoever is standing in it, and the shock rings for the second afterwards
			// when the smoke has covered everything else.
			WorldInterfaceVfx.detonation(level, impact.add(0.0D, 0.25D, 0.0D),
					lanceRadius * 1.3D, SKY_LANCE_SHOCK_RINGS);
			// And the mark it leaves burned into the floor, written rather than drawn.
			WorldInterfaceVfx.runeCircle(level, impact.add(0.0D, 0.15D, 0.0D),
					lanceRadius * 1.8D, 0.0D, 44);
			AudioService.playDetail(level, impactPos, ModSounds.WORLD_INTERFACE_BLAST,
				SoundSource.HOSTILE, 1.0F, 0.82F);
			craterAt(level, boss, runtime, impact, SKY_LANCE_SCAR_RADIUS, SKY_LANCE_SCAR_EDITS);
			corruptAround(level, runtime, impactPos);
			// Only the third form is large enough for this to read as reach rather than coincidence.
			if (boss.form() == WorldInterfaceEntity.FORM_INTERFACE) {
				EndBossArenaService.shearNearestSpikeCrown(level, BlockPos.containing(impact),
						runtime.seed ^ 0x5C0A11L);
			}
			AudioService.playBounded(level, BlockPos.containing(impact),
					ModSounds.WORLD_INTERFACE_LASER_FIRE, SoundSource.HOSTILE, 1.0F, 0.72F);
			// The camera answers on the tick the column arrives, not on the tick it starts falling.
			// The client used to derive this from SKY_LANCE_FALL_TICKS, which is a render-only number
			// describing the last three ticks of the charge - so the shake landed a second and a half
			// before the crater did, and read as an unrelated tremor.
			WorldInterfaceBlastService.emit(level, runtime.encounterId, impact,
					SKY_LANCE_SHAKE_RADIUS, WorldInterfaceProtocol.BlastGrade.HEAVY);
			runtime.damageApplied = true;
		}
		// The column keeps burning for a moment, so the strike has a visible aftermath.
		if ((elapsed - strikeTick) % 2L == 0L) markLanceImpact(level, boss, runtime, 0.9F);
		return elapsed >= strikeTick + SKY_LANCE_STRIKE_TICKS;
	}

	/**
	 * Custody is now visible in the inventory instead of being a hole in it. The taken tool is
	 * replaced in its own slot by a barrier placeholder the player can shuffle around but cannot
	 * drop or use, so "the interface is holding my sword" is legible without a HUD line, and the
	 * exact stack still comes back out of the durable ledger.
	 */
	private static boolean tickWeaponSteal(ServerLevel level, WorldInterfaceEntity boss,
			WorldInterfaceState.Snapshot snapshot, AttackRuntime runtime, long elapsed) {
		ServerPlayer target = onlineTarget(level, runtime, 0);
		if (elapsed < WEAPON_WARNING_TICKS) {
			announceLock(level, runtime, target, elapsed, WEAPON_WARNING_TICKS);
			return false;
		}
		if (!runtime.damageApplied) {
			if (target != null) {
				damage(level, boss, target, formDamage(boss, 10.0F));
				runtime.weapon = takeSelectedWeapon(level.getServer(), snapshot, runtime, target);
				if (runtime.weapon != null) {
					playActionCue(level, target.blockPosition(), runtime.action, 0.9F, 0.78F);
				}
			}
			runtime.damageApplied = true;
		}
		if (runtime.weapon != null && elapsed >= WEAPON_WARNING_TICKS + WEAPON_CUSTODY_TICKS) {
			deliverRecovery(level.getServer(), runtime.encounterId, runtime.weapon.recoveryId,
					target == null ? null : target.getUUID());
			runtime.weapon = null;
		}
		return elapsed >= WEAPON_WARNING_TICKS + WEAPON_CUSTODY_TICKS + 1L;
	}

	/**
	 * Same staged carry as the slam, but the release throws the victim straight up and lets go.
	 *
	 * <p>Throwing them at a scripted landing point meant the encounter authored both ends of the
	 * arc, and the player was a passenger for all of it. Hurling them into the sky hands the second
	 * half back: the interface decides they are going up, and what happens on the way down is
	 * theirs - a water bucket, a slow-fall potion, or the ground.</p>
	 */
	private static boolean tickGrabThrow(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, long elapsed) {
		long liftStart = GRAB_WARNING_TICKS;
		long windupStart = liftStart + GRAB_LIFT_TICKS;
		long launchTick = windupStart + THROW_WINDUP_TICKS;
		long endTick = launchTick + THROW_RELEASE_TICKS;
		ServerPlayer grabbed = onlineTarget(level, runtime, 0);
		if (grabbed == null) return elapsed >= liftStart;
		if (elapsed < liftStart) {
			announceLock(level, runtime, grabbed, elapsed, GRAB_WARNING_TICKS);
			return false;
		}
		if (runtime.control == null && !runtime.damageApplied) {
			// The lock is an actual warning, not a countdown to a foregone conclusion.
			//
			// The grab used to seize whoever it had marked wherever they had got to, which made the
			// two and a half seconds of telegraph pure theatre - there was nothing to do with them.
			// The tendrils have a reach; run past it before they close and they come back empty.
			if (grabbed.distanceToSqr(boss) > GRAB_REACH * GRAB_REACH) {
				runtime.damageApplied = true;
				Vec3 empty = carryHold(boss, grabbed.position());
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, boss.getX(), empty.y, boss.getZ(),
						40, 3.0D, 1.2D, 3.0D, 0.14D);
				AudioService.playBounded(level, BlockPos.containing(empty), ModSounds.WORLD_INTERFACE_GRAB,
						SoundSource.HOSTILE, 0.7F, 0.62F);
				TerminalNoticeService.encounter(grabbed, Component.translatable(
						"message.thefourthfrequency.world_interface.grab_evaded"));
				return true;
			}
			runtime.control = beginControl(level.getServer(), runtime, grabbed);
			runtime.carryStart = grabbed.position();
			// The bearing to hurl them along, fixed at the moment of the grab: whichever way they
			// already were from the interface. Frozen here rather than resampled every tick so the
			// swing is one continuous arc instead of a direction that chases the victim around.
			runtime.throwBearing = outwardBearing(runtime, grabbed.position(), boss.position());
			AudioService.playBounded(level, grabbed.blockPosition(), ModSounds.WORLD_INTERFACE_GRAB,
					SoundSource.HOSTILE, 0.9F, 1.05F);
		}
		if (runtime.control == null && !runtime.damageApplied) return elapsed >= endTick;
		Vec3 hold = carryHold(boss, runtime.carryStart);
		// Held in something, not simply floating. Two hoops through the victim and a tether of
		// light back to the core: being carried by a thing that is not drawn - the tendrils are
		// client-side geometry the victim cannot see from inside - was the whole problem with this
		// attack, and a cage they are visibly inside answers it from their own camera.
		emitCarryCage(level, boss, grabbed, elapsed);
		if (elapsed < windupStart) {
			double progress = easeOut((elapsed - liftStart + 1) / (double) GRAB_LIFT_TICKS);
			carryTo(grabbed, runtime.carryStart.lerp(hold, progress)
					.add(0.0D, Math.sin(progress * Math.PI) * 1.8D, 0.0D));
			return false;
		}
		if (elapsed < launchTick) {
			// Swung out from under the body, not spun on the spot.
			//
			// The wind-up is what makes the launch survivable: it ends with the victim past the rim
			// of the mass, in open air, so the impulse that follows does not have thirty-three blocks
			// of interface in front of it. Dips as it goes so the release is an underarm hurl.
			double swing = easeIn((elapsed - windupStart + 1) / (double) THROW_WINDUP_TICKS);
			double reach = (WorldInterfaceAnatomy.massRadius(boss.form()) + THROW_CLEARANCE) * swing;
			Vec3 bearing = runtime.throwBearing;
			carryTo(grabbed, hold.add(bearing.x * reach, -swing * 2.2D, bearing.z * reach));
			return false;
		}
		if (!runtime.damageApplied) {
			// Control ends at the launch, not at a landing: from here the fall is the player's
			// problem and their clutch, so nothing may keep holding their position.
			finishControl(level.getServer(), runtime, grabbed, false);
			grabbed.setNoGravity(false);
			// Let go, drawn as a release rather than as the cage simply stopping: a shell off the
			// victim and a ring across the bearing they are about to travel along.
			Vec3 released = grabbed.position().add(0.0D, grabbed.getBbHeight() * 0.5D, 0.0D);
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.core(), released, 1.6D, 90, 0.75D);
			WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), released,
					runtime.throwBearing, 2.2D, 24, 0.0D, 0.0D);
			// Thrown out and up, not straight up.
			//
			// A vertical launch reads as an elevator: the victim goes up, comes down on the spot
			// they left, and the interface may as well have pushed a button. Hurling them away from
			// itself gives the throw a direction the whole table can see, and it lands them
			// somewhere they have to walk back from - which is the point of being thrown.
			//
			// Along the same bearing the wind-up already swung them out on, so the release continues
			// the arc rather than snapping to wherever they happen to have ended up.
			Vec3 away = runtime.throwBearing != null ? runtime.throwBearing
					: outwardBearing(runtime, grabbed.position(), boss.position());
			grabbed.setDeltaMovement(away.x * THROW_LAUNCH_DRIFT, THROW_LAUNCH_SPEED,
					away.z * THROW_LAUNCH_DRIFT);
			grabbed.hurtMarked = true;
			grabbed.fallDistance = 0.0F;
			damage(level, boss, grabbed, formDamage(boss, 10.0F));
			level.sendParticles(ParticleTypes.EXPLOSION, grabbed.getX(), grabbed.getY(),
					grabbed.getZ(), 14, 1.2D, 0.6D, 1.2D, 0.09D);
			AudioService.playBounded(level, grabbed.blockPosition(),
					ModSounds.WORLD_INTERFACE_THROW, SoundSource.HOSTILE, 1.0F, 0.88F);
			runtime.damageApplied = true;
		}
		return elapsed >= endTick;
	}

	/**
	 * The purge no longer reserves what it throws. The hotbar empties left to right after its
	 * warning and the stacks land as ordinary drops: anyone can pick them up, and getting them back
	 * is the table's problem rather than the ledger's.
	 */
	private static boolean tickHotbar(ServerLevel level, AttackRuntime runtime, long elapsed) {
		ServerPlayer target = onlineTarget(level, runtime, 0);
		if (elapsed < HOTBAR_WARNING_TICKS) {
			announceLock(level, runtime, target, elapsed, HOTBAR_WARNING_TICKS);
			return false;
		}
		if (target != null && runtime.originalSelectedSlot < 0) {
			runtime.originalSelectedSlot = target.getInventory().getSelectedSlot();
		}
		long firstDrop = HOTBAR_WARNING_TICKS + HOTBAR_STEP_TICKS;
		if (target != null && elapsed >= firstDrop && (elapsed - firstDrop) % HOTBAR_STEP_TICKS == 0L
				&& runtime.cursor < HOTBAR_SLOTS) {
			int slot = runtime.cursor++;
			// The cooldown starts on the first slot that actually hits the floor, not when the attack
			// was scheduled: a target who went offline during the warning had nothing taken from them
			// and should not be spending three minutes of immunity on it.
			if (slot == 0) {
				recordHotbarSweep(runtime.encounterId, target.getUUID(),
						runtime.startedActiveTick + elapsed);
			}
			target.getInventory().setSelectedSlot(slot);
			dropHotbarSlot(level, runtime, target, slot);
		}
		if (elapsed >= HOTBAR_WARNING_TICKS + HOTBAR_STEP_TICKS * HOTBAR_SLOTS + 1L) {
			if (target != null && runtime.originalSelectedSlot >= 0) {
				target.getInventory().setSelectedSlot(runtime.originalSelectedSlot);
			}
			return true;
		}
		return false;
	}

	/**
	 * Replaces arrow reflection. The tendrils rear up over the arena, then lash three times, each
	 * strike swinging at whoever is nearest at that moment - so the flurry has to be read and moved
	 * away from rather than simply shot through.
	 */
	private static boolean tickTendrilLash(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, long elapsed) {
		if (elapsed < TENDRIL_WARNING_TICKS) {
			for (UUID targetId : runtime.targets) {
				if (runtime.unavailableTargets.contains(targetId)) continue;
				ServerPlayer warned = level.getServer().getPlayerList().getPlayer(targetId);
				announceLock(level, runtime, warned, elapsed, TENDRIL_WARNING_TICKS);
			}
			return false;
		}
		long sinceWarning = elapsed - TENDRIL_WARNING_TICKS;
		long total = (long) TENDRIL_STRIKE_INTERVAL_TICKS * TENDRIL_STRIKE_COUNT;
		if (sinceWarning >= total) return true;
		long phase = sinceWarning % TENDRIL_STRIKE_INTERVAL_TICKS;

		if (phase == 0L) {
			// Each lash commits to one spot, here, and stops tracking.
			//
			// Picking the nearest player at the moment of impact meant the landing followed whoever
			// it had chosen, so the only way to not be hit was to not be the closest - which is not
			// something a player under attack can act on. Choosing now and marking the ground turns
			// the flurry into three readable events instead of three unavoidable ones.
			//
			// And each lash reaches for somebody the flurry has not had yet. Nearest-of-everyone,
			// evaluated three times in a row, is nearest-of-everyone three times: whoever is holding
			// the front - which on any table with a melee player is the same person every flurry -
			// took all three landings, and three overlapping five-block circles inside two seconds is
			// not a telegraph anybody can answer. Once the flurry has been round the table it starts
			// again, so this narrows nothing on a solo player.
			ServerPlayer struck = nearestTarget(level, runtime, boss, runtime.struckThisFlurry);
			if (struck == null) {
				runtime.struckThisFlurry.clear();
				struck = nearestTarget(level, runtime, boss, Set.of());
			}
			if (struck != null) runtime.struckThisFlurry.add(struck.getUUID());
			runtime.tendrilImpact = struck != null ? groundUnder(level, struck.position())
					: groundUnder(level, boss.position().add(boss.getLookAngle().scale(12.0D)));
			AudioService.playBounded(level, BlockPos.containing(WorldInterfaceAnatomy.coreOrigin(boss)),
					ModSounds.WORLD_INTERFACE_TENDRIL, SoundSource.HOSTILE, 0.7F, 1.0F);
		}
		if (runtime.tendrilImpact == null) return false;
		Vec3 impact = runtime.tendrilImpact;

		if (phase < TENDRIL_STRIKE_TELEGRAPH_TICKS) {
			// The mark tightens as the limb comes down, on the same clock the damage lands on.
			float charge = phase / (float) TENDRIL_STRIKE_TELEGRAPH_TICKS;
			markGround(level, impact, TENDRIL_REACH, 0.4F + charge * 0.9F);
			// The limb on its way down, drawn as a line from the body to the mark.
			//
			// This attack throws nothing: the thing that hits you is one of the interface's own
			// tendrils, which at the third form starts eighteen blocks overhead on a body that is
			// thirty-three across. The model animates, but from the floor - which is where the
			// person being hit is standing - the limb is lost against the mass, and what the player
			// actually experiences is a circle appearing and then being thrown out of it by
			// nothing. A wound line from the core to the mark, tightening as the strike lands,
			// gives the blow a visible source without changing anything about the attack.
			Vec3 root = WorldInterfaceAnatomy.coreOrigin(boss);
			double reach = root.distanceTo(impact);
			WorldInterfaceVfx.helix(level, WorldInterfaceVfx.violet(), root, impact,
					TENDRIL_TRACE_RADIUS * (1.0D - charge * 0.55D), TENDRIL_TRACE_SAMPLES, 2,
					reach * 0.04D, phase * 0.35D);
			WorldInterfaceVfx.arc(level, ParticleTypes.ELECTRIC_SPARK, root, impact,
					TENDRIL_TRACE_SEGMENTS, 1.6D * (1.0D - charge), runtime.seed ^ phase);
			// Every eight ticks rather than every four. Three limbs telegraphing at once - which is
			// what the third phase's volley lane produces - was twenty-four samples in a second and a
			// half, on top of everything else the fight is playing. The mark on the ground is what
			// carries the warning; the ticking is only there so it is noticed.
			return false;
		}
		if (phase == TENDRIL_STRIKE_TELEGRAPH_TICKS + 5L) {
			AudioService.playDetail(level, BlockPos.containing(impact),
					ModSounds.WORLD_INTERFACE_TENDRIL_RECOVER, SoundSource.HOSTILE, 0.45F, 1.0F);
		}
		if (phase == TENDRIL_STRIKE_TELEGRAPH_TICKS) {
			AudioService.playBounded(level, BlockPos.containing(impact),
					ModSounds.WORLD_INTERFACE_TENDRIL_STRIKE, SoundSource.HOSTILE, 0.9F, 1.0F);
			runtime.cursor++;
			runtime.damageApplied = true;
			double reachSqr = TENDRIL_REACH * TENDRIL_REACH;
			for (ServerPlayer nearby : level.getEntitiesOfClass(ServerPlayer.class,
					new AABB(impact, impact).inflate(TENDRIL_REACH + 1.0D),
					candidate -> candidate.isAlive() && !candidate.isSpectator())) {
				if (nearby.position().distanceToSqr(impact) > reachSqr) continue;
				damage(level, boss, nearby, formDamage(boss, TENDRIL_DAMAGE));
				Vec3 away = nearby.position().subtract(impact);
				nearby.knockback(0.85D * (1.0D + boss.form() * 0.3D), -away.x, -away.z);
				nearby.hurtMarked = true;
			}
			level.sendParticles(ParticleTypes.EXPLOSION, impact.x, impact.y + 0.4D, impact.z,
					10, 1.4D, 0.3D, 1.4D, 0.06D);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, impact.x, impact.y + 0.6D, impact.z,
					50, TENDRIL_REACH * 0.4D, 0.7D, TENDRIL_REACH * 0.4D, 0.16D);
			// The limb landing, at the radius the knockback is thrown from. The telegraph draws that
			// circle for a second and a half and then the landing used to happen entirely inside it:
			// a puff at the centre of a five-block mark, which said nothing about how far the reach
			// had actually gone. This throws the edge of the circle outward on the frame it lands.
			level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0.74F, 0.5F, 0.96F),
					impact.x, impact.y + 0.6D, impact.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			for (int index = 0; index < TENDRIL_LANDING_SAMPLES; index++) {
				double angle = Math.PI * 2.0D * index / TENDRIL_LANDING_SAMPLES;
				double x = impact.x + Math.cos(angle) * TENDRIL_REACH;
				double z = impact.z + Math.sin(angle) * TENDRIL_REACH;
				level.sendParticles(ParticleTypes.END_ROD, x, impact.y + 0.3D, z,
						0, Math.cos(angle), 0.25D, Math.sin(angle), 0.55D);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, impact.y + 0.4D, z,
						0, Math.cos(angle), 0.12D, Math.sin(angle), 0.3D);
			}
			// The floor splitting away from where the limb came down. Two rings out of the three a
			// full detonation would throw - a lash is one of three landings in a flurry, and giving
			// each of them the lance's whole blast would flatten the difference between them.
			WorldInterfaceVfx.detonation(level, impact.add(0.0D, 0.3D, 0.0D), TENDRIL_REACH * 0.7D, 2);
			craterAt(level, boss, runtime, impact, TENDRIL_SCAR_RADIUS, TENDRIL_SCAR_EDITS);
			// On the landing frame. The client used to shake every thirty ticks off the action clock,
			// which lines up with none of the three landings - they fall at the warning plus a
			// telegraph, every forty-five ticks - so the camera was shaking to its own rhythm while
			// the limbs hit to another. The volley lane throws these outside the action envelope
			// entirely, so there was no clock to derive them from there at all.
			WorldInterfaceBlastService.emit(level, runtime.encounterId, impact,
					TENDRIL_SHAKE_RADIUS, WorldInterfaceProtocol.BlastGrade.MEDIUM);
		}
		return false;
	}

	/**
	 * The cage a carried player is inside, drawn from their own point of view.
	 *
	 * <p>Two upright hoops crossed through them, spinning, plus a tether of light running back to
	 * the core that is holding them. The limbs doing the carrying are drawn by the client model, and
	 * from inside the grip the victim is looking down the length of one - so from the seat that
	 * matters most, this attack had no visual at all.</p>
	 */
	private static void emitCarryCage(ServerLevel level, WorldInterfaceEntity boss,
			ServerPlayer carried, long elapsed) {
		// Under the feet and over the head, never across the face. Being carried already takes the
		// player's control of their own position; taking their view of the arena on top of it is
		// two punishments for one attack, and the second one is not readable as an attack at all.
		Vec3 centre = carried.position().add(0.0D, carried.getBbHeight() * 0.5D, 0.0D);
		double spin = elapsed * 0.22D;
		WorldInterfaceVfx.ring(level, WorldInterfaceVfx.violet(), carried.position(), 1.5D, 16,
				spin, 0.0D);
		WorldInterfaceVfx.ring(level, WorldInterfaceVfx.violet(),
				carried.position().add(0.0D, carried.getBbHeight() + 1.1D, 0.0D), 1.2D, 14,
				-spin, 0.0D);
		if (elapsed % 2L != 0L) return;
		Vec3 core = WorldInterfaceAnatomy.coreOrigin(boss);
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.core(), core, centre, 0.7D, 26, 2,
				core.distanceTo(centre) * 0.05D, spin);
	}

	private static boolean tickEviction(ServerLevel level, AttackRuntime runtime, long elapsed) {
		if (!runtime.damageApplied && elapsed >= WorldInterfaceActionScheduler.FORCED_EVICTION_WARNING_TICKS) {
			MinecraftServer server = level.getServer();
			// Checked at the execution frame rather than at selection, so an operator turning it off
			// mid-fight also cancels a lock that is already counting down. The warning the targets
			// have been watching resolves into nothing, which is the correct reading of an attack the
			// server refused rather than one that missed.
			if (!RuntimeServices.config().meta().forcedEvictionEnabled()) {
				runtime.damageApplied = true;
				return true;
			}
			// The execution frame was silent: players simply vanished. Whoever is left needs to hear
			// that the interface did that, or a disconnected teammate reads as their own connection.
			AudioService.playBounded(level, BlockPos.containing(runtime.safeSpawn.getCenter()),
					ModSounds.WORLD_INTERFACE_EVICTION, SoundSource.HOSTILE, 1.0F, 1.0F);
			for (UUID targetId : runtime.targets) {
				if (runtime.unavailableTargets.contains(targetId)) continue;
				ServerPlayer target = server.getPlayerList().getPlayer(targetId);
				if (target == null || target.level() != level
						|| server.isSingleplayerOwner(target.nameAndId())) continue;
				target.connection.disconnect(EVICTION_REASON);
				// Recorded where it actually happens, not where it was selected: a target that was
				// skipped above because they had already left has not been evicted by the interface,
				// and must not be moved to the back of the queue as though it had.
				EVICTED.computeIfAbsent(runtime.encounterId, ignored -> ConcurrentHashMap.newKeySet())
						.add(targetId);
			}
			runtime.damageApplied = true;
		}
		return elapsed >= WorldInterfaceActionScheduler.FORCED_EVICTION_WARNING_TICKS + 1L;
	}

	private static ControlLease beginControl(MinecraftServer server, AttackRuntime runtime, ServerPlayer player) {
		UUID id = deterministicRecoveryId(runtime, player.getUUID(), "control", 0);
		CompoundTag payload = new CompoundTag();
		payload.putString("phase", "active");
		payload.putBoolean("original_no_gravity", player.isNoGravity());
		payload.putDouble("x", player.getX());
		payload.putDouble("y", player.getY());
		payload.putDouble("z", player.getZ());
		payload.putDouble("yaw", player.getYRot());
		payload.putDouble("pitch", player.getXRot());
		WorldInterfaceState.RecoveryEntry entry = new WorldInterfaceState.RecoveryEntry(
				id, player.getUUID(), "control", payload, false);
		if (!addRecovery(server, runtime.encounterId, entry)) return null;
		ControlLease lease = new ControlLease(id, player.getUUID(), player.isNoGravity(), player.position(),
				player.getYRot(), player.getXRot());
		player.setNoGravity(true);
		player.setDeltaMovement(Vec3.ZERO);
		return lease;
	}

	/**
	 * One step of a carry, called every tick of a lift, hold, swing or slam.
	 *
	 * <p>The teleport is the authority - the encounter owns where a carried player is, and nothing
	 * they press may argue with it. The motion sent alongside it is what the client interpolates
	 * with between ticks: without it, twenty authoritative positions a second render as twenty
	 * discrete relocations, which is the same thing the single teleport this replaced looked like,
	 * only more often.</p>
	 */
	private static void carryTo(ServerPlayer player, Vec3 position) {
		Vec3 step = position.subtract(player.position());
		player.setNoGravity(true);
		player.fallDistance = 0.0F;
		player.teleportTo(position.x, position.y, position.z);
		player.setDeltaMovement(step);
		player.hurtMarked = true;
	}

	/**
	 * Where a carried player is held: just under the body, never inside it and never in the floor.
	 *
	 * <p>This used to be a flat five blocks over the entity position, which stopped meaning anything
	 * once the position became a bookkeeping point that can sit below the arena. Hung off the
	 * underside of the drawn mass instead, so the victim is held where the limbs holding them are.
	 */
	private static Vec3 carryHold(WorldInterfaceEntity boss, Vec3 carryStart) {
		double under = boss.getY() + WorldInterfaceAnatomy.massBottomLift(boss.form()) - CARRY_HOLD_DROP;
		return new Vec3(boss.getX(), Math.max(under, carryStart.y + CARRY_MIN_LIFT), boss.getZ());
	}

	/**
	 * Flat unit bearing from the interface to a point, for the direction a victim is swung and hurled
	 * along. Directly overhead has no outward direction to take, so the deterministic seed picks one
	 * rather than the throw silently collapsing back to vertical.
	 */
	private static Vec3 outwardBearing(AttackRuntime runtime, Vec3 from, Vec3 bossPosition) {
		Vec3 away = from.subtract(bossPosition);
		double flat = Math.sqrt(away.x * away.x + away.z * away.z);
		if (flat < 1.0E-3D) {
			double angle = ((runtime.seed >>> 17) % 1000) / 1000.0D * Math.PI * 2.0D;
			return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
		}
		return new Vec3(away.x / flat, 0.0D, away.z / flat);
	}

	/** Ends a carry in place, so the last step of an arc does not become a launch. */
	private static void settleAt(ServerPlayer player, Vec3 position) {
		player.setNoGravity(true);
		player.fallDistance = 0.0F;
		player.teleportTo(position.x, position.y, position.z);
		player.setDeltaMovement(Vec3.ZERO);
		player.hurtMarked = true;
	}

	private static double easeOut(double progress) {
		double clamped = Math.clamp(progress, 0.0D, 1.0D);
		return 1.0D - Math.pow(1.0D - clamped, 2.4D);
	}

	private static double easeIn(double progress) {
		double clamped = Math.clamp(progress, 0.0D, 1.0D);
		return clamped * clamped * (1.0D + clamped * 0.6D) / 1.6D;
	}

	/**
	 * How much harder the same attack lands at each later form.
	 *
	 * <p>The rotation unlocks new attacks per phase, but the ones a player already knows kept hitting
	 * for exactly what they hit for in the first minute - so the interface got wider and slower to
	 * kill without ever getting more dangerous. These scale what an attack does, never how much room
	 * there is to answer it: damage, shove and the hole left in the island all grow with the form,
	 * while the burn radius of the laser and the reach of a lash deliberately do not. Those two are
	 * load-bearing against their own telegraphs - the beam has to stay inside what its tracking lag
	 * covers and a lash inside what a sprint out of the marked circle covers - and widening them
	 * would not make the fight harder, it would make it unfair in a way nobody can see.</p>
	 *
	 * <p>These are pre-mitigation figures: {@link WorldInterfaceDamageService#apply} puts them
	 * through armour, resistance and protection like any other hit, so what a player actually loses
	 * depends on what they brought. The step per form stays modest because the spread between an
	 * unarmoured player and a fully enchanted one is already most of an order of magnitude, and
	 * multiplying that spread again per phase is what made the later forms feel arbitrary.</p>
	 */
	private static float formDamage(WorldInterfaceEntity boss, float base) {
		return base * (1.0F + boss.form() * 0.15F);
	}

	/** Drops a point onto the surface below it, which is where scars and impacts belong. */
	private static Vec3 groundUnder(ServerLevel level, Vec3 position) {
		int x = (int) Math.floor(position.x);
		int z = (int) Math.floor(position.z);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		double surface = Math.max(level.getMinY() + 1, Math.min(position.y, y));
		return new Vec3(position.x, surface, position.z);
	}

	/**
	 * The shared "you are the one being aimed at" tell. Every locking action calls this for the whole
	 * of its warning, so a lock always has a sound and a body of particles on the player as well as
	 * the screen treatment the client draws from the same envelope.
	 */
	private static void announceLock(ServerLevel level, AttackRuntime runtime, ServerPlayer target,
			long elapsed, int warningTicks) {
		if (target == null) return;
		float progress = Math.clamp(elapsed / (float) Math.max(1, warningTicks), 0.0F, 1.0F);
		if (elapsed == 0L && runtime.action != WorldInterfaceAction.LASER_SWEEP) {
			AudioService.playBounded(level, target.blockPosition(), sound(runtime.action),
					SoundSource.HOSTILE, 0.85F, 0.9F);
		}
		// Tightens as the lock resolves: a ring that closes is readable at a glance, a steady ring
		// is just decoration and says nothing about how long is left.
		int every = progress < 0.6F ? 4 : 2;
		if (elapsed % every != 0L) return;
		double radius = 1.9D - progress * 1.25D;
		Vec3 floor = new Vec3(target.getX(), target.getY() + 0.1D, target.getZ());

		// Every telegraph is drawn on the floor, and each one is drawn as a different figure.
		//
		// Two problems at once. The first is that they all used to be the same closing ring, and a
		// player who is busy fighting does not stop to read which attack the label says - they see
		// "a lock" and have to guess whether the answer is to run, to look up, or to do nothing.
		// The second is that the version before this one answered that by adding hoops through the
		// player's own body, at eye level, which is exactly where nothing may be: a telegraph that
		// obscures the arena is worse than one that is ambiguous.
		//
		// So the shape carries the meaning and it all lies flat under the player's feet, where it
		// is in view without being in the way.
		switch (runtime.action) {
			// The beam arrives along a line from the core, so its mark has a direction: a ring with
			// spokes that point back at where the shot is coming from.
			case LASER_SWEEP -> {
				WorldInterfaceVfx.ring(level, WorldInterfaceVfx.core(), floor, radius, 16,
						elapsed * 0.16D, 0.0D);
				WorldInterfaceVfx.spokes(level, WorldInterfaceVfx.violet(), floor, radius * 1.5D,
						4, 3, elapsed * 0.16D, 0.0D);
			}
			// The lance falls from directly overhead, so its mark is the only one that reaches up:
			// a ring on the floor and a second one descending onto it.
			case SKY_LANCE -> {
				WorldInterfaceVfx.ring(level, WorldInterfaceVfx.core(), floor, radius, 20,
						-elapsed * 0.1D, 0.0D);
				WorldInterfaceVfx.ring(level, WorldInterfaceVfx.violet(),
						floor.add(0.0D, LOCK_OVERHEAD_LIFT * (1.0F - progress), 0.0D),
						radius * 1.3D, 14, elapsed * 0.2D, 0.0D);
			}
			// The tendrils close from three sides, and three is the shape: a triangle that turns.
			case TENDRIL_LASH -> WorldInterfaceVfx.polygon(level, WorldInterfaceVfx.violet(), floor,
					radius * 1.4D, 3, 7, elapsed * 0.09D);
			// Nothing is coming - something is being taken. Arrowheads closing inward, and a square
			// rather than a circle, because what it is reaching for is an inventory.
			case CHARGE_WEAPON_STEAL -> {
				WorldInterfaceVfx.polygon(level, WorldInterfaceVfx.core(), floor, radius * 1.3D,
						4, 6, elapsed * 0.05D);
				WorldInterfaceVfx.chevrons(level, WorldInterfaceVfx.violet(), floor, radius * 2.0D,
						4, 0.8D, elapsed * 0.05D);
			}
			// Nine beads for nine slots, closing on the player one telegraph at a time.
			case GAZE_HOTBAR_CLEAR -> {
				WorldInterfaceVfx.beads(level, WorldInterfaceVfx.violet(), floor, radius * 1.5D,
						HOTBAR_SLOTS, elapsed * 0.07D);
				WorldInterfaceVfx.polygon(level, WorldInterfaceVfx.core(), floor, radius * 1.1D,
						4, 5, -elapsed * 0.07D);
			}
			// The grab reaches in and lifts, so its mark converges hard and has nothing at the rim.
			case GRAB_THROW -> WorldInterfaceVfx.chevrons(level, WorldInterfaceVfx.core(), floor,
					radius * 2.2D, 6, 1.4D, elapsed * 0.18D);
			default -> WorldInterfaceVfx.ring(level, WorldInterfaceVfx.core(), floor, radius, 12,
					elapsed * 0.16D, 0.0D);
		}

		if (progress >= 0.6F && elapsed % 12L == 0L) {
			AudioService.playBounded(level, target.blockPosition(), ModSounds.WORLD_INTERFACE_IMPACT,
					SoundSource.HOSTILE, 0.30F, 1.45F + progress * 0.35F);
			// The last stretch, marked above the player's head rather than through it: high enough
			// to be out of the sightline and still inside the frame when they are looking level.
			WorldInterfaceVfx.ring(level, WorldInterfaceVfx.core(),
					floor.add(0.0D, LOCK_OVERHEAD_LIFT, 0.0D), radius * 0.8D, 10,
					elapsed * 0.3D, 0.0D);
		}
	}

	/**
	 * How far above the player's feet the overhead half of a lock is drawn, in blocks.
	 *
	 * <p>Above head height by a clear margin. Anything at eye level competes with the arena for the
	 * one part of the screen the player is reading the fight out of, and a telegraph that hides the
	 * thing it is warning about has cost more than it gave.
	 */
	private static final double LOCK_OVERHEAD_LIFT = 3.1D;

	/**
	 * The lance's own column, thrown up the same shaft the client draws.
	 *
	 * <p>Fills from the top down as {@code intensity} rises, which is the direction the thing is
	 * actually coming from: at a quarter of the way through the gather only the highest points are
	 * lit, and by the strike the whole column is. A column that filled from the ground up would have
	 * been the mark growing rather than something descending onto it.</p>
	 *
	 * <p>Nine points however tall the column is, for the same reason the laser's shaft takes eight:
	 * the cost of a shot must not depend on where it lands.</p>
	 */
	private static void emitLanceColumn(ServerLevel level, Vec3 impact, float intensity) {
		if (impact == null) return;
		float reach = Math.clamp(intensity, 0.0F, 1.0F);
		if (reach <= 0.0F) return;
		double height = WorldInterfaceProtocol.SKY_LANCE_COLUMN_BLOCKS;
		for (int index = 0; index < SKY_LANCE_COLUMN_SAMPLES; index++) {
			// Index 0 is the top of the column, and the band grows downward from it as reach rises.
			double fraction = 1.0D - index / (double) SKY_LANCE_COLUMN_SAMPLES;
			if (fraction < 1.0D - reach) continue;
			double y = impact.y + height * fraction;
			ArenaParticles.emit(level, ParticleTypes.END_ROD, impact.x, y, impact.z,
					2 + Math.round(reach * 3.0F), 0.35D, 0.6D, 0.35D, 0.02D);
		}
		// Wisps in the lit band itself, which is what makes the front read as moving: they are
		// emitted around the middle of however much of the column exists, so the cloud walks down
		// the shaft as it fills instead of sitting at a fixed height waiting for it.
		ArenaParticles.emit(level, ParticleTypes.REVERSE_PORTAL,
				impact.x, impact.y + height * (1.0D - reach * 0.5D), impact.z,
				6 + Math.round(reach * 10.0F), 0.5D, height * reach * 0.25D, 0.5D, 0.4D);
		// The lit part of the shaft, as a real column: flame core, two counter-wound helices and a
		// collar at each end. Only over the part that exists, so the top-down fill still reads.
		Vec3 front = new Vec3(impact.x, impact.y + height * (1.0D - reach), impact.z);
		WorldInterfaceVfx.beacon(level, front, height * reach, SKY_LANCE_HELIX_RADIUS,
				reach * 9.0D, SKY_LANCE_HELIX_SAMPLES);
	}

	/** The ground mark the sky lance is about to fall on; intensity carries how close that is. */
	private static void markLanceImpact(ServerLevel level, WorldInterfaceEntity boss,
			AttackRuntime runtime, float intensity) {
		if (runtime.lanceImpact == null) return;
		// Drawn at the radius that will actually be hit, so a bigger lance is a visibly bigger ring.
		markGround(level, runtime.lanceImpact, WorldInterfacePhasePressure.lanceRadius(boss.form()), intensity);
	}

	/**
	 * A ring on the floor saying "this is about to be hit". Shared by every attack that commits to
	 * a spot ahead of time, so a marked circle means the same thing whichever limb drew it.
	 */
	private static void markGround(ServerLevel level, Vec3 impact, double radius, float intensity) {
		int samples = Math.clamp(Math.round(10.0F + intensity * 14.0F), 8, 28);
		for (int index = 0; index < samples; index++) {
			double angle = Math.PI * 2.0D * index / samples;
			level.sendParticles(ParticleTypes.END_ROD,
					impact.x + Math.cos(angle) * radius, impact.y + 0.1D,
					impact.z + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, impact.x, impact.y + 0.4D, impact.z,
				Math.round(4.0F + intensity * 16.0F), radius * 0.5D, 0.5D,
				radius * 0.5D, 0.05D + intensity * 0.06D);
		// A sigil circle at the marked radius, plus an inner ring closing on the middle as the
		// telegraph runs out. One static circle tells the player where; it never told them when,
		// and when is the half of a telegraph a dodge is actually made out of.
		Vec3 centre = new Vec3(impact.x, impact.y + 0.12D, impact.z);
		WorldInterfaceVfx.runeCircle(level, centre, radius, intensity * 2.0D, samples);
		WorldInterfaceVfx.ring(level, WorldInterfaceVfx.core(), centre,
				radius * Math.max(0.08D, 1.0D - intensity), samples, intensity * 5.0D, 0.0D);
	}

	/** The closest live target this runtime still has, skipping anyone the caller has already used. */
	private static ServerPlayer nearestTarget(ServerLevel level, AttackRuntime runtime,
			WorldInterfaceEntity boss, Set<UUID> excluded) {
		ServerPlayer nearest = null;
		double best = Double.MAX_VALUE;
		for (int index = 0; index < runtime.targets.size(); index++) {
			ServerPlayer candidate = onlineTarget(level, runtime, index);
			if (candidate == null || excluded.contains(candidate.getUUID())) continue;
			double distance = candidate.distanceToSqr(boss);
			if (distance < best) {
				best = distance;
				nearest = candidate;
			}
		}
		return nearest;
	}

	private static void finishControl(MinecraftServer server, AttackRuntime runtime,
			ServerPlayer player, boolean restorePosition) {
		ControlLease lease = runtime.control;
		if (lease == null) return;
		player.setNoGravity(lease.originalNoGravity);
		if (restorePosition) {
			player.teleportTo(lease.originalPosition.x, lease.originalPosition.y, lease.originalPosition.z);
			player.setYRot(lease.yaw);
			player.setXRot(lease.pitch);
		}
		removeRecovery(server, runtime.encounterId, lease.recoveryId);
		runtime.control = null;
	}

	private static WeaponLease takeSelectedWeapon(MinecraftServer server,
			WorldInterfaceState.Snapshot snapshot, AttackRuntime runtime, ServerPlayer player) {
		int slot = player.getInventory().getSelectedSlot();
		ItemStack selected = player.getInventory().getItem(slot);
		if (!isValidWeapon(selected)) return null;
		ItemStack exact = selected.copy();
		UUID recoveryId = deterministicRecoveryId(runtime, player.getUUID(), "weapon", slot);
		CompoundTag payload = itemPayload(server, exact, slot, matchingItemCount(player, exact), "prepared");
		payload.putString("weapon_kind", weaponKind(exact));
		WorldInterfaceState.RecoveryEntry prepared = new WorldInterfaceState.RecoveryEntry(
				recoveryId, player.getUUID(), "weapon", payload, false);
		if (!addRecovery(server, runtime.encounterId, prepared)) return null;

		ItemStack removed = player.getInventory().removeItemNoUpdate(slot);
		if (!ItemStack.matches(removed, exact)) {
			restoreStackImmediately(player, slot, removed);
			removeRecovery(server, runtime.encounterId, recoveryId);
			return null;
		}
		payload.putString("phase", "custody");
		WorldInterfaceState.RecoveryEntry custody = new WorldInterfaceState.RecoveryEntry(
				recoveryId, player.getUUID(), "weapon", payload, false);
		if (!replaceRecovery(server, runtime.encounterId, custody)) {
			restoreStackImmediately(player, slot, removed);
			removeRecovery(server, runtime.encounterId, recoveryId);
			return null;
		}
		// A barrier placeholder goes into the slot, and it says what it is.
		//
		// Custody was silent for a while, on the argument that being told the weapon is coming back
		// spends the fear. In play it did the opposite of what that argument assumed: an item that
		// vanishes out of your hand mid-fight with no mark left behind does not read as "taken", it
		// reads as "lost" - and a player who believes their sword is gone for good plays the rest of
		// the encounter as though it were, which is a real cost paid for an effect nobody reported
		// feeling. The placeholder is the honest version: the slot is visibly occupied by something
		// the interface is holding, it cannot be dropped or used, and it is swapped back for the
		// real stack when the ledger resolves.
		player.getInventory().setItem(slot, ConfiscationService.placeholder(recoveryId));
		return new WeaponLease(recoveryId, player.getUUID(), slot, removed,
				payload.getStringOr("weapon_kind", "tool"));
	}

	private static void dropHotbarSlot(ServerLevel level, AttackRuntime runtime,
			ServerPlayer player, int slot) {
		ItemStack stack = player.getInventory().getItem(slot);
		if (stack.isEmpty()) return;
		ItemStack removed = player.getInventory().removeItemNoUpdate(slot);
		if (removed.isEmpty()) return;
		// An ordinary drop, with an ordinary pickup delay: anyone at the table can get to it first.
		ItemEntity item = new ItemEntity(level, player.getX(), player.getEyeY(), player.getZ(), removed);
		item.setDefaultPickUpDelay();
		// Thrown outward with the slot order, so nine stacks do not land in one pile.
		double angle = Math.PI * 2.0D * slot / HOTBAR_SLOTS;
		item.setDeltaMovement(Math.cos(angle) * 0.24D, 0.22D, Math.sin(angle) * 0.24D);
		if (!level.addFreshEntity(item)) {
			restoreStackImmediately(player, slot, removed);
			return;
		}
		// Marked, not made easier. The stack keeps its ordinary pickup delay and its ordinary
		// despawn; what it gains is a column of light, because nine stacks scattered across end
		// stone in the middle of a fight were recoverable in principle and unfindable in practice.
		WorldInterfaceDropBeaconService.mark(item);
		playActionCue(level, player.blockPosition(), runtime.action, 0.72F, 1.0F + slot * 0.025F);
	}

	private static void finishRuntime(MinecraftServer server, AttackRuntime runtime, boolean restorePosition) {
		ACTIVE.remove(runtime.encounterId, runtime);
		ServerLevel level = server.getLevel(Level.END);
		if (level != null && runtime.orbId != null) {
			Entity orb = level.getEntity(runtime.orbId);
			if (orb != null) orb.discard();
		}
		if (runtime.control != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(runtime.control.playerId);
			if (player != null) finishControl(server, runtime, player, restorePosition);
		}
		if (runtime.weapon != null) {
			deliverRecovery(server, runtime.encounterId, runtime.weapon.recoveryId, runtime.weapon.playerId);
			runtime.weapon = null;
		}
		if (runtime.originalSelectedSlot >= 0 && !runtime.targets.isEmpty()) {
			ServerPlayer target = server.getPlayerList().getPlayer(runtime.targets.getFirst());
			if (target != null) target.getInventory().setSelectedSlot(runtime.originalSelectedSlot);
		}
	}

	private static void cancelRuntime(MinecraftServer server, AttackRuntime runtime, boolean restorePosition) {
		finishRuntime(server, runtime, restorePosition);
		ServerLevel level = server.getLevel(Level.END);
		if (level != null && level.getEntity(runtime.bossId) instanceof WorldInterfaceEntity boss) boss.clearAction();
	}

	private static void releasePlayerControl(MinecraftServer server, AttackRuntime runtime,
			UUID playerId, boolean restorePosition) {
		if (runtime.control == null || !runtime.control.playerId.equals(playerId)) return;
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player != null) finishControl(server, runtime, player, restorePosition);
	}

	private static void restoreForcedHotbarSlot(AttackRuntime runtime, ServerPlayer player) {
		if (runtime.originalSelectedSlot < 0 || runtime.targets.isEmpty()
				|| !runtime.targets.getFirst().equals(player.getUUID())) return;
		player.getInventory().setSelectedSlot(runtime.originalSelectedSlot);
		runtime.originalSelectedSlot = -1;
	}

	private static int restoreRecoveryEntries(MinecraftServer server, UUID encounterId, UUID onlyOwner) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || snapshot.encounterId().filter(encounterId::equals).isEmpty()) return 0;
		int restored = 0;
		List<WorldInterfaceState.RecoveryEntry> entries = new ArrayList<>(snapshot.recoveryLedger());
		entries.sort(Comparator
				.comparing((WorldInterfaceState.RecoveryEntry entry) -> entry.ownerId().toString())
				.thenComparing(WorldInterfaceState.RecoveryEntry::kind)
				.thenComparing(entry -> entry.id().toString()));
		for (WorldInterfaceState.RecoveryEntry entry : entries) {
			if (entry.resolved() || (onlyOwner != null && !onlyOwner.equals(entry.ownerId()))) continue;
			ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId());
			if (owner == null) continue;
			if ("control".equals(entry.kind())) {
				CompoundTag payload = entry.payload();
				owner.setNoGravity(payload.getBooleanOr("original_no_gravity", false));
				if ("active".equals(payload.getStringOr("phase", ""))) {
					owner.teleportTo(payload.getDoubleOr("x", owner.getX()),
							payload.getDoubleOr("y", owner.getY()), payload.getDoubleOr("z", owner.getZ()));
					owner.setYRot((float) payload.getDoubleOr("yaw", owner.getYRot()));
					owner.setXRot((float) payload.getDoubleOr("pitch", owner.getXRot()));
				}
				if (removeRecovery(server, encounterId, entry.id())) restored++;
			} else if ("weapon".equals(entry.kind()) || "hotbar_drop".equals(entry.kind())) {
				// "hotbar_drop" is no longer written - the purge makes ordinary drops now - but a
				// save that was mid-encounter across the change still has entries under it, and
				// handing those back is strictly better than stranding them in the ledger forever.
				if (deliverRecovery(server, encounterId, entry.id(), owner.getUUID())) restored++;
			}
		}
		return restored;
	}

	/**
	 * Hands a confiscated stack back. The placeholder that stood in for it is the preferred landing
	 * site, so the tool returns to wherever the player had shuffled the barrier to rather than to
	 * whichever slot happened to be free.
	 */
	private static boolean deliverRecovery(MinecraftServer server, UUID encounterId,
			UUID recoveryId, UUID preferredOwner) {
		WorldInterfaceState.RecoveryEntry entry = recoveryEntry(server, encounterId, recoveryId).orElse(null);
		if (entry == null) return true;
		ServerPlayer owner = server.getPlayerList().getPlayer(entry.ownerId());
		if (owner == null || (preferredOwner != null && !preferredOwner.equals(owner.getUUID()))) return false;
		CompoundTag payload = entry.payload();
		ItemStack stack;
		try {
			stack = decodeItem(server, payload);
		} catch (RuntimeException exception) {
			// The item behind this ledger entry can no longer be decoded (e.g. it came from a
			// mod/data version that has since changed). The stack was already taken from the
			// owner's inventory when custody began, so retrying forever - which is what returning
			// false here used to do, since restoreRecoveryEntries() calls this on every login and
			// death - just leaves the entry "unresolved" forever with the item gone and no record
			// of why. Drop the unrecoverable entry and tell the owner outright instead.
			TheFourthFrequency.LOGGER.warn(
					"Discarding unrecoverable world_interface recovery entry {} for {} ({}): {}",
					entry.id(), entry.ownerId(), entry.kind(), exception.toString());
			ConfiscationService.clearPlaceholder(owner, entry.id());
			if (removeRecovery(server, encounterId, entry.id())) {
				TerminalNoticeService.denied(owner,
						"message.thefourthfrequency.world_interface.recovery_lost");
			}
			return true;
		}
		payload.putString("phase", "delivering");
		WorldInterfaceState.RecoveryEntry delivering = new WorldInterfaceState.RecoveryEntry(
				entry.id(), entry.ownerId(), entry.kind(), payload, false);
		if (!replaceRecovery(server, encounterId, delivering)) return false;
		// Saves written before custody went silent may still hold a barrier placeholder; clear it so
		// it does not outlive the stack it stood for. Its slot is deliberately not reused.
		ConfiscationService.clearPlaceholder(owner, entry.id());
		giveExact(owner, stack);
		return removeRecovery(server, encounterId, entry.id());
	}

	/**
	 * Hands the stack back wherever the inventory puts it, which is deliberately not where it left.
	 *
	 * <p>Custody used to remember the slot and restore into it, so a returned weapon appeared exactly
	 * where the player last saw it and the whole episode closed without a mark. It comes back in
	 * whatever slot is free now instead: the player is never short an item, but they have to notice
	 * that their inventory is not arranged the way they left it. Nothing is lost and nothing is said.
	 */
	private static void giveExact(ServerPlayer owner, ItemStack stack) {
		ItemStack remainder = stack.copy();
		owner.getInventory().add(remainder);
		if (!remainder.isEmpty()) owner.drop(remainder, false, true);
	}

	private static boolean addRecovery(MinecraftServer server, UUID encounterId,
			WorldInterfaceState.RecoveryEntry entry) {
		for (int attempt = 0; attempt < 3; attempt++) {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			if (!snapshot.valid() || snapshot.encounterId().filter(encounterId::equals).isEmpty()) return false;
			if (snapshot.recoveryLedger().stream().anyMatch(value -> value.id().equals(entry.id()))) return true;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server, encounterId,
					snapshot.revision(), state -> state.addRecovery(entry));
			if (result.applied()) return true;
		}
		return false;
	}

	private static boolean replaceRecovery(MinecraftServer server, UUID encounterId,
			WorldInterfaceState.RecoveryEntry replacement) {
		for (int attempt = 0; attempt < 3; attempt++) {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			if (!snapshot.valid() || snapshot.encounterId().filter(encounterId::equals).isEmpty()) return false;
			if (snapshot.recoveryLedger().stream().noneMatch(value -> value.id().equals(replacement.id()))) return false;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server, encounterId,
					snapshot.revision(), state -> {
					state.removeRecovery(replacement.id());
					state.addRecovery(replacement);
				});
			if (result.applied()) return true;
		}
		return false;
	}

	private static boolean removeRecovery(MinecraftServer server, UUID encounterId, UUID recoveryId) {
		for (int attempt = 0; attempt < 3; attempt++) {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			if (!snapshot.valid() || snapshot.encounterId().filter(encounterId::equals).isEmpty()) return false;
			if (snapshot.recoveryLedger().stream().noneMatch(value -> value.id().equals(recoveryId))) return true;
			WorldInterfaceState.MutationResult result = WorldInterfaceState.mutate(server, encounterId,
					snapshot.revision(), state -> state.removeRecovery(recoveryId));
			if (result.applied()) return true;
		}
		return false;
	}

	private static Optional<WorldInterfaceState.RecoveryEntry> recoveryEntry(MinecraftServer server,
			UUID encounterId, UUID recoveryId) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
		if (!snapshot.valid() || snapshot.encounterId().filter(encounterId::equals).isEmpty()) return Optional.empty();
		return snapshot.recoveryLedger().stream().filter(value -> value.id().equals(recoveryId)).findFirst();
	}

	private static CompoundTag itemPayload(MinecraftServer server, ItemStack stack,
			int slot, int baselineCount, String phase) {
		CompoundTag payload = new CompoundTag();
		payload.put("item", encodeItem(server, stack));
		payload.putInt("slot", slot);
		payload.putInt("baseline_count", baselineCount);
		payload.putString("phase", phase);
		return payload;
	}

	private static Tag encodeItem(MinecraftServer server, ItemStack stack) {
		DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
		return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
	}

	private static ItemStack decodeItem(MinecraftServer server, CompoundTag payload) {
		Tag encoded = payload.get("item");
		if (encoded == null) throw new IllegalArgumentException("Recovery payload has no item");
		DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
		return ItemStack.CODEC.parse(ops, encoded).getOrThrow();
	}

	private static double distanceToSegmentSqr(Vec3 point, Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		double lengthSqr = segment.lengthSqr();
		if (lengthSqr <= 1.0E-8D) return point.distanceToSqr(start);
		double progress = Math.clamp(point.subtract(start).dot(segment) / lengthSqr, 0.0D, 1.0D);
		return point.distanceToSqr(start.add(segment.scale(progress)));
	}

	/**
	 * Every attack here is ranged or area, so a landed hit used to be a red flash and nothing else -
	 * no direction, no weight, no way to tell it apart from standing in the wrong place. The impulse
	 * says where it came from, the burst says it was you, and the cue gives it a size.
	 */
	/**
	 * Slams and throws now leave the floor they landed on. The arena's own scar queue already
	 * bounds this - protected positions, a per-encounter permanent-edit ceiling and a paced
	 * drain - so the only thing missing was that nothing but the laser ever called it, and a
	 * fight that rearranges the world was reading as a fight happening on top of a static one.
	 */
	private static void craterAt(ServerLevel level, WorldInterfaceEntity boss, AttackRuntime runtime,
			Vec3 impact, int radius, int maximumEdits) {
		// Bigger bites the further the fight has gone. The arena's own budget still bounds the total,
		// so a harder third phase spends that budget faster rather than exceeding it.
		int form = boss.form();
		EndBossArenaService.queueExplosionScar(level, BlockPos.containing(impact), radius + form,
				maximumEdits + maximumEdits * form / 2, runtime.seed ^ BlockPos.containing(impact).asLong());
	}

	/**
	 * Leaves the rim of a lance crater as missing-texture proxies rather than as clean air.
	 *
	 * <p>Every other scar in this fight removes blocks, which reads as damage. The interface is not
	 * damaging the island so much as failing to keep rendering it, and this is the one attack big
	 * enough to say so: the hole is ringed by world that is still solid and no longer has a texture.
	 * Goes through the arena's own protection check, so it can no more touch bedrock, the altar or
	 * an anchor than the crater it borders can.</p>
	 */
	private static void corruptAround(ServerLevel level, AttackRuntime runtime, BlockPos center) {
		int converted = 0;
		long seed = runtime.seed ^ center.asLong() ^ 0x4D1551_0000L;
		for (BlockPos position : BlockPos.betweenClosed(
				center.offset(-SKY_LANCE_CORRUPTION_RADIUS, -3, -SKY_LANCE_CORRUPTION_RADIUS),
				center.offset(SKY_LANCE_CORRUPTION_RADIUS, 3, SKY_LANCE_CORRUPTION_RADIUS))) {
			if (converted >= SKY_LANCE_CORRUPTION_EDITS) break;
			double distanceSqr = position.distSqr(center);
			// A ring, not a disc: the middle of this is the crater, which is already gone.
			if (distanceSqr <= (double) SKY_LANCE_SCAR_RADIUS * SKY_LANCE_SCAR_RADIUS
					|| distanceSqr > (double) SKY_LANCE_CORRUPTION_RADIUS * SKY_LANCE_CORRUPTION_RADIUS) {
				continue;
			}
			BlockState state = level.getBlockState(position);
			if (state.isAir() || state.is(ModBlocks.MISSING_TEXTURE_PROXY)) continue;
			if (!EndBossArenaService.canDestroy(level, position, state)) continue;
			// Scattered rather than solid, so the rim reads as the world coming apart in patches.
			if ((mix(seed ^ position.asLong()) >>> 59) < 20L) continue;
			level.setBlock(position, ModBlocks.MISSING_TEXTURE_PROXY.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			converted++;
		}
	}

	private static void damage(ServerLevel level, WorldInterfaceEntity boss,
			ServerPlayer player, float amount) {
		if (!WorldInterfaceDamageService.apply(level,
				WorldInterfaceDamageService.source(level, boss), player, amount)) return;

		// Heavier the larger the interface gets. The same shove that reads as a blow from a
		// six-block embryo reads as a nudge from something thirty-three blocks across, and the
		// escalation is the whole point of the phases.
		double push = (0.30D + 0.022D * Math.min(amount, 20.0F))
				* (1.0D + boss.form() * 0.22D);
		Vec3 away = player.position().subtract(boss.position());
		double flat = Math.sqrt(away.x * away.x + away.z * away.z);
		Vec3 impulse = flat < 1.0E-4D
				? new Vec3(0.0D, push, 0.0D)
				: new Vec3(away.x / flat * push, push * 0.62D, away.z / flat * push);
		player.push(impulse.x, impulse.y, impulse.z);
		// Player motion is authoritative on the client, so without this the shove is never sent.
		player.hurtMarked = true;

		level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(),
				player.getY() + player.getBbHeight() * 0.6D, player.getZ(),
				Math.round(12.0F + amount * 2.5F), 0.36D, 0.52D, 0.36D, 0.15D);
		AudioService.playDetail(level, player.blockPosition(), ModSounds.WORLD_INTERFACE_IMPACT,
				SoundSource.HOSTILE, 0.85F, 1.06F - 0.02F * Math.min(amount, 14.0F));
	}

	private static void invalidateUnavailableTargets(ServerLevel level, AttackRuntime runtime) {
		for (UUID targetId : runtime.targets) {
			if (runtime.unavailableTargets.contains(targetId)) continue;
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
			if (player == null || player.level() != level || !player.isAlive() || player.isSpectator()) {
				runtime.unavailableTargets.add(targetId);
			}
		}
	}

	private static ServerPlayer onlineTarget(ServerLevel level, AttackRuntime runtime, int index) {
		if (index < 0 || index >= runtime.targets.size()) return null;
		UUID targetId = runtime.targets.get(index);
		if (runtime.unavailableTargets.contains(targetId)) return null;
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
		return player != null && player.level() == level && player.isAlive() && !player.isSpectator()
				? player : null;
	}

	private static boolean requiresTarget(WorldInterfaceAction action) {
		return action != WorldInterfaceAction.TENDRIL_LASH;
	}

	private static int durationTicks(WorldInterfaceAction action) {
		return switch (action) {
			case LASER_SWEEP -> LASER_WARNING_TICKS + LASER_SWEEP_TICKS;
			case ENERGY_ORB -> ORB_WARNING_TICKS + ORB_TRACKING_TICKS;
			case SKY_LANCE -> SKY_LANCE_LOCK_TICKS + SKY_LANCE_CHARGE_TICKS + SKY_LANCE_STRIKE_TICKS;
			case CHARGE_WEAPON_STEAL -> WEAPON_WARNING_TICKS + WEAPON_CUSTODY_TICKS + 1;
			case GRAB_THROW -> GRAB_WARNING_TICKS + GRAB_LIFT_TICKS + THROW_WINDUP_TICKS
					+ THROW_RELEASE_TICKS;
			case GAZE_HOTBAR_CLEAR -> HOTBAR_WARNING_TICKS + HOTBAR_STEP_TICKS * HOTBAR_SLOTS + 1;
			case TENDRIL_LASH -> TENDRIL_WARNING_TICKS
					+ TENDRIL_STRIKE_INTERVAL_TICKS * TENDRIL_STRIKE_COUNT;
			case FORCED_EVICTION -> WorldInterfaceActionScheduler.FORCED_EVICTION_WARNING_TICKS + 1;
		};
	}

	private static boolean isValidWeapon(ItemStack stack) {
		return !stack.isEmpty() && (stack.is(ItemTags.WEAPON_ENCHANTABLE)
				|| stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES)
				|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
				|| stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
				|| stack.is(ItemTags.TRIDENT_ENCHANTABLE));
	}

	private static String weaponKind(ItemStack stack) {
		return stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
				|| stack.is(ItemTags.TRIDENT_ENCHANTABLE) ? "ranged"
				: stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) ? "melee" : "tool";
	}

	private static int matchingItemCount(ServerPlayer player, ItemStack reference) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack candidate = player.getInventory().getItem(slot);
			if (ItemStack.isSameItemSameComponents(reference, candidate)) count += candidate.getCount();
		}
		return count;
	}

	private static void restoreStackImmediately(ServerPlayer player, int slot, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (slot >= 0 && slot < player.getInventory().getContainerSize()
				&& player.getInventory().getItem(slot).isEmpty()) player.getInventory().setItem(slot, stack);
		else player.getInventory().placeItemBackInInventory(stack);
	}

	private static UUID deterministicRecoveryId(AttackRuntime runtime, UUID owner,
			String kind, int index) {
		String value = runtime.encounterId + ":" + runtime.sequence + ":" + owner + ":" + kind + ":" + index;
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Each action owns three samples played at one fixed pitch, which over a three-phase fight is
	 * few enough that the rotation becomes audible - the boss starts sounding like a soundboard.
	 * A few percent of pitch scatter per cast hides the repetition.
	 *
	 * <p>The scatter is derived rather than drawn from a random source: the encounter is
	 * deterministic by design, and this keeps a replayed fight sounding identical to the one that
	 * was recorded.</p>
	 */
	private static void playActionCue(ServerLevel level, BlockPos position,
			WorldInterfaceAction action, float volume, float pitch) {
		// This charge is a cancellable sound attached to the tracked mouth on each client.
		if (action == WorldInterfaceAction.LASER_SWEEP) return;
		long scatter = mix(position.asLong() ^ (long) action.ordinal() * 0x9E3779B97F4A7C15L
				^ level.getGameTime() * 0xC2B2AE3D27D4EB4FL);
		float jittered = pitch * (1.0F + ((scatter >>> 40) / (float) 0xFFFFFF * 2.0F - 1.0F) * 0.045F);
		AudioService.playBounded(level, position, sound(action), SoundSource.HOSTILE, volume, jittered);
	}

	private static SoundEvent sound(WorldInterfaceAction action) {
		return switch (action) {
			case LASER_SWEEP -> ModSounds.WORLD_INTERFACE_LASER;
			case ENERGY_ORB -> ModSounds.WORLD_INTERFACE_ORB;
			// Its own voice, not the mental-interference one. Sharing that sample meant the attack
			// that drops a column on your head and the one that only distorts what you see announced
			// themselves identically, which is most of why the telegraphs were unreadable.
			case SKY_LANCE -> ModSounds.WORLD_INTERFACE_LANCE;
			case CHARGE_WEAPON_STEAL -> ModSounds.WORLD_INTERFACE_WEAPON;
			case GRAB_THROW -> ModSounds.WORLD_INTERFACE_THROW;
			case GAZE_HOTBAR_CLEAR -> ModSounds.WORLD_INTERFACE_HOTBAR;
			case TENDRIL_LASH -> ModSounds.WORLD_INTERFACE_TENDRIL;
			case FORCED_EVICTION -> ModSounds.WORLD_INTERFACE_EXPULSION;
		};
	}

	private static long mix(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	public enum AttackStatus {
		CONTINUE,
		COMPLETE,
		CANCELLED_RESTART
	}

	public record AttackStart(WorldInterfaceState.AttackEnvelope envelope, WorldInterfaceAction action,
			int durationTicks, Set<UUID> targets, long seed) {
		public AttackStart {
			Objects.requireNonNull(envelope, "envelope");
			Objects.requireNonNull(action, "action");
			targets = Set.copyOf(targets);
		}
	}

	public record AttackTick(AttackStatus status,
			Optional<WorldInterfaceState.AttackEnvelope> replacementEnvelope) {
		public AttackTick {
			Objects.requireNonNull(status, "status");
			replacementEnvelope = replacementEnvelope == null ? Optional.empty() : replacementEnvelope;
		}

		private static AttackTick cancelledRestart() {
			return new AttackTick(AttackStatus.CANCELLED_RESTART, Optional.empty());
		}
	}

	private static final class AttackRuntime {
		private final UUID encounterId;
		private final UUID bossId;
		private final WorldInterfaceAction action;
		private final long startedActiveTick;
		private final long dueActiveTick;
		private final long sequence;
		private final long seed;
		private final List<UUID> targets;
		private final BlockPos origin;
		private final BlockPos arenaCenter;
		private final BlockPos safeSpawn;
		private final Set<UUID> unavailableTargets = new LinkedHashSet<>();
		/**
		 * Who the current lash flurry has already brought a limb down on.
		 *
		 * @see WorldInterfaceAttackService#tickTendrilLash
		 */
		private final Set<UUID> struckThisFlurry = new LinkedHashSet<>();
		/**
		 * A short trail of where the laser's target has been, newest last. The sweep aims at the
		 * entry {@link WorldInterfaceProtocol#LASER_TRACKING_LAG_TICKS} back, which is the whole
		 * reason running works and standing still does not.
		 */
		private final Deque<Vec3> aimTrail = new ArrayDeque<>();
		private UUID orbId;
		private int cursor;
		private boolean damageApplied;
		private ControlLease control;
		private WeaponLease weapon;
		private int originalSelectedSlot = -1;
		private Vec3 carryStart;
		/** Flat unit direction the grab swings and then hurls its victim along; null until grabbed. */
		private Vec3 throwBearing;
		private Vec3 lanceImpact;
		private Vec3 tendrilImpact;
		private Vec3 throwStart;
		private Vec3 throwLanding;

		private void recordAim(Vec3 position) {
			aimTrail.addLast(position);
			// Sized on the longest lag any form asks for, so a form with a shorter one simply reads
			// a more recent entry out of the same trail.
			while (aimTrail.size() > LASER_TRACKING_LAG_TICKS + 1) aimTrail.removeFirst();
		}

		/** The oldest retained sample once the trail is full; null until it is. */
		private Vec3 laggedAim(int lagTicks) {
			return aimTrail.size() > lagTicks ? aimTrail.peekFirst() : aimTrail.peekLast();
		}

		private AttackRuntime(UUID encounterId, UUID bossId, WorldInterfaceAction action,
				long startedActiveTick, long dueActiveTick, long sequence, long seed,
				List<UUID> targets, BlockPos origin, BlockPos arenaCenter, BlockPos safeSpawn) {
			this.encounterId = encounterId;
			this.bossId = bossId;
			this.action = action;
			this.startedActiveTick = startedActiveTick;
			this.dueActiveTick = dueActiveTick;
			this.sequence = sequence;
			this.seed = seed;
			this.targets = List.copyOf(targets);
			this.origin = origin.immutable();
			this.arenaCenter = arenaCenter.immutable();
			this.safeSpawn = safeSpawn.immutable();
		}

		private WorldInterfaceState.AttackEnvelope envelope() {
			return new WorldInterfaceState.AttackEnvelope(action.wireId(), sequence, startedActiveTick,
					dueActiveTick, seed, origin, Set.copyOf(targets), cursor, damageApplied);
		}
	}

	private record ControlLease(UUID recoveryId, UUID playerId, boolean originalNoGravity,
			Vec3 originalPosition, float yaw, float pitch) {
	}

	private record WeaponLease(UUID recoveryId, UUID playerId, int slot, ItemStack stack, String kind) {
	}
}
