package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread allocator for the two globally concurrent pursuit sessions.
 *
 * <p>Every entry point here - {@link #acquire}, {@link #release}, {@link #lease},
 * {@link #activeCount} and the two {@link ServerLifecycleEvents} callbacks - runs on the
 * server tick thread; Fabric API fires {@code SERVER_STARTED}/{@code SERVER_STOPPED} there,
 * same as every other server-side event this codebase relies on elsewhere. {@code ACTIVE} is a
 * plain {@link HashMap} and is never touched off that thread, so no synchronization is needed
 * for it; do not reintroduce it piecemeal on individual methods without also covering the two
 * {@code clear()} calls below, or the two access patterns disagree about the threading model.</p>
 */
public final class PursuitSlotManager {
	/**
	 * One chase at a time, server-wide.
	 *
	 * <p>Lowered from two. The mirror is the mod's statement that the observer has been taken out of
	 * shared reality, and two of them at once quietly contradicts it: the moment two players can
	 * separately be "the only one it is looking at", being taken is a thing that happens on a
	 * schedule rather than a thing that happens to you. One also removes the case where two people
	 * compare notes and discover they were both gone at the same time - which is the cheapest
	 * possible way to learn that the correction is a system rather than an attention.
	 *
	 * <p>It costs throughput, and the cost is real: a queue of eight now drains one at a time. That
	 * is what {@link PursuitDirector}'s waiting order and its refusal notice exist to make bearable -
	 * a wait that is explained and fairly ordered is a different thing from a wait that is silent.
	 *
	 * <p>The mirror dimensions are unchanged. Six of them stay registered ({@link PursuitDimensions})
	 * because recovery has to be able to find a player left in any of them by an older save.
	 */
	public static final int MAX_ACTIVE_PURSUITS = 1;
	/** Mirror slots registered per family, which recovery still has to cover regardless of the cap. */
	private static final int MIRROR_SLOTS_PER_FAMILY = 2;
	private static final Map<UUID, Lease> ACTIVE = new HashMap<>();
	private static boolean initialized;

	private PursuitSlotManager() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerLifecycleEvents.SERVER_STARTED.register(PursuitSlotManager::recoverAfterRestart);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.clear());
	}

	public static Optional<Lease> acquire(MinecraftServer server, UUID playerId,
			PursuitDimensions.Family family) {
		Lease existing = ACTIVE.get(playerId);
		if (existing != null) return Optional.of(existing);
		if (ACTIVE.size() >= MAX_ACTIVE_PURSUITS) return Optional.empty();
		for (int slot = 0; slot < MIRROR_SLOTS_PER_FAMILY; slot++) {
			int candidate = slot;
			boolean occupied = ACTIVE.values().stream()
					.anyMatch(value -> value.family() == family && value.slot() == candidate);
			if (occupied) continue;
			ResourceKey<Level> dimension = PursuitDimensions.mirrorKey(family, slot);
			if (server.getLevel(dimension) == null) {
				TheFourthFrequency.LOGGER.error("Missing pursuit mirror dimension {}", dimension.identifier());
				continue;
			}
			Lease lease = new Lease(playerId, family, slot, dimension);
			ACTIVE.put(playerId, lease);
			return Optional.of(lease);
		}
		return Optional.empty();
	}

	public static void release(UUID playerId) {
		ACTIVE.remove(playerId);
	}

	public static Optional<Lease> lease(UUID playerId) {
		return Optional.ofNullable(ACTIVE.get(playerId));
	}

	public static int activeCount() {
		return ACTIVE.size();
	}

	/** Everyone currently inside a mirror, for the visibility isolation a newcomer has to be told about. */
	public static Set<UUID> activePlayerIds() {
		return Set.copyOf(ACTIVE.keySet());
	}

	private static void recoverAfterRestart(MinecraftServer server) {
		ACTIVE.clear();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (UUID ownerId : data.terminalOwnerIds()) {
			if (data.terminalRecord(ownerId)
					.map(record -> record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false))
					.orElse(false)) {
				data.updateTerminalRecord(ownerId, record -> {
					boolean debugSession = record.getBooleanOr(TerminalData.PURSUIT_SESSION_DEBUG, false);
					record.putBoolean(TerminalData.PURSUIT_ACTIVE, false);
					record.putString(TerminalData.PURSUIT_SESSION_PHASE, "recovery_pending");
					record.putInt(TerminalData.PURSUIT_MIRROR_SLOT, -1);
					if (!debugSession) record.putBoolean(TerminalData.PURSUIT_PENDING, true);
				});
			}
		}
	}

	public record Lease(UUID playerId, PursuitDimensions.Family family, int slot,
			ResourceKey<Level> dimension) {
	}
}
