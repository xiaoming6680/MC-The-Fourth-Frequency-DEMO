package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.entity.BacteriaEntity;
import com.xm.thefourthfrequency.entity.HimEntity;
import com.xm.thefourthfrequency.entity.ReworkEntity;
import com.xm.thefourthfrequency.entity.StabilityAnchorEntity;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import com.xm.thefourthfrequency.entity.WorldInterfaceEnergyOrbEntity;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.entity.WorldInterfacePartEntity;
import net.minecraft.world.entity.Entity;

/**
 * Which entities the developer HUD outlines, and when.
 *
 * <p>Only this mod's own entities. Hostile mobs were considered and left out: at night the answer
 * would be most of the screen, and the question this is actually asked to settle - did the figure
 * spawn, and where did it go - is one a wall of glowing zombies makes harder rather than easier.</p>
 *
 * <p>Named types rather than a registry namespace check so that adding an entity is a compile-time
 * decision about whether it is worth outlining, not something that happens silently.</p>
 *
 * <p>What this cannot do is hold a figure still. HIM and the dark watcher decide they have been seen
 * from the server's own view angle, and a client-side outline is not part of that judgement: an
 * outline visible through a wall shows where one was placed, not one that will still be there when
 * the wall is gone.</p>
 */
public final class DebugEntityHighlight {
	private DebugEntityHighlight() { }

	/**
	 * Gated on both the debug status and HUD visibility.
	 *
	 * <p>The status is the permission - it only arrives for a player the server has debug enabled for
	 * - and the visibility key is what turns the whole readout off for a screenshot. Outlines are
	 * part of the readout, so the one key takes them with it.</p>
	 */
	public static boolean shouldHighlight(Entity entity) {
		return entity != null && DebugHud.active() && DebugHudState.visible()
				&& isModEntity(entity);
	}

	public static boolean isModEntity(Entity entity) {
		return entity instanceof ReworkEntity
				|| entity instanceof WatcherEntity
				|| entity instanceof HimEntity
				|| entity instanceof BacteriaEntity
				|| entity instanceof StabilityAnchorEntity
				|| entity instanceof WorldInterfaceEntity
				|| entity instanceof WorldInterfacePartEntity
				|| entity instanceof WorldInterfaceEnergyOrbEntity;
	}
}
