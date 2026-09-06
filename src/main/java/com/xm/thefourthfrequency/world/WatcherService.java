package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WatcherService {
	/** Raised from 24: two extra placement rules reject more columns, so more are sampled. */
	private static final int POSITION_ATTEMPTS = 48;
	/** How far behind it something solid has to stand for the figure to read as framed. */
	private static final double BACKDROP_RANGE = 6.0;
	/**
	 * Time between attempts per player, before the random spread is added.
	 *
	 * <p>Shortened from 2800+3600. Two of the three things that decide whether a sighting happens -
	 * darkness and a usable position - are environmental and fail often, and until now a failure of
	 * either cost a full interval (see the retry constant below). Raising the rate and stopping the
	 * waste are the same change made from both ends.
	 */
	private static final long BASE_INTERVAL_TICKS = 1800L;
	private static final int INTERVAL_SPREAD_TICKS = 2400;
	/** The wait after an attempt that never got as far as being a sighting. */
	private static final long RETRY_INTERVAL_TICKS = 400L;

	private static final Map<UUID, Long> NEXT_ATTEMPT = new HashMap<>();
	private static boolean initialized;
	private WatcherService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(WatcherService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) trySpawn(player, data, false);
	}

	public static boolean debugSpawn(ServerPlayer player) {
		return trySpawn(player, FrequencyWorldData.get(player.level().getServer()), true);
	}

	public static WatcherEntity spawnAnomaly(ServerPlayer player, int lifetimeTicks) {
		if (!(player.level() instanceof ServerLevel level) || !player.isAlive() || player.isSpectator()) return null;
		// A pair of eyes in the dark needs the dark. The natural sighting has always required this
		// and the anomaly did not, so in daylight it produced a plainly lit humanoid standing in a
		// field - which is the same figure, minus the only thing that made it work.
		if (!inDarkness(level, player)) return null;
		AABB search = player.getBoundingBox().inflate(32.0);
		if (!level.getEntitiesOfClass(WatcherEntity.class, search,
				watcher -> watcher.observes(player.getUUID())).isEmpty()) return null;
		BlockPos position = findPosition(level, player, true, 8.0, 14.0, true);
		if (position == null) return null;
		WatcherEntity watcher = ModEntities.WATCHER.create(level, EntitySpawnReason.EVENT);
		if (watcher == null) return null;
		// The anomaly beat is "a glowing eye in the dark", so this one squares up to the player.
		orient(watcher, position, player, 0.0F);
		watcher.observe(player, Math.min(400, Math.max(20, lifetimeTicks)));
		return level.addFreshEntity(watcher) ? watcher : null;
	}

	/**
	 * Night outside, or unlit underground. Shared by the natural sighting and the anomaly.
	 *
	 * <p>Both need it for the same reason, so it is one test rather than two that can drift apart -
	 * which is exactly what had happened: the anomaly had no darkness condition at all.
	 */
	private static boolean inDarkness(ServerLevel level, ServerPlayer player) {
		long day = Math.floorMod(level.getDayTime(), 24_000L);
		if (day >= 12_500L || day <= 1_000L) return true;
		BlockPos at = player.blockPosition();
		return !level.canSeeSky(at) && level.getMaxLocalRawBrightness(at) <= 7;
	}

	private static boolean trySpawn(ServerPlayer player, FrequencyWorldData data, boolean forced) {
		if (!(player.level() instanceof ServerLevel level) || !player.isAlive() || player.isSpectator()) return false;
		// Applies to the forced debug path as well. The band-stage gate below already makes an
		// ambient sighting in the unrendered layer impossible - that anomaly is tier five and this
		// figure only appears before the terminal band has advanced at all - but the debug entry
		// point bypasses that gate, and a watcher standing in a corridor of the layer would be the
		// one thing down there that means something, in a place whose whole effect is that nothing
		// does. See PrivateDimensions for why this question is asked in one place.
		if (PrivateDimensions.isPrivate(level)) return false;
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || (!forced && record.getIntOr(TerminalData.BAND_STAGE, 0) > 0)) return false;
		long now = level.getGameTime();
		if (!forced) {
			if (now < NEXT_ATTEMPT.getOrDefault(player.getUUID(), record.getLongOr(TerminalData.ISSUED_GAME_TIME, now) + 2400L)) return false;
			NEXT_ATTEMPT.put(player.getUUID(), now + BASE_INTERVAL_TICKS + level.getRandom().nextInt(INTERVAL_SPREAD_TICKS));
			// Daylight is not a sighting that happened, so it does not cost one.
			//
			// The clock was consumed above before this test, which meant an attempt that landed at
			// noon spent the whole two-to-five-minute interval on a condition that was never going to
			// pass and could not have been influenced. Half the day is daylight, so on average half
			// of every player's chances were being burned this way - which is most of why the figure
			// felt rare. Retrying shortly is cheap and the input really does change: night arrives.
			if (!inDarkness(level, player)) {
				NEXT_ATTEMPT.put(player.getUUID(), now + RETRY_INTERVAL_TICKS);
				return false;
			}
		}
		AABB search = player.getBoundingBox().inflate(64.0);
		if (level.getEntitiesOfClass(WatcherEntity.class, search, watcher -> watcher.observes(player.getUUID())).size() > 0) return false;
		BlockPos position = findPosition(level, player, forced,
				forced ? 12.0 : 18.0, forced ? 20.0 : 32.0, forced);
		if (position == null) {
			// Same reasoning as the darkness test: no placement, no sighting, no charge.
			if (!forced) NEXT_ATTEMPT.put(player.getUUID(), now + RETRY_INTERVAL_TICKS);
			return false;
		}
		WatcherEntity watcher = ModEntities.WATCHER.create(level, EntitySpawnReason.EVENT);
		if (watcher == null) return false;
		// Standing at an angle so the torso reads as facing elsewhere while the head is already
		// turned back. 115 degrees stays inside the neck limit, so the eye still reaches the player.
		orient(watcher, position, player, level.getRandom().nextBoolean() ? 115.0F : -115.0F);
		watcher.observe(player, forced ? 400 : 900);
		return level.addFreshEntity(watcher);
	}

	/** Places the watcher with its head already on the player and its body turned away by an offset. */
	private static void orient(WatcherEntity watcher, BlockPos position, ServerPlayer player,
			float bodyOffsetDegrees) {
		double dx = player.getX() - (position.getX() + 0.5);
		double dz = player.getZ() - (position.getZ() + 0.5);
		float towardPlayer = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
		float bodyYaw = Mth.wrapDegrees(towardPlayer + bodyOffsetDegrees);
		watcher.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, bodyYaw, 0.0F);
		watcher.setYBodyRot(bodyYaw);
		watcher.setYHeadRot(Mth.wrapDegrees(towardPlayer));
	}

	private static BlockPos findPosition(ServerLevel level, ServerPlayer player, boolean forced,
			double minimumDistance, double maximumDistance, boolean frontVisible) {
		for (int attempt = 0; attempt < POSITION_ATTEMPTS; attempt++) {
			double offset;
			if (frontVisible) offset = -48.0 + level.getRandom().nextDouble() * 96.0;
			else {
				double side = 48.0 + level.getRandom().nextDouble() * 42.0;
				offset = level.getRandom().nextBoolean() ? side : -side;
			}
			double angle = Math.toRadians(player.getYRot() + offset);
			double distance = minimumDistance + level.getRandom().nextDouble() * (maximumDistance - minimumDistance);
			int x = Mth.floor(player.getX() - Math.sin(angle) * distance);
			int z = Mth.floor(player.getZ() + Math.cos(angle) * distance);
			if (!level.hasChunkAt(new BlockPos(x, player.blockPosition().getY(), z))) continue;
			for (int y = player.blockPosition().getY() + 7; y >= player.blockPosition().getY() - 12; y--) {
				BlockPos candidate = new BlockPos(x, y, z);
				if (!level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), net.minecraft.core.Direction.UP)
						|| !level.getBlockState(candidate).isAir() || !level.getBlockState(candidate.above()).isAir()
						|| !level.getBlockState(candidate.above(2)).isAir()) continue;
				if (!forced && level.getMaxLocalRawBrightness(candidate) > 5) continue;
				if (!visibleFrom(level, candidate, player)) continue;
				if (!hasBackdrop(level, candidate, player)) continue;
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The eye must actually reach the player. This is the same COLLIDER trace the runtime gaze
	 * check uses, so a spot that passes here is a spot where looking at it can count.
	 */
	private static boolean visibleFrom(ServerLevel level, BlockPos candidate, ServerPlayer player) {
		Vec3 eye = new Vec3(candidate.getX() + 0.5,
				candidate.getY() + ModEntities.WATCHER.getDimensions().eyeHeight(),
				candidate.getZ() + 0.5);
		return level.clip(new ClipContext(player.getEyePosition(), eye, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	/**
	 * It has to be standing against something. Alone in the middle of an open field it reads as an
	 * ordinary mob that happened to spawn; framed by a treeline, a cliff or a cave wall it reads as
	 * something that was already there. Traced horizontally away from the player at chest height,
	 * so the backdrop is behind it from the only viewpoint that matters.
	 */
	private static boolean hasBackdrop(ServerLevel level, BlockPos candidate, ServerPlayer player) {
		Vec3 chest = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.6, candidate.getZ() + 0.5);
		double dx = chest.x - player.getX();
		double dz = chest.z - player.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-4) return false;
		Vec3 behind = chest.add(dx / length * BACKDROP_RANGE, 0.0, dz / length * BACKDROP_RANGE);
		return level.clip(new ClipContext(chest, behind, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.BLOCK;
	}
}
