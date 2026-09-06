package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Everything the developer test bench and its HUD read, as one message.
 *
 * <p>One payload rather than a lean HUD packet beside a fat panel packet: the two surfaces show the
 * same numbers, and two paths carrying the same number is two paths that can disagree. It is pushed
 * by the server on a fixed cadence while debug is enabled, so the HUD keeps updating with no screen
 * open; the panel's poll survives only as a way to ask for an immediate answer.</p>
 *
 * <p>{@code candidateMask} and {@code unseenMask} are indexed by position in
 * {@code AnomalyCatalog.definitions()} - the order the panel lists rows in - and deliberately not by
 * {@code MASK_ORDER}, which carries retired ids and exists to keep save files readable.</p>
 */
public record DebugStatusPayload(int protocolVersion, boolean allowed, String playerName, int plotStage,
		int bandStage, boolean bound, int discoveredFiles, int unlockedFiles,
		int discoveredFileMask, int unlockedFileMask, int readFileMask, int unreadFiles,
		int decayStage, boolean decayAuto,
		int anomalyTier, int anomalyCeiling, int anomalyHeat,
		String activeAnomaly, int activeSeconds, int nextSeconds, int strongCooldownSeconds,
		int compositeCooldownSeconds, boolean anomaliesSuspended,
		long candidateMask, long unseenMask, int selectionTier, boolean signaturePending,
		boolean pursuitActive, int pursuitForm, int pursuitSlotsUsed,
		boolean inUnrenderedLayer, int unrenderedSessions, int worldInterfaceStage,
		int milestoneMask,
		String message) implements CustomPacketPayload {
	public static final int CURRENT_PROTOCOL_VERSION = 6;
	public static final Type<DebugStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "debug_status"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DebugStatusPayload> CODEC = StreamCodec.of(
			DebugStatusPayload::write, DebugStatusPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, DebugStatusPayload value) {
		buf.writeVarInt(value.protocolVersion); buf.writeBoolean(value.allowed); buf.writeUtf(value.playerName, 64);
		buf.writeVarInt(value.plotStage); buf.writeVarInt(value.bandStage); buf.writeBoolean(value.bound);
		buf.writeVarInt(value.discoveredFiles); buf.writeVarInt(value.unlockedFiles);
		buf.writeVarInt(value.discoveredFileMask); buf.writeVarInt(value.unlockedFileMask);
		buf.writeVarInt(value.readFileMask); buf.writeVarInt(value.unreadFiles);
		buf.writeVarInt(value.decayStage); buf.writeBoolean(value.decayAuto);
		buf.writeVarInt(value.anomalyTier); buf.writeVarInt(value.anomalyCeiling); buf.writeVarInt(value.anomalyHeat);
		buf.writeUtf(value.activeAnomaly, 64); buf.writeVarInt(value.activeSeconds); buf.writeVarInt(value.nextSeconds);
		buf.writeVarInt(value.strongCooldownSeconds); buf.writeVarInt(value.compositeCooldownSeconds);
		buf.writeBoolean(value.anomaliesSuspended);
		buf.writeLong(value.candidateMask); buf.writeLong(value.unseenMask);
		buf.writeVarInt(value.selectionTier); buf.writeBoolean(value.signaturePending);
		buf.writeBoolean(value.pursuitActive); buf.writeVarInt(value.pursuitForm);
		buf.writeVarInt(value.pursuitSlotsUsed);
		buf.writeBoolean(value.inUnrenderedLayer); buf.writeVarInt(value.unrenderedSessions);
		buf.writeVarInt(value.worldInterfaceStage); buf.writeVarInt(value.milestoneMask);
		buf.writeUtf(value.message, 200);
	}

	private static DebugStatusPayload read(RegistryFriendlyByteBuf buf) {
		return new DebugStatusPayload(buf.readVarInt(), buf.readBoolean(), buf.readUtf(64), buf.readVarInt(),
				buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readBoolean(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readBoolean(),
				buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readBoolean(),
				buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
				buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readUtf(200));
	}

	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
