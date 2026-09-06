package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import com.xm.thefourthfrequency.pursuit.PursuitSlotManager;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.DebugNames;
import com.xm.thefourthfrequency.unrendered.UnrenderedAnchorPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * One formatter for the two surfaces that read the debug status.
 *
 * <p>The panel writes the whole block down its right column; the HUD writes whichever groups are
 * switched on. Both come from here rather than each formatting the payload themselves, because the
 * failure that costs the most time is not a missing readout - it is two readouts of the same field
 * that disagree, one of which is stale or in the wrong unit, with nothing on screen saying which.</p>
 *
 * <p>Pure and Minecraft-free, so the wording and the arithmetic are covered by a plain unit test
 * rather than only by looking at a running client.</p>
 */
public final class DebugReadout {
	/** Rows the panel reserves for the state block; {@link #panelLines} always returns exactly this many. */
	public static final int PANEL_LINE_COUNT = 10;
	/** Candidates the HUD is willing to name before it stops and counts the rest. */
	public static final int MAX_NAMED_CANDIDATES = 5;

	private DebugReadout() { }

	/**
	 * @param elapsedSeconds whole seconds since this payload arrived, subtracted from every countdown
	 *                       on it so the numbers keep moving between the server pushes
	 */
	public static List<String> panelLines(DebugStatusPayload status, String dimensionMode, int elapsedSeconds) {
		List<String> lines = new ArrayList<>(PANEL_LINE_COUNT);
		lines.add("阶段 " + status.plotStage() + " · 绑定 " + yes(status.bound())
				+ " · 崩坏 " + status.decayStage() + (status.decayAuto() ? "" : "（手动）"));
		lines.add("信号 " + DebugNames.bandStage(status.bandStage()));
		lines.add("等级 " + status.anomalyTier() + "/" + status.anomalyCeiling()
				+ " · 热度 " + status.anomalyHeat() + "% · 选池 " + status.selectionTier()
				+ (status.signaturePending() ? "（特征）" : ""));
		lines.add("当前 " + activeText(status, elapsedSeconds));
		lines.add("下次 " + nextText(status, dimensionMode, elapsedSeconds));
		lines.add("冷却 强 " + countdown(status.strongCooldownSeconds(), elapsedSeconds) + "s · 复合 "
				+ countdown(status.compositeCooldownSeconds(), elapsedSeconds) + "s");
		lines.add("候选 " + candidateCount(status) + " 条 · 未见过 " + unseenCandidateCount(status) + " 条");
		lines.add("文件 " + status.unlockedFiles() + "/" + status.discoveredFiles()
				+ " 解锁 · 未读 " + status.unreadFiles());
		lines.add("追逐 " + pursuitText(status) + " · 镜像 " + status.pursuitSlotsUsed()
				+ "/" + PursuitSlotManager.MAX_ACTIVE_PURSUITS);
		lines.add("未渲染 " + status.unrenderedSessions() + "/" + UnrenderedAnchorPolicy.MAX_CONCURRENT
				+ (status.inUnrenderedLayer() ? "（在层内）" : "")
				+ " · 接口 " + DebugNames.worldInterfaceStage(status.worldInterfaceStage()));
		return List.copyOf(lines);
	}

	/**
	 * The HUD body, one group at a time and in a fixed order.
	 *
	 * <p>Order is fixed rather than following which groups happen to be on, so that turning a group
	 * off never moves the line above it: a readout whose rows shuffle is one that has to be re-read
	 * from the top every time it changes.</p>
	 */
	public static List<String> hudLines(DebugStatusPayload status, String dimensionMode, int groupMask,
			int elapsedSeconds) {
		List<String> lines = new ArrayList<>();
		if (DebugHudState.Group.RHYTHM.enabledIn(groupMask)) {
			lines.add("下次 " + nextText(status, dimensionMode, elapsedSeconds));
			lines.add("当前 " + activeText(status, elapsedSeconds));
			lines.addAll(candidateLines(status));
		}
		if (DebugHudState.Group.NUMBERS.enabledIn(groupMask)) {
			lines.add("等级 " + status.anomalyTier() + "/" + status.anomalyCeiling()
					+ " · 热度 " + status.anomalyHeat() + "% · 选池 " + status.selectionTier()
					+ (status.signaturePending() ? "（特征）" : ""));
			lines.add("冷却 强 " + countdown(status.strongCooldownSeconds(), elapsedSeconds) + "s · 复合 "
					+ countdown(status.compositeCooldownSeconds(), elapsedSeconds) + "s");
		}
		if (DebugHudState.Group.STORY.enabledIn(groupMask)) {
			lines.add("阶段 " + status.plotStage() + " · 绑定 " + yes(status.bound())
					+ " · 崩坏 " + status.decayStage() + (status.decayAuto() ? "" : "（手动）"));
			lines.add("信号 " + DebugNames.bandStage(status.bandStage()));
			lines.add("文件 " + status.unlockedFiles() + "/" + status.discoveredFiles()
					+ " 解锁 · 未读 " + status.unreadFiles());
		}
		if (DebugHudState.Group.PURSUIT.enabledIn(groupMask)) {
			lines.add("追逐 " + pursuitText(status) + " · 镜像 " + status.pursuitSlotsUsed()
					+ "/" + PursuitSlotManager.MAX_ACTIVE_PURSUITS);
			lines.add("未渲染 " + status.unrenderedSessions() + "/" + UnrenderedAnchorPolicy.MAX_CONCURRENT
					+ (status.inUnrenderedLayer() ? "（在层内）" : "")
					+ " · 接口 " + DebugNames.worldInterfaceStage(status.worldInterfaceStage()));
		}
		return List.copyOf(lines);
	}

