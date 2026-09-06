package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Locks view distance to 6/12/16 until a successful finale returns the player to the Overworld. */
public final class DimensionViewDistanceController {
	private static boolean initialized;
	private static boolean stateLoaded;
	private static boolean unlocked;
	private static boolean successfulReturnPending;
	/**
	 * What the player had set before this mod took the option away, or null while it has not.
	 *
	 * <p>Held in memory rather than in the config because it answers a question about this session:
	 * the value {@code options.txt} would have kept if the mod had never touched it. It is captured
	 * once, on the tick the override is first written, and deliberately not refreshed afterwards -
	 * every later write is this class overwriting itself, and re-reading then would record the lock
	 * as the player's own preference. Crossing into the Nether is exactly that case: the stored value
	 * is six, the new lock is twelve, and the difference is not evidence of anything the player did.
	 */
	private static Integer playerChoice;

	private DimensionViewDistanceController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		loadStateIfNeeded();
		ClientTickEvents.END_CLIENT_TICK.register(DimensionViewDistanceController::enforce);
	}

	/**
	 * Whether the render distance is currently this mod's to decide.
	 *
	 * <p>Two conditions, and the second one used to be missing. The story condition is that this run
	 * has not been won yet; the scope condition is that the player is actually in a world this mod is
	 * running in. Every consumer of this method - the per-tick enforcement below, the option's own
	 * {@code set} rejection, the greyed-out slider in Video Settings, and the fog range - inherits
	 * both from here, which is why the scope belongs in this method rather than at each call site.
	 *
	 * @see ModWorldPresence
	 */
	public static synchronized boolean isLocked() {
		loadStateIfNeeded();
		return !unlocked && ModWorldPresence.currentWorldRunsThisMod();
	}

	public static int lockedChunks(Minecraft client) {
		if (client == null || client.level == null) {
			return DimensionViewDistancePolicy.OVERWORLD_CHUNKS;
		}
		// The fall out of the unrendered layer overrides whatever the destination would otherwise
		// get. It is the only case in this mod where the lock goes up rather than down, and it lasts
		// exactly as long as the player is in the air.
		if (UnrenderedLayerClient.descending()) return DimensionViewDistancePolicy.SKY_RETURN_CHUNKS;
		return DimensionViewDistancePolicy.lockedChunks(
				client.level.dimension().identifier().toString());
	}

	public static int atmosphericChunks(String dimensionId, int vanillaChunks) {
		return isLocked() ? DimensionViewDistancePolicy.lockedChunks(dimensionId) : vanillaChunks;
	}

	public static synchronized void armUnlockAfterSuccessfulReturn() {
		loadStateIfNeeded();
		if (!unlocked) successfulReturnPending = true;
	}

	private static void enforce(Minecraft client) {
		if (client.options == null) return;
		if (successfulReturnPending && client.level != null
				&& Level.OVERWORLD.equals(client.level.dimension())) {
			completeSuccessfulReturn(client);
			return;
		}
		if (!isLocked()) {
			// Reached on the title screen and on every unrelated world, so it is also the hand-back.
			// Without it the last value written here - six chunks, in the Overworld - would simply
			// stay in the options and be saved to disk on the next quit, which is the lock outliving
			// the world that justified it by the least visible route available.
			releaseOverride(client);
			return;
		}
		int locked = lockedChunks(client);
		if (!client.options.renderDistance().get().equals(locked)) {
			if (playerChoice == null) playerChoice = client.options.renderDistance().get();
			client.options.renderDistance().set(locked);
		}
	}

	/** Hands the option back exactly once, and only if this class is what took it. */
	private static synchronized void releaseOverride(Minecraft client) {
		if (playerChoice == null) return;
		int restored = playerChoice;
		playerChoice = null;
		client.options.renderDistance().set(restored);
		client.options.save();
	}

	private static synchronized void completeSuccessfulReturn(Minecraft client) {
		if (!successfulReturnPending || client.level == null
				|| !Level.OVERWORLD.equals(client.level.dimension())) return;
		boolean persisted = ConfigManager.updateClientState(ModConfig.ClientState::unlockViewDistance);
		unlocked = true;
		successfulReturnPending = false;
		// Dropped rather than restored. Winning is not the lock being lifted back to whatever the
		// player happened to have before it; it is the world opening up, which is what sixteen says.
		playerChoice = null;
		client.options.renderDistance().set(DimensionViewDistancePolicy.SUCCESS_RETURN_CHUNKS);
		client.options.save();
		if (!persisted) {
			TheFourthFrequency.LOGGER.error(
					"View distance was unlocked for this session but could not be persisted");
		}
	}

	private static synchronized void loadStateIfNeeded() {
		if (stateLoaded) return;
		unlocked = ConfigManager.loadClientState().viewDistanceUnlocked();
		stateLoaded = true;
	}

	public static synchronized void resetForTesting(Minecraft client) {
		// Only the view-distance flag is under test here; the recovered fragment is carried through
		// untouched so a test reset cannot silently destroy a previous run.
		ConfigManager.updateClientState(state -> new ModConfig.ClientState(
				state.alphaDowngradeComplete(), false, state.previousRun(), state.debugHudGroups()));
		stateLoaded = true;
		unlocked = false;
		successfulReturnPending = false;
		playerChoice = null;
		if (client != null && client.options != null) enforce(client);
	}

	public static synchronized void resetForReplay(Minecraft client) {
		stateLoaded = true;
		unlocked = false;
		successfulReturnPending = false;
		playerChoice = null;
		if (client != null && client.options != null) enforce(client);
	}

	public static synchronized void reloadFromDiskForTesting() {
		stateLoaded = false;
		loadStateIfNeeded();
	}
}
