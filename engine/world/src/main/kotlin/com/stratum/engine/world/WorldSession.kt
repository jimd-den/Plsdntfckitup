package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.SkillCost
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.actor.SkillCooldowns
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.sandbox.StatBreakdown
import com.stratum.core.domain.sandbox.StatQuery
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.session.SavedChunk
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldPlayer
import com.stratum.core.domain.session.WorldSave
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
 *
 * A session can be kept and resumed: [worldSave] takes the world as it
 * stands, and [restore] builds a session from one. A restored session is
 * built exactly as a new one is -- same seed, same parts -- and then has
 * the saved state laid over it, so there is one road into a world rather
 * than two that drift apart.
 */
class WorldSession private constructor(
    private val parts: SessionParts,
    /** The save this session resumes, or null for a new world. Read once, while the session is built. */
    restoring: WorldSave? = null,
) :
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

    /** Models, sculpting and chiselling laid over this world's land, oldest first; empty on block worlds. */
    val microStamps: List<com.stratum.core.domain.micro.MicroStamp> get() = parts.stampSurface?.stamps().orEmpty()

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

    /**
     * Seconds this world has been played, summed from the steps it was
     * advanced by rather than read from a clock, so a replay counts the same.
     */
    var playSeconds: Double = 0.0
        private set

    init {
        // A save's chunks go in before anything streams, so the ground under a
        // resumed player is the ground they left rather than a fresh copy of it.
        restoring?.let { save ->
            val remap = SavedChunk.remapTable(save.blockIds, content.registry)
            // Stamps are part of the land as generated: laid before any chunk is made, so the saved chunks match it.
            if (save.stamps.isNotEmpty()) parts.stampSurface?.restoreStamps(save.microModels, save.stamps)
            streamingWorld.restoreEdited(save.chunks.map { it.toChunk(remap) })
            encounters.restoreMarkers(save.consumedMarkers)
        }
        streamingWorld.focusOn(restoring?.player?.position?.toBlockPos()?.chunkPos ?: SPAWN_CHUNK)
        val spawn = restoring?.player?.position ?: parts.spawnPoint()
        val fresh = parts.heroClass?.let { PlayerState.from(it, spawn) } ?: PlayerState(heroClassId = "none", position = spawn)
        player = parts.gear.armed(fresh, parts.heroClass).let { armed -> parts.hero?.restoreOnto(armed) ?: armed }
        player = parts.profile.restored(parts.progression.settle(player))
        restoring?.let { save ->
            player = save.player.applyTo(player)
            clock.restore(save.clockSeconds)
            playSeconds = save.playSeconds.toDouble()
            parts.politics.restore(save.realm)
        }
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
        playSeconds += deltaSeconds
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
        // The ground around the player now; the rest of the window a couple of chunks a tick.
        streamingWorld.focusOn(player.blockPos, URGENT_STREAM_RADIUS)
        streamingWorld.pump(STREAM_BUDGET)
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

    /**
     * The world as it stands, to be kept: every chunk the player changed,
     * where they are, the realm, the markers already cleared, the clock, and
     * this world's copy of the hero.
     *
     * Everything is copied, block cells included, so the save can be written
     * out on another thread while this one keeps digging: take it on the
     * thread that ticks the session and nothing in it can tear.
     */
    fun worldSave(identity: WorldIdentity, savedAt: Long = 0L, heroId: String = player.heroClassId): WorldSave = WorldSave(
        identity = identity,
        lastPlayedAt = savedAt,
        playSeconds = playSeconds.toLong(),
        config = config,
        difficulty = difficulty,
        hero = heroSave(heroId, savedAt),
        player = WorldPlayer.of(player),
        clockSeconds = clock.elapsedSeconds,
        blockIds = content.registry.all.map { it.id },
        chunks = streamingWorld.dirtyChunks().map(SavedChunk::of).sortedWith(compareBy({ it.x }, { it.y })),
        realm = parts.politics.realmSave(),
        consumedMarkers = encounters.consumedMarkers,
        microModels = parts.stampSurface?.stampModels().orEmpty(),
        stamps = parts.stampSurface?.stamps().orEmpty(),
    )

    /** Short-lived visuals: damage numbers, misses, level-ups. */
    val feedback: List<FeedbackMark> get() = cues.active

    /** How hard an actor is currently being shoved, 0..1. */
    fun impactFor(actorId: String): Float = parts.impacts.intensity(actorId)

    fun animationFor(actorId: String): AnimationPlayback = animator.playbackFor(actorId)

    /** How lit an actor is from a recent hit, 0..1. */
    fun flashFor(actorId: String): Float = parts.flashes.intensity(actorId)

    /** Which region a column belongs to, or null when the generator has none. Art direction is per region. */
    fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition? = parts.biomeSource?.biomeAt(worldX, worldY)

    /**
     * Quarter-block detail behind the blocks, when this world was generated
     * in microvoxels (`stratum:microvoxel`); null otherwise. Read-only: the
     * block [world] stays the truth for play, and a renderer draws blocks
     * wherever the player has changed them.
     */
    val microTerrain: com.stratum.engine.microvoxel.MicroTerrainSource? get() = parts.microTerrain

    /**
     * The terrain's stages, live-editable, when this world is made of
     * microvoxels; null for worlds that cannot be retuned while played.
     */
    val hotTerrain: com.stratum.engine.microbridge.HotTerrain? get() = parts.hotTerrain

    /**
     * Reshapes the world while it is played: rebuilds the terrain from
     * [passes], remakes every chunk the player has not changed, and stands
     * the player (and anyone near) back on the new ground. The passes are
     * kept in [config], so the world saves as it was tuned.
     *
     * Returns null when the new land is in place, or why it could not be --
     * in which case the world is exactly as it was.
     */
    fun retuneTerrain(passes: List<com.stratum.engine.microvoxel.gen.StageSpec>): String? {
        val hot = parts.hotTerrain ?: return NOT_HOT
        return installTerrain(hot.prepare(passes))
    }

    /**
     * The second half of [retuneTerrain], on the thread that ticks the
     * session: [prepared] was built by [com.stratum.engine.microbridge.HotTerrain.prepare]
     * on any thread, so the slow part never stalls a frame.
     */
    fun installTerrain(prepared: com.stratum.engine.microbridge.HotTerrain.Prepared): String? {
        val hot = parts.hotTerrain ?: return NOT_HOT
        hot.install(prepared)?.let { return it }
        parts.config = parts.config.copy(terrainPasses = hot.passes.map { com.stratum.core.domain.world.PassSpec(it.id, it.options) })
        streamingWorld.regenerate()
        player = player.copy(position = onGround(player.position))
        state.enemies = state.enemies.map { it.copy(position = onGround(it.position)) }
        return null
    }

    private val NOT_HOT = "This world's terrain cannot be reshaped while played"

    /** [p] moved up out of the ground, if new land has buried it; left alone in the air, where it will fall. */
    private fun onGround(p: WorldPoint): WorldPoint {
        val x = kotlin.math.floor(p.x).toInt(); val y = kotlin.math.floor(p.y).toInt()
        fun open(z: Int) = !streamingWorld.isSolid(com.stratum.core.domain.world.BlockPos(x, y, z))
        var z = kotlin.math.floor(p.z).toInt().coerceAtLeast(1)
        // The first gap two blocks tall at or above the feet: under a roof stays under it, inside a hill climbs out.
        while (z < com.stratum.core.domain.world.Chunk.HEIGHT - 2 && !(open(z) && open(z + 1))) z++
        return if (z.toFloat() <= p.z) p else p.copy(z = z.toFloat())
    }

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
        if (outcome.moved) streamingWorld.focusOn(player.blockPos, URGENT_STREAM_RADIUS)
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
        // Edits and streaming both: a chunk that streams in starts at revision
        // zero, so the edit sum alone never changed when new ground arrived.
        worldRevision = 31 * streamingWorld.loadedChunks.sumOf { it.revision } + streamingWorld.residency,
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

    internal val inspector: BuildInspector get() = parts.inspector

    /** One of the player's numbers, every source of it, and the formula that made it; see [BuildInspector]. */
    fun explain(query: StatQuery): StatBreakdown = inspector.explain(player, query)

    /** The rules the character breaks now: from the class, the tree and what is worn. */
    val keystones: Set<Keystone> get() = parts.profile.traits(player).keystones

    /** What one use of [skill] costs this character now, paid in life under a keystone that says so. */
    fun costOf(skill: SkillDefinition): SkillCost = SkillCost.of(skill, Keystone.LIFE_PAYS_COSTS in keystones)

    /** The fought-with stats of [state] as this world resolves them: for comparing a piece of gear against the whole character. */
    fun statsFor(state: PlayerState): CombatStats = parts.profile.statsWith(state, parts.profile.traits(state).modifiers)

    /** [state] refilled to its own ceilings, counted as they are for the player: for tools that remake the character. */
    internal fun refilled(state: PlayerState): PlayerState = parts.profile.restored(state)

    /** Places a monster at [rank] through the same path a dungeon's boss takes: for tools that call a specific fight. */
    internal fun spawnAt(definition: EnemyDefinition, position: WorldPoint, rank: EnemyRank): EnemyInstance = encounters.spawn(definition, position, rank)

    /** The build sandbox's tools, in a world whose rules allow them; null everywhere else. */
    val sandbox: SandboxTools? = if (config.rules.sandbox) SandboxTools(this, combat) else null

    companion object {
        /**
         * A session resuming [save]: its world regenerated from the seed with
         * the saved chunks laid in, and the player, the realm, the markers and
         * the clock where the save left them. [terrainGenerator] is for the
         * same callers who pass one to a new session; null resolves the
         * packs' own, as the save's world was made with.
         */
        fun restore(content: AssembledContent, save: WorldSave, terrainGenerator: TerrainGenerator? = null): WorldSession =
            WorldSession(SessionParts(content, save.config, save.heroClassId, terrainGenerator, save.difficulty, save.hero), save)

        /**
         * Chunks each way from the player's that are generated the tick they
         * are needed. Beyond it the window is filled [STREAM_BUDGET] a tick:
         * the player is always at least this far from ungenerated ground, and
         * crossing a chunk border no longer generates a whole row at once.
         */
        const val URGENT_STREAM_RADIUS = 2
        const val STREAM_BUDGET = 2
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
