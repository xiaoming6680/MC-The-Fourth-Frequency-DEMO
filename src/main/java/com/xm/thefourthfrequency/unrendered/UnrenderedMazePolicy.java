package com.xm.thefourthfrequency.unrendered;

/**
 * Every block-placement decision the unrendered layer makes, as a pure function of seed and position.
 *
 * <p><b>Nothing here may read a neighbouring chunk.</b> Chunks are generated off-thread and in no
 * fixed order, so a maze that grew outward from a start cell - which is what every textbook maze
 * algorithm does - would produce a different answer depending on which chunk asked first, and the
 * seams would show at chunk borders. Instead every cell hashes its own coordinates and decides
 * alone. Two adjacent chunks agree about the wall on their shared border because they are asking
 * the same question about the same cell, not because either told the other anything.
 *
 * <p>That constraint is also what makes the layer free: no state is kept, so no state has to be
 * saved, and a region of the maze nobody has visited costs nothing.
 */
public final class UnrenderedMazePolicy {
	/**
	 * Chance in 256 that a given cell boundary carries a wall.
	 *
	 * <p>Deliberately well under half. Level zero is not a maze of corridors, it is a large open
	 * floor plan with enough walls to destroy any sense of direction; at densities above roughly a
	 * third it stops reading as a room and starts reading as a hedge maze, which is a different and
	 * much less unpleasant feeling.
	 */
	private static final int WALL_CHANCE_256 = 90;
	/** Chance in 256 that a cell's ceiling panel still works. The rest are the dark patches. */
	private static final int LIGHT_CHANCE_256 = 155;

	/**
	 * Every this-many cells, one full row and one full column lose their crossing walls.
	 *
	 * <p>Two jobs. It guarantees the floor plan is connected without any global reachability test -
	 * random walls alone can seal a pocket, and a player sealed into one has been handed an
	 * unwinnable room. And it supplies the sightline the place is known for: a corridor that runs
	 * past the render distance and gives no information at all about how far it goes.
	 */
	public static final int TRUNK_SPACING_CELLS = 8;

	/**
	 * Side, in cells, of the square guaranteed exactly one way out.
	 *
	 * <p>Not an independent per-cell probability. That would make the distance to the nearest way
	 * out an exponential draw, so some runs would put one twenty blocks from the entry point and
	 * others would put none inside the timeout at all. Partitioning the plane and placing one per
	 * square keeps the density fixed while the position inside each square stays unpredictable - the
	 * player cannot learn a spacing, but the layer cannot forget to have a way out either.
	 *
	 * <p>Thirty-two cells is one per hundred and sixty blocks, denser than the forty-eight it started
	 * at. The first version had a single obvious hole in the floor per two hundred and forty blocks
	 * and that was the wrong shape of problem: one exit that is easy to recognise is worse than
	 * several that are not, because the first turns the layer into a search with a known target and
	 * the second leaves it a place you are trying to get out of.
	 */
	private static final int EXIT_SQUARE_CELLS = 32;

	/**
	 * Side of the way out itself, in cells. Three cells is fifteen blocks square.
	 *
	 * <p>Large on purpose, and the size is doing the discoverability work that being obvious used to
	 * do. What marks it is a slight difference in colour, which at one cell would be a patch small
	 * enough to be dismissed as a lighting artefact; at fifteen blocks across it is a wall panel that
	 * is visibly the wrong shade, still deniable at a glance and unmistakable once looked at. That is
	 * the band this has to sit in - findable by paying attention, never findable by not paying
	 * attention.
	 */
	private static final int EXIT_REGION_CELLS = 3;

	private static final long WEST_SALT = 0x5F1E_2B77_A1C3_0D45L;
	private static final long NORTH_SALT = 0x27B4_9E01_6C8F_3AA9L;
	private static final long LIGHT_SALT = 0x71C0_45D2_9E3B_86F7L;
	private static final long EXIT_SALT = 0x1A93_C7E5_0B62_4D18L;
	private static final long SOFT_SALT = 0x3D07_B1F4_5E29_A6C3L;

	/**
	 * Chance in 65536 that a four-by-four patch of floor quietly fails to hold weight.
	 *
	 * <p>The second way out, and the one nobody looks for. The exit regions are found by paying
	 * attention; these are found by not being able to afford to - a player being chased picks
	 * whichever corridor is open and is not reading the floor.
	 *
	 * <p>Twenty-five in sixty-five thousand works out at one opening per forty-odd thousand floor
	 * blocks, about a two-hundred-block square, so they are noticeably rarer than the exit regions
	 * one per hundred and sixty. That ordering is the point: the reliable way out is the one you can
	 * learn to spot, and this is luck on top of it. A running player sweeps a corridor a few blocks
	 * wide, so a session crosses one of these about once.
	 *
	 * <p>It was eight times commoner than this and a single block rather than a patch, which was
	 * wrong twice over. Single blocks were both too easy to see - off-colour speckle across every
	 * floor in the layer - and too easy to miss underfoot, because at six blocks a second the gap
	 * between footfalls is wider than the hole.
	 */
	private static final int SOFT_FLOOR_CHANCE_65536 = 25;

