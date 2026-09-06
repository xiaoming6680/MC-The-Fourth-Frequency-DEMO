package com.xm.thefourthfrequency.unrendered;

import com.xm.thefourthfrequency.client_ui.DimensionViewDistancePolicy;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where players and the entity are put, and the two distances that keep the layer working.
 *
 * <p>Both failures these guard against are invisible in a single-player test and expensive
 * everywhere else: two players landing close enough to see each other turns a private event into a
 * shared one, and an entity placed inside view distance is watched appearing out of nothing, which
 * answers the only question it exists to ask.
 */
final class UnrenderedPlacementTest {
	private static final long SEED = UnrenderedLayerLayout.DEFAULT_SEED;

	@Test
	void noTwoSlotsCanEverSeeEachOther() {
		// Every slot against every other slot, at every visit either of them might be on. The layer
		// is one dimension, so this is the entire multiplayer isolation guarantee - there is no
		// second dimension underneath it to catch a mistake here.
		Set<String> seen = new HashSet<>();
		for (int slot = 0; slot < UnrenderedAnchorPolicy.MAX_CONCURRENT; slot++) {
			for (int visit = 0; visit < 64; visit++) {
				int[] entry = UnrenderedAnchorPolicy.entry(slot, visit);
				assertTrue(seen.add(entry[0] + ":" + entry[1]),
						"two sessions share an entry point at slot " + slot + " visit " + visit);
				for (int other = 0; other < UnrenderedAnchorPolicy.MAX_CONCURRENT; other++) {
					if (other == slot) continue;
					for (int otherVisit = 0; otherVisit < 64; otherVisit += 7) {
						int[] rival = UnrenderedAnchorPolicy.entry(other, otherVisit);
						long dx = entry[0] - (long) rival[0];
						long dz = entry[1] - (long) rival[1];
						assertTrue(Math.abs(dx) > 100_000L || Math.abs(dz) > 100_000L,
								"slots " + slot + " and " + other + " are within reach of each other");
					}
				}
			}
		}
	}

	@Test
	void everyEntryPointStaysInsideTheWorldBorder() {
		// The border is +-29,999,984 and the build limit +-30,000,000. A slot pushed past either does
		// not fail loudly; the teleport simply puts the player somewhere they cannot move.
		for (int slot = 0; slot < UnrenderedAnchorPolicy.MAX_CONCURRENT; slot++) {
			for (int visit = 0; visit < 64; visit++) {
				int[] entry = UnrenderedAnchorPolicy.entry(slot, visit);
				assertTrue(Math.abs(entry[0]) < 29_000_000, "slot " + slot + " x out of bounds");
				assertTrue(Math.abs(entry[1]) < 29_000_000, "slot " + slot + " z out of bounds");
				assertTrue(Math.abs(entry[0]) <= UnrenderedAnchorPolicy.MAX_ABSOLUTE_COORDINATE,
						"slot " + slot + " exceeds its own declared maximum");
			}
		}
	}

	@Test
	void everyEntryPointIsStandableAndOpen() {
		// The session service teleports here without checking anything, on the strength of this.
		for (int slot = 0; slot < UnrenderedAnchorPolicy.MAX_CONCURRENT; slot++) {
			for (int visit = 0; visit < 64; visit += 3) {
				int[] entry = UnrenderedAnchorPolicy.entry(slot, visit);
				assertTrue(UnrenderedMazePolicy.walkable(SEED, entry[0], entry[1]),
						"entry point is not standable at slot " + slot + " visit " + visit);
			}
		}
		assertEquals(UnrenderedLayerLayout.FLOOR_Y + 1, UnrenderedAnchorPolicy.entryY());
	}

	@Test
	void theEntityIsAlwaysPlacedBeyondWhatThePlayerCanSee() {
		// The pairing that has to hold: spawn distance above view distance. Both numbers are tunable
		// and they live in different files, so nothing but this stops one moving without the other.
		int visibleBlocks = DimensionViewDistancePolicy.UNRENDERED_LAYER_CHUNKS * 16;
		assertTrue(UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE > visibleBlocks,
				"the entity would be watched arriving: minimum spawn "
						+ UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE + " vs visible " + visibleBlocks);
		assertEquals(DimensionViewDistancePolicy.UNRENDERED_LAYER_CHUNKS,
				DimensionViewDistancePolicy.lockedChunks(DimensionViewDistancePolicy.UNRENDERED_LAYER_ID));
	}

	@Test
	void atLeastOneSpawnDirectionClearsTheMinimumFromAnywhere() {
		// The caller re-rolls the direction and gives up if none qualifies, so "gives up" must never
		// be the answer - an entity that is never placed is a six-minute walk with nothing in it.
		for (int slot = 0; slot < UnrenderedAnchorPolicy.MAX_CONCURRENT; slot += 3) {
			for (int visit = 0; visit < 64; visit += 11) {
				int[] entry = UnrenderedAnchorPolicy.entry(slot, visit);
				assertTrue(anyDirectionQualifies(entry[0], entry[1]),
						"no usable spawn direction at slot " + slot + " visit " + visit);
			}
		}
	}

	@Test
	void everySpawnPointIsStandableAndOpen() {
		// Placed without a search, on the strength of landing on a trunk intersection. An entity in
		// a sealed cell passes every distance check and then never moves, which reads as broken.
		for (int x = -2_000; x <= 2_000; x += 137) {
			for (int z = -2_000; z <= 2_000; z += 149) {
				for (long nonce = 0; nonce < UnrenderedStalkerPolicy.directionCount(); nonce++) {
					int[] spot = UnrenderedStalkerPolicy.spawnPosition(x, z, nonce);
					assertTrue(UnrenderedMazePolicy.walkable(SEED, spot[0], spot[1]),
							"spawn point is not standable at " + spot[0] + "," + spot[1]);
					assertFalse(UnrenderedMazePolicy.falseFloor(SEED, spot[0], spot[1]),
							"spawn point is inside a way out at " + spot[0] + "," + spot[1]);
				}
			}
		}
	}

	private static boolean anyDirectionQualifies(int playerX, int playerZ) {
		double minimum = (double) UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE
				* UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE;
		for (long nonce = 0; nonce < UnrenderedStalkerPolicy.directionCount(); nonce++) {
			int[] spot = UnrenderedStalkerPolicy.spawnPosition(playerX, playerZ, nonce);
			double dx = spot[0] - (double) playerX;
			double dz = spot[1] - (double) playerZ;
			if (dx * dx + dz * dz >= minimum) return true;
		}
		return false;
	}
}
