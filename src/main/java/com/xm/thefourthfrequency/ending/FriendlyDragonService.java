package com.xm.thefourthfrequency.ending;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the non-hostile, fight-detached dragon used by the successful ending. */
public final class FriendlyDragonService {
	public static final String FRIENDLY_DRAGON_TAG = "thefourthfrequency.friendly_ending_dragon";
	public static final double ORBIT_RADIUS = 72.0D;
	public static final double ORBIT_HEIGHT = 48.0D;
	/**
	 * One lap, in ticks.
	 *
	 * <p>Two thousand four hundred was two real minutes for a single circuit of a seventy-two block
	 * orbit - about a fifth of a block per tick. Over the handful of seconds the dragon is actually
	 * on screen that is not slow flight, it is a stationary model. Thirty seconds a lap reads as a
	 * dragon crossing the sky.</p>
	 */
	public static final int ORBIT_PERIOD_TICKS = 600;
	/**
	 * Where the dragon flies while it is prising the exit open.
	 *
	 * <p>The portal used to well up out of the altar on its own while the dragon kept its distance,
	 * which made the way out something the arena produced and the dragon something that happened to
	 * be in the sky at the time. It comes down to here instead, close enough that the particles and
	 * the sound are visibly attached to it, and high enough to stay clear of the players standing on
	 * the altar it is opening.</p>
	 */
	public static final double WORK_RADIUS = 17.0D;
	public static final double WORK_HEIGHT = 15.0D;
	/** A tighter circle has to be flown faster, or the descent reads as the dragon stalling. */
	public static final int WORK_PERIOD_TICKS = 190;
	/**
	 * Ticks the dragon takes to climb back off the finished exit and onto the resting orbit.
	 *
	 * <p>The climb used not to exist. The descent was gated on the encounter still being in its
	 * success resolution, so on the tick the exit opened and the stage advanced, the approach the
	 * whole flight path interpolates on fell from one to zero between two ticks - and the body was
	 * thrown from the low working circle to the resting orbit, sixty-odd blocks, in one step. That is
	 * the teleport: not the dragon arriving, the dragon leaving.
	 *
	 * <p>Longer than the descent on purpose. Coming down is work the players are meant to watch; going
	 * back up is the ending letting go of them, and it happens while they are reading the poem.</p>
	 */
	public static final int RETURN_TICKS = 200;
	/**
	 * Ticks the dragon spends climbing out of the altar and onto its orbit.
	 *
	 * <p>It is added to the world at the arena centre, inside the column of light the summon has been
	 * raising, and spirals out from there onto the ring the ceremony spent six seconds drawing. Placed
	 * straight onto the ring it arrived seventy-two blocks from everything anyone was looking at - the
	 * altar - so the one moment the whole ending builds towards happened in the corner of the screen.
	 */
	public static final int EMERGE_TICKS = 70;
	/** The radius it leaves the altar at, so the first tangent is never degenerate. */
	private static final double EMERGE_START_RADIUS = 1.5D;
	/** Ticks of missed updates the orbit will catch up on; past this it simply resumes. */
	private static final int MAX_ORBIT_CATCHUP_TICKS = 40;

