package com.xm.thefourthfrequency.ending;

/** Pure numeric and resolution rules for the world-interface encounter. */
public final class WorldInterfacePolicy {
	public static final int MIN_ROSTER_SIZE = 1;
	public static final int MAX_ROSTER_SIZE = 8;
	public static final int TOTAL_ANCHORS = 10;
	/**
	 * Three minutes: how long a terminal sits in the core before it comes back out.
	 *
	 * <p>The window is a deadline to <em>start</em>, not a countdown to starting. Nothing happens when
	 * it runs out except that every escrowed terminal is returned and the altar goes dormant, which
	 * is why it can afford to be short. What it is protecting against is a party that hands over one
	 * terminal and then goes to do something else: the ritual is the one state in the mod where a
	 * player is standing in the End without the object the whole game is about, and leaving that open
	 * indefinitely turns an act into a condition.
	 *
	 * <p>Long enough that eight people who are already on the island can each take a turn at the core
	 * without hurrying, and short enough that walking away from it costs a walk back rather than a
	 * lost evening. Nobody is punished for letting it lapse - the terminals are handed back and the
	 * ritual can be started again immediately.
	 */
	public static final int RITUAL_WINDOW_TICKS = 3 * 60 * 20;
	/**
	 * Ten minutes, and the only clock the fight runs on.
	 *
	 * <p>Cutting an anchor used to spend a slice of this, which made the one action the encounter
	 * is built around read as a cost: the reward for opening the interface up was less time to use
	 * it in, and a roster that cut all ten had halved the fight before landing a hit. The deadline
	 * is now fixed, and what an anchor buys is stated in the one place the player is already
	 * looking - the gauge flares when the interface stops resisting damage as hard.</p>
	 *
	 * <p>{@code WorldInterfaceProtocol} carries the same figure to the client for the HUD clock, and
	 * the two must not drift - {@code WorldInterfacePolicyTest} pins them equal.</p>
	 */
	public static final int COLLAPSE_DURATION_TICKS = 12_000;
	public static final int MAX_PERMANENT_TERRAIN_EDITS = 8_192;
	public static final int MAX_TERRAIN_EDITS_PER_TICK = 32;

	/** The one-player pool, and the base every larger roster scales up from. */
	private static final double BASE_HEALTH = 600.0D;
	/**
	 * What each participant past the first adds to the pool, as a fraction of {@link #BASE_HEALTH}.
	 *
	 * <p>The pool used to be a flat six hundred per head, which is the wrong shape for this fight.
	 * A table does not scale linearly with its size - they share one boss, one set of anchors and
	 * one collapse clock, and every extra pair of hands is another damage source against the same
	 * timer - so charging full price per player made the encounter get strictly harder the more
	 * people showed up, and a four-stack was grinding twenty-four hundred points inside a deadline
	 * built for six hundred. At half price the pool still grows with the roster, so nobody's
	 * presence is free, but it grows slower than the damage does.</p>
	 */
	private static final double HEALTH_PER_EXTRA_PLAYER = 0.5D;
	/** Anchored shell at full stability; each fallen anchor exposes another four percentage points. */
	private static final double BASE_DAMAGE_TAKEN_MULTIPLIER = 0.60D;
	private static final double DAMAGE_TAKEN_GAIN_PER_ANCHOR = 0.04D;
	/**
	 * Fraction of maximum health each surviving anchor returns per second.
	 *
	 * <p>Kept large enough to register in the damage log, but below the old quarter-percent total so
	 * the anchored shell and its safe ground carry part of the value instead of regeneration doing
	 * all of the work.</p>
	 */
	private static final double HEALING_PER_SECOND_PER_ANCHOR = 0.00020D;
	/**
	 * What an arrow is worth against the interface, over and above its own damage roll.
	 *
	 * <p>The encounter is built so that the body is out of reach on purpose - it clears the arena
	 * floor by eight, twelve and sixteen blocks, and {@code WorldInterfaceAnatomy} says outright that
	 * it is "a target for anything ranged and for nothing else". Everything a player on the ground can
	 * physically swing at is a head, a neck or a limb tip: small, moving, and standing next to the
	 * thing that is attacking them.
	 *
	 * <p>Ranged was nonetheless paying the melee rate. A fully drawn Power V bow lands somewhere near
	 * nine points about once a second, and against an eight-player pool with every anchor still
	 * standing the interface regenerates more than five points a second - so a table that chose to
	 * fight it the way the arena is shaped was spending most of its damage undoing the regeneration
	 * and could not tell its arrows were doing anything at all. Melee, at roughly ten points at one
	 * and a half swings a second, was quietly the only real answer, in the one range band the fight
	 * spends its whole design keeping players out of.
	 *
	 * <p>Two and a half puts a good bow at a little over twenty a shot: comfortably ahead of the
	 * regeneration on its own, worth the arrows, and still short of trivialising the pool inside one
	 * collapse clock.
	 * It is a flat multiplier rather than a per-form or per-part one deliberately - "where do I aim"
	 * is already answered by the geometry, and this should not add a second, invisible answer.
	 */
	private static final double ARROW_DAMAGE_MULTIPLIER = 2.5D;
	private static final double BASE_ATTACK_COOLDOWN_MULTIPLIER = 1.15D;
	private static final double ATTACK_COOLDOWN_LOSS_PER_ANCHOR = 0.03D;
	/** Horizontal radius of one surviving anchor's vertical stability projection. */
	public static final double STABILITY_FIELD_RADIUS = 8.0D;
	/** World-interface damage that remains when a player stands inside a stability field. */
	public static final float STABILITY_FIELD_DAMAGE_MULTIPLIER = 0.80F;