	/**
	 * Side, in blocks, of one scattered opening.
	 *
	 * <p>Four, not one. A single block that does not hold is something a running player crosses
	 * without their foot ever landing on it - at six blocks a second the gap between footfalls is
	 * wider than the hole - so as a panic exit it mostly did not fire, and when it did it read as the
	 * floor glitching rather than as a way out. Four blocks square is something you fall into.
	 *
	 * <p>Anchored to a four-block lattice rather than centred on the drawn block, so a patch is
	 * always a clean square and two patches can never partly overlap into an L.
	 */
	private static final int SOFT_FLOOR_PATCH = 4;

	private UnrenderedMazePolicy() {
	}

	/** How many directions the entry search may try before repeating itself. */
	public static int trunkSpacingCells() {
		return TRUNK_SPACING_CELLS;
	}

	/**
	 * Nearest cell where a trunk row crosses a trunk column, which is the only cell this layer can
	 * promise is open before it has generated anything.
	 *
	 * <p>An arbitrary cell may have walls on all four sides - about one in seventy does, and a solid
	 * block in the floor plan is wanted, not a defect. But a player dropped inside one would be
	 * sealed in a four-by-four room with no door and no way to read the layer as anything but
	 * broken. At a trunk intersection both suppression rules fire on all four boundaries at once, so
	 * every entry point is a four-way junction by construction rather than by luck.
	 */
	public static int[] nearestTrunkIntersection(int cellX, int cellZ) {
		return new int[] {
				Math.round((float) cellX / TRUNK_SPACING_CELLS) * TRUNK_SPACING_CELLS,
				Math.round((float) cellZ / TRUNK_SPACING_CELLS) * TRUNK_SPACING_CELLS };
	}

	/**
	 * Nearest trunk intersection that is also somewhere a body can stand.
	 *
	 * <p>{@link #nearestTrunkIntersection} answers a question about walls. That is not the same as
	 * somewhere standable: a way out is a region whose floor does not hold anyone up, and an
	 * intersection that lands inside one would drop the player or the entity out of the world on the
	 * tick they were placed - for the player, the way out arriving before they took a step.
	 *
	 * <p>Steps outward along the trunk grid until it finds one clear of any exit region. Regions are
	 * one per thirty-two cells squared, so this all but always returns on the first try; the walk
	 * exists because "all but always" is not a guarantee and this is the call both entry and spawn
	 * placement are built on.
	 */
	public static int[] standableTrunkIntersection(long seed, int cellX, int cellZ) {
		int[] nearest = nearestTrunkIntersection(cellX, cellZ);
		for (int[] step : SEARCH_ORDER) {
			int candidateX = nearest[0] + step[0] * TRUNK_SPACING_CELLS;
			int candidateZ = nearest[1] + step[1] * TRUNK_SPACING_CELLS;
			if (!withinExitRegion(seed, candidateX, candidateZ)) {
				return new int[] { candidateX, candidateZ };
			}
		}
		return nearest;
	}

	/** Centre first, then the eight neighbouring intersections. Fixed, so placement stays repeatable. */
	private static final int[][] SEARCH_ORDER = {
			{ 0, 0 }, { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 },
			{ 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 } };

	/** Whether this position is floor a player can actually stand on. */
	public static boolean walkable(long seed, int x, int z) {
		return !wall(seed, x, z) && !falseFloor(seed, x, z);
	}

	/**
	 * Whether the full-height wall column here is solid.
	 *
	 * <p>A false wall is deliberately <em>not</em> one. It looks like a wall and stops nothing, so
	 * every reachability question in this class - and every path the entity's navigation builds -
	 * has to treat it as open, which is also what makes an exit region incapable of sealing off any
	 * part of the floor plan.
	 */
	public static boolean wall(long seed, int x, int z) {
		if (withinExitRegionBlock(seed, x, z)) return false;
		int localX = UnrenderedLayerLayout.local(x);
		int localZ = UnrenderedLayerLayout.local(z);
		if (localX != 0 && localZ != 0) return false;
		int cellX = UnrenderedLayerLayout.cell(x);
		int cellZ = UnrenderedLayerLayout.cell(z);
		// The corner post stands whether or not either wall meeting there does. It is what keeps a
		// wall-less stretch from reading as empty space rather than as a room with the walls removed.
		if (localX == 0 && localZ == 0) return true;
		return localX == 0 ? westWall(seed, cellX, cellZ) : northWall(seed, cellX, cellZ);
	}