	private static final Set<UUID> FRIENDLY_IDS = ConcurrentHashMap.newKeySet();
	/** Same-tick fallback while the vanilla visible UUID/dragon indexes catch up. */
	private static final Map<UUID, EnderDragon> LOADED_FRIENDLY_DRAGONS = new ConcurrentHashMap<>();
	/**
	 * Where each dragon is on its orbit, accumulated rather than recomputed.
	 *
	 * <p><b>This replaces {@code (gameTime % period) / period}, which is not a continuous function of
	 * the period.</b> That expression is the fractional part of {@code gameTime / period}, and the
	 * descent sweeps the period from six hundred ticks down to a hundred and ninety - so on a world
	 * whose clock has reached six figures, {@code gameTime / period} runs from about 167 revolutions
	 * to about 526 across the hundred and sixty ticks of the descent. The fractional part therefore
	 * cycles some three hundred and sixty times, better than two full orbits <em>per tick</em>: the
	 * dragon was thrown to a different point of the circle every single tick, from the moment it
	 * appeared, because the descent starts on the tick after it spawns. That is the teleport.
	 *
	 * <p>An angle that is advanced by the current angular speed cannot do that, whatever the period
	 * does. Transient on purpose: a restart resumes the orbit from a different phase, and any phase of
	 * a circle is as correct as any other - which is a much smaller price than carrying a persisted
	 * field for a decoration.
	 */
	private static final Map<UUID, Orbit> ORBITS = new ConcurrentHashMap<>();
	/**
	 * How often the last-resort sweep in {@link #recover} may walk the whole End.
	 *
	 * <p>That sweep is a safety net for a visible-index desync, and it was written as an
	 * unconditional fallback - which made it the common case rather than the rare one. The encounter
	 * ticks {@link #tick(ServerLevel, UUID, BlockPos, double)} on <em>every</em> server tick for as
	 * long as the persisted state names a dragon, and that outlives the fight: the successful ending
	 * leaves the dragon in the sky for good. So the moment the last player leaves the End and the
	 * dragon's chunk unloads, none of the three cheap lookups can resolve it and every tick from then
	 * on walked every entity in the dimension, forever, on a world nobody is even looking at.
	 *
	 * <p>Ten seconds. The three cheap lookups still run every tick and still resolve the dragon the
	 * instant its chunk is back, so this only paces the case where the answer is "not loaded" - and
	 * {@link #tick(ServerLevel, UUID, BlockPos, double)} already documents that a caller which gets
	 * {@code false} should simply retry later.
	 */
	private static final long FULL_SCAN_INTERVAL_TICKS = 200L;
	/**
	 * Earliest game time the sweep above may run again.
	 *
	 * <p>A single field rather than one per dragon: there is at most one friendly dragon per world,
	 * and the value it guards is a whole-dimension scan rather than anything owned by an id.
	 */
	private static long nextFullScanTick = Long.MIN_VALUE;
	private static boolean initialized;

	/** One dragon's place on its orbit: the tick it was last advanced on, and how far round it is. */
	private record Orbit(long lastTick, double angle, int age) {
	}

