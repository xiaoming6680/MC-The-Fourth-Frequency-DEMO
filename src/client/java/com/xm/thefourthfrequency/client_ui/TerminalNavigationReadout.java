package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The guidance tool's bearing, kept where the player can read it without opening anything.
 *
 * <p>The terminal already draws this line, and it is the right place for it - but it is also the
 * only place, and the terminal is a screen. Following a bearing means walking, and walking means the
 * screen is shut, so the loop was: open the terminal, read "south-west, about 5 blocks", close it,
 * walk, discover you have drifted, open it again. The information was live and the player could only
 * see it in stills.
 *
 * <p>This is the same sentence, on the HUD, recomputed every frame. Deliberately <em>one</em> line
 * that is replaced in place rather than a stream of notices: the bearing changes every step, and a
 * notice per step is not a readout, it is a flood that would push everything the fight and the
 * story have to say out of the stack.
 *
 * <p>Client-side by construction. The server sends the same small navigation payload it has always
 * sent - it just keeps sending it once the screen is closed - and the sentence is assembled here by
 * {@link TerminalSnapshot#navigationLine}, from the payload and the player's own Y. That keeps the
 * distance and the height difference honest between frames without the server sending anything per
 * step.
 */
public final class TerminalNavigationReadout {
	/**
	 * How long a payload stays on screen without a fresh one behind it.
	 *
	 * <p>The server streams at four-tick intervals, so anything approaching a second of silence means
	 * the stream has stopped - the tool was cleared, the terminal was taken, the player died. The
	 * readout going quiet on its own is what makes "the server simply stops sending" a complete way
	 * to turn it off, with no second message to keep in step with the first.
	 */
	private static final long STALE_MILLIS = 1_200L;

	private static TerminalNavigationPayload latest;
	private static long receivedAt;

	private TerminalNavigationReadout() {
	}

	/**
	 * Called for every navigation payload; kept only while the terminal is shut.
	 *
	 * <p>This line exists for a player walking with the terminal closed. While it is open the
	 * terminal is already saying the same sentence on its own page, and the readout is behind the
	 * screen where nobody can see it - so taking payloads then buys nothing and costs a bug.
	 *
	 * <p>The bug: while the screen is open the server answers for whichever tool page is being
	 * <em>looked at</em>, and once it closes it answers for whichever tool is actually guiding. Those
	 * are different questions. Probe a mineral, get a bearing without a fix, never press start - and
	 * the open terminal streams a mineral line while the closed one has nothing to stream. Closing
	 * left the client holding that last packet with no successor, and the readout appeared at the
	 * moment of closing and faded a second later. A line that only ever shows up as you stop looking
	 * is worse than no line.
	 *
	 * <p>Fixed here rather than on the server because the server payload has a second consumer - the
	 * terminal page itself - and silencing it would take the reading off the page that asked for it.
	 * The client is also the side that actually knows whether its own screen is up.
	 */
	public static void accept(TerminalNavigationPayload payload) {
		if (payload == null
				|| payload.protocolVersion() != TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION) {
			return;
		}
		if (terminalScreenOpen()) return;
		latest = payload;
		receivedAt = Util.getMillis();
	}

	private static boolean terminalScreenOpen() {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		return client != null && client.screen instanceof TerminalScreen;
	}

	/** Drops the readout immediately, for a disconnect or a world change. */
	public static void clear() {
		latest = null;
		receivedAt = 0L;
	}

	/**
	 * The line to draw, or null when there is nothing to say.
	 *
	 * <p>Recomputed rather than cached: the height difference is measured against the player's
	 * current Y, so a cached sentence would be wrong the moment they climbed a block.
	 */
	public static Component line() {
		TerminalNavigationPayload payload = latest;
		if (!streams(payload, Util.getMillis() - receivedAt)) return null;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) return null;
		// Relative wording, and from the live yaw: on the HUD there is no compass beside the line, so
		// "south-west" is a bearing the player has to translate against whichever way they are facing.
		return TerminalSnapshot.navigationLine(payload, client.player.getBlockY(),
				client.player.getYRot());
	}

	/**
	 * Whether a payload of this age still belongs on screen.
	 *
	 * <p>Split out from {@link #line()} because it is the whole of the readout's own judgement and the
	 * rest of that method is asking the client for a player. Every way the readout turns itself off
	 * is here: no payload at all, a tool with nothing selected, a version the client cannot read, and
	 * a stream that has gone quiet.
	 */
	public static boolean streams(TerminalNavigationPayload payload, long ageMillis) {
		return payload != null
				&& payload.protocolVersion() == TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION
				&& payload.targetKind() != TerminalNavigationPayload.NONE
				&& ageMillis >= 0L
				&& ageMillis <= STALE_MILLIS;
	}

	/** Ticks of silence the readout tolerates, stated for the test that pins it against the server. */
	public static long staleMillis() {
		return STALE_MILLIS;
	}
}
