package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.terminal.TerminalMotion;
import com.xm.thefourthfrequency.terminal.TerminalControlPolicy;
import com.xm.thefourthfrequency.terminal.TerminalOnboardingPolicy;
import com.xm.thefourthfrequency.terminal.TerminalOnboardingTransition;
import com.xm.thefourthfrequency.terminal.TerminalPage;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import com.xm.thefourthfrequency.terminal.TerminalUiLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Draws the first-boot walkthrough: the self test, and the pointer that walks the player through
 * the four tabs.
 *
 * <p>Holds no authority. Which phase is current, what it is waiting for and whether it blocks the
 * exit are all decided by {@code TerminalOnboardingPolicy}; this only decides what that looks
 * like.</p>
 */
final class TerminalOnboardingOverlay {
	/**
	 * The self-test lines, as literal keys.
	 *
	 * <p>Written out rather than built by concatenating a suffix, because the contract test that
	 * checks every translation key exists only recognises literal arguments. A key assembled at
	 * runtime would slip past it and could go missing without anything failing.</p>
	 */
	private static final String[] BOOT_KEYS = {
			"terminal.thefourthfrequency.boot.line.power",
			"terminal.thefourthfrequency.boot.line.memory",
			"terminal.thefourthfrequency.boot.line.receiver",
			"terminal.thefourthfrequency.boot.line.archive",
			"terminal.thefourthfrequency.boot.line.bind",
			"terminal.thefourthfrequency.boot.line.ready"};
	/** The line that names the holder, and so is the one that takes an argument. */
	private static final int BIND_LINE = 4;
	private static final int DETAIL_LINE_HEIGHT = 11;

	private final String[] bootFull = new String[TerminalOnboardingPolicy.BOOT_LINE_COUNT];
	private boolean bootTextReady;
	private boolean bootCompletionHeard;

	/**
	 * Resolves the self-test lines once.
	 *
	 * <p>{@code getString()} walks the language table, so doing it per frame would resolve six lines
	 * sixty times a second to print the same text.</p>
	 */
	void prepareBootText(String holder) {
		if (bootTextReady) return;
		for (int line = 0; line < BOOT_KEYS.length; line++) {
			bootFull[line] = line == BIND_LINE
					? Component.translatable(BOOT_KEYS[line], holder).getString()
					: Component.translatable(BOOT_KEYS[line]).getString();
		}
		bootTextReady = true;
	}

	/**
	 * The power-on self test.
	 *
	 * <p>Draws no panel of its own. The page is not rendered at all during boot - the terminal has
	 * not finished starting, so there is nothing behind this to hide - and the text sits directly on
	 * the display glass the way every other line in the terminal does. An opaque plate here read as
	 * a sticker laid over the device rather than as the device's own screen.</p>
	 */
	void drawBoot(GuiGraphics graphics, Font font, long elapsedMillis, int accent) {
		var body = TerminalUiLayout.PAGE_BODY;
		graphics.drawString(font, Component.translatable("terminal.thefourthfrequency.boot.title"),
				body.left() + 9, body.top() + 8, accent, false);
		int visible = TerminalOnboardingPolicy.visibleBootLines(elapsedMillis);
		if (!bootCompletionHeard && visible == TerminalOnboardingPolicy.BOOT_LINE_COUNT) {
			bootCompletionHeard = true;
			TerminalClientAudio.bootComplete();
		}
		int percent = TerminalOnboardingPolicy.bootProgressPercent(elapsedMillis);
		int diagramWidth = (body.right() - body.left()) * 2 / 5;
		AnalogBootGraphics.drawMemoryCheck(graphics, body.left() + 6, body.top() + 25,
				diagramWidth, body.bottom() - body.top() - 49, elapsedMillis / 1000D,
				percent / 100D, 1);
		int left = body.left() + diagramWidth + 15;
		int available = body.right() - left - 12;
		for (int line = 0; line < bootFull.length; line++) {
			int y = body.top() + 32 + line * 13;
			boolean complete = elapsedMillis >= (line + 1) * TerminalOnboardingPolicy.BOOT_LINE_MILLIS;
			int color = line < visible ? TerminalVisualTheme.GREEN : TerminalVisualTheme.DIM;
			graphics.fill(left, y + 2, left + 3, y + 5, complete ? accent : color);
			graphics.drawString(font, font.plainSubstrByWidth(bootFull[line], Math.max(1, available - 9)),
					left + 9, y, color, false);
		}
		int trackLeft = body.left() + 9, trackRight = body.right() - 9, trackY = body.bottom() - 12;
		graphics.fill(trackLeft, trackY, trackRight, trackY + 2, TerminalVisualTheme.PROGRESS_TRACK);
		graphics.fill(trackLeft, trackY, trackLeft + (trackRight - trackLeft) * percent / 100, trackY + 2, accent);
	}

