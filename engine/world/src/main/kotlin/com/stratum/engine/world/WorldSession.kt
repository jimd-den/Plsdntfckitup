package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.sprite.AnimationSelector
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One run: a streaming world, a player, and the rules that connect them.
 *
 * The session owns all mutable game state and exposes it only as immutable
 * snapshots, so the UI layer can never reach in and change the world behind the
 * simulation's back.
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
    private val generator: TerrainGenerator = terrainGenerator ?: StratumTerrain.create(
        TerrainContext(config, content.biomes, content.terrain),
    )

    /**
     * Only generators that claim to know about biomes are asked. One that does
     * not — a dungeon builder, a flat sandbox — leaves the region unnamed rather
     * than being forced to invent one.
     */
    private val biomeSource: BiomeSource? = generator as? BiomeSource
    private val streamingWorld = StreamingWorld(content.registry, generator, config)
    private val interaction = BlockInteractionSystem(streamingWorld)

    /**
     * One RNG for the whole run, seeded from the world seed, so a session
     * replays identically given the same inputs.
     */
    private val random = Random(config.seed)

    private val combat = CombatResolver()

    private val feedbackLog = FeedbackLog()
    private val hitFlashes = HitFlashes()

    /** Knockback, so a hit moves the thing it lands on. */
    private val impacts = ImpactField(streamingWorld)

    /** How hard an actor is currently being shoved, 0..1, for the renderer. */
    fun impactFor(actorId: String): Float = impacts.intensity(actorId)

    /** Short-lived visuals: damage numbers, misses, level-ups. */
    val feedback: List<FeedbackMark> get() = feedbackLog.active

    /**
     * Animation clocks, per actor id. Kept here rather than in the renderer so
     * two monsters in the same state stay independent and a recomposition does
     * not restart every walk cycle.
     */
    private val playbacks = HashMap<String, AnimationPlayback>()

    /** Seconds left of an actor's attack animation, so a swing is not instant. */
    private val attackHolds = HashMap<String, Float>()

    /**
     * Seconds left of a *skill* animation. Kept apart from [attackHolds] so a
     * power does not look like an ordinary swing — if the two read the same,
     * the resource you spent bought nothing you can see.
     */
    private val castHolds = HashMap<String, Float>()

    fun animationFor(actorId: String): AnimationPlayback =
        playbacks[actorId] ?: AnimationPlayback()

    /** How lit an actor is from a recent hit, 0..1. */
    fun flashFor(actorId: String): Float = hitFlashes.intensity(actorId)
    private val lootRoller = LootRoller(content.weapons, content.affixes, content.inserts)
    private val director = EnemyDirector(streamingWorld, content.enemies)

    val world: World get() = streamingWorld

    private val hero = heroClassId
        ?.let { id -> content.heroClasses.firstOrNull { it.id == id } }
        ?: content.heroClasses.firstOrNull()

    /**
     * Writable inside the engine only. Feature modules see an immutable value,
     * so the UI cannot reach in and change the world behind the simulation.
     * Persistence and tests live in this module and legitimately need the seam.
     */
    var player: PlayerState internal set

    /** Effort spent on the block currently being mined, reset when the target changes. */
    private var miningTarget: BlockPos? = null
    private var miningProgress: Float = 0f

    var enemies: List<EnemyInstance> = emptyList()
        internal set

    /** Loot lying on the ground, waiting to be walked over. */
    var groundLoot: List<GroundLoot> = emptyList()
        internal set

    /** Inserts lying on the ground. Separate from [groundLoot] because they
     * stack into a pouch rather than becoming items in their own right. */
    var groundInserts: List<GroundInsert> = emptyList()
        internal set

    /** Events produced by the last tick, for the UI to draw and then forget. */
    var events: List<CombatEvent> = emptyList()
        private set

    init {
        streamingWorld.focusOn(SPAWN_CHUNK)
        val spawn = findSpawn()
        player = hero
            ?.let { PlayerState.from(it, spawn) }
            ?: PlayerState(heroClassId = "none", position = spawn)

        // Arm the class with its starting weapon so the first fight is winnable.
        // Common, so the first upgrade is an upgrade.
        val startingBase = hero?.startingWeaponId?.let(content::weapon)
            ?: content.weapons.minByOrNull { it.minItemLevel }
        if (startingBase != null) {
            player = player.equipping(
                lootRoller.craft(startingBase, itemLevel = 1, rarity = ItemRarity.COMMON, random = random),
            )
        }
        player = player.copy(health = player.maxHealthWithGear)
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
                    if (surface in 1 until Chunk.HEIGHT - 2) {
                        return WorldPoint(x + 0.5f, y + 0.5f, (surface + 1).toFloat())
                    }
                }
            }
        }
        return WorldPoint(0.5f, 0.5f, (config.seaLevel + 1).toFloat())
    }

    // ---- movement --------------------------------------------------------

    /** Stick intent, the dodge roll, collision and gravity. See [PlayerMotion]. */
    private val motion = PlayerMotion(streamingWorld)

    val isRolling: Boolean get() = motion.isRolling

    val isInvulnerable: Boolean get() = motion.isInvulnerable

    val rollCooldownFraction: Float get() = motion.rollCooldownFraction

    /** Sets the direction the player wants to go. Zero stops them. */
    fun setMoveInput(dx: Float, dy: Float) {
        motion.aim(dx, dy)?.let { player = player.copy(facing = it) }
    }

    fun dodge(): DodgeResult = motion.dodge(player.facing, player.isAlive)

    private fun advanceMovement(deltaSeconds: Float) {
        player = motion.advance(player, deltaSeconds)
        streamingWorld.focusOn(player.blockPos)
    }

    /**
     * Single-shot movement, for anything that wants to nudge the player a fixed
     * distance rather than hold a direction.
     */
    fun move(dx: Float, dy: Float): MoveOutcome {
        val outcome = motion.step(player, dx, dy)
        player = outcome.player
        if (outcome.moved) streamingWorld.focusOn(player.blockPos)
        return outcome
    }


    // ---- interaction -----------------------------------------------------

    /**
     * Applies mining effort to a block. Progress is kept per target here rather
     * than by the caller, so releasing and re-pressing on the same block does not
     * silently restart the dig.
     */
    fun mine(target: BlockPos, deltaSeconds: Float): MineResult {
        if (target != miningTarget) {
            miningTarget = target
            miningProgress = 0f
        }

        val result = interaction.mine(
            MineRequest(
                origin = player.blockPos,
                target = target,
                toolTier = player.toolTier,
                deltaSeconds = deltaSeconds,
                progressSoFar = miningProgress,
            ),
        )

        when (result) {
            is MineResult.InProgress -> miningProgress = result.progress
            is MineResult.Broken -> {
                miningTarget = null
                miningProgress = 0f
                player = player.withItem(result.drop)
                if (result.drop !in player.hotbar && content.registry.contains(result.drop)) {
                    player = player.copy(hotbar = player.hotbar + result.drop)
                }
                player = motion.advance(player, 0f)
            }
            is MineResult.Rejected -> {
                miningTarget = null
                miningProgress = 0f
            }
        }
        return result
    }

    fun cancelMining() {
        miningTarget = null
        miningProgress = 0f
    }

    val miningFraction: Float
        get() {
            val target = miningTarget ?: return 0f
            val hardness = world.blockAt(target).hardness
            return if (hardness <= 0f) 0f else (miningProgress / hardness).coerceIn(0f, 1f)
        }

    /**
     * Places the selected hotbar block against the block the player touched,
     * spending one from the inventory.
     *
     * [picked] is a solid block — the only thing a tap can resolve to — so the
     * cell to fill is the face next to it, not the block itself.
     */
    fun place(picked: BlockPos): PlaceResult {
        val blockId = player.selectedBlockId
            ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)

        // Building and digging share a surface. Placing without stopping the dig
        // means the block you were mining keeps breaking while you build, which
        // reads as "placing removes blocks".
        cancelMining()

        val target = interaction.placementCellFor(picked, player.blockPos, actorCells())
            ?: return PlaceResult.Rejected(PlaceRejection.OCCUPIED)

        val spent = player.consuming(blockId)
            ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)

        val result = interaction.place(
            PlaceRequest(
                origin = player.blockPos,
                target = target,
                blockId = blockId,
                occupiedByActors = setOf(player.feet, player.feet.above()),
            ),
        )
        if (result is PlaceResult.Placed) {
            player = spent
        }
        return result
    }

    /** Where a tap on [picked] would actually put a block, for the ghost preview. */
    fun placementPreviewFor(picked: BlockPos): BlockPos? =
        interaction.placementCellFor(picked, player.blockPos, actorCells())

    /**
     * Cells a body is standing in. Tapping the ground at your feet should build
     * beside you rather than refuse, so these are skipped while resolving the
     * cell rather than rejected after one has been chosen.
     */
    private fun actorCells(): Set<BlockPos> =
        buildSet {
            add(player.feet)
            add(player.feet.above())
            enemies.forEach {
                add(it.blockPos)
                add(it.blockPos.above())
            }
        }

    fun selectSlot(slot: Int) {
        player = player.selectingSlot(slot)
    }

    // ---- the fight ------------------------------------------------------

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

        val produced = mutableListOf<CombatEvent>()

        feedbackLog.advance(deltaSeconds)
        hitFlashes.advance(deltaSeconds)
        advanceAttackHolds(deltaSeconds)
        advanceImpacts(deltaSeconds)

        // Movement first: a roll should be able to carry the player out of
        // reach before the monsters around them take their swing.
        advanceMovement(deltaSeconds)

        if (content.enemies.isNotEmpty()) {
            enemies = director.maintainPopulation(
                current = enemies,
                focus = player.position,
                biomeId = currentBiome.id,
                playerLevel = player.level,
                random = random,
            ).map { director.advance(it, player.position, deltaSeconds) }

            val incoming = combat.enemyAttacks(
                enemies = enemies,
                defender = playerStats,
                defenderPosition = player.position,
                cooldownFor = { it.stats.secondsBetweenAttacks },
                random = random,
            )
            // Whoever swung is mid-attack for a beat, so the animation reads.
            incoming.enemies.filter { it.attackCooldown > 0f && it.isAlive }
                .forEach { attackHolds[it.instanceId] = ATTACK_ANIMATION_HOLD }
            enemies = incoming.enemies
            if (incoming.totalDamage > 0) {
                if (isInvulnerable) {
                    // The swing happened and went on cooldown; it simply did not
                    // land. Reporting it is what makes a well-timed roll legible.
                    produced += CombatEvent.PlayerDodged(incoming.totalDamage)
                    feedbackLog.add(
                        kind = FeedbackKind.DODGED,
                        text = "DODGED",
                        origin = player.position,
                        color = FEEDBACK_DODGE,
                        emphasis = 1.1f,
                    )
                } else {
                    player = player.damaged(incoming.totalDamage)
                    produced += CombatEvent.PlayerHurt(incoming.totalDamage, incoming.results)
                    feedbackLog.add(
                        kind = FeedbackKind.DAMAGE_TAKEN,
                        text = "-${incoming.totalDamage}",
                        origin = player.position,
                        color = FEEDBACK_HURT,
                        emphasis = 1.2f,
                    )
                    hitFlashes.strike(PLAYER_ACTOR_ID)
                    if (!player.isAlive) {
                        produced += CombatEvent.PlayerDied
                        feedbackLog.add(
                            kind = FeedbackKind.KILL,
                            text = "FALLEN",
                            origin = player.position,
                            color = FEEDBACK_HURT,
                            emphasis = 1.8f,
                            lifetime = 2.5f,
                        )
                    }
                }
            }
        }

        produced += collectLoot()
        produced += collectInserts()
        advanceAnimations(deltaSeconds)

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
        val outcome = combat.playerAttack(
            attacker = stats,
            attackerPosition = player.position,
            facing = player.facing,
            enemies = enemies,
            damageTypeId = player.equippedWeapon?.damageTypeWithSockets(::insertOrNull)
                ?: DEFAULT_DAMAGE_TYPE,
            random = random,
        )
        player = player.copy(attackCooldown = stats.secondsBetweenAttacks)
        attackHolds[PLAYER_ACTOR_ID] = ATTACK_ANIMATION_HOLD
        return applyOutcome(outcome)
    }

    /** Casts one of the class's skills, spending resource and starting its cooldown. */
    fun castSkill(skillId: String): AttackReport {
        val skill = content.skill(skillId) ?: return AttackReport.UnknownSkill
        if (!player.cooldowns.isReady(skillId)) return AttackReport.OnCooldown
        if (player.resource < skill.resourceCost) return AttackReport.NotEnoughResource

        val outcome = combat.castSkill(
            attacker = playerStats,
            attackerPosition = player.position,
            facing = player.facing,
            enemies = enemies,
            skill = skill,
            random = random,
        )

        // Cost and cooldown are paid whether or not anything was standing there,
        // so a skill cannot be spammed to scout for targets for free.
        player = player.copy(
            resource = (player.resource - skill.resourceCost).coerceAtLeast(0),
            cooldowns = player.cooldowns.started(skill),
        )
        attackHolds[PLAYER_ACTOR_ID] = ATTACK_ANIMATION_HOLD
        castHolds[PLAYER_ACTOR_ID] = ATTACK_ANIMATION_HOLD
        return applyOutcome(outcome, skill)
    }

    private fun applyOutcome(outcome: AttackOutcome, skill: SkillDefinition? = null): AttackReport {
        if (outcome !is AttackOutcome.Hits) return AttackReport.Missed

        val byId = outcome.hits.associateBy { it.enemyId }
        enemies = enemies.map { byId[it.instanceId]?.enemy ?: it }

        outcome.hits.forEach { hit ->
            val typeColor = content.damageType(hit.result.damageTypeId).color
            when {
                hit.result.wasBlocked -> feedbackLog.add(
                    kind = FeedbackKind.BLOCKED,
                    text = "BLOCKED",
                    origin = hit.enemy.position,
                    color = FEEDBACK_BLOCKED,
                    emphasis = 0.85f,
                )
                hit.result.wasCritical -> feedbackLog.add(
                    kind = FeedbackKind.CRITICAL,
                    text = "${hit.result.amount}!",
                    origin = hit.enemy.position,
                    color = typeColor,
                    // Crits read as bigger and last longer. A critical the
                    // player cannot distinguish from a normal hit is a stat
                    // they have no reason to build for.
                    emphasis = 1.7f,
                    lifetime = 1.2f,
                )
                else -> feedbackLog.add(
                    kind = FeedbackKind.DAMAGE_DEALT,
                    text = hit.result.amount.toString(),
                    origin = hit.enemy.position,
                    color = typeColor,
                )
            }
            hitFlashes.strike(hit.enemyId)
            // Shoved away from whoever swung. Force scales with the blow, so a
            // critical visibly throws and a chip barely rocks — the difference
            // between a heavy hit and a glancing one becomes something you see
            // rather than something you read off a number.
            // Only a heavy hit really throws. An ordinary swing barely rocks
            // the body, because knocking a monster back every time pushes it
            // out of reach and turns melee into chase-and-poke.
            val heavy = hit.result.wasCritical ||
                hit.result.amount >= hit.enemy.stats.maxHealth * HEAVY_HIT_FRACTION
            impacts.strike(
                actorId = hit.enemyId,
                from = player.position,
                to = hit.enemy.position,
                force = hit.result.amount.toFloat() * if (heavy) 1f else LIGHT_HIT_DAMPING,
            )
        }

        val healed = outcome.hits.sumOf { it.result.healedAttacker }
        if (healed > 0) {
            player = player.healed(healed)
            feedbackLog.add(
                kind = FeedbackKind.HEAL,
                text = "+$healed",
                origin = player.position,
                color = FEEDBACK_HEAL,
            )
        }

        val slain = enemies.filterNot { it.isAlive }
        if (slain.isNotEmpty()) {
            enemies = enemies.filter { it.isAlive }
            slain.forEach { enemy ->
                hitFlashes.forget(enemy.instanceId)
                impacts.forget(enemy.instanceId)
                dropLootFor(enemy)
                dropInsertFor(enemy)
            }
            awardExperience(slain.sumOf { it.experience })
        }

        return AttackReport.Landed(
            hits = outcome.hits,
            slain = slain,
            skill = skill,
        )
    }

    /**
     * Rolls a drop for a slain monster. Rank feeds the rarity roll, so an elite
     * is worth walking over to rather than just being tougher.
     */
    private fun dropLootFor(enemy: EnemyInstance): GroundLoot? {
        val definition = content.enemies.firstOrNull { it.id == enemy.definitionId }
        val chance = BASE_DROP_CHANCE + (definition?.bonusDropChance ?: 0f) + enemy.rank.extraAffixChance
        if (random.nextFloat() > chance) return null

        val depth = (config.seaLevel - enemy.blockPos.z).coerceAtLeast(0)
        val item = lootRoller.roll(
            itemLevel = Progression.itemLevelFor(player.level, depth),
            random = random,
            rarityBonus = enemy.rank.extraAffixChance,
        ) ?: return null

        val loot = GroundLoot(item, enemy.position)
        groundLoot = groundLoot + loot
        return loot
    }

    /**
     * Rolls an insert for a slain monster, independently of its gear drop. A
     * fight that yields no weapon can still yield something to put in one, so
     * the socket economy does not stall behind the rarity table.
     */
    private fun dropInsertFor(enemy: EnemyInstance) {
        if (content.inserts.isEmpty()) return
        val chance = INSERT_DROP_CHANCE + enemy.rank.extraAffixChance
        if (random.nextFloat() > chance) return

        val depth = (config.seaLevel - enemy.blockPos.z).coerceAtLeast(0)
        val insert = lootRoller.rollInsert(
            itemLevel = Progression.itemLevelFor(player.level, depth),
            random = random,
        ) ?: return
        groundInserts = groundInserts + GroundInsert(insert.id, enemy.position)
    }

    private fun awardExperience(amount: Int) {
        if (amount <= 0) return
        val result = Progression.apply(player.level, player.experience, amount)
        player = player.copy(level = result.level, experience = result.experience)
        if (result.leveledUp) {
            // A level restores the character, which is what makes pushing one
            // more fight at low health a real decision rather than a mistake.
            player = player.copy(
                health = player.maxHealthWithGear,
                resource = player.maxResource,
            )
            feedbackLog.add(
                kind = FeedbackKind.LEVEL_UP,
                text = "LEVEL ${result.level}",
                origin = player.position,
                color = FEEDBACK_LEVEL,
                emphasis = 1.9f,
                lifetime = 1.8f,
            )
        }
    }

    /** Picks up anything the player is standing on. */
    private fun collectLoot(): List<CombatEvent> {
        if (groundLoot.isEmpty()) return emptyList()

        val (reached, remaining) = groundLoot.partition {
            it.position.horizontalDistanceTo(player.position) <= PICKUP_RADIUS
        }
        if (reached.isEmpty()) return emptyList()

        groundLoot = remaining
        return reached.map { loot ->
            // Upgrades equip themselves. Making the player open a bag to feel a
            // drop is the fastest way to make loot stop feeling like a reward.
            val autoEquipped = player.isUpgrade(loot.item)
            player = if (autoEquipped) player.equipping(loot.item) else player.collecting(loot.item)
            feedbackLog.add(
                kind = FeedbackKind.LOOT,
                text = loot.item.name,
                origin = player.position,
                color = content.rarityColor(loot.item.rarity),
                emphasis = if (autoEquipped) 1.3f else 1f,
                lifetime = 1.4f,
            )
            CombatEvent.LootTaken(loot.item, autoEquipped)
        }
    }

    /** Picks up inserts the player is standing on, into the pouch. */
    private fun collectInserts(): List<CombatEvent> {
        if (groundInserts.isEmpty()) return emptyList()

        val (reached, remaining) = groundInserts.partition {
            it.position.horizontalDistanceTo(player.position) <= PICKUP_RADIUS
        }
        if (reached.isEmpty()) return emptyList()

        groundInserts = remaining
        return reached.mapNotNull { ground ->
            val definition = content.insert(ground.insertId) ?: return@mapNotNull null
            player = player.withInsert(definition.id)
            feedbackLog.add(
                kind = FeedbackKind.LOOT,
                text = definition.name,
                origin = player.position,
                color = definition.color,
                emphasis = 1.1f,
                lifetime = 1.3f,
            )
            CombatEvent.InsertTaken(definition)
        }
    }

    /**
     * Places a monster deliberately, for a scripted encounter or a shrine that
     * wakes something up. The director fills the world on its own; this is for
     * when the world should contain something specific.
     */
    fun spawn(
        definition: com.stratum.core.domain.actor.EnemyDefinition,
        position: WorldPoint,
    ): EnemyInstance {
        val enemy = director.instantiate(definition, position, player.level, random)
        enemies = enemies + enemy
        return enemy
    }

    /** Puts an item on the ground, for a chest or a quest reward. */
    fun dropLoot(item: ItemInstance, position: WorldPoint) {
        groundLoot = groundLoot + GroundLoot(item, position)
    }

    /** Puts an insert on the ground. */
    fun dropInsert(insertId: String, position: WorldPoint) {
        groundInserts = groundInserts + GroundInsert(insertId, position)
    }

    /**
     * Gets the player back on their feet after dying, in the same world.
     *
     * Death costs progress toward the current level, not the level itself, and
     * never the gear: losing a weapon you spent an hour socketing to a mistimed
     * roll is how people stop playing. What it does cost is the walk back — you
     * return to the spawn point, and whatever killed you is still out there.
     */
    fun revive(): ReviveResult {
        if (player.isAlive) return ReviveResult.StillStanding

        val lost = (player.experience * EXPERIENCE_LOST_ON_DEATH).roundToInt()
        player = player.copy(
            position = findSpawn(),
            experience = (player.experience - lost).coerceAtLeast(0),
            attackCooldown = 0f,
            cooldowns = com.stratum.core.domain.actor.SkillCooldowns(),
        )
        player = player.copy(
            health = player.maxHealthWith(::insertOrNull),
            resource = player.maxResource,
        )

        // The run starts clean: no leftover roll, no stale numbers floating over
        // a corpse that is no longer there.
        motion.reset()
        miningTarget = null
        miningProgress = 0f
        feedbackLog.clear()
        hitFlashes.clear()
        impacts.clear()
        attackHolds.clear()
        castHolds.clear()
        playbacks.clear()

        // Monsters that had cornered the player do not get to greet them at the
        // spawn point; the director refills the world soon enough.
        enemies = enemies.filter {
            it.position.horizontalDistanceTo(player.position) > REVIVE_CLEAR_RADIUS
        }
        events = emptyList()
        return ReviveResult.Revived(experienceLost = lost)
    }

    // ---- the satchel -----------------------------------------------------

    /**
     * Equips something from the bag. What was held goes back into the bag
     * rather than being destroyed, so a swap is always reversible and a player
     * can carry a mining weapon and a fighting one.
     */
    fun equip(instanceId: String): EquipResult {
        val item = player.bag.firstOrNull { it.instanceId == instanceId }
            ?: return EquipResult.NotInBag
        val previous = player.equippedWeapon
        player = player.equipping(item)
        // Inserts can carry health, so the ceiling moves on a swap; clamp rather
        // than leaving the player reading more health than they have.
        player = player.copy(health = player.health.coerceAtMost(player.maxHealthWith(::insertOrNull)))
        return EquipResult.Equipped(item, previous)
    }

    /**
     * Drops an item out of the bag onto the ground at the player's feet.
     *
     * It lands rather than vanishing: the pickup radius means the player can
     * change their mind, and a bag with no way out fills up and stops being a
     * bag at all.
     */
    fun discard(instanceId: String): EquipResult {
        val item = player.bag.firstOrNull { it.instanceId == instanceId }
            ?: return EquipResult.NotInBag
        player = player.copy(bag = player.bag - item)
        // Dropped a step away, or the player picks it straight back up.
        groundLoot = groundLoot + GroundLoot(item, player.position.translated(DISCARD_STEP, 0f, 0f))
        return EquipResult.Discarded(item)
    }

    // ---- the anvil -------------------------------------------------------

    /** Resolves an insert id against the loaded packs. */
    fun insertOrNull(insertId: String): InsertDefinition? = content.insert(insertId)

    /**
     * The player's stats with their weapon's inserts counted in. Every combat
     * path reads this rather than [PlayerState.combatStats], so a rune is never
     * visible in the tooltip but missing from the swing.
     */
    val playerStats: com.stratum.core.domain.combat.CombatStats
        get() = player.combatStatsWith(::insertOrNull)

    /** Inserts the player is carrying loose, resolved and sorted for display. */
    val heldInserts: List<HeldInsert>
        get() = player.insertBag.entries
            .mapNotNull { (id, count) ->
                content.insert(id)?.let { HeldInsert(it, count) }
            }
            .sortedWith(compareByDescending<HeldInsert> { it.definition.tier }
                .thenBy { it.definition.name })

    /**
     * Slots one of the player's inserts into an item they are holding.
     *
     * Spends the insert from the pouch and writes the item back wherever it
     * lives, so an equipped weapon changes under the player's hand and a bagged
     * one stays bagged.
     */
    fun slotInsert(instanceId: String, insertId: String): SocketResult {
        val item = player.itemById(instanceId) ?: return SocketResult.NoSuchItem
        if (content.insert(insertId) == null) return SocketResult.NoSuchInsert
        val spent = player.consumingInsert(insertId) ?: return SocketResult.NoneHeld
        val sockets = item.sockets.slotting(insertId) ?: return SocketResult.NoFreeSocket

        val updated = item.copy(sockets = sockets)
        player = spent.replacing(updated)
        // Health can move when a rune carries it; top up rather than leaving the
        // player at a fraction of a maximum they just earned.
        player = player.copy(health = player.health.coerceAtMost(player.maxHealthWith(::insertOrNull)))
        feedbackLog.add(
            kind = FeedbackKind.LOOT,
            text = content.insert(insertId)?.name ?: insertId,
            origin = player.position,
            color = content.insert(insertId)?.color ?: FEEDBACK_BUILT,
            emphasis = 1.2f,
            lifetime = 1.2f,
        )
        return SocketResult.Slotted(updated, insertId)
    }

    /**
     * Pulls an insert back out. The insert returns to the pouch intact, which is
     * the whole point of sockets over fusing: a decision you can take back.
     */
    fun unslotInsert(instanceId: String, socketIndex: Int): SocketResult {
        val item = player.itemById(instanceId) ?: return SocketResult.NoSuchItem
        val (sockets, insertId) = item.sockets.unslotting(socketIndex)
            ?: return SocketResult.EmptySocket

        val updated = item.copy(sockets = sockets)
        player = player.replacing(updated).withInsert(insertId)
        player = player.copy(health = player.health.coerceAtMost(player.maxHealthWith(::insertOrNull)))
        return SocketResult.Unslotted(updated, insertId)
    }

    /** Environmental damage: a fall, a trap, a hazard block. */
    fun hurtPlayer(amount: Int): Boolean {
        if (amount <= 0) return player.isAlive
        player = player.damaged(amount)
        return player.isAlive
    }

    /** Moves whatever is still being knocked back, and forgets the dead. */
    private fun advanceImpacts(deltaSeconds: Float) {
        val moved = impacts.advance(
            deltaSeconds,
            enemies.associate { it.instanceId to it.position },
        )
        if (moved.isEmpty()) return
        enemies = enemies.map { enemy ->
            moved[enemy.instanceId]?.let { enemy.copy(position = it) } ?: enemy
        }
    }

    private fun advanceAttackHolds(deltaSeconds: Float) {
        listOf(attackHolds, castHolds).forEach { holds ->
            val iterator = holds.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val remaining = entry.value - deltaSeconds
                if (remaining <= 0f) iterator.remove() else entry.setValue(remaining)
            }
        }
    }

    /**
     * Picks each actor's animation state from what it is actually doing, and
     * advances its clock.
     *
     * Derived every frame rather than stored, so state can never drift out of
     * step with the simulation that produced it.
     */
    private fun advanceAnimations(deltaSeconds: Float) {
        val deltaMs = (deltaSeconds * 1000f).toLong()

        val playerState = AnimationSelector.select(
            isDead = !player.isAlive,
            isRolling = isRolling,
            wasHitRecently = hitFlashes.intensity(PLAYER_ACTOR_ID) > 0f,
            isAttacking = attackHolds.containsKey(PLAYER_ACTOR_ID),
            isCasting = castHolds.containsKey(PLAYER_ACTOR_ID),
            isMoving = motion.input != WorldPoint.ZERO,
        )
        playbacks[PLAYER_ACTOR_ID] = animate(PLAYER_ACTOR_ID, playerState, deltaMs)

        enemies.forEach { enemy ->
            val state = AnimationSelector.select(
                isDead = !enemy.isAlive,
                wasHitRecently = hitFlashes.intensity(enemy.instanceId) > 0f,
                isAttacking = attackHolds.containsKey(enemy.instanceId),
                isMoving = enemy.state == com.stratum.core.domain.actor.EnemyState.CHASING ||
                    enemy.state == com.stratum.core.domain.actor.EnemyState.FLEEING,
            )
            playbacks[enemy.instanceId] = animate(enemy.instanceId, state, deltaMs)
        }

        // Forget actors that no longer exist, or the map grows for the whole run.
        val living = enemies.map { it.instanceId }.toSet() + PLAYER_ACTOR_ID
        playbacks.keys.retainAll(living)
    }

    private fun animate(actorId: String, state: AnimationState, deltaMs: Long): AnimationPlayback =
        (playbacks[actorId] ?: AnimationPlayback())
            .transitionTo(state)
            .advanced(deltaMs)

    // ---- building ---------------------------------------------------------

    private val roomScanner = RoomScanner(streamingWorld)

    /**
     * Blocks a pending build would place, for the ghost preview. Empty when not
     * building.
     */
    var buildPreview: List<BlockPos> = emptyList()
        private set

    var buildTool: BuildTool = BuildTool.SINGLE
        private set

    fun selectBuildTool(tool: BuildTool) {
        buildTool = tool
        buildPreview = emptyList()
    }

    /**
     * Previews what a drag from [from] to [to] would build. Nothing is placed;
     * this exists so the player sees the shape before spending the blocks.
     */
    fun previewBuild(from: BlockPos, to: BlockPos): BuildPreview {
        val blockId = player.selectedBlockId
            ?: return BuildPreview(emptyList(), 0, 0, false).also { buildPreview = emptyList() }

        val planned = BuildPlanner.plan(buildTool, from, to)
        // Only cells that are actually free: the preview should show what will
        // happen, not what was asked for.
        val placeable = planned.filter { pos ->
            pos.z in 0 until Chunk.HEIGHT &&
                streamingWorld.isLoaded(pos.chunkPos) &&
                streamingWorld.blockAt(pos).isAir &&
                pos != player.feet &&
                pos != player.feet.above()
        }
        buildPreview = placeable

        val held = player.countOf(blockId)
        return BuildPreview(
            positions = placeable,
            required = placeable.size,
            held = held,
            affordable = held >= placeable.size,
        )
    }

    fun cancelBuild() {
        buildPreview = emptyList()
    }

    /**
     * Commits the previewed build, spending one held block per cell.
     *
     * Partial builds are allowed: running out halfway through leaves what was
     * afforded rather than refusing the whole thing, which is what a player
     * expects from a drag that was slightly too ambitious.
     */
    fun commitBuild(): BuildResult {
        val blockId = player.selectedBlockId ?: return BuildResult.NothingSelected
        val planned = buildPreview
        if (planned.isEmpty()) return BuildResult.NothingToBuild

        val index = content.registry.indexOrNull(blockId) ?: return BuildResult.NothingSelected
        var placed = 0

        for (pos in planned) {
            val spent = player.consuming(blockId) ?: break
            if (!streamingWorld.setBlock(pos, index)) continue
            player = spent
            placed++
        }

        buildPreview = emptyList()
        if (placed == 0) return BuildResult.OutOfBlocks

        feedbackLog.add(
            kind = FeedbackKind.LOOT,
            text = "Built $placed",
            origin = player.position,
            color = FEEDBACK_BUILT,
        )
        return BuildResult.Built(placed, planned.size - placed)
    }

    /**
     * The room the player is standing in, if any. Recomputed on demand rather
     * than cached: walls change constantly while building, and a stale answer
     * would be worse than none.
     */
    fun shelter(): RoomScan = roomScanner.scan(player.blockPos)

    /** Skills the class has, resolved against the loaded packs. */
    val skills: List<SkillDefinition> get() = player.skillIds.mapNotNull(content::skill)

    /**
     * The biome under the player's feet. Asked of the generator rather than
     * stored on the chunk, because it is the same deterministic function of
     * position that produced the terrain in the first place.
     */
    val currentBiome: BiomeDefinition
        get() = biomeSource?.biomeAt(player.blockPos.x, player.blockPos.y)
            ?: content.biomes.firstOrNull()
            ?: UNCHARTED

    /** Immutable view for the UI layer. */
    fun snapshot(): SessionSnapshot = SessionSnapshot(
        player = player,
        focus = streamingWorld.focus,
        biome = currentBiome,
        miningTarget = miningTarget,
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

    companion object {
        const val SPAWN_SEARCH_RADIUS = 12
        const val PICKUP_RADIUS = 1.6f
        const val BASE_DROP_CHANCE = 0.35f
        /** Rolled separately from gear, so a socket always has something to fill it. */
        const val INSERT_DROP_CHANCE = 0.22f
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
        const val ATTACK_ANIMATION_HOLD = 0.3f
        private const val FEEDBACK_HURT = 0xFFD2544BL
        private const val FEEDBACK_DODGE = 0xFF7FD4E0L
        private const val FEEDBACK_BLOCKED = 0xFF9A96A8L
        private const val FEEDBACK_HEAL = 0xFF7BC67EL
        private const val FEEDBACK_LEVEL = 0xFFFFC107L
        private const val FEEDBACK_BUILT = 0xFF8FB8DEL
        private val SPAWN_CHUNK = com.stratum.core.domain.world.ChunkPos(0, 0)

        /** Shown when a generator names no regions and the packs define none. */
        private val UNCHARTED = BiomeDefinition(
            id = "stratum:uncharted",
            name = "Uncharted",
            surfaceBlockId = com.stratum.core.domain.world.BlockType.BEDROCK.id,
            subsurfaceBlockId = com.stratum.core.domain.world.BlockType.BEDROCK.id,
            bedrockFillerBlockId = com.stratum.core.domain.world.BlockType.BEDROCK.id,
        )
    }
}

