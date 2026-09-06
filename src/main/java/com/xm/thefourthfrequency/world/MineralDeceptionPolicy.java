package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.terminal.TerminalResource;

/**
 * The one time the mineral probe reports something that is not there.
 *
 * <p>Every other instrument in this terminal fails visibly. The weather tool goes to "读数无法解析"
 * and stays there, and that blank readout is the best picture the mod has of a device losing the
 * sky - it is deliberately not touched. This probe does the opposite exactly once: it answers in the
 * ordinary format, with an ordinary bearing and an ordinary distance band, and there is nothing
 * there.
 *
 * <h2>Why this instrument</h2>
 *
 * <p>Its own summary line, sitting on the page the whole run, reads <em>只报告真实听见的矿</em>. The
 * lie turns a promise the player has read a hundred times into a false statement, which is the
 * mod's thesis in one readout and costs nothing but dug blocks - no death, no lost progress, and
 * therefore no grounds to call it unfair.
 *
 * <h2>Why so late</h2>
 *
 * <p>Nothing before tier four has established that the terminal can be made to lie, so a forged
 * reading earlier reads as a bug rather than as an event. By tier four the interface layer is
 * already in play, and the Interface Corrector - which forges coordinates, text and direction -
 * is what the player will connect this to on their own. The terminal never draws that connection;
 * it is not allowed to explain itself here.
 *
 * <h2>The trace is not optional</h2>
 *
 * <p>A wrong value with no trace is a bug. The caller must file the contradiction line in Records
 * in the same operation, and that line must not be marked unread - a terminal that points at its
 * own lie has told the player rather than let them find it.
 *
 * <p>Pure and common-side: no Minecraft types, no world access, no randomness of its own.
 */
public final class MineralDeceptionPolicy {
	/**
	 * Anomaly tier from which a forged reading may happen at all.
	 *
	 * <p>Four rather than three. Three is where the interface layer starts being visible; four is
	 * where the player has lived with it long enough that a clean, well-formatted, wrong answer reads
	 * as something done to the device rather than as the device being broken.
	 */
	public static final int MIN_ANOMALY_TIER = 4;

	/** Compass points the probe can name, matching the eight the honest bearing snaps to. */
	public static final int OCTANTS = 8;

	private static final int MIN_DISTANCE = 24;
	private static final int MAX_DISTANCE = 72;

	/**
	 * What a forged reading is made of.
	 *
	 * <p>Distance rather than a min/max pair, because {@link MineralSurveyPolicy#bandMinimum} and
	 * {@link MineralSurveyPolicy#bandMaximum} derive both from it. Storing the pair would let the
	 * forged band drift out of the shape every honest band has, and a band the player could tell
	 * apart by its width would give the lie away before they dug.
	 */
	public record Forgery(TerminalResource resource, int octant, int distance) {
		public int bandMinimum() {
			return MineralSurveyPolicy.bandMinimum(distance);
		}

		public int bandMaximum() {
			return MineralSurveyPolicy.bandMaximum(distance);
		}

		public MineralSurveyPolicy.Bearing bearing() {
			double angle = octant * (Math.PI / 4.0D);
			return MineralSurveyPolicy.quantizeBearing(
					(int) Math.round(Math.cos(angle) * 1000.0D),
					(int) Math.round(Math.sin(angle) * 1000.0D));
		}
	}

	private MineralDeceptionPolicy() {
	}

	/**
	 * Whether this probe result may be replaced by a forgery.
	 *
	 * <p>{@code emptyResult} is required: the probe only lies where it would otherwise have said
	 * "范围内无读数". Overwriting a real hit would take something away from the player, and would also
	 * make the trace incoherent - the contradiction line's whole content is that the log says nothing
	 * was found.
	 */
	public static boolean eligible(int anomalyTier, boolean alreadyForged, boolean emptyResult) {
		return emptyResult && !alreadyForged && anomalyTier >= MIN_ANOMALY_TIER;
	}

	/**
	 * Builds the forgery from a caller-supplied seed, so the decision is reproducible in tests and
	 * the policy owns no randomness.
	 */
	public static Forgery forge(long seed) {
		long mixed = seed * 0x9E3779B97F4A7C15L;
		int octant = (int) Math.floorMod(mixed >> 17, OCTANTS);
		int span = MAX_DISTANCE - MIN_DISTANCE + 1;
		int distance = MIN_DISTANCE + (int) Math.floorMod(mixed >> 33, span);
		return new Forgery(resourceFor(mixed), octant, distance);
	}

	/**
	 * Only the two the player would walk for.
	 *
	 * <p>A forged coal seam costs nobody a journey, so it would be a lie with no consequence and
	 * nothing to remember. The reading has to be worth forty blocks of tunnel or the whole event
	 * passes unnoticed.
	 */
	private static TerminalResource resourceFor(long mixed) {
		return Math.floorMod(mixed >> 49, 2) == 0 ? TerminalResource.DIAMOND : TerminalResource.EMERALD;
	}

	/** Packs the bearing and distance into the one spare integer a records line carries. */
	public static int packTrace(int octant, int distance) {
		return Math.floorMod(octant, OCTANTS) * 1000 + Math.clamp(distance, 0, 999);
	}

	public static int unpackOctant(int packed) {
		return Math.floorMod(Math.max(0, packed) / 1000, OCTANTS);
	}

	public static int unpackDistance(int packed) {
		return Math.max(0, packed) % 1000;
	}
}
