package com.xm.thefourthfrequency.ending;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four finale gates, which had no coverage at all despite deciding whether ambient anomalies,
 * empty segments and pursuits may still reach the player. Every one of them is a threshold read, and
 * a threshold that nothing checks is a threshold that drifts.
 */
final class FinaleRuntimePolicyTest {
	@Test
	void anAbsentOrUnreadableEncounterLeavesTheBackgroundAlone() {
		// No encounter in this world: the finale has no opinion, so nothing may be suppressed.
		assertTrue(FinaleRuntimePolicy.backgroundSystemsAllowed(snapshot(false, true,
				WorldInterfaceStage.UNPREPARED, WorldInterfaceState.Outcome.NONE)));
		// A corrupted root is reported as present-but-invalid. Treating that as "encounter running"
		// would silence the whole background for a world whose save is merely damaged.
		assertTrue(FinaleRuntimePolicy.backgroundSystemsAllowed(snapshot(true, false,
				WorldInterfaceStage.PHASE_2, WorldInterfaceState.Outcome.NONE)));
		assertFalse(FinaleRuntimePolicy.pressureActive(snapshot(true, false,
				WorldInterfaceStage.PHASE_2, WorldInterfaceState.Outcome.NONE)));
		assertFalse(FinaleRuntimePolicy.concluded(snapshot(true, false,
				WorldInterfaceStage.SUCCESS_RESOLUTION, WorldInterfaceState.Outcome.SUCCESS)));
		assertFalse(FinaleRuntimePolicy.succeeded(snapshot(true, false,
				WorldInterfaceStage.COMPLETE, WorldInterfaceState.Outcome.SUCCESS)));
	}

