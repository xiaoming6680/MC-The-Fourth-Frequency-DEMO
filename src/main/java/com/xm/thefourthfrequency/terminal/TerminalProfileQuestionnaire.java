package com.xm.thefourthfrequency.terminal;

/**
 * The first-boot user profile: six questions the terminal asks before it will bind to anybody.
 *
 * <p>Answered on the receiver slider, not on buttons. Each option sits at a frequency; the option
 * text is unreadable until the player tunes near it, and an answer is committed by holding the lock
 * rather than by clicking. The device is not offering a menu, it is asking which station you are.
 *
 * <h2>The tone is deliberate</h2>
 *
 * <p>This is not a settings wizard pretending to be one. It asks about play style, then about cats
 * and dogs, then whether being alone frightens you. A device that only asked about units and
 * notification preferences would be configuring itself; this one is taking a file on you, and it
 * says so by what it wants to know.
 *
 * <p>Which is what makes the closing line land. After six questions, three of which were about how
 * the player feels, the terminal answers with <em>user preferences recorded</em> - and nothing else.
 * It does not respond to any of it. The coldness is the payload; a warmer acknowledgement would
 * spend the whole sequence.
 *
 * <p>Pure and on the common side. The server owns the answers, the walkthrough phase and the latch;
 * the client owns none of it and cannot advance a question on its own.
 */
public final class TerminalProfileQuestionnaire {
	/**
	 * Half-width of the commit window, on the 0-100 tuning scale.
	 *
	 * <p>Not {@code TerminalControlPolicy.RECEIVER_LOCK_RADIUS}, which is 2. The receiver tool wants
	 * a hard target because precise tuning is that tool's actual gameplay; on the 84-pixel track a
	 * radius of 2 is a window of about 3.4 pixels, and putting a narrative beat behind it turns the
	 * opening minute into a test of fine motor control. Six is about 10 pixels: reachable with a
	 * mouse, still narrow enough that the player has to mean it.
	 */
	public static final int LOCK_RADIUS = 6;

	/**
	 * Ticks the lock must be held before the answer commits.
	 *
	 * <p>It is a radio: you hold it on the station. That also removes the entire class of mis-commits
	 * from sweeping across an option, which is why there is no separate confirm button competing for
	 * space on a panel that has none.
	 *
	 * <p>Was one second, and one second was not enough. The questions are the only place in the
	 * mod where the player is asked what they think, and a second is barely time to finish reading
	 * the option you have landed on before it is taken as your answer. Nearly two gives room to
	 * arrive, read, and decide to stay - and the cost of the extra beat is nothing, because there is
	 * nothing else happening on this screen.
	 */
	public static final int COMMIT_HOLD_TICKS = 35;

	/**
	 * How long a player may hunt one question before the terminal starts sweeping for them.
	 *
	 * <p>The walkthrough is allowed to hold the exit only because it has a definite end, so no
	 * question may be a dead end. After fifteen seconds without a single lock the device sweeps to
	 * the nearest option and stops there - the player can accept it or keep tuning. Help, in the
	 * device's own voice, rather than an escape hatch bolted onto the side.
	 */
	public static final long ASSIST_SWEEP_MILLIS = 15_000L;

	/**
	 * How long the closing acknowledgement holds before the walkthrough moves on, in milliseconds.
	 *
	 * <p>Two seconds of the terminal having nothing further to say. The whole sequence is built to
	 * arrive at one flat line - six questions, three of them about how the player feels, answered
	 * with a note that the file has been written - and cutting straight to the tab tour would throw
	 * that away in the frame it lands. It is the same reasoning as the task card's completion hold:
	 * the payoff needs somewhere to be looked at.
	 */
	public static final long RECORDED_HOLD_MILLIS = 2_000L;

