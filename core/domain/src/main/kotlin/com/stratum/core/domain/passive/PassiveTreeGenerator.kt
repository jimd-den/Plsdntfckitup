package com.stratum.core.domain.passive

import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Grows a vast passive tree for packs that do not draw their own.
 *
 * Rings of nodes around the class starts, cut into themed sectors: each
 * sector leans on a handful of stats, so a direction on the tree means a kind
 * of build. Rings are gated -- sectors only join their neighbours through
 * every other ring -- so reaching a far notable is a route to plan, not a
 * straight line. Notables sit on the spokes between rings, and keystones on
 * the rim, one per theme.
 *
 * Deterministic in its inputs, so a character saved against the generated
 * tree finds the same nodes next time.
 */
object PassiveTreeGenerator {

    data class Settings(
        val seed: Long = 0x5EED_7EE,
        val rings: Int = 14,
        val sectorsPerTheme: Int = 2,
        /** Distance between rings, in tree units. */
        val ringSpacing: Float = 100f,
    ) {
        init {
            require(rings >= 2) { "A passive tree needs at least two rings, not $rings" }
            require(sectorsPerTheme >= 1) { "Each theme needs a sector" }
        }
    }

    const val TREE_ID = "stratum:passives"

    fun generate(classes: List<HeroClassDefinition>, settings: Settings = Settings()): PassiveTree =
        Growth(classes, settings).grow()

    private class Growth(private val classes: List<HeroClassDefinition>, private val settings: Settings) {
        private val random = Random(settings.seed)
        private val sectors = PassiveThemes.all.size * settings.sectorsPerTheme
        private val nodes = mutableListOf<PassiveNode>()
        private val links = mutableListOf<PassiveLink>()

        /** Node ids by ring, sector and position along the sector. */
        private val grid = HashMap<Triple<Int, Int, Int>, String>()

        fun grow(): PassiveTree {
            for (ring in 1..settings.rings) for (sector in 0 until sectors) growSector(ring, sector)
            placeStarts()
            return PassiveTree(TREE_ID, "Passive skills", nodes, links)
        }

        private fun perSector(ring: Int): Int = 2 + ring / 2

        private fun growSector(ring: Int, sector: Int) {
            val count = perSector(ring)
            repeat(count) { index ->
                val id = "$TREE_ID/r${ring}s${sector}n$index"
                grid[Triple(ring, sector, index)] = id
                val (x, y) = position(ring, angleOf(sector, index, count))
                nodes += nodeAt(id, ring, sector, index == count / 2, x, y)
                if (index > 0) link(grid.getValue(Triple(ring, sector, index - 1)), id)
            }
            joinInward(ring, sector, count / 2)
            if (ring % 3 == 0) joinInward(ring, sector, 0)
            if (sector == sectors - 1) closeRing(ring)
        }

        private fun nodeAt(id: String, ring: Int, sector: Int, onSpoke: Boolean, x: Float, y: Float): PassiveNode {
            val themeIndex = sector / settings.sectorsPerTheme
            val theme = PassiveThemes.all[themeIndex]
            // One keystone per theme: on the rim of the first of its sectors.
            val isKeystone = onSpoke && ring == settings.rings && sector % settings.sectorsPerTheme == 0
            val strength = 1f + ring * OUTWARD_GROWTH
            return when {
                isKeystone -> theme.keystone.copy(id = id, x = x, y = y)
                onSpoke && ring % NOTABLE_EVERY == 0 -> PassiveNode(
                    id = id,
                    name = theme.notableNames[(ring / NOTABLE_EVERY + sector) % theme.notableNames.size],
                    kind = PassiveKind.NOTABLE,
                    modifiers = theme.rolls.shuffled(random).take(2).map { it.scaled(strength * NOTABLE_STRENGTH) },
                    x = x,
                    y = y,
                )
                else -> {
                    val roll = theme.rolls.random(random)
                    PassiveNode(id, roll.stat.label.replaceFirstChar(Char::uppercase), PassiveKind.SMALL, listOf(roll.scaled(strength)), x, y)
                }
            }
        }

