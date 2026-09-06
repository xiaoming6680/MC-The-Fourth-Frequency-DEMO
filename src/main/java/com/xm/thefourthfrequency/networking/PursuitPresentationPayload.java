package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PursuitPresentationPayload(String sessionId, int phase, int form)
		implements CustomPacketPayload {
	public static final int CLEAR = 0;
	public static final int WARNING = 1;
	public static final int BLACKOUT = 2;
	public static final int RUNNING = 3;
	public static final int CAPTURE_FREEZE = 4;
	public static final int ESCAPE_RESOLUTION = 5;
	public static final Type<PursuitPresentationPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "pursuit_presentation"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PursuitPresentationPayload> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.sessionId);
				buf.writeVarInt(value.phase);
				buf.writeVarInt(value.form);
			},
			buf -> new PursuitPresentationPayload(buf.readUtf(), buf.readVarInt(), buf.readVarInt()));

	public PursuitPresentationPayload {
		sessionId = sessionId == null ? "" : sessionId;
		phase = Math.clamp(phase, CLEAR, ESCAPE_RESOLUTION);
		form = Math.clamp(form, 0, com.xm.thefourthfrequency.correction.ReworkFormStage.MAX_STAGE);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