	/**
	 * Dims the whole panel behind the step, and points at nothing.
	 *
	 * <p>There used to be a pointer here: the tab strip was dimmed everywhere except the tab being
	 * asked for, which kept its own pixels, wore a pulsing ring and had an arrow bobbing over it.
	 * All three were instructions to click, and the strip stopped accepting clicks when the
	 * walkthrough's own button became the only way forward. A highlight over a dead control is worse
	 * than no highlight: the player presses it, nothing happens, and the first thing the terminal
	 * ever teaches them is that its own signposting cannot be trusted.
	 *
	 * <p>So the dim is uniform now, including across the strip, and the step is read entirely from
	 * the panel and the button. {@code TerminalOnboardingPolicy#dimBands} still exists and is still
	 * tested - it is how a hole is cut when something on the panel genuinely is live - but this scene
	 * has nothing live behind it to cut one for.
	 *
	 * <p>Faded in rather than switched on. This scene does not get the blank the profile does, and it
	 * must not: the page standing behind the dim is the page the brief is describing. The alpha ramp
	 * is continuous, so there is no state change here owing a minimum hold.
	 */
	void drawStep(GuiGraphics graphics, Font font, TerminalOnboardingPolicy.Phase phase,
			TerminalPage currentPage, double renderAge, long transitionMillis, int accent,
			boolean advanceHovered) {
		TerminalPage target = TerminalOnboardingPolicy.target(phase);
		if (target == null) return;
		double arrival = TerminalOnboardingTransition.rowProgress(transitionMillis, 0);
		if (arrival <= 0.0D) return;
		var display = TerminalUiLayout.DISPLAY;
		int dim = TerminalMotion.lerpColor(TerminalVisualTheme.ONBOARD_DIM & 0x00FFFFFF,
				TerminalVisualTheme.ONBOARD_DIM, arrival);
		graphics.fill(display.left(), display.top(), display.right(), display.bottom(), dim);

		// The accent still breathes, but it now only tints the line naming the step. A raised cosine
		// rather than a blink: a continuous curve has no state change to hold for a minimum duration,
		// and at one cycle per two seconds it is nowhere near the flicker ceiling.
		double pulse = TerminalMotion.breathe(renderAge, TerminalOnboardingPolicy.PULSE_PERIOD_TICKS);
		int ring = TerminalMotion.lerpColor(accent, TerminalVisualTheme.ONBOARD_ACCENT, pulse);
		ring = TerminalMotion.lerpColor(ring & 0x00FFFFFF, ring, arrival);

		drawBrief(graphics, font, TerminalOnboardingPolicy.briefSubject(phase, currentPage),
				transitionMillis, accent, advanceHovered, TerminalOnboardingPolicy.finalStep(phase));

		// The standing status strip stands down for the walkthrough rather than being covered over,
		// so this writes into an empty band instead of onto a plate laid across the readout. It names
		// where the button goes next; it does not ask for a click, because nothing up there takes one.
		var strip = TerminalUiLayout.STATUS_BAR;
		Component instruction = Component.translatable(stepKey(target));
		graphics.drawString(font, instruction, strip.left() + 4, strip.top() + 2, ring, false);
		Component counter = Component.translatable("terminal.thefourthfrequency.onboarding.counter",
				TerminalOnboardingPolicy.stepIndex(phase), TerminalOnboardingPolicy.stepCount());
		// Left of the button rather than hard right, which is where the button now is.
		graphics.drawString(font, counter,
				TerminalUiLayout.ONBOARD_NEXT.left() - 6 - font.width(counter), strip.top() + 2,
				TerminalVisualTheme.DIM, false);
	}

