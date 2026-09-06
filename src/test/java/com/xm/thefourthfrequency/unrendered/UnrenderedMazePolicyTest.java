package com.xm.thefourthfrequency.unrendered;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer has to be endless and confusing without ever being a trap.
 *
 * <p>These are the assertions that cannot be made by looking at it. A floor plan can look correct
 * from inside one room and still have sealed the only way out four hundred blocks away, and the
 * cost of finding that out in game is a player who has to be freed by an operator. The flood fills
 * below walk the same block-level geometry the generator writes, so what they prove about
 * reachability is what the dimension will actually do.
 */
final class UnrenderedMazePolicyTest {
	private static final long SEED = UnrenderedLayerLayout.DEFAULT_SEED;
	private static final int CELL = UnrenderedLayerLayout.CELL_SIZE;
	/** Matches EXIT_SQUARE_CELLS; the partition the density guarantee is stated over. */
	private static final int SQUARE = 32;
	/** Matches EXIT_REGION_CELLS. */
	private static final int REGION = 3;

	@Test
	void everyEntryPointIsAFourWayJunction() {
		// The entry cell is chosen by rounding to a trunk intersection, so this is the property the
		// session service depends on: whatever cell a player is dropped into, all four sides are open.
		for (int cellX = -40; cellX <= 40; cellX++) {
			for (int cellZ = -40; cellZ <= 40; cellZ++) {
				int[] entry = UnrenderedMazePolicy.standableTrunkIntersection(SEED, cellX, cellZ);
				assertEquals(0, Math.floorMod(entry[0], UnrenderedMazePolicy.TRUNK_SPACING_CELLS));
				assertEquals(0, Math.floorMod(entry[1], UnrenderedMazePolicy.TRUNK_SPACING_CELLS));
				assertOpenOnAllFourSides(entry[0], entry[1]);
				assertFalse(UnrenderedMazePolicy.withinExitRegion(SEED, entry[0], entry[1]),
						"an entry point inside an exit region drops the player out on arrival");
			}
		}
	}

	@Test
	void aPlayerEnteringAtAnyTrunkIntersectionCanWalkToAWayOut() {
		// The one guarantee the exit mechanism rests on. A way out nobody can reach is the same as no
		// way out at all, and the timeout would then be the only ending - which is a worse experience
		// and, on a long enough stay, reads as a softlock.
		for (int entryCellX : new int[] { 0, 24, -32, 400, -1_000 }) {
			for (int entryCellZ : new int[] { 0, -16, 56, -800, 2_048 }) {
				int[] entry = UnrenderedMazePolicy.standableTrunkIntersection(SEED, entryCellX, entryCellZ);
				int startX = entry[0] * CELL + 2;
				int startZ = entry[1] * CELL + 2;
				assertTrue(UnrenderedMazePolicy.walkable(SEED, startX, startZ),
						"entry block must be standable at cell " + entry[0] + "," + entry[1]);
				int steps = stepsToNearestWayOut(startX, startZ, 700);
				assertTrue(steps > 0,
						"no reachable way out within 700 blocks of cell " + entry[0] + "," + entry[1]);
			}
		}
	}

	@Test
	void theWayOutIsAFalseWallAroundAFalseFloor() {
		// The shape of the exit, asserted where it is decided rather than where it is drawn. The shell
		// has to be the boundary ring and the inside has to be the floor that lets go, because that
		// order is what makes it two beats - through the wall, then through the floor - rather than
		// a hole somebody fell into.
		for (int squareX = -2; squareX <= 2; squareX++) {
			for (int squareZ = -2; squareZ <= 2; squareZ++) {
				int[] origin = UnrenderedMazePolicy.exitRegionInSquare(SEED,
						squareX * SQUARE, squareZ * SQUARE);
				int minX = origin[0] * CELL;
				int minZ = origin[1] * CELL;
				int span = REGION * CELL;
				for (int offset = 0; offset <= span; offset++) {
					assertTrue(UnrenderedMazePolicy.falseWall(SEED, minX, minZ + offset),
							"west shell missing at " + minX + "," + (minZ + offset));
					assertTrue(UnrenderedMazePolicy.falseWall(SEED, minX + span, minZ + offset),
							"east shell missing");
					assertTrue(UnrenderedMazePolicy.falseWall(SEED, minX + offset, minZ),
							"north shell missing");
					assertTrue(UnrenderedMazePolicy.falseWall(SEED, minX + offset, minZ + span),
							"south shell missing");
				}
				for (int x = minX + 1; x < minX + span; x++) {
					for (int z = minZ + 1; z < minZ + span; z++) {
						assertTrue(UnrenderedMazePolicy.falseFloor(SEED, x, z),
								"interior floor still holds at " + x + "," + z);
					}
				}
			}
		}
	}

