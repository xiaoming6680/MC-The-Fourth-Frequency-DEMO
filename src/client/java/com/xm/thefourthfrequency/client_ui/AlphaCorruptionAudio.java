package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/** A device cue catches in a repeated buffer; blackout cuts it. No background noise beds. */
public final class AlphaCorruptionAudio {
    private static SimpleSoundInstance warning;
    private static SimpleSoundInstance failure;
    private static boolean warningPlayed;
    private static boolean failurePlayed;

    private AlphaCorruptionAudio() {}

    public static void tick(Minecraft client, int screenTicks) {
        if (screenTicks >= AlphaLoadTimeline.BLACKOUT_START_TICK) {
            stopVoices(client);
            return;
        }
        float master = (float)Math.clamp(RuntimeServices.config().meta().peakVolume(), 0, 1);
        if (!warningPlayed && screenTicks >= AlphaLoadTimeline.GLITCH_START_TICK) {
            warningPlayed = true;
            if (screenTicks < AlphaLoadTimeline.FLOOD_START_TICK && master > 0) {
                warning = SimpleSoundInstance.forUI(ModSounds.ALPHA_CORRUPTION_WARNING, 1, .40F * master);
                client.getSoundManager().play(warning);
            }
        }
        if (!failurePlayed && screenTicks >= AlphaLoadTimeline.FLOOD_START_TICK) {
            failurePlayed = true;
            if (warning != null) client.getSoundManager().stop(warning);
            warning = null;
            if (master > 0) {
                failure = SimpleSoundInstance.forUI(ModSounds.ALPHA_CORRUPTION_COLLAPSE, 1, .48F * master);
                client.getSoundManager().play(failure);
            }
        }
    }

    private static void stopVoices(Minecraft client) {
        if (warning != null) client.getSoundManager().stop(warning);
        if (failure != null) client.getSoundManager().stop(failure);
        warning = null;
        failure = null;
    }

    /** Screen exit shares the blackout's abrupt cut, including disconnects. */
    public static void fadeOutAll() { stopAll(); }

    public static void stopAll() {
        stopVoices(Minecraft.getInstance());
        warningPlayed = false;
        failurePlayed = false;
    }

    public static int playingForTesting(Minecraft client) {
        return (warning != null && client.getSoundManager().isActive(warning) ? 1 : 0)
                + (failure != null && client.getSoundManager().isActive(failure) ? 1 : 0);
    }
}
