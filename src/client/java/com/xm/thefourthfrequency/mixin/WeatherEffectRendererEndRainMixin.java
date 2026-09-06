package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.EndWeatherClient;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The second of the two switches that keep the End dry.
 *
 * <p>{@link EndWeatherClient} writes the rain level; this answers the other question the renderer
 * asks. For every column inside the weather radius, {@code getPrecipitationAt} resolves the biome
 * and asks it what falls there - and the End biome declares no precipitation, so the answer is
 * always {@code NONE} and the column is skipped. Turning that answer into {@code RAIN} is what
 * actually puts drops on the screen, and it does so through vanilla's own weather renderer, its own
 * surface particles and its own rain ambience rather than through a reimplementation of any of them.
 *
 * <p>Injected on {@code RETURN} rather than {@code HEAD} on purpose. The method's first act is a
 * loaded-chunk test that answers {@code NONE} for columns the client has not been sent, and
 * short-circuiting at the head would throw that away and draw rain over the void. The predicate in
 * {@link EndWeatherClient#rainsAt} repeats the same test rather than assuming it.
 *
 * <p>Only ever widens {@code NONE} to {@code RAIN}, and only in the End. A column that already
 * resolved to rain or snow is left exactly as vanilla answered it, so no other dimension - and no
 * private mirror dimension, which carries its own dimension key - can see this at all.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererEndRainMixin {
	@Inject(method = "getPrecipitationAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)"
			+ "Lnet/minecraft/world/level/biome/Biome$Precipitation;",
			at = @At("RETURN"), cancellable = true)
	private void thefourthfrequency$rainInTheEnd(Level level, BlockPos pos,
			CallbackInfoReturnable<Biome.Precipitation> callback) {
		if (callback.getReturnValue() != Biome.Precipitation.NONE) return;
		if (!EndWeatherClient.rainsAt(level, pos)) return;
		callback.setReturnValue(Biome.Precipitation.RAIN);
	}
}