	/**
	 * Drops every transient index when the server it belongs to goes away.
	 *
	 * <p>{@link #LOADED_FRIENDLY_DRAGONS} holds live {@code EnderDragon} references, and an entity
	 * holds its {@code Level}, which holds the {@code MinecraftServer}. Without this the whole
	 * integrated server of any world where the good ending had spawned its dragon stayed reachable
	 * from a static field for the rest of the process - so a player who finished the mod and went
	 * back to the title screen was carrying the finished world around in memory.
	 *
	 * <p>{@code FRIENDLY_IDS} and {@code ORBITS} only hold ids and doubles, but they are the same
	 * server's state and are cleared for the same reason.
	 */
	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			FRIENDLY_IDS.clear();
			LOADED_FRIENDLY_DRAGONS.clear();
			ORBITS.clear();
			nextFullScanTick = Long.MIN_VALUE;
		});
	}

	private FriendlyDragonService() {
	}

	public static EnderDragon spawn(ServerLevel level, BlockPos center) {
		return spawn(level, center, UUID.randomUUID());
	}

	/**
	 * Creates or recovers the dragon using the persisted UUID. It is deliberately never registered
	 * with EndDragonFight, so no hostile boss bar, portal or gateway bookkeeping is attached.
	 */
	public static EnderDragon spawn(ServerLevel level, BlockPos center, UUID persistedUuid) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(center, "center");
		Objects.requireNonNull(persistedUuid, "persistedUuid");
		requireEnd(level);
		Optional<EnderDragon> recovered = recover(level, persistedUuid, false);
		if (recovered.isPresent()) {
			EnderDragon dragon = recovered.get();
			configure(dragon, center);
			return dragon;
		}

		Entity collision = level.getEntity(persistedUuid);
		if (collision != null) {
			throw new IllegalStateException("Friendly dragon UUID is already owned by " + collision.getType());
		}
		EnderDragon dragon = EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT);
		if (dragon == null) throw new IllegalStateException("Unable to construct the friendly Ender Dragon");
		dragon.setUUID(persistedUuid);
		dragon.addTag(FRIENDLY_DRAGON_TAG);
		FRIENDLY_IDS.add(persistedUuid);
		configure(dragon, center);
		// Placed at the arena centre before anything else touches it: this is the point the summon
		// ceremony has spent six seconds pointing at, it is where every player is looking, and it is
		// where the altar's column of light is. The orbit is then spiralled out of here over
		// EMERGE_TICKS rather than started on the ring, so the dragon leaves the altar instead of
		// being discovered seventy-two blocks away.
		ORBITS.remove(persistedUuid);
		dragon.setPos(center.getX() + 0.5D, center.getY() + ORBIT_HEIGHT, center.getZ() + 0.5D);
		positionOnOrbit(level, dragon, center);
		// A freshly constructed entity sits at the world origin, so the previous-position fields
		// positionOnOrbit just wrote would hold (0, 0, 0) if the placement above had not run first.
		// Left that way the dragon's first movement of record is a jump from the origin to a point
		// over the arena, which is both a bogus velocity and a bogus interpolation on the tick it
		// becomes visible.
		dragon.xo = dragon.getX();
		dragon.yo = dragon.getY();
		dragon.zo = dragon.getZ();
		dragon.setDeltaMovement(Vec3.ZERO);
		if (!level.addFreshEntity(dragon)) {
			FRIENDLY_IDS.remove(persistedUuid);
			throw new IllegalStateException("Unable to add the friendly Ender Dragon to the End");
		}
		rememberLoaded(level, dragon);
		EndBossArenaService.suppressVanillaFight(level);
		return dragon;
	}

	/** Finds a loaded persisted dragon by UUID and reasserts its friendly runtime contract. */
	public static Optional<EnderDragon> recover(ServerLevel level, UUID persistedUuid) {
		return recover(level, persistedUuid, true);
	}

	/**
	 * @param rateLimitFullScan whether the last-resort whole-dimension sweep may be skipped because
	 *                          it ran recently. True for the per-tick callers, which only need to
	 *                          know whether the dragon is drivable right now and are told to retry;
	 *                          false for {@link #spawn(ServerLevel, BlockPos, UUID)}, whose answer
	 *                          decides whether a second dragon is created and therefore must never
	 *                          be "not this instant".
	 */
	private static Optional<EnderDragon> recover(ServerLevel level, UUID persistedUuid,
			boolean rateLimitFullScan) {
		if (level == null || persistedUuid == null) return Optional.empty();
		if (level.dimension() != Level.END) return Optional.empty();
		Entity direct = level.getEntity(persistedUuid);
		Optional<EnderDragon> resolved = friendlyInLevel(level, persistedUuid, direct);
		if (resolved.isPresent()) return resolved;
		resolved = friendlyInLevel(level, persistedUuid, LOADED_FRIENDLY_DRAGONS.get(persistedUuid));
		if (resolved.isPresent()) return resolved;
		for (EnderDragon dragon : level.getDragons()) {
			resolved = friendlyInLevel(level, persistedUuid, dragon);
			if (resolved.isPresent()) return resolved;
		}
		// Last resort, and deliberately rate limited - see FULL_SCAN_INTERVAL_TICKS. The three
		// lookups above are the ones that answer while the dragon is loaded; this one only ever
		// answers when the visible index has desynchronised, and paying for it every tick on a
		// dimension whose dragon is simply unloaded is the far more common outcome.
		long now = level.getGameTime();
		if (rateLimitFullScan && now < nextFullScanTick) return Optional.empty();
		nextFullScanTick = now + FULL_SCAN_INTERVAL_TICKS;
		for (Entity entity : level.getAllEntities()) {
			resolved = friendlyInLevel(level, persistedUuid, entity);
			if (resolved.isPresent()) return resolved;
		}
		return Optional.empty();
	}

	/** Returns false while the persisted entity's chunk is not loaded; callers should retry later. */
	public static boolean tick(ServerLevel level, UUID persistedUuid, BlockPos center) {
		return tick(level, persistedUuid, center, 0.0D);
	}

	/**
	 * @param approach 0 for the high resting orbit, 1 for the low circle it flies while opening the
	 *                 exit. Values between the two are interpolated, so the descent is a flight
	 *                 path rather than a teleport.
	 */
	public static boolean tick(ServerLevel level, UUID persistedUuid, BlockPos center, double approach) {
		Optional<EnderDragon> recovered = recover(level, persistedUuid);
		if (recovered.isEmpty()) return false;
		tick(level, recovered.get(), center, approach);
		return true;
	}

	public static void tick(ServerLevel level, EnderDragon dragon, BlockPos center) {
		tick(level, dragon, center, 0.0D);
	}

	public static void tick(ServerLevel level, EnderDragon dragon, BlockPos center, double approach) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(dragon, "dragon");
		Objects.requireNonNull(center, "center");
		requireEnd(level);
		if (!isFriendly(dragon)) {
			throw new IllegalArgumentException("Dragon is not owned by the successful ending");
		}
		configure(dragon, center);
		positionOnOrbit(level, dragon, center, approach);
		EndBossArenaService.suppressVanillaFight(level);
	}

	/** Smoothstep, so the dragon eases out of the high orbit instead of snapping onto the descent. */
	public static double approachEase(double approach) {
		double clamped = Math.clamp(approach, 0.0D, 1.0D);
		return clamped * clamped * (3.0D - 2.0D * clamped);
	}

	/**
	 * The whole descent-and-return schedule, as a pure function of the dragon's own age.
	 *
	 * <p>Deliberately not a function of the encounter stage. The stage is what the runtime used to
	 * read, and a stage is a step function: the tick the exit opened, the encounter left its success
	 * resolution and the approach the flight path interpolates on dropped straight to zero. Age is
	 * continuous, so a schedule written in age cannot produce a discontinuity however the stages fall
	 * around it - including a stage that is skipped, repeated or arrived at late.
	 *
	 * @param ticksSinceArrival ticks since the dragon was added to the world; negative before it exists
	 * @param workTicks         how long it spends coming down and prising the exit open
	 * @param returnTicks       how long the climb back to the resting orbit takes
	 * @return 0 on the resting orbit, 1 on the low working circle, rising and then falling between
	 */
	public static double approach(long ticksSinceArrival, int workTicks, int returnTicks) {
		if (ticksSinceArrival <= 0L) return 0.0D;
		double descent = Math.clamp(ticksSinceArrival / (double) Math.max(1, workTicks), 0.0D, 1.0D);
		double climb = Math.clamp((ticksSinceArrival - workTicks) / (double) Math.max(1, returnTicks),
				0.0D, 1.0D);
		// The climb cannot start before the descent has finished, so the difference never leaves [0, 1]
		// and is continuous across the hand-over: at exactly workTicks the descent is one and the
		// climb is zero.
		return descent - climb;
	}

	/** Ticks per lap at this point in the descent. */
	public static double orbitPeriod(double approach) {
		double eased = approachEase(approach);
		return ORBIT_PERIOD_TICKS + (WORK_PERIOD_TICKS - ORBIT_PERIOD_TICKS) * eased;
	}

	/**
	 * The whole flight path, as a pure function, so it can be checked without a world.
	 *
	 * <p>{@code age} is ticks since the dragon left the altar and drives only the spiral out;
	 * {@code approach} is the descent onto the exit. Everything the dragon's position depends on is an
	 * argument, which is what lets {@code FriendlyDragonServiceTest} sweep the whole schedule and
	 * assert the one property the old arithmetic violated: that consecutive ticks are adjacent.
	 *
	 * @return the offset from the arena centre, in blocks
	 */
	public static Vec3 orbitOffset(double angle, double approach, int age) {
		double eased = approachEase(approach);
		// The spiral out of the altar. One is fully on the ring; below that the radius is scaled
		// toward the centre the dragon was added at.
		double emergence = smoothstep(Math.clamp(age / (double) EMERGE_TICKS, 0.0D, 1.0D));
		double radius = (ORBIT_RADIUS + (WORK_RADIUS - ORBIT_RADIUS) * eased) * emergence
				+ EMERGE_START_RADIUS * (1.0D - emergence);
		double height = ORBIT_HEIGHT + (WORK_HEIGHT - ORBIT_HEIGHT) * eased;
		// The vertical weave flattens as it comes down: a five block bob is a wingbeat at forty-eight
		// blocks up and a collision with the altar at fifteen.
		double bob = 5.0D * (1.0D - eased) + 1.2D * eased;
		return new Vec3(Math.cos(angle) * radius, height + Math.sin(angle * 2.0D) * bob,
				Math.sin(angle) * radius);
	}

	/** One tick of turn at the given period, wrapped. The rule the accumulator advances on. */
	public static double advanceAngle(double previous, int steps, double period) {
		double angle = previous + steps * (Math.PI * 2.0D / Math.max(1.0D, period));
		return angle >= Math.PI * 2.0D ? angle % (Math.PI * 2.0D) : angle;
	}

	public static boolean isFriendly(Entity entity) {
		if (!(entity instanceof EnderDragon) || !entity.getTags().contains(FRIENDLY_DRAGON_TAG)) return false;
		FRIENDLY_IDS.add(entity.getUUID());
		return true;
	}

	public static boolean isFriendly(UUID uuid) {
		return uuid != null && FRIENDLY_IDS.contains(uuid);
	}

	private static void requireEnd(ServerLevel level) {
		if (level.dimension() != Level.END) {
			throw new IllegalArgumentException("The friendly ending dragon can only exist in the End");
		}
	}

	private static Optional<EnderDragon> friendlyInLevel(ServerLevel level, UUID persistedUuid,
			Entity candidate) {
		if (!(candidate instanceof EnderDragon dragon) || dragon.isRemoved() || dragon.level() != level
				|| !persistedUuid.equals(dragon.getUUID()) || !isFriendly(dragon)) return Optional.empty();
		rememberLoaded(level, dragon);
		return Optional.of(dragon);
	}

	private static void rememberLoaded(ServerLevel level, EnderDragon dragon) {
		if (level.dimension() == Level.END && dragon.level() == level && !dragon.isRemoved()) {
			LOADED_FRIENDLY_DRAGONS.put(dragon.getUUID(), dragon);
		}
	}

	private static void configure(EnderDragon dragon, BlockPos center) {
		dragon.addTag(FRIENDLY_DRAGON_TAG);
		FRIENDLY_IDS.add(dragon.getUUID());
		dragon.setInvulnerable(true);
		// Deliberately NOT setNoAi(true).
		//
		// The one thing EnderDragon#aiStep does behind isNoAi() is pin flapTime to a constant, and
		// flapTime is the wing beat: a dragon with no AI hangs in the sky with its wings held open,
		// which is exactly what the ending used to show. Hostility is already handled without it -
		// the phase is pinned to HOVERING, which only holds station, the fight registration is null
		// so there is no bar or crystal bookkeeping, the dragon is invulnerable, and its own flight
		// integration is redirected away from it by EnderDragonMixin so that this service is the
		// only thing that decides where the body is.
		dragon.setNoAi(false);
		dragon.setNoGravity(true);
		dragon.setPersistenceRequired();
		dragon.setTarget(null);
		dragon.setDragonFight(null);
		dragon.setFightOrigin(center);
		dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
		// Re-pinned every tick, and that is the whole point.
		//
		// DragonHoverPhase records its fly target exactly once - the first server tick after the
		// phase begins - and never updates it; setPhase is a no-op once the phase is already
		// current, so nothing was ever re-beginning it. Meanwhile this service flies the dragon
		// around a seventy-two block orbit, so vanilla's own flight integration spent every tick
		// accelerating toward a target the dragon was being carried further and further away from.
		// That is the "moves at enormous speed" - it was not the orbit, it was the dragon fighting
		// the orbit. begin() clears the target so the phase's own doServerTick re-pins it to
		// wherever this service has just put the body.
		//
		// Still needed now that the integration itself is redirected away, and for a second reason:
		// aiStep steers the heading only when the fly target is at least a hundred-thousandth of a
		// block away horizontally. Pinned to the body's own position that guard never opens, so the
		// yaw this service writes from the step the dragon actually took is the yaw it keeps.
		//
		// Deliberately not solved with setNoAi(true): see the note above on why the AI has to run.
		dragon.getPhaseManager().getCurrentPhase().begin();
		dragon.nearestCrystal = null;
		dragon.inWall = false;
		dragon.noPhysics = true;
		dragon.setHealth(dragon.getMaxHealth());
	}

	private static void positionOnOrbit(ServerLevel level, EnderDragon dragon, BlockPos center) {
		positionOnOrbit(level, dragon, center, 0.0D);
	}

	private static void positionOnOrbit(ServerLevel level, EnderDragon dragon, BlockPos center,
			double approach) {
		Orbit orbit = advanceOrbit(level, dragon, orbitPeriod(approach));
		Vec3 offset = orbitOffset(orbit.angle(), approach, orbit.age());
		double x = center.getX() + 0.5D + offset.x;
		double y = center.getY() + offset.y;
		double z = center.getZ() + 0.5D + offset.z;

		// The previous position has to survive the write, or the client interpolates every frame
		// from the new position to itself and the dragon renders as if it were nailed in place even
		// while its coordinates change.
		Vec3 from = dragon.position();
		dragon.xo = from.x;
		dragon.yo = from.y;
		dragon.zo = from.z;
		dragon.setPos(x, y, z);
		// Facing is taken from the step the dragon actually took rather than from a tangent formula.
		// The path is no longer a plain circle - the radius spirals outward while it emerges and
		// inward while it descends, and the height moves with both - so a tangent derived from the
		// circle alone points somewhere the dragon is not going. A difference of positions is right
		// for any path by construction, and it self-corrects if anything else ever moves the body.
		Vec3 step = new Vec3(x - from.x, y - from.y, z - from.z);
		dragon.setDeltaMovement(step);
		if (step.horizontalDistanceSqr() > 1.0E-6D) {
			// The dragon does not use the same yaw convention as everything else. Vanilla's own aiStep
			// steers it toward `180 - atan2(dx, dz)`, where an ordinary entity uses `-atan2(dx, dz)`:
			// its yaw is half a turn out from the standard, because the model faces the other way.
			// Aimed with the ordinary formula it flew its whole orbit tail first. The pitch inverts
			// with it - flipping the yaw flips the local axis the pitch turns about - so that sign
			// goes too.
			float yaw = 180.0F + (float) Math.toDegrees(Math.atan2(-step.x, step.z));
			dragon.setYRot(yaw);
			dragon.setYHeadRot(yaw);
			dragon.setYBodyRot(yaw);
			dragon.setXRot((float) Math.toDegrees(Math.atan2(step.y, step.horizontalDistance())));
		}
	}

	/**
	 * Advances this dragon's place on its orbit by one tick's worth of turn, and returns it.
	 *
	 * <p>Idempotent within a tick: the resolution can reach a dragon through more than one path on the
	 * same tick, and a second call must not double the turn. A gap - a chunk that was unloaded, a
	 * server that was paused - is caught up to a ceiling and then simply resumed, because a dragon
	 * that has been out of sight for a minute should come back where the orbit is now, not sprint
	 * through a minute of arc to get there.
	 */
	private static Orbit advanceOrbit(ServerLevel level, EnderDragon dragon, double period) {
		long now = level.getGameTime();
		UUID id = dragon.getUUID();
		Orbit previous = ORBITS.get(id);
		if (previous == null) {
			// Seeded off the UUID so two runs of the same ending do not start from the same bearing.
			double seeded = Math.floorMod(id.getLeastSignificantBits(), ORBIT_PERIOD_TICKS)
					/ (double) ORBIT_PERIOD_TICKS * Math.PI * 2.0D;
			Orbit started = new Orbit(now, seeded, 0);
			ORBITS.put(id, started);
			return started;
		}
		if (previous.lastTick() == now) return previous;
		int steps = (int) Math.clamp(now - previous.lastTick(), 0L, MAX_ORBIT_CATCHUP_TICKS);
		// Wrapped so the accumulator cannot drift into the range where a double stops resolving small
		// increments; an orbit is periodic, so the wrap is invisible.
		double angle = advanceAngle(previous.angle(), steps, period);
		Orbit advanced = new Orbit(now, angle, previous.age() + steps);
		ORBITS.put(id, advanced);
		return advanced;
	}

	private static double smoothstep(double progress) {
		return progress * progress * (3.0D - 2.0D * progress);
	}
}
