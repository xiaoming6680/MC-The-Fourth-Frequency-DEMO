package com.xm.thefourthfrequency.client_render;

import com.xm.thefourthfrequency.content.ModParticles;
import com.xm.thefourthfrequency.entity.HorrorMotion;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/** Short, directional energy traces with a luminous centre and a fading broken edge. */
public final class StormParticle extends SingleQuadParticle {
	private final float baseSize, spin;
	private final int kind;
	private StormParticle(ClientLevel level,double x,double y,double z,double dx,double dy,double dz,
			SpriteSet sprites,RandomSource random,int kind){
		super(level,x,y,z,sprites.first());this.kind=kind;
		setParticleSpeed(dx,dy,dz);hasPhysics=false;friction=kind==1?.93F:.97F;gravity=kind==1?.035F:0;
		lifetime=kind==1?12+random.nextInt(7):kind==2?16:10+random.nextInt(7);
		baseSize=kind==1?.10F+random.nextFloat()*.08F:kind==2?.24F:.13F+random.nextFloat()*.09F;
		quadSize=baseSize;roll=random.nextFloat()*(float)Math.PI*2;oRoll=roll;
		spin=(random.nextBoolean()?1:-1)*(kind==2?.025F:.08F);
		if(kind==1)setColor(.88F,.76F,1F);else if(kind==2)setColor(.57F,.27F,.88F);else setColor(.76F,.34F,1F);
		alpha=0;
	}
	@Override public void tick(){
		super.tick();oRoll=roll;roll+=spin;
		float progress=age/(float)lifetime;
		alpha=HorrorMotion.ease(progress/.14F)*(1-HorrorMotion.ease((progress-.35F)/.65F))*(kind==2?.58F:.80F);
		quadSize=baseSize*(kind==1?1-progress*.60F:.7F+progress*.7F);
	}
	@Override protected Layer getLayer(){return Layer.TRANSLUCENT;}
	@Override protected int getLightColor(float partialTick){return LightTexture.FULL_BRIGHT;}
	public static void initialize(){
		ParticleFactoryRegistry.getInstance().register(ModParticles.STORM_FILAMENT,sprites->new Factory(sprites,0));
		ParticleFactoryRegistry.getInstance().register(ModParticles.STORM_EMBER,sprites->new Factory(sprites,1));
		ParticleFactoryRegistry.getInstance().register(ModParticles.STORM_SIGIL,sprites->new Factory(sprites,2));
	}
	private record Factory(SpriteSet sprites,int kind) implements ParticleProvider<SimpleParticleType>{
		@Override public Particle createParticle(SimpleParticleType type,ClientLevel level,double x,double y,double z,
				double dx,double dy,double dz,RandomSource random){return new StormParticle(level,x,y,z,dx,dy,dz,sprites,random,kind);}
	}
}
