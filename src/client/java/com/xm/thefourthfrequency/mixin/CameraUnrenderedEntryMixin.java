package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.UnrenderedLayerClient;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the camera through the floor on the way into the unrendered layer, and holds it in the
 * ceiling on the way out the other side.
 *
 * <p>Both halves of the entry are the same one-line effect: the picture moves vertically and nothing
 * else does. The player's position, collision box, aim and every ray trace are untouched, exactly as
 * in {@code CameraShakeMixin} - which matters more here than it does there, because the alternative
 * that suggests itself is real. Teleporting the body down through the ground would show the same
 * fall and would also mean a player inside blocks: suffocation damage, a shove out of the wall by
 * vanilla's own unstuck handling, and a position the server has to argue with the client about, all
 * for a presentation that costs nothing this way.
 *
 * <p>Placed on {@code setup} TAIL, after Minecraft has finished positioning the camera for the
 * frame, so the offset is the last word and nothing recomputes over it. World-vertical rather than
 * camera-relative - {@code move} works in the camera's own basis, so it would tilt the fall with the
 * player's pitch, and a fall that leans when you look down is not a fall.
 */
@Mixin(Camera.class)
public abstract class CameraUnrenderedEntryMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract Vec3 position();

	// The public accessor rather than a shadowed field. Shadowed fields resolve at class-load time
	// and fail there, not at compile time, whenever the field they name moves or is inherited rather
	// than declared; a getter is part of the class's own surface and cannot go missing quietly.
	@Shadow
	public abstract float getPartialTickTime();

	@Inject(method = "setup", at = @At("TAIL"))
	private void thefourthfrequency$applyUnrenderedEntryOffset(CallbackInfo callback) {
		double offset = UnrenderedLayerClient.cameraOffset(getPartialTickTime());
		if (offset == 0.0D) return;
		setPosition(position().add(0.0D, offset, 0.0D));
	}
}
