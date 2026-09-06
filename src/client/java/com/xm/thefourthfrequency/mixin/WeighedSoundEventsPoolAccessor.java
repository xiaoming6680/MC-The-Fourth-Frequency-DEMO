package com.xm.thefourthfrequency.mixin;

import java.util.List;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.client.sounds.WeighedSoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches how many entries a sound event's pool actually holds.
 *
 * <p>{@code WeighedSoundEvents} exposes {@code getSound(RandomSource)} and {@code getWeight()} and
 * nothing else about its contents, so from the outside the pool is a black box you can draw from
 * but never count. That is enough for every ordinary sound and not enough for
 * {@link com.xm.thefourthfrequency.audio.MusicRotationPolicy}, which has to answer "has this pass
 * used everything?" — a question about the size of the pool, not about any one draw.</p>
 *
 * <p>Counting matters because the alternative is guessing. The rotation used to infer that a pass
 * was over by drawing a fixed number of times and giving up: with seven tracks that missed the last
 * unplayed one about seven times in a thousand, and at ten tracks it missed roughly one pass in
 * thirty — each miss being a track the player never heard that round, which is the exact complaint
 * the rotation exists to prevent. With the size in hand the question is answered outright.</p>
 *
 * <p>{@code getWeight()} is deliberately not used for this. It is the sum of the entries' weights,
 * which equals the entry count only while every entry is weighted 1; that happens to be true of the
 * score today and is not something the pool guarantees.</p>
 */
@Mixin(WeighedSoundEvents.class)
public interface WeighedSoundEventsPoolAccessor {
	/**
	 * The pool's entries. An entry is usually a {@code Sound}, but may itself be a nested event, so
	 * this is the number of things that can be drawn rather than the number of files behind them.
	 */
	@Accessor("list")
	List<Weighted<Sound>> thefourthfrequency$pool();
}