	/**
	 * One line saying what the page currently on screen is for.
	 *
	 * <p>The pointer above says which tab to click next, which tells a new player where to put the
	 * cursor and nothing about what they are looking at. This is the other half: it names the page
	 * standing behind the dimming and says what it holds. See
	 * {@code TerminalOnboardingPolicy#briefSubject} for why it is the current page rather than the
	 * one being pointed at.</p>
	 *
	 * <p>The page's own name leads the line, so the two instructions on screen can never be read as
	 * one. "Home: your objective, ..." underneath "select the Tools tab" is unambiguous in a way
	 * that a bare description is not.</p>
	 *
	 * <p>Kept to a single line on purpose. This is a walkthrough a player sees once, while four
	 * other things are already competing for their attention; a paragraph here would be read by
	 * nobody and would push the pointer off the bottom of the page.</p>
	 */
	/**
	 * The first-boot profile: one question, and its options laid out across the receiver band.
	 *
	 * <p>Draws no plate of its own, for the same reason the self test does not. The page is not
	 * rendered underneath - there is nothing to hide - so the text sits on the display glass the way
	 * every other line in this terminal does.
	 *
	 * <p>Question and options alike are legible from the moment the scene has arrived. The dial still
	 * reports which answer the player is on - the notch grows and the colour warms - but it no longer
	 * decides whether the words can be read. See {@code drawTuningBand}.
	 */
	/**
	 * The loading bar that carries a scene change, including the beat where nothing else is drawn.
	 *
	 * <p>A groove and a fill, which is the one kind of solid rectangle this surface allows: the
	 * layout rules list progress tracks alongside the oscilloscope well and the slider channel as
	 * hardware rather than as something laid over the screen. It sits at the very bottom of the page
	 * body so it never competes with the lines arriving above it.
	 *
	 * <p>The fill is {@code stutteredProgress}, so it stalls and surges instead of rising evenly. That
	 * is the whole reason it is here: an empty beat with a smooth bar under it says the software is
	 * being polite about a wait, and an empty beat with a bar that hesitates says the device is old.
	 *
	 * <p>It stops being drawn once the scene has fully arrived. A loading bar that lingers at full is
	 * a loading bar nobody finished writing.
	 */
	void drawTransitionProgress(GuiGraphics graphics, long transitionMillis, int rowCount, long seed) {
		long total = TerminalOnboardingTransition.totalMillis(rowCount);
		if (transitionMillis >= total) return;
		var body = TerminalUiLayout.PAGE_BODY;
		int left = body.left() + 9;
		int right = body.right() - 9;
		int y = body.bottom() - 4;
		graphics.fill(left, y, right, y + 2, TerminalVisualTheme.MUTED_DARK);
		double filled = TerminalOnboardingTransition.stutteredProgress(
				transitionMillis / (double) total, seed);
		int span = (int) Math.round((right - left) * filled);
		if (span > 0) graphics.fill(left, y, left + span, y + 2, TerminalVisualTheme.DIM);
	}

	/**
	 * The same bar, sized to a profile question rather than to a caller-counted number of lines.
	 *
	 * <p>The row count has to match what {@code drawProfile} actually lays out or the bar finishes at
	 * the wrong moment - early and it vanishes while lines are still landing, late and it sits full
	 * on a settled screen. Counting it here, next to the code that draws those rows, is what keeps
	 * the two from drifting; the question wraps to a variable number of lines, so no constant works.
	 */
	void drawTransitionProfileProgress(GuiGraphics graphics, Font font, int question,
			long transitionMillis, long seed) {
		var body = TerminalUiLayout.PAGE_BODY;
		int questionLines = font.getSplitter().splitLines(Component.translatable(
				"terminal.thefourthfrequency.profile.question."
						+ TerminalProfileQuestionnaire.questionId(question)),
				body.width() - 18, net.minecraft.network.chat.Style.EMPTY).size();
		int rows = 1 + questionLines + TerminalProfileQuestionnaire.optionCount(question) + 1;
		drawTransitionProgress(graphics, transitionMillis, rows, seed);
	}

