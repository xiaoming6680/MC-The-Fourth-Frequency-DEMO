package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.ending.EndingWorldQuarantine;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

/**
 * Durable client-local ending lock. It never changes the authoritative server outcome directly.
 *
 * <p>The lock records <em>which</em> run ended, and everything that reads it is expected to seal
 * only that. It used to be read as a single global boolean that disabled Singleplayer, Multiplayer
 * and Realms outright, which is correct for the world the ending happened in and wrong for every
 * other one: finishing the mod on a friend's server took away the player's own unrelated saves and
 * every other server they play on. The identity needed to do better was already being written here
 * - {@code levelId} for a local save, and now {@code serverAddress} for a remote one - so the
 * narrowing is entirely in the consumers. See {@code TitleScreenErosionMixin}.</p>
 */
public final class FailureMenuLockState {
	private static final String DIRECTORY_NAME = "thefourthfrequency-ending";
	private static final String LOCK_FILE_NAME = "failure-menu.lock";
	/** Lock format. 4 adds {@code serverAddress}; 1-3 are still read, and simply seal less. */
	private static final String LOCK_VERSION = "4";
	private static volatile boolean initialized;
	private static volatile boolean locked;
	private static volatile UUID encounterId;
	private static volatile String worldId = "";
	private static volatile String levelId = "";
	private static volatile String serverAddress = "";
	private static volatile WorldInterfaceProtocol.Outcome outcome = WorldInterfaceProtocol.Outcome.FAILURE;
	private static WindowSnapshot windowSnapshot;

