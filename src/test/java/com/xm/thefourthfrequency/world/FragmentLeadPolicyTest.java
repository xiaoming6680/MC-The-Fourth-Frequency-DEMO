package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers behind "the terminal overheard something while you were walking around".
 *
 * <p>Leads used to be worked out once from Relay Station Zero by searching 256 chunks outward, which
 * handed the player four coordinates before they had left the building - some of them thousands of
 * blocks away, none of them related to where they actually went, and all of them the corner of a
 * starting chunk rather than the structure. They now come from the structure references already
 * recorded in the chunks loaded around the player.</p>
 */
final class FragmentLeadPolicyTest {
	/**
	 * The interval is a range, and a receiver that answers on a schedule is a status bar.
	 *
	 * <p>Bounded at both ends for opposite reasons: too short and the terminal is a radar sweeping
	 * continuously, too long and a player can cross a continent without the device ever speaking.</p>
	 */
	/**
	 * A player the terminal has nothing to say to is scanned for more often.
	 *
	 * <p>The ordinary one-to-three minutes is right for a device that occasionally notices something,
	 * and wrong for the stretch before the Nether: a sprinting player crosses hundreds of blocks
	 * between scans, so an eight-chunk window samples their route rather than sweeping it, and the
	 * whole first act can pass without a single lead.
	 */
	@Test
	void aPlayerWithNoLeadIsScannedForOnAFasterClock() {
		assertTrue(FragmentLeadPolicy.UNSERVED_MAX_SCAN_INTERVAL_TICKS
						< FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS,
				"the unserved band must be strictly faster, not merely overlapping");
		assertEquals(FragmentLeadPolicy.UNSERVED_MIN_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(0.0D, false));
		assertEquals(FragmentLeadPolicy.UNSERVED_MAX_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(1.0D, false));
		for (int step = 0; step <= 100; step++) {
			final int at = step;
			int unserved = FragmentLeadPolicy.scanIntervalTicks(step / 100.0D, false);
			int served = FragmentLeadPolicy.scanIntervalTicks(step / 100.0D, true);
			assertTrue(unserved < served, () -> "the unserved interval was not shorter at " + at);
			assertTrue(unserved >= FragmentLeadPolicy.UNSERVED_MIN_SCAN_INTERVAL_TICKS
					&& unserved <= FragmentLeadPolicy.UNSERVED_MAX_SCAN_INTERVAL_TICKS,
					() -> "unserved interval left its bounds at " + at);
		}
		// Still a receiver rather than a radar: never so fast that it reads as a status bar.
		assertTrue(FragmentLeadPolicy.UNSERVED_MIN_SCAN_INTERVAL_TICKS >= 20 * 15,
				"scanning more often than every fifteen seconds is sweeping, not listening");
	}

	@Test
	void theScanIntervalIsRandomisedBetweenTwoHonestBounds() {
		assertEquals(FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(0.0D, true));
		assertEquals(FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(1.0D, true));
		assertTrue(FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS < FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS,
				"a range with no width is a schedule");

		// Monotone and inside the bounds everywhere, including for a caller handing over a value from
		// outside 0..1 - which is what a raw random of some other range would be.
		int previous = Integer.MIN_VALUE;
		for (int step = 0; step <= 100; step++) {
			final int at = step;
			int interval = FragmentLeadPolicy.scanIntervalTicks(step / 100.0D, true);
			assertTrue(interval >= previous, "the interval must not go backwards");
			assertTrue(interval >= FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS
					&& interval <= FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS,
					() -> "interval left its bounds at " + at);
			previous = interval;
		}
		assertEquals(FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(-4.0D, true));
		assertEquals(FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS,
				FragmentLeadPolicy.scanIntervalTicks(9.0D, true));

		// Slow enough that the device reads as listening rather than sweeping: a minute at the very
		// least, and never so long that a session goes by in silence.
		assertTrue(FragmentLeadPolicy.MIN_SCAN_INTERVAL_TICKS >= 20 * 30,
				"a scan every few seconds is a radar, not a receiver");
		assertTrue(FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS <= 20 * 60 * 10,
				"a player must not be able to explore for an hour without the terminal speaking");
	}

	/**
	 * The pool is a preference with a timeout, and the timeout is what keeps the story finishable.
	 *
	 * <p>Each fragment prefers four kinds of place, which is what makes the four hidden files feel
	 * like they come from four kinds of somewhere. But the world is under no obligation to put those
	 * four anywhere near the player: an ocean start, a flat world, or an unlucky stretch of terrain
	 * can load chunks for hours without one appearing. Held strictly, that fragment's hidden file is
	 * unobtainable, and the complete journal is gated behind all four - so the save is locked out of
	 * its own ending by terrain.</p>
	 */
	@Test
	void thePoolStopsBindingOnceAFragmentHasWaitedLongEnough() {
		assertTrue(FragmentLeadPolicy.poolStillBinding(0), "a fresh fragment prefers its own groups");
		for (int scans = 0; scans < FragmentLeadPolicy.POOL_PATIENCE_SCANS; scans++) {
			final int spent = scans;
			assertTrue(FragmentLeadPolicy.poolStillBinding(scans),
					() -> "the pool gave up after " + spent + " scans, before its patience ran out");
		}
		assertFalse(FragmentLeadPolicy.poolStillBinding(FragmentLeadPolicy.POOL_PATIENCE_SCANS),
				"patience has to actually run out, or a fragment can be locked out by terrain");
		// And stay run out. A counter that keeps climbing past the threshold must not wrap back into
		// binding - the service goes on incrementing it for as long as the fragment finds nothing.
		assertFalse(FragmentLeadPolicy.poolStillBinding(FragmentLeadPolicy.POOL_PATIENCE_SCANS * 100));
		assertFalse(FragmentLeadPolicy.poolStillBinding(Integer.MAX_VALUE));

		// Long enough to be a real preference rather than a formality, short enough that a stuck
		// player is not stuck for a whole session: with the interval above, this is tens of minutes.
		assertTrue(FragmentLeadPolicy.POOL_PATIENCE_SCANS >= 3,
				"a pool that gives up after one unlucky scan is not a pool");
		long worstCaseTicks = (long) FragmentLeadPolicy.POOL_PATIENCE_SCANS
				* FragmentLeadPolicy.MAX_SCAN_INTERVAL_TICKS;
		assertTrue(worstCaseTicks <= 20L * 60L * 60L,
				() -> "waiting " + worstCaseTicks / 20 / 60 + " minutes for a fallback is a soft lock");
	}

	/** The scan may only read what is already loaded, so its reach has to fit inside a view distance. */
	@Test
	void theScanReachStaysInsideChunksThatAreAlreadyThere() {
		assertTrue(FragmentLeadPolicy.SCAN_CHUNK_RADIUS > 0);
		// The smallest view distance a server is realistically run at. Beyond it every extra chunk in
		// the sweep is one the scan will skip anyway, so the cost is real and the benefit is not.
		assertTrue(FragmentLeadPolicy.SCAN_CHUNK_RADIUS <= 10,
				"a reach past the view distance only buys chunk lookups that always miss");
		// Bounded work per scan, stated as the number of chunks touched rather than left implicit.
		int chunksTouched = (2 * FragmentLeadPolicy.SCAN_CHUNK_RADIUS + 1)
				* (2 * FragmentLeadPolicy.SCAN_CHUNK_RADIUS + 1);
		assertTrue(chunksTouched <= 441,
				() -> "a scan touching " + chunksTouched + " chunks is no longer a map lookup");
	}
}
