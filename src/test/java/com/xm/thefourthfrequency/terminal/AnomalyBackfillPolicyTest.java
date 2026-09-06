package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnomalyBackfillPolicyTest {
	/**
	 * The quarantined store has to outlast a whole playthrough.
	 *
	 * <p>The backfill's entire claim is that nothing was missed. A store sized for a rolling recent
	 * buffer would drop the stage 1 entries first - the quiet, deniable ones - which is exactly the
	 * half that makes the list land when it finally opens.
	 */
	@Test
	void theStoreHoldsAFullRunNotARollingWindow() {
		assertTrue(AnomalyBackfillPolicy.MAX_ENTRIES >= 120,
				"a player who reaches the End has usually cleared eighty-plus anomalies");
	}

	/** Every catalogue anomaly is written; nothing else is. */
	@Test
	void onlyRealAnomaliesAreRetained() {
		for (AnomalyDefinition anomaly : AnomalyCatalog.definitions()) {
			assertTrue(AnomalyBackfillPolicy.retained(anomaly.id()), anomaly.id());
		}
		assertFalse(AnomalyBackfillPolicy.retained(null));
		assertFalse(AnomalyBackfillPolicy.retained(""));
		assertFalse(AnomalyBackfillPolicy.retained("   "));
		assertFalse(AnomalyBackfillPolicy.retained("weather_changed"));
		assertFalse(AnomalyBackfillPolicy.retained("pursuit_warning_1"));
	}

	/**
	 * Writing and showing are separate, and they have to stay separate.
	 *
	 * <p>Anomalies are recorded from the first one onward and read by nobody until the latch flips.
	 * If the write ever became conditional on the latch, the list would open on an empty page and the
	 * whole moment would be spent on nothing.
	 */
	@Test
	void theStoreFillsLongBeforeAnythingReadsIt() {
		assertTrue(AnomalyBackfillPolicy.retained(AnomalyCatalog.definitions().getFirst().id()));
		assertFalse(AnomalyBackfillPolicy.released(false));
		assertTrue(AnomalyBackfillPolicy.released(true));
	}

	/** One bearing opens it, and it never re-opens. */
	@Test
	void theLatchIsOneWay() {
		assertFalse(AnomalyBackfillPolicy.shouldRelease(false, 0));
		assertTrue(AnomalyBackfillPolicy.shouldRelease(false, 1));
		assertTrue(AnomalyBackfillPolicy.shouldRelease(false, 3));
		assertFalse(AnomalyBackfillPolicy.shouldRelease(true, 1),
				"a set latch must never fire the release again");
		assertFalse(AnomalyBackfillPolicy.shouldRelease(true, 0));
	}

	/** Sanity: the catalogue is the twenty the documentation claims. */
	@Test
	void theCatalogueIsTheDocumentedSize() {
		assertEquals(18, AnomalyCatalog.definitions().size());
	}
}
