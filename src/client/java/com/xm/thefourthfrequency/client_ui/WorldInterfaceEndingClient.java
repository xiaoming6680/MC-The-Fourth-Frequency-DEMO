package com.xm.thefourthfrequency.client_ui;

import com.mojang.blaze3d.platform.IconSet;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.meta_api.MetaController;
import com.xm.thefourthfrequency.meta_windows.WindowsEndingMetaTransaction;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates local cleanup, pack restoration, durable failure locking and normal shutdown. */
public final class WorldInterfaceEndingClient {
	private static final Identifier FAILURE_IMAGE = Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "textures/gui/ending/world_interface_failure.png");
	private static final int FAILURE_TEXTURE_WIDTH = 2560;
	private static final int FAILURE_TEXTURE_HEIGHT = 1600;
	private static final int FALLBACK_MINIMUM_TICKS = 160;
	/** The failure screen holds for at least this long even if the world is already gone. */
	private static final int SHUTDOWN_MINIMUM_TICKS = 40;
	/**
	 * Backstop for a save that never reports finished. Long enough that an ordinary world - and this
	 * one has just had thousands of blocks torn out of it - has room to write itself out.
	 */
	private static final int SHUTDOWN_TIMEOUT_TICKS = 1_200;
	/** How long the "the run is over" action bar keeps re-posting itself, and how often. */
	private static final int RUN_COMPLETE_PROMPT_TICKS = 1_200;
	private static final int RUN_COMPLETE_PROMPT_INTERVAL = 40;
	private static boolean initialized;
	private static boolean recoveryRequested;
	private static boolean replayResetRequested;
	private static boolean recoveryStarted;
	private static Mode mode = Mode.IDLE;
	private static WindowLease presentationWindow;
	private static int modeTicks;
	private static boolean fallbackMayShutdown;
	private static boolean disconnectIssued;
	private static boolean stopIssued;
	private static boolean failureLanHost;
	private static UUID failureEncounterId;

	private WorldInterfaceEndingClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		FailureMenuLockState.initialize();
		WorldInterfaceResourcePackLease.initialize();
		// A win is the only thing that ends the presentation. A loss used to retire it too, which
		// meant one failed run permanently turned the client back into ordinary Minecraft: every
		// later session came up with the vanilla window title and icon instead of Alpha 1.0.0, even
		// though the story it belongs to has not finished. A loss is "not yet", not "over".
		//
		// A pending Windows transaction was not a second reason to retire, it was that same loss
		// wearing a different hat: only the failure ending writes that manifest, and it deliberately
		// stays on disk until F8 rolls the desktop back. Testing for it here therefore retired the
		// presentation on the next launch after every single loss - and because the Alpha pack order
		// only ever lives in memory, that launch came up in vanilla textures.
		if (endingWasWon() || WorldInterfaceResourcePackLease.presentationRetired()) {
			Minecraft client = Minecraft.getInstance();
			if (client.options != null) {
				WorldInterfaceResourcePackLease.adoptExistingAutomaticSelection(
						List.copyOf(client.options.resourcePacks));
			}
			AlphaLoadSessionController.retirePresentation();
		}
		recoveryRequested = Boolean.getBoolean("thefourthfrequency.safeMode");
		ClientTickEvents.END_CLIENT_TICK.register(WorldInterfaceEndingClient::tick);
	}

	public static void observeEncounter(Minecraft client, WorldInterfaceSnapshotS2C snapshot) {
		if (presentationWindow != null || client.getWindow() == null) return;
		switch (snapshot.stage()) {
			case SUMMONING, PHASE_1, PHASE_2, PHASE_3, SUCCESS_RESOLUTION,
					FAILURE_RESOLUTION, PORTAL_OPEN -> presentationWindow = WindowLease.capture(client);
			default -> { }
		}
	}

	public static boolean onPoemAcknowledged(PoemStartS2C poem,
			WorldInterfaceProtocol.PoemCompletion completion, Runnable completionAck) {
		Minecraft client = Minecraft.getInstance();
		if (mode != Mode.IDLE || client == null || completionAck == null) return false;
		cleanupRuntime(client);
		// Filed before either branch below, because both of them are worth leaving a note about and
		// only one of them ends with the client still in a fit state to write anything.
		PreviousRunClient.record(poem.outcome(), poem.destroyedAnchors(),
				client.level == null ? 0L : client.level.getGameTime() / 24_000L + 1L);
		if (poem.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS) {
			// Locked here, not in the restore callback, for the same reason the note above is filed
			// here: this is the last point at which the client is certainly still able to write.
			//
			// The callback runs after WorldInterfaceResourcePackLease.restoreAsync, which is a full
			// resource reload - seconds of it - and it only runs at all if the client survives to
			// execute the scheduled task. A player who quit out during that window finished the mod
			// and landed on a title screen with nothing sealed, which is the one outcome the durable
			// lock exists to make impossible. The failure branch never had this problem because it
			// locks on the spot.
			//
			// Without the window snapshot, deliberately: the ending's presentation window is still up
			// and a lock that captured it would restore the player back into it. The call in the
			// callback below takes the snapshot once the window has been handed back, and reaches the
			// write because an identity-only lock does not satisfy the idempotence check.
			if (!FailureMenuLockState.lock(poem.encounterId(), poem.outcome(), poem.worldId(), client, false)) {
				TheFourthFrequency.LOGGER.error("Success ending could not persist its local replay lock");
			}
			// Before the restore, not after it. Restoring the packs is a full resource reload, which
			// tears the sound engine down and builds it again - and whatever the music director still
			// wants when it comes back is started from the top. This used to be released in the
			// completion callback, which runs once the reload has finished, so the reload found the
			// ending track still owning the score and the victory theme played a second time over a
			// player who had already finished reading the poem it belonged to.
			WorldInterfaceVanillaPoemClient.releaseScore();
			// Handed back to ordinary play here, and followed out of the world later: the run is over,
			// so whatever is playing when this player finally quits belongs to their ending rather
			// than to the title screen they land on. The hold waits for the world to come back before
			// it arms, so the pack restore below is still scored as the gap it is.
			MusicDirector.noteRunEnded();
			AlphaLoadSessionController.retirePresentation();
			mode = Mode.SUCCESS_RESTORING;
			WorldInterfaceResourcePackLease.restoreAsync(client).whenComplete((restored, failure) ->
					client.execute(() -> {
						if (failure != null || !Boolean.TRUE.equals(restored)) {
							TheFourthFrequency.LOGGER.error("Success cleanup could not restore resource packs", failure);
						}
						restorePresentationWindow(client);
						// Re-locks only to attach the window snapshot; the identity was already sealed
						// before the reload started, so a failure here costs the window restore rather
						// than the lock itself.
						if (!FailureMenuLockState.lock(poem.encounterId(), poem.outcome(), poem.worldId(), client)) {
							TheFourthFrequency.LOGGER.error("Success ending could not record its window snapshot");
						}
						completionAck.run();
						beginRunCompletePrompt();
					}));
			return true;
		}

		restorePresentationWindow(client, false);
		failureLanHost = isPublishedLanHost(client);
		failureEncounterId = poem.encounterId();
		boolean durableLock = FailureMenuLockState.lock(poem.encounterId(), poem.outcome(), poem.worldId(), client);
		if (!durableLock) {
			TheFourthFrequency.LOGGER.error("Failure ending could not persist its local menu lock");
		}
		mode = Mode.FAILURE_PRESENTING;
		modeTicks = 0;
		fallbackMayShutdown = false;
		client.setScreen(new FailureFallbackScreen(true));
		if (!durableLock) {
			if (client.screen instanceof FailureFallbackScreen fallback) fallback.systemFailed();
			fallbackMayShutdown = true;
			completionAck.run();
			return true;
		}
		if (WindowsEndingMetaTransaction.isWindows() && MetaController.enabled()) {
			WindowsEndingMetaTransaction.applyAsync(client, poem.encounterId()).whenComplete((applied, failure) ->
					client.execute(() -> {
						if (failure == null && Boolean.TRUE.equals(applied)) {
							completionAck.run();
							concludeFailure(client);
						} else {
							WindowsEndingMetaTransaction.restoreAsync(client).whenComplete((restored, restoreFailure) ->
									client.execute(() -> {
										fallbackMayShutdown = true;
										if (client.screen instanceof FailureFallbackScreen fallback) fallback.systemFailed();
										completionAck.run();
									}));
						}
					}));
		} else {
			fallbackMayShutdown = true;
			if (client.screen instanceof FailureFallbackScreen fallback) fallback.systemFailed();
			completionAck.run();
		}
		return true;
	}

	public static void requestRecovery() {
		replayResetRequested = false;
		recoveryRequested = true;
	}

	public static void requestRecoveryConfirmation(Minecraft client) {
		if (client == null || !FailureMenuLockState.locked() || recoveryStarted) return;
		Screen returnScreen = client.screen;
		client.setScreen(new ConfirmScreen(confirmed -> {
			if (!confirmed) {
				client.setScreen(returnScreen);
				return;
			}
			client.setScreen(new RecoveryProgressScreen(returnScreen));
			replayResetRequested = true;
			recoveryRequested = true;
		}, Component.translatable("screen.thefourthfrequency.ending_reset.title"),
				// A won run and a lost run reset identically, but they are not the same message. The
				// failure copy walks through the desktop and resource-pack effects being undone,
				// because that is what a player who just lost needs reassuring about; a player who
				// just won is being told what happens to the save they won on.
				Component.translatable(FailureMenuLockState.outcome()
						== WorldInterfaceProtocol.Outcome.SUCCESS
						? "screen.thefourthfrequency.ending_reset.body.success"
						: "screen.thefourthfrequency.ending_reset.body"),
				Component.translatable("screen.thefourthfrequency.ending_reset.confirm"),
				CommonComponents.GUI_CANCEL));
	}

	public static boolean replayResetAvailable() {
		return FailureMenuLockState.locked() && switch (mode) {
			case IDLE, FAILURE_RETURNING, RUN_COMPLETE -> true;
			case FAILURE_PRESENTING -> fallbackMayShutdown;
			default -> false;
		};
	}

	public static boolean recoveryRequired() {
		return FailureMenuLockState.locked() || WindowsEndingMetaTransaction.hasPendingTransaction();
	}

	private static void tick(Minecraft client) {
		if (recoveryRequested && !recoveryStarted) startRecovery(client);
		if (mode == Mode.FAILURE_PRESENTING) {
			modeTicks++;
			if (fallbackMayShutdown && modeTicks >= FALLBACK_MINIMUM_TICKS) concludeFailure(client);
		} else if (mode == Mode.FAILURE_RETURNING) {
			if (failureEncounterId != null && client.level != null && client.player != null
					&& Level.OVERWORLD.equals(client.level.dimension())) {
				LanHostFailureVisualState.activate(client, failureEncounterId);
				beginRunCompletePrompt();
			}
		} else if (mode == Mode.RUN_COMPLETE) {
			tickRunCompletePrompt(client);
		} else if (mode == Mode.SHUTTING_DOWN) {
			modeTicks++;
			if (!disconnectIssued) {
				disconnectIssued = true;
				// Queued, never called inline.
				//
				// This runs inside END_CLIENT_TICK, which is inside Minecraft#tick, which is inside
				// Minecraft#runTick. Every disconnect path sets a progress screen and then drives
				// runTick itself to paint it - so calling one from here re-entered runTick from
				// within its own tick, and the failure ending hung forever on "Saving world" until
				// the watchdog killed the process. Minecraft#execute drains on the next frame,
				// outside this call stack, which is the whole fix.
				client.execute(() -> {
					if (client.level == null) return;
					if (client.hasSingleplayerServer()) client.disconnectWithSavingScreen();
					else client.disconnectFromWorld(Component.translatable(
							"disconnect.thefourthfrequency.world_interface.ending"));
				});
			}
			// disconnectWithSavingScreen() is asynchronous. Stopping on a flat forty-tick timer meant
			// the client tore its own render, audio and resource systems down two seconds in, while
			// the integrated server was still writing the world out - which is why the failure
			// ending exited into an error or hung instead of closing. Wait for the world and the
			// server to actually be gone; the timer is only a floor and a last-resort ceiling.
			if (!stopIssued && (readyToStop(client) || modeTicks >= SHUTDOWN_TIMEOUT_TICKS)) {
				// Queued for the same reason: stop() tears down the window and the render system,
				// which must not happen partway through the tick that asked for it.
				stopIssued = true;
				client.execute(client::stop);
			}
		}
	}

	private static boolean readyToStop(Minecraft client) {
		return modeTicks >= SHUTDOWN_MINIMUM_TICKS && client.level == null
				&& client.getSingleplayerServer() == null;
	}

	private static void startRecovery(Minecraft client) {
		recoveryStarted = true;
		recoveryRequested = false;
		mode = Mode.RECOVERING;
		boolean replayReset = replayResetRequested && FailureMenuLockState.locked();
		replayResetRequested = false;
		MetaController.setEnabled(false);
		AlphaLoadSessionController.retirePresentation();
		cleanupRuntime(client);
		CompletableFuture<Boolean> packs = WorldInterfaceResourcePackLease.restoreAsync(client);
		CompletableFuture<Boolean> windows = WindowsEndingMetaTransaction.restoreAsync(client);
		packs.thenCombine(windows, (packsRestored, windowsRestored) -> packsRestored && windowsRestored)
				.whenComplete((restored, failure) -> client.execute(() -> {
					if (failure != null || !Boolean.TRUE.equals(restored)) {
						TheFourthFrequency.LOGGER.error("Safe ending recovery remains incomplete", failure);
						recoveryStarted = false;
						mode = Mode.IDLE;
						recoveryFailed(client);
						return;
					}
					FailureMenuLockState.restoreWindow(client);
					restorePresentationWindow(client);
					if (replayReset && !resetLocalProgress(client)) {
						recoveryStarted = false;
						mode = Mode.IDLE;
						recoveryFailed(client);
						return;
					}
					if (replayReset && !FailureMenuLockState.stageReplayQuarantine()) {
						recoveryStarted = false;
						mode = Mode.IDLE;
						recoveryFailed(client);
						return;
					}
					if (!FailureMenuLockState.unlockAfterLocalRecovery()) {
						recoveryStarted = false;
						mode = Mode.IDLE;
						recoveryFailed(client);
						return;
					}
					MetaController.setEnabled(false);
					recoveryStarted = false;
					if (replayReset) beginNormalShutdown();
					else {
						mode = Mode.IDLE;
						if (client.level == null) client.setScreen(new TitleScreen());
					}
				}));
	}

	private static boolean resetLocalProgress(Minecraft client) {
		boolean configReset = ConfigManager.resetClientState();
		boolean noticeReset = FirstRunNoticeController.resetForReplay();
		boolean presentationReset = WorldInterfaceResourcePackLease.resetPresentationForReplay();
		DimensionViewDistanceController.resetForReplay(client);
		MenuErosionState.resetForReplay();
		AlphaLoadSessionController.resetForReplay();
		LanHostFailureVisualState.reset(client);
		return configReset && noticeReset && presentationReset;
	}

	private static void recoveryFailed(Minecraft client) {
		if (client.screen instanceof RecoveryProgressScreen progress) progress.failed();
		else client.setScreen(new RecoveryProgressScreen(new TitleScreen(), true));
	}

	private static void cleanupRuntime(Minecraft client) {
		WorldInterfaceClientState.clearSession();
		WorldInterfacePresentationController.resetForEnding();
		AnomalyPresentationController.restoreForMetaToggle();
		MetaController.restore();
		if (client.options != null) {
			client.options.keyUp.setDown(false);
			client.options.keyDown.setDown(false);
			client.options.keyLeft.setDown(false);
			client.options.keyRight.setDown(false);
			client.options.keyJump.setDown(false);
			client.options.keyShift.setDown(false);
			client.options.keyAttack.setDown(false);
			client.options.keyUse.setDown(false);
			client.options.keyDrop.setDown(false);
		}
	}

	private static void beginNormalShutdown() {
		mode = Mode.SHUTTING_DOWN;
		modeTicks = 0;
		disconnectIssued = false;
		stopIssued = false;
	}

	/**
	 * Every loss now ends the way the published LAN host's already did: the client stays open and the
	 * player is handed back the world they lost, corrupted renderers and all.
	 *
	 * <p>The host was exempted from the shutdown because disconnecting them ends everyone's session.
	 * The shutdown then turned out to be unreliable for everybody - it hung inside the save of a
	 * world this ending has just torn thousands of blocks out of, and the shutdown watchdog killed
	 * the process on a "Saving world" screen - so the exemption became the rule.</p>
	 */
	private static void concludeFailure(Minecraft client) {
		fallbackMayShutdown = false;
		mode = Mode.FAILURE_RETURNING;
		if (client != null && client.screen instanceof FailureFallbackScreen) client.setScreen(null);
	}

	/**
	 * Hands the finished world back and asks the player to leave, instead of leaving for them.
	 *
	 * <p>Both endings used to end the session themselves - a win disconnected to the menu, a loss
	 * disconnected and then stopped the client. Neither survived contact with a world this ending
	 * has just torn thousands of blocks out of: the client hung inside the save, and the shutdown
	 * watchdog killed the process on a "Saving world" screen. Driving that from a mod means owning
	 * a teardown whose timing Minecraft does not actually guarantee.</p>
	 *
	 * <p>So the run ends where vanilla already put the player. The poem acknowledgement still lets
	 * vanilla perform its respawn, which is what moves them out of the End - skipping it would
	 * strand them there on the next login - and from the Overworld the action bar says the story is
	 * over and the menu is where "over" lives. Quitting is one pause menu away, and it is the
	 * ordinary quit path that Minecraft does guarantee.</p>
	 */
	private static void beginRunCompletePrompt() {
		mode = Mode.RUN_COMPLETE;
		modeTicks = 0;
	}

	/**
	 * Re-posts itself because a vanilla action bar fades after about sixty ticks, and a player who
	 * spends the epilogue looking at their own base would otherwise never see it. Bounded rather
	 * than permanent: after a minute the prompt has been made, and holding the overlay forever would
	 * cost them every item name and every other action bar the game has to show.
	 */
	private static void tickRunCompletePrompt(Minecraft client) {
		modeTicks++;
		if (client.player == null || client.level == null) return;
		if (modeTicks > RUN_COMPLETE_PROMPT_TICKS) {
			mode = Mode.IDLE;
			return;
		}
		if (modeTicks % RUN_COMPLETE_PROMPT_INTERVAL != 1) return;
		client.player.displayClientMessage(Component.translatable(
				"hud.thefourthfrequency.world_interface.ending.complete"), true);
	}

	private static boolean isPublishedLanHost(Minecraft client) {
		return client != null && client.hasSingleplayerServer()
				&& client.getSingleplayerServer() != null
				&& client.getSingleplayerServer().isPublished();
	}

	/** A durable lock only means the presentation is over when the run that wrote it was won. */
	private static boolean endingWasWon() {
		return FailureMenuLockState.locked()
				&& FailureMenuLockState.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS;
	}

	private static void restorePresentationWindow(Minecraft client) {
		restorePresentationWindow(client, true);
	}

	/**
	 * @param restoreVanillaChrome whether the release icon goes back on the window. Only a retired
	 *        presentation wants that. Putting it back on a loss showed the vanilla client for as
	 *        long as the failure screen was up, which is precisely the illusion the ending is
	 *        holding onto. The title is always refreshed and needs no such guard: the retention
	 *        mixin re-stamps Alpha 1.0.0 over it while the presentation is still running.
	 */
	private static void restorePresentationWindow(Minecraft client, boolean restoreVanillaChrome) {
		if (presentationWindow != null) {
			presentationWindow.restore(client);
			presentationWindow = null;
		}
		if (client.getWindow() == null) return;
		client.updateTitle();
		if (!restoreVanillaChrome) return;
		try { client.getWindow().setIcon(client.getVanillaPackResources(), IconSet.RELEASE); }
		catch (IOException exception) {
			TheFourthFrequency.LOGGER.warn("Could not restore the vanilla window icon", exception);
		}
	}

	private enum Mode {
		IDLE,
		SUCCESS_RESTORING,
		FAILURE_PRESENTING,
		/** Waiting for the vanilla respawn to land the player back in the Overworld. */
		FAILURE_RETURNING,
		/** The epilogue: the world is handed back and the player is asked to leave, not made to. */
		RUN_COMPLETE,
		/** Only the confirmed F8 replay reset still closes the client, and only from the menu. */
		SHUTTING_DOWN,
		RECOVERING
	}

	private static final class RecoveryProgressScreen extends Screen {
		private final Screen returnScreen;
		private boolean failed;

		private RecoveryProgressScreen(Screen returnScreen) {
			this(returnScreen, false);
		}

		private RecoveryProgressScreen(Screen returnScreen, boolean failed) {
			super(Component.translatable("screen.thefourthfrequency.ending_reset.title"));
			this.returnScreen = returnScreen;
			this.failed = failed;
		}

		private void failed() {
			failed = true;
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			super.render(graphics, mouseX, mouseY, partialTick);
			graphics.drawCenteredString(font, Component.translatable(failed
					? "screen.thefourthfrequency.ending_reset.failed"
					: "screen.thefourthfrequency.ending_reset.progress"), width / 2, height / 2, 0xFFFFFFFF);
		}

		@Override public boolean shouldCloseOnEsc() { return failed; }
		@Override public void onClose() {
			if (failed && minecraft != null) minecraft.setScreen(returnScreen == null ? new TitleScreen() : returnScreen);
		}
		@Override public boolean isPauseScreen() { return true; }
	}

	private static final class FailureFallbackScreen extends Screen {
		private boolean systemAttempt;

		private FailureFallbackScreen(boolean systemAttempt) {
			super(Component.translatable("screen.thefourthfrequency.world_interface.poem.failure.title"));
			this.systemAttempt = systemAttempt;
		}

		private void systemFailed() {
			systemAttempt = false;
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, FAILURE_IMAGE, 0, 0, 0.0F, 0.0F,
					width, height, FAILURE_TEXTURE_WIDTH, FAILURE_TEXTURE_HEIGHT,
					FAILURE_TEXTURE_WIDTH, FAILURE_TEXTURE_HEIGHT);
			graphics.fill(0, 0, width, height, 0x44000000);
			int panelWidth = Math.min(520, width - 48);
			int panelHeight = 92;
			int left = (width - panelWidth) / 2;
			int top = height - panelHeight - 26;
			graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE80A0710);
			graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFFC33DDA);
			graphics.drawString(font, Component.translatable(systemAttempt
					? "screen.thefourthfrequency.world_interface.failure.system_attempt"
					: "screen.thefourthfrequency.world_interface.failure.safe_fallback"), left + 14, top + 12,
					0xFFB98CC8, false);
			String finalLine = Component.translatable(
					"ending.thefourthfrequency.world_interface.poem.failure.15").getString();
			int visible = Math.min(finalLine.length(), Math.max(0, modeTicks / 5));
			String typed = finalLine.substring(0, visible);
			graphics.drawCenteredString(font, Component.literal(typed), width / 2, top + 42, 0xFFFFE6FF);
			graphics.drawCenteredString(font, Component.translatable(
					failureLanHost
							? "screen.thefourthfrequency.world_interface.failure.lan_host_return"
							: "screen.thefourthfrequency.world_interface.failure.normal_shutdown"),
					width / 2, top + 67, 0xFF8E8294);
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void onClose() { }
		@Override public boolean isPauseScreen() { return false; }
	}

	private record WindowLease(boolean fullscreen, boolean maximized, int x, int y, int width, int height) {
		private static WindowLease capture(Minecraft client) {
			long handle = client.getWindow().handle();
			int[] x = new int[1], y = new int[1], width = new int[1], height = new int[1];
			GLFW.glfwGetWindowPos(handle, x, y);
			GLFW.glfwGetWindowSize(handle, width, height);
			return new WindowLease(client.getWindow().isFullscreen(),
					GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE,
					x[0], y[0], width[0], height[0]);
		}

		private void restore(Minecraft client) {
			long handle = client.getWindow().handle();
			if (fullscreen != client.getWindow().isFullscreen()) client.getWindow().toggleFullScreen();
			if (!fullscreen) {
				GLFW.glfwRestoreWindow(handle);
				GLFW.glfwSetWindowPos(handle, x, y);
				GLFW.glfwSetWindowSize(handle, width, height);
				if (maximized) GLFW.glfwMaximizeWindow(handle);
			}
		}
	}
}