	/**
	 * How far every option must sit from where the dial starts.
	 *
	 * <p>Twice the lock radius. Without this the receiver can open already locked onto an answer -
	 * and it did: the dial rests at {@code DEFAULT_TUNING}, and three of the original five questions
	 * had an option within six of it, so the hold began the instant the question appeared and
	 * committed a second later. Three questions answered themselves before the player touched
	 * anything.
	 *
	 * <p>Clearance alone is not the whole fix - see {@code requiresMovement} - but it is the half that
	 * keeps the screen honest. An option the dial is already sitting on is drawn fully legible and
	 * highlighted, which reads as pre-selected however the commit rule behaves.
	 */
	public static final int MIN_DEFAULT_CLEARANCE = LOCK_RADIUS * 2;

	/**
	 * How far away an option starts assembling out of the noise.
	 *
	 * <p>Deliberately much wider than the strength curve's 25. The meter and the text answer
	 * different questions - "is there a station near" versus "what does it say" - and tying the
	 * second to the first made options invisible until the dial was practically on them.
	 *
	 * <p>Thirty-two is a third of the band, which on the widest gap here still leaves a stretch in
	 * the middle where nothing is forming. That gap matters: if every option were legible from
	 * everywhere the dial would stop being how you find them.
	 */
	public static final int REVEAL_RANGE = 32;

	/**
	 * Whether a commit is allowed yet, given whether the dial has been moved on this question.
	 *
	 * <p>The belt to the clearance's braces. Clearance stops the specific arrangement that caused
	 * questions to answer themselves; this stops the whole class of it, including any future question
	 * whose options someone places carelessly. A profile is a record of what the player chose, so it
	 * must not be able to contain something they never did.
	 */
	public static boolean requiresMovement() {
		return true;
	}

	/** Stored answer for a question the player never got to. Never silently replaced by a default. */
	public static final int UNANSWERED = -1;

	/**
	 * Six questions, each with exactly one consumer.
	 *
	 * <p>A question with no consumer is a cutscene wearing a form, and there are none of those here.
	 * The pacing layer reads {@code PLAY_STYLE}, the herd-turn anomaly reads {@code ANIMAL}, the
	 * anomaly selection weights read {@code FEAR_ALONE} and {@code MINING_UNEASE}, the relay reads
	 * {@code TRUST_ALLIES}, and {@code TerminalGuidanceVerbosity} reads {@code PRIOR_CONTACT}.
	 *
	 * <p>{@code ANIMAL} is the one that lands hardest, precisely because it looks like a joke. A form
	 * that asks whether you prefer cats or dogs is not a form anyone braces for - and thirty hours
	 * later, when every animal in the field turns its head at once, the ones that turn are cats.
	 * Nothing was done to the player; they wired it themselves in the first minute.
	 *
	 * <h2>Self-reported fear is not a difficulty dial</h2>
	 *
	 * <p>{@code FEAR_ALONE} and {@code MINING_UNEASE} ask the player to report on themselves, and the
	 * obvious wiring - you said the dark bothers you, so here is more dark - is forbidden. It would
	 * be a hidden difficulty selector chosen in the first minute and never revisitable, which is the
	 * one thing a preference must never become.
	 *
	 * <p>What they may do is pick <em>which</em> of several equally intense things arrives. The
	 * anomaly layer already sorts its catalogue into personal and shared and already prefers personal
	 * entries when someone else is standing nearby; these two answers weight that existing choice.
	 * Same count, same intensity, same pacing - different flavour. The player who said the mines
	 * bother them does not get more anomalies underground, they get the ones that are about being
	 * underground.
	 */
	private static final Question[] QUESTIONS = {
			new Question("play_style", new int[] {8, 37, 88},
					new String[] {"speedrun", "normal", "casual"}),
			new Question("animal", new int[] {12, 64, 94},
					new String[] {"cat", "dog", "neither"}),
			new Question("fear_alone", new int[] {9, 38, 92},
					new String[] {"yes", "no", "depends"}),
			new Question("mining_unease", new int[] {14, 66, 96},
					new String[] {"sometimes", "often", "never"}),
			/*
			 * Two options, not three. The tuning maths does not care - option counts are read from the
			 * array - but the placement does, and it has two rules to satisfy at once: both ends have
			 * to clear MIN_DEFAULT_CLEARANCE so the dial does not open sitting on an answer, and the
			 * midpoint between them still has to read something. 20 and 80 leave the centre thirty
			 * away from either peak, which is past where the station term reaches zero - the needle
			 * lives on the carrier floor there, which is exactly what that floor exists for.
			 */
			new Question("trust_allies", new int[] {20, 80},
					new String[] {"yes", "no"}),
			/*
			 * The one question that is about the player's relationship to this device rather than
			 * about them, and the only one whose consumer is the terminal's own voice: it sets
			 * TerminalGuidanceVerbosity, which decides whether the unread reminder also says how to
			 * open the terminal.
			 *
			 * It sits inside the line ProfilePreference draws rather than across it. Verbosity changes
			 * how the terminal presents things - the class comment's own first clause - and touches
			 * nothing about how much arrives, how fast, how hard, or how long. A player who says they
			 * have met this model before gets the same anomalies at the same rate; they are simply not
			 * told twice which button opens the thing.
			 *
			 * Being unrevisitable is survivable here for the same reason: the worst case is a first-time
			 * player who answered "before" and gets half a line less on one reminder, which is exactly
			 * the experience every player had before this question existed. It cannot lock anybody out
			 * of anything, which is what the never-revisitable rule is actually protecting against.
			 *
			 * Three options, not two. "Unclear" is not padding - it is where the honest answer goes for
			 * someone who has watched somebody else play, and it is read as no prior contact, because
			 * having seen a video is not knowing what the dial does.
			 */
			new Question("prior_contact", new int[] {10, 62, 95},
					new String[] {"none", "before", "unclear"})
	};

