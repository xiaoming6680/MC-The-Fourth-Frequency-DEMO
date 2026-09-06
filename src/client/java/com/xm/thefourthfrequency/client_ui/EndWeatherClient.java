package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.world.EndWeatherPolicy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * Makes it rain in the End, and thunder over it.
 *
 * <p>Two switches, and this holds both. Vanilla already carries a rain level for the End - the
 * dimension type has skylight, so the value is tracked and synchronised like any other - and the
 * only reason no drops appear is that the biome declares no precipitation, which makes the renderer
 * resolve every column to {@code NONE} and return before it draws. This writes the level, and
 * {@code WeatherEffectRendererEndRainMixin} answers the column question. Between them the End rains
 * with vanilla's own renderer, its own particles and its own ambience, rather than with a
 * reimplementation of any of it.
 *
 * <p><b>Client-side, and it has to be.</b> Rain, thunder and their timers live in one
 * {@code ServerLevelData} shared by every dimension in the save, so making the End rain by writing
 * the server's weather would be making the Overworld rain as a permanent side effect of the End's
 * atmosphere. The rain level written here belongs to the End's own {@code ClientLevel} object,
 * which exists only while the player is in the End and is discarded when they leave; nothing about
 * it survives, reaches another dimension, or reaches another player.
 *
 * <p><b>And it leaks into nothing at all, including the client's own rules.</b> That is worth
 * writing down because it is not obvious and it was not designed: {@code Level.canHaveWeather()}
 * names the End explicitly and answers false for it, whatever the rain level is, so
 * {@code isRaining()} and {@code isRainingAt()} stay false in the End no matter what this writes.
 * Every rule in the game that asks "is it raining" therefore keeps the answer it has always had.
 *
 * <p>Nothing that draws the rain asks that question. The weather extraction, the surface particles
 * and the rain ambience are all gated on {@code getRainLevel() > 0} and nothing else, so the End
 * ends up with weather that can be seen and heard and that the world does not believe in - which is
 * both the safest possible version of this feature and, for this mod, the correct one.
 */
public final class EndWeatherClient {
	/**
	 * How far a clap of thunder is placed from the listener, in blocks.
	 *
	 * <p>Placed rather than played at the ear, so it arrives from a direction. The bearing comes
	 * from the shared schedule, so a clap that came from the north came from the north for everyone
	 * at the table - which is the whole reason the storm reads as one sky.
	 */
	private static final double THUNDER_OFFSET_BLOCKS = 96.0D;
	/** Blocks above the listener the clap comes from. The sky, not the ground. */
	private static final double THUNDER_LIFT_BLOCKS = 64.0D;
	/**
	 * How far the clap carries, in blocks.
	 *
	 * <p>Vanilla's thunder states no attenuation of its own, so it fades to nothing sixteen blocks
	 * from its origin - and this origin is a hundred and sixteen blocks away by construction. Stated
	 * as a reach and converted the same way {@code AudioService.playWithReach} does it, or the clap
	 * would be played and never heard.
	 */
	private static final float THUNDER_REACH_BLOCKS = 256.0F;
	/** Distance vanilla gives a cue whose sounds.json entry states no attenuation. */
	private static final float VANILLA_FALLOFF_BLOCKS = 16.0F;
	private static final float THUNDER_VOLUME = 0.7F;

	private static boolean initialized;
	/** Ticks the local player has been in the End, which is what the fade-in runs on. */
	private static long ticksInEnd;
	/** Last tick a clap was played, so a paused or stuttering client cannot double it. */
	private static long lastThunderTick = Long.MIN_VALUE;
	/**
	 * Ticks since the interface was beaten, or negative while it has not been.
	 *
	 * <p>Counted here rather than derived from the world clock because the encounter's own tick is
	 * not what this is measuring - it is measuring how long this client has been looking at a sky
	 * with nothing left in it. A player who wins, leaves and comes back gets the storm already gone
	 * rather than a second fade, which is what the jump to a finished fade below is for.</p>
	 */
	private static long clearedTicks = -1L;

