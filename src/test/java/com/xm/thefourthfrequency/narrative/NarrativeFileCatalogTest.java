package com.xm.thefourthfrequency.narrative;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NarrativeFileCatalogTest {
	/**
	 * The catalogue, in the order the terminal serves it.
	 *
	 * <p>Seven files belong to this world. The eighth is the fragment a previous playthrough left on
	 * this machine, and it is last on purpose: everything before it is content the mod ships, and it
	 * is the only entry whose body the server never sends.
	 */
	@Test
	void catalogContainsTheSevenWorldFilesAndTheRecoveredFragment() {
		assertEquals(List.of(
				"maintenance_handoff",
				"surface_shelter_record",
				"field_observation_record",
				"underground_mine_record",
				"abandoned_warehouse_record",
				"encrypted_witness_file",
				"body_mapping_warning",
				"recovered_predecessor_record"),
				NarrativeFileCatalog.definitions().stream()
						.map(NarrativeFileCatalog.Definition::id)
						.toList());
	}
}
