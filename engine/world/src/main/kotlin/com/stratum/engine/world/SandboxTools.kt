package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.EnemyState
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.sandbox.BuildCode
import com.stratum.core.domain.sandbox.DamageMeter
import com.stratum.core.domain.sandbox.DummySpec
import com.stratum.core.domain.sandbox.ImportedBuild
import com.stratum.core.domain.sandbox.SharedBuild
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/** What a sandbox tool did. */
sealed interface SandboxResult {
    data class ItemMade(val item: ItemInstance) : SandboxResult

    data class Rerolled(val before: ItemInstance, val after: ItemInstance) : SandboxResult

    data class Granted(val name: String, val count: Int) : SandboxResult

    data class LevelSet(val level: Int) : SandboxResult

    /** Every passive node given back; [nodes] is how many. */
    data class Respecced(val nodes: Int) : SandboxResult

    data class Spawned(val enemies: List<EnemyInstance>) : SandboxResult

    data class Cleared(val count: Int) : SandboxResult

    /** A base, unique, currency, support or monster the loaded packs do not have. */
    data object NotFound : SandboxResult

    /** Asked for something the tool cannot make: a unique rarity with no unique named, say. */
    data class Refused(val reason: String) : SandboxResult
}

/** What to conjure: a base at a rarity, or a unique or set piece by id, at an item level. */
data class ItemRequest(
    val baseId: String? = null,
    val uniqueId: String? = null,
    val itemLevel: Int = 1,
    val rarity: ItemRarity = ItemRarity.RARE,
)

/**
 * The build sandbox: tools that break the game's economy on purpose so a
 * player can make a build and see what it does. Respec for free, conjure any
 * base, unique or set piece at any level and reroll it, grant currency,
 * supports and levels, stand training dummies up and call any monster, and
 * read a damage meter while it all happens.
 *
 * Only a world whose rules say sandbox has one (see [WorldSession.sandbox]).
 * It works on the session's player and monsters through the same seams
 * persistence does, and rolls from its own seeded dice, so using it never
 * shifts the world's own sequence of drops and spawns.
 */
