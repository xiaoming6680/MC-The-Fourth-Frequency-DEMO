package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guidance readout on the HUD, and the one sentence it shares with the terminal.
 *
 * <p>Two things are pinned. The readout turns itself off by the stream stopping and by nothing else,
 * which is what lets the server switch it off simply by not sending; and the line it draws is the
 * <em>same</em> line the terminal's home page draws, because two renderings of one bearing are two
 * facts that can disagree.
 */
class TerminalNavigationReadoutTest {
	private static TerminalNavigationPayload payload(int kind, int dx, int dz, int targetY) {
		return new TerminalNavigationPayload(TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION,
				kind, true, true, dx, dz, targetY, 0.0F);
	}

	@Test
	void theReadoutShowsOnlyWhileTheStreamIsAliveAndHasATarget() {
		TerminalNavigationPayload live = payload(TerminalNavigationPayload.IRON, -4, -3, 58);
		assertTrue(TerminalNavigationReadout.streams(live, 0L));
		assertTrue(TerminalNavigationReadout.streams(live, TerminalNavigationReadout.staleMillis()));

		// The server switches the readout off by ceasing to send. Nothing else has to be kept in step
		// with that, which is the whole reason there is no second "stop" message.
		assertFalse(TerminalNavigationReadout.streams(live,
				TerminalNavigationReadout.staleMillis() + 1L), "a stream gone quiet must fade out");
		assertFalse(TerminalNavigationReadout.streams(null, 0L), "nothing received yet shows nothing");
		assertFalse(TerminalNavigationReadout.streams(
				payload(TerminalNavigationPayload.NONE, 0, 0, 0), 0L),
				"a guidance tool with nothing selected has nothing to say");
		assertFalse(TerminalNavigationReadout.streams(new TerminalNavigationPayload(
						TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION - 1,
						TerminalNavigationPayload.IRON, true, true, -4, -3, 58, 0.0F), 0L),
				"a payload this client cannot read must not be drawn as though it could");
	}

	/**
	 * The staleness window has to outlast the server's send interval, or the readout blinks between
	 * packets on a table with any latency at all.
	 */
	@Test
	void theStaleWindowComfortablyOutlastsTheServerSendInterval() {
		long serverIntervalMillis = 4L * 50L;
		assertTrue(TerminalNavigationReadout.staleMillis() >= serverIntervalMillis * 4L,
				"the readout must survive several missed packets: window is "
						+ TerminalNavigationReadout.staleMillis() + "ms against a "
						+ serverIntervalMillis + "ms send interval");
	}

	/**
	 * The HUD and the terminal home page render one sentence from one method, so a change to the
	 * phrasing cannot land in one of them only.
	 */
	@Test
	void theHudLineIsTheSameSentenceTheTerminalDraws() {
		TerminalNavigationPayload located = payload(TerminalNavigationPayload.IRON, -4, -3, 58);
		Component line = TerminalSnapshot.navigationLine(located, 64);
		assertNotNull(line);
		assertEquals("terminal.thefourthfrequency.navigation.located",
				((net.minecraft.network.chat.contents.TranslatableContents) line.getContents()).getKey(),
				"a located mineral must use the same key the terminal's live-info block uses");

		// The height difference is measured against the player, not baked into the payload, which is
		// what keeps the number honest as they climb without the server resending anything.
		Object[] high = ((net.minecraft.network.chat.contents.TranslatableContents)
				TerminalSnapshot.navigationLine(located, 64).getContents()).getArgs();
		Object[] low = ((net.minecraft.network.chat.contents.TranslatableContents)
				TerminalSnapshot.navigationLine(located, 40).getContents()).getArgs();
		assertEquals(-6, high[high.length - 1], "58 - 64 is the drop the terminal reports");
		assertEquals(18, low[low.length - 1], "the same target read from lower down is above the player");
	}