	/**
	 * Blocks of a player's own climb the storm's combat station will follow upwards.
	 *
	 * <p>The station used to be written straight off the player's Y, which made the body a ceiling
	 * fixed above their head: eight, fourteen or eighteen blocks up depending on form, wherever they
	 * went. On flat ground that is invisible and correct. On the obsidian spikes it is the fight,
	 * because the spikes are where the stability anchors are - a player climbing one to break an
	 * anchor took the whole thirty-three-block body up with them and arrived under it, with the
	 * anchor and their own sightline inside the mass they were trying to shoot past.
	 *
	 * <p>Four blocks keeps the station honest over the small rises and dips of the island - the storm
	 * still answers a player who steps onto the altar - and stops answering well before the shortest
	 * spike. Above that the body stays where the island is, which is the point: the climb is how a
	 * player gets out from under it, and a ceiling that climbs too cannot be escaped by climbing.
	 *
	 * <p>Set to zero to pin the station to the arena floor outright.
	 */
	public static final double MAX_VERTICAL_FOLLOW = 4.0D;

	private WorldInterfacePolicy() {
	}

	/**
	 * World Y for the storm's combat station.
	 *
	 * @param arenaFloorY the arena centre's Y - the height the island itself is at
	 * @param targetY     the hunted player's feet
	 * @param hover       the form's own clearance, skyhold lift included
	 * @return the station, following the player up by at most {@link #MAX_VERTICAL_FOLLOW}
	 *
	 * <p>The follow is clamped at both ends. The lower clamp matters as much as the upper one: a
	 * player who falls off the island, or fights from a hole, is not somewhere the storm should
	 * descend to - it would put the body through the ground it is hovering over.
	 */
	public static double combatStationY(double arenaFloorY, double targetY, double hover) {
		return arenaFloorY + Math.clamp(targetY - arenaFloorY, 0.0D, MAX_VERTICAL_FOLLOW) + hover;
	}

