package com.stratum.core.domain.strategy

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.world.BlockMaterial
import kotlin.random.Random

/** Every resource, structure and unit a world knows, looked up by id. */
class StrategyBook(
    val resources: List<ResourceDefinition>,
    val structures: List<StructureDefinition>,
    val units: List<UnitDefinition>,
) {
    private val structuresById = structures.associateBy { it.id }
    private val unitsById = units.associateBy { it.id }

    fun structure(id: String): StructureDefinition? = structuresById[id]

    fun unit(id: String): UnitDefinition? = unitsById[id]

    val isEmpty: Boolean get() = resources.isEmpty() || structures.isEmpty()
}

/** Why something cannot be built or recruited, in terms the panel can show. */
sealed interface Affordability {
    data object Ok : Affordability
    data class Missing(val resources: Map<String, Float>) : Affordability
    data class Requires(val structureId: String) : Affordability
    data object AtLimit : Affordability
    data object Unknown : Affordability
}

/**
 * The rules of an outpost, pure: what it houses, what it makes, what it can
 * afford, how a raid on it goes. The session owns the outposts and the clock.
 */
object Colony {

    fun population(outpost: Outpost, book: StrategyBook): Int = sumOf(outpost, book) { it.housing }

    fun workersNeeded(outpost: Outpost, book: StrategyBook): Int = sumOf(outpost, book) { it.workers }

    /** Share of full production the outpost can staff, 0..1. */
    fun staffing(outpost: Outpost, book: StrategyBook): Float {
        val needed = workersNeeded(outpost, book)
        return if (needed == 0) 1f else (population(outpost, book).toFloat() / needed).coerceIn(0f, 1f)
    }

    fun capacity(outpost: Outpost, book: StrategyBook, resourceId: String): Float =
        BASE_STORAGE + outpost.structures.entries.sumOf { (id, n) -> (book.structure(id)?.storage?.get(resourceId) ?: 0) * n }.toFloat()

    fun defense(outpost: Outpost, book: StrategyBook): Int =
        sumOf(outpost, book) { it.defense } + outpost.garrison.entries.sumOf { (id, n) -> (book.unit(id)?.defense ?: 0) * n }

    /** Net change per minute of every resource: production at the current staffing, less upkeep and the garrison's food. */
    fun netPerMinute(outpost: Outpost, book: StrategyBook): Map<String, Float> {
        val staffing = staffing(outpost, book)
        val totals = HashMap<String, Float>()
        outpost.structures.forEach { (id, count) ->
            val structure = book.structure(id) ?: return@forEach
            structure.produces.forEach { (r, rate) -> totals[r] = (totals[r] ?: 0f) + rate * count * staffing }
            structure.upkeep.forEach { (r, rate) -> totals[r] = (totals[r] ?: 0f) - rate * count }
        }
        outpost.garrison.forEach { (id, count) ->
            book.unit(id)?.upkeep?.forEach { (r, rate) -> totals[r] = (totals[r] ?: 0f) - rate * count }
        }
        return totals
    }

    /** The outpost after [minutes] of work, every stock kept between empty and its storage. */
    fun produced(outpost: Outpost, book: StrategyBook, minutes: Float): Outpost {
        val net = netPerMinute(outpost, book)
        val ids = outpost.stockpile.keys + net.keys
        return outpost.copy(
            stockpile = ids.associateWith { id ->
                ((outpost.stockpile[id] ?: 0f) + (net[id] ?: 0f) * minutes).coerceIn(0f, capacity(outpost, book, id))
            },
        )
    }

    fun canBuild(outpost: Outpost, book: StrategyBook, structureId: String): Affordability {
        val structure = book.structure(structureId) ?: return Affordability.Unknown
        if (outpost.count(structureId) >= structure.maxCount) return Affordability.AtLimit
        structure.requires.firstOrNull { outpost.count(it) == 0 }?.let { return Affordability.Requires(it) }
        return affordable(outpost, structure.cost)
    }

