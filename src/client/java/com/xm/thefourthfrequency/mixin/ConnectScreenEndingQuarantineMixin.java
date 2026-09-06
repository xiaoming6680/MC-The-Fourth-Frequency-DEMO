package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.FailureMenuLockState;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The remote half of the ending seal, and the exact counterpart of the local save one.
 *
 * <p>A run that ended on a server used to take the whole client's Multiplayer button with it, which
 * sealed every other server the player had for the sake of one. This seals the one - and it does it
 * at {@code startConnecting} rather than on the server list button, for the same reason
 * {@code WorldOpenFlowsEndingQuarantineMixin} sits on the open flow rather than on the world list:
 * quick play, a direct connect and a transfer all arrive here and none of them pass through the
 * list.</p>
 *
 * <p>Keyed on {@link ServerData#ip}, which is what the entry the player clicks is stored under. A
 * player who deliberately retypes the address as something else - a raw IP for a hostname, a
 * different port spelling - gets through, and that is accepted: this is a seal on the run, not a
 * ban, and the honest way out of it is the same F8 recovery the title screen points at.</p>
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenEndingQuarantineMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void thefourthfrequency$blockSealedServer(Screen parent, Minecraft minecraft,
			ServerAddress address, ServerData serverData, boolean isQuickPlay, TransferState transferState,
			CallbackInfo callback) {
		if (serverData == null || !FailureMenuLockState.seals(serverData.ip)) return;
		// The same success/failure wording split the local save uses, so one run cannot be described
		// as sealed in one place and damaged in the other depending on where it was played.
		String prefix = FailureMenuLockState.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS
				? "multiplayer.thefourthfrequency.sealed"
				: "multiplayer.thefourthfrequency.corrupted";
		minecraft.setScreen(new AlertScreen(() -> minecraft.setScreen(parent),
				Component.translatable(prefix), Component.translatable(prefix + ".details")));
		callback.cancel();
	}
}
