package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.entity.StabilityAnchorEntity;
import com.xm.thefourthfrequency.mixin.EndDragonFightAccessor;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Idempotent world-interface arena preparation and the bounded, no-drop terrain-scar queue.
 * Pending scar work is intentionally memory-only; callers restore only the committed edit count.
 */
public final class EndBossArenaService {
	public static final int MAX_PERMANENT_EDITS = WorldInterfacePolicy.MAX_PERMANENT_TERRAIN_EDITS;
	public static final int MAX_EDITS_PER_TICK = WorldInterfacePolicy.MAX_TERRAIN_EDITS_PER_TICK;
	public static final int MAX_LASER_EDITS = 160;
	private static final double SPIKE_SHEAR_REACH = 18.0D;
	private static final int SPIKE_SHEAR_DEPTH = 9;
	private static final int SPIKE_SHEAR_EDITS = 64;
	public static final int ARENA_RADIUS = 160;
	public static final int PORTAL_SAFE_RADIUS = 8;
	public static final int GATEWAY_COUNT = 20;
	public static final int GATEWAY_RADIUS = 96;
	public static final int ANCHOR_COUNT = 10;

	private static final int ALTAR_RADIUS = AltarShape.RADIUS;
	private static final int EDIT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
			| Block.UPDATE_SUPPRESS_DROPS;
	private static final int MAX_PENDING_SCARS = 8_192;
	/**
	 * Retained only to recognise anchors written by a build that carried them on tagged
	 * {@link EndCrystal}s. Nothing tags a new anchor: identity now comes from the entity's own type
	 * plus its synched slot index, so an untagged vanilla crystal is unambiguously not an anchor.
	 */
	private static final String ANCHOR_TAG = "thefourthfrequency.world_interface_anchor";
	private static final String ANCHOR_INDEX_PREFIX = "thefourthfrequency.world_interface_anchor.";

