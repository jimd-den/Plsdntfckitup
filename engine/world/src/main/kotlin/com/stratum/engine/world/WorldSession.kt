package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillCooldowns
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.tabletop.ActiveBoon
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlin.math.roundToInt

/**
 * One run: a streaming world, a player, and the rules that connect them.
 *
 * The session owns all mutable game state and exposes it only as immutable
 * snapshots, so the UI layer can never reach in and change the world behind
 * the simulation's back.
 *
 * It is the orchestrator, not the rulebook. The rules live in systems --
 * [BuildingSystem], [GearSystem], [SurvivalFacade], [ProgressionSystem],
 * [PoliticsSystem], [FightSystem] and [EncounterSystem], over [CombatSystem]
 * -- which share the player and the bodies through one [SessionState], and
 * are built and joined by [SessionParts]. The session decides the order they
 * run in each tick, starts and restarts the run, takes snapshots, and answers
 * for each system's verbs by delegation, so callers see one object.
 */
class WorldSession private constructor(private val parts: SessionParts) :
    SessionBuilding by parts.building,
    SessionGear by parts.gear,
    SessionSurvival by parts.survival,
    SessionProgression by parts.progression,
    SessionPolitics by parts.politics,
    SessionFight by parts.fight {

    constructor(
        content: AssembledContent,
        config: WorldConfig,
        heroClassId: String? = null,
        /**
         * The algorithm that builds the terrain. Null resolves the one the loaded
         * packs asked for, which is what makes world generation swappable without
         * touching the session: pass your own here, or register a factory and name
         * it in a pack's recipe.
         */
        terrainGenerator: TerrainGenerator? = null,
        /** How hard this world is: a tier, and a waystone's mods when one opened it. */
        difficulty: Difficulty = Difficulty.BASE,
        /** A character carried in from an earlier world; null starts fresh at level one. */
        hero: HeroSave? = null,
    ) : this(SessionParts(content, config, heroClassId, terrainGenerator, difficulty, hero))

    val content: AssembledContent get() = parts.content
    val config: WorldConfig get() = parts.config
    val difficulty: Difficulty get() = parts.difficulty

    /** How this world plays; see [WorldRules]. */
    val rules: WorldRules get() = config.rules

    /** Dawn to dawn; night is colder and busier. */
    val clock: WorldClock get() = parts.clock

    private val state = parts.state
    private val streamingWorld = parts.world
    private val motion = parts.motion
    private val combat = parts.combat
    private val encounters = parts.encounters
    private val animator = parts.animator
    private val cues = parts.cues

    val world: World get() = streamingWorld

    /** The world with write access, for persistence and tests in this module; features see [world]. */
    internal val editableWorld: StreamingWorld get() = streamingWorld

    /** Writable inside the engine only, so the UI cannot change the world behind the simulation. */
    var player: PlayerState
        get() = state.player
        internal set(value) {
            state.player = value
        }

    var enemies: List<EnemyInstance>
        get() = state.enemies
        internal set(value) {
            state.enemies = value
        }

    /** Loot lying on the ground, waiting to be walked over. */
    var groundLoot: List<GroundLoot>
        get() = parts.ground.loot
        internal set(value) {
            parts.ground.loot = value
        }

    /** Inserts lying on the ground. Separate from [groundLoot] because they stack into a pouch. */
    var groundInserts: List<GroundInsert>
        get() = parts.ground.inserts
        internal set(value) {
            parts.ground.inserts = value
        }

    /** Events produced by the last tick, for the UI to draw and then forget. */
    var events: List<CombatEvent> = emptyList()
        private set

    init {
        streamingWorld.focusOn(SPAWN_CHUNK)
        val spawn = parts.spawnPoint()
        val fresh = parts.heroClass?.let { PlayerState.from(it, spawn) } ?: PlayerState(heroClassId = "none", position = spawn)
        player = parts.gear.armed(fresh, parts.heroClass).let { armed -> parts.hero?.restoreOnto(armed) ?: armed }
        player = parts.profile.restored(parts.progression.settle(player))
        encounters.placeLevelEncounters(currentBiome.id)
    }

    /**
     * Advances the world by one frame, in a fixed order: clocks and the
     * body's needs, movement (so a roll can carry the player out of reach
     * before the monsters swing), the markers the player walked up to, the
     * realm, the fight, pickups, and the animation that shows it all.
     *
     * Returns the events it produced rather than mutating a log, so a caller
     * that drops a frame loses the floating numbers and nothing else.
     */
    fun tick(deltaSeconds: Float): List<CombatEvent> {
        if (!player.isAlive) {
            events = emptyList()
            return events
        }
        cues.advance(deltaSeconds)
        parts.table.advance(deltaSeconds)
        parts.flashes.advance(deltaSeconds)
        animator.advanceHolds(deltaSeconds)
        advanceImpacts(deltaSeconds)
        clock.advance(deltaSeconds)
        parts.survival.advance(deltaSeconds, clock, currentBiome)
        // A stun roots the player; a chill slows them. Movement still runs, so a roll already under way finishes.
        val hindered = if (combat.playerStunned(player)) 0f else 1f - combat.playerSlow(player)
        val pace = (player.sheet(content::insert) + parts.survival.modifiers()).multiplier(Stat.MOVE_SPEED) * hindered
        player = motion.advance(player, deltaSeconds, PlayerMotion.WALK_SPEED * pace)
        streamingWorld.focusOn(player.blockPos)
        encounters.populateMarkers()

        val news = parts.politics.advance(deltaSeconds)
        val earlier = state.pending.toList().also { state.pending.clear() }
        val fought = parts.fight.monstersAct(deltaSeconds, currentBiome.id)
        val produced = earlier + news + fought + parts.gear.collect() + parts.politics.endRaids()
        animator.advance(deltaSeconds, PLAYER_ACTOR_ID, playerMotionState(), enemies) { parts.flashes.intensity(it) > 0f }
        player = player.copy(cooldowns = player.cooldowns.advanced(deltaSeconds), attackCooldown = (player.attackCooldown - deltaSeconds).coerceAtLeast(0f))
        events = produced
        return produced
    }

    /**
     * Gets the player back on their feet after dying, in the same world.
     * Death costs progress toward the current level, never the level and
     * never the gear: losing a weapon to a mistimed roll is how people stop
     * playing. What it does cost is the walk back.
     */
    fun revive(): ReviveResult {
        if (player.isAlive) return ReviveResult.StillStanding
        val lost = (player.experience * config.rules.deathPenalty).roundToInt()
        player = player.copy(position = parts.spawnPoint(), experience = (player.experience - lost).coerceAtLeast(0), attackCooldown = 0f, cooldowns = SkillCooldowns())
        combat.clear()
        player = parts.profile.restored(player)
        // The run starts clean: no leftover roll, no stale numbers over a corpse that is no longer there.
        motion.reset()
        parts.building.reset()
        cues.clear()
        parts.flashes.clear()
        parts.impacts.clear()
        animator.clear()
        parts.table.clear()
        parts.survival.clear()
        encounters.clearAround(player.position, REVIVE_CLEAR_RADIUS)
        events = emptyList()
        return ReviveResult.Revived(experienceLost = lost)
    }

    /** Boons and banes running now, from tabletop checks. */
    val activeBoons: List<ActiveBoon> get() = parts.table.boons

    /** Seconds before a check can be tried again; 0 when it is ready. */
    fun checkCooldown(checkId: String): Float = parts.table.cooldownOf(checkId)

    /** Rolls a tabletop check from the session's own dice; see [TableState.attempt]. */
    fun attemptCheck(checkId: String): CheckAttempt =
        parts.table.attempt(content.check(checkId), parts.heroClass, player.level, player.isAlive, parts.random)
            .also { if (it is CheckAttempt.Rolled) cues.checkRolled(it.result, player.position) }

    /** The character as it should be kept, to carry into the next world. */
    fun heroSave(id: String = player.heroClassId, savedAt: Long = 0L): HeroSave = parts.progression.heroSave(id, savedAt)

    /** Short-lived visuals: damage numbers, misses, level-ups. */
    val feedback: List<FeedbackMark> get() = cues.active

    /** How hard an actor is currently being shoved, 0..1. */
    fun impactFor(actorId: String): Float = parts.impacts.intensity(actorId)

    fun animationFor(actorId: String): AnimationPlayback = animator.playbackFor(actorId)

    /** How lit an actor is from a recent hit, 0..1. */
    fun flashFor(actorId: String): Float = parts.flashes.intensity(actorId)

    /** Which region a column belongs to, or null when the generator has none. Art direction is per region. */
    fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition? = parts.biomeSource?.biomeAt(worldX, worldY)

    /** The biome under the player's feet, from the same function of position that made the terrain. */
    val currentBiome: BiomeDefinition get() = parts.biomeAt(player.blockPos.x, player.blockPos.y)

    val isRolling: Boolean get() = motion.isRolling

    val isInvulnerable: Boolean get() = motion.isInvulnerable

    val rollCooldownFraction: Float get() = motion.rollCooldownFraction

    /** Sets the direction the player wants to go. Zero stops them. */
    fun setMoveInput(dx: Float, dy: Float) {
        motion.aim(dx, dy)?.let { player = player.copy(facing = it) }
    }

    fun dodge(): DodgeResult = motion.dodge(player.facing, player.isAlive)

    /** Single-shot movement, for anything that nudges the player a fixed distance rather than holding a direction. */
    fun move(dx: Float, dy: Float): MoveOutcome {
        val outcome = motion.step(player, dx, dy)
        player = outcome.player
        if (outcome.moved) streamingWorld.focusOn(player.blockPos)
        return outcome
    }

    fun selectSlot(slot: Int) {
        player = player.selectingSlot(slot)
    }

    /** Immutable view for the UI layer. */
    fun snapshot(): SessionSnapshot = SessionSnapshot(
        player = player,
        focus = streamingWorld.focus,
        biome = currentBiome,
        miningTarget = parts.building.miningTarget,
        miningFraction = miningFraction,
        isRolling = isRolling,
        isInvulnerable = isInvulnerable,
        rollCooldownFraction = rollCooldownFraction,
        feedback = feedback,
        playerFlash = parts.flashes.intensity(PLAYER_ACTOR_ID),
        playerAnimation = animationFor(PLAYER_ACTOR_ID),
        buildPreview = buildPreview,
        buildTool = buildTool,
        worldRevision = streamingWorld.loadedChunks.sumOf { it.revision },
        enemies = enemies,
        groundLoot = groundLoot,
        groundInserts = groundInserts,
        heldInserts = heldInserts,
        skills = skills,
        activeBoons = activeBoons,
        settlement = currentSettlement,
        settlementHostile = currentSettlement?.let(::isHostileTown) ?: false,
        projectiles = combat.projectiles.active,
        zones = combat.zones.active,
        telegraphs = combat.telegraphs(enemies, player),
        statuses = combat.statuses.all().mapValues { it.value.instances },
        flasks = combat.flaskViews,
        maxHealth = maxHealth,
        maxResource = maxResource,
    )

    /** Moves whatever is still being knocked back. */
    private fun advanceImpacts(deltaSeconds: Float) {
        val moved = parts.impacts.advance(deltaSeconds, enemies.associate { it.instanceId to it.position })
        if (moved.isNotEmpty()) enemies = enemies.map { enemy -> moved[enemy.instanceId]?.let { enemy.copy(position = it) } ?: enemy }
    }

    private fun playerMotionState() = ActorAnimator.PlayerMotionState(isAlive = player.isAlive, isRolling = isRolling, isMoving = motion.input != WorldPoint.ZERO)

    companion object {
        const val PICKUP_RADIUS = GroundItems.PICKUP_RADIUS
        const val BASE_DROP_CHANCE = LootDrops.BASE_DROP_CHANCE
        const val INSERT_DROP_CHANCE = LootDrops.INSERT_DROP_CHANCE

        /** Death costs progress toward this level, never a level and never gear. */
        const val EXPERIENCE_LOST_ON_DEATH = 0.25f

        /** Monsters this close to the spawn point are cleared on a revive. */
        const val REVIVE_CLEAR_RADIUS = 8f
        const val DEFAULT_DAMAGE_TYPE = "stratum:physical"

        /** A hit taking this share of a body's health throws it properly. */
        const val HEAVY_HIT_FRACTION = 0.25f

        /** What an ordinary swing's knockback is scaled down to. */
        const val LIGHT_HIT_DAMPING = 0.2f

        const val PLAYER_ACTOR_ID = "player"

        /** How long an actor is considered mid-swing, for animation only. */
        const val ATTACK_ANIMATION_HOLD = ActorAnimator.HOLD_SECONDS
        private val SPAWN_CHUNK = ChunkPos(0, 0)
    }
}