        private fun joinInward(ring: Int, sector: Int, index: Int) {
            if (ring == 1) return
            val inner = perSector(ring - 1)
            val angle = angleOf(sector, index, perSector(ring))
            val nearest = (0 until inner).minBy { abs(angleOf(sector, it, inner) - angle) }
            link(grid.getValue(Triple(ring, sector, index)), grid.getValue(Triple(ring - 1, sector, nearest)))
        }

        /**
         * Joins each sector to the next around the ring. The first ring is a
         * full circle, so every start can reach every theme; beyond it, gates
         * alternate ring by ring.
         */
        private fun closeRing(ring: Int) {
            for (sector in 0 until sectors) {
                if (ring > 1 && (ring + sector) % 2 != 0) continue
                val last = grid.getValue(Triple(ring, sector, perSector(ring) - 1))
                link(last, grid.getValue(Triple(ring, (sector + 1) % sectors, 0)))
            }
        }

        private fun placeStarts() {
            val homes = classes.ifEmpty { listOf(null) }
            homes.forEachIndexed { index, hero ->
                val angle = 2.0 * PI * index / homes.size
                val (x, y) = position(START_RING, angle)
                val id = "$TREE_ID/start/${hero?.id ?: "any"}"
                nodes += PassiveNode(
                    id = id,
                    name = hero?.let { "${it.name}'s threshold" } ?: "Threshold",
                    kind = PassiveKind.START,
                    x = x,
                    y = y,
                    classIds = listOfNotNull(hero?.id),
                )
                val count = perSector(1)
                val nearest = (0 until sectors).flatMap { sector -> (0 until count).map { sector to it } }
                    .minBy { (sector, index) -> angularDistance(angleOf(sector, index, count), angle) }
                link(id, grid.getValue(Triple(1, nearest.first, nearest.second)))
            }
        }

        private fun angleOf(sector: Int, index: Int, count: Int): Double = 2.0 * PI * (sector + (index + 0.5) / count) / sectors

        private fun position(ring: Float, angle: Double): Pair<Float, Float> =
            (cos(angle) * ring * settings.ringSpacing).toFloat() to (sin(angle) * ring * settings.ringSpacing).toFloat()

        private fun position(ring: Int, angle: Double) = position(ring.toFloat(), angle)

        private fun link(a: String, b: String) {
            links += PassiveLink(a, b)
        }
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val raw = abs(a - b) % (2 * PI)
        return minOf(raw, 2 * PI - raw)
    }

    private const val START_RING = 0.45f
    private const val NOTABLE_EVERY = 3
    private const val NOTABLE_STRENGTH = 2.5f

    /** Nodes farther out are a little stronger, which is what makes the trek worth it. */
    private const val OUTWARD_GROWTH = 0.04f
}

/** One stat a theme's nodes can grant, at a small node's strength. */
data class ThemeRoll(val stat: Stat, val kind: ModifierKind, val value: Float) {
    fun scaled(by: Float): StatModifier {
        val raw = value * by
        val tidy = if (kind == ModifierKind.FLAT && !stat.isPercent) raw.roundToInt().toFloat() else (raw * 200f).roundToInt() / 200f
        return StatModifier(stat, kind, tidy)
    }
}

/** A direction on the generated tree: the stats it grants, what its notables are called, and its keystone. */
data class PassiveTheme(
    val name: String,
    val rolls: List<ThemeRoll>,
    val notableNames: List<String>,
    val keystone: PassiveNode,
)

object PassiveThemes {
    private fun inc(stat: Stat, value: Float) = ThemeRoll(stat, ModifierKind.INCREASED, value)
    private fun flat(stat: Stat, value: Float) = ThemeRoll(stat, ModifierKind.FLAT, value)
    private fun more(stat: Stat, value: Float) = StatModifier(stat, ModifierKind.MORE, value)
    private fun keystone(name: String, description: String, vararg modifiers: StatModifier) =
        PassiveNode(id = "", name = name, kind = PassiveKind.KEYSTONE, modifiers = modifiers.toList(), description = description)

