package com.xm.thefourthfrequency.terminal;

/**
 * When one terminal passes a line to another, and what it is allowed to say.
 *
 * <p>Multiplayer in this mod is otherwise several single-player games sharing a map. The anomalies
 * are personal, the pursuits happen in a private mirror, and the one existing acknowledgement that
 * anybody else is out there is a single line when somebody is taken. This gives the terminals a
 * bandwidth of their own: stand near another holder for long enough and, some minutes later,
 * something of theirs surfaces in your log.
 *
 * <h2>What may cross, and what may not</h2>
 *
 * <ul>
 * <li><b>Never attributed.</b> No name reaches the other terminal. A line that said who it came from
 * would be a chat channel, and the loneliness this mod is built on does not survive a chat
 * channel.</li>
 * <li><b>Never actionable.</b> Only the shape of what the peer's terminal logged travels, never a
 * coordinate, a target or a lead. A relayed line the player could walk to would make standing next
 * to somebody a way to farm the investigation.</li>
 * <li><b>Never a pursuit.</b> The private correction layer is the one place a player is genuinely
 * alone, and nothing about it leaves. The existing "a terminal nearby has stopped resolving" line
 * already says exactly as much as anyone is allowed to know.</li>
 * <li><b>Consented in both directions.</b> Outbound needs an explicit yes on the profile's
 * companions question; an unanswered profile does not relay, because silence is not consent.</li>
 * </ul>
 */
public final class TerminalRelayPolicy {
	/**
	 * How close two holders must be, in blocks.
	 *
	 * <p>Thirty-two, the same radius the anomaly layer already uses to decide whether a player counts
	 * as alone. Reusing it means the two rules cannot drift into disagreeing about what "together"
	 * means - the same distance that stops a shared anomaly from being thrown at you is the distance
	 * at which your terminals start hearing each other.
	 */
	public static final double RANGE_BLOCKS = 32.0D;

	/** How long the two have to stay in range before anything is captured, in ticks. */
	public static final long CONTACT_TICKS = 20L * 60L;

	/** The earliest and latest a captured line can surface, in ticks. */
	public static final long MIN_DELAY_TICKS = 20L * 60L * 4L;
	public static final long MAX_DELAY_TICKS = 20L * 60L * 11L;

	/** No terminal receives more than one relayed line in this window. */
	public static final long COOLDOWN_TICKS = 20L * 60L * 18L;

	/** At most this many undelivered lines are held for one player. */
	public static final int MAX_PENDING = 4;

	/**
	 * The shapes a relayed line can take.
	 *
	 * <p>Deliberately a tiny closed set rather than the peer's actual text. What travels is that
	 * their terminal recorded something of this kind - which is all the bandwidth the fiction claims
	 * to have, and also the only version of this that cannot leak a lead.
	 */
	public enum Shape {
		/** Their terminal logged a milestone. */
		PROGRESS("relay_progress"),
		/** Their terminal logged something it could not resolve. */
		UNRESOLVED("relay_unresolved"),
		/**
		 * A line the receiver themselves logged, long ago, arriving as though from somewhere else.
		 *
		 * <p>Stage five only. Nothing leaves either player - the text is the receiver's own history
		 * handed back to them with the wrong origin, so there is no boundary to cross and nothing to
		 * leak. The world is not passing a message here; it has stopped being able to tell two
		 * holders apart, which is the thing this whole stage is about.
		 */
		SELF_ECHO("relay_self_echo");

		private final String type;

		Shape(String type) {
			this.type = type;
		}

		public String type() {
			return type;
		}

		public static boolean isRelayType(String type) {
			if (type == null) return false;
			for (Shape shape : values()) if (shape.type.equals(type)) return true;
			return false;
		}
	}

	private TerminalRelayPolicy() {
	}

	/** Whether a holder's own profile lets their terminal pass anything on. */
	public static boolean maysend(int[] senderProfile) {
		return ProfilePreference.relaysOut(senderProfile);
	}

	/** Whether a holder's own profile lets their terminal accept anything. */
	public static boolean mayReceive(int[] receiverProfile) {
		return ProfilePreference.relaysIn(receiverProfile);
	}

	public static boolean inRange(double distanceSquared) {
		return distanceSquared <= RANGE_BLOCKS * RANGE_BLOCKS;
	}

	/** Whether this terminal is off cooldown and has room for another pending line. */
	public static boolean mayQueue(long lastDeliveredTick, long now, int pending) {
		if (pending >= MAX_PENDING) return false;
		return lastDeliveredTick <= 0L || now - lastDeliveredTick >= COOLDOWN_TICKS;
	}

	/**
	 * How long a captured line waits before it surfaces.
	 *
	 * <p>Minutes, not seconds, and never a fixed figure. The delay is the whole effect: a line that
	 * arrived while the other player was still standing there would read as a status bar. Arriving
	 * long after they walked off is what makes it read as something that took time to get here.
	 */
	public static long delayTicks(long seed) {
		long span = MAX_DELAY_TICKS - MIN_DELAY_TICKS + 1L;
		return MIN_DELAY_TICKS + Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 17, span);
	}

	/**
	 * Which shape a capture takes.
	 *
	 * @param anomalyStage the receiver's own stage, which is the only thing that unlocks the echo
	 * @param seed         stable per capture
	 */
	public static Shape shapeFor(int anomalyStage, boolean peerHasProgress, long seed) {
		if (anomalyStage >= 5 && Math.floorMod(seed >>> 7, 3L) == 0L) return Shape.SELF_ECHO;
		return peerHasProgress ? Shape.PROGRESS : Shape.UNRESOLVED;
	}
}
