package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.terminal.TerminalRecordPolicy;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record TerminalLogEntryPayload(
		int sequence,
		int band,
		String type,
		long gameTime,
		long dayTime,
		String dimension,
		long position,
		int variant,
		int severity,
		boolean unread,
		/**
		 * Which store this line came out of, as {@code TerminalRecordPolicy.Source#wireId}.
		 *
		 * <p>Appended last because {@link #read} is positional. The page needs it for two decisions
		 * it cannot make from the type alone: whether the navigation shortcut may target the line,
		 * and whether it arrives through the glyph settle or is simply drawn.</p>
		 */
		int source
) {
	static void write(RegistryFriendlyByteBuf buf, TerminalLogEntryPayload value) {
		buf.writeVarInt(value.sequence);
		buf.writeVarInt(value.band);
		buf.writeUtf(value.type, 64);
		buf.writeVarLong(value.gameTime);
		buf.writeVarLong(value.dayTime);
		buf.writeUtf(value.dimension, 128);
		buf.writeLong(value.position);
		buf.writeVarInt(value.variant);
		buf.writeVarInt(value.severity);
		buf.writeBoolean(value.unread);
		buf.writeVarInt(value.source);
	}

	static TerminalLogEntryPayload read(RegistryFriendlyByteBuf buf) {
		return new TerminalLogEntryPayload(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readVarLong(),
				buf.readVarLong(), buf.readUtf(128), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
				buf.readBoolean(), buf.readVarInt());
	}

	/** A story line: the default for every existing construction site. */
	public static TerminalLogEntryPayload story(int sequence, int band, String type, long gameTime,
			long dayTime, String dimension, long position, int variant, int severity, boolean unread) {
		return new TerminalLogEntryPayload(sequence, band, type, gameTime, dayTime, dimension, position,
				variant, severity, unread, TerminalRecordPolicy.Source.STORY.wireId());
	}
}
