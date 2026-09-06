package com.xm.thefourthfrequency.ending;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Stands a column of light over every stack the interface has taken out of a hand.
 *
 * <p>The hotbar purge throws nine stacks outward on nine bearings, in the middle of a fight, on an
 * island whose floor is end stone under a violet sky. A dropped item is a twenty-centimetre sprite
 * that bobs; against that ground, at the distance the purge's own knockback leaves the player at,
 * it is invisible. The stacks were recoverable the whole time and players were losing them anyway,
 * which is a readability failure rather than a difficulty one - the fear the purge is written for is
 * losing your grip on the fight, not hunting a pixel in the dark.
 *
 * <p>So the drops are marked instead of being made easier: nothing about the purge, the pickup delay
 * or the despawn timer changes, and what is added is only the ability to see where a stack went from
 * across the arena. The column is emitted through {@link ArenaParticles} for exactly that reason -
 * the whole point is that it is legible from further away than vanilla's own 32-block particle
 * limiter would carry it.
 *
 * <p>Marks are dropped the moment the item entity is gone - picked up, burned, despawned - and the
 * whole registry goes with the server. Nothing here is authoritative and nothing is persisted: a
 * restart loses the columns and keeps the items, which is the right way round.
 */
public final class WorldInterfaceDropBeaconService {
	/** How tall the column stands above the stack, in blocks. */
	private static final double COLUMN_HEIGHT = 3.6D;
	/** Blocks between two motes of the column. */
	private static final double COLUMN_STEP = 0.4D;
	/**
	 * Ticks between two emissions.
	 *
	 * <p>Every other tick rather than every tick. The motes vanilla draws for {@code END_ROD} live
	 * about three seconds, so the column is continuous either way and this halves what eight clients
	 * are sent while a table is scattered across the island looking for nine stacks each.
	 */
	private static final int EMIT_INTERVAL_TICKS = 2;
	/**
	 * Hard ceiling on how long one mark is kept, in ticks.
	 *
	 * <p>Five minutes and a bit: vanilla despawns a dropped item at 6000 ticks, so anything still
	 * marked past this is an entity lookup that will never succeed again. The per-tick sweep already
	 * drops marks whose entity is gone; this only bounds the case where the End is unloaded and the
	 * lookup cannot answer either way.
	 */
	private static final int MAX_AGE_TICKS = 6_100;
	/**
	 * Ceiling on simultaneous marks.
	 *
	 * <p>Eight players times nine slots is seventy-two per sweep, and the purge can come round again
	 * before the first set has been picked up. Two hundred and fifty-six is several sweeps' worth and
	 * still a bounded map; past it the oldest mark is dropped rather than the newest refused, so what
	 * a player loses sight of is the drop they have had the longest to collect.
	 */
	private static final int MAX_MARKERS = 256;

	/** Violet, the arena's own. The column has to read as the interface's doing, not as a waypoint. */
	private static final float MARK_RED = 0.78F;
	private static final float MARK_GREEN = 0.47F;
	private static final float MARK_BLUE = 1.0F;

	private static final Map<MinecraftServer, Map<UUID, Long>> MARKED =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static boolean initialized;

	private WorldInterfaceDropBeaconService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(WorldInterfaceDropBeaconService::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(MARKED::remove);
	}

	/**
	 * Starts a column over one dropped stack.
	 *
	 * <p>Takes the entity rather than a position because the stack is still moving: it was thrown
	 * outward and has a bounce and a slide left in it, and a column drawn where it was launched from
	 * would point at the wrong place by the time anybody looked.
	 */
	public static void mark(ItemEntity item) {
		if (item == null || item.level().isClientSide()) return;
		MinecraftServer server = item.level().getServer();
		if (server == null) return;
		Map<UUID, Long> marks = MARKED.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
		synchronized (marks) {
			// LinkedHashMap in insertion order, so the eldest entry is the head.
			while (marks.size() >= MAX_MARKERS) {
				Iterator<UUID> oldest = marks.keySet().iterator();
				if (!oldest.hasNext()) break;
				oldest.next();
				oldest.remove();
			}
			marks.put(item.getUUID(), Math.max(0L, server.getTickCount()));
		}
	}

	/** Drops every mark for one server. For encounter teardown and tests. */
	public static void clear(MinecraftServer server) {
		if (server != null) MARKED.remove(server);
	}

	/** How many stacks are currently marked. Test and debug surface only. */
	public static int markedCount(MinecraftServer server) {
		Map<UUID, Long> marks = MARKED.get(server);
		if (marks == null) return 0;
		synchronized (marks) {
			return marks.size();
		}
	}

	private static void tick(MinecraftServer server) {
		Map<UUID, Long> marks = MARKED.get(server);
		if (marks == null) return;
		boolean empty;
		synchronized (marks) {
			empty = marks.isEmpty();
		}
		if (empty) return;
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) return;
		boolean emit = server.getTickCount() % EMIT_INTERVAL_TICKS == 0;
		long now = server.getTickCount();
		synchronized (marks) {
			Iterator<Map.Entry<UUID, Long>> entries = marks.entrySet().iterator();
			while (entries.hasNext()) {
				Map.Entry<UUID, Long> entry = entries.next();
				if (now - entry.getValue() > MAX_AGE_TICKS) {
					entries.remove();
					continue;
				}
				Entity entity = end.getEntity(entry.getKey());
				if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.isRemoved()) {
					entries.remove();
					continue;
				}
				if (emit) emitColumn(end, item);
			}
		}
	}

	/**
	 * One column: a shaft of end-rod motes with a violet cap and a violet ring at its foot.
	 *
	 * <p>Three parts because they answer three different distances. The shaft is what is seen from
	 * across the island, the cap is what separates one column from another when several stand near
	 * each other, and the ring is what tells the player standing on top of it which block to walk
	 * onto - a shaft alone is ambiguous about its own base once you are underneath it.
	 */
	private static void emitColumn(ServerLevel level, ItemEntity item) {
		double x = item.getX();
		double y = item.getY() + 0.2D;
		double z = item.getZ();
		for (double offset = 0.0D; offset <= COLUMN_HEIGHT; offset += COLUMN_STEP) {
			ArenaParticles.emit(level, ParticleTypes.END_ROD, x, y + offset, z,
					1, 0.03D, 0.0D, 0.03D, 0.0D);
		}
		ArenaParticles.emit(level, ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
						MARK_RED, MARK_GREEN, MARK_BLUE),
				x, y + COLUMN_HEIGHT, z, 2, 0.12D, 0.12D, 0.12D, 0.0D);
		ArenaParticles.emit(level, ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
						MARK_RED, MARK_GREEN, MARK_BLUE),
				x, y, z, 4, 0.28D, 0.02D, 0.28D, 0.0D);
	}
}
