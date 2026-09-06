package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.WorldDecayPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class WorldDecayClient {
	private static volatile int serverStage;
	private static int transientStage;
	private static int transientTicks;
	private WorldDecayClient() { }

	public static void initialize() {
		ClientPlayNetworking.registerGlobalReceiver(WorldDecayPayload.TYPE, (payload, context) ->
				context.client().execute(() -> serverStage = Math.clamp(payload.stage(), 0, 5)));
		ClientTickEvents.END_CLIENT_TICK.register(WorldDecayClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	/**
	 * Decay stage as it applies where the player currently is - which is zero inside the unrendered
	 * layer.
	 *
	 * <p>Decay is a statement about <em>the player's own world</em> coming apart, sent per player and
	 * painted onto the blocks around them. The layer is not their world; it is the thing behind it,
	 * and it was never solved correctly to begin with. Letting stage-five corruption eat its walls
	 * would say the layer is rotting along with everything else - which files it inside the same
	 * story rather than outside it, and costs the one property the place actually runs on, that every
	 * room down there looks exactly like every other room.
	 *
	 * <p>Safe to gate here rather than at each consumer because {@code TextureDecayMixin} hooks
	 * {@code TextureManager.getTexture}, which resolves per draw rather than at resource load: the
	 * walls stop being corrupted on the tick the player arrives and start again on the tick they
	 * leave, with no reload.
	 */
	public static int stage() {
		if (UnrenderedLayerClient.inLayer()) return 0;
		return Math.max(serverStage, transientTicks > 0 ? transientStage : 0);
	}
	public static void pulse(int requestedStage, int durationTicks) {
		transientStage = Math.clamp(requestedStage, 1, 5);
		transientTicks = Math.max(transientTicks, Math.max(1, durationTicks));
	}

	public static boolean corruptTexture(net.minecraft.resources.Identifier id) {
		return WorldDecayTexturePolicy.shouldCorrupt(stage(), id.getNamespace(), id.getPath());
	}

	private static void tick(Minecraft client) {
		if (transientTicks > 0) transientTicks--;
	}

	private static void reset() {
		serverStage = 0;
		transientStage = 0;
		transientTicks = 0;
	}
}
