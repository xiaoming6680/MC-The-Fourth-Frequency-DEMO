package com.xm.thefourthfrequency.terminal;

/**
 * When the terminal a player was just handed is allowed to come up on its own.
 *
 * <p>The first thing that happens in this mod is waking up in the station with a device that has
 * already bound itself to you. Making the player find it in their hotbar and right-click it first
 * puts an inventory lookup in front of the one moment the fiction needs to land unassisted, so the
 * device brings itself up - once, on the join that issues it.</p>
 *
 * <p>Pure and on the common side so every branch is testable without a client. The server decides
 * <em>whether</em> (the join that issues the terminal, and no other), and this decides
 * <em>when</em>. The two are split because only the client knows the answer to the question that
 * actually matters here: is there a world in front of the player right now, or a loading screen?
 * A screen opened under one of those is closed again by vanilla the moment the load finishes, and
 * the player would have seen nothing at all.</p>
 *
 * <p>Nothing here can open a terminal by itself. The client answers {@code OPEN} by sending the
 * same request a right-click sends, which the server validates the same way.</p>
 */
public final class TerminalAutoOpenPolicy {
	/**
	 * The beat between the world appearing and the device coming up.
	 *
	 * <p>One second, and it is not a load guard - {@link #decide} already refuses to count a tick
	 * the player was not being shown the world. It is there because the terminal rising into frame
	 * the same instant the world fades in reads as part of the loading screen rather than as the
	 * device doing something. A moment of the room first is what makes it an event.</p>
	 */
	public static final int SETTLE_TICKS = 20;
	/**
	 * How long the offer stays open, counted in the same shown-world ticks.
	 *
	 * <p>Half a minute. The failure this bounds is the terminal that is never in the player's hand -
	 * dropped because the inventory was full, or swapped away in the first seconds - and the wrong
	 * answer to that is to keep waiting: a screen that opens itself several minutes into play, over
	 * whatever the player is doing by then, is the mod taking the controls rather than greeting
	 * them. It gives up silently instead, and the terminal is still a right-click away.</p>
	 */
	public static final int GIVE_UP_TICKS = 600;

	public enum Decision {
		/** Not yet, and the offer stands. */
		WAIT,
		/** Send the open request now. */
		OPEN,
		/** Drop the offer; this join is not getting one. */
		GIVE_UP
	}

	private TerminalAutoOpenPolicy() {
	}

	/**
	 * @param worldShown       the player is in a world with nothing in front of it - no screen, no
	 *                         overlay - and the safety notice has already been dismissed
	 * @param holdingTerminal  the player's main hand holds a terminal bound to them
	 * @param terminalEngaged  a terminal screen is already up, or already coming up
	 * @param shownTicks       ticks counted since the offer arrived, <em>only</em> those where
	 *                         {@code worldShown} was true - so a slow first world generation costs
	 *                         the offer nothing, and neither does reading the pause menu
	 */
	public static Decision decide(boolean worldShown, boolean holdingTerminal, boolean terminalEngaged,
			int shownTicks) {
		// The player got there first. Their own right-click is the better version of this event, and
		// a second request behind it would only re-open what is already open.
		if (terminalEngaged) return Decision.GIVE_UP;
		if (!worldShown) return Decision.WAIT;
		if (shownTicks >= GIVE_UP_TICKS) return Decision.GIVE_UP;
		if (!holdingTerminal) return Decision.WAIT;
		if (shownTicks < SETTLE_TICKS) return Decision.WAIT;
		return Decision.OPEN;
	}
}