	public static final TagKey<Block> WORLD_INTERFACE_IMMUNE = TagKey.create(Registries.BLOCK,
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "world_interface_immune"));
	/** Existing data-pack tag remains honored while the old boss assets are retired. */

	private static final Map<ServerLevel, ArenaRuntime> RUNTIMES =
			Collections.synchronizedMap(new WeakHashMap<>());
	/**
	 * Newly added entities can be accepted by the persistent entity manager before the visible UUID
	 * index exposes them. Retain only the ten bounded arena references so same-tick reconciliation
	 * cannot try to add a second entity with the same deterministic UUID.
	 *
	 * <p><b>The weak key does not collect this map on its own, which is why {@link #initialize}
	 * clears it explicitly.</b> The values are {@code StabilityAnchorEntity}, and an entity holds a
	 * reference to its own {@code Level} - so every value in here reaches its own key, and a
	 * {@code WeakHashMap} whose value strongly references its key never drops the entry. Any world
	 * left with a surviving anchor therefore pinned its whole {@code ServerLevel}, and with it the
	 * chunk and entity storage hanging off it, for the rest of the process. {@link #RUNTIMES} does
	 * not have this problem - {@code ArenaRuntime} holds nothing but block positions - but it is
	 * cleared alongside for the same reason the rest of the ending package clears its tables.
	 */
	private static final Map<ServerLevel, Map<UUID, StabilityAnchorEntity>> KNOWN_ANCHORS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static boolean initialized;

	private EndBossArenaService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(EndBossArenaService::tickServer);
		// Matched on the owning server rather than on Level.END, because this runs after the server
		// has stopped and must not depend on its level map still answering lookups.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			synchronized (RUNTIMES) {
				RUNTIMES.keySet().removeIf(level -> level.getServer() == server);
			}
			synchronized (KNOWN_ANCHORS) {
				KNOWN_ANCHORS.keySet().removeIf(level -> level.getServer() == server);
			}
		});
	}

	/**
	 * Prepares the central altar, twenty inert gateways and ten authoritative anchors. The resonance
	 * core is written last and acts as the durable preparation marker.
	 */
	public static PreparedArena prepare(ServerLevel level) {
		Objects.requireNonNull(level, "level");
		if (level.dimension() != Level.END) {
			throw new IllegalArgumentException("The world-interface arena can only be prepared in the End");
		}
		synchronized (RUNTIMES) {
			ArenaRuntime cached = RUNTIMES.get(level);
			if (cached != null) {
				suppressVanillaFight(level);
				removeInitialHostileDragon(level);
				return cached.arena;
			}

			BlockPos existingCore = findBlockInColumn(level, 0, 0, ModBlocks.RESONANCE_CORE);
			boolean alreadyPrepared = existingCore != null;
			int altarFloorY = centralAltarFloorY(level);
			BlockPos center = new BlockPos(0, altarFloorY, 0);
			BlockPos altar = AltarShape.corePosition(center);
			BlockPos safeSpawn = mainIslandSurfaceAir(level, 0, ALTAR_RADIUS + 2);

			buildAltar(level, center, false);
			List<BlockPos> gateways = buildGateways(level);
			List<AnchorSlot> anchors = ensureAnchorSlots(level, alreadyPrepared);
			removeInitialHostileDragon(level);
			suppressVanillaFight(level);
			level.setBlock(altar, ModBlocks.RESONANCE_CORE.defaultBlockState(), EDIT_FLAGS);

			PreparedArena arena = new PreparedArena(center, altar, safeSpawn, gateways, anchors);
			ArenaRuntime runtime = new ArenaRuntime(arena, computeProtectedPositions(arena), new TerrainScarQueue());
			RUNTIMES.put(level, runtime);
			return arena;
		}
	}

	public static Set<BlockPos> protectedPositions(ServerLevel level) {
		return runtime(level).protectedPositions;
	}

	public static Set<BlockPos> protectedPositions(PreparedArena arena) {
		return computeProtectedPositions(Objects.requireNonNull(arena, "arena"));
	}

	/** Queues deterministic scar candidates; only successfully changed blocks consume the total budget. */
	public static int queueTerrainScar(ServerLevel level, Collection<BlockPos> candidates,
			int requestedMaximum, long seed) {
		Objects.requireNonNull(candidates, "candidates");
		if (requestedMaximum < 0) throw new IllegalArgumentException("Requested maximum cannot be negative");
		ArenaRuntime runtime = runtime(level);
		return runtime.scars.enqueue(candidates, requestedMaximum, seed);
	}

	/** Laser paths carry the largest single-attack allowance; the arena total still bounds them. */
	public static int queueLaserScar(ServerLevel level, Collection<BlockPos> candidates, long seed) {
		return queueTerrainScar(level, candidates, MAX_LASER_EDITS, seed);
	}

	public static int queueExplosionScar(ServerLevel level, BlockPos center, int radius,
			int requestedMaximum, long seed) {
		Objects.requireNonNull(center, "center");
		if (radius < 0 || radius > 16) throw new IllegalArgumentException("Scar radius must be between 0 and 16");
		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos position : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
				center.offset(radius, radius, radius))) {
			if (position.distSqr(center) <= (double) radius * radius) candidates.add(position.immutable());
		}
		return queueTerrainScar(level, candidates, requestedMaximum, seed);
	}

	/**
	 * Shears the crown off whichever End spike is nearest an impact. The third form is sixteen
	 * times model scale against a pillar it can actually reach, so a slam beside one should take
	 * part of it with it. The authoritative anchor, its cage and the altar are all in the protected
	 * set, so this can dismantle the scenery without ever touching encounter-critical blocks.
	 */
	public static int shearNearestSpikeCrown(ServerLevel level, BlockPos impact, long seed) {
		Objects.requireNonNull(impact, "impact");
		SpikeFeature.EndSpike nearest = null;
		double best = Double.MAX_VALUE;
		for (SpikeFeature.EndSpike spike : SpikeFeature.getSpikesForLevel(level)) {
			double dx = spike.getCenterX() - impact.getX();
			double dz = spike.getCenterZ() - impact.getZ();
			double distance = dx * dx + dz * dz;
			if (distance < best) {
				best = distance;
				nearest = spike;
			}
		}
		if (nearest == null || best > SPIKE_SHEAR_REACH * SPIKE_SHEAR_REACH) return 0;
		int radius = nearest.getRadius();
		List<BlockPos> candidates = new ArrayList<>();
		// Only the top few courses: the pillar is chipped, never felled out from under its anchor.
		for (int dy = 0; dy < SPIKE_SHEAR_DEPTH; dy++) {
			int y = nearest.getHeight() - dy;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz > radius * radius) continue;
					candidates.add(new BlockPos(nearest.getCenterX() + dx, y, nearest.getCenterZ() + dz));
				}
			}
		}
		return queueTerrainScar(level, candidates, SPIKE_SHEAR_EDITS, seed);
	}

	/** Runs one bounded queue slice. Exposed for deterministic GameTests. */
	public static int tickTerrainScars(ServerLevel level) {
		ArenaRuntime runtime;
		synchronized (RUNTIMES) {
			runtime = RUNTIMES.get(level);
		}
		return runtime == null ? 0 : runtime.scars.process(level, runtime.protectedPositions);
	}

	public static int permanentTerrainEdits(ServerLevel level) {
		return runtime(level).scars.permanentEdits;
	}

	/** Restores the persisted committed count and cancels all transient work after a restart. */
	public static void restoreTerrainEditCount(ServerLevel level, int committedEdits) {
		if (committedEdits < 0 || committedEdits > MAX_PERMANENT_EDITS) {
			throw new IllegalArgumentException("Committed terrain edits must be between 0 and 2048");
		}
		TerrainScarQueue queue = runtime(level).scars;
		queue.clearPending();
		queue.permanentEdits = committedEdits;
	}

	public static void cancelQueuedScars(ServerLevel level) {
		ArenaRuntime runtime;
		synchronized (RUNTIMES) {
			runtime = RUNTIMES.get(level);
		}
		if (runtime != null) runtime.scars.clearPending();
	}

	public static boolean canDestroy(ServerLevel level, BlockPos pos, BlockState state) {
		if (!insideEditableArena(pos) || !level.isInWorldBounds(pos) || !level.hasChunkAt(pos)
				|| state.isAir() || !state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
		if (WorldInterfaceAnchorService.protects(level, pos)) return false;
		ArenaRuntime runtime;
		synchronized (RUNTIMES) {
			runtime = RUNTIMES.get(level);
		}
		if (runtime != null && runtime.protectedPositions.contains(pos)) return false;
		if (state.is(WORLD_INTERFACE_IMMUNE)
				|| state.is(ModBlocks.RESONANCE_CORE)
				|| state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN)
				|| state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.END_PORTAL)
				|| state.is(Blocks.END_GATEWAY) || state.is(Blocks.END_PORTAL_FRAME)) return false;
		return state.getDestroySpeed(level, pos) >= 0.0F;
	}

	public static boolean insideEditableArena(BlockPos pos) {
		long distance = (long) pos.getX() * pos.getX() + (long) pos.getZ() * pos.getZ();
		return distance <= (long) ARENA_RADIUS * ARENA_RADIUS
				&& distance > (long) PORTAL_SAFE_RADIUS * PORTAL_SAFE_RADIUS;
	}

	public static void setAnchorsInvulnerable(ServerLevel level, PreparedArena arena, boolean invulnerable) {
		for (AnchorSlot slot : arena.anchors) {
			StabilityAnchorEntity anchor = findAuthoritativeAnchor(level, slot.anchorEntityUuid).orElse(null);
			if (anchor != null && anchor.anchorIndex() == slot.index) {
				anchor.setInvulnerable(invulnerable);
			}
		}
	}

	/** Resolves an authoritative anchor even before the vanilla visible UUID index catches up. */
	public static Optional<StabilityAnchorEntity> findAuthoritativeAnchor(ServerLevel level, UUID anchorEntityUuid) {
		if (level == null || anchorEntityUuid == null || level.dimension() != Level.END) return Optional.empty();
		Entity entity = findLoadedEntity(level, anchorEntityUuid);
		if (entity instanceof StabilityAnchorEntity anchor && anchor.isAlive()) {
			rememberAnchor(level, anchor);
			return Optional.of(anchor);
		}
		return Optional.empty();
	}

	/**
	 * Reconciles the ten deterministic anchor entities from the persisted authority set.
	 * A missing live anchor is a load/restart concern and is recreated; a persisted destroyed
	 * anchor is never resurrected. This is intentionally separate from {@link #prepare} because
	 * the arena marker alone cannot distinguish those cases.
	 *
	 * <p>This is also the one place a world prepared by an older build is brought forward: an anchor
	 * whose UUID still resolves to a tagged {@link EndCrystal} has that crystal removed and a
	 * {@link StabilityAnchorEntity} put in its place under the same UUID and slot index. Untagged
	 * crystals are never migrated - they are the End's own, and are only swept when they are standing
	 * on an anchor's block, exactly as before.</p>
	 */
	public static void restoreAuthoritativeAnchors(ServerLevel level, WorldInterfaceState.Snapshot snapshot,
			boolean invulnerable) {
		if (!snapshot.valid() || !snapshot.present() || snapshot.anchors().size() != ANCHOR_COUNT) return;
		if (level.dimension() != Level.END) {
			throw new IllegalArgumentException("Authoritative anchors can only be restored in the End");
		}
		for (WorldInterfaceState.Anchor anchor : snapshot.anchors()) {
			level.getChunkAt(anchor.position());
			level.waitForEntities(new ChunkPos(anchor.position()), 0);
		}
		AABB bounds = new AABB(-ARENA_RADIUS, level.getMinY(), -ARENA_RADIUS,
				ARENA_RADIUS, level.getMaxY(), ARENA_RADIUS);
		List<Entity> claimed = new ArrayList<>(level.getEntitiesOfClass(StabilityAnchorEntity.class, bounds,
				Entity::isAlive));
		claimed.addAll(level.getEntitiesOfClass(EndCrystal.class, bounds, EndBossArenaService::isLegacyTaggedAnchor));
		for (WorldInterfaceState.Anchor anchor : snapshot.anchors()) {
			BlockPos position = anchor.position();
			UUID uuid = anchor.anchorEntityUuid().orElse(null);
			for (Entity candidate : claimed) {
				if (claimsAnchorSlot(candidate, anchor.index())
						&& (anchor.destroyed() || uuid == null || !uuid.equals(candidate.getUUID()))) {
					candidate.discard();
				}
			}
			if (uuid == null) {
				if (!anchor.destroyed()) {
					throw new IllegalStateException("Live anchor " + anchor.index() + " has no entity UUID");
				}
				continue;
			}
			Entity loaded = findLoadedEntity(level, uuid);
			if (anchor.destroyed()) {
				if (loaded != null) loaded.discard();
				continue;
			}
			// A world written before the bespoke entity existed still resolves this UUID to a tagged
			// crystal. Take it down first: the replacement has to be added under the same UUID, and
			// two entities cannot hold one id at the same time.
			if (isLegacyTaggedAnchor(loaded)) {
				loaded.discard();
				forgetAnchor(level, uuid);
				loaded = null;
			}
			StabilityAnchorEntity entity = loaded instanceof StabilityAnchorEntity value ? value : null;
			if (loaded != null && entity == null) {
				throw new IllegalStateException("Anchor UUID " + uuid + " belongs to " + loaded.getType());
			}
			if (entity == null) {
				entity = StabilityAnchorEntity.create(level, anchor.index(), position.getX() + 0.5D,
						position.getY(), position.getZ() + 0.5D);
				entity.setUUID(uuid);
				if (!level.addFreshEntity(entity)) {
					throw new IllegalStateException("Unable to restore authoritative anchor " + anchor.index());
				}
				rememberAnchor(level, entity);
			}
			// The pillar already had a crystal on it.
			//
			// The sweep above only discards entities carrying this anchor's own slot, so the End's
			// own dragon-fight crystal - which claims no slot at all - sat on the same block as the
			// anchor, invisible as a duplicate and fully destructible. A player aiming at the anchor
			// hit that instead, watched it explode, and found the anchor still standing: the "an
			// anchor takes two or three hits" report, exactly.
			for (EndCrystal stray : level.getEntitiesOfClass(EndCrystal.class,
					new AABB(position).inflate(2.0D), Entity::isAlive)) {
				stray.discard();
			}
			entity.setAnchorIndex(anchor.index());
			entity.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
			entity.setInvulnerable(invulnerable);
			restoreAnchorFooting(level, position);
		}
	}

	/** Whether this entity currently occupies an anchor slot - either as the anchor or as a legacy crystal. */
	public static boolean isAuthoritativeAnchor(Entity entity) {
		return entity instanceof StabilityAnchorEntity || isLegacyTaggedAnchor(entity);
	}

	public static boolean isAuthoritativeAnchor(Entity entity, int index) {
		return claimsAnchorSlot(entity, index);
	}

	private static boolean claimsAnchorSlot(Entity entity, int index) {
		if (entity instanceof StabilityAnchorEntity anchor) return anchor.anchorIndex() == index;
		return isLegacyTaggedAnchor(entity) && entity.getTags().contains(ANCHOR_INDEX_PREFIX + index);
	}

	/** An anchor written by a build that carried the slot on a tagged vanilla crystal. */
	private static boolean isLegacyTaggedAnchor(Entity entity) {
		return entity instanceof EndCrystal && entity.getTags().contains(ANCHOR_TAG);
	}

	private static ArenaRuntime runtime(ServerLevel level) {
		synchronized (RUNTIMES) {
			ArenaRuntime runtime = RUNTIMES.get(level);
			if (runtime != null) return runtime;
		}
		prepare(level);
		synchronized (RUNTIMES) {
			return RUNTIMES.get(level);
		}
	}

	private static void tickServer(MinecraftServer server) {
		ServerLevel level = server.getLevel(Level.END);
		if (level == null) return;
		synchronized (RUNTIMES) {
			if (!RUNTIMES.containsKey(level)) return;
		}
		suppressVanillaFight(level);
		tickTerrainScars(level);
	}

	private static int centralAltarFloorY(ServerLevel level) {
		for (int x : new int[]{-ALTAR_RADIUS, ALTAR_RADIUS}) {
			for (int z : new int[]{-ALTAR_RADIUS, ALTAR_RADIUS}) {
				level.getChunkAt(new BlockPos(x, 64, z));
			}
		}
		int lowestSurface = level.getMaxY() - 1;
		for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx++) {
			for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz++) {
				lowestSurface = Math.min(lowestSurface,
						level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dx, dz) - 1);
			}
		}
		int minimum = level.getMinY() + 1;
		for (int floorY = Math.max(minimum, lowestSurface); floorY > minimum; floorY--) {
			if (altarLayerHasNativeSupport(level, floorY)) return floorY;
		}
		return minimum;
	}

	private static boolean altarLayerHasNativeSupport(ServerLevel level, int floorY) {
		for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx++) {
			for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz++) {
				BlockState support = level.getBlockState(new BlockPos(dx, floorY - 1, dz));
				if (support.isAir() || !support.getFluidState().isEmpty()) return false;
			}
		}
		return true;
	}

	private static BlockPos mainIslandSurfaceAir(ServerLevel level, int x, int z) {
		level.getChunkAt(new BlockPos(x, 64, z));
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, Math.max(level.getMinY() + 2, y), z);
	}

	/**
	 * Writes the altar and clears the air above it. The shape itself lives in {@link AltarShape} so
	 * that this and {@code ResonanceCoreBlock.buildAltar} cannot drift into two different altars.
	 *
	 * <p>Every cell that already holds a block entity is left exactly as it is. The altar is fixed at
	 * the origin of the native main island, which is where a shared world puts its return portal, its
	 * storage and its dragon-fight staging - so on any server that has been to the End before, this
	 * build lands on somebody's chests. {@link #EDIT_FLAGS} carries no {@code UPDATE_NEIGHBORS}, so
	 * the contents would not even spill: the block entity would simply cease to exist, along with
	 * everything in it, at a moment nobody chose and with nothing left to read. That is the silent
	 * swallowing this mod promises never to do, and it is worth a hole in a wall to avoid.
	 *
	 * <p>The resonance core is exempt because it is the one block entity the altar places itself; a
	 * re-prepare would otherwise refuse to rebuild around its own core.
	 */
	private static void buildAltar(ServerLevel level, BlockPos center, boolean includeCore) {
		BlockPos core = AltarShape.corePosition(center);
		int preserved = 0;
		for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx++) {
			for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz++) {
				int top = AltarShape.topOffset(dx, dz);
				for (int dy = 0; dy <= top; dy++) {
					BlockPos position = center.offset(dx, dy, dz);
					if (holdsForeignBlockEntity(level, position)) {
						preserved++;
						continue;
					}
					level.setBlock(position, AltarShape.state(dx, dy, dz, top), EDIT_FLAGS);
				}
				for (int dy = top + 1; dy <= top + AltarShape.HEADROOM; dy++) {
					BlockPos clearance = center.offset(dx, dy, dz);
					BlockState existing = level.getBlockState(clearance);
					if (clearance.equals(core) && existing.is(ModBlocks.RESONANCE_CORE)) continue;
					if (holdsForeignBlockEntity(level, clearance)) {
						preserved++;
						continue;
					}
					if (!existing.isAir() && !existing.is(Blocks.BEDROCK)) {
						level.setBlock(clearance, Blocks.AIR.defaultBlockState(), EDIT_FLAGS);
					}
				}
			}
		}
		if (includeCore && !holdsForeignBlockEntity(level, core)) {
			level.setBlock(core, ModBlocks.RESONANCE_CORE.defaultBlockState(), EDIT_FLAGS);
		}
		if (preserved > 0) {
			TheFourthFrequency.LOGGER.warn("Relay altar left {} cell(s) unbuilt: something with stored "
					+ "contents already stood at the End origin, and it was kept rather than erased", preserved);
		}
	}

	/**
	 * Whether this position holds a block entity the altar did not put there.
	 *
	 * <p>Read per position rather than per chunk, unlike the station's own siting probe: that one is
	 * choosing between hundreds of candidate footprints and can afford no per-block cost, while this
	 * one has a single fixed footprint of a few hundred cells and runs once.
	 */
	private static boolean holdsForeignBlockEntity(ServerLevel level, BlockPos position) {
		return level.getBlockEntity(position) != null
				&& !level.getBlockState(position).is(ModBlocks.RESONANCE_CORE);
	}

	private static List<BlockPos> buildGateways(ServerLevel level) {
		List<BlockPos> cores = new ArrayList<>(GATEWAY_COUNT);
		Set<BlockPos> unique = new HashSet<>();
		for (int index = 0; index < GATEWAY_COUNT; index++) {
			double angle = Math.PI * 2.0D * index / GATEWAY_COUNT;
			int x = (int) Math.round(Math.cos(angle) * GATEWAY_RADIUS);
			int z = (int) Math.round(Math.sin(angle) * GATEWAY_RADIUS);
			level.getChunkAt(new BlockPos(x, 64, z));
			int y = Math.max(64, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 3);
			BlockPos core = new BlockPos(x, y, z);
			if (!unique.add(core)) throw new IllegalStateException("Gateway position collision at " + core);
			clearGatewayStructure(level, core);
			cores.add(core);
		}
		return List.copyOf(cores);
	}

	/**
	 * The warp gate structure is no longer built. The slot positions are still computed, because
	 * they remain the addresses the encounter snapshot points its deposit particles at, but nothing
	 * is placed in the world any more and nothing is drawn there either - and any structure left
	 * behind by an older save is removed here, so a world that was prepared before this change does
	 * not keep a ring of dead bedrock.
	 */
	private static void clearGatewayStructure(ServerLevel level, BlockPos core) {
		for (int dy = -2; dy <= 2; dy++) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				if (dy == 0 || Math.abs(dy) == 1) {
					clearIfLegacyGatewayBlock(level, core.relative(direction, 2).offset(0, dy, 0));
				}
			}
			if (Math.abs(dy) == 2) clearIfLegacyGatewayBlock(level, core.offset(0, dy, 0));
		}
		clearIfLegacyGatewayBlock(level, core);
	}

	private static void clearIfLegacyGatewayBlock(ServerLevel level, BlockPos pos) {
		// Only the pieces this method used to place are removed; naturally generated End terrain
		// that happens to sit in the same column is left exactly where it is. The gate core block is
		// no longer registered, so an older save already loads those positions as air; the bedrock
		// frame around them is the part that still has to be taken down.
		if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), EDIT_FLAGS);
		}
	}

	private static List<AnchorSlot> ensureAnchorSlots(ServerLevel level, boolean alreadyPrepared) {
		List<SpikeFeature.EndSpike> spikes = new ArrayList<>(SpikeFeature.getSpikesForLevel(level));
		spikes.sort(Comparator
				.comparingDouble((SpikeFeature.EndSpike spike) -> normalizedAngle(spike.getCenterX(), spike.getCenterZ()))
				.thenComparingInt(SpikeFeature.EndSpike::getHeight));
		if (spikes.size() != ANCHOR_COUNT) {
			throw new IllegalStateException("Expected exactly ten End spikes, found " + spikes.size());
		}

		List<AnchorSlot> slots = new ArrayList<>(ANCHOR_COUNT);
		for (int index = 0; index < spikes.size(); index++) {
			SpikeFeature.EndSpike spike = spikes.get(index);
			BlockPos position = new BlockPos(spike.getCenterX(), spike.getHeight() + 1, spike.getCenterZ());
			level.getChunkAt(position);
			// Unconditional, and it was not. The spike feature puts a crystal on exactly this block,
			// and entities from a chunk that getChunkAt has just generated are not in the section
			// storage the instant it returns. Gating the wait on the arena already being prepared was
			// precisely backwards: the first prepare is the one that generates the chunk, so it is the
			// only one that can race the crystal it just caused to exist. The sweep below then found
			// nothing, the anchor went up, and the vanilla crystal loaded in on top of it.
			level.waitForEntities(new ChunkPos(position), 0);
			UUID uuid = deterministicAnchorUuid(level, index);
			Entity loaded = findLoadedEntity(level, uuid);
			// A world prepared by an older build still has a tagged crystal under this UUID. Take it
			// down here too, so re-preparing an existing arena migrates it rather than throwing on a
			// UUID that belongs to the wrong entity type.
			if (isLegacyTaggedAnchor(loaded)) {
				loaded.discard();
				forgetAnchor(level, uuid);
				loaded = null;
			}
			if (loaded != null && !(loaded instanceof StabilityAnchorEntity)) {
				throw new IllegalStateException("Anchor UUID " + uuid + " belongs to " + loaded.getType());
			}
			StabilityAnchorEntity existing = loaded instanceof StabilityAnchorEntity anchor ? anchor : null;
			if (existing == null && !alreadyPrepared) {
				removeCrystalsAt(level, position);
				existing = StabilityAnchorEntity.create(level, index, position.getX() + 0.5D,
						position.getY(), position.getZ() + 0.5D);
				existing.setUUID(uuid);
				existing.setInvulnerable(true);
				if (!level.addFreshEntity(existing)) {
					throw new IllegalStateException("Unable to create authoritative anchor " + index);
				}
				rememberAnchor(level, existing);
			} else if (existing != null) {
				// The vanilla crystal can come back after the anchor exists -- respawning the dragon
				// re-seeds the spikes -- so the sweep has to run here too, not only where the anchor
				// is first created. The anchor is no longer an EndCrystal, so nothing has to be
				// excluded from the sweep any more.
				removeCrystalsAt(level, position);
				rememberAnchor(level, existing);
				existing.setAnchorIndex(index);
			}
			restoreAnchorFooting(level, position);
			slots.add(new AnchorSlot(index, position, uuid));
		}
		return List.copyOf(slots);
	}

	/**
	 * The anchor sits bare on the spike it grew from. The old cage - one block under the anchor and one
	 * on each of the four sides - is no longer placed at all: it wrapped a band of bright custom
	 * texture around the one thing in the arena a player is meant to be looking at.
	 *
	 * <p>The block is no longer registered either, so a world prepared before this change loads
	 * those five positions as air. The four flanks were air to begin with and are left that way;
	 * the one underneath was the spike's own bedrock cap before the cage overwrote it, so an
	 * unsupported anchor gets its cap back rather than floating over a hole.</p>
	 */
	private static void restoreAnchorFooting(ServerLevel level, BlockPos anchorPosition) {
		BlockPos footing = anchorPosition.below();
		if (level.getBlockState(footing).isAir()) {
			level.setBlock(footing, Blocks.BEDROCK.defaultBlockState(), EDIT_FLAGS);
		}
	}

	/**
	 * Clears the spike top of end crystals. The anchor itself is a different entity type now, so
	 * every crystal standing here is the End's own and none of them has to be spared.
	 */
	private static void removeCrystalsAt(ServerLevel level, BlockPos position) {
		AABB bounds = new AABB(position).inflate(2.0D);
		for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, bounds, Entity::isAlive)) {
			crystal.discard();
		}
	}

	private static UUID deterministicAnchorUuid(ServerLevel level, int index) {
		String key = TheFourthFrequency.MOD_ID + ":world_interface_anchor:" + level.getSeed() + ':' + index;
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private static Entity findLoadedEntity(ServerLevel level, UUID uuid) {
		Entity direct = level.getEntity(uuid);
		if (direct != null) return direct;
		StabilityAnchorEntity known = knownAnchor(level, uuid);
		if (known != null) return known;
		for (Entity entity : level.getAllEntities()) {
			if (!entity.isRemoved() && uuid.equals(entity.getUUID())) {
				if (entity instanceof StabilityAnchorEntity anchor) rememberAnchor(level, anchor);
				return entity;
			}
		}
		return null;
	}

	private static void rememberAnchor(ServerLevel level, StabilityAnchorEntity anchor) {
		synchronized (KNOWN_ANCHORS) {
			KNOWN_ANCHORS.computeIfAbsent(level, ignored -> new HashMap<>()).put(anchor.getUUID(), anchor);
		}
	}

	/** Drops a cached reference so a migrated or discarded entity cannot be handed back out. */
	private static void forgetAnchor(ServerLevel level, UUID uuid) {
		synchronized (KNOWN_ANCHORS) {
			Map<UUID, StabilityAnchorEntity> anchors = KNOWN_ANCHORS.get(level);
			if (anchors == null) return;
			anchors.remove(uuid);
			if (anchors.isEmpty()) KNOWN_ANCHORS.remove(level);
		}
	}

	private static StabilityAnchorEntity knownAnchor(ServerLevel level, UUID uuid) {
		synchronized (KNOWN_ANCHORS) {
			Map<UUID, StabilityAnchorEntity> anchors = KNOWN_ANCHORS.get(level);
			if (anchors == null) return null;
			StabilityAnchorEntity anchor = anchors.get(uuid);
			if (anchor != null && (anchor.isRemoved() || anchor.level() != level)) {
				anchors.remove(uuid);
				anchor = null;
			}
			if (anchors.isEmpty()) KNOWN_ANCHORS.remove(level);
			return anchor;
		}
	}

	private static double normalizedAngle(int x, int z) {
		double angle = Math.atan2(z, x);
		return angle < 0.0D ? angle + Math.PI * 2.0D : angle;
	}

	private static BlockPos findBlockInColumn(ServerLevel level, int x, int z, Block block) {
		level.getChunkAt(new BlockPos(x, 64, z));
		for (int y = level.getMaxY() - 1; y >= level.getMinY(); y--) {
			BlockPos position = new BlockPos(x, y, z);
			if (level.getBlockState(position).is(block)) return position;
		}
		return null;
	}

	private static Set<BlockPos> computeProtectedPositions(PreparedArena arena) {
		Set<BlockPos> positions = new HashSet<>();
		for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx++) {
			for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz++) {
				// Down through the footing, up past the pillars and the air the terrace stands in.
				for (int dy = -2; dy <= AltarShape.MAX_OFFSET + AltarShape.HEADROOM; dy++) {
					positions.add(arena.center.offset(dx, dy, dz));
				}
			}
		}
		for (BlockPos core : arena.gatewayCorePositions) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -3; dz <= 3; dz++) positions.add(core.offset(dx, dy, dz));
				}
			}
		}
		for (AnchorSlot anchor : arena.anchors) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dy = -2; dy <= 3; dy++) {
					for (int dz = -2; dz <= 2; dz++) positions.add(anchor.position.offset(dx, dy, dz));
				}
			}
		}
		for (int dx = -PORTAL_SAFE_RADIUS; dx <= PORTAL_SAFE_RADIUS; dx++) {
			for (int dz = -PORTAL_SAFE_RADIUS; dz <= PORTAL_SAFE_RADIUS; dz++) {
				if (dx * dx + dz * dz > PORTAL_SAFE_RADIUS * PORTAL_SAFE_RADIUS) continue;
				for (int dy = -8; dy <= 8; dy++) positions.add(arena.center.offset(dx, dy, dz));
			}
		}
		return Set.copyOf(positions);
	}

	private static void removeInitialHostileDragon(ServerLevel level) {
		for (EnderDragon dragon : List.copyOf(level.getDragons())) {
			if (!FriendlyDragonService.isFriendly(dragon)) dragon.discard();
		}
	}

	/** Suppresses vanilla fight bookkeeping without invoking EndDragonFight#setDragonKilled. */
	public static void suppressVanillaFight(ServerLevel level) {
		EndDragonFight fight = level.getDragonFight();
		if (fight == null) return;
		EndDragonFightAccessor accessor = (EndDragonFightAccessor) fight;
		accessor.thefourthfrequency$setDragonKilledSilently(true);
		accessor.thefourthfrequency$setNeedsStateScanning(false);
		accessor.thefourthfrequency$setDragonUuid(null);
		accessor.thefourthfrequency$setRespawnStage(null);
		accessor.thefourthfrequency$setRespawnCrystals(null);
		ServerBossEvent event = accessor.thefourthfrequency$dragonEvent();
		event.setVisible(false);
		event.removeAllPlayers();
	}

	private static long mix(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	public record PreparedArena(BlockPos center, BlockPos altar, BlockPos safeSpawn,
			List<BlockPos> gatewayCorePositions, List<AnchorSlot> anchors) {
		public PreparedArena {
			Objects.requireNonNull(center, "center");
			Objects.requireNonNull(altar, "altar");
			Objects.requireNonNull(safeSpawn, "safeSpawn");
			center = center.immutable();
			altar = altar.immutable();
			safeSpawn = safeSpawn.immutable();
			gatewayCorePositions = gatewayCorePositions.stream().map(BlockPos::immutable).toList();
			anchors = List.copyOf(anchors);
			if (gatewayCorePositions.size() != GATEWAY_COUNT) {
				throw new IllegalArgumentException("Prepared arena must contain exactly twenty gateways");
			}
			if (new HashSet<>(gatewayCorePositions).size() != GATEWAY_COUNT) {
				throw new IllegalArgumentException("Prepared gateway positions must be unique");
			}
			if (anchors.size() != ANCHOR_COUNT) {
				throw new IllegalArgumentException("Prepared arena must contain exactly ten anchors");
			}
			if (anchors.stream().map(AnchorSlot::index).distinct().count() != ANCHOR_COUNT
					|| anchors.stream().map(AnchorSlot::position).distinct().count() != ANCHOR_COUNT
					|| anchors.stream().map(AnchorSlot::anchorEntityUuid).distinct().count() != ANCHOR_COUNT) {
				throw new IllegalArgumentException("Prepared anchors must have unique indices, positions and UUIDs");
			}
		}
	}

	public record AnchorSlot(int index, BlockPos position, UUID anchorEntityUuid) {
		public AnchorSlot {
			if (index < 0 || index >= ANCHOR_COUNT) throw new IllegalArgumentException("Anchor index must be 0..9");
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(anchorEntityUuid, "anchorEntityUuid");
			position = position.immutable();
		}
	}

	private record ArenaRuntime(PreparedArena arena, Set<BlockPos> protectedPositions,
			TerrainScarQueue scars) {
	}

	private static final class TerrainScarQueue {
		private final Deque<BlockPos> pending = new ArrayDeque<>();
		private final Set<Long> pendingKeys = new LinkedHashSet<>();
		private int permanentEdits;

		private int enqueue(Collection<BlockPos> candidates, int requestedMaximum, long seed) {
			if (requestedMaximum == 0 || permanentEdits >= MAX_PERMANENT_EDITS) return 0;
			List<BlockPos> ordered = candidates.stream()
					.filter(Objects::nonNull)
					.map(BlockPos::immutable)
					.distinct()
					.sorted(Comparator.<BlockPos>comparingLong(position -> mix(seed ^ position.asLong()))
							.thenComparingLong(BlockPos::asLong))
					.toList();
			int available = Math.min(MAX_PENDING_SCARS - pending.size(),
					MAX_PERMANENT_EDITS - permanentEdits - pending.size());
			int limit = Math.min(requestedMaximum, Math.max(0, available));
			int added = 0;
			for (BlockPos position : ordered) {
				if (added >= limit) break;
				if (!pendingKeys.add(position.asLong())) continue;
				pending.addLast(position);
				added++;
			}
			return added;
		}

		private int process(ServerLevel level, Set<BlockPos> protectedPositions) {
			if (permanentEdits >= MAX_PERMANENT_EDITS) {
				clearPending();
				return 0;
			}
			int changed = 0;
			int examined = 0;
			while (!pending.isEmpty() && changed < MAX_EDITS_PER_TICK && examined < 64
					&& permanentEdits < MAX_PERMANENT_EDITS) {
				BlockPos position = pending.removeFirst();
				pendingKeys.remove(position.asLong());
				examined++;
				if (!insideEditableArena(position) || !level.isInWorldBounds(position)
						|| !level.hasChunkAt(position)) continue;
				BlockState state = level.getBlockState(position);
				if (protectedPositions.contains(position) || !canDestroy(level, position, state)) continue;
				// Erosion outranks a scar.
				//
				// A lost encounter deliberately keeps draining this queue, and the failure erosion
				// commits its missing-texture blocks into the same ground. Left to run, the queue
				// carved those straight back out to air - so the island a table lost on visibly shed
				// a slab of the very damage it was supposed to keep, right as the boss vanished.
				// A block that has stopped being able to describe itself is already the final state
				// of that column; there is nothing further for a scar to take.
				if (state.is(ModBlocks.MISSING_TEXTURE_PROXY)) continue;
				if (level.setBlock(position, Blocks.AIR.defaultBlockState(), EDIT_FLAGS)) {
					changed++;
					permanentEdits++;
				}
			}
			return changed;
		}

		private void clearPending() {
			pending.clear();
			pendingKeys.clear();
		}
	}
}
