package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Has this player done anything lately", for the one anomaly that replays them.
 *
 * <p>Two halves, because either alone answers the wrong question. The record already carries
 * {@link TerminalData#LAST_ACTIVITY_GAME_TIME}, written whenever the player mines, places, crafts
 * or opens something - that covers a player standing at a wall breaking stone, whose replay is
 * full of swings and has no displacement in it at all. It says nothing about a player walking, so
 * the other half is a position sample: somebody crossing a field touches no blocks for minutes.
 *
 * <p>In memory rather than on the record. It is a three-second question, no part of it survives a
 * restart in any useful form, and a per-tick write to persistent player data to answer it would be
 * the expensive way to learn something the next sample re-establishes in a second.
 */
public final class PlayerMotionTracker {
	private static final Map<UUID, Sample> SAMPLES = new HashMap<>();

	private PlayerMotionTracker() { }

	/** Called once per scheduler pass (every 20 ticks) for every online player. */
	public static void sample(ServerPlayer player, long now) {
		UUID id = player.getUUID();
		Sample previous = SAMPLES.get(id);
		double x = player.getX();
		double z = player.getZ();
		if (previous == null) {
			SAMPLES.put(id, new Sample(x, z, 0L));
			return;
		}
		double dx = x - previous.x();
		double dz = z - previous.z();
		boolean moved = AnomalySelectionRules.sampleMoved(Math.sqrt(dx * dx + dz * dz));
		SAMPLES.put(id, new Sample(x, z, moved ? now : previous.lastMovedTick()));
	}

	public static void forget(UUID id) {
		SAMPLES.remove(id);
	}

	public static void clear() {
		SAMPLES.clear();
	}

	/** Whether a replay of the last few seconds would show this player doing something. */
	public static boolean recentlyActive(ServerPlayer player, long now) {
		Sample sample = SAMPLES.get(player.getUUID());
		long lastMoved = sample == null ? 0L : sample.lastMovedTick();
		long lastHandled = 0L;
		CompoundTag record = FrequencyWorldData.get(player.level().getServer())
				.terminalRecord(player.getUUID()).orElse(null);
		if (record != null) lastHandled = record.getLongOr(TerminalData.LAST_ACTIVITY_GAME_TIME, 0L);
		return AnomalySelectionRules.recentlyActive(Math.max(lastMoved, lastHandled), now,
				AnomalySelectionRules.ACTIVITY_WINDOW_TICKS);
	}

	/** Test seam: the tracker is memory-only, so a test has no other way to state a premise. */
	public static void markMovedForTesting(UUID id, long tick) {
		Sample previous = SAMPLES.get(id);
		SAMPLES.put(id, new Sample(previous == null ? 0.0D : previous.x(),
				previous == null ? 0.0D : previous.z(), tick));
	}

	private record Sample(double x, double z, long lastMovedTick) { }
}
