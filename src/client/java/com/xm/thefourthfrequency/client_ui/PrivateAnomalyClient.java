package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.PrivateAnomalyPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class PrivateAnomalyClient {
	private static final PrivateAnomalyPresentation PRESENTATION = new PrivateAnomalyPresentation();

	private PrivateAnomalyClient() {
	}

	public static void initialize() {
		ClientPlayNetworking.registerGlobalReceiver(PrivateAnomalyPayload.TYPE, (payload, context) -> {
			PRESENTATION.accept(payload.anomalyId(), payload.variant());
		});
		ClientTickEvents.END_CLIENT_TICK.register(PrivateAnomalyClient::tick);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
				(handler, sender, client) -> clear());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> clear());
	}

	private static void tick(Minecraft client) {
		// Portal generation is not part of the five seconds the player gets to see this event.
		PRESENTATION.advance(client.player != null && client.level != null && client.getOverlay() == null
				&& !(client.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen));
	}

	private static void clear() {
		PRESENTATION.clear();
	}

	public static String anomalyId() {
		return PRESENTATION.id();
	}

	public static int variant() {
		return PRESENTATION.variant();
	}

	public static int remainingTicks() { return PRESENTATION.remaining(); }
}