	/**
	 * The horizontal point the storm holds station over and turns to face, given where everyone is.
	 *
	 * <p>The chase used to be written against the single nearest player, which on a solo table is
	 * the only thing it could mean and on any other table is a hard argmax with all the behaviour
	 * that implies. A body up to thirty-three blocks across parks over whoever happens to be closest
	 * and stays there: it is a ceiling on that one person's screen for the whole phase, it puts the
	 * anchors and the core they are trying to shoot inside the silhouette, and because the lance and
	 * the lashes are aimed at the ground under the body, standing closest quietly meant standing
	 * where most of the fight was going to land. It also rewarded the wrong thing - the safest way to
	 * play a four-stack was for everybody to stay far away and let one person hold the aggro they did
	 * not ask for.
	 *
	 * <p>Weighting by {@code 1 / (1 + distance)} keeps the pull toward whoever is close - the storm
	 * still comes for the people in front of it, and a lone player is still followed exactly as
	 * before, because one position weighted by anything is that position - while letting a spread-out
	 * table drag the body into the space between them. A sniper sixty blocks out moves the station by
	 * a little; a second player at melee range moves it by half.
	 *
	 * @param fromX where the storm currently is, which is what "close" is measured from
	 * @return the station's {@code {x, z}}
	 */
	public static double[] attentionCentroid(double[] xs, double[] zs, double fromX, double fromZ) {
		if (xs == null || zs == null || xs.length != zs.length) {
			throw new IllegalArgumentException("Participant coordinates must be paired");
		}
		if (xs.length == 0) throw new IllegalArgumentException("Need at least one participant");
		if (!Double.isFinite(fromX) || !Double.isFinite(fromZ)) {
			throw new IllegalArgumentException("Origin coordinates must be finite");
		}
		double totalWeight = 0.0D;
		double x = 0.0D;
		double z = 0.0D;
		for (int index = 0; index < xs.length; index++) {
			if (!Double.isFinite(xs[index]) || !Double.isFinite(zs[index])) {
				throw new IllegalArgumentException("Participant coordinates must be finite");
			}
			double dx = xs[index] - fromX;
			double dz = zs[index] - fromZ;
			double weight = 1.0D / (1.0D + Math.sqrt(dx * dx + dz * dz));
			totalWeight += weight;
			x += xs[index] * weight;
			z += zs[index] * weight;
		}
		return new double[]{x / totalWeight, z / totalWeight};
	}

	/**
	 * The authoritative virtual-health pool for a frozen roster: 600, 900, 1200 ... 2700 at eight.
	 *
	 * <p>Every other reader of this number - the ritual commit that writes it into the save, and the
	 * state validator that refuses a save whose pool disagrees with its roster - has to call this
	 * method rather than restate the arithmetic, or a rule change silently turns every existing
	 * encounter into a {@code maximum_health_roster_mismatch}.</p>
	 */
	public static double maxHealth(int frozenRosterSize) {
		requireRosterSize(frozenRosterSize);
		return BASE_HEALTH * (1.0D + HEALTH_PER_EXTRA_PLAYER * (frozenRosterSize - 1));
	}

	/** Multiplier applied to incoming boss damage after {@code destroyedAnchors} authoritative anchors fall. */
	public static double damageTakenMultiplier(int destroyedAnchors) {
		requireDestroyedAnchors(destroyedAnchors);
		return BASE_DAMAGE_TAKEN_MULTIPLIER + DAMAGE_TAKEN_GAIN_PER_ANCHOR * destroyedAnchors;
	}

	/** What an arrow or trident is multiplied by before the anchor wall is applied. */
	public static double arrowDamageMultiplier() {
		return ARROW_DAMAGE_MULTIPLIER;
	}

	public static double adjustedIncomingDamage(double rawDamage, int destroyedAnchors) {
		return adjustedIncomingDamage(rawDamage, destroyedAnchors, false);
	}

	/**
	 * The damage one accepted hit actually removes from the pool.
	 *
	 * @param arrow whether the blow was delivered by an arrow or trident rather than in melee; see
	 *              {@link #ARROW_DAMAGE_MULTIPLIER} for why the fight pays ranged more
	 */
	public static double adjustedIncomingDamage(double rawDamage, int destroyedAnchors, boolean arrow) {
		if (!Double.isFinite(rawDamage) || rawDamage < 0.0D) {
			throw new IllegalArgumentException("Raw damage must be finite and non-negative");
		}
		double weapon = arrow ? ARROW_DAMAGE_MULTIPLIER : 1.0D;
		return rawDamage * weapon * damageTakenMultiplier(destroyedAnchors);
	}

	/** Healing per server tick; twenty invocations equal the specified per-second rule. */
	public static double healingPerTick(double maximumHealth, int aliveAnchors) {
		if (!Double.isFinite(maximumHealth) || maximumHealth <= 0.0D) {
			throw new IllegalArgumentException("Maximum health must be finite and positive");
		}
		requireAliveAnchors(aliveAnchors);
		return maximumHealth * HEALING_PER_SECOND_PER_ANCHOR * aliveAnchors / 20.0D;
	}

