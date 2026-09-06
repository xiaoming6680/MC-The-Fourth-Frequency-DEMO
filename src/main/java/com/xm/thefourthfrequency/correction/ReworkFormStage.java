package com.xm.thefourthfrequency.correction;

/** Pure world-progress-to-form mapping shared by runtime code and unit tests. */
public final class ReworkFormStage {
	public static final int MIN_STAGE = 1;
	public static final int MAX_STAGE = 3;
	public static final int SCHEMA = 2;

	private ReworkFormStage() { }

	public static int forResolvedChases(int resolvedChases) {
		return (int) Math.clamp((long) resolvedChases + 1L, MIN_STAGE, MAX_STAGE);
	}

	public static int readStage(int stage, int schema) {
		return schema < SCHEMA ? (Math.clamp(stage, 1, 5) + 1) / 2
				: Math.clamp(stage, MIN_STAGE, MAX_STAGE);
	}

	public static int legacyPermission(int stage) {
		return stage <= 0 ? 0 : readStage(stage, 1);
	}

	public static int legacyResolved(int count) {
		return (Math.clamp(count, 0, 5) + 1) / 2;
	}

	public static int legacyMask(int mask) {
		int result = 0;
		for (int stage = 1; stage <= 5; stage++) {
			if ((mask & (1 << (stage - 1))) != 0) result |= 1 << (readStage(stage, 1) - 1);
		}
		return result;
	}
}
