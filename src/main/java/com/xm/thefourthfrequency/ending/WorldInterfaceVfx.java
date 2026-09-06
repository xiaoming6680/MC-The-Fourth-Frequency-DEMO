package com.xm.thefourthfrequency.ending;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The encounter's shape vocabulary: rings, helices, arcs, shells and shafts.
 *
 * <p>Every emitter in the fight used to be written where it was used, which meant they were all
 * boxes - {@code sendParticles} with a spread and a count, which draws a cloud. A cloud is the one
 * thing a particle system does without being asked, and eight of them at different sizes is what
 * "the fight has effects" looks like when nobody has drawn a single shape. What reads as an effect
 * is structure: a ring has a centre and an edge, a helix has a direction, an arc has two ends and a
 * path between them, and all three survive being seen from a hundred blocks away and from behind.
 *
 * <p>So this holds the shapes and nothing else. No state, no randomness that is not derived from a
 * seed the caller passes, no authority: every method is a pure function from arguments to particle
 * packets, safe to call on any tick, safe to lose entirely, and identical on every client because
 * the phase terms come from the world clock rather than from {@code Random}.
 *
 * <p><b>On budget.</b> The rest of this package is careful about the particle channel, and the
 * reasoning there is still correct. This class is deliberately not: the user asked for the fight to
 * be spent on rather than metered, so the counts here are chosen for how the shot looks and the
 * throttling is left to the call sites, which know which of them are once-per-attack and which run
 * every tick. Everything routes through {@link ArenaParticles} because the arena is a hundred and
 * sixty blocks across and vanilla's own 32-block particle limiter would hide most of it.
 */
public final class WorldInterfaceVfx {
	/** The interface's violet, used wherever a coloured particle carries the palette. */
	public static final float VIOLET_RED = 0.78F;
	public static final float VIOLET_GREEN = 0.44F;
	public static final float VIOLET_BLUE = 1.0F;
	/** The hotter core colour, for the instant something discharges. */
	public static final float CORE_RED = 0.92F;
	public static final float CORE_GREEN = 0.78F;
	public static final float CORE_BLUE = 1.0F;

	private WorldInterfaceVfx() {
	}

	/**
	 * The arena's violet, as a rune rather than as a dot.
	 *
	 * <p>This used to be a tinted {@code ENTITY_EFFECT} - the potion swirl - which is the smallest,
	 * flattest particle in the game and the one every player's eye has been trained to ignore for
	 * ten years. Sixty rings drawn out of it read as haze. {@code WITCH} is the same violet at
	 * several times the size, with a shape that survives being seen at fifty blocks, which is the
	 * distance most of this fight is watched from.</p>
	 */
	public static ParticleOptions violet() {
		return ParticleTypes.WITCH;
	}

	/**
	 * The hot core, as flame rather than as a dot.
	 *
	 * <p>{@code SOUL_FIRE_FLAME} draws a trail behind itself, so a ring of it is a ring that has
	 * <em>turned</em> and a helix of it is visibly travelling. That motion is most of what makes the
	 * beams and the shells read as energy instead of as geometry.</p>
	 */
	public static ParticleOptions core() {
		return ParticleTypes.SOUL_FIRE_FLAME;
	}

	/** Sigils, for the rings that are supposed to look written rather than drawn. */
	public static ParticleOptions sigil() {
		return ParticleTypes.ENCHANT;
	}

