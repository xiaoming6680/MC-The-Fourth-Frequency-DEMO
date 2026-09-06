package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.audio.MusicRotationPolicy;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-draws a background track the current pass has already used.
 *
 * <p>This is the only place the choice is made. {@code AbstractSoundInstance#resolve} is where an
 * event id becomes a concrete file - {@code this.sound = events.getSound(this.random)} - and it is
 * the last point at which the pick is still changeable; by the time the engine sees the instance it
 * is holding a {@code Sound}, not a pool. {@code WeighedSoundEvents} itself is the wrong hook
 * because it does not carry its own id, so a mixin there could not tell the score apart from the
 * eight attack cues that are supposed to draw freely.
 *
 * <p>Scoped twice over: to this mod's namespace, and to the score events
 * {@link MusicRotationPolicy#rotates} names. Every other sound in the game, including this mod's
 * own, resolves exactly as it did.
 *
 * <p>Re-drawing rather than picking an index keeps vanilla's weighting intact. The pool entries may
 * carry weights; choosing "some other entry" by hand would quietly flatten them, whereas drawing
 * again and rejecting a repeat leaves the relative odds of everything else untouched.
 *
 * <p>What ends a pass is a count, not a run of failed draws. The pool's size is read through
 * {@link WeighedSoundEventsPoolAccessor} and compared against what the pass has used, so "every
 * track has played" is known rather than inferred. The earlier version inferred it - it gave up
 * after a fixed number of re-draws and treated that as the pool being spent - which quietly dropped
 * the last unplayed track of about one pass in thirty once the gameplay playlist reached ten
 * tracks. Losing a track the player has not heard is the one outcome this whole mechanism exists to
 * prevent, so the guess is gone.
 */
@Mixin(AbstractSoundInstance.class)
public abstract class AbstractSoundInstanceRotationMixin {
	@Shadow protected Sound sound;
	@Shadow @Final protected Identifier identifier;
	@Shadow protected RandomSource random;

	@Inject(method = "resolve", at = @At("RETURN"))
	private void thefourthfrequency$rotateScoreTrack(SoundManager manager,
			CallbackInfoReturnable<WeighedSoundEvents> callback) {
		if (!TheFourthFrequency.MOD_ID.equals(identifier.getNamespace())
				|| !MusicRotationPolicy.rotates(identifier.getPath())) {
			return;
		}
		WeighedSoundEvents events = callback.getReturnValue();
		if (events == null || sound == null || random == null) return;
		String event = identifier.getPath();
		int poolSize = ((WeighedSoundEventsPoolAccessor) events).thefourthfrequency$pool().size();
		if (MusicRotationPolicy.passComplete(event, poolSize)) {
			thefourthfrequency$startNewPass(event, events);
			return;
		}
		// The pass still has something unplayed in it, and the count above is what says so - so this
		// loop is looking for something that is definitely there rather than deciding whether it
		// exists. Running out of attempts therefore costs one repeated track and nothing else: the
		// pass stays open and whatever it had not reached yet is still owed to the player.
		for (int attempt = 0; attempt < MusicRotationPolicy.rerollCeiling(poolSize)
				&& MusicRotationPolicy.playedThisPass(event, sound.getLocation().toString()); attempt++) {
			Sound redrawn = events.getSound(random);
			if (redrawn == null) break;
			sound = redrawn;
		}
		// Recorded after the loop settles, so the memory is what the player actually hears rather
		// than the first draw. A track already in the pass is not recorded twice - the set absorbs
		// it - so a re-draw that ran out of attempts cannot advance the pass on a repeat.
		MusicRotationPolicy.remember(event, sound.getLocation().toString());
	}

	/**
	 * Opens the next pass. The pool is fully eligible again - except for the track that just ended,
	 * because a pass boundary is not a licence to play the same piece twice running. That is the old
	 * adjacency rule, kept as the seam of the new one.
	 */
	private void thefourthfrequency$startNewPass(String event, WeighedSoundEvents events) {
		for (int attempt = 0; attempt < MusicRotationPolicy.SEAM_REROLLS
				&& MusicRotationPolicy.followsItself(event, sound.getLocation().toString()); attempt++) {
			Sound redrawn = events.getSound(random);
			if (redrawn == null) break;
			sound = redrawn;
		}
		MusicRotationPolicy.startNewPass(event, sound.getLocation().toString());
	}
}
