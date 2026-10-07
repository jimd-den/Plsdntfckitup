package com.stratum.core.domain.settlement.culture

import kotlin.random.Random

/** What a past event left on a town's ground, for its layout to draw. */
enum class Mark {
    /** A quarter burnt or abandoned: thinner, with gaps where houses stood. */
    RUIN,
    /** A wall raised against a threat, where there was none before. */
    WALL,
    /** The town grew past its old bounds. */
    GROWTH,
    /** A second shrine or mosque raised. */
    SHRINE,
    /** A wider market, or a second one. */
    MARKET,
    /** Newcomers given a quarter of their own. */
    STRANGERS,
}

/** One thing that happened to a town, [yearsAgo] before now. */
data class TownEvent(val yearsAgo: Int, val text: String, val mark: Mark? = null)

/** How a town began. */
data class Founding(val founder: String, val deed: String, val sign: String) {
    val text: String get() = "$founder who $deed, $sign"
}

/**
 * Everything that makes one settlement itself, decided before a single
 * building is placed: its people and their way of building, what kind of
 * place it is, who rules it, what it makes, what stands at its heart, which
 * quarters it has, whether it is walled, how it was founded and what has
 * happened to it since -- and its name.
 *
 * Its layout reads it: a kraal rings its byre, an Igbo village-group
 * scatters walled compounds round its square, a Hausa city walls itself
 * and opens a gate for each road, a Swahili stone town crowds its lanes
 * against the water. Events leave marks on the ground -- a burnt quarter,
 * a second wall, a strangers' quarter -- so a town's history can be read
 * from its plan.
 */
data class CityGenome(
    val seed: Long,
    val culture: String,
    val name: String,
    val form: Form,
    val rule: Governance,
    /** What it makes, the first being what it is known for. */
    val crafts: List<Craft>,
    val heart: Heart,
    /** Its quarters, in the order its layout fills them. */
    val quarters: List<Quarter>,
    val walled: Boolean,
    /** Gates in its wall, when walled. */
    val gates: Int,
    val founding: Founding,
    /** Years since it was founded. */
    val age: Int,
    /** What has happened to it since, oldest first. */
    val history: List<TownEvent>,
) {
    val people: Culture get() = Cultures.of(culture)

    val marks: Set<Mark> get() = history.mapNotNullTo(LinkedHashSet()) { it.mark }

    /** "Umuagu-Ukwu, an Igbo village-group known for its great yams". */
    val title: String get() = "$name, ${article(people.name)} ${people.name} ${form.label.lowercase()} known for its ${crafts.first().noun}"

    /** The town's story, as a board, a guide or a loading screen tells it. */
    fun describe(): String = buildString {
        append(title).append(". ")
        append("Ruled by the ${rule.label.lowercase()} from the ${rule.seat.lowercase()}; at its heart, ${heart.label.lowercase()}. ")
        if (crafts.size > 1) append("Its people also deal in ${crafts.drop(1).joinToString(" and ") { it.noun }}. ")
        if (walled) append("A wall rings it, with $gates ${if (gates == 1) "gate" else "gates"}. ")
        append("Founded some $age years ago by ${founding.text}.")
        history.forEach { append(" ").append(it.yearsAgo).append(" years ago, ").append(it.text).append('.') }
    }

    private fun article(word: String) = if (word.first().lowercaseChar() in "aeiou") "an" else "a"
}

/**
 * Rolls [CityGenome]s: the same seed and culture give the same town, every
 * time, on every device.
 */
object CityGenerator {

    private val founders = listOf(
        "a hunter", "a blacksmith", "twin sisters", "a diviner", "a queen", "a band of traders", "exiled princes",
        "a rainmaker", "a herder and her cattle", "a fisherman", "a potter", "a scholar", "a warrior woman",
        "three brothers", "a runaway bride", "a kola merchant", "a wandering griot", "a weaver",
    )
    private val deeds = listOf(
        "followed a leopard to water", "struck the first hoe on this ground", "split a kola nut that fell with every lobe up",
        "dreamed of a python coiled round a silk-cotton tree", "buried a bronze head beneath the square",
        "outran a flood on the river", "found salt where the ground lay white", "planted a tree where the paths crossed",
        "made peace between two feuding lineages", "bargained a market day from the spirits", "read the stars over the dunes",
        "carried fire from the old capital", "dug a well where no one else could find water", "carved a mask that spoke",
        "led the herds through the dry season", "traded cloth for the right to settle", "climbed the cliff to escape raiders",
        "won the land in a wrestling match",
    )
    private val signs = listOf(
        "and the rains came early that year", "and a white ram stood waiting", "under a sky full of weaver birds",
        "as the drums of the old town fell silent", "and the earth accepted the first yam", "as the harmattan turned",
        "and an egret settled on the first roof", "and the river ran red with laterite", "on the first market day of the week",
        "and the elders' kola fell true", "as a comet hung over the hills", "and the bees swarmed to the doorway",
        "while the masquerades danced", "and no child cried for seven days",
    )
    private val causes = listOf(
        "a disputed farm boundary", "the market's toll", "a stolen bride", "the right to a well", "a broken oath",
        "the salt road", "grazing on the floodplain", "an insult at a funeral", "the river crossing",
    )
    private val places = listOf("the north", "the coast", "the forest", "beyond the river", "the old capital", "the desert's edge", "the highlands", "the lakes")

