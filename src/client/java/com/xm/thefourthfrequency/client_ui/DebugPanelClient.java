package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.DebugOpenPayload;
import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class DebugPanelClient {
	private static KeyMapping openKey;
	private static KeyMapping hudKey;
	private static String pendingAnomalyId;
	private static boolean pendingPursuitResponse;
	private static boolean pendingBossResponse;
	private static boolean initialized;
	private DebugPanelClient() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.thefourthfrequency.debug_panel", GLFW.GLFW_KEY_M, KeyMapping.Category.MISC));
		// A key of its own rather than a panel toggle: hiding the readout for a screenshot is
		// something done mid-play, and having to open a screen to do it defeats the point.
		hudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.thefourthfrequency.debug_hud", GLFW.GLFW_KEY_N, KeyMapping.Category.MISC));
		DebugHud.initialize();
		ClientPlayNetworking.registerGlobalReceiver(DebugStatusPayload.TYPE, (payload, context) ->
				context.client().execute(() -> accept(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (hudKey.consumeClick()) DebugHudState.toggleVisible();
			while (openKey.consumeClick()) {
				if (client.player != null && ClientPlayNetworking.canSend(DebugOpenPayload.TYPE))
					ClientPlayNetworking.send(new DebugOpenPayload());
			}
		});
	}

	private static void accept(DebugStatusPayload payload) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (payload.protocolVersion() != DebugStatusPayload.CURRENT_PROTOCOL_VERSION) {
			if (client.player != null) client.player.displayClientMessage(Component.literal("调试协议版本不匹配"), false);
			return;
		}
		DebugHud.accept(payload);
		if (!payload.allowed()) {
			boolean anomalyResponse = pendingAnomalyId != null;
			boolean pursuitResponse = pendingPursuitResponse;
			boolean bossResponse = pendingBossResponse;
			pendingAnomalyId = null;
			pendingPursuitResponse = false;
			pendingBossResponse = false;
			if (client.player != null) client.player.displayClientMessage(Component.literal(
					(anomalyResponse ? "[异象触发失败] " : pursuitResponse ? "[追逐测试失败] "
							: bossResponse ? "[BOSS 战测试失败] " : "")
							+ payload.message()), false);
			if (client.screen instanceof DebugPanelScreen) client.setScreen(null);
			return;
		}
		boolean pursuitResponse = pendingPursuitResponse && !payload.message().isEmpty();
		if (pursuitResponse) pendingPursuitResponse = false;
		if (pursuitResponse && payload.message().startsWith("追逐测试已启动")) {
			if (client.screen instanceof DebugPanelScreen) client.setScreen(null);
			return;
		}
		// The boss test moves the player into the End arena, so the panel has to get out of the way
		// whichever way the sacrifice landed - started, or waiting on the rest of the roster.
		boolean bossResponse = pendingBossResponse && !payload.message().isEmpty();
		if (bossResponse) {
			pendingBossResponse = false;
			if (client.player != null) {
				client.player.displayClientMessage(Component.literal("[BOSS 战测试] " + payload.message()), false);
			}
			if (client.screen instanceof DebugPanelScreen) client.setScreen(null);
			return;
		}
		String requestedAnomaly = pendingAnomalyId;
		boolean anomalyResponse = requestedAnomaly != null && !payload.message().isEmpty();
		boolean anomalyStarted = anomalyResponse
				&& requestedAnomaly.equals(payload.activeAnomaly())
				&& payload.message().startsWith("已触发异象：");
		if (anomalyResponse) pendingAnomalyId = null;
		if (anomalyResponse && client.player != null) client.player.displayClientMessage(Component.literal(
				(anomalyStarted ? "[异象触发成功] " : "[异象触发失败] ") + payload.message()), false);
		if (anomalyStarted) {
			if (client.screen instanceof DebugPanelScreen) client.setScreen(null);
			return;
		}
		if (client.screen instanceof DebugPanelScreen screen) screen.update(payload);
		else if (!payload.message().isEmpty()) client.setScreen(new DebugPanelScreen(payload));
	}

	static void expectAnomalyResponse(String anomalyId) {
		pendingAnomalyId = anomalyId;
	}

	static void expectPursuitResponse() {
		pendingPursuitResponse = true;
	}

	static void expectBossResponse() {
		pendingBossResponse = true;
	}
}