	/**
	 * Whether this column is the shell of a way out: it draws as a wall and has no collision.
	 *
	 * <p>This is the whole exit mechanism, and it is the same trick the layer opened with. Getting in
	 * was the floor deciding not to be solid; getting out is a wall doing the same. A door, a hole or
	 * anything else with a purpose would be a new object in a place whose entire effect depends on
	 * containing nothing that was put there for the player.
	 */
	public static boolean falseWall(long seed, int x, int z) {
		int[] region = exitRegionOrigin(seed, UnrenderedLayerLayout.cell(x), UnrenderedLayerLayout.cell(z));
		if (region == null) return false;
		int minX = region[0] * UnrenderedLayerLayout.CELL_SIZE;
		int minZ = region[1] * UnrenderedLayerLayout.CELL_SIZE;
		int span = EXIT_REGION_CELLS * UnrenderedLayerLayout.CELL_SIZE;
		// The shell is the boundary ring of the region box, one block thick on all four sides.
		return x == minX || x == minX + span || z == minZ || z == minZ + span;
	}

	/**
	 * Whether the floor here draws as floor and holds nobody up.
	 *
	 * <p>The inside of the shell. Stepping through the false wall puts the player in a column of
	 * wall texture standing on real floor for exactly one block, and the step after that is this -
	 * which is why the way out reads as two beats rather than as falling into a hole.
	 */
	/**
	 * A single floor block, somewhere in the open plan, that does not hold.
	 *
	 * <p>Hashed per block rather than per cell - these are meant to be individual and unpatterned, and
	 * a per-cell roll would put them on the same lattice everything else in the layer sits on.
	 *
	 * <p>Never on a wall line, because a wall stands on its floor block and cutting that away leaves
	 * it hanging over nothing. Never on a trunk intersection either: those are the cells the entry
	 * and the entity are placed in, and both are placed without checking anything on the strength of
	 * that guarantee.
	 */
	private static boolean scatteredSoftFloor(long seed, int x, int z) {
		// A wall stands on its own floor block; cutting that away leaves it hanging over nothing.
		// Tested per block rather than per patch, so a patch that would clip a wall line simply
		// loses that block and stays a hole everywhere else.
		if (UnrenderedLayerLayout.local(x) == 0 || UnrenderedLayerLayout.local(z) == 0) return false;
		int cellX = UnrenderedLayerLayout.cell(x);
		int cellZ = UnrenderedLayerLayout.cell(z);
		// Entry and the entity are both placed on trunk intersections without checking anything.
		if (Math.floorMod(cellX, TRUNK_SPACING_CELLS) == 0
				&& Math.floorMod(cellZ, TRUNK_SPACING_CELLS) == 0) return false;
		// Hashed per patch, not per block: every block in the same four-by-four square asks the same
		// question and gets the same answer, which is what makes the opening a square rather than
		// scattered single blocks that happen to be near each other.
		int patchX = Math.floorDiv(x, SOFT_FLOOR_PATCH);
		int patchZ = Math.floorDiv(z, SOFT_FLOOR_PATCH);
		return ((mix(seed, patchX, patchZ, SOFT_SALT) >>> 32) & 0xFFFFL) < SOFT_FLOOR_CHANCE_65536;
	}

	public static boolean falseFloor(long seed, int x, int z) {
		if (scatteredSoftFloor(seed, x, z)) return true;
		int[] region = exitRegionOrigin(seed, UnrenderedLayerLayout.cell(x), UnrenderedLayerLayout.cell(z));
		if (region == null) return false;
		int minX = region[0] * UnrenderedLayerLayout.CELL_SIZE;
		int minZ = region[1] * UnrenderedLayerLayout.CELL_SIZE;
		int span = EXIT_REGION_CELLS * UnrenderedLayerLayout.CELL_SIZE;
		return x > minX && x < minX + span && z > minZ && z < minZ + span;
	}

	/** Whether this position carries a working ceiling panel rather than a dead one. */
	public static boolean ceilingLight(long seed, int x, int z) {
		if (withinExitRegionBlock(seed, x, z)) return false;
		if (UnrenderedLayerLayout.local(x) != UnrenderedLayerLayout.LIGHT_LOCAL_X
				|| UnrenderedLayerLayout.local(z) != UnrenderedLayerLayout.LIGHT_LOCAL_Z) return false;
		int cellX = UnrenderedLayerLayout.cell(x);
		int cellZ = UnrenderedLayerLayout.cell(z);
		return below(mix(seed, cellX, cellZ, LIGHT_SALT), LIGHT_CHANCE_256);
	}

