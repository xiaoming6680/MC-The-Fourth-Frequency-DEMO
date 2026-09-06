package com.xm.thefourthfrequency.world;

/**
 * When a vanilla structure the player has walked into answers the candidate the terminal marked.
 *
 * <p>The terminal gives the player exactly two things: a kind of place and a coordinate. So the
 * question this policy answers has to be the same two things - <em>are you inside that kind of
 * place, near there</em> - because anything else is a criterion the player has no way to observe and
 * therefore no way to satisfy on purpose.
 *
 * <h2>Why the marked coordinate is not the structure</h2>
 *
 * <p>The mark comes from {@code ChunkGenerator.findNearestMapStructure}, which returns
 * {@code StructurePlacement.getLocatePos} - the <em>corner of the chunk the structure started in</em>,
 * offset by the placement's own locate offset, with Y pinned to 0. For a desert pyramid that corner
 * is a few blocks from the door. For an abandoned mineshaft, which is a start room plus corridors
 * running hundreds of blocks out through the stone, it is a point somewhere near one end of a sprawl
 * that has no centre. The mark is a place to walk to, not the structure's identity, and a rule that
 * treats it as an identity is a rule that fails on exactly the structures that are hardest to walk
 * into.
 *
 * <h2>The failure this exists to end</h2>
 *
 * <p>The check used to be: the mark must lie inside the found structure's bounding box grown by 96
 * blocks on each horizontal axis. Two things are wrong with that, and both bite.
 *
 * <ul>
 * <li><b>96 is far smaller than the thing being matched.</b> A player who reaches the marked
 * coordinates, digs down and comes out in a corridor 150 blocks along - or in the neighbouring
 * mineshaft, which underground is indistinguishable from this one - is standing in an abandoned
 * mineshaft at the place the terminal sent them, and the receiver stayed silent. There is no
 * feedback for that and nothing for the player to try differently, so the fragment simply reads as
 * broken.</li>
 * <li><b>It was simultaneously too loose.</b> Growing a box on each axis independently accepts a
 * corner at 96·√2 ≈ 136 blocks diagonally while rejecting 97 along an axis, so "how close is close"
 * depended on which way the player happened to approach.</li>
 * </ul>
 *
 * <p>Both are replaced by one number, {@link #SIGNAL_RANGE_BLOCKS}, measured as a real horizontal
 * distance from the mark to the nearest point of the structure. The rule is now strictly more
 * permissive than the one it replaces, which is the safe direction: nothing that used to answer
 * stops answering.
 *
 * <h2>Height never participates</h2>
 *
 * <p>Every method here is horizontal. The mark's Y is 0 because that is what {@code getLocatePos}
 * writes, not because the structure is at bedrock, and an ancient city 100 blocks under the player's
 * feet is not further away than one they are standing on.
 */
public final class FragmentSignalPolicy {
	/**
	 * How far the structure may be from the marked coordinate and still be the one it marked.
	 *
	 * <p>Sized against the structures actually in the pool rather than against a feeling: a mineshaft
	 * regularly runs this far from its start chunk, and an ancient city is over 200 blocks across.
	 * Being generous costs a false positive - answering in the next mineshaft over - and being tight
	 * costs silence with no way to act on it. Between a signal the player did not quite earn and a
	 * fragment that cannot be completed, only one of the two is recoverable.
	 */
	public static final int SIGNAL_RANGE_BLOCKS = 320;

	private FragmentSignalPolicy() {
	}

	/**
	 * Cheap pre-filter: is the player anywhere near this candidate at all.
	 *
	 * <p>Exists so the detector does not pay for a structure lookup - which loads the chunk to its
	 * structure references - once per candidate, several times a second, for every player carrying a
	 * terminal. It is deliberately the same range as {@link #answersCandidate}, so it can only ever
	 * reject what that would have rejected anyway.
	 */
	public static boolean withinSignalRange(int playerX, int playerZ, int markX, int markZ) {
		long dx = playerX - (long) markX;
		long dz = playerZ - (long) markZ;
		return dx * dx + dz * dz <= (long) SIGNAL_RANGE_BLOCKS * SIGNAL_RANGE_BLOCKS;
	}

	/**
	 * Whether the structure the player is standing inside is the one the terminal marked.
	 *
	 * <p>Measured from the mark to the nearest point of the structure's own horizontal footprint, so
	 * a structure that reaches the mark answers at any size and one that does not is judged by how
	 * far short it falls - not by where its far corner happens to be.
	 *
	 * @param markX the marked coordinate's X, from the candidate
	 * @param markZ the marked coordinate's Z, from the candidate
	 * @param boxMinX the found structure's bounding box, horizontal extent only
	 */
	public static boolean answersCandidate(int markX, int markZ,
			int boxMinX, int boxMinZ, int boxMaxX, int boxMaxZ) {
		long dx = axisGap(markX, boxMinX, boxMaxX);
		long dz = axisGap(markZ, boxMinZ, boxMaxZ);
		return dx * dx + dz * dz <= (long) SIGNAL_RANGE_BLOCKS * SIGNAL_RANGE_BLOCKS;
	}

	/** How far outside the box the mark is on one axis; 0 while it is between the two edges. */
	private static long axisGap(int mark, int min, int max) {
		if (mark < min) return min - (long) mark;
		if (mark > max) return mark - (long) max;
		return 0L;
	}
}