	/** The colour-carrying mote, for the few places that genuinely need an arbitrary tint. */
	public static ParticleOptions tinted(float red, float green, float blue) {
		return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, red, green, blue);
	}

	/** A white flash, sized by the caller through its emission count. */
	public static ParticleOptions flash() {
		return ColorParticleOption.create(ParticleTypes.FLASH, CORE_RED, CORE_GREEN, CORE_BLUE);
	}

	/**
	 * A ring in the horizontal plane.
	 *
	 * @param phase turns the ring, in radians, so a caller driving it off the world clock gets a
	 *              ring that spins rather than one that sits still and flickers
	 */
	public static void ring(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int points, double phase, double speed) {
		if (points <= 0 || radius <= 0.0D) return;
		for (int index = 0; index < points; index++) {
			double angle = phase + Math.PI * 2.0D * index / points;
			ArenaParticles.emit(level, type, centre.x + Math.cos(angle) * radius, centre.y,
					centre.z + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, speed);
		}
	}

	/**
	 * A ring around an arbitrary axis.
	 *
	 * <p>The one that makes a body read as having orientation. A stack of horizontal rings is a
	 * cake; rings tilted against each other are a thing spinning in three dimensions.
	 */
	public static void orientedRing(ServerLevel level, ParticleOptions type, Vec3 centre, Vec3 axis,
			double radius, int points, double phase, double speed) {
		if (points <= 0 || radius <= 0.0D) return;
		Vec3 normal = axis.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : axis.normalize();
		Vec3 first = perpendicular(normal);
		Vec3 second = normal.cross(first);
		for (int index = 0; index < points; index++) {
			double angle = phase + Math.PI * 2.0D * index / points;
			Vec3 point = centre
					.add(first.scale(Math.cos(angle) * radius))
					.add(second.scale(Math.sin(angle) * radius));
			ArenaParticles.emit(level, type, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, speed);
		}
	}

	/**
	 * A ring thrown outward, for the moment something lands.
	 *
	 * <p>Different from {@link #ring} in what it says rather than in what it draws: the motes carry
	 * outward velocity, so a single emission is a wave leaving rather than a circle appearing.
	 */
	public static void shockRing(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int points, double outwardSpeed, double lift) {
		if (points <= 0) return;
		for (int index = 0; index < points; index++) {
			double angle = Math.PI * 2.0D * index / points;
			double x = Math.cos(angle);
			double z = Math.sin(angle);
			level.sendParticles(type, true, false,
					centre.x + x * radius, centre.y, centre.z + z * radius,
					0, x * outwardSpeed, lift, z * outwardSpeed, 1.0D);
		}
	}

	/**
	 * A helix wound around the line from {@code from} to {@code to}.
	 *
	 * <p>The shape that gives a beam a direction. A shaft of evenly spaced motes is the same picture
	 * whichever end fired it; a helix has a handedness and a pitch, and the pitch is what the eye
	 * reads as speed.
	 *
	 * @param strands how many separate threads are wound around the axis, offset evenly in phase
	 * @param phase   advanced by the caller each emission so the threads travel
	 */
	public static void helix(ServerLevel level, ParticleOptions type, Vec3 from, Vec3 to, double radius,
			int samples, int strands, double turns, double phase) {
		Vec3 bearing = to.subtract(from);
		double length = bearing.length();
		if (length < 1.0E-4D || samples <= 0 || strands <= 0) return;
		Vec3 forward = bearing.scale(1.0D / length);
		Vec3 first = perpendicular(forward);
		Vec3 second = forward.cross(first);
		for (int strand = 0; strand < strands; strand++) {
			double offset = Math.PI * 2.0D * strand / strands;
			for (int index = 0; index <= samples; index++) {
				double along = index / (double) samples;
				double angle = phase + offset + along * turns * Math.PI * 2.0D;
				Vec3 point = from.add(bearing.scale(along))
						.add(first.scale(Math.cos(angle) * radius))
						.add(second.scale(Math.sin(angle) * radius));
				ArenaParticles.emit(level, type, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
	}

	/**
	 * A jagged arc between two points.
	 *
	 * <p>Deterministic: the path is derived from {@code seed} and nothing else, so the same tick of
	 * the same encounter draws the same bolt on every client and a replay is the replay. The
	 * displacement is perpendicular to the run, which is what keeps it a bolt rather than a wobble.
	 */
	public static void arc(ServerLevel level, ParticleOptions type, Vec3 from, Vec3 to, int segments,
			double jitter, long seed) {
		Vec3 bearing = to.subtract(from);
		double length = bearing.length();
		if (length < 1.0E-4D || segments <= 0) return;
		Vec3 forward = bearing.scale(1.0D / length);
		Vec3 first = perpendicular(forward);
		Vec3 second = forward.cross(first);
		for (int index = 1; index < segments; index++) {
			double along = index / (double) segments;
			// Zero at both ends and widest in the middle, so the bolt is attached to what it joins.
			double taper = Math.sin(along * Math.PI);
			long mixed = mix(seed + index);
			double first0 = ((mixed & 0xFFFFL) / (double) 0xFFFF - 0.5D) * 2.0D;
			double second0 = (((mixed >>> 16) & 0xFFFFL) / (double) 0xFFFF - 0.5D) * 2.0D;
			Vec3 point = from.add(bearing.scale(along))
					.add(first.scale(first0 * jitter * taper))
					.add(second.scale(second0 * jitter * taper));
			ArenaParticles.emit(level, type, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/**
	 * A hollow shell of motes thrown outward from a point.
	 *
	 * <p>Spherical Fibonacci rather than three boxed spreads: a box with a spread fills its volume,
	 * so it reads as fog, while an even shell reads as a surface leaving. The difference is the
	 * whole distinction between "an explosion happened" and "something burst".
	 */
	public static void shell(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int points, double speed) {
		if (points <= 0) return;
		double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
		for (int index = 0; index < points; index++) {
			double y = 1.0D - 2.0D * (index + 0.5D) / points;
			double ring = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
			double angle = golden * index;
			double x = Math.cos(angle) * ring;
			double z = Math.sin(angle) * ring;
			level.sendParticles(type, true, false,
					centre.x + x * radius, centre.y + y * radius, centre.z + z * radius,
					0, x * speed, y * speed, z * speed, 1.0D);
		}
	}

	/** A vertical shaft of light standing on {@code base}. */
	public static void column(ServerLevel level, ParticleOptions type, Vec3 base, double height,
			double radius, int samples, double phase) {
		if (samples <= 0 || height <= 0.0D) return;
		for (int index = 0; index <= samples; index++) {
			double along = index / (double) samples;
			double angle = phase + along * Math.PI * 4.0D;
			ArenaParticles.emit(level, type,
					base.x + Math.cos(angle) * radius, base.y + along * height,
					base.z + Math.sin(angle) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/**
	 * Straight spokes leaving a point along the ground.
	 *
	 * <p>What an impact does to the floor. A ring says how far the force reached; spokes say it came
	 * from the middle, which a ring on its own cannot.
	 */
	public static void spokes(ServerLevel level, ParticleOptions type, Vec3 centre, double length,
			int count, int samples, double phase, double lift) {
		if (count <= 0 || samples <= 0) return;
		for (int spoke = 0; spoke < count; spoke++) {
			double angle = phase + Math.PI * 2.0D * spoke / count;
			double x = Math.cos(angle);
			double z = Math.sin(angle);
			for (int index = 1; index <= samples; index++) {
				double along = index / (double) samples;
				ArenaParticles.emit(level, type,
						centre.x + x * length * along, centre.y + lift * (1.0D - along),
						centre.z + z * length * along, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
	}

	/**
	 * A sigil circle: concentric rings of different particles, counter-rotating, with spokes.
	 *
	 * <p>Three rings rather than one, because a single ring of anything reads as a circle and three
	 * nested rings read as a <em>diagram</em> - something that was drawn on purpose by something that
	 * draws. The outer ring is runes, the middle is light, the inner is sigils being pulled toward
	 * the centre, and the spokes tie them into one figure instead of three that happen to share a
	 * middle.</p>
	 *
	 * @param phase turns the whole figure; the rings counter-rotate around it
	 */
	public static void runeCircle(ServerLevel level, Vec3 centre, double radius, double phase,
			int density) {
		int points = Math.max(8, density);
		ring(level, violet(), centre, radius, points, phase, 0.0D);
		ring(level, ParticleTypes.END_ROD, centre, radius * 0.72D, points - 4, -phase * 1.4D, 0.0D);
		// Drawn inward: ENCHANT travels along its velocity, so a ring of it aimed at the middle is a
		// figure being written rather than one sitting there.
		for (int index = 0; index < points / 2; index++) {
			double angle = phase * 0.6D + Math.PI * 2.0D * index / (points / 2);
			double x = Math.cos(angle);
			double z = Math.sin(angle);
			double outer = radius * 0.45D;
			level.sendParticles(sigil(), true, false,
					centre.x + x * outer, centre.y + 0.1D, centre.z + z * outer,
					0, -x * 0.6D, 0.05D, -z * 0.6D, 1.0D);
		}
		spokes(level, ParticleTypes.END_ROD, centre, radius, 6, 3, phase * 0.3D, 0.0D);
	}

	/**
	 * The full detonation: everything that says something just went off, in one call.
	 *
	 * <p>Layered by how far each part carries. The flash and the sonic front are what a player on the
	 * far side of the island sees; the emitters and the firework shell are for whoever is close
	 * enough for the middle of it to fill their screen; the shock rings are the shape that is still
	 * legible a second later once the smoke has covered everything else.</p>
	 *
	 * <p>{@code SONIC_BOOM} is used one at a time and never more: it is a single enormous ring, and
	 * two of them overlapping is one unreadable white smear.</p>
	 */
	public static void detonation(ServerLevel level, Vec3 centre, double radius, int rings) {
		ArenaParticles.emit(level, flash(), centre.x, centre.y, centre.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		ArenaParticles.emit(level, ParticleTypes.SONIC_BOOM, centre.x, centre.y, centre.z,
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		ArenaParticles.emit(level, ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z,
				1 + rings / 2, radius * 0.3D, radius * 0.2D, radius * 0.3D, 0.0D);
		// Firework sparks carry a trail and a twinkle, which is what keeps a burst alive for the
		// second after the emitters have already finished.
		shell(level, ParticleTypes.FIREWORK, centre, radius * 0.5D, 90, 0.9D);
		shell(level, core(), centre, radius * 0.75D, 120, 0.75D);
		for (int wave = 1; wave <= Math.max(1, rings); wave++) {
			shockRing(level, wave == 1 ? flash() : core(), centre, radius * 0.55D * wave,
					26 + wave * 10, 0.55D + wave * 0.35D, 0.22D);
		}
		spokes(level, violet(), centre, radius * 2.2D, 10, 6, 0.0D, 0.9D);
	}

	/**
	 * A heavy column: a flame core, two counter-wound helices and a collar at each end.
	 *
	 * <p>A column drawn as a line of motes is a dotted line however many motes it has. What makes a
	 * beam look like a beam is a bright centre with something turning around it, which is three
	 * separate shapes sharing one axis.</p>
	 */
	public static void beacon(ServerLevel level, Vec3 base, double height, double radius,
			double phase, int samples) {
		if (height <= 0.0D || samples <= 0) return;
		Vec3 top = base.add(0.0D, height, 0.0D);
		for (int index = 0; index <= samples; index++) {
			double along = index / (double) samples;
			ArenaParticles.emit(level, core(), base.x, base.y + height * along, base.z,
					1, radius * 0.12D, 0.0D, radius * 0.12D, 0.0D);
		}
		helix(level, violet(), base, top, radius, samples, 2, height * 0.06D, phase);
		helix(level, ParticleTypes.END_ROD, base, top, radius * 0.55D, samples, 2,
				height * 0.09D, -phase);
		ring(level, ParticleTypes.END_ROD, base, radius * 1.5D, 18, phase, 0.0D);
		ring(level, violet(), top, radius * 1.2D, 14, -phase, 0.0D);
	}

	/**
	 * A regular polygon lying flat on the ground.
	 *
	 * <p>The one shape family a circle cannot be mistaken for. Three sides and four sides are
	 * distinguishable at a glance and at distance in a way that two circles of different radius are
	 * not, which is what makes this the right primitive for telling two telegraphs apart.
	 *
	 * @param sides   3 or more; below that nothing is drawn
	 * @param density points drawn along each side
	 */
	public static void polygon(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int sides, int density, double phase) {
		if (sides < 3 || density < 1 || radius <= 0.0D) return;
		for (int side = 0; side < sides; side++) {
			double from = phase + Math.PI * 2.0D * side / sides;
			double to = phase + Math.PI * 2.0D * (side + 1) / sides;
			double fromX = centre.x + Math.cos(from) * radius;
			double fromZ = centre.z + Math.sin(from) * radius;
			double toX = centre.x + Math.cos(to) * radius;
			double toZ = centre.z + Math.sin(to) * radius;
			for (int step = 0; step < density; step++) {
				double along = step / (double) density;
				ArenaParticles.emit(level, type, fromX + (toX - fromX) * along, centre.y,
						fromZ + (toZ - fromZ) * along, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
	}

	/**
	 * Short marks pointing inward from a circle, like arrowheads closing on the middle.
	 *
	 * <p>Spokes say "from the centre". These say "toward it", which is the difference between a
	 * telegraph for something arriving and one for something being taken.
	 */
	public static void chevrons(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int count, double length, double phase) {
		if (count <= 0 || radius <= 0.0D) return;
		for (int index = 0; index < count; index++) {
			double angle = phase + Math.PI * 2.0D * index / count;
			double x = Math.cos(angle);
			double z = Math.sin(angle);
			for (int step = 0; step < 3; step++) {
				double along = radius - length * step / 3.0D;
				double spread = 0.18D * step;
				ArenaParticles.emit(level, type, centre.x + x * along - z * spread, centre.y,
						centre.z + z * along + x * spread, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				ArenaParticles.emit(level, type, centre.x + x * along + z * spread, centre.y,
						centre.z + z * along - x * spread, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
	}

	/** Discrete marks around a circle, for a telegraph that is counting something. */
	public static void beads(ServerLevel level, ParticleOptions type, Vec3 centre, double radius,
			int count, double phase) {
		ring(level, type, centre, radius, Math.max(1, count), phase, 0.0D);
	}

	/** Any unit vector at right angles to {@code direction}. */
	private static Vec3 perpendicular(Vec3 direction) {
		Vec3 axis = Math.abs(direction.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
		Vec3 side = direction.cross(axis);
		return side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
	}

	private static long mix(long value) {
		long mixed = value * 0x9E3779B97F4A7C15L;
		mixed ^= mixed >>> 29;
		mixed *= 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 32;
		return mixed & Long.MAX_VALUE;
	}
}
