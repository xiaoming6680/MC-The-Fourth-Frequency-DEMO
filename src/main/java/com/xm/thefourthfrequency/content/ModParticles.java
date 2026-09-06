package com.xm.thefourthfrequency.content;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;

/** Dedicated storm particles shared by every attack and encounter transition. */
public final class ModParticles {
	public static final SimpleParticleType STORM_FILAMENT=register("storm_filament");
	public static final SimpleParticleType STORM_EMBER=register("storm_ember");
	public static final SimpleParticleType STORM_SIGIL=register("storm_sigil");
	private ModParticles(){}
	private static SimpleParticleType register(String name){
		return Registry.register(BuiltInRegistries.PARTICLE_TYPE,Identifier.fromNamespaceAndPath("thefourthfrequency",name),FabricParticleTypes.simple(true));
	}
	public static void initialize(){}
}