data class MoveOutcome(val player: PlayerState, val moved: Boolean, val blocked: Boolean)

sealed interface DodgeResult {
    data object Rolling : DodgeResult
    data object OnCooldown : DodgeResult
    data object AlreadyRolling : DodgeResult
    data object Rejected : DodgeResult
}

data class SessionSnapshot(
    val player: PlayerState,
    val focus: com.stratum.core.domain.world.ChunkPos,
    val biome: BiomeDefinition,
    val miningTarget: BlockPos?,
    val miningFraction: Float,
    val isRolling: Boolean = false,
    val isInvulnerable: Boolean = false,
    val rollCooldownFraction: Float = 0f,
    val feedback: List<FeedbackMark> = emptyList(),
    val playerFlash: Float = 0f,
    val playerAnimation: AnimationPlayback = AnimationPlayback(),
    val buildPreview: List<BlockPos> = emptyList(),
    val buildTool: BuildTool = BuildTool.SINGLE,
    /** Changes when any loaded chunk changes, so the renderer knows to redraw. */
    val worldRevision: Int,
    val enemies: List<EnemyInstance> = emptyList(),
    val groundLoot: List<GroundLoot> = emptyList(),
    val groundInserts: List<GroundInsert> = emptyList(),
    val heldInserts: List<HeldInsert> = emptyList(),
    val skills: List<SkillDefinition> = emptyList(),
)

