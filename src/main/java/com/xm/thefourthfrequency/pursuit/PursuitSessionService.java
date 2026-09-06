package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.networking.PursuitPresentationPayload;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authoritative source/mirror teleport transaction and disconnect recovery. */
public final class PursuitSessionService {
	private static final long INTERRUPTED_RETRY_TICKS = 5L * 60L * 20L;
	private static final Map<UUID, PendingTransfer> PENDING_TRANSFERS = new HashMap<>();
	private static boolean initialized;

	private PursuitSessionService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerPlayerEvents.JOIN.register(PursuitSessionService::recoverOnJoin);
		ServerPlayerEvents.LEAVE.register(PursuitSessionService::deferDisconnectedSession);
		ServerTickEvents.END_SERVER_TICK.register(PursuitSessionService::tickPendingTransfers);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING_TRANSFERS.clear());
	}

	public static boolean enterEmptyMirror(ServerPlayer player, PursuitSlotManager.Lease lease, int form) {
		return enterEmptyMirror(player, lease, form, false);
	}

	public static boolean enterEmptyMirror(ServerPlayer player, PursuitSlotManager.Lease lease, int form,
			boolean debugSession) {
		if (PursuitDimensions.isMirror(player.level()) || !lease.playerId().equals(player.getUUID())) return false;
		ServerLevel source = (ServerLevel) player.level();
		ServerLevel mirror = source.getServer().getLevel(lease.dimension());
		if (mirror == null) return false;
		var family = PursuitDimensions.sourceFamily(source.dimension());
		if (family.isEmpty() || family.get() != lease.family()) return false;

		BlockPos origin = player.blockPosition();
		String sessionId = UUID.randomUUID().toString();
		int normalizedForm = Math.clamp(form, 1, 5);
		FrequencyWorldData data = FrequencyWorldData.get(source.getServer());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putBoolean(TerminalData.PURSUIT_ACTIVE, true);
			record.putString(TerminalData.PURSUIT_SESSION_ID, sessionId);
			record.putString(TerminalData.PURSUIT_SESSION_PHASE, "warning");
			record.putInt(TerminalData.PURSUIT_SESSION_FORM, normalizedForm);
			record.putBoolean(TerminalData.PURSUIT_SESSION_DEBUG, debugSession);
			if (!debugSession) {
				record.putInt(TerminalData.PURSUIT_TUTORIAL_WARNING_MASK, PursuitTutorialPolicy.mark(
						record.getIntOr(TerminalData.PURSUIT_TUTORIAL_WARNING_MASK, 0), normalizedForm));
			}
			record.putBoolean(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, true);
			record.putString(TerminalData.PURSUIT_SOURCE_DIMENSION, source.dimension().identifier().toString());
			record.putLong(TerminalData.PURSUIT_SOURCE_POSITION, origin.asLong());
			record.putDouble(TerminalData.PURSUIT_SOURCE_YAW, player.getYRot());
			record.putDouble(TerminalData.PURSUIT_SOURCE_PITCH, player.getXRot());
			record.putString(TerminalData.PURSUIT_MIRROR_DIMENSION, lease.dimension().identifier().toString());
			record.putInt(TerminalData.PURSUIT_MIRROR_SLOT, lease.slot());
			record.putLong(TerminalData.PURSUIT_SESSION_STARTED_TICK, source.getGameTime());
			TerminalSignalLog.append(record, SignalBand.UNKNOWN, "pursuit_warning_" + normalizedForm,
					source.getGameTime(), source.getDayTime(), source.dimension().identifier().toString(),
					origin.asLong(), normalizedForm, 2, true);
		});
		PENDING_TRANSFERS.put(player.getUUID(), new PendingTransfer(player.getUUID(), sessionId,
				normalizedForm, source.dimension(), lease, source.getServer().getTickCount()));
		TerminalNoticeService.pursuitWarning(player);
		TerminalRuntimeService.synchronizeAttentionProjection(player, data);
		TerminalRuntimeService.refresh(player);
		sendPresentation(player, sessionId, PursuitPresentationPayload.WARNING, normalizedForm);
		return true;
	}

	public static boolean returnToSource(ServerPlayer player, String resolution) {
		PENDING_TRANSFERS.remove(player.getUUID());
		PursuitFormController.interrupt(player);
		PursuitSnapshotBuilder.cancel(player.getUUID());
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		ServerLevel source = PursuitDimensions.sourceLevel(player.level().getServer(),
				record.getStringOr(TerminalData.PURSUIT_SOURCE_DIMENSION, ""))
				.orElse(player.level().getServer().overworld());
		// A session abandoned before the transfer never moved the player, so ending it must not move
		// them either. See PursuitReturnPolicy: the phase that reaches this most often is the one
		// tickPendingTransfers abandons *because* the player changed dimension, and teleporting them
		// then undoes the very move that ended the chase. Everything else the return does - the
		// ledger, visibility, vision and the session record - still has to happen.
		if (!PursuitReturnPolicy.requiresTeleport(
				record.getStringOr(TerminalData.PURSUIT_SESSION_PHASE, ""),
				PursuitDimensions.isMirror(player.level()))) {
			// No blackout either. It exists to cover a teleport, and there is not going to be one;
			// clearSession sends CLEAR, which is what takes the warning presentation off the screen.
			PursuitVisibilityService.restore(player);
			PursuitVisionService.clear(player);
			PursuitRecoveryLedger.settleAndDeliver(player);
			clearSession(data, player, resolution);
			return true;
		}
		BlockPos entry = BlockPos.of(record.getLongOr(TerminalData.PURSUIT_SOURCE_POSITION,
				source.getRespawnData().pos().asLong()));
		// The mirror is a block-for-block copy at the same coordinates, so wherever the chase left
		// the player is a real place in the source world. Always returning them to the entry point
		// threw the whole chase away: someone who ran two hundred blocks to break line of sight was
		// put back where they started, which reads as the escape not having counted for anything.
		boolean fromMirror = PursuitDimensions.isMirror(player.level());
		BlockPos preferred = fromMirror ? player.blockPosition() : entry;
		BlockPos safe = PursuitReturnLocator.find(source, preferred, entry);
		// Facing comes off the same clock as the position above; the rule and its reasoning live in
		// PursuitReturnView, where they are directly testable.
		PursuitReturnView view = PursuitReturnView.forReturn(fromMirror,
				(float) record.getDoubleOr(TerminalData.PURSUIT_SOURCE_YAW, player.getYRot()),
				(float) record.getDoubleOr(TerminalData.PURSUIT_SOURCE_PITCH, player.getXRot()));
		sendPresentation(player, record.getStringOr(TerminalData.PURSUIT_SESSION_ID, ""),
				PursuitPresentationPayload.BLACKOUT,
				record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 0));
		// Must precede the teleport, not follow it. A cross-dimension teleportTo runs
		// ChunkMap.addEntity synchronously, which pushes ClientboundAddEntityPacket to every nearby
		// player (and every nearby entity back to this player) before this method sees the next
		// line. A client that receives an add-entity for a player it has no PlayerInfo entry for
		// drops the entity outright and only logs a warning, and nothing ever re-sends it until the
		// tracker cycles - so restoring afterwards left the returning player invisible to everyone
		// who was in range at the moment of arrival, and them invisible to the returning player.
		// Vanilla PlayerList.placeNewPlayer observes the same ordering for the same reason.
		PursuitVisibilityService.restore(player);
		player.teleportTo(source, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D,
				view.relative(), view.yaw(), view.pitch(), true);
		// interrupt() above already clears this for a session that reached the mirror; this also
		// covers a return from the prelude, and a recovery join where no runtime exists any more but
		// the effect was persisted with the player.
		PursuitVisionService.clear(player);
		PursuitRecoveryLedger.settleAndDeliver(player);
		clearSession(data, player, resolution);
		return true;
	}

	/**
	 * Books a session closed for a player who is already out of the mirror, without moving them.
	 *
	 * <p>{@link #returnToSource} is the wrong close there, in two ways. It teleports, and with the
	 * player outside the mirror the destination it picks is the session's entry point - so someone
	 * who died in the mirror and respawned would be pulled off their respawn point and put back at
	 * the coordinates the chase started from, next to whatever killed them, and an operator's
	 * teleport would be silently undone a tick after it landed. It also sends BLACKOUT to cover a
	 * transition that is not happening.</p>
	 *
	 * <p>Everything else it does is bookkeeping that still has to run. The slot lease is one of only
	 * {@link PursuitSlotManager#MAX_ACTIVE_PURSUITS} server-wide, and a record left with
	 * {@code PURSUIT_ACTIVE} set makes {@link PursuitDirector} skip that player on every pass.</p>
	 */
	public static boolean abandonSession(ServerPlayer player, String resolution) {
		PENDING_TRANSFERS.remove(player.getUUID());
		PursuitFormController.interrupt(player);
		PursuitSnapshotBuilder.cancel(player.getUUID());
		// Nothing else undoes isolate() for this player: without this they stay missing from
		// everyone's tab list, and everyone else from theirs, until they relog.
		PursuitVisibilityService.restore(player);
		// interrupt() covers a session that reached the mirror; this also covers one abandoned
		// during the prelude, where no runtime was ever created.
		PursuitVisionService.clear(player);
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) {
			// No record left to clear, but the lease is real and has to come back regardless -
			// clearSession is the only other thing that would have released it.
			PursuitSlotManager.release(player.getUUID());
			sendPresentation(player, "", PursuitPresentationPayload.CLEAR, 0);
			return false;
		}
		PursuitRecoveryLedger.settleAndDeliver(player);
		clearSession(data, player, resolution);
		return true;
	}

	private static void deferDisconnectedSession(ServerPlayer player) {
		PENDING_TRANSFERS.remove(player.getUUID());
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) return;
		boolean debugSession = record.getBooleanOr(TerminalData.PURSUIT_SESSION_DEBUG, false);
		data.updateTerminalRecord(player.getUUID(), value -> {
			value.putBoolean(TerminalData.PURSUIT_ACTIVE, false);
			value.putString(TerminalData.PURSUIT_SESSION_PHASE, "recovery_pending");
			if (!debugSession) {
				value.putBoolean(TerminalData.PURSUIT_PENDING, true);
				value.putLong(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK,
						player.level().getGameTime() + INTERRUPTED_RETRY_TICKS);
			}
		});
		PursuitFormController.interrupt(player);
		PursuitSnapshotBuilder.cancel(player.getUUID());
		PursuitSlotManager.release(player.getUUID());
	}

	private static void recoverOnJoin(ServerPlayer player) {
		// Before this player's own recovery, and unconditional: it is about everybody else's chase,
		// not theirs. Vanilla has just handed them the whole player list, which includes anyone the
		// mirror is supposed to have removed from shared reality.
		PursuitVisibilityService.isolateFromArrival(player);
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return;
		if (!record.getListOrEmpty(TerminalData.PURSUIT_RECOVERY_QUEUE).isEmpty()
				|| !record.getListOrEmpty(TerminalData.PURSUIT_REFUND_LEDGER).isEmpty()) {
			PursuitRecoveryLedger.settleAndDeliver(player);
			record = data.terminalRecord(player.getUUID()).orElse(record);
		}
		boolean recoveryPending = record.getStringOr(TerminalData.PURSUIT_SESSION_PHASE, "none")
				.equals("recovery_pending");
		if (!recoveryPending && !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
				&& !PursuitDimensions.isMirror(player.level())) return;
		returnToSource(player, "recovered");
	}

	private static void completeTransfer(net.minecraft.server.MinecraftServer server, UUID playerId,
			String sessionId, PursuitSlotManager.Lease lease, boolean success) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		var record = data.terminalRecord(playerId).orElse(null);
		if (record == null || !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
				|| !record.getStringOr(TerminalData.PURSUIT_SESSION_ID, "").equals(sessionId)
				|| !record.getStringOr(TerminalData.PURSUIT_SESSION_PHASE, "").equals("copying")) return;
		if (!success) {
			clearSession(data, player, "snapshot_failed");
			return;
		}
		ServerLevel mirror = server.getLevel(lease.dimension());
		if (mirror == null) {
			clearSession(data, player, "mirror_missing");
			return;
		}
		ServerLevel departureLevel = (ServerLevel) player.level();
		BlockPos departurePosition = player.blockPosition();
		mirror.getChunkAt(player.blockPosition());
		// Same rule as the return below: the entry keeps the view rather than re-applying it. The
		// black screen and the two-second freeze hide this one, so nothing was visibly wrong here -
		// but re-applying the server's copy of a rotation is a snap by however far the mouse moved
		// since the last movement packet, and leaving the two ends of the same trip asymmetric is how
		// the return drifted in the first place.
		player.teleportTo(mirror, player.getX(), player.getY(), player.getZ(), Relative.ROTATION,
				0.0F, 0.0F, true);
		data.updateTerminalRecord(playerId,
				value -> value.putString(TerminalData.PURSUIT_SESSION_PHASE, "running"));
		notifyNearbyObservers(departureLevel, departurePosition, playerId);
		if (!PursuitFormController.begin(player, sessionId,
				record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 1),
				record.getBooleanOr(TerminalData.PURSUIT_SESSION_DEBUG, false))) {
			returnToSource(player, "entity_unavailable");
			return;
		}
		sendPresentation(player, sessionId, PursuitPresentationPayload.RUNNING,
				record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 1));
	}

	/**
	 * Solitude comes from the observer being removed from shared reality, but the removal itself
	 * should not read as a disconnect to the people standing next to them. Nearby bound terminals
	 * record one impersonal line; it names no one and reveals nothing about the chase.
	 */
	private static void notifyNearbyObservers(ServerLevel source, BlockPos origin, UUID departedId) {
		FrequencyWorldData data = FrequencyWorldData.get(source.getServer());
		for (ServerPlayer observer : source.players()) {
			if (observer.getUUID().equals(departedId)
					|| observer.blockPosition().distSqr(origin) > 64.0D * 64.0D
					|| data.terminalRecord(observer.getUUID()).isEmpty()) continue;
			data.updateTerminalRecord(observer.getUUID(), record ->
					TerminalSignalLog.append(record, SignalBand.UNKNOWN, "peer_signal_lost",
							source.getGameTime(), source.getDayTime(),
							source.dimension().identifier().toString(), origin.asLong(), 0, 2, true));
			TerminalRuntimeService.synchronizeAttentionProjection(observer, data);
			TerminalRuntimeService.refresh(observer);
		}
	}

	private static void clearSession(FrequencyWorldData data, ServerPlayer player, String resolution) {
		PENDING_TRANSFERS.remove(player.getUUID());
		PursuitSnapshotBuilder.cancel(player.getUUID());
		long retryAt = player.level().getGameTime() + INTERRUPTED_RETRY_TICKS;
		boolean successfulResolution = successfulResolution(resolution);
		boolean completedResolution = completedResolution(resolution);
		boolean debugSession = data.terminalRecord(player.getUUID())
				.map(record -> record.getBooleanOr(TerminalData.PURSUIT_SESSION_DEBUG, false))
				.orElse(false);
		// Read before the update lambda, which zeroes the session form on its way through.
		int survivedForm = Math.clamp(data.terminalRecord(player.getUUID())
				.map(record -> record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 0))
				.orElse(0), 1, 5);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putBoolean(TerminalData.PURSUIT_ACTIVE, false);
			record.putString(TerminalData.PURSUIT_SESSION_ID, "");
			record.putString(TerminalData.PURSUIT_SESSION_PHASE,
					resolution == null || resolution.isBlank() ? "returned" : resolution);
			record.putInt(TerminalData.PURSUIT_SESSION_FORM, 0);
			record.putBoolean(TerminalData.PURSUIT_SESSION_DEBUG, false);
			record.putString(TerminalData.PURSUIT_MIRROR_DIMENSION, "");
			record.putInt(TerminalData.PURSUIT_MIRROR_SLOT, -1);
			record.putLong(TerminalData.PURSUIT_SESSION_STARTED_TICK, 0L);
			if (successfulResolution) {
				TerminalSignalLog.removeTypesStartingWith(record, "pursuit_warning_");
				// The only thing the terminal says about a pursuit the player lived through, and it
				// explains nothing. Naming what the form does would hand over rules the next form is
				// about to break, and the page would start reading as a walkthrough. It notes that the
				// signal is gone and that this was not the last of it.
				//
				// Gated on a real escape, so a capture leaves the page silent: not being told anything
				// is what dying costs. Debug sessions are excluded for the same reason they are
				// excluded from every other progress write.
				if (!debugSession) {
					TerminalSignalLog.append(record, SignalBand.UNKNOWN, "pursuit_survived",
							player.level().getGameTime(), player.level().getDayTime(),
							player.level().dimension().identifier().toString(),
							player.blockPosition().asLong(), survivedForm, 2, true);
				}
			}
			if (completedResolution) {
				record.putBoolean(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, true);
				TerminalSignalLog.append(record, SignalBand.UNKNOWN, "pursuit_return_instability",
						player.level().getGameTime(), player.level().getDayTime(),
						player.level().dimension().identifier().toString(), player.blockPosition().asLong(),
						0, 2, true);
			}
			if (!debugSession && !"success".equals(resolution)) {
				record.putBoolean(TerminalData.PURSUIT_PENDING, true);
				record.putLong(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK, retryAt);
			}
		});
		TerminalRuntimeService.synchronizeAttentionProjection(player, data);
		TerminalRuntimeService.refresh(player);
		PursuitSlotManager.release(player.getUUID());
		sendPresentation(player, "", PursuitPresentationPayload.CLEAR, 0);
	}

	private static boolean successfulResolution(String resolution) {
		return "success".equals(resolution) || "debug_complete".equals(resolution);
	}

	private static boolean completedResolution(String resolution) {
		return successfulResolution(resolution) || "caught".equals(resolution);
	}

	private static void tickPendingTransfers(MinecraftServer server) {
		for (PendingTransfer pending : Map.copyOf(PENDING_TRANSFERS).values()) {
			ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
			if (player == null) {
				// Normally ServerPlayerEvents.LEAVE (deferDisconnectedSession) already released
				// this slot before the player list ever loses them. This branch is the defensive
				// fallback for the rare case where that event does not fire before this tick
				// observes the player gone (e.g. a forced removal); release() is idempotent, so
				// this is safe to call even when deferDisconnectedSession already handled it.
				PENDING_TRANSFERS.remove(pending.playerId);
				PursuitSlotManager.release(pending.playerId);
				continue;
			}
			FrequencyWorldData data = FrequencyWorldData.get(server);
			var record = data.terminalRecord(pending.playerId).orElse(null);
			boolean valid = record != null
					&& record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
					&& record.getStringOr(TerminalData.PURSUIT_SESSION_ID, "").equals(pending.sessionId)
					&& record.getStringOr(TerminalData.PURSUIT_SESSION_PHASE, "").equals("warning")
					&& player.level().dimension().equals(pending.sourceDimension);
			if (!valid) {
				PENDING_TRANSFERS.remove(pending.playerId);
				if (record != null && record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) {
					returnToSource(player, "prelude_interrupted");
				} else {
					PursuitSlotManager.release(pending.playerId);
					sendPresentation(player, "", PursuitPresentationPayload.CLEAR, 0);
				}
				continue;
			}
			if (server.getTickCount() - pending.startedAt < PursuitPresentationTimeline.PRELUDE_TICKS) continue;
			PENDING_TRANSFERS.remove(pending.playerId);
			BlockPos origin = player.blockPosition();
			data.updateTerminalRecord(pending.playerId, value -> {
				value.putString(TerminalData.PURSUIT_SESSION_PHASE, "copying");
				value.putLong(TerminalData.PURSUIT_SOURCE_POSITION, origin.asLong());
				value.putDouble(TerminalData.PURSUIT_SOURCE_YAW, player.getYRot());
				value.putDouble(TerminalData.PURSUIT_SOURCE_PITCH, player.getXRot());
			});
			sendPresentation(player, pending.sessionId, PursuitPresentationPayload.BLACKOUT, pending.form);
			boolean accepted = PursuitSnapshotBuilder.start(server, pending.playerId,
					pending.sourceDimension, pending.lease.dimension(), origin,
					success -> completeTransfer(server, pending.playerId, pending.sessionId,
							pending.lease, success));
			if (!accepted) clearSession(data, player, "snapshot_rejected");
		}
	}

	private static void sendPresentation(ServerPlayer player, String sessionId, int phase, int form) {
		if (ServerPlayNetworking.canSend(player, PursuitPresentationPayload.TYPE)) {
			ServerPlayNetworking.send(player, new PursuitPresentationPayload(sessionId, phase, form));
		}
	}

	static void presentResolution(ServerPlayer player, int phase) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) return;
		sendPresentation(player, record.getStringOr(TerminalData.PURSUIT_SESSION_ID, ""), phase,
				record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 0));
	}

	private record PendingTransfer(UUID playerId, String sessionId, int form,
			ResourceKey<Level> sourceDimension, PursuitSlotManager.Lease lease, long startedAt) {
	}
}