	/**
	 * One profile question, laid out as the band it is actually answered on.
	 *
	 * <p>This was a vertical list of options with a hint underneath, and it did not survive contact
	 * with a player: a stack of lines reads as something to click, so the receiver on the right sat
	 * untouched while somebody looked for a button. The answer is not to explain the control better.
	 * It is to stop drawing a list.
	 *
	 * <p>So the options sit at their frequencies. The strip below the question is the same 0-100 the
	 * dial travels, each option is a marked position on it, and a cursor rides the strip wherever the
	 * dial currently is. Moving the slider moves the cursor along a row of answers. Nothing has to
	 * say what the control is, because the picture is the control.
	 *
	 * <p>{@code transitionMillis} is how long this scene has been the scene: the screen holds empty
	 * for a beat and then lets the parts in, which is what turns moving between two questions from a
	 * dropped frame into a step the device took.
	 */
	void drawProfile(GuiGraphics graphics, Font font, int question, int tuning, int lockedOption,
			int heldTicks, long seed, long bucket, long transitionMillis, int accent) {
		if (question == 0 && transitionMillis < 320) {
			var area = TerminalUiLayout.PAGE_BODY;
			int diagramWidth = area.width() * 2 / 5;
			float fade = 1 - transitionMillis / 320F;
			AnalogBootGraphics.drawMemoryCheck(graphics, area.left() + 6, area.top() + 25,
					diagramWidth, area.height() - 49,
					(TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS + transitionMillis) / 1000D, 1, fade);
			return;
		}
		// The screen is between scenes. Drawn by not drawing - a plate laid over the page would read
		// as a sticker on the device rather than as the device's own display going empty.
		if (TerminalOnboardingTransition.blank(transitionMillis)) return;
		var body = TerminalUiLayout.PAGE_BODY;
		int row = 0;

		double headerProgress = TerminalOnboardingTransition.rowProgress(transitionMillis, row++);
		if (headerProgress > 0.0D) {
			TerminalGlyphRenderer.drawSettling(graphics, font,
					Component.translatable("terminal.thefourthfrequency.profile.title").getString(),
					body.left() + 9, body.top() + 8, accent, seed, 90, headerProgress, bucket);
			Component counter = Component.literal((question + 1) + " / "
					+ TerminalProfileQuestionnaire.questionCount());
			graphics.drawString(font, counter, body.right() - 9 - font.width(counter), body.top() + 8,
					TerminalVisualTheme.DIM, false);
			int span = (int) Math.round((body.width() - 18) * headerProgress);
			graphics.fill(body.left() + 9, body.top() + 20, body.left() + 9 + span, body.top() + 21,
					TerminalVisualTheme.DARK_BORDER);
		}

		int width = body.width() - 18;
		int y = body.top() + 28;
		String questionText = Component.translatable(
				"terminal.thefourthfrequency.profile.question."
						+ TerminalProfileQuestionnaire.questionId(question)).getString();
		for (net.minecraft.network.chat.FormattedText piece : font.getSplitter()
				.splitLines(Component.literal(questionText), width, net.minecraft.network.chat.Style.EMPTY)) {
			double progress = TerminalOnboardingTransition.rowProgress(transitionMillis, row++);
			if (progress > 0.0D) {
				TerminalGlyphRenderer.drawSettling(graphics, font, piece.getString(),
						body.left() + 9, y, TerminalVisualTheme.GREEN, seed, row, progress, bucket);
			}
			y += 11;
		}

		double bandProgress = TerminalOnboardingTransition.rowProgress(transitionMillis, row);
		if (bandProgress <= 0.0D) return;
		drawTuningBand(graphics, font, question, tuning, lockedOption, heldTicks, seed, bucket,
				bandProgress, accent);
	}

