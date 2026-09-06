package com.xm.thefourthfrequency.ending;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldInterfaceEndingClientContractTest {
	private static String source(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
	}

	@Test
	void windowsEndingUsesFixedOwnedWriteAheadTransaction() throws Exception {
		String transaction = source(
				"src/client/java/com/xm/thefourthfrequency/meta_windows/WindowsEndingMetaTransaction.java");
		assertTrue(transaction.contains("PREPARED") && transaction.contains("APPLIED")
				&& transaction.contains("NOTEPAD_TYPED") && transaction.contains("LOCKED"));
		assertTrue(transaction.contains("我永远在盯着你......."));
		// The note is the only place a player is told how to undo this, so it has to name the key.
		assertTrue(transaction.contains("按下F8来撤销MOD对电脑造成的所有更改"));
		// SW_RESTORE. A maximized Notepad would cover the failure wallpaper it is written over.
		assertTrue(transaction.contains("ShowWindow(window,9)"));
		assertFalse(transaction.contains("ShowWindow(window,3)") || transaction.contains("IsZoomed"));
		assertTrue(transaction.contains("GetForegroundWindow()==window"));
		assertTrue(transaction.contains("notepad.start") && transaction.contains("TffOwnedNotepad"));
		assertTrue(transaction.contains("wallpaper.path64") && transaction.contains("wallpaper.style")
				&& transaction.contains("wallpaper.tile"));
		// Wallpaper Engine hides the failure wallpaper, so it is paused and resumed - never killed,
		// never started - and the executable it is told to signal is recorded write-ahead.
		assertTrue(transaction.contains("engine.path64") && transaction.contains("'-control', $command"));
		assertTrue(transaction.contains("wallpaper32.exe") && transaction.contains("wallpaper64.exe"));
		assertTrue(transaction.contains("\"STOP\"") && transaction.contains("\"RESUME\""));
		assertFalse(transaction.contains("Stop-Process"));
		assertFalse(transaction.contains("System.exit"));
		assertFalse(transaction.contains("taskkill"));
		assertFalse(transaction.contains("-Command"));
	}

	@Test
	void failureWallpaperHasTheRequiredOwnedDimensions() throws Exception {
		Path imagePath = Path.of(
				"src/main/resources/assets/thefourthfrequency/textures/gui/ending/world_interface_failure.png");
		var image = ImageIO.read(imagePath.toFile());
		assertEquals(2560, image.getWidth());
		assertEquals(1600, image.getHeight());
	}

	@Test
	void localRecoveryOwnsOnlyItsLockPacksAndNormalShutdown() throws Exception {
		String lock = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FailureMenuLockState.java");
		String packs = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceResourcePackLease.java");
		String ending = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceEndingClient.java");
		assertTrue(lock.contains("StandardCopyOption.ATOMIC_MOVE"));
		assertTrue(packs.contains("originalOrder") && packs.contains("autoAddedIds"));
		assertTrue(packs.contains("client.options.updateResourcePacks(repository)")
				&& packs.contains("client.reloadResourcePacks()"));
		assertTrue(ending.contains("disconnectWithSavingScreen") && ending.contains("client::stop"));
		// Both of these paint a progress screen by driving runTick themselves. Called inline from
		// END_CLIENT_TICK they re-enter runTick from inside its own tick, and the failure ending
		// hangs on "Saving world" until the watchdog kills the process. They have to be queued.
		assertTrue(ending.contains("client.execute(() -> {") && ending.contains("client.execute(client::stop)"),
				"shutdown must be queued off the tick that requests it, never called inline");
		assertTrue(ending.contains("thefourthfrequency.safeMode"));
		assertFalse(ending.contains("System.exit"));
	}

	@Test
	void upgradedNoticeDisclosesSafetyBoundariesWithoutSpoilingTheFinale() throws Exception {
		String controller = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeController.java");
		String screen = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeScreen.java");
		JsonObject chinese = JsonParser.parseString(source(
				"src/main/resources/assets/thefourthfrequency/lang/zh_cn.json")).getAsJsonObject();
		// Tracks the revision of the whole first-run flow, not just this copy: it went to 4 when the
		// audio page was added in front of the disclosure, so returning players meet both pages.
		assertTrue(controller.contains("CURRENT_NOTICE_VERSION = 4"));
		assertTrue(screen.contains("screen.thefourthfrequency.first_run_notice.body.safety_v2"));
		assertTrue(screen.contains("screen.thefourthfrequency.first_run_notice.body.recovery_v3"));
		assertFalse(screen.contains("screen.thefourthfrequency.first_run_notice.recovery_hint"));
		assertFalse(chinese.has("screen.thefourthfrequency.first_run_notice.recovery_hint"));
		String disclosure = String.join(" ",
				chinese.get("screen.thefourthfrequency.first_run_notice.eyebrow").getAsString(),
				chinese.get("screen.thefourthfrequency.first_run_notice.body.control").getAsString(),
				chinese.get("screen.thefourthfrequency.first_run_notice.body.safety").getAsString(),
				chinese.get("screen.thefourthfrequency.first_run_notice.body.safety_v2").getAsString(),
				chinese.get("screen.thefourthfrequency.first_run_notice.body.recovery_v3").getAsString());
		assertTrue(disclosure.contains("安全边界") && disclosure.contains("不是病毒"));
		assertTrue(disclosure.contains("不可逆") && disclosure.contains("提前备份"));
		for (String spoiler : new String[]{"失败结局", "成功或失败", "最终战", "末地",
				"壁纸", "Notepad", "已损坏", "封锁"})
			assertFalse(disclosure.contains(spoiler), spoiler);
	}

	@Test
	void endingReplayIsConfirmedScopedAndLanHostSafe() throws Exception {
		String ending = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceEndingClient.java");
		String title = source(
				"src/client/java/com/xm/thefourthfrequency/mixin/TitleScreenErosionMixin.java");
		String quarantine = source(
				"src/main/java/com/xm/thefourthfrequency/ending/EndingWorldQuarantine.java");
		String summary = source(
				"src/client/java/com/xm/thefourthfrequency/mixin/LevelSummaryEndingQuarantineMixin.java");
		String worldOpen = source(
				"src/client/java/com/xm/thefourthfrequency/mixin/WorldOpenFlowsEndingQuarantineMixin.java");
		String lock = source(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FailureMenuLockState.java");
		String fluids = source(
				"src/client/java/com/xm/thefourthfrequency/mixin/LiquidBlockRendererFailureMixin.java");
		assertTrue(ending.contains("getSingleplayerServer().isPublished()"));
		// Every loss now ends the way the LAN host's already did. Driving Minecraft's teardown from a
		// mod hung inside the save of a world this ending had just gutted, and the shutdown watchdog
		// killed the process on "Saving world" - so neither ending closes the client any more.
		assertTrue(ending.contains("Mode.FAILURE_RETURNING") && ending.contains("Mode.RUN_COMPLETE"));
		assertFalse(ending.contains("Mode.LAN_HOST_RETURNING"));
		assertTrue(ending.contains("hud.thefourthfrequency.world_interface.ending.complete"),
				"the epilogue has to tell the player the run is over, since it no longer leaves for them");
		assertFalse(ending.contains("returnToMenuAfterSuccess"));
		assertTrue(ending.contains("new ConfirmScreen"));
		assertTrue(title.contains("menu.singleplayer") && title.contains("menu.multiplayer")
				&& title.contains("menu.online") && title.contains("setTooltip"));
		assertTrue(quarantine.contains("StandardCopyOption.ATOMIC_MOVE")
				&& quarantine.contains(".thefourthfrequency-corrupted")
				&& quarantine.contains("worldId"));
		assertTrue(lock.contains("LevelResource.ROOT") && lock.contains("stageReplayQuarantine()")
				&& lock.contains("LOCK_VERSION = \"4\"")
				&& lock.contains("properties.setProperty(\"serverAddress\", endingServerAddress)"));
		// The seal is scoped to the run that earned it. A local save is sealed by the two quarantine
		// mixins, a remote server by its own address, and the title screen keeps all three entries
		// shut only while the desktop transaction is still owed - never for the ending as such.
		String connect = source(
				"src/client/java/com/xm/thefourthfrequency/mixin/ConnectScreenEndingQuarantineMixin.java");
		assertTrue(connect.contains("method = \"startConnecting\"")
				&& connect.contains("FailureMenuLockState.seals(serverData.ip)")
				&& connect.contains("new AlertScreen"));
		assertTrue(title.contains("WindowsEndingMetaTransaction.hasPendingTransaction()")
				&& title.contains("ending_menu_lock.recovery_pending"));
		// The ending holds every entry point on the title screen until recovery runs.
		//
		// This assertion has been all three ways round. It first required the lock to be absent
		// here; then to reach Singleplayer only; and now to reach every game entry, which is what
		// the user asked for on 2026-08-29. What has not changed is the reason the *quarantine*
		// stays scoped - a run finished on somebody else's server must not seal this client's own
		// saves or the other servers it plays on, and ConnectScreenEndingQuarantineMixin below is
		// still asserted to seal by address. The title screen is doing a different job: refusing to
		// let the game continue at all until the ending has been closed out.
		assertTrue(title.contains("FailureMenuLockState.locked()")
						&& title.contains("endingLocked && thefourthfrequency$isGameEntry(key)")
						&& title.contains("ending_menu_lock.success")
						&& title.contains("ending_menu_lock.failure"),
				"an ending seals every entry and the dead buttons say F8 undoes it");
		assertTrue(summary.contains("selectWorld.thefourthfrequency.corrupted")
				&& summary.contains("primaryActionActive")
				&& summary.contains("canUpload") && summary.contains("canEdit")
				&& summary.contains("canRecreate"));
		assertTrue(worldOpen.contains("method = \"openWorld\"")
				&& worldOpen.contains("EndingWorldQuarantine.isQuarantined")
				&& worldOpen.contains("new AlertScreen"));
		assertFalse(Files.exists(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/EndingReplayResetService.java")));
		assertTrue(fluids.contains("lavaStill") && fluids.contains("lavaFlowing")
				&& fluids.contains("waterStill") && fluids.contains("waterFlowing")
				&& fluids.contains("waterOverlay"));
		String chinese = source("src/main/resources/assets/thefourthfrequency/lang/zh_cn.json");
		assertTrue(chinese.contains("\"存档已损坏\"") && chinese.contains("无法进入"));
	}
}