	public static double attackCooldownMultiplier(int destroyedAnchors) {
		requireDestroyedAnchors(destroyedAnchors);
		return BASE_ATTACK_COOLDOWN_MULTIPLIER - ATTACK_COOLDOWN_LOSS_PER_ANCHOR * destroyedAnchors;
	}

	/** Whether a point lies inside an anchor's cylindrical stability projection. */
	public static boolean insideStabilityField(double x, double z, double anchorX, double anchorZ) {
		if (!Double.isFinite(x) || !Double.isFinite(z)
				|| !Double.isFinite(anchorX) || !Double.isFinite(anchorZ)) {
			throw new IllegalArgumentException("Stability-field coordinates must be finite");
		}
		double dx = x - anchorX;
		double dz = z - anchorZ;
		return dx * dx + dz * dz <= STABILITY_FIELD_RADIUS * STABILITY_FIELD_RADIUS;
	}

	/** Applies the visible refuge supplied by a surviving anchor to one authored attack figure. */
	public static float adjustedPlayerDamage(float rawDamage, boolean insideStabilityField) {
		if (!Float.isFinite(rawDamage) || rawDamage < 0.0F) {
			throw new IllegalArgumentException("Player damage must be finite and non-negative");
		}
		return insideStabilityField ? rawDamage * STABILITY_FIELD_DAMAGE_MULTIPLIER : rawDamage;
	}

	/** Returns collapse progress clamped to the HUD domain [0, 1]. */
	public static double collapseProgress(long elapsedTicks) {
		if (elapsedTicks < 0L) throw new IllegalArgumentException("Elapsed ticks cannot be negative");
		return Math.min(1.0D, elapsedTicks / (double) COLLAPSE_DURATION_TICKS);
	}

	/**
	 * How long a won encounter spends putting the island back, in ticks.
	 *
	 * <p>Matched to the success resolution's own length, so the repair finishes on the tick the way
	 * out opens: the last of the corruption goes as the dragon finishes prising the exit apart. It is
	 * deliberately slow. The heal used to sweep the whole disc in under six seconds while the HUD
	 * still showed a frozen countdown labelled "paused", so the one thing the players had just earned
	 * went past before anyone could look at it, and the readout said nothing about it at all.
	 */
	public static final int REPAIR_DURATION_TICKS = 500;

	/**
	 * How much of the repair is done, in [0, 1].
	 *
	 * <p>One fraction drives all three halves of it - the terrain sweep, the collapse rail running
	 * backwards, and the material erosion lifting - so they cannot drift apart into three separate
	 * clocks describing the same event at different speeds.
	 *
	 * @param resolutionAge ticks since the resolution began, or negative before it has
	 */
	public static double repairFraction(long resolutionAge) {
		if (resolutionAge < 0L) return 0.0D;
		return Math.clamp(resolutionAge / (double) REPAIR_DURATION_TICKS, 0.0D, 1.0D);
	}

	/**
	 * The collapse clock as the winners should now read it: running backwards to zero.
	 *
	 * <p>Applied to the projection that goes out on the wire rather than to the authoritative timer.
	 * The stored elapsed count is what the encounter was decided on and must not move; what the rail
	 * is showing after the fight is over is no longer a deadline but the damage being undone.
	 */
	public static long repairedElapsedTicks(long elapsedAtDefeat, long resolutionAge) {
		if (elapsedAtDefeat < 0L) throw new IllegalArgumentException("Elapsed ticks cannot be negative");
		return Math.round(elapsedAtDefeat * (1.0D - repairFraction(resolutionAge)));
	}

	/**
	 * Whether the collapse readout is showing the repair rather than the deadline it was decided on.
	 *
	 * <p>The whole tail of a won encounter, not one stage of it. Keyed on {@code SUCCESS_RESOLUTION}
	 * alone, the rail unwound to zero over {@link #REPAIR_DURATION_TICKS} and then **snapped back to
	 * full** - because that duration is 500 ticks and the exit opens on resolution tick 500, so the
	 * stage advanced to {@code PORTAL_OPEN} on the very tick the repair finished and the projection
	 * fell through to the raw stored clock. What the players saw was the damage being undone and then
	 * instantly re-applied, on the one screen that is supposed to say it is over.
	 *
	 * <p>{@link #repairFraction} clamps at 1, so continuing past the repair duration simply holds the
	 * readout at zero for as long as the encounter is still on screen.
	 *
	 * <p>Gated on the outcome rather than on the stage alone, because {@code PORTAL_OPEN} is reached
	 * from a loss too, and a losing table keeps the island the countdown left them.</p>
	 */
	public static boolean repairsCollapseReadout(WorldInterfaceStage stage, boolean succeeded) {
		return succeeded && stage != null
				&& stage.wireId() >= WorldInterfaceStage.SUCCESS_RESOLUTION.wireId();
	}

