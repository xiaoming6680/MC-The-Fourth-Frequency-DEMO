package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.terminal.ActiveAnomaly;
import com.xm.thefourthfrequency.terminal.AnomalyBackfillPolicy;
import com.xm.thefourthfrequency.terminal.TerminalAnomalyLog;
import com.xm.thefourthfrequency.terminal.TerminalAnomalyLogService;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Server-side acceptance for the records backfill and the first-boot profile.
 *
 * <p>Both are things the unit tests can only half-check: the policies are pure and covered there,
 * but the properties that actually matter - that anomalies accumulate somewhere nobody can read,
 * that one mainline event opens them, that the profile cannot be answered twice - only exist once a
 * real record, a real terminal and the real control path are involved.
 */
public final class TerminalBackfillAndProfileGameTests {
	/**
	 * The whole point of the quarantined store: it fills from the first anomaly and nothing reads it.
	 *
	 * <p>If the write were ever made conditional on the latch, the page would open on an empty list
	 * and the entire moment would be spent on nothing - and every unit test would still pass, because
	 * the policy that decides to write and the service that does the writing are different objects.
	 */
	@GameTest
	public void anomaliesAccumulateUnreadableUntilABearingIsRecorded(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());

		TerminalAnomalyLogService.recordCompleted(player, anomaly("phantom_echo", 1, 0));
		TerminalAnomalyLogService.recordCompleted(player, anomaly("light_dropout", 1, 1));
		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();