    /**
     * The town [seed] gives a people of [cultureId]; [form] fixes the kind of
     * place, when the caller already knows it. A [gathering] place -- a home
     * town, where a player begins -- is never a lone homestead or a camp.
     */
    fun roll(seed: Long, cultureId: String?, form: Form? = null, gathering: Boolean = false): CityGenome {
        val people = Cultures.of(cultureId)
        val r = Random(seed xor 0x5EED_C17EL)
        val forms = if (gathering) people.forms.filter { it.first.scale != Scale.SMALL }.ifEmpty { people.forms } else people.forms
        val f = form ?: weighted(forms, r)
        val rule = weighted(people.rule, r)
        val craftCount = if (f.city) 2 + r.nextInt(2) else 1 + r.nextInt(2)
        val crafts = distinctWeighted(people.crafts, craftCount, r)
        val heart = when {
            f.pattern == Pattern.RINGED -> Heart.CATTLE_BYRE.takeIf { it in people.hearts } ?: people.hearts.random(r)
            rule == Governance.OBA || rule == Governance.NEGUS || rule == Governance.STOOL -> if (r.nextFloat() < 0.6f) Heart.PALACE_COURT else people.hearts.random(r)
            else -> people.hearts.random(r)
        }
        val quarters = quartersFor(f, people, crafts, r)
        val canWall = f.pattern == Pattern.WALLED || f.pattern == Pattern.KSAR || f.pattern == Pattern.CITADEL || f.city || f.pattern == Pattern.RINGED
        val walled = f.pattern == Pattern.WALLED || f.pattern == Pattern.KSAR || (canWall && r.nextFloat() < people.walls)
        val age = when {
            f.city -> 180 + r.nextInt(820)
            else -> 40 + r.nextInt(400)
        }
        val founding = Founding(founders.random(r), deeds.random(r), signs.random(r))
        val history = historyFor(age, f, people, rule, quarters, walled, r)
        val wallsNow = walled || history.any { it.mark == Mark.WALL }
        val gates = if (wallsNow) (if (f.city) 3 + r.nextInt(4) else 1 + r.nextInt(3)) else 0
        val extra = history.mapNotNull { e ->
            when (e.mark) {
                Mark.STRANGERS -> Quarter.STRANGERS
                Mark.MARKET -> Quarter.MARKET
                Mark.SHRINE -> Quarter.SHRINES
                else -> null
            }
        }
        val allQuarters = (quarters + extra + (if (wallsNow) listOf(Quarter.GATES) else emptyList())).distinct()
        return CityGenome(seed, people.id, nameFor(people, r), f, rule, crafts, heart, allQuarters, wallsNow, gates, founding, age, history)
    }

    /** A town's name in [people]'s manner. */
    fun nameFor(people: Culture, r: Random): String {
        val prefix = people.prefixes.random(r)
        val root = people.roots.random(r)
        val suffix = people.suffixes.random(r)
        val joined = if (prefix.endsWith("-") || prefix.endsWith(" ")) prefix + root.replaceFirstChar { it.uppercase() } else prefix + root
        return (joined + suffix).replaceFirstChar { it.uppercase() }
    }

    private fun quartersFor(form: Form, people: Culture, crafts: List<Craft>, r: Random): List<Quarter> {
        val base = mutableListOf(Quarter.SEAT, Quarter.COMPOUNDS)
        if (form.pattern != Pattern.RINGED && form.pattern != Pattern.COMPOUND) base += Quarter.SQUARE
        // The craft quarters its trades need: smiths, a market, stores, farms...
        crafts.forEach { craft ->
            base += when (craft.role) {
                com.stratum.core.domain.settlement.BuildingRole.SMITHY -> Quarter.CRAFTS
                com.stratum.core.domain.settlement.BuildingRole.SHOP -> Quarter.MARKET
                com.stratum.core.domain.settlement.BuildingRole.WAREHOUSE -> if (craft == Craft.BOATS || craft == Craft.FISHING) Quarter.WATERFRONT else Quarter.GRANARIES
                com.stratum.core.domain.settlement.BuildingRole.FARM -> Quarter.FIELDS
                com.stratum.core.domain.settlement.BuildingRole.TAVERN -> Quarter.STRANGERS
                com.stratum.core.domain.settlement.BuildingRole.TEMPLE -> Quarter.SCHOOL
                else -> Quarter.COMPOUNDS
            }
        }
        // Then a few of the people's own, more for a city.
        val more = people.quarters.shuffled(r).take(if (form.city) 3 + r.nextInt(3) else 1 + r.nextInt(2))
        return (base + more).distinct()
    }

