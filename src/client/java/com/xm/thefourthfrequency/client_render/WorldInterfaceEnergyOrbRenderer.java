package com.xm.thefourthfrequency.client_render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xm.thefourthfrequency.entity.WorldInterfaceEnergyOrbEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** A fractured three-dimensional plasma core; its scale follows the server's hittable projectile. */
public final class WorldInterfaceEnergyOrbRenderer extends EntityRenderer<WorldInterfaceEnergyOrbEntity, WorldInterfaceEnergyOrbRenderState> {
    public static final ModelLayerLocation MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath("thefourthfrequency", "world_interface_energy_orb"), "main");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("thefourthfrequency", "textures/entity/world_interface_energy_orb.png");
    private final WorldInterfaceEnergyOrbModel model;
    public WorldInterfaceEnergyOrbRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new WorldInterfaceEnergyOrbModel(context.bakeLayer(MODEL_LAYER));
    }
    @Override public WorldInterfaceEnergyOrbRenderState createRenderState() { return new WorldInterfaceEnergyOrbRenderState(); }
    @Override public void extractRenderState(WorldInterfaceEnergyOrbEntity entity, WorldInterfaceEnergyOrbRenderState state, float partialTick) {
        super.extractRenderState(entity,state,partialTick);
        state.scale = Math.clamp(entity.orbScale(),WorldInterfaceEnergyOrbEntity.MIN_SCALE,WorldInterfaceEnergyOrbEntity.MAX_SCALE);
    }
    @Override public void submit(WorldInterfaceEnergyOrbRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.isInvisible) {
            poseStack.pushPose();
            poseStack.scale(-state.scale,-state.scale,state.scale);
            model.setupAnim(state);
            collector.submitModel(model,state,poseStack,RenderTypes.entityCutoutNoCull(TEXTURE),
                    LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY,-1,null,state.outlineColor,null);
            poseStack.popPose();
        }
        super.submit(state,poseStack,collector,camera);
    }
}
