package com.xm.thefourthfrequency.world;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StructureSearchCacheTest {
	@Test void teammatesSharePositiveAndNegativeResultsButDimensionsNeverDo() {
		var cache = new StructureSearchCache();
		var key = new StructureSearchCache.Key("overworld", "portal", 0, 0);
		var calls = new AtomicInteger();
		for (int i = 0; i < 8; i++) assertNull(cache.find(key, 100 + i, () -> { calls.incrementAndGet(); return null; }));
		assertEquals(1, calls.get());
		assertEquals(BlockPos.ZERO, cache.find(new StructureSearchCache.Key("nether", "portal", 0, 0), 108,
				() -> { calls.incrementAndGet(); return BlockPos.ZERO; }));
		assertEquals(2, calls.get());
		assertEquals(BlockPos.ZERO, cache.find(key, 700, () -> { calls.incrementAndGet(); return BlockPos.ZERO; }));
		assertEquals(BlockPos.ZERO, cache.find(key, 701, () -> fail("A cached result must not locate again")));
		assertEquals(3, calls.get());
		cache.clear();
		assertNull(cache.find(key, 702, () -> null));
	}

	@Test void boundedCacheEvictsOldRegionsAndReversedClocksCannotReuseAnotherSession() {
		var cache = new StructureSearchCache();
		var first = new StructureSearchCache.Key("overworld", "village", 0, 0);
		cache.find(first, 100, () -> BlockPos.ZERO);
		assertNull(cache.find(first, 99, () -> null));
		for (int i = 1; i <= StructureSearchCache.MAX_ENTRIES; i++)
			cache.find(new StructureSearchCache.Key("overworld", "village", i, 0), 100, () -> BlockPos.ZERO);
		assertEquals(new BlockPos(1, 2, 3), cache.find(first, 101, () -> new BlockPos(1, 2, 3)));
	}
}
