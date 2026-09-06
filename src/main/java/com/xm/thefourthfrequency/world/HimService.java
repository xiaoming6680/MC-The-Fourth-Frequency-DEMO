package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.entity.HimEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Places {@link HimEntity} where a player has not looked yet.
 *
 * <p>The placement rules are the anomaly. The figure itself only stands still and disappears; what
 * decides whether that reads as a sighting or as a spawn is entirely where it was standing when the
 * player turned round.
 */
public final class HimService {
	private static final int POSITION_ATTEMPTS = 40;
	/** Far enough that the figure is a shape rather than a model, close enough to be unmistakable. */
	private static final double MINIMUM_DISTANCE = 22.0;
	private static final double MAXIMUM_DISTANCE = 44.0;
	/**
	 * Degrees off the player's facing that count as out of view.
	 *
	 * <p>Minecraft's default horizontal field of view is around seventy degrees either side once
	 * the aspect ratio is taken into account, so the near bound clears it with room to spare: the
	 * figure must not be on screen when it arrives, or the illusion is a spawn animation.
	 */
	private static final double MINIMUM_VIEW_OFFSET = 95.0;
	private static final double MAXIMUM_VIEW_OFFSET = 180.0;
	/**
	 * Terrain relief required within {@link #RELIEF_SAMPLE_RADIUS} blocks, unless the spot is
	 * enclosed anyway. See {@link #standingOnOpenFlat}.
	 */
	private static final int RELIEF_SAMPLE_RADIUS = 7;
	/**
	 * Height range the surrounding surface must span before a spot counts as broken ground.
	 *
	 * <p>Raised from three. Three blocks of relief over a fourteen-block span is a gentle slope, and
	 * a figure standing on a gentle slope in the open is as legible as one standing on a plain - the
	 * player sees the whole silhouette against the sky and knows exactly what they saw. Five needs
	 * something the eye can actually lose a shape against.
	 */
	private static final int RELIEF_MINIMUM_RANGE = 5;
	/**
	 * How much further out it stands in daylight.
	 *
	 * <p>At night the dark does the work: twenty-two blocks is a shape you are not sure about. In
	 * full daylight the same twenty-two blocks is a clearly rendered humanoid, close enough to read
	 * as a mob that spawned rather than as something that was already there. Pushing it out keeps
	 * the sighting at the edge of what the light will resolve.
	 */
	private static final double DAYLIGHT_DISTANCE_SCALE = 1.6D;
	/** Time between attempts per player, before the random spread below is added. */
	private static final long BASE_INTERVAL_TICKS = 3600L;
	private static final int INTERVAL_SPREAD_TICKS = 5400;
	/**
	 * The wait after an attempt that never got as far as being a sighting.
	 *
	 * <p>Five to twelve minutes is the gap between two <em>chances</em> at seeing the figure. It was
	 * also, wrongly, the gap after a chance that could not be taken: {@code findPosition} fails
	 * whenever there is no spot at the right distance and angle - which is most of a cave system, a
	 * boat, or anywhere hemmed in - and the full interval was spent on it regardless. So the players
	 * least able to satisfy the placement rule were also the ones charged the most for failing it,
	 * and the figure could go an hour without appearing while the clock said otherwise.
	 *
	 * <p>Half a minute, because a failed placement says nothing about whether the next one will work:
	 * the player has moved since, and that is the entire input.
	 */
	private static final long RETRY_INTERVAL_TICKS = 600L;

	private static final Map<UUID, Long> NEXT_ATTEMPT = new HashMap<>();
	private static boolean initialized;

