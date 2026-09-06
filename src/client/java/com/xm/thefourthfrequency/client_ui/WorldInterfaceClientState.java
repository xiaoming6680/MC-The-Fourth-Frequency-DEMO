package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable client projection with one monotonic sequence stream per encounter UUID. */
public final class WorldInterfaceClientState {
	private static final AtomicReference<Projection> CURRENT = new AtomicReference<>(Projection.empty());

	private WorldInterfaceClientState() {
	}

	public static Projection snapshot() {
		return CURRENT.get();
	}

	public static synchronized boolean accept(AltarSnapshotS2C payload) {
		Projection base = advance(payload.encounterId(), payload.sequence());
		if (base == null) return false;
		CURRENT.set(new Projection(payload.encounterId(), payload.sequence(), payload,
				base.encounter(), base.action(), base.poem()));
		return true;
	}

	public static synchronized boolean accept(WorldInterfaceSnapshotS2C payload) {
		Projection base = advance(payload.encounterId(), payload.sequence());
		if (base == null) return false;
		CURRENT.set(new Projection(payload.encounterId(), payload.sequence(), base.altar(),
				payload, base.action(), base.poem()));
		return true;
	}

	public static synchronized boolean accept(BossActionS2C payload) {
		Projection base = advance(payload.encounterId(), payload.sequence());
		if (base == null) return false;
		CURRENT.set(new Projection(payload.encounterId(), payload.sequence(), base.altar(),
				base.encounter(), payload, base.poem()));
		return true;
	}

	public static synchronized boolean accept(PoemStartS2C payload) {
		Projection base = advance(payload.encounterId(), payload.sequence());
		if (base == null) return false;
		CURRENT.set(new Projection(payload.encounterId(), payload.sequence(), base.altar(),
				base.encounter(), base.action(), payload));
		return true;
	}

	public static synchronized void clearSession() {
		CURRENT.set(Projection.empty());
	}

	private static Projection advance(UUID encounterId, long sequence) {
		Projection current = CURRENT.get();
		if (current.encounterId() == null) return Projection.forEncounter(encounterId);
		if (!current.encounterId().equals(encounterId)) {
			if (!current.terminal()) return null;
			return Projection.forEncounter(encounterId);
		}
		return sequence > current.lastSequence() ? current : null;
	}

	public record Projection(
			UUID encounterId,
			long lastSequence,
			AltarSnapshotS2C altar,
			WorldInterfaceSnapshotS2C encounter,
			BossActionS2C action,
			PoemStartS2C poem
	) {
		private static Projection empty() {
			return new Projection(null, -1L, null, null, null, null);
		}

		private static Projection forEncounter(UUID encounterId) {
			return new Projection(encounterId, -1L, null, null, null, null);
		}

		public boolean terminal() {
			return encounter != null && encounter.stage() == WorldInterfaceProtocol.Stage.COMPLETE;
		}

		/**
		 * Whether the finale is live enough to hold the pause menu's exit shut.
		 *
		 * <p>Narrower than {@link #combatVisible()} on purpose. That predicate is about what the
		 * client draws, and it stays true through the resolution stages and the open portal, which
		 * are the epilogue - the fight is decided, the world has been handed back, and the menu is
		 * exactly where a player should be able to go. Holding the door through those would be the
		 * story taking something it no longer needs.
		 */
		public boolean locksPauseExit() {
			if (encounter == null) return false;
			return switch (encounter.stage()) {
				case SUMMONING, PHASE_1, PHASE_2, PHASE_3 -> true;
				default -> false;
			};
		}

		public boolean combatVisible() {
			if (encounter == null) return false;
			return switch (encounter.stage()) {
				case SUMMONING, PHASE_1, PHASE_2, PHASE_3, SUCCESS_RESOLUTION, FAILURE_RESOLUTION,
						PORTAL_OPEN -> true;
				default -> false;
			};
		}

		public float healthRatio() {
			if (encounter == null || encounter.maxHealth() <= 0.0F) return 0.0F;
			return Math.clamp(encounter.currentHealth() / encounter.maxHealth(), 0.0F, 1.0F);
		}

		public double collapseProgress(long currentServerTick) {
			if (encounter == null) return 0.0D;
			long extrapolated = encounter.elapsedTicks();
			if (!encounter.timerPaused() && encounter.outcome() == WorldInterfaceProtocol.Outcome.NONE) {
				extrapolated += Math.max(0L, currentServerTick - encounter.serverTick());
			}
			return Math.clamp(extrapolated / (double) WorldInterfaceProtocol.COLLAPSE_DURATION_TICKS,
					0.0D, 1.0D);
		}

		public boolean actionActive(long currentServerTick) {
			if (action == null || action.action() == WorldInterfaceProtocol.BossAction.NONE) return false;
			long elapsed = currentServerTick - action.startTick();
			return elapsed >= 0L && elapsed < action.duration();
		}

		public boolean actionTargets(UUID playerId) {
			return action != null && (action.targetIds().isEmpty() || action.targetIds().contains(playerId));
		}
	}
}