	/**
	 * The pool the next draw is choosing between, named.
	 *
	 * <p>This is what stands in for the thing that cannot be shown. The winner is decided on the tick
	 * it fires - the seed is mixed with the game time - and the director then walks past any entry
	 * whose own preflight refuses, so a single name here would be wrong most of the time and would
	 * still read as a promise. The pool is a fact for as long as nothing about the player changes,
	 * and a star marks one they have never met, which outranks the rest on every draw.</p>
	 */
	public static List<String> candidateLines(DebugStatusPayload status) {
		List<String> names = candidateNames(status);
		if (names.isEmpty()) return List.of("候选 无（本维度不触发或条件未满足）");
		List<String> lines = new ArrayList<>();
		lines.add("候选 " + names.size() + " 条 · 未见过 " + unseenCandidateCount(status) + " 条");
		for (int index = 0; index < Math.min(names.size(), MAX_NAMED_CANDIDATES); index++) {
			lines.add("  " + names.get(index));
		}
		if (names.size() > MAX_NAMED_CANDIDATES) {
			lines.add("  …另外 " + (names.size() - MAX_NAMED_CANDIDATES) + " 条");
		}
		return List.copyOf(lines);
	}

	/** Candidate display names in catalogue order, unseen entries starred. */
	public static List<String> candidateNames(DebugStatusPayload status) {
		var definitions = AnomalyCatalog.definitions();
		List<String> names = new ArrayList<>();
		for (int index = 0; index < definitions.size() && index < Long.SIZE; index++) {
			if (!bit(status.candidateMask(), index)) continue;
			names.add((bit(status.unseenMask(), index) ? "★" : "•")
					+ DebugNames.anomaly(definitions.get(index).id()));
		}
		return List.copyOf(names);
	}

	public static int candidateCount(DebugStatusPayload status) {
		return Long.bitCount(status.candidateMask());
	}

	public static int unseenCandidateCount(DebugStatusPayload status) {
		return Long.bitCount(status.candidateMask() & status.unseenMask());
	}

	private static String activeText(DebugStatusPayload status, int elapsedSeconds) {
		if (status.activeAnomaly().equals("none")) return "无";
		int remaining = countdown(status.activeSeconds(), elapsedSeconds);
		return DebugNames.anomaly(status.activeAnomaly()) + (remaining > 0 ? " " + remaining + "s" : "");
	}

	/**
	 * The countdown, or the reason there is not one.
	 *
	 * <p>A suspended director and a frozen dimension both produce a number that will never be reached,
	 * and printing it beside a live one is the single most misleading thing this readout could do.
	 * Suspension is named first because it is the state a developer put the world into on purpose and
	 * then forgot about.</p>
	 */
	private static String nextText(DebugStatusPayload status, String dimensionMode, int elapsedSeconds) {
		if (status.anomaliesSuspended()) return "自动触发已停止";
		return countdown(status.nextSeconds(), elapsedSeconds) + "s · " + dimensionMode;
	}

	/**
	 * A pushed countdown carried forward to now, floored at zero.
	 *
	 * <p>Floored rather than allowed to go negative because a deadline that has passed without the
	 * anomaly arriving is a real state - the director checks on its own interval and every candidate
	 * can refuse - and "0s" says that, where "-14s" reads as a broken clock.</p>
	 */
	private static int countdown(int pushedSeconds, int elapsedSeconds) {
		return Math.max(0, pushedSeconds - Math.max(0, elapsedSeconds));
	}

	private static String pursuitText(DebugStatusPayload status) {
		return status.pursuitActive() ? "第 " + status.pursuitForm() + " 形态" : "无";
	}

	private static boolean bit(long mask, int index) {
		return index >= 0 && index < Long.SIZE && (mask & 1L << index) != 0L;
	}

	private static String yes(boolean value) {
		return value ? "是" : "否";
	}
}