	private HimService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(HimService::tick);
		ServerPlayerEvents.LEAVE.register(player -> NEXT_ATTEMPT.remove(player.getUUID()));
	}

	private static long nextAttemptTick(ServerLevel level, long now) {
		return now + BASE_INTERVAL_TICKS + level.getRandom().nextInt(INTERVAL_SPREAD_TICKS);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) trySpawn(player, false);
	}

	public static boolean debugSpawn(ServerPlayer player) {
		return trySpawn(player, true);
	}

	private static boolean trySpawn(ServerPlayer player, boolean forced) {
		if (!(player.level() instanceof ServerLevel level) || !player.isAlive() || player.isSpectator()) {
			return false;
		}
		// The mirror is the one place this cannot happen, forced or not. A private chase is the mod's
		// statement that the observer has been taken out of shared reality; a second figure that the
		// chase did not put there is the mirror leaking, and the debug entry point is not a reason to
		// let it - an operator testing HIM inside somebody's correction layer would be testing the
		// wrong thing and breaking the isolation to do it.
		if (PrivateDimensions.isPrivate(level)) return false;
		long now = level.getGameTime();
		if (!forced) {
			// Both of these are the standing gates every other ambient system already applies, and
			// this service had neither: a sighting for somebody the story holds no record of, or after
			// the story it belongs to has been answered, is a figure with nothing behind it.
			FrequencyWorldData data = FrequencyWorldData.get(level.getServer());
			if (data.terminalRecord(player.getUUID()).isEmpty()
					|| !FinaleRuntimePolicy.ambientPressureAllowed(data, player)) return false;
			// The first sighting has to be scheduled, not defaulted. Falling back to "now plus one
			// interval" without writing it made every tick recompute a deadline it could never reach,
			// so the figure only ever appeared through the debug entry point.
			Long next = NEXT_ATTEMPT.get(player.getUUID());
			if (next == null) {
				NEXT_ATTEMPT.put(player.getUUID(), nextAttemptTick(level, now));
				return false;
			}
			if (now < next) return false;
			NEXT_ATTEMPT.put(player.getUUID(), nextAttemptTick(level, now));
		}
		// One at a time. Two of them standing in different directions is a mod with a spawn rate.
		AABB search = player.getBoundingBox().inflate(96.0);
		if (!level.getEntitiesOfClass(HimEntity.class, search,
				him -> him.haunts(player.getUUID())).isEmpty()) return false;

		BlockPos position = findPosition(level, player);
		if (position == null) {
			// Not a sighting that happened, so not a sighting that should be paid for. See above.
			if (!forced) NEXT_ATTEMPT.put(player.getUUID(), now + RETRY_INTERVAL_TICKS);
			return false;
		}
		HimEntity him = ModEntities.HIM.create(level, EntitySpawnReason.EVENT);
		if (him == null) return false;
		// Facing the player from the start. There is no time to turn: it has a fifth of a second on
		// screen, and a figure caught in profile reads as a mob going about its business.
		double dx = player.getX() - (position.getX() + 0.5);
		double dz = player.getZ() - (position.getZ() + 0.5);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
		him.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
				Mth.wrapDegrees(yaw), 0.0F);
		him.setYBodyRot(Mth.wrapDegrees(yaw));
		him.setYHeadRot(Mth.wrapDegrees(yaw));
		him.haunt(player, 600);
		return level.addFreshEntity(him);
	}

	private static BlockPos findPosition(ServerLevel level, ServerPlayer player) {
		for (int attempt = 0; attempt < POSITION_ATTEMPTS; attempt++) {
			double spread = MINIMUM_VIEW_OFFSET
					+ level.getRandom().nextDouble() * (MAXIMUM_VIEW_OFFSET - MINIMUM_VIEW_OFFSET);
			double offset = level.getRandom().nextBoolean() ? spread : -spread;
			double angle = Math.toRadians(player.getYRot() + offset);
			double distance = (MINIMUM_DISTANCE
					+ level.getRandom().nextDouble() * (MAXIMUM_DISTANCE - MINIMUM_DISTANCE))
					* (level.isBrightOutside() ? DAYLIGHT_DISTANCE_SCALE : 1.0D);
			int x = Mth.floor(player.getX() - Math.sin(angle) * distance);
			int z = Mth.floor(player.getZ() + Math.cos(angle) * distance);
			if (!level.hasChunkAt(new BlockPos(x, player.blockPosition().getY(), z))) continue;

			for (int y = player.blockPosition().getY() + 8; y >= player.blockPosition().getY() - 10; y--) {
				BlockPos candidate = new BlockPos(x, y, z);
				if (!standable(level, candidate)) continue;
				// Line of sight has to already exist, or turning round reveals a wall and the
				// sighting never happens at all.
				if (!reachableByEye(level, candidate, player)) continue;
				if (standingOnOpenFlat(level, candidate)) continue;
				return candidate;
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos candidate) {
		BlockPos floor = candidate.below();
		return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
				&& level.getBlockState(candidate).isAir()
				&& level.getBlockState(candidate.above()).isAir();
	}

	private static boolean reachableByEye(ServerLevel level, BlockPos candidate, ServerPlayer player) {
		Vec3 head = new Vec3(candidate.getX() + 0.5,
				candidate.getY() + ModEntities.HIM.getDimensions().eyeHeight(),
				candidate.getZ() + 0.5);
		return level.clip(new ClipContext(player.getEyePosition(), head, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	/**
	 * Rejects wide open flats, which is where the illusion dies.
	 *
	 * <p>On broken ground — a treeline, a ridge, a cave mouth, anything with a silhouette of its own
	 * — a figure standing still for a fifth of a second is something the eye can plausibly have
	 * mistaken. On an open plain there is nothing else vertical for forty blocks, so the same figure
	 * is unmistakably a humanoid that was there and then was not, and the player knows exactly what
	 * they saw. Measured as the height range of the surface on a ring around the spot: no relief
	 * and open sky means a flat, and a flat is refused.
	 *
	 * <p>Enclosed spots are exempt regardless of how level the floor is. A flat cave floor or a
	 * building interior has walls doing the same job the terrain would have done outside.
	 */
	private static boolean standingOnOpenFlat(ServerLevel level, BlockPos candidate) {
		if (!level.canSeeSky(candidate)) return false;
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (int index = 0; index < 8; index++) {
			double angle = index * Math.PI / 4.0;
			int x = candidate.getX() + Mth.floor(Math.cos(angle) * RELIEF_SAMPLE_RADIUS);
			int z = candidate.getZ() + Mth.floor(Math.sin(angle) * RELIEF_SAMPLE_RADIUS);
			if (!level.hasChunkAt(new BlockPos(x, candidate.getY(), z))) return true;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			lowest = Math.min(lowest, surface);
			highest = Math.max(highest, surface);
		}
		return highest - lowest < RELIEF_MINIMUM_RANGE;
	}
}
