package com.xm.thefourthfrequency.terminal;

import java.util.List;

/**
 * Reads the first-boot profile back out, as the six things that actually consume it.
 *
 * <p>One place, so no consumer has to know that the answers are an int array or which index a
 * question sits at. Every accessor tolerates {@code UNANSWERED} and says so with its own neutral
 * value: the damage failsafe and a dropped connection both leave real gaps, and a consumer that
 * treated a gap as an answer would be acting on a preference the player never gave.
 *
 * <h2>The line none of these may cross</h2>
 *
 * <p>A preference changes how the terminal presents things, or which of several equally intense
 * things arrives. It never changes how much arrives, how fast, how hard it hits, or how long the
 * player has. The profile is taken in the first minute and can never be revisited, so anything it
 * scaled would be a hidden difficulty setting chosen before the player knew what any of it meant.
 *
 * <p>{@link #priorContact} is the clearest case of the permitted half and is worth reading as the
 * example. It decides whether the unread reminder also says how to open the terminal -
 * presentation, the clause's own first words - and touches nothing about what arrives or when.
 * Anyone comparing two saves will find the same anomalies at the same intervals; one player got
 * half a sentence more on one prompt. That is the whole of its reach, and it is why an answer
 * nobody can revise is not dangerous here: the worst outcome is a first-time player who said
 * "before" and is not told which button opens the thing, which is the experience every player had
 * before the question existed.
 *
 * <p>Pure, on the common side, no Minecraft types. The animal preference is expressed as entity type
 * ids rather than as {@code EntityType} constants precisely so this stays testable without a server.
 */
public final class ProfilePreference {
	private static final int PLAY_STYLE = 0;
	private static final int ANIMAL = 1;
	private static final int FEAR_ALONE = 2;
	private static final int MINING_UNEASE = 3;
	private static final int TRUST_ALLIES = 4;
	private static final int PRIOR_CONTACT = 5;

	public enum PlayStyle { SPEEDRUN, NORMAL, CASUAL, UNSTATED }

	/**
	 * Which animal the player named.
	 *
	 * <p>Deliberately drives nothing in the world. The obvious wiring was to make the herd-turn
	 * anomaly single out that species, and it does not survive contact with an actual save: cats
	 * generate in villages and ocelots in jungles, so a player thirty hours into a world - at their
	 * base, or down a shaft - essentially never has one within range. The rule would have sat there
	 * never firing, and on the occasions it did it would have been dogs, for the players who happened
	 * to have tamed one.
	 *
	 * <p>So this answer is read by the recovered fragment a previous run leaves behind, and by nothing
	 * else. It is the one question whose payoff is deferred to a second playthrough - which is a real
	 * cost, and the alternative was a consumer that only pretended to exist.
	 */
	public enum Animal { CAT, DOG, NEITHER, UNSTATED }

	/** How the player described being alone. Never scales anything - only weights flavour. */
	public enum Solitude { UNTROUBLED, SOMETIMES, TROUBLED, UNSTATED }

	public enum Trust { YES, NO, UNSTATED }

	/**
	 * Whether the player says they have met this model of terminal before.
	 *
	 * <p>The only answer here that is about the device rather than about the player, and the only one
	 * whose consumer is the terminal's own voice rather than the world. {@code UNCLEAR} exists for
	 * the person who has watched somebody else play; see {@link #priorContact} for why it is not
	 * treated as prior contact.
	 */
	public enum PriorContact { NONE, BEFORE, UNCLEAR, UNSTATED }

	private ProfilePreference() {
	}

	public static PlayStyle playStyle(int[] answers) {
		return switch (answer(answers, PLAY_STYLE)) {
			case 0 -> PlayStyle.SPEEDRUN;
			case 1 -> PlayStyle.NORMAL;
			case 2 -> PlayStyle.CASUAL;
			default -> PlayStyle.UNSTATED;
		};
	}

	public static Animal animal(int[] answers) {
		return switch (answer(answers, ANIMAL)) {
			case 0 -> Animal.CAT;
			case 1 -> Animal.DOG;
			case 2 -> Animal.NEITHER;
			default -> Animal.UNSTATED;
		};
	}