/** What a pending build would cost and cover. */
data class BuildPreview(
    val positions: List<BlockPos>,
    val required: Int,
    val held: Int,
    val affordable: Boolean,
) {
    val isEmpty: Boolean get() = positions.isEmpty()
}

sealed interface BuildResult {
    /** [short] is how many cells were skipped for want of blocks. */
    data class Built(val placed: Int, val short: Int) : BuildResult

    data object NothingSelected : BuildResult
    data object NothingToBuild : BuildResult
    data object OutOfBlocks : BuildResult
}

/** An item lying in the world. */
data class GroundLoot(val item: ItemInstance, val position: WorldPoint)

/** An insert lying in the world, identified rather than instanced: two of the
 * same rune are the same rune. */
data class GroundInsert(val insertId: String, val position: WorldPoint)

/** An insert in the pouch, with how many of it the player holds. */
data class HeldInsert(val definition: InsertDefinition, val count: Int)

/** What getting back up did. */
sealed interface ReviveResult {
    data class Revived(val experienceLost: Int) : ReviveResult

    /** Asked to revive someone who never died. */
    data object StillStanding : ReviveResult
}

/** What changing gear did. */
sealed interface EquipResult {
    data class Equipped(val item: ItemInstance, val replaced: ItemInstance?) : EquipResult
    data class Discarded(val item: ItemInstance) : EquipResult
    data object NotInBag : EquipResult
}