	/**
	 * The band: the dial's own travel, with the answers standing on it.
	 *
	 * <p>Laid out against the same 0-100 the receiver slider uses, so a position here and a position
	 * there are the same number. The cursor is a caret above the rail rather than a block on it,
	 * because the rail has to stay readable underneath - the marks are the content and the cursor is
	 * only where you happen to be standing.
	 */
	private void drawTuningBand(GuiGraphics graphics, Font font, int question, int tuning,
			int lockedOption, int heldTicks, long seed, long bucket, double arrival, int accent) {
		var body = TerminalUiLayout.PAGE_BODY;
		int left = body.left() + 16;
		int right = body.right() - 16;
		int rail = body.top() + 92;

		// The rail draws itself across as the scene arrives, the way the header rule above it does -
		// one gesture rather than a part that blinks into place.
		int span = (int) Math.round((right - left) * arrival);
		graphics.fill(left, rail, left + span, rail + 1, TerminalVisualTheme.DARK_BORDER);
		graphics.fill(left, rail - 3, left + 1, rail + 4, TerminalVisualTheme.MUTED);
		if (arrival >= 1.0D) graphics.fill(right - 1, rail - 3, right, rail + 4, TerminalVisualTheme.MUTED);

		int options = TerminalProfileQuestionnaire.optionCount(question);
		for (int option = 0; option < options; option++) {
			int x = bandX(left, right, TerminalProfileQuestionnaire.optionTuning(question, option));
			double tuned = TerminalProfileQuestionnaire.settleProgress(question, tuning, option);
			// The answers arrive with the scene and then stay readable, whatever the dial is doing.
			//
			// They used to resolve out of noise as the player tuned onto them, so an option they were
			// not pointing at was illegible. It reads well and it asks the player to answer five
			// questions about themselves while the answers are scrambled - the one screen in the mod
			// where being unable to read something is not atmosphere, it is the interface withholding
			// the thing it is asking about. Tuning still says which option you are on, through the
			// notch height and the colour ramp below; what it no longer decides is whether the words
			// can be read at all.
			double progress = arrival;
			boolean locked = option == lockedOption;
			int color = locked ? TerminalVisualTheme.CLAIMABLE
					: tuned > 0.0D ? TerminalVisualTheme.GREEN : TerminalVisualTheme.MUTED;
			// A notch on the rail, taller when it is the one being held.
			graphics.fill(x - 1, rail - (locked ? 6 : 4), x + 1, rail + 1, color);
			String label = Component.translatable("terminal.thefourthfrequency.profile.option."
					+ TerminalProfileQuestionnaire.questionId(question) + "."
					+ TerminalProfileQuestionnaire.optionId(question, option)).getString();
			// Centred on its own frequency, then pulled inside the rail at the ends so the first and
			// last answers are not half off the glass.
			int labelX = Math.clamp(x - font.width(label) / 2, left, Math.max(left, right - font.width(label)));
			TerminalGlyphRenderer.drawSettling(graphics, font, label, labelX, rail + 6, color,
					seed, option, progress, bucket);
		}

		if (arrival < 1.0D) return;

		// The cursor, drawn last so it sits over the marks rather than under them.
		int cursorX = bandX(left, right, TerminalControlPolicy.tuning(tuning));
		int cursorColor = lockedOption >= 0 ? TerminalVisualTheme.CLAIMABLE : accent;
		for (int step = 0; step < 4; step++) {
			graphics.fill(cursorX - step, rail - 12 + step, cursorX + step + 1, rail - 11 + step, cursorColor);
		}
		graphics.fill(cursorX, rail - 8, cursorX + 1, rail - 1, cursorColor);

		// The hold fills under the answer it belongs to rather than across the page: a track spanning
		// the width would be reporting on the band, and what is filling is one option.
		if (lockedOption >= 0) {
			int optionX = bandX(left, right, TerminalProfileQuestionnaire.optionTuning(question, lockedOption));
			int half = 22;
			int trackLeft = Math.clamp(optionX - half, left, Math.max(left, right - half * 2));
			int barY = rail + 20;
			double held = Math.clamp(heldTicks / (double) TerminalProfileQuestionnaire.COMMIT_HOLD_TICKS,
					0.0D, 1.0D);
			graphics.fill(trackLeft, barY, trackLeft + half * 2, barY + 2, TerminalVisualTheme.MUTED_DARK);
			graphics.fill(trackLeft, barY, trackLeft + (int) Math.round(half * 2 * held), barY + 2,
					TerminalVisualTheme.CLAIMABLE);
		} else {
			// Right-aligned, so the line sits under the end of the band nearest the receiver - which
			// is on the hardware column immediately to the right of this page.
			Component hint = Component.translatable("terminal.thefourthfrequency.profile.hint");
			graphics.drawString(font, hint, right - font.width(hint), rail + 19,
					TerminalVisualTheme.DIM, false);
		}
	}

	/** Maps a 0-100 tuning value onto the band, the same way the receiver slider maps it. */
	private static int bandX(int left, int right, int tuning) {
		return left + (int) Math.round((right - left) * Math.clamp(tuning, 0, 100) / 100.0D);
	}

