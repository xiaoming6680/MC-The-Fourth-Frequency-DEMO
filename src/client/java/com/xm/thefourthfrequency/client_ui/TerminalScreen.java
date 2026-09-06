package com.xm.thefourthfrequency.client_ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.networking.TerminalLogEntryPayload;
import com.xm.thefourthfrequency.networking.TerminalFilePayload;
import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import com.xm.thefourthfrequency.terminal.SkyInstrumentPolicy;
import com.xm.thefourthfrequency.terminal.TerminalControlPolicy;
import com.xm.thefourthfrequency.terminal.TerminalGlyphSettle;
import com.xm.thefourthfrequency.terminal.TerminalMotion;
import com.xm.thefourthfrequency.terminal.TerminalOnboardingPolicy;
import com.xm.thefourthfrequency.terminal.TerminalSelfTest;
import com.xm.thefourthfrequency.terminal.TerminalOnboardingTransition;
import com.xm.thefourthfrequency.terminal.OscilloscopeWaveformPolicy;
import com.xm.thefourthfrequency.terminal.TerminalPage;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import com.xm.thefourthfrequency.terminal.TerminalRecordPolicy;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalStructureTarget;
import com.xm.thefourthfrequency.terminal.TerminalTaskService;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.terminal.TerminalUiLayout;
import com.xm.thefourthfrequency.terminal.TerminalUnreadPolicy;
import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import com.xm.thefourthfrequency.terminal.TuningTransition;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// These static imports are also the anchor for the palette contract: ResourceContractTest asserts
// that this file's text contains "TerminalVisualTheme.GREEN" and eight more like it, and these
// lines are the only place those substrings appear. An "optimize imports" pass that drops one
// turns the contract red without touching a single pixel.
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.ALERT_BACKGROUND;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.AMBER;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.BRASS_BEZEL;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.CARD_BODY;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.CARD_BODY_FOCUSED;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.CLAIMABLE;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.COMPASS_FACE;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.CYAN;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.DARK_BORDER;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.DIM;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.GLASS;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.GLASS_BACKDROP;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.GREEN;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.HOT;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.INSTRUMENT_WELL;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.LCD_BACKGROUND;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.LCD_BORDER;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.MUTED;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.MUTED_DARK;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.PROGRESS_TRACK;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.READING_META;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.READING_TEXT;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.READING_TITLE;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.REWARD_SLOT;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.SCOPE_BACKGROUND;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.SCOPE_GRID;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.SCOPE_HIGHLIGHT;
import static com.xm.thefourthfrequency.client_ui.TerminalVisualTheme.SELECTED;
import static com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy.animatedProbeDots;
import static com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy.corruptNavigationName;
import static com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy.sideRouteGlitchActive;
import static com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy.targetNeedleVisible;

public final class TerminalScreen extends Screen {
	private static final int BASE_WIDTH = 512;
	private static final int BASE_HEIGHT = 256;
	private static final long TRANSITION_MILLIS = 160L;
	private static final long WAVE_MORPH_MILLIS = 260L;
	private static final long WAVE_COLOR_MILLIS = 180L;
	private static final long FILE_UNLOCK_FADE_MILLIS = 1_000L;
	private static final int ROW_HEIGHT = 10;
	private static final int RECORD_INDENT = 6;
	private static final int TOOL_DETAIL_ROW_HEIGHT = 12;
	private static final int TOOL_DETAIL_SCROLLBAR_GUTTER = 7;
	/** Below this the pixel font stops being legible, so a label is ellipsized instead of shrunk. */
	private static final float MIN_FITTED_SCALE = 0.62F;
	private static final int OBJECTIVE_FIRST_ROW_Y = 19;
	private static final int OBJECTIVE_ROW_HEIGHT = 11;
	private static final int OBJECTIVE_MAX_ROWS = 2;
	/**
	 * How long the finished task stays on the home card before the next one takes its place.
	 *
	 * <p>Without it the card never showed a completed task at all. The reward is delivered in the
	 * same server tick the last unit of progress lands, and the snapshot that reports the delivery
	 * already names the <em>next</em> task - so the bar the player was watching was replaced by an
	 * empty one for an objective they had not read yet, while the reward arrived in their inventory
	 * with nothing on screen accounting for it. That is most visible on the very first task, which
	 * completes inside the first-boot walkthrough: four tabs clicked, six bread from nowhere.</p>
	 *
	 * <p>Three seconds, and deliberately longer than the walkthrough's own 40-tick closing note, so
	 * the two are on screen together rather than in sequence.</p>
	 */
	private static final int COMPLETION_HOLD_TICKS = 60;
	/** Gap between a record's text and the navigation shortcut that follows it on the same line. */
	private static final int RECORD_SHORTCUT_GAP = 5;
	/** Indent for a shortcut that had to wrap onto its own row, so it reads as belonging above. */
	private static final int RECORD_SHORTCUT_INDENT = 14;
	/** Distance from the dial centre to the middle of a cardinal label, clear of the bezel. */
	private static final int COMPASS_LABEL_RADIUS = 19;
	private static final int FILE_TEXT_INSET = 8;
	private static final int FILE_SCROLLBAR_GUTTER = 7;
	private static final float FILE_TITLE_SCALE = 0.90F;
	private static final float FILE_BODY_SCALE = 0.78F;
	private static final float FILE_NOTE_SCALE = 0.68F;
	private static final int FILE_TITLE_ROW_HEIGHT = 11;
	private static final int FILE_BODY_ROW_HEIGHT = 9;
	private static final int FILE_NOTE_ROW_HEIGHT = 8;
	private static final int FILE_TITLE_GAP = 5;
	private static final int FILE_PARAGRAPH_GAP = 4;
	private static final int WAVE_SAMPLES = 48;
	/**
	 * How much of the ECG waveform is mixed into the carrier trace at each terminal visual stage.
	 *
	 * <p>Named rather than written inline because the client suite asserts the mix the player is
	 * looking at, and a copied literal there goes stale silently the moment the shape changes.
	 * Which stage the panel is at is decided on the server by
	 * {@code PursuitProgressPolicy#terminalVisualStage}; these only say what each stage looks
	 * like.</p>
	 */
	public static final double STAGE_ONE_WAVEFORM_MORPH = 0.42D;
	public static final double STAGE_TWO_WAVEFORM_MORPH = 1.0D;
	/** A locked nearby carrier overrides the stage mix while the dial is inside its window. */
	public static final double RECEIVER_LOCK_WAVEFORM_MORPH = 0.78D;
	/** Floor between sky-monitor one-shots, so back-to-back bursts cannot stack into a wall. */
	private static final int SKY_CUE_COOLDOWN_TICKS = 20;

	private TerminalSnapshot snapshot;
	private TerminalToolSnapshot tools = TerminalToolSnapshot.empty();
	private int mode;
	private TerminalPage page;
	private TerminalTool selectedTool;
	private int tuning;
	private int age;
	private double renderAge;
	/** Ticks spent at sky-monitor stage 2 or worse, which is what the fault flood grows from. */
	private int skyFloodTicks;
	private int skyStage;
	private int skyCueCooldown;
	private double unreadFlashStartedAt;
	private double unreadFileFlashStartedAt;
	private long renderNowMillis;
	/** Every in-flight transition on this screen. See {@link TerminalMotionState}. */
	private final TerminalMotionState motion = new TerminalMotionState();
	private final TerminalOnboardingOverlay onboardingOverlay = new TerminalOnboardingOverlay();
	private TerminalOnboardingPolicy.Phase onboardingPhase = TerminalOnboardingPolicy.Phase.DONE;
	private final TerminalSelfTestOverlay selfTestOverlay = new TerminalSelfTestOverlay();
	/** Whether this open runs the short power-on check at all. */
	private boolean selfTestArmed;
	/** When it started, or -1 while it is armed and has not been drawn a first frame yet. */
	private long selfTestStartedAtMillis = -1L;
	private boolean selfTestSkipped;
	private int profileQuestionShown = -1;
	private long profileQuestionStartedAtMillis;
	private int profileHeldTicks;
	private int profileHeldOption = -1;
	private boolean profileEverLocked;
	/** Whether the dial has moved since this question appeared. No movement, no commit. */
	private boolean profileTuned;
	/** Which first-boot scene was drawn last, so the seam can notice when it changes. */
	private int onboardingSceneKey = Integer.MIN_VALUE;
	private boolean onboardingAdvanceHovered;
	private long onboardingSceneStartedAtMillis;
	/** When the closing acknowledgement started. Zero until the server says the profile is taken. */
	private long profileRecordedAtMillis;
	private long onboardingStartedAtMillis;
	private long onboardingReleasedAtMillis = -1L;
	private int onboardingDoneAtAge = Integer.MIN_VALUE;
	/** Last sampled health, so the failsafe also catches damage that arrives without a hurt animation. */
	private float lastKnownHealth = Float.NaN;
	private final TuningTransition tuningTransition;
	private final TuningTransition waveformMorphTransition;
	private final double[] waveFromSamples = new double[WAVE_SAMPLES];
	private int waveFromColor = GREEN;
	private int waveTargetColor = GREEN;
	private long waveColorStartedAtMillis;
	private boolean draggingTuner;
	private boolean closedByServer;
	private TerminalNavigationPayload navigation = new TerminalNavigationPayload(
			TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION, 0, false, false, 0, 0, 0, 0.0F);
	private boolean navigationInitialized;
	private double facingNeedle;
	private double facingNeedleTarget;
	private double mineralNeedle;
	private double mineralNeedleTarget;
	private double displayedObjectiveFraction;
	private double targetObjectiveFraction;
	private String animatedObjectiveId;
	/** The task that has just been finished and paid for, held on the card. Null the rest of the time. */
	private CompletedTask completedTask;
	private int completedTaskUntilAge;
	private boolean toolOpenedFromHome;
	private TerminalTool homeLiveTool;
	private boolean localNavigationTargetChosen;
	private boolean initialRecordsAcknowledged;
	/** Set when the read acknowledgement is sent, so the marker clears without a round trip. */
	private boolean recordsAcknowledged;
	private boolean filesAcknowledged;
	private int hoveredToolSlot = -1;
	/** Locked tool whose unlock condition stays on the grid after a click, until another is picked. */
	private TerminalTool lockedHintTool;
	private boolean localTuningOnly;
	private double navigationNeedleFlashStartedAt = -100.0D;

	private LogView logView = LogView.DIRECTORY;
	private TerminalFilePayload detailFile;
	private int recordsScrollRow;
	private int toolDetailScroll;
	private int fileListScroll;
	private int fileContentScroll;
	private int selectedFile = -1;
	private String selectedFileId = "";
	private int hoveredFile = -1;
	private long diaryUnlockStartedAtMillis = -1L;
	private final List<NavigationHit> navigationHits = new ArrayList<>();
	/** Click regions for the shortcut that turns an optional-investigation record into an action. */
	private final List<NavigationHit> recordNavigationHits = new ArrayList<>();
	/**
	 * Wrapped rows for the two scrolling surfaces, rebuilt only when their input changes.
	 *
	 * <p>They exist so a scroll maximum is a property of the content rather than a side effect of
	 * having drawn it. While both maxima were assigned inside the draw call, the first wheel notch or
	 * page-down after switching pages clamped against whatever the previous page had left behind, so
	 * a freshly opened Records list would refuse to move until it had rendered once. Re-wrapping every
	 * frame was also the largest per-frame allocation on this screen: up to 128 records through
	 * {@code font.split} at the display refresh rate.</p>
	 */
	private List<RecordRow> cachedRecordRows;
	/** Armed on leaving Records, spent on arriving, so the settle plays once per visit. */
	private boolean recordsSettleArmed = true;
	private boolean previousRunReported;
	private long recordsSettleStartMillis;
	/**
	 * Per-screen noise seed for the records settle.
	 *
	 * <p>Fixed for the life of one open terminal so a line resolves the same way every frame, and
	 * different between opens so re-reading the archive is not a recording of the last time.
	 */
	private final long recordsSettleSeed = System.nanoTime();
	private List<FileRow> cachedFileRows;

	public TerminalScreen(TerminalSnapshotPayload payload) {
		super(Component.translatable("screen.thefourthfrequency.terminal"));
		this.snapshot = new TerminalSnapshot(payload);
		this.mode = snapshot.mode();
		this.page = TerminalPage.fromIndex(snapshot.initialPage());
		this.tuning = snapshot.tuning();
		this.tuningTransition = new TuningTransition(tuning, TRANSITION_MILLIS);
		this.waveformMorphTransition = new TuningTransition(
				waveformMorphTarget(tuning), WAVE_MORPH_MILLIS);
		this.waveFromColor = signalColor(tuning);
		this.waveTargetColor = waveFromColor;
		this.displayedObjectiveFraction = snapshot.objectiveFraction();
		this.targetObjectiveFraction = displayedObjectiveFraction;
		this.animatedObjectiveId = snapshot.objectiveId();
		// While learn_terminal is the current task, its progress is the count of tabs already
		// visited - so it doubles as the walkthrough's resume point and no extra wire field is
		// needed after a disconnect. Past that task the walkthrough is over regardless.
		int visitedTabs = snapshot.objectiveId().equals("learn_terminal")
				? snapshot.objectiveProgress() : TerminalTaskService.PAGE_COUNT;
		this.onboardingPhase = TerminalOnboardingPolicy.initial(snapshot.onboardingRequired(), visitedTabs,
				profileTaken());
		// Kept for the ending, which happens long after every terminal has been surrendered to the core.
		PreviousRunClient.rememberProfile(snapshot);
		this.onboardingStartedAtMillis = nowMillis();
		// The walkthrough opens with a self test of its own, six lines long. Running the short one
		// as well would put two power-on checks back to back on the one boot that already has one.
		this.selfTestArmed = TerminalSelfTest.playsOnOpen(onboardingLocksExit());
	}

	private boolean onboardingLocksExit() {
		return TerminalOnboardingPolicy.locksExit(onboardingPhase);
	}

	/**
	 * Whether the short power-on check still owns the page area.
	 *
	 * <p>It owns nothing else. The tabs, the status bar and the hardware column keep drawing and
	 * keep taking input throughout, and the exit is never held - {@link #shouldCloseOnEsc} is not
	 * consulted here and must never learn about this. The first-boot walkthrough is the only thing
	 * in this mod allowed to take the way out, and it is allowed because it happens once; a check
	 * that runs on every open would take it hundreds of times.
	 */
	private boolean selfTestRunning() {
		if (!selfTestArmed || selfTestSkipped) return false;
		// Armed but never drawn: the clock starts on the first frame, not in the constructor. A
		// screen can be built and then sit behind something before it is ever shown, and half a
		// second is short enough that it would simply run out unseen.
		if (selfTestStartedAtMillis < 0L) return true;
		return !TerminalSelfTest.finished(selfTestElapsedMillis());
	}

	private long selfTestElapsedMillis() {
		if (selfTestStartedAtMillis < 0L) return 0L;
		long now = renderNowMillis > 0L ? renderNowMillis : nowMillis();
		return now - selfTestStartedAtMillis;
	}

	/**
	 * Whether the server considers this terminal's profile finished.
	 *
	 * <p>Read off the question index rather than carried as its own flag: the server sends {@code -1}
	 * exactly when there is no question to ask, so the two can never disagree about whether the
	 * profile is over.
	 */
	private boolean profileTaken() {
		return snapshot.profileQuestion() < 0;
	}

	/**
	 * Whether the profile owns the screen, including the two seconds it spends acknowledging.
	 *
	 * <p>Not gated on there still being a question. The server drops {@code profileQuestion} to
	 * {@code -1} the instant the last answer lands, and if that also ended this the closing line
	 * would never be drawn at all.
	 */
	private boolean profileActive() {
		return onboardingPhase == TerminalOnboardingPolicy.Phase.PROFILE;
	}

	/** Whether there is still a question on screen, as opposed to the closing acknowledgement. */
	private boolean profileAsking() {
		return profileActive() && snapshot.profileQuestion() >= 0;
	}

