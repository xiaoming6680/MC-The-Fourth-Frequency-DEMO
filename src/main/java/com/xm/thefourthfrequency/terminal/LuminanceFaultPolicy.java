package com.xm.thefourthfrequency.terminal;

/**
 * The shape of the volume {@code local_rule_collapse} stops solving light in, and nothing else.
 *
 * <p>Pure and on the common side so the geometry can be asserted without a client: the anomaly
 * itself is a client illusion - no block, light level or piece of server state changes - but the
 * size of the volume is a product decision with a rendering cost attached, and that is worth being
 * able to test and to argue about in one place.
 *
 * <h2>Why these radii</h2>
 *
 * <p>The darkness is baked into chunk meshes, so applying it costs one rebuild per section and
 * lifting it costs another. Seven by three by seven is 147 sections - a volume 112 blocks across and
 * 48 tall, which is comfortably "the area around the player" at any normal render distance - and it
 * is the largest box that still rebuilds as a brief hitch rather than a stall. Widening the
 * horizontal radius by one alone would take it to 243.
 *
 * <p>The vertical radius is deliberately the smaller one. Darkness that follows the player's own
 * elevation reads as the world going out; darkness stacked eighty blocks above them is spent on
 * sections they cannot see.
 */
public final class LuminanceFaultPolicy {
	public static final int HORIZONTAL_SECTION_RADIUS = 3;
	public static final int VERTICAL_SECTION_RADIUS = 1;
	public static final int HORIZONTAL_SPAN = HORIZONTAL_SECTION_RADIUS * 2 + 1;
	public static final int VERTICAL_SPAN = VERTICAL_SECTION_RADIUS * 2 + 1;
	public static final int SECTION_COUNT = HORIZONTAL_SPAN * VERTICAL_SPAN * HORIZONTAL_SPAN;

	private LuminanceFaultPolicy() { }

	/**
	 * Position of one section inside the darkened box, or -1 when it is outside it.
	 *
	 * <p>All coordinates are section coordinates, not block coordinates. Returning -1 rather than
	 * throwing is the contract the caller needs: this is asked once per light lookup, which during a
	 * chunk rebuild means millions of times, and the overwhelming majority of those are for sections
	 * the anomaly never touched.
	 */
	public static int index(int originSectionX, int originSectionY, int originSectionZ,
			int sectionX, int sectionY, int sectionZ) {
		int offsetX = sectionX - originSectionX + HORIZONTAL_SECTION_RADIUS;
		int offsetY = sectionY - originSectionY + VERTICAL_SECTION_RADIUS;
		int offsetZ = sectionZ - originSectionZ + HORIZONTAL_SECTION_RADIUS;
		if (offsetX < 0 || offsetX >= HORIZONTAL_SPAN) return -1;
		if (offsetY < 0 || offsetY >= VERTICAL_SPAN) return -1;
		if (offsetZ < 0 || offsetZ >= HORIZONTAL_SPAN) return -1;
		return (offsetY * HORIZONTAL_SPAN + offsetZ) * HORIZONTAL_SPAN + offsetX;
	}
}
