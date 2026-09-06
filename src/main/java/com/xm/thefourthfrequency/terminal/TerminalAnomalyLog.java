package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;

public final class TerminalAnomalyLog {
	/**
	 * Sized by {@link AnomalyBackfillPolicy}, not by this class.
	 *
	 * <p>It was 32 for as long as nothing read this store. The backfill turns the same list into a
	 * full account of one playthrough, and the two numbers have to be the same number or the wire
	 * clamp and the storage cap will disagree about which end gets dropped.
	 */
	public static final int MAX_ENTRIES = AnomalyBackfillPolicy.MAX_ENTRIES;

	private TerminalAnomalyLog() {
	}

	public static Entry append(CompoundTag record, String type, long gameTime,
			String dimension, long position, int variant, int severity) {
		int sequence = Math.max(0, record.getIntOr(TerminalData.ANOMALY_LOG_SEQUENCE, 0)) + 1;
		ListTag logs = record.getListOrEmpty(TerminalData.ANOMALY_LOGS).copy();
		CompoundTag encoded = new CompoundTag();
		encoded.putInt("sequence", sequence);
		encoded.putString("type", bounded(type, 32));
		encoded.putLong("game_time", Math.max(0L, gameTime));
		encoded.putString("dimension", bounded(dimension, 128));
		encoded.putLong("position", position);
		encoded.putInt("variant", Math.floorMod(variant, 4));
		encoded.putInt("severity", Math.clamp(severity, 0, 2));
		encoded.putBoolean("unread", true);
		logs.add(encoded);
		while (logs.size() > MAX_ENTRIES) logs.remove(0);
		record.put(TerminalData.ANOMALY_LOGS, logs);
		record.putInt(TerminalData.ANOMALY_LOG_SEQUENCE, sequence);
		record.putInt(TerminalData.UNREAD_ANOMALY_COUNT, unreadCount(logs));
		return decode(encoded);
	}

	public static List<Entry> entries(CompoundTag record) {
		ListTag logs = record.getListOrEmpty(TerminalData.ANOMALY_LOGS);
		List<Entry> result = new ArrayList<>(logs.size());
		for (int index = logs.size() - 1; index >= 0; index--) {
			result.add(decode(logs.getCompoundOrEmpty(index)));
		}
		return List.copyOf(result);
	}

	public static boolean markRead(CompoundTag record, int sequence) {
		ListTag logs = record.getListOrEmpty(TerminalData.ANOMALY_LOGS).copy();
		boolean changed = false;
		for (int index = 0; index < logs.size(); index++) {
			CompoundTag entry = logs.getCompoundOrEmpty(index);
			if (entry.getIntOr("sequence", -1) == sequence && entry.getBooleanOr("unread", false)) {
				entry.putBoolean("unread", false);
				logs.set(index, entry);
				changed = true;
				break;
			}
		}
		if (changed) {
			record.put(TerminalData.ANOMALY_LOGS, logs);
			record.putInt(TerminalData.UNREAD_ANOMALY_COUNT, unreadCount(logs));
		}
		return changed;
	}

	public static boolean markAllRead(CompoundTag record) {
		ListTag logs = record.getListOrEmpty(TerminalData.ANOMALY_LOGS).copy();
		boolean changed = false;
		for (int index = 0; index < logs.size(); index++) {
			CompoundTag entry = logs.getCompoundOrEmpty(index);
			if (entry.getBooleanOr("unread", false)) {
				entry.putBoolean("unread", false);
				logs.set(index, entry);
				changed = true;
			}
		}
		if (changed) record.put(TerminalData.ANOMALY_LOGS, logs);
		record.putInt(TerminalData.UNREAD_ANOMALY_COUNT, 0);
		return changed;
	}

	public static int unreadCount(CompoundTag record) {
		return unreadCount(record.getListOrEmpty(TerminalData.ANOMALY_LOGS));
	}

	private static int unreadCount(ListTag logs) {
		int count = 0;
		for (int index = 0; index < logs.size(); index++) {
			if (logs.getCompoundOrEmpty(index).getBooleanOr("unread", false)) count++;
		}
		return count;
	}

	private static Entry decode(CompoundTag encoded) {
		return new Entry(
				Math.max(0, encoded.getIntOr("sequence", 0)),
				bounded(encoded.getStringOr("type", "unknown"), 32),
				Math.max(0L, encoded.getLongOr("game_time", 0L)),
				bounded(encoded.getStringOr("dimension", "minecraft:overworld"), 128),
				encoded.getLongOr("position", 0L),
				Math.floorMod(encoded.getIntOr("variant", 0), 4),
				Math.clamp(encoded.getIntOr("severity", 0), 0, 2),
				encoded.getBooleanOr("unread", false));
	}

	private static String bounded(String value, int maximum) {
		if (value == null) return "";
		return value.length() <= maximum ? value : value.substring(0, maximum);
	}

	public record Entry(int sequence, String type, long gameTime, String dimension,
			long position, int variant, int severity, boolean unread) {
	}
}
