package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.io.IOException;

/** Client-local, one-time safety notice shown when the title screen first becomes available. */
public final class FirstRunNoticeController {
	/**
	 * Bumped to 4 when the audio page was added in front of the disclosure.
	 *
	 * <p>The marker's whole job is "has this player seen <em>this</em> first-run flow", and the flow
	 * now has a page in it that decides how loud the mod is. Leaving the marker at 3 would hide that
	 * page from precisely the players who already own the mod - the ones with the most reason to want
	 * it - and they would have no way of knowing the setting existed.
	 */
	private static final int CURRENT_NOTICE_VERSION = 4;
	private static final String VERSION_FILE = "thefourthfrequency-safety-notice.version";
	private static boolean initialized;
	private static boolean acknowledged;
	private static boolean pending;
	private static boolean releasing;

	private FirstRunNoticeController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		acknowledged = readAcknowledged();
		pending = !acknowledged;
		ClientTickEvents.END_CLIENT_TICK.register(FirstRunNoticeController::tick);
	}

	private static void tick(Minecraft client) {
		// Minecraft mounts the title screen behind the still-fading loading overlay, and it ticks
		// screens without checking for one. Opening any earlier would spend the notice entrance -
		// animation and startup sound alike - behind a loading bar the player is still watching.
		if (!pending || acknowledged || FailureMenuLockState.locked() || client.getOverlay() != null
				|| !(client.screen instanceof TitleScreen titleScreen)) return;
		client.setScreen(new FirstRunNoticeScreen(titleScreen));
	}

	/**
	 * Called the moment the player commits to the notice, which is a second and a half before
	 * {@link #acknowledge} runs: the screen still owes an exit animation, and that animation is part
	 * of leaving rather than part of reading.
	 */
	static void beginRelease() {
		releasing = true;
	}

	static void acknowledge(Minecraft client, Screen returnScreen) {
		acknowledged = true;
		pending = false;
		releasing = false;
		writeAcknowledged();
		client.setScreen(returnScreen);
	}

	private static boolean readAcknowledged() {
		Path path = noticeVersionPath();
		try {
			return Files.isRegularFile(path)
					&& Integer.parseInt(Files.readString(path, StandardCharsets.UTF_8).strip()) >= CURRENT_NOTICE_VERSION;
		} catch (IOException | NumberFormatException ignored) {
			return false;
		}
	}

	private static void writeAcknowledged() {
		Path path = noticeVersionPath();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, Integer.toString(CURRENT_NOTICE_VERSION), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException ignored) {
			// The upgraded notice safely appears again if its version marker cannot be persisted.
		}
	}

	public static synchronized boolean resetForReplay() {
		acknowledged = false;
		releasing = false;
		// The current process is closing; the next launch will arm the notice from disk.
		pending = false;
		try {
			Files.deleteIfExists(noticeVersionPath());
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	private static Path noticeVersionPath() {
		return ConfigManager.configPath().resolveSibling(VERSION_FILE).toAbsolutePath().normalize();
	}

	/**
	 * Whether the player is done with the safety notice, and therefore whether the title screen is
	 * theirs again rather than a page they still have to read. Scoring it before that point would
	 * play the menu theme underneath text the notice needs them to actually take in.
	 *
	 * <p>This turns true on the button press rather than on {@link #acknowledge}, which the screen
	 * only reaches after its exit animation. Waiting for that put the menu theme a second and a half
	 * behind the decision, and the fade-in behind it again, so the music arrived well after the
	 * title screen it belongs to. The notice has already been read by the time the button is pressed
	 * - there is nothing left for the silence to protect.</p>
	 */
	public static boolean released() {
		return acknowledged || releasing;
	}

	public static boolean acknowledgedForTesting() { return acknowledged; }
	public static boolean pendingForTesting() { return pending; }
	public static Path noticeVersionPathForTesting() { return noticeVersionPath(); }
	public static void reloadFromDiskForTesting() {
		acknowledged = readAcknowledged();
		releasing = false;
		pending = false;
	}
}
