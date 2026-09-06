package com.xm.thefourthfrequency.client_ui;

/** Pure dimension policy shared by client enforcement, fog rendering and tests. */
public final class DimensionViewDistancePolicy {
	public static final String OVERWORLD_ID = "minecraft:overworld";
	public static final String NETHER_ID = "minecraft:the_nether";
	public static final String END_ID = "minecraft:the_end";
	public static final int OVERWORLD_CHUNKS = 6;
	public static final int NETHER_CHUNKS = 12;
	public static final int END_CHUNKS = 16;
	public static final int OTHER_CHUNKS = 12;
	public static final String UNRENDERED_LAYER_ID = "thefourthfrequency:unrendered_layer";
	/**
	 * Six, the same as the overworld, and here it is a design requirement rather than a budget.
	 *
	 * <p>The layer is one floor plan repeated without end, so the only thing keeping it frightening
	 * is that the player can never see enough of it at once to work out that it repeats. At the
	 * twelve chunks every other custom dimension gets, a trunk corridor runs visibly to the horizon
	 * and the eight-cell grid behind it becomes obvious from any junction - the place stops being
	 * endless and becomes merely large, which is a much smaller feeling.
	 *
	 * <p>Ninety-six blocks is also what {@code UnrenderedStalkerPolicy} places the entity outside of.
	 * The two numbers are a pair: raising this without raising that one lets the player watch it
	 * arrive.
	 */
	public static final int UNRENDERED_LAYER_CHUNKS = 6;
	/**
	 * What the player sees on the way back down, and the exact inverse of the lock above.
	 *
	 * <p>Leaving the layer hands them back two hundred blocks over their own world, falling. The
	 * whole moment is how much is suddenly visible - six chunks of that is a hole in a cloud, and it
	 * would read as another corridor rather than as the world coming back. Sixteen is the same
	 * figure a successful finale unlocks, which is the other place this mod says "you are out".
	 *
	 * <p>Temporary by construction: it applies while the client believes the local player is
	 * airborne after an exit, and the client drops it on landing without asking the server.
	 */
	public static final int SKY_RETURN_CHUNKS = 16;
	public static final int SUCCESS_RETURN_CHUNKS = 16;

	private DimensionViewDistancePolicy() {
	}

	public static int lockedChunks(String dimensionId) {
		if (OVERWORLD_ID.equals(dimensionId)) return OVERWORLD_CHUNKS;
		if (NETHER_ID.equals(dimensionId)) return NETHER_CHUNKS;
		if (END_ID.equals(dimensionId)) return END_CHUNKS;
		if (UNRENDERED_LAYER_ID.equals(dimensionId)) return UNRENDERED_LAYER_CHUNKS;
		return OTHER_CHUNKS;
	}
}