    private fun historyFor(age: Int, form: Form, people: Culture, rule: Governance, quarters: List<Quarter>, walled: Boolean, r: Random): List<TownEvent> {
        val count = (if (form.city) 3 else 1) + r.nextInt(if (form.city) 5 else 3)
        val years = List(count) { 1 + r.nextInt((age - 5).coerceAtLeast(2)) }.distinct().sortedDescending()
        val rival = { nameFor(people, r) }
        val quarter = { quarters.filter { it != Quarter.SEAT && it != Quarter.SQUARE }.randomOrNull(r)?.label?.lowercase() ?: "outer farms" }
        var hasWall = walled
        return years.map { y ->
            val kind = r.nextInt(18)
            when (kind) {
                0 -> TownEvent(y, "it fought ${rival()} over ${causes.random(r)}")
                1 -> TownEvent(y, "a great fire burned the ${quarter()}", Mark.RUIN)
                2 -> TownEvent(y, "the long hunger came, and the granaries were opened to all")
                3 -> TownEvent(y, "two heirs claimed the ${rule.seat.lowercase()}, and the town split behind them for a season")
                4 -> TownEvent(y, "it won the right to hold a market every ${listOf(4, 8, 5, 7).random(r)} days", Mark.MARKET)
                5 -> TownEvent(y, "a new shrine was raised after a sickness passed", Mark.SHRINE)
                6 -> TownEvent(y, "${people.festivals.random(r)} was first held here")
                7 -> TownEvent(y, "families from ${places.random(r)} settled in a quarter of their own", Mark.STRANGERS)
                8 -> if (!hasWall) { hasWall = true; TownEvent(y, "after raiders came from ${places.random(r)}, the people raised a wall", Mark.WALL) }
                    else TownEvent(y, "the wall was rebuilt higher after the rains broke it")
                9 -> TownEvent(y, "caravans from ${places.random(r)} began to stop here", Mark.GROWTH)
                10 -> TownEvent(y, "a daughter of the house married into ${rival()}, sealing a peace")
                11 -> TownEvent(y, "the river changed its course and the ${quarter()} was abandoned", Mark.RUIN)
                12 -> TownEvent(y, "a scholar from ${places.random(r)} began to teach under the ${listOf("market tree", "mosque's shade", "elders' roof", "great tree").random(r)}")
                13 -> TownEvent(y, "locusts ate the harvest, and the town lived on its stores")
                14 -> TownEvent(y, "gold was found in a stream nearby, and the town doubled", Mark.GROWTH)
                15 -> TownEvent(y, "a famous carver's doors were hung on the ${rule.seat.lowercase()}")
                16 -> TownEvent(y, "${rival()} burned its outer farms, and was driven off", Mark.RUIN)
                else -> TownEvent(y, "a drought dried the wells, and a new one was dug at the edge of town", Mark.GROWTH)
            }
        }
    }

    private fun <T> weighted(options: List<Pair<T, Int>>, r: Random): T {
        val total = options.sumOf { it.second }
        var roll = r.nextInt(total)
        for ((v, w) in options) { roll -= w; if (roll < 0) return v }
        return options.first().first
    }

    private fun <T> distinctWeighted(options: List<Pair<T, Int>>, count: Int, r: Random): List<T> {
        val left = options.toMutableList()
        val out = ArrayList<T>()
        repeat(count.coerceAtMost(options.size)) {
            val pick = weighted(left, r)
            out += pick
            left.removeAll { it.first == pick }
        }
        return out
    }

    // ---- how many there are ----------------------------------------------------------------------

    /**
     * Distinct towns a people can make, counted from the choices alone --
     * form, rule, crafts in order, heart, walls and gates, founding, and the
     * kind of its first event -- before names and history's details, which
     * only add to it. [cities] counts its cities; otherwise its villages,
     * hamlets, homesteads and camps.
     */
    fun permutations(cultureId: String, cities: Boolean): Long {
        val people = Cultures.of(cultureId)
        val forms = people.forms.count { it.first.city == cities }.toLong()
        if (forms == 0L) return 0L
        val crafts = people.crafts.size.toLong()
        val craftOrders = if (cities) crafts * (crafts - 1) else crafts
        val legends = founders.size.toLong() * deeds.size * signs.size
        val gates = if (cities) 5L else 3L
        val firstEvents = 18L
        return forms * people.rule.size * craftOrders * people.hearts.size * gates * legends * firstEvents
    }

    /** Distinct names a people's towns can bear. */
    fun names(cultureId: String): Long = Cultures.of(cultureId).let { it.prefixes.size.toLong() * it.roots.size * it.suffixes.size }
}
