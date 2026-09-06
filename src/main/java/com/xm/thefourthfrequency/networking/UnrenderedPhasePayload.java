package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The only thing the server tells a client about the unrendered layer.
 *
 * <p>Everything else the presentation needs, the client can already see: it knows which dimension it
 * is in, so the ambience loop, the suppressed score and the view distance all key off that and cost
 * no protocol at all. What it cannot see is a transition that has not happened yet, and both phases
 * here exist to cover exactly that - the screen has to be black <em>before</em> the teleport, or the
 * player watches the destination chunk light up and learns how the place is built.
 *
 * <p>Deliberately not carried on {@code AnomalyPhaseS2C}, which would have been the obvious reuse.
 * {@code AnomalyPresentationController} tears its whole presentation down the moment
 * {@code client.level} changes - correctly, for every anomaly that decorates the world the player is
 * standing in - so an anomaly-borne blackout ends at precisely the teleport it was covering.
 */
public record UnrenderedPhasePayload(String phase, int holdTicks) implements CustomPacketPayload {
	/**
	 * The floor giving way, before anything is covered.
	 *
	 * <p>Carries no authority and moves nothing. The client sinks its own camera through the floor
	 * for the stated number of ticks so the player watches themselves go down, and {@link #ENTER}
	 * arrives on the far side of it to raise the cover over the teleport that follows. It is a
	 * separate phase rather than a lead-in encoded into ENTER because the server owns both clocks
	 * and a client that missed this packet still gets a correct, merely undecorated, entry.
	 */
	public static final String FALL = "fall";
	/** Cover the entry teleport. Raised immediately, dropped when the hold elapses. */
	public static final String ENTER = "enter";
	/** Reached by the entity: blackout, the scream, and then the return teleport underneath it. */
	public static final String CAPTURE = "capture";
	/**
	 * Left through a false wall. Covers the teleport, then hands the player back in mid-air.
	 *
	 * <p>Carries a second job past the blackout: the client holds the view distance at sixteen
	 * chunks for the descent and drops it again on landing. The layer is locked to six so the player
	 * can never see enough of it at once, and the return is the exact inverse - they come out above
	 * their own world and the whole point of the moment is how much of it is suddenly visible.
	 * The client ends this itself when the local player touches ground, so the server never has to
	 * be asked what a falling body is doing.
	 */
	public static final String EXIT = "exit";
	/**
	 * The standing bearing, with {@code holdTicks} carrying the compass index (or -1 for none).
	 *
	 * <p>Sent on change rather than on a timer, because the readout it feeds is permanent: it is
	 * redrawn from this value every frame for as long as the player is in the layer, the way the
	 * guidance readout is, instead of being a notice that appears and expires. A direction that
	 * vanishes after four seconds is a thing the player has to remember; one that stays is a thing
	 * they can walk by.
	 *
	 * <p>An index rather than a component so the client owns the wording - the same reason the
	 * compass keys live in the language file rather than in a packet.
	 */
	public static final String BEARING = "bearing";

	/** The session ended normally. Drop any cover still up. */
	public static final String CLEAR = "clear";

	public static final Type<UnrenderedPhasePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "unrendered_phase"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UnrenderedPhasePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.stringUtf8(16), UnrenderedPhasePayload::phase,
					ByteBufCodecs.VAR_INT, UnrenderedPhasePayload::holdTicks,
					UnrenderedPhasePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