	private TerminalProfileQuestionnaire() {
	}

	public static int questionCount() {
		return QUESTIONS.length;
	}

	public static int optionCount(int questionIndex) {
		return valid(questionIndex) ? QUESTIONS[questionIndex].optionIds().length : 0;
	}

	public static boolean valid(int questionIndex) {
		return questionIndex >= 0 && questionIndex < QUESTIONS.length;
	}

	public static boolean validAnswer(int questionIndex, int optionIndex) {
		return valid(questionIndex) && optionIndex >= 0 && optionIndex < optionCount(questionIndex);
	}

	/** Translation key stem for a question, e.g. {@code ...profile.rest_schedule}. */
	public static String questionId(int questionIndex) {
		return valid(questionIndex) ? QUESTIONS[questionIndex].id() : "";
	}

	/** Translation key stem for one option of a question. */
	public static String optionId(int questionIndex, int optionIndex) {
		if (!validAnswer(questionIndex, optionIndex)) return "";
		return QUESTIONS[questionIndex].optionIds()[optionIndex];
	}

	/** Where on the 0-100 scale an option sits. */
	public static int optionTuning(int questionIndex, int optionIndex) {
		if (!validAnswer(questionIndex, optionIndex)) return TerminalControlPolicy.DEFAULT_TUNING;
		return QUESTIONS[questionIndex].tunings()[optionIndex];
	}

