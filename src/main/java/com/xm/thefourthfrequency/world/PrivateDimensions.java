package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.pursuit.PursuitDimensions;
import com.xm.thefourthfrequency.unrendered.UnrenderedDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * "The player is not in a world they live in" - the one question every ambient system has to ask
 * before it acts.
 *
 * <p>This mod now takes players out of shared reality in two unrelated ways: a pursuit copies them
 * into a private mirror, and the unrendered layer drops them somewhere that is not a copy of
 * anything. Everything that decorates or measures the world has to stand down for both, and for the
 * same reason each time - progress, guidance, sightings, activity and pattern learning are all
 * statements about the world the player is playing in, and none of them are true of somewhere they
 * were taken.
 *
 * <p><b>It exists because the alternative was fifteen copies of half the answer.</b> Every one of
 * those call sites was written against the mirror, correctly, before the layer existed; adding a
 * second private dimension made every one of them silently incomplete, and not one of them would
 * have failed a test or logged anything. Asking the question in one place is what makes a third
 * private dimension an edit here instead of a hunt.
 */
public final class PrivateDimensions {
	private PrivateDimensions() {
	}

	public static boolean isPrivate(Level level) {
		return level != null && (PursuitDimensions.isMirror(level) || UnrenderedDimensions.isUnrendered(level));
	}

	public static boolean isPrivate(ResourceKey<Level> dimension) {
		return dimension != null
				&& (PursuitDimensions.isMirror(dimension) || UnrenderedDimensions.isUnrendered(dimension));
	}
}