	@Test
	void theWayOutIsLargeEnoughToBeNoticedAndSmallEnoughToBeMissed() {
		// Fifteen blocks across. The size is what does the discoverability work now that the exit is
		// no longer obvious: a one-cell patch of slightly-wrong colour is a lighting artefact, and a
		// wall panel three cells wide is not.
		assertEquals(15, REGION * CELL);
		int[] origin = UnrenderedMazePolicy.exitRegionInSquare(SEED, 0, 0);
		assertFalse(UnrenderedMazePolicy.falseWall(SEED, origin[0] * CELL - 1, origin[1] * CELL),
				"the shell must not bleed outside the region");
		assertFalse(UnrenderedMazePolicy.falseFloor(SEED, origin[0] * CELL, origin[1] * CELL),
				"the shell line itself keeps real floor, so there is a block to stand on inside it");
	}

	@Test
	void aFalseWallBlocksNothingAndARealWallIsNeverFalse() {
		// The two have to be mutually exclusive at every position, because every reachability question
		// in the policy - and every path the entity's navigation builds - reads `wall` alone. A
		// position that answered true to both would be an obstacle the exit could hide behind.
		for (int x = -400; x < 400; x++) {
			for (int z = -400; z < 400; z++) {
				if (UnrenderedMazePolicy.falseWall(SEED, x, z)) {
					assertFalse(UnrenderedMazePolicy.wall(SEED, x, z),
							"a false wall must not also be solid at " + x + "," + z);
				}
			}
		}
	}

	@Test
	void exactlyOneWayOutExistsPerPartitionSquare() {
		// Fixed density, unpredictable position. An independent per-cell roll would instead make the
		// distance to the nearest exit an exponential draw, so some entries would land almost on top
		// of one and others would have none inside the timeout.
		int regions = 0;
		for (int cellX = 0; cellX < SQUARE; cellX++) {
			for (int cellZ = 0; cellZ < SQUARE; cellZ++) {
				int[] origin = UnrenderedMazePolicy.exitRegionOrigin(SEED, cellX, cellZ);
				if (origin != null && origin[0] == cellX && origin[1] == cellZ) regions++;
			}
		}
		assertEquals(1, regions, "one exit region per 32x32-cell square");
	}

	@Test
	void everyPartitionSquareKeepsItsRegionWhollyInside() {
		// A region allowed to straddle the partition boundary would let two neighbouring squares each
		// place one within a few blocks of the other, which is the one way a fixed density can still
		// produce a cluster.
		for (int squareX = -4; squareX <= 4; squareX++) {
			for (int squareZ = -4; squareZ <= 4; squareZ++) {
				int[] origin = UnrenderedMazePolicy.exitRegionInSquare(SEED,
						squareX * SQUARE, squareZ * SQUARE);
				assertTrue(origin[0] >= squareX * SQUARE
								&& origin[0] + REGION <= (squareX + 1) * SQUARE,
						"region escapes its square on x: " + origin[0]);
				assertTrue(origin[1] >= squareZ * SQUARE
								&& origin[1] + REGION <= (squareZ + 1) * SQUARE,
						"region escapes its square on z: " + origin[1]);
			}
		}
	}

	@Test
	void theBearingAlwaysPointsAtTheNearestWayOut() {
		// The terminal's one piece of help, and the rule it has to keep: coarse is allowed, wrong is
		// not. Checked against a direct scan of the nine surrounding squares rather than against the
		// same helper, so an error in the search would not agree with itself.
		for (int x = -600; x <= 600; x += 137) {
			for (int z = -600; z <= 600; z += 149) {
				int[] centre = UnrenderedMazePolicy.nearestExitCentre(SEED, x, z);
				int best = Integer.MAX_VALUE;
				for (int cx = -2; cx <= 2; cx++) {
					for (int cz = -2; cz <= 2; cz++) {
						int[] origin = UnrenderedMazePolicy.exitRegionInSquare(SEED,
								UnrenderedLayerLayout.cell(x) + cx * SQUARE,
								UnrenderedLayerLayout.cell(z) + cz * SQUARE);
						int candidateX = origin[0] * CELL + REGION * CELL / 2;
						int candidateZ = origin[1] * CELL + REGION * CELL / 2;
						best = Math.min(best, Math.abs(candidateX - x) + Math.abs(candidateZ - z));
					}
				}
				assertEquals(best, Math.abs(centre[0] - x) + Math.abs(centre[1] - z),
						"the bearing target is not the nearest region from " + x + "," + z);
			}
		}
	}

