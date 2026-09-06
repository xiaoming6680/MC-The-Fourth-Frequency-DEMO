package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio;
import com.xm.thefourthfrequency.client_ui.AlphaCorruptionRenderer;
import com.xm.thefourthfrequency.client_ui.AlphaLoadSessionController;
import com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline;
import com.xm.thefourthfrequency.client_ui.PersistentAlphaLoadingStyle;
import com.xm.thefourthfrequency.client_ui.PursuitPresentationClient;
import com.xm.thefourthfrequency.client_ui.UnrenderedLayerClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenCorruptionMixin {
	/**
	 * Bright red, well clear of the wall's own.
	 *
	 * <p>The wall runs 206-248 in red with almost nothing in the other channels. Matching that
	 * hue would hide the line; lifting green and blue together keeps it unmistakably red while
	 * putting it far enough above the wall in brightness to be picked out at a glance.</p>
	 */
	@Unique private static final int INTRUSION_COLOR = 0xFFFF5C57;
	/** Off the centre line, below and right of it: near enough to catch, far enough to miss. */
	@Unique private static final float INTRUSION_ROW_FRACTION = 0.62F;
	@Unique private static final float INTRUSION_SLOT_FRACTION = 0.55F;
	@Shadow private LevelLoadTracker loadTracker;
	@Shadow private float smoothedProgress;
	@Unique private int thefourthfrequency$screenTicks;
	@Unique private boolean thefourthfrequency$worldEntryReason;
	@Unique private boolean thefourthfrequency$corruptionClaimed;
	@Unique private boolean thefourthfrequency$viewportFlooded;
	@Unique private boolean thefourthfrequency$testScreenshotRequested;
	@Unique private boolean thefourthfrequency$legacyFrameRecorded;
	@Unique private boolean thefourthfrequency$legacyScreenshotRequested;
	@Unique private boolean thefourthfrequency$recoveryFaultApplied;
	@Unique private float thefourthfrequency$legacyProgress = 0.04F;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void thefourthfrequency$rememberEntryReason(LevelLoadTracker tracker,
			LevelLoadingScreen.Reason reason, CallbackInfo callback) {
		thefourthfrequency$worldEntryReason = reason == LevelLoadingScreen.Reason.OTHER;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void thefourthfrequency$advanceFailure(CallbackInfo callback) {
		if (thefourthfrequency$worldEntryReason && !thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.claimInitialCorruptionScreen()) {
			thefourthfrequency$corruptionClaimed = true;
			thefourthfrequency$screenTicks = 0;
		}
		thefourthfrequency$screenTicks++;
		if (!thefourthfrequency$shouldCorrupt()) return;
		AlphaLoadSessionController.loadingScreenTick(thefourthfrequency$screenTicks);
		Minecraft client = Minecraft.getInstance();
		AlphaCorruptionAudio.tick(client, thefourthfrequency$screenTicks);
		if (thefourthfrequency$screenTicks == AlphaLoadTimeline.GLITCH_START_TICK) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(
					ModSounds.ALPHA_CORRUPTION_WARNING, 1.0F, 0.62F));
		} else if (thefourthfrequency$screenTicks == AlphaLoadTimeline.FLOOD_START_TICK) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(
					ModSounds.ALPHA_CORRUPTION_COLLAPSE, 1.0F, 0.96F));
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void thefourthfrequency$holdVanillaProgressAtHalf(CallbackInfo callback) {
		if (!thefourthfrequency$worldEntryReason) return;
		if (!thefourthfrequency$corruptionClaimed) {
			if (AlphaLoadSessionController.shouldPrepareInitialCorruptionScreen()) {
				smoothedProgress = Math.min(smoothedProgress, 0.5F);
			}
			return;
		}
		if (!AlphaLoadSessionController.shouldCorruptLoadingScreen()
				|| AlphaLoadTimeline.legacyRecoveryFrame(thefourthfrequency$screenTicks)) return;
		// During the prelude the bar creeps up and then loses ground. Nothing has visibly
		// corrupted yet, so this is the first thing the player cannot explain by waiting longer.
		smoothedProgress = AlphaLoadTimeline.initialNormalFrame(thefourthfrequency$screenTicks)
				? AlphaLoadTimeline.initialNormalProgress(thefourthfrequency$screenTicks)
				: 0.5F;
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;isLevelReady()Z"))
	private boolean thefourthfrequency$holdForBoundedFailure(LevelLoadTracker tracker) {
		if (thefourthfrequency$worldEntryReason && !thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.shouldPrepareInitialCorruptionScreen()) return false;
		return tracker.isLevelReady()
				&& (!thefourthfrequency$shouldCorrupt()
				|| AlphaLoadSessionController.canCloseLoadingScreen(thefourthfrequency$screenTicks));
	}

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$renderStableFirstEntryBackground(GuiGraphics graphics,
			int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		boolean preparing = thefourthfrequency$worldEntryReason
				&& !thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.shouldPrepareInitialCorruptionScreen();
		if (!preparing && !thefourthfrequency$shouldCorrupt()) return;
		thefourthfrequency$drawWorldLoadingDirtBackground(graphics);
		callback.cancel();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void thefourthfrequency$coverHalfProgressHandoffBeforeVanillaRender(
			GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
			CallbackInfo callback) {
		boolean preparing = thefourthfrequency$worldEntryReason
				&& !thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.shouldPrepareInitialCorruptionScreen();
		if (preparing || thefourthfrequency$shouldCorrupt()) {
			thefourthfrequency$drawWorldLoadingDirtBackground(graphics);
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void thefourthfrequency$hideVanillaLoadingText(GuiGraphics graphics, Font font,
			Component text, int centerX, int y, int color) {
		if (!thefourthfrequency$shouldCorrupt()
				|| AlphaLoadTimeline.initialNormalFrame(thefourthfrequency$screenTicks)) {
			graphics.drawCenteredString(font, text, centerX, y, color);
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$renderLegacyLoadingScreen(GuiGraphics graphics, int mouseX,
			int mouseY, float partialTick, CallbackInfo callback) {
		// Both private dimensions cover this screen, for the same reason and through the same line.
		// The HUD blackout each of them raises is drawn from HudRenderCallback, which does not run
		// while a Screen is up - so the one frame it exists to hide, the cross-dimension load, is
		// exactly the frame it was missing. Falling into the unrendered layer showed the player the
		// terrain progress spinner in the middle of a sequence whose entire premise is that they did
		// not see what happened.
		if (PursuitPresentationClient.shouldCoverLoadingScreen()
				|| UnrenderedLayerClient.covering()) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
			callback.cancel();
			return;
		}
		if (thefourthfrequency$corruptionClaimed
				&& !AlphaLoadSessionController.shouldCorruptLoadingScreen()) {
			// Keep the final loading frame covered until the world produces its first frame.
			thefourthfrequency$drawWorldLoadingDirtBackground(graphics);
			callback.cancel();
			return;
		}
		boolean initialNormalPrelude = thefourthfrequency$corruptionClaimed
				&& thefourthfrequency$shouldCorrupt()
				&& AlphaLoadTimeline.initialNormalFrame(thefourthfrequency$screenTicks);
		boolean recoveringFromCorruption = thefourthfrequency$corruptionClaimed
				&& thefourthfrequency$shouldCorrupt()
				&& AlphaLoadTimeline.legacyRecoveryFrame(thefourthfrequency$screenTicks);
		boolean blackout = thefourthfrequency$corruptionClaimed
				&& thefourthfrequency$shouldCorrupt()
				&& AlphaLoadTimeline.blackoutFrame(thefourthfrequency$screenTicks);
		boolean activeCorruption = thefourthfrequency$corruptionClaimed
				&& thefourthfrequency$shouldCorrupt()
				&& !initialNormalPrelude && !blackout && !recoveringFromCorruption;
		boolean subsequentLegacy = !thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.shouldRenderLegacyLoadingScreen();
		if (blackout) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
			AlphaCorruptionRenderer.drawDeadAir(graphics, thefourthfrequency$screenTicks);
			callback.cancel();
			return;
		}
		if (initialNormalPrelude || activeCorruption) return;
		if (!initialNormalPrelude && !activeCorruption && !recoveringFromCorruption
				&& !subsequentLegacy) return;
		Minecraft client = Minecraft.getInstance();
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		thefourthfrequency$drawWorldLoadingDirtBackground(graphics);
		int centerX = width / 2;
		int centerY = height / 2;
		graphics.drawCenteredString(client.font, Component.translatable(
				"screen.thefourthfrequency.legacy_loading.generating_world"),
				centerX, centerY - 17, 0xFFFFFFFF);
		graphics.drawCenteredString(client.font, Component.translatable(
				"screen.thefourthfrequency.legacy_loading.generating_terrain"),
				centerX, centerY + 2, 0xFFFFFFFF);

		float target = loadTracker.hasProgress() ? loadTracker.serverProgress()
				: loadTracker.isLevelReady() ? 1.0F : 0.08F;
		thefourthfrequency$legacyProgress += (Math.clamp(target, 0.0F, 1.0F)
				- thefourthfrequency$legacyProgress) * 0.18F;
		// Once, well after the picture has settled and the screen has earned back some trust.
		// Guarded by a flag rather than an exact tick because render is not tied to the tick rate.
		if (recoveringFromCorruption && !thefourthfrequency$recoveryFaultApplied
				&& AlphaLoadTimeline.recoveryProgressFault(thefourthfrequency$screenTicks)) {
			thefourthfrequency$recoveryFaultApplied = true;
			thefourthfrequency$legacyProgress *= 0.55F;
		}
		int barWidth = Math.min(120, Math.max(40, width - 40));
		int barLeft = centerX - barWidth / 2;
		int barY = centerY + 16;
		graphics.fill(barLeft, barY, barLeft + barWidth, barY + 2, 0xFF808080);
		graphics.fill(barLeft, barY,
				barLeft + Math.round(barWidth * thefourthfrequency$legacyProgress), barY + 2,
				0xFF80FF80);

		if (recoveringFromCorruption) {
			AlphaCorruptionRenderer.drawRecoveryLock(graphics, thefourthfrequency$screenTicks);
		}

		if (!thefourthfrequency$legacyFrameRecorded) {
			thefourthfrequency$legacyFrameRecorded = true;
			AlphaLoadSessionController.recordLegacyLoadingScreenRendered();
		}
		if (!thefourthfrequency$legacyScreenshotRequested
				&& thefourthfrequency$isClientGameTest()
				&& (!initialNormalPrelude || thefourthfrequency$screenTicks
						>= AlphaLoadTimeline.NORMAL_PROGRESS_END_TICK)) {
			thefourthfrequency$legacyScreenshotRequested = true;
			AlphaLoadSessionController.requestRegressionScreenshot("legacy-loading-normal.png");
		}
		callback.cancel();
	}

	@Unique
	private static void thefourthfrequency$drawWorldLoadingDirtBackground(GuiGraphics graphics) {
		PersistentAlphaLoadingStyle.drawWorldLoadingBackground(graphics);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void thefourthfrequency$renderFailureOverVanillaPage(GuiGraphics graphics, int mouseX,
			int mouseY, float partialTick, CallbackInfo callback) {
		if (thefourthfrequency$corruptionClaimed
				&& thefourthfrequency$shouldCorrupt()
				&& !AlphaLoadTimeline.initialNormalFrame(thefourthfrequency$screenTicks)
				&& !AlphaLoadTimeline.blackoutFrame(thefourthfrequency$screenTicks)
				&& !AlphaLoadTimeline.legacyRecoveryFrame(thefourthfrequency$screenTicks)) {
			thefourthfrequency$renderTerrainFailureContents(graphics);
		}
	}

	@Unique
	private void thefourthfrequency$renderTerrainFailureContents(GuiGraphics graphics) {
		if (!thefourthfrequency$shouldCorrupt()
				|| thefourthfrequency$screenTicks < AlphaLoadTimeline.GLITCH_START_TICK
				|| AlphaLoadTimeline.legacyRecoveryFrame(thefourthfrequency$screenTicks)) return;
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		int motionTick = AlphaLoadTimeline.failureMotionTick(thefourthfrequency$screenTicks);
		int centerX = graphics.guiWidth() / 2;
		int labelY = thefourthfrequency$labelY(graphics);
		String prefix = Component.translatable("screen.thefourthfrequency.alpha_loading.prefix").getString();
		boolean failed = thefourthfrequency$screenTicks >= AlphaLoadTimeline.FAILURE_TICK;
		String suffix = Component.translatable(!failed
				? "screen.thefourthfrequency.alpha_loading.progress"
				: "screen.thefourthfrequency.alpha_loading.failed").getString();
		String failedLine = thefourthfrequency$failedLine();
		if (AlphaLoadTimeline.fullScreenFailureWall(thefourthfrequency$screenTicks)) {
			thefourthfrequency$renderFullScreenFailureWall(graphics, font, failedLine);
			AlphaCorruptionRenderer.drawMediumLayers(graphics, thefourthfrequency$screenTicks);
			if (AlphaLoadTimeline.frozenObserverVisible(thefourthfrequency$screenTicks)) {
				// Drawn without the tremor every other line carries: on a frame where nothing is
				// allowed to move, this is steady, and being steady is what makes it wrong.
				String observer = Component.translatable(
						"screen.thefourthfrequency.alpha_loading.observer_detected").getString();
				int observerY = graphics.guiHeight() / 2;
				graphics.drawCenteredString(font, observer, centerX + 1, observerY + 1,
						0xC8140505);
				graphics.drawCenteredString(font, observer, centerX, observerY, 0xFFF0E8DC);
			}
			thefourthfrequency$viewportFlooded = true;
			AlphaLoadSessionController.recordViewportFlooded(true);
			if (!thefourthfrequency$testScreenshotRequested
					&& thefourthfrequency$isClientGameTest()
					&& thefourthfrequency$screenTicks >= AlphaLoadTimeline.FREEZE_START_TICK + 5) {
				thefourthfrequency$testScreenshotRequested = true;
				AlphaLoadSessionController.requestRegressionScreenshot("alpha-loading-corruption.png");
			}
			return;
		}

		// Nothing here moves. Text that jitters from frame to frame reads as an effect applied to
		// the screen; text that holds perfectly still while the medium around it comes apart
		// reads as a screen that is genuinely failing. The damage is carried by the chroma bleed,
		// the scanlines and the tracking band, all of which sit on top of a stable layout.
		int startX = centerX - (font.width(prefix) + font.width(suffix)) / 2;
		int suffixX = startX + font.width(prefix);
		if (Math.floorMod(motionTick, 13) == 2) {
			graphics.drawString(font, failedLine, startX + 1, labelY,
					failed ? 0x4C651E22 : 0x383C3531, false);
		}
		AlphaCorruptionRenderer.drawChromaString(graphics, font, prefix, startX, labelY,
				failed ? 0xFFD5CEC3 : 0xFFE8E3DA, thefourthfrequency$screenTicks);
		int suffixColor = !failed
				? (Math.floorMod(motionTick, 11) == 4 ? 0xFF766C65 : 0xFFE8E3DA)
				: 0xFF9B302C;
		AlphaCorruptionRenderer.drawChromaString(graphics, font, suffix, suffixX, labelY,
				suffixColor, thefourthfrequency$screenTicks);

		if (AlphaLoadTimeline.observerMessageVisible(thefourthfrequency$screenTicks)) {
			String observer = Component.translatable(
					"screen.thefourthfrequency.alpha_loading.observer_detected").getString();
			graphics.drawCenteredString(font, observer, centerX + 1, labelY + 29,
					0x6A321416);
			AlphaCorruptionRenderer.drawChromaCenteredString(graphics, font, observer, centerX,
					labelY + 28, 0xC9D8D0C4, thefourthfrequency$screenTicks);
		}

		int copies = AlphaLoadTimeline.copiedFailureLines(thefourthfrequency$screenTicks);
		int y = labelY + 8;
		for (int copy = 0; copy < copies; copy++) {
			if (y >= graphics.guiHeight() - 8) break;
			// Placement is seeded by the copy index alone, so each line keeps the offset, the
			// spacing and the truncation it was born with instead of rearranging itself every few
			// frames. Only the colour breathes. A perfectly even stack of identical lines reads
			// as a list something is printing on purpose; uneven spacing and the occasional line
			// that gives out partway reads as a system losing the ability to finish a sentence.
			int placement = thefourthfrequency$chaos(copy * 31);
			int shimmer = thefourthfrequency$chaos(copy * 31 + motionTick / 4);
			int jitter = Math.floorMod(placement, 5) - 2;
			int alpha = Math.max(34, 170 - copy * 10);
			int red = 112 + Math.floorMod(shimmer >>> 8, 37);
			int green = 22 + Math.floorMod(shimmer >>> 14, 16);
			int blue = 24 + Math.floorMod(shimmer >>> 19, 13);
			int color = alpha << 24 | red << 16 | green << 8 | blue;
			String line = failedLine;
			if (Math.floorMod(placement >>> 7, 6) == 0 && failedLine.length() > 2) {
				line = failedLine.substring(0,
						2 + Math.floorMod(placement >>> 11, failedLine.length() - 2));
			}
			graphics.drawCenteredString(font, line, centerX + jitter, y, color);
			y += 5 + Math.floorMod(placement >>> 3, 4);
		}
		thefourthfrequency$renderSignalDropouts(graphics, motionTick, failed);
		AlphaCorruptionRenderer.drawMediumLayers(graphics, thefourthfrequency$screenTicks);
	}

	@Unique
	private static String thefourthfrequency$failedLine() {
		return Component.translatable("screen.thefourthfrequency.alpha_loading.prefix").getString()
				+ Component.translatable("screen.thefourthfrequency.alpha_loading.failed").getString();
	}

	/**
	 * The wall, whole, on the frame it starts.
	 *
	 * <p>It used to open outward from the middle of the screen over six ticks. That reads as a
	 * transition - as something the software decided to play - and a transition is exactly what a
	 * failing signal does not do. Arriving complete on one frame is a cut, and a cut is what makes
	 * the player unsure whether they saw it start.
	 */
	@Unique
	private static void thefourthfrequency$renderFullScreenFailureWall(GuiGraphics graphics,
			Font font, String failedLine) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		graphics.fill(0, 0, width, height, 0xD00B0000);

		float scale = 2.85F;
		int logicalWidth = (int) Math.ceil(width / scale);
		int logicalHeight = (int) Math.ceil(height / scale);
		String word = failedLine + " ";
		int wordWidth = Math.max(1, font.width(word));
		int repetitions = Math.max(1, logicalWidth / wordWidth + 6);
		String wallLine = word.repeat(repetitions);

		// One line in the wall does not agree with the wall, and it is woven into the wall rather
		// than laid on top of it: same glyph size, same baseline, standing in the flow of repeated
		// text where one repetition of the word should have been. Nothing frames it. Twenty-eight
		// ticks of the same word is parsed by the eye in ten and then costs attention without
		// paying any back; a single line that contradicts every other line spends the rest of that
		// time well - and it has to be *found* to do that, which a floating label can never be.
		// What it says reframes the failure entirely: nothing failed to load. Something connected.
		String intrusion = Component.translatable(
				"screen.thefourthfrequency.alpha_loading.wall_intrusion").getString();
		int intrusionRow = Math.round(logicalHeight * INTRUSION_ROW_FRACTION / font.lineHeight);
		int intrusionSlot = Math.max(1, Math.round(repetitions * INTRUSION_SLOT_FRACTION));
		String intrusionHead = word.repeat(intrusionSlot);
		int intrusionOffsetX = font.width(intrusionHead);
		String intrusionLine = intrusionHead + intrusion + " "
				+ word.repeat(Math.max(1, repetitions - intrusionSlot));

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		int row = -2;
		for (int y = -font.lineHeight * 2; y < logicalHeight + font.lineHeight * 2;
				y += font.lineHeight, row++) {
			boolean carriesIntrusion = row == intrusionRow;
			String line = carriesIntrusion ? intrusionLine : wallLine;
			int stagger = (row & 1) == 0 ? 0 : -(wordWidth / 2);
			int x = -wordWidth * 2 + stagger;
			int red = 206 + Math.floorMod(row * 17, 42);
			int green = 8 + Math.floorMod(row * 11, 17);
			int blue = 9 + Math.floorMod(row * 7, 13);
			graphics.drawString(font, line, x + 1, y + 1,
					0xB8000000 | red << 16, false);
			graphics.drawString(font, line, x, y,
					0xFF000000 | red << 16 | green << 8 | blue, false);
			if (carriesIntrusion) {
				// Overdrawn in place. Minecraft's font is not antialiased, so repainting the same
				// glyphs at the same position in a different colour leaves no seam behind.
				graphics.drawString(font, intrusion, x + intrusionOffsetX, y,
						INTRUSION_COLOR, false);
			}
		}
		graphics.pose().popMatrix();
	}

	@Unique
	private static void thefourthfrequency$renderSignalDropouts(GuiGraphics graphics,
			int motionTick, boolean failed) {
		int bandCount = failed
				? Math.min(4, 1 + Math.max(0, motionTick - AlphaLoadTimeline.FAILURE_TICK) / 28)
				: 1;
		for (int band = 0; band < bandCount; band++) {
			int seed = thefourthfrequency$chaos((motionTick / 3) * 0x1F123BB5
					+ band * 0x6D2B79F5);
			if (!failed && Math.floorMod(seed, 4) != 0) continue;
			int bandY = Math.floorMod(seed >>> 3, Math.max(1, graphics.guiHeight()));
			int bandHeight = 1 + Math.floorMod(seed >>> 13, failed ? 4 : 2);
			int alpha = failed ? 72 + Math.floorMod(seed >>> 20, 61) : 48;
			graphics.fill(0, bandY, graphics.guiWidth(),
					Math.min(graphics.guiHeight(), bandY + bandHeight), alpha << 24);
		}
	}

	/** Same seed source the medium layers use, so one tick shakes every layer the same way. */
	@Unique
	private static int thefourthfrequency$chaos(int value) {
		return AlphaLoadTimeline.noise(value);
	}

	@Unique
	private boolean thefourthfrequency$shouldCorrupt() {
		return thefourthfrequency$corruptionClaimed
				&& AlphaLoadSessionController.shouldCorruptLoadingScreen();
	}

	@Unique
	private int thefourthfrequency$labelY(GuiGraphics graphics) {
		ChunkLoadStatusView status = loadTracker.statusView();
		return status == null ? graphics.guiHeight() / 2 - 50
				: graphics.guiHeight() / 2 - status.radius() * 2 - 27;
	}

	@Unique
	private static boolean thefourthfrequency$isClientGameTest() {
		return FabricLoader.getInstance().isModLoaded("thefourthfrequency-test");
	}

	@Inject(method = "onClose", at = @At("TAIL"))
	private void thefourthfrequency$finishInitialFailure(CallbackInfo callback) {
		// Unconditional: a bed that outlives this screen would follow the player into the world.
		// Released with a tail rather than cut, so the world comes up underneath the noise floor
		// instead of replacing it on a frame the player can point at.
		AlphaCorruptionAudio.fadeOutAll();
		if (thefourthfrequency$corruptionClaimed) {
			AlphaLoadSessionController.loadingScreenClosed(thefourthfrequency$screenTicks,
					thefourthfrequency$viewportFlooded);
		}
	}
}
