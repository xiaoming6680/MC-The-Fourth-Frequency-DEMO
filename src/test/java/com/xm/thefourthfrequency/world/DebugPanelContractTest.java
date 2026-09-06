package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug panel is a client screen talking to a common service through one free-form string.
 *
 * <p>Nothing in the type system connects the two ends: a button can send any id it likes, and the
 * server's answer to an id it does not know is a rejection toast the tester reads as "the feature
 * is broken" rather than "the wire is not connected". The panel is also the one screen no suite
 * clicks through, so a dead button survives every run. These assertions are the wire.</p>
 */
class DebugPanelContractTest {
	private static final Path SCREEN =
			Path.of("src/client/java/com/xm/thefourthfrequency/client_ui/DebugPanelScreen.java");
	private static final Path SERVICE =
			Path.of("src/main/java/com/xm/thefourthfrequency/world/DebugPanelService.java");
	/** {@code DebugActionPayload} writes the action with {@code writeUtf(value.action, 32)}. */
	private static final int ACTION_WIRE_LIMIT = 32;
	/**
	 * Reachable from the panel, and the reason each one has to stay reachable.
	 *
	 * <p>Each of these was supported by the service with no way to press it, which is the failure this
	 * whole class exists to catch pointing the other way: a live branch nobody can reach costs the
	 * same afternoon as a dead button, and is harder to notice because nothing looks broken.</p>
	 */
	private static final java.util.List<String> REQUIRED_ACTIONS = java.util.List.of(
			"anomaly", "anomaly_stop", "anomaly_resume", "him_spawn", "watcher_spawn",
			"prelude_ready", "progress_next", "progress_reset",
			"pursuit_test", "boss_test",
			"file_unlock", "file_lock", "files_lock",
			"milestone", "decay", "decay_auto");

	/**
	 * The only three that may stop and ask.
	 *
	 * <p>A confirmation on something with a cleanup path is noise, and noise is what makes the three
	 * that matter get clicked through. Two of these erase progress with no record of what was there;
	 * the third commits the one finale state a world is allowed to have.</p>
	 */
	private static final java.util.List<String> CONFIRMED_ACTIONS =
			java.util.List.of("progress_reset", "files_lock", "boss_test");

	@Test
	void everyActionThePanelCanSendIsHandledByTheService() throws Exception {
		String service = read(SERVICE);
		Set<String> actions = clientActions();
		assertFalse(actions.isEmpty(), "no debug actions were parsed out of the panel");
		for (String action : actions) {
			assertTrue(service.contains("case \"" + action + "\""),
					"the panel sends an action the service does not handle: " + action);
		}
	}

	@Test
	void everyServiceBranchWorthPressingHasAButton() throws Exception {
		Set<String> actions = clientActions();
		for (String action : REQUIRED_ACTIONS) {
			assertTrue(actions.contains(action),
					"the service handles an action the panel can no longer reach: " + action);
		}
	}

	/**
	 * The confirmation list is a product decision, not a formatting one.
	 *
	 * <p>Asserted from the {@code confirm} flag on the specs rather than from the constant beside
	 * them, because the constant is documentation and the flag is what the button actually does.</p>
	 */
	@Test
	void onlyTheThreeIrreversibleActionsAskForConfirmation() throws Exception {
		String screen = read(SCREEN);
		Matcher matcher = Pattern.compile(
				"new ActionSpec\\([^,]+,\\s*\"([a-z_]+)\",[^()]*?(true|false)\\)")
				.matcher(screen);
		Set<String> confirmed = new LinkedHashSet<>();
		while (matcher.find()) {
			if (matcher.group(2).equals("true")) confirmed.add(matcher.group(1));
		}
		assertEquals(new LinkedHashSet<>(CONFIRMED_ACTIONS), confirmed,
				"the set of actions that stop and ask has changed");
	}

	@Test
	void actionIdsFitTheWire() throws Exception {
		for (String action : clientActions()) {
			assertTrue(action.length() <= ACTION_WIRE_LIMIT,
					"action id is longer than the payload codec allows: " + action);
		}
	}

	/**
	 * HIM has no row in the anomaly list to be reached from, because the catalogue does not carry
	 * it - no tier, no duration, no active-anomaly slot. The toolbar entry is the only way to ask
	 * for one instead of waiting out the service's own 6000-15000 tick interval, so losing it
	 * silently removes the only handle the figure has.
	 */
	@Test
	void himIsReachableFromThePanelAndSpawnsThroughItsOwnService() throws Exception {
		String screen = read(SCREEN);
		assertTrue(screen.contains("\"him_spawn\""), "the panel no longer offers a HIM entry");
		assertTrue(screen.contains("\"watcher_spawn\""), "the panel no longer offers a dark watcher entry");
		assertTrue(screen.contains("ANOMALY_TOOLBAR"),
				"the HIM entry must stay on the anomaly toolbar, where the other ambient controls are");

		String service = read(SERVICE);
		String branch = service.substring(service.indexOf("case \"him_spawn\""));
		assertTrue(branch.startsWith("case \"him_spawn\""), "the service lost its HIM branch");
		assertTrue(branch.contains("HimService.debugSpawn(player)"),
				"the HIM entry must go through the service that owns the placement rules");
		// The placement rules are the anomaly. A panel entry that dropped the figure in front of
		// the player would be a different feature wearing the same name.
		assertFalse(branch.substring(0, branch.indexOf("yield")).contains("addFreshEntity"),
				"the panel must not place the figure itself");
	}

	private static Set<String> clientActions() throws Exception {
		String screen = read(SCREEN);
		Set<String> actions = new LinkedHashSet<>();
		// The action is the second string literal in the call. The first is matched loosely because a
		// label is allowed to be computed; the action id is not, and this is where that is enforced.
		collect(actions, screen, Pattern.compile("new ActionSpec\\([^,]+,\\s*\"([a-z_]+)\""));
		collect(actions, screen, Pattern.compile("send\\(\"([a-z_]+)\""));
		return actions;
	}

	private static void collect(Set<String> into, String source, Pattern pattern) {
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) into.add(matcher.group(1));
	}

	private static String read(Path path) throws Exception {
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
