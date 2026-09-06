package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.client_ui.AnomalyPresentationController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsAnomalyMixin {
	private static final int EYE_TEXTURE_SIZE = 128;
	private static final Identifier EYE = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID,
			"textures/gui/anomaly/eye_item.png");

	@Inject(method = "renderItem(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$misreadInventoryIcon(ItemStack stack, int x, int y, CallbackInfo callback) {
		if (!thefourthfrequency$drawMisread(stack, x, y)) return;
		callback.cancel();
	}

	@Inject(method = "renderItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$misreadSeededInventoryIcon(ItemStack stack, int x, int y, int seed,
			CallbackInfo callback) {
		if (!thefourthfrequency$drawMisread(stack, x, y)) return;
		callback.cancel();
	}

	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V",
			at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$misreadLivingInventoryIcon(LivingEntity entity, ItemStack stack,
			int x, int y, int seed, CallbackInfo callback) {
		if (!thefourthfrequency$drawMisread(stack, x, y)) return;
		callback.cancel();
	}

	private boolean thefourthfrequency$drawMisread(ItemStack stack, int x, int y) {
		// Ahead of the controller's own guard, and ahead of vanilla's: this runs at the head of
		// renderItem, which AbstractContainerScreen.renderSlot calls for empty slots as well, so
		// every vacant square in an open inventory arrives here several times a frame.
		if (stack == null || stack.isEmpty()) return false;
		if (!AnomalyPresentationController.isMisread(stack)) return false;
		((GuiGraphics) (Object) this).blit(RenderPipelines.GUI_TEXTURED, EYE, x, y,
				0.0F, 0.0F, 16, 16, EYE_TEXTURE_SIZE, EYE_TEXTURE_SIZE,
				EYE_TEXTURE_SIZE, EYE_TEXTURE_SIZE);
		return true;
	}
}