    fun built(outpost: Outpost, book: StrategyBook, structureId: String): Outpost? {
        if (canBuild(outpost, book, structureId) != Affordability.Ok) return null
        val structure = book.structure(structureId)!!
        return paid(outpost, structure.cost).copy(structures = outpost.structures + (structureId to outpost.count(structureId) + 1))
    }

    fun canRecruit(outpost: Outpost, book: StrategyBook, unitId: String): Affordability {
        val unit = book.unit(unitId) ?: return Affordability.Unknown
        unit.requires?.takeIf { outpost.count(it) == 0 }?.let { return Affordability.Requires(it) }
        return affordable(outpost, unit.cost)
    }

    fun recruited(outpost: Outpost, book: StrategyBook, unitId: String): Outpost? {
        if (canRecruit(outpost, book, unitId) != Affordability.Ok) return null
        val unit = book.unit(unitId)!!
        return paid(outpost, unit.cost).copy(garrison = outpost.garrison + (unitId to (outpost.garrison[unitId] ?: 0) + 1))
    }

    /**
     * What depositing [held] into the outpost is worth: resource by resource,
     * and which items it uses up. Items worth nothing to any resource stay
     * with the player.
     */
    fun depositValue(held: Map<String, Int>, book: StrategyBook, materialOf: (String) -> BlockMaterial?): Pair<Map<String, Float>, Map<String, Int>> {
        val gained = HashMap<String, Float>()
        val used = HashMap<String, Int>()
        held.forEach { (itemId, count) ->
            val resource = book.resources.firstOrNull { itemId in it.fromItems } ?: book.resources.firstOrNull { r -> materialOf(itemId)?.let { it in r.fromMaterials } == true }
                ?: return@forEach
            val each = resource.fromItems[itemId] ?: 1
            gained[resource.id] = (gained[resource.id] ?: 0f) + each * count
            used[itemId] = count
        }
        return gained to used
    }

    fun deposited(outpost: Outpost, book: StrategyBook, gained: Map<String, Float>): Outpost =
        outpost.copy(stockpile = outpost.stockpile + gained.mapValues { (id, n) -> (outpost.has(id) + n).coerceAtMost(capacity(outpost, book, id)) })

    /** How many raiders come: more each time it holds, more when it is rich. */
    fun raidStrength(outpost: Outpost): Int =
        RAID_BASE + outpost.raidsSurvived * RAID_GROWTH + (outpost.stockpile.values.sum() / WEALTH_PER_RAIDER).toInt()

    fun raidInterval(outpost: Outpost): Float = (RAID_INTERVAL - outpost.raidsSurvived * RAID_QUICKENING).coerceAtLeast(MIN_RAID_INTERVAL)

    /**
     * A raid fought without the player: defence against numbers, with some
     * luck. Held, and the outpost grows harder to crack and more tempting.
     * Lost, and the raiders carry off a share of everything and burn a
     * building -- never the last hearth, so an outpost can always recover.
     */
    fun resolveRaid(outpost: Outpost, book: StrategyBook, random: Random): Pair<Outpost, RaidOutcome> {
        val attackers = raidStrength(outpost)
        val luck = 1f + (random.nextFloat() - 0.5f) * LUCK
        val defended = defense(outpost, book) * luck >= attackers * DEFENSE_PER_RAIDER
        if (defended) {
            val held = outpost.copy(raidsSurvived = outpost.raidsSurvived + 1, raidIn = raidInterval(outpost))
            return held to RaidOutcome(outpost.id, attackers, true, emptyMap(), emptyList())
        }
        val lost = outpost.stockpile.mapValues { (_, n) -> n * LOSS_SHARE }
        val burnable = outpost.structures.filter { (id, n) -> n > if (book.structure(id)?.housing ?: 0 > 0) 1 else 0 }.keys.toList()
        val razed = burnable.takeIf { it.isNotEmpty() }?.let { listOf(it[random.nextInt(it.size)]) }.orEmpty()
        val sacked = outpost.copy(
            stockpile = outpost.stockpile.mapValues { (id, n) -> n - (lost[id] ?: 0f) },
            structures = outpost.structures.mapValues { (id, n) -> if (id in razed) n - 1 else n }.filterValues { it > 0 },
            garrison = emptyMap(),
            raidIn = raidInterval(outpost),
        )
        return sacked to RaidOutcome(outpost.id, attackers, false, lost, razed)
    }

