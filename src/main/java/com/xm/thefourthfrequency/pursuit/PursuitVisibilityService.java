package com.xm.thefourthfrequency.pursuit;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Removes tab-list presence in both directions while dimension separation handles entity visibility. */
public final class PursuitVisibilityService {
	private PursuitVisibilityService() {
	}

	public static void isolate(ServerPlayer target) {
		for (ServerPlayer other : target.level().getServer().getPlayerList().getPlayers()) {
			if (other == target) continue;
			hide(target, other);
		}
	}

	/**
	 * Re-applies the isolation for one arrival, because {@link #isolate} could not have known about
	 * them.
	 *
	 * <p>{@code isolate} sends its removals once, to whoever is online at that moment. Anyone
	 * connecting afterwards is handed the full player list by vanilla's own join sequence - so a
	 * chase that started five minutes ago leaked in both directions the instant somebody logged in:
	 * the newcomer saw a name that is supposed to be gone, and the person in the mirror watched
	 * somebody appear in a world they had been removed from.
	 *
	 * <p>Called from the join path, after vanilla has finished populating the list; sending before
	 * that would be a removal for an entry that has not arrived yet.
	 */
	public static void isolateFromArrival(ServerPlayer arrival) {
		for (UUID hiddenId : PursuitSlotManager.activePlayerIds()) {
			ServerPlayer hidden = arrival.level().getServer().getPlayerList().getPlayer(hiddenId);
			if (hidden == null || hidden == arrival) continue;
			hide(hidden, arrival);
		}
	}

	private static void hide(ServerPlayer target, ServerPlayer other) {
		other.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		target.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(other.getUUID())));
	}

	public static void restore(ServerPlayer target) {
		List<ServerPlayer> others = target.level().getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != target)
				.filter(player -> !PursuitDimensions.isMirror(player.level()))
				.toList();
		for (ServerPlayer other : others) {
			other.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
		}
		if (!others.isEmpty()) {
			target.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(others));
		}
	}
}
