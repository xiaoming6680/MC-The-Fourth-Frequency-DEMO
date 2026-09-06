package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.client_render.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntityMcpGeometryTest {
	@org.junit.jupiter.api.BeforeAll static void bootstrap(){
		net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
	}
	@Test
	void exportedAssetsBakeAndBindToTheRealAnimationModels() {
		new WatcherModel(WatcherModel.createBodyLayer().bakeRoot());
		new BacteriaModel(BacteriaModel.createBodyLayer().bakeRoot());
		new HimModel(HimModel.createBodyLayer().bakeRoot());
		new StabilityAnchorModel(StabilityAnchorModel.createLayer().bakeRoot());
		new ReworkBodyModel(ReworkBodyModel.createStage1Layer().bakeRoot(),1);
		new ReworkBodyModel(ReworkBodyModel.createStage2Layer().bakeRoot(),2);
		new ReworkBodyModel(ReworkBodyModel.createStage3Layer().bakeRoot(),3);
		new WorldInterfaceEnergyOrbModel(WorldInterfaceEnergyOrbModel.createLayer().bakeRoot());
	}
	@Test void everyVisibleEntityHasMovingBonesAndResetsBetweenSamples() {
		var root=BacteriaModel.createBodyLayer().bakeRoot();var bacteria=new BacteriaModel(root);
		var b=new BacteriaRenderState();b.walkAnimationSpeed=.8F;b.walkSurge=.8F;
		checkMotion(root,bacteria,b,(s,t)->{s.ageInTicks=t;s.walkAnimationPos=t*.7F;},12);
		root=HimModel.createBodyLayer().bakeRoot();
		checkMotion(root,new HimModel(root),new HimRenderState(),(s,t)->s.ageInTicks=t,2);
		root=StabilityAnchorModel.createLayer().bakeRoot();
		checkMotion(root,new StabilityAnchorModel(root),new StabilityAnchorRenderState(),(s,t)->s.ageInTicks=t,9);
		root=WorldInterfaceEnergyOrbModel.createLayer().bakeRoot();
		checkMotion(root,new WorldInterfaceEnergyOrbModel(root),new WorldInterfaceEnergyOrbRenderState(),(s,t)->s.ageInTicks=t,9);
		for(int stage=1;stage<=3;stage++){
			root=WorldInterfaceGeometry.loadEntity("rework_body_stage_"+stage).layer().bakeRoot();
			checkMotion(root,new ReworkBodyModel(root,stage),new ReworkBodyRenderState(),(s,t)->s.ageInTicks=t,12);
		}
	}
	private static <S extends net.minecraft.client.renderer.entity.state.EntityRenderState> void checkMotion(
			net.minecraft.client.model.geom.ModelPart root, net.minecraft.client.model.EntityModel<S> model,S state,
			java.util.function.BiConsumer<S,Float> pose,int minimumMoving){
		var parts=root.getAllParts();
		pose.accept(state,12F);model.setupAnim(state);var before=parts.stream().map(EntityMcpGeometryTest::values).toList();
		pose.accept(state,70F);model.setupAnim(state);var after=parts.stream().map(EntityMcpGeometryTest::values).toList();
		int moving=0;
		for(int i=0;i<parts.size();i++)if(!java.util.Arrays.equals(before.get(i),after.get(i)))moving++;
		assertTrue(moving>=minimumMoving,model.getClass().getSimpleName()+" has only "+moving+" moving bones");
		model.setupAnim(state);
		for(int i=0;i<parts.size();i++)assertArrayEquals(after.get(i),values(parts.get(i)),1e-6F,"Pose accumulated between frames");
	}
	private static float[] values(net.minecraft.client.model.geom.ModelPart p){
		return new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale};
	}
	@Test
	void repeatedAnimationSamplesDoNotAccumulateOffsetsAndMoveTheNewDigits() {
		var root = WatcherModel.createBodyLayer().bakeRoot();
		var model = new WatcherModel(root);
		var state = new WatcherRenderState();
		var digit = root.getChild("torso").getChild("left_arm").getChild("forearm").getChild("hand").getChild("digit_0");
		state.ageInTicks = 65;
		model.setupAnim(state);
		float angle = digit.xRot;
		model.setupAnim(state);
		assertEquals(angle,digit.xRot,1e-6);
		state.ageInTicks = 12;
		model.setupAnim(state);
		assertTrue(Math.abs(angle-digit.xRot)>.2F);
	}
}