    private fun affordable(outpost: Outpost, cost: Map<String, Int>): Affordability {
        val missing = cost.mapValues { (id, n) -> n - outpost.has(id) }.filterValues { it > 0f }
        return if (missing.isEmpty()) Affordability.Ok else Affordability.Missing(missing)
    }

    private fun paid(outpost: Outpost, cost: Map<String, Int>): Outpost =
        outpost.copy(stockpile = outpost.stockpile + cost.mapValues { (id, n) -> outpost.has(id) - n })

    private fun sumOf(outpost: Outpost, book: StrategyBook, value: (StructureDefinition) -> Int): Int =
        outpost.structures.entries.sumOf { (id, n) -> (book.structure(id)?.let(value) ?: 0) * n }

    const val BASE_STORAGE = 200f
    const val RAID_BASE = 3
    const val RAID_GROWTH = 2
    const val WEALTH_PER_RAIDER = 150f
    const val DEFENSE_PER_RAIDER = 8f
    const val RAID_INTERVAL = 600f
    const val RAID_QUICKENING = 30f
    const val MIN_RAID_INTERVAL = 240f
    const val LOSS_SHARE = 0.3f
    const val LUCK = 0.4f
}

/**
 * The economy, units and structures the engine uses when a pack defines
 * none: four resources any world has, a dozen structures, two kinds of
 * soldier. Enough for a full loop -- gather, build, recruit, defend -- in
 * any pack, and a template for a pack that wants its own.
 */
object StandardStrategy {
    private const val NS = "stratum"

    val food = ResourceDefinition(
        "$NS:food", "Food", "🌾", 0xFFD9A441,
        fromItems = mapOf("$NS:raw_meat" to 3, "$NS:cooked_meat" to 5, "$NS:berries" to 1),
        fromMaterials = setOf(BlockMaterial.FOLIAGE),
    )
    val timber = ResourceDefinition("$NS:timber", "Timber", "🌲", 0xFF8A6238, fromMaterials = setOf(BlockMaterial.WOOD))
    val stone = ResourceDefinition("$NS:stone", "Stone", "⛰", 0xFF9E9E9E, fromMaterials = setOf(BlockMaterial.STONE, BlockMaterial.SOIL))
    val metal = ResourceDefinition("$NS:metal", "Metal", "⛓", 0xFFCD7F32, fromMaterials = setOf(BlockMaterial.METAL, BlockMaterial.ORE))
    val resources = listOf(food, timber, stone, metal)

    private fun cost(vararg pairs: Pair<ResourceDefinition, Int>) = pairs.associate { (r, n) -> r.id to n }
    private fun rate(vararg pairs: Pair<ResourceDefinition, Float>) = pairs.associate { (r, n) -> r.id to n }

    val hearth = StructureDefinition("$NS:hearth", "Hearth", "Homes for four. Every outpost starts with one.", "🏠", cost(timber to 10, stone to 5), housing = 4, maxCount = 8)
    val farm = StructureDefinition("$NS:farm", "Farm", "Feeds the outpost.", "🌾", cost(timber to 15), produces = rate(food to 6f), workers = 2)
    val lumberCamp = StructureDefinition("$NS:lumber_camp", "Lumber Camp", "Fells timber.", "🪓", cost(timber to 5, stone to 5), produces = rate(timber to 4f), workers = 2)
    val quarry = StructureDefinition("$NS:quarry", "Quarry", "Cuts stone.", "⛏", cost(timber to 10), produces = rate(stone to 3f), workers = 2)
    val forge = StructureDefinition(
        "$NS:forge", "Forge", "Smelts metal, burning timber.", "⚒", cost(stone to 20, timber to 10),
        produces = rate(metal to 1.5f), upkeep = rate(timber to 1f), workers = 2, requires = listOf(quarry.id),
    )
    val storehouse = StructureDefinition(
        "$NS:storehouse", "Storehouse", "Room for more of everything.", "📦", cost(timber to 20),
        storage = resources.associate { it.id to 200 },
    )
    val barracks = StructureDefinition(
        "$NS:barracks", "Barracks", "Trains soldiers.", "⚔", cost(timber to 25, stone to 25),
        upkeep = rate(food to 1f), workers = 1, requires = listOf(hearth.id), maxCount = 2,
    )
    val watchtower = StructureDefinition("$NS:watchtower", "Watchtower", "Sees raiders coming.", "🗼", cost(stone to 20, timber to 10), workers = 1, defense = 25)
    val palisade = StructureDefinition("$NS:palisade", "Palisade", "A wall of stakes.", "🛡", cost(timber to 30), defense = 15, maxCount = 4)
    val structures = listOf(hearth, farm, lumberCamp, quarry, forge, storehouse, barracks, watchtower, palisade)