	/**
	 * Every kind the payload calls a mineral must have a name to be called by.
	 *
	 * <p>Emerald did not. {@code isMineral} accepted it, so a survey that found emerald came back
	 * located and navigable, and the display switch - which listed the other four - dropped it on the
	 * {@code unresolved} placeholder. That placeholder reads "还不知道需要什么": a sentence about the
	 * player not having a goal yet, printed on top of a bearing pointing straight at one. The
	 * translation it needed existed in both language files the whole time.
	 *
	 * <p>Walked from {@code isMineral} rather than from a list written out here, so the predicate and
	 * the switch cannot drift apart again: adding a mineral to one without the other fails this.
	 */
	@Test
	void everyMineralKindHasAName() {
		for (int kind = 0; kind <= TerminalNavigationPayload.EMERALD; kind++) {
			if (!TerminalNavigationPayload.isMineral(kind)) continue;
			String key = ((net.minecraft.network.chat.contents.TranslatableContents)
					TerminalSnapshot.navigationLine(payload(kind, -4, -3, 58), 64).getContents())
					.getArgs()[0].toString();
			assertFalse(key.contains("unresolved"), "mineral kind " + kind
					+ " renders as the unresolved placeholder, which tells the player they have no"
					+ " goal while pointing them at one");
		}
	}

	/**
	 * The HUD speaks bearings relative to the player; the terminal keeps the compass.
	 *
	 * <p>Inside the terminal there is a drawn compass immediately beside the line, so "south-west"
	 * agrees with something on screen. On the HUD there is nothing to agree with: a player walking
	 * with the screen shut has to translate a compass bearing against whichever way they are
	 * currently pointing, every step. Eight sectors rather than four, because four means a
	 * ninety-degree band all reading "ahead" and no way to tell a drift from being on course.
	 */
	@Test
	void theHudSpeaksRelativeBearingsAndTheTerminalKeepsTheCompass() {
		TerminalNavigationPayload target = payload(TerminalNavigationPayload.IRON, 0, 40, 58);
		String absolute = ((net.minecraft.network.chat.contents.TranslatableContents)
				TerminalSnapshot.navigationLine(target, 64).getContents()).getArgs()[1].toString();
		assertTrue(absolute.contains("direction."), "the terminal must keep compass wording: " + absolute);

		String facingIt = ((net.minecraft.network.chat.contents.TranslatableContents)
				TerminalSnapshot.navigationLine(target, 64, 0.0F).getContents()).getArgs()[1].toString();
		assertTrue(facingIt.contains("relative_octant.ahead"),
				"a target dead ahead must read as ahead: " + facingIt);

		String backToIt = ((net.minecraft.network.chat.contents.TranslatableContents)
				TerminalSnapshot.navigationLine(target, 64, 180.0F).getContents()).getArgs()[1].toString();
		assertTrue(backToIt.contains("relative_octant.behind"),
				"turning around must move the same target behind the player: " + backToIt);

		// Every sector must be reachable and named, or a bearing lands on a key with no translation.
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int step = 0; step < 8; step++) {
			seen.add(TerminalNavigationMath.relativeOctantId(
					TerminalNavigationMath.relativeOctant(0, 40, step * 45.0F)));
		}
		assertEquals(8, seen.size(), "all eight sectors must be distinguishable: " + seen);
	}

	/** A target that has not been found yet still gets a line, so the tool never looks broken. */
	@Test
	void anUnlocatedTargetStillHasSomethingToSay() {
		TerminalNavigationPayload scanning = new TerminalNavigationPayload(
				TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION,
				TerminalNavigationPayload.IRON, false, false, 0, 0, 0, 0.0F);
		assertTrue(TerminalNavigationReadout.streams(scanning, 0L),
				"scanning is a state worth showing, not a reason to hide the readout");
		assertEquals("terminal.thefourthfrequency.navigation.scanning",
				((net.minecraft.network.chat.contents.TranslatableContents)
						TerminalSnapshot.navigationLine(scanning, 64).getContents()).getKey());
	}
}
