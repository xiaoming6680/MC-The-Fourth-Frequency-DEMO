package com.xm.thefourthfrequency.client_render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.util.Mth;

public final class WorldInterfaceEnergyOrbModel extends EntityModel<WorldInterfaceEnergyOrbRenderState> {
	private final ModelPart core;
	private final ModelPart[] shards = new ModelPart[8];
	public WorldInterfaceEnergyOrbModel(ModelPart root) {
		super(root);
		core=root.getChild("core");
		for(int i=0;i<shards.length;i++) shards[i]=core.getChild("shard_"+i);
	}
	public static LayerDefinition createLayer() { return WorldInterfaceGeometry.loadEntity("world_interface_energy_orb").layer(); }
	@Override public void setupAnim(WorldInterfaceEnergyOrbRenderState state) {
		super.setupAnim(state);
		core.yRot=state.ageInTicks*.045F;
		core.xRot=state.ageInTicks*.028F;
		for(int i=0;i<shards.length;i++) {
			ModelPart shard=shards[i];
			shard.yRot+=(i%2==0?1:-1)*state.ageInTicks*.06F;
			shard.xRot+=Mth.sin(state.ageInTicks*.085F+i)*.16F;
			float spread=1+Mth.sin(state.ageInTicks*.08F-i*.7F)*.10F;
			shard.xScale=shard.yScale=shard.zScale=spread;
		}
	}
}
