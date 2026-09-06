package com.xm.thefourthfrequency.networking;

import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.Objects;

/** Stable wire identifiers and shared bounds for the v3 world-interface protocol. */
public final class WorldInterfaceProtocol {
	/**
	 * Bumped to 3 for the ritual window and the explicit summon.
	 *
	 * <p>{@code AltarSnapshotS2C} gained two fields and {@code AltarAction} gained a value, so a v2
	 * client talking to a v3 server would mis-read the altar rather than fail; {@link #requireVersion}
	 * turns that into a clean refusal instead. Retired wire ids are kept rather than reused - see
	 * {@link AltarStatus#ROSTER_CHANGED}.</p>
	 */
	public static final int VERSION = 3;
	public static final int MAX_PARTICIPANTS = 8;
	public static final int MAX_GATEWAYS = 20;
	public static final int MAX_ANCHORS = 10;
	public static final int ANCHOR_MASK = 0x03FF;
	/** Must equal {@code WorldInterfacePolicy.COLLAPSE_DURATION_TICKS}; the HUD clock reads this one. */
	public static final long COLLAPSE_DURATION_TICKS = 12_000L;
	/**
	 * Ticks the laser spends locked on a player before the sweep starts. Shared so the client beam
	 * can widen against the exact same clock the server damages on, instead of guessing from the
	 * action duration.
	 */
	public static final int LASER_WARNING_TICKS = 90;
	/** Ticks the fired laser keeps glowing after the action envelope has already completed. */
	/**
	 * Render-only. At six ticks the fired beam faded from its very first frame, so the shot itself
	 * was a sub-third-of-a-second flash that players read as the telegraph simply vanishing. The
	 * beam now holds before it decays; see WorldInterfaceBeamBatchRenderer#extractLaser.
	 */
	public static final int LASER_AFTERGLOW_TICKS = 24;
	/** Ticks the laser keeps sweeping after the lock resolves. */
	public static final int LASER_SWEEP_TICKS = 40;
	/**
	 * How far behind its target the sweeping beam trails, in ticks. This is the whole mechanic: a
	 * player who keeps moving outruns the beam, and a player who stands still does not. Client and
	 * server both resolve the aim point from this lag, so the shaft that is drawn is the shaft that
	 * burns.
	 *
	 * <p>The lag has to beat the burn radius or running is not actually an escape, only a slower
	 * death. Half a second of sprint is about 2.8 blocks against a 2.2-block burn, which leaves the
	 * beam visibly chasing a moving player and landing squarely on a stationary one.</p>
	 */
	public static final int LASER_TRACKING_LAG_TICKS = 10;
	/**
	 * Ticks the interface spends charging the breath weapon before the bolt actually leaves the core.
	 *
	 * <p>The bolt travels at nearly two blocks a tick and is aimed once, at launch, which makes the
	 * launch the only readable moment in its whole flight - and it used to happen on the same tick
	 * the action started, with no tell at all. Whoever it had picked found out when it hit them. The
	 * charge is short by this fight's standards, because the shot still has to be dodged after it is
	 * fired rather than instead of being fired.</p>
	 */
	public static final int ORB_WARNING_TICKS = 40;
	/** Ticks the sky lance spends picking and marking its impact before it starts charging. */
	public static final int SKY_LANCE_LOCK_TICKS = 60;
	/**
	 * Charge on a marked, no-longer-moving impact: the window to run out of it.
	 *
	 * <p>Ten ticks was not a window. The mark is 3.6 blocks across and half a second of sprinting
	 * covers under three, so the lance was landing on anyone it picked no matter what they did.</p>
	 */
	public static final int SKY_LANCE_CHARGE_TICKS = 30;
	/** Ticks the fallen lance keeps burning at the impact. */
	public static final int SKY_LANCE_STRIKE_TICKS = 20;
	/**
	 * Ticks the column actually spends falling, at the very end of the charge.
	 *
	 * <p>Render-only, and deliberately a fraction of the charge. Spreading the descent across the
	 * whole window meant seventy blocks covered in a second and a half - a lance drifting down
	 * slowly enough to watch without concern. The charge is the warning and the mark on the ground
	 * carries it; the fall is the hit, and a hit that takes a quarter of a second has weight the
	 * same distance stretched over six times as long does not.</p>
	 */
	public static final int SKY_LANCE_FALL_TICKS = 3;
	/**
	 * How far above the impact the column starts, in blocks.
	 *
	 * <p>Render geometry, and shared for the same reason the tracking lag is: the client draws the
	 * shaft down this height and the server throws the particles that come off it up the same one.
	 * The two were separate numbers - the drawn column reached seventy-two blocks and nothing on the
	 * server knew that - and the moment anything is emitted along the column, two numbers is two
	 * columns.</p>
	 */
	public static final double SKY_LANCE_COLUMN_BLOCKS = 72.0D;
	/** Ticks the tendrils rear up before the first lash lands. */
	public static final int TENDRIL_WARNING_TICKS = 45;
	/**
	 * Ticks between successive lashes once the flurry has started.
	 *
	 * <p>Has to stay above {@link #TENDRIL_STRIKE_TELEGRAPH_TICKS}: the mark and the landing share
	 * one cycle, so the interval is the telegraph plus whatever recovery is left before the next
	 * limb commits. Raised alongside the telegraph to keep that recovery beat intact.</p>
	 */
	public static final int TENDRIL_STRIKE_INTERVAL_TICKS = 45;
	/**
	 * Ticks each individual lash spends marked on the ground before it lands.
	 *
	 * <p>The flurry used to pick whoever was nearest at the instant it struck and land on top of
	 * them, which is not an attack anyone can answer - the landing followed the player. Each lash
	 * now commits to a spot and telegraphs it, and this is what sprinting out of the marked radius
	 * actually costs.</p>
	 *
	 * <p>A flat second was enough to leave the circle and not enough to notice it first. The mark
	 * has to be seen, read as a mark, and then acted on, and the first two of those cost most of a
	 * second on their own when there are three limbs, a laser and a halo all moving at once. At
	 * thirty-two the window is still short enough that standing still is fatal, which is the only
	 * thing it has to keep being.</p>
	 */
	public static final int TENDRIL_STRIKE_TELEGRAPH_TICKS = 32;
	public static final int TENDRIL_STRIKE_COUNT = 3;
	/**
	 * Ticks the interface spends reaching before a held tool is actually taken.
	 *
	 * <p>Shorter than the rest, but not by much. Every other lock is a warning you can act on by
	 * moving and this one is not - the tool is going whatever you do - so it does not need the full
	 * window. It does need long enough to read: at thirty ticks the notice was gone before players
	 * had worked out what had just been taken.</p>
	 */
	public static final int WEAPON_WARNING_TICKS = 45;
	/** Ticks the gaze holds a hotbar before it starts emptying it. */
	public static final int HOTBAR_WARNING_TICKS = 60;
	/** Ticks the grab spends telegraphing, then lifting, before the victim leaves the ground. */
	public static final int GRAB_WARNING_TICKS = 50;
	public static final int GRAB_LIFT_TICKS = 14;

