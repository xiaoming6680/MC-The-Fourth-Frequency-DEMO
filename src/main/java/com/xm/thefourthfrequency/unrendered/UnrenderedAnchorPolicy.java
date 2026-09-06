package com.xm.thefourthfrequency.unrendered;

/**
 * Where in the endless floor plan each concurrent session is dropped.
 *
 * <p>This is the whole of the multiplayer isolation. There is one unrendered level, and two players
 * in it are kept apart by distance rather than by separate dimensions: a slot's entry points are one
 * and a half million blocks from the next slot's, which is far enough that neither chunk loading,
 * entity tracking, sound nor the map can carry anything between them. Because the maze is hashed
 * from position they are not even in the same-looking place, so two players comparing screenshots
 * afterwards will not find they were describing one room.
 *
 * <p>Pure integer arithmetic on purpose. The two properties that matter - that slots never overlap,
 * and that no entry point can be pushed outside the world border - are exactly the kind that hold
 * for the cases anybody tries by hand and fail at slot fifteen on the ninth visit.
 */
public final class UnrenderedAnchorPolicy {
	/**
	 * Concurrent sessions the layer will host.
	 *
	 * <p>Sixteen rather than one. A shared anomaly would be wrong here - the layer is a private
	 * event and its whole effect depends on being alone in it - but so would a server-wide lock,
	 * which on a full server means most players simply never see it.
	 */
	public static final int MAX_CONCURRENT = 16;

	private static final int SLOT_SPACING = 1_500_000;

	/**
	 * Distinct entry points per slot, cycled through by visit count.
	 *
	 * <p>A single fixed entry per slot would be learnable: the second visit would start in a room
	 * the player recognises, and recognising anything is the one thing this place must not offer. An
	 * unbounded random offset would fix that and quietly grow the level's region files without limit
	 * instead, since every session would generate chunks somewhere new and nothing ever deletes
	 * them. Sixty-four bounds the footprint at a few hundred chunks per slot while leaving nothing
	 * for a player to recognise.
	 */
	private static final int ORIGIN_VARIANTS = 64;
	private static final int VARIANTS_PER_ROW = 8;
	private static final int VARIANT_SPACING = 2_048;

	/** Furthest a valid entry point can sit from the origin, used by the world border assertion. */
	public static final int MAX_ABSOLUTE_COORDINATE =
			(MAX_CONCURRENT / 2) * SLOT_SPACING + ORIGIN_VARIANTS / VARIANTS_PER_ROW * VARIANT_SPACING;

	private UnrenderedAnchorPolicy() {
	}

	/**
	 * Block position of a session's entry point, as {@code {x, z}}.
	 *
	 * <p>Snapped to the middle of a trunk intersection that is not also the exit cell - the only kind
	 * of cell the maze can promise is both open on all four sides and floored, before it has
	 * generated anything. See {@link UnrenderedMazePolicy#standableTrunkIntersection}.
	 */
	public static int[] entry(int slot, int visit) {
		int normalizedSlot = Math.clamp(slot, 0, MAX_CONCURRENT - 1);
		int variant = Math.floorMod(visit, ORIGIN_VARIANTS);
		int baseX = (normalizedSlot - MAX_CONCURRENT / 2) * SLOT_SPACING
				+ variant % VARIANTS_PER_ROW * VARIANT_SPACING;
		int baseZ = variant / VARIANTS_PER_ROW * VARIANT_SPACING;
		int[] cell = UnrenderedMazePolicy.standableTrunkIntersection(UnrenderedLayerLayout.DEFAULT_SEED,
				UnrenderedLayerLayout.cell(baseX), UnrenderedLayerLayout.cell(baseZ));
		return new int[] {
				cell[0] * UnrenderedLayerLayout.CELL_SIZE + UnrenderedLayerLayout.CELL_SIZE / 2,
				cell[1] * UnrenderedLayerLayout.CELL_SIZE + UnrenderedLayerLayout.CELL_SIZE / 2 };
	}

	/** The y a player stands on after entry: directly on the floor. */
	public static int entryY() {
		return UnrenderedLayerLayout.FLOOR_Y + 1;
	}

	/**
	 * How far above {@link #entryY()} an arriving player is actually placed.
	 *
	 * <p>They come through the ceiling rather than appearing on the floor, so the first thing the
	 * cover lifts on is a fall. Two blocks and no more: the drop is then under three, which is the
	 * exact height vanilla charges no fall damage for, and entering this place has never cost a
	 * player anything. The interior is four blocks tall, so this still leaves the standing box clear
	 * of the ceiling slab - the head being <em>in</em> the ceiling is a camera offset the client
	 * holds for a few frames, not a body inside a block with suffocation attached.
	 */
	public static final int ENTRY_DROP_BLOCKS = 2;
}