	@Test
	void ceilingPanelsNeverSitOnTopOfAWallColumn() {
		// A panel inside a wall is a light source the player can see the glow of but never the source
		// of, which is the one kind of lighting oddity this place should not have.
		for (int x = -300; x < 300; x++) {
			for (int z = -300; z < 300; z++) {
				if (UnrenderedMazePolicy.ceilingLight(SEED, x, z)) {
					assertFalse(UnrenderedMazePolicy.wall(SEED, x, z),
							"ceiling panel above a wall at " + x + "," + z);
				}
			}
		}
	}

	@Test
	void trunkCorridorsRunWithoutInterruption() {
		// The long sightline, and the reason the floor plan is connected at all. A trunk column must
		// be walkable end to end along z, and a trunk row end to end along x.
		int laneX = 2;
		for (int z = -2_000; z < 2_000; z++) {
			assertTrue(UnrenderedMazePolicy.walkable(SEED, laneX, z)
							|| UnrenderedMazePolicy.falseFloor(SEED, laneX, z),
					"trunk column blocked at z=" + z);
		}
		int laneZ = 2;
		for (int x = -2_000; x < 2_000; x++) {
			assertTrue(UnrenderedMazePolicy.walkable(SEED, x, laneZ)
							|| UnrenderedMazePolicy.falseFloor(SEED, x, laneZ),
					"trunk row blocked at x=" + x);
		}
	}

	@Test
	void theLayerIsTheSameFloorPlanInEveryWorld() {
		// The seed is a constant rather than the world seed, so two saves describe the same corridor.
		// Asserting it here is what stops a later "just use the world seed" change from going
		// unnoticed until two players compare screenshots.
		assertEquals(0x4C45_5645_4C30_0000L, UnrenderedLayerLayout.DEFAULT_SEED);
	}

	@Test
	void scatteredSoftFloorIsRareUnpatternedAndNeverWhereSomethingStands() {
		// The second way out: single floor blocks that do not hold, found by running rather than by
		// looking. Three things have to be true of them and none is visible from a single block.
		int soft = 0;
		int sampled = 0;
		for (int x = -400; x < 400; x++) {
			for (int z = -400; z < 400; z++) {
				if (UnrenderedMazePolicy.wall(SEED, x, z)) continue;
				int[] region = UnrenderedMazePolicy.exitRegionOrigin(SEED,
						UnrenderedLayerLayout.cell(x), UnrenderedLayerLayout.cell(z));
				if (region != null) continue;
				sampled++;
				if (!UnrenderedMazePolicy.falseFloor(SEED, x, z)) continue;
				soft++;
				// A wall stands on its own floor block; cutting that away leaves it over nothing.
				assertFalse(UnrenderedLayerLayout.local(x) == 0 || UnrenderedLayerLayout.local(z) == 0,
						"a soft floor block undercuts a wall line at " + x + "," + z);
				// Entry and the entity are both placed on trunk intersections without checking
				// anything, on the strength of those cells being solid.
				boolean trunkIntersection =
						Math.floorMod(UnrenderedLayerLayout.cell(x), UnrenderedMazePolicy.TRUNK_SPACING_CELLS) == 0
						&& Math.floorMod(UnrenderedLayerLayout.cell(z), UnrenderedMazePolicy.TRUNK_SPACING_CELLS) == 0;
				assertFalse(trunkIntersection,
						"a soft floor block landed on a placement cell at " + x + "," + z);
			}
		}
		// Rare enough to be luck rather than a route: on the order of one block in a thousand. Both
		// bounds matter - too many and the maze stops mattering, too few and the panic exit never
		// happens at all.
		double rate = soft / (double) sampled;
		assertTrue(rate > 0.0002D && rate < 0.0025D,
				"soft floor rate out of band: " + rate + " (" + soft + " of " + sampled + ")");
	}