	/** Collapse fraction below which combat shows no erosion at all. */
	public static final double EROSION_START_COLLAPSE = 0.40D;
	/**
	 * How far erosion may go while the fight is still winnable. The End has to stay readable
	 * enough to fight in, so combat never approaches the full missing-texture wash that the
	 * failure resolution ends on.
	 */
	public static final float COMBAT_EROSION_CEILING = 0.55F;

	/**
	 * How far the world has visibly stopped being able to describe itself, in [0, 1].
	 *
	 * <p>This used to key off the failure resolution clock alone, which meant the whole fight
	 * rendered at exactly zero and only a lost encounter ever showed the erosion - for six seconds,
	 * after the outcome was already decided. Winning players never saw it at all. Driving it from
	 * the collapse timer instead turns the countdown from a number into something visible: the
	 * island degrades as the deadline approaches, which is the pressure the fight was missing.</p>
	 *
	 * <p>Erosion holds at zero until {@link #EROSION_START_COLLAPSE} so the opening minutes stay
	 * clean, then accelerates quadratically to {@link #COMBAT_EROSION_CEILING}. A lost fight
	 * continues from that ceiling to a full wash across the resolution clock; a won fight returns
	 * to zero on the spot, so cutting the interface visibly restores the world's materials.</p>
	 */
	public static float presentationErosionProgress(WorldInterfaceStage stage, long elapsedTicks,
			long resolutionTick, long gameTime, int durationTicks) {
		if (stage == null) throw new IllegalArgumentException("Stage cannot be null");
		if (durationTicks <= 0) throw new IllegalArgumentException("Presentation duration must be positive");
		if (stage == WorldInterfaceStage.FAILURE_RESOLUTION) {
			if (resolutionTick < 0L || gameTime <= resolutionTick) return COMBAT_EROSION_CEILING;
			long age = gameTime - resolutionTick;
			float completion = Math.min(age, durationTicks) / (float) durationTicks;
			return COMBAT_EROSION_CEILING + (1.0F - COMBAT_EROSION_CEILING) * completion;
		}
		// A win runs the same combat curve, but on the projected clock rather than the stored one -
		// which the caller has already wound back by the repair fraction. So the materials lift at
		// exactly the rate the rail unwinds and the terrain sweep advances, instead of snapping to
		// clean on the tick the interface died.
		if (stage == WorldInterfaceStage.SUCCESS_RESOLUTION) return combatErosion(elapsedTicks);
		if (!stage.isCombat()) return 0.0F;
		return combatErosion(elapsedTicks);
	}

	private static float combatErosion(long elapsedTicks) {
		double collapse = collapseProgress(Math.max(0L, elapsedTicks));
		if (collapse <= EROSION_START_COLLAPSE) return 0.0F;
		double advance = (collapse - EROSION_START_COLLAPSE) / (1.0D - EROSION_START_COLLAPSE);
		return (float) (COMBAT_EROSION_CEILING * advance * advance);
	}

	public static int remainingCollapseTicks(long elapsedTicks) {
		if (elapsedTicks < 0L) throw new IllegalArgumentException("Elapsed ticks cannot be negative");
		return (int) Math.max(0L, COLLAPSE_DURATION_TICKS - elapsedTicks);
	}

	public static boolean hasTimedOut(long elapsedTicks) {
		if (elapsedTicks < 0L) throw new IllegalArgumentException("Elapsed ticks cannot be negative");
		return elapsedTicks >= COLLAPSE_DURATION_TICKS;
	}

	/** The server calls this once per running tick; all-offline frozen rosters pause the timer. */
	public static boolean timerAdvances(int frozenRosterSize, int onlineFrozenMembers) {
		requireRosterSize(frozenRosterSize);
		if (onlineFrozenMembers < 0 || onlineFrozenMembers > frozenRosterSize) {
			throw new IllegalArgumentException("Online frozen-member count is outside the roster");
		}
		return onlineFrozenMembers > 0;
	}

