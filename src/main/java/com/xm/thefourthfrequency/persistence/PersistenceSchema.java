package com.xm.thefourthfrequency.persistence;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class PersistenceSchema {
	public static final int CURRENT_VERSION = 13;

	private PersistenceSchema() {
	}

	/**
	 * One step per version, each stamping the version it produces.
	 *
	 * <p>Every step is the identity beyond that stamp, and always has been: the world document itself
	 * has never needed rewriting, because per-player record migration is data-driven and lives in
	 * {@code TerminalData#migrateRecord}, which reads the stamped version and fills in whatever that
	 * vintage lacks. The steps exist so the migrator can walk an old save forward one version at a
	 * time rather than jumping it.
	 *
	 * <p>Built in a loop rather than written out. It was eleven near-identical entries in a
	 * {@code Map.of}, which caps at ten pairs - so version 11 did not fit, and the failure mode was a
	 * type-inference error that named neither the cap nor the version.
	 */
	public static SchemaMigrator migrator() {
		Map<Integer, UnaryOperator<JsonObject>> steps = new HashMap<>();
		for (int from = 0; from < CURRENT_VERSION; from++) {
			int to = from + 1;
			steps.put(from, document -> {
				document.addProperty("schemaVersion", to);
				return document;
			});
		}
		return new SchemaMigrator(CURRENT_VERSION, Map.copyOf(steps));
	}
}