		helper.assertValueEqual(TerminalAnomalyLog.entries(record).size(), 2,
				"Completed anomalies must be written to the quarantined store from the very first one");
		helper.assertFalse(TerminalData.anomalyBackfillReleased(record),
				"Nothing may release the store before a bearing is recorded");
		for (var entry : TerminalSignalLog.entries(record)) {
			helper.assertFalse(entry.type().equals("phantom_echo") || entry.type().equals("light_dropout"),
					"Catalogue anomalies must never reach the record log the page reads");
		}
		helper.succeed();
	}

	/**
	 * The release, and the one ordinary line that has to come with it.
	 *
	 * <p>The quarantined store keeps its own unread counter that nothing reads, so without that line
	 * the page would silently grow by dozens of entries and the player would find out whenever they
	 * next happened to open Records - which could be an hour later, or never.
	 */
	@GameTest
	public void oneBearingReleasesTheWholeLogAndAnnouncesItself(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		TerminalAnomalyLogService.recordCompleted(player, anomaly("local_rule_collapse", 1, 0));

		helper.assertFalse(TerminalAnomalyLogService.releaseBackfillIfDue(player),
				"A terminal with no bearing has nothing to release");

		data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.EYE_SAMPLE_COUNT, 1));
		helper.assertTrue(TerminalAnomalyLogService.releaseBackfillIfDue(player),
				"The first recorded bearing must release the store");

		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(TerminalData.anomalyBackfillReleased(record), "The latch must be set");
		helper.assertTrue(TerminalSignalLog.containsType(record, "anomaly_archive_released"),
				"The release must write the one ordinary line that carries the unread badge");
		helper.assertTrue(TerminalAnomalyLog.entries(record).size() == 1,
				"Releasing must not disturb what the store holds");

		// One way. A second bearing does not re-announce.
		data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.EYE_SAMPLE_COUNT, 3));
		helper.assertFalse(TerminalAnomalyLogService.releaseBackfillIfDue(player),
				"A set latch must never fire the release again");
		helper.succeed();
	}

	/** The store has to survive a full run without dropping its oldest, quietest entries. */
	@GameTest
	public void theStoreHoldsAWholeRunRatherThanARollingWindow(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		int written = 100;
		for (int index = 0; index < written; index++) {
			TerminalAnomalyLogService.recordCompleted(player, anomaly("phantom_echo", 1, index % 4));
		}
		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(TerminalAnomalyLog.entries(record).size(), written,
				"A hundred anomalies must all still be there; the old 32-entry cap would have eaten most");
		helper.assertTrue(written < AnomalyBackfillPolicy.MAX_ENTRIES,
				"This test only proves anything while it stays under the cap");
		helper.succeed();
	}

	/**
	 * The profile is the server's, start to finish.
	 *
	 * <p>The client sends an option and never a question number, so the three things worth defending
	 * against - answering ahead, answering the same question twice, and rewriting one already down -
	 * are not guarded against so much as unrepresentable. What this checks is that the index really
	 * does advance here and nowhere else.
	 */
	@GameTest
	public void theProfileAdvancesOnlyOnTheServerAndOnlyForward(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));
		TerminalRuntimeService.open(player, 0);
		helper.assertTrue(TerminalRuntimeService.isOpen(player), "The terminal view must open");

		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(record.getIntOr(TerminalData.PROFILE_QUESTION, -1), 0,
				"A fresh record starts on the first question");
		helper.assertFalse(TerminalData.profileTaken(record), "A fresh record has not been profiled");

		// An option that does not exist on this question changes nothing at all.
		TerminalRuntimeService.control(player, TerminalControlPayload.ANSWER_PROFILE, 99);
		record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(record.getIntOr(TerminalData.PROFILE_QUESTION, -1), 0,
				"An illegal option must not advance the question");
		helper.assertValueEqual(TerminalData.profileAnswer(record, 0),
				TerminalProfileQuestionnaire.UNANSWERED, "An illegal option must not be recorded");

		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			TerminalRuntimeService.control(player, TerminalControlPayload.ANSWER_PROFILE, 0);
		}
		record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(TerminalData.profileTaken(record),
				"Answering every question must set the one-shot latch");
		for (int question = 0; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			helper.assertValueEqual(TerminalData.profileAnswer(record, question), 0,
					"Every answer must be stored where it was given");
		}

		// And it is over. Another answer after the latch cannot rewrite anything.
		TerminalRuntimeService.control(player, TerminalControlPayload.ANSWER_PROFILE, 1);
		record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(TerminalData.profileAnswer(record, 0), 0,
				"A taken profile must not accept a rewrite");
		helper.succeed();
	}

	/**
	 * An interrupted profile keeps its gaps rather than being filled in.
	 *
	 * <p>Closing the terminal is how the damage failsafe and a forced close both end up here. Supplying
	 * defaults for what was never answered would be exactly the quietly-wrong-but-plausible value the
	 * safety rules exist to forbid, written into a record the player can never revisit.
	 */
	@GameTest
	public void anInterruptedProfileKeepsItsGaps(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));
		TerminalRuntimeService.open(player, 0);

		TerminalRuntimeService.control(player, TerminalControlPayload.ANSWER_PROFILE, 2);
		TerminalRuntimeService.control(player, TerminalControlPayload.CLOSE, 0);

		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(TerminalData.profileTaken(record),
				"Closing part-way through ends the profile; the walkthrough does not ask twice");
		helper.assertValueEqual(TerminalData.profileAnswer(record, 0), 2,
				"The answer that was given must survive");
		for (int question = 1; question < TerminalProfileQuestionnaire.questionCount(); question++) {
			helper.assertValueEqual(TerminalData.profileAnswer(record, question),
					TerminalProfileQuestionnaire.UNANSWERED,
					"Question " + question + " was never answered and must not be given a default");
		}
		helper.succeed();
	}

	/**
	 * The recovered fragment appears and disappears on the client's word, and counts toward nothing.
	 *
	 * <p>The four investigation files gate the complete journal, drive the title stage and set the
	 * read percentage. If this one ever joined them, every one of those numbers would mean something
	 * different for a player who had finished the mod before.
	 */
	@GameTest
	public void theRecoveredFragmentIsServedWithoutCountingTowardTheInvestigation(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));
		TerminalRuntimeService.open(player, 0);

		CompoundTag before = data.terminalRecord(player.getUUID()).orElseThrow();
		int discoveredBefore = HiddenFilePolicy.discoveredCount(before);
		int filesBefore = TerminalRuntimeService.visibleFiles(before).size();

		TerminalRuntimeService.control(player, TerminalControlPayload.REPORT_PREVIOUS_RUN, 1);
		CompoundTag withFragment = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(TerminalFileState.discovered(withFragment, HiddenFilePolicy.RECOVERED_FILE_ID),
				"Reporting a previous run must file the fragment");
		helper.assertValueEqual(TerminalRuntimeService.visibleFiles(withFragment).size(), filesBefore + 1,
				"The fragment must be served like any other file");
		helper.assertValueEqual(HiddenFilePolicy.discoveredCount(withFragment), discoveredBefore,
				"The fragment must not count toward the four investigation files");
		helper.assertValueEqual(HiddenFilePolicy.readPercent(withFragment),
				HiddenFilePolicy.readPercent(before),
				"The fragment must not move the read percentage");

		// F8 clears the record on the machine, so the file goes with it.
		TerminalRuntimeService.control(player, TerminalControlPayload.REPORT_PREVIOUS_RUN, 0);
		CompoundTag cleared = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertFalse(TerminalFileState.discovered(cleared, HiddenFilePolicy.RECOVERED_FILE_ID),
				"A reset machine must not leave the fragment standing");
		helper.succeed();
	}

	private static ActiveAnomaly anomaly(String id, int tier, int variant) {
		return new ActiveAnomaly(UUID.randomUUID(), UUID.randomUUID(), id, tier, variant,
				0L, 0L, 20, 20);
	}

	private static ItemStack findTerminal(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(ModItems.OLD_TERMINAL)) return stack;
		}
		throw new AssertionError("Mock player had no issued terminal");
	}
}
