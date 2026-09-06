package com.xm.thefourthfrequency.terminal;

/**
 * What each kind of press on the terminal sounds like.
 *
 * <p>The panel used to answer almost everything with one contact sound. Moving a highlight had its
 * own lighter cue, and everything else - switching pages, opening a tool, backing out of one,
 * ordering the server to start guiding, clearing an unread marker - shared a single click at a
 * single weight. That is a device with one button on it. A player could hear that <em>something</em>
 * was pressed and never hear <em>what kind</em> of thing had happened, which put the whole burden of
 * telling a page change apart from a committed order on the eyes.
 *
 * <p>So the presses are graded instead, and the grades are meant to be heard rather than read:
 *
 * <ul>
 *   <li><b>Weight rises with consequence.</b> {@link #MOVE} is the lightest thing the panel does and
 *       {@link #COMMIT} - the only voice that means the server was told to do something - is the
 *       heaviest.</li>
 *   <li><b>Direction is audible.</b> {@link #OPEN} sits above {@link #BACK} in pitch, so going a
 *       level in and coming a level out are not the same event with different pixels.</li>
 *   <li><b>Not every voice is a press.</b> {@link #ACKNOWLEDGE} is the unread lamp going out, which
 *       is the machine finishing something rather than the player starting it, and it is the one
 *       voice pitched below everything else.</li>
 * </ul>
 *
 * <p>This class holds only the table. It is here in the common source set, and not next to the
 * client audio that plays it, because the numbers are the part worth pinning down: JUnit can reach
 * this package and cannot reach {@code client_ui}, and a table that drifts back into six values
 * within a hair of each other is a regression no compile catches.
 *
 * <h2>Samples</h2>
 *
 * <p>Nothing here needs a new recording. The device already owns four sample families with
 * genuinely different textures, and picking the right family carries more of the distinction than
 * pitch does - two voices built from the same family have to be separated by pitch and weight, two
 * voices from different families are already unmistakable.
 */
public enum TerminalContactVoice {
	/** Moving a highlight through a list. Not choosing anything, so it stays out of the way. */
	MOVE(Sample.KEY, 1.00F, 0.34F),
	/** Leaving a page for another one: the dullest, heaviest contact, a switch being thrown. */
	TAB(Sample.CONTACT, 0.68F, 0.48F),
	/** One level in - a tool detail, a file body. */
	OPEN(Sample.CONTACT, 1.18F, 0.44F),
	/** One level back out. Deliberately under {@link #OPEN}: the pair is a direction, not a click. */
	BACK(Sample.CONTACT, 0.84F, 0.36F),
	/** The player told the server to do something. The only voice with a bolt in it. */
	COMMIT(Sample.BOLT, 1.14F, 0.52F),
	/** An unread marker clearing. The lamp going out, not a button going down. */
	ACKNOWLEDGE(Sample.NOTCH, 0.60F, 0.22F);

	/**
	 * The recordings the panel already ships, named by what they sound like rather than by the
	 * event they were first written for.
	 */
	public enum Sample {
		/** {@code device/terminal/click} - four light contacts. */
		CONTACT,
		/** {@code device/terminal/password} - a single key. */
		KEY,
		/** {@code device/terminal/tune} - the dial's detents. */
		NOTCH,
		/** {@code device/terminal/lock} - the heaviest thing the device does. */
		BOLT
	}

	/**
	 * The terminal's visual stage runs 0-2; anything above that is the same as two.
	 *
	 * <p>Two, not five. The number the client is handed is {@code TerminalSnapshot.visualStage},
	 * which is {@code PursuitProgressPolicy.terminalVisualStage} - a three-step tier computed from
	 * resolved chases, the allowed form and the anomaly stage, and clamped to 0-2 on the way in.
	 * There are five anomaly stages, but the panel is never told which one it is in; writing this
	 * bound as five would leave two thirds of the range unreachable and the wear per step three
	 * times smaller than it reads.
	 */
	public static final int MAX_STAGE = 2;

	/**
	 * How much of its pitch a contact loses per stage.
	 *
	 * <p>Six percent end to end, about a semitone, spread over three steps. Large enough to survive
	 * a side-by-side recording, small enough that nobody catches it happening - and it cannot be
	 * caught happening in any case, because the stage only ever moves between sessions, so there is
	 * no press where the sound steps.
	 *
	 * <p>Still well under the carrier loop, which drops five percent per step of the same tier: the
	 * noise floor is one continuous sound the ear settles into, while these are short transients
	 * heard dozens of times a session.
	 *
	 * <p>This is the mod's own premise pointed at its own furniture. Nothing is added to the panel
	 * and nothing is taken away; the familiar thing simply stops answering the way it used to. The
	 * device states the resulting figure out loud exactly once per open, in
	 * {@code TerminalSelfTest.Line.BASELINE}, which reads it back off this constant.
	 */
	public static final float WEAR_PER_STAGE = 0.03F;

	private final Sample sample;
	private final float pitch;
	private final float relativeVolume;

	TerminalContactVoice(Sample sample, float pitch, float relativeVolume) {
		this.sample = sample;
		this.pitch = pitch;
		this.relativeVolume = relativeVolume;
	}

	public Sample sample() {
		return sample;
	}

	/** The pitch on a device still at stage zero. */
	public float pitch() {
		return pitch;
	}

	/** Volume relative to the configured peak; the caller still multiplies by the mod's own cap. */
	public float relativeVolume() {
		return relativeVolume;
	}

	/**
	 * The pitch this voice answers with for a holder at {@code stage}.
	 *
	 * @param stage the terminal's visual stage; out-of-range values are clamped rather than refused,
	 *              because a snapshot arriving from a future protocol must not silence the panel
	 */
	public float pitchAt(int stage) {
		return pitch * (1.0F - WEAR_PER_STAGE * Math.clamp(stage, 0, MAX_STAGE));
	}
}
