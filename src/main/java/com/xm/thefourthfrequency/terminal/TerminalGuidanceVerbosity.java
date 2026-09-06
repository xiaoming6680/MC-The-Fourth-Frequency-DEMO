package com.xm.thefourthfrequency.terminal;

/**
 * How much the terminal spells out, for a player who has not met one of these before.
 *
 * <p>One consumer: the unread reminder. "You have 2 unread records" names a place, and the terminal
 * is opened by holding the bound one and right-clicking - which the mod says nowhere outside the
 * first-boot walkthrough. That reminder is also the one line that fires precisely when somebody has
 * not been opening it, so for a first-time player it carries the second half and for a returning one
 * it does not.
 *
 * <h2>Why this is not a difficulty setting</h2>
 *
 * <p>It changes nothing about the world. Same anomalies, same intervals, same intensities, same
 * timers, same rewards; two saves that differ only here are identical everywhere a player could
 * measure them. What differs is half of one sentence. That is presentation, which
 * {@link ProfilePreference} explicitly permits a preference to touch.
 *
 * <p>Which is also the reason it can be safely driven by an answer nobody can revise. Getting it
 * wrong in the terse direction costs a player half a line - exactly what every player had before
 * the question existed - and in the verbose direction costs a returning player half a line they did
 * not need. Neither can lock anyone out of anything.
 */
public enum TerminalGuidanceVerbosity {
	/** Prompts carry the extra half-sentence that says how to act on them. */
	VERBOSE,
	/** Prompts stand on their own. The player said they have met this device before. */
	TERSE;

	/**
	 * Reads the profile, falling back on whether the profile was ever taken at all.
	 *
	 * <p>The fallback is the half that matters, because {@code UNSTATED} means two opposite things
	 * depending on how the save got here. A record written before this question existed belongs to
	 * somebody already playing - possibly for thirty hours - and starting to explain the dial to
	 * them would read as the terminal having forgotten who they are, so those go {@link #TERSE}. A
	 * profile that has not been taken yet belongs to somebody who has not started, and they get
	 * {@link #VERBOSE}: the damage failsafe can release the walkthrough early, and a player who
	 * never reached the question must not be punished for the interruption.
	 *
	 * <p>{@code UNCLEAR} resolves to {@link #VERBOSE}. Somebody who has watched a video knows what
	 * this mod looks like, not which button opens the terminal; the cost of telling them anyway is
	 * half a line they skim, and the cost of not telling them is the thing this class exists for.
	 *
	 * @param answers      the stored profile, normalised - {@code TerminalData#profileAnswers}
	 * @param profileTaken whether this player was ever put through the profile at all
	 */
	public static TerminalGuidanceVerbosity of(int[] answers, boolean profileTaken) {
		return switch (ProfilePreference.priorContact(answers)) {
			case BEFORE -> TERSE;
			case NONE, UNCLEAR -> VERBOSE;
			case UNSTATED -> profileTaken ? TERSE : VERBOSE;
		};
	}

	public boolean explains() {
		return this == VERBOSE;
	}
}
