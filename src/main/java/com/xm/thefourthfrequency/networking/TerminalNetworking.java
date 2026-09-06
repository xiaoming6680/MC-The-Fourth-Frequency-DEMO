package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class TerminalNetworking {
	private static boolean initialized;

	private TerminalNetworking() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PayloadTypeRegistry.playC2S().register(TerminalOpenPayload.TYPE, TerminalOpenPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TerminalControlPayload.TYPE, TerminalControlPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalSnapshotPayload.TYPE, TerminalSnapshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalToolSnapshotPayload.TYPE, TerminalToolSnapshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalNavigationPayload.TYPE, TerminalNavigationPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalClosedPayload.TYPE, TerminalClosedPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalAutoOpenPayload.TYPE, TerminalAutoOpenPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TerminalNoticePayload.TYPE, TerminalNoticePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(AnomalyStartS2C.TYPE, AnomalyStartS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(AnomalyPhaseS2C.TYPE, AnomalyPhaseS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(MenuErosionStageS2C.TYPE, MenuErosionStageS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(AnomalyCompleteC2S.TYPE, AnomalyCompleteC2S.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TerminalOpenPayload.TYPE,
				(payload, context) -> TerminalRuntimeService.open(context.player(), payload.hand()));
		ServerPlayNetworking.registerGlobalReceiver(TerminalControlPayload.TYPE,
				(payload, context) -> TerminalRuntimeService.control(context.player(), payload.action(), payload.value()));
		ServerPlayNetworking.registerGlobalReceiver(AnomalyCompleteC2S.TYPE,
				(payload, context) -> AnomalyRuntimeService.complete(context.player(), payload));
	}
}
