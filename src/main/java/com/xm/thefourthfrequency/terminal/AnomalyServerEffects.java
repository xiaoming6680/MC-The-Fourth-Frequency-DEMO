package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.WatcherService;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded server-side implementations for reality-changing anomalies. */
public final class AnomalyServerEffects {
	private static final Map<ServerPlayer, AlignmentTask> ALIGNMENTS = new HashMap<>();
	private static final Map<ServerPlayer, LightDropoutTask> LIGHT_DROPOUTS = new HashMap<>();
	private static final Map<ServerPlayer, DoorCascadeTask> DOORS = new HashMap<>();
	private static final Map<ServerPlayer, MovementTask> MOVEMENTS = new HashMap<>();
	private static boolean initialized;

	private AnomalyServerEffects() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(AnomalyServerEffects::tick);
	}

	public static EffectLease begin(ServerPlayer player, AnomalyDefinition definition, int durationTicks,
			long seed, AnomalyRuntimeService.Anchor anchor) {
		return switch (definition.id()) {
			case "watcher_alignment" -> alignment(player, durationTicks);
			case "dark_watcher" -> watcher(player, durationTicks);
			case "light_dropout" -> lightDropout(player, durationTicks);
			case "door_cascade" -> doors(player, seed);
			case "experience_gap" -> movement(player, durationTicks);
			case "unrendered_layer" -> unrenderedLayer(player, durationTicks);
			default -> new EffectLease(() -> { });
		};
	}

	private static EffectLease alignment(ServerPlayer player, int durationTicks) {
		AlignmentTask task = new AlignmentTask(player, player.level().getGameTime() + durationTicks);
		ALIGNMENTS.put(player, task);
		return new EffectLease(() -> ALIGNMENTS.remove(player));
	}

	private static EffectLease watcher(ServerPlayer player, int durationTicks) {
		WatcherEntity watcher = WatcherService.spawnAnomaly(player, durationTicks);
		return watcher == null ? null : new EffectLease(() -> { if (watcher.isAlive()) watcher.discard(); });
	}

	private static EffectLease lightDropout(ServerPlayer player, int durationTicks) {
		LightDropoutTask task = LightDropoutTask.create(player, durationTicks);
		if (task == null) return null;
		LIGHT_DROPOUTS.put(player, task);
		return new EffectLease(() -> {
			LightDropoutTask removed = LIGHT_DROPOUTS.remove(player);
			if (removed != null) removed.restore();
		});
	}

	private static EffectLease doors(ServerPlayer player, long seed) {
		DoorCascadeTask task = DoorCascadeTask.create(player, seed);
		if (task == null) return null;
		DOORS.put(player, task);
		return new EffectLease(() -> {
			DoorCascadeTask removed = DOORS.remove(player);
			if (removed != null) removed.clearProgress();
		});
	}

	/**
	 * The one effect that owns its own clock.
	 *
	 * <p>Everything else here is torn down by the lease when the anomaly's duration elapses, and the
	 * lease is enough because the effect is something layered over the world the player is standing
	 * in. This one moved them out of that world, so its end condition is a place rather than a time -
	 * and {@code UnrenderedSessionService} has to keep watching for a disconnect, a death and a
	 * shutdown that the anomaly runtime knows nothing about. The lease is still the timeout: it fires
	 * at the duration and returns anybody still down there.
	 */
	private static EffectLease unrenderedLayer(ServerPlayer player, int durationTicks) {
		if (!com.xm.thefourthfrequency.unrendered.UnrenderedSessionService.begin(player, durationTicks)) {
			return null;
		}
		return new EffectLease(() -> com.xm.thefourthfrequency.unrendered.UnrenderedSessionService
				.returnToSource(player, "anomaly_cleanup"));
	}

	private static EffectLease movement(ServerPlayer player, int durationTicks) {
		MovementTask task = MovementTask.create(player, durationTicks);
		if (task == null) return null;
		MOVEMENTS.put(player, task);
		return new EffectLease(() -> {
			MovementTask removed = MOVEMENTS.remove(player);
			if (removed != null) removed.stop();
		});
	}

	private static void tick(MinecraftServer server) {
		for (AlignmentTask task : List.copyOf(ALIGNMENTS.values())) task.tick(server);
		for (LightDropoutTask task : List.copyOf(LIGHT_DROPOUTS.values())) task.tick();
		for (DoorCascadeTask task : List.copyOf(DOORS.values())) task.tick();
		for (MovementTask task : List.copyOf(MOVEMENTS.values())) task.tick(server);
	}

	private record AlignmentTask(ServerPlayer player, long endTick) {
		private void tick(MinecraftServer server) {
			if (player.isRemoved() || player.level().getGameTime() >= endTick) {
				ALIGNMENTS.remove(player);
				return;
			}
			AABB range = player.getBoundingBox().inflate(30.0D);
			for (Mob mob : player.level().getEntitiesOfClass(Mob.class, range, Mob::isAlive)) {
				mob.getLookControl().setLookAt(player, 360.0F, 360.0F);
				Vec3 delta = player.getEyePosition().subtract(mob.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
				mob.setYHeadRot(yaw);
			}
		}
	}

	/**
	 * The dark closing in from the outside, and letting go from the inside.
	 *
	 * <p>Pacing and ordering live in {@link LightDropoutSequence}; this only applies them. The
	 * task holds no schedule of its own - every tick it asks how many lights should be out and
	 * how many back by now, then works the difference - so a lagging server catches up rather
	 * than silently skipping one and leaving it lit.</p>
	 */
	private static final class LightDropoutTask {
		private static final int HORIZONTAL_RADIUS = 16;
		private static final int VERTICAL_RADIUS = 8;
		private final ServerLevel level;
		private final ServerPlayer player;
		private final List<LightSnapshot> ordered;
		private final int durationTicks;
		/** Null until that light is actually out, and null again once it is back. */
		private final BlockState[] applied;
		private int age;
		private int extinguished;
		private int restored;

		private LightDropoutTask(ServerLevel level, ServerPlayer player, List<LightSnapshot> ordered,
				int durationTicks) {
			this.level = level;
			this.player = player;
			this.ordered = ordered;
			this.durationTicks = durationTicks;
			this.applied = new BlockState[ordered.size()];
		}

		private static LightDropoutTask create(ServerPlayer player, int durationTicks) {
			if (!(player.level() instanceof ServerLevel level)) return null;
			BlockPos origin = player.blockPosition();
			List<LightSnapshot> candidates = new ArrayList<>();
			forEachNearbyLight(level, origin, (pos, original, extinguished) ->
					candidates.add(new LightSnapshot(pos.immutable(), original, extinguished)));
			if (candidates.isEmpty()) return null;
			// Farthest first. The order is the whole effect: the same set of blocks going out
			// from the edge inward is the dark arriving, while going out all at once is a switch.
			candidates.sort(Comparator.comparingDouble((LightSnapshot value) ->
					value.position().distSqr(origin)).reversed());
			return new LightDropoutTask(level, player, List.copyOf(candidates), durationTicks);
		}

		private void tick() {
			if (player.isRemoved()) return;
			age++;
			int count = ordered.size();
			int wantOut = LightDropoutSequence.extinguishedBy(age, count, durationTicks);
			while (extinguished < wantOut) extinguishNext();
			int wantBack = LightDropoutSequence.restoredBy(age, count, durationTicks);
			while (restored < wantBack) restoreNext();
		}

		private void extinguishNext() {
			int index = extinguished++;
			LightSnapshot snapshot = ordered.get(index);
			BlockPos pos = snapshot.position();
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).equals(snapshot.original())) return;
			if (!level.setBlock(pos, snapshot.extinguished(), Block.UPDATE_CLIENTS)) return;
			applied[index] = snapshot.extinguished();
			// A light that goes out silently is a rendering change. A light that goes out with the
			// sound of one going out is an event happening at a place, which is what lets the
			// player turn and look at the wrong part of their own base.
			level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
					0.55F, 0.86F + Math.floorMod(pos.hashCode(), 17) * 0.01F);
		}

		private void restoreNext() {
			restoreAt(LightDropoutSequence.restoreIndex(restored++, ordered.size()));
		}

		/** Silent on purpose: the dark announced itself, and its leaving does not get to. */
		private void restoreAt(int index) {
			BlockState extinguishedState = applied[index];
			if (extinguishedState == null) return;
			applied[index] = null;
			LightSnapshot snapshot = ordered.get(index);
			BlockPos pos = snapshot.position();
			if (level.hasChunkAt(pos) && level.getBlockState(pos).equals(extinguishedState)) {
				level.setBlock(pos, snapshot.original(), Block.UPDATE_CLIENTS);
			}
		}

		/** The lease closing. Whatever the sequence has not handed back yet returns at once. */
		private void restore() {
			for (int step = 0; step < ordered.size(); step++)
				restoreAt(LightDropoutSequence.restoreIndex(step, ordered.size()));
		}
	}

	private record LightSnapshot(BlockPos position, BlockState original, BlockState extinguished) {
	}

	@FunctionalInterface
	private interface LightConsumer {
		void accept(BlockPos pos, BlockState original, BlockState extinguished);
	}

	static boolean hasExtinguishableLight(ServerLevel level, BlockPos origin) {
		boolean[] found = {false};
		forEachNearbyLight(level, origin, (pos, original, extinguished) -> found[0] = true, found);
		return found[0];
	}

	private static void forEachNearbyLight(ServerLevel level, BlockPos origin, LightConsumer consumer) {
		forEachNearbyLight(level, origin, consumer, null);
	}

	private static void forEachNearbyLight(ServerLevel level, BlockPos origin, LightConsumer consumer,
			boolean[] stop) {
		FrequencyWorldData data = FrequencyWorldData.get(level.getServer());
		for (BlockPos cursor : BlockPos.betweenClosed(
				origin.offset(-LightDropoutTask.HORIZONTAL_RADIUS, -LightDropoutTask.VERTICAL_RADIUS,
						-LightDropoutTask.HORIZONTAL_RADIUS),
				origin.offset(LightDropoutTask.HORIZONTAL_RADIUS, LightDropoutTask.VERTICAL_RADIUS,
						LightDropoutTask.HORIZONTAL_RADIUS))) {
			if (stop != null && stop[0]) return;
			int dx = cursor.getX() - origin.getX();
			int dz = cursor.getZ() - origin.getZ();
			if (dx * dx + dz * dz > LightDropoutTask.HORIZONTAL_RADIUS * LightDropoutTask.HORIZONTAL_RADIUS
					|| !level.hasChunkAt(cursor) || protectedPosition(level, data, cursor)) continue;
			BlockState original = level.getBlockState(cursor);
			if (original.getLightEmission() <= 0) continue;
			BlockState extinguished = extinguishedState(level, cursor, original);
			if (extinguished != null && !extinguished.equals(original)) {
				consumer.accept(cursor.immutable(), original, extinguished);
			}
		}
	}

	/**
	 * A block whose light is off, or {@code null} to leave this one alone.
	 *
	 * <p>The order is deliberate, and the last two branches are the point. Anything with a {@code LIT}
	 * property or a light level just turns it off and is perfectly reversible. Everything else has to
	 * be replaced, and the two kinds of light source want opposite answers:
	 *
	 * <ul>
	 * <li><b>Thin fittings</b> - torches, lanterns, end rods, glow lichen - are things hung on a
	 *     surface. Removing one leaves the surface intact, which is exactly what a light going out
	 *     looks like, so these are cleared.</li>
	 * <li><b>Solid blocks</b> - glowstone, sea lanterns, shroomlight - are structure. Clearing one
	 *     punches a hole through somebody's wall, which is not a light going out, it is a block being
	 *     stolen. These are swapped for a dead relative of the same family where one exists
	 *     ({@link #DARK_TWIN}), and left completely alone where none does.</li>
	 * </ul>
	 *
	 * <p>Leaving one alone is a real answer rather than a failure: the effect is "the dark arrives",
	 * and it survives one glowstone block in the ceiling staying lit far better than it survives the
	 * ceiling gaining a hole.
	 */
	private static BlockState extinguishedState(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
			return state.setValue(BlockStateProperties.LIT, false);
		}
		if (state.is(Blocks.LIGHT) && state.hasProperty(LightBlock.LEVEL)) {
			return state.setValue(LightBlock.LEVEL, 0);
		}
		// Never erase inventories, portal controllers, or fluids merely because they emit light.
		if (level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) return null;
		Block twin = DARK_TWIN.get(state.getBlock());
		if (twin != null) return twin.defaultBlockState();
		return state.isCollisionShapeFullBlock(level, pos) ? null : Blocks.AIR.defaultBlockState();
	}

	/**
	 * Solid light sources that have an honest unlit relative, and what it is.
	 *
	 * <p>Curated rather than derived: there is no registry relation between a jack o'lantern and a
	 * carved pumpkin, and a heuristic that guessed at one would eventually swap somebody's build for
	 * a block that merely happens to be the same colour. Anything not on this list is left standing.
	 *
	 * <p>Glowstone is deliberately absent. It has no unlit relative, and the nearest thing by colour
	 * is sand - which falls. Swapping a ceiling light for a gravity block would rearrange the build
	 * permanently and leave nothing to read it by. It stays lit.
	 */
	private static final Map<Block, Block> DARK_TWIN = Map.of(
			Blocks.JACK_O_LANTERN, Blocks.CARVED_PUMPKIN,
			Blocks.SEA_LANTERN, Blocks.PRISMARINE_BRICKS,
			Blocks.SHROOMLIGHT, Blocks.NETHER_WART_BLOCK,
			Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN);

	private static final class DoorCascadeTask {
		private static final int SEARCH_RADIUS = 20;
		private static final int BREAK_TICKS = 10;
		private final ServerLevel level;
		private final ServerPlayer player;
		private final List<BlockPos> doors;
		private int age;
		private DoorCascadeTask(ServerLevel level, ServerPlayer player, List<BlockPos> doors) {
			this.level = level; this.player = player; this.doors = doors;
		}

		private static DoorCascadeTask create(ServerPlayer player, long seed) {
			if (!(player.level() instanceof ServerLevel level)) return null;
			FrequencyWorldData data = FrequencyWorldData.get(level.getServer());
			List<BlockPos> candidates = new ArrayList<>();
			BlockPos origin = player.blockPosition();
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -6, -SEARCH_RADIUS),
					origin.offset(SEARCH_RADIUS, 6, SEARCH_RADIUS))) {
				if (pos.distSqr(origin) > SEARCH_RADIUS * SEARCH_RADIUS || protectedPosition(level, data, pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof DoorBlock) || !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
						|| state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
				// A door that is already open has nothing to be forced. Filtered here rather than at the
				// tick so the count the sequence is built from is the number of doors that will actually
				// move - otherwise a house left open would spend half the cascade on nothing visible.
				if (!state.hasProperty(BlockStateProperties.OPEN)
						|| state.getValue(BlockStateProperties.OPEN)) continue;
				BlockPos upper = pos.above();
				if (!(level.getBlockState(upper).getBlock() instanceof DoorBlock) || protectedPosition(level, data, upper)) continue;
				candidates.add(pos.immutable());
			}
			candidates.sort(Comparator.comparingDouble((BlockPos value) -> value.distSqr(origin)).reversed());
			int count = doorCount(candidates.size(), seed);
			if (count < 2) return null;
			return new DoorCascadeTask(level, player, List.copyOf(candidates.subList(0, count)));
		}

		private void tick() {
			int doorIndex = age / BREAK_TICKS;
			int stageAge = age % BREAK_TICKS;
			if (doorIndex < doors.size()) {
				BlockPos lower = doors.get(doorIndex);
				BlockState state = level.getBlockState(lower);
				int breaker = breakerId(player, doorIndex);
				if (state.getBlock() instanceof DoorBlock) {
					level.destroyBlockProgress(breaker, lower, stageAge);
					if (stageAge == BREAK_TICKS - 1) {
						level.destroyBlockProgress(breaker, lower, -1);
						// Everything about this beat is kept except the deletion. The cracks, the
						// particle burst, the zombie's break sound and the material's own break sound
						// all still say a door was forced - but what is left afterwards is a door
						// standing open rather than a doorway with nothing in it and no dropped item to
						// pick up. The old version replaced both halves with air and then discarded the
						// drops, which is the silent swallowing the world bible rules out; on a shared
						// server it was doing it to doors the target had never touched.
						level.levelEvent(2001, lower, Block.getId(state));
						level.playSound(null, lower, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR,
								SoundSource.BLOCKS, 1.15F, 0.82F);
						level.playSound(null, lower, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
						forceOpen(level, lower, state);
					}
				}
			}
			age++;
			if (doorIndex >= doors.size()) DOORS.remove(player);
		}

		private void clearProgress() {
			for (int index = 0; index < doors.size(); index++)
				level.destroyBlockProgress(breakerId(player, index), doors.get(index), -1);
		}

		/**
		 * Swings the door and leaves it swung. Both halves, because a door is two blocks and setting
		 * only the lower one produces a pair whose halves disagree about being open.
		 *
		 * <p>{@code UPDATE_ALL} rather than the client-only flag the deletion used: this is a real
		 * state change that redstone, pathfinding and anything else watching the block is entitled to
		 * see. An already-open door is left alone rather than being toggled shut, since the effect is
		 * "your doors are open", not "your doors moved".
		 */
		private static void forceOpen(ServerLevel level, BlockPos lower, BlockState state) {
			if (!state.hasProperty(BlockStateProperties.OPEN)
					|| state.getValue(BlockStateProperties.OPEN)) return;
			level.setBlock(lower, state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
			BlockState upper = level.getBlockState(lower.above());
			if (upper.getBlock() instanceof DoorBlock && upper.hasProperty(BlockStateProperties.OPEN)) {
				level.setBlock(lower.above(), upper.setValue(BlockStateProperties.OPEN, true),
						Block.UPDATE_ALL);
			}
		}
	}

	public static int doorCount(int candidates, long seed) {
		return AnomalySelectionRules.doorCount(candidates, seed);
	}

	public static List<BlockPos> safeMovementPath(ServerLevel level, BlockPos origin) {
		List<BlockPos> best = List.of();
		for (int direction = 0; direction < 16; direction++) {
			double angle = direction * Math.PI * 2.0D / 16.0D;
			List<BlockPos> path = new ArrayList<>();
			Set<BlockPos> visited = new HashSet<>();
			BlockPos previous = origin;
			for (int distance = 1; distance <= 24; distance++) {
				int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
				int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
				BlockPos next = safeStep(level, x, previous.getY(), z);
				if (next == null || !visited.add(next)) break;
				path.add(next);
				previous = next;
			}
			if (path.size() == 24) return List.copyOf(path);
			if (path.size() > best.size()) best = List.copyOf(path);
		}
		return best.size() >= 12 ? best : List.of();
	}

	private static BlockPos safeStep(ServerLevel level, int x, int previousY, int z) {
		for (int offset : new int[] { 0, 1, -1 }) {
			BlockPos feet = new BlockPos(x, previousY + offset, z);
			BlockState floor = level.getBlockState(feet.below());
			if (!level.hasChunkAt(feet) || !floor.isFaceSturdy(level, feet.below(), Direction.UP)
					|| hazardous(floor) || hazardous(level.getBlockState(feet))
					|| hazardous(level.getBlockState(feet.above()))) continue;
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
					&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return feet;
		}
		return null;
	}

	private static boolean hazardous(BlockState state) {
		return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)
				|| state.getFluidState().is(Fluids.LAVA);
	}

	private static final class MovementTask {
		private static final double MAX_SPEED = 0.55D;
		private final ServerPlayer player;
		private final List<BlockPos> path;
		private final int duration;
		private int age;
		private int waypoint;
		private MovementTask(ServerPlayer player, List<BlockPos> path, int duration) {
			this.player = player; this.path = path; this.duration = duration;
		}
		private static MovementTask create(ServerPlayer player, int duration) {
			if (!(player.level() instanceof ServerLevel level)) return null;
			List<BlockPos> path = safeMovementPath(level, player.blockPosition());
			return path.isEmpty() ? null : new MovementTask(player, path, duration);
		}
		private void tick(MinecraftServer server) {
			if (player.isRemoved() || !(player.level() instanceof ServerLevel level)) { MOVEMENTS.remove(player); return; }
			while (waypoint < path.size()
					&& horizontalDelta(Vec3.atBottomCenterOf(path.get(waypoint)), player.position()).lengthSqr() < 0.16D)
				waypoint++;
			if (waypoint < path.size()) {
				Vec3 direction = horizontalDelta(Vec3.atBottomCenterOf(path.get(waypoint)), player.position());
				double plannedSpeed = Math.min(MAX_SPEED, path.size() / Math.max(1.0D, duration) * 1.08D);
				Vec3 movement = direction.lengthSqr() <= plannedSpeed * plannedSpeed
						? direction : direction.normalize().scale(plannedSpeed);
				player.setDeltaMovement(movement.x, player.getDeltaMovement().y, movement.z);
			}
			player.hurtMarked = true;
			if (++age >= duration) {
				MOVEMENTS.remove(player);
				stop();
			}
		}
		private void stop() {
			player.setDeltaMovement(0.0D, player.getDeltaMovement().y, 0.0D);
			player.hurtMarked = true;
		}
		private static Vec3 horizontalDelta(Vec3 target, Vec3 current) {
			return new Vec3(target.x - current.x, 0.0D, target.z - current.z);
		}
	}

	public static boolean protectedPosition(ServerLevel level, FrequencyWorldData data, BlockPos pos) {
		String namespace = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace();
		if (namespace.equals("thefourthfrequency")) return true;
		BlockPos station = data.stationPosition().orElse(null);
		if (station != null && within(pos, station, 6, 7, 5)) return true;
		return false;
	}

	static int activeLeaseCountForGameTest() {
		return ALIGNMENTS.size() + LIGHT_DROPOUTS.size() + DOORS.size() + MOVEMENTS.size();
	}

	private static boolean within(BlockPos pos, BlockPos center, int x, int y, int z) {
		return Math.abs(pos.getX() - center.getX()) <= x && Math.abs(pos.getY() - center.getY()) <= y
				&& Math.abs(pos.getZ() - center.getZ()) <= z;
	}

	private static int breakerId(ServerPlayer player, int index) {
		return -Math.abs(System.identityHashCode(player) * 31 + index + 1);
	}

	public static final class EffectLease {
		private final Runnable cleanup;
		private boolean cleaned;
		public EffectLease(Runnable cleanup) { this.cleanup = cleanup == null ? () -> { } : cleanup; }
		public void cleanup() { if (!cleaned) { cleaned = true; cleanup.run(); } }
	}
}
