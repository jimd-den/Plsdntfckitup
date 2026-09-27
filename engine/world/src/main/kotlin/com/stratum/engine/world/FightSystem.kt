package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.status.StatusSet
import com.stratum.core.domain.world.WorldPoint

/** The player's side of the fight as a session offers it; see [FightSystem]. */
interface SessionFight {
    /**
     * The player's stats with gear, inserts, traits, boons and needs counted
     * in. Every combat path reads this, so a rune or a blessing is never in
     * the tooltip but missing from the swing.
     */
    val playerStats: CombatStats

    /** Skills the class has, resolved against the loaded packs and tuned by the build. */
    val skills: List<SkillDefinition>

    /** The belt, with charges. */
    val flasks: List<FlaskView>

    /** A skill as this character casts it, with the build's damage, cost, cooldown and area applied. */
    fun skillOrNull(skillId: String): SkillDefinition?

    /** A basic swing. Refused while the weapon is still recovering. */
    fun attack(): AttackReport

    /** Casts one of the class's skills, spending its cost and starting its cooldown. */
    fun castSkill(skillId: String): AttackReport

    /** Drinks from the flask in [slot] on the belt. */
    fun useFlask(slot: Int): FlaskResult

    /** Statuses [actorId] carries now; the player's under [WorldSession.PLAYER_ACTOR_ID]. */
    fun statusesOf(actorId: String): StatusSet

    /**
     * Places a monster deliberately, at its definition's rank, for a scripted
     * encounter or a shrine that wakes something up. The director fills the
     * world on its own; this is for when it should contain something specific.
     */
    fun spawn(definition: EnemyDefinition, position: WorldPoint): EnemyInstance

    /** Environmental damage: a fall, a trap, a hazard block. Returns whether the player still stands. */
    fun hurtPlayer(amount: Int): Boolean
}

/**
 * The fight as the session runs it: the player's swings, casts and flasks,
 * and the monsters' turn each tick, each resolved by [CombatSystem] on one
 * [Battlefield] and taken back in one place.
 *
 * Taking a fight back is where its consequences land -- who is now provoked,
 * what was reported, who died -- so every way into a fight ends the same way.
 */
internal class FightSystem(
    private val state: SessionState,
    private val content: AssembledContent,
    private val combat: CombatSystem,
    private val profile: PlayerProfile,
    private val gear: GearSystem,
    private val encounters: EncounterSystem,
    private val politics: PoliticsSystem,
    private val animator: ActorAnimator,
    private val invulnerable: () -> Boolean,
) : SessionFight {

    override val playerStats: CombatStats get() = profile.statsWith(state.player, profile.traits(state.player).modifiers)

    override val skills: List<SkillDefinition> get() = state.player.skillIds.mapNotNull(::skillOrNull)

    override val flasks: List<FlaskView> get() = combat.flaskViews

    override fun skillOrNull(skillId: String): SkillDefinition? = content.skill(skillId)?.let(gear::tuned)

    override fun attack(): AttackReport {
        if (state.player.attackCooldown > 0f) return AttackReport.NotReady
        val damageType = state.player.equippedWeapon?.damageTypeWithSockets(content::insert) ?: WorldSession.DEFAULT_DAMAGE_TYPE
        val battle = battlefield()
        val report = combat.basicAttack(battle, damageType)
        if (report == AttackReport.Stunned) return report
        battle.player = battle.player.copy(attackCooldown = playerStats.secondsBetweenAttacks)
        animator.holdAttack(WorldSession.PLAYER_ACTOR_ID)
        settle(battle)
        return report
    }

    override fun castSkill(skillId: String): AttackReport {
        val skill = skillOrNull(skillId) ?: return AttackReport.UnknownSkill
        val battle = battlefield()
        val report = combat.castPlayerSkill(battle, skill)
        if (report is AttackReport.Landed || report is AttackReport.Cast || report == AttackReport.Missed) animator.holdCast(WorldSession.PLAYER_ACTOR_ID)
        settle(battle)
        return report
    }

    override fun useFlask(slot: Int): FlaskResult {
        val battle = battlefield()
        return combat.drinkFlask(battle, slot).also { settle(battle) }
    }

    override fun statusesOf(actorId: String): StatusSet = combat.statuses.of(actorId)

    override fun spawn(definition: EnemyDefinition, position: WorldPoint): EnemyInstance = encounters.spawn(definition, position)

    override fun hurtPlayer(amount: Int): Boolean {
        if (amount > 0) state.player = state.player.damaged(amount)
        return state.player.isAlive
    }

    /**
     * The monsters' turn: the wilds are topped up and every body moves, then
     * the fight's clocks run -- statuses, wind-ups, casts, swings, projectiles,
     * the player's recovery. Returns what the player should be told.
     */
    fun monstersAct(deltaSeconds: Float, biomeId: String): List<CombatEvent> {
        if (content.enemies.isEmpty()) return emptyList()
        encounters.advance(deltaSeconds, biomeId)
        val battle = battlefield()
        val told = combat.advance(battle, deltaSeconds)
        // Whoever swung is mid-attack for a beat, so the animation reads.
        battle.swung.forEach(animator::holdAttack)
        settle(battle, reportEvents = false)
        encounters.skirmish()
        politics.afterFight(deltaSeconds)
        return told
    }

    /** The fight as the combat system works on it: this instant's player and monsters. */
    private fun battlefield() = Battlefield(state.player, state.enemies, invulnerable(), politics::isAllied, politics::isHostile)

    /** Takes a resolved fight back: the player, the monsters, who is now provoked, and the dead to bury. */
    private fun settle(battle: Battlefield, reportEvents: Boolean = true) {
        state.player = battle.player
        state.enemies = battle.enemies
        politics.provoke(battle.hits.map { it.enemyId })
        politics.provoke(battle.provoked)
        if (reportEvents) state.pending += battle.events
        encounters.bury(state.enemies.filterNot { it.isAlive })
    }
}