	/**
	 * The closing acknowledgement, alone on the glass.
	 *
	 * <p>Nothing else is drawn. The terminal has just been told how the player plays, which animal
	 * they like and whether being alone frightens them, and it answers with the fact that the file
	 * exists. Anything else on screen - a summary of the answers, a thank-you, a progress bar at
	 * 5/5 - would be the device responding, and it is not responding.
	 */
	void drawProfileRecorded(GuiGraphics graphics, Font font, boolean complete,
			long seed, long bucket, long transitionMillis, int accent) {
		// The questions are gone before this arrives, and they go by the screen emptying rather than
		// by anything sliding over them. Two seconds of one line on blank glass is the payload; a
		// transition that carried the old scene along would be softening exactly the wrong beat.
		if (TerminalOnboardingTransition.blank(transitionMillis)) return;
		var body = TerminalUiLayout.PAGE_BODY;
		Component line = Component.translatable(complete
				? "terminal.thefourthfrequency.profile.recorded"
				: "terminal.thefourthfrequency.profile.incomplete");
		String text = line.getString();
		// Assembled out of noise like every other line the terminal has trouble with, which is the
		// closest this device comes to hesitating before it says something.
		TerminalGlyphRenderer.drawSettling(graphics, font, text,
				body.left() + (body.width() - font.width(line)) / 2,
				body.top() + body.height() / 2 - 4,
				complete ? accent : TerminalVisualTheme.DIM, seed, 0,
				TerminalOnboardingTransition.rowProgress(transitionMillis, 0), bucket);
	}

	/**
	 * The page explanation, printed a line at a time, with the control that leaves it.
	 *
	 * <p>This replaces a single centred sentence and a wait for the player to find the right tab.
	 * Two things were wrong with that. A new player was being asked to hunt a four-tab strip for the
	 * one tab that was not dimmed, which is a puzzle rather than a tutorial; and a one-line brief had
	 * room to say what a page was called and nothing about what it was for.
	 *
	 * <p>The lines type in rather than appearing, and the button does not open until the last of them
	 * has finished. That is the only pacing here: an enabled button is an invitation to skip, and a
	 * player who presses it on arrival never reads the step. Once the text is done there is nothing
	 * left to protect and the button opens at once.
	 */
	private static void drawBrief(GuiGraphics graphics, Font font, TerminalPage subject,
			long stepElapsedMillis, int accent, boolean hovered, boolean finalStep) {
		if (subject == null) return;
		var brief = TerminalUiLayout.ONBOARD_BRIEF;
		// Three passes of the same dim, not a plate. The step's own bands already lay one over this
		// area and the panel adds two more, because there are now four lines to read through whatever
		// the page underneath happens to be showing - on Home that is the record card, and at two
		// passes its timestamps still came through the explanation and interleaved with it. Stacking
		// the dim pushes the page back while leaving it visibly present, which is this surface's rule
		// for clearing space; an opaque rectangle here would read as a sticker laid over the screen.
		graphics.fill(brief.left(), brief.top(), brief.right(), brief.bottom(),
				TerminalVisualTheme.ONBOARD_DIM);
		graphics.fill(brief.left(), brief.top(), brief.right(), brief.bottom(),
				TerminalVisualTheme.ONBOARD_DIM);
		Component heading = Component.translatable(
				"terminal.thefourthfrequency.onboarding.brief.current",
				Component.translatable(tabKey(subject)), Component.translatable(briefKey(subject)));
		graphics.drawString(font, heading, brief.left() + 6, brief.top() + 4,
				TerminalVisualTheme.READING_TEXT, false);

		int lines = TerminalOnboardingPolicy.visibleDetailLines(stepElapsedMillis);
		for (int line = 0; line < lines; line++) {
			String full = Component.translatable(detailKey(subject, line)).getString();
			int shown = TerminalOnboardingPolicy.typedDetailCharacters(full.codePointCount(0, full.length()),
					stepElapsedMillis, line);
			if (shown <= 0) continue;
			String text = full.substring(0, full.offsetByCodePoints(0, Math.min(shown,
					full.codePointCount(0, full.length()))));
			// Reading text, not DIM. These lines are the step - the thing the player is here to read -
			// and a dim body under a bright heading reads as a caption to something else.
			graphics.drawString(font, text, brief.left() + 6, brief.top() + 15 + line * DETAIL_LINE_HEIGHT,
					TerminalVisualTheme.READING_TEXT, false);
		}

		drawAdvanceButton(graphics, font, stepElapsedMillis, accent, hovered, finalStep,
				longestDetailCodePoints(subject));
	}

