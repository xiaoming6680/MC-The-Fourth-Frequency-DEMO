package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.config.ConfigManager;

/**
 * Client-side plumbing behind the mod-volume slider.
 *
 * <p>Two operations, deliberately separate. {@link #apply} moves the live value only, because every
 * audio path in the mod reads {@code RuntimeServices.config()} at the moment it plays - so the
 * preview button and anything already sounding follow the handle as it is dragged. {@link #commit}
 * is what touches the disk, and runs when the drag ends rather than on every pixel of it: a slider
 * that rewrote the config file on each frame would spend a whole page of file writes on one gesture.
 *
 * <p>What this reaches is exactly what {@code meta.peakVolume} reaches - every cue this mod plays
 * and its authored score - and nothing else. Vanilla's own sliders still apply on top, and the two
 * are never written by each other.
 *
 * <p>One honest limit: on a dedicated server the cues the server itself emits are scaled by
 * <em>that</em> process's config, which no client can write. This is a client-local trim, and in
 * that one case it reaches everything except the server's own authored cues.
 */
public final class ModVolumeControl {
	private ModVolumeControl() {
	}

	public static double current() {
		return ModVolumePolicy.snap(RuntimeServices.config().meta().peakVolume());
	}

	/** Live, in memory only. Safe to call on every frame of a drag. */
	public static void apply(double value) {
		double snapped = ModVolumePolicy.snap(value);
		if (snapped == current()) return;
		RuntimeServices.updateConfig(config -> config.withMeta(config.meta().withPeakVolume(snapped)));
	}

	/** Writes the live value into the config file, preserving every other setting in it. */
	public static boolean commit() {
		double value = current();
		return ConfigManager.updateMeta(meta -> meta.withPeakVolume(value));
	}
}
