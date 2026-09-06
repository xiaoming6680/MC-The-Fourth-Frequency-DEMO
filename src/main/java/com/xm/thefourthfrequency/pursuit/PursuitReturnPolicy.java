package com.xm.thefourthfrequency.pursuit;

/**
 * Whether a pursuit that is ending has to move the player at all.
 *
 * <p>Every way a session can end funnels through one return, and that return was written for the
 * case it is named after: the player is standing in a private mirror and has to be put back in the
 * world they were taken out of. Two of the phases it also runs for have not taken them anywhere.
 *
 * <p>The warning is a countdown played where the player is standing. The copy that follows it is a
 * snapshot being built around them, still where they are standing. Only when that finishes is the
 * player teleported into the mirror. So a session abandoned in either of the first two phases is
 * one where the player has not been moved, and there is nothing to give back.
 *
 * <p>Returning them anyway does not restore anything - it <em>takes</em> something: whatever the
 * player did during the prelude gets undone. The case that made this visible is the interruption
 * check in {@code PursuitSessionService.tickPendingTransfers}, which abandons the prelude precisely
 * <em>because</em> the player changed dimension - and then handed them to the return, which
 * teleported them back to the dimension they had just deliberately left. A player who walked into a
 * nether portal while a chase was counting down was pulled back out of it.
 *
 * <p>Pure, so both halves of the rule are directly testable and neither depends on a live server.
 */
public final class PursuitReturnPolicy {
	/** Counting down where the player stands. Nothing has been moved. */
	public static final String PHASE_WARNING = "warning";
	/** The mirror snapshot is being built around the player. Still nothing has been moved. */
	public static final String PHASE_COPYING = "copying";
	/** The player is in the mirror. */
	public static final String PHASE_RUNNING = "running";
	/**
	 * A session that outlived its own runtime - a crash, or a login that found the ledger still
	 * open. This one always teleports: the player may be standing in a mirror that no longer
	 * exists, or wherever vanilla put them when it could not be loaded, and only the recorded entry
	 * point is known to be a real place in a real world.
	 */
	public static final String PHASE_RECOVERY_PENDING = "recovery_pending";

	private PursuitReturnPolicy() {
	}

	/**
	 * Whether ending a session in this state has to teleport the player.
	 *
	 * @param phase    the session's recorded phase
	 * @param inMirror whether the player is currently standing in a mirror dimension
	 */
	public static boolean requiresTeleport(String phase, boolean inMirror) {
		// A player who is in a mirror has to come out of it, whatever the ledger says the phase is.
		// This is the safety direction to fail in: a stale phase must never strand somebody in a
		// dimension that is about to be released back to the slot pool.
		if (inMirror) return true;
		return !PHASE_WARNING.equals(phase) && !PHASE_COPYING.equals(phase);
	}
}
