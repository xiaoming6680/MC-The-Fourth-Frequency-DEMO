package com.xm.thefourthfrequency.networking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Cosmetic samples only; combat geometry and damage never depend on delivery. */
public record StormParticleBatchS2C(Identifier dimension, BlockPos origin, List<Sample> samples)
		implements CustomPacketPayload {
	public static final int MAX_SAMPLES = 256;
	public static final Type<StormParticleBatchS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
			"thefourthfrequency", "storm_particles_v1"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StormParticleBatchS2C> CODEC =
			StreamCodec.of(StormParticleBatchS2C::write, StormParticleBatchS2C::read);

	public StormParticleBatchS2C {
		Objects.requireNonNull(dimension);
		origin = Objects.requireNonNull(origin).immutable();
		samples = List.copyOf(samples);
		if (samples.isEmpty() || samples.size() > MAX_SAMPLES) throw new IllegalArgumentException("Particle batch size");
	}

	/** Coordinates are relative to a 64-block cell, preserving precision near the world border. */
	public record Sample(int kind, float x, float y, float z, int count,
			float spreadX, float spreadY, float spreadZ, float speed) {
		public Sample {
			if (kind < 0 || kind > 2 || count < 0 || count > 64) throw new IllegalArgumentException("Particle kind/count");
			if (!valid(x) || !valid(y) || !valid(z) || !valid(spreadX) || !valid(spreadY)
					|| !valid(spreadZ) || !valid(speed)) throw new IllegalArgumentException("Particle value");
		}
		private static boolean valid(float value) { return Float.isFinite(value) && Math.abs(value) <= 1024; }
	}

	private static void write(RegistryFriendlyByteBuf buf, StormParticleBatchS2C batch) {
		buf.writeIdentifier(batch.dimension);
		buf.writeBlockPos(batch.origin);
		buf.writeVarInt(batch.samples.size());
		for (Sample sample : batch.samples) {
			buf.writeByte(sample.kind);
			buf.writeFloat(sample.x); buf.writeFloat(sample.y); buf.writeFloat(sample.z);
			buf.writeByte(sample.count);
			buf.writeFloat(sample.spreadX); buf.writeFloat(sample.spreadY); buf.writeFloat(sample.spreadZ);
			buf.writeFloat(sample.speed);
		}
	}

	private static StormParticleBatchS2C read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		BlockPos origin = buf.readBlockPos();
		int size = WorldInterfaceProtocol.readBoundedSize(buf, MAX_SAMPLES, "storm particles");
		List<Sample> samples = new ArrayList<>(size);
		for (int i = 0; i < size; i++) samples.add(new Sample(buf.readUnsignedByte(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUnsignedByte(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
		return new StormParticleBatchS2C(dimension, origin, samples);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
