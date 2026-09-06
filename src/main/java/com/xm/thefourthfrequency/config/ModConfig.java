package com.xm.thefourthfrequency.config;

import java.util.List;

public record ModConfig(
		Meta meta,
		Pacing pacing,
		ClientState clientState,
		Presentation presentation
) {
	public static ModConfig defaults() {
		return new ModConfig(Meta.defaults(), Pacing.defaults(), ClientState.defaults(),
				Presentation.defaults());
	}

	public ModConfig validated() {
		return new ModConfig(
				meta == null ? Meta.defaults() : meta.validated(),
				pacing == null ? Pacing.defaults() : pacing,
				clientState == null ? ClientState.defaults() : clientState,
				presentation == null ? Presentation.defaults() : presentation.validated()
		);
	}

	public ModConfig withClientState(ClientState updatedClientState) {
		return new ModConfig(meta, pacing, updatedClientState, presentation).validated();
	}

	public ModConfig withPresentation(Presentation updatedPresentation) {
		return new ModConfig(meta, pacing, clientState, updatedPresentation).validated();
	}

	public ModConfig withMeta(Meta updatedMeta) {
		return new ModConfig(updatedMeta, pacing, clientState, presentation).validated();
	}

	/**
	 * The three impact effects, each switchable on its own.
	 *
	 * <p>Deliberately three independent settings rather than one "screen effects" toggle. They fail
	 * differently for different people: shake is the one that causes motion sickness, hit-stop is
	 * the one that feels like a stutter if you are not expecting it, and the flash is the one that
	 * matters if you are photosensitive. Bundling them would mean a player who needs one off loses
	 * the other two.
	 *
	 * <p>All three are boxed, for exactly the reason {@code Meta.bedVolume} is: Gson fills an absent
	 * primitive with 0/false, so shipping these unboxed would silently switch all three off for
	 * every player whose config file predates the field. Null means "not configured" and resolves to
	 * the default; an explicit 0 or false still means off.
	 */
	public record Presentation(
			Double cameraShake,
			Boolean hitStop,
			Boolean impactFlash
	) {
		private static final double DEFAULT_CAMERA_SHAKE = 1.0D;

		private static Presentation defaults() {
			return new Presentation(DEFAULT_CAMERA_SHAKE, true, true);
		}

		private Presentation validated() {
			return new Presentation(
					clamp(cameraShake == null ? DEFAULT_CAMERA_SHAKE : cameraShake, 0.0D, 1.0D),
					hitStop == null || hitStop,
					impactFlash == null || impactFlash);
		}

		/**
		 * Shake strength as a 0..1 scale rather than a boolean.
		 *
		 * <p>A player who finds the shake nauseating usually wants it toned down, not removed - the
		 * feedback is doing real work telling them a hit landed. 0.0 is still a full off switch.
		 */
		public double effectiveCameraShake() {
			return clamp(cameraShake == null ? DEFAULT_CAMERA_SHAKE : cameraShake, 0.0D, 1.0D);
		}

		public boolean hitStopEnabled() {
			return hitStop == null || hitStop;
		}

		public boolean impactFlashEnabled() {
			return impactFlash == null || impactFlash;
		}
	}

	public record Meta(
			boolean enabled,
			/**
			 * The one trim over everything this mod plays: authored cues server-side and client-side,
			 * the signal beds through {@link #effectiveBedVolume()}, and the authored score through
			 * {@code MusicDirector.musicVolume}. Vanilla's own sliders are untouched and still apply
			 * on top, which is what makes this safe to expose as "mod volume" rather than "volume".
			 *
			 * <p>Set from the first-run audio page as well as from the file, so it is the one number
			 * a player who finds the mod too loud can reach before hearing any of it.
			 */
			double peakVolume,
			/**
			 * Boxed on purpose. Gson fills an absent primitive with 0.0, so shipping this as a
			 * {@code double} would have silently muted the beds for everyone who already has a
			 * config file written before the field existed. Null means "not configured" and
			 * resolves to {@link #DEFAULT_BED_VOLUME}; an explicit 0 still means silence.
			 */
			Double bedVolume,
			/**
			 * Whether the world interface may throw a player off the server outright.
			 *
			 * <p>Forced eviction treats the connection itself as the attack, which is the point of it -
			 * but the cost is not the same everywhere. On an integrated server the host is exempt
			 * because ending their own process would end everyone's game; on a dedicated server there
			 * is no single owner to exempt, so it can land on anybody including the operator, and
			 * "reconnect" can mean a queue, a whitelist check or a proxy that does not want to be
			 * hammered. Nothing is lost when it is off: the encounter has seven other actions, and
			 * whatever a target was holding is restored on rejoin either way.
			 *
			 * <p>Boxed for the same reason {@link #bedVolume} is - an absent primitive would read as
			 * {@code false} and silently disable the action for every existing config file.
			 */
			Boolean forcedEviction
	) {
		private static final double DEFAULT_BED_VOLUME = 1.0D;
		private static final boolean DEFAULT_FORCED_EVICTION = true;

		private static Meta defaults() {
			return new Meta(true, 0.8D, DEFAULT_BED_VOLUME, DEFAULT_FORCED_EVICTION);
		}

		private Meta validated() {
			return new Meta(enabled, clamp(peakVolume, 0.0D, 1.0D),
					clamp(bedVolume == null ? DEFAULT_BED_VOLUME : bedVolume, 0.0D, 1.0D),
					forcedEviction == null ? DEFAULT_FORCED_EVICTION : forcedEviction);
		}

		/** Leaves every other Meta choice - including the separate bed trim - exactly where it was. */
		public Meta withPeakVolume(double updatedPeakVolume) {
			return new Meta(enabled, updatedPeakVolume, bedVolume, forcedEviction);
		}

		/** Resolved with its default, so callers never have to know the field is nullable. */
		public boolean forcedEvictionEnabled() {
			return forcedEviction == null ? DEFAULT_FORCED_EVICTION : forcedEviction;
		}

		/**
		 * A separate trim for the continuous signal beds, multiplied on top of {@link #peakVolume}.
		 *
		 * <p>The beds run on MASTER so the silent_world anomaly cannot mute them along with the
		 * world, which is the right call for the fiction but leaves a player with no way to turn
		 * down a permanent hiss: the game's own MASTER slider takes everything with it, and
		 * peakVolume trims every other cue this mod owns. This is the one knob that reaches the
		 * beds and nothing else.</p>
		 */
		public double effectiveBedVolume() {
			return clamp(peakVolume, 0.0D, 1.0D)
					* clamp(bedVolume == null ? DEFAULT_BED_VOLUME : bedVolume, 0.0D, 1.0D);
		}
	}

	public record Pacing(boolean developerAcceleration) {
		private static Pacing defaults() {
			return new Pacing(false);
		}
	}

	public record ClientState(
			boolean alphaDowngradeComplete,
			boolean viewDistanceUnlocked,
			PreviousRun previousRun,
			Integer debugHudGroups
	) {
		/**
		 * Which groups the developer HUD draws, one bit per group, all on by default.
		 *
		 * <p>A bitmask rather than a field per group so that adding a fifth readout later is not a
		 * fourth positional argument on a record every caller constructs. It lives in the shipped
		 * client state because this mod has exactly one config file and inventing a second one for
		 * four bits would be the larger mess; nothing reads it unless the personal debug flag is on,
		 * so a normal player carries the default and never sees it act.</p>
		 */
		public static final int ALL_DEBUG_HUD_GROUPS = 0b1111;

		public ClientState {
			previousRun = previousRun == null ? PreviousRun.none() : previousRun.validated();
		}

		/**
		 * The mask to draw with, resolving both a config written before this field existed and a
		 * hand-edited value with bits nothing owns. Absent means all on: a developer who has never
		 * touched this should get the whole readout, not a blank HUD they have to go and enable.
		 */
		public int debugHudGroupMask() {
			return debugHudGroups == null ? ALL_DEBUG_HUD_GROUPS : debugHudGroups & ALL_DEBUG_HUD_GROUPS;
		}

		private static ClientState defaults() {
			return new ClientState(false, false, PreviousRun.none(), ALL_DEBUG_HUD_GROUPS);
		}

		public ClientState completeAlphaDowngrade() {
			return new ClientState(true, viewDistanceUnlocked, previousRun, debugHudGroups);
		}

		public ClientState unlockViewDistance() {
			return new ClientState(alphaDowngradeComplete, true, previousRun, debugHudGroups);
		}

		public ClientState withPreviousRun(PreviousRun run) {
			return new ClientState(alphaDowngradeComplete, viewDistanceUnlocked, run, debugHudGroups);
		}

		public ClientState withDebugHudGroups(int groups) {
			return new ClientState(alphaDowngradeComplete, viewDistanceUnlocked, previousRun, groups);
		}
	}

	/**
	 * What the last finished playthrough left behind, for the fragment the next one recovers.
	 *
	 * <p>This is the only piece of mod state that is deliberately allowed to outlive a world, and it
	 * is kept to the few facts a person would actually have written down: how it ended, how much of
	 * the ground they held, how long they lasted, and the answers they gave a terminal on their first
	 * day. Nothing here is read by any rule - it is narrative, and only narrative, which is what makes
	 * it safe for it to persist at all.
	 *
	 * <p>It lives in the mod's own config file, so {@code F8} removing it is the same operation as
	 * {@code F8} removing everything else the mod holds locally. A player who resets has no previous
	 * run, which is the correct and truthful outcome rather than a special case.
	 */
	public record PreviousRun(
			/** {@code "none"}, {@code "success"} or {@code "failure"}, as the ending serialises it. */
			String outcome,
			/** Anchors the party destroyed, 0-10. The poem already counts these. */
			int destroyedAnchors,
			/** World day the ending fell on, so the fragment can say how long its author lasted. */
			long day,
			/** The author's first-boot answers, unanswered entries included. */
			List<Integer> profileAnswers
	) {
		public PreviousRun {
			outcome = outcome == null || outcome.isBlank() ? "none" : outcome;
			if (!outcome.equals("none") && !outcome.equals("success") && !outcome.equals("failure")) {
				outcome = "none";
			}
			destroyedAnchors = Math.clamp(destroyedAnchors, 0, 10);
			day = Math.clamp(day, 0L, 100_000_000L);
			profileAnswers = profileAnswers == null ? List.of()
					: List.copyOf(profileAnswers.subList(0, Math.min(profileAnswers.size(), 16)));
		}

		public static PreviousRun none() {
			return new PreviousRun("none", 0, 0L, List.of());
		}

		private PreviousRun validated() {
			return new PreviousRun(outcome, destroyedAnchors, day, profileAnswers);
		}

		/** Whether there is anything here worth recovering. */
		public boolean present() {
			return !outcome.equals("none");
		}
	}

	private static double clamp(double value, double minimum, double maximum) {
		if (!Double.isFinite(value)) {
			return maximum;
		}
		return Math.max(minimum, Math.min(maximum, value));
	}
}
