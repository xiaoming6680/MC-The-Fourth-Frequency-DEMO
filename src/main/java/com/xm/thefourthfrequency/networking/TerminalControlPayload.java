package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record TerminalControlPayload(int action, int value) implements CustomPacketPayload {
	public static final int MODE = 0;
	public static final int TUNE = 1;
	public static final int REFRESH = 2;
	/**
	 * The terminal is no longer in front of the player. {@code value} says which way it went.
	 *
	 * <p>{@link #CLOSE_SHOWN} is the ordinary one: a screen was up and the player closed it, so an
	 * unfinished profile is latched as ended - they stopped answering. {@link #CLOSE_NEVER_SHOWN}
	 * says the client gave up an opening that never produced a screen at all, which happens when
	 * something the player opened themselves covers the terminal while it is still rising. Nothing
	 * was asked, so nothing is latched.
	 *
	 * <p>Whether a screen was ever drawn is a client fact, the same way the auto-open's timing is -
	 * see {@code TerminalAutoOpenPolicy}. Forging {@link #CLOSE_NEVER_SHOWN} mid-profile would let a
	 * player keep their own one-shot questionnaire open a while longer, which unlocks nothing and
	 * counts toward nothing; it is the same trade {@link #REPORT_PREVIOUS_RUN} already makes.
	 */
	public static final int CLOSE = 3;
	/** {@link #CLOSE} value: a terminal that was on screen was closed. */
	public static final int CLOSE_SHOWN = 0;
	/** {@link #CLOSE} value: an opening was abandoned before any screen existed. */
	public static final int CLOSE_NEVER_SHOWN = 1;
	public static final int SELECT_FRAGMENT_TARGET = 4;
	public static final int SELECT_TOOL = 5;
	public static final int START_GUIDANCE = 6;
	public static final int STOP_GUIDANCE = 7;
	public static final int SELECT_RESOURCE = 8;
	public static final int REQUEST_RESCAN = 9;
	public static final int SET_HOME = 10;
	/** Reserved legacy action. The server intentionally rejects it. */
	@Deprecated
	public static final int SET_AUTO_TUNING = 11;
	public static final int READ_TRUTH_FILE = 12;
	public static final int MARK_RECORDS_READ = 13;
	public static final int SELECT_STRUCTURE_TARGET = 14;
	public static final int SELECT_NEAREST_UNSTABLE = 15;
	public static final int READ_HIDDEN_FILE = 16;
	public static final int VISIT_PAGE = 17;
	public static final int CLAIM_TASK_REWARD = 18;
	public static final int DISMISS_NAVIGATION_COMPLETION = 19;
	public static final int MARK_FILES_SEEN = 20;
	/**
	 * Commits one first-boot profile answer. The high bits hold the displayed question, and the
	 * low four bits hold the option. The server requires an exact question match before advancing;
	 * repeated or delayed packets cannot answer a later question. See {@link #profileAnswer}.
	 */
	public static final int ANSWER_PROFILE = 21;

	/** Carries the question revision so a delayed/repeated answer cannot consume the next one. */
	public static int profileAnswer(int question, int option) {
		if (question < 0 || question >= 16 || option < 0 || option >= 16)
			throw new IllegalArgumentException("Invalid profile answer");
		return question * 16 + option;
	}
	/**
	 * Tells the server this client holds a record of a previous playthrough. {@code value} is 1 or 0.
	 *
	 * <p>The one place a client asserts something the server cannot check, and it is allowed because
	 * of what it buys: a text file about the player's own past game. It unlocks no progression, counts
	 * toward no total, and is excluded from the four investigation files for exactly that reason. A
	 * player who forged it would be handing themselves a fragment about a run they never had, which is
	 * not an exploit so much as a strange way to spend an afternoon.
	 *
	 * <p>The body is never sent. The server learns only that the file exists and owns whether it has
	 * been found and read; the words are composed on the client from its own config, because they are
	 * a fact about that machine rather than about this world.
	 */
	public static final int REPORT_PREVIOUS_RUN = 22;

	public static final Type<TerminalControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "terminal_control"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalControlPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.action);
				buf.writeVarInt(payload.value);
			},
			buf -> new TerminalControlPayload(buf.readVarInt(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