	/** Whether every question actually got an answer, as opposed to being ended by the failsafe. */
	private boolean profileComplete() {
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			if (!TerminalProfileQuestionnaire.validAnswer(question, snapshot.profileAnswer(question))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One tick of the profile: hold-to-commit, and the assist sweep for a player who found nothing.
	 *
	 * <p>Nothing here decides an answer. Holding a lock for a second asks the server to record one and
	 * the server advances its own question; if it declines, the next snapshot simply still shows the
	 * question it showed before. The client cannot move the profile forward on its own, which is the
	 * same rule the four tab steps already live under.
	 */
	private void tickProfile() {
		int question = snapshot.profileQuestion();
		if (question < 0) return;
		if (question != profileQuestionShown) {
			profileQuestionShown = question;
			profileQuestionStartedAtMillis = nowMillis();
			profileHeldTicks = 0;
			profileHeldOption = -1;
			profileEverLocked = false;
			profileTuned = false;
		}
		int locked = TerminalProfileQuestionnaire.lockedOption(question, tuning);
		// Nothing commits until the player has actually turned the dial on this question. The option
		// placement already keeps the resting position clear of every answer, but that is an
		// arrangement and this is a rule: a profile is a record of what somebody chose, so it must
		// not be able to contain something they never did.
		if (TerminalProfileQuestionnaire.requiresMovement() && !profileTuned) {
			profileHeldTicks = 0;
			profileHeldOption = -1;
		} else if (locked < 0) {
			profileHeldTicks = 0;
			profileHeldOption = -1;
		} else if (locked != profileHeldOption) {
			// Arriving on a station, in the receiver's own vocabulary. Not a detent: setTuning already
			// clicks one per notch of movement, and a second click on the same frame would read as the
			// dial having moved twice. The lock tone is also the one the receiver tool uses when it
			// catches a target, which is the whole point - this is that instrument, before the player
			// has been given it.
			profileHeldOption = locked;
			profileHeldTicks = 0;
			profileEverLocked = true;
			TerminalClientAudio.lock();
		} else if (TerminalProfileQuestionnaire.commits(++profileHeldTicks)) {
			profileHeldTicks = 0;
			profileHeldOption = -1;
			TerminalClientAudio.noticeStable();
			send(TerminalControlPayload.ANSWER_PROFILE, locked);
		}
		// No question may be a dead end: the walkthrough is only allowed to hold the exit because it
		// ends. A player who has never locked anything gets the device sweeping to the nearest option
		// for them - they can accept it or keep tuning, but they are no longer stuck.
		if (TerminalProfileQuestionnaire.assistDue(nowMillis() - profileQuestionStartedAtMillis,
				profileEverLocked)) {
			int nearest = TerminalProfileQuestionnaire.nearestOption(question, tuning);
			if (nearest >= 0) {
				tuning = TerminalProfileQuestionnaire.optionTuning(question, nearest);
				retargetTuningVisual(tuning, nowMillis());
				// Counts as movement, because at this point the device has taken the dial on purpose.
				// Without this the sweep would park on an option and then refuse to commit it, which
				// would turn the one escape from a stuck question into a dead end - the exact thing it
				// exists to prevent. The player still has the hold to move off it.
				profileTuned = true;
			}
		}
	}

	/** Whether the walkthrough is currently writing into the status strip instead of the readout. */
	private boolean onboardingOwnsStatusStrip() {
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.BOOT) return false;
		if (onboardingLocksExit()) return true;
		return onboardingDoneAtAge != Integer.MIN_VALUE
				&& age - onboardingDoneAtAge < TerminalOnboardingPolicy.DONE_LINGER_TICKS;
	}

	/**
	 * Answers an input the walkthrough will not act on.
	 *
	 * <p>It answers rather than ignores. The close hint already says the exit is held, and a refused
	 * press that made no sound at all would read as the terminal having stopped responding.</p>
	 */
	private void refuseOnboardingInput() {
		TerminalClientAudio.fault();
	}

	/**
	 * Lets go of the exit early, keeping the walkthrough's progress.
	 *
	 * <p>The terminal does not pause the world, so a held exit during a fight means the player cannot
	 * fight back. Any damage at all releases it - the walkthrough is worth a few seconds of a calm
	 * first morning, not a death.</p>
	 */
	private void releaseOnboarding() {
		if (!onboardingLocksExit()) return;
		onboardingPhase = TerminalOnboardingPolicy.Phase.RELEASED;
		onboardingReleasedAtMillis = nowMillis();
		refuseOnboardingInput();
	}

	/**
	 * Advances the self test and runs the damage failsafe.
	 *
	 * <p>On the tick rather than in render: {@code hurtTime} counts down over ten ticks, so sampling
	 * per frame would read one hit as many, while twenty samples a second has ten times the margin it
	 * needs to catch every one.</p>
	 */
	private void tickOnboarding() {
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.DONE) return;
		onboardingPhase = TerminalOnboardingPolicy.afterBoot(onboardingPhase,
				nowMillis() - onboardingStartedAtMillis, profileTaken());
		// The server owns when the profile is over, so this only ever reads the answer back. A client
		// that decided for itself would be a way to skip the questions by claiming they were done.
		//
		// The hold is client-side because it is presentation only: the answers are already recorded
		// and nothing the player does during it can change them. Delaying the phase rather than
		// drawing over it keeps the exit held for those two seconds, which is what stops the
		// acknowledgement from being something a player can walk out of half-read.
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.PROFILE && profileTaken()
				&& profileRecordedAtMillis == 0L) {
			profileRecordedAtMillis = nowMillis();
		}
		if (profileRecordedAtMillis == 0L
				|| nowMillis() - profileRecordedAtMillis >= TerminalProfileQuestionnaire.RECORDED_HOLD_MILLIS) {
			onboardingPhase = TerminalOnboardingPolicy.afterProfile(onboardingPhase, profileTaken());
		}
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.PROFILE) tickProfile();
		if (!onboardingLocksExit()) return;
		var player = minecraft == null ? null : minecraft.player;
		if (player == null) {
			releaseOnboarding();
			return;
		}
		// hurtTime covers every source, including hits that cost no health - a blocked or absorbed
		// blow still means something is attacking and the player needs to be able to leave.
		boolean hit = player.hurtTime > 0 || player.isDeadOrDying()
				|| (!Float.isNaN(lastKnownHealth) && player.getHealth() < lastKnownHealth - 0.01F);
		lastKnownHealth = player.getHealth();
		if (hit) releaseOnboarding();
	}

	/** True for a short window after an early release, so the hint can explain what changed. */
	private boolean recentlyReleased() {
		return onboardingReleasedAtMillis >= 0L
				&& renderNowMillis - onboardingReleasedAtMillis < TerminalOnboardingPolicy.RELEASED_HINT_MILLIS;
	}

	private Component closeHintText() {
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.DONE && !recentlyReleased()) {
			return Component.translatable("terminal.thefourthfrequency.close_hint");
		}
		return TerminalOnboardingOverlay.closeHint(onboardingPhase, recentlyReleased());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// The formal gate vanilla's Escape handling consults before closing a screen.
		return !onboardingLocksExit();
	}

	@Override
	protected void init() {
		super.init();
		// Tells the server whether this machine holds a previous run, so it can file the recovered
		// fragment - or drop it again after an F8 reset. Only the existence travels; the words never do.
		if (!previousRunReported) {
			previousRunReported = true;
			send(TerminalControlPayload.REPORT_PREVIOUS_RUN, PreviousRunClient.present() ? 1 : 0);
		}
		if (!initialRecordsAcknowledged && page == TerminalPage.RECORDS) {
			initialRecordsAcknowledged = true;
			send(TerminalControlPayload.VISIT_PAGE, TerminalPage.RECORDS.ordinal());
			if (snapshot.unreadCount() > 0) {
				send(TerminalControlPayload.MARK_RECORDS_READ, 0);
				recordsAcknowledged = true;
			}
		}
	}

	/**
	 * Whether the tab should still be drawn as having unread records.
	 *
	 * <p>Answers no from the moment the acknowledgement is sent, rather than waiting for the snapshot
	 * that confirms it. The mark is a packet: the server clears the flag and sends a fresh snapshot,
	 * and until that lands - a round trip later - the page the player is currently reading is still
	 * drawn with its unread marker lit. On a local server that is a flicker; on a real one it reads
	 * as the terminal refusing to acknowledge what is on screen.
	 *
	 * <p>Only ever hides a marker the player has already been shown the contents of, and any genuinely
	 * new record arriving afterwards clears the latch through {@link #update}.
	 */
	private boolean showsUnreadRecords() {
		return !recordsAcknowledged && snapshot.unreadCount() > 0;
	}

	private boolean showsUnreadFiles() {
		return !filesAcknowledged && snapshot.unreadFileCount() > 0;
	}

	public void update(TerminalSnapshotPayload payload) {
		TerminalSnapshot next = new TerminalSnapshot(payload);
		// A record that arrives after the acknowledgement is genuinely new, so the optimistic clear
		// has to end here or it would hide everything that came in while the page stayed open.
		if (next.unreadCount() > snapshot.unreadCount()) recordsAcknowledged = false;
		if (next.unreadFileCount() > snapshot.unreadFileCount()) filesAcknowledged = false;
		TerminalFilePayload previousDiary = snapshot.files().stream()
				.filter(file -> file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID)).findFirst().orElse(null);
		TerminalFilePayload nextDiary = next.files().stream()
				.filter(file -> file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID)).findFirst().orElse(null);
		if (previousDiary != null && nextDiary != null && !previousDiary.unlocked() && nextDiary.unlocked()) {
			diaryUnlockStartedAtMillis = nowMillis();
		}
		if (next.unreadCount() > snapshot.unreadCount()) unreadFlashStartedAt = age;
		if (next.unreadFileCount() > snapshot.unreadFileCount()) unreadFileFlashStartedAt = age;
		long nowMillis = nowMillis();
		int nextTuning = next.tuning();
		if (nextTuning != tuning && (!localTuningOnly || receiverGameplayActive())) {
			retargetTuningVisual(nextTuning, nowMillis);
		}
		// The objective index only ever moves forward, and only once a task's reward has actually been
		// handed over - so an index that grew is the server saying "the task you were looking at is
		// finished and paid for". That is the one thing the card had no way to know: it saw the new
		// task's zeroed line and nothing else. Everything shown during the hold comes from the
		// outgoing snapshot, which is the completed task's own data.
		if (next.objectiveIndex() > snapshot.objectiveIndex()) {
			completedTask = new CompletedTask(snapshot.completedObjectiveLine(), snapshot.objectiveReward());
			completedTaskUntilAge = age + COMPLETION_HOLD_TICKS;
			// Adopted now so the id-change branch below cannot zero the bar out from under the hold.
			animatedObjectiveId = next.objectiveId();
		} else if (!next.objectiveId().equals(animatedObjectiveId)) {
			animatedObjectiveId = next.objectiveId();
			displayedObjectiveFraction = 0.0D;
		}
		// While the finished task is held the bar belongs to it, and it finishes filling rather than
		// jumping - the last step of the fill is the payoff the hold exists to show.
		targetObjectiveFraction = completedTask != null ? 1.0D : next.objectiveFraction();
		snapshot = next;
		// Both wrapped-row caches read straight off the snapshot, so a new one retires them.
		cachedRecordRows = null;
		cachedFileRows = null;
		if (!selectedFileId.isEmpty()) {
			selectedFile = indexOfFile(selectedFileId);
			if (detailFile != null) {
				setDetailFile(snapshot.files().stream().filter(file -> file.id().equals(selectedFileId))
						.findFirst().orElse(null));
			}
		}
		mode = next.mode();
		waveformMorphTransition.retarget(waveformMorphTarget(tuning), nowMillis);
		retargetSignalColor(signalColor(tuning), nowMillis);
	}

	public void updateNavigation(TerminalNavigationPayload payload) {
		if (payload.protocolVersion() != TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION) {
			throw new IllegalStateException("Terminal navigation protocol mismatch: server=" + payload.protocolVersion()
					+ ", client=" + TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION);
		}
		navigation = payload;
		facingNeedleTarget = TerminalNavigationMath.facingNeedleDegrees(payload.playerYaw());
		if (payload.navigable()) {
			mineralNeedleTarget = TerminalNavigationMath.targetNeedleDegrees(
					payload.targetDx(), payload.targetDz());
		}
		if (!navigationInitialized) {
			navigationInitialized = true;
			facingNeedle = facingNeedleTarget;
			mineralNeedle = mineralNeedleTarget;
		}
	}

	public void updateTools(TerminalToolSnapshotPayload payload) {
		boolean gameplayBefore = receiverGameplayActive();
		tools = new TerminalToolSnapshot(payload);
		// Records are gated on owning the navigator, which is a tool-snapshot fact.
		cachedRecordRows = null;
		TerminalTool activeGuidance = tools.guidanceTool();
		if (activeGuidance != null) homeLiveTool = activeGuidance;
		else if (homeLiveTool != TerminalTool.WEATHER) homeLiveTool = null;
		localNavigationTargetChosen = tools.selectedNavigationTarget() != TerminalStructureTarget.NONE;
		if (selectedTool != null && !tools.available(selectedTool)) clearSelectedTool(false);
		if (!gameplayBefore && receiverGameplayActive() && localTuningOnly) {
			localTuningOnly = false;
			if (snapshot.tuning() != tuning) retargetTuningVisual(snapshot.tuning(), nowMillis());
			TerminalClientAudio.signalSweep();
		}
		long nowMillis = nowMillis();
		waveformMorphTransition.retarget(waveformMorphTarget(tuning), nowMillis);
		retargetSignalColor(signalColor(tuning), nowMillis);
	}

	public void closeFromServer() {
		closedByServer = true;
		onClose();
	}

	@Override
	public void tick() {
		age++;
		facingNeedle = TerminalNavigationMath.interpolateDegrees(facingNeedle, facingNeedleTarget, 0.35D);
		if (navigation.navigable()) {
			mineralNeedle = TerminalNavigationMath.interpolateDegrees(mineralNeedle, mineralNeedleTarget, 0.35D);
		}
		tickSkyInstrument();
		tickOnboarding();
		// Idempotent: starts the carrier the first time and only retunes it afterwards. Driven from
		// the tick rather than from init so that it follows the stage the server is currently
		// reporting, and so there is no ordering question about whether a snapshot had arrived yet.
		TerminalClientAudio.carrierOn(snapshot.visualStage());
		tickCompletionHold();
		tickUnreadAcknowledgement();
		TerminalClientAudio.tick();
	}

	/**
	 * Acknowledges an unread marker whose contents the player is already looking at.
	 *
	 * <p>Without this, a record arriving while the player sits on the Records page lights the tab
	 * and the handheld lamp for something whose text is on screen in front of them, and only a
	 * click on the tab they are already on will put it out. The acknowledgement is the same packet
	 * that click sends.</p>
	 *
	 * <p>Gated on the scrolled viewport rather than the page, because "the player is on the page"
	 * is not the same claim as "the player has been shown this". A long log scrolled away from the
	 * new line, or a directory row below the fold, stays unread until it is actually brought into
	 * view - the marker is then still doing its job, which is to say there is something here you
	 * have not seen.</p>
	 *
	 * <p>Runs on tick rather than in {@link #update}: a snapshot can arrive before the screen has
	 * laid out, and the scroll offsets are only clamped against real content once drawn.</p>
	 */
	private void tickUnreadAcknowledgement() {
		if (page == TerminalPage.RECORDS && !recordsAcknowledged && snapshot.unreadCount() > 0
				&& showsUnreadRecordRow()) {
			send(TerminalControlPayload.MARK_RECORDS_READ, 0);
			recordsAcknowledged = true;
		}
		if (page == TerminalPage.FILES && !filesAcknowledged && snapshot.unreadFileCount() > 0
				&& showsUnreadFileRow()) {
			send(TerminalControlPayload.MARK_FILES_SEEN, 0);
			filesAcknowledged = true;
		}
	}

	/** Whether any unread record's first wrapped line is inside the Records viewport. */
	private boolean showsUnreadRecordRow() {
		List<RecordRow> rows = recordRows();
		int scroll = Math.clamp(recordsScrollRow, 0, recordsMaxScroll());
		int visible = recordsVisibleRows();
		for (int index = 0; index < rows.size(); index++) {
			// marker() is set on the first row of an unread entry, which is the one carrying its
			// timestamp - seeing a later wrapped line of it is not seeing the record arrive.
			if (rows.get(index).marker() && TerminalUnreadPolicy.rowVisible(index, scroll, visible)) return true;
		}
		return false;
	}

	/** Whether any file the unread badge is counting has its directory row inside the file list. */
	private boolean showsUnreadFileRow() {
		List<TerminalFilePayload> files = snapshot.files();
		if (files.isEmpty()) return false;
		long[] unlockedTimes = new long[files.size()];
		for (int index = 0; index < files.size(); index++) {
			TerminalFilePayload file = files.get(index);
			unlockedTimes[index] = file.unlocked() ? file.unlockedGameTime() : -1L;
		}
		int scroll = Math.clamp(fileListScroll, 0, TerminalUiLayout.fileMaxScrollRow(files.size()));
		for (int row : TerminalUnreadPolicy.unreadFileRows(unlockedTimes, snapshot.unreadFileCount())) {
			if (TerminalUnreadPolicy.rowVisible(row, scroll, TerminalUiLayout.FILE_LIST_VISIBLE_ROWS)) return true;
		}
		return false;
	}

	/**
	 * Hands the home card back to the live task once the finished one has been read.
	 *
	 * <p>The bar restarts from empty rather than easing down from full: the two values belong to
	 * different objectives, so an animation between them would draw a fall in progress that never
	 * happened.</p>
	 */
	private void tickCompletionHold() {
		if (completedTask == null || age < completedTaskUntilAge) return;
		completedTask = null;
		displayedObjectiveFraction = 0.0D;
		targetObjectiveFraction = snapshot.objectiveFraction();
	}

	/**
	 * Advances the sky monitor's fault flood and fires its one-shot cues.
	 *
	 * <p>On the client tick rather than in {@code render()}: the flood gains a line every half
	 * second and each burst is announced once, and both would run at frame rate if they were
	 * driven from drawing. State is dropped the moment the player looks at anything else, so
	 * reopening the tool starts the flood over rather than resuming a pile-up they never saw.</p>
	 */
	private void tickSkyInstrument() {
		if (skyCueCooldown > 0) skyCueCooldown--;
		if (page != TerminalPage.TOOLS || selectedTool != TerminalTool.WEATHER) {
			skyFloodTicks = 0;
			skyStage = 0;
			return;
		}
		int stage = SkyInstrumentPolicy.stage(SkyInstrumentRenderer.instability(), age);
		if (stage >= 2) skyFloodTicks++;
		else skyFloodTicks = 0;
		if (stage > skyStage && stage >= 2 && skyCueCooldown <= 0) {
			if (stage >= 3) TerminalClientAudio.fault();
			else TerminalClientAudio.skyCarrierLost();
			skyCueCooldown = SKY_CUE_COOLDOWN_TICKS;
		}
		skyStage = stage;
	}

	/** Sky-monitor stage while the weather tool is the open page; zero otherwise. For the signal beds. */
	public int skyMonitorStage() {
		return skyStage;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderNowMillis = nowMillis();
		if (selfTestArmed && selfTestStartedAtMillis < 0L) selfTestStartedAtMillis = renderNowMillis;
		renderAge = age + partialTick;
		motion.beginFrame(renderNowMillis);
		// The objective bar advanced once per client tick, so it climbed in twenty steps a second on
		// a bar that only gains a pixel or two per step - and the staircase was most visible exactly
		// when the player was watching it fill. Following the frame clock instead keeps the rate the
		// same on every machine, which a per-tick fraction of the remaining distance does not.
		displayedObjectiveFraction = TerminalMotion.catchUp(displayedObjectiveFraction,
				targetObjectiveFraction, motion.frameDelta(), TerminalMotion.PROGRESS_TAU_MILLIS);
		if (Math.abs(targetObjectiveFraction - displayedObjectiveFraction) < 0.001D) {
			displayedObjectiveFraction = targetObjectiveFraction;
		}
		float scale = panelScale();
		int panelWidth = Math.round(BASE_WIDTH * scale);
		int panelHeight = Math.round(BASE_HEIGHT * scale);
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		Identifier panel = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID,
				"textures/gui/terminal/panel_" + snapshot.visualStage() + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, panel, left, top, 0.0F, 0.0F,
				panelWidth, panelHeight, BASE_WIDTH, BASE_HEIGHT, BASE_WIDTH, BASE_HEIGHT);

		graphics.pose().pushMatrix();
		graphics.pose().translate(left, top);
		graphics.pose().scale(scale, scale);
		double[] localMouse = local(mouseX, mouseY);
		motion.updateHover(controlAt(localMouse[0], localMouse[1]));
		hoveredFile = page == TerminalPage.FILES
				? TerminalUiLayout.fileIndexAt(localMouse[0], localMouse[1], fileListScroll, snapshot.files().size()) : -1;
		// Computed here with every other hover rather than inside drawOnboarding, because this is the
		// one place in the frame that holds the pointer in panel coordinates.
		onboardingAdvanceHovered = TerminalUiLayout.ONBOARD_NEXT.contains(localMouse[0], localMouse[1]);
		hoveredToolSlot = -1;
		if (page == TerminalPage.TOOLS && selectedTool == null) {
			int slot = TerminalUiLayout.toolSlotAt(localMouse[0], localMouse[1]);
			// Locked cells hover too, so the grid can name what is holding their padlock shut.
			if (TerminalTool.fromSlot(slot) != null) hoveredToolSlot = slot;
		}
		drawGlass(graphics);
		drawTabs(graphics);
		drawPageContent(graphics);
		// The walkthrough writes its instruction into this band, so the standing readout stands down
		// for the duration rather than being painted over. Same during the short completion note.
		if (!onboardingOwnsStatusStrip()) {
			TerminalChrome.drawStatusBar(graphics, font, snapshot, holderName());
		}
		drawOnboarding(graphics);
		// The CRT layer goes over the page and the status strip - both are behind the same glass -
		// but under the hardware column, which sits outside the display entirely.
		TerminalChrome.drawCrt(graphics);
		drawScope(graphics);
		TerminalLampRenderer.drawUnreadLamp(graphics, snapshot.attentionActive(), renderAge);
		drawCompass(graphics);
		drawReceiverSlider(graphics);
		drawLcd(graphics);
		drawCenteredFitted(graphics, closeHintText(), TerminalUiLayout.CLOSE_HINT,
				onboardingLocksExit() ? HOT : DIM);
		graphics.pose().popMatrix();
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * The current page, slid into place while a page change is still settling.
	 *
	 * <p>Only the incoming page is drawn. Redrawing the outgoing one is not an option: by the time
	 * this runs, {@link #selectPage} has already cleared the selected tool, dropped the pinned unlock
	 * reason, reset the file view and zeroed the records scroll - so "the previous page" would render
	 * as a version the player never actually saw, snapping out of a tool detail into the grid on its
	 * way off screen. The band it vacates shows the display's own glass instead, which reads as the
	 * surface being cleared.</p>
	 */
	private void drawPageContent(GuiGraphics graphics) {
		// Nothing is drawn during the self test. The terminal has not finished starting, so a home
		// card sitting there would be claiming the machine is further along than it is - and it is
		// what forced the boot text to carry an opaque plate to hide it.
		//
		// The profile is the same case for the same reason. The device has not been bound yet, so it
		// has no holder to show a task card to, and no tab is reachable from there anyway.
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.BOOT
				|| onboardingPhase == TerminalOnboardingPolicy.Phase.PROFILE) return;
		// Same reason, half a second long: the machine has not said it is up yet.
		if (selfTestRunning()) return;
		var body = TerminalUiLayout.PAGE_BODY;
		double progress = motion.pageProgress(renderNowMillis);
		if (progress >= 1.0D) {
			drawCurrentPage(graphics);
			return;
		}
		float offset = (float) (motion.transitionDirection() * body.width() * (1.0D - progress));
		// GuiGraphics#enableScissor runs the current pose itself, so this takes the terminal's own
		// 512x256 coordinates and never a screen-pixel conversion. Opening it before the content
		// offset is pushed is what holds the clip rectangle still while the page slides through it -
		// pushed the other way round, the clip travels with the content and never cuts anything.
		graphics.enableScissor(body.left(), body.top(), body.right(), body.bottom());
		graphics.pose().pushMatrix();
		graphics.pose().translate(offset, 0.0F);
		drawCurrentPage(graphics);
		graphics.pose().popMatrix();
		drawPageWipeEdge(graphics, body, offset);
		graphics.disableScissor();
	}

	private void drawCurrentPage(GuiGraphics graphics) {
		// The settle is anchored to arriving on the page, so leaving it arms the next arrival. Kept
		// here rather than in selectPage because the page can also change under a slide that this
		// method is the only witness to.
		if (page != TerminalPage.RECORDS) recordsSettleArmed = true;
		switch (page) {
			case HOME -> drawHome(graphics);
			case TOOLS -> drawTools(graphics);
			case RECORDS -> drawRecords(graphics);
			case FILES -> drawLog(graphics);
		}
	}

	/** A bright seam on the leading edge of the incoming page, so the slide reads as a refresh. */
	private void drawPageWipeEdge(GuiGraphics graphics, TerminalUiLayout.Bounds body, float offset) {
		if (offset == 0.0F) return;
		int x = offset > 0.0F
				? body.left() + (int) Math.ceil(offset)
				: body.right() + (int) Math.floor(offset);
		graphics.fill(x - 1, body.top(), x, body.bottom(), SCOPE_HIGHLIGHT);
	}

	/**
	 * Changes page and starts the slide.
	 *
	 * <p>The field moves immediately - hit testing, scrolling and the keyboard all answer for the
	 * incoming page from this instant. Only the drawing lags behind, so nothing can be clicked at a
	 * position it merely appears to occupy part way through the transition.</p>
	 */
	private void enterPage(TerminalPage next) {
		if (page == next) return;
		motion.beginPageTransition(page, next, nowMillis());
		page = next;
	}

	/**
	 * How long the first-boot scene on screen has been the scene.
	 *
	 * <p>Sampled while drawing rather than pushed from the tick, because a scene can also change
	 * because a snapshot arrived - the server advancing the profile question is not a client-side
	 * event anybody could have hooked. Comparing the key every frame catches all of them with one
	 * rule instead of one hook per cause.
	 */
	private long onboardingTransitionMillis() {
		int key = TerminalOnboardingTransition.sceneKey(onboardingPhase, snapshot.profileQuestion());
		if (key != onboardingSceneKey) {
			onboardingSceneKey = key;
			onboardingSceneStartedAtMillis = renderNowMillis;
			// One soft contact per step. The seam is quiet enough that without it a scene change can
			// be missed entirely by a player who happened to be looking at the slider.
			TerminalClientAudio.keypress();
		}
		return renderNowMillis - onboardingSceneStartedAtMillis;
	}

	/** The self test, the step pointer, or the brief "setup complete" note - whichever applies. */
	private void drawOnboarding(GuiGraphics graphics) {
		if (selfTestRunning()) {
			selfTestOverlay.prepare(snapshot, snapshot.files().size());
			selfTestOverlay.draw(graphics, font, selfTestElapsedMillis());
			return;
		}
		if (onboardingPhase == TerminalOnboardingPolicy.Phase.BOOT) {
			onboardingOverlay.prepareBootText(holderName());
			onboardingOverlay.drawBoot(graphics, font,
					renderNowMillis - onboardingStartedAtMillis, pageAccent());
			return;
		}
		if (profileAsking()) {
			// No loading bar here. It was added on the reasoning that an empty beat wants something in
			// it, and in play it read as the game waiting on itself - a question the player is meant
			// to answer should not look like a question the device is still fetching. The blank and
			// the lines arriving carry the seam on their own.
			onboardingOverlay.drawProfile(graphics, font, snapshot.profileQuestion(), tuning,
					TerminalProfileQuestionnaire.lockedOption(snapshot.profileQuestion(), tuning),
					profileHeldTicks, recordsSettleSeed, TerminalGlyphSettle.bucket(renderNowMillis),
					onboardingTransitionMillis(), pageAccent());
			return;
		}
		if (profileActive()) {
			// Every question answered, or gaps the failsafe left. The terminal says which, because a
			// file it knows is incomplete and reports as finished would be the quietly-wrong-but-
			// plausible value the safety rules exist to forbid.
			onboardingOverlay.drawProfileRecorded(graphics, font, profileComplete(), recordsSettleSeed,
					TerminalGlyphSettle.bucket(renderNowMillis), onboardingTransitionMillis(), pageAccent());
			return;
		}
		if (onboardingLocksExit()) {
			// The page field, not the one the slide is still catching up to: the brief describes what
			// the player is on, and every other part of the screen already answers for the new page
			// from the instant of the click.
			onboardingOverlay.drawStep(graphics, font, onboardingPhase, page, renderAge,
					onboardingTransitionMillis(), pageAccent(), onboardingAdvanceHovered);
			return;
		}
		if (onboardingDoneAtAge == Integer.MIN_VALUE
				|| age - onboardingDoneAtAge >= TerminalOnboardingPolicy.DONE_LINGER_TICKS) {
			return;
		}
		// Finishing is the payoff, so the note lingers long enough to be read next to the bar
		// reaching 4/4 and the reward notice. It holds nothing back while it does.
		var strip = TerminalUiLayout.STATUS_BAR;
		Component done = Component.translatable("terminal.thefourthfrequency.onboarding.complete");
		drawCenteredFitted(graphics, done, strip, CLAIMABLE);
	}

	/**
	 * Whether a click landed on the walkthrough's advance button while it was open.
	 *
	 * <p>The readiness test is the same one the drawing uses, so a button that looks unavailable is
	 * unavailable. Without that a player could press through the explanation before it finished
	 * printing, which is the one thing the typing is there to prevent.
	 */
	private boolean onboardingAdvanceClicked(double x, double y) {
		return TerminalOnboardingPolicy.target(onboardingPhase) != null
				&& TerminalUiLayout.ONBOARD_NEXT.contains(x, y)
				&& TerminalOnboardingPolicy.advanceReady(onboardingTransitionMillis(),
						TerminalOnboardingOverlay.longestDetailCodePoints(
								TerminalOnboardingPolicy.briefSubject(onboardingPhase, page)));
	}

	private void advanceOnboarding(TerminalPage visited) {
		TerminalOnboardingPolicy.Phase next = TerminalOnboardingPolicy.advance(onboardingPhase, visited);
		if (next == onboardingPhase) return;
		onboardingPhase = next;
		if (next == TerminalOnboardingPolicy.Phase.DONE) onboardingDoneAtAge = age;
	}

	/** The tab a point falls in, or null. Used to let the walkthrough pass its own target through. */
	private static TerminalPage tabAt(double x, double y) {
		if (TerminalUiLayout.HOME_TAB.contains(x, y)) return TerminalPage.HOME;
		if (TerminalUiLayout.TOOLS_TAB.contains(x, y)) return TerminalPage.TOOLS;
		if (TerminalUiLayout.RECORDS_TAB.contains(x, y)) return TerminalPage.RECORDS;
		if (TerminalUiLayout.FILES_TAB.contains(x, y)) return TerminalPage.FILES;
		return null;
	}

	private void drawGlass(GuiGraphics graphics) {
		var display = TerminalUiLayout.DISPLAY;
		graphics.fill(display.left(), display.top(), display.right(), display.bottom(), GLASS_BACKDROP);
	}

	private void drawTabs(GuiGraphics graphics) {
		drawTab(graphics, TerminalUiLayout.HOME_TAB, TerminalPage.HOME,
				"terminal.thefourthfrequency.tab.home", false);
		drawTab(graphics, TerminalUiLayout.TOOLS_TAB, TerminalPage.TOOLS,
				"terminal.thefourthfrequency.tab.tools", false);
		drawTab(graphics, TerminalUiLayout.RECORDS_TAB, TerminalPage.RECORDS,
				"terminal.thefourthfrequency.tab.records", showsUnreadRecords(), unreadFlashStartedAt);
		drawTab(graphics, TerminalUiLayout.FILES_TAB, TerminalPage.FILES,
				"terminal.thefourthfrequency.tab.files", showsUnreadFiles(),
				unreadFileFlashStartedAt);
		drawTabIndicator(graphics);
	}

	/**
	 * The rail under the selected tab, which travels between tabs rather than reappearing on the
	 * new one. It is the only thing on screen that shows which way the page slid.
	 */
	private void drawTabIndicator(GuiGraphics graphics) {
		var target = tabBounds(page);
		motion.retargetTabIndicator(target, renderNowMillis);
		int x = (int) Math.round(motion.tabIndicatorX(renderNowMillis));
		int y = target.bottom() - 2;
		graphics.fill(x, y, x + target.width(), y + 2, pageAccent());
	}

	private static TerminalUiLayout.Bounds tabBounds(TerminalPage tab) {
		return switch (tab) {
			case HOME -> TerminalUiLayout.HOME_TAB;
			case TOOLS -> TerminalUiLayout.TOOLS_TAB;
			case RECORDS -> TerminalUiLayout.RECORDS_TAB;
			case FILES -> TerminalUiLayout.FILES_TAB;
		};
	}

	/**
	 * The control under a point in panel space, or null.
	 *
	 * <p>Shared by the click handler and the per-frame hover pass, so what lights up under the
	 * pointer is by construction the thing a press would reach. Ordering matches
	 * {@link #mouseClicked}: the tab strip and the dial answer from any page, then the page's own
	 * controls.</p>
	 *
	 * <p>The three tool action buttons are deliberately absent. {@code TOOL_ACTION_FULL} spans both
	 * {@code TOOL_ACTION_PRIMARY} and {@code TOOL_ACTION_SECONDARY}, and which of them is live
	 * depends on the selected tool and its guidance state - reproducing that here would be a second
	 * copy of {@code handleToolAction}'s rules, free to drift out of step with it.</p>
	 */
	private TerminalMotionState.Control controlAt(double x, double y) {
		if (TerminalUiLayout.HOME_TAB.contains(x, y)) return TerminalMotionState.Control.TAB_HOME;
		if (TerminalUiLayout.TOOLS_TAB.contains(x, y)) return TerminalMotionState.Control.TAB_TOOLS;
		if (TerminalUiLayout.RECORDS_TAB.contains(x, y)) return TerminalMotionState.Control.TAB_RECORDS;
		if (TerminalUiLayout.FILES_TAB.contains(x, y)) return TerminalMotionState.Control.TAB_FILES;
		if (receiverMechanicalInteractive() && TerminalUiLayout.RECEIVER_SLIDER.contains(x, y)) {
			return TerminalMotionState.Control.RECEIVER_SLIDER;
		}
		if (page == TerminalPage.HOME) {
			if (homeLiveTool != null) {
				if (TerminalUiLayout.HOME_TOOL_CLOSE.contains(x, y)) return TerminalMotionState.Control.HOME_TOOL_CLOSE;
				if (TerminalUiLayout.HOME_TOOL_DETAIL.contains(x, y)) return TerminalMotionState.Control.HOME_TOOL_DETAIL;
			} else {
				if (TerminalUiLayout.HOME_QUICK_PRIMARY.contains(x, y)) {
					return TerminalMotionState.Control.HOME_QUICK_PRIMARY;
				}
				if (TerminalUiLayout.HOME_QUICK_SECONDARY.contains(x, y)) {
					return TerminalMotionState.Control.HOME_QUICK_SECONDARY;
				}
			}
		}
		if (page == TerminalPage.TOOLS) {
			if (selectedTool != null) {
				return TerminalUiLayout.TOOL_BACK.contains(x, y) ? TerminalMotionState.Control.TOOL_BACK : null;
			}
			return TerminalMotionState.Control.toolCell(TerminalUiLayout.toolSlotAt(x, y));
		}
		if (page == TerminalPage.FILES) {
			int index = TerminalUiLayout.fileIndexAt(x, y, fileListScroll, snapshot.files().size());
			if (index >= 0) return TerminalMotionState.Control.fileRow(index - fileListScroll);
		}
		return null;
	}

	/** Blends a control's base colour toward its hover and press tints. */
	private int interactiveColor(TerminalMotionState.Control control, int base) {
		if (control == null) return base;
		int hovered = TerminalMotion.lerpColor(base, TerminalVisualTheme.HOVER,
				motion.hoverAmount(control) * 0.6D);
		return TerminalMotion.lerpColor(hovered, TerminalVisualTheme.PRESSED,
				motion.pressAmount(control, renderNowMillis));
	}

	private void drawTab(GuiGraphics graphics, TerminalUiLayout.Bounds bounds,
			TerminalPage tab, String key, boolean unread) {
		drawTab(graphics, bounds, tab, key, unread, unreadFlashStartedAt);
	}

	private void drawTab(GuiGraphics graphics, TerminalUiLayout.Bounds bounds,
			TerminalPage tab, String key, boolean unread, double flashStartedAt) {
		boolean selected = page == tab;
		boolean flashOn = unread && TerminalUiLayout.unreadFlashOn(renderAge - flashStartedAt);
		int background = flashOn ? ALERT_BACKGROUND : selected ? SELECTED : GLASS;
		graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
				interactiveColor(TerminalMotionState.Control.tab(tab), background));
		if (flashOn) graphics.renderOutline(bounds.left(), bounds.top(), bounds.width(), bounds.height(), HOT);
		if (selected) graphics.fill(bounds.left(), bounds.top() + 2, bounds.left() + 2, bounds.bottom() - 2,
				unread ? HOT : pageAccent());
		Component label = Component.translatable(key);
		if (unread) label = label.copy().append(Component.literal(" [!]"));
		drawCenteredFitted(graphics, label, bounds, unread ? HOT : selected ? pageAccent() : DIM);
	}

	private void drawHome(GuiGraphics graphics) {
		// A held completion owns the card outright: its line, its reward, its colour. Mixing the two
		// would put the finished task's text over the new task's progress.
		boolean settled = completedTask != null;
		int taskAccent = settled || snapshot.objectiveClaimable() ? CLAIMABLE : pageAccent();
		drawCard(graphics, TerminalUiLayout.HOME_TASK, taskAccent);
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.home.current_task"),
				TerminalUiLayout.HOME_TASK.left() + 7, TerminalUiLayout.HOME_TASK.top() + 6, AMBER, false);
		drawTaskReward(graphics, settled ? completedTask.reward() : snapshot.objectiveReward(),
				settled || snapshot.objectiveClaimable());
		drawObjectiveLine(graphics, settled ? completedTask.line() : snapshot.objectiveLine());
		if (settled || snapshot.objectiveClaimable()) drawClaimTag(graphics);
		int progressWidth = TerminalUiLayout.HOME_TASK.width() - 14;
		int filled = (int) Math.round(progressWidth * Math.clamp(displayedObjectiveFraction, 0.0D, 1.0D));
		int progressY = TerminalUiLayout.HOME_TASK.bottom() - 5;
		graphics.fill(TerminalUiLayout.HOME_TASK.left() + 7, progressY,
				TerminalUiLayout.HOME_TASK.right() - 7, progressY + 3, DARK_BORDER);
		graphics.fill(TerminalUiLayout.HOME_TASK.left() + 7, progressY,
				TerminalUiLayout.HOME_TASK.left() + 7 + filled, progressY + 3, taskAccent);

		if (homeLiveTool != null) {
			drawHomeToolDetail(graphics, homeLiveTool);
		} else {
			drawQuickTool(graphics, tools.recommendedPrimaryTool(), TerminalUiLayout.HOME_QUICK_PRIMARY);
			drawQuickTool(graphics, tools.recommendedSecondaryTool(), TerminalUiLayout.HOME_QUICK_SECONDARY);
		}

		drawCard(graphics, TerminalUiLayout.HOME_RECENT, DIM);
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.home.recent"),
				TerminalUiLayout.HOME_RECENT.left() + 7, TerminalUiLayout.HOME_RECENT.top() + 5, AMBER, false);
		// Same navigator gate the Records page lists under, so "most recent" names an entry that is
		// actually in the log the player is about to open.
		Component recent = snapshot.latestSignalEvent(tools.available(TerminalTool.NAVIGATION));
		graphics.drawString(font, font.split(recent, TerminalUiLayout.HOME_RECENT.width() - 14).getFirst(),
				TerminalUiLayout.HOME_RECENT.left() + 7, TerminalUiLayout.HOME_RECENT.top() + 16, GREEN, false);
	}

	/**
	 * The objective, over two rows.
	 *
	 * <p>One row was all this card ever drew: the wrapped remainder was thrown away, so an objective
	 * whose sentence ran long told the player to do the first half of something. The second row is
	 * paid for by {@link #drawClaimTag}, which turned the full-width "claim your reward" line into a
	 * tag beside the progress bar.</p>
	 */
	private void drawObjectiveLine(GuiGraphics graphics, Component line) {
		var card = TerminalUiLayout.HOME_TASK;
		List<FormattedCharSequence> objective = font.split(line, card.width() - 52);
		int y = card.top() + OBJECTIVE_FIRST_ROW_Y;
		for (int index = 0; index < objective.size() && index < OBJECTIVE_MAX_ROWS; index++) {
			graphics.drawString(font, objective.get(index), card.left() + 7, y, GREEN, false);
			y += OBJECTIVE_ROW_HEIGHT;
		}
	}

	/**
	 * The "task complete" readout on the progress row, in the colour the bar and reward box share.
	 *
	 * <p>Deliberately flat - no border, no plate. Nothing here is pressable: the reward is delivered
	 * the moment the task completes, so anything shaped like a button would be promising an action
	 * that has already happened.</p>
	 *
	 * <p>It does not restate what was handed over either. During a held completion the card is
	 * already saying that four ways - the objective at n/n, the bar filled, the whole card turned to
	 * the completion colour, and the reward box drawing the item and its count - and a fifth line
	 * spelling out "issued bread ×6" beside them was words where the card had already finished
	 * speaking.</p>
	 */
	private void drawClaimTag(GuiGraphics graphics) {
		var card = TerminalUiLayout.HOME_TASK;
		Component label = Component.translatable("terminal.thefourthfrequency.home.claim_reward");
		graphics.drawString(font, label, card.right() - 7 - font.width(label),
				card.bottom() - 16, CLAIMABLE, false);
	}

	private void drawTaskReward(GuiGraphics graphics, ItemStack reward, boolean claimable) {
		if (reward.isEmpty()) return;
		int x = TerminalUiLayout.HOME_TASK.right() - 24;
		int y = TerminalUiLayout.HOME_TASK.top() + 4;
		graphics.fill(x - 2, y - 2, x + 18, y + 18, REWARD_SLOT);
		graphics.renderOutline(x - 2, y - 2, 20, 20, claimable ? CLAIMABLE : AMBER);
		graphics.renderItem(reward, x, y);
		if (reward.getCount() > 1) {
			String count = Integer.toString(reward.getCount());
			graphics.drawString(font, count, x + 17 - font.width(count), y + 9, 0xFFFFFFFF, true);
		}
	}

	/**
	 * A task that has just been completed and paid for, kept exactly as the card last saw it.
	 *
	 * @param line   the objective at n/n, so the row reads as finished rather than as one short
	 * @param reward what was actually handed over, held here because the live snapshot has already
	 *               moved on to the next task's reward
	 */
	private record CompletedTask(Component line, ItemStack reward) {
	}

	private void drawQuickTool(GuiGraphics graphics, TerminalTool tool, TerminalUiLayout.Bounds bounds) {
		if (tool == null) {
			drawCard(graphics, bounds, DIM);
			graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.home.pending_hint"),
					bounds.left() + 7, bounds.top() + 8, DIM, false);
			Component detail = Component.translatable("terminal.thefourthfrequency.home.pending_hint.detail");
			graphics.drawString(font, ellipsize(detail.getString(), bounds.width() - 14),
					bounds.left() + 7, bounds.top() + 23, DIM, false);
			return;
		}
		drawCard(graphics, bounds, GREEN);
		boolean mineralSurvey = tool == TerminalTool.MINERALS && tools.mineralSurveyNearby();
		Component heading = Component.translatable(mineralSurvey
				? "terminal.thefourthfrequency.tool.minerals.nearby_short"
				: "terminal.thefourthfrequency.home.quick");
		graphics.drawString(font, ellipsize(heading.getString(), bounds.width() - 14),
				bounds.left() + 7, bounds.top() + 6, mineralSurvey ? AMBER : DIM, false);
		graphics.drawString(font, toolName(tool), bounds.left() + 7, bounds.top() + 20, GREEN, false);
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.tool.open"),
				bounds.right() - 7 - font.width(Component.translatable("terminal.thefourthfrequency.tool.open")),
				bounds.bottom() - 13, AMBER, false);
	}

	private void drawHomeToolDetail(GuiGraphics graphics, TerminalTool tool) {
		TerminalUiLayout.Bounds bounds = TerminalUiLayout.HOME_TOOL_DETAIL;
		drawCard(graphics, bounds, tools.available(tool) ? pageAccent() : DIM);
		drawToolButton(graphics, TerminalUiLayout.HOME_TOOL_CLOSE,
				Component.translatable("terminal.thefourthfrequency.tool.close_live"), false);
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.home.live_tool", toolName(tool)),
				bounds.left() + 7, bounds.top() + 6, tools.available(tool) ? AMBER : DIM, false);
		List<Component> lines = toolDetailLines(tool);
		Component status = lines.size() > 1 ? lines.getLast() : lines.getFirst();
		graphics.drawString(font, ellipsize(status.getString(), TerminalUiLayout.HOME_TOOL_CLOSE.left()
				- bounds.left() - 14), bounds.left() + 7, bounds.top() + 21,
				tools.available(tool) ? GREEN : DIM, false);
	}

	private void drawTools(GuiGraphics graphics) {
		if (selectedTool != null) {
			drawToolDetail(graphics, selectedTool);
			return;
		}
		for (TerminalTool tool : TerminalTool.values()) {
			TerminalUiLayout.Bounds cell = TerminalUiLayout.toolCell(tool.slot());
			boolean available = tools.available(tool);
			boolean hovered = available && hoveredToolSlot == tool.slot();
			drawCard(graphics, cell, hovered ? AMBER : available ? GREEN : DARK_BORDER,
					TerminalMotionState.Control.toolCell(tool.slot()));
			drawToolGlyph(graphics, tool, cell);
			drawCenteredFitted(graphics, toolName(tool),
					new TerminalUiLayout.Bounds(cell.left() + 3, cell.bottom() - 18, cell.right() - 3, cell.bottom() - 3),
					hovered ? AMBER : available ? GREEN : DIM);
			if (!available) drawPixelLock(graphics, cell.right() - 14, cell.top() + 5);
		}
		drawToolHint(graphics);
	}

	/**
	 * The single line under the grid that explains whatever the pointer is on.
	 *
	 * <p>It started as the padlock hint alone. A locked cell is rejected by the detail-opening
	 * boundary, which is deliberate - there is nothing inside a tool the player does not have yet -
	 * so without this the padlock was a dead end: click it, nothing happens, and the terminal never
	 * says what would unlock it.
	 *
	 * <p>It now answers for unlocked cells too, because the grid had the same gap pointing the other
	 * way: six glyphs and six nouns, and the only way to learn what any of them reported was to open
	 * it. Both answers go in the same place rather than in a floating tooltip - the terminal draws
	 * inside a scissored, pose-transformed display area, and a box that follows the cursor is the
	 * kind of thing that ends up clipped away or drawn at the wrong origin.
	 *
	 * <p>Green for a tool you have, dim for one you do not, which is the same colour language the
	 * cells themselves already use.
	 */
	private void drawToolHint(GuiGraphics graphics) {
		var grid = TerminalUiLayout.TOOLS_GRID;
		var line = new TerminalUiLayout.Bounds(grid.left(), grid.bottom() - 2, grid.right(), grid.bottom() + 10);
		TerminalTool hovered = TerminalTool.fromSlot(hoveredToolSlot);
		if (hovered != null && tools.available(hovered)) {
			drawCenteredFitted(graphics, tools.hintLine(hovered), line, GREEN);
			return;
		}
		// Falls back to the last locked cell the pointer visited, so the reason does not vanish the
		// instant the player moves off the padlock to read it.
		TerminalTool subject = hovered != null ? hovered : lockedHintTool;
		if (subject == null || tools.available(subject)) return;
		drawCenteredFitted(graphics, tools.lockedLine(subject), line, DIM);
	}

	private void drawToolGlyph(GuiGraphics graphics, TerminalTool tool, TerminalUiLayout.Bounds cell) {
		String[] pixels = switch (tool) {
			case HOME -> new String[]{
					".....#.....", "....###....", "...#####...", "..##...##..", ".##.....##.",
					".##..+..##.", ".##..+..##.", ".##..+..##.", ".##.+++.##.", ".#########.",
					"..........."};
			case MINERALS -> new String[]{
					".....#.....", "...#####...", "..##~~~##..", ".##~!~!~##.", ".#~~!!!~~#.",
					".##~!~!~##.", "..##~~~##..", "...##+##...", "....#+#....", ".....#.....",
					"..........."};
			case PORTAL -> new String[]{
					"..#######..", ".##+++++##.", ".##~~~~~##.", ".##~!~~~##.", ".##~~~!~##.",
					".##~~!~~##.", ".##~!~~~##.", ".##~~~~~##.", ".##+++++##.", "..#######..",
					"..........."};
			case WEATHER -> new String[]{
					".......#...", ".....#####.", "....###!###", "...###..##.", "..~~~~~~~~.",
					".~~~~~~~~~.", "..~~~~~~~..", "...+.+.+...", "..+.+.+....", "...........",
					"..........."};
			case NAVIGATION -> new String[]{
					"...#####...", "..##...##..", ".##..!..##.", "##...!...##", "#...!!!...#",
					"#....!....#", "#....~....#", "##..~~~..##", ".##..~..##.", "..##...##..",
					"...#####..."};
			case STRONGHOLD -> new String[]{
					"...........", "..#.....#..", ".###...###.", ".###...###.", ".#########.",
					".##~###~##.", ".##~###~##.", ".##~!#!~##.", ".##~!#!~##.", ".#########.",
					"..........."};
		};
		int pixelSize = 2;
		int glyphWidth = pixels[0].length() * pixelSize;
		int glyphHeight = pixels.length * pixelSize;
		int x = (cell.left() + cell.right() - glyphWidth) / 2;
		int y = cell.top() + 5;
		boolean available = tools.available(tool);
		boolean hovered = available && hoveredToolSlot == tool.slot();
		drawToolGlyphPlate(graphics, tool, x - 4, y - 3, glyphWidth + 8, glyphHeight + 6,
				available, hovered);
		drawPixelPattern(graphics, pixels, x, y, available);
	}

	private void drawToolGlyphPlate(GuiGraphics graphics, TerminalTool tool, int x, int y,
			int width, int height, boolean available, boolean hovered) {
		graphics.fill(x, y, x + width, y + height, available ? 0xC20A120D : 0xB20A0E0B);
		int frame = !available ? DARK_BORDER : hovered ? AMBER : toolAccent(tool);
		int corner = 5;
		graphics.fill(x, y, x + corner, y + 1, frame);
		graphics.fill(x, y, x + 1, y + corner, frame);
		graphics.fill(x + width - corner, y, x + width, y + 1, frame);
		graphics.fill(x + width - 1, y, x + width, y + corner, frame);
		graphics.fill(x, y + height - 1, x + corner, y + height, frame);
		graphics.fill(x, y + height - corner, x + 1, y + height, frame);
		graphics.fill(x + width - corner, y + height - 1, x + width, y + height, frame);
		graphics.fill(x + width - 1, y + height - corner, x + width, y + height, frame);
		graphics.fill(x + 3, y + height - 4, x + 5, y + height - 2, available ? frame : DIM);
	}

	private static int toolAccent(TerminalTool tool) {
		return switch (tool) {
			case HOME, STRONGHOLD -> AMBER;
			case MINERALS, WEATHER -> CYAN;
			case PORTAL -> GREEN;
			case NAVIGATION -> HOT;
		};
	}

	private void drawPixelPattern(GuiGraphics graphics, String[] pixels, int x, int y, boolean available) {
		for (int row = 0; row < pixels.length; row++) {
			for (int column = 0; column < pixels[row].length(); column++) {
				char pixel = pixels[row].charAt(column);
				if (pixel == '.') continue;
				int color = available ? switch (pixel) {
					case '+' -> GREEN;
					case '~' -> CYAN;
					case '!' -> HOT;
					default -> AMBER;
				} : switch (pixel) {
					case '+' -> DARK_BORDER;
					case '~' -> MUTED_DARK;
					case '!' -> MUTED;
					default -> DIM;
				};
				graphics.fill(x + column * 2, y + row * 2, x + column * 2 + 2, y + row * 2 + 2, color);
			}
		}
	}

	private void drawPixelLock(GuiGraphics graphics, int x, int y) {
		graphics.fill(x + 2, y, x + 7, y + 1, DIM);
		graphics.fill(x + 1, y + 1, x + 3, y + 5, DIM);
		graphics.fill(x + 6, y + 1, x + 8, y + 5, DIM);
		graphics.fill(x, y + 4, x + 9, y + 11, 0xFF111711);
		graphics.renderOutline(x, y + 4, 9, 7, DIM);
	}

	private void drawToolDetail(GuiGraphics graphics, TerminalTool tool) {
		drawCard(graphics, TerminalUiLayout.TOOL_HEADER, pageAccent());
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.tool.back"),
				TerminalUiLayout.TOOL_HEADER.left() + 7, TerminalUiLayout.TOOL_HEADER.top() + 6, AMBER, false);
		if (toolOpenedFromHome) {
			graphics.fill(TerminalUiLayout.TOOL_HEADER.left() + 4, TerminalUiLayout.TOOL_HEADER.top() + 3,
					TerminalUiLayout.TOOL_HEADER.left() + 60, TerminalUiLayout.TOOL_HEADER.bottom() - 3, 0xFF0C1710);
			graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.tool.back_home"),
				TerminalUiLayout.TOOL_HEADER.left() + 7, TerminalUiLayout.TOOL_HEADER.top() + 6, AMBER, false);
		}
		graphics.drawString(font, toolName(tool), TerminalUiLayout.TOOL_HEADER.left() + 68,
				TerminalUiLayout.TOOL_HEADER.top() + 6, GREEN, false);
		drawCard(graphics, TerminalUiLayout.TOOL_DETAIL, GREEN);
		List<FormattedCharSequence> rows = toolDetailRows(tool);
		int visible = toolDetailVisibleRows(tool);
		toolDetailScroll = Math.clamp(toolDetailScroll, 0, toolDetailMaxScroll(tool));
		int y = TerminalUiLayout.TOOL_DETAIL.top() + 8;
		for (int index = toolDetailScroll; index < rows.size() && index < toolDetailScroll + visible; index++) {
			graphics.drawString(font, rows.get(index), TerminalUiLayout.TOOL_DETAIL.left() + 7, y, GREEN, false);
			y += TOOL_DETAIL_ROW_HEIGHT;
		}
		drawToolDetailOverflow(graphics, rows.size(), visible);
		if (tool == TerminalTool.NAVIGATION) drawNavigationToolList(graphics);
		else navigationHits.clear();
		// Before the action row, so the pin button stays crisp under a burst: the flood may bury
		// the readings, but never a control.
		if (tool == TerminalTool.WEATHER) SkyInstrumentRenderer.draw(graphics, font, renderAge, skyFloodTicks);
		drawToolActions(graphics, tool);
	}

	/**
	 * How far down the card body may run. NAVIGATION keeps the tighter ceiling because its target
	 * buttons sit under it, and WEATHER because the sky monitor owns the band below; every other
	 * tool has empty card all the way down to the action row.
	 */
	private static int toolDetailLimit(TerminalTool tool) {
		return switch (tool) {
			case NAVIGATION -> TerminalUiLayout.TOOL_OPTION_ONE.top() - 17;
			case WEATHER -> SkyInstrumentRenderer.INSTRUMENT_TOP - 3;
			default -> TerminalUiLayout.TOOL_ACTION_PRIMARY.top() - 3;
		};
	}

	private int toolDetailVisibleRows(TerminalTool tool) {
		int room = toolDetailLimit(tool) - (TerminalUiLayout.TOOL_DETAIL.top() + 8) - font.lineHeight;
		return Math.max(1, room / TOOL_DETAIL_ROW_HEIGHT + 1);
	}

	private int toolDetailMaxScroll(TerminalTool tool) {
		if (tool == null) return 0;
		return Math.max(0, toolDetailRows(tool).size() - toolDetailVisibleRows(tool));
	}

	private List<FormattedCharSequence> toolDetailRows(TerminalTool tool) {
		// The gutter is always reserved, so text wraps the same whether or not the bar is showing -
		// otherwise a line appearing would reflow every line above it.
		int width = TerminalUiLayout.TOOL_DETAIL.width() - 14 - TOOL_DETAIL_SCROLLBAR_GUTTER;
		List<FormattedCharSequence> rows = new ArrayList<>();
		for (Component line : toolDetailLines(tool)) {
			rows.addAll(font.split(line, width));
		}
		return rows;
	}

	/**
	 * Says that the card is holding text the player cannot see. The old code simply stopped drawing
	 * at the ceiling, so a mineral probe whose summary had grown to two lines lost its "scanning" or
	 * bearing readout with no mark of any kind - the reading the player opened the tool for.
	 */
	private void drawToolDetailOverflow(GuiGraphics graphics, int total, int visible) {
		if (total <= visible) return;
		var detail = TerminalUiLayout.TOOL_DETAIL;
		int x = detail.right() - 9;
		int top = detail.top() + 8;
		int bottom = top + visible * TOOL_DETAIL_ROW_HEIGHT;
		int trackHeight = bottom - top;
		graphics.fill(x, top, x + 2, bottom, DARK_BORDER);
		int thumbHeight = Math.max(6, Math.round(trackHeight * visible / (float) total));
		int thumbTop = top + Math.round((trackHeight - thumbHeight)
				* toolDetailScroll / (float) Math.max(1, total - visible));
		graphics.fill(x, thumbTop, x + 2, thumbTop + thumbHeight, AMBER);
	}

	private List<Component> toolDetailLines(TerminalTool tool) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("terminal.thefourthfrequency.tool." + tool.id() + ".summary"));
		switch (tool) {
			case HOME -> lines.add(tools.homeLine());
			case MINERALS -> {
				// The charge bank goes above the reading, not below it. The compact home card shows
				// the last line of this list as the tool's status, and a player who pinned the
				// mineral tool while walking to an ore wants the bearing there, not the cost.
				if (!tools.mineralScanning()) lines.add(tools.mineralProbeLine());
				if (tools.mineralScanning()) {
					lines.add(mineralScanningLine());
				} else if (mineralTargetLocated()) {
					lines.add(TerminalSnapshot.navigationLine(navigation, tools.playerY()));
				} else if (tools.mineralBearingReading()) {
					lines.add(tools.mineralBearingLine());
				} else if (tools.mineralProbeHeardNothing()) {
					lines.add(Component.translatable("terminal.thefourthfrequency.tool.minerals.not_found"));
				} else if (tools.mineralSurveyNearby()) {
					lines.add(Component.translatable("terminal.thefourthfrequency.tool.minerals.nearby"));
				} else {
					lines.add(Component.translatable("terminal.thefourthfrequency.tool.minerals.waiting"));
				}
			}
			case PORTAL -> lines.add(tools.portalLine());
			// Visibly unreadable, never quietly wrong. Players plan around "N minutes until dark",
			// so a sky anomaly may take that number away from them but must not hand them a
			// different one that still looks trustworthy.
			case WEATHER -> lines.add(tools.weatherLine(SkyInstrumentRenderer.readoutLost(renderAge)));
			case NAVIGATION -> {
				if (navigation.targetKind() != 0) lines.add(TerminalSnapshot.navigationLine(navigation, tools.playerY()));
				int omitted = omittedNavigationTargets();
				if (omitted > 0) lines.add(Component.translatable(
						"terminal.thefourthfrequency.navigation.more_targets", omitted));
			}
			case STRONGHOLD -> lines.add(tools.strongholdLine());
		}
		if (!tools.available(tool)) lines.add(tools.lockedLine(tool));
		if (tools.toolsDisabled() && tool != TerminalTool.WEATHER) lines.add(tools.disabledLine());
		return List.copyOf(lines);
	}

	private Component mineralScanningLine() {
		int dots = animatedProbeDots(renderAge);
		return Component.translatable("terminal.thefourthfrequency.tool.minerals.scanning")
				.copy().append(Component.literal(".".repeat(dots)));
	}

	private boolean mineralTargetLocated() {
		return TerminalNavigationPayload.isMineral(navigation.targetKind()) && navigation.located();
	}

	private Component navigationTargetName(TerminalStructureTarget target) {
		Component normal = Component.translatable(
				"terminal.thefourthfrequency.navigation.target." + target.id());
		if (!target.sideRoute() || !sideRouteGlitchActive(renderAge)) return normal;
		long cycle = Math.max(1L, (long) Math.floor(renderAge / 40.0D));
		return Component.literal(corruptNavigationName(normal.getString(), target.wireId(), cycle));
	}

	/**
	 * Draws the destination buttons, reserving the last slot for the unstable signal whenever there
	 * is one.
	 *
	 * <p>Structures used to fill all three slots first, so the unstable signal - the one option here
	 * that belongs to the story rather than to convenience - disappeared without a trace exactly when
	 * the world had become rich enough to offer three structures. Structures are the ones that can be
	 * counted and mentioned instead; {@link #omittedNavigationTargets} is what says so.</p>
	 */
	private void drawNavigationToolList(GuiGraphics graphics) {
		navigationHits.clear();
		TerminalStructureTarget selected = tools.selectedNavigationTarget();
		boolean unstable = tools.unstableSignalAvailable();
		int structureSlots = TerminalUiLayout.TOOL_OPTION_SLOTS - (unstable ? 1 : 0);
		List<TerminalStructureTarget> targets = availableNavigationTargets();
		int index = 0;
		for (TerminalStructureTarget target : targets) {
			if (index >= structureSlots) break;
			TerminalUiLayout.Bounds bounds = TerminalUiLayout.navigationOptionBounds(index++);
			Component name = navigationTargetName(target);
			drawToolButton(graphics, bounds, name, selected == target);
			navigationHits.add(new NavigationHit(bounds, TerminalControlPayload.SELECT_STRUCTURE_TARGET, target.wireId()));
		}
		if (unstable) {
			TerminalUiLayout.Bounds bounds = TerminalUiLayout.navigationOptionBounds(index++);
			double pulse = (Math.sin(renderAge * 0.075D) + 1.0D) * 0.5D;
			int red = lerpColor(0xFF754D50, 0xFFE5A0A4, pulse);
			graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), 0xFF171012);
			graphics.renderOutline(bounds.left(), bounds.top(), bounds.width(), bounds.height(), red);
			drawCenteredFitted(graphics, Component.translatable(
					"terminal.thefourthfrequency.navigation.unstable_signal"), bounds, red);
			navigationHits.add(new NavigationHit(bounds, TerminalControlPayload.SELECT_NEAREST_UNSTABLE, 0));
		}
		if (index == 0) {
			drawCenteredFitted(graphics, Component.translatable("terminal.thefourthfrequency.navigation.no_targets"),
					TerminalUiLayout.TOOL_LIST_AREA, DIM);
		}
	}

	private List<TerminalStructureTarget> availableNavigationTargets() {
		List<TerminalStructureTarget> targets = new ArrayList<>();
		for (TerminalStructureTarget target : TerminalStructureTarget.values()) {
			if (tools.navigationTargetAvailable(target)) targets.add(target);
		}
		return targets;
	}

	/** How many destinations the option row could not show, so the detail text can say so. */
	private int omittedNavigationTargets() {
		int slots = TerminalUiLayout.TOOL_OPTION_SLOTS - (tools.unstableSignalAvailable() ? 1 : 0);
		return Math.max(0, availableNavigationTargets().size() - slots);
	}

	private void drawToolActions(GuiGraphics graphics, TerminalTool tool) {
		if (!tools.available(tool) || tools.toolsDisabled() && tool != TerminalTool.WEATHER) return;
		switch (tool) {
			case HOME -> {
				if (tools.payload().homeKnown()) drawGuidanceToggle(graphics, tool,
						TerminalUiLayout.TOOL_ACTION_FULL);
			}
			case MINERALS -> {
				// Selected-looking while probing or out of charges: in both cases the button is
				// inert, and it should look inert rather than inviting another press.
				drawToolButton(graphics, TerminalUiLayout.TOOL_ACTION_PRIMARY,
						Component.translatable("terminal.thefourthfrequency.tool.minerals.refresh"),
						tools.mineralScanning() || !tools.mineralProbeReady());
				if (tools.guidanceTool() == tool || mineralTargetLocated()) drawGuidanceToggle(graphics, tool,
						TerminalUiLayout.TOOL_ACTION_SECONDARY);
			}
			case PORTAL -> {
				if (tools.payload().portalKnown()) drawGuidanceToggle(graphics, tool,
						TerminalUiLayout.TOOL_ACTION_FULL);
			}
			case NAVIGATION -> {
				if (tools.guidanceTool() == tool || navigationTargetChosen()) drawGuidanceToggle(graphics, tool,
						TerminalUiLayout.TOOL_ACTION_FULL);
			}
			case STRONGHOLD -> {
				if (tools.payload().strongholdKnown()) drawGuidanceToggle(graphics, tool,
						TerminalUiLayout.TOOL_ACTION_FULL);
			}
			case WEATHER -> drawToolButton(graphics, TerminalUiLayout.TOOL_ACTION_FULL,
					Component.translatable(homeLiveTool == tool
							? "terminal.thefourthfrequency.tool.unpin"
							: "terminal.thefourthfrequency.tool.pin"), homeLiveTool == tool);
		}
	}

	private void drawGuidanceToggle(GuiGraphics graphics, TerminalTool tool, TerminalUiLayout.Bounds bounds) {
		boolean active = tools.guidanceTool() == tool;
		drawToolButton(graphics, bounds, Component.translatable(active
				? "terminal.thefourthfrequency.tool.stop"
				: "terminal.thefourthfrequency.tool.guide"), active);
	}

	private void drawToolButton(GuiGraphics graphics, TerminalUiLayout.Bounds bounds, Component label, boolean selected) {
		graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), selected ? SELECTED : 0xFF10140E);
		graphics.renderOutline(bounds.left(), bounds.top(), bounds.width(), bounds.height(), selected ? AMBER : GREEN);
		drawCenteredFitted(graphics, label, bounds, selected ? AMBER : GREEN);
	}

	private void drawRecords(GuiGraphics graphics) {
		var body = TerminalUiLayout.RECORDS_BODY;
		graphics.fill(body.left(), body.top(), body.right(), body.bottom(), GLASS);
		recordNavigationHits.clear();
		List<RecordRow> rows = recordRows();
		int visible = recordsVisibleRows();
		recordsScrollRow = Math.clamp(recordsScrollRow, 0, recordsMaxScroll());
		if (recordsSettleArmed) {
			recordsSettleArmed = false;
			recordsSettleStartMillis = renderNowMillis;
		}
		long settleElapsed = renderNowMillis - recordsSettleStartMillis;
		long settleBucket = TerminalGlyphSettle.bucket(renderNowMillis);
		int y = body.top() + 5;
		for (int index = recordsScrollRow; index < rows.size() && index < recordsScrollRow + visible; index++) {
			RecordRow row = rows.get(index);
			if (row.marker()) graphics.fill(body.left() + 4, y + 2, body.left() + 6, y + 7, row.color());
			if (row.settleText() != null) {
				// Staggered by position on screen, not by position in the list. A backfill is a
				// hundred and sixty entries; staggering by absolute index would take the last one
				// nineteen seconds to arrive, and the player would be looking at a settled page long
				// before then anyway. Off-screen rows have nothing to animate.
				TerminalGlyphRenderer.drawSettling(graphics, font, row.settleText(),
						body.left() + row.indent(), y, row.color(), recordsSettleSeed, index,
						TerminalGlyphSettle.rowProgress(settleElapsed, index - recordsScrollRow),
						settleBucket);
			} else if (row.text() != null) {
				graphics.drawString(font, row.text(), body.left() + row.indent(), y, row.color(), false);
			}
			if (row.shortcut()) drawRecordNavigationShortcut(graphics, body, row, y);
			y += ROW_HEIGHT;
		}
	}


	private int recordsVisibleRows() {
		return Math.max(1, (TerminalUiLayout.RECORDS_BODY.height() - 10) / ROW_HEIGHT);
	}

	private int recordsMaxScroll() {
		return Math.max(0, recordRows().size() - recordsVisibleRows());
	}

	private List<RecordRow> recordRows() {
		if (cachedRecordRows == null) cachedRecordRows = buildRecordRows();
		return cachedRecordRows;
	}

	/**
	 * Wraps the record log into rows and decides where each optional investigation's navigation
	 * shortcut goes: after the text when the last wrapped line leaves room, otherwise on an indented
	 * row of its own.
	 *
	 * <p>It used to be dropped outright in the second case. The player then saw a list where some
	 * investigations were actionable and others were not, with nothing on screen explaining the
	 * difference - the only thing that had changed was how long the location name happened to be.</p>
	 */
	private List<RecordRow> buildRecordRows() {
		var body = TerminalUiLayout.RECORDS_BODY;
		int width = body.width() - 16;
		int shortcutWidth = font.width(
				Component.translatable("terminal.thefourthfrequency.records.open_navigation"));
		List<RecordRow> rows = new ArrayList<>();
		boolean navigator = tools.available(TerminalTool.NAVIGATION);
		// Exactly what the navigation page draws its own unstable-signal option from.
		boolean investigationOffered = tools.unstableSignalAvailable();
		// The navigator gate lives in recordEntries so every reader of the log agrees on what is in
		// it - the home card's "recent" line is the first entry of this same list.
		for (TerminalLogEntryPayload entry : snapshot.recordEntries(navigator)) {
			int color = entry.unread() ? AMBER : GREEN;
			Component line = Component.literal("[" + snapshot.signalTime(entry) + "] ")
					.append(snapshot.signalEvent(entry));
			if (TerminalSnapshot.settlesIn(entry)) {
				// Wrapped against the clean text, then carried as plain strings. Wrapping once and
				// never again is what keeps the line breaks from moving while the glyphs resolve.
				for (net.minecraft.network.chat.FormattedText piece
						: font.getSplitter().splitLines(line, Math.max(1, width),
								net.minecraft.network.chat.Style.EMPTY)) {
					rows.add(new RecordRow(null, color, RECORD_INDENT, false, -1, piece.getString()));
				}
				continue;
			}
			List<FormattedCharSequence> wrapped = font.split(line, Math.max(1, width));
			if (wrapped.isEmpty()) rows.add(new RecordRow(null, color, RECORD_INDENT, entry.unread(), -1));
			for (int index = 0; index < wrapped.size(); index++) {
				rows.add(new RecordRow(wrapped.get(index), color, RECORD_INDENT,
						entry.unread() && index == 0, -1));
			}
			// A lead whose fragment has since been found keeps its line - the log says what happened -
			// but loses the shortcut, because the server refuses to retarget at a fragment already in
			// hand and a control that silently does nothing is worse than no control.
			//
			// The same reason is why the whole class of shortcut hangs off the server's own answer to
			// "is this investigation on offer" rather than off the row: the two used to be decided
			// separately and drifted, so the page drew a shortcut the click was refused for.
			int candidate = TerminalRecordPolicy.candidateEncodedIndex(entry.type());
			if (candidate < 0 || !investigationOffered || snapshot.candidateFragmentFound(entry.type())) continue;
			RecordRow last = rows.getLast();
			int textEnd = body.left() + last.indent() + (last.text() == null ? 0 : font.width(last.text()));
			if (textEnd + RECORD_SHORTCUT_GAP + shortcutWidth <= body.right() - 5) {
				rows.set(rows.size() - 1, last.withShortcut(candidate));
			} else {
				rows.add(new RecordRow(null, color, RECORD_SHORTCUT_INDENT, false, candidate));
			}
		}
		if (rows.isEmpty()) {
			for (FormattedCharSequence wrapped : font.split(
					Component.translatable("terminal.thefourthfrequency.records.empty"), Math.max(1, width))) {
				rows.add(new RecordRow(wrapped, DIM, RECORD_INDENT, false, -1));
			}
		}
		return List.copyOf(rows);
	}

	/**
	 * The shortcut that follows an optional-investigation line, in the bright green this terminal
	 * already reserves for "there is something you can act on here".
	 */
	private void drawRecordNavigationShortcut(GuiGraphics graphics, TerminalUiLayout.Bounds body,
			RecordRow row, int y) {
		Component label = Component.translatable("terminal.thefourthfrequency.records.open_navigation");
		int width = font.width(label);
		int textEnd = body.left() + row.indent() + (row.text() == null ? 0 : font.width(row.text()));
		int left = row.text() == null ? textEnd : textEnd + RECORD_SHORTCUT_GAP;
		graphics.drawString(font, label, left, y, CLAIMABLE, false);
		recordNavigationHits.add(new NavigationHit(
				new TerminalUiLayout.Bounds(left - 2, y - 1, left + width + 2, y + 9),
				TerminalControlPayload.SELECT_FRAGMENT_TARGET, row.candidate()));
	}

	/**
	 * Sends the player to the lead on <em>this</em> line, then opens the tool.
	 *
	 * <p>It used to only open the tool, leaving the player to press the navigator's own "unstable
	 * signal" button - which selects the nearest lead. With leads in two directions the shortcut
	 * therefore pointed somewhere other than the line it was drawn on, and nothing said so.
	 *
	 * <p>Aim before opening: both requests travel the same ordered channel, so the tool page opens
	 * onto a selection the server has already made rather than onto the previous target.
	 */
	private boolean handleRecordNavigationClick(double x, double y) {
		for (NavigationHit hit : recordNavigationHits) {
			if (!hit.bounds().contains(x, y)) continue;
			send(hit.action(), hit.value());
			openTool(TerminalTool.NAVIGATION);
			return true;
		}
		return false;
	}

	private void drawCard(GuiGraphics graphics, TerminalUiLayout.Bounds bounds, int outline) {
		drawCard(graphics, bounds, outline, null);
	}

	/** A card that answers the pointer. Pass {@code null} for the ones that are readouts, not controls. */
	private void drawCard(GuiGraphics graphics, TerminalUiLayout.Bounds bounds, int outline,
			TerminalMotionState.Control control) {
		graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
				interactiveColor(control, CARD_BODY));
		graphics.renderOutline(bounds.left(), bounds.top(), bounds.width(), bounds.height(), outline);
		// Brackets in the card's own accent, so a locked panel stays dim instead of being outlined
		// dim and then corner-marked bright.
		TerminalChrome.drawCornerBrackets(graphics, bounds, outline);
	}

	private Component toolName(TerminalTool tool) {
		return Component.translatable("terminal.thefourthfrequency.tool." + tool.id());
	}

	private void drawLog(GuiGraphics graphics) {
		drawLogDirectory(graphics);
	}

	private void drawLogDirectory(GuiGraphics graphics) {
		var body = TerminalUiLayout.FILE_BODY;
		graphics.fill(body.left(), body.top(), body.right(), body.bottom(), GLASS);
		graphics.fill(TerminalUiLayout.FILE_DIVIDER.left(), TerminalUiLayout.FILE_DIVIDER.top(),
				TerminalUiLayout.FILE_DIVIDER.right(), TerminalUiLayout.FILE_DIVIDER.bottom(), DARK_BORDER);
		int total = snapshot.files().size();
		fileListScroll = Math.clamp(fileListScroll, 0, TerminalUiLayout.fileMaxScrollRow(total));
		if (!selectedFileId.isEmpty()) selectedFile = indexOfFile(selectedFileId);
		for (int row = 0; row < TerminalUiLayout.FILE_LIST_VISIBLE_ROWS; row++) {
			int index = fileListScroll + row;
			if (index < total) drawDirectoryEntry(graphics, index, TerminalUiLayout.fileListRow(row));
		}
		if (total == 0) {
			drawCenteredFitted(graphics, Component.translatable("terminal.thefourthfrequency.file.empty"),
					TerminalUiLayout.FILE_LIST, DIM);
		}
		drawSelectedFileContent(graphics);
	}

	private void drawDirectoryEntry(GuiGraphics graphics, int index, TerminalUiLayout.Bounds cell) {
		if (index < 0 || index >= snapshot.files().size()) return;
		TerminalFilePayload file = snapshot.files().get(index);
		boolean focused = index == selectedFile || index == hoveredFile;
		graphics.fill(cell.left(), cell.top(), cell.right(), cell.bottom(),
				interactiveColor(TerminalMotionState.Control.fileRow(index - fileListScroll),
						focused ? CARD_BODY_FOCUSED : CARD_BODY));
		if (focused) graphics.fill(cell.left(), cell.top(), cell.left() + 2, cell.bottom(), pageAccent());
		boolean completeDiary = file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID);
		Component title = file.unlocked() || completeDiary || HiddenFilePolicy.isHiddenFile(file.id())
				? snapshot.fileTitle(file)
				: Component.translatable("terminal.thefourthfrequency.file.locked_title", snapshot.fileTitle(file));
		int color = HiddenFilePolicy.isHiddenFile(file.id()) ? DIM
				: completeDiary ? diaryTitleColor(file) : file.unlocked() ? GREEN : CYAN;
		List<FormattedCharSequence> titleRows = font.split(title, cell.width() - 10);
		if (!titleRows.isEmpty()) {
			graphics.drawString(font, titleRows.getFirst(), cell.left() + 5,
					cell.top() + Math.max(4, (cell.height() - font.lineHeight) / 2), color, false);
		}
	}

	private void drawSelectedFileContent(GuiGraphics graphics) {
		var content = TerminalUiLayout.FILE_CONTENT;
		if (selectedFile < 0 || detailFile == null) {
			drawCenteredFitted(graphics, Component.translatable("terminal.thefourthfrequency.file.select_prompt"), content, DIM);
			fileContentScroll = 0;
			return;
		}
		switch (logView) {
			case DIRECTORY, DETAIL -> drawLogDetail(graphics);
			case LOCKED_DIARY -> drawLockedDiary(graphics);
		}
	}

	private void drawLogDetail(GuiGraphics graphics) {
		var content = TerminalUiLayout.FILE_CONTENT;
		List<FileRow> rows = fileDetailRows();
		int viewportHeight = fileViewportHeight();
		fileContentScroll = Math.clamp(fileContentScroll, 0, fileContentMaxScroll());
		int y = content.top() + 4;
		for (int index = fileContentScroll; index < rows.size() && y < content.bottom() - 4; index++) {
			FileRow row = rows.get(index);
			if (row.text() != null) {
				drawScaledFileText(graphics, row, content.left() + FILE_TEXT_INSET, y);
			}
			y += row.height();
		}
		drawFileScrollbar(graphics, content, filePixelHeight(rows), viewportHeight);
	}

	private static int fileViewportHeight() {
		return TerminalUiLayout.FILE_CONTENT.height() - 8;
	}

	private int fileContentMaxScroll() {
		return maxFileScroll(fileDetailRows(), fileViewportHeight());
	}

	private List<FileRow> fileDetailRows() {
		if (cachedFileRows == null) cachedFileRows = buildFileDetailRows();
		return cachedFileRows;
	}

	private List<FileRow> buildFileDetailRows() {
		if (detailFile == null) return List.of();
		int wrapWidth = TerminalUiLayout.FILE_CONTENT.width() - FILE_TEXT_INSET * 2 - FILE_SCROLLBAR_GUTTER;
		List<FileRow> rows = new ArrayList<>(fileRows(snapshot.fileTitle(detailFile), wrapWidth,
				READING_TITLE, FILE_TITLE_SCALE, FILE_TITLE_ROW_HEIGHT));
		rows.add(FileRow.gap(FILE_TITLE_GAP));
		List<Component> paragraphs = snapshot.fileContent(detailFile);
		for (int index = 0; index < paragraphs.size(); index++) {
			boolean damagedNotice = HiddenFilePolicy.isHiddenFile(detailFile.id()) && index == 0;
			int color = damagedNotice
					|| detailFile.id().equals("encrypted_witness_file") && index == 0
					? READING_META : READING_TEXT;
			rows.addAll(fileRows(paragraphs.get(index), wrapWidth,
					color, damagedNotice ? FILE_NOTE_SCALE : FILE_BODY_SCALE,
					damagedNotice ? FILE_NOTE_ROW_HEIGHT : FILE_BODY_ROW_HEIGHT));
			if (index + 1 < paragraphs.size()) {
				rows.add(FileRow.gap(FILE_PARAGRAPH_GAP));
			}
		}
		return List.copyOf(rows);
	}

	private void drawFileScrollbar(GuiGraphics graphics, TerminalUiLayout.Bounds content,
			int totalHeight, int viewportHeight) {
		int maxScroll = fileContentMaxScroll();
		if (maxScroll <= 0 || totalHeight <= viewportHeight) return;
		int x = content.right() - 4;
		int top = content.top() + 4;
		int bottom = content.bottom() - 4;
		int trackHeight = bottom - top;
		graphics.fill(x, top, x + 2, bottom, DARK_BORDER);
		int thumbHeight = Math.max(8, Math.round(trackHeight * viewportHeight / (float) totalHeight));
		int thumbTop = top + Math.round((trackHeight - thumbHeight)
				* fileContentScroll / (float) maxScroll);
		graphics.fill(x, thumbTop, x + 2, thumbTop + thumbHeight, AMBER);
	}

	private List<FileRow> fileRows(Component text, int width, int color, float scale, int height) {
		int logicalWidth = Math.max(1, (int) Math.floor(width / scale));
		List<FormattedCharSequence> wrapped = font.split(text, logicalWidth);
		List<FileRow> rows = new ArrayList<>();
		if (wrapped.isEmpty()) rows.add(new FileRow(null, color, scale, height));
		else for (FormattedCharSequence line : wrapped) rows.add(new FileRow(line, color, scale, height));
		return rows;
	}

	private int maxFileScroll(List<FileRow> rows, int viewportHeight) {
		int suffixHeight = 0;
		for (int index = rows.size() - 1; index >= 0; index--) {
			if (suffixHeight + rows.get(index).height() > viewportHeight) return index + 1;
			suffixHeight += rows.get(index).height();
		}
		return 0;
	}

	private int filePixelHeight(List<FileRow> rows) {
		int height = 0;
		for (FileRow row : rows) height += row.height();
		return height;
	}

	private void drawScaledFileText(GuiGraphics graphics, FileRow row, int x, int y) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(row.scale(), row.scale());
		graphics.drawString(font, row.text(), 0, 0, row.color(), false);
		graphics.pose().popMatrix();
	}

	private void drawLockedDiary(GuiGraphics graphics) {
		var body = TerminalUiLayout.FILE_CONTENT;
		if (detailFile != null && detailFile.unlocked()) {
			drawLogDetail(graphics);
			return;
		}
		int centerX = (body.left() + body.right()) / 2;
		int centerY = (body.top() + body.bottom()) / 2;
		int percent = snapshot.hiddenFileReadPercent();
		graphics.drawCenteredString(font, Component.translatable("terminal.thefourthfrequency.file.locked"),
				centerX, centerY - 25, DIM);
		graphics.drawCenteredString(font, Component.literal(percent + "%"), centerX, centerY - 8, AMBER);
		int barLeft = body.left() + 24;
		int barRight = body.right() - 24;
		int barTop = centerY + 8;
		graphics.fill(barLeft, barTop, barRight, barTop + 10, PROGRESS_TRACK);
		graphics.renderOutline(barLeft, barTop, barRight - barLeft, 10, DARK_BORDER);
		int fillWidth = Math.round((barRight - barLeft - 4) * percent / 100.0F);
		graphics.fill(barLeft + 2, barTop + 2, barLeft + 2 + fillWidth, barTop + 8,
				percent == 100 ? GREEN : AMBER);
	}

	private void drawFooter(GuiGraphics graphics, Component hint, boolean ignored) {
		var footer = TerminalUiLayout.FOOTER;
		graphics.drawString(font, hint, footer.left(), footer.top() + 3, DIM, false);
	}

	private void drawScope(GuiGraphics graphics) {
		var scope = TerminalUiLayout.SCOPE;
		graphics.fill(scope.left(), scope.top(), scope.right(), scope.bottom(), SCOPE_BACKGROUND);
		graphics.renderOutline(scope.left(), scope.top(), scope.width(), scope.height(), BRASS_BEZEL);
		for (int x = scope.left() + 12; x < scope.right(); x += 20)
			graphics.fill(x, scope.top() + 2, x + 1, scope.bottom() - 2, SCOPE_GRID);
		for (int y = scope.top() + 12; y < scope.bottom(); y += 16)
			graphics.fill(scope.left() + 2, y, scope.right() - 2, y + 1, SCOPE_GRID);
		int centerY = (scope.top() + scope.bottom()) / 2;
		int previous = centerY + (int) Math.round(currentWaveOffset(0, renderNowMillis, renderAge));
		int waveLeft = scope.left() + 4;
		int waveWidth = scope.width() - 8;
		for (int sample = 1; sample < WAVE_SAMPLES; sample++) {
			int value = centerY + (int) Math.round(currentWaveOffset(sample, renderNowMillis, renderAge));
			int x = waveLeft + (int) Math.round((waveWidth - 1) * sample / (double) (WAVE_SAMPLES - 1));
			graphics.fill(x, Math.min(previous, value), x + 1, Math.max(previous, value) + 1,
					currentSignalColor(renderNowMillis));
			previous = value;
		}
		graphics.fill(scope.left() + 2, scope.top() + 2, scope.right() - 2, scope.top() + 3, SCOPE_HIGHLIGHT);
	}

	private void drawCompass(GuiGraphics graphics) {
		var compass = TerminalUiLayout.COMPASS;
		int cx = (compass.left() + compass.right()) / 2;
		int cy = (compass.top() + compass.bottom()) / 2;
		// The dial is drawn a few pixels tighter than the bezel it used to fill, because the cardinal
		// labels have to live somewhere and the panel leaves only five or six pixels of clearance
		// above and below the compass. Pulling the rings in buys that room without moving anything
		// else on the hardware side.
		drawPixelCircle(graphics, cx, cy, 16, BRASS_BEZEL);
		drawPixelCircle(graphics, cx, cy, 14, INSTRUMENT_WELL);
		drawPixelCircle(graphics, cx, cy, 12, COMPASS_FACE);
		drawCompassLabel(graphics, cx, cy - COMPASS_LABEL_RADIUS, "north", HOT);
		drawCompassLabel(graphics, cx + COMPASS_LABEL_RADIUS, cy, "east", DIM);
		drawCompassLabel(graphics, cx, cy + COMPASS_LABEL_RADIUS, "south", DIM);
		drawCompassLabel(graphics, cx - COMPASS_LABEL_RADIUS, cy, "west", DIM);

		double flashAge = renderAge - navigationNeedleFlashStartedAt;
		if (targetNeedleVisible(tools.guidanceTool() != null, navigation.navigable(), flashAge)) {
			drawTargetNeedle(graphics, cx, cy, mineralNeedle);
		}
		drawFacingNeedle(graphics, cx, cy, facingNeedle);
		graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, INSTRUMENT_WELL);
		graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, AMBER);
	}

	/**
	 * One cardinal label on the dial. North stays the highlighted one because it is the reference the
	 * whole face is oriented to; the other three are dim, being a scale rather than a reading.
	 */
	private void drawCompassLabel(GuiGraphics graphics, int x, int y, String direction, int color) {
		String label = Component.translatable("terminal.thefourthfrequency.compass." + direction).getString();
		graphics.drawString(font, label, x - font.width(label) / 2, y - font.lineHeight / 2, color, false);
	}

	private void drawReceiverSlider(GuiGraphics graphics) {
		var slider = TerminalUiLayout.RECEIVER_SLIDER;
		graphics.fill(slider.left(), slider.top(), slider.right(), slider.bottom(), INSTRUMENT_WELL);
		// While the profile is asking, the control answers for itself. Players looked at a screen of
		// options and went hunting for something to click, because nothing on the panel said the
		// answer was over here - and no amount of hint text fixes that, since the hint is on the same
		// side of the screen as the problem. Lighting the physical control is the shortest sentence
		// available: the thing that is going to move is the thing that is glowing.
		//
		// A raised cosine, the same curve the walkthrough's tab pointer breathes on. Continuous, so
		// there is no state change owing a minimum hold, and at one cycle per two seconds it is
		// nowhere near the flicker ceiling.
		if (profileAsking()) {
			double pulse = TerminalMotion.breathe(renderAge, TerminalOnboardingPolicy.PULSE_PERIOD_TICKS);
			int ring = TerminalMotion.lerpColor(pageAccent(), CLAIMABLE, pulse);
			graphics.renderOutline(slider.left() - 1, slider.top() - 1,
					slider.width() + 2, slider.height() + 2, ring);
			graphics.renderOutline(slider.left(), slider.top(), slider.width(), slider.height(), ring);
		} else {
			graphics.renderOutline(slider.left(), slider.top(), slider.width(), slider.height(), BRASS_BEZEL);
		}
		int trackY = (slider.top() + slider.bottom()) / 2;
		graphics.fill(slider.left() + 3, trackY - 1, slider.right() - 3, trackY + 2, 0xFF423D23);
		for (int tick = 0; tick <= 10; tick++) {
			int x = slider.left() + (int) Math.round((slider.width() - 1) * tick / 10.0D);
			graphics.fill(x, slider.top() + 3, x + 1, slider.top() + 7, 0xFF827747);
			graphics.fill(x, slider.bottom() - 7, x + 1, slider.bottom() - 3, 0xFF827747);
		}
		int displayedTuning = (int) Math.round(tuningTransition.valueAt(renderNowMillis));
		int thumb = TerminalUiLayout.sliderX(displayedTuning);
		graphics.fill(thumb - 3, slider.top() + 3, thumb + 4, slider.bottom() - 3,
				profileAsking() ? CLAIMABLE : 0xFFB9A561);
		graphics.renderOutline(thumb - 3, slider.top() + 3, 7, slider.height() - 6, 0xFFF1D98B);
	}

	private void drawLcd(GuiGraphics graphics) {
		var lcd = TerminalUiLayout.RECEIVER_LCD;
		graphics.fill(lcd.left(), lcd.top(), lcd.right(), lcd.bottom(), LCD_BACKGROUND);
		graphics.renderOutline(lcd.left(), lcd.top(), lcd.width(), lcd.height(), LCD_BORDER);
		Component lineOne;
		Component lineTwo;
		int lineOneColor = GREEN;
		int lineTwoColor = DIM;
		if (profileAsking()) {
			// The profile's own readout. The receiver tool is not unlocked yet, so the gameplay branch
			// below would leave the meter dead - and the meter is the only thing orienting a player who
			// is being asked to find an answer they cannot read yet. Same strings as the tool, because
			// this is that instrument: what the player learns here is how the receiver behaves.
			int question = snapshot.profileQuestion();
			int strength = TerminalProfileQuestionnaire.strength(question, tuning);
			int locked = TerminalProfileQuestionnaire.lockedOption(question, tuning);
			lineOne = Component.translatable("terminal.thefourthfrequency.receiver.strength", strength);
			if (locked < 0) {
				lineTwo = Component.translatable("terminal.thefourthfrequency.receiver.search");
				lineTwoColor = HOT;
			} else {
				lineTwo = Component.translatable("terminal.thefourthfrequency.receiver.locking",
						profileHeldTicks, TerminalProfileQuestionnaire.COMMIT_HOLD_TICKS);
				lineTwoColor = AMBER;
			}
		} else if (receiverGameplayActive()) {
			int strength = receiverStrength(tuning);
			boolean locked = receiverLocked(tuning);
			lineOne = Component.translatable("terminal.thefourthfrequency.receiver.strength", strength);
			if (!locked) {
				lineTwo = Component.translatable("terminal.thefourthfrequency.receiver.search");
				lineTwoColor = HOT;
			} else if (tools.receiverLockTicks() < 20) {
				lineTwo = Component.translatable("terminal.thefourthfrequency.receiver.locking",
						tools.receiverLockTicks(), 20);
				lineTwoColor = AMBER;
			} else {
				lineTwo = Component.translatable("terminal.thefourthfrequency.receiver.locked");
				lineTwoColor = AMBER;
			}
		} else {
			lineOne = Component.translatable("terminal.thefourthfrequency.receiver.label");
			lineTwo = Component.translatable(tools.receiverAvailable()
					? "terminal.thefourthfrequency.receiver.open_navigation"
					: "terminal.thefourthfrequency.receiver.mechanical_only");
			if (tools.receiverAvailable()) lineTwoColor = HOT;
		}
		drawFittedLine(graphics, lineOne, TerminalUiLayout.LCD_LINE_ONE, lineOneColor);
		drawFittedLine(graphics, lineTwo, TerminalUiLayout.LCD_LINE_TWO, lineTwoColor);
	}

	private double currentWaveOffset(int sample, long nowMillis, double phaseAge) {
		double progress = tuningTransition.progressAt(nowMillis);
		double target = targetWaveOffset(tuning, sample, phaseAge, nowMillis);
		return waveFromSamples[sample] + (target - waveFromSamples[sample]) * progress;
	}

	private double targetWaveOffset(int tuningValue, int sample, double phaseAge, long nowMillis) {
		double coherence = tools.receiverAvailable() ? receiverStrength(tuningValue) / 100.0D : 0.0D;
		double x = sample;
		double primary = Math.sin((x + phaseAge * 2.1D) * (0.31D - coherence * 0.20D)
				+ tuningValue * 0.085D);
		double secondary = Math.sin(x * (1.17D - coherence * 0.94D) + phaseAge * 0.19D)
				* (0.52D - coherence * 0.28D);
		double receiver = (primary + secondary) * (9.0D - coherence * 3.0D);
		double cycle = positiveModulo(x + phaseAge * 1.35D, 31.0D) / 31.0D;
		double electrocardiogram = Math.sin((x + phaseAge * 1.35D) * 0.08D) * 0.45D
				- gaussian(cycle, 0.18D, 0.045D) * 2.0D
				+ gaussian(cycle, 0.345D, 0.022D) * 3.0D
				- gaussian(cycle, 0.385D, 0.016D) * 16.0D
				+ gaussian(cycle, 0.435D, 0.024D) * 7.0D
				- gaussian(cycle, 0.68D, 0.075D) * 3.5D;
		double morph = waveformMorphTransition.valueAt(nowMillis);
		double pulse = AmbientAnomalyClient.pulse();
		double anomaly = pulse <= 0.0F ? 0.0D : Math.exp(-Math.pow((sample % 43 - 14) / 4.0D, 2.0D)) * pulse * 12.0D;
		// The approach term. Deliberately not a separate shape the eye could learn to name - it rides
		// on the trace that is already there and makes it restless, so what a player eventually
		// notices is "the scope has been strange for a bit" rather than a symbol appearing. See
		// OscilloscopeWaveformPolicy for why an instrument is allowed to know this and a label is not.
		double approach = OscilloscopeWaveformPolicy.disturbance(snapshot.anomalyApproach());
		double restlessness = approach == 0.0D ? 0.0D
				: Math.sin(x * 2.7D + phaseAge * 5.3D) * Math.sin(x * 0.61D - phaseAge * 3.1D)
						* approach * 6.5D;
		return Math.clamp(receiver + (electrocardiogram - receiver) * morph - anomaly * 0.55D
						+ restlessness,
				-17.0D, 17.0D);
	}

	private int currentSignalColor(long nowMillis) {
		double progress = Math.clamp((nowMillis - waveColorStartedAtMillis) / (double) WAVE_COLOR_MILLIS,
				0.0D, 1.0D);
		progress = progress * progress * (3.0D - 2.0D * progress);
		return lerpColor(waveFromColor, waveTargetColor, progress);
	}

	private int signalColor(int tuningValue) {
		if (tools.receiverAvailable() && receiverLocked(tuningValue)) return AMBER;
		if (tools.receiverAvailable()) return HOT;
		if (snapshot.visualStage() >= 2) return HOT;
		return snapshot.bandStage() > 0 && snapshot.visualStage() > 0 ? CYAN : GREEN;
	}

	private void retargetTuningVisual(int value, long nowMillis) {
		for (int sample = 0; sample < WAVE_SAMPLES; sample++) {
			waveFromSamples[sample] = currentWaveOffset(sample, nowMillis, renderAge);
		}
		tuningTransition.retarget(value, nowMillis);
		tuning = value;
		waveformMorphTransition.retarget(waveformMorphTarget(tuning), nowMillis);
		retargetSignalColor(signalColor(tuning), nowMillis);
	}

	private void retargetSignalColor(int color, long nowMillis) {
		if (color == waveTargetColor) return;
		waveFromColor = currentSignalColor(nowMillis);
		waveTargetColor = color;
		waveColorStartedAtMillis = nowMillis;
	}

	private double waveformMorphTarget(int tuningValue) {
		double base = snapshot.visualStage() >= 2 ? STAGE_TWO_WAVEFORM_MORPH
				: snapshot.visualStage() > 0 ? STAGE_ONE_WAVEFORM_MORPH : 0.0D;
		if (!tools.receiverAvailable()) return base;
		double receiver = receiverLocked(tuningValue)
				? RECEIVER_LOCK_WAVEFORM_MORPH : receiverStrength(tuningValue) * 0.0025D;
		return Math.max(base, receiver);
	}

	private boolean receiverGameplayActive() {
		return tools.receiverAvailable() && !tools.toolsDisabled();
	}

	private boolean receiverMechanicalInteractive() {
		return !tools.toolsDisabled();
	}

	private boolean receiverLocked(int value) {
		return tools.receiverAvailable()
				&& TerminalControlPolicy.receiverLocked(value, tools.receiverTarget());
	}

	private int receiverStrength(int value) {
		return tools.receiverAvailable()
				? TerminalControlPolicy.receiverStrength(value, tools.receiverTarget()) : 0;
	}

	private static double gaussian(double value, double center, double width) {
		double normalized = (value - center) / width;
		return Math.exp(-(normalized * normalized));
	}

	private static double positiveModulo(double value, double divisor) {
		double result = value % divisor;
		return result < 0.0D ? result + divisor : result;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		// Every control below is a commit: claiming a reward, toggling guidance, grabbing the dial.
		// Without this gate a right- or middle-click did all of them, which is not what any of those
		// buttons look like they promise.
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled);
		double[] local = local(event.x(), event.y());
		// A press ends the check. Only one aimed at the page area is eaten with it: that is the part
		// standing empty, and a click there would otherwise reach a control the player cannot see.
		// The tabs and the hardware column were drawn the whole time and answer normally.
		if (selfTestRunning()) {
			selfTestSkipped = true;
			if (TerminalUiLayout.PAGE_BODY.contains(local[0], local[1])) return true;
		}
		// Before the action rather than after it, so a press the boundary below refuses - a locked
		// tool cell, a dial the receiver is not offering - still acknowledges the click. Feedback is
		// about the input arriving, not about it being granted.
		motion.press(controlAt(local[0], local[1]), nowMillis());
		// While the walkthrough holds the exit, the only control on the panel is the tab it is
		// pointing at. selectPage refuses the wrong one, so this only has to route tab clicks.
		if (onboardingLocksExit()) {
			// The profile is answered on the dial, so the dial has to be reachable while it is up.
			// This gate used to route tab clicks and refuse everything else, which was right when the
			// only thing the walkthrough ever asked for was a tab - and silently made the receiver
			// unusable the moment a step started asking for something else.
			if (profileAsking() && TerminalUiLayout.RECEIVER_SLIDER.contains(local[0], local[1])) {
				draggingTuner = true;
				updateTuningFromSlider(local[0]);
				return true;
			}
			// The walkthrough's own button. It does not advance anything by itself: it issues the
			// exact page visit the tab click would have, down to the same selectPage call, so the
			// server still sees an ordinary visit and learn_terminal still completes off the visit
			// rather than off the client saying it played an animation. What it removes is the
			// hunt - a new player no longer has to find the one tab in four that is not dimmed.
			if (onboardingAdvanceClicked(local[0], local[1])) {
				TerminalPage target = TerminalOnboardingPolicy.target(onboardingPhase);
				return target != null && selectPage(target);
			}
			// The tab strip is not a control while the walkthrough holds the terminal. It used to be -
			// clicking the one tab that was not dimmed is how the walkthrough originally advanced - and
			// leaving it live after the button arrived meant the strip was three quarters dead and one
			// quarter secretly live, which is a worse thing for a first-boot tutorial to teach than
			// either extreme. One control, in one place, doing the advancing.
			refuseOnboardingInput();
			return true;
		}
		if (TerminalUiLayout.HOME_TAB.contains(local[0], local[1])) return selectPage(TerminalPage.HOME);
		if (TerminalUiLayout.TOOLS_TAB.contains(local[0], local[1])) return selectPage(TerminalPage.TOOLS);
		if (TerminalUiLayout.RECORDS_TAB.contains(local[0], local[1])) return selectPage(TerminalPage.RECORDS);
		if (TerminalUiLayout.FILES_TAB.contains(local[0], local[1])) return selectPage(TerminalPage.FILES);
		if (receiverMechanicalInteractive() && TerminalUiLayout.RECEIVER_SLIDER.contains(local[0], local[1])) {
			draggingTuner = true;
			updateTuningFromSlider(local[0]);
			return true;
		}
		if (page == TerminalPage.HOME) {
			// The task card is a readout, not a button. Rewards arrive the moment a task completes,
			// so a claim control could only ever ask for something the player already had - and the
			// packet it sent (TerminalControlPayload.CLAIM_TASK_REWARD) would then also pay out every
			// other finished task at once. The server still answers that action for older clients.
			if (homeLiveTool != null) {
				if (TerminalUiLayout.HOME_TOOL_CLOSE.contains(local[0], local[1])) {
					closeHomeLiveTool();
					return true;
				}
				if (TerminalUiLayout.HOME_TOOL_DETAIL.contains(local[0], local[1])) {
					openTool(homeLiveTool);
					return true;
				}
			} else if (TerminalUiLayout.HOME_QUICK_PRIMARY.contains(local[0], local[1])) {
				TerminalTool recommended = tools.recommendedPrimaryTool();
				if (recommended != null) openTool(recommended);
				return true;
			} else if (TerminalUiLayout.HOME_QUICK_SECONDARY.contains(local[0], local[1])) {
				TerminalTool recommended = tools.recommendedSecondaryTool();
				if (recommended != null) openTool(recommended);
				return true;
			}
		}
		if (page == TerminalPage.TOOLS) {
			if (selectedTool != null && TerminalUiLayout.TOOL_BACK.contains(local[0], local[1])) {
				return backFromToolDetail();
			}
			if (selectedTool == null) {
				TerminalTool tool = TerminalTool.fromSlot(TerminalUiLayout.toolSlotAt(local[0], local[1]));
				if (tool != null && tools.available(tool)) {
					openTool(tool);
					return true;
				}
				if (tool != null) {
					// The cell stays closed, but the click is answered: the reason pins under the grid
					// instead of the player pressing a padlock that does nothing at all.
					lockedHintTool = tool;
					TerminalClientAudio.fault();
					return true;
				}
			} else if (handleToolAction(local[0], local[1])) return true;
		}
		if (page == TerminalPage.RECORDS && handleRecordNavigationClick(local[0], local[1])) return true;
		if (page == TerminalPage.FILES && handleLogClick(local[0], local[1])) return true;
		return super.mouseClicked(event, doubled);
	}

	private boolean handleToolAction(double x, double y) {
		if (selectedTool == null || !tools.available(selectedTool)
				|| tools.toolsDisabled() && selectedTool != TerminalTool.WEATHER) return false;
		switch (selectedTool) {
			case HOME -> { }
			case MINERALS -> {
				if (TerminalUiLayout.TOOL_ACTION_PRIMARY.contains(x, y)
						&& !tools.mineralScanning() && tools.mineralProbeReady()) {
					send(TerminalControlPayload.REQUEST_RESCAN, 0);
					TerminalClientAudio.commit();
					return true;
				}
			}
			case NAVIGATION -> {
				if (TerminalUiLayout.TOOL_OPTION_ROW.contains(x, y)
						&& handleNavigationClick(x, y)) return true;
			}
			case PORTAL, STRONGHOLD, WEATHER -> { }
		}
		if (selectedTool == TerminalTool.WEATHER && TerminalUiLayout.TOOL_ACTION_FULL.contains(x, y)) {
			togglePinnedTool();
			return true;
		}
		TerminalUiLayout.Bounds guidanceBounds = selectedTool == TerminalTool.MINERALS
				? TerminalUiLayout.TOOL_ACTION_SECONDARY : TerminalUiLayout.TOOL_ACTION_FULL;
		if (selectedTool != TerminalTool.WEATHER && guidanceBounds.contains(x, y)
				&& canToggleGuidance(selectedTool)) {
			toggleGuidance();
			return true;
		}
		return false;
	}

	/**
	 * Whether the navigator has a destination to start guiding to.
	 *
	 * <p>{@link #localNavigationTargetChosen} only ever tracked the <em>structure</em> targets: it is
	 * set optimistically when one of the option buttons is pressed and then recomputed from
	 * {@code selectedNavigationTarget}, which is {@code NONE} for an optional investigation. So the
	 * one destination that does not come from those buttons - a lead, chosen either from the records
	 * page shortcut or from the navigator's own option - reached the server, showed up in the readout,
	 * and had no "start guidance" button under it. The optimistic flag hid this for a frame or two
	 * after a press on the option itself, then the next tool snapshot cleared it and the button went
	 * away again while the target was still selected.
	 *
	 * <p>The lead's own answer therefore comes from the navigation payload, which is where the server
	 * says what the target actually is.</p>
	 */
	private boolean navigationTargetChosen() {
		return localNavigationTargetChosen
				|| navigation.targetKind() == TerminalNavigationPayload.UNSTABLE_SIGNAL;
	}

	private boolean canToggleGuidance(TerminalTool tool) {
		if (tools.guidanceTool() == tool) return true;
		return switch (tool) {
			case HOME -> tools.payload().homeKnown();
			case MINERALS -> mineralTargetLocated();
			case PORTAL -> tools.payload().portalKnown();
			case NAVIGATION -> navigationTargetChosen();
			case STRONGHOLD -> tools.payload().strongholdKnown();
			case WEATHER -> false;
		};
	}

	private void toggleGuidance() {
		navigationNeedleFlashStartedAt = renderAge;
		if (tools.guidanceTool() == selectedTool) {
			send(TerminalControlPayload.STOP_GUIDANCE, 0);
			if (homeLiveTool == selectedTool) homeLiveTool = null;
			TerminalClientAudio.commit();
			return;
		}
		send(TerminalControlPayload.START_GUIDANCE, selectedTool.slot());
		homeLiveTool = selectedTool;
		returnHomeAfterToolActivation();
	}

	private void togglePinnedTool() {
		if (homeLiveTool == selectedTool) {
			homeLiveTool = null;
			TerminalClientAudio.backLevel();
			return;
		}
		if (tools.guidanceTool() != null) send(TerminalControlPayload.STOP_GUIDANCE, 0);
		homeLiveTool = selectedTool;
		returnHomeAfterToolActivation();
	}

	private void returnHomeAfterToolActivation() {
		clearSelectedTool(false);
		enterPage(TerminalPage.HOME);
		setMode(TerminalPage.HOME.wireMode());
		// The order reaching the server outranks the page move it happens to end with.
		TerminalClientAudio.commit();
	}

	private void closeHomeLiveTool() {
		if (tools.guidanceTool() != null) send(TerminalControlPayload.STOP_GUIDANCE, 0);
		homeLiveTool = null;
		TerminalClientAudio.backLevel();
	}

	private boolean backFromToolDetail() {
		if (selectedTool == null) return false;
		boolean returnHome = toolOpenedFromHome;
		clearSelectedTool(true);
		if (returnHome) {
			enterPage(TerminalPage.HOME);
			setMode(TerminalPage.HOME.wireMode());
		}
		return true;
	}

	private boolean handleNavigationClick(double x, double y) {
		for (NavigationHit hit : navigationHits) {
			if (!hit.bounds().contains(x, y)) continue;
			localNavigationTargetChosen = true;
			navigationNeedleFlashStartedAt = renderAge;
			send(hit.action(), hit.value());
			TerminalClientAudio.commit();
			return true;
		}
		return false;
	}

	private boolean handleLogClick(double x, double y) {
		int index = TerminalUiLayout.fileIndexAt(x, y, fileListScroll, snapshot.files().size());
		if (index >= 0) {
			openDirectoryEntry(index);
			return true;
		}
		return TerminalUiLayout.FILE_BODY.contains(x, y);
	}

	private void openDirectoryEntry(int index) {
		if (index < 0 || index >= snapshot.files().size()) return;
		selectedFile = index;
		TerminalFilePayload file = snapshot.files().get(index);
		selectedFileId = file.id();
		setDetailFile(file);
		fileContentScroll = 0;
		if (file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID)) {
			logView = file.unlocked() ? LogView.DETAIL : LogView.LOCKED_DIARY;
			if (file.unlocked()) send(TerminalControlPayload.READ_TRUTH_FILE, 0);
		} else {
			logView = LogView.DETAIL;
			int hiddenIndex = HiddenFilePolicy.indexOf(file.id());
			if (hiddenIndex >= 0) send(TerminalControlPayload.READ_HIDDEN_FILE, hiddenIndex);
		}
		TerminalClientAudio.openLevel();
	}

	private void openDetail(TerminalFilePayload file) {
		logView = LogView.DETAIL;
		setDetailFile(file);
		selectedFileId = file.id();
		selectedFile = indexOfFile(file.id());
		fileContentScroll = 0;
		int hiddenIndex = HiddenFilePolicy.indexOf(file.id());
		if (hiddenIndex >= 0) {
			send(TerminalControlPayload.READ_HIDDEN_FILE, hiddenIndex);
		} else if (file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID) && file.unlocked()) {
			send(TerminalControlPayload.READ_TRUTH_FILE, 0);
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		double[] local = local(event.x(), event.y());
		if (draggingTuner) {
			updateTuningFromSlider(local[0]);
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingTuner) {
			draggingTuner = false;
			TerminalClientAudio.endTuningInput();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (verticalAmount == 0.0D) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		int delta = verticalAmount > 0.0D ? -1 : 1;
		double[] local = local(mouseX, mouseY);
		if (page == TerminalPage.TOOLS && selectedTool != null
				&& TerminalUiLayout.TOOL_DETAIL.contains(local[0], local[1])) {
			toolDetailScroll = TerminalUiLayout.scroll(toolDetailScroll, delta, toolDetailMaxScroll(selectedTool));
		} else if (page == TerminalPage.RECORDS && TerminalUiLayout.RECORDS_BODY.contains(local[0], local[1])) {
			recordsScrollRow = TerminalUiLayout.scroll(recordsScrollRow, delta, recordsMaxScroll());
		} else if (page == TerminalPage.FILES && TerminalUiLayout.FILE_LIST.contains(local[0], local[1])) {
			fileListScroll = TerminalUiLayout.scroll(fileListScroll, delta,
					TerminalUiLayout.fileMaxScrollRow(snapshot.files().size()));
		} else if (page == TerminalPage.FILES && TerminalUiLayout.FILE_CONTENT.contains(local[0], local[1])) {
			fileContentScroll = TerminalUiLayout.scroll(fileContentScroll, delta, fileContentMaxScroll());
		} else if (receiverMechanicalInteractive() && TerminalUiLayout.RECEIVER_SLIDER.contains(local[0], local[1])) {
			setTuning(tuning - delta);
		} else return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Any key ends the power-on check, and is then handled as though it had never run. Escape
		// included: a player who opened the terminal to read a pursuit warning must not be made to
		// watch a status line finish printing, and must not lose the way out to it either.
		selfTestSkipped = true;
		// Swallows everything the walkthrough is not asking for, Escape included. shouldCloseOnEsc
		// already refuses vanilla's own Escape path; this stops the key reaching anything else on
		// the way there.
		// The profile is answered on a control that is eighty-four pixels wide with a commit window of
		// about ten. Leaving it to the mouse alone would put a narrative beat behind a test of fine
		// motor control, in the one sequence the player is not allowed to leave. Arrows step the dial,
		// shift jumps a station, and Enter is the hold - all of which route through exactly the same
		// paths the mouse does, so the keyboard cannot reach anything the mouse could not.
		if (profileActive()) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_LEFT -> setTuning(tuning - profileTuningStep(event));
				case GLFW.GLFW_KEY_RIGHT -> setTuning(tuning + profileTuningStep(event));
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
					int locked = TerminalProfileQuestionnaire.lockedOption(snapshot.profileQuestion(), tuning);
					if (locked < 0) {
						refuseOnboardingInput();
					} else {
						profileHeldTicks = 0;
						profileHeldOption = -1;
						TerminalClientAudio.noticeStable();
						send(TerminalControlPayload.ANSWER_PROFILE, locked);
					}
				}
				default -> refuseOnboardingInput();
			}
			return true;
		}
		if (onboardingLocksExit()) {
			// The number keys are the tab strip by another name, and they are refused for the same
			// reason: while the walkthrough is up, the button is the only thing that turns a page.
			refuseOnboardingInput();
			return true;
		}
		if (event.key() >= GLFW.GLFW_KEY_1 && event.key() <= GLFW.GLFW_KEY_4) {
			selectPage(TerminalPage.fromIndex(event.key() - GLFW.GLFW_KEY_1));
			return true;
		}
		// The page gets first refusal, then the hardware column. No page claims the arrow keys the
		// dial uses today, but the dial claiming them first means the page never could - and the
		// binding it lost would have gone missing with nothing on screen to explain it.
		if (pageKeyPressed(event)) return true;
		if (receiverMechanicalInteractive()
				&& (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
			setTuning(tuning + (event.key() == GLFW.GLFW_KEY_LEFT ? -1 : 1));
			return true;
		}
		return super.keyPressed(event);
	}

	/**
	 * How far one arrow press moves the dial during the profile.
	 *
	 * <p>One unit normally, so a player can creep onto a station. Held with shift it steps by the
	 * lock radius, which crosses the band in a dozen presses instead of ninety - the difference
	 * between a keyboard path that exists and one that is usable.
	 */
	private static int profileTuningStep(KeyEvent event) {
		return event.hasShiftDown() ? TerminalProfileQuestionnaire.LOCK_RADIUS : 1;
	}

	private boolean pageKeyPressed(KeyEvent event) {
		if (page == TerminalPage.TOOLS && selectedTool != null
				&& (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)) {
			toolDetailScroll = TerminalUiLayout.scroll(toolDetailScroll,
					event.key() == GLFW.GLFW_KEY_UP ? -1 : 1, toolDetailMaxScroll(selectedTool));
			return true;
		}
		if (page == TerminalPage.RECORDS && (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)) {
			recordsScrollRow = TerminalUiLayout.scroll(recordsScrollRow,
					event.key() == GLFW.GLFW_KEY_UP ? -1 : 1, recordsMaxScroll());
			return true;
		}
		if (page == TerminalPage.FILES) {
			if (event.key() == GLFW.GLFW_KEY_ENTER) {
				openDirectoryEntry(selectedFile);
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN) {
				moveFileSelection(event.key() == GLFW.GLFW_KEY_UP ? -1 : 1);
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_PAGE_UP || event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
				fileContentScroll = TerminalUiLayout.scroll(fileContentScroll,
						event.key() == GLFW.GLFW_KEY_PAGE_UP ? -3 : 3, fileContentMaxScroll());
				return true;
			}
		}
		return false;
	}

	private boolean selectPage(TerminalPage next) {
		// One gate for every route into a page change: the tabs, the number keys, and the tool
		// screens that return home.
		if (!TerminalOnboardingPolicy.allowsPage(onboardingPhase, next)) {
			refuseOnboardingInput();
			return true;
		}
		send(TerminalControlPayload.VISIT_PAGE, next.ordinal());
		advanceOnboarding(next);
		if (next == page) {
			if (next == TerminalPage.RECORDS && snapshot.unreadCount() > 0) {
				send(TerminalControlPayload.MARK_RECORDS_READ, 0);
				recordsAcknowledged = true;
				TerminalClientAudio.acknowledge();
			}
			if (next == TerminalPage.FILES && snapshot.unreadFileCount() > 0) {
				send(TerminalControlPayload.MARK_FILES_SEEN, 0);
				filesAcknowledged = true;
				TerminalClientAudio.acknowledge();
			}
			if (selectedTool != null && (next == TerminalPage.TOOLS || next == TerminalPage.HOME)) {
				clearSelectedTool(true);
			}
			return true;
		}
		TerminalPage previous = page;
		if (selectedTool != null) clearSelectedTool(false);
		enterPage(next);
		// The pinned unlock reason answers one click on one padlock; leaving the page ends it.
		lockedHintTool = null;
		if (next == TerminalPage.RECORDS) {
			send(TerminalControlPayload.MARK_RECORDS_READ, 0);
			recordsAcknowledged = true;
		}
		if (next == TerminalPage.FILES) {
			send(TerminalControlPayload.MARK_FILES_SEEN, 0);
			filesAcknowledged = true;
			if (previous != TerminalPage.FILES) resetLogView();
		}
		recordsScrollRow = next == TerminalPage.RECORDS ? recordsScrollRow : 0;
		TerminalClientAudio.tab();
		setMode(next.wireMode());
		return true;
	}

	private void openTool(TerminalTool tool) {
		if (tool == null || !tools.available(tool)) return;
		boolean fromHome = page == TerminalPage.HOME;
		toolOpenedFromHome = fromHome;
		selectedTool = tool;
		toolDetailScroll = 0;
		send(TerminalControlPayload.SELECT_TOOL, tool.slot());
		enterPage(TerminalPage.TOOLS);
		setMode(TerminalControlPolicy.Mode.SIGNAL.ordinal());
		TerminalClientAudio.openLevel();
	}

	private void clearSelectedTool(boolean audio) {
		if (selectedTool != null) send(TerminalControlPayload.SELECT_TOOL, TerminalToolService.NO_TOOL);
		selectedTool = null;
		toolDetailScroll = 0;
		toolOpenedFromHome = false;
		navigationHits.clear();
		if (audio) TerminalClientAudio.backLevel();
	}

	private void setMode(int value) {
		int safe = TerminalControlPolicy.mode(value);
		if (safe == mode) return;
		mode = safe;
		send(TerminalControlPayload.MODE, mode);
	}

	private void setTuning(int value) {
		// The profile is exempt. receiverMechanicalInteractive answers "is the receiver tool usable",
		// which is a question about a tool the player has not been given yet - the profile runs before
		// binding, before the tool snapshot means anything, and before any of that is unlocked. Asking
		// it here was the second of two gates silently refusing the dial: the click gate above let the
		// press through and this one threw the value away, so the slider took input and never moved,
		// and the arrow keys did nothing either because they land here too.
		if (!profileAsking() && !receiverMechanicalInteractive()) return;
		int safe = TerminalControlPolicy.tuning(value);
		if (safe == tuning) return;
		profileTuned = true;
		boolean gameplay = receiverGameplayActive();
		boolean receiverLockBefore = gameplay && receiverLocked(tuning);
		retargetTuningVisual(safe, nowMillis());
		boolean receiverLockAfter = gameplay && receiverLocked(tuning);
		TerminalClientAudio.tuningInput();
		// The loop covers the sweep; the detent marks that the dial actually moved a notch, which
		// is what makes a stepped control feel mechanical rather than painted on.
		TerminalClientAudio.detent();
		if (!receiverLockBefore && receiverLockAfter) TerminalClientAudio.lock();
		if (gameplay) {
			localTuningOnly = false;
			send(TerminalControlPayload.TUNE, tuning);
		} else {
			localTuningOnly = true;
		}
	}

	private void updateTuningFromSlider(double x) {
		setTuning(TerminalUiLayout.sliderTuning(x));
	}

	private void moveFileSelection(int delta) {
		int total = snapshot.files().size();
		if (total == 0) return;
		selectedFile = selectedFile < 0 ? (delta < 0 ? total - 1 : 0)
				: Math.clamp(selectedFile + delta, 0, total - 1);
		selectedFileId = snapshot.files().get(selectedFile).id();
		if (selectedFile < fileListScroll) fileListScroll = selectedFile;
		if (selectedFile >= fileListScroll + TerminalUiLayout.FILE_LIST_VISIBLE_ROWS) {
			fileListScroll = selectedFile - TerminalUiLayout.FILE_LIST_VISIBLE_ROWS + 1;
		}
		fileListScroll = Math.clamp(fileListScroll, 0, TerminalUiLayout.fileMaxScrollRow(total));
		// Moving the highlight is not choosing anything, so it gets the lighter contact.
		TerminalClientAudio.keypress();
	}

	private int indexOfFile(String id) {
		for (int index = 0; index < snapshot.files().size(); index++) {
			if (snapshot.files().get(index).id().equals(id)) return index;
		}
		return -1;
	}

	private int diaryTitleColor(TerminalFilePayload file) {
		if (!file.unlocked()) return DIM;
		if (diaryUnlockStartedAtMillis < 0L) return GREEN;
		long now = renderNowMillis > 0L ? renderNowMillis : nowMillis();
		float progress = Math.clamp((now - diaryUnlockStartedAtMillis) / (float) FILE_UNLOCK_FADE_MILLIS, 0.0F, 1.0F);
		if (progress >= 1.0F) diaryUnlockStartedAtMillis = -1L;
		return interpolateColor(DIM, GREEN, progress);
	}

	private static int interpolateColor(int from, int to, float progress) {
		int alpha = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * progress);
		int red = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * progress);
		int green = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * progress);
		int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * progress);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private void resetLogView() {
		logView = LogView.DIRECTORY;
		fileListScroll = 0;
		fileContentScroll = 0;
		selectedFile = -1;
		selectedFileId = "";
		setDetailFile(null);
	}

	/** Every path that changes what the reading pane shows goes through here, so its rows follow. */
	private void setDetailFile(TerminalFilePayload file) {
		detailFile = file;
		cachedFileRows = null;
	}

	/** Scrolls whichever list the current page owns. Pages without one stay put. */
	private void scrollCurrentPage(int delta) {
		if (page == TerminalPage.RECORDS) {
			recordsScrollRow = TerminalUiLayout.scroll(recordsScrollRow, delta, recordsMaxScroll());
		} else if (page == TerminalPage.FILES) {
			fileContentScroll = TerminalUiLayout.scroll(fileContentScroll, delta, fileContentMaxScroll());
		}
	}

	private void send(int action, int value) {
		if (ClientPlayNetworking.canSend(TerminalControlPayload.TYPE)) {
			ClientPlayNetworking.send(new TerminalControlPayload(action, value));
		}
	}

	public void selectModeForTesting(int value) { selectPage(TerminalPage.initialPage(value)); }
	public void selectPageForTesting(int value) { selectPage(TerminalPage.fromIndex(value)); }
	public boolean onboardingLocksExitForTesting() { return onboardingLocksExit(); }
	/** Page ordinal the walkthrough is waiting for, or -1 when it is not waiting for one. */
	public int onboardingTargetPageForTesting() {
		TerminalPage target = TerminalOnboardingPolicy.target(onboardingPhase);
		return target == null ? -1 : target.ordinal();
	}
	public void setTuningForTesting(int value) { setTuning(value); }
	/** The profile question on screen, or {@code -1}. Paired with {@link #setTuningForTesting}. */
	public int profileQuestionForTesting() { return profileAsking() ? snapshot.profileQuestion() : -1; }
	/**
	 * Parks the receiver on one option of the question being asked.
	 *
	 * <p>Deliberately does not send the answer. It tunes and leaves, so the commit still has to come
	 * out of the real path - lock detection, the one-second hold, the packet, the server advancing its
	 * own question index. A hook that shortcut to the packet would test the server and skip the only
	 * part of this that is on screen.
	 */
	public void tuneToProfileOptionForTesting(int option) {
		int question = snapshot.profileQuestion();
		if (question < 0 || !TerminalProfileQuestionnaire.validAnswer(question, option)) return;
		setTuning(TerminalProfileQuestionnaire.optionTuning(question, option));
	}
	/** Whether the home card is still showing the task that was just completed and paid for. */
	public boolean taskCompletionHeldForTesting() { return completedTask != null; }
	public String objectiveIdForTesting() { return snapshot.objectiveId(); }
	public void openToolForTesting(int slot) {
		TerminalTool tool = TerminalTool.fromSlot(slot);
		if (tool != null) openTool(tool);
	}
	public int selectedToolForTesting() {
		return selectedTool == null ? TerminalToolService.NO_TOOL : selectedTool.slot();
	}
	public void activateSelectedToolForTesting() {
		if (selectedTool == TerminalTool.WEATHER) togglePinnedTool();
		else if (selectedTool != null && canToggleGuidance(selectedTool)) toggleGuidance();
	}
	public void backFromToolForTesting() { backFromToolDetail(); }
	public void closeHomeLiveToolForTesting() { closeHomeLiveTool(); }
	public void refreshMineralsForTesting() { send(TerminalControlPayload.REQUEST_RESCAN, 0); }
	public void startGuidanceForTesting(int slot) { send(TerminalControlPayload.START_GUIDANCE, slot); }
	public void stopGuidanceForTesting() { send(TerminalControlPayload.STOP_GUIDANCE, 0); }
	public void selectCacheForTesting(int value) {
		selectPage(TerminalPage.FILES);
		openDirectoryEntry(Math.clamp(value, 0, 1));
	}
	public void openLogEntryForTesting(int value) {
		selectPage(TerminalPage.FILES);
		openDirectoryEntry(Math.max(0, value));
	}
	/**
	 * Tells the server this client holds a previous run, without touching the real config.
	 *
	 * <p>The recovered fragment is the one catalogue entry that is conditionally served, so a test
	 * that wants a full FILES list has to ask for it. Sending the control directly rather than
	 * writing a previous run into {@code ConfigManager} keeps the test off the config file of
	 * whoever happens to be running it.
	 */
	public void reportPreviousRunForTesting(boolean present) {
		send(TerminalControlPayload.REPORT_PREVIOUS_RUN, present ? 1 : 0);
	}

	public void openLogDirectoryForTesting() {
		selectPage(TerminalPage.FILES);
		resetLogView();
	}
	public void openWitnessFragmentsForTesting() {
		selectPage(TerminalPage.FILES);
		int index = -1;
		for (int current = 0; current < snapshot.files().size(); current++) {
			if (snapshot.files().get(current).id().equals("encrypted_witness_file")) index = current;
		}
		openDirectoryEntry(index);
	}
	public void openFragmentForTesting(int fragment) {
		TerminalFilePayload file = snapshot.fragmentFile(fragment);
		if (file != null) openDetail(file);
	}
	public void openCompleteFileForTesting() {
		TerminalFilePayload parent = snapshot.directoryFiles().stream()
				.filter(file -> file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID) && file.unlocked())
				.findFirst().orElse(null);
		if (parent != null) openDetail(parent);
	}
	public void markAllReadForTesting() { send(TerminalControlPayload.MARK_RECORDS_READ, 0); }
	public int hiddenFileReadPercentForTesting() { return snapshot.hiddenFileReadPercent(); }
	public int unreadCountForTesting() { return snapshot.unreadCount(); }
	public int unreadFileCountForTesting() { return snapshot.unreadFileCount(); }
	public boolean unreadFlashOnForTesting() {
		return snapshot.unreadCount() > 0 && TerminalUiLayout.unreadFlashOn(age - unreadFlashStartedAt);
	}
	public String logViewForTesting() { return logView.name(); }
	public void scrollRowsForTesting(int rows) { scrollCurrentPage(rows); }
	public int modeForTesting() { return mode; }
	public String pageForTesting() { return page.name(); }
	public boolean toolAvailableForTesting(int slot) {
		TerminalTool tool = TerminalTool.fromSlot(slot);
		return tool != null && tools.available(tool);
	}
	public int selectedResourceForTesting() { return tools.selectedResource().wireId(); }
	public int guidanceToolForTesting() {
		TerminalTool tool = tools.guidanceTool();
		return tool == null ? TerminalToolService.NO_TOOL : tool.slot();
	}
	public int homeLiveToolForTesting() {
		return homeLiveTool == null ? TerminalToolService.NO_TOOL : homeLiveTool.slot();
	}
	public boolean toolReturnsHomeForTesting() { return toolOpenedFromHome; }
	public int tuningForTesting() { return tuning; }
	public boolean receiverGameplayActiveForTesting() { return receiverGameplayActive(); }
	public double displayedTuningForTesting(long nowMillis) { return tuningTransition.valueAt(nowMillis); }
	public int selectedFileForTesting() { return selectedFile; }
	public int fileScrollRowForTesting() { return fileListScroll; }
	public int recordsScrollRowForTesting() { return recordsScrollRow; }
	public int fileContentScrollForTesting() { return fileContentScroll; }
	public int fileCountForTesting() { return snapshot.files().size(); }
	public String fileIdForTesting(int index) { return snapshot.files().get(index).id(); }
	public String selectedFileIdForTesting() { return selectedFileId; }
	public int discoveredHiddenFileCountForTesting() { return snapshot.discoveredHiddenFileCount(); }
	public boolean diaryUnlockFadeActiveForTesting() { return diaryUnlockStartedAtMillis >= 0L; }
	public double waveformMorphTargetForTesting() { return waveformMorphTarget(tuning); }
	/**
	 * The stage the panel believes it is at. Split out from the waveform mix so a suite failure
	 * says which half broke: the server never sending the stage, or the stage not reaching the
	 * trace.
	 */
	public int visualStageForTesting() { return snapshot.visualStage(); }
	public boolean navigationTargetLocatedForTesting() { return navigation.located(); }
	public boolean navigationActiveForTesting() {
		return tools.guidanceTool() != null && navigation.navigable();
	}
	public void moveFileSelectionForTesting(int delta) { moveFileSelection(delta); }
	public void openSelectedFileForTesting() { openDirectoryEntry(selectedFile); }

	/**
	 * The one guaranteed teardown.
	 *
	 * <p>{@code onClose} is not it: the screen can also be replaced, or torn down with the world, and
	 * a carrier left running through either would be a hum with no terminal under it. Stopping here
	 * means every exit path is covered by one line instead of by remembering to add a line to each.
	 */
	@Override
	public void removed() {
		TerminalClientAudio.carrierOff();
		super.removed();
	}

	@Override
	public void onClose() {
		// closedByServer is checked first on purpose: a server-side close - the terminal being taken,
		// a pursuit starting - always outranks the walkthrough's hold.
		if (!closedByServer && onboardingLocksExit()) {
			refuseOnboardingInput();
			return;
		}
		TerminalClientAudio.endTuningInput();
		if (!closedByServer) send(TerminalControlPayload.CLOSE, 0);
		TerminalHandheldAnimator.requestClose();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() { return false; }

	/**
	 * Centres a label inside {@code bounds}, shrinking it rather than losing the end of it.
	 *
	 * <p>This used to wrap the text and draw only the first line, which meant every tab, tool name,
	 * button and option label on this screen silently dropped whatever did not fit on one line, with
	 * no ellipsis to show that it had. Longer translations of the same string simply came out as a
	 * different, shorter sentence. Now a label that does not fit is scaled down; only a label that is
	 * still too wide at the smallest readable scale is cut, and that one gets an ellipsis.</p>
	 */
	private void drawCenteredFitted(GuiGraphics graphics, Component text, TerminalUiLayout.Bounds bounds, int color) {
		int room = Math.max(1, bounds.width() - 6);
		int width = font.width(text);
		if (width <= room) {
			graphics.drawString(font, text, bounds.left() + (bounds.width() - width) / 2,
					bounds.top() + Math.max(3, (bounds.height() - font.lineHeight) / 2), color, false);
			return;
		}
		float scale = room / (float) width;
		if (scale >= MIN_FITTED_SCALE) {
			float renderedHeight = font.lineHeight * scale;
			graphics.pose().pushMatrix();
			graphics.pose().translate(bounds.left() + (bounds.width() - room) / 2.0F,
					bounds.top() + Math.max(0.0F, (bounds.height() - renderedHeight) / 2.0F));
			graphics.pose().scale(scale, scale);
			graphics.drawString(font, text, 0, 0, color, false);
			graphics.pose().popMatrix();
			return;
		}
		int floorWidth = Math.max(1, Math.round(room / MIN_FITTED_SCALE));
		String clipped = ellipsize(text.getString(), floorWidth);
		float renderedHeight = font.lineHeight * MIN_FITTED_SCALE;
		graphics.pose().pushMatrix();
		graphics.pose().translate(bounds.left() + (bounds.width() - room) / 2.0F,
				bounds.top() + Math.max(0.0F, (bounds.height() - renderedHeight) / 2.0F));
		graphics.pose().scale(MIN_FITTED_SCALE, MIN_FITTED_SCALE);
		graphics.drawString(font, clipped, 0, 0, color, false);
		graphics.pose().popMatrix();
	}

	private void drawFittedLine(GuiGraphics graphics, Component text, TerminalUiLayout.Bounds bounds, int color) {
		int textWidth = Math.max(1, font.width(text));
		float scale = Math.min(1.0F, bounds.width() / (float) textWidth);
		float renderedHeight = font.lineHeight * scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(bounds.left(), bounds.top() + Math.max(0.0F,
				(bounds.height() - renderedHeight) / 2.0F));
		graphics.pose().scale(scale, scale);
		graphics.drawString(font, text, 0, 0, color, false);
		graphics.pose().popMatrix();
	}

	private int pageAccent() {
		return snapshot.visualStage() >= 2 ? HOT : snapshot.visualStage() == 1 ? CYAN : AMBER;
	}

	/**
	 * The name the status strip prints as the holder.
	 *
	 * <p>Read from the local player rather than the stack's owner tag: the terminal is bound to one
	 * person and only its holder can open it, so the two always agree - and this way the strip has
	 * something to print during the frames before a snapshot has arrived.</p>
	 */
	private String holderName() {
		return minecraft == null || minecraft.player == null ? "" : minecraft.player.getName().getString();
	}

	private static int lerpColor(int from, int to, double progress) {
		int a = lerpChannel(from >>> 24, to >>> 24, progress);
		int r = lerpChannel(from >>> 16 & 0xFF, to >>> 16 & 0xFF, progress);
		int g = lerpChannel(from >>> 8 & 0xFF, to >>> 8 & 0xFF, progress);
		int b = lerpChannel(from & 0xFF, to & 0xFF, progress);
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static int lerpChannel(int from, int to, double progress) {
		return (int) Math.round(from + (to - from) * progress);
	}

	private static void drawPixelCircle(GuiGraphics graphics, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int span = (int) Math.floor(Math.sqrt(radius * radius - dy * dy));
			graphics.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, color);
		}
	}

	private static void drawTargetNeedle(GuiGraphics graphics, int cx, int cy, double degrees) {
		double radians = Math.toRadians(degrees);
		int widthX = (int) Math.round(Math.cos(radians));
		int widthY = (int) Math.round(Math.sin(radians));
		int endX = cx;
		int endY = cy;
		for (int step = 2; step <= 8; step++) {
			endX = cx + (int) Math.round(Math.sin(radians) * step);
			endY = cy - (int) Math.round(Math.cos(radians) * step);
			graphics.fill(endX, endY, endX + 1, endY + 1, AMBER);
			graphics.fill(endX + widthX, endY + widthY,
					endX + widthX + 1, endY + widthY + 1, AMBER);
			graphics.fill(endX - widthX, endY - widthY,
					endX - widthX + 1, endY - widthY + 1, AMBER);
		}
		graphics.fill(endX - 1, endY, endX + 2, endY + 1, AMBER);
		graphics.fill(endX, endY - 1, endX + 1, endY + 2, AMBER);
	}

	private static void drawFacingNeedle(GuiGraphics graphics, int cx, int cy, double degrees) {
		double radians = Math.toRadians(degrees);
		int endX = cx;
		int endY = cy;
		// Stops one pixel inside the tightened face rather than the old twelve, so the needle never
		// crosses the bezel the cardinal labels now sit outside of.
		for (int step = 2; step <= 10; step++) {
			endX = cx + (int) Math.round(Math.sin(radians) * step);
			endY = cy - (int) Math.round(Math.cos(radians) * step);
			graphics.fill(endX, endY, endX + 1, endY + 1, HOT);
		}
		graphics.fill(endX - 1, endY, endX + 2, endY + 1, HOT);
		graphics.fill(endX, endY - 1, endX + 1, endY + 2, HOT);
	}

	private String ellipsize(String raw, int width) {
		String text = raw == null ? "" : raw;
		if (font.width(text) <= width) return text;
		String suffix = "…";
		int cut = fittingPrefix(text, Math.max(1, width - font.width(suffix)));
		return text.substring(0, Math.max(0, cut)).stripTrailing() + suffix;
	}

	private int fittingPrefix(String text, int width) {
		int cut = 0;
		while (cut < text.length() && font.width(text.substring(0, cut + 1)) <= width) cut++;
		return cut;
	}

	private float panelScale() {
		return Math.min(2.0F, Math.max(0.55F,
				Math.min((width - 16) / (float) BASE_WIDTH, (height - 16) / (float) BASE_HEIGHT)));
	}

	private double[] local(double mouseX, double mouseY) {
		float scale = panelScale();
		return new double[]{
				(mouseX - (width - BASE_WIDTH * scale) / 2.0D) / scale,
				(mouseY - (height - BASE_HEIGHT * scale) / 2.0D) / scale};
	}

	private static long nowMillis() { return System.nanoTime() / 1_000_000L; }

	private enum LogView { DIRECTORY, DETAIL, LOCKED_DIARY }
	/** One rendered line of the Records list. {@code shortcut} means the navigation control sits here. */
	/**
	 * One drawn line of the records page.
	 *
	 * <p>{@code settleText} is non-null only for backfilled anomaly lines, and it holds the clean
	 * string rather than a laid-out sequence. The settle has to be drawn glyph by glyph at the clean
	 * text's own advances: this font is not monospace - {@code i} is two pixels and {@code #} is six -
	 * so substituting characters into a laid-out line would make it change width as it resolves, and
	 * the page would appear to breathe. Positioning by the original character's advance means the
	 * junk lands exactly where the real glyph will, and nothing moves at all.
	 */
	/**
	 * @param candidate the encoded lead this row's navigation shortcut aims at, or {@code -1} for the
	 *                  rows that carry no shortcut. Carried per row rather than resolved at click
	 *                  time because the page collapses several leads that share a location into one
	 *                  line, so the row is the only thing that still knows which lead it stands for.
	 */
	private record RecordRow(FormattedCharSequence text, int color, int indent, boolean marker,
			int candidate, String settleText) {
		private RecordRow(FormattedCharSequence text, int color, int indent, boolean marker, int candidate) {
			this(text, color, indent, marker, candidate, null);
		}

		private RecordRow withShortcut(int encoded) {
			return new RecordRow(text, color, indent, marker, encoded, settleText);
		}

		private boolean shortcut() {
			return candidate >= 0;
		}
	}
	private record FileRow(FormattedCharSequence text, int color, float scale, int height) {
		private static FileRow gap(int height) {
			return new FileRow(null, DIM, 1.0F, height);
		}
	}
	private record NavigationHit(TerminalUiLayout.Bounds bounds, int action, int value) { }
}
