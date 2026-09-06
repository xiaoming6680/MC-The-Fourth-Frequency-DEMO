package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded bottom-of-screen notice with an optional attention tone. */
public record TerminalNoticePayload(Component message, int tone) implements CustomPacketPayload {
	public static final int TONE_NONE = 0;
	public static final int TONE_UNREAD = 1;
	public static final int TONE_TASK_COMPLETE = 2;
	public static final int TONE_PURSUIT_WARNING = 3;
	/** A refused action: distinct presentation so it is never read as another progress line. */
	public static final int TONE_DENIED = 4;
	/**
	 * The finale's own narration - phases, resolution, the fight starting.
	 *
	 * <p>These used to go to the chat log, where the encounter's most consequential lines sat in
	 * the same undifferentiated stack as death messages and whatever anyone happened to type. The
	 * three tones below give the fight a legible voice: what the encounter is doing, what the
	 * anchors are doing, and what the dragon is saying are each their own colour.</p>
	 */
	public static final int TONE_ENCOUNTER = 5;
	/** Anchor pressure: the one boss-fight channel that is about something the players can act on. */
	public static final int TONE_ANCHOR = 6;
	/** The dragon speaking, which is nobody else's voice in the whole mod. */
	public static final int TONE_DRAGON = 7;
	/**
	 * The terminal speaking from inside the unrendered layer.
	 *
	 * <p>Its own tone rather than the default one, because down there the default is unreadable in
	 * the way that matters: a notice that looks like every other notice reads as the game talking,
	 * and the whole point of that line is that it is the <em>terminal</em> still reaching the player
	 * somewhere nothing else does. The panel it draws is cold and colourless against a room that is
	 * entirely warm yellow - the one thing in view that did not come from the layer.
	 */
	public static final int TONE_UNRENDERED = 8;

	/**
	 * Whether a notice may still surface while the World Interface encounter is running.
	 *
	 * <p>The fight is the one stretch of the run where the world has stopped being ambiguous, and a
	 * line about a file turning up somewhere else is noise laid across it. Everything that is not the
	 * encounter waits in the queue instead, and nothing is lost by that: the toast is a courtesy copy,
	 * while the thing it announces is already in the terminal's own records with the unread lamp lit.
	 *
	 * <p>{@link #TONE_DENIED} is on this list even though it is not the encounter's own voice. It is
	 * the direct answer to something the player just tried - the wrong item in the core, dropping an
	 * escrowed terminal - and holding it back would turn a refusal into an action that silently did
	 * nothing, which is the one thing the terminal is never allowed to do.</p>
	 */
	public static boolean surfacesDuringEncounter(int tone) {
		return tone == TONE_ENCOUNTER || tone == TONE_ANCHOR || tone == TONE_DRAGON
				|| tone == TONE_DENIED;
	}
	public static final Type<TerminalNoticePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "terminal_notice"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalNoticePayload> CODEC = StreamCodec.of(
			TerminalNoticePayload::write, TerminalNoticePayload::read);

	private static void write(RegistryFriendlyByteBuf buf, TerminalNoticePayload value) {
		ComponentSerialization.STREAM_CODEC.encode(buf, value.message);
		// Clamped to the highest tone that exists, which is a thing to update when adding one. A new
		// tone left out of this bound does not fail - it silently arrives as the previous highest,
		// so a layer bearing would reach the player in the dragon's voice.
		buf.writeVarInt(Math.clamp(value.tone, TONE_NONE, TONE_UNRENDERED));
	}

	private static TerminalNoticePayload read(RegistryFriendlyByteBuf buf) {
		return new TerminalNoticePayload(ComponentSerialization.STREAM_CODEC.decode(buf),
				// Both clamps name the highest declared tone. Only the write side was updated when
				// TONE_UNRENDERED was added, so a layer notice was written as 8 and read back as 7 -
				// arriving as the dragon, with its teal panel and its roar. Exactly the silent
				// degradation the comment on the write side warns about, missed on the half nobody
				// looked at. If a tone is added, both of these move.
				Math.clamp(buf.readVarInt(), TONE_NONE, TONE_UNRENDERED));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
