package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Current-world runtime gates derived exclusively from the World Interface state. */
public final class FinaleRuntimePolicy {
	private FinaleRuntimePolicy() {
	}

	/**
	 * Whether ambient anomalies, empty segments and the rest of the background may still fire.
	 *
	 * <p>This used to open back up at {@link WorldInterfaceStage#COMPLETE}, on the reasoning that
	 * the world keeps living after the encounter. It does not keep living like <em>that</em>. Every
	 * one of those systems exists to make the world feel like it is losing its grip on itself, and
	 * the whole mainline is the story of finding out why and putting a stop to it. Firing them at
	 * someone who has walked back out of the exit portal tells them the thing they just ended did
	 * not end - which is not an epilogue, it is the ending being taken back.</p>
	 *
	 * <p>So the gate closes at the resolution and stays closed. It is the same instant {@link
	 * #concluded} names, and for the same reason it was introduced for pursuits: once the encounter
	 * is decided, nothing that was pressure during the run should still be arriving.</p>
	 */
	public static boolean backgroundSystemsAllowed(FrequencyWorldData data) {
		return backgroundSystemsAllowed(WorldInterfaceState.snapshot(data));
	}

	public static boolean pressureActive(FrequencyWorldData data) {
		return pressureActive(WorldInterfaceState.snapshot(data));
	}

	/**
	 * Whether ordinary background pressure may fire right now.
	 *
	 * <p>{@link #backgroundSystemsAllowed} closes at the <em>resolution</em>, which leaves the whole
	 * encounter - summon and all three phases - open to anomalies, empty segments and everything else
	 * the run has been throwing at the player. That was not a judgement call, it was an oversight: the
	 * only thing the fight ever did with {@link #pressureActive} was raise the anomaly ceiling, so the
	 * finale made background pressure <em>more</em> likely rather than stopping it.
	 *
	 * <p><b>It silenced the boss fight.</b> {@code silent_world} mutes MUSIC, AMBIENT and HOSTILE, it
	 * is sustained for two to three minutes, and every attack cue the encounter plays is HOSTILE. One
	 * roll of it during the summon takes the score, the telegraphs and the hit feedback away for a
	 * third of the fight. The client watchdog on {@code gainBySource[HOSTILE]} was built to fight
	 * exactly this symptom without knowing what was causing it.
	 *
	 * <p>The narrower reason stands on its own even where nothing is muted: an encounter is the one
	 * stretch of the run where the world has stopped being ambiguous, and a corridor of unrelated
	 * pressure arriving through it is noise across the only unambiguous thing in the mod.
	 *
	 * <p>Pursuits already asked exactly this question, spelled out at their own call site. It lives
	 * here now so there is one answer rather than one per subsystem.</p>
	 */
	public static boolean ambientPressureAllowed(FrequencyWorldData data) {
		return ambientPressureAllowed(WorldInterfaceState.snapshot(data));
	}

	static boolean ambientPressureAllowed(WorldInterfaceState.Snapshot snapshot) {
		return backgroundSystemsAllowed(snapshot) && !pressureActive(snapshot);
	}

	/**
	 * The same gate, asked about one player, which is the only way it should ever have been asked
	 * during a running encounter.
	 *
	 * <p>The two halves of this policy are not the same kind of statement, and treating them as one
	 * world-level boolean made the smaller one enormous. <b>After the resolution</b> the world is
	 * over: nothing may arrive for anybody, ever again, and that is world-level on purpose.
	 * <b>During the encounter</b> the reason is completely different - a fight is the one stretch of
	 * the run where the world has stopped being ambiguous, and unrelated pressure crossing it is
	 * noise over the only unambiguous thing in the mod. That reason applies to the people in the
	 * fight. It says nothing whatsoever about somebody mining in the Overworld who is not on the
	 * roster, and silencing them for ten minutes because eight other players walked into the End is
	 * borrowing an argument that was never about them.
	 *
	 * <p>The End is included alongside the roster because a non-participant standing on the island is
	 * inside the encounter whether or not they committed to it - their anomalies would be landing in
	 * the middle of somebody else's boss fight.
	 */
	public static boolean ambientPressureAllowed(FrequencyWorldData data, ServerPlayer player) {
		return backgroundSystemsAllowed(WorldInterfaceState.snapshot(data))
				&& !insideRunningEncounter(data, player);
	}

	/**
	 * Whether this player is inside an encounter that is happening right now.
	 *
	 * <p>Two ways to be inside one, and they are not the same commitment: having put a terminal into
	 * the core, or standing on the island while other people's fight happens around you. Both are
	 * inside it. Everyone else - a different dimension, a different part of the story, or simply
	 * asleep at their base - is not, and this is the question anything that wants to treat the finale
	 * as a fact about the world has to ask about a person instead.
	 *
	 * <p>Extracted because two callers had grown separate answers to it. {@link
	 * #ambientPressureAllowed(FrequencyWorldData, ServerPlayer)} had the correct one written out
	 * inline; {@code WorldDecayService} had a world-level {@code pressureActive} check that jumped
	 * every client on the server to full corruption the moment a summon happened, directly under a
	 * comment explaining at length why decay is personal.
	 */
	public static boolean insideRunningEncounter(FrequencyWorldData data, ServerPlayer player) {
		WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(data);
		if (!pressureActive(snapshot)) return false;
		return snapshot.frozenRoster().contains(player.getUUID())
				|| player.level().dimension() == Level.END;
	}

	// The four gates below are pure reads of a snapshot. They are split out so the thresholds can be
	// exercised directly: reaching them through FrequencyWorldData needs a running server, which is
	// why every one of these decisions went untested while being consulted from both ending/ and
	// pursuit/.
	static boolean backgroundSystemsAllowed(WorldInterfaceState.Snapshot snapshot) {
		if (!snapshot.valid() || !snapshot.present()) return true;
		return snapshot.stage().wireId() < WorldInterfaceStage.SUCCESS_RESOLUTION.wireId();
	}

	static boolean pressureActive(WorldInterfaceState.Snapshot snapshot) {
		return snapshot.valid() && snapshot.present()
				&& snapshot.stage().wireId() >= WorldInterfaceStage.SUMMONING.wireId()
				&& snapshot.stage() != WorldInterfaceStage.COMPLETE;
	}

	/**
	 * True once the encounter has been decided, whichever way it went.
	 *
	 * <p>Pursuits were the first system to need this instant rather than {@link
	 * WorldInterfaceStage#COMPLETE}: a player who walks out of the exit portal into the overworld
	 * has finished the mainline, and any pursuit still marked pending from an earlier story gate -
	 * the stronghold milestone alone raises the allowed form to its maximum - fired at them the
	 * moment they arrived, as an epilogue nobody wrote. {@link #backgroundSystemsAllowed} has since
	 * been closed at the same instant, for the same reason, so the two now agree.</p>
	 */
	public static boolean concluded(FrequencyWorldData data) {
		return concluded(WorldInterfaceState.snapshot(data));
	}

	public static boolean succeeded(FrequencyWorldData data) {
		return succeeded(WorldInterfaceState.snapshot(data));
	}

	static boolean concluded(WorldInterfaceState.Snapshot snapshot) {
		return snapshot.valid() && snapshot.present()
				&& snapshot.stage().wireId() >= WorldInterfaceStage.SUCCESS_RESOLUTION.wireId();
	}

	static boolean succeeded(WorldInterfaceState.Snapshot snapshot) {
		return snapshot.valid() && snapshot.present()
				&& snapshot.outcome() == WorldInterfaceState.Outcome.SUCCESS;
	}
}
