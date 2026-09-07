package com.xm.thefourthfrequency.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.xm.thefourthfrequency.audio.ModOutputMix;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineOutputMixMixin {
	/** Initial play bypasses the overload used by tickable sounds and category refreshes. */
	@ModifyExpressionValue(method = "play", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
	private float thefourthfrequency$initialGain(float original, SoundInstance sound) {
		return ModOutputMix.apply(sound.getIdentifier().getNamespace(), sound.getIdentifier().getPath(), original);
	}

	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"), cancellable = true)
	private void thefourthfrequency$updatedGain(SoundInstance sound, CallbackInfoReturnable<Float> callback) {
		callback.setReturnValue(ModOutputMix.apply(sound.getIdentifier().getNamespace(),
				sound.getIdentifier().getPath(), callback.getReturnValueF()));
	}
}
