package com.xm.thefourthfrequency.terminal;

import java.util.Objects;
import java.util.UUID;

/** Non-persistent per-player lifecycle state for one anomaly instance. */
public final class ActiveAnomaly {
	public enum Stage { STARTING, RUNNING, CLEANING, COMPLETED }

	private final UUID instanceId;
	private final UUID targetPlayerId;
	private final String anomalyId;
	private final int tier;
	private final int variant;
	private final long seed;
	private final long startTick;
	private final long earliestCompletionTick;
	private final long timeoutTick;
	private Stage stage = Stage.STARTING;
	private int phaseSequence;
	private boolean terminalRecorded;
	private AnomalyCompletionStatus completionStatus;

	public ActiveAnomaly(UUID instanceId, UUID targetPlayerId, String anomalyId, int tier, int variant,
			long seed, long startTick, int expectedDurationTicks, int timeoutGraceTicks) {
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.targetPlayerId = Objects.requireNonNull(targetPlayerId, "targetPlayerId");
		this.anomalyId = Objects.requireNonNull(anomalyId, "anomalyId");
		this.tier = Math.clamp(tier, 1, 5);
		this.variant = Math.max(0, variant);
		this.seed = seed;
		this.startTick = startTick;
		this.earliestCompletionTick = startTick + Math.max(1, expectedDurationTicks);
		this.timeoutTick = earliestCompletionTick + Math.max(20, timeoutGraceTicks);
	}

	public void markRunning() {
		if (stage != Stage.STARTING) throw new IllegalStateException("Anomaly cannot enter RUNNING from " + stage);
		stage = Stage.RUNNING;
	}

	public boolean beginCleanup() {
		if (stage == Stage.COMPLETED || stage == Stage.CLEANING) return false;
		stage = Stage.CLEANING;
		return true;
	}

	public boolean acceptCompletion(UUID playerId, UUID reportedInstanceId, long now,
			AnomalyCompletionStatus status) {
		if (!targetPlayerId.equals(playerId) || !instanceId.equals(reportedInstanceId)
				|| stage == Stage.COMPLETED || now < earliestCompletionTick) return false;
		stage = Stage.COMPLETED;
		completionStatus = Objects.requireNonNull(status, "status");
		return true;
	}

	/**
	 * Accepts a completion the server decided on, without the earliest-completion check.
	 *
	 * <p>That check exists to stop a <em>client</em> claiming an anomaly finished the tick it
	 * started; it is a statement about who is allowed to say so, not about how long an anomaly must
	 * last. An anomaly whose end condition the server watches for itself - a player walking into the
	 * way out of the unrendered layer - can legitimately end at any moment, and running it through
	 * {@link #acceptCompletion} would reject that and let the instance time out as interrupted
	 * instead, so a real success would never be recorded.
	 */
	public boolean acceptServerCompletion(UUID playerId, AnomalyCompletionStatus status) {
		if (!targetPlayerId.equals(playerId) || stage == Stage.COMPLETED) return false;
		stage = Stage.COMPLETED;
		completionStatus = Objects.requireNonNull(status, "status");
		return true;
	}

	public void interrupt() {
		if (stage == Stage.COMPLETED) return;
		stage = Stage.COMPLETED;
		completionStatus = AnomalyCompletionStatus.INTERRUPTED;
	}

	public boolean markTerminalRecorded() {
		if (terminalRecorded || completionStatus != AnomalyCompletionStatus.COMPLETED) return false;
		terminalRecorded = true;
		return true;
	}

	public int nextPhaseSequence() { return ++phaseSequence; }
	public UUID instanceId() { return instanceId; }
	public UUID targetPlayerId() { return targetPlayerId; }
	public String anomalyId() { return anomalyId; }
	public int tier() { return tier; }
	public int variant() { return variant; }
	public long seed() { return seed; }
	public long startTick() { return startTick; }
	public long earliestCompletionTick() { return earliestCompletionTick; }
	public long timeoutTick() { return timeoutTick; }
	public Stage stage() { return stage; }
	public boolean terminalRecorded() { return terminalRecorded; }
	public AnomalyCompletionStatus completionStatus() { return completionStatus; }
}
