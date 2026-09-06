package com.xm.thefourthfrequency.world;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;

/**
 * Keeps endermen out of the End.
 *
 * <p>The End is this mod's last act, and by the time a player reaches it the dimension has stopped
 * being a place with wildlife in it: it rains, it is scored, and the only thing that is supposed to
 * be moving out there is the encounter. Vanilla's endermen work against all three at once. They are
 * the one mob that teleports, so they arrive without being watched arriving; they are the one mob
 * that reacts to being looked at, so the whole island turns into a thing the player must avoid
 * making eye contact with; and during the finale they are thirty-block-tall silhouettes' worth of
 * unrelated purple particles in an arena whose entire visual language is purple particles.
 *
 * <p>Removed rather than prevented from spawning. A spawn rule would have to be a mixin on
 * {@code NaturalSpawner} or a data pack that rewrites the biome, and both of those reach further
 * than this needs to: entities also arrive by portal, by command, by chunk load from a save made
 * before this mod was installed, and by the dragon fight's own gateway. Discarding on load covers
 * every one of those with one rule, and it is exactly reversible - remove the mod and the End
 * repopulates itself on its own.
 *
 * <p><b>Scoped to the real End only.</b> The mirror dimensions copy the End's terrain and the
 * unrendered layer is its own place; neither is {@code minecraft:the_end} and neither is touched.
 * Nothing in the Overworld or the Nether is touched either, and no other mob anywhere is.
 *
 * <p><b>What this costs the player:</b> ender pearls stop dropping in the End. That is survivable -
 * pearls come from the Overworld and from trading, the mod's own progression never asks for one past
 * the stronghold, and by the time the End is reachable the twelve eyes are already spent.
 */
public final class EndPopulationService {
	private static boolean initialized;

	private EndPopulationService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (level.dimension() == Level.END && entity instanceof EnderMan) entity.discard();
		});
		// A pearl thrown from the Overworld, a gateway crossing, or an admin summon can all put one
		// in the End without the load event firing there, because the entity was loaded somewhere
		// else. The world-change event is the other half of the same rule.
		ServerEntityWorldChangeEvents.AFTER_ENTITY_CHANGE_WORLD.register(
				(original, copy, origin, destination) -> {
					if (destination.dimension() == Level.END && copy instanceof EnderMan) copy.discard();
				});
	}
}
