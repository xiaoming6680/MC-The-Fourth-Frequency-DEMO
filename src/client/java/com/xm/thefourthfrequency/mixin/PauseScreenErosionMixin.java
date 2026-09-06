package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.AnalogFilter;
import com.xm.thefourthfrequency.client_ui.ExitDecayTimeline;
import com.xm.thefourthfrequency.client_ui.FailureMenuLockState;
import com.xm.thefourthfrequency.client_ui.MenuErosionState;
import com.xm.thefourthfrequency.client_ui.PursuitPresentationClient;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenErosionMixin {
	@Shadow private Button disconnectButton;
	@Unique private Component thefourthfrequency$originalDisconnectMessage;
	@Unique private boolean thefourthfrequency$originalDisconnectActive;

	@Inject(method = "init", at = @At("TAIL"))
	private void thefourthfrequency$erodeSingleplayerExit(CallbackInfo callback) {
		if (disconnectButton == null) return;
		thefourthfrequency$originalDisconnectMessage = disconnectButton.getMessage();
		thefourthfrequency$originalDisconnectActive = disconnectButton.active;
		thefourthfrequency$updateDisconnectButton();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void thefourthfrequency$lockPursuitExit(GuiGraphics graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo callback) {
		thefourthfrequency$updateDisconnectButton();
	}

	@Unique
	private void thefourthfrequency$updateDisconnectButton() {
		if (disconnectButton == null || thefourthfrequency$originalDisconnectMessage == null) return;
		disconnectButton.setMessage(thefourthfrequency$originalDisconnectMessage);
		disconnectButton.active = thefourthfrequency$originalDisconnectActive;
		// The only two states that actually hold the door shut, and both of them are a fight the
		// player is currently inside. Everything else may pressure the exit; nothing else may take it.
		if (PursuitPresentationClient.locksPauseExit()) {
			disconnectButton.setMessage(Component.translatable(
					"message.thefourthfrequency.pursuit.exit_locked"));
			disconnectButton.active = false;
			return;
		}
		if (WorldInterfaceClientState.snapshot().locksPauseExit()) {
			disconnectButton.setMessage(Component.translatable(
					"message.thefourthfrequency.world_interface.exit_locked"));
			disconnectButton.active = false;
			return;
		}
		if (!Minecraft.getInstance().hasSingleplayerServer()) return;
		// An ending releases the exit, whichever way it went. The erosion is pressure from a run that
		// is still going; the epilogue hands the world back and spends a minute of action bars saying
		// the story is over and the menu is where "over" lives. The noise below is not part of the
		// release: a world that was lost should still look like one.
		if (FailureMenuLockState.locked()) return;
		//
		// LATE used to set active = false here, and that was the bug: menu erosion is driven by story
		// progress, not by anything the player is in the middle of, so past that threshold the quit
		// button was simply dead - in the overworld, mid-build, at any hour, with nothing happening.
		// A player who wanted to stop playing had to kill the process.
		//
		// It also broke the mod's own hard rule that page tabs, back, close, the pause menu and safe
		// recovery may never be permanently taken by a performance. The erosion keeps everything it
		// had that was pressure rather than seizure: the label still says the window is closing, and
		// the grain, scanlines and mistracking bar still crawl over the control. The door opens.
		switch (MenuErosionState.stage()) {
			case MID, LATE -> disconnectButton.setMessage(Component.translatable(
					"message.thefourthfrequency.menu_erosion.escape_window"));
			default -> { }
		}
	}

	/**
	 * The exit control failing, drawn on the control itself.
	 *
	 * <p>This used to be nine drifting hexadecimal strings laid across the whole pause screen. That
	 * read as text pasted over the menu rather than as the menu being in trouble, and it said
	 * nothing about the one thing it was about - the way out. So the damage moves onto the door: the
	 * same grain, scanlines and mistracking bar the loading screen and the terminal's weather card
	 * use, clipped to the button's own rectangle.
	 *
	 * <p>Drawn after the button, so it sits on top of the label rather than under it, and bounded to
	 * the button's box, so nothing else on the screen is touched. Rates come from
	 * {@link ExitDecayTimeline} and are all far below the flash ceiling - see that class.
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void thefourthfrequency$corruptTheExit(GuiGraphics graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo callback) {
		if (disconnectButton == null || !Minecraft.getInstance().hasSingleplayerServer()
				|| MenuErosionState.stage() != MenuErosionState.Stage.LATE) return;
		int left = disconnectButton.getX();
		int top = disconnectButton.getY();
		int right = left + disconnectButton.getWidth();
		int bottom = top + disconnectButton.getHeight();
		if (right <= left || bottom <= top) return;

		long now = System.currentTimeMillis();
		int frame = (int) (now / 55L);
		AnalogFilter.grain(graphics, left, top, right, bottom,
				ExitDecayTimeline.grainStrength(now), frame);
		AnalogFilter.scanlines(graphics, left, top, right, bottom, 3,
				ExitDecayTimeline.scanlineStrength(), frame);

		float progress = ExitDecayTimeline.rollProgress(now);
		float strength = ExitDecayTimeline.rollStrength(progress);
		if (strength <= 0.0F) return;
		int barHeight = Math.max(2, (bottom - top) / 3);
		// Enters above the control and leaves below it, so the pass reads as something crossing the
		// button rather than as a band switching on inside it.
		int barTop = Math.round(top - barHeight + progress * (bottom - top + barHeight));
		AnalogFilter.rollBar(graphics, left, top, right, bottom, barTop, barHeight, strength);
	}
}
