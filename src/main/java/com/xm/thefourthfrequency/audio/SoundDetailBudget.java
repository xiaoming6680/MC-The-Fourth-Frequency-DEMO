package com.xm.thefourthfrequency.audio;

import java.util.HashMap;
import java.util.Map;

/** Per-world/per-client decorative cues. Authoritative warning and impact beats bypass this. */
public final class SoundDetailBudget {
	private final int limit;
	private final Map<String, Long> recent = new HashMap<>();
	private long tick = Long.MIN_VALUE;
	private int used;

	public SoundDetailBudget(int limit) {
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");
		this.limit = limit;
	}

	public boolean admit(long now, String voice, int minimumGap) {
		if (now != tick) {
			if (now < tick) recent.clear();
			tick = now;
			used = 0;
			recent.entrySet().removeIf(entry -> now - entry.getValue() > 200L);
		}
		Long previous = recent.get(voice);
		if (used >= limit || (previous != null && now - previous < minimumGap)) return false;
		if (recent.size() >= 512 && previous == null) return false;
		recent.put(voice, now);
		used++;
		return true;
	}

	public void clear() { recent.clear(); tick = Long.MIN_VALUE; used = 0; }
}
