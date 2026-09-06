package com.xm.thefourthfrequency.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "thefourthfrequency.json";

	private ConfigManager() {
	}

	public static synchronized ModConfig load() {
		Path path = configPath();
		if (!Files.exists(path)) {
			ModConfig defaults = ModConfig.defaults();
			writeAtomically(path, defaults);
			return defaults;
		}

		return readOrDefaults(path);
	}

	public static synchronized ModConfig.ClientState loadClientState() {
		return load().clientState();
	}

	public static synchronized boolean updateClientState(
			UnaryOperator<ModConfig.ClientState> update
	) {
		Objects.requireNonNull(update, "update");
		Path path = configPath();
		ModConfig current;
		if (!Files.exists(path)) {
			current = ModConfig.defaults();
		} else {
			try {
				current = read(path);
			} catch (IOException | JsonParseException exception) {
				TheFourthFrequency.LOGGER.error(
						"Unable to update client state because {} could not be read",
						path, exception);
				return false;
			}
		}

		ModConfig.ClientState updatedState = Objects.requireNonNull(
				update.apply(current.clientState()), "updated client state");
		return writeAtomically(path, current.withClientState(updatedState));
	}

	/**
	 * Rewrites the Meta block, leaving everything else in the file exactly as the player left it.
	 *
	 * <p>Reads the file back first rather than writing out the in-memory config, for the same reason
	 * {@link #updateClientState} does: the process-wide config was loaded at startup, and anything
	 * edited in the file since then would be silently reverted by writing that copy back.
	 */
	public static synchronized boolean updateMeta(UnaryOperator<ModConfig.Meta> update) {
		Objects.requireNonNull(update, "update");
		Path path = configPath();
		ModConfig current;
		if (!Files.exists(path)) {
			current = ModConfig.defaults();
		} else {
			try {
				current = read(path);
			} catch (IOException | JsonParseException exception) {
				TheFourthFrequency.LOGGER.error(
						"Unable to update meta settings because {} could not be read", path, exception);
				return false;
			}
		}

		ModConfig.Meta updatedMeta = Objects.requireNonNull(update.apply(current.meta()), "updated meta");
		return writeAtomically(path, current.withMeta(updatedMeta));
	}

	/** Restores only MOD progression flags; user-selected Meta and pacing settings are preserved. */
	public static synchronized boolean resetClientState() {
		// Clears the recovered fragment along with everything else. F8 means "undo what this mod holds
		// locally", and the previous run is the one piece of that state which deliberately outlives a
		// world - so it is also the one piece most obviously wrong to leave behind after a reset.
		// The developer HUD layout is not world state and is not what F8 undoes, so it is the one
		// field carried across rather than cleared.
		return updateClientState(state -> new ModConfig.ClientState(false, false,
				ModConfig.PreviousRun.none(), state.debugHudGroups()));
	}

	private static ModConfig readOrDefaults(Path path) {
		try {
			return read(path);
		} catch (IOException | JsonParseException exception) {
			TheFourthFrequency.LOGGER.error(
					"Unable to read {}; safe defaults will be used without overwriting it",
					path, exception);
			return ModConfig.defaults();
		}
	}

	private static ModConfig read(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ModConfig decoded = GSON.fromJson(reader, ModConfig.class);
			if (decoded == null) {
				throw new JsonParseException("configuration root is null");
			}
			return decoded.validated();
		}
	}

	private static boolean writeAtomically(Path path, ModConfig config) {
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException exception) {
			TheFourthFrequency.LOGGER.error("Unable to write configuration at {}", path, exception);
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException ignored) {
			}
			return false;
		}
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
