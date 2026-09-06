package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.DebugEntityHighlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outlines this mod's entities while the developer HUD is up.
 *
 * <p>Vanilla already draws a glowing entity's outline through terrain, so borrowing the flag buys a
 * see-through marker for one boolean rather than a renderer of its own.</p>
 *
 * <p>Both classes are targeted because {@link LivingEntity} overrides the method: mixing only into
 * {@link Entity} would light up the anchors and the boss parts and silently miss every mob, which
 * is most of what there is to look for.</p>
 */
@Mixin({Entity.class, LivingEntity.class})
public abstract class EntityDebugHighlightMixin {
	@Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
	private void thefourthfrequency$outlineForDebugHud(CallbackInfoReturnable<Boolean> callback) {
		if (!callback.getReturnValueZ()
				&& DebugEntityHighlight.shouldHighlight((Entity) (Object) this)) {
			callback.setReturnValue(true);
		}
	}
}