    const val MILITIA_ACTOR = "$NS:militia"
    const val ARCHER_ACTOR = "$NS:militia_archer"

    val militia = UnitDefinition("$NS:militia", "Militia", MILITIA_ACTOR, cost(food to 10, metal to 2), rate(food to 0.5f), requires = barracks.id, defense = 8)
    val archer = UnitDefinition("$NS:archer", "Archer", ARCHER_ACTOR, cost(food to 10, timber to 10, metal to 2), rate(food to 0.5f), requires = barracks.id, defense = 6)
    val units = listOf(militia, archer)

    /** The standard soldiers' bodies, hitting with [damageTypeId]: whatever the pack's first damage type is. */
    fun actors(damageTypeId: String): List<EnemyDefinition> = listOf(
        EnemyDefinition(
            MILITIA_ACTOR, "Militia", baseStats = CombatStats(maxHealth = 80, attackPower = 10, armour = 3, attackSpeed = 1f, attackRange = 1),
            damageTypeId = damageTypeId, moveSpeed = 4.4f, aggroRange = 10, spawnWeight = 0, factionId = Factions.PLAYER, role = CombatRole.MELEE, bodyColor = 0xFF4FC3F7,
        ),
        EnemyDefinition(
            ARCHER_ACTOR, "Archer", baseStats = CombatStats(maxHealth = 55, attackPower = 9, attackSpeed = 0.9f, attackRange = 6),
            damageTypeId = damageTypeId, moveSpeed = 4.4f, aggroRange = 12, spawnWeight = 0, factionId = Factions.PLAYER, role = CombatRole.RANGED, bodyColor = 0xFF81D4FA,
        ),
    )

    /** What founding an outpost costs, in blocks from the player's bag. */
    const val FOUNDING_BLOCKS = 20

    /** A liberated town's buildings, turned into the structures closest to what they were. */
    fun structuresFor(roles: List<com.stratum.core.domain.settlement.BuildingRole>): Map<String, Int> {
        val mapped = roles.mapNotNull { role ->
            when (role) {
                com.stratum.core.domain.settlement.BuildingRole.HOUSE, com.stratum.core.domain.settlement.BuildingRole.HALL -> hearth.id
                com.stratum.core.domain.settlement.BuildingRole.FARM -> farm.id
                com.stratum.core.domain.settlement.BuildingRole.SMITHY -> forge.id
                com.stratum.core.domain.settlement.BuildingRole.BARRACKS -> barracks.id
                com.stratum.core.domain.settlement.BuildingRole.TOWER -> watchtower.id
                com.stratum.core.domain.settlement.BuildingRole.WAREHOUSE, com.stratum.core.domain.settlement.BuildingRole.SHOP -> storehouse.id
                else -> null
            }
        }
        val counts = mapped.groupingBy { it }.eachCount().mapValues { (id, n) -> n.coerceAtMost(structures.first { it.id == id }.maxCount) }
        // A forge needs a quarry to stand; a town that had a smithy had somewhere its stone came from.
        return (counts + (hearth.id to maxOf(1, counts[hearth.id] ?: 0))).let { if (forge.id in it) it + (quarry.id to maxOf(1, it[quarry.id] ?: 0)) else it }
    }
}
