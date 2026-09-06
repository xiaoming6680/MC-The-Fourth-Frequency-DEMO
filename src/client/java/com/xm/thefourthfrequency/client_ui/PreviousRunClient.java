package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.config.ModConfig;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.terminal.ProfilePreference;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The record one playthrough leaves for the next, and the fragment the next one reads.
 *
 * <p>The only mod state that deliberately survives a world. It is narrative and nothing else: no
 * rule reads it, it unlocks nothing, and it is excluded from the four investigation files precisely
 * so that no total the mod keeps means something different for a returning player.
 *
 * <p>Written on the client because everything in it is a fact about this machine rather than about
 * any world - who was holding the terminal, what they answered on their first day, how their run
 * ended. The server is told only that the file exists, and owns whether it has been found and read.
 */
public final class PreviousRunClient {
	/** The last profile this client saw, kept so the ending can record it after the terminal is gone. */
	private static int[] latestProfile = new int[0];

	private PreviousRunClient() {
	}

	/**
	 * Remembers the profile answers from a terminal snapshot.
	 *
	 * <p>Cached rather than read at the ending, because by then there is no terminal to read: every
	 * participant surrendered theirs into the resonance core, which is the whole point of the ritual.
	 * The player will have opened it hundreds of times before that.
	 */
	public static void rememberProfile(TerminalSnapshot snapshot) {
		if (snapshot == null) return;
		int[] answers = new int[TerminalProfileQuestionnaire.questionCount()];
		for (int question = 0; question < answers.length; question++) {
			answers[question] = snapshot.profileAnswer(question);
		}
		latestProfile = answers;
	}

	/**
	 * Files this run away for the next one. Called once, as the closing poem is acknowledged.
	 *
	 * <p>Both endings are recorded. A run that ended in the interface crossing the client boundary is
	 * exactly as worth leaving a note about as one that did not - arguably more so, and the fragment
	 * says different things about each.
	 */
	public static void record(WorldInterfaceProtocol.Outcome outcome, int destroyedAnchors, long day) {
		if (outcome == null || outcome == WorldInterfaceProtocol.Outcome.NONE) return;
		List<Integer> answers = new ArrayList<>(latestProfile.length);
		for (int answer : latestProfile) answers.add(answer);
		ModConfig.PreviousRun run = new ModConfig.PreviousRun(
				outcome == WorldInterfaceProtocol.Outcome.SUCCESS ? "success" : "failure",
				destroyedAnchors, day, List.copyOf(answers));
		ConfigManager.updateClientState(state -> state.withPreviousRun(run));
	}

	/** Whether this machine holds a run worth recovering. */
	public static boolean present() {
		return ConfigManager.loadClientState().previousRun().present();
	}

	/**
	 * The fragment's body, in the previous holder's own voice.
	 *
	 * <p>Every number in it is true, which is the rule the whole FILES page lives under: a previous
	 * holder is allowed to be incomplete, to give a direction without a coefficient, and to be wrong
	 * about what any of it meant - but the figures they quote have to be the figures that happened,
	 * or the page stops being evidence and becomes decoration.
	 */
	public static List<Component> lines() {
		ModConfig.PreviousRun run = ConfigManager.loadClientState().previousRun();
		String stem = "terminal.thefourthfrequency.file.recovered_predecessor_record.";
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable(stem + "line1"));
		lines.add(Component.translatable(stem + "line2", run.day()));
		lines.add(Component.translatable(
				stem + "line3." + (run.outcome().equals("success") ? "success" : "failure"),
				run.destroyedAnchors()));
		lines.add(Component.translatable(stem + "line4"));
		lines.add(trustLine(stem, run));
		lines.add(Component.translatable(stem + "line6"));
		return List.copyOf(lines);
	}

	/**
	 * The line that quotes the author's own answer back.
	 *
	 * <p>An interrupted profile says so rather than inventing a reply. The gap is the truthful thing
	 * to report, and a fragment that put words in its author's mouth would be the first place in this
	 * page where the numbers stopped being real.
	 */
	private static Component trustLine(String stem, ModConfig.PreviousRun run) {
		int[] answers = new int[run.profileAnswers().size()];
		for (int index = 0; index < answers.length; index++) answers[index] = run.profileAnswers().get(index);
		ProfilePreference.Trust trust = ProfilePreference.trust(answers);
		if (trust == ProfilePreference.Trust.UNSTATED) {
			return Component.translatable(stem + "line5.unanswered");
		}
		return Component.translatable(stem + "line5", Component.translatable(
				"terminal.thefourthfrequency.profile.option.trust_allies."
						+ (trust == ProfilePreference.Trust.YES ? "yes" : "no")));
	}
}
