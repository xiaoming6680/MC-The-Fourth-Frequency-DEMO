package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;

/** Attack boundaries shared by damage, the rig and sound playback. */
public final class WorldInterfaceAttackMotion {
	public static final int LASER_FIRE_TICK = WorldInterfaceProtocol.LASER_WARNING_TICKS;
	public static final int LASER_END_TICK = LASER_FIRE_TICK + WorldInterfaceProtocol.LASER_SWEEP_TICKS;
	public static final int LASER_RECOVERY_TICKS = 18;
	public static final int LASER_DURATION_TICKS = LASER_END_TICK + LASER_RECOVERY_TICKS;
	public static final int TENDRIL_RECOVER_DELAY_TICKS = 5;
	public static final int TENDRIL_DURATION_TICKS = WorldInterfaceProtocol.TENDRIL_WARNING_TICKS
			+ WorldInterfaceProtocol.TENDRIL_STRIKE_INTERVAL_TICKS * WorldInterfaceProtocol.TENDRIL_STRIKE_COUNT + 8;

	private WorldInterfaceAttackMotion() {}

	/** Half-open intervals: the first recovery tick cannot burn or keep a beam voice alive. */
	public static int laserPhase(long elapsedTicks) {
		if (elapsedTicks < 0 || elapsedTicks >= LASER_DURATION_TICKS) return 0;
		if (elapsedTicks < LASER_FIRE_TICK) return 1;
		return elapsedTicks < LASER_END_TICK ? 2 : 3;
	}

	public static float laserCharge(long elapsedMillis) {
		if (elapsedMillis < 0 || elapsedMillis >= LASER_DURATION_TICKS * 50L) return -1;
		if (elapsedMillis < LASER_FIRE_TICK * 50L)
			return HorrorMotion.ease(elapsedMillis / (LASER_FIRE_TICK * 50.0F));
		if (elapsedMillis < LASER_END_TICK * 50L) return 1;
		return 1 - HorrorMotion.ease((elapsedMillis - LASER_END_TICK * 50L) / (LASER_RECOVERY_TICKS * 50.0F));
	}

	public static int tendrilStrikeTick(int strike) {
		if (strike < 0 || strike >= WorldInterfaceProtocol.TENDRIL_STRIKE_COUNT)
			throw new IllegalArgumentException("Invalid tendril strike: " + strike);
		return WorldInterfaceProtocol.TENDRIL_WARNING_TICKS + WorldInterfaceProtocol.TENDRIL_STRIKE_TELEGRAPH_TICKS
				+ strike * WorldInterfaceProtocol.TENDRIL_STRIKE_INTERVAL_TICKS;
	}

	/** The distal bend peaks on contact; joints nearer the root lead it into the swing. */
	public static float tendrilJointContactSeconds(int strike, int jointSerial, int totalJoints) {
		return tendrilStrikeTick(strike) / 20.0F - (totalJoints - jointSerial) * .018F;
	}
}
