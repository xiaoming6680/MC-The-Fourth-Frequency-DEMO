package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.ending.WorldInterfacePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete, revisioned projection for the shared resonance-altar screen. */
public record AltarSnapshotS2C(
		int protocolVersion,
		UUID encounterId,
		long sequence,
		long revision,
		int stageId,
		BlockPos altarPos,
		List<UUID> rosterIds,
		List<String> rosterNames,
		int depositedMask,
		boolean localEligible,
		int statusId,
		/** Ticks left before every escrowed terminal is handed back, or 0 when no window is running. */
		int windowRemainingTicks,
		/** Whether this viewer may press summon right now: their own terminal is in, and all are in. */
		boolean localCanSummon
) implements CustomPacketPayload {
	public static final int PROTOCOL_VERSION = WorldInterfaceProtocol.VERSION;
	public static final Type<AltarSnapshotS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "world_interface_altar_snapshot_v1"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AltarSnapshotS2C> CODEC = StreamCodec.of(
			AltarSnapshotS2C::write, AltarSnapshotS2C::read);

	public AltarSnapshotS2C {
		WorldInterfaceProtocol.requireVersion(protocolVersion);
		Objects.requireNonNull(encounterId, "encounterId");
		WorldInterfaceProtocol.requireSequence(sequence);
		if (revision < 0L) throw new IllegalArgumentException("Revision must be non-negative");
		WorldInterfaceProtocol.Stage.fromWireId(stageId);
		Objects.requireNonNull(altarPos, "altarPos");
		rosterIds = WorldInterfaceProtocol.copyBounded(rosterIds,
				WorldInterfaceProtocol.MAX_PARTICIPANTS, "altar roster ids");
		rosterNames = WorldInterfaceProtocol.copyBounded(rosterNames,
				WorldInterfaceProtocol.MAX_PARTICIPANTS, "altar roster names");
		if (rosterIds.size() != rosterNames.size()) {
			throw new IllegalArgumentException("Altar roster ids and names must have equal sizes");
		}
		for (String name : rosterNames) WorldInterfaceProtocol.requireUtf(name, 64, "roster name");
		int allowedMask = rosterIds.isEmpty() ? 0 : (1 << rosterIds.size()) - 1;
		if ((depositedMask & ~allowedMask) != 0) {
			throw new IllegalArgumentException("Deposited mask references a player outside the altar roster");
		}
		WorldInterfaceProtocol.AltarStatus.fromWireId(statusId);
		if (windowRemainingTicks < 0 || windowRemainingTicks > WorldInterfacePolicy.RITUAL_WINDOW_TICKS) {
			throw new IllegalArgumentException("Ritual window remainder outside 0.."
					+ WorldInterfacePolicy.RITUAL_WINDOW_TICKS);
		}
	}

	/** Whether a window is counting down at all, which is what the ring on the screen is drawn from. */
	public boolean windowRunning() {
		return windowRemainingTicks > 0;
	}

	public WorldInterfaceProtocol.Stage stage() {
		return WorldInterfaceProtocol.Stage.fromWireId(stageId);
	}

	public boolean deposited(int rosterIndex) {
		return rosterIndex >= 0 && rosterIndex < rosterIds.size() && (depositedMask & (1 << rosterIndex)) != 0;
	}

	public WorldInterfaceProtocol.AltarStatus status() {
		return WorldInterfaceProtocol.AltarStatus.fromWireId(statusId);
	}

	private static void write(RegistryFriendlyByteBuf buffer, AltarSnapshotS2C value) {
		buffer.writeVarInt(value.protocolVersion);
		buffer.writeUUID(value.encounterId);
		buffer.writeVarLong(value.sequence);
		buffer.writeVarLong(value.revision);
		buffer.writeVarInt(value.stageId);
		buffer.writeBlockPos(value.altarPos);
		buffer.writeVarInt(value.rosterIds.size());
		for (UUID rosterId : value.rosterIds) buffer.writeUUID(rosterId);
		buffer.writeVarInt(value.rosterNames.size());
		for (String rosterName : value.rosterNames) buffer.writeUtf(rosterName, 64);
		buffer.writeVarInt(value.depositedMask);
		buffer.writeBoolean(value.localEligible);
		buffer.writeVarInt(value.statusId);
		buffer.writeVarInt(value.windowRemainingTicks);
		buffer.writeBoolean(value.localCanSummon);
	}

	private static AltarSnapshotS2C read(RegistryFriendlyByteBuf buffer) {
		int protocolVersion = buffer.readVarInt();
		UUID encounterId = buffer.readUUID();
		long sequence = buffer.readVarLong();
		long revision = buffer.readVarLong();
		int stageId = buffer.readVarInt();
		BlockPos altarPos = buffer.readBlockPos();
		int rosterIdCount = WorldInterfaceProtocol.readBoundedSize(buffer,
				WorldInterfaceProtocol.MAX_PARTICIPANTS, "altar roster ids");
		List<UUID> rosterIds = new ArrayList<>(rosterIdCount);
		for (int index = 0; index < rosterIdCount; index++) rosterIds.add(buffer.readUUID());
		int rosterNameCount = WorldInterfaceProtocol.readBoundedSize(buffer,
				WorldInterfaceProtocol.MAX_PARTICIPANTS, "altar roster names");
		if (rosterNameCount != rosterIdCount) {
			throw new IllegalArgumentException("Altar roster ids and names have different wire sizes");
		}
		List<String> rosterNames = new ArrayList<>(rosterNameCount);
		for (int index = 0; index < rosterNameCount; index++) rosterNames.add(buffer.readUtf(64));
		return new AltarSnapshotS2C(protocolVersion, encounterId, sequence, revision, stageId, altarPos,
				rosterIds, rosterNames, buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(),
				buffer.readVarInt(), buffer.readBoolean());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
