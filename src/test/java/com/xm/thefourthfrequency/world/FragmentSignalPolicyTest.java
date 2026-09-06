package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides whether the receiver answers where the player is standing.
 *
 * <p>The case this file was written for is the one that was reported as "the fragment does not
 * work": a player follows the terminal to a marked abandoned mineshaft, digs down, comes out in a
 * mineshaft - and nothing happens. There is no message, no partial reading, and nothing to try
 * differently, so from the outside the whole mechanic looks broken.</p>
 *
 * <p>Every coordinate below is horizontal and relative to the marked point, which is what
 * {@code findNearestMapStructure} returns: the corner of the chunk the structure <em>started</em> in,
 * with Y pinned to 0. That is a place to walk to, not a description of where the structure is.</p>
 */
final class FragmentSignalPolicyTest {
	/**
	 * The reported failure: the player is in a mineshaft, at the marked place, and gets nothing.
	 *
	 * <p>Underground there is no way to tell one abandoned mineshaft from the next, and they overlap:
	 * the corridors of the marked one run hundreds of blocks out through the stone, and the
	 * neighbouring start's corridors run back the other way. The box below is a mineshaft whose
	 * nearest corridor is 120 blocks from the mark - a two-minute walk underground, and closer than
	 * the marked mineshaft's own far end.</p>
	 *
	 * <p>The old rule grew the box by 96 blocks per axis and asked whether the mark landed inside, so
	 * this answered no. 96 is smaller than the structures being matched, which is what made it
	 * possible to be demonstrably in the right place and still be refused.</p>
	 */
	@Test
	void aMineshaftReachedAtTheMarkedPlaceAnswersEvenWhenItIsNotTheMarkedStart() {
		assertTrue(FragmentSignalPolicy.answersCandidate(0, 0, 120, -40, 260, 90),
				"a mineshaft 120 blocks from the mark is the mineshaft the player was sent to");
		// And the marked start itself, whose box simply contains the mark.
		assertTrue(FragmentSignalPolicy.answersCandidate(0, 0, -64, -48, 176, 208),
				"the structure the mark came from must always answer");
		// A structure the mark sits just outside, which is the ordinary case for anything with a
		// footprint smaller than a chunk - a ruined portal, an igloo.
		assertTrue(FragmentSignalPolicy.answersCandidate(0, 0, 3, 6, 14, 19));
	}

	/**
	 * Being generous has an end.
	 *
	 * <p>The range is not "any structure of the right kind": walking into an unrelated mineshaft half
	 * a kilometre away must not hand over a fragment the player never went to find.</p>
	 */
	@Test
	void aStructureNowhereNearTheMarkStillAnswersNothing() {
		assertFalse(FragmentSignalPolicy.answersCandidate(0, 0, 500, -40, 640, 90));
		assertFalse(FragmentSignalPolicy.answersCandidate(0, 0, -900, -900, -700, -700));
		assertFalse(FragmentSignalPolicy.withinSignalRange(0, 0, 400, 300),
				"500 blocks out is not near the mark");
	}

	/**
	 * The range is a distance, not a grown rectangle.
	 *
	 * <p>The rule it replaces tested each axis on its own, so a structure 136 blocks away on the
	 * diagonal was accepted while one 97 blocks away straight ahead was refused. That made "how close
	 * counts as close" depend on which way the player happened to approach, which is not a rule
	 * anybody could have followed.</p>
	 */
	@Test
	void theRangeIsTheSameInEveryDirection() {
		int range = FragmentSignalPolicy.SIGNAL_RANGE_BLOCKS;
		// Straight out along each axis, exactly at the limit: in, in every direction.
		for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			int x = step[0] * range;
			int z = step[1] * range;
			assertTrue(FragmentSignalPolicy.answersCandidate(0, 0, x, z, x, z),
					() -> "the limit must be the same on every axis, failed at " + x + "," + z);
			assertTrue(FragmentSignalPolicy.withinSignalRange(0, 0, x, z),
					() -> "the pre-filter must not be tighter than the rule, failed at " + x + "," + z);
		}
		// One block further out, and it is out - on every axis.
		for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			int x = step[0] * (range + 1);
			int z = step[1] * (range + 1);
			assertFalse(FragmentSignalPolicy.answersCandidate(0, 0, x, z, x, z));
			assertFalse(FragmentSignalPolicy.withinSignalRange(0, 0, x, z));
		}
		// The diagonal is not a free extension: a corner at the axis limit on both axes is
		// range * sqrt(2) away and must be refused.
		assertFalse(FragmentSignalPolicy.answersCandidate(0, 0, range, range, range + 64, range + 64),
				"a rectangle grown per axis is what let a far corner in");
		assertFalse(FragmentSignalPolicy.withinSignalRange(0, 0, range, range));
		// Just inside the circle on the diagonal, so the two directions really are one radius.
		int diagonal = (int) Math.floor(range / Math.sqrt(2.0D));
		assertTrue(FragmentSignalPolicy.answersCandidate(0, 0, diagonal, diagonal, diagonal, diagonal));
		assertTrue(FragmentSignalPolicy.withinSignalRange(0, 0, diagonal, diagonal));
	}

	/**
	 * Far from the world origin the arithmetic must not overflow.
	 *
	 * <p>Coordinates run to thirty million, and a squared difference between two of them does not fit
	 * in an int. A rule that silently wraps there would answer at random for anyone who built far out.
	 * </p>
	 */
	@Test
	void theRuleSurvivesTheEdgeOfTheWorld() {
		int far = 29_000_000;
		assertTrue(FragmentSignalPolicy.withinSignalRange(far, far, far + 10, far - 10));
		assertFalse(FragmentSignalPolicy.withinSignalRange(far, far, -far, -far));
		assertTrue(FragmentSignalPolicy.answersCandidate(far, -far, far - 100, -far - 100,
				far + 100, -far + 100));
		assertFalse(FragmentSignalPolicy.answersCandidate(far, far, -far, -far, -far + 200, -far + 200));
	}
}
