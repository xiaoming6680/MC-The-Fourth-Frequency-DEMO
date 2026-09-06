package com.xm.thefourthfrequency.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class UnrenderedNetworking {
	private static boolean initialized;

	private UnrenderedNetworking() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PayloadTypeRegistry.playS2C().register(UnrenderedPhasePayload.TYPE, UnrenderedPhasePayload.CODEC);
	}
}
