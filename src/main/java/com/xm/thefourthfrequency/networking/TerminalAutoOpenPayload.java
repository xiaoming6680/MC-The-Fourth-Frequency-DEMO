package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "This player has just been handed their first terminal; bring it up for them."
 *
 * <p>Sent once per player per save, from the join that issues the device. It carries nothing
 * because it decides nothing: the client answers it with the same {@link TerminalOpenPayload} a
 * right-click sends, and the server validates that request exactly as it validates every other one.
 * Opening the screen from the server directly would have skipped that check and, worse, would have
 * put a screen up while the client was still on a loading screen - which is why the decision of
 * <em>when</em> belongs on the client. See {@code TerminalAutoOpenPolicy}.</p>
 *
 * <p>A dropped packet costs the player one convenience on one join and nothing else, so this has no
 * version step and no acknowledgement.</p>
 */
public record TerminalAutoOpenPayload() implements CustomPacketPayload {
	public static final Type<TerminalAutoOpenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "terminal_auto_open"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalAutoOpenPayload> CODEC =
			StreamCodec.unit(new TerminalAutoOpenPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
