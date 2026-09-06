package com.xm.thefourthfrequency.client_render;

import com.xm.thefourthfrequency.content.ModParticles;
import com.xm.thefourthfrequency.networking.StormParticleBatchS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;

/** Distance, graphics-setting and per-tick limits apply independently on every client. */
public final class StormParticleReceiver {
	private static net.minecraft.resources.Identifier lastDimension;
	private static long lastTick = Long.MIN_VALUE;
	private static int spent;
	private static long batchesReceived;
	private static long samplesReceived;
	private static long particlesSpawned;
	private StormParticleReceiver() {}

	public static void clear() {
		lastDimension = null; lastTick = Long.MIN_VALUE; spent = 0;
		batchesReceived = 0; samplesReceived = 0; particlesSpawned = 0;
	}
	public record Diagnostics(long batches, long samples, long spawned) {}
	public static Diagnostics diagnostics() { return new Diagnostics(batchesReceived, samplesReceived, particlesSpawned); }

	public static void accept(Minecraft client, StormParticleBatchS2C packet) {
		ClientLevel level = client.level;
		if (level == null || client.player == null || !level.dimension().identifier().equals(packet.dimension())) return;
		batchesReceived++;
		samplesReceived += packet.samples().size();
		if (!packet.dimension().equals(lastDimension) || level.getGameTime() != lastTick) {
			lastDimension = packet.dimension(); lastTick = level.getGameTime(); spent = 0;
		}
		int stride = client.options.particles().get() == ParticleStatus.MINIMAL ? 4
				: client.options.particles().get() == ParticleStatus.DECREASED ? 2 : 1;
		var random = level.random;
		for (var sample : packet.samples()) {
			double x = packet.origin().getX() + (double) sample.x();
			double y = packet.origin().getY() + (double) sample.y();
			double z = packet.origin().getZ() + (double) sample.z();
			if (client.player.distanceToSqr(x, y, z) > 512 * 512) continue;
			var type = ModParticles.stormType(sample.kind());
			for (int i = 0; i < Math.max(1, sample.count()); i++) {
				if (spent >= 2048) return;
				if (spent++ % stride != 0) continue;
				particlesSpawned++;
				// Vanilla count=0 is a directed particle; positive count is a Gaussian burst.
				if (sample.count() == 0) {
					level.addParticle(type, true, false, x, y, z, sample.spreadX() * sample.speed(),
							sample.spreadY() * sample.speed(), sample.spreadZ() * sample.speed());
				} else {
					level.addParticle(type, true, false, x + random.nextGaussian() * sample.spreadX(),
							y + random.nextGaussian() * sample.spreadY(), z + random.nextGaussian() * sample.spreadZ(),
							random.nextGaussian() * sample.speed(), random.nextGaussian() * sample.speed(),
							random.nextGaussian() * sample.speed());
				}
			}
		}
	}
}
