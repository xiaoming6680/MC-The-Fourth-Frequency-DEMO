package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.mixin.WeighedSoundEventsPoolAccessor;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.util.RandomSource;
import java.util.HashMap;
import java.util.Map;

/** Avoid an immediately repeated foley take; music retains its independent playlist policy. */
public final class SoundVariation {
	private static final Map<String,String> LAST = new HashMap<>();
	private SoundVariation() { }
	public static Sound select(String event, Sound sound, WeighedSoundEvents events, RandomSource random) {
		var pool=((WeighedSoundEventsPoolAccessor)events).thefourthfrequency$pool();
		if (pool.size()<2) return sound;
		String previous=LAST.get(event);
		for(int i=0;i<8 && sound.getLocation().toString().equals(previous);i++) sound=events.getSound(random);
		if(sound.getLocation().toString().equals(previous)) {
			int start=random.nextInt(pool.size());
			for(int i=0;i<pool.size();i++) {
				Sound candidate=pool.get((start+i)%pool.size()).getSound(random);
				if(!candidate.getLocation().toString().equals(previous)) { sound=candidate; break; }
			}
		}
		if(LAST.size()<256 || LAST.containsKey(event)) LAST.put(event,sound.getLocation().toString());
		return sound;
	}
	public static void clearSession() { LAST.clear(); }
}
