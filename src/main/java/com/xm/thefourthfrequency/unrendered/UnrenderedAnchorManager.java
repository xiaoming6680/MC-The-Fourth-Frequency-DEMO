package com.xm.thefourthfrequency.unrendered;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hands out the {@link UnrenderedAnchorPolicy#MAX_CONCURRENT} entry slots, one per player in the
 * layer at a time.
 *
 * <p>In memory only, and deliberately so. The slot exists to keep two live sessions apart, and there
 * are no live sessions across a restart - what does have to survive one is the return address, which
 * is on the player's record where it belongs. Persisting the lease as well would create a second
 * copy of the same fact that could disagree with the first, and a leaked lease would then be
 * permanent rather than lasting until the next restart.
 */
public final class UnrenderedAnchorManager {
	private static final Map<UUID, Lease> LEASES = new HashMap<>();

	private UnrenderedAnchorManager() {
	}

	/**
	 * Takes the lowest free slot, or empty when the layer is full.
	 *
	 * <p>Lowest rather than random: a server that never has more than two people in the layer at
	 * once should keep reusing the same two slots, so the chunks it generates stay in the regions it
	 * has already generated.
	 */
	public static synchronized Optional<Lease> acquire(UUID playerId, int visit) {
		if (playerId == null) return Optional.empty();
		Lease existing = LEASES.get(playerId);
		if (existing != null) return Optional.of(existing);
		for (int slot = 0; slot < UnrenderedAnchorPolicy.MAX_CONCURRENT; slot++) {
			if (occupied(slot)) continue;
			int[] entry = UnrenderedAnchorPolicy.entry(slot, visit);
			Lease lease = new Lease(playerId, slot,
					new BlockPos(entry[0], UnrenderedAnchorPolicy.entryY(), entry[1]));
			LEASES.put(playerId, lease);
			return Optional.of(lease);
		}
		return Optional.empty();
	}

	public static synchronized void release(UUID playerId) {
		if (playerId != null) LEASES.remove(playerId);
	}

	public static synchronized Optional<Lease> lease(UUID playerId) {
		return playerId == null ? Optional.empty() : Optional.ofNullable(LEASES.get(playerId));
	}

	public static synchronized int activeCount() {
		return LEASES.size();
	}

	public static synchronized boolean hasCapacity() {
		return LEASES.size() < UnrenderedAnchorPolicy.MAX_CONCURRENT;
	}

	/** Server shutdown only. Sessions are closed by their own service before this runs. */
	public static synchronized void clear() {
		LEASES.clear();
	}

	private static boolean occupied(int slot) {
		return LEASES.values().stream().anyMatch(lease -> lease.slot() == slot);
	}

	public record Lease(UUID playerId, int slot, BlockPos entry) {
		public Lease {
			if (playerId == null) throw new IllegalArgumentException("playerId");
			if (slot < 0 || slot >= UnrenderedAnchorPolicy.MAX_CONCURRENT) {
				throw new IllegalArgumentException("slot out of range: " + slot);
			}
			if (entry == null) throw new IllegalArgumentException("entry");
		}
	}
}