	/**
	 * Nothing ambient reaches the player while the encounter is being fought.
	 *
	 * <p>This is the gap {@code backgroundSystemsAllowed} left: it closes at the resolution, so the
	 * summon and all three phases were open to anomalies and empty segments. {@code silent_world}
	 * mutes MUSIC, AMBIENT and HOSTILE and is sustained for two to three minutes, and every attack
	 * cue the encounter plays is HOSTILE - one roll of it during the summon takes the score, the
	 * telegraphs and the hit feedback away for most of the fight.</p>
	 */
	@Test
	void noAmbientPressureReachesAFightInProgress() {
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.SUMMONING,
				WorldInterfaceStage.PHASE_1, WorldInterfaceStage.PHASE_2,
				WorldInterfaceStage.PHASE_3)) {
			assertFalse(FinaleRuntimePolicy.ambientPressureAllowed(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE)),
					() -> "ambient pressure must be shut off at " + stage);
		}
		// Everything after the fight is already covered by the resolution gate, and must stay shut.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.SUCCESS_RESOLUTION,
				WorldInterfaceStage.FAILURE_RESOLUTION, WorldInterfaceStage.PORTAL_OPEN,
				WorldInterfaceStage.COMPLETE)) {
			assertFalse(FinaleRuntimePolicy.ambientPressureAllowed(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.SUCCESS)),
					() -> "ambient pressure must stay shut at " + stage);
		}
		// Before the roster is even frozen the run is still the run, and the background is the run.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.UNPREPARED,
				WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS)) {
			assertTrue(FinaleRuntimePolicy.ambientPressureAllowed(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE)),
					() -> "ambient pressure must still run at " + stage);
		}
		// A damaged save is not a running encounter, and must not silence a world by accident.
		assertTrue(FinaleRuntimePolicy.ambientPressureAllowed(snapshot(true, false,
				WorldInterfaceStage.PHASE_2, WorldInterfaceState.Outcome.NONE)));
		assertTrue(FinaleRuntimePolicy.ambientPressureAllowed(snapshot(false, true,
				WorldInterfaceStage.UNPREPARED, WorldInterfaceState.Outcome.NONE)));
	}

	@Test
	void theBackgroundClosesAtTheResolutionAndNeverReopens() {
		// Up to the resolution the world is still losing its grip, so the background belongs.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.UNPREPARED,
				WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS,
				WorldInterfaceStage.SUMMONING, WorldInterfaceStage.PHASE_1,
				WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3)) {
			assertTrue(FinaleRuntimePolicy.backgroundSystemsAllowed(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE)),
					() -> "background must still run at " + stage);
		}
		// Once decided it stays shut, including after the portal and through COMPLETE. Reopening at
		// COMPLETE is what told a player who had walked back out of the exit portal that the thing
		// they had just ended was still happening.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.SUCCESS_RESOLUTION,
				WorldInterfaceStage.FAILURE_RESOLUTION, WorldInterfaceStage.PORTAL_OPEN,
				WorldInterfaceStage.COMPLETE)) {
			assertFalse(FinaleRuntimePolicy.backgroundSystemsAllowed(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.SUCCESS)),
					() -> "background must stay closed at " + stage);
		}
	}

	@Test
	void concludedAndBackgroundSuppressionNameTheSameInstant() {
		// These two were introduced separately and have since been deliberately aligned. If one is
		// ever moved without the other, a pursuit and an ambient anomaly disagree about whether the
		// encounter is over - which the player experiences as an epilogue nobody wrote.
		for (WorldInterfaceStage stage : WorldInterfaceStage.values()) {
			WorldInterfaceState.Snapshot snapshot =
					snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE);
			assertTrue(FinaleRuntimePolicy.concluded(snapshot)
							!= FinaleRuntimePolicy.backgroundSystemsAllowed(snapshot),
					() -> "concluded and background suppression disagree at " + stage);
		}
	}

	@Test
	void pressureRunsFromSummoningUntilTheEncounterIsFiledAway() {
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.UNPREPARED,
				WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS)) {
			assertFalse(FinaleRuntimePolicy.pressureActive(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE)),
					() -> "pressure must not start before summoning, at " + stage);
		}
		// Resolution and the portal still count: the encounter is decided but not yet put away.
		for (WorldInterfaceStage stage : List.of(WorldInterfaceStage.SUMMONING,
				WorldInterfaceStage.PHASE_1, WorldInterfaceStage.PHASE_2, WorldInterfaceStage.PHASE_3,
				WorldInterfaceStage.SUCCESS_RESOLUTION, WorldInterfaceStage.FAILURE_RESOLUTION,
				WorldInterfaceStage.PORTAL_OPEN)) {
			assertTrue(FinaleRuntimePolicy.pressureActive(
							snapshot(true, true, stage, WorldInterfaceState.Outcome.NONE)),
					() -> "pressure must run at " + stage);
		}
		assertFalse(FinaleRuntimePolicy.pressureActive(snapshot(true, true,
				WorldInterfaceStage.COMPLETE, WorldInterfaceState.Outcome.SUCCESS)));
	}

	@Test
	void successIsReadFromTheOutcomeRatherThanTheStage() {
		assertTrue(FinaleRuntimePolicy.succeeded(snapshot(true, true,
				WorldInterfaceStage.SUCCESS_RESOLUTION, WorldInterfaceState.Outcome.SUCCESS)));
		// A failed run reaches its own resolution and the portal too; neither is a success.
		assertFalse(FinaleRuntimePolicy.succeeded(snapshot(true, true,
				WorldInterfaceStage.FAILURE_RESOLUTION, WorldInterfaceState.Outcome.FAILURE)));
		assertFalse(FinaleRuntimePolicy.succeeded(snapshot(true, true,
				WorldInterfaceStage.PORTAL_OPEN, WorldInterfaceState.Outcome.FAILURE)));
		assertFalse(FinaleRuntimePolicy.succeeded(snapshot(true, true,
				WorldInterfaceStage.COMPLETE, WorldInterfaceState.Outcome.NONE)));
	}

	/** Mirrors {@code WorldInterfaceState.Snapshot.empty}, varying only what these gates read. */
	private static WorldInterfaceState.Snapshot snapshot(boolean present, boolean valid,
			WorldInterfaceStage stage, WorldInterfaceState.Outcome outcome) {
		return new WorldInterfaceState.Snapshot(present, valid, Optional.empty(), 0L, stage, outcome,
				0, "", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, 0,
				List.of(), List.of(), Set.of(), Map.of(), false, Optional.empty(),
				0.0D, 0.0D, 0L, -1L, 0L, Optional.empty(),
				0L, 0L, 0, 0L, -1L, Map.of(), 0, 0, Map.of(), Map.of(), List.of(),
				Optional.empty(), BlockPos.ZERO, false, 0, -1L, -1L);
	}
}
