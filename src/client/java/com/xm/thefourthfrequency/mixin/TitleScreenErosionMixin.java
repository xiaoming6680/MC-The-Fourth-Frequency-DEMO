package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.AlphaLoadSessionController;
import com.xm.thefourthfrequency.client_ui.FailureMenuLockState;
import com.xm.thefourthfrequency.client_ui.MenuErosionState;
import com.xm.thefourthfrequency.meta_windows.WindowsEndingMetaTransaction;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenErosionMixin {
	private static final int VANILLA_SPLASH_YELLOW = 0xFFFF00;
	private static final String REALMS_BUTTON_KEY = "menu.online";
	@Shadow private SplashRenderer splash;

	@Inject(method = "init", at = @At("TAIL"))
	private void thefourthfrequency$applyPersistentMenuIdentity(CallbackInfo callback) {
		// Every title-screen instance, including one recreated after leaving a world, keeps the same
		// session slogan until the server reports a new erosion stage.
		splash = new SplashRenderer(Component.translatable(MenuErosionState.sessionSplashKey())
				.withColor(VANILLA_SPLASH_YELLOW));
		// The only thing that still closes the whole title screen is an unfinished desktop
		// transaction. Everything else the ending locks is now sealed where it actually lives:
		// a local save by LevelSummaryEndingQuarantineMixin and WorldOpenFlowsEndingQuarantineMixin,
		// a remote server by ConnectScreenEndingQuarantineMixin. The lock used to be read here as one
		// global boolean, which took away every unrelated single-player world and every other server
		// the player had - a cost that belongs to the run they finished and to nothing else.
		//
		// A pending Windows transaction is different in kind: the desktop is still altered, the mod
		// still owes the player a restore, and entering any world would leave that owed. So this one
		// case keeps the three entries shut and says outright where the way out is.
		boolean desktopUnrestored = WindowsEndingMetaTransaction.hasPendingTransaction();
		// A finished run holds every way into a world, not only the save it happened on.
		//
		// The per-save and per-server seals below are still there and still do their job, but on
		// their own they made the end of the mod look like nothing had happened: the title screen
		// came back exactly as it always is, with one greyed row several clicks inside a list nobody
		// opens after an ending. The run is over, the thing to do next is press F8, and the menu has
		// to say so where the player is actually looking.
		//
		// Singleplayer went first; Multiplayer followed at the user's request on 2026-08-29. The
		// argument for leaving Multiplayer alone was that a run finished on a friend's server has no
		// claim on the other servers this client plays on - true, and it is the reason the
		// *quarantine* is still scoped by address. What the title screen is doing is different: it
		// is the ending refusing to let the game continue at all until the player has closed it out,
		// and that refusal is not about which world the run happened in.
		//
		// This does not seize the way out: F8 is polled directly by MetaController, works with any
		// screen open, and the tooltip on the dead buttons names it. Recovery unlocks this.
		boolean endingLocked = FailureMenuLockState.locked();
		for (var element : Screens.getButtons((TitleScreen) (Object) this)) {
			String key = thefourthfrequency$translationKey(element.getMessage());
			if (desktopUnrestored && thefourthfrequency$isGameEntry(key)) {
				element.active = false;
				element.setTooltip(Tooltip.create(Component.translatable(
						"screen.thefourthfrequency.ending_menu_lock.recovery_pending")));
				continue;
			}
			if (endingLocked && thefourthfrequency$isGameEntry(key)) {
				element.active = false;
				element.setTooltip(Tooltip.create(Component.translatable(
						FailureMenuLockState.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS
								? "screen.thefourthfrequency.ending_menu_lock.success"
								: "screen.thefourthfrequency.ending_menu_lock.failure")));
				continue;
			}
			// Keyed rather than label-matched, so Realms stays disabled in every language.
			if (REALMS_BUTTON_KEY.equals(key)) element.active = false;
		}
	}

	private static String thefourthfrequency$translationKey(Component message) {
		return message.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
	}

	private static boolean thefourthfrequency$isGameEntry(String translationKey) {
		return switch (translationKey) {
			case "menu.singleplayer", "menu.multiplayer", REALMS_BUTTON_KEY -> true;
			default -> false;
		};
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
			index = 1)
	private String thefourthfrequency$alphaMenuVersion(String vanillaText) {
		return AlphaLoadSessionController.menuVersionText(vanillaText);
	}
}
