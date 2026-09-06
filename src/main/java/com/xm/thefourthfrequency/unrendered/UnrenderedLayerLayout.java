package com.xm.thefourthfrequency.unrendered;

/**
 * Fixed vertical geometry of the unrendered layer, and the cell grid its maze is cut from.
 *
 * <p>Six occupied blocks per column and nothing else. The dimension is declared sixteen blocks tall
 * because a dimension height must be a multiple of sixteen, not because anything lives above the
 * ceiling - the ten blocks over {@link #CEILING_Y} exist only to satisfy that rule and are never
 * written to.
 *
 * <p>The floor sits on the very bottom of the world on purpose. A hole in it is a hole in the world,
 * so falling through one needs no trigger block and no collision test: the player simply leaves the
 * build height, which {@code UnrenderedSessionService} watches for. That is the whole exit
 * mechanism, and it is why {@link #FLOOR_Y} may not be raised without replacing it.
 */
public final class UnrenderedLayerLayout {
	/**
	 * The layer's seed, fixed rather than taken from the world.
	 *
	 * <p>A generator is not handed the world seed by {@code RandomState}, but that is only half the
	 * reason. The layer is not part of anybody's world - it is the same place behind all of them, and
	 * two players comparing notes across two saves should be describing the same corridor. A
	 * per-world layout would also make every acceptance screenshot and every test fixture
	 * unreproducible.
	 */
	public static final long DEFAULT_SEED = 0x4C45_5645_4C30_0000L;

	/** Multiple of sixteen, as every dimension height must be. Only y=0..5 is ever written. */
	public static final int WORLD_HEIGHT = 16;
	public static final int MIN_Y = 0;
	public static final int FLOOR_Y = 0;
	public static final int CEILING_Y = 5;
	/** Four blocks of headroom: enough to jump, not enough to build a way out. */
	public static final int INTERIOR_BOTTOM_Y = FLOOR_Y + 1;
	public static final int INTERIOR_TOP_Y = CEILING_Y - 1;

	/**
	 * Blocks per maze cell.
	 *
	 * <p>Walls occupy the cell's own north and west boundary lines, so a cell of five contributes a
	 * one-block wall and a four-block gap. Corridors therefore read as four wide, which is the width
	 * at which a corridor stops feeling like a tunnel and starts feeling like a room somebody
	 * forgot to furnish.
	 */
	public static final int CELL_SIZE = 5;

	/** Where the ceiling panel of a lit cell goes. Never on a wall line, which is local zero. */
	public static final int LIGHT_LOCAL_X = 2;
	public static final int LIGHT_LOCAL_Z = 2;

	private UnrenderedLayerLayout() {
	}

	public static int cell(int blockCoordinate) {
		return Math.floorDiv(blockCoordinate, CELL_SIZE);
	}

	public static int local(int blockCoordinate) {
		return Math.floorMod(blockCoordinate, CELL_SIZE);
	}

	/** Lowest y a player can occupy while still standing in the layer. */
	public static int voidThreshold() {
		return MIN_Y - 8;
	}
}
