package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInterfacePhasePressureTest {
	private static final int FIRST = WorldInterfaceEntity.FORM_LISTENING;
	private static final int SECOND = WorldInterfaceEntity.FORM_CONSUMING;
	private static final int THIRD = WorldInterfaceEntity.FORM_INTERFACE;

	@Test
	@DisplayName("The first form is exactly what it was before phases were tuned separately")
	void firstFormIsTheBaseline() {
		assertEquals(WorldInterfacePhasePressure.LANCE_BASE_RADIUS,
				WorldInterfacePhasePressure.lanceRadius(FIRST), 1.0E-9D);
		assertEquals(WorldInterfaceAttackService.SKY_LANCE_RADIUS,
				WorldInterfacePhasePressure.lanceRadius(FIRST), 1.0E-9D,
				"the policy's baseline and the documented constant must not drift apart");
		assertEquals(WorldInterfaceAttackService.LASER_BURN_INTERVAL_TICKS,
				WorldInterfacePhasePressure.laserBurnIntervalTicks(FIRST));
		assertEquals(WorldInterfaceAttackService.LASER_SCAR_INTERVAL_TICKS,
				WorldInterfacePhasePressure.laserScarIntervalTicks(FIRST));
		assertEquals(WorldInterfaceProtocol.LASER_TRACKING_LAG_TICKS,
				WorldInterfacePhasePressure.laserTrackingLagTicks(FIRST));
	}

	@Test
	@DisplayName("The lance widens once and then again, and never shrinks")
	void lanceWidensEveryForm() {
		double first = WorldInterfacePhasePressure.lanceRadius(FIRST);
		double second = WorldInterfacePhasePressure.lanceRadius(SECOND);
		double third = WorldInterfacePhasePressure.lanceRadius(THIRD);
		assertTrue(second > first, "second form: " + second + " must exceed " + first);
		assertTrue(third > second, "third form: " + third + " must exceed " + second);
		// The circle still has to be escapable inside its own telegraph. The lock plus the charge is
		// 90 ticks, and a sprinting player covers roughly 5.6 blocks a second, so anything under
		// about 25 blocks of radius leaves a real dodge. This is the assertion that stops a later
		// "make it bigger" from quietly turning the lance into an unavoidable hit.
		double telegraphSeconds = (WorldInterfaceProtocol.SKY_LANCE_LOCK_TICKS
				+ WorldInterfaceProtocol.SKY_LANCE_CHARGE_TICKS) / 20.0D;
		assertTrue(third < telegraphSeconds * 5.6D,
				"the widest lance must still be escapable inside its own telegraph: radius " + third
						+ " against " + telegraphSeconds * 5.6D + " blocks of running");
	}

	@Test
	@DisplayName("The beam gets faster and tighter after the first form, and never slower")
	void beamEscalatesAndHolds() {
		assertTrue(WorldInterfacePhasePressure.laserBurnIntervalTicks(SECOND)
						< WorldInterfacePhasePressure.laserBurnIntervalTicks(FIRST),
				"a shorter interval is a faster hit rate");
		assertTrue(WorldInterfacePhasePressure.laserScarIntervalTicks(SECOND)
				< WorldInterfacePhasePressure.laserScarIntervalTicks(FIRST));
		assertTrue(WorldInterfacePhasePressure.laserTrackingLagTicks(SECOND)
						< WorldInterfacePhasePressure.laserTrackingLagTicks(FIRST),
				"less lag is tighter tracking");
		// The third form deliberately holds the second's rate rather than compounding it: its own
		// escalation is the split beam and the second lane, not a beam nobody can leave.
		assertEquals(WorldInterfacePhasePressure.laserBurnIntervalTicks(SECOND),
				WorldInterfacePhasePressure.laserBurnIntervalTicks(THIRD));
		assertEquals(WorldInterfacePhasePressure.laserScarIntervalTicks(SECOND),
				WorldInterfacePhasePressure.laserScarIntervalTicks(THIRD));
		assertEquals(WorldInterfacePhasePressure.laserTrackingLagTicks(SECOND),
				WorldInterfacePhasePressure.laserTrackingLagTicks(THIRD));
		// Every interval has to stay a legal modulus.
		for (int form : new int[] {FIRST, SECOND, THIRD}) {
			assertTrue(WorldInterfacePhasePressure.laserBurnIntervalTicks(form) > 0);
			assertTrue(WorldInterfacePhasePressure.laserScarIntervalTicks(form) > 0);
			assertTrue(WorldInterfacePhasePressure.laserTrackingLagTicks(form) > 0);
		}
	}

	@Test
	@DisplayName("The beam widens with the form, and the drawn edge is the burning edge")
	void beamWidensWithTheForm() {
		assertEquals(WorldInterfacePhasePressure.LASER_BASE_BURN_RADIUS,
				WorldInterfacePhasePressure.laserBurnRadius(FIRST), 1.0E-9D);
		assertTrue(WorldInterfacePhasePressure.beamScale(SECOND)
				> WorldInterfacePhasePressure.beamScale(FIRST));
		assertTrue(WorldInterfacePhasePressure.beamScale(THIRD)
				> WorldInterfacePhasePressure.beamScale(SECOND));
		// The renderer multiplies its widths by beamScale and the server multiplies its burn radius
		// by the same number, so this identity is what keeps the two edges the same edge.
		for (int form : new int[] {FIRST, SECOND, THIRD}) {
			assertEquals(WorldInterfacePhasePressure.LASER_BASE_BURN_RADIUS
							* WorldInterfacePhasePressure.beamScale(form),
					WorldInterfacePhasePressure.laserBurnRadius(form), 1.0E-9D);
		}
	}

	@Test
	@DisplayName("Only the third form splits, and the split straddles the aim symmetrically")
	void thirdFormSplitsSymmetrically() {
		assertEquals(1, WorldInterfacePhasePressure.laserBeamCount(FIRST));
		assertEquals(1, WorldInterfacePhasePressure.laserBeamCount(SECOND));
		assertEquals(2, WorldInterfacePhasePressure.laserBeamCount(THIRD));
		// A single beam must be exactly the old one: no offset at all.
		assertEquals(0.0D, WorldInterfacePhasePressure.laserBeamYawOffset(FIRST, 0), 1.0E-9D);
		assertEquals(0.0D, WorldInterfacePhasePressure.laserBeamYawOffset(SECOND, 0), 1.0E-9D);
		double left = WorldInterfacePhasePressure.laserBeamYawOffset(THIRD, 0);
		double right = WorldInterfacePhasePressure.laserBeamYawOffset(THIRD, 1);
		assertTrue(left < 0.0D && right > 0.0D, "the pair has to straddle the aim: " + left + ", " + right);
		assertEquals(0.0D, left + right, 1.0E-9D, "and straddle it symmetrically");
		// A gap the player can stand in is the whole reason a split is an escalation rather than a
		// wider unavoidable bar, so the halves must not be so far apart that they stop being one
		// attack either.
		assertTrue(right - left > 0.05D && right - left < 0.6D,
				"the split has to be readable as a V: " + (right - left) + " radians apart");
	}

	@Test
	@DisplayName("Swinging a point around the core keeps its height and its distance")
	void swingKeepsHeightAndRange() {
		Vec3 origin = new Vec3(10.0D, 70.0D, -4.0D);
		Vec3 point = new Vec3(40.0D, 62.0D, -4.0D);
		assertSame(point, WorldInterfacePhasePressure.swingAroundY(origin, point, 0.0D),
				"a zero swing must not even allocate");
		Vec3 swung = WorldInterfacePhasePressure.swingAroundY(origin, point, 0.4D);
		assertEquals(point.y, swung.y, 1.0E-9D, "the split fans across the ground, it does not tilt");
		double before = Math.hypot(point.x - origin.x, point.z - origin.z);
		double after = Math.hypot(swung.x - origin.x, swung.z - origin.z);
		assertEquals(before, after, 1.0E-6D, "a swing is a rotation, not a stretch");
	}

	@Test
	@DisplayName("Out-of-range forms clamp instead of throwing")
	void formsClamp() {
		assertEquals(WorldInterfacePhasePressure.lanceRadius(FIRST),
				WorldInterfacePhasePressure.lanceRadius(-4), 1.0E-9D);
		assertEquals(WorldInterfacePhasePressure.lanceRadius(THIRD),
				WorldInterfacePhasePressure.lanceRadius(99), 1.0E-9D);
	}

	/**
	 * The runtime has to actually read the policy, or every number above is a correct answer to a
	 * question nothing asks.
	 */
	@Test
	@DisplayName("The attack service and the client both consult the policy")
	void consumersActuallyConsultThePolicy() throws Exception {
		String attacks = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceAttackService.java"),
				StandardCharsets.UTF_8);
		assertTrue(attacks.contains("WorldInterfacePhasePressure.laserTrackingLagTicks(boss.form())"));
		assertTrue(attacks.contains("WorldInterfacePhasePressure.laserBurnIntervalTicks(boss.form())"));
		assertTrue(attacks.contains("WorldInterfacePhasePressure.laserScarIntervalTicks(boss.form())"));
		assertTrue(attacks.contains("WorldInterfacePhasePressure.lanceRadius(boss.form())"));
		assertFalse(attacks.contains("formRadius(boss, SKY_LANCE_RADIUS)"),
				"the lance's size must have exactly one source");
		// The third form's second lane has to be able to open the two aimed weapons, or "several
		// attacks at once" stays a bolt-and-lash lane.
		assertTrue(attacks.contains("WorldInterfaceAction.SKY_LANCE,")
						&& attacks.contains("WorldInterfaceAction.LASER_SWEEP);"),
				"the volley pool must include both aimed weapons");
		// ...and it must refuse a second beam, which is unreadable rather than harder.
		assertTrue(attacks.contains("action == WorldInterfaceAction.LASER_SWEEP)")
						&& attacks.contains("laneHolds(lane, action)"),
				"a second simultaneous beam has to be swapped out");
		// The renderer has to read the same three numbers the server aims and burns with, or the
		// shaft that is drawn is not the shaft that hurts.
		String renderer = Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency/"
				+ "client_render/WorldInterfaceBeamBatchRenderer.java"), StandardCharsets.UTF_8);
		assertTrue(renderer.contains("WorldInterfacePhasePressure.laserTrackingLagTicks(boss.form())"),
				"the drawn aim has to lag by the same amount the burning one does");
		assertTrue(renderer.contains("WorldInterfacePhasePressure.beamScale(boss.form())"),
				"the drawn width has to scale with the burn radius");
		assertTrue(renderer.contains("WorldInterfacePhasePressure.laserBeamCount(boss.form())")
						&& renderer.contains("WorldInterfacePhasePressure.laserBeamYawOffset("),
				"the renderer has to draw every barrel the server fires");
		assertTrue(renderer.contains("WorldInterfacePhasePressure.swingAroundY("),
				"and derive their angles the same way rather than being sent them");
	}
}
