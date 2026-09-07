package com.xm.thefourthfrequency.audio;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public final class AudioService {
	private AudioService() {
	}

	private static final Map<ServerLevel, SoundDetailBudget> DETAIL_BUDGETS = new java.util.WeakHashMap<>();
	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			DETAIL_BUDGETS.clear();
			AudioService.BOUNDED_CUES.set(0L);
			AudioService.BOUNDED_CUES_BY_EVENT.clear();
		});
	}

	/** Spatial decorative accents are bounded separately, so they cannot consume warning beats. */
	public static void playDetail(ServerLevel level, BlockPos position, SoundEvent event,
			SoundSource source, float volume, float pitch) {
		String voice = event.location() + ":" + (position.getX() >> 3) + ":"
				+ (position.getY() >> 3) + ":" + (position.getZ() >> 3);
		if (DETAIL_BUDGETS.computeIfAbsent(level, ignored -> new SoundDetailBudget(16))
				.admit(level.getGameTime(), voice, 6)) {
			playBounded(level, position, event, source, volume, pitch);
		}
	}

	/** A private sighting must never disclose itself to other players through a sound packet. */
	public static void playForPlayer(net.minecraft.server.level.ServerPlayer player,
			net.minecraft.world.phys.Vec3 position, SoundEvent event, SoundSource source, float relativeVolume) {
		float volume = (float) Math.clamp(RuntimeServices.config().meta().peakVolume()
				* relativeVolume, 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
				net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event),
				source, position.x, position.y, position.z, volume, 1.0F, player.getRandom().nextLong()));
	}

	/**
	 * Plays a narrative cue as the two authored layers it was always meant to be.
	 *
	 * <p>The layering has to happen here rather than in {@code sounds.json}, because the
	 * {@code sounds} array there is a weighted random pool, not a stack: listing two entries
	 * under one event makes the client pick <em>one</em> of them. Four of these cues used to be
	 * written that way and were silently playing half of themselves - a door <em>or</em> a
	 * footstep, never the pair that gives the cue its meaning.</p>
	 */
	public static void play(ServerLevel level, BlockPos position, Cue cue) {
		float volume = (float) Math.clamp(
				RuntimeServices.config().meta().peakVolume() * cue.relativeVolume, 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		level.playSound(null, position, cue.captioned, cue.source, volume, cue.pitch);
		level.playSound(null, position, cue.layer, cue.source, volume * 0.45F,
				Math.min(2.0F, cue.pitch * 1.08F));
	}

	/**
	 * Cues actually handed to the server's broadcast since the counter was last read.
	 *
	 * <p>Exists to settle one question that has cost several wrong answers: when the encounter goes
	 * quiet, is the server no longer emitting, or is the client no longer playing what it is sent?
	 * Those have nothing in common except the symptom, and neither side can answer it alone - the
	 * client cannot see a packet that was never sent, and the server cannot see a sound that was
	 * dropped. Counted here, at the one place every authored encounter cue passes through, and read
	 * by the encounter's own tick alongside the client-side channel watchdog.
	 */
	private static final java.util.concurrent.atomic.AtomicLong BOUNDED_CUES =
			new java.util.concurrent.atomic.AtomicLong();
	/**
	 * The same count, broken down by which cue it was.
	 *
	 * <p>A total answers "is the server still scoring the fight" and nothing else, and that turned out
	 * not to be the question. "Hitting it makes no hurt sound" is a claim about <em>one</em> cue, and a
	 * healthy-looking total is exactly what you would see if every cue except that one were firing -
	 * because the missing one would then not be an audio fault at all, it would be hits that are not
	 * landing. The breakdown is what tells those apart without another round of guessing.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
			BOUNDED_CUES_BY_EVENT = new java.util.concurrent.ConcurrentHashMap<>();

	/** Reads and clears the count of cues emitted since the previous call. */
	public static long takeBoundedCueCount() {
		return BOUNDED_CUES.getAndSet(0L);
	}

	/** Reads and clears the per-cue breakdown, busiest first, as a single log-ready string. */
	public static String takeBoundedCueBreakdown() {
		if (BOUNDED_CUES_BY_EVENT.isEmpty()) return "none";
		StringBuilder summary = new StringBuilder();
		BOUNDED_CUES_BY_EVENT.entrySet().stream()
				.map(entry -> Map.entry(entry.getKey(), entry.getValue().getAndSet(0L)))
				.filter(entry -> entry.getValue() > 0L)
				.sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
				.forEach(entry -> summary.append(summary.isEmpty() ? "" : ", ")
						.append(entry.getKey()).append('=').append(entry.getValue()));
		return summary.isEmpty() ? "none" : summary.toString();
	}

	private static void countCue(SoundEvent event) {
		BOUNDED_CUES.incrementAndGet();
		BOUNDED_CUES_BY_EVENT.computeIfAbsent(event.location().getPath(),
				ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
	}

	/**
	 * Headroom the encounter's own score is mixed into.
	 *
	 * <p>The fight and the track under it are not mixed against each other anywhere - the cues are
	 * authored one at a time against silence, and the music is baked at a fraction of full scale
	 * before it ever reaches the game. Sixteen cues at full relative volume therefore sit on top of a
	 * quiet track, and what the player reports is not "the fight is loud", it is "the music is gone".
	 *
	 * <p>One factor rather than a pass over forty call sites, because the relative volumes between
	 * cues are authored and correct - a lance impact is meant to be louder than a lock tick. What was
	 * wrong is where the whole set sits, and that is one number. Applied to every authored encounter
	 * cue, server-side and client-side alike, so there is a single place to answer "how loud is the
	 * fight" from.
	 *
	 * @see #playWithReach for the one family of cues this cannot reach
	 */
	public static final float ENCOUNTER_MIX_TRIM = 0.72F;

	/** Plays an authored encounter cue while honoring the same configured peak-volume ceiling. */
	public static void playBounded(ServerLevel level, BlockPos position, SoundEvent event,
			SoundSource source, float relativeVolume, float pitch) {
		playBounded(level, net.minecraft.world.phys.Vec3.atCenterOf(position), event, source, relativeVolume, pitch);
	}

	/** Moving emitters keep their actual socket position instead of snapping to block centres. */
	public static void playBounded(ServerLevel level, Vec3 position, SoundEvent event,
			SoundSource source, float relativeVolume, float pitch) {
		float volume = (float) Math.clamp(RuntimeServices.config().meta().peakVolume()
				* Math.clamp(relativeVolume, 0.0F, 1.0F) * ENCOUNTER_MIX_TRIM, 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		countCue(event);
		level.playSound(null, position.x, position.y, position.z, event, source, volume, Math.clamp(pitch, 0.5F, 2.0F));
	}

	/**
	 * How far a borrowed vanilla cue fades over when nothing says otherwise.
	 *
	 * <p>A sound with no {@code attenuation_distance} in its {@code sounds.json} entry gets sixteen
	 * blocks, and the encounter borrows several that have none: the dragon growl and the wither's
	 * ambient that the interface roars with, and vanilla's own explosion under every blast.
	 */
	private static final float VANILLA_FALLOFF_BLOCKS = 16.0F;
	/**
	 * How far a detonation carries.
	 *
	 * <p>The encounter's blasts borrow vanilla's own explosion, which states no attenuation and so
	 * fades out over sixteen blocks - about a sixth of the arena, and less than the distance a player
	 * driven off by a lance has already been thrown. Set near the arena radius the mod's own cues
	 * use, so a detonation is heard by the table rather than only by whoever it landed on.
	 *
	 * <p>Seventy-two rather than the arena's ninety-six, because reach is the <em>only</em> level
	 * control a borrowed cue has - {@link #ENCOUNTER_MIX_TRIM} cannot touch its gain, for the reason
	 * {@link #playWithReach} sets out. The engine's falloff is linear in the reach, so at the forty
	 * blocks or so most of this fight is actually watched from, a seventy-two block blast arrives at
	 * about three quarters of the level a ninety-six block one did: the same trim the rest of the
	 * encounter takes, spent on the loudest thing in it. It still carries well past anyone who is
	 * still in the fight.
	 */
	public static final float BLAST_REACH_BLOCKS = 72.0F;

	/**
	 * Plays a borrowed vanilla cue so that it actually carries across the arena.
	 *
	 * <p><b>Why this cannot go through {@link #playBounded}.</b> The engine's falloff distance is
	 * {@code max(volume, 1) * the sound's own attenuation_distance}, and a vanilla cue states none -
	 * so it fades to nothing over sixteen blocks. {@code playBounded} clamps its volume to one,
	 * because that is the peak-volume safety ceiling, and one is exactly the value that leaves the
	 * falloff at sixteen. Every roar the interface has made from its second form onward has therefore
	 * been emitted twenty-nine to forty-two blocks over the player's head and faded out a third of the
	 * way down. It was not quiet; it was inaudible, and had been since the body first started climbing.
	 *
	 * <p>Vanilla solves this the same way for its own dragon - it plays the growl at volume 2.5 for
	 * exactly this reason - and the volume field is the only reach control the sound protocol has. So
	 * this states the radius it wants and converts. The consequence is honest and worth writing down:
	 * near the source the engine clamps the gain to one, so a cue played this way can be louder at
	 * point blank than the configured peak. These are cues emitted by a body that is never at point
	 * blank - the interface holds station tens of blocks up - and the configured peak still decides
	 * whether the cue plays at all.
	 *
	 * <p><b>{@link #ENCOUNTER_MIX_TRIM} is therefore not applied here, and could not be.</b> Any reach
	 * past sixteen blocks needs a stated volume above one, and the engine clamps the gain of anything
	 * above one to exactly one - so every cue played this way is already at full gain and no factor
	 * short of dropping it back under sixteen blocks would move it. What the trim would buy in level
	 * it would spend in radius, which is the fault this method exists to fix. These cues are trimmed
	 * by their reach instead, where they have one: see {@link #BLAST_REACH_BLOCKS}. The roar keeps its
	 * full radius, because a roar that only the arena hears is the same defect again.
	 */
	public static void playWithReach(ServerLevel level, BlockPos position, SoundEvent event,
			SoundSource source, float relativeVolume, float pitch, float reachBlocks) {
		float volume = (float) Math.clamp(RuntimeServices.config().meta().peakVolume()
				* Math.clamp(relativeVolume, 0.0F, 1.0F), 0.0D, 1.0D);
		if (volume <= 0.0F) return;
		countCue(event);
		level.playSound(null, position, event, source,
				Math.max(volume, reachBlocks / VANILLA_FALLOFF_BLOCKS),
				Math.clamp(pitch, 0.5F, 2.0F));
	}

	public enum Cue {
		EMPTY_VIEWPOINT(ModSounds.EMPTY_VIEWPOINT, ModSounds.LAYER_STONE_STEP,
				SoundSource.AMBIENT, 0.55F, 0.72F),
		EMPTY_BASE(ModSounds.EMPTY_BASE, ModSounds.LAYER_WOODEN_DOOR_CLOSE,
				SoundSource.AMBIENT, 0.62F, 0.78F),
		EMPTY_EXPERIENCE(ModSounds.EMPTY_EXPERIENCE, ModSounds.LAYER_CHEST_CLOSE,
				SoundSource.AMBIENT, 0.50F, 0.60F),
		FOURTH_BAND(ModSounds.FOURTH_BAND, ModSounds.LAYER_BEACON_DEACTIVATE,
				SoundSource.AMBIENT, 0.72F, 0.58F),
		REWORK_JOINT(ModSounds.REWORK_JOINT, ModSounds.LAYER_DEEPSLATE_BREAK,
				SoundSource.HOSTILE, 0.68F, 0.64F),
		ANOMALY_ECHO(ModSounds.ANOMALY_ECHO, ModSounds.LAYER_COMPARATOR_CLICK,
				SoundSource.AMBIENT, 0.46F, 0.68F),
		WINDOW_GLITCH(ModSounds.WINDOW_GLITCH, ModSounds.LAYER_COMPARATOR_CLICK,
				SoundSource.AMBIENT, 0.38F, 0.92F),
		DOOR_CASCADE(ModSounds.DOOR_CASCADE, ModSounds.LAYER_WOODEN_DOOR_CLOSE,
				SoundSource.AMBIENT, 0.66F, 0.61F),
		RULE_COLLAPSE(ModSounds.RULE_COLLAPSE, ModSounds.LAYER_DEEPSLATE_BREAK,
				SoundSource.AMBIENT, 0.82F, 0.48F);

		/** Carries the authored subtitle; this is the layer the player is meant to notice. */
		private final SoundEvent captioned;
		/** Sits underneath at 45%, deliberately without a subtitle of its own. */
		private final SoundEvent layer;
		private final SoundSource source;
		private final float relativeVolume;
		private final float pitch;

		Cue(SoundEvent captioned, SoundEvent layer, SoundSource source,
				float relativeVolume, float pitch) {
			this.captioned = captioned;
			this.layer = layer;
			this.source = source;
			this.relativeVolume = relativeVolume;
			this.pitch = pitch;
		}
	}
}
