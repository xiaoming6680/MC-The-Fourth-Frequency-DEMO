package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.world.DebugPanelService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class DebugNetworking {
	private static boolean initialized;
	private DebugNetworking() { }
	public static void initialize() {
		if (initialized) return;
		initialized = true;
		DebugPanelService.initialize();
		PayloadTypeRegistry.playC2S().register(DebugOpenPayload.TYPE, DebugOpenPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DebugActionPayload.TYPE, DebugActionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DebugStatusPayload.TYPE, DebugStatusPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DebugOpenPayload.TYPE,
				(payload, context) -> DebugPanelService.open(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DebugActionPayload.TYPE,
				(payload, context) -> DebugPanelService.handle(context.player(), payload));
	}
}
