package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.content.ModParticles;
import com.xm.thefourthfrequency.networking.StormParticleBatchS2C;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-thread-only, one-tick cosmetic queue. No history is replayed after reconnecting. */
public final class StormParticleBatcher {
	static final int MAX_SAMPLES_PER_TICK = 4096;
	private static final Map<ServerLevel, Queue> PENDING = new LinkedHashMap<>();
	private static boolean initialized;
	private StormParticleBatcher() {}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(StormParticleBatcher::flush);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
	}

	static boolean enqueue(ServerLevel level, ParticleOptions type, double x, double y, double z,
			int count, double dx, double dy, double dz, double speed) {
		int kind = type == ModParticles.STORM_FILAMENT ? 0 : type == ModParticles.STORM_EMBER ? 1
				: type == ModParticles.STORM_SIGIL ? 2 : -1;
		if (kind < 0 || !initialized) return false;
		if (count < 0) return false;
		Queue queue = PENDING.computeIfAbsent(level, ignored -> new Queue());
		if (queue.particles >= MAX_SAMPLES_PER_TICK) return true;
		BlockPos cell = cell(x, y, z);
		queue.addBurst(cell, new StormParticleBatchS2C.Sample(kind, (float) (x - cell.getX()),
				(float) (y - cell.getY()), (float) (z - cell.getZ()), 0,
				(float) dx, (float) dy, (float) dz, (float) speed), count);
		return true;
	}

	static BlockPos cell(double x, double y, double z) {
		return new BlockPos((int) Math.floor(x / 64) * 64, (int) Math.floor(y / 64) * 64,
				(int) Math.floor(z / 64) * 64);
	}

	static final class Queue {
		final Map<BlockPos, List<StormParticleBatchS2C.Sample>> cells = new LinkedHashMap<>();
		int size;
		int particles;
		void addBurst(BlockPos cell, StormParticleBatchS2C.Sample source, int count) {
			if (count < 0) throw new IllegalArgumentException("Negative particle count");
			// Split Gaussian bursts without letting huge input counts create an unbounded loop.
			int remaining = Math.max(1, count);
			while (remaining > 0 && particles < MAX_SAMPLES_PER_TICK) {
				int part = count == 0 ? 0 : Math.min(64, remaining);
				if (!add(cell, new StormParticleBatchS2C.Sample(source.kind(), source.x(), source.y(), source.z(),
						part, source.spreadX(), source.spreadY(), source.spreadZ(), source.speed()))) break;
				remaining -= Math.max(1, part);
			}
		}
		boolean add(BlockPos cell, StormParticleBatchS2C.Sample sample) {
			int cost = Math.max(1, sample.count());
			if (particles + cost > MAX_SAMPLES_PER_TICK) return false;
			cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(sample);
			size++;
			particles += cost;
			return true;
		}
	}

	private static void flush(MinecraftServer server) {
		try {
			PENDING.forEach((level, queue) -> queue.cells.forEach((cell, samples) -> {
				List<StormParticleBatchS2C> packets = new ArrayList<>();
				for (int start = 0; start < samples.size(); start += StormParticleBatchS2C.MAX_SAMPLES) {
					packets.add(new StormParticleBatchS2C(level.dimension().identifier(), cell,
							samples.subList(start, Math.min(samples.size(), start + StormParticleBatchS2C.MAX_SAMPLES))));
				}
				for (var player : level.players()) {
					// Include cell radius so nobody loses a telegraph at a cell boundary.
					if (player.position().distanceToSqr(Vec3.atLowerCornerOf(cell).add(32, 32, 32)) > 568 * 568) continue;
					if (ServerPlayNetworking.canSend(player, StormParticleBatchS2C.TYPE)) {
						packets.forEach(packet -> ServerPlayNetworking.send(player, packet));
					} else {
						for (var sample : samples) level.sendParticles(player, ModParticles.stormType(sample.kind()), true, false,
								cell.getX() + sample.x(), cell.getY() + sample.y(), cell.getZ() + sample.z(),
								sample.count(), sample.spreadX(), sample.spreadY(), sample.spreadZ(), sample.speed());
					}
				}
			}));
		} finally {
			PENDING.clear();
		}
	}

}