	private WorldInterfaceProtocol() {
	}

	public interface WireValue {
		int wireId();
	}

	public enum Stage implements WireValue {
		UNPREPARED(0),
		ARENA_READY(1),
		WAITING_TERMINALS(2),
		SUMMONING(3),
		PHASE_1(4),
		PHASE_2(5),
		PHASE_3(6),
		SUCCESS_RESOLUTION(7),
		FAILURE_RESOLUTION(8),
		PORTAL_OPEN(9),
		COMPLETE(10);

		private final int wireId;
		Stage(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		/** Mirrors {@code WorldInterfaceStage#isCombat}, for client code that only has the wire enum. */
		public boolean isCombat() { return this == PHASE_1 || this == PHASE_2 || this == PHASE_3; }
		public static Stage fromWireId(int wireId) { return decode(values(), wireId, "stage"); }
	}

	public enum Form implements WireValue {
		NONE(0),
		LISTENING_EMBRYO(1),
		FREQUENCY_DEVOURER(2),
		WORLD_INTERFACE(3);

		private final int wireId;
		Form(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static Form fromWireId(int wireId) { return decode(values(), wireId, "form"); }
	}

	public enum GatewayState implements WireValue {
		DORMANT(0),
		PURPLE(1),
		GOLD(2),
		RED(3);

		private final int wireId;
		GatewayState(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static GatewayState fromWireId(int wireId) { return decode(values(), wireId, "gateway state"); }
	}

	public enum Outcome implements WireValue {
		NONE(0),
		SUCCESS(1),
		FAILURE(2);

		private final int wireId;
		Outcome(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static Outcome fromWireId(int wireId) { return decode(values(), wireId, "outcome"); }
	}

	public enum AltarAction implements WireValue {
		DEPOSIT(1),
		WITHDRAW(2),
		CANCEL(3),
		/** Starts the encounter. Only a player whose own terminal is already escrowed may send it. */
		SUMMON(4);

		private final int wireId;
		AltarAction(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static AltarAction fromWireId(int wireId) { return decode(values(), wireId, "altar action"); }
	}

