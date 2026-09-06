package com.xm.thefourthfrequency.pursuit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Finds a safe return point near the recorded source without loading an unbounded area. */
public final class PursuitReturnLocator {
	private static final int HORIZONTAL_RADIUS = 8;
	private static final int VERTICAL_RADIUS = 4;

	private PursuitReturnLocator() {
	}

	public static BlockPos find(ServerLevel level, BlockPos preferred) {
		return find(level, preferred, preferred);
	}

	/**
	 * Finds somewhere to put the player back, trying {@code preferred} first and {@code fallback}
	 * before giving up on the world spawn.
	 *
	 * <p>The two-candidate form exists for chases that end somewhere the player dug to. The mirror
	 * lets them cut through terrain, and those cuts do not exist in the source world, so the spot
	 * they escaped from can be solid rock back home. Falling straight to world spawn in that case
	 * would punish the escape harder than being caught; the entry point is a far better second
	 * choice, and it is what this used to do unconditionally.</p>
	 */
	public static BlockPos find(ServerLevel level, BlockPos preferred, BlockPos fallback) {
		BlockPos near = nearby(level, preferred);
		// An air pocket is not the same as a place to arrive. The escape point is a mirror coordinate
		// and the source world has been played in for however long the chase lasted, so the gap that
		// existed there can now be the inside of somebody's wall, a one-block hole under a floor, or a
		// sealed room a teammate built. Materialising inside one is not dangerous, but it is
		// unexplainable - and the entry point is a place the player demonstrably stood.
		if (near != null && !enclosed(level, near)) return near;
		if (!fallback.equals(preferred)) {
			BlockPos alternate = nearby(level, fallback);
			if (alternate != null) return alternate;
		}
		if (near != null) return near;
		BlockPos spawn = level.getRespawnData().pos();
		return safe(level, spawn) ? spawn : level.getHeightmapPos(
				net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
	}

	private static BlockPos nearby(ServerLevel level, BlockPos preferred) {
		if (safe(level, preferred)) return preferred;
		for (int radius = 1; radius <= HORIZONTAL_RADIUS; radius++) {
			for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
						BlockPos candidate = preferred.offset(dx, dy, dz);
						if (safe(level, candidate)) return candidate;
					}
				}
			}
		}
		return null;
	}

	/** How many open cells the escape test may visit before it accepts the space as somewhere to be. */
	private static final int OPEN_SPACE_BUDGET = 96;

	/**
	 * Whether this position sits in a pocket too small to be anywhere.
	 *
	 * <p>A bounded flood fill rather than a line to the sky: a cellar, a mineshaft and the inside of a
	 * mountain are all legitimate places to come back to, and all of them fail a sky test. What is
	 * being rejected is the sealed one-block void - the difference between "underground" and "inside
	 * a wall". Ninety-six cells is comfortably larger than any accidental gap and far smaller than
	 * any room, so the search settles either way almost immediately, and the cap means an open
	 * cavern costs the same bounded work as a sealed one.
	 */
	private static boolean enclosed(ServerLevel level, BlockPos feet) {
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		seen.add(feet);
		queue.add(feet);
		while (!queue.isEmpty()) {
			if (seen.size() > OPEN_SPACE_BUDGET) return false;
			BlockPos current = queue.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = current.relative(direction);
				if (!seen.add(next)) continue;
				if (next.getY() <= level.getMinY() || next.getY() >= level.getMaxY()) continue;
				if (!level.hasChunkAt(next)) continue;
				if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) queue.add(next);
			}
		}
		return true;
	}

	private static boolean safe(ServerLevel level, BlockPos feet) {
		if (feet.getY() <= level.getMinY() || feet.getY() + 1 >= level.getMaxY()) return false;
		if (!level.hasChunkAt(feet)) level.getChunkAt(feet);
		return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
	}
}