	/**
	 * The one control the walkthrough offers.
	 *
	 * <p>Pressing it does not advance anything by itself - it issues the same page visit the tab
	 * would have, and the server advances the walkthrough and the task off that. See
	 * {@code TerminalScreen#onboardingAdvanceClicked}. The button is a shortcut to the click, not a
	 * replacement for it, which is what keeps the client with no way to claim progress it did not
	 * earn.
	 */
	private static void drawAdvanceButton(GuiGraphics graphics, Font font, long stepElapsedMillis,
			int accent, boolean hovered, boolean finalStep, int longestDetail) {
		var button = TerminalUiLayout.ONBOARD_NEXT;
		boolean ready = TerminalOnboardingPolicy.advanceReady(stepElapsedMillis, longestDetail);
		int border = ready ? (hovered ? TerminalVisualTheme.ONBOARD_ACCENT : accent) : TerminalVisualTheme.DIM;
		graphics.renderOutline(button.left(), button.top(), button.width(), button.height(), border);
		Component label = Component.translatable(finalStep
				? "terminal.thefourthfrequency.onboarding.finish"
				: "terminal.thefourthfrequency.onboarding.next");
		int width = font.width(label);
		graphics.drawString(font, label, button.left() + (button.width() - width) / 2,
				button.top() + (button.height() - font.lineHeight) / 2 + 1, border, false);
	}

	private static String detailKey(TerminalPage subject, int line) {
		return briefKey(subject) + ".detail_" + (line + 1);
	}

	/**
	 * The longest explanation line for a page, in code points.
	 *
	 * <p>Measured rather than assumed, because the answer is whatever the active language file says.
	 * Both the drawing and the click test read the button's readiness through this, so a button that
	 * looks shut is shut.
	 */
	static int longestDetailCodePoints(TerminalPage subject) {
		if (subject == null) return 0;
		int longest = 0;
		for (int line = 0; line < TerminalOnboardingPolicy.DETAIL_LINE_COUNT; line++) {
			String text = Component.translatable(detailKey(subject, line)).getString();
			longest = Math.max(longest, text.codePointCount(0, text.length()));
		}
		return longest;
	}

	/**
	 * The close hint, replaced while the exit is held.
	 *
	 * <p>Leaving "press Esc to close" on screen while Esc does nothing would be the terminal lying
	 * about its own controls, which is the one thing its failure language is not allowed to do.</p>
	 */
	static Component closeHint(TerminalOnboardingPolicy.Phase phase, boolean recentlyReleased) {
		if (TerminalOnboardingPolicy.locksExit(phase)) {
			return Component.translatable("terminal.thefourthfrequency.onboarding.locked_hint");
		}
		if (recentlyReleased) {
			return Component.translatable("terminal.thefourthfrequency.onboarding.released_hint");
		}
		return Component.translatable("terminal.thefourthfrequency.close_hint");
	}

	private static String stepKey(TerminalPage target) {
		return switch (target) {
			case HOME -> "terminal.thefourthfrequency.onboarding.step.home";
			case TOOLS -> "terminal.thefourthfrequency.onboarding.step.tools";
			case RECORDS -> "terminal.thefourthfrequency.onboarding.step.records";
			case FILES -> "terminal.thefourthfrequency.onboarding.step.files";
		};
	}

	/**
	 * The tab strip's own labels, so the name the brief leads with is character for character the
	 * name printed on the tab the player is looking at.
	 */
	private static String tabKey(TerminalPage target) {
		return switch (target) {
			case HOME -> "terminal.thefourthfrequency.tab.home";
			case TOOLS -> "terminal.thefourthfrequency.tab.tools";
			case RECORDS -> "terminal.thefourthfrequency.tab.records";
			case FILES -> "terminal.thefourthfrequency.tab.files";
		};
	}

	/** Written out per case rather than assembled, for the reason given on {@link #BOOT_KEYS}. */
	private static String briefKey(TerminalPage target) {
		return switch (target) {
			case HOME -> "terminal.thefourthfrequency.onboarding.brief.home";
			case TOOLS -> "terminal.thefourthfrequency.onboarding.brief.tools";
			case RECORDS -> "terminal.thefourthfrequency.onboarding.brief.records";
			case FILES -> "terminal.thefourthfrequency.onboarding.brief.files";
		};
	}
}
