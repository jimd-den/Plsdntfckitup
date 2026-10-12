package com.stratum.engine.world

import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.PendingCast
import com.stratum.core.domain.actor.SkillCost
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.SkillTags
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.DamageDealt
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.combat.DamageSource
import com.stratum.core.domain.combat.DamageSourceKind
import com.stratum.core.domain.combat.HitAttacker
import com.stratum.core.domain.combat.HitDefender
import com.stratum.core.domain.combat.HitResolver
import com.stratum.core.domain.combat.HitRolls
import com.stratum.core.domain.combat.KeyedTrigger
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.difficulty.BlowCeiling
import com.stratum.core.domain.difficulty.MonsterLevel
import com.stratum.core.domain.settings.Challenge
import com.stratum.core.domain.status.StatusApplication
import com.stratum.core.domain.status.DotTick
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The fight: casting, hitting, statuses, projectiles, zones, triggers,
 * flasks and recovery, for the player and monsters alike.
 *
 * One cast path for everyone. A monster's fireball and the player's are the
 * same [cast] with a different side, so every rule -- evasion, conversion,
 * ailments, the trigger guards -- applies to both without being written
 * twice. The player's followers, summons and defenders fight on the
 * player's side through the same path, with the skills their definitions
 * give them, and monsters strike them as readily as the player. The session hands in a [Battlefield] and takes the results back;
 * nothing here reaches into the session.
 */
