package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.LuminanceFaultClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two hooks the unsolved lighting in {@code local_rule_collapse} needs: one to make light
 * wrong, one to let it be right again.
 *
 * <p>See {@link LuminanceFaultClient} for the mechanic these serve.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererLuminanceFaultMixin {
	/**
	 * Reports zero packed brightness inside a faulted section.
	 *
	 * <p>This four-argument overload is the one to hold rather than the short one beside it: the
	 * two-argument {@code getLightColor} does nothing but supply the default brightness getter and
	 * call straight through to here, so covering this covers both, and covering both separately would
	 * darken nothing twice.
	 *
	 * <p>It is the single funnel for baked chunk lighting and for entity lighting alike, which is why
	 * mobs standing in the dark go dark with it instead of floating through it fully lit.
	 */
	@Inject(method = "getLightColor(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;"
			+ "Lnet/minecraft/world/level/BlockAndTintGetter;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
			at = @At("HEAD"), cancellable = true)
	private static void thefourthfrequency$darkenFaultedSection(LevelRenderer.BrightnessGetter brightness,
			BlockAndTintGetter level, BlockState state, BlockPos pos,
			CallbackInfoReturnable<Integer> callback) {
		if (LuminanceFaultClient.darkened(pos)) callback.setReturnValue(0);
	}

	/**
	 * Releases a section the moment anything asks for it to be recompiled.
	 *
	 * <p>Deliberately the innermost public step of the chain rather than {@code blockChanged}:
	 * {@code setSectionDirtyWithNeighbors} and {@code setSectionRangeDirty} both end here, so a
	 * neighbour update at a section boundary repairs the section it actually affected, and no future
	 * caller of the dirty machinery can route around the repair.
	 */
	@Inject(method = "setSectionDirty(III)V", at = @At("HEAD"))
	private void thefourthfrequency$releaseFaultedSection(int sectionX, int sectionY, int sectionZ,
			CallbackInfo callback) {
		LuminanceFaultClient.sectionDirtied(sectionX, sectionY, sectionZ);
	}
}
