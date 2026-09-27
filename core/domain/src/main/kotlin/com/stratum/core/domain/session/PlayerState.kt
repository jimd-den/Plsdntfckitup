package com.stratum.core.domain.session

import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.actor.SkillCooldowns
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.faction.Reputation
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.roundToInt

/**
 * Everything about the player the simulation needs. Immutable: the session
 * replaces it each tick, so a renderer holding an old copy sees a consistent
 * past rather than a half-updated present.
 */
data class PlayerState(
    val heroClassId: String,
    val position: WorldPoint,
    val facing: Direction = Direction.SOUTH,
    val health: Int = 100,
    val maxHealth: Int = 100,
    val resource: Int = 50,
    val maxResource: Int = 50,
    val resourceName: String = "Focus",
    val toolTier: Int = 1,
    /** Block ids the player can place, in hotbar order. */
    val hotbar: List<String> = emptyList(),
    val selectedSlot: Int = 0,
    val inventory: Map<String, Int> = emptyMap(),
    val level: Int = 1,
    val experience: Int = 0,
    /** The class's baseline, before gear and levels. */
    val baseStats: CombatStats = CombatStats(),
    val equippedWeapon: ItemInstance? = null,
    /** Loot picked up but not equipped. */
    val bag: List<ItemInstance> = emptyList(),
    /**
     * Inserts held loose, by definition id and count. Kept apart from
     * [inventory] because that map feeds the block hotbar: a rune appearing as
     * something placeable would be a bug the player discovers the hard way.
     */
    val insertBag: Map<String, Int> = emptyMap(),
    val skillIds: List<String> = emptyList(),
    val cooldowns: SkillCooldowns = SkillCooldowns(),
    /** Counts down between basic attacks, from the weapon's speed. */
    val attackCooldown: Float = 0f,
    /** Passive nodes bought with points, not counting the free start. */
    val passives: Set<String> = emptySet(),
    /**
     * Every lasting modifier the character has, resolved: passives today,
     * anything else that grants modifiers as it arrives. Stored rather than
     * derived because deriving it needs the tree, and this is read every
     * frame by everything that asks for a stat.
     */
    val build: StatSheet = StatSheet.EMPTY,
    /** Crafting currency by id. Stacks, like the insert pouch. */
    val currency: Map<String, Int> = emptyMap(),
    /** Support gems held but not linked, by id. */
    val supportBag: Map<String, Int> = emptyMap(),
    /** Support gems linked to each skill, by skill id, in link order. */
    val supports: Map<String, List<String>> = emptyMap(),
    /** Waystones carried, each a harder world waiting to be opened. */
    val waystones: List<Waystone> = emptyList(),
    /** The hardest world tier this character has unlocked; 0 is the base game. */
    val highestTier: Int = 0,
    /** Standing with every faction. */
    val reputation: Reputation = Reputation(),
    /** Survival needs by id, 0..100; a need not listed is full. */
    val needs: Map<String, Float> = emptyMap(),
) {
    val blockPos: BlockPos get() = position.toBlockPos()

    /** The block the player is standing in; the one below is what holds them up. */
    val feet: BlockPos get() = blockPos

    val selectedBlockId: String?
        get() = hotbar.getOrNull(selectedSlot)

    /**
     * The stats combat actually uses: the class baseline, plus what levelling
     * granted, plus whatever is equipped. Computed rather than stored so
     * swapping a weapon cannot leave a stale number behind.
     */
    val combatStats: CombatStats
        get() = combatStatsWith({ null })

    /**
     * The same numbers, with whatever is slotted into the weapon counted in.
     *
     * Inserts arrive as a lookup rather than being stored on the item, so the
     * pack stays the single source of truth for what a rune is worth: rebalance
     * a rune and every weapon carrying one rebalances with it.
     */
    fun combatStatsWith(inserts: (String) -> InsertDefinition?, damageTypeIds: Collection<String> = emptyList()): CombatStats =
        build.applyTo(gearedStats(inserts), damageTypeIds)

    private fun gearedStats(inserts: (String) -> InsertDefinition?): CombatStats {
        val levelled = baseStats.copy(
            maxHealth = baseStats.maxHealth + Progression.healthBonusFor(level),
            attackPower = baseStats.attackPower + Progression.attackBonusFor(level),
        )
        val weapon = equippedWeapon ?: return levelled
        val fromWeapon = weapon.toStats(inserts)
        val combined = levelled + fromWeapon
        // Attack speed is a base of 1 plus bonuses; the weapon replaces the
        // base rather than adding to it, or a fast weapon would also inherit
        // the fists it replaced.
        return combined.copy(
            attackSpeed = weapon.baseAttackSpeed + fromWeapon.attackSpeed,
            attackRange = weapon.attackRange,
        )
    }

    val maxHealthWithGear: Int get() = combatStats.maxHealth

    /** Health ceiling including inserts, for the session that can resolve them. */
    fun maxHealthWith(inserts: (String) -> InsertDefinition?): Int =
        combatStatsWith(inserts).maxHealth

    /** The resource pool with the build's modifiers counted in. */
    val resourceCeiling: Int get() = build.apply(Stat.MAX_RESOURCE, maxResource.toFloat()).roundToInt()

    /** Points earned by levelling and not yet spent on the tree. */
    val unspentPassivePoints: Int get() = (Progression.passivePointsFor(level) - passives.size).coerceAtLeast(0)

    val toolTierWithGear: Int get() = maxOf(toolTier, equippedWeapon?.toolTier ?: 0)

    val experienceForNextLevel: Int get() = Progression.experienceForNextLevel(level)

    val experienceFraction: Float
        get() {
            val needed = experienceForNextLevel
            return if (needed <= 0 || needed == Int.MAX_VALUE) 1f
            else (experience.toFloat() / needed).coerceIn(0f, 1f)
        }

    fun damaged(amount: Int): PlayerState = copy(health = (health - amount).coerceAtLeast(0))

    fun healed(amount: Int): PlayerState =
        copy(health = (health + amount).coerceAtMost(maxHealthWithGear))

    /**
     * Equips an item, moving whatever was held into the bag rather than
     * destroying it, and tops health up to the new maximum so a health affix is
     * felt immediately.
     */
    fun equipping(item: ItemInstance): PlayerState {
        val previous = equippedWeapon
        val updated = copy(
            equippedWeapon = item,
            bag = (bag - item) + listOfNotNull(previous),
        )
        return updated.copy(health = health.coerceAtMost(updated.maxHealthWithGear))
    }

    fun collecting(item: ItemInstance): PlayerState = copy(bag = bag + item)

    /** Replaces an item wherever it is held, so slotting edits the thing in hand. */
    fun replacing(item: ItemInstance): PlayerState = when {
        equippedWeapon?.instanceId == item.instanceId -> copy(equippedWeapon = item)
        else -> copy(bag = bag.map { if (it.instanceId == item.instanceId) item else it })
    }

    /** The item with this id, equipped or bagged. */
    fun itemById(instanceId: String): ItemInstance? =
        equippedWeapon?.takeIf { it.instanceId == instanceId }
            ?: bag.firstOrNull { it.instanceId == instanceId }

    fun currencyCount(currencyId: String): Int = currency[currencyId] ?: 0

    fun withCurrency(currencyId: String, amount: Int = 1): PlayerState = copy(currency = currency.adding(currencyId, amount))

    fun supportCount(supportId: String): Int = supportBag[supportId] ?: 0

    fun withSupport(supportId: String, amount: Int = 1): PlayerState = copy(supportBag = supportBag.adding(supportId, amount))

    fun insertCount(insertId: String): Int = insertBag[insertId] ?: 0

    fun withInsert(insertId: String, amount: Int = 1): PlayerState =
        copy(insertBag = insertBag + (insertId to insertCount(insertId) + amount))

    /** Spends one insert, or returns null when the player holds none. */
    fun consumingInsert(insertId: String): PlayerState? {
        val held = insertCount(insertId)
        if (held <= 0) return null
        return copy(
            insertBag = if (held == 1) insertBag - insertId else insertBag + (insertId to held - 1),
        )
    }

    /** True when the item beats what is held on raw damage. */
    fun isUpgrade(item: ItemInstance): Boolean {
        val current = equippedWeapon ?: return true
        return item.toStats().attackPower > current.toStats().attackPower
    }

    val isAlive: Boolean get() = health > 0

    fun countOf(itemId: String): Int = inventory[itemId] ?: 0

    fun withItem(itemId: String, amount: Int = 1): PlayerState =
        copy(inventory = inventory + (itemId to (countOf(itemId) + amount)))

    /** Removes one of an item, returning null when the player has none to spend. */
    fun consuming(itemId: String): PlayerState? {
        val held = countOf(itemId)
        if (held <= 0) return null
        return copy(
            inventory = if (held == 1) inventory - itemId else inventory + (itemId to held - 1),
        )
    }

    fun selectingSlot(slot: Int): PlayerState =
        if (hotbar.isEmpty()) this else copy(selectedSlot = slot.coerceIn(0, hotbar.lastIndex))

    companion object {
        /** A stack map with [amount] more of [id]; a stack that reaches zero is removed. */
        fun Map<String, Int>.adding(id: String, amount: Int): Map<String, Int> {
            val total = (this[id] ?: 0) + amount
            return if (total <= 0) this - id else this + (id to total)
        }

        fun from(hero: HeroClassDefinition, spawn: WorldPoint): PlayerState = PlayerState(
            heroClassId = hero.id,
            position = spawn,
            health = hero.baseHealth,
            maxHealth = hero.baseHealth,
            resource = hero.baseResource,
            maxResource = hero.baseResource,
            resourceName = hero.resourceName,
            hotbar = hero.startingBlockIds,
            inventory = hero.startingBlockIds.associateWith { STARTING_STACK },
            baseStats = hero.resolvedStats,
            skillIds = hero.abilityIds,
        )

        const val STARTING_STACK = 32
    }
}
