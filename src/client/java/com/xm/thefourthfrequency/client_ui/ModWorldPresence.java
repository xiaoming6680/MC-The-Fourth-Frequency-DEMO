package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalOpenPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Whether the world the client is currently in is one this mod is telling a story in.
 *
 * <p>The client half of this mod takes over things that belong to the player rather than to a save:
 * the render distance in {@code options.txt}, the selected resource packs, the window title. That is
 * the correct trade inside its own world - the whole first act is the game being a version it is
 * not - and it is indefensible everywhere else. Without this question the answer was "always": a
 * player who installed the mod had their render distance pinned to six chunks on the title screen,
 * in unrelated single-player saves, and on other people's servers, with the options slider greyed
 * out and no world anywhere to explain it.
 *
 * <p>The signal is the server having declared it can receive {@link TerminalOpenPayload}, which is
 * one of this mod's own C2S channels. Nothing new is added to the protocol: Fabric already exchanges
 * channel registrations during configuration, and a server that answers for that channel is running
 * this mod by definition.
 *
 * <p><b>It cannot be asked arbitrarily early.</b> {@link ClientPlayNetworking#canSend} reads
 * {@code Minecraft#getConnection()}, which returns null until {@code Minecraft#player} exists - and
 * the player is created in {@code handleLogin}. So this answers {@code false} throughout
 * {@code ClientPlayConnectionEvents.INIT} and only becomes meaningful from {@code JOIN} onwards.
 * The integrated-server test is not a shortcut for convenience; it is what covers the window where
 * the channel test cannot answer yet, and it is exact rather than approximate, because an integrated
 * server is this very process with this very mod loaded.
 */
public final class ModWorldPresence {
	private ModWorldPresence() {
	}

	public static boolean currentWorldRunsThisMod() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) return false;
		return client.hasSingleplayerServer() || ClientPlayNetworking.canSend(TerminalOpenPayload.TYPE);
	}
}