	/** Fixed client-visible ritual results. Internal journal errors collapse to UNKNOWN. */
	public enum AltarStatus implements WireValue {
		WAITING(0, "waiting"),
		READY(1, "ready"),
		INVALID_CONTEXT(2, "invalid_context"),
		ALREADY_DEPOSITED(3, "already_deposited"),
		REVISION_MISMATCH(4, "revision_mismatch"),
		RITUAL_NOT_WAITING(5, "ritual_not_waiting"),
		/**
		 * Retired with the v3 roster. Both of these described the old whole-server roster: one refused
		 * every deposit while a ninth player was online anywhere in the world, the other tore the
		 * ritual down whenever anybody joined or left. Neither is emitted any more and neither id is
		 * reassigned, so a log or a save from before the change still reads as what it was.
		 */
		INVALID_ROSTER_SIZE(6, "invalid_roster_size"),
		ROSTER_CHANGED(7, "roster_changed"),
		VALID_BOUND_TERMINAL_MISSING(8, "valid_bound_terminal_missing"),
		TERMINAL_MISMATCH(9, "terminal_mismatch"),
		TERMINAL_DISAPPEARED(10, "terminal_disappeared"),
		TERMINAL_DEPOSITED(11, "terminal_deposited"),
		SACRIFICE_COMMITTED(12, "sacrifice_committed"),
		NOTHING_DEPOSITED(13, "nothing_deposited"),
		ALREADY_COMMITTED(14, "already_committed"),
		WITHDRAWN(15, "withdrawn"),
		RITUAL_ALREADY_EMPTY(16, "ritual_already_empty"),
		ROLLBACK_ALREADY_PENDING(17, "rollback_already_pending"),
		NOT_IN_FROZEN_ROSTER(18, "not_in_frozen_roster"),
		CANCELLED(19, "cancelled"),
		PREPARED_RECOVERY(20, "prepared_recovery"),
		STATE_UNAVAILABLE(21, "state_unavailable"),
		ENCOUNTER_MISMATCH(22, "encounter_mismatch"),
		INVALID_MUTATION_RITUAL_NOT_WAITING(23, "invalid_mutation_ritual_not_waiting"),
		INVALID_MUTATION_ROSTER_CHANGED(24, "invalid_mutation_roster_changed"),
		INVALID_MUTATION_PREPARED_TRANSACTION_MISSING(25, "invalid_mutation_prepared_transaction_missing"),
		INVALID_MUTATION_TRANSACTION_MISSING(26, "invalid_mutation_transaction_missing"),
		PREPARED_TRANSACTION_MISSING(27, "prepared_transaction_missing"),
		TRANSACTION_MISSING(28, "transaction_missing"),
		SACRIFICE_NOT_READY(29, "sacrifice_not_ready"),
		UNKNOWN(30, "unknown"),
		/** The altar already holds eight terminals; this one is turned away and the eight are kept. */
		ROSTER_FULL(31, "roster_full"),
		INVALID_MUTATION_ROSTER_FULL(32, "invalid_mutation_roster_full"),
		/** Pressed summon without having handed anything over. */
		SUMMON_REQUIRES_DEPOSIT(33, "summon_requires_deposit"),
		/** Summon pressed while some escrow entry is still mid-transaction. */
		SUMMON_NOT_READY(34, "summon_not_ready"),
		/** Three minutes elapsed without a summon; everything escrowed is on its way back. */
		RITUAL_WINDOW_EXPIRED(35, "ritual_window_expired"),
		/** A pre-v3 ritual found in a loaded save; rolled back so it can be started under the new rules. */
		RITUAL_WINDOW_MISSING(36, "ritual_window_missing");

		private final int wireId;
		private final String key;
		AltarStatus(int wireId, String key) { this.wireId = wireId; this.key = key; }
		@Override public int wireId() { return wireId; }
		public String translationKey() {
			return "screen.thefourthfrequency.resonance_altar.status." + key;
		}
		public static AltarStatus fromWireId(int wireId) { return decode(values(), wireId, "altar status"); }
		public static AltarStatus fromReason(String reason) {
			String normalized = reason == null ? "unknown" : reason.replace(':', '_');
			if ("rollback_pending".equals(normalized)) return ROLLBACK_ALREADY_PENDING;
			for (AltarStatus status : values()) if (status.key.equals(normalized)) return status;
			return UNKNOWN;
		}
	}

	public enum BossAction implements WireValue {
		NONE(0),
		LASER_SWEEP(1),
		ENERGY_ORB(2),
		// 3 is retired: the grab-slam is gone and the id is deliberately not reassigned.
		SKY_LANCE(4),
		WEAPON_CHARGE(5),
		GRAB_THROW(6),
		HOTBAR_PURGE(7),
		TENDRIL_LASH(8),
		FORCED_EXPULSION(9),
		SUMMONING(10),
		MORPH_TO_SECOND(11),
		MORPH_TO_THIRD(12),
		SUCCESS_DEATH(13),
		FAILURE_ESCAPE(14);

		private final int wireId;
		BossAction(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static BossAction fromWireId(int wireId) { return decode(values(), wireId, "boss action"); }
	}

