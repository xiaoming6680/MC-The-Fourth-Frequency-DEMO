package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.content.TerminalData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class BoundTerminalContainerMixin {
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$preventBoundTerminalStorage(int slotId, int button, ClickType clickType,
			Player player, CallbackInfo callback) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		Slot clicked = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
		boolean nonPlayerTarget = clicked != null && !(clicked.container instanceof Inventory);
		// The player's own inventory screen has nowhere external to shift-click into: its
		// quickMoveStack only ever targets slots 9-45, which are backpack, armour and offhand, never
		// the crafting grid. Blocking the transfer there stopped a bound terminal from being moved
		// between the backpack and the hotbar at all, which is not what "no container" means.
		boolean externalTransferMenu = menu != player.inventoryMenu;
		// Slot id -999 means two different things and only one of them is a refusal.
		//
		// It is the id for "clicked outside the window", which is how a carried stack gets thrown on
		// the ground. It is also the id vanilla's drag protocol sends for its own START and END
		// headers. Reading the id alone therefore refused every drag gesture that had a bound
		// terminal on the cursor - on its first click, before any target slot was known, including a
		// drag that never left the player's own backpack. Click-to-place and shift-click each had a
		// test and each worked, so what the player was left with was a terminal that could be moved
		// by some gestures and not by the most obvious one.
		//
		// Where a drag actually puts things is decided by the CONTINUE clicks between those two
		// headers, and those carry real slot ids - so they are already caught, one slot at a time, by
		// the first rule below. Nothing is given up by letting the headers through.
		boolean thrownOnTheGround = slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
				&& clickType != ClickType.QUICK_CRAFT;
		boolean blocked = (nonPlayerTarget && TerminalData.isBound(menu.getCarried()))
				|| (clickType == ClickType.QUICK_MOVE && externalTransferMenu && clicked != null
						&& clicked.container instanceof Inventory && TerminalData.isBound(clicked.getItem()))
				|| (clickType == ClickType.THROW && clicked != null && TerminalData.isBound(clicked.getItem()))
				|| (thrownOnTheGround && TerminalData.isBound(menu.getCarried()))
				// Hotbar keys are 0-8 and the offhand key is 40; both swap the hovered slot with a slot
				// the player owns, so both can carry a bound terminal into a container.
				|| (clickType == ClickType.SWAP && nonPlayerTarget && thefourthfrequency$isOwnSwapSlot(button)
						&& TerminalData.isBound(player.getInventory().getItem(button)));
		if (!blocked) {
			return;
		}
		com.xm.thefourthfrequency.terminal.TerminalNoticeService.denied(serverPlayer,
				"message.thefourthfrequency.terminal.bound_no_container");
		callback.cancel();
	}

	/** The inventory slots a swap key can reach: the nine hotbar keys, and F for the offhand. */
	private static boolean thefourthfrequency$isOwnSwapSlot(int button) {
		return (button >= 0 && button < 9) || button == Inventory.SLOT_OFFHAND;
	}
}