    val might = PassiveTheme(
        name = "Might",
        rolls = listOf(inc(Stat.DAMAGE, 0.08f), inc(Stat.ATTACK_SPEED, 0.04f), flat(Stat.CRIT_MULTIPLIER, 0.1f)),
        notableNames = listOf("Heavy Hand", "Iron Grip", "Butcher's Rhythm", "Sundering Weight", "Warlord's Due"),
        keystone = keystone("Glass Oath", "Hit harder than anything alive, and break like it.", more(Stat.DAMAGE, 0.4f), more(Stat.MAX_HEALTH, -0.3f)),
    )

    val bulwark = PassiveTheme(
        name = "Bulwark",
        rolls = listOf(flat(Stat.MAX_HEALTH, 8f), inc(Stat.MAX_HEALTH, 0.05f), inc(Stat.ARMOUR, 0.1f), flat(Stat.RESISTANCE, 0.04f)),
        notableNames = listOf("Stone Skin", "Hearth Blood", "Unyielding", "Old Oak", "Last Wall"),
        keystone = keystone("Iron Vow", "Nothing gets through, and you are in no hurry.", more(Stat.ARMOUR, 0.3f), more(Stat.MAX_HEALTH, 0.2f), more(Stat.ATTACK_SPEED, -0.15f)),
    )

    val arcana = PassiveTheme(
        name = "Arcana",
        rolls = listOf(inc(Stat.SKILL_DAMAGE, 0.1f), inc(Stat.COOLDOWN_RECOVERY, 0.04f), inc(Stat.RESOURCE_COST, -0.04f), flat(Stat.MAX_RESOURCE, 5f)),
        notableNames = listOf("Deep Well", "Quickened Word", "Echoing Rite", "Spellwright", "Open Channel"),
        keystone = keystone("Unbound Mind", "Every skill lands like a storm and drinks like one.", more(Stat.SKILL_DAMAGE, 0.4f), more(Stat.RESOURCE_COST, 0.5f)),
    )

    val precision = PassiveTheme(
        name = "Precision",
        rolls = listOf(flat(Stat.CRIT_CHANCE, 0.01f), flat(Stat.CRIT_MULTIPLIER, 0.08f), inc(Stat.ATTACK_SPEED, 0.03f)),
        notableNames = listOf("Hawk's Eye", "Needle Point", "Killing Calm", "Flaw Finder", "Clean Cut"),
        keystone = keystone("Fleetfoot Pact", "Never be where the blow lands.", more(Stat.MOVE_SPEED, 0.25f), more(Stat.ATTACK_SPEED, 0.2f), more(Stat.ARMOUR, -0.4f)),
    )

    val wanderer = PassiveTheme(
        name = "Wanderer",
        rolls = listOf(inc(Stat.MOVE_SPEED, 0.03f), inc(Stat.AREA, 0.06f), inc(Stat.EXPERIENCE_GAIN, 0.03f)),
        notableNames = listOf("Long Road", "Wide Sky", "Pathfinder", "Far Horizon", "Tireless"),
        keystone = keystone("Storm Crown", "Your reach is vast; your blows are thin.", more(Stat.AREA, 0.5f), more(Stat.DAMAGE, -0.15f)),
    )

    val fortune = PassiveTheme(
        name = "Fortune",
        rolls = listOf(inc(Stat.ITEM_RARITY, 0.06f), inc(Stat.ITEM_QUANTITY, 0.03f), flat(Stat.LIFE_STEAL, 0.005f)),
        notableNames = listOf("Lucky Find", "Magpie", "Blood Price", "Gilded Path", "Treasure Sense"),
        keystone = keystone("Gambler's Mark", "The world pays out richly, if you live to collect.", more(Stat.ITEM_RARITY, 0.5f), more(Stat.ITEM_QUANTITY, 0.25f), more(Stat.MAX_HEALTH, -0.15f)),
    )

    val all: List<PassiveTheme> = listOf(might, bulwark, arcana, precision, wanderer, fortune)
}