	/**
	 * Failure is evaluated before lethal damage on the same tick. A kill is successful only while
	 * the clock is strictly below 100%.
	 */
	public static TickVerdict resolveTick(long elapsedTicks, boolean lethalDamage) {
		if (hasTimedOut(elapsedTicks)) return TickVerdict.FAILURE;
		return lethalDamage ? TickVerdict.SUCCESS : TickVerdict.ONGOING;
	}

	/** Number of real disconnect targets selected by forced eviction. */
	public static int forcedEvictionTargetCount(int islandParticipantCount) {
		if (islandParticipantCount < 0 || islandParticipantCount > MAX_ROSTER_SIZE) {
			throw new IllegalArgumentException("Island participant count must be between 0 and 8");
		}
		if (islandParticipantCount < 3) return 0;
		return (islandParticipantCount * 3 + 9) / 10;
	}

	/**
	 * Horizontal reach of the failure erosion, for both the client's render-time replacement and the
	 * server's durable commit.
	 *
	 * <p>These were two different numbers - a hundred and sixty blocks of rendering over forty
	 * blocks of committed world - so the moment the encounter cleared and the render stopped, the
	 * outer four fifths of the damage evaporated and the island visibly healed itself. What a losing
	 * table watched spread has to be what a losing table is left with, which means one radius.</p>
	 *
	 * <p>A hundred and sixty is what the render always showed. The commit reaches it by running
	 * across the whole fight instead of the resolution, and by only ever touching end stone.</p>
	 */
	public static final int EROSION_RADIUS_BLOCKS = 160;
	/** Surface layers the erosion reaches down each column. */
	public static final int EROSION_DEPTH = 6;

	/**
	 * The failure erosion's per-position threshold, shared by the client's render-time replacement
	 * and the server's durable commit.
	 *
	 * <p>Both sides have to agree exactly. The client has been showing this erosion since the
	 * collapse timer started moving; when the loss is finally locked, the server writes the same
	 * set of positions for real. If the two used different hashes the world would visibly rearrange
	 * itself at the moment of failure, and the damage a player watched spread would not be the
	 * damage they were left with.</p>
	 */
	public static float erosionThreshold(long value) {
		long mixed = value;
		mixed ^= mixed >>> 33;
		mixed *= 0xff51afd7ed558ccdL;
		mixed ^= mixed >>> 33;
		mixed *= 0xc4ceb9fe1a85ec53L;
		mixed ^= mixed >>> 33;
		return (mixed >>> 40) / (float) 0xFFFFFF;
	}

	/** Whether a block position is eroded at the given progress, for one encounter. */
	public static boolean erodesAt(java.util.UUID encounterId, long packedPosition, float progress) {
		if (encounterId == null) return false;
		return erosionThreshold(encounterId.getMostSignificantBits()
				^ encounterId.getLeastSignificantBits() ^ packedPosition) <= progress;
	}

	public static int terrainEditBudgetThisTick(int permanentEditsSoFar) {
		if (permanentEditsSoFar < 0) {
			throw new IllegalArgumentException("Permanent edit count cannot be negative");
		}
		return Math.min(MAX_TERRAIN_EDITS_PER_TICK,
				Math.max(0, MAX_PERMANENT_TERRAIN_EDITS - permanentEditsSoFar));
	}

	private static void requireRosterSize(int frozenRosterSize) {
		if (frozenRosterSize < MIN_ROSTER_SIZE || frozenRosterSize > MAX_ROSTER_SIZE) {
			throw new IllegalArgumentException("Frozen roster size must be between 1 and 8");
		}
	}

	private static void requireDestroyedAnchors(int destroyedAnchors) {
		if (destroyedAnchors < 0 || destroyedAnchors > TOTAL_ANCHORS) {
			throw new IllegalArgumentException("Destroyed anchor count must be between 0 and 10");
		}
	}

	private static void requireAliveAnchors(int aliveAnchors) {
		if (aliveAnchors < 0 || aliveAnchors > TOTAL_ANCHORS) {
			throw new IllegalArgumentException("Alive anchor count must be between 0 and 10");
		}
	}

	public enum TickVerdict {
		ONGOING,
		SUCCESS,
		FAILURE
	}
}
