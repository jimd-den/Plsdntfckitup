package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/** The satchel and the anvil as a session offers them; see [GearSystem]. */
interface SessionGear {
    /** Inserts the player is carrying loose, resolved and sorted for display. */
    val heldInserts: List<HeldInsert>

    /** Currency the player holds, in the order the packs list it. */
    val heldCurrency: List<Held<CurrencyDefinition>>

    /** Supports the player holds but has not linked. */
    val heldSupports: List<Held<SupportDefinition>>

    /** Resolves an insert id against the loaded packs. */
    fun insertOrNull(insertId: String): InsertDefinition?

    /** Equips something from the bag, in [slot] or wherever it goes; what was worn there goes back into it. */
    fun equip(instanceId: String, slot: EquipmentSlot? = null): EquipResult

    /** Takes off whatever is worn in [slot], into the bag. */
    fun unequip(slot: EquipmentSlot): EquipResult

    /**
     * Drops an item out of the bag onto the ground a step from the player. It
     * lands rather than vanishing, so the player can change their mind.
     */
    fun discard(instanceId: String): EquipResult

    /** Slots one of the player's inserts into an item they are holding. */
    fun slotInsert(instanceId: String, insertId: String): SocketResult

    /** Pulls an insert back out, returning it to the pouch intact. */
    fun unslotInsert(instanceId: String, socketIndex: Int): SocketResult

    /** Supports linked to one skill, in link order. */
    fun supportsOn(skillId: String): List<SupportDefinition>

    /** Spends one currency on an item the player holds. */
    fun craft(instanceId: String, currencyId: String): CraftResult

    fun linkSupport(skillId: String, supportId: String): SupportResult

    fun unlinkSupport(skillId: String, supportId: String): SupportResult

    /** Puts an item on the ground, for a chest or a quest reward. */
    fun dropLoot(item: ItemInstance, position: WorldPoint)

    /** Puts an insert on the ground. */
    fun dropInsert(insertId: String, position: WorldPoint)
}

/**
 * Everything the player carries and wears: equipping, the bag, sockets,
 * currency crafting, support links, and picking up what lies on the ground.
 *
 * The rules are in [PlayerGear], [Workbench] and [GroundItems]; this is the
 * part of the session that applies them to the one player and tells the
 * screen what changed.
 */
internal class GearSystem(
    private val state: SessionState,
    private val content: AssembledContent,
    private val workbench: Workbench,
    private val ground: GroundItems,
    private val roller: LootRoller,
    private val cues: SessionCues,
    private val random: Random,
) : SessionGear {
    private val gear = PlayerGear(::insertOrNull)
    private var player: PlayerState
        get() = state.player
        set(value) {
            state.player = value
        }

    override fun insertOrNull(insertId: String): InsertDefinition? = content.insert(insertId)

    /** A skill as this character casts it, with the build's damage, cost, cooldown and area applied. */
    fun tuned(skill: SkillDefinition): SkillDefinition = workbench.tuned(player, skill)

    fun linkedTo(player: PlayerState, skillId: String): List<SupportDefinition> = workbench.linkedTo(player, skillId)

    override fun supportsOn(skillId: String): List<SupportDefinition> = workbench.linkedTo(player, skillId)

    override val heldCurrency: List<Held<CurrencyDefinition>> get() = workbench.heldCurrency(player)

    override val heldSupports: List<Held<SupportDefinition>> get() = workbench.heldSupports(player)

    override val heldInserts: List<HeldInsert>
        get() = player.insertBag.entries
            .mapNotNull { (id, count) -> content.insert(id)?.let { HeldInsert(it, count) } }
            .sortedWith(compareByDescending<HeldInsert> { it.definition.tier }.thenBy { it.definition.name })

    override fun equip(instanceId: String, slot: EquipmentSlot?): EquipResult = gear.equip(player, instanceId, slot).also { player = it.player }.result

    override fun unequip(slot: EquipmentSlot): EquipResult = gear.unequip(player, slot).also { player = it.player }.result

    override fun discard(instanceId: String): EquipResult {
        val item = player.bag.firstOrNull { it.instanceId == instanceId } ?: return EquipResult.NotInBag
        player = player.copy(bag = player.bag - item)
        // A step away, or the player picks it straight back up.
        ground.drop(GroundLoot(item, player.position.translated(DISCARD_STEP, 0f, 0f)))
        return EquipResult.Discarded(item)
    }

    override fun slotInsert(instanceId: String, insertId: String): SocketResult {
        val result = gear.slot(player, instanceId, insertId).also { player = it.player }.result
        if (result is SocketResult.Slotted) {
            val insert = content.insert(insertId)
            cues.insertSlotted(insert?.name ?: insertId, player.position, insert?.color)
        }
        return result
    }

    override fun unslotInsert(instanceId: String, socketIndex: Int): SocketResult =
        gear.unslot(player, instanceId, socketIndex).also { player = it.player }.result

    override fun craft(instanceId: String, currencyId: String): CraftResult {
        val (updated, result) = workbench.craft(player, instanceId, currencyId, random)
        player = updated
        if (result is CraftResult.Crafted) cues.itemTaken(result.after.name, player.position, content.rarityColor(result.after.rarity), equipped = true)
        return result
    }

    override fun linkSupport(skillId: String, supportId: String): SupportResult = workbench.link(player, skillId, supportId).also { player = it.first }.second

    override fun unlinkSupport(skillId: String, supportId: String): SupportResult = workbench.unlink(player, skillId, supportId).also { player = it.first }.second

    override fun dropLoot(item: ItemInstance, position: WorldPoint) = ground.drop(GroundLoot(item, position))

    override fun dropInsert(insertId: String, position: WorldPoint) = ground.drop(GroundInsert(insertId, position))

    /**
     * Picks up whatever the player stands on. Upgrades equip themselves:
     * making the player open a bag to feel a drop is the fastest way to make
     * loot stop feeling like a reward. Inserts go into the pouch.
     */
    fun collect(): List<CombatEvent> {
        val loot = ground.takeLootNear(player.position).map { found ->
            val autoEquipped = player.isUpgrade(found.item)
            player = if (autoEquipped) player.equipping(found.item) else player.collecting(found.item)
            cues.itemTaken(found.item.name, player.position, content.rarityColor(found.item.rarity), autoEquipped)
            CombatEvent.LootTaken(found.item, autoEquipped)
        }
        val inserts = ground.takeInsertsNear(player.position).mapNotNull { found ->
            val definition = content.insert(found.insertId) ?: return@mapNotNull null
            player = player.withInsert(definition.id)
            cues.insertTaken(definition.name, player.position, definition.color)
            CombatEvent.InsertTaken(definition)
        }
        return loot + inserts
    }

    /**
     * Arms a fresh character with its class's starting weapon so the first
     * fight is winnable. Common, so the first upgrade is an upgrade.
     */
    fun armed(fresh: PlayerState, heroClass: HeroClassDefinition?): PlayerState {
        val base = heroClass?.startingWeaponId?.let(content::itemBase)
            ?: content.itemCatalogue.bases.filter { it.weapon != null && it.family == null }.minByOrNull { it.minItemLevel }
            ?: return fresh
        return fresh.equipping(roller.craft(base, itemLevel = 1, rarity = ItemRarity.COMMON, random = random))
    }

    private companion object {
        /** Far enough that a discard is not undone by the next tick. */
        const val DISCARD_STEP = 2f
    }
}
