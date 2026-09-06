package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.ending.WorldInterfacePolicy;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorldInterfaceProtocolPersistenceTest {
	private static final UUID ENCOUNTER_ID = UUID.fromString("ab8e8138-776c-4a0c-8b48-7bc41292c034");
	private static final long ABOVE_SIGNED_INT_MAX = (long) Integer.MAX_VALUE + 1L;
	private static final List<BlockPos> ANCHOR_POSITIONS = java.util.stream.IntStream
			.range(0, WorldInterfaceProtocol.MAX_ANCHORS)
			.mapToObj(index -> new BlockPos(index * 8, 80, index * 5)).toList();

	@Test
	void everyFinalePayloadRoundTripsAboveTheSignedIntBoundary() {
		assertEquals(3, WorldInterfaceProtocol.VERSION);
		AltarSnapshotS2C altar = new AltarSnapshotS2C(WorldInterfaceProtocol.VERSION, ENCOUNTER_ID,
				ABOVE_SIGNED_INT_MAX, 91L, WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(),
				new BlockPos(0, 65, 0), List.of(ENCOUNTER_ID), List.of("player"), 1, true,
				WorldInterfaceProtocol.AltarStatus.READY.wireId(),
				WorldInterfacePolicy.RITUAL_WINDOW_TICKS, true);
		WorldInterfaceSnapshotS2C snapshot = new WorldInterfaceSnapshotS2C(WorldInterfaceProtocol.VERSION,
				ENCOUNTER_ID, ABOVE_SIGNED_INT_MAX + 1L, WorldInterfaceProtocol.Stage.PHASE_3.wireId(),
				WorldInterfaceProtocol.Form.WORLD_INTERFACE.wireId(), UUID.fromString(
						"a7fb1bc2-3e95-4330-a924-4ff1a3d3b160"), BlockPos.ZERO, 1_200.0F, 300.0F,
				WorldInterfaceProtocol.ANCHOR_MASK, 4_000L, false, 9_000L,
				WorldInterfaceProtocol.GatewayState.PURPLE.wireId(),
				List.of(new BlockPos(1, 70, 1), new BlockPos(-1, 70, -1)),
				ANCHOR_POSITIONS,
				WorldInterfaceProtocol.Outcome.NONE.wireId(), 0.4F);
		BossActionS2C action = new BossActionS2C(ENCOUNTER_ID, ABOVE_SIGNED_INT_MAX + 2L,
				WorldInterfaceProtocol.BossAction.GRAB_THROW.wireId(), 9_100L, 80,
				List.of(ENCOUNTER_ID), 0x51A2L, 3);
		PoemStartS2C poem = new PoemStartS2C(ENCOUNTER_ID, Long.MAX_VALUE,
				WorldInterfaceProtocol.Outcome.SUCCESS.wireId(), "world-identity-for-replay", 7);
		PoemCompleteC2S acknowledgement = new PoemCompleteC2S(ENCOUNTER_ID, Long.MAX_VALUE,
				WorldInterfaceProtocol.PoemCompletion.READ);

		assertEquals(altar, roundTrip(AltarSnapshotS2C.CODEC, altar));
		assertEquals(snapshot, roundTrip(WorldInterfaceSnapshotS2C.CODEC, snapshot));
		assertEquals(action, roundTrip(BossActionS2C.CODEC, action));
		assertEquals(poem, roundTrip(PoemStartS2C.CODEC, poem));
		assertEquals(acknowledgement, roundTrip(PoemCompleteC2S.CODEC, acknowledgement));
	}

	@Test
	void negativeSequencesUnknownWireIdsAndOldVersionsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new BossActionS2C(ENCOUNTER_ID, -1L,
				WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(), 0L, 1, List.of(), 0L, 0));
		assertThrows(IllegalArgumentException.class, () -> new PoemStartS2C(ENCOUNTER_ID, 0L,
				WorldInterfaceProtocol.Outcome.NONE.wireId()));
		assertThrows(IllegalArgumentException.class, () -> new PoemStartS2C(ENCOUNTER_ID, 0L,
				WorldInterfaceProtocol.Outcome.FAILURE.wireId(), "x".repeat(129), 0));
		assertThrows(IllegalArgumentException.class, () -> new PoemStartS2C(ENCOUNTER_ID, 0L,
				WorldInterfaceProtocol.Outcome.FAILURE.wireId(), "world", 11));
		assertThrows(IllegalArgumentException.class, () -> new WorldInterfaceSnapshotS2C(
				WorldInterfaceProtocol.VERSION, ENCOUNTER_ID, 0L,
				WorldInterfaceProtocol.Stage.PHASE_1.wireId(),
				WorldInterfaceProtocol.Form.LISTENING_EMBRYO.wireId(), UUID.randomUUID(), BlockPos.ZERO,
				600.0F, 600.0F, WorldInterfaceProtocol.ANCHOR_MASK, 0L, false, 0L,
				WorldInterfaceProtocol.GatewayState.PURPLE.wireId(), List.of(),
				java.util.Collections.nCopies(WorldInterfaceProtocol.MAX_ANCHORS, BlockPos.ZERO),
				WorldInterfaceProtocol.Outcome.NONE.wireId(), 0.0F));
		assertThrows(IllegalArgumentException.class,
				() -> WorldInterfaceProtocol.BossAction.fromWireId(Integer.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> new AltarSnapshotS2C(
				WorldInterfaceProtocol.VERSION - 1, ENCOUNTER_ID, 0L, 0L,
				WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(), BlockPos.ZERO,
				List.of(), List.of(), 0, false, Integer.MAX_VALUE, 0, false));
		assertEquals(WorldInterfaceProtocol.AltarStatus.SACRIFICE_NOT_READY,
				WorldInterfaceProtocol.AltarStatus.fromReason("sacrifice_not_ready"));
		assertEquals(WorldInterfaceProtocol.AltarStatus.INVALID_MUTATION_ROSTER_CHANGED,
				WorldInterfaceProtocol.AltarStatus.fromReason("invalid_mutation:roster_changed"));
		assertEquals(WorldInterfaceProtocol.AltarStatus.UNKNOWN,
				WorldInterfaceProtocol.AltarStatus.fromReason("server_supplied_arbitrary_text"));
	}

	private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			codec.encode(buffer, value);
			return codec.decode(buffer);
		} finally {
			buffer.release();
		}
	}
}