	/**
	 * Bottom-left cell of the exit region covering this cell, or null when there is none.
	 *
	 * <p>The region is anchored so the whole {@link #EXIT_REGION_CELLS} square stays inside one
	 * partition square. Letting it straddle the boundary would put two regions within a few blocks
	 * of each other whenever two neighbouring squares both drew an edge position, which is the one
	 * way a fixed density can still produce a cluster.
	 */
	public static int[] exitRegionOrigin(long seed, int cellX, int cellZ) {
		int squareX = Math.floorDiv(cellX, EXIT_SQUARE_CELLS);
		int squareZ = Math.floorDiv(cellZ, EXIT_SQUARE_CELLS);
		long h = mix(seed, squareX, squareZ, EXIT_SALT);
		int span = EXIT_SQUARE_CELLS - EXIT_REGION_CELLS;
		int originX = squareX * EXIT_SQUARE_CELLS + (int) ((h >>> 8) & 0xFFFFL) % span;
		int originZ = squareZ * EXIT_SQUARE_CELLS + (int) ((h >>> 32) & 0xFFFFL) % span;
		if (cellX < originX || cellX > originX + EXIT_REGION_CELLS
				|| cellZ < originZ || cellZ > originZ + EXIT_REGION_CELLS) {
			return null;
		}
		return new int[] { originX, originZ };
	}

	/** Bottom-left cell of the exit region in the partition square containing this cell. */
	public static int[] exitRegionInSquare(long seed, int cellX, int cellZ) {
		int squareX = Math.floorDiv(cellX, EXIT_SQUARE_CELLS);
		int squareZ = Math.floorDiv(cellZ, EXIT_SQUARE_CELLS);
		long h = mix(seed, squareX, squareZ, EXIT_SALT);
		int span = EXIT_SQUARE_CELLS - EXIT_REGION_CELLS;
		return new int[] {
				squareX * EXIT_SQUARE_CELLS + (int) ((h >>> 8) & 0xFFFFL) % span,
				squareZ * EXIT_SQUARE_CELLS + (int) ((h >>> 32) & 0xFFFFL) % span };
	}

	/** Block coordinates of the centre of the exit region nearest this position, as {@code {x, z}}. */
	public static int[] nearestExitCentre(long seed, int x, int z) {
		int cellX = UnrenderedLayerLayout.cell(x);
		int cellZ = UnrenderedLayerLayout.cell(z);
		int best = Integer.MAX_VALUE;
		int[] chosen = null;
		// The nine partition squares around this one. A region can never be further than the square
		// diagonal, so this always finds the true nearest.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int[] origin = exitRegionInSquare(seed,
						cellX + dx * EXIT_SQUARE_CELLS, cellZ + dz * EXIT_SQUARE_CELLS);
				int centreX = origin[0] * UnrenderedLayerLayout.CELL_SIZE
						+ EXIT_REGION_CELLS * UnrenderedLayerLayout.CELL_SIZE / 2;
				int centreZ = origin[1] * UnrenderedLayerLayout.CELL_SIZE
						+ EXIT_REGION_CELLS * UnrenderedLayerLayout.CELL_SIZE / 2;
				int distance = Math.abs(centreX - x) + Math.abs(centreZ - z);
				if (distance < best) {
					best = distance;
					chosen = new int[] { centreX, centreZ };
				}
			}
		}
		return chosen;
	}

	/** Whether this cell is part of an exit region. */
	public static boolean withinExitRegion(long seed, int cellX, int cellZ) {
		return exitRegionOrigin(seed, cellX, cellZ) != null;
	}

	private static boolean withinExitRegionBlock(long seed, int x, int z) {
		return falseWall(seed, x, z) || falseFloor(seed, x, z);
	}

	private static boolean westWall(long seed, int cellX, int cellZ) {
		if (Math.floorMod(cellZ, TRUNK_SPACING_CELLS) == 0) return false;
		return below(mix(seed, cellX, cellZ, WEST_SALT), WALL_CHANCE_256);
	}

	private static boolean northWall(long seed, int cellX, int cellZ) {
		if (Math.floorMod(cellX, TRUNK_SPACING_CELLS) == 0) return false;
		return below(mix(seed, cellX, cellZ, NORTH_SALT), WALL_CHANCE_256);
	}

	private static boolean below(long hash, int chanceOf256) {
		return ((hash >>> 40) & 0xFFL) < chanceOf256;
	}

	/**
	 * SplitMix-style finaliser over the two cell coordinates and a per-question salt.
	 *
	 * <p>The salt is why one cell can answer four unrelated questions without its answers
	 * correlating: a plain hash of the coordinates would give the wall, the light and the exit the
	 * same bits, and the layer would visibly line up its features.
	 */
	private static long mix(long seed, int cellX, int cellZ, long salt) {
		long h = seed ^ salt;
		h ^= (long) cellX * 0x9E3779B97F4A7C15L;
		h ^= (long) cellZ * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= h >>> 33;
		return h;
	}
}
