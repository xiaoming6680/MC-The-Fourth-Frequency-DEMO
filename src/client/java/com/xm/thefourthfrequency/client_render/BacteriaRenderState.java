package com.xm.thefourthfrequency.client_render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Client-only presentation data. The entity's behaviour stays server authoritative.
 *
 * <p>{@code walkSurge} is how hard it is currently moving, normalised to nought-to-one. The model
 * leans on it, which is the only readable difference between the thing coming for you and the thing
 * having lost you - it has no face to show either with.
 */
public final class BacteriaRenderState extends LivingEntityRenderState {
	public float walkSurge;
}
