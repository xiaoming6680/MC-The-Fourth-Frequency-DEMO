package com.xm.thefourthfrequency.audio;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a background playlist play everything it has before it plays anything twice.
 *
 * <p>Minecraft does not have playlists. An event with several {@code sounds} entries is a weighted
 * random pool that is drawn from independently every time the event is played, so with ten game
 * tracks a listener hears some of them repeatedly and others not at all, and roughly one handover
 * in ten puts the track that just finished straight back on.
 *
 * <h2>This used to be an adjacency rule, and that was not enough</h2>
 *
 * <p>The first version of this class forbade only "the same track twice in a row", and argued in
 * this comment that a full shuffle bag was the wrong trade because it makes the order predictable
 * towards the end of each pass. That reasoning holds for a pool being drawn a hundred times an hour.
 * It does not hold for a score: ten tracks over a session is not a distribution the player
 * perceives statistically, it is a list they notice they have not finished. Being handed the same
 * three pieces while the rest never surface is the complaint, and adjacency does nothing about it.
 *
 * <p>So the rule is now a pass: a track that has played is out until every other track in its pool
 * has played too, at which point the pass resets and all of them are eligible again. The old
 * adjacency rule survives inside that as the tie-break at the seam - the first track of a new pass
 * is still not allowed to be the last track of the old one, which is the one repeat that would
 * otherwise be audible as a repeat.
 *
 * <h2>Ending a pass is counted, not guessed</h2>
 *
 * <p>A pass ends when {@link #passComplete} says the pool is exhausted, which it decides by
 * comparing the pass against the pool's actual size. The first version instead inferred it from a
 * run of failed re-draws, and that inference was only ever probable: it missed the last unplayed
 * track of a seven-track pass about seven times in a thousand, and of a ten-track pass about one
 * pass in thirty. Every miss silently dropped a track the player had not heard - the exact failure
 * this class was written to remove, reintroduced by the mechanism meant to remove it.
 *
 * <h2>Why only some events</h2>
 *
 * <p>Pools are the right behaviour nearly everywhere else. The encounter's eight attack cues each
 * hold three variants precisely so that the same attack does not sound identical twice, and there
 * forbidding a repeat would be forbidding the pool from doing its job. Only the background score is
 * listened to as a sequence of whole pieces, so only the background score rotates.
 *
 * <p>Single-track events are excluded for free: the pursuit, the End, the three encounter phases
 * and the two endings each hold exactly one sound, and "do not repeat" is meaningless for a pool of
 * one. They
 * are not listed below, and {@code MusicRotationPolicyTest} checks that the listing and the actual
 * pool sizes in {@code sounds.json} agree in both directions - a new multi-track score event that
 * forgets to rotate is a test failure rather than something noticed months later by ear.
 */
public final class MusicRotationPolicy {
	/**
	 * Score events whose pool holds more than one track.
	 *
	 * <p>Paths rather than {@code SoundEvent} constants: this is read from a mixin that runs on the
	 * sound thread during resource resolution, well before anything should be touching registries.
	 */
	private static final Set<String> ROTATING = Set.of("music_game", "music_menu");

	/**
	 * Re-draws allowed <em>per track in the pool</em> while looking for one this pass has not used.
	 *
	 * <p>Scaled by pool size rather than fixed, because the hard case scales with it: finding the
	 * <em>last</em> unplayed track of an N-track pass rejects (N-1) draws in N, so a ceiling that
	 * was generous at seven tracks is thin at ten and useless at fifty. Thirty-two per track puts
	 * the chance of failing to find it below one in a trillion at ten tracks, and keeps that margin
	 * as the playlist grows.
	 *
	 * <p>Affordable because a draw is a pick from an in-memory list, not I/O - a few hundred of them
	 * happen once every few minutes, between two songs. The ceiling exists only so that a pool whose
	 * weights make one entry overwhelmingly likely cannot spin here: a repeat is a blemish, a hang
	 * would be a fault.
	 *
	 * <p>Crucially, exhausting it is no longer read as "the pass is over" - see
	 * {@link #passComplete}. Running out now costs one repeated track and leaves the pass standing,
	 * rather than discarding whatever was still unplayed in it.
	 */
	public static final int REROLLS_PER_TRACK = 32;

	/**
	 * Absolute ceiling on a single search, whatever the pool size multiplies out to.
	 *
	 * <p>Nothing shipped comes near it - ten tracks ask for 320 - and it is here because the pool is
	 * not entirely ours: a resource pack may redefine {@code thefourthfrequency:music_game} with as
	 * many entries as it likes, and "scaled by pool size" would then scale into a stall on the sound
	 * thread. Reaching this costs one repeated track, which is the same price every other ceiling in
	 * this class pays.
	 */
	public static final int MAX_REROLL_CEILING = 4_096;

	/** Draws allowed while hunting for an unplayed track in a pool of this size. */
	public static int rerollCeiling(int poolSize) {
		return Math.min(REROLLS_PER_TRACK * Math.max(poolSize, 1), MAX_REROLL_CEILING);
	}

	/**
	 * Re-draws allowed at the seam of a pass, where the only thing still being avoided is adjacency.
	 *
	 * <p>By this point every track in the pool has played, so the only bad pick is the single one
	 * that just finished. Thirty-two rather than a handful because the odds here are set by pool
	 * size too: at two tracks, eight draws still land on the wrong one about four times in a
	 * thousand.
	 */
	public static final int SEAM_REROLLS = 32;

	private static final Map<String, Set<String>> PLAYED_THIS_PASS = new ConcurrentHashMap<>();
	private static final Map<String, String> LAST_PLAYED = new ConcurrentHashMap<>();

	private MusicRotationPolicy() {
	}

	/** The score events this policy governs. */
	public static Set<String> rotatingEvents() {
		return ROTATING;
	}

	/** Whether {@code eventPath} is a score event that must rotate rather than draw freely. */
	public static boolean rotates(String eventPath) {
		return eventPath != null && ROTATING.contains(eventPath);
	}

	/** Whether this track has already been heard in the pass currently in progress. */
	public static boolean playedThisPass(String eventPath, String track) {
		if (eventPath == null || track == null) return false;
		Set<String> pass = PLAYED_THIS_PASS.get(eventPath);
		return pass != null && pass.contains(track);
	}

	/** Whether this draw would put the track that just finished straight back on. */
	public static boolean followsItself(String eventPath, String track) {
		return track != null && track.equals(LAST_PLAYED.get(eventPath));
	}

	/** Records a track as heard, keeping the pass in progress. */
	public static void remember(String eventPath, String track) {
		if (eventPath == null || track == null) return;
		PLAYED_THIS_PASS.computeIfAbsent(eventPath, key -> ConcurrentHashMap.newKeySet()).add(track);
		LAST_PLAYED.put(eventPath, track);
	}

	/**
	 * Ends the pass and opens a new one containing only {@code track}.
	 *
	 * <p>Called once {@link #passComplete} has counted the pass out against the pool's real size.
	 *
	 * <p><b>This comment used to argue the opposite</b>, and the argument is preserved here because
	 * it is the one worth not repeating: it said the class deliberately did not know how big the
	 * pool was, and that "every re-draw came back as something already heard" meant the same thing.
	 * It does not. That inference is only ever probable - it missed the last unplayed track of a
	 * ten-track pass about one pass in thirty - and every miss dropped a track the listener had not
	 * heard, which is the exact fault this class exists to remove. The pool size now arrives with
	 * the pool, through {@code WeighedSoundEventsPoolAccessor}, and this method only records the
	 * decision somebody else counted.
	 */
	public static void startNewPass(String eventPath, String track) {
		if (eventPath == null || track == null) return;
		Set<String> pass = ConcurrentHashMap.newKeySet();
		pass.add(track);
		PLAYED_THIS_PASS.put(eventPath, pass);
		LAST_PLAYED.put(eventPath, track);
	}

	/**
	 * Whether the pass in progress has used everything the pool has to offer.
	 *
	 * <p>This is the question that ends a pass, and it is answered by counting rather than by
	 * guessing. The rotation used to infer it from a run of failed re-draws - "nothing unplayed came
	 * back, so there must be nothing unplayed" - which is only ever probable. At seven tracks it was
	 * wrong about seven times in a thousand; at ten it was wrong about one pass in thirty, and each
	 * time it was wrong it threw away a track the player had not heard yet. That is precisely the
	 * complaint this class exists to answer, so the guess had to go.
	 *
	 * <p>{@code poolSize} comes from the pool itself, read through {@code
	 * WeighedSoundEventsPoolAccessor} at the one point where the pool is in hand. Compared with
	 * {@code >=} rather than {@code ==} so that a pool which shrinks underneath a pass - a resource
	 * pack swapping the score out mid-session - ends that pass instead of stranding it forever.
	 *
	 * @param poolSize how many entries the event's pool holds; a non-positive value is treated as
	 *                 "unknown", which keeps the pass open rather than ending it on no evidence
	 */
	public static boolean passComplete(String eventPath, int poolSize) {
		return poolSize > 0 && passSize(eventPath) >= poolSize;
	}

	/** How many distinct tracks the pass in progress has used. Test and debug seam. */
	public static int passSize(String eventPath) {
		Set<String> pass = PLAYED_THIS_PASS.get(eventPath);
		return pass == null ? 0 : pass.size();
	}

	/** Forgets every event's history. Test seam; nothing in production needs to reset this. */
	public static void forgetAll() {
		PLAYED_THIS_PASS.clear();
		LAST_PLAYED.clear();
	}
}
