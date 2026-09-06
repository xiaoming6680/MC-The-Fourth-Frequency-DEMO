package com.xm.thefourthfrequency.unrendered;

/**
 * Where the entity is put when it arrives, expressed as maze geometry rather than as a search.
 *
 * <p>Two things have to hold at once and neither is obvious from a distance check. The spot must be
 * standable, and it must be somewhere the entity can actually path <em>from</em> - a walkable block
 * inside a sealed cell is both of those tests passed and an entity that never moves, which reads as
 * the encounter being broken rather than as the entity being far away.
 *
 * <p>So it does not search. It lands on a trunk intersection, which
 * {@link UnrenderedMazePolicy#nearestTrunkIntersection} already guarantees is open on all four sides
 * and which the trunk corridors connect to the rest of the floor plan by construction. The distance
 * comes out of the intersection spacing rather than being asked for exactly, which is why the
 * bounds below are a band and not a number.
 */
public final class UnrenderedStalkerPolicy {
	/**
	 * Cells out along one axis before snapping. Ten cells is fifty blocks.
	 *
	 * <p>Twenty-four cells is a hundred and twenty blocks, and the number it has to clear is the
	 * ninety-six the layer's view distance is locked to - the entity must never be watched arriving,
	 * because something that was placed is something that can be reasoned about and something that
	 * was always there cannot. Snapping to an intersection can pull it back by up to twenty, which
	 * is what {@link #MIN_SPAWN_DISTANCE} exists to catch.
	 *
	 * <p>The other end of the band is patience. At its speed a hundred and twenty blocks is around
	 * twenty seconds if the player stands still, so the minute of being alone is followed by a pause
	 * and then by something arriving - not by the minute simply happening again.
	 */
	private static final int SPAWN_CELL_DISTANCE = 21;

	/** The eight compass directions, as cell deltas. */
	private static final int[][] DIRECTIONS = {
			{ 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 },
			{ -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 } };

	private UnrenderedStalkerPolicy() {
	}

	/** How many directions {@link #spawnPosition} can be asked for before it repeats itself. */
	public static int directionCount() {
		return DIRECTIONS.length;
	}

	/**
	 * Block position to place the entity at, as {@code {x, z}}, given where the player is standing.
	 *
	 * <p>{@code nonce} picks the compass direction. It is the session id's hash rather than a random
	 * draw so that the same session always answers the same way - a spawn that moves between two
	 * ticks of the same session is a spawn that can happen twice.
	 */
	public static int[] spawnPosition(int playerX, int playerZ, long nonce) {
		int[] direction = DIRECTIONS[(int) Math.floorMod(nonce, DIRECTIONS.length)];
		int cellX = UnrenderedLayerLayout.cell(playerX) + direction[0] * SPAWN_CELL_DISTANCE;
		int cellZ = UnrenderedLayerLayout.cell(playerZ) + direction[1] * SPAWN_CELL_DISTANCE;
		int[] junction = UnrenderedMazePolicy.standableTrunkIntersection(
				UnrenderedLayerLayout.DEFAULT_SEED, cellX, cellZ);
		return new int[] {
				junction[0] * UnrenderedLayerLayout.CELL_SIZE + UnrenderedLayerLayout.CELL_SIZE / 2,
				junction[1] * UnrenderedLayerLayout.CELL_SIZE + UnrenderedLayerLayout.CELL_SIZE / 2 };
	}

	/**
	 * Closest the placement is ever allowed to land, in blocks.
	 *
	 * <p>Snapping to an intersection can pull the target back towards the player by up to half the
	 * trunk spacing, so the distance actually achieved is a range rather than the hundred and twenty
	 * blocks asked for. This is the floor of that range and it sits above the ninety-six blocks the
	 * player can see; the caller re-rolls the direction rather than accepting anything under it.
	 */
	/**
	 * Closest the placement is ever allowed to land, in blocks.
	 *
	 * <p>Ninety-seven, one block clear of the ninety-six the layer's view distance covers. Brought in
	 * from a hundred: the arrival should be as close as it can be without ever being watched, and the
	 * whole margin this number needs is the one block that makes "outside view distance" true.
	 */
	public static final int MIN_SPAWN_DISTANCE = 97;
}