	/**
	 * How hard a detonation hits the camera. Mirrors {@code ScreenShakePolicy.Grade} one for one, and
	 * exists separately only so the wire has stable ids that a policy rename cannot change.
	 */
	public enum BlastGrade implements WireValue {
		LIGHT(0),
		MEDIUM(1),
		HEAVY(2),
		CATACLYSM(3);

		private final int wireId;
		BlastGrade(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static BlastGrade fromWireId(int wireId) { return decode(values(), wireId, "blast grade"); }
	}

	public enum PoemCompletion implements WireValue {
		READ(1),
		SKIPPED(2);

		private final int wireId;
		PoemCompletion(int wireId) { this.wireId = wireId; }
		@Override public int wireId() { return wireId; }
		public static PoemCompletion fromWireId(int wireId) { return decode(values(), wireId, "poem completion"); }
	}

	/**
	 * Ticks a targeted player has between being locked and the action landing, or 0 for actions that
	 * do not lock. Shared so the screen treatment, the HUD warning and the server's own particle and
	 * audio tell all count down the same window instead of three separate literals drifting apart.
	 */
	/**
	 * Fraction of a lock window after which the lock reads as committed rather than as searching.
	 *
	 * <p>Lives beside {@link #lockWarningTicks} for the same reason that does: the lock tone going
	 * solid and the mark on the countdown bar are two ways of saying one thing, and the instant they
	 * are two literals they start describing different instants. A player who has learned that the
	 * buzz means "now" has to be able to see "now" on the bar.</p>
	 */
	public static final float LOCK_COMMIT_FRACTION = 0.7F;

	/**
	 * Whether a telegraphed action is a targeting lock rather than a windup on a dispossession.
	 *
	 * <p>Both kinds count down on {@link #lockWarningTicks}, and to the HUD they look alike, but
	 * they are not asking the player for the same thing. A laser, an orb, a lance, a grab and a
	 * lash are all ordnance with the player's position in them: the window exists so they can stop
	 * being where it is going, and every one of their HUD labels is an instruction to move. The
	 * weapon charge and the hotbar purge take something instead - there is nowhere to move to, the
	 * window is only notice, and their labels say what is about to be gone rather than where to go.
	 *
	 * <p>So they must not share a warning. The missile-lock cadence promises "you can still get out
	 * of this", and spending it on something nobody can get out of teaches the player to stop
	 * believing it on the occasions that matter.</p>
	 */
	public static boolean isTargetingLock(BossAction action) {
		return action != null && switch (action) {
			case LASER_SWEEP, ENERGY_ORB, SKY_LANCE, GRAB_THROW, TENDRIL_LASH -> true;
			default -> false;
		};
	}

	public static int lockWarningTicks(BossAction action) {
		return action == null ? 0 : switch (action) {
			case LASER_SWEEP -> LASER_WARNING_TICKS;
			case ENERGY_ORB -> ORB_WARNING_TICKS;
			case SKY_LANCE -> SKY_LANCE_LOCK_TICKS;
			case GRAB_THROW -> GRAB_WARNING_TICKS;
			case WEAPON_CHARGE -> WEAPON_WARNING_TICKS;
			case HOTBAR_PURGE -> HOTBAR_WARNING_TICKS;
			case TENDRIL_LASH -> TENDRIL_WARNING_TICKS;
			default -> 0;
		};
	}

	static void requireVersion(int protocolVersion) {
		if (protocolVersion != VERSION) {
			throw new IllegalArgumentException("Unsupported world-interface protocol " + protocolVersion);
		}
	}

	static void requireSequence(long sequence) {
		if (sequence < 0) throw new IllegalArgumentException("Sequence must be non-negative");
	}

	static int readBoundedSize(RegistryFriendlyByteBuf buffer, int maximum, String label) {
		int size = buffer.readVarInt();
		if (size < 0 || size > maximum) {
			throw new IllegalArgumentException(label + " size " + size + " exceeds " + maximum);
		}
		return size;
	}

	static <T> List<T> copyBounded(List<T> values, int maximum, String label) {
		Objects.requireNonNull(values, label);
		if (values.size() > maximum) {
			throw new IllegalArgumentException(label + " size " + values.size() + " exceeds " + maximum);
		}
		for (T value : values) Objects.requireNonNull(value, label + " entry");
		return List.copyOf(values);
	}

	static String requireUtf(String value, int maximumLength, String label) {
		Objects.requireNonNull(value, label);
		if (value.length() > maximumLength) {
			throw new IllegalArgumentException(label + " is longer than " + maximumLength);
		}
		return value;
	}

	private static <T extends Enum<T> & WireValue> T decode(T[] values, int wireId, String label) {
		for (T value : values) if (value.wireId() == wireId) return value;
		throw new IllegalArgumentException("Unknown " + label + " wire id " + wireId);
	}
}