internal class CombatSystem(
    private val content: AssembledContent,
    private val rules: CombatRules,
    private val world: World,
    private val director: EnemyDirector,
    private val cues: SessionCues,
    private val flashes: HitFlashes,
    private val impacts: ImpactField,
    private val random: Random,
    private val profile: PlayerProfile,
    /** A skill as the player casts it, tuned by their build and supports. */
    private val playerSkill: (String) -> SkillDefinition?,
    /** Share of a monster's damage that reaches the player; see [com.stratum.core.domain.world.WorldRules.enemyDamage]. */
    private val incomingDamage: Float = 1f,
    /** How far attacks reshape the ground, and how many thrown blocks fly at once. */
    destruction: com.stratum.core.domain.settings.VoxelDestruction = com.stratum.core.domain.settings.VoxelDestruction.SCARS,
    maxDebris: Int = 0,
    /** How hard monsters press the player; null fights at full strength with no guard against stun-locks. */
    private val challenge: Challenge? = null,
    /** Lets stuns chain with no guard between them, for those who want the harsher fight. */
    private val stunLocks: Boolean = false,
    /** Friendly towns: no hostile blow, burst or voxel payload lands there, and nothing hostile casts from inside. */
    private val safe: SafeGround? = null,
    /** The world tier, which presses harder on every blow: see [com.stratum.core.domain.difficulty.TierLadder.pressure]. */
    private val tier: Int = 0,
) {
    private val book = content.statusBook
    val statuses = StatusSystem(book, rules)
    val projectiles = ProjectileSystem(world)
    val zones = ZoneSystem()
    val triggers = TriggerEngine(rules)
    /** What attacks do to the blocks, when the world can be changed. */
    private val terrain = (world as? com.stratum.core.domain.world.MutableWorld)?.let { w ->
        TerrainImpacts(w, destruction, if (destruction == com.stratum.core.domain.settings.VoxelDestruction.PHYSICS && maxDebris > 0) DebrisField(w, maxDebris) else null)
    }

    /** Blocks thrown loose and still flying, for the renderer. */
    fun debris(): List<DebrisPiece> = terrain?.debris?.pieces().orEmpty()

    /** Voxel payloads this fight has applied, for the quests that want things kept quiet. */
    val blasts: Int get() = terrain?.applied ?: 0
    val flasks = FlaskSystem(content.flasks)
    private val vitals = VitalsSystem(rules)
    private val abilities = MonsterAbilities(content, director::definition)

    private var pendingPlayer: Pair<SkillDefinition, PendingCast>? = null

    /** What reaches the player of a monster's damage. */
    private val damageScale = incomingDamage * (challenge?.damageTaken ?: 1f)
    /** Seconds before another stun can land on the player: the stun's own length, then [Challenge.stunGuard]. */
    private var stunGuard = 0f
    /** Monsters attacking the player now, and how long each holds its turn without attacking again. */
    private val engaged = HashMap<String, Float>()
    private val lastAttackers = HashMap<String, HitAttacker>()
    private val dotCarry = HashMap<String, Float>()
    private val basicSkills = HashMap<Pair<String, Int>, SkillDefinition>()
    private var lastLifeFraction = 1f
    private var dotTaken = 0

    /**
     * Told about every piece of damage the player's side deals, as it lands:
     * each hit and each tick of damage over time, with what dealt it. For a
     * damage meter or a log; combat does not care whether anyone listens.
     */
    var onDealt: ((DamageDealt) -> Unit)? = null

    // ---- the player acting ---------------------------------------------------

    fun playerStunned(player: PlayerState): Boolean =
        !profile.traits(player).has(Keystone.UNSHAKEABLE) && statuses.of(PLAYER).isStunned(book)

    /** How much slower the player moves, 0..1. */
    fun playerSlow(player: PlayerState): Float =
        if (profile.traits(player).has(Keystone.UNSHAKEABLE)) 0f else statuses.of(PLAYER).slow(book)

    /** A basic swing with the weapon's damage type. The caller has checked the weapon's recovery. */
    fun basicAttack(battle: Battlefield, damageTypeId: String): AttackReport {
        if (playerStunned(battle.player)) return AttackReport.Stunned
        triggers.beginAction()
        val skill = basicSkill(damageTypeId, battle.player.combatStats.attackRange)
        cast(battle, PLAYER, CombatSide.PLAYER, skill, battle.player.position, aimFor(battle, skill), null, 0)
        return if (battle.hits.isEmpty()) AttackReport.Missed else landed(battle, null)
    }

    /**
     * Casts one of the player's skills: pays for it -- in life, for a build
     * that says so -- starts its cooldown, and either winds it up or lets it go.
     */
    fun castPlayerSkill(battle: Battlefield, skill: SkillDefinition): AttackReport {
        val player = battle.player
        if (playerStunned(player)) return AttackReport.Stunned
        if (pendingPlayer != null) return AttackReport.NotReady
        if (!player.cooldowns.isReady(skill)) return AttackReport.OnCooldown
        val cost = SkillCost.of(skill, profile.traits(player).has(Keystone.LIFE_PAYS_COSTS))
        if (!cost.affordable(player.resource, player.health)) return AttackReport.NotEnoughResource
        // Paid whether or not anything is standing there, so a skill cannot be spammed to scout for free.
        battle.player = player.copy(
            resource = player.resource - cost.resource,
            health = player.health - cost.life,
            cooldowns = player.cooldowns.started(skill),
        )
        triggers.beginAction()
        val aim = aimFor(battle, skill)
        if (skill.castTime > 0f) {
            val landing = SkillTargeting.landingPoint(skill, player.position, aim, candidatesAgainst(battle, CombatSide.PLAYER))
            pendingPlayer = skill to PendingCast(skill.id, skill.castTime, skill.castTime, landing, aim.dx, aim.dy)
            return AttackReport.Cast(skill)
        }
        cast(battle, PLAYER, CombatSide.PLAYER, skill, player.position, aim, null, 0)
        return when {
            battle.hits.isNotEmpty() -> landed(battle, skill)
            skill.delivery in DEFERRED -> AttackReport.Cast(skill)
            else -> AttackReport.Missed
        }
    }

    fun drinkFlask(battle: Battlefield, slot: Int): FlaskResult {
        val player = battle.player
        if (!player.isAlive) return FlaskResult.NoSuchFlask
        val extras = profile.extras(player, statuses.of(PLAYER), null, null)
        val maxHealth = profile.maxHealth(player)
        val (result, dose) = flasks.drink(slot, maxHealth, profile.sheet(player, extras).multiplier(Stat.FLASK_EFFECT))
        if (result !is FlaskResult.Drunk || dose == null) return result
        if (dose.seconds <= 0f) {
            battle.player = player.copy(
                health = (player.health + dose.life.roundToInt()).coerceAtMost(maxOf(player.health, maxHealth)),
                resource = (player.resource + dose.resource.roundToInt()).coerceAtMost(maxOf(player.resource, profile.maxResource(player))),
            )
        } else {
            vitals.recoverOverTime(dose)
        }
        if (result.flask.cleanses) statuses.cleanse(PLAYER)
        result.flask.statusId?.let { applyStatus(battle, PLAYER, StatusApplication(it, sourceId = PLAYER)) }
        if (result.lifeRestored > 0) cues.healed(result.lifeRestored, player.position)
        return result
    }

    val flaskViews: List<FlaskView> get() = flasks.views

    // ---- time passing --------------------------------------------------------

    /**
     * One tick of the fight: statuses burn, winds-up land, monsters choose and
     * swing, projectiles fly, zones pulse, summons fade and the player
     * recovers. Returns what the player should be told.
     */
    fun advance(battle: Battlefield, deltaSeconds: Float): List<CombatEvent> {
        triggers.beginAction()
        triggers.advance(deltaSeconds)
        dotTaken = 0
        stunGuard = (stunGuard - deltaSeconds).coerceAtLeast(0f)
        if (engaged.isNotEmpty()) engaged.entries.removeAll { e -> e.setValue(e.value - deltaSeconds); e.value <= 0f || battle.enemy(e.key)?.isAlive != true }
        burn(battle, deltaSeconds)
        resolvePlayerWindUp(battle, deltaSeconds)
        monstersCast(battle, deltaSeconds)
        monstersSwing(battle)
        projectiles.advance(deltaSeconds, { side -> candidatesAgainst(battle, side) }).forEach { impact ->
            val p = impact.projectile
            val skill = skillFor(p.side, p.skillId) ?: return@forEach
            hit(battle, p.side, p.casterId, skill, impact.targetId, p.depth, p.position)
        }
        // A shot that struck only the ground or a wall still lands: its voxel payload, and whatever it launches there.
        projectiles.drainLandings().forEach { p ->
            val skill = skillFor(p.side, p.skillId) ?: return@forEach
            landed(battle, p.casterId, p.side, skill, p.position, Aim(p.dx, p.dy), p.depth)
        }
        terrain?.advance(deltaSeconds)
        zones.advance(deltaSeconds) { side -> candidatesAgainst(battle, side) }.forEach { pulse ->
            val skill = skillFor(pulse.zone.side, pulse.zone.skillId) ?: return@forEach
            pulse.targetIds.forEach { hit(battle, pulse.zone.side, pulse.zone.casterId, skill, it, pulse.zone.depth, pulse.zone.position) }
        }
        fadeSummons(battle, deltaSeconds)
        recover(battle, deltaSeconds)
        statuses.retain(battle.enemies.mapTo(HashSet()) { it.instanceId } + PLAYER)
        return finish(battle)
    }

    /**
     * Holds back what the crowd just moved: stunned and winding-up monsters
     * stay put, slowed ones cover less ground and recover their swings slower.
     */
    fun constrain(before: List<EnemyInstance>, after: List<EnemyInstance>, deltaSeconds: Float): List<EnemyInstance> {
        val previous = before.associateBy { it.instanceId }
        return after.map { enemy ->
            val prior = previous[enemy.instanceId] ?: return@map enemy
            val set = statuses.of(enemy.instanceId)
            when {
                set.isStunned(book) -> enemy.copy(position = prior.position, attackCooldown = prior.attackCooldown, casting = null)
                enemy.casting != null -> enemy.copy(position = prior.position)
                else -> {
                    val slow = set.slow(book)
                    if (slow <= 0f) enemy
                    else enemy.copy(
                        position = WorldPoint(
                            prior.position.x + (enemy.position.x - prior.position.x) * (1f - slow),
                            prior.position.y + (enemy.position.y - prior.position.y) * (1f - slow),
                            enemy.position.z,
                        ),
                        attackCooldown = (prior.attackCooldown - deltaSeconds * (1f - slow)).coerceAtLeast(0f),
                    )
                }
            }
        }
    }

    fun telegraphs(enemies: List<EnemyInstance>, player: PlayerState): List<Telegraph> =
        enemies.mapNotNull { enemy ->
            val casting = enemy.casting ?: return@mapNotNull null
            val skill = content.skill(casting.skillId) ?: return@mapNotNull null
            Telegraph.of(enemy.instanceId, skill, casting, enemy.position, hostile = enemy.factionId != Factions.PLAYER)
        } + listOfNotNull(pendingPlayer?.let { (skill, cast) -> Telegraph.of(PLAYER, skill, cast, player.position, hostile = false) })

    fun forget(actorId: String) {
        statuses.forget(actorId)
        dotCarry.remove(actorId)
        lastAttackers.remove(actorId)
    }

    /** A fresh start after a death: nothing burning, nothing in flight, a full belt. */
    fun clear() {
        statuses.clear()
        projectiles.clear()
        zones.clear()
        terrain?.clear()
        triggers.clear()
        vitals.clear()
        flasks.refill()
        pendingPlayer = null
        lastAttackers.clear()
        dotCarry.clear()
        lastLifeFraction = 1f
    }

    // ---- casting ---------------------------------------------------------------

    /** Casts [skill] from [origin]: effects on the caster and allies, then the delivery. */
    private fun cast(battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, origin: WorldPoint, aim: Aim, point: WorldPoint?, depth: Int) {
        val candidates = candidatesAgainst(battle, side)
        val landing = point ?: SkillTargeting.landingPoint(skill, origin, aim, candidates)
        supportive(battle, casterId, side, skill, if (skill.delivery == SkillDelivery.SELF) origin else landing, depth)
        when (skill.delivery) {
            SkillDelivery.PROJECTILE -> launch(casterId, side, skill, origin, aim, depth)
            SkillDelivery.ZONE -> zones.place(
                Zone(0, skill.id, casterId, side, landing, SkillTargeting.areaRadius(skill), skill.zone.durationSeconds, skill.zone.pulseSeconds,
                    isTrap = skill.zone.isTrap, depth = depth, color = skill.color, look = skill.look),
            )
            SkillDelivery.SUMMON -> summon(battle, casterId, side, skill, origin)
            SkillDelivery.SELF -> Unit
            SkillDelivery.DASH -> {
                val end = dash(battle, casterId, origin, aim, skill.reach)
                val travelled = end.horizontalDistanceTo(origin)
                candidates.filter { SkillTargeting.inLane(origin, aim, it.position, travelled + 1f, skill.area.halfWidth) }
                    .forEach { hit(battle, side, casterId, skill, it.id, depth, origin) }
            }
            else -> SkillTargeting.targets(skill, origin, aim, landing, candidates).forEach { hit(battle, side, casterId, skill, it, depth, origin) }
        }
        // An area, beam or zone changes the ground where it lands, once, whatever it hit.
        if (skill.delivery != SkillDelivery.PROJECTILE && skill.delivery != SkillDelivery.SELF) {
            // A nova is centred on its caster; everything else lands where it was aimed.
            val ground = if (skill.delivery == SkillDelivery.NOVA) origin else landing
            if (side != CombatSide.MONSTERS || !sheltered(ground)) skill.resolvedEffects.filterIsInstance<SkillEffect.Terrain>().forEach { terrain?.apply(it, ground, aim) }
        }
        if (casterId == PLAYER) fire(battle, TriggerEvent.ON_SKILL_USE, depth, skill.allTags, null)
    }

    /** A projectile that came down on the ground or a wall: its voxel payload, and the attacks it launches where it fell. */
    private fun landed(battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, at: WorldPoint, aim: Aim, depth: Int) {
        val ground = WorldPoint(at.x, at.y, (at.z - ProjectileSystem.BODY_CENTRE).coerceAtLeast(0f))
        skill.resolvedEffects.forEach { effect ->
            when (effect) {
                is SkillEffect.Terrain -> if (side != CombatSide.MONSTERS || !sheltered(ground)) terrain?.apply(effect, ground, aim)
                is SkillEffect.CastSkill -> if (effect.target == EffectTarget.TARGET) castFollowUp(battle, casterId, side, effect.skillId, ground, at, depth)
                else -> Unit
            }
        }
    }

    private fun launch(casterId: String, side: CombatSide, skill: SkillDefinition, origin: WorldPoint, aim: Aim, depth: Int) {
        val spec = skill.projectile
        ProjectileSystem.fan(aim, spec.count, spec.spreadDegrees).forEach { direction ->
            projectiles.launch(
                Projectile(
                    id = 0, skillId = skill.id, casterId = casterId, side = side,
                    position = WorldPoint(origin.x, origin.y, origin.z + ProjectileSystem.BODY_CENTRE),
                    dx = direction.dx, dy = direction.dy, speed = spec.speed, range = skill.range.toFloat(), radius = spec.radius,
                    pierce = spec.pierce, chain = spec.chain, fork = spec.fork, collidesWithBlocks = spec.collidesWithBlocks,
                    depth = depth, color = skill.color, look = skill.look,
                ),
            )
        }
    }

    /** Carries the caster along [aim] until a wall or [length]; returns where it stopped. */
    private fun dash(battle: Battlefield, casterId: String, origin: WorldPoint, aim: Aim, length: Float): WorldPoint {
        var at = origin
        var travelled = 0f
        while (travelled < length) {
            val step = minOf(DASH_STEP, length - travelled)
            val next = WorldPoint(at.x + aim.dx * step, at.y + aim.dy * step, at.z)
            val feet = BlockPos(floor(next.x).toInt(), floor(next.y).toInt(), floor(next.z).toInt())
            if (world.isSolid(feet) || world.isSolid(feet.above())) break
            at = next
            travelled += step
        }
        if (casterId == PLAYER) battle.player = battle.player.copy(position = at)
        else battle.enemy(casterId)?.let { battle.put(it.copy(position = at)) }
        return at
    }

    private fun summon(battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, origin: WorldPoint) {
        val spec = skill.summon ?: return
        val body = director.definition(spec.enemyId) ?: return
        val caster = battle.enemy(casterId)
        repeat(spec.count) { i ->
            val angle = random.nextFloat() * TWO_PI + i
            val spot = director.grounded(origin.translated(cos(angle) * SUMMON_RING, sin(angle) * SUMMON_RING, 0f)) ?: origin
            val summoned = director.instantiate(body, spot, battle.player.level, random).copy(
                summonerId = casterId,
                expiresIn = spec.durationSeconds.takeIf { it > 0f },
                factionId = if (side == CombatSide.PLAYER) Factions.PLAYER else caster?.factionId,
                squadId = if (side == CombatSide.PLAYER) SUMMON_SQUAD + casterId else caster?.squadId,
                home = null,
            )
            battle.put(summoned)
            if (side == CombatSide.MONSTERS) battle.provoked += summoned.instanceId
        }
        // Over the limit, the oldest answer to the call goes first.
        val mine = battle.enemies.filter { it.summonerId == casterId && it.definitionId == spec.enemyId && it.isAlive }
        mine.take((mine.size - spec.limit).coerceAtLeast(0)).forEach {
            battle.remove(it.instanceId)
            forget(it.instanceId)
        }
    }

    /** The caster's own and its allies' share of a skill: heals, buffs, restored resource. */
    private fun supportive(battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, center: WorldPoint, depth: Int) {
        skill.resolvedEffects.forEach { effect ->
            val target = if (skill.delivery == SkillDelivery.SELF && effect.target == EffectTarget.TARGET) EffectTarget.SELF else effect.target
            when (target) {
                EffectTarget.TARGET -> Unit
                EffectTarget.SELF -> benefit(battle, casterId, side, effect, casterId, depth)
                EffectTarget.ALLIES -> alliesOf(battle, casterId, side)
                    .filter { (battle.positionOf(it) ?: return@filter false).horizontalDistanceTo(center) <= skill.reach + SkillTargeting.REACH_FORGIVENESS }
                    .forEach { benefit(battle, casterId, side, effect, it, depth) }
            }
        }
    }

    private fun benefit(battle: Battlefield, casterId: String, side: CombatSide, effect: SkillEffect, recipientId: String, depth: Int) {
        when (effect) {
            is SkillEffect.Heal -> heal(battle, recipientId, effect.amount, effect.maxShare)
            is SkillEffect.ApplyStatus -> if (roll(effect.chance)) {
                applyStatus(battle, recipientId, StatusApplication(effect.statusId, stacks = effect.stacks, sourceId = casterId))
            }
            is SkillEffect.RestoreResource -> if (recipientId == PLAYER) {
                battle.player = battle.player.copy(resource = (battle.player.resource + effect.amount).coerceAtMost(profile.maxResource(battle.player)))
            }
            is SkillEffect.CastSkill -> castFollowUp(battle, casterId, side, effect.skillId, battle.positionOf(recipientId) ?: return, null, depth)
            else -> Unit
        }
    }

    private fun heal(battle: Battlefield, recipientId: String, amount: Int, share: Float) {
        if (recipientId == PLAYER) {
            val max = profile.maxHealth(battle.player)
            val healed = (amount + share * max).roundToInt()
            if (healed <= 0 || !battle.player.isAlive) return
            battle.player = battle.player.copy(health = (battle.player.health + healed).coerceAtMost(maxOf(battle.player.health, max)))
            cues.healed(healed, battle.player.position)
        } else {
            val enemy = battle.enemy(recipientId)?.takeIf { it.isAlive } ?: return
            val healed = (amount + share * enemy.stats.maxHealth).roundToInt()
            if (healed > 0) battle.put(enemy.copy(health = (enemy.health + healed).coerceAtMost(enemy.stats.maxHealth)))
        }
    }

    /** A cast caused by another: bounded by the same depth and budget as a trigger. */
    private fun castFollowUp(battle: Battlefield, casterId: String, side: CombatSide, skillId: String, at: WorldPoint, from: WorldPoint?, depth: Int) {
        if (!triggers.allowChainedCast(depth)) return
        val skill = skillFor(side, skillId) ?: return
        val aim = from?.let { Aim.toward(it, at) } ?: Aim(0f, 1f)
        cast(battle, casterId, side, skill.copy(tags = skill.tags + SkillTags.TRIGGERED), at, aim, at, depth + 1)
    }

    // ---- hitting -----------------------------------------------------------------

    private fun hit(battle: Battlefield, side: CombatSide, casterId: String, skill: SkillDefinition, targetId: String, depth: Int, from: WorldPoint) {
        when {
            targetId == PLAYER -> hitPlayer(battle, casterId, skill, depth, from)
            side == CombatSide.MONSTERS -> hitAlly(battle, casterId, skill, targetId, depth, from)
            else -> hitEnemy(battle, casterId, side, skill, targetId, depth, from)
        }
    }

    /** The player's side striking a monster. */
    private fun hitEnemy(battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, enemyId: String, depth: Int, from: WorldPoint) {
        if (side != CombatSide.PLAYER) return
        val enemy = battle.enemy(enemyId)?.takeIf { it.isAlive } ?: return
        val attacker = if (casterId == PLAYER) profile.attacker(battle.player, statuses.of(PLAYER), statuses.of(enemyId), skill)
        else monsterAttacker(battle, casterId, skill) ?: return
        val result = resolve(attacker, monsterDefender(enemy), skill)
        val struck = enemy.damaged(result.amount)
        battle.put(struck)
        val enemyHit = EnemyHit(enemyId, struck, result)
        battle.hits += enemyHit
        showHit(enemyHit, from)
        onDealt?.invoke(dealt(battle, casterId, skill, enemyId, result))
        if (result.wasEvaded || (result.wasBlocked && result.amount == 0)) return
        if (casterId == PLAYER) leech(battle, result.healedAttacker)
        result.inflicted.forEach { applyStatus(battle, enemyId, it) }
        onTarget(battle, casterId, side, skill, enemyId, struck.position, from, attacker, depth)
        if (casterId == PLAYER) {
            if (result.landed) fire(battle, TriggerEvent.ON_HIT, depth, skill.allTags, enemyId)
            if (result.wasCritical) fire(battle, TriggerEvent.ON_CRIT, depth, skill.allTags, enemyId)
        }
        if (!struck.isAlive) killed(battle, struck, depth, skill.allTags)
    }

    /** A monster's skill or swing reaching the player. */
    private fun hitPlayer(battle: Battlefield, casterId: String, skill: SkillDefinition, depth: Int, from: WorldPoint) {
        if (!battle.player.isAlive) return
        if (sheltered(battle.player.position)) return
        val attacker = monsterAttacker(battle, casterId, skill) ?: return
        if (battle.playerInvulnerable) {
            // A swing during a roll still happened; saying it missed is what makes a well-timed roll legible.
            battle.dodged += (attacker.stats.attackPower * skill.powerMultiplier).roundToInt().coerceAtLeast(1)
            return
        }
        val result = resolve(attacker, profile.defender(battle.player, statuses.of(PLAYER)), skill).let { r ->
            if (damageScale == 1f || r.amount == 0) r
            // A gentler world softens every packet alike, never below one point for a hit that landed.
            else r.copy(amount = (r.amount * damageScale).roundToInt().coerceAtLeast(1), packets = r.packets.mapValues { (_, v) -> (v * damageScale).roundToInt() })
        }.let { r -> withinCeiling(battle, casterId, r) }
        battle.incoming += result
        when {
            result.wasEvaded -> {
                cues.evaded(battle.player.position)
                fire(battle, TriggerEvent.ON_EVADE, 0, emptySet(), casterId)
                return
            }
            result.wasBlocked && result.amount == 0 -> {
                cues.blocked(battle.player.position)
                fire(battle, TriggerEvent.ON_BLOCK, 0, emptySet(), casterId)
                return
            }
        }
        hurtPlayer(battle, result.amount)
        battle.enemy(casterId)?.takeIf { result.healedAttacker > 0 }?.let { heal(battle, it.instanceId, result.healedAttacker, 0f) }
        result.inflicted.forEach { applyStatus(battle, PLAYER, it) }
        onTarget(battle, casterId, CombatSide.MONSTERS, skill, PLAYER, battle.player.position, from, attacker, depth)
        if (result.landed) fire(battle, TriggerEvent.ON_HIT_TAKEN, 0, emptySet(), casterId)
        checkLowLife(battle)
    }

    /**
     * A monster's skill or swing reaching one of the player's side who is not
     * the player: a follower, a summon, a defender. Its numbers show like any
     * other hit, but it is nobody's kill: a follower that falls simply falls.
     */
    private fun hitAlly(battle: Battlefield, casterId: String, skill: SkillDefinition, allyId: String, depth: Int, from: WorldPoint) {
        val ally = battle.enemy(allyId)?.takeIf { it.isAlive } ?: return
        if (sheltered(ally.position)) return
        val attacker = monsterAttacker(battle, casterId, skill) ?: return
        val result = resolve(attacker, monsterDefender(ally), skill)
        val struck = ally.damaged(result.amount)
        battle.put(struck)
        showHit(EnemyHit(allyId, struck, result), from)
        if (result.wasEvaded || (result.wasBlocked && result.amount == 0)) return
        battle.enemy(casterId)?.takeIf { result.healedAttacker > 0 }?.let { heal(battle, it.instanceId, result.healedAttacker, 0f) }
        result.inflicted.forEach { applyStatus(battle, allyId, it) }
        onTarget(battle, casterId, CombatSide.MONSTERS, skill, allyId, struck.position, from, attacker, depth)
    }

    /**
     * Takes [amount] from the player. Under [Keystone.RESOURCE_SHIELDS_LIFE]
     * the resource pool soaks it first, so the pool becomes a second life bar
     * and spending it on skills is spending armour.
     */
    private fun hurtPlayer(battle: Battlefield, amount: Int) {
        val player = battle.player
        if (!profile.traits(player).has(Keystone.RESOURCE_SHIELDS_LIFE)) {
            battle.player = player.damaged(amount)
            return
        }
        val soaked = minOf(player.resource, amount)
        battle.player = player.copy(resource = player.resource - soaked).damaged(amount - soaked)
    }

    /** What a skill does to each thing it lands on, beyond its damage. */
    private fun onTarget(
        battle: Battlefield, casterId: String, side: CombatSide, skill: SkillDefinition, targetId: String,
        at: WorldPoint, from: WorldPoint, attacker: HitAttacker, depth: Int,
    ) {
        if (skill.delivery == SkillDelivery.SELF) return
        skill.resolvedEffects.filter { it.target == EffectTarget.TARGET }.forEach { effect ->
            when (effect) {
                is SkillEffect.ApplyStatus -> if (roll(effect.chance)) {
                    applyStatus(battle, targetId, StatusApplication(effect.statusId, durationScale = attacker.sheet.multiplier(Stat.DURATION), stacks = effect.stacks, sourceId = casterId))
                }
                is SkillEffect.Knockback -> if (targetId != PLAYER) impacts.strike(targetId, from, at, effect.force)
                is SkillEffect.CastSkill -> castFollowUp(battle, casterId, side, effect.skillId, at, from, depth)
                // A projectile's voxel payload goes off where it struck the body; areas change the ground once, in cast().
                is SkillEffect.Terrain -> if (skill.delivery == SkillDelivery.PROJECTILE && (side != CombatSide.MONSTERS || !sheltered(at))) terrain?.apply(effect, at, Aim.toward(from, at))
                is SkillEffect.Heal -> heal(battle, targetId, effect.amount, effect.maxShare)
                else -> Unit
            }
        }
    }

    private fun resolve(attacker: HitAttacker, defender: HitDefender, skill: SkillDefinition): DamageResult {
        val power = attacker.stats.attackPower * skill.powerMultiplier
        val damage = LinkedHashMap<String, Float>()
        skill.resolvedEffects.filterIsInstance<SkillEffect.Damage>().forEach { damage[it.damageTypeId] = (damage[it.damageTypeId] ?: 0f) + power * it.share }
        if (damage.isEmpty()) return DamageResult(0, skill.damageTypeId, wasCritical = false, wasBlocked = false)
        return HitResolver.resolve(attacker, defender, damage, HitRolls.from(random), rules, content::damageTypeOrNull, book)
    }

    fun applyStatus(battle: Battlefield, actorId: String, application: StatusApplication) {
        val definition = book[application.statusId] ?: return
        val holds = definition.behaviours.any { it is StatusBehaviour.Stun }
        val hinders = holds || definition.behaviours.any { it is StatusBehaviour.Slow }
        if (actorId == PLAYER && hinders && profile.traits(battle.player).has(Keystone.UNSHAKEABLE)) return
        var applied = application
        if (actorId == PLAYER && hinders && challenge != null && application.sourceId != PLAYER) {
            // No stun-locks: while the last stun and its guard last, another cannot land.
            if (holds && !stunLocks && stunGuard > 0f) return
            applied = application.copy(durationScale = application.durationScale * challenge.controlTime)
            if (holds && !stunLocks) stunGuard = definition.durationSeconds * applied.durationScale.coerceAtLeast(0f) + challenge.stunGuard
        }
        val fresh = !statuses.of(actorId).has(definition.id)
        statuses.apply(actorId, applied)
        val at = battle.positionOf(actorId) ?: return
        if (definition.behaviours.any { it is StatusBehaviour.Stun }) {
            if (actorId == PLAYER) pendingPlayer = null else battle.enemy(actorId)?.let { battle.put(it.copy(casting = null)) }
        }
        if (fresh) cues.status(definition.name, at, definition.color)
    }

    private fun leech(battle: Battlefield, amount: Int) {
        if (amount <= 0) return
        if (profile.traits(battle.player).has(Keystone.INSTANT_LEECH)) heal(battle, PLAYER, amount, 0f) else vitals.leech(amount)
    }

    private fun killed(battle: Battlefield, enemy: EnemyInstance, depth: Int, tags: Set<String>) {
        if (!battle.killed.add(enemy.instanceId)) return
        val sheet = profile.sheet(battle.player, emptyList())
        flasks.onKill(enemy.rank, sheet.multiplier(Stat.FLASK_CHARGES))
        fire(battle, TriggerEvent.ON_KILL, depth, tags, enemy.instanceId)
    }

    /** Fires the player's triggers for [event]; each fired one casts or applies what it names. */
    private fun fire(battle: Battlefield, event: TriggerEvent, depth: Int, tags: Set<String>, targetId: String?, only: List<KeyedTrigger>? = null) {
        val candidates = only ?: profile.triggers(battle.player).takeIf { list -> list.any { it.trigger.event == event } } ?: return
        triggers.fire(event, candidates, depth, tags, random).forEach { keyed ->
            val trigger = keyed.trigger
            trigger.applyStatusId?.let { status ->
                applyStatus(battle, if (trigger.statusOnSelf || targetId == null) PLAYER else targetId, StatusApplication(status, sourceId = PLAYER))
            }
            val skill = trigger.castSkillId?.let(playerSkill) ?: return@forEach
            val origin = battle.player.position
            val target = targetId?.let(battle::positionOf)
            val aim = target?.let { Aim.toward(origin, it, facing(battle.player)) } ?: facing(battle.player)
            cast(battle, PLAYER, CombatSide.PLAYER, skill.copy(tags = skill.tags + SkillTags.TRIGGERED), origin, aim, target, depth + 1)
        }
    }

    private fun checkLowLife(battle: Battlefield) {
        val max = profile.maxHealth(battle.player).coerceAtLeast(1)
        val now = battle.player.health.toFloat() / max
        val crossing = profile.triggers(battle.player).filter {
            it.trigger.event == TriggerEvent.ON_LOW_LIFE && lastLifeFraction > it.trigger.lowLifeThreshold && now <= it.trigger.lowLifeThreshold
        }
        lastLifeFraction = now
        if (crossing.isNotEmpty() && battle.player.isAlive) fire(battle, TriggerEvent.ON_LOW_LIFE, 0, emptySet(), null, crossing)
    }

    // ---- the tick's parts ---------------------------------------------------------

    private fun burn(battle: Battlefield, deltaSeconds: Float) {
        statuses.advance(deltaSeconds).forEach { (actorId, ticks) ->
            val defender = if (actorId == PLAYER) profile.defender(battle.player, statuses.of(PLAYER))
            else battle.enemy(actorId)?.takeIf { it.isAlive }?.let(::monsterDefender) ?: return@forEach
            val resolved = ticks.map { it to HitResolver.dot(it.amount, it.damageTypeId, defender, rules, book) }
            // Monsters' burns on the player are softened as their blows are.
            val raw = resolved.sumOf { (tick, amount) -> (if (actorId == PLAYER && tick.sourceId != PLAYER) amount * damageScale else amount).toDouble() }.toFloat()
            if (actorId != PLAYER) onDealt?.let { listener -> resolved.forEach { (tick, amount) -> ailmentDealt(battle, actorId, tick, amount)?.let(listener) } }
            val carried = (dotCarry[actorId] ?: 0f) + raw
            val whole = carried.toInt()
            dotCarry[actorId] = carried - whole
            if (whole <= 0) return@forEach
            if (actorId == PLAYER) {
                if (!battle.player.isAlive) return@forEach
                hurtPlayer(battle, whole)
                dotTaken += whole
                checkLowLife(battle)
            } else {
                val enemy = battle.enemy(actorId) ?: return@forEach
                val burnt = enemy.damaged(whole)
                battle.put(burnt)
                val source = ticks.maxByOrNull { it.amount }?.sourceId
                if (!burnt.isAlive && (source == PLAYER || battle.enemy(source.orEmpty())?.factionId == Factions.PLAYER)) killed(battle, burnt, 0, emptySet())
            }
        }
    }

    private fun resolvePlayerWindUp(battle: Battlefield, deltaSeconds: Float) {
        val (skill, pending) = pendingPlayer ?: return
        if (!battle.player.isAlive) {
            pendingPlayer = null
            return
        }
        val left = pending.remaining - deltaSeconds
        if (left > 0f) {
            pendingPlayer = skill to pending.copy(remaining = left)
            return
        }
        pendingPlayer = null
        cast(battle, PLAYER, CombatSide.PLAYER, skill, battle.player.position, Aim(pending.aimX, pending.aimY), pending.target, 0)
    }

    /**
     * Boss phases, wind-ups landing, and new casts chosen -- by hostile
     * monsters against the player's side, and by the player's followers
     * against whatever is hostile near them.
     */
    private fun monstersCast(battle: Battlefield, deltaSeconds: Float) {
        battle.enemies.map { it.instanceId }.forEach { id ->
            var enemy = battle.enemy(id)?.takeIf { it.isAlive } ?: return@forEach
            enemy = enemy.copy(skillCooldowns = enemy.skillCooldowns.advanced(deltaSeconds))
            battle.put(enemy)
            abilities.phaseDue(enemy)?.let { enemy = enterPhase(battle, enemy) }
            val side = sideOf(battle, enemy) ?: return@forEach
            if (statuses.of(id).isStunned(book)) return@forEach
            val casting = enemy.casting
            if (casting != null) {
                val left = casting.remaining - deltaSeconds
                if (left > 0f) return@forEach battle.put(enemy.copy(casting = casting.copy(remaining = left)))
                battle.put(enemy.copy(casting = null))
                val skill = content.skill(casting.skillId) ?: return@forEach
                cast(battle, id, side, skill, enemy.position, Aim(casting.aimX, casting.aimY), casting.target, 0)
                return@forEach
            }
            val foe = targetFor(battle, enemy, side)
            val target = foe?.position
            val allies = alliesOf(battle, id, side).mapNotNull(battle::enemy)
            val choice = abilities.choose(enemy, target, allies, statuses::of, random) ?: return@forEach
            val skill = choice.skill
            val atPlayer = side == CombatSide.MONSTERS && foe?.id == PLAYER && !skill.isBeneficial
            // Nothing hostile attacks from a safe town or into one.
            if (side == CombatSide.MONSTERS && !skill.isBeneficial && (sheltered(enemy.position) || target?.let(::sheltered) == true)) return@forEach
            if (atPlayer && !mayPress(id)) return@forEach
            val tempo = tempoOf(side)
            val aim = target?.let { Aim.toward(enemy.position, it) } ?: Aim.of(enemy.facingX, enemy.facingY)
            val started = enemy.copy(
                skillCooldowns = enemy.skillCooldowns.started(skill.copy(cooldownSeconds = choice.cooldownSeconds * tempo)),
                // Casting is its move for this beat: no basic swing on top of it.
                attackCooldown = maxOf(enemy.attackCooldown, skill.castTime + enemy.stats.secondsBetweenAttacks.coerceAtMost(MAX_SWING_GAP) * tempo),
            )
            if (atPlayer) press(id, started.attackCooldown)
            if (skill.castTime > 0f) {
                val point = if (skill.delivery == SkillDelivery.NOVA || skill.isBeneficial) enemy.position else target ?: enemy.position
                battle.put(started.copy(casting = PendingCast(skill.id, skill.castTime, skill.castTime, point, aim.dx, aim.dy)))
            } else {
                battle.put(started)
                cast(battle, id, side, skill, enemy.position, aim, if (skill.isBeneficial) enemy.position else target, 0)
            }
        }
    }

    /** A boss moves into its next phase: enraged, reinforced, announced. */
    private fun enterPhase(battle: Battlefield, enemy: EnemyInstance): EnemyInstance {
        val definition = director.definition(enemy.definitionId) ?: return enemy
        val phase = definition.phases.getOrNull(enemy.phase) ?: return enemy
        val enraged = if (phase.enrage.isEmpty()) enemy.stats else StatSheet(phase.enrage).applyTo(enemy.stats, content.damageTypes.map { it.id })
        val health = (enemy.healthFraction * enraged.maxHealth).roundToInt().coerceAtLeast(1)
        val next = enemy.copy(phase = enemy.phase + 1, stats = enraged, health = health, casting = null)
        battle.put(next)
        phase.adds.forEach { member ->
            val body = director.definition(member.enemyId) ?: return@forEach
            repeat(member.count) { i ->
                val angle = TWO_PI * i / member.count + random.nextFloat()
                val spot = director.grounded(enemy.position.translated(cos(angle) * ADD_RING, sin(angle) * ADD_RING, 0f)) ?: enemy.position
                val add = director.instantiate(body, spot, battle.player.level, random)
                    .copy(factionId = enemy.factionId, squadId = enemy.squadId, summonerId = enemy.instanceId)
                battle.put(add)
                if (battle.isHostile(enemy)) battle.provoked += add.instanceId
            }
        }
        phase.statusId?.let { applyStatus(battle, next.instanceId, StatusApplication(it, sourceId = next.instanceId)) }
        val text = phase.announcement.ifBlank { phase.name }
        cues.announce(text, next.position)
        battle.events += CombatEvent.BossPhaseBegan(next.name, phase.name, phase.announcement)
        return next
    }

    /**
     * Bodies in reach and off cooldown swing: a hostile monster at the player
     * when the player is in reach, or else at one of the player's side; a
     * follower at the nearest hostile.
     */
    private fun monstersSwing(battle: Battlefield) {
        if (!battle.player.isAlive) return
        battle.enemies.map { it.instanceId }.forEach { id ->
            val enemy = battle.enemy(id)?.takeIf { it.isAlive } ?: return@forEach
            if (enemy.attackCooldown > 0f || enemy.casting != null) return@forEach
            val side = sideOf(battle, enemy) ?: return@forEach
            if (statuses.of(id).isStunned(book)) return@forEach
            val reach = enemy.stats.attackRange + SkillTargeting.REACH_FORGIVENESS
            val target = targetFor(battle, enemy, side, reach) ?: return@forEach
            // Past the challenge's limit, a monster waits its turn: its swing stays ready for when one comes free.
            if (target.id == PLAYER && side == CombatSide.MONSTERS && !mayPress(id)) return@forEach
            if (side == CombatSide.MONSTERS && (sheltered(enemy.position) || sheltered(target.position))) return@forEach
            val gap = enemy.stats.secondsBetweenAttacks * tempoOf(side)
            battle.put(enemy.copy(attackCooldown = gap))
            if (target.id == PLAYER && side == CombatSide.MONSTERS) press(id, gap)
            battle.swung += id
            hit(battle, side, id, basicSkill(enemy.damageTypeId, enemy.stats.attackRange), target.id, 0, enemy.position)
        }
    }

    /** Inside a safe town, where no hostile attack lands. */
    private fun sheltered(at: WorldPoint): Boolean = safe?.contains(at) == true

    /**
     * Holds a monster's blow to what the player's level can bear: never
     * more than [BlowCeiling]'s share of their life, scaled by the challenge.
     */
    private fun withinCeiling(battle: Battlefield, casterId: String, r: DamageResult): DamageResult {
        if (challenge == null || r.amount <= 0) return r
        val caster = battle.enemy(casterId)
        val rank = caster?.rank ?: EnemyRank.MINION
        val bonus = tier * com.stratum.core.domain.difficulty.Difficulty.LEVELS_PER_TIER
        val gap = caster?.let { MonsterLevel.gap(it.level, rank, battle.player.level, bonus) } ?: 0
        val ceiling = BlowCeiling.of(profile.maxHealth(battle.player), rank, gap, tier, challenge.damageTaken)
        if (r.amount <= ceiling) return r
        val k = ceiling.toFloat() / r.amount
        return r.copy(amount = ceiling, packets = r.packets.mapValues { (_, v) -> (v * k).roundToInt() })
    }

    /** Hostile monsters' time between attacks is stretched or shortened by the challenge; followers keep their own pace. */
    private fun tempoOf(side: CombatSide): Float = if (side == CombatSide.MONSTERS) challenge?.tempo ?: 1f else 1f

    /** Whether monster [id] may attack the player now: it already holds a turn, or fewer than the challenge allows do. */
    private fun mayPress(id: String): Boolean {
        val limit = challenge?.attackers ?: return true
        return id in engaged || engaged.size < limit
    }

    /** Monster [id] attacked the player: it holds its turn until a little after its next attack is due. */
    private fun press(id: String, untilNext: Float) {
        if (challenge != null) engaged[id] = untilNext + TURN_GRACE
    }

    /** Which side [enemy] fights on now, or null when it is not fighting: followers for the player, hostile monsters against. */
    private fun sideOf(battle: Battlefield, enemy: EnemyInstance): CombatSide? = when {
        enemy.factionId == Factions.PLAYER -> CombatSide.PLAYER
        battle.isHostile(enemy) -> CombatSide.MONSTERS
        else -> null
    }

    /**
     * Whom [enemy] goes for: the nearest of the other side, within [within]
     * blocks when given. A monster with the player in swinging reach swings
     * at the player -- the player is the one the fight is about -- and
     * otherwise at the nearest of the player's side; a follower goes for the
     * nearest hostile it can see.
     */
    private fun targetFor(battle: Battlefield, enemy: EnemyInstance, side: CombatSide, within: Float? = null): Candidate? {
        val here = enemy.position
        return when (side) {
            CombatSide.MONSTERS -> {
                val range = within ?: Float.MAX_VALUE
                if (within != null && battle.player.isAlive && battle.player.position.horizontalDistanceTo(here) <= range) {
                    return Candidate(PLAYER, battle.player.position)
                }
                candidatesAgainst(battle, CombatSide.MONSTERS).filter { it.position.horizontalDistanceTo(here) <= range }
                    .minByOrNull { it.position.horizontalDistanceTo(here) }
            }
            CombatSide.PLAYER -> {
                val range = within ?: (director.definition(enemy.definitionId)?.aggroRange?.toFloat() ?: FOLLOWER_SIGHT)
                battle.enemies.filter { it.isAlive && it.factionId != Factions.PLAYER && battle.isHostile(it) && it.position.horizontalDistanceTo(here) <= range }
                    .minByOrNull { it.position.horizontalDistanceTo(here) }?.let { Candidate(it.instanceId, it.position) }
            }
        }
    }

    private fun fadeSummons(battle: Battlefield, deltaSeconds: Float) {
        battle.enemies.forEach { enemy ->
            val left = enemy.expiresIn ?: return@forEach
            if (left - deltaSeconds <= 0f) {
                battle.remove(enemy.instanceId)
                forget(enemy.instanceId)
            } else {
                battle.put(enemy.copy(expiresIn = left - deltaSeconds))
            }
        }
    }

    private fun recover(battle: Battlefield, deltaSeconds: Float) {
        val player = battle.player
        if (!player.isAlive) return
        val own = statuses.of(PLAYER)
        val sheet = profile.sheet(player, profile.extras(player, own, null, null))
        val maxHealth = profile.maxHealth(player)
        val maxResource = profile.maxResource(player)
        val noRegen = profile.traits(player).has(Keystone.NO_REGENERATION)
        val life = if (noRegen) 0f else sheet.apply(Stat.LIFE_REGEN, 0f) + own.recoveryPerSecond(maxHealth, book)
        val resource = BASE_RESOURCE_REGEN * maxResource + sheet.apply(Stat.RESOURCE_REGEN, 0f)
        battle.player = vitals.advance(player, deltaSeconds, Regeneration(life, resource, maxHealth, maxResource))
        lastLifeFraction = battle.player.health.toFloat() / maxHealth.coerceAtLeast(1)
    }

    /** Turns what reached the player this tick into cues and events, as one hurt number rather than a flurry. */
    private fun finish(battle: Battlefield): List<CombatEvent> {
        val events = mutableListOf<CombatEvent>()
        val landed = battle.incoming.sumOf { it.amount } + dotTaken
        if (battle.dodged > 0) {
            cues.dodged(battle.player.position)
            events += CombatEvent.PlayerDodged(battle.dodged)
        }
        if (landed > 0) {
            cues.hurt(landed, battle.player.position)
            flashes.strike(PLAYER)
            events += CombatEvent.PlayerHurt(landed, battle.incoming.toList())
        }
        events += battle.events
        if (!battle.player.isAlive && (landed > 0)) {
            cues.fallen(battle.player.position)
            events += CombatEvent.PlayerDied
        }
        return events
    }

    // ---- who is who ------------------------------------------------------------------

    /**
     * What a side's skills can reach: the player's reach any monster that is
     * not an ally; the monsters' reach the player and the player's followers,
     * summons and defenders.
     */
    private fun candidatesAgainst(battle: Battlefield, side: CombatSide): List<Candidate> = when (side) {
        CombatSide.PLAYER -> battle.enemies.filter { it.isAlive && !battle.isAllied(it) }.map { Candidate(it.instanceId, it.position) }
        CombatSide.MONSTERS -> {
            val allies = battle.enemies.filter { it.isAlive && it.factionId == Factions.PLAYER }.map { Candidate(it.instanceId, it.position) }
            if (battle.player.isAlive) listOf(Candidate(PLAYER, battle.player.position)) + allies else allies
        }
    }

    /** Ids of the caster's side, the caster included. */
    private fun alliesOf(battle: Battlefield, casterId: String, side: CombatSide): List<String> = when (side) {
        CombatSide.PLAYER -> listOf(PLAYER) + battle.enemies.filter { it.isAlive && it.factionId == Factions.PLAYER }.map { it.instanceId }
        CombatSide.MONSTERS -> {
            val caster = battle.enemy(casterId)
            battle.enemies.filter {
                it.isAlive && it.factionId != Factions.PLAYER && caster != null && it.factionId == caster.factionId &&
                    battle.isHostile(it) == battle.isHostile(caster)
            }.map { it.instanceId }
        }
    }

    private fun monsterAttacker(battle: Battlefield, casterId: String, skill: SkillDefinition): HitAttacker? {
        val enemy = battle.enemy(casterId) ?: return lastAttackers[casterId]
        val modifiers = statuses.of(casterId).modifiers(book)
        val sheet = StatSheet(modifiers)
        val attacker = HitAttacker(
            stats = sheet.applyTo(enemy.stats), sheet = sheet, conversions = skill.conversions,
            evadable = skill.hasTag(SkillTags.ATTACK), id = casterId,
        )
        if (lastAttackers.size > MAX_REMEMBERED) lastAttackers.clear()
        lastAttackers[casterId] = attacker
        return attacker
    }

    private fun monsterDefender(enemy: EnemyInstance): HitDefender {
        val set = statuses.of(enemy.instanceId)
        val sheet = StatSheet(set.modifiers(book))
        return HitDefender(sheet.applyTo(enemy.stats), sheet, set)
    }

    private fun skillFor(side: CombatSide, id: String): SkillDefinition? =
        if (side == CombatSide.PLAYER) playerSkill(id) ?: content.skill(id) else content.skill(id)

    private fun basicSkill(damageTypeId: String, reach: Int): SkillDefinition = basicSkills.getOrPut(damageTypeId to reach) {
        SkillDefinition(
            id = BASIC_ATTACK_ID, name = "Attack", damageTypeId = damageTypeId, powerMultiplier = 1f, resourceCost = 0,
            cooldownSeconds = 0f, delivery = SkillDelivery.MELEE, range = reach, tags = setOf(SkillTags.ATTACK, SkillTags.MELEE),
        )
    }

    /**
     * Where the player's skill points: at the nearest thing it could reach,
     * or straight ahead. A phone has no mouse cursor; aiming a projectile at
     * the thing that is obviously the target is the game's job.
     */
    private fun aimFor(battle: Battlefield, skill: SkillDefinition): Aim {
        val here = battle.player.position
        val nearest = candidatesAgainst(battle, CombatSide.PLAYER)
            .filter { it.position.horizontalDistanceTo(here) <= skill.range + AUTO_AIM_SLACK }
            .minByOrNull { it.position.horizontalDistanceTo(here) }
        return nearest?.let { Aim.toward(here, it.position, facing(battle.player)) } ?: facing(battle.player)
    }

    private fun facing(player: PlayerState) = Aim.of(player.facing.dx.toFloat(), player.facing.dy.toFloat())

    private fun roll(chance: Float): Boolean = chance >= 1f || random.nextFloat() < chance

    private fun landed(battle: Battlefield, skill: SkillDefinition?) = AttackReport.Landed(
        hits = battle.hits.toList(),
        slain = battle.enemies.filter { !it.isAlive },
        skill = skill,
    )

    /** One hit of the player's side, named for the meter: the swing, a skill, a triggered cast, or a minion's blow. */
    private fun dealt(battle: Battlefield, casterId: String, skill: SkillDefinition, targetId: String, result: DamageResult): DamageDealt {
        val source = when {
            casterId != PLAYER -> {
                val minion = battle.enemy(casterId)
                val definition = minion?.let { director.definition(it.definitionId) }
                DamageSource(DamageSourceKind.MINION, definition?.id ?: casterId, definition?.name ?: minion?.name ?: casterId)
            }
            skill.id == BASIC_ATTACK_ID -> DamageSource(DamageSourceKind.ATTACK, skill.id, skill.name)
            SkillTags.TRIGGERED in skill.tags -> DamageSource(DamageSourceKind.TRIGGER, skill.id, skill.name)
            else -> DamageSource(DamageSourceKind.SKILL, skill.id, skill.name)
        }
        return DamageDealt(
            source, targetId, result.amount.toFloat(), result.packets.mapValues { it.value.toFloat() },
            critical = result.wasCritical, evaded = result.wasEvaded, blocked = result.wasBlocked,
        )
    }

    /** A tick of damage over time the player's side inflicted, or null for one somebody else did. */
    private fun ailmentDealt(battle: Battlefield, targetId: String, tick: DotTick, amount: Float): DamageDealt? {
        val source = tick.sourceId ?: return null
        if (source != PLAYER && battle.enemy(source)?.factionId != Factions.PLAYER) return null
        val name = book[tick.statusId]?.name ?: tick.statusId
        return DamageDealt(DamageSource(DamageSourceKind.AILMENT, tick.statusId, name), targetId, amount, mapOf(tick.damageTypeId to amount), overTime = true)
    }

    /** The number, the flash and the shove of one hit. */
    private fun showHit(hit: EnemyHit, from: WorldPoint) {
        val result = hit.result
        val color = content.damageType(result.damageTypeId).color
        when {
            result.wasEvaded -> cues.evaded(hit.enemy.position)
            result.wasBlocked -> cues.blocked(hit.enemy.position)
            result.wasCritical -> cues.critical(result.amount, hit.enemy.position, color)
            else -> cues.dealt(result.amount, hit.enemy.position, color)
        }
        if (result.amount <= 0) return
        flashes.strike(hit.enemyId)
        // Force scales with the blow, but only a heavy hit really throws: an
        // ordinary swing barely rocks the body, because knocking a monster back
        // every time pushes it out of reach and turns melee into chase-and-poke.
        val heavy = result.wasCritical || result.amount >= hit.enemy.stats.maxHealth * WorldSession.HEAVY_HIT_FRACTION
        impacts.strike(hit.enemyId, from, hit.enemy.position, result.amount.toFloat() * if (heavy) 1f else WorldSession.LIGHT_HIT_DAMPING)
    }

    companion object {
        const val PLAYER = WorldSession.PLAYER_ACTOR_ID
        const val BASIC_ATTACK_ID = "stratum:basic-attack"

        /** Resource trickles back at this share of the pool a second, so skills are a rhythm rather than a ration. */
        const val BASE_RESOURCE_REGEN = 0.02f
        private val DEFERRED = setOf(SkillDelivery.PROJECTILE, SkillDelivery.ZONE, SkillDelivery.SUMMON, SkillDelivery.SELF, SkillDelivery.DASH)
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private const val DASH_STEP = 0.25f
        private const val SUMMON_RING = 1.5f
        private const val ADD_RING = 2.5f
        private const val SUMMON_SQUAD = "summon:"
        private const val AUTO_AIM_SLACK = 2f
        private const val MAX_SWING_GAP = 1.5f
        /** How long past its next attack a monster keeps its turn at the player, so a steady attacker is not cut in on. */
        private const val TURN_GRACE = 0.75f

        /** How far a follower looks for a fight when its definition does not say. */
        private const val FOLLOWER_SIGHT = 8f
        private const val MAX_REMEMBERED = 256
    }
}
