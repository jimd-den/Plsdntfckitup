package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.quest.QuestGenerator
import com.stratum.core.domain.quest.Trade
import com.stratum.core.domain.quest.Townsperson
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.PlacedBuilding
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor

/**
 * The people of friendly towns, living their day.
 *
 * Each friendly town in sight is peopled with its residents -- the same
 * people, by name and trade, as give out its quests -- and each walks a
 * schedule through the day: home at night, to their work in the morning, the
 * town square at midday, back to work, the tavern at dusk, home again. They
 * are civilians: never hostile, never a target, never counted against the
 * wild's population. The crowd moves them; this only says where home is
 * right now.
 *
 * In a safe town, hostiles that wander or chase in turn back at the edge.
 */
internal class TownLife(
    private val settings: GameSettings,
    private val seed: Long,
    /** Updated each tick with the friendly towns in sight. */
    private val safe: SafeGround,
    /** A point standing on the ground at (x, y), or null where there is none loaded. */
    private val ground: (Int, Int) -> WorldPoint?,
) {
    /** Each town's residents, as quest givers, made once. */
    private val people = HashMap<String, List<Townsperson>>()

    /** The residents of [town], the same on every visit. */
    fun residentsOf(town: SettlementPlan): List<Townsperson> = people.getOrPut(town.id) {
        QuestGenerator.residents(town.id, town.name, seed, populationOf(town))
    }

    /** How many people live in [town] at the chosen town life. */
    fun populationOf(town: SettlementPlan): Int {
        val houses = town.buildings.count { it.template.role == BuildingRole.HOUSE }.coerceAtLeast(1)
        val perHouse = floatArrayOf(0.5f, 1f, 1.6f, 2.4f)[settings.townLife.coerceIn(0, 3)]
        return (houses * perHouse).toInt().coerceIn(2, MAX_RESIDENTS)
    }

    /**
     * The enemies list with every friendly town in [towns] peopled, every
     * resident heading for where its schedule says, and -- in safe towns --
     * every hostile inside turned back.
     *
     * @param dayFraction 0 at dawn, 0.5 at dusk.
     */
    fun advance(
        enemies: List<EnemyInstance>,
        towns: List<SettlementPlan>,
        friendly: (SettlementPlan) -> Boolean,
        hostile: (EnemyInstance) -> Boolean,
        dayFraction: Float,
    ): List<EnemyInstance> {
        val peaceful = towns.filter(friendly)
        safe.update(peaceful)
        if (peaceful.isEmpty()) return enemies
        val present = enemies.mapNotNullTo(HashSet()) { e -> e.instanceId.takeIf { e.civilian } }
        val arrivals = ArrayList<EnemyInstance>()
        val byPerson = HashMap<String, Pair<SettlementPlan, Townsperson>>()
        for (town in peaceful) {
            residentsOf(town).forEachIndexed { i, person ->
                val id = idOf(person)
                byPerson[id] = town to person
                if (id !in present) spawn(town, person, i, dayFraction)?.let { arrivals += it }
            }
        }
        return (enemies + arrivals).mapNotNull { e ->
            when {
                e.civilian -> {
                    val (town, person) = byPerson[e.instanceId] ?: return@mapNotNull e
                    val index = residentsOf(town).indexOf(person)
                    val goal = scheduleFor(town, person, index, dayFraction) ?: return@mapNotNull e
                    if (e.home == goal) e else e.copy(home = goal)
                }
                // Safe streets: hostiles stop at the edge (see SafeGround); one already inside, as when a town comes into sight around it, leaves.
                settings.safeTowns && e.home == null && hostile(e) && peaceful.any { it.contains(floor(e.position.x).toInt(), floor(e.position.y).toInt()) } -> null
                else -> e
            }
        }
    }

    private fun spawn(town: SettlementPlan, person: Townsperson, index: Int, dayFraction: Float): EnemyInstance? {
        val at = scheduleFor(town, person, index, dayFraction) ?: return null
        return EnemyInstance(
            instanceId = idOf(person), definitionId = TOWNSPERSON, name = "${person.name} the ${person.trade.label}",
            rank = EnemyRank.MINION, position = at, health = HEALTH, stats = CombatStats(maxHealth = HEALTH, attackPower = 0, attackRange = 1),
            damageTypeId = "", experience = 0, bodyColor = colourOf(person), factionId = town.factionId, role = CombatRole.SUPPORT,
            squadId = "civ:${town.id}", home = at, civilian = true,
        )
    }

    /** Where [person] should be at [dayFraction]: a door, the square or the tavern. Each keeps their own hours. */
    fun scheduleFor(town: SettlementPlan, person: Townsperson, index: Int, dayFraction: Float): WorldPoint? {
        // Nobody keeps exactly the same hours: each shifts their day by up to an hour or so.
        val t = ((dayFraction + ((person.id.hashCode() and 0xFF) / 255f - 0.5f) * 0.06f) % 1f + 1f) % 1f
        val houses = town.buildings.filter { it.template.role == BuildingRole.HOUSE }
        val home = houses.getOrNull(index % houses.size.coerceAtLeast(1))
        val place: PlacedBuilding? = when {
            t < 0.06f || t >= 0.62f -> home
            t < 0.28f || (t >= 0.36f && t < 0.5f) -> workOf(town, person.trade) ?: home
            t < 0.36f -> null // midday: the square
            else -> town.buildings.firstOrNull { it.template.role == BuildingRole.TAVERN } // dusk: the tavern, or the square
        }
        // Spread people about a door or the square rather than stacking them on one block.
        val jitter = (index * 7 % 5) - 2
        val (x, y) = if (place != null) place.doorX + jitter / 2 to place.doorY + jitter % 2
        else town.centerX + (index * 13 % 9) - 4 to town.centerY + (index * 17 % 9) - 4
        return ground(x, y)
    }

    private fun workOf(town: SettlementPlan, trade: Trade): PlacedBuilding? {
        val roles = when (trade) {
            Trade.SMITH -> listOf(BuildingRole.SMITHY)
            Trade.TRADER, Trade.WEAVER, Trade.POTTER, Trade.CARVER -> listOf(BuildingRole.SHOP, BuildingRole.WAREHOUSE)
            Trade.HEALER, Trade.DIVINER, Trade.ELDER, Trade.DRUMMER -> listOf(BuildingRole.TEMPLE, BuildingRole.HALL)
            Trade.FARMER, Trade.FISHER -> listOf(BuildingRole.FARM)
            Trade.GUARD, Trade.HUNTER -> listOf(BuildingRole.BARRACKS, BuildingRole.TOWER)
            Trade.BREWER -> listOf(BuildingRole.TAVERN)
            Trade.BUILDER, Trade.MASON -> listOf(BuildingRole.WAREHOUSE, BuildingRole.HALL)
        }
        return roles.firstNotNullOfOrNull { role -> town.buildings.firstOrNull { it.template.role == role } }
    }

    private fun colourOf(person: Townsperson): Long {
        val h = person.id.hashCode()
        return 0xFF000000 or ((0x60 + (h and 0x5F)).toLong() shl 16) or ((0x50 + (h shr 8 and 0x4F)).toLong() shl 8) or (0x40 + (h shr 16 and 0x3F)).toLong()
    }

    companion object {
        /** The definition every townsperson is drawn and moved as. */
        const val TOWNSPERSON = "stratum:townsperson"
        const val MAX_RESIDENTS = 24
        private const val HEALTH = 100

        fun idOf(person: Townsperson) = "civ:${person.id}"
    }
}
