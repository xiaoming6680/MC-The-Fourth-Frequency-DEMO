package com.xm.thefourthfrequency.bootstrap;

import com.xm.thefourthfrequency.config.ModConfig;
import com.xm.thefourthfrequency.persistence.PersistenceSchema;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeServices {
	public static final int PERSISTENCE_SCHEMA_VERSION = PersistenceSchema.CURRENT_VERSION;
	private static final AtomicReference<ModConfig> CONFIG = new AtomicReference<>();

	private RuntimeServices() {
	}

	public static void initialize(ModConfig config) {
		Objects.requireNonNull(config, "config");
		if (!CONFIG.compareAndSet(null, config)) {
			throw new IllegalStateException("Runtime services were initialized more than once");
		}
	}

	/**
	 * Swaps the live configuration for one derived from it.
	 *
	 * <p>Exists for the settings a player is allowed to change while the game is running - today that
	 * is the mod volume on the first-run audio page, which would be a slider you could not hear if
	 * every reader kept the copy loaded at startup. Deliberately narrow: the reference is replaced
	 * whole, so a reader either sees the old config or the new one and never a half-written record.
	 *
	 * <p>Initialization order still holds - this refuses to run before {@link #initialize}, so it can
	 * never be the thing that supplies the first config.
	 */
	public static void updateConfig(java.util.function.UnaryOperator<ModConfig> update) {
		Objects.requireNonNull(update, "update");
		ModConfig updated = Objects.requireNonNull(update.apply(config()), "updated config");
		CONFIG.set(updated);
	}

	public static ModConfig config() {
		ModConfig config = CONFIG.get();
		if (config == null) {
			throw new IllegalStateException("Runtime services are not initialized");
		}
		return config;
	}
}

