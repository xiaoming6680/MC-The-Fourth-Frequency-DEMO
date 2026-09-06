package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.networking.StormParticleBatchS2C;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StormParticleBatcherTest {
	private static StormParticleBatchS2C.Sample sample(int count) {
		return new StormParticleBatchS2C.Sample(0, 1.25F, 2, 3, count, 0, 0.2F, -0.3F, 0.5F);
	}

	@Test void batchedCodecPreservesDirectionAndSavesWireBytes() {
		var packet = new StormParticleBatchS2C(Identifier.parse("minecraft:the_end"),
				new BlockPos(29_999_936, 64, -30_000_000), Collections.nCopies(256, sample(0)));
		var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			StormParticleBatchS2C.CODEC.encode(buf, packet);
			// Vanilla: three doubles + seven scalar fields, flags/type and a frame for each point.
			assertTrue(buf.readableBytes() < 256 * 40, "Batch should be smaller even before per-packet framing");
			assertEquals(packet, StormParticleBatchS2C.CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@Test void allocationAndNonFiniteValuesAreRejectedBeforeRendering() {
		var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			buf.writeIdentifier(Identifier.parse("minecraft:the_end"));
			buf.writeBlockPos(BlockPos.ZERO);
			buf.writeVarInt(Integer.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> StormParticleBatchS2C.CODEC.decode(buf));
		} finally { buf.release(); }
		assertThrows(IllegalArgumentException.class, () -> new StormParticleBatchS2C.Sample(0, Float.NaN,
				0, 0, 0, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new StormParticleBatchS2C.Sample(3, 0,
				0, 0, 0, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new StormParticleBatchS2C(
				Identifier.parse("minecraft:the_end"), BlockPos.ZERO, List.of()));
	}

	@Test void budgetCountsActualParticlesAndIsIndependentBetweenWorldQueues() {
		var end = new StormParticleBatcher.Queue();
		var overworld = new StormParticleBatcher.Queue();
		end.addBurst(BlockPos.ZERO, sample(0), Integer.MAX_VALUE);
		assertFalse(end.add(BlockPos.ZERO, sample(0)));
		assertEquals(4096, end.particles);
		assertEquals(64, end.size);
		assertTrue(end.cells.get(BlockPos.ZERO).stream().allMatch(value -> value.count() == 64));
		assertTrue(overworld.add(BlockPos.ZERO, sample(0)));
		assertEquals(1, overworld.particles);
	}

	@Test void cellOffsetsRetainSubBlockPrecisionAtBothWorldBorders() {
		for (double x : new double[]{29_999_999.125, -29_999_999.875, -0.125, 0.125}) {
			BlockPos cell = StormParticleBatcher.cell(x, 80, x);
			float offset = (float) (x - cell.getX());
			assertTrue(offset >= 0 && offset < 64);
			assertEquals(x, cell.getX() + (double) offset, 0.00001);
		}
	}
}
