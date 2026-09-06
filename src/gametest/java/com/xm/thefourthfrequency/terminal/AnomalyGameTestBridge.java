package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.minecraft.server.level.ServerPlayer;

/** Same-package bridge: accelerated timelines are unreachable from payloads, config, commands, or player input. */
public final class AnomalyGameTestBridge {
	private AnomalyGameTestBridge() { }

	public static boolean start(ServerPlayer player, String id, long seed, int acceleratedTicks) {
		return AmbientAnomalyService.triggerForGameTest(player, id, seed, acceleratedTicks);
	}

	public static ActiveSnapshot active(ServerPlayer player) {
		ActiveAnomaly active = AnomalyRuntimeService.active(player);
		return active == null ? null : new ActiveSnapshot(active.instanceId(), active.anomalyId(), active.stage(),
				active.earliestCompletionTick(), active.terminalRecorded());
	}

	public static int logSequence(ServerPlayer player) {
		return FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID()).orElseThrow()
				.getIntOr(TerminalData.SIGNAL_EVENT_SEQUENCE, 0);
	}

	public static int anomalyLogCount(ServerPlayer player, String anomalyId) {
		return (int) TerminalSignalLog.entries(FrequencyWorldData.get(player.level().getServer())
				.terminalRecord(player.getUUID()).orElseThrow(), SignalBand.UNKNOWN).stream()
				.filter(entry -> entry.type().equals(anomalyId)).count();
	}

	public static String projectedActiveId(ServerPlayer player) {
		return FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID()).orElseThrow()
				.getStringOr(TerminalData.ACTIVE_ANOMALY_ID, "none");
	}

	/** One scheduler draw at {@code tier}, exactly as the ambient tick would run it. */
	public static boolean draw(ServerPlayer player, int tier) {
		return AmbientAnomalyService.triggerSelected(player,
				FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID()).orElseThrow(),
				tier, player.level().getGameTime());
	}

	public static int activeLeaseCount() { return AnomalyServerEffects.activeLeaseCountForGameTest(); }
	public static void cleanup(ServerPlayer player) { AnomalyRuntimeService.interrupt(player, false); }

	public record ActiveSnapshot(java.util.UUID instanceId, String id, ActiveAnomaly.Stage stage,
			long earliestCompletionTick, boolean terminalRecorded) { }
}