	@Test
	void aScatteredOpeningIsAFourByFourSquareNotASingleBlock() {
		// The shape is the whole fix. As single blocks these both showed up as off-colour speckle
		// across every floor in the layer and were stepped over without being hit - at six blocks a
		// second the gap between footfalls is wider than a one-block hole. A patch is something you
		// fall into.
		int patches = 0;
		for (int x = -600; x < 600; x += 4) {
			for (int z = -600; z < 600; z += 4) {
				if (!UnrenderedMazePolicy.falseFloor(SEED, x, z)) continue;
				int[] region = UnrenderedMazePolicy.exitRegionOrigin(SEED,
						UnrenderedLayerLayout.cell(x), UnrenderedLayerLayout.cell(z));
				if (region != null) continue;
				patches++;
				// Every block of the patch answers the same way, except where a wall line runs
				// through it - a wall stands on its own floor block and keeps it.
				for (int dx = 0; dx < 4; dx++) {
					for (int dz = 0; dz < 4; dz++) {
						int bx = x + dx;
						int bz = z + dz;
						boolean onWallLine = UnrenderedLayerLayout.local(bx) == 0
								|| UnrenderedLayerLayout.local(bz) == 0;
						if (onWallLine) continue;
						assertTrue(UnrenderedMazePolicy.falseFloor(SEED, bx, bz),
								"a scattered opening is not a whole patch at " + bx + "," + bz);
					}
				}
			}
		}
		assertTrue(patches > 0, "no scattered openings found in the sampled area at all");
	}

	private static void assertOpenOnAllFourSides(int cellX, int cellZ) {
		int baseX = cellX * CELL;
		int baseZ = cellZ * CELL;
		for (int local = 1; local < CELL; local++) {
			assertFalse(UnrenderedMazePolicy.wall(SEED, baseX, baseZ + local),
					"west side sealed at cell " + cellX + "," + cellZ);
			assertFalse(UnrenderedMazePolicy.wall(SEED, baseX + CELL, baseZ + local),
					"east side sealed at cell " + cellX + "," + cellZ);
			assertFalse(UnrenderedMazePolicy.wall(SEED, baseX + local, baseZ),
					"north side sealed at cell " + cellX + "," + cellZ);
			assertFalse(UnrenderedMazePolicy.wall(SEED, baseX + local, baseZ + CELL),
					"south side sealed at cell " + cellX + "," + cellZ);
		}
	}

	/**
	 * Breadth-first walk over standable blocks, returning the step count at which the way out is
	 * first reached, or zero if none is reachable inside the radius.
	 *
	 * <p>Block resolution rather than cell resolution on purpose: walls are one block thick and sit
	 * on cell boundaries, so a cell graph would answer a slightly different question than the one the
	 * player asks by walking. False walls are passable here for the same reason they are passable in
	 * game - they have no collision, and a search that treated them as obstacles would be proving
	 * something about a floor plan that does not exist.
	 */
	private static int stepsToNearestWayOut(int startX, int startZ, int radius) {
		int side = radius * 2 + 1;
		boolean[] seen = new boolean[side * side];
		Deque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] { startX, startZ, 0 });
		seen[index(radius, radius, side)] = true;
		while (!queue.isEmpty()) {
			int[] node = queue.removeFirst();
			int x = node[0];
			int z = node[1];
			int distance = node[2];
			if (UnrenderedMazePolicy.falseFloor(SEED, x, z)) return Math.max(1, distance);
			if (distance >= radius) continue;
			for (int[] step : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
				int nextX = x + step[0];
				int nextZ = z + step[1];
				int gridX = nextX - startX + radius;
				int gridZ = nextZ - startZ + radius;
				if (gridX < 0 || gridZ < 0 || gridX >= side || gridZ >= side) continue;
				int flat = index(gridX, gridZ, side);
				if (seen[flat]) continue;
				if (UnrenderedMazePolicy.wall(SEED, nextX, nextZ)) continue;
				seen[flat] = true;
				queue.addLast(new int[] { nextX, nextZ, distance + 1 });
			}
		}
		return 0;
	}

	private static int index(int gridX, int gridZ, int side) {
		return gridZ * side + gridX;
	}
}
