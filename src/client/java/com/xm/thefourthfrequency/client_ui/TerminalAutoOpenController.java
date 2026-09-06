package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.networking.TerminalAutoOpenPayload;
import com.xm.thefourthfrequency.networking.TerminalOpenPayload;
import com.xm.thefourthfrequency.terminal.TerminalAutoOpenPolicy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Brings the terminal up by itself on the join that hands it out.
 *
 * <p>The server sends {@link TerminalAutoOpenPayload} from that join and nothing else; everything
 * about the timing is decided here, because the server cannot see the one thing that decides it -
 * whether the player is looking at the world yet or at a loading screen. The rules are
 * {@link TerminalAutoOpenPolicy}; this class is the wiring that feeds them real state.</p>
 *
 * <p>What it sends is {@link TerminalOpenPayload}, the same request a right-click sends, so the
 * open goes through the server's own validation and through the item's opening performance. There
 * is no path here that puts a screen up on its own.</p>
 */
public final class TerminalAutoOpenController {
	private static boolean initialized;
	/** Whether an offer is outstanding. Cleared by the first decision that is not {@code WAIT}. */
	private static boolean armed;
	/** Ticks the world has actually been in front of the player since the offer arrived. */
	private static int shownTicks;

	private TerminalAutoOpenController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(TerminalAutoOpenPayload.TYPE, (payload, context) ->
				context.client().execute(TerminalAutoOpenController::arm));
		ClientTickEvents.END_CLIENT_TICK.register(TerminalAutoOpenController::clientTick);
		// An offer belongs to one connection. Leaving the world with it still outstanding must not
		// leave it waiting for the next world to be shown.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disarm());
	}

	private static void arm() {
		armed = true;
		shownTicks = 0;
	}

	static void disarm() {
		armed = false;
		shownTicks = 0;
	}

	/** Whether an offer is outstanding. For the client GameTest. */
	public static boolean armed() {
		return armed;
	}

	static void clientTick(Minecraft client) {
		if (!armed) return;
		boolean worldShown = worldShown(client);
		TerminalAutoOpenPolicy.Decision decision = TerminalAutoOpenPolicy.decide(worldShown,
				holdingOwnTerminal(client), terminalEngaged(client), shownTicks);
		switch (decision) {
			case WAIT -> {
				// Only ticks the player spent being shown the world count, so a long first load
				// spends none of the budget and neither does time in a menu.
				if (worldShown && shownTicks < TerminalAutoOpenPolicy.GIVE_UP_TICKS) shownTicks++;
			}
			case GIVE_UP -> disarm();
			case OPEN -> {
				disarm();
				if (ClientPlayNetworking.canSend(TerminalOpenPayload.TYPE)) {
					ClientPlayNetworking.send(new TerminalOpenPayload(0));
				}
			}
		}
	}

	/**
	 * The world is in front of the player, with nothing over it.
	 *
	 * <p>The screen and overlay checks are what keep this from firing under a loading screen, the
	 * resource reload, the pause menu or the inventory. The safety notice is included for the same
	 * reason the menu music waits on it: until it is dismissed the player is still reading a page
	 * this mod put in front of them, and opening a second one over it would be the mod arguing with
	 * itself.</p>
	 */
	private static boolean worldShown(Minecraft client) {
		return client.player != null && client.level != null && client.screen == null
				&& client.getOverlay() == null && FirstRunNoticeController.released();
	}

	/** The same test the right-click path uses, so both routes open exactly the same device. */
	private static boolean holdingOwnTerminal(Minecraft client) {
		if (client.player == null) return false;
		ItemStack stack = client.player.getMainHandItem();
		return stack.is(ModItems.OLD_TERMINAL)
				&& TerminalData.belongsTo(stack, client.player.getUUID());
	}

	private static boolean terminalEngaged(Minecraft client) {
		return client.screen instanceof TerminalScreen
				|| TerminalHandheldAnimator.state() != TerminalHandheldAnimator.State.IDLE;
	}
}
