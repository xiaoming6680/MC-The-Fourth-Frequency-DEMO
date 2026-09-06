package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.StoryProgressService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Tracks anomaly lifecycle without exposing catalog anomalies in terminal records. */
public final class TerminalAnomalyLogService {
	private TerminalAnomalyLogService() {
	}

	public static void record(ServerPlayer player, String type, int variant, int severity,
			int durationTicks, boolean present) {
		record(player, type, Math.max(1, severity), variant, severity, durationTicks, present,
				player.level().getGameTime() ^ player.getUUID().getLeastSignificantBits());
	}

	public static void record(ServerPlayer player, String type, int tier, int variant, int severity,
			int durationTicks, boolean present, long stableSeed) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		StoryProgressService.recordAnomaly(player, type);
		long now = player.level().getGameTime();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.ACTIVE_ANOMALY_ID, type);
			tag.putLong(TerminalData.ACTIVE_ANOMALY_UNTIL, now + durationTicks);
		});
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	/** Completes anomaly bookkeeping after the active presentation has restored state. */
	public static void recordCompleted(ServerPlayer player, ActiveAnomaly anomaly) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		StoryProgressService.recordAnomaly(player, anomaly.anomalyId());
		long completedAt = player.level().getGameTime();
		String dimension = player.level().dimension().identifier().toString();
		long position = player.blockPosition().asLong();
		int severity = anomaly.tier() <= 2 ? 0 : anomaly.tier() <= 4 ? 1 : 2;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.ACTIVE_ANOMALY_ID, "none");
			tag.putLong(TerminalData.ACTIVE_ANOMALY_UNTIL, 0L);
			// Written from the first anomaly onward and read by nobody until the backfill latch is
			// set. The class doc above still holds - catalogue anomalies never reach terminal records
			// - because this is not terminal records: it is the quarantined store that exists so the
			// page can one day be handed an account that has no gaps in it.
			if (AnomalyBackfillPolicy.retained(anomaly.anomalyId())) {
				TerminalAnomalyLog.append(tag, anomaly.anomalyId(), completedAt, dimension, position,
						anomaly.variant(), severity);
			}
		});
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	/**
	 * Opens the quarantined anomaly store to the records page. Once, and never again.
	 *
	 * <p>Called from both places a terminal can come to know which way the stronghold is: the player
	 * threw an eye, or somebody in the party threw one and the fix was shared. Both are the same
	 * statement - this terminal has a bearing now - and the release is about the terminal having
	 * stopped needing to pretend, not about who paid for the pearls.
	 *
	 * <p>It writes exactly one ordinary record line as it goes. That line is what carries the unread
	 * badge, because the quarantined store has its own counter that nothing reads: without it the
	 * page would silently grow by eighty entries and the player would find out whenever they next
	 * happened to open Records, which could be an hour later or never. One new line brings them in;
	 * what they find when they get there is the rest of it.
	 */
	public static boolean releaseBackfillIfDue(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		if (!AnomalyBackfillPolicy.shouldRelease(TerminalData.anomalyBackfillReleased(record),
				record.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0))) {
			return false;
		}
		long now = player.level().getGameTime();
		long dayTime = player.level().getDayTime() % 24_000L;
		String dimension = player.level().dimension().identifier().toString();
		long position = player.blockPosition().asLong();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.ANOMALY_BACKFILL_RELEASED, true);
			TerminalSignalLog.append(tag, SignalBand.UNKNOWN, "anomaly_archive_released", now, dayTime,
					dimension, position, 0, 1, true);
		});
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
		return true;
	}
}