/** What a trip to the anvil did. */
sealed interface SocketResult {
    data class Slotted(val item: ItemInstance, val insertId: String) : SocketResult
    data class Unslotted(val item: ItemInstance, val insertId: String) : SocketResult

    data object NoSuchItem : SocketResult
    data object NoSuchInsert : SocketResult
    data object NoneHeld : SocketResult
    data object NoFreeSocket : SocketResult
    data object EmptySocket : SocketResult
}

/** What a swing did. */
sealed interface AttackReport {
    data class Landed(
        val hits: List<EnemyHit>,
        val slain: List<EnemyInstance>,
        val skill: SkillDefinition? = null,
    ) : AttackReport {
        val totalDamage: Int get() = hits.sumOf { it.result.amount }
    }

    data object Missed : AttackReport
    data object NotReady : AttackReport
    data object OnCooldown : AttackReport
    data object NotEnoughResource : AttackReport
    data object UnknownSkill : AttackReport
}

/** Something worth showing the player. Produced per tick and not retained. */
sealed interface CombatEvent {
    data class PlayerHurt(val amount: Int, val results: List<DamageResult>) : CombatEvent

    /** An attack that would have landed but did not, because of a roll. */
    data class PlayerDodged(val amountAvoided: Int) : CombatEvent
    data class LootTaken(val item: ItemInstance, val equipped: Boolean) : CombatEvent
    data class InsertTaken(val insert: InsertDefinition) : CombatEvent
    data object PlayerDied : CombatEvent
}
