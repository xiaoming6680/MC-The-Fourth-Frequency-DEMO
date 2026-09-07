package com.xm.thefourthfrequency.audio;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
	public static final SoundEvent TERMINAL_RAISE = register("terminal_raise");
	public static final SoundEvent TERMINAL_LOWER = register("terminal_lower");
	public static final SoundEvent LOCK_SEARCH = register("lock_search");
	public static final SoundEvent DISPOSSESS = register("dispossess");
	public static final SoundEvent WATCHER_VANISH = register("watcher_vanish", 64.0F);
	public static final SoundEvent HIM_PRESENCE = register("him_presence", 32.0F);
	public static final SoundEvent BACTERIA_SKITTER = register("bacteria_skitter", 18.0F);
	public static final SoundEvent REWORK_STEP_1 = register("rework_step_1", 32.0F);
	public static final SoundEvent REWORK_STEP_2 = register("rework_step_2", 32.0F);
	public static final SoundEvent REWORK_STEP_3 = register("rework_step_3", 40.0F);
	public static final SoundEvent REWORK_BREATH_1 = register("rework_breath_1", 24.0F);
	public static final SoundEvent REWORK_BREATH_2 = register("rework_breath_2", 24.0F);
	public static final SoundEvent REWORK_BREATH_3 = register("rework_breath_3", 32.0F);
	public static final SoundEvent REWORK_HURT = register("rework_hurt", 32.0F);
	public static final SoundEvent REWORK_DEATH = register("rework_death", 40.0F);
	public static final SoundEvent WORLD_INTERFACE_LASER_LOOP = register("world_interface_laser_loop", 96.0F);
	public static final SoundEvent WORLD_INTERFACE_LASER_RELEASE = register("world_interface_laser_release", 96.0F);
	public static final SoundEvent WORLD_INTERFACE_LASER_IMPACT = register("world_interface_laser_impact", 72.0F);
	public static final SoundEvent WORLD_INTERFACE_BLAST = register("world_interface_blast", 72.0F);
	public static final SoundEvent WORLD_INTERFACE_TENDRIL = register("world_interface_tendril", 64.0F);
	public static final SoundEvent WORLD_INTERFACE_TENDRIL_STRIKE = register("world_interface_tendril_strike", 96.0F);
	public static final SoundEvent WORLD_INTERFACE_TENDRIL_RECOVER = register("world_interface_tendril_recover", 64.0F);
	public static final SoundEvent WORLD_INTERFACE_ROAR_1 = register("world_interface_roar_1", 128.0F);
	public static final SoundEvent WORLD_INTERFACE_ROAR_2 = register("world_interface_roar_2", 128.0F);
	public static final SoundEvent WORLD_INTERFACE_ROAR_3 = register("world_interface_roar_3", 128.0F);
	/** The whole End arena hears it: summon and phase morph change the rules for everyone. */
	private static final float ARENA_RANGE = 96.0F;
	/** Wider still, because the encounter resolving is not something anyone should miss. */
	private static final float RESOLUTION_RANGE = 128.0F;
	/** Ritual structures - altar, bound terminals, stability anchors - read as places. */
	private static final float LANDMARK_RANGE = 64.0F;
	/** Spatial warnings emitted at a place on the ground: a mark at the player's own feet. */
	private static final float TELEGRAPH_RANGE = 48.0F;
	/**
	 * Anything the interface's own body emits.
	 *
	 * <p><b>The body is never near anybody, and that is the whole reason this exists.</b> Cues cast by
	 * the interface come out of {@code WorldInterfaceAnatomy.coreOrigin}, which sits {@code hover +
	 * coreLift} over the arena floor - about 16, 29 and 37 blocks at the three forms - and the storm
	 * additionally holds station one head-reach away horizontally. A player standing directly under it
	 * is therefore 18, 32 and 42 blocks from the source before they move anywhere.
	 *
	 * <p>These were registered at {@link #TELEGRAPH_RANGE}, which is the radius for a mark on the
	 * ground at the player's feet. Against a 48-block linear falloff those distances leave 62%, 33%
	 * and 12% of the cue - and the last of those, multiplied by any ordinary master-volume setting,
	 * is silence. The attacks went quiet exactly as the forms grew, which is the same shape as the
	 * bug {@code WorldInterfaceAudioManifestTest} was written for: it fails per form, so it reads as
	 * "the sound disappears after a while" rather than as a radius being wrong.
	 *
	 * <p>Ninety-six leaves 81%, 66% and 56%. The gateways keep the telegraph radius: they are fixed
	 * points on the ground, there are twenty of them, and they are the one cue that should stay local.
	 */
	private static final float BODY_RANGE = 96.0F;

	public static final SoundEvent EMPTY_VIEWPOINT = register("empty_viewpoint", 32.0F);
	public static final SoundEvent EMPTY_BASE = register("empty_base", 32.0F);
	public static final SoundEvent EMPTY_EXPERIENCE = register("empty_experience", 32.0F);
	public static final SoundEvent FOURTH_BAND = register("fourth_band", 32.0F);
	public static final SoundEvent REWORK_JOINT = register("rework_joint");
	public static final SoundEvent ANOMALY_ECHO = register("anomaly_echo", 32.0F);
	public static final SoundEvent WINDOW_GLITCH = register("window_glitch", 32.0F);
	public static final SoundEvent DOOR_CASCADE = register("door_cascade", 32.0F);
	public static final SoundEvent RULE_COLLAPSE = register("rule_collapse", 32.0F);
	// Subtitle-less second layers for the narrative cues. They have their own generated recordings and
	// they must be played through mod ids rather than the vanilla SoundEvent constants: playing
	// SoundEvents.STONE_STEP directly makes the client print vanilla's own "Footsteps" subtitle
	// underneath the authored one, which tells a captioned player the thing they just heard was
	// ordinary. Owning the id lets the layer stay silent in the subtitle list.
	public static final SoundEvent LAYER_STONE_STEP = register("layer_stone_step", 32.0F);
	public static final SoundEvent LAYER_WOODEN_DOOR_CLOSE = register("layer_wooden_door_close", 32.0F);
	public static final SoundEvent LAYER_CHEST_CLOSE = register("layer_chest_close", 32.0F);
	public static final SoundEvent LAYER_BEACON_DEACTIVATE = register("layer_beacon_deactivate", 32.0F);
	public static final SoundEvent LAYER_DEEPSLATE_BREAK = register("layer_deepslate_break", 32.0F);
	public static final SoundEvent LAYER_COMPARATOR_CLICK = register("layer_comparator_click", 32.0F);
	public static final SoundEvent TERMINAL_CLICK = register("terminal_click");
	public static final SoundEvent TERMINAL_TUNE = register("terminal_tune");
	/**
	 * The terminal's own noise floor, held for as long as the screen is open.
	 *
	 * <p>One asset, pitched by the client to say which anomaly stage the holder is on. The player is
	 * not meant to hear it as information and almost certainly never will - it is there so that the
	 * one time it stops while the terminal is still open, something is missing.
	 */
	public static final SoundEvent TERMINAL_CARRIER = register("terminal_carrier");
	public static final SoundEvent TERMINAL_LOCK = register("terminal_lock");
	public static final SoundEvent TERMINAL_FAULT = register("terminal_fault");
	public static final SoundEvent TERMINAL_ANOMALY = register("terminal_anomaly");
	/** The light contact, for navigation that commits nothing. Firmer clicks stay for decisions. */
	public static final SoundEvent TERMINAL_KEYPRESS = register("terminal_keypress");
	/** One notch of the tuning dial. The loop covers the sweep; this marks the discrete steps. */
	public static final SoundEvent TERMINAL_DETENT = register("terminal_detent");
	/**
	 * One line of the power-on self test landing.
	 *
	 * <p>The boot sequence is the first thing the mod ever shows a player and it played in complete
	 * silence, which made the one screen that is supposed to establish the terminal as a working
	 * machine read as a title card. Five of these and the completion below give it a machine's own
	 * cadence: something is being checked, and each check answers.
	 *
	 * <p>Drawn from the device's own recorded contacts rather than from a vanilla event. The first
	 * pass borrowed a comparator click and a beacon chime, and those are sounds a player has spent
	 * hundreds of hours learning to read as redstone and as a beacon - hearing them come out of the
	 * terminal places it in the wrong world. The pitch ramp in {@code TerminalClientAudio} is what
	 * keeps this from being indistinguishable from an ordinary click.
	 */
	public static final SoundEvent TERMINAL_BOOT_LINE = register("terminal_boot_line");
	/** The last line - the one that says it is ready. Deliberately not the same sound as the rest. */
	public static final SoundEvent TERMINAL_BOOT_COMPLETE = register("terminal_boot_complete");
	public static final SoundEvent ALPHA_CORRUPTION_WARNING = register("alpha_corruption_warning");
	public static final SoundEvent ALPHA_CORRUPTION_COLLAPSE = register("alpha_corruption_collapse");
	/**
	 * The one thing the capture blackout is allowed to be loud about.
	 *
	 * <p>Everything else in that moment is equipment failing - the collapse, the corruption warnings
	 * repeating underneath it - and equipment failing is the register the rest of the mod stays in.
	 * This is deliberately the only cue that is not. It plays once, into a screen that has already
	 * gone black, at the instant the player is told they were caught.
	 *
	 * <p>Ships at -7.8 LUFS with a -0.1 dBFS true peak. It was first aligned to {@code signal_alert}
	 * at -12, on the reasoning that it had to be the loudest thing in this moment without being the
	 * loudest thing in the mod; what that produced was a scream the collapse it plays over
	 * (-9.2 LUFS) was covering. It now clears the collapse, and the only cue above it is
	 * {@link #UNRENDERED_CAPTURE_SCREAM} - the layer's own, which is a different ending.
	 */
	public static final SoundEvent PURSUIT_CAPTURE_SCREAM = register("pursuit_capture_scream");
	// The analog-horror signal palette. The four loops are beds meant to sit under everything
	// else at a level low enough to be doubted; the three cues are one-shots.
	public static final SoundEvent SIGNAL_CARRIER = register("signal_carrier");
	public static final SoundEvent SIGNAL_STATIC = register("signal_static");
	public static final SoundEvent SIGNAL_TAPE_HISS = register("signal_tape_hiss");
	public static final SoundEvent SIGNAL_DEAD_AIR = register("signal_dead_air");
	public static final SoundEvent SIGNAL_ALERT = register("signal_alert");
	public static final SoundEvent SIGNAL_CARRIER_LOST = register("signal_carrier_lost");
	public static final SoundEvent SIGNAL_TUNING_SWEEP = register("signal_tuning_sweep");
	// Everything below is an End-arena cue, and the arena is far wider than the 16 blocks a
	// variable-range event reaches at volume <= 1. See #register(String, float).
	public static final SoundEvent WORLD_INTERFACE_ALTAR = register("world_interface_altar", LANDMARK_RANGE);
	public static final SoundEvent WORLD_INTERFACE_TERMINAL = register("world_interface_terminal", LANDMARK_RANGE);
	public static final SoundEvent WORLD_INTERFACE_ANCHOR = register("world_interface_anchor", LANDMARK_RANGE);
	public static final SoundEvent WORLD_INTERFACE_GATEWAY_PURPLE =
			register("world_interface_gateway_purple", TELEGRAPH_RANGE);
	public static final SoundEvent WORLD_INTERFACE_GATEWAY_GOLD =
			register("world_interface_gateway_gold", TELEGRAPH_RANGE);
	public static final SoundEvent WORLD_INTERFACE_GATEWAY_RED =
			register("world_interface_gateway_red", TELEGRAPH_RANGE);
	public static final SoundEvent WORLD_INTERFACE_SUMMON = register("world_interface_summon", ARENA_RANGE);
	// Deliberately variable-range, and deliberately NOT "fixed" the way the cues below were. These
	// three are the phase beds: WorldInterfacePresentationController plays them through
	// playLocalSound with Attenuation.NONE, so they never travel through the positional path a range
	// would apply to. Giving them a radius would state a distance that nothing ever reads.
	public static final SoundEvent WORLD_INTERFACE_AMBIENT_1 = register("world_interface_ambient_1");
	public static final SoundEvent WORLD_INTERFACE_AMBIENT_2 = register("world_interface_ambient_2");
	public static final SoundEvent WORLD_INTERFACE_AMBIENT_3 = register("world_interface_ambient_3");
	public static final SoundEvent WORLD_INTERFACE_MORPH = register("world_interface_morph", ARENA_RANGE);
	// The boss was silent under fire, which read as hits not landing at all. Both cues carry to the
	// arena edge, because a fight this size is fought from further out than 16 blocks.
	public static final SoundEvent WORLD_INTERFACE_HURT = register("world_interface_hurt", ARENA_RANGE);
	/** Heard by whoever was hit, so it is deliberately near-field rather than arena-wide. */
	public static final SoundEvent WORLD_INTERFACE_IMPACT = register("world_interface_impact", BODY_RANGE);
	public static final SoundEvent WORLD_INTERFACE_FORM_SHIFT = register("world_interface_form_shift", ARENA_RANGE);
	public static final SoundEvent WORLD_INTERFACE_DEATH = register("world_interface_death", RESOLUTION_RANGE);
	public static final SoundEvent WORLD_INTERFACE_LASER = register("world_interface_laser", BODY_RANGE);
	/** The discharge, not the charge. Reusing the telegraph sample made firing sound like aiming. */
	public static final SoundEvent WORLD_INTERFACE_LASER_FIRE =
			register("world_interface_laser_fire", ARENA_RANGE);
	public static final SoundEvent WORLD_INTERFACE_ORB = register("world_interface_orb", BODY_RANGE);
	// These six were left on the variable-range default and so were inaudible past 16 blocks - far
	// inside the radius the fight is actually fought at. Each states the radius its role needs.
	public static final SoundEvent WORLD_INTERFACE_GRAB = register("world_interface_grab", BODY_RANGE);
	public static final SoundEvent WORLD_INTERFACE_MENTAL = register("world_interface_mental", BODY_RANGE);
	public static final SoundEvent WORLD_INTERFACE_WEAPON = register("world_interface_weapon", BODY_RANGE);
	/** Arena-wide: a teammate being slammed into the floor is not a private event. */
	public static final SoundEvent WORLD_INTERFACE_THROW = register("world_interface_throw", ARENA_RANGE);
	public static final SoundEvent WORLD_INTERFACE_HOTBAR = register("world_interface_hotbar", BODY_RANGE);
	public static final SoundEvent WORLD_INTERFACE_ARROW = register("world_interface_arrow", BODY_RANGE);
	/** Top-level event: someone is being removed from the world. Everyone hears it. */
	public static final SoundEvent WORLD_INTERFACE_EXPULSION =
			register("world_interface_expulsion", RESOLUTION_RANGE);
	// The encounter's remaining silent frames. Each of these moments previously played nothing at
	// all, which is why they read as the fight skipping rather than landing.
	/** The ring itself, emitted by {@code WorldInterfaceShockwaveService}. */
	public static final SoundEvent WORLD_INTERFACE_SHOCKWAVE =
			register("world_interface_shockwave", ARENA_RANGE);
	/** The downbeat the summon ceremony hands over to. See {@code WorldInterfaceSummonTimeline}. */
	public static final SoundEvent WORLD_INTERFACE_COMBAT_START =
			register("world_interface_combat_start", ARENA_RANGE);
	/** The eviction executing, distinct from the expulsion tear that follows it. */
	public static final SoundEvent WORLD_INTERFACE_EVICTION =
			register("world_interface_eviction", RESOLUTION_RANGE);
	/**
	 * The sky lance charge. It used to borrow the mental-interference sample, which meant a physical
	 * attack that can kill outright and a purely perceptual one announced themselves identically.
	 */
	public static final SoundEvent WORLD_INTERFACE_LANCE = register("world_interface_lance", BODY_RANGE);
	public static final SoundEvent WORLD_INTERFACE_LANCE_IMPACT =
			register("world_interface_lance_impact", ARENA_RANGE);
	/** Third-form hunting displacement: the storm relocating is audible from anywhere in the arena. */
	public static final SoundEvent WORLD_INTERFACE_FLIGHT = register("world_interface_flight", ARENA_RANGE);
	public static final SoundEvent WORLD_INTERFACE_SUCCESS = register("world_interface_success", RESOLUTION_RANGE);
	public static final SoundEvent WORLD_INTERFACE_FAILURE = register("world_interface_failure", RESOLUTION_RANGE);
	// The authored background score. One event per context, each holding that context's whole
	// playlist, so the music manager - which identifies a track by its *event* id - keeps treating
	// the playlist as a single continuous piece of music while the sound system shuffles the files.
	public static final SoundEvent MUSIC_MENU = register("music_menu");
	public static final SoundEvent MUSIC_GAME = register("music_game");
	public static final SoundEvent MUSIC_PURSUIT = register("music_pursuit");
	/**
	 * The unrendered layer.
	 *
	 * <p>The layer used to be the one place the score was suppressed outright, on the reasoning that
	 * somewhere that was never authored for anybody should not sound authored. What that produced was
	 * six minutes carried by a single twenty-second drone, and the drone alone says "nothing here is
	 * for you" without also saying the far worse thing this track does: that something down here still
	 * remembers the playlist the player was listening to upstairs. It is a track pulled out of the
	 * gameplay rotation rather than a piece of its own, so a player who has been playing long enough
	 * recognises it, and the wrongness is that they recognise it <em>here</em>.</p>
	 *
	 * <p>Mixed under {@code unrendered_layer_ambience} rather than over it - see
	 * {@code UNRENDERED_LOUDNESS_TARGET_LUFS} in {@code tools/import_music.py}. The bed still owns the
	 * room; this is the thing that should not be in it.</p>
	 */
	public static final SoundEvent MUSIC_UNRENDERED = register("music_unrendered");
	/**
	 * The End before the interface is summoned.
	 *
	 * <p>Its own event rather than another entry in the gameplay playlist, because arriving in the
	 * End is the point the run stops being a survival game: the place is scored from the moment the
	 * player lands, and it stays on the one track until the summon takes over.
	 */
	public static final SoundEvent MUSIC_END = register("music_end");
	// A track per body rather than one for the whole encounter. Separate events are what lets the
	// music manager cut from one to the next on each morph instead of waiting for the current one
	// to end.
	//
	// The summon has no track of its own: it fades the first body's in instead, so the piece
	// playing while the interface descends is still playing when it starts fighting.
	public static final SoundEvent MUSIC_ENCOUNTER_PHASE_1 = register("music_encounter_phase_1");
	public static final SoundEvent MUSIC_ENCOUNTER_PHASE_2 = register("music_encounter_phase_2");
	/** The third body's track. Named "final" since before the phases were scored separately. */
	public static final SoundEvent MUSIC_ENCOUNTER_FINAL = register("music_encounter_final");
	public static final SoundEvent MUSIC_ENDING = register("music_ending");
	/**
	 * The unrendered layer's two cues.
	 *
	 * <p>The ambience is a twenty-second bed the client loops for as long as the player is in the
	 * layer, and it is what the place sounds like: {@link #MUSIC_UNRENDERED} is mixed underneath it
	 * rather than over it. The scream is its opposite: one shot, into a screen that has already gone
	 * black, at the instant the entity reaches them.
	 *
	 * <p>Original procedural material in RC.3. Decoded levels live in
	 * docs/art/audio/soundscape_manifest.json; playback owns the relative mix.

	 */
	public static final SoundEvent UNRENDERED_LAYER_AMBIENCE = register("unrendered_layer_ambience");
	public static final SoundEvent UNRENDERED_CAPTURE_SCREAM = register("unrendered_capture_scream");
	/**
	 * The heartbeat the thing in the layer carries, and the only way to tell where it is.
	 *
	 * <p>A fixed radius rather than a variable one, and it has to be: the entity is placed at least a
	 * hundred blocks away, so at the sixteen blocks a variable-range event resolves to it would be
	 * inaudible until it was already on top of the player - which is the opposite of what a locator
	 * is for. Sixty-four blocks means it fades in while the entity is still a long way off and grows
	 * the whole way in, so distance is something the player hears rather than something they are
	 * told.
	 *
	 * <p>The client adds quiet foot friction inside 18 blocks. Vanilla movement/hurt
	 * sounds stay suppressed; the 64-block heartbeat remains the distant locator.
	 */
	public static final SoundEvent UNRENDERED_HEARTBEAT = register("unrendered_heartbeat", LANDMARK_RANGE);
	public static final SoundEvent MUSIC_ENDING_FAILURE = register("music_ending_failure");

	private ModSounds() {
	}

	private static SoundEvent register(String path) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	/**
	 * Registers a cue whose audible radius is stated outright instead of being inferred from
	 * whatever volume the caller happens to pass.
	 *
	 * <p>A variable-range event resolves to a flat 16 blocks for every volume at or below 1.0,
	 * and {@link AudioService#playBounded} clamps its relative volume into that range by
	 * construction. Encounter cues were therefore capped at 16 blocks no matter how loud they
	 * were mixed - the server simply never sent the packet to anyone further out - which left
	 * players fighting at range unable to hear the summon, the phase morph or the resolution
	 * that the whole encounter builds towards. Stating the radius here decouples "how far this
	 * carries" from "how loud this is", so the two can be tuned independently.</p>
	 */
	private static SoundEvent register(String path, float range) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createFixedRangeEvent(id, range));
	}

	public static void initialize() {
		TheFourthFrequency.LOGGER.info("Registered bounded narrative and terminal device sound cues");
	}
}