	/**
	 * Combines the two questions about being alone into one flavour weight.
	 *
	 * <p>Two questions, one axis: whether solitude is a thing this player reports feeling. They are
	 * merged rather than read separately because no consumer wants to branch on four combinations of
	 * two self-reports, and because "never uneasy underground" and "not frightened alone" are the
	 * same claim made twice.
	 *
	 * <p>What this may do is bias which anomaly is drawn from a pool of equals. What it may never do
	 * is change the size of the pool, the interval between draws, or the intensity of what is drawn.
	 */
	public static Solitude solitude(int[] answers) {
		int fear = answer(answers, FEAR_ALONE);
		int unease = answer(answers, MINING_UNEASE);
		if (fear == TerminalProfileQuestionnaire.UNANSWERED
				&& unease == TerminalProfileQuestionnaire.UNANSWERED) {
			return Solitude.UNSTATED;
		}
		// fear_alone: 0 yes, 1 no, 2 depends. mining_unease: 0 sometimes, 1 often, 2 never.
		int score = 0;
		if (fear == 0) score += 2;
		if (fear == 2) score += 1;
		if (unease == 0) score += 1;
		if (unease == 1) score += 2;
		if (score >= 3) return Solitude.TROUBLED;
		if (score >= 1) return Solitude.SOMETIMES;
		return Solitude.UNTROUBLED;
	}

	public static Trust trust(int[] answers) {
		return switch (answer(answers, TRUST_ALLIES)) {
			case 0 -> Trust.YES;
			case 1 -> Trust.NO;
			default -> Trust.UNSTATED;
		};
	}

	/**
	 * What the player said about having used one of these before.
	 *
	 * <p>{@code UNCLEAR} is deliberately its own constant rather than being folded into either
	 * neighbour here. Reading it as prior contact would take the extra half-line away from somebody
	 * who only ever watched a video, and reading it as none would be discarding an answer they did
	 * give; {@code TerminalGuidanceVerbosity} is where that judgement belongs, because it is the
	 * thing that knows what the cost of being wrong in each direction is.
	 */
	public static PriorContact priorContact(int[] answers) {
		return switch (answer(answers, PRIOR_CONTACT)) {
			case 0 -> PriorContact.NONE;
			case 1 -> PriorContact.BEFORE;
			case 2 -> PriorContact.UNCLEAR;
			default -> PriorContact.UNSTATED;
		};
	}

	/**
	 * Whether this terminal will pass its own record lines to another one.
	 *
	 * <p>An unstated answer does not relay. Consent that was never given is not consent, and the
	 * relay is the one preference here whose subject is somebody other than the player who set it.
	 */
	public static boolean relaysOut(int[] answers) {
		return trust(answers) == Trust.YES;
	}

	/** Whether this terminal will accept lines from another one. */
	public static boolean relaysIn(int[] answers) {
		return trust(answers) != Trust.NO;
	}

	/**
	 * Anomaly ids this player's own answers make more apt, never more frequent.
	 *
	 * <p>Used as a tie-break weight inside a pool that was already assembled by the pacing rules, so
	 * the count and the timing are untouched. A player who said the mines get to them does not get
	 * more anomalies; they get the ones that are about being underground, in place of ones that are
	 * about something else.
	 */
	public static List<String> flavouredAnomalies(int[] answers) {
		return switch (solitude(answers)) {
			case TROUBLED -> List.of("light_dropout", "phantom_echo", "dark_watcher", "silent_world");
			// One entry because the two this line used to name were merged into one anomaly.
			case SOMETIMES -> List.of("phantom_echo");
			case UNTROUBLED -> List.of("watcher_alignment", "action_echo", "organ_misread");
			case UNSTATED -> List.of();
		};
	}

	private static int answer(int[] answers, int questionIndex) {
		if (answers == null || questionIndex < 0 || questionIndex >= answers.length) {
			return TerminalProfileQuestionnaire.UNANSWERED;
		}
		int value = answers[questionIndex];
		return TerminalProfileQuestionnaire.validAnswer(questionIndex, value)
				? value : TerminalProfileQuestionnaire.UNANSWERED;
	}
}
