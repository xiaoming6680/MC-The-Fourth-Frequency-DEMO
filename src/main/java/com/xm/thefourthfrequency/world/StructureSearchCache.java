package com.xm.thefourthfrequency.world;

import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Server-thread cache shared by nearby teammates, including unsuccessful searches. */
final class StructureSearchCache {
	static final int MAX_ENTRIES = 128;
	static final long TTL_TICKS = 600;
	record Key(String dimension, String target, int chunkX, int chunkZ) {}
	private record Entry(long tick, BlockPos result) {}
	private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

	BlockPos find(Key key, long tick, Supplier<BlockPos> search) {
		Entry cached = entries.get(key);
		if (cached != null && tick >= cached.tick && tick - cached.tick < TTL_TICKS) return cached.result;
		BlockPos found = search.get();
		entries.put(key, new Entry(tick, found == null ? null : found.immutable()));
		if (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
		return found;
	}

	void clear() { entries.clear(); }
}
