package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.ConfiscationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses to let go of the two stacks that are not the player's to throw away.
 *
 * <p>The bound terminal and the weapon-custody placeholder share this injection because they share
 * a reason: both are held on the player's behalf by something with a ledger behind it, and both are
 * supposed to come back.
 *
 * <p><b>The target is {@link LivingEntity}, not {@code Player}, and that is the whole point.</b>
 * {@code Player} declares only the two-argument convenience overload; the implementation every drop
 * actually reaches is {@code LivingEntity#drop(ItemStack, boolean, boolean)}. This injection used to
 * sit on the two-argument one, which covers the drop key (the server play handler calls it) and
 * {@code Inventory#placeItemBackInInventory} - but <em>not</em> {@code Inventory#dropAll} or {@code
 * EntityEquipment#dropAll}, both of which call the three-argument method directly. Those two are
 * death. So with {@code keepInventory} off, dying scattered exactly the two stacks this class exists
 * to hold on to: a bound terminal anyone could pick up, and a barrier placeholder that would then
 * never resolve, because custody hands items back by scanning the <em>owner's</em> inventory.
 *
 * <p>Targeting the implementation covers both shapes at once, since the two-argument overload
 * delegates here. The cost is that this now runs for every living entity that drops a stack - armour
 * coming off a dying mob, mostly - which is two component reads on items that carry neither marker.
 *
 * <p>The refusal notice is withheld from a dead player on purpose. Cancelling still happens, because
 * the ledger is what makes the stack recoverable either way; but "you cannot drop this" arriving on
 * the death screen answers a question nobody asked, and the player did not choose the drop. What
 * they see instead is the terminal already back in their inventory when they respawn.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDropMixin {
	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$preventUndroppableDrop(ItemStack stack, boolean dropAround,
			boolean traceItem, CallbackInfoReturnable<ItemEntity> callback) {
		String refusal;
		if (TerminalData.isBound(stack)) {
			refusal = "message.thefourthfrequency.terminal.bound_no_drop";
		} else if (ConfiscationService.isPlaceholder(stack)) {
			refusal = "message.thefourthfrequency.world_interface.confiscated_locked";
		} else {
			return;
		}
		if ((Object) this instanceof ServerPlayer serverPlayer && serverPlayer.isAlive()) {
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.denied(serverPlayer, refusal);
		}
		callback.setReturnValue(null);
	}
}