	private EndWeatherClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(EndWeatherClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	/** For tests and teardown paths; the next visit to the End starts its fade from zero again. */
	public static void reset() {
		ticksInEnd = 0L;
		lastThunderTick = Long.MIN_VALUE;
		clearedTicks = -1L;
	}

	/**
	 * Whether this column should be treated as raining, for the renderer's precipitation question.
	 *
	 * <p>The chunk test is vanilla's own and is kept deliberately: {@code getPrecipitationAt} answers
	 * {@code NONE} for a column whose chunk is not loaded, and answering {@code RAIN} there would
	 * draw rain over the void the client has not been sent yet.
	 */
	public static boolean rainsAt(Level level, BlockPos pos) {
		if (level == null || pos == null) return false;
		if (level.dimension() != Level.END) return false;
		if (level.getRainLevel(1.0F) <= 0.0F) return false;
		return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()));
	}

	private static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			reset();
			return;
		}
		if (level.dimension() != Level.END) {
			// Nothing is written back. Every other dimension keeps whatever weather the server told
			// it to have, and this feature must be invisible outside the End.
			ticksInEnd = 0L;
			return;
		}
		if (client.isPaused()) return;
		ticksInEnd++;
		tickClearing();
		long gameTime = level.getGameTime();
		// setRainLevel writes both the current and the previous value, so there is no partial-tick
		// interpolation between a stale zero and this - which is the difference between rain and a
		// strobe. It is re-applied every tick rather than once, because the server still sends this
		// dimension rain-level updates and whichever wrote last would otherwise win.
		level.setRainLevel(EndWeatherPolicy.rainLevel(ticksInEnd, gameTime, clearedTicks));
		// Nothing new is thrown into a sky that is on its way out. The claps already in flight are
		// vanilla sounds and finish on their own.
		if (clearedTicks < 0L) tickThunder(client, level, gameTime);
	}

	/**
	 * Advances - or, on arrival, skips - the storm's departure.
	 *
	 * <p>Walking back into a won End starts the counter at the far end of the fade instead of at
	 * zero, so the sky the player left cleared is the sky they come back to. The distinction is
	 * whether this client saw the fight end: it did if the counter starts while the rain has not yet
	 * finished fading <em>in</em>.</p>
	 */
	private static void tickClearing() {
		if (!defeated()) {
			clearedTicks = -1L;
			return;
		}
		if (clearedTicks < 0L) {
			clearedTicks = ticksInEnd <= EndWeatherPolicy.FADE_IN_TICKS
					? EndWeatherPolicy.CLEAR_FADE_OUT_TICKS : 0L;
			return;
		}
		if (clearedTicks < EndWeatherPolicy.CLEAR_FADE_OUT_TICKS) clearedTicks++;
	}

	/**
	 * Whether the interface has been beaten.
	 *
	 * <p>A won encounter only. A lost one leaves the world collapsing, and its sky is not something
	 * the player has taken anything away from.</p>
	 */
	private static boolean defeated() {
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		return projection.encounter() != null
				&& projection.encounter().outcome() == WorldInterfaceProtocol.Outcome.SUCCESS;
	}

	private static void tickThunder(Minecraft client, ClientLevel level, long gameTime) {
		if (gameTime == lastThunderTick || !EndWeatherPolicy.isThunderTick(gameTime)) return;
		lastThunderTick = gameTime;
		float volume = (float) Math.clamp(
				RuntimeServices.config().meta().peakVolume() * THUNDER_VOLUME, 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		float bearing = EndWeatherPolicy.thunderBearing(gameTime);
		double x = client.player.getX() + Math.cos(bearing) * THUNDER_OFFSET_BLOCKS;
		double z = client.player.getZ() + Math.sin(bearing) * THUNDER_OFFSET_BLOCKS;
		double y = client.player.getY() + THUNDER_LIFT_BLOCKS;
		level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
				Math.max(volume, THUNDER_REACH_BLOCKS / VANILLA_FALLOFF_BLOCKS),
				EndWeatherPolicy.thunderPitch(gameTime), false);
	}
}
