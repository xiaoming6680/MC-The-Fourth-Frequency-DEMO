package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import com.xm.thefourthfrequency.world.FragmentInvestigationService;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TerminalSignalService {
	private static final Map<UUID, UnreadReminderState> UNREAD_REMINDERS = new HashMap<>();
	private static boolean initialized;

	private TerminalSignalService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(TerminalSignalService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) updatePlayer(player, data);
		UNREAD_REMINDERS.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	private static void updatePlayer(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag before = data.terminalRecord(player.getUUID()).orElse(null);
		if (before == null) return;
		long now = player.level().getGameTime();
		long dayTime = player.level().getDayTime();
		List<String> fileNotifications = new ArrayList<>();
		List<FragmentInvestigationService.SharedReceipt> sharedReceipts = new ArrayList<>();
		boolean[] projectionChanged = {false};
		data.updateTerminalRecord(player.getUUID(), tag -> {
			projectionChanged[0] |= TerminalSignalLog.pruneOperationalTelemetry(tag);
			if (player.isAlive() && !player.isSpectator()) {
				long survived = Math.max(0L, tag.getLongOr(TerminalData.ONLINE_SURVIVAL_TICKS, 0L)) + 20L;
				tag.putLong(TerminalData.ONLINE_SURVIVAL_TICKS, survived);
			}

			int weather = player.level().isThundering() ? 2 : player.level().isRaining() ? 1 : 0;
			tag.putInt(TerminalData.LAST_SIGNAL_WEATHER, weather);
			String dimension = player.level().dimension().identifier().toString();
			tag.putString(TerminalData.LAST_SIGNAL_DIMENSION, dimension);

			if (tag.getBooleanOr(TerminalData.BOUND, false))
				ensureFile(tag, "maintenance_handoff", true, now, dayTime, fileNotifications);
			projectionChanged[0] |= FragmentInvestigationService.synchronizeSharedFiles(tag, player, data, sharedReceipts);
			projectionChanged[0] |= FragmentInvestigationService.ensureSignalMarkers(tag, player);
			projectionChanged[0] |= FragmentInvestigationService.appendCandidateLogs(tag, player, data);
			if (tag.getBooleanOr(TerminalData.LOCAL_FILE_UNLOCKED, false)
					&& TerminalFileState.discovered(tag, "encrypted_witness_file"))
				ensureFile(tag, "encrypted_witness_file", true, now, dayTime, fileNotifications);
			if (tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0)
					>= com.xm.thefourthfrequency.world.SurvivalProgressService.REQUIRED_EYE_SAMPLES)
				ensureFile(tag, "body_mapping_warning", true, now, dayTime, fileNotifications);

			recordStageEvents(tag, player, projectionChanged);
			if (!fileNotifications.isEmpty()) projectionChanged[0] = true;
		});
		if (projectionChanged[0]) {
			TerminalLifecycleService.ensureCarried(player, false);
			TerminalRuntimeService.synchronizeProjection(player);
			TerminalRuntimeService.refresh(player);
		}
		// A sync can carry several files or replay every share a late owner missed. Naming each one
		// would push the rest of the stack out, so only a single arrival keeps its own title.
		if (fileNotifications.size() == 1) {
			TerminalNoticeService.send(player, Component.translatable(
					"message.thefourthfrequency.file.discovered", Component.translatable(
							"terminal.thefourthfrequency.file." + fileNotifications.getFirst() + ".title")));
		} else if (!fileNotifications.isEmpty()) {
			TerminalNoticeService.send(player, Component.translatable(
					"message.thefourthfrequency.file.discovered_batch", fileNotifications.size()));
		}
		if (sharedReceipts.size() == 1) {
			FragmentInvestigationService.SharedReceipt receipt = sharedReceipts.getFirst();
			TerminalNoticeService.send(player, receipt.own()
					? Component.translatable("message.thefourthfrequency.fragment.shared", receipt.fragment())
					: Component.translatable("message.thefourthfrequency.fragment.received",
							receipt.discovererName(), receipt.fragment()));
		} else if (!sharedReceipts.isEmpty()) {
			TerminalNoticeService.send(player, Component.translatable(
					"message.thefourthfrequency.fragment.received_batch", sharedReceipts.size()));
		}
		TerminalTaskService.notifyIfCompleted(player);
		updateUnreadAlert(player, data);
	}

	public static void updatePlayerForTesting(ServerPlayer player) {
		updatePlayer(player, FrequencyWorldData.get(player.level().getServer()));
	}

	public static void revealFromAnomaly(ServerPlayer player) {
		// Kept as a source-compatible hook: anomalies now feed the prelude gate instead of revealing the band.
	}

	public static void record(ServerPlayer player, SignalBand band, String type, int variant,
			int severity, boolean unread) {
		if (AnomalyCatalog.containsHistorical(type)) return;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		data.updateTerminalRecord(player.getUUID(), tag -> append(tag, player, band, type, variant, severity, unread));
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
		updateUnreadAlert(player, data);
	}

	private static void updateUnreadAlert(ServerPlayer player, FrequencyWorldData data) {
		boolean[] cycleStarted = {false};
		int[] unreadCount = {0};
		data.updateTerminalRecord(player.getUUID(), tag -> {
			unreadCount[0] = totalUnreadCount(tag);
			boolean hasUnread = unreadCount[0] > 0;
			boolean latched = tag.getBooleanOr(TerminalData.UNREAD_ALERT_ACTIVE, false);
			if (TerminalAttentionPolicy.unreadStarted(hasUnread, latched)) {
				tag.putBoolean(TerminalData.UNREAD_ALERT_ACTIVE, true);
				cycleStarted[0] = true;
			} else if (!hasUnread && latched) {
				tag.putBoolean(TerminalData.UNREAD_ALERT_ACTIVE, false);
			}
		});
		UUID playerId = player.getUUID();
		if (unreadCount[0] <= 0) {
			UNREAD_REMINDERS.remove(playerId);
			return;
		}
		long now = player.level().getGameTime();
		UnreadReminderState state = UNREAD_REMINDERS.get(playerId);
		if (state == null || cycleStarted[0] || unreadCount[0] < state.lastUnreadCount) {
			state = new UnreadReminderState(now, unreadCount[0], false);
			UNREAD_REMINDERS.put(playerId, state);
		} else if (unreadCount[0] != state.lastUnreadCount) {
			state = new UnreadReminderState(state.unreadSince, unreadCount[0], state.sent);
			UNREAD_REMINDERS.put(playerId, state);
		}
		if (TerminalAttentionPolicy.unreadReminderDue(
				unreadCount[0], state.unreadSince, now, state.sent)) {
			UNREAD_REMINDERS.put(playerId, new UnreadReminderState(state.unreadSince, unreadCount[0], true));
			TerminalNoticeService.unreadReminder(player, unreadCount[0], verbosity(
					data.terminalRecord(player.getUUID()).orElse(null)));
		}
	}

	/**
	 * How much this player's profile says the terminal should explain.
	 *
	 * <p>Read here rather than passed in because the reminder is the only consumer: everything else
	 * this class sends is a record line, and record lines do not change shape by who is reading them.
	 */
	private static TerminalGuidanceVerbosity verbosity(CompoundTag record) {
		if (record == null) return TerminalGuidanceVerbosity.VERBOSE;
		return TerminalGuidanceVerbosity.of(TerminalData.profileAnswers(record),
				TerminalData.profileTaken(record));
	}

	private static int totalUnreadCount(CompoundTag tag) {
		return TerminalSignalLog.unreadCount(tag)
				+ TerminalFileState.unreadCount(tag)
				+ (tag.getBooleanOr(TerminalData.NAVIGATION_COMPLETION_UNREAD, false) ? 1 : 0);
	}

	private static void recordStageEvents(CompoundTag tag, ServerPlayer player, boolean[] changed) {
		int bandStage = tag.getIntOr(TerminalData.BAND_STAGE, 0);
		if (bandStage >= 2) changed[0] |= appendOnce(tag, player, SignalBand.UNKNOWN, "terminal_bound", 0);
		if (tag.getIntOr(TerminalData.PLOT_STAGE, 1) >= 3)
			changed[0] |= appendOnce(tag, player, SignalBand.UNKNOWN, "investigation_stage", 0);
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		if (SurvivalMilestone.IRON.present(milestones))
			changed[0] |= appendOnce(tag, player, SignalBand.MINING, "survival_iron", 0);
		if (SurvivalMilestone.THREW_EYE.present(milestones))
			changed[0] |= appendOnce(tag, player, SignalBand.UNKNOWN, "survival_eye", 0);
		if (SurvivalMilestone.FOUND_STRONGHOLD.present(milestones))
			changed[0] |= appendOnce(tag, player, SignalBand.UNKNOWN, "survival_stronghold", 0);
	}

	private static boolean appendOnce(CompoundTag tag, ServerPlayer player, SignalBand band, String type, int variant) {
		if (TerminalSignalLog.containsType(tag, type)) return false;
		append(tag, player, band, type, variant, 1, true);
		return true;
	}

	private static void append(CompoundTag tag, ServerPlayer player, SignalBand band, String type,
			int variant, int severity, boolean unread) {
		TerminalSignalLog.append(tag, band, type, player.level().getGameTime(), player.level().getDayTime(),
				player.level().dimension().identifier().toString(), player.blockPosition().asLong(),
				variant, severity, unread);
	}

	private static void ensureFile(CompoundTag tag, String id, boolean unlocked, long now, long dayTime,
			List<String> notifications) {
		boolean existed = TerminalFileState.discovered(tag, id);
		if (TerminalFileState.discover(tag, id, now, dayTime, unlocked) && !existed) notifications.add(id);
	}

	private record UnreadReminderState(long unreadSince, int lastUnreadCount, boolean sent) {
	}

}
