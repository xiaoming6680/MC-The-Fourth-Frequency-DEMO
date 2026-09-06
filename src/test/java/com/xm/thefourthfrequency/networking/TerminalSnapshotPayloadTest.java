package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.terminal.AnomalyBackfillPolicy;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import com.xm.thefourthfrequency.terminal.TerminalRecordPolicy;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalSnapshotPayloadTest {
	@Test
	void protocolV16RoundTripsInitialPageUnreadFilesTaskRewardOnboardingAttentionAndCurrentFileReadState() {
		TerminalFilePayload file = new TerminalFilePayload("surface_shelter_record", true, true,
				10L, 20L, 11L, 21L, true, 30L, 40L, 0);
		TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 2, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 3, List.of(), "none", 0,
				List.of(file), -1, "learn_terminal", 4, 4,
				0, true, "minecraft:bread", 6, true, true,
				List.of(), -1, List.of(), 0, 0);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
		TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
		assertEquals(16, decoded.protocolVersion());
		assertEquals(2, decoded.initialPage());
		assertEquals(3, decoded.unreadFileCount());
		assertEquals(List.of(file), decoded.files());
		assertEquals("minecraft:bread", decoded.objectiveRewardItem());
		assertEquals(6, decoded.objectiveRewardCount());
		assertEquals(true, decoded.objectiveClaimable());
		assertEquals(true, decoded.onboardingRequired());
		assertEquals(true, decoded.attentionActive());
	}

	@Test
	void trailingFlagsSurviveBeingFalse() {
		TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 0, List.of(), "none", 0,
				List.of(), -1, "mine_logs", 3, 12,
				1, false, "minecraft:stone_axe", 1, false, false,
				List.of(), -1, List.of(), 0, 0);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
		TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
		assertEquals(false, decoded.onboardingRequired());
		assertEquals(false, decoded.attentionActive());
		assertEquals("mine_logs", decoded.objectiveId());
		assertEquals(1, decoded.objectiveIndex());
		assertEquals(List.of(), decoded.anomalyLogs());
		assertEquals(-1, decoded.profileQuestion());
		assertEquals(0, decoded.anomalyApproach());
	}

	/**
	 * The two booleans that used to be last are independent on the wire.
	 *
	 * <p>Both are read positionally from adjacent bytes, so a codec that dropped or duplicated one
	 * would still pass every test above as long as the two happened to agree. Setting them opposite
	 * ways is what makes that failure visible. They are no longer the final fields, which makes this
	 * more load-bearing rather than less: anything appended after them now depends on both having
	 * been consumed correctly.</p>
	 */
	@Test
	void unreadLampAndWalkthroughFlagsDoNotShadowEachOther() {
		for (boolean onboarding : new boolean[]{true, false}) {
			TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
					TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
					0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
					0, false, false, 100L, 0, 0, List.of(), "none", 0,
					List.of(), -1, "mine_logs", 3, 12,
					1, false, "minecraft:stone_axe", 1, onboarding, !onboarding,
					List.of(), -1, List.of(), 0, 0);
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
			TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
			assertEquals(onboarding, decoded.onboardingRequired());
			assertEquals(!onboarding, decoded.attentionActive());
		}
	}

	/**
	 * A full backfill has to survive the wire intact.
	 *
	 * <p>The list's entire claim is that nothing was dropped, so the one failure that would be worst
	 * here is a silent truncation. The signal log's reader clamps at 128 and this store holds more
	 * than that, so a backfill read through the wrong clamp would lose its oldest entries - exactly
	 * the quiet stage-one ones the list exists to produce - and would still decode cleanly.
	 */
	@Test
	void aFullBackfillSurvivesTheWireWithoutBeingTruncated() {
		List<TerminalLogEntryPayload> anomalies = new ArrayList<>();
		for (int index = 0; index < AnomalyBackfillPolicy.MAX_ENTRIES; index++) {
			anomalies.add(new TerminalLogEntryPayload(index + 1, 0, "false_echo", 1_000L + index,
					index % 24_000L, "minecraft:overworld", index, index % 4, index % 3, true,
					TerminalRecordPolicy.Source.ANOMALY_BACKFILL.wireId()));
		}
		assertTrue(anomalies.size() > 128, "this test is pointless if it fits in the signal log clamp");
		TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 0, List.of(), "none", 0,
				List.of(), -1, "mine_logs", 3, 12,
				1, false, "minecraft:stone_axe", 1, false, false,
				List.copyOf(anomalies), 2, List.of(1, 0, TerminalProfileQuestionnaire.UNANSWERED, 2, 1), 0, 0);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
		TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
		assertEquals(anomalies.size(), decoded.anomalyLogs().size());
		assertEquals(anomalies, decoded.anomalyLogs());
		assertEquals(TerminalRecordPolicy.Source.ANOMALY_BACKFILL.wireId(),
				decoded.anomalyLogs().getFirst().source());
	}

	/**
	 * An unanswered profile slot is a real value, not a gap to be tidied away.
	 *
	 * <p>The damage failsafe can release the walkthrough mid-question. Encoding {@code -1} as
	 * anything other than {@code -1} would hand the client a plausible wrong answer, which is the
	 * precise failure the safety rules exist to prevent - and it would do it to a value the player
	 * can never correct, because the profile is one-shot.
	 */
	@Test
	void anUnansweredProfileSlotSurvivesAsUnanswered() {
		// Built to the questionnaire's own length rather than to a literal five, so adding a question
		// is caught by the codec's own round trip instead of by this list going quietly short.
		List<Integer> answers = new java.util.ArrayList<>();
		for (int index = 0; index < TerminalProfileQuestionnaire.questionCount(); index++) {
			answers.add(index % 2 == 1 ? TerminalProfileQuestionnaire.UNANSWERED : 0);
		}
		answers = List.copyOf(answers);
		TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 0, List.of(), "none", 0,
				List.of(), -1, "mine_logs", 3, 12,
				1, false, "minecraft:stone_axe", 1, true, false,
				List.of(), 1, answers, 0, 0);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
		TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
		assertEquals(answers, decoded.profileAnswers());
		assertEquals(1, decoded.profileQuestion());
		assertEquals(TerminalProfileQuestionnaire.questionCount(), decoded.profileAnswers().size());
	}

	/**
	 * The last field on the wire, which is the one a positional codec loses first.
	 *
	 * <p>Everything before it is exercised by the tests above, and every one of them would still pass
	 * if the trailing varint were never written: decoding simply stops early and the reader returns a
	 * default. Setting it to something that is neither zero nor the maximum is what makes that
	 * visible.
	 */
	@Test
	void theApproachReadingSurvivesAsTheFinalField() {
		TerminalSnapshotPayload snapshot = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 0, List.of(), "none", 0,
				List.of(), -1, "mine_logs", 3, 12,
				1, false, "minecraft:stone_axe", 1, true, true,
				List.of(), 3, List.of(0, 1, 2, 0, 1), 73, 0b1010);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TerminalSnapshotPayload.CODEC.encode(buffer, snapshot);
		TerminalSnapshotPayload decoded = TerminalSnapshotPayload.CODEC.decode(buffer);
		assertEquals(73, decoded.anomalyApproach());
		assertEquals(3, decoded.profileQuestion());
		assertEquals(0b1010, decoded.discoveredFragmentMask());
		assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
	}
}
