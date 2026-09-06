package com.xm.thefourthfrequency.terminal;

/** One feedback snapshot per player and server tick; later inputs are flushed on the next tick. */
public final class TerminalSyncWindow {
	private long lastSentTick = Long.MIN_VALUE;
	public boolean take(long tick) {
		if (lastSentTick == tick) return false;
		lastSentTick = tick;
		return true;
	}
}
