package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.actor.SkillCooldowns
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One run: a streaming world, a player, and the rules that connect them.
 *
 * The session owns all mutable game state and exposes it only as immutable
 * snapshots, so the UI layer can never reach in and change the world behind the
 * simulation's back.
 *
 * It is the orchestrator, not the rulebook. Each concern keeps its own state
 * and rules in its own part -- [PlayerMotion], [MiningProgress], [BuildSession],
 * [GroundItems], [LootDrops], [PlayerGear], [ActorAnimator], [SessionCues] --
 * and the session decides only the order they run in and passes the player
 * between them, since the player is the one thing they all touch.
 */
class WorldSession(
    val content: AssembledContent,
    val config: WorldConfig,
    heroClassId: String? = null,
    /**
     * The algorithm that builds the terrain. Null resolves the one the loaded
     * packs asked for, which is what makes world generation swappable without
     * touching the session: pass your own here, or register a factory and name
     * it in a pack's recipe.
     */
    terrainGenerator: TerrainGenerator? = null,
) {
    private val generator: TerrainGenerator = terrainGenerator ?: StratumTerrain.create(content.terrainContext(config))

    /**
     * Only generators that claim to know about biomes are asked. One that does
     * not -- a dungeon builder, a flat sandbox -- leaves the region unnamed rather
     * than being forced to invent one.
     */
    private val biomeSource: BiomeSource? = generator as? BiomeSource
    private val streamingWorld = StreamingWorld(content.registry, generator, config)
    private val interaction = BlockInteractionSystem(streamingWorld)

    /** One RNG for the whole run, seeded from the world seed, so a session replays identically given the same inputs. */
    private val random = Random(config.seed)

    private val combat = CombatResolver()
    private val cues = SessionCues()
    private val hitFlashes = HitFlashes()
    private val animator = ActorAnimator()

    /** Knockback, so a hit moves the thing it lands on. */
    private val impacts = ImpactField(streamingWorld)
    private val lootRoller = LootRoller(content.weapons, content.affixes, content.inserts)
    private val drops = LootDrops(content, lootRoller, config.seaLevel)
    private val ground = GroundItems()
    private val gear = PlayerGear(::insertOrNull)
    private val director = EnemyDirector(streamingWorld, content.enemies)
    private val mining = MiningProgress()
    private val building = BuildSession(streamingWorld, interaction, content.registry)
    private val roomScanner = RoomScanner(streamingWorld)

    /** Stick intent, the dodge roll, collision and gravity. See [PlayerMotion]. */
    private val motion = PlayerMotion(streamingWorld)

    private val hero = heroClassId
        ?.let { id -> content.heroClasses.firstOrNull { it.id == id } }
        ?: content.heroClasses.firstOrNull()

    val world: World get() = streamingWorld

    /**
     * Writable inside the engine only. Feature modules see an immutable value,
     * so the UI cannot reach in and change the world behind the simulation.
     * Persistence and tests live in this module and legitimately need the seam.
     */
    var player: PlayerState internal set

    var enemies: List<EnemyInstance> = emptyList()
        internal set

    /** Loot lying on the ground, waiting to be walked over. */
    var groundLoot: List<GroundLoot>
        get() = ground.loot
        internal set(value) {
            ground.loot = value
        }

    /** Inserts lying on the ground. Separate from [groundLoot] because they stack into a pouch. */
    var groundInserts: List<GroundInsert>
        get() = ground.inserts
        internal set(value) {
            ground.inserts = value
        }

    /** Events produced by the last tick, for the UI to draw and then forget. */
    var events: List<CombatEvent> = emptyList()
        private set

    init {
        streamingWorld.focusOn(SPAWN_CHUNK)
        val spawn = findSpawn()
        player = hero?.let { PlayerState.from(it, spawn) } ?: PlayerState(heroClassId = "none", position = spawn)
        player = armed(player).let { it.copy(health = it.maxHealthWithGear) }
    }

    // ---- what the renderer asks -------------------------------------------

    /** Short-lived visuals: damage numbers, misses, level-ups. */
    val feedback: List<FeedbackMark> get() = cues.active

    /** How hard an actor is currently being shoved, 0..1. */
    fun impactFor(actorId: String): Float = impacts.intensity(actorId)

    fun animationFor(actorId: String): AnimationPlayback = animator.playbackFor(actorId)

    /** How lit an actor is from a recent hit, 0..1. */
    fun flashFor(actorId: String): Float = hitFlashes.intensity(actorId)

    /**
     * Which region a column belongs to, or null when the generator has none.
     * Exposed for the renderer: art direction is per region.
     */
    fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition? = biomeSource?.biomeAt(worldX, worldY)

    /** The biome under the player's feet, from the same function of position that made the terrain. */
    val currentBiome: BiomeDefinition
        get() = biomeSource?.biomeAt(player.blockPos.x, player.blockPos.y) ?: content.biomes.firstOrNull() ?: UNCHARTED

    /** Skills the class has, resolved against the loaded packs. */
    val skills: List<SkillDefinition> get() = player.skillIds.mapNotNull(content::skill)

    // ---- movement --------------------------------------------------------

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

    // ---- digging and placing ---------------------------------------------

    /** Applies mining effort to a block, continuing a dig already under way on it. */
    fun mine(target: BlockPos, deltaSeconds: Float): MineResult {
        val request = MineRequest(player.blockPos, target, player.toolTier, deltaSeconds, mining.effortOn(target))
        val result = interaction.mine(request)
        when (result) {
            is MineResult.InProgress -> mining.record(result.progress)
            is MineResult.Broken -> {
                mining.reset()
                player = motion.advance(pocketed(player, result.drop), 0f)
            }
            is MineResult.Rejected -> mining.reset()
        }
        return result
    }

    fun cancelMining() = mining.reset()

    val miningFraction: Float get() = mining.fraction(world)

    /**
     * Places the selected hotbar block against the block the player touched,
     * spending one from the inventory. [picked] is a solid block -- the only
     * thing a tap can resolve to -- so the cell to fill is the face next to it.
     */
    fun place(picked: BlockPos): PlaceResult {
        val blockId = player.selectedBlockId ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)
        // Building and digging share a surface. Placing without stopping the dig
        // means the block being mined keeps breaking while you build.
        cancelMining()
        val target = interaction.placementCellFor(picked, player.blockPos, actorCells())
            ?: return PlaceResult.Rejected(PlaceRejection.OCCUPIED)
        val spent = player.consuming(blockId) ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)
        val result = interaction.place(
            PlaceRequest(player.blockPos, target, blockId, occupiedByActors = setOf(player.feet, player.feet.above())),
        )
        if (result is PlaceResult.Placed) player = spent
        return result
    }

    /** Where a tap on [picked] would actually put a block, for the ghost preview. */
    fun placementPreviewFor(picked: BlockPos): BlockPos? =
        interaction.placementCellFor(picked, player.blockPos, actorCells())

    fun selectSlot(slot: Int) {
        player = player.selectingSlot(slot)
    }

    // ---- building ---------------------------------------------------------

    /** Blocks a pending build would place, for the ghost preview. Empty when not building. */
    val buildPreview: List<BlockPos> get() = building.preview

    val buildTool: BuildTool get() = building.tool

    fun selectBuildTool(tool: BuildTool) = building.selectTool(tool)

    /** Previews what a drag from [from] to [to] would build. Nothing is placed. */
    fun previewBuild(from: BlockPos, to: BlockPos): BuildPreview = building.plan(from, to, player)

    fun cancelBuild() = building.cancel()

    fun commitBuild(): BuildResult {
        val committed = building.commit(player)
        player = committed.player
        when (val result = committed.result) {
            is BuildResult.Built -> cues.built(result.placed, player.position)
            is BuildResult.Erased -> {
                player = motion.advance(player, 0f)
                cues.cleared(result.removed, player.position)
            }
            else -> Unit
        }
        return committed.result
    }

    /** The room the player is standing in, if any. Recomputed on demand, because walls change while building. */
    fun shelter(): RoomScan = roomScanner.scan(player.blockPos)

    // ---- the fight --------------------------------------------------------

    /**
     * Advances the world by one frame: spawns, moves and resolves monsters,
     * collects loot the player is standing on, and ticks cooldowns.
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
        hitFlashes.advance(deltaSeconds)
        animator.advanceHolds(deltaSeconds)
        advanceImpacts(deltaSeconds)
        // Movement first: a roll should be able to carry the player out of
        // reach before the monsters around them take their swing.
        player = motion.advance(player, deltaSeconds)
        streamingWorld.focusOn(player.blockPos)

        val produced = monstersAct(deltaSeconds) + collectLoot() + collectInserts()
        animator.advance(deltaSeconds, PLAYER_ACTOR_ID, playerMotionState(), enemies) { hitFlashes.intensity(it) > 0f }
        player = player.copy(
            cooldowns = player.cooldowns.advanced(deltaSeconds),
            attackCooldown = (player.attackCooldown - deltaSeconds).coerceAtLeast(0f),
        )
        events = produced
        return produced
    }

    /** A basic swing. Refused while the weapon is still recovering. */
    fun attack(): AttackReport {
        if (player.attackCooldown > 0f) return AttackReport.NotReady
        val stats = playerStats
        val damageType = player.equippedWeapon?.damageTypeWithSockets(::insertOrNull) ?: DEFAULT_DAMAGE_TYPE
        val outcome = combat.playerAttack(stats, player.position, player.facing, enemies, damageType, random)
        player = player.copy(attackCooldown = stats.secondsBetweenAttacks)
        animator.holdAttack(PLAYER_ACTOR_ID)
        return applyOutcome(outcome)
    }

    /** Casts one of the class's skills, spending resource and starting its cooldown. */
    fun castSkill(skillId: String): AttackReport {
        val skill = content.skill(skillId) ?: return AttackReport.UnknownSkill
        if (!player.cooldowns.isReady(skillId)) return AttackReport.OnCooldown
        if (player.resource < skill.resourceCost) return AttackReport.NotEnoughResource

        val outcome = combat.castSkill(playerStats, player.position, player.facing, enemies, skill, random)
        // Cost and cooldown are paid whether or not anything was standing there,
        // so a skill cannot be spammed to scout for targets for free.
        player = player.copy(
            resource = (player.resource - skill.resourceCost).coerceAtLeast(0),
            cooldowns = player.cooldowns.started(skill),
        )
        animator.holdCast(PLAYER_ACTOR_ID)
        return applyOutcome(outcome, skill)
    }

    /**
     * Places a monster deliberately, for a scripted encounter or a shrine that
     * wakes something up. The director fills the world on its own; this is for
     * when the world should contain something specific.
     */
    fun spawn(definition: EnemyDefinition, position: WorldPoint): EnemyInstance =
        director.instantiate(definition, position, player.level, random).also { enemies = enemies + it }

    /** Puts an item on the ground, for a chest or a quest reward. */
    fun dropLoot(item: ItemInstance, position: WorldPoint) = ground.drop(GroundLoot(item, position))

    /** Puts an insert on the ground. */
    fun dropInsert(insertId: String, position: WorldPoint) = ground.drop(GroundInsert(insertId, position))

    /** Environmental damage: a fall, a trap, a hazard block. */
    fun hurtPlayer(amount: Int): Boolean {
        if (amount > 0) player = player.damaged(amount)
        return player.isAlive
    }

    /**
     * Gets the player back on their feet after dying, in the same world.
     *
     * Death costs progress toward the current level, not the level itself, and
     * never the gear: losing a weapon you spent an hour socketing to a mistimed
     * roll is how people stop playing. What it does cost is the walk back.
     */
    fun revive(): ReviveResult {
        if (player.isAlive) return ReviveResult.StillStanding
        val lost = (player.experience * EXPERIENCE_LOST_ON_DEATH).roundToInt()
        player = player.copy(
            position = findSpawn(),
            experience = (player.experience - lost).coerceAtLeast(0),
            attackCooldown = 0f,
            cooldowns = SkillCooldowns(),
        )
        player = player.copy(health = player.maxHealthWith(::insertOrNull), resource = player.maxResource)
        forgetTheLastRun()
        // Monsters that had cornered the player do not get to greet them at the
        // spawn point; the director refills the world soon enough.
        enemies = enemies.filter { it.position.horizontalDistanceTo(player.position) > REVIVE_CLEAR_RADIUS }
        events = emptyList()
        return ReviveResult.Revived(experienceLost = lost)
    }

    // ---- the satchel and the anvil ------------------------------------------

    /** Equips something from the bag; what was held goes back into it. */
    fun equip(instanceId: String): EquipResult {
        val (updated, result) = gear.equip(player, instanceId)
        player = updated
        return result
    }

    /**
     * Drops an item out of the bag onto the ground at the player's feet. It
     * lands rather than vanishing, so the player can change their mind.
     */
    fun discard(instanceId: String): EquipResult {
        val item = player.bag.firstOrNull { it.instanceId == instanceId } ?: return EquipResult.NotInBag
        player = player.copy(bag = player.bag - item)
        // Dropped a step away, or the player picks it straight back up.
        ground.drop(GroundLoot(item, player.position.translated(DISCARD_STEP, 0f, 0f)))
        return EquipResult.Discarded(item)
    }

    /** Resolves an insert id against the loaded packs. */
    fun insertOrNull(insertId: String): InsertDefinition? = content.insert(insertId)

    /**
     * The player's stats with their weapon's inserts counted in. Every combat
     * path reads this, so a rune is never in the tooltip but missing from the swing.
     */
    val playerStats: CombatStats get() = player.combatStatsWith(::insertOrNull)

    /** Inserts the player is carrying loose, resolved and sorted for display. */
    val heldInserts: List<HeldInsert>
        get() = player.insertBag.entries
            .mapNotNull { (id, count) -> content.insert(id)?.let { HeldInsert(it, count) } }
            .sortedWith(compareByDescending<HeldInsert> { it.definition.tier }.thenBy { it.definition.name })

    /** Slots one of the player's inserts into an item they are holding. */
    fun slotInsert(instanceId: String, insertId: String): SocketResult {
        val (updated, result) = gear.slot(player, instanceId, insertId)
        player = updated
        if (result is SocketResult.Slotted) {
            val insert = content.insert(insertId)
            cues.insertSlotted(insert?.name ?: insertId, player.position, insert?.color)
        }
        return result
    }

    /** Pulls an insert back out, returning it to the pouch intact. */
    fun unslotInsert(instanceId: String, socketIndex: Int): SocketResult {
        val (updated, result) = gear.unslot(player, instanceId, socketIndex)
        player = updated
        return result
    }

    /** Immutable view for the UI layer. */
    fun snapshot(): SessionSnapshot = SessionSnapshot(
        player = player,
        focus = streamingWorld.focus,
        biome = currentBiome,
        miningTarget = mining.target,
        miningFraction = miningFraction,
        isRolling = isRolling,
        isInvulnerable = isInvulnerable,
        rollCooldownFraction = rollCooldownFraction,
        feedback = feedback,
        playerFlash = hitFlashes.intensity(PLAYER_ACTOR_ID),
        playerAnimation = animationFor(PLAYER_ACTOR_ID),
        buildPreview = buildPreview,
        buildTool = buildTool,
        worldRevision = streamingWorld.loadedChunks.sumOf { it.revision },
        enemies = enemies,
        groundLoot = groundLoot,
        groundInserts = groundInserts,
        heldInserts = heldInserts,
        skills = skills,
    )

    // ---- the steps a tick is made of ----------------------------------------

    /** Monsters spawn, close in and swing. Returns what the player should be told. */
    private fun monstersAct(deltaSeconds: Float): List<CombatEvent> {
        if (content.enemies.isEmpty()) return emptyList()
        enemies = director.maintainPopulation(enemies, player.position, currentBiome.id, player.level, random)
            .map { director.advance(it, player.position, deltaSeconds) }

        val incoming = combat.enemyAttacks(
            enemies = enemies,
            defender = playerStats,
            defenderPosition = player.position,
            cooldownFor = { it.stats.secondsBetweenAttacks },
            random = random,
        )
        // Whoever swung is mid-attack for a beat, so the animation reads.
        incoming.enemies.filter { it.attackCooldown > 0f && it.isAlive }.forEach { animator.holdAttack(it.instanceId) }
        enemies = incoming.enemies
        return if (incoming.totalDamage > 0) takeHit(incoming) else emptyList()
    }

    /**
     * A blow that lands, or does not. A swing during a roll still happened and
     * went on cooldown; reporting that it missed is what makes a well-timed
     * roll legible.
     */
    private fun takeHit(incoming: EnemyAttackOutcome): List<CombatEvent> {
        if (isInvulnerable) {
            cues.dodged(player.position)
            return listOf(CombatEvent.PlayerDodged(incoming.totalDamage))
        }
        player = player.damaged(incoming.totalDamage)
        cues.hurt(incoming.totalDamage, player.position)
        hitFlashes.strike(PLAYER_ACTOR_ID)
        val hurt = CombatEvent.PlayerHurt(incoming.totalDamage, incoming.results)
        if (player.isAlive) return listOf(hurt)
        cues.fallen(player.position)
        return listOf(hurt, CombatEvent.PlayerDied)
    }

    private fun applyOutcome(outcome: AttackOutcome, skill: SkillDefinition? = null): AttackReport {
        if (outcome !is AttackOutcome.Hits) return AttackReport.Missed
        val byId = outcome.hits.associateBy { it.enemyId }
        enemies = enemies.map { byId[it.instanceId]?.enemy ?: it }
        outcome.hits.forEach(::showHit)

        val healed = outcome.hits.sumOf { it.result.healedAttacker }
        if (healed > 0) {
            player = player.healed(healed)
            cues.healed(healed, player.position)
        }
        val slain = enemies.filterNot { it.isAlive }
        if (slain.isNotEmpty()) buryTheDead(slain)
        return AttackReport.Landed(hits = outcome.hits, slain = slain, skill = skill)
    }

    /** The number, the flash and the shove of one hit. */
    private fun showHit(hit: EnemyHit) {
        val color = content.damageType(hit.result.damageTypeId).color
        when {
            hit.result.wasBlocked -> cues.blocked(hit.enemy.position)
            hit.result.wasCritical -> cues.critical(hit.result.amount, hit.enemy.position, color)
            else -> cues.dealt(hit.result.amount, hit.enemy.position, color)
        }
        hitFlashes.strike(hit.enemyId)
        // Force scales with the blow, but only a heavy hit really throws: an
        // ordinary swing barely rocks the body, because knocking a monster back
        // every time pushes it out of reach and turns melee into chase-and-poke.
        val heavy = hit.result.wasCritical || hit.result.amount >= hit.enemy.stats.maxHealth * HEAVY_HIT_FRACTION
        impacts.strike(
            actorId = hit.enemyId,
            from = player.position,
            to = hit.enemy.position,
            force = hit.result.amount.toFloat() * if (heavy) 1f else LIGHT_HIT_DAMPING,
        )
    }

    private fun buryTheDead(slain: List<EnemyInstance>) {
        enemies = enemies.filter { it.isAlive }
        slain.forEach { enemy ->
            hitFlashes.forget(enemy.instanceId)
            impacts.forget(enemy.instanceId)
            drops.gearFor(enemy, player.level, random)?.let(ground::drop)
            drops.insertFor(enemy, player.level, random)?.let(ground::drop)
        }
        awardExperience(slain.sumOf { it.experience })
    }

    private fun awardExperience(amount: Int) {
        if (amount <= 0) return
        val result = Progression.apply(player.level, player.experience, amount)
        player = player.copy(level = result.level, experience = result.experience)
        if (!result.leveledUp) return
        // A level restores the character, which is what makes pushing one more
        // fight at low health a real decision rather than a mistake.
        player = player.copy(health = player.maxHealthWithGear, resource = player.maxResource)
        cues.levelUp(result.level, player.position)
    }

    /** Picks up anything the player is standing on. Upgrades equip themselves. */
    private fun collectLoot(): List<CombatEvent> = ground.takeLootNear(player.position).map { loot ->
        // Making the player open a bag to feel a drop is the fastest way to
        // make loot stop feeling like a reward.
        val autoEquipped = player.isUpgrade(loot.item)
        player = if (autoEquipped) player.equipping(loot.item) else player.collecting(loot.item)
        cues.itemTaken(loot.item.name, player.position, content.rarityColor(loot.item.rarity), autoEquipped)
        CombatEvent.LootTaken(loot.item, autoEquipped)
    }

    /** Picks up inserts the player is standing on, into the pouch. */
    private fun collectInserts(): List<CombatEvent> = ground.takeInsertsNear(player.position).mapNotNull { found ->
        val definition = content.insert(found.insertId) ?: return@mapNotNull null
        player = player.withInsert(definition.id)
        cues.insertTaken(definition.name, player.position, definition.color)
        CombatEvent.InsertTaken(definition)
    }

    /** Moves whatever is still being knocked back. */
    private fun advanceImpacts(deltaSeconds: Float) {
        val moved = impacts.advance(deltaSeconds, enemies.associate { it.instanceId to it.position })
        if (moved.isEmpty()) return
        enemies = enemies.map { enemy -> moved[enemy.instanceId]?.let { enemy.copy(position = it) } ?: enemy }
    }

    private fun playerMotionState() = ActorAnimator.PlayerMotionState(
        isAlive = player.isAlive,
        isRolling = isRolling,
        isMoving = motion.input != WorldPoint.ZERO,
    )

    // ---- setting up, and starting over ---------------------------------------

    /**
     * Arms the class with its starting weapon so the first fight is winnable.
     * Common, so the first upgrade is an upgrade.
     */
    private fun armed(player: PlayerState): PlayerState {
        val base = hero?.startingWeaponId?.let(content::weapon)
            ?: content.weapons.minByOrNull { it.minItemLevel }
            ?: return player
        return player.equipping(lootRoller.craft(base, itemLevel = 1, rarity = ItemRarity.COMMON, random = random))
    }

    /** A mined block goes in the bag, and onto the hotbar if it is something that can be placed. */
    private fun pocketed(player: PlayerState, drop: String): PlayerState {
        val holding = player.withItem(drop)
        val placeable = drop !in holding.hotbar && content.registry.contains(drop)
        return if (placeable) holding.copy(hotbar = holding.hotbar + drop) else holding
    }

    /** The run starts clean: no leftover roll, no stale numbers floating over a corpse that is no longer there. */
    private fun forgetTheLastRun() {
        motion.reset()
        mining.reset()
        cues.clear()
        hitFlashes.clear()
        impacts.clear()
        animator.clear()
    }

    /**
     * Cells a body is standing in. Tapping the ground at your feet should build
     * beside you rather than refuse, so these are skipped while resolving the
     * cell rather than rejected after one has been chosen.
     */
    private fun actorCells(): Set<BlockPos> = buildSet {
        add(player.feet)
        add(player.feet.above())
        enemies.forEach {
            add(it.blockPos)
            add(it.blockPos.above())
        }
    }

    /**
     * Drops the player onto the surface at the world origin. Searches outward if
     * the origin column happens to be unsuitable, so a spawn is never inside rock.
     */
    private fun findSpawn(): WorldPoint {
        for (radius in 0..SPAWN_SEARCH_RADIUS) {
            for (y in -radius..radius) {
                for (x in -radius..radius) {
                    if (maxOf(abs(x), abs(y)) != radius) continue
                    val surface = streamingWorld.surfaceAt(x, y)
                    if (surface in 1 until Chunk.HEIGHT - 2) return WorldPoint(x + 0.5f, y + 0.5f, (surface + 1).toFloat())
                }
            }
        }
        return WorldPoint(0.5f, 0.5f, (config.seaLevel + 1).toFloat())
    }

    companion object {
        const val SPAWN_SEARCH_RADIUS = 12
        const val PICKUP_RADIUS = GroundItems.PICKUP_RADIUS
        const val BASE_DROP_CHANCE = LootDrops.BASE_DROP_CHANCE
        const val INSERT_DROP_CHANCE = LootDrops.INSERT_DROP_CHANCE

        /** Far enough that a discard is not undone by the next tick. */
        const val DISCARD_STEP = 2f

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

        /** Shown when a generator names no regions and the packs define none. */
        private val UNCHARTED = BiomeDefinition(
            id = "stratum:uncharted",
            name = "Uncharted",
            surfaceBlockId = BlockType.BEDROCK.id,
            subsurfaceBlockId = BlockType.BEDROCK.id,
            bedrockFillerBlockId = BlockType.BEDROCK.id,
        )
    }
}
