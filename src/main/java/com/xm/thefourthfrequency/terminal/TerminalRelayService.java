package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.world.PrivateDimensions;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Terminals that have stood near each other for a while, leaking into one another minutes later.
 *
 * <p>Rules and numbers live in {@link TerminalRelayPolicy}; this only applies them. Nothing here
 * decides what may cross - it captures a shape, holds it for the delay, and writes one ordinary
 * record line when the time comes.
 */
public final class TerminalRelayService {
	private static final int CHECK_INTERVAL = 40;
	private static boolean initialized;

	private TerminalRelayService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(TerminalRelayService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
			if (record == null || !record.getBooleanOr(TerminalData.BOUND, false)) continue;
			deliverDue(player, data, record);
			if (!eligible(player, record, data)) {
				clearContact(player, data, record);
				continue;
			}
			observeContact(player, data, record);
		}
	}

	/**
	 * Whether this terminal is in a state where a relay makes any sense.
	 *
	 * <p>The mirror is excluded outright: a player in the private correction layer is not standing
	 * near anybody, and the whole point of that layer is that they have been taken out of the shared
	 * world. The finale is excluded for the same reason every other background system is - once the
	 * ritual starts, nothing else is allowed to speak.
	 */
	private static boolean eligible(ServerPlayer player, CompoundTag record, FrequencyWorldData data) {
		return player.isAlive() && !player.isSpectator() && !player.isSleeping()
				&& !PrivateDimensions.isPrivate(player.level())
				&& !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
				&& FinaleRuntimePolicy.backgroundSystemsAllowed(data);
	}

	/**
	 * Accumulates time in range of a qualifying peer, and captures once it is long enough.
	 *
	 * <p>Consent is checked on both sides and in the right direction: the peer must have agreed to
	 * pass anything on, and this player must not have refused to receive. An unanswered profile does
	 * not send, because nobody who was never asked has agreed to anything.
	 */
	private static void observeContact(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		long now = player.level().getGameTime();
		if (!TerminalRelayPolicy.mayReceive(TerminalData.profileAnswers(record))) {
			clearContact(player, data, record);
			return;
		}
		ServerPlayer peer = nearestConsentingPeer(player, data);
		if (peer == null) {
			clearContact(player, data, record);
			return;
		}
		long since = record.getLongOr(TerminalData.RELAY_CONTACT_SINCE, 0L);
		if (since <= 0L) {
			data.updateTerminalRecord(player.getUUID(),
					tag -> tag.putLong(TerminalData.RELAY_CONTACT_SINCE, now));
			return;
		}
		if (now - since < TerminalRelayPolicy.CONTACT_TICKS) return;
		int pending = record.getListOrEmpty(TerminalData.RELAY_PENDING).size();
		long lastDelivered = record.getLongOr(TerminalData.RELAY_LAST_DELIVERED, 0L);
		if (!TerminalRelayPolicy.mayQueue(lastDelivered, now, pending)) return;

		CompoundTag peerRecord = data.terminalRecord(peer.getUUID()).orElse(null);
		boolean peerHasProgress = peerRecord != null
				&& peerRecord.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0) != 0;
		long seed = now ^ player.getUUID().getLeastSignificantBits()
				^ peer.getUUID().getMostSignificantBits();
		TerminalRelayPolicy.Shape shape = TerminalRelayPolicy.shapeFor(
				record.getIntOr(TerminalData.ANOMALY_TIER, 0), peerHasProgress, seed);
		long deliverAt = now + TerminalRelayPolicy.delayTicks(seed);
		data.updateTerminalRecord(player.getUUID(), tag -> {
			ListTag queue = tag.getListOrEmpty(TerminalData.RELAY_PENDING).copy();
			CompoundTag entry = new CompoundTag();
			entry.putLong("deliver_at", deliverAt);
			entry.putString("type", shape.type());
			queue.add(entry);
			tag.put(TerminalData.RELAY_PENDING, queue);
			// Reset so the same standing-together does not capture again immediately. The delivery
			// cooldown is what actually paces this, but leaving the accumulator full would let a pair
			// who never move fill the pending queue in four checks.
			tag.putLong(TerminalData.RELAY_CONTACT_SINCE, now);
		});
	}

	/**
	 * The nearest bound player who has agreed to pass their lines on.
	 *
	 * <p>Their identity seeds the capture and is never stored, so nothing about who it was survives
	 * into the line that eventually surfaces.
	 */
	private static ServerPlayer nearestConsentingPeer(ServerPlayer player, FrequencyWorldData data) {
		ServerPlayer best = null;
		double bestDistance = Double.MAX_VALUE;
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			if (other == player || other.isSpectator() || other.level() != player.level()) continue;
			double distance = other.distanceToSqr(player);
			if (!TerminalRelayPolicy.inRange(distance) || distance >= bestDistance) continue;
			CompoundTag otherRecord = data.terminalRecord(other.getUUID()).orElse(null);
			if (otherRecord == null || !otherRecord.getBooleanOr(TerminalData.BOUND, false)) continue;
			if (!TerminalRelayPolicy.maysend(TerminalData.profileAnswers(otherRecord))) continue;
			best = other;
			bestDistance = distance;
		}
		return best;
	}

	private static void clearContact(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		if (record.getLongOr(TerminalData.RELAY_CONTACT_SINCE, 0L) <= 0L) return;
		data.updateTerminalRecord(player.getUUID(),
				tag -> tag.putLong(TerminalData.RELAY_CONTACT_SINCE, 0L));
	}

	/** Surfaces any captured line whose delay has run out, one per check at most. */
	private static void deliverDue(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		ListTag queue = record.getListOrEmpty(TerminalData.RELAY_PENDING);
		if (queue.isEmpty()) return;
		long now = player.level().getGameTime();
		int due = -1;
		for (int index = 0; index < queue.size(); index++) {
			if (queue.getCompoundOrEmpty(index).getLongOr("deliver_at", Long.MAX_VALUE) <= now) {
				due = index;
				break;
			}
		}
		if (due < 0) return;
		String stored = queue.getCompoundOrEmpty(due).getStringOr("type", "");
		String delivered = TerminalRelayPolicy.Shape.isRelayType(stored)
				? stored : TerminalRelayPolicy.Shape.UNRESOLVED.type();
		int index = due;
		long dayTime = player.level().getDayTime() % 24_000L;
		String dimension = player.level().dimension().identifier().toString();
		long position = player.blockPosition().asLong();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			ListTag pending = tag.getListOrEmpty(TerminalData.RELAY_PENDING).copy();
			if (index < pending.size()) pending.remove(index);
			tag.put(TerminalData.RELAY_PENDING, pending);
			tag.putLong(TerminalData.RELAY_LAST_DELIVERED, now);
			TerminalSignalLog.append(tag, SignalBand.UNKNOWN, delivered, now, dayTime,
					dimension, position, 0, 0, true);
		});
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}
}
