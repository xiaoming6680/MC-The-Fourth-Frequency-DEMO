package com.xm.thefourthfrequency.art;

import com.google.gson.*;
import com.xm.thefourthfrequency.client_render.*;
import com.xm.thefourthfrequency.entity.WorldInterfaceClips;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Samples the actual game animation models for an inspectable Blockbench motion library. */
public final class EntityMotionExport {
	private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Path OUT = Path.of("docs/art/entities/motion");
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Files.createDirectories(OUT);
		var root = WatcherModel.createBodyLayer().bakeRoot();
		var watcher = new WatcherModel(root);
		write("watcher", List.of(
				sample("watching", 10, root, watcher, new WatcherRenderState(), (s,t)->s.ageInTicks=t*20),
				sample("gaze_lock", 4, root, watcher, new WatcherRenderState(), (s,t)->{s.ageInTicks=t*20;s.gazeProgress=Math.min(1,t/2);} )));
		root=BacteriaModel.createBodyLayer().bakeRoot(); var bacteria=new BacteriaModel(root);
		write("bacteria",List.of(sample("breathing",8,root,bacteria,new BacteriaRenderState(),(s,t)->s.ageInTicks=t*20),
				sample("pursuit_gait",5,root,bacteria,new BacteriaRenderState(),(s,t)->{
					s.ageInTicks=t*20;s.walkAnimationPos=t*13;s.walkAnimationSpeed=.8F;s.walkSurge=.8F;})));
		root=HimModel.createBodyLayer().bakeRoot();var him=new HimModel(root);
		write("him",List.of(sample("held_listening",9.85F,root,him,new HimRenderState(),(s,t)->s.ageInTicks=t*20),
				sample("head_first_turn",4,root,him,new HimRenderState(),(s,t)->{s.ageInTicks=80+t*20;s.yRot=(float)Math.sin(t*Math.PI/2)*30;})));
		for(int stage=1;stage<=3;stage++){
			final int form=stage;
			root=WorldInterfaceGeometry.loadEntity("rework_body_stage_"+stage).layer().bakeRoot();
			var rework=new ReworkBodyModel(root,stage);
			write("rework_body_stage_"+stage,List.of(
					sample("listening",9,root,rework,new ReworkBodyRenderState(),(s,t)->{s.ageInTicks=t*20;s.formStage=form;}),
					sample("pursuit",6,root,rework,new ReworkBodyRenderState(),(s,t)->{s.ageInTicks=t*20;s.formStage=form;s.walkAnimationPos=t*14;s.walkAnimationSpeed=.7F;}),
					sample("morph",2.4F,root,rework,new ReworkBodyRenderState(),(s,t)->{s.ageInTicks=60+t*20;s.formStage=form;s.morphTargetStage=Math.min(3,form+1);s.morphTicks=Math.max(0,40-(int)(t*20));})));
		}
		root=StabilityAnchorModel.createLayer().bakeRoot();var anchor=new StabilityAnchorModel(root);
		write("stability_anchor",List.of(sample("relay_tension",8,root,anchor,new StabilityAnchorRenderState(),(s,t)->s.ageInTicks=t*20),
				sample("collapse",.8F,root,anchor,new StabilityAnchorRenderState(),(s,t)->{s.ageInTicks=100+t*20;s.collapseAge=t*20;})));
		root=WorldInterfaceGeometry.loadEntity("world_interface_energy_orb").layer().bakeRoot();
		var orb=new WorldInterfaceEnergyOrbModel(root);
		write("world_interface_energy_orb",List.of(sample("unstable_flight",6,root,orb,new WorldInterfaceEnergyOrbRenderState(),(s,t)->s.ageInTicks=t*20)));
		root=WorldInterfaceModel.createLayer().bakeRoot();var storm=new WorldInterfaceModel(root);
		List<JsonObject> stormClips=new ArrayList<>();
		for(int stage=0;stage<3;stage++){
			final int form=stage;
			stormClips.add(sample("form_"+(stage+1)+"_idle",6,root,storm,new WorldInterfaceRenderState(),(s,t)->{s.form=form;s.ageInTicks=t*20;}));
		}
		for(int action=1;action<=14;action++){
			if(action==3)continue;
			final int id=action;
			float duration=0;for(var clip:WorldInterfaceClips.clipsForAction(action))duration=Math.max(duration,clip.lengthSeconds());
			if(duration<=0)continue;
			stormClips.add(sample("action_"+action,duration,root,storm,new WorldInterfaceRenderState(),(s,t)->{
				s.form=2;s.ageInTicks=t*20;s.actionId=id;s.actionAgeMillis=Math.round(t*1000);}));
		}
		write("world_interface",stormClips);
	}
	private static void write(String name,List<JsonObject> clips)throws Exception{
		JsonObject result=new JsonObject();result.addProperty("source","Game model, sampled at 10 Hz; runtime retains continuous curves");
		result.add("clips",JSON.toJsonTree(clips));Files.writeString(OUT.resolve(name+".json"),JSON.toJson(result));
		System.out.println(name+": "+clips.size()+" runtime motion studies");
	}
	private static <S extends EntityRenderState> JsonObject sample(String name,float length,ModelPart root,
			EntityModel<S> model,S state,BiConsumer<S,Float> pose)throws Exception{
		Map<String,ModelPart> parts=new LinkedHashMap<>();walk(root,"",parts);
		Map<String,float[][][]> tracks=new LinkedHashMap<>();int frames=Math.round(length*10)+1;
		for(String part:parts.keySet())tracks.put(part,new float[3][frames][3]);
		for(int k=0;k<frames;k++){
			float t=k*length/(frames-1);pose.accept(state,t);model.setupAnim(state);
			for(var entry:parts.entrySet()){
				var p=entry.getValue();var bind=p.getInitialPose();var channels=tracks.get(entry.getKey());
				channels[0][k]=new float[]{-(p.xRot-bind.xRot())*180/(float)Math.PI,-(p.yRot-bind.yRot())*180/(float)Math.PI,(p.zRot-bind.zRot())*180/(float)Math.PI};
				channels[1][k]=new float[]{-(p.x-bind.x()),-(p.y-bind.y()),p.z-bind.z()};
				channels[2][k]=new float[]{p.xScale,p.yScale,p.zScale};
			}
		}
		JsonObject clip=new JsonObject(),output=new JsonObject();clip.addProperty("name",name);clip.addProperty("length",length);
		String[] labels={"rotation","position","scale"};
		for(var entry:tracks.entrySet()){
			JsonObject channels=new JsonObject();
			for(int c=0;c<3;c++){
				var values=entry.getValue()[c];boolean useful=false;
				for(float[] v:values)for(float x:v){if(!Float.isFinite(x))throw new AssertionError(name+" non-finite pose");if(Math.abs(x-(c==2?1:0))>.0001F)useful=true;}
				if(!useful)continue;
				JsonArray keys=new JsonArray();for(int k=0;k<frames;k++){
					JsonArray key=new JsonArray();key.add(k*length/(frames-1));for(float x:values[k])key.add(Math.round(x*100000)/100000.0);keys.add(key);
				}channels.add(labels[c],keys);
			}
			if(!channels.isEmpty())output.add(entry.getKey(),channels);
		}
		clip.add("tracks",output);return clip;
	}
	@SuppressWarnings("unchecked") private static void walk(ModelPart part,String path,Map<String,ModelPart> output)throws Exception{
		if(!path.isEmpty())output.put(path,part);
		var field=ModelPart.class.getDeclaredField("children");field.setAccessible(true);
		for(var child:((Map<String,ModelPart>)field.get(part)).entrySet())walk(child.getValue(),path.isEmpty()?child.getKey():path+"/"+child.getKey(),output);
	}
}
