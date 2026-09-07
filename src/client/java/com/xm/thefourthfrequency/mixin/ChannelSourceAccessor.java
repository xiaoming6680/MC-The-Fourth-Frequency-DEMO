package com.xm.thefourthfrequency.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Used only on ChannelAccess's sound thread for synchronising static attack buffers. */
@Mixin(Channel.class)
public interface ChannelSourceAccessor {
	@Accessor("source")
	int thefourthfrequency$source();
}
