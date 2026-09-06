package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.terminal.AnomalyBackfillPolicy;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

public record TerminalSnapshotPayload(
		int protocolVersion,
		int publicStationMask,
		int mode,
		int initialPage,
		int tuning,
		int visualStage,
		int bandStage,
		int cacheVariant,
		boolean secondCacheUnlocked,
		int secondCacheVariant,
		int personality,
		boolean continuityLearned,
		int continuityConfidence,
		int portalTransitions,
		boolean localFileUnlocked,
		boolean terminalCaptured,
		long gameTime,
		int unreadCount,
		int unreadFileCount,
		List<TerminalLogEntryPayload> signalEvents,
		String activeAnomalyId,
		int activeAnomalyTicks,
		List<TerminalFilePayload> files,
		int reminderBand,
		String objectiveId,
		int objectiveProgress,
		int objectiveTarget,
		int objectiveIndex,
		boolean objectiveClaimable,
		String objectiveRewardItem,
		int objectiveRewardCount,
		/**
		 * Whether this player still owes the first-boot walkthrough.
		 *
		 * <p>Phrased as "required" rather than mirroring the stored "done" flag so the client never
		 * has to negate it, and appended at the end rather than inserted beside {@code initialPage}
		 * because {@link #read} is positional - a boolean slipped into the middle silently shifts
		 * every varint after it.</p>
		 */
		boolean onboardingRequired,
		/**
		 * Whether the terminal is asking for the player's attention right now.
		 *
		 * <p>Decided by {@code TerminalData.attentionActive}, the same call that picks which of the
		 * six item forms the player sees in their hand, so the amber lamp on the panel and the amber
		 * lamp on the device can never disagree. Sent as one settled boolean rather than left to the
		 * client to re-derive: three of its four sources are already on the wire, but the fourth -
		 * an unacknowledged navigation completion - is not, and a UI that approximated the rule from
		 * what it happened to have would drift from the item.</p>
		 *
		 * <p>Appended at the end for the same reason {@link #onboardingRequired} was: {@link #read}
		 * is positional, so a field inserted in the middle silently shifts every varint after it.</p>
		 */
		boolean attentionActive,
		/**
		 * The quarantined anomaly store, or empty until the terminal releases it.
		 *
		 * <p>Empty is authoritative, not a hint. The server sends nothing at all before the backfill
		 * latch is set, so a modified client cannot read ahead: the one list whose entire value is
		 * that the player was never shown it must not sit on their machine behind a conditional.</p>
		 */
		List<TerminalLogEntryPayload> anomalyLogs,
		/** The profile question being asked, or {@code -1} when the profile is not on screen. */
		int profileQuestion,
		/**
		 * Answers so far, one per question, {@code TerminalProfileQuestionnaire.UNANSWERED} for gaps.
		 *
		 * <p>Sent rather than accumulated client-side so that reopening the terminal, reconnecting or
		 * being released early by the damage failsafe all show the same thing the server has. The
		 * client never writes here; it only ever asks the server to record one.</p>
		 */
		List<Integer> profileAnswers,
		/**
		 * How close the next ambient anomaly is, 0-100, from {@code OscilloscopeWaveformPolicy}.
		 *
		 * <p>Coarse on purpose. It drives an unlabelled instrument and must not be able to carry a
		 * countdown - the scope is allowed to be restless, not to be an oracle.</p>
		 */
		int anomalyApproach,
		/**
		 * One bit per fragment already discovered, so the records page knows which candidate rows can
		 * still be acted on.
		 *
		 * <p>Sent rather than derived because discovery is not otherwise on this wire, and a page that
		 * guessed would keep offering a shortcut the server has started refusing. The rows themselves
		 * stay either way - the log records what happened - so this only gates the shortcut.
		 */
		int discoveredFragmentMask
) implements CustomPacketPayload {
	/**
	 * Version 14 appends the anomaly backfill list and the first-boot profile.
	 *
	 * <p>{@link #read} is positional, so every one of those fields is at the end. The entry payload
	 * gained a trailing {@code source} in the same version; the two go together and there is no
	 * intermediate build where one exists without the other.
	 *
	 * <p>Version 15 appends the oscilloscope's approach reading, also at the end.
	 *
	 * <p>Version 16 appends the discovered-fragment mask, again at the end, so the records page can
	 * withhold a navigation shortcut the server would refuse.
	 */
	public static final int CURRENT_PROTOCOL_VERSION = 16;
	public static final Type<TerminalSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "terminal_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSnapshotPayload> CODEC = StreamCodec.of(
			TerminalSnapshotPayload::write, TerminalSnapshotPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, TerminalSnapshotPayload value) {
		buf.writeVarInt(value.protocolVersion);
		buf.writeVarInt(value.publicStationMask);
		buf.writeVarInt(value.mode);
		buf.writeVarInt(value.initialPage);
		buf.writeVarInt(value.tuning);
		buf.writeVarInt(value.visualStage);
		buf.writeVarInt(value.bandStage);
		buf.writeVarInt(value.cacheVariant);
		buf.writeBoolean(value.secondCacheUnlocked);
		buf.writeVarInt(value.secondCacheVariant);
		buf.writeVarInt(value.personality);
		buf.writeBoolean(value.continuityLearned);
		buf.writeVarInt(value.continuityConfidence);
		buf.writeVarInt(value.portalTransitions);
		buf.writeBoolean(value.localFileUnlocked);
		buf.writeBoolean(value.terminalCaptured);
		buf.writeVarLong(value.gameTime);
		buf.writeVarInt(value.unreadCount);
		buf.writeVarInt(value.unreadFileCount);
		buf.writeVarInt(value.signalEvents.size());
		for (TerminalLogEntryPayload entry : value.signalEvents) TerminalLogEntryPayload.write(buf, entry);
		buf.writeUtf(value.activeAnomalyId, 64);
		buf.writeVarInt(value.activeAnomalyTicks);
		buf.writeVarInt(value.files.size());
		for (TerminalFilePayload file : value.files) TerminalFilePayload.write(buf, file);
		buf.writeVarInt(value.reminderBand);
		buf.writeUtf(value.objectiveId, 32);
		buf.writeVarInt(value.objectiveProgress);
		buf.writeVarInt(value.objectiveTarget);
		buf.writeVarInt(value.objectiveIndex);
		buf.writeBoolean(value.objectiveClaimable);
		buf.writeUtf(value.objectiveRewardItem, 128);
		buf.writeVarInt(value.objectiveRewardCount);
		buf.writeBoolean(value.onboardingRequired);
		buf.writeBoolean(value.attentionActive);
		buf.writeVarInt(value.anomalyLogs.size());
		for (TerminalLogEntryPayload entry : value.anomalyLogs) TerminalLogEntryPayload.write(buf, entry);
		buf.writeVarInt(value.profileQuestion);
		buf.writeVarInt(value.profileAnswers.size());
		for (Integer answer : value.profileAnswers) buf.writeVarInt(answer);
		buf.writeVarInt(value.anomalyApproach);
		buf.writeVarInt(value.discoveredFragmentMask);
	}

	private static TerminalSnapshotPayload read(RegistryFriendlyByteBuf buf) {
		return new TerminalSnapshotPayload(
				buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(),
				buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
				buf.readVarInt(),
				buf.readBoolean(), buf.readBoolean(),
				buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), readLogs(buf), buf.readUtf(64), buf.readVarInt(),
				readFiles(buf), buf.readVarInt(), buf.readUtf(32), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readBoolean(), buf.readUtf(128), buf.readVarInt(),
				buf.readBoolean(), buf.readBoolean(),
				readAnomalyLogs(buf), buf.readVarInt(), readProfileAnswers(buf), buf.readVarInt(),
				buf.readVarInt());
	}

	/**
	 * The backfill list, clamped to what the store can actually hold.
	 *
	 * <p>Deliberately not {@link #readLogs}, whose 128 is sized for the rolling signal log. The
	 * backfill store holds {@link AnomalyBackfillPolicy#MAX_ENTRIES}, and reading it through the
	 * smaller clamp would silently truncate the oldest entries off a list that exists specifically
	 * to prove nothing was dropped.
	 */
	private static List<TerminalLogEntryPayload> readAnomalyLogs(RegistryFriendlyByteBuf buf) {
		int size = Math.clamp(buf.readVarInt(), 0, AnomalyBackfillPolicy.MAX_ENTRIES);
		java.util.ArrayList<TerminalLogEntryPayload> result = new java.util.ArrayList<>(size);
		for (int i = 0; i < size; i++) result.add(TerminalLogEntryPayload.read(buf));
		return List.copyOf(result);
	}

	private static List<Integer> readProfileAnswers(RegistryFriendlyByteBuf buf) {
		int size = Math.clamp(buf.readVarInt(), 0, TerminalProfileQuestionnaire.questionCount());
		java.util.ArrayList<Integer> result = new java.util.ArrayList<>(size);
		for (int i = 0; i < size; i++) result.add(buf.readVarInt());
		return List.copyOf(result);
	}

	private static List<TerminalLogEntryPayload> readLogs(RegistryFriendlyByteBuf buf) {
		int size = Math.clamp(buf.readVarInt(), 0, 128);
		java.util.ArrayList<TerminalLogEntryPayload> result = new java.util.ArrayList<>(size);
		for (int i = 0; i < size; i++) result.add(TerminalLogEntryPayload.read(buf));
		return List.copyOf(result);
	}

	private static List<TerminalFilePayload> readFiles(RegistryFriendlyByteBuf buf) {
		int size = Math.clamp(buf.readVarInt(), 0, 12);
		java.util.ArrayList<TerminalFilePayload> result = new java.util.ArrayList<>(size);
		for (int i = 0; i < size; i++) result.add(TerminalFilePayload.read(buf));
		return List.copyOf(result);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
