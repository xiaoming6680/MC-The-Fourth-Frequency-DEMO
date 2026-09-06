package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalClosedPayload;
import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.networking.TerminalNoticePayload;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class TerminalClientNetworking {
	private static boolean initialized;
	/**
	 * Navigation and tool snapshots that arrived before the screen existed.
	 *
	 * <p>The server sends all three payloads the moment it accepts the open request, but the screen
	 * is now created at the end of the item's opening animation. Without somewhere to put them, that
	 * first navigation and tool state - the bearing a survey just found, which tools are unlocked -
	 * would be dropped and the terminal would open blank until the next periodic resend.</p>
	 */
	private static TerminalNavigationPayload pendingNavigation;
	private static TerminalToolSnapshotPayload pendingTools;

	private TerminalClientNetworking() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(TerminalSnapshotPayload.TYPE, (payload, context) ->
				context.client().execute(() -> openOrUpdate(payload)));
		ClientPlayNetworking.registerGlobalReceiver(TerminalNavigationPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					// The HUD readout takes every one of these, open screen or not: the server keeps
					// streaming while the terminal is shut, and that stream is the only thing telling
					// the readout it is still alive.
					TerminalNavigationReadout.accept(payload);
					if (context.client().screen instanceof TerminalScreen terminal) terminal.updateNavigation(payload);
					else pendingNavigation = payload;
				}));
		ClientPlayNetworking.registerGlobalReceiver(TerminalToolSnapshotPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().screen instanceof TerminalScreen terminal) terminal.updateTools(payload);
					else pendingTools = payload;
				}));
		ClientPlayNetworking.registerGlobalReceiver(TerminalNoticePayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					TerminalNoticeHud.enqueue(payload.message(), payload.tone());
				}));
		ClientPlayNetworking.registerGlobalReceiver(TerminalClosedPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().screen instanceof TerminalScreen terminal) terminal.closeFromServer();
					// Also cancels an opening that has not produced a screen yet, so a refusal cannot
					// leave the item stuck half open in the hand.
					TerminalHandheldAnimator.abort();
				}));
		ClientTickEvents.END_CLIENT_TICK.register(client -> TerminalHandheldAnimator.clientTick());
	}

	/**
	 * A snapshot either refreshes the open screen or starts the terminal coming up.
	 *
	 * <p>The screen no longer appears the instant the packet lands: the item plays its opening first
	 * and hands the snapshot over when the lid is up. That is presentation only - the server has
	 * considered the terminal open since it sent this.</p>
	 */
	private static void openOrUpdate(TerminalSnapshotPayload payload) {
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof TerminalScreen terminal) terminal.update(payload);
		else TerminalHandheldAnimator.requestOpen(payload);
	}

	/**
	 * Delivers whatever arrived while the terminal was still coming up.
	 *
	 * <p>Called once by the animator immediately after it creates the screen, so the first frame the
	 * player sees already carries the state the server sent with the open.</p>
	 */
	static void deliverPending(TerminalScreen terminal) {
		if (pendingNavigation != null) {
			terminal.updateNavigation(pendingNavigation);
			pendingNavigation = null;
		}
		if (pendingTools != null) {
			terminal.updateTools(pendingTools);
			pendingTools = null;
		}
	}

	/** Drops held payloads when an opening is abandoned, so a later one cannot show stale state. */
	static void discardPending() {
		pendingNavigation = null;
		pendingTools = null;
	}
}