	private FailureMenuLockState() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		initialized = true;
		readLock();
	}

	public static boolean locked() {
		if (!initialized) initialize();
		return locked;
	}

	public static UUID encounterId() {
		if (!initialized) initialize();
		return encounterId;
	}

	public static String worldId() {
		if (!initialized) initialize();
		return worldId;
	}

	public static WorldInterfaceProtocol.Outcome outcome() {
		if (!initialized) initialize();
		return outcome;
	}

	public static String levelId() {
		if (!initialized) initialize();
		return levelId;
	}

	/** The address of the server the run ended on, or empty for a local save or a pre-v4 lock. */
	public static String serverAddress() {
		if (!initialized) initialize();
		return serverAddress;
	}

	/** Whether this exact server is the one this client finished its run on. */
	public static boolean seals(String candidateAddress) {
		if (!initialized) initialize();
		return locked && !serverAddress.isBlank() && serverAddress.equalsIgnoreCase(candidateAddress);
	}

	public static synchronized boolean lock(UUID encounter) {
		return lock(encounter, WorldInterfaceProtocol.Outcome.FAILURE, "", null);
	}

	public static synchronized boolean lock(UUID encounter, Minecraft client) {
		return lock(encounter, WorldInterfaceProtocol.Outcome.FAILURE, "", client);
	}

	public static synchronized boolean lock(UUID encounter, WorldInterfaceProtocol.Outcome endingOutcome,
			String endingWorldId, Minecraft client) {
		return lock(encounter, endingOutcome, endingWorldId, client, true);
	}

	/**
	 * Writes the lock, optionally without capturing the window to restore to.
	 *
	 * <p>The two things this file holds have opposite deadlines. <b>The identity - which run ended,
	 * and where - must be written the instant the outcome is known</b>, because after that the client
	 * may not get another chance. <b>The window snapshot must be taken late</b>, after the ending's
	 * presentation window has been undone, or the lock would restore the player into the window the
	 * ending was performed in.
	 *
	 * <p>The failure path never had to choose: it undoes its window and locks on the same tick. The
	 * success path did, and resolved it the wrong way round - it deferred the whole lock behind an
	 * asynchronous resource-pack reload, so a player who left during the reload finished the mod and
	 * kept an unlocked title screen. Splitting the two lets the success path write identity
	 * immediately and come back for the snapshot once the window is its own again.
	 *
	 * @param captureWindow false to persist identity now and leave the snapshot for a later call
	 */
	public static synchronized boolean lock(UUID encounter, WorldInterfaceProtocol.Outcome endingOutcome,
			String endingWorldId, Minecraft client, boolean captureWindow) {
		initialize();
		if (endingOutcome == null || endingOutcome == WorldInterfaceProtocol.Outcome.NONE
				|| endingWorldId == null || endingWorldId.length() > 128) return false;
		String endingLevelId = captureLocalLevelId(client);
		String endingServerAddress = captureServerAddress(client);
		// The snapshot clause is what lets the second, window-capturing call through. Without it an
		// identity-only lock written moments earlier would satisfy this and the snapshot would never
		// be taken at all.
		if (locked && encounter.equals(encounterId) && endingOutcome == outcome
				&& endingWorldId.equals(worldId) && endingLevelId.equals(levelId)
				&& endingServerAddress.equals(serverAddress)
				&& (!captureWindow || windowSnapshot != null)) return true;
		Properties properties = new Properties();
		properties.setProperty("version", LOCK_VERSION);
		properties.setProperty("encounter", encounter.toString());
		properties.setProperty("worldId", endingWorldId);
		properties.setProperty("levelId", endingLevelId);
		properties.setProperty("serverAddress", endingServerAddress);
		properties.setProperty("outcome", endingOutcome.name());
		properties.setProperty("lockedAt", Long.toString(System.currentTimeMillis()));
		WindowSnapshot captured = !captureWindow || client == null || client.getWindow() == null
				? null : WindowSnapshot.capture(client);
		if (captured != null) captured.write(properties);
		if (!writeAtomically(lockPath(), properties)) return false;
		encounterId = encounter;
		worldId = endingWorldId;
		levelId = endingLevelId;
		serverAddress = endingServerAddress;
		outcome = endingOutcome;
		windowSnapshot = captured;
		locked = true;
		return true;
	}

	public static synchronized boolean stageReplayQuarantine() {
		initialize();
		if (!locked || encounterId == null || worldId.isBlank()) {
			TheFourthFrequency.LOGGER.error("Ending replay quarantine has no valid world identity");
			return false;
		}
		if (levelId.isBlank()) {
			TheFourthFrequency.LOGGER.info("Ending came from a remote world; no local save was quarantined");
			return true;
		}
		return EndingWorldQuarantine.quarantine(levelId, worldId, encounterId, outcome.name());
	}

	public static synchronized boolean unlockAfterLocalRecovery() {
		initialize();
		try {
			Files.deleteIfExists(lockPath());
			locked = false;
			encounterId = null;
			worldId = "";
			levelId = "";
			serverAddress = "";
			outcome = WorldInterfaceProtocol.Outcome.FAILURE;
			windowSnapshot = null;
			return true;
		} catch (IOException exception) {
			TheFourthFrequency.LOGGER.error("Could not clear the local failure menu lock", exception);
			return false;
		}
	}

	public static Path lockPathForTesting() {
		return lockPath();
	}

	public static synchronized void restoreWindow(Minecraft client) {
		initialize();
		if (windowSnapshot != null && client.getWindow() != null) windowSnapshot.restore(client);
	}

	private static void readLock() {
		Path path = lockPath();
		if (!Files.isRegularFile(path)) {
			locked = false;
			encounterId = null;
			worldId = "";
			levelId = "";
			serverAddress = "";
			outcome = WorldInterfaceProtocol.Outcome.FAILURE;
			windowSnapshot = null;
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			properties.load(input);
			String version = properties.getProperty("version");
			if (!"1".equals(version) && !"2".equals(version) && !"3".equals(version)
					&& !LOCK_VERSION.equals(version)) {
				throw new IOException("Unknown lock version");
			}
			encounterId = UUID.fromString(properties.getProperty("encounter", ""));
			worldId = !"1".equals(version) ? properties.getProperty("worldId", "") : "";
			if (worldId.length() > 128) throw new IOException("Invalid world id");
			levelId = "3".equals(version) || LOCK_VERSION.equals(version)
					? properties.getProperty("levelId", "") : "";
			if (!levelId.isBlank() && EndingWorldQuarantine.markerPath(levelId).isEmpty()) {
				throw new IOException("Invalid local level id");
			}
			// A lock written before version 4 never recorded which server it came from, so it seals
			// no server. That is the correct way for the narrowing to land on an existing lock: it
			// can only ever seal less than it did, never something it was not told about.
			serverAddress = LOCK_VERSION.equals(version) ? properties.getProperty("serverAddress", "") : "";
			if (serverAddress.length() > 255) throw new IOException("Invalid server address");
			outcome = !"1".equals(version)
					? WorldInterfaceProtocol.Outcome.valueOf(properties.getProperty("outcome", "FAILURE"))
					: WorldInterfaceProtocol.Outcome.FAILURE;
			if (outcome == WorldInterfaceProtocol.Outcome.NONE) throw new IOException("Invalid ending outcome");
			windowSnapshot = WindowSnapshot.read(properties);
			locked = true;
		} catch (IOException | IllegalArgumentException exception) {
			// A malformed file must fail closed: recovery is still available through F8/safe mode.
			locked = true;
			encounterId = null;
			worldId = "";
			levelId = "";
			serverAddress = "";
			outcome = WorldInterfaceProtocol.Outcome.FAILURE;
			windowSnapshot = null;
			TheFourthFrequency.LOGGER.error("Failure menu lock is damaged; keeping the client locked", exception);
		}
	}

	private static String captureLocalLevelId(Minecraft client) {
		if (client == null || !client.hasSingleplayerServer() || client.getSingleplayerServer() == null) return "";
		Path savesDirectory = FabricLoader.getInstance().getGameDir().resolve("saves")
				.toAbsolutePath().normalize();
		Path worldDirectory = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
				.toAbsolutePath().normalize();
		if (!savesDirectory.equals(worldDirectory.getParent()) || worldDirectory.getFileName() == null) {
			TheFourthFrequency.LOGGER.warn("Could not safely identify the local ending save at {}", worldDirectory);
			return "";
		}
		return worldDirectory.getFileName().toString();
	}

	/**
	 * The address of the remote server the run ended on, or empty when it did not end on one.
	 *
	 * <p>Taken from {@code ServerData} rather than from the resolved socket, because that is the
	 * string the server list itself keys on - so what gets sealed is the entry the player will
	 * actually click, in the form they typed it.</p>
	 */
	private static String captureServerAddress(Minecraft client) {
		if (client == null || client.hasSingleplayerServer()) return "";
		ServerData current = client.getCurrentServer();
		if (current == null || current.ip == null || current.ip.isBlank()) return "";
		return current.ip.length() > 255 ? "" : current.ip;
	}

	private static Path endingDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
	}

	private static Path lockPath() {
		return endingDirectory().resolve(LOCK_FILE_NAME).normalize();
	}

	private static boolean writeAtomically(Path target, Properties properties) {
		Path directory = endingDirectory();
		Path temporary = directory.resolve(LOCK_FILE_NAME + ".tmp").normalize();
		if (!target.getParent().equals(directory) || !temporary.getParent().equals(directory)) return false;
		try {
			Files.createDirectories(directory);
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				properties.store(output, "The Fourth Frequency local failure lock");
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException exception) {
			TheFourthFrequency.LOGGER.error("Could not persist the local failure menu lock", exception);
			try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
			return false;
		}
	}

	private record WindowSnapshot(boolean fullscreen, boolean maximized, int x, int y, int width, int height) {
		static WindowSnapshot capture(Minecraft client) {
			long handle = client.getWindow().handle();
			int[] x = new int[1], y = new int[1], width = new int[1], height = new int[1];
			GLFW.glfwGetWindowPos(handle, x, y);
			GLFW.glfwGetWindowSize(handle, width, height);
			return new WindowSnapshot(client.getWindow().isFullscreen(),
					GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE,
					x[0], y[0], width[0], height[0]);
		}

		void write(Properties properties) {
			properties.setProperty("window.fullscreen", Boolean.toString(fullscreen));
			properties.setProperty("window.maximized", Boolean.toString(maximized));
			properties.setProperty("window.x", Integer.toString(x));
			properties.setProperty("window.y", Integer.toString(y));
			properties.setProperty("window.width", Integer.toString(width));
			properties.setProperty("window.height", Integer.toString(height));
		}

		static WindowSnapshot read(Properties properties) {
			if (!properties.containsKey("window.width")) return null;
			try {
				return new WindowSnapshot(Boolean.parseBoolean(properties.getProperty("window.fullscreen", "false")),
						Boolean.parseBoolean(properties.getProperty("window.maximized", "false")),
						Integer.parseInt(properties.getProperty("window.x", "80")),
						Integer.parseInt(properties.getProperty("window.y", "80")),
						Math.clamp(Integer.parseInt(properties.getProperty("window.width")), 320, 16_384),
						Math.clamp(Integer.parseInt(properties.getProperty("window.height")), 240, 16_384));
			} catch (RuntimeException ignored) {
				return null;
			}
		}

		void restore(Minecraft client) {
			long handle = client.getWindow().handle();
			if (fullscreen != client.getWindow().isFullscreen()) client.getWindow().toggleFullScreen();
			if (!fullscreen) {
				GLFW.glfwRestoreWindow(handle);
				GLFW.glfwSetWindowPos(handle, x, y);
				GLFW.glfwSetWindowSize(handle, width, height);
				if (maximized) GLFW.glfwMaximizeWindow(handle);
			}
		}
	}
}