class SandboxTools internal constructor(
    private val session: WorldSession,
    combat: CombatSystem,
) {
    private val content = session.content
    private val generator = ItemGenerator(content.itemCatalogue)
    private val random = Random(session.config.seed xor SANDBOX_SALT)
    private val passives = PassiveProgress(content.passiveTree)

    /** Counts the player's damage from the moment the world began. */
    val meter = DamageMeter()

    /** Where each dummy stands, by instance id: it is put back there after every tick. */
    private val pinned = LinkedHashMap<String, WorldPoint>()
    private var dummiesMade = 0

    init {
        combat.onDealt = meter::record
    }

    /** Whether this world's caps are lifted. Changing it means a new world; see the play screen. */
    val capsLifted: Boolean get() = session.rules.combat == CombatRules.UNBOUND

    /**
     * Runs after each tick: the meter's clock moves, and dummies are stood
     * back where they were put -- knockback and the crowd may shove them, and
     * a target that wanders is not a measurement.
     */
    fun advance(deltaSeconds: Float) {
        meter.advance(deltaSeconds)
        if (pinned.isEmpty()) return
        val alive = session.enemies.mapTo(HashSet()) { it.instanceId }
        pinned.keys.retainAll(alive)
        session.enemies = session.enemies.map { enemy ->
            val at = pinned[enemy.instanceId] ?: return@map enemy
            enemy.copy(position = at, state = EnemyState.IDLE, attackCooldown = NEVER, casting = null)
        }
    }

    // ---- the hero ------------------------------------------------------------------

    /** Every passive node back, for free. */
    fun respec(): SandboxResult {
        val count = session.player.passives.size
        session.player = passives.settle(session.player.copy(passives = emptySet()))
        return SandboxResult.Respecced(count)
    }

    /**
     * Sets the hero's level. A level too low for the nodes already taken
     * gives them all back, since a build the points cannot pay for is not
     * the build that level would have.
     */
    fun setLevel(level: Int): SandboxResult {
        val target = level.coerceIn(1, Progression.MAX_LEVEL)
        var player = session.player.copy(level = target, experience = 0)
        if (player.passives.size > Progression.passivePointsFor(target)) player = passives.settle(player.copy(passives = emptySet()))
        session.player = player.copy(health = session.statsFor(player).maxHealth, resource = player.resourceCeiling)
        return SandboxResult.LevelSet(target)
    }

    fun grantCurrency(currencyId: String, count: Int = GRANT): SandboxResult {
        val currency = content.currency(currencyId) ?: return SandboxResult.NotFound
        session.player = session.player.withCurrency(currency.id, count)
        return SandboxResult.Granted(currency.name, count)
    }

    fun grantSupport(supportId: String, count: Int = 1): SandboxResult {
        val support = content.support(supportId) ?: return SandboxResult.NotFound
        session.player = session.player.withSupport(support.id, count)
        return SandboxResult.Granted(support.name, count)
    }

    /** A stack of every currency, or one of every support: a fresh bench to craft or link on. */
    fun grantEverything(currencies: Int = GRANT, supports: Int = 1): SandboxResult {
        var player = session.player
        content.currencies.forEach { player = player.withCurrency(it.id, currencies) }
        content.supports.forEach { player = player.withSupport(it.id, supports) }
        session.player = player
        return SandboxResult.Granted("everything", content.currencies.size + content.supports.size)
    }

    // ---- items ---------------------------------------------------------------------

    /** Every base, including the deeper rungs grown from them, in catalogue order. */
    val bases: List<ItemBase> get() = content.itemCatalogue.bases

    /** Every unique and set piece. */
    val uniques: List<UniqueDefinition> get() = content.itemCatalogue.uniques

    /** Makes the item asked for and puts it in the bag. */
    fun spawnItem(request: ItemRequest): SandboxResult {
        val level = request.itemLevel.coerceIn(1, MAX_ITEM_LEVEL)
        val item = when {
            request.uniqueId != null -> {
                val unique = content.itemCatalogue.unique(request.uniqueId) ?: return SandboxResult.NotFound
                generator.unique(unique, level, random) ?: return SandboxResult.NotFound
            }
            request.baseId != null -> {
                val base = content.itemCatalogue.base(request.baseId) ?: return SandboxResult.NotFound
                if (!request.rarity.isRolled) return SandboxResult.Refused("A ${request.rarity.name.lowercase()} item is chosen by name, not rolled on a base")
                generator.craft(base, level, request.rarity, random)
            }
            else -> return SandboxResult.Refused("Choose a base or a unique")
        }
        session.player = session.player.collecting(item)
        return SandboxResult.ItemMade(item)
    }

    /**
     * Rolls a held item again as the same thing -- the same base or unique,
     * level and rarity -- keeping its place, its id and what is socketed in
     * it, so a worn piece is rerolled where it is worn.
     */
    fun reroll(instanceId: String): SandboxResult {
        val before = session.player.itemById(instanceId) ?: return SandboxResult.NotFound
        val fresh = before.uniqueId?.let { id -> content.itemCatalogue.unique(id)?.let { generator.unique(it, before.itemLevel, random) } }
            ?: content.itemCatalogue.base(before.baseId)?.takeIf { before.rarity.isRolled }?.let { generator.craft(it, before.itemLevel, before.rarity, random) }
            ?: return SandboxResult.NotFound
        val after = fresh.copy(instanceId = before.instanceId, sockets = before.sockets)
        val player = session.player.replacing(after)
        session.player = player.copy(health = player.health.coerceAtMost(session.statsFor(player).maxHealth))
        return SandboxResult.Rerolled(before, after)
    }

    // ---- targets -------------------------------------------------------------------

    /** The dummies standing now. */
    val dummies: List<EnemyInstance> get() = session.enemies.filter { it.instanceId in pinned }

    /** Monsters the loaded packs define, the bosses among them first. */
    val monsters: List<EnemyDefinition>
        get() = content.enemies.sortedWith(compareByDescending<EnemyDefinition> { isBoss(it) }.thenBy { it.name })

    fun isBoss(definition: EnemyDefinition): Boolean = definition.rank == EnemyRank.BOSS || definition.phases.isNotEmpty()

    /**
     * Stands a training dummy a few steps in front of the player, beside any
     * already there. It has [spec]'s defences, no attack, no skills and no
     * reward, and it is put back where it stood after every tick.
     */
    fun spawnDummy(spec: DummySpec = DummySpec()): SandboxResult {
        val at = besidePlayer(pinned.size) ?: return SandboxResult.Refused("No ground in front of you")
        val types = content.damageTypes.map { it.id }
        val dummy = EnemyInstance(
            instanceId = "dummy_${dummiesMade++}",
            definitionId = DUMMY_ID,
            name = "Training dummy",
            rank = EnemyRank.MINION,
            position = at,
            health = spec.life,
            stats = spec.stats(types),
            damageTypeId = types.firstOrNull().orEmpty(),
            attackCooldown = NEVER,
            experience = 0,
            bodyColor = DUMMY_COLOUR,
        )
        pinned[dummy.instanceId] = at
        session.enemies = session.enemies + dummy
        return SandboxResult.Spawned(listOf(dummy))
    }

    /** Every dummy back to full life. */
    fun healDummies(): SandboxResult {
        session.enemies = session.enemies.map { if (it.instanceId in pinned) it.copy(health = it.stats.maxHealth) else it }
        return SandboxResult.Cleared(pinned.size)
    }

    fun clearDummies(): SandboxResult {
        val count = pinned.size
        session.enemies = session.enemies.filterNot { it.instanceId in pinned }
        pinned.clear()
        return SandboxResult.Cleared(count)
    }

    /** Calls a monster the packs define in front of the player, at [rank] -- its own rank when none is asked for. */
    fun spawnMonster(definitionId: String, rank: EnemyRank? = null): SandboxResult {
        val definition = content.enemies.firstOrNull { it.id == definitionId } ?: return SandboxResult.NotFound
        val at = besidePlayer(0, reach = MONSTER_REACH) ?: return SandboxResult.Refused("No ground in front of you")
        val spawned = session.spawn(definition, at)
        val wanted = rank ?: if (isBoss(definition)) EnemyRank.BOSS else definition.rank
        val ranked = reranked(spawned, definition, wanted)
        session.enemies = session.enemies.map { if (it.instanceId == spawned.instanceId) ranked else it }
        return SandboxResult.Spawned(listOf(ranked))
    }

    // ---- builds --------------------------------------------------------------------

    fun exportBuild(name: String = ""): SharedBuild = BuildCode.of(session.player, name)

    /**
     * Reads a shared build into a hero for a new world. Taking it on means a
     * new world, because a class is chosen when the world is made; the play
     * screen does that with the hero this returns.
     */
    fun importBuild(text: String): Result<ImportedBuild> =
        BuildCode.read(text).map { BuildCode.toHero(it, content.itemCatalogue, random, id = session.player.heroClassId) }

    // ---- where things stand --------------------------------------------------------

    /** A spot [reach] steps in front of the player, [slot] steps to the side of the last, standing on the ground. */
    private fun besidePlayer(slot: Int, reach: Float = DUMMY_REACH): WorldPoint? {
        val player = session.player
        val fx = player.facing.dx.toFloat()
        val fy = player.facing.dy.toFloat()
        // Alternate sides, a step further out each pair: 0, +1, -1, +2, -2 ...
        val side = if (slot == 0) 0 else ((slot + 1) / 2) * if (slot % 2 == 1) 1 else -1
        val x = player.position.x + fx * reach - fy * side * DUMMY_SPACING
        val y = player.position.y + fy * reach + fx * side * DUMMY_SPACING
        val surface = session.world.surfaceAt(floor(x).toInt(), floor(y).toInt())
        if (surface < 0) return null
        return WorldPoint(floor(x) + 0.5f, floor(y) + 0.5f, (surface + 1).toFloat())
    }

    /** The same monster at another rank: its health and hit scaled by the ranks' ratio, the name to match. */
    private fun reranked(enemy: EnemyInstance, definition: EnemyDefinition, rank: EnemyRank): EnemyInstance {
        if (enemy.rank == rank) return enemy
        val health = rank.healthMultiplier / enemy.rank.healthMultiplier
        val damage = rank.damageMultiplier / enemy.rank.damageMultiplier
        val stats = enemy.stats.copy(
            maxHealth = (enemy.stats.maxHealth * health).roundToInt().coerceAtLeast(1),
            attackPower = (enemy.stats.attackPower * damage).roundToInt().coerceAtLeast(1),
        )
        return enemy.copy(
            rank = rank,
            stats = stats,
            health = stats.maxHealth,
            name = if (rank == EnemyRank.MINION) definition.name else "${rank.name.lowercase().replaceFirstChar { it.uppercase() }} ${definition.name}",
            experience = (enemy.experience * rank.experienceMultiplier / enemy.rank.experienceMultiplier).roundToInt(),
        )
    }

    companion object {
        /** Not content: the sandbox's own target, which no pack defines and nothing else spawns. */
        const val DUMMY_ID = "stratum:training-dummy"
        const val MAX_ITEM_LEVEL = 100
        const val GRANT = 20
        private const val DUMMY_REACH = 3f
        private const val MONSTER_REACH = 6f
        private const val DUMMY_SPACING = 2f
        private const val DUMMY_COLOUR = 0xFFB08850
        private const val SANDBOX_SALT = 0x5A4DB0L

        /** An attack clock that never runs down: a dummy never swings. */
        private const val NEVER = Float.MAX_VALUE
    }
}
