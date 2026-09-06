package com.xm.thefourthfrequency.ending;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;

/**
 * Particle emission for things that are meant to be seen from across the island.
 *
 * <p>{@code ServerLevel.sendParticles(type, x, y, z, ...)} only sends to players within
 * <b>32 blocks</b> of the emission point. That default is right for almost every particle in the
 * game, and wrong for most of what this encounter emits: the arena is a hundred and sixty blocks
 * across, the interface hovers tens of blocks up with a body that is itself thirty-three blocks
 * wide, and its two beam weapons reach the whole island. An emitter written the ordinary way is
 * therefore an effect that exists only for whoever happens to be standing next to it - which, for a
 * telegraph whose entire purpose is that the rest of the table can see the shot coming, means the
 * telegraph does not exist.
 *
 * <p>It is not a difference anything catches. The particles are emitted, the server is doing exactly
 * what the code says, and the only symptom is that seven of eight players never mention seeing it.
 *
 * <p>So the encounter's long-range presentation goes through here instead, with the limiter
 * overridden (512 blocks, comfortably the whole island) and {@code alwaysShow} left alone - a player
 * who has turned their particle setting down has asked for fewer particles and still gets that.
 *
 * <p>This is deliberately not the default for everything the fight emits. Ground marks, impact
 * bursts and lock rings are local events read by the person standing in them, and widening those
 * would spend bandwidth on eight clients to draw something seven of them cannot make out anyway.
 * The rule is the honest one: use this when the thing being drawn is bigger than 32 blocks, further
 * away than 32 blocks, or addressed to somebody who is.
 */
final class ArenaParticles {
	/** Distance vanilla sends an override-limiter particle, in blocks. Documentation only. */
	static final double REACH_BLOCKS = 512.0D;

	private ArenaParticles() {
	}

	static <T extends ParticleOptions> void emit(ServerLevel level, T type, double x, double y,
			double z, int count, double spreadX, double spreadY, double spreadZ, double speed) {
		if (!StormParticleBatcher.enqueue(level, type, x, y, z, count, spreadX, spreadY, spreadZ, speed)) {
			level.sendParticles(type, true, false, x, y, z, count, spreadX, spreadY, spreadZ, speed);
		}
	}
}
