package com.xm.thefourthfrequency.pursuit;

import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * Which rotation a pursuit return arrives on.
 *
 * <p>Facing has to come off the same clock as position, and the return takes its position from
 * wherever the chase actually left the player. Reading the facing back out of the entry record put
 * the two a whole pursuit apart: someone who ran two hundred blocks and turned to watch their own
 * escape route arrived looking wherever they happened to be facing when the warning fired. The black
 * screen hides the teleport; it does not hide the fact that it lifts on a different view than the one
 * it came down on.
 *
 * <p>A mirror return therefore <em>keeps</em> the view rather than re-applying it.
 * {@link Relative#ROTATION} with zero means "add nothing to the current rotation", so not even the
 * tick of mouse movement between the client and the server's copy is swallowed - and
 * {@code ServerPlayer.teleportTo} honours the flag for the head rotation as well as the body.
 *
 * <p>A return that never reached the mirror - an aborted warning phase, or a recovery login - still
 * applies the recorded pair, because there the <em>position</em> comes from that same record and the
 * two halves stay consistent.
 *
 * @param relative the relative-movement flags to hand to {@code teleportTo}
 * @param yaw      the yaw to pass alongside them
 * @param pitch    the pitch to pass alongside them
 */
public record PursuitReturnView(Set<Relative> relative, float yaw, float pitch) {
	public static PursuitReturnView forReturn(boolean fromMirror, float recordedYaw, float recordedPitch) {
		return fromMirror
				? new PursuitReturnView(Relative.ROTATION, 0.0F, 0.0F)
				: new PursuitReturnView(Set.of(), recordedYaw, recordedPitch);
	}

	/** Whether this arrival leaves the player's view exactly as it was. */
	public boolean keepsCurrentView() {
		return relative.contains(Relative.Y_ROT) && relative.contains(Relative.X_ROT)
				&& yaw == 0.0F && pitch == 0.0F;
	}
}
