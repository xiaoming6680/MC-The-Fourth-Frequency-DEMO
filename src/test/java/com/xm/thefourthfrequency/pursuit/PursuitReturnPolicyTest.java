package com.xm.thefourthfrequency.pursuit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PursuitReturnPolicyTest {
	@Test
	@DisplayName("A prelude that never moved the player must not move them on the way out")
	void preludePhasesDoNotTeleport() {
		assertFalse(PursuitReturnPolicy.requiresTeleport(PursuitReturnPolicy.PHASE_WARNING, false),
				"the warning is a countdown played where the player stands");
		assertFalse(PursuitReturnPolicy.requiresTeleport(PursuitReturnPolicy.PHASE_COPYING, false),
				"the snapshot is built around the player, who has still not been moved");
	}

	@Test
	@DisplayName("A player who is actually in the mirror always comes out of it")
	void mirrorAlwaysTeleports() {
		// Including from the two prelude phases: a stale phase must never be a reason to leave
		// somebody standing in a dimension that is about to go back into the slot pool.
		for (String phase : new String[] {PursuitReturnPolicy.PHASE_WARNING,
				PursuitReturnPolicy.PHASE_COPYING, PursuitReturnPolicy.PHASE_RUNNING,
				PursuitReturnPolicy.PHASE_RECOVERY_PENDING, "", "nonsense"}) {
			assertTrue(PursuitReturnPolicy.requiresTeleport(phase, true),
					"a player in a mirror has to be taken out of it, phase=" + phase);
		}
	}

	@Test
	@DisplayName("Every other state teleports, including the ones this policy does not name")
	void unknownStatesTeleport() {
		assertTrue(PursuitReturnPolicy.requiresTeleport(PursuitReturnPolicy.PHASE_RUNNING, false),
				"a running session put the player in a mirror they may have already fallen out of");
		assertTrue(PursuitReturnPolicy.requiresTeleport(
						PursuitReturnPolicy.PHASE_RECOVERY_PENDING, false),
				"a recovery login only knows the recorded entry point to be a real place");
		assertTrue(PursuitReturnPolicy.requiresTeleport("", false),
				"an unreadable phase must fall back to the behaviour that cannot strand anyone");
		assertTrue(PursuitReturnPolicy.requiresTeleport("returned", false),
				"a resolution written back into the phase field is not a prelude");
	}

	/**
	 * The bug this policy exists for, asserted where it actually lived.
	 *
	 * <p>{@code tickPendingTransfers} abandons a prelude when the player has changed dimension, and
	 * then called the return - which teleported them back to the dimension they had just left. The
	 * contract is that the return consults this policy before it moves anybody; without that call
	 * the policy is a correct function nothing asks.
	 */
	@Test
	@DisplayName("The session service actually consults the policy before teleporting")
	void sessionServiceConsultsPolicy() throws Exception {
		String service = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		assertTrue(service.contains("PursuitReturnPolicy.requiresTeleport("),
				"returnToSource must ask the policy before it moves the player");
		int guard = service.indexOf("PursuitReturnPolicy.requiresTeleport(");
		int teleport = service.indexOf("player.teleportTo(source,");
		assertTrue(guard >= 0 && teleport > guard,
				"the guard has to come before the teleport it guards");
		assertTrue(service.contains("returnToSource(player, \"prelude_interrupted\")"),
				"the interruption path this was written for still routes through the return");
	}
}
