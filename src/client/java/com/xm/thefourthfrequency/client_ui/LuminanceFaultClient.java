package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.terminal.LuminanceFaultPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The lighting half of {@code local_rule_collapse} - the light around the player stops being
 * computed, and stays wrong until the world is touched. The missing textures scattered through the
 * same volume are the other half, and they are the half that does not repair.
 *
 * <p>Purely a client illusion. No block changes, no light level changes, nothing reaches the server,
 * and mob spawning - which is decided server-side from the real light - is unaffected. What the
 * player is looking at is their own renderer having lost the answer, which is the only reading of
 * "the lights are out" that this mod is allowed to make true.
 *
 * <h2>How it holds, and how it lets go</h2>
 *
 * <p>Chunk lighting is baked into the section mesh at compile time, and that single fact supplies
 * the whole mechanic. Marking the box dark and forcing one rebuild bakes black into every section in
 * it; the darkness then persists with no per-frame work at all, because it is simply what those
 * meshes now contain. Any block update dirties its section, vanilla recompiles it, and the section
 * comes back with real light - so the world repairs itself wherever the player disturbs it, one
 * section at a time, and the first thing they will try is exactly the thing that works.
 *
 * <p>{@link #end} is the backstop rather than the mechanism. A player standing in the open with
 * nothing to mine could otherwise wait out an anomaly that has no way to end, and "the exit is a
 * block update" is not an exit for someone in a boat.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #darkened} is called from chunk-build worker threads; {@link #begin}, {@link #end} and
 * {@link #sectionDirtied} are called from the client thread. The region is published through a
 * single volatile reference and its per-section flags live in an {@link AtomicIntegerArray}, so a
 * worker either sees the whole region or none of it. Boxing was not an option here: this is on the
 * per-light-lookup path.
 */
public final class LuminanceFaultClient {
	private static volatile Region region;
	/**
	 * True only for the instant {@link #begin} and {@link #end} are marking their own sections dirty.
	 *
	 * <p>Without it the two would undo themselves. The dirty call is how the darkness gets baked in,
	 * and it travels through the very hook that exists to clear the darkness when something dirties a
	 * section. Client-thread only, and the calls it guards are synchronous marking rather than the
	 * rebuild itself, so the window is a few microseconds wide.
	 */
	private static boolean selfDirtying;

	private LuminanceFaultClient() { }

	public static void begin(Minecraft client) {
		if (client.player == null || client.level == null) return;
		BlockPos feet = client.player.blockPosition();
		Region started = new Region(client.level, SectionPos.blockToSectionCoord(feet.getX()),
				SectionPos.blockToSectionCoord(feet.getY()), SectionPos.blockToSectionCoord(feet.getZ()));
		region = started;
		rebuild(client, started);
	}

	/** Restores every section the fault still holds. Safe to call when nothing is running. */
	public static void end(Minecraft client) {
		Region ending = region;
		if (ending == null) return;
		// Cleared before the rebuild, not after: the rebuild is what re-reads the light, and a worker
		// that got there while the region was still published would bake the darkness straight back in.
		region = null;
		if (client.level == ending.level) rebuild(client, ending);
	}

	/**
	 * Whether this position currently renders as unlit.
	 *
	 * <p>Reached from {@code LevelRendererLuminanceFaultMixin} on every light lookup in the game,
	 * so the inactive case - which is nearly all of them - is one volatile read and a null check.
	 */
	public static boolean darkened(BlockPos pos) {
		Region current = region;
		if (current == null || pos == null) return false;
		int index = LuminanceFaultPolicy.index(current.originX, current.originY, current.originZ,
				SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
				SectionPos.blockToSectionCoord(pos.getZ()));
		return index >= 0 && current.dark.get(index) != 0;
	}

	/**
	 * A section is about to be recompiled - let this one go if the fault was holding it.
	 *
	 * <p>Every path that changes a block funnels into {@code LevelRenderer.setSectionDirty}, which is
	 * why the repair does not have to enumerate what counts as a block update: placing, breaking, a
	 * piston, a server correction and a neighbour update all arrive here.
	 */
	public static void sectionDirtied(int sectionX, int sectionY, int sectionZ) {
		if (selfDirtying) return;
		Region current = region;
		if (current == null) return;
		int index = LuminanceFaultPolicy.index(current.originX, current.originY, current.originZ,
				sectionX, sectionY, sectionZ);
		if (index >= 0) current.dark.set(index, 0);
	}

	public static boolean active() {
		return region != null;
	}

	private static void rebuild(Minecraft client, Region target) {
		if (client.levelRenderer == null) return;
		selfDirtying = true;
		try {
			client.levelRenderer.setSectionRangeDirty(
					target.originX - LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS,
					target.originY - LuminanceFaultPolicy.VERTICAL_SECTION_RADIUS,
					target.originZ - LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS,
					target.originX + LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS,
					target.originY + LuminanceFaultPolicy.VERTICAL_SECTION_RADIUS,
					target.originZ + LuminanceFaultPolicy.HORIZONTAL_SECTION_RADIUS);
		} finally {
			selfDirtying = false;
		}
	}

	private static final class Region {
		private final ClientLevel level;
		private final int originX;
		private final int originY;
		private final int originZ;
		private final AtomicIntegerArray dark;

		private Region(ClientLevel level, int originX, int originY, int originZ) {
			this.level = level;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.dark = new AtomicIntegerArray(LuminanceFaultPolicy.SECTION_COUNT);
			for (int index = 0; index < LuminanceFaultPolicy.SECTION_COUNT; index++) dark.set(index, 1);
		}
	}
}