	/**
	 * The option the receiver is closest to, or {@code -1} if the question does not exist.
	 *
	 * <p>Ties break low so the answer is a function of the tuning alone; a tie that resolved by
	 * approach direction would make the same slider position mean two different things.
	 */
	public static int nearestOption(int questionIndex, int tuning) {
		if (!valid(questionIndex)) return -1;
		int[] tunings = QUESTIONS[questionIndex].tunings();
		int clamped = TerminalControlPolicy.tuning(tuning);
		int best = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int index = 0; index < tunings.length; index++) {
			int distance = Math.abs(clamped - tunings[index]);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = index;
			}
		}
		return best;
	}

	/**
	 * Signal strength against the nearest option, 1-100. Never zero.
	 *
	 * <p>Two curves, maximum of the two, and they are doing different jobs.
	 *
	 * <p>The <b>station</b> term is {@link TerminalControlPolicy#receiverStrength} verbatim - a sharp
	 * peak that reaches zero 25 away - so the needle behaves exactly like the receiver tool the
	 * player will unlock later whenever they are anywhere near an answer. Sweeping the band makes it
	 * rise and fall once per option, which is the entire tutorial for how a receiver works.
	 *
	 * <p>The <b>carrier</b> term is a shallow floor that never quite reaches zero. It exists because
	 * the sharp curve alone leaves dead ground, and dead ground is not survivable here: the exit is
	 * held during the walkthrough, the strength meter is the only guide the player has, and a needle
	 * pinned at zero offers no direction to move in. Two options cannot cover the band at all under
	 * the sharp curve - each reaches 49 positions and the band is 101 - so this is not something
	 * option placement could have fixed.
	 *
	 * <p>Both terms fall with distance, so their maximum does too: there is always somewhere uphill.
	 *
	 * <p>Note that {@link #settleProgress} deliberately does <em>not</em> use this. The needle is
	 * allowed to acknowledge a station from across the band; the text is not. If option text tracked
	 * this curve, every option would be faintly legible everywhere and there would be nothing to tune
	 * onto.
	 */
	public static int strength(int questionIndex, int tuning) {
		int nearest = nearestOption(questionIndex, tuning);
		if (nearest < 0) return 0;
		int distance = Math.abs(TerminalControlPolicy.tuning(tuning) - optionTuning(questionIndex, nearest));
		int station = Math.clamp(100 - distance * 4, 0, 100);
		int carrier = Math.clamp(25 - distance / 4, 1, 100);
		return Math.max(station, carrier);
	}

	/**
	 * How far an option's text has come out of the noise, for {@link TerminalGlyphSettle}.
	 *
	 * <p>On its own curve rather than the needle's. The needle is an instrument reading and falls off
	 * sharply so that each station is a distinct peak; the text is what the player is trying to
	 * <em>find</em>, and borrowing that sharpness meant an option stayed pure noise until the dial
	 * was almost on top of it. Sweeping the band then felt like hunting in the dark rather than like
	 * closing on something - a player could pass an answer without ever seeing it form.
	 *
	 * <p>So the reveal reaches further than the meter does. An option begins to assemble from
	 * {@link #REVEAL_RANGE} away, which on every question here is far enough to be visible before the
	 * dial arrives. Full legibility still belongs to the lock and to nothing else: this returns 1
	 * inside the radius and strictly less than 1 everywhere outside it, so reading an answer off is
	 * still something you have to commit to being on top of.
	 */
	public static double settleProgress(int questionIndex, int tuning, int optionIndex) {
		if (!validAnswer(questionIndex, optionIndex)) return 0.0D;
		int distance = Math.abs(TerminalControlPolicy.tuning(tuning) - optionTuning(questionIndex, optionIndex));
		if (distance <= LOCK_RADIUS) return 1.0D;
		if (distance >= REVEAL_RANGE) return 0.0D;
		// Scaled so the first step outside the lock radius is already below 1: the jump to full
		// legibility must happen at the lock and nowhere else.
		double outside = (distance - LOCK_RADIUS) / (double) (REVEAL_RANGE - LOCK_RADIUS);
		return Math.clamp(1.0D - outside, 0.0D, 1.0D) * 0.92D;
	}

	/** The option the receiver is locked onto, or {@code -1} if it is between stations. */
	public static int lockedOption(int questionIndex, int tuning) {
		int nearest = nearestOption(questionIndex, tuning);
		if (nearest < 0) return -1;
		int distance = Math.abs(TerminalControlPolicy.tuning(tuning) - optionTuning(questionIndex, nearest));
		return distance <= LOCK_RADIUS ? nearest : -1;
	}

	/** Whether a lock held for this many ticks has earned a commit. */
	public static boolean commits(int heldTicks) {
		return heldTicks >= COMMIT_HOLD_TICKS;
	}

	/** Whether the terminal should take over and sweep to the nearest option. */
	public static boolean assistDue(long millisOnQuestion, boolean everLocked) {
		return !everLocked && millisOnQuestion >= ASSIST_SWEEP_MILLIS;
	}

	/** Whether every question has a real answer. A single {@link #UNANSWERED} makes this false. */
	public static boolean complete(int[] answers) {
		if (answers == null || answers.length != QUESTIONS.length) return false;
		for (int index = 0; index < answers.length; index++) {
			if (!validAnswer(index, answers[index])) return false;
		}
		return true;
	}

	private record Question(String id, int[] tunings, String[] optionIds) {
	}
}
