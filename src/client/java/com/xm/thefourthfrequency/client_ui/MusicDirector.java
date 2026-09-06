package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.mixin.MusicManagerGainAccessor;
import com.xm.thefourthfrequency.ending.WorldInterfaceSummonTimeline;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * Decides which authored score - if any - the current situation calls for.
 *
 * <p>The mod ships a playlist per context rather than one for the whole game: the menu, ordinary
 * play, the pursuit, the unrendered layer, the End before anything is summoned, the encounter -
 * which is scored once per body, the first of which also covers the summon - and one track for each
 * of the two ways the story can end. Everything else the vanilla music manager would have chosen is
 * deliberately dropped.</p>
 *
 * <p>What is <em>not</em> scored matters as much as what is. Nothing plays across a world load: a
 * loading screen is the gap between two contexts rather than a context of its own, and letting the
 * menu playlist run underneath one means whichever track it picked is the one waiting on the other
 * side. Nothing plays over the aftermath of the encounter either - the two ending tracks are held
 * back until a player actually steps into the exit portal.</p>
 *
 * <p>Silence is expressed two ways. {@link #musicVolume} drives the vanilla fade, so music retreats
 * over a few seconds when a situation changes and swells back in when the next one starts - that is
 * the normal path, and it also gives every track a fade-in. The hard stop in {@link #tick} is for
 * the moments where a fade would be a loose end: the pursuit blackout, and the far side of a load.</p>
 *
 * <p>Tracks that replace each other directly - the summon taking over from whatever was playing,
 * and each body taking over from the one before - go through {@linkplain Handover a handover}
 * instead of either of those. See {@link #tickHandover}.</p>
 */
public final class MusicDirector {
	/** Menu pacing, and the vanilla "take over from whatever is playing" flag, matching Musics.MENU. */
	private static final Music MENU = new Music(Holder.direct(ModSounds.MUSIC_MENU), 20, 600, true);
	/**
	 * Four to six minutes between songs, against vanilla's ten to twenty.
	 *
	 * <p>Only the lower bound really decides the gap. The music manager re-rolls this range and takes
	 * the minimum on every single tick, including the thousands that pass while a song is playing, so
	 * the drawn value is pinned to the bottom of the range by the time it is used; the upper bound
	 * states the intent and little else.</p>
	 *
	 * <p>Vanilla's pacing was written for one playlist covering a whole game. Ten tracks averaging
	 * two minutes fifteen, played on that schedule, leave the score audible less than a fifth of the
	 * time and take two hours to come round once - long enough that a player can finish a stretch of
	 * the main line without hearing half of them. At four minutes the score is present about a third
	 * of the time and the playlist turns over in roughly an hour. That is longer than it was at
	 * seven tracks, and it is the price of the longer playlist rather than a fault in the interval:
	 * shortening the gap only spends the rotation faster, and what the rotation is protecting is
	 * that every track in a pass is one the player has not heard yet. Going lower would start
	 * working against the mod: silence is the default state here, and a track playing is what tells
	 * the player the moment is authored, therefore safe. The signal beds are what keep those gaps
	 * from reading as an empty channel.</p>
	 *
	 * <p>The player's music-frequency option still applies. "Frequent" caps the gap at 12000 ticks,
	 * which is above this and therefore changes nothing; "constant" is hard-coded to 100 ticks in the
	 * manager and overrides any pacing a track asks for.</p>
	 */
	private static final Music GAME = new Music(Holder.direct(ModSounds.MUSIC_GAME), 4_800, 7_200, false);
	private static final Music PURSUIT = immediate(ModSounds.MUSIC_PURSUIT);
	/**
	 * The unrendered layer.
	 *
	 * <p>Immediate, like the other one-track contexts: the way in is a dimension change, so the
	 * vanilla side has already stopped whatever was playing and there is never an outgoing track to
	 * hand over from. What the player hears instead is the fade-in arriving underneath the ambience
	 * a second or two after the floor closes over them.</p>
	 */
	private static final Music UNRENDERED = immediate(ModSounds.MUSIC_UNRENDERED);
	/** How far under its own ambience the layer's track sits. See {@link #musicVolume}. */
	private static final float UNRENDERED_MUSIC_TRIM = 0.45F;
	/**
	 * The End, before the interface is summoned.
	 *
	 * <p>Scored from arrival rather than left on the ordinary playlist: the End is where the run
	 * stops being a survival game, and the gaps the gameplay pacing is built around would read as
	 * the score having simply run out. Immediate rather than handed over because a dimension change
	 * stops the music outright on the vanilla side - there is never an outgoing track to carry.</p>
	 */
	private static final Music END = immediate(ModSounds.MUSIC_END);
	// The encounter tracks are the only ones that ever replace a track that is still playing, and
	// they are handed over rather than cut - so they must NOT carry the vanilla replace flag. It is
	// evaluated inside the music manager's own tick, which runs before this class gets a look at
	// the frame, so leaving it set would cut the outgoing track before the handover ever started.
	//
	// The first body's track also scores the summon. Returning the same Music for both means the
	// handover happens once, on the way in, and the moment the interface finishes descending is
	// not a track change at all - which is the point: the descent and the first body are one event.
	private static final Music ENCOUNTER_PHASE_1 = handedOver(ModSounds.MUSIC_ENCOUNTER_PHASE_1);
	private static final Music ENCOUNTER_PHASE_2 = handedOver(ModSounds.MUSIC_ENCOUNTER_PHASE_2);
	private static final Music ENCOUNTER_FINAL = handedOver(ModSounds.MUSIC_ENCOUNTER_FINAL);
	private static final Music ENDING = immediate(ModSounds.MUSIC_ENDING);
	private static final Music ENDING_FAILURE = immediate(ModSounds.MUSIC_ENDING_FAILURE);

	/**
	 * Per-tick multiplier applied to the fade's gain on top of vanilla's own 0.97 while a handover
	 * is emptying the channel. Together they clear a full gain in about a second and a half - long
	 * enough to read as the previous track leaving, short enough that the morph it belongs to has
	 * not finished playing out by the time the next one arrives.
	 */
	private static final float STEEP_FADE_OUT = 0.80F;
	/**
	 * Extra vanilla-shaped fade-in steps to run per tick while a handover fills the channel back up.
	 * One step a tick is a ten-second swell, which is right for a track that drifts in over quiet
	 * play and far too slow for one that answers a phase change.
	 */
	private static final int HANDOVER_FADE_IN_STEPS = 7;
	/** The vanilla fade-in step, reproduced so the extra steps ramp on the same curve it does. */
	private static final float VANILLA_FADE_IN_MIN_STEP = 0.000_5F;
	private static final float VANILLA_FADE_IN_MAX_STEP = 0.005F;
	/** Ceiling on either half, so a handover can never strand itself if the gain stops moving. */
	private static final int HANDOVER_TIMEOUT_TICKS = 60;
	/**
	 * How long the menu theme gets to leave once the player commits to a world.
	 *
	 * <p>Half a second, and linear rather than eased, because this is the one fade that races
	 * something. Entering a world destroys the playing sound twice over - {@code updateLevelInEngines}
	 * stops every sound outright, and the Alpha pack swap reloads the sound engine - and neither is
	 * a fade this class can lengthen. Worse, the window before them is not a fixed length and is not
	 * even continuous: reading the level data blocks the main thread, so no ticks pass at all until
	 * the server is being waited on. A fade measured in seconds simply loses the race and gets cut
	 * off wherever it happened to be, which is exactly the abrupt stop it was added to avoid.</p>
	 *
	 * <p>Linear, because an eased fade spends most of its drop in the first few ticks and then
	 * crawls; over ten ticks that reads as a clip rather than a fade. A straight ramp over the same
	 * ten ticks is short but unmistakably a fade.</p>
	 */
	private static final int LOAD_FADE_TICKS = 10;

	/**
	 * Opt-in trace of every decision this class makes, off unless
	 * {@code -Dthefourthfrequency.musicDebug=true} is passed to the client.
	 *
	 * <p>It exists because nothing else can see this code. The client GameTest runner starts with
	 * {@code soundCategory_music: 0.0}, so the one automated layer that could exercise the score is
	 * muted by construction and every branch below has always been verified by ear. A score that
	 * fails to arrive leaves no trace at all otherwise: no exception, no missing resource, just
	 * silence that is indistinguishable from silence the fiction asked for.</p>
	 *
	 * <p>Logs only on a change of situation, so a whole session is a few dozen lines.</p>
	 */
	private static final boolean DEBUG = Boolean.getBoolean("thefourthfrequency.musicDebug");
	private static Music debugWanted;
	private static Handover debugHandover;
	private static String debugPlaying = "";

	private static boolean cut;
	private static boolean loadingScreen;
	private static boolean scored;
	private static boolean initialized;
	/** An acknowledged ending waiting to arm the exit hold. See {@link EndingScoreHandoff}. */
	private static boolean runEnded;
	private static EndingScoreHandoff.State exitHold = EndingScoreHandoff.State.OFF;
	private static Handover handover = Handover.NONE;
	private static int handoverTicks;
	private static float fadeTarget = 1.0F;
	/** Negative while no world-entry fade is running; the ramp needs its own start point. */
	private static int loadFadeTicks = -1;
	private static float loadFadeFrom;

	private MusicDirector() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(MusicDirector::tick);
	}

	/**
	 * Reports that the run has finished, so the score follows the player out to the title screen
	 * instead of being replaced by the menu playlist. See {@link EndingScoreHandoff}.
	 *
	 * <p>Called for the success ending only. The failure ending keeps its own track scored all the
	 * way to the locked menu through {@code scoredOutcome}, so there is nothing there for the menu
	 * theme to take over from - and its presentation owns that stretch of audio outright.</p>
	 */
	public static void noteRunEnded() {
		runEnded = true;
	}

	/**
	 * Whether a disconnect happening right now must leave the music channel playing.
	 *
	 * <p>Read by {@code MinecraftEndingScoreCarryMixin}, which is the only thing that can act on it:
	 * leaving a world stops every sound in the engine, and that is not a fade this class can
	 * lengthen or a stop it can see coming.</p>
	 */
	public static boolean keepsScoreAcrossDisconnect() {
		return EndingScoreHandoff.keepsSoundsAcrossDisconnect(exitHold);
	}

	/**
	 * Stops everything the way leaving a world normally would, except the score.
	 *
	 * <p>Category by category rather than all at once, because the engine's own "stop everything" is
	 * the single call that would take the music with it. Every instance the engine is tracking is
	 * filed under one of these sources, so the only thing left running afterwards is the track the
	 * hold is carrying.</p>
	 */
	public static void stopEverythingButTheScore(SoundManager sounds) {
		for (SoundSource source : SoundSource.values()) {
			if (source == SoundSource.MUSIC) continue;
			sounds.stop(null, source);
		}
	}

	/**
	 * The score this situation calls for.
	 *
	 * @return the music to play, or null when this situation is one the player should hear
	 *         unaccompanied - the vanilla music manager treats null as "keep quiet"
	 */
	public static Music situationalMusic(Minecraft client) {
		// A handover empties the channel before it fills it again, and "nothing" is how that is
		// asked for: it drops the fade target to zero, which is what carries the outgoing track out.
		return handover == Handover.FADING_OUT ? null : wantedMusic(client);
	}

	private static Music wantedMusic(Minecraft client) {
		if (PursuitPresentationClient.scoresMusic()) return PURSUIT;
		if (PursuitPresentationClient.silencesMusic()) return null;
		// Answered ahead of the encounter stage on purpose. The poem is armed the instant a player
		// steps into the exit portal, but the stage the client still holds at that point is
		// PORTAL_OPEN, which is deliberately one of the silent ones.
		if (endingScored() || client.screen instanceof WinScreen) return endingMusic();
		WorldInterfaceProtocol.Stage stage = encounterStage();
		if (stage != null) {
			switch (stage) {
				// The arrival is scored from its first tick, and the fade-in is the point.
				//
				// It used to stay silent until MUSIC_HANDOVER, on the reasoning that the ceremony is
				// already carried by the rise cue and the anchor chain and that a track underneath
				// all of that would flatten both. What that actually produced was thirteen seconds
				// of the biggest entrance in the mod with no score, and then a track arriving after
				// the thing had already landed. Starting here instead means the score comes up
				// under the descent - a fade-in has somewhere to go precisely because it starts
				// from nothing, and the handover machinery is what stops it colliding with whatever
				// was playing before.
				//
				// It is the first body's track, not one of the summon's own. The encounter folder
				// no longer ships a separate summon piece: the descent and the body that lands out
				// of it are one event, and hearing the score change between them would say they
				// were two.
				case SUMMONING -> {
					return ENCOUNTER_PHASE_1;
				}
				// A track per body. Each morph is a handover, so what the player hears at the
				// moment the interface changes shape is the score changing with it rather than a
				// single piece running underneath all three.
				case PHASE_1 -> {
					return ENCOUNTER_PHASE_1;
				}
				case PHASE_2 -> {
					return ENCOUNTER_PHASE_2;
				}
				case PHASE_3 -> {
					return ENCOUNTER_FINAL;
				}
				// Both resolutions and the open portal stay silent: the aftermath is the quietest
				// the End ever gets, and starting an ending track the instant the fight is decided
				// spends it on an empty arena. It is held back for the portal.
				case SUCCESS_RESOLUTION, FAILURE_RESOLUTION, PORTAL_OPEN -> {
					return null;
				}
				default -> {
				}
			}
		}
		// Both of the next two answers change while the exit hold is in force: the load out of a
		// finished run is not a gap to be covered with silence, it is the middle of a track, and the
		// title screen on the far side of it is where that track is going.
		boolean holding = EndingScoreHandoff.holdsScore(exitHold);
		if (loading(client)) return holding ? GAME : null;
		// The menu theme waits for the safety notice to be dismissed. Until then the title screen is
		// still carrying a page the player has to read, and scoring it would work against that.
		if (client.player == null || client.level == null) {
			if (holding) return GAME;
			return FirstRunNoticeController.released() ? MENU : null;
		}
		// Any other boss bar that asked for its own music - the vanilla dragon, a wither - is still
		// a fight, and the same reasoning applies.
		if (client.gui.getBossOverlay().shouldPlayMusic()) return null;
		// The layer has one track, mixed under its ambience.
		//
		// It was silent here for a long time, on the reasoning that a backing track would say the place
		// is an event with a mood when the effect depends on it reading as somewhere that was never
		// scored for anybody. What that reasoning missed is which track. This is not a piece written
		// for the layer - it is one of the gameplay playlist's own, playing in a room that has no
		// business having heard it, and a player who recognises it learns something worse about the
		// place than silence was telling them.
		// And only once there is something to score. The first minute down there is the layer
		// establishing that it is empty, and a track arriving with the player says it is not - the
		// score is the entity's, not the room's, so it starts when the entity does.
		if (UnrenderedLayerClient.inLayer()) {
			return UnrenderedLayerClient.hunted() ? UNRENDERED : null;
		}
		// The End before the summon has its own track. Read off the dimension rather than the
		// stage, because the stage is the thing that may not have arrived yet: a player who lands
		// in the End before the server has sent a world-interface snapshot holds no stage at all,
		// and that is exactly the stretch this scores. The stages that are past the summon have
		// already been answered above; the ones listed here are the ones that precede it.
		if (inEnd(client) && beforeTheSummon(stage)) return END;
		return GAME;
	}

	private static boolean inEnd(Minecraft client) {
		return client.level != null && client.level.dimension() == Level.END;
	}

	/**
	 * Whether the interface has yet to be called. A missing stage counts: the snapshot arrives with
	 * the world, and until then nothing has been summoned by definition.
	 *
	 * <p>{@code COMPLETE} is deliberately not here. It is the far side of the whole encounter, and
	 * the End it describes is not the one a player walks into.</p>
	 */
	private static boolean beforeTheSummon(WorldInterfaceProtocol.Stage stage) {
		return stage == null || stage == WorldInterfaceProtocol.Stage.UNPREPARED
				|| stage == WorldInterfaceProtocol.Stage.ARENA_READY
				|| stage == WorldInterfaceProtocol.Stage.WAITING_TERMINALS;
	}

	/**
	 * Scales the vanilla music fade target.
	 *
	 * <p>The music manager eases its category gain towards this value every tick and stops the track
	 * outright once the gain reaches zero, so returning zero here is a fade-out rather than a cut,
	 * and the ramp back up is the fade-in for whatever plays next.</p>
	 *
	 * <p>The mod's own volume trim is folded in here, and this is the only place it can be: every
	 * track this class can choose is one the mod ships, vanilla's playlist is dropped outright, and
	 * the category gain is the only control the music channel has. Without it the mod-volume slider
	 * would silence every cue the mod owns while its score kept playing at whatever the vanilla music
	 * slider said - which is the one reading of "mod volume" no player would expect.</p>
	 */
	public static float musicVolume(Minecraft client, float vanilla) {
		// Remembered so a handover knows where its fade-in has to stop, and remembered *trimmed*:
		// every other path in this class compares a written gain against this, so a raw target here
		// would let a handover climb past the level the player asked for. The manager asks for this
		// once at the top of every one of its own ticks, so the cached value is never stale.
		fadeTarget = vanilla * (float) RuntimeServices.config().meta().peakVolume();
		// The layer's track sits under its ambience rather than over it. Everywhere else in the mod
		// the score is the loudest thing in the mix; here the two sounds that carry information are
		// the heartbeat and the player's own footsteps, and a track at full level buries both.
		if (UnrenderedLayerClient.inLayer()) fadeTarget *= UNRENDERED_MUSIC_TRIM;
		return situationalMusic(client) == null ? 0.0F : fadeTarget;
	}

	private static void tick(Minecraft client) {
		boolean wanted = PursuitPresentationClient.cutsMusic();
		// Edge-triggered: stopPlaying pushes the next-song delay out every call, so holding it down
		// for the length of a pursuit would keep pushing it for no reason.
		if (wanted && !cut) {
			client.getMusicManager().stopPlaying();
			// A cut leaves the gain wherever the fade had reached; zeroing it is what makes the
			// pursuit theme rise out of the blackout instead of arriving at whatever level the
			// overworld track happened to have left behind.
			silenceGain(client);
		}
		cut = wanted;
		boolean nowLoading = loading(client);
		tickExitHold(client, nowLoading);
		// Backstop for a load short enough that even the half-second ramp below could not finish
		// inside it: whatever is left has to go, or the menu playlist swells back up in the world it
		// faded out for. Normally the ramp has already handed the track back before this fires.
		//
		// Two loads this must keep its hands off. A pursuit: the blackout covers a dimension
		// transfer, and the pursuit theme is already fading in underneath it by the time the loading
		// screen goes away. And the exit out of a finished run: the track the hold is carrying is
		// meant to be on the other side of that load, so stopping it here would undo the handoff
		// this backstop was never asked to police.
		if (!nowLoading && loadingScreen && !PursuitPresentationClient.scoresMusic()
				&& !EndingScoreHandoff.holdsScore(exitHold)) {
			client.getMusicManager().stopPlaying();
			silenceGain(client);
		}
		loadingScreen = nowLoading;
		tickHandover(client);
		traceScore(client);
		// The manager starts life at full gain and only ever eases it while a track is already
		// playing, so the first song of a session would otherwise begin at full volume. Zeroing on
		// the edge into "there is a score again" buys that first entrance a fade-in too. A handover
		// leans on the same edge: its fade-out reports "no score", so the tick that ends it is the
		// tick that drops the gain the incoming track then climbs out of.
		Music scoring = situationalMusic(client);
		if (scoring != null || !nowLoading) loadFadeTicks = -1;
		if (scoring != null) {
			if (!scored) {
				silenceGain(client);
				// The menu theme's own pacing leaves up to a second before a song starts. That gap
				// belongs between two of them; in front of the one that answers the acknowledgement
				// it is just the theme arriving later than the decision that asked for it.
				if (scoring == MENU) {
					((MusicManagerGainAccessor) client.getMusicManager())
							.thefourthfrequency$setNextSongDelay(0);
				}
			}
		} else if (nowLoading) {
			fadeOutForWorldEntry(client);
		}
		// Last, so it sees the gain every other path has finished writing for this tick.
		tickStuckScore(client, scoring);
		scored = scoring != null;
	}

	/**
	 * Advances the exit hold, and spends the pending ending on the tick it arms.
	 *
	 * <p>Runs before anything in {@link #tick} that reads the loading edge, so the tick the player
	 * lands on the title screen is already holding by the time the backstop is considered.</p>
	 */
	private static void tickExitHold(Minecraft client, boolean nowLoading) {
		boolean inWorld = client.level != null && client.player != null;
		// The channel itself, not a timer: the carried track's length is whatever the resource pack
		// ships, and the manager is already tracking when it ends.
		boolean scorePlaying = ((MusicManagerGainAccessor) client.getMusicManager())
				.thefourthfrequency$currentMusic() != null;
		EndingScoreHandoff.State next = EndingScoreHandoff.next(exitHold, runEnded, inWorld, nowLoading,
				scorePlaying);
		if (exitHold == EndingScoreHandoff.State.OFF && next == EndingScoreHandoff.State.ARMED) {
			runEnded = false;
		}
		exitHold = next;
	}

	/**
	 * Walks the menu theme down a straight ramp once the player has committed to a world.
	 *
	 * <p>See {@link #LOAD_FADE_TICKS} for why this is its own fade rather than a steeper version of
	 * the vanilla one: it is racing two hard stops it cannot influence, across a window that is not
	 * a fixed length. Ten ticks is short enough to finish inside the shortest window that carries
	 * any ticks at all, and the ramp is recomputed from a remembered start rather than accumulated,
	 * so vanilla easing the same value in the same tick cannot bend it.</p>
	 */
	private static void fadeOutForWorldEntry(Minecraft client) {
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		if (manager.thefourthfrequency$currentMusic() == null) {
			loadFadeTicks = -1;
			return;
		}
		if (loadFadeTicks < 0) {
			loadFadeTicks = 0;
			loadFadeFrom = manager.thefourthfrequency$currentGain();
		}
		float remaining = 1.0F - ++loadFadeTicks / (float) LOAD_FADE_TICKS;
		if (remaining > 0.0F) {
			writeGain(client, loadFadeFrom * remaining);
			return;
		}
		// Inaudible by now, so this stop cannot be heard - but it does hand the track back. Left
		// alone, a track sitting at zero gain holds the channel until the world change tears it
		// down, and the manager would treat it as still playing in the meantime.
		client.getMusicManager().stopPlaying();
		silenceGain(client);
		loadFadeTicks = -1;
	}

	/**
	 * Carries one track out and the next one in when the two would otherwise cut.
	 *
	 * <p>Everywhere else the score changes, it changes through a stretch of deliberate silence, and
	 * vanilla's own curve covers that: the fade target drops to zero, the outgoing track eases out
	 * over about fifteen seconds, and whatever plays next climbs back over about ten. The encounter
	 * is the exception. Its three tracks replace each other directly - ordinary play gives way to the
	 * summon, and each body gives way to the next - and at vanilla's pace each swap would
	 * either be a hard cut or a twenty-five second hole in the middle of a boss fight.</p>
	 *
	 * <p>So the handover runs the same two curves, steeply. It empties the channel by reporting no
	 * music at all, then multiplies the gain down on top of vanilla's own easing until the manager
	 * stops the track; then it clears the next-song delay so the incoming track starts on the spot,
	 * and runs the vanilla fade-in step several times a tick until the gain is back at target. The
	 * whole exchange is a little under three seconds - long enough to hear one track leave and the
	 * other arrive, short enough to fit inside the morph it belongs to.</p>
	 */
	private static void tickHandover(Minecraft client) {
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		SoundInstance playing = manager.thefourthfrequency$currentMusic();
		switch (handover) {
			case NONE -> {
				Music wanted = wantedMusic(client);
				if (playing == null || wanted == null || !handsOver(wanted) || plays(playing, wanted)) {
					return;
				}
				handover = Handover.FADING_OUT;
				handoverTicks = 0;
			}
			case FADING_OUT -> {
				if (playing != null && ++handoverTicks < HANDOVER_TIMEOUT_TICKS) {
					deepenFadeOut(client);
					return;
				}
				// The manager stops a track of its own accord once the fade reaches zero, so an
				// empty channel is how the fade-out reports that it is finished.
				if (playing != null) client.getMusicManager().stopPlaying();
				handover = Handover.FADING_IN;
				handoverTicks = 0;
				// Every stop pushes the next-song delay out by five seconds, and the "constant"
				// music-frequency option floors it at five seconds regardless of what the incoming
				// track's own pacing asks for. Neither is a gap this handover wanted.
				manager.thefourthfrequency$setNextSongDelay(0);
			}
			case FADING_IN -> {
				// A situation that changed again mid-handover has its own answer, and the vanilla
				// fade is the right one for it. The tick ceiling is the backstop for a track that
				// never starts at all.
				if (wantedMusic(client) == null || ++handoverTicks >= HANDOVER_TIMEOUT_TICKS) {
					handover = Handover.NONE;
					return;
				}
				if (playing == null) return;
				float gain = manager.thefourthfrequency$currentGain();
				for (int step = 0; step < HANDOVER_FADE_IN_STEPS; step++) {
					gain += Math.clamp(gain, VANILLA_FADE_IN_MIN_STEP, VANILLA_FADE_IN_MAX_STEP);
				}
				if (gain >= fadeTarget) {
					gain = fadeTarget;
					handover = Handover.NONE;
				}
				// Through writeGain, so the tick that ends the handover is also the tick the engine
				// is told about - see writeGain. Landing on the target is precisely the state in
				// which vanilla will never run another fade to do it for us.
				writeGain(client, gain);
			}
		}
	}

	/**
	 * Only the encounter's own tracks are handed over. Every other change of score already passes
	 * through a stretch of silence that belongs to the fiction, and shortening those would cost
	 * more than it bought.
	 */
	private static boolean handsOver(Music wanted) {
		return wanted == ENCOUNTER_PHASE_1 || wanted == ENCOUNTER_PHASE_2
				|| wanted == ENCOUNTER_FINAL;
	}

	private static boolean plays(SoundInstance instance, Music music) {
		return music.sound().value().location().equals(instance.getIdentifier());
	}

	/**
	 * True while the client is between places rather than in one: chunk loading, a world handoff,
	 * a connection attempt, or the resource reload behind the startup overlay.
	 *
	 * <p>These screens are not a context that gets scored, they are the gap between two that do.
	 * Letting the menu playlist run underneath a progress bar also means whichever track it picked
	 * is the one waiting on the other side, which is the wrong track for wherever the player just
	 * went.</p>
	 */
	private static boolean loading(Minecraft client) {
		return client.getOverlay() != null
				|| client.screen instanceof LevelLoadingScreen
				|| client.screen instanceof ProgressScreen
				|| client.screen instanceof GenericMessageScreen
				|| client.screen instanceof ConnectScreen;
	}

	/**
	 * Arms the next track to fade in, by emptying the music channel while nothing is playing.
	 *
	 * <p>Guarded on there being no current track, because a situation that flickers - a boss bar
	 * that blinks out for a tick, a stage that lands a frame early - would otherwise reach this on
	 * an edge and cut a track that was only part-way through an honest fade.</p>
	 *
	 * <p>The engine's own category volume has to come down with the fade's gain, not just the gain.
	 * The manager pushes one into the other only from inside its fade, and the fade only runs once a
	 * track is already playing - so a track starting while the engine still carries the previous
	 * one's volume plays its whole first tick at that volume and is yanked down on the next. Fifty
	 * milliseconds at full level followed by near-silence is a click, arriving exactly where the
	 * fade-in was supposed to be. Zeroing both is what makes the entrance actually start from
	 * nothing.</p>
	 */
	/**
	 * Steepens the fade the manager is already running, so a track that has to be gone by a deadline
	 * gets there. A no-op when nothing is playing: the fade target is what asks for the fade, and
	 * this only decides how fast it is taken.
	 */
	private static void deepenFadeOut(Minecraft client) {
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		if (manager.thefourthfrequency$currentMusic() == null) return;
		writeGain(client, manager.thefourthfrequency$currentGain() * STEEP_FADE_OUT);
	}

	private static void silenceGain(Minecraft client) {
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		if (manager.thefourthfrequency$currentMusic() != null) return;
		writeGain(client, 0.0F);
	}

	/**
	 * Writes the fade's gain, and pushes it into the sound engine in the same breath.
	 *
	 * <p>These are two values, not one. {@code currentGain} is the music manager's bookkeeping; the
	 * engine multiplies every sound in the category by its own {@code gainBySource} entry, and the
	 * only thing in the game that ever writes that entry is {@code MusicManager.fadePlaying} - which
	 * the manager calls <em>only</em> while {@code currentGain != getMusicVolume()}.
	 *
	 * <p>So a gain this class writes by hand is invisible until vanilla happens to run a fade, and a
	 * gain this class writes that lands exactly on the target guarantees vanilla never runs one
	 * again: from that tick the engine keeps whatever it last received, for the rest of the session.
	 * {@link #silenceGain} deliberately puts a zero there, so every path that leaves the gain parked
	 * on the target without a fade in between is a way to mute the entire music category
	 * permanently - silently, and with every other category still audible.
	 *
	 * <p>Keeping the two in step here removes the whole class of failure rather than the one path
	 * that was noticed. It costs one call: {@code updateCategoryVolume} only writes a float into a
	 * map and multiplies the channels already open in that category.
	 */
	private static void writeGain(Minecraft client, float gain) {
		float clamped = Math.clamp(gain, 0.0F, 1.0F);
		((MusicManagerGainAccessor) client.getMusicManager()).thefourthfrequency$setCurrentGain(clamped);
		client.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, clamped);
	}

	/**
	 * Ticks a wanted score may stay inaudible before the director puts the gain back itself.
	 *
	 * <p>Five seconds. Long enough that no legitimate fade, handover or one-tick edge can reach it,
	 * short enough that a player who has just lost the music gets it back inside a breath.
	 */
	private static final int STUCK_SCORE_TICKS = 100;
	private static int stuckTicks;

	/**
	 * Puts the music back when the fade gain has been left parked at zero.
	 *
	 * <p>{@link #writeGain} already documents the hazard this closes, and it is worth restating because
	 * the consequence is so much larger than the mechanism: the engine multiplies every sound in a
	 * category by one float, the only thing in the game that writes that float is the music manager's
	 * fade, and the fade only runs while {@code currentGain != target}. So any path that leaves the
	 * gain sitting exactly on a target of <em>zero</em> mutes the whole music category for the rest of
	 * the session. Not one track - the category. Every other sound in the game carries on, which is
	 * precisely why it reads as "the BGM disappeared" rather than as anything being broken.
	 *
	 * <p>Keeping the two values in step, which is what {@code writeGain} does, removes the paths this
	 * class knows about. It cannot remove the ones it does not: vanilla stops a faded-out track on its
	 * own schedule, the Alpha resource swap reloads the sound engine underneath all of this, and a
	 * world change tears down every channel. Rather than chase each of those, this asserts the
	 * invariant directly - <em>while a score is wanted and no handover is running, the music gain must
	 * not be pinned at zero</em> - and repairs it when it is violated for long enough to be real.
	 *
	 * <p>Two shapes count as violations, and the distinction matters because only one of them is
	 * unambiguous from the player's chair:
	 * <ul>
	 *   <li>a track <em>is</em> in the channel and the gain is zero - it is playing, holding a
	 *       streaming channel, and inaudible. There is no reading of that which is correct.</li>
	 *   <li>nothing is in the channel, the gain is zero, and what is wanted is one of the tracks that
	 *       is supposed to start promptly. The ordinary playlist is excluded here: it sits between
	 *       songs for minutes at a time by design, and repairing during that gap would cost the next
	 *       track its fade-in for no reason.</li>
	 * </ul>
	 *
	 * <p>A player who has turned music off in the options is left alone - the target is genuinely zero
	 * for them, and there is nothing to repair.
	 */
	private static void tickStuckScore(Minecraft client, Music scoring) {
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		SoundInstance playing = manager.thefourthfrequency$currentMusic();
		float gain = manager.thefourthfrequency$currentGain();
		boolean suspect = scoring != null && handover == Handover.NONE && fadeTarget > 0.0F
				&& gain <= 0.0F && (playing != null || startsPromptly(scoring));
		if (!suspect) {
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks < STUCK_SCORE_TICKS) return;
		stuckTicks = 0;
		TheFourthFrequency.LOGGER.warn(
				"World-interface music was left inaudible (wanted={} playing={} gain={} target={});"
						+ " restoring the music category gain",
				trackName(scoring), playing == null ? "none" : playing.getIdentifier(), gain, fadeTarget);
		writeGain(client, fadeTarget);
		// Hands the channel back as well, so a track that was stopped while silent is re-picked at
		// once rather than after the manager's five-second post-stop delay.
		if (playing == null) manager.thefourthfrequency$setNextSongDelay(0);
	}

	/**
	 * Tracks that answer a moment and must therefore start at that moment.
	 *
	 * <p>Everything here is either handed over or flagged to replace whatever is playing. The ordinary
	 * playlist and the menu theme are deliberately absent: both are allowed to sit silent between
	 * songs, and that silence is not a fault to be repaired.
	 */
	private static boolean startsPromptly(Music wanted) {
		return wanted == PURSUIT || wanted == END || wanted == ENCOUNTER_PHASE_1
				|| wanted == ENCOUNTER_PHASE_2 || wanted == ENCOUNTER_FINAL
				|| wanted == ENDING || wanted == ENDING_FAILURE;
	}

	/**
	 * One line per change of situation: what was asked for, what is actually in the channel, and the
	 * two numbers that decide whether it can be heard.
	 *
	 * <p>{@code gain} is the music manager's own fade value and {@code target} is where it is headed.
	 * A track named in {@code playing} with a gain pinned at zero is a different fault from no track
	 * at all, and the two are indistinguishable from the player's chair - which is the whole reason
	 * this is here.</p>
	 */
	private static void traceScore(Minecraft client) {
		if (!DEBUG) return;
		MusicManagerGainAccessor manager = (MusicManagerGainAccessor) client.getMusicManager();
		SoundInstance instance = manager.thefourthfrequency$currentMusic();
		String playing = instance == null ? "none" : instance.getIdentifier().toString();
		Music wanted = wantedMusic(client);
		if (wanted == debugWanted && handover == debugHandover && playing.equals(debugPlaying)) return;
		debugWanted = wanted;
		debugHandover = handover;
		debugPlaying = playing;
		TheFourthFrequency.LOGGER.info(
				"[music] stage={} wanted={} handover={} playing={} gain={} target={}",
				encounterStage(), trackName(wanted), handover, playing,
				manager.thefourthfrequency$currentGain(), fadeTarget);
	}

	private static String trackName(Music music) {
		return music == null ? "silence" : music.sound().value().location().toString();
	}

	private static WorldInterfaceProtocol.Stage encounterStage() {
		WorldInterfaceSnapshotS2C encounter = WorldInterfaceClientState.snapshot().encounter();
		return encounter == null ? null : encounter.stage();
	}

	/**
	 * Ticks into the summon ceremony, or -1 when one is not running.
	 *
	 * <p>Read off the action wire rather than tracked here: {@code BossActionS2C} already carries
	 * the start tick and the duration, so the client knows exactly how far into the arrival it is
	 * without a second clock that could drift from the server's.
	 */
	private static long summonAge() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return -1L;
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		long now = client.level.getGameTime();
		if (!projection.actionActive(now)) return -1L;
		BossActionS2C action = projection.action();
		if (action.action() != WorldInterfaceProtocol.BossAction.SUMMONING) return -1L;
		return now - action.startTick();
	}

	private static boolean endingScored() {
		return WorldInterfaceVanillaPoemClient.scoredOutcome() != WorldInterfaceProtocol.Outcome.NONE;
	}

	/**
	 * The two endings are scored separately, and a vanilla dragon kill - which reaches the same
	 * screen without arming a poem - keeps the one this mod always played there.
	 */
	private static Music endingMusic() {
		return WorldInterfaceVanillaPoemClient.scoredOutcome() == WorldInterfaceProtocol.Outcome.FAILURE
				? ENDING_FAILURE : ENDING;
	}

	/**
	 * A track that starts the moment it is asked for and repeats, like Musics.CREDITS. Zero delays
	 * are also what lets one of these replace another on the spot: the manager derives the gap
	 * before the next song from the incoming track's own pacing.
	 */
	private static Music immediate(SoundEvent sound) {
		return new Music(Holder.direct(sound), 0, 0, true);
	}

	/**
	 * Same zero pacing, but without the vanilla replace flag: {@link #tickHandover} owns the moment
	 * one of these takes over, and the flag would let the manager cut the outgoing track first.
	 */
	private static Music handedOver(SoundEvent sound) {
		return new Music(Holder.direct(sound), 0, 0, false);
	}

	/** Which half of a two-track exchange the score is in, if it is in one at all. */
	private enum Handover {
		NONE,
		FADING_OUT,
		FADING_IN
	}
}
