package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.tabletop.ActiveBoon
import com.stratum.core.domain.tabletop.CheckResult
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldPoint

// What a WorldSession reports back: the result of each thing a player can
// do, and the snapshot the UI draws from. Values only; no rules live here.

data class MoveOutcome(val player: PlayerState, val moved: Boolean, val blocked: Boolean)

sealed interface DodgeResult {
    data object Rolling : DodgeResult
    data object OnCooldown : DodgeResult
    data object AlreadyRolling : DodgeResult
    data object Rejected : DodgeResult
}

data class SessionSnapshot(
    val player: PlayerState,
    val focus: ChunkPos,
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
    /** Boons and banes running from tabletop checks. */
    val activeBoons: List<ActiveBoon> = emptyList(),
    /** The town the player stands in, or null in the wilds. */
    val settlement: SettlementPlan? = null,
    /** Whether that town is held against the player. */
    val settlementHostile: Boolean = false,
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

    /** An erase drag, and how many blocks it handed back. */
    data class Erased(val removed: Int) : BuildResult
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

    /** A stronghold's garrison is gone: the town is the player's side's now. */
    data class TownLiberated(val town: SettlementPlan) : CombatEvent
}

/** What trying a tabletop check did. */
sealed interface CheckAttempt {
    data class Rolled(val result: CheckResult) : CheckAttempt
    data class OnCooldown(val secondsLeft: Float) : CheckAttempt
    data object UnknownCheck : CheckAttempt

    /** The fallen roll no dice. */
    data object Refused : CheckAttempt
}
