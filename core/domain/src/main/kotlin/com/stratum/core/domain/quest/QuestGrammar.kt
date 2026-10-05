package com.stratum.core.domain.quest

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyRank
import java.math.BigInteger
import kotlin.random.Random

/*
 * A quest is a sentence built from orthogonal parts, each of which changes
 * what the player actually does:
 *
 *   a GIVER (their trade and temper colour the asking)
 *   with a MOTIVE (why they ask)
 *   wants an OBJECTIVE (slay, gather, mine, deliver, visit, clear, build, hunt)
 *   aimed at a TARGET (which monster, block, place or person; how many; how far)
 *   bent by a TWIST (the target is elite, flees, comes at night, is guarded...)
 *   held to a CONSTRAINT (before nightfall, untouched, without fire...)
 *   for a REWARD (coin, gear, standing, a forged attack, a map...).
 *
 * Every objective is something the engine counts; every twist and constraint
 * changes the counting or the fight, not just the words. A board never shows
 * the same pair of objective and twist twice.
 */

/** What a townsperson does for a living: who they are, and what they tend to need. */
enum class Trade(val label: String, val needs: List<ObjectiveKind>) {
    FARMER("farmer", listOf(ObjectiveKind.SLAY, ObjectiveKind.GATHER, ObjectiveKind.CLEAR)),
    SMITH("smith", listOf(ObjectiveKind.MINE, ObjectiveKind.DELIVER, ObjectiveKind.HUNT)),
    HUNTER("hunter", listOf(ObjectiveKind.HUNT, ObjectiveKind.SLAY, ObjectiveKind.VISIT)),
    HEALER("healer", listOf(ObjectiveKind.GATHER, ObjectiveKind.DELIVER, ObjectiveKind.VISIT)),
    BUILDER("builder", listOf(ObjectiveKind.BUILD, ObjectiveKind.MINE, ObjectiveKind.GATHER)),
    TRADER("trader", listOf(ObjectiveKind.DELIVER, ObjectiveKind.VISIT, ObjectiveKind.CLEAR)),
    ELDER("elder", listOf(ObjectiveKind.CLEAR, ObjectiveKind.HUNT, ObjectiveKind.VISIT)),
    CARVER("carver", listOf(ObjectiveKind.GATHER, ObjectiveKind.MINE, ObjectiveKind.BUILD)),
    DRUMMER("drummer", listOf(ObjectiveKind.DELIVER, ObjectiveKind.SLAY, ObjectiveKind.VISIT)),
    WEAVER("weaver", listOf(ObjectiveKind.GATHER, ObjectiveKind.DELIVER, ObjectiveKind.BUILD)),
    POTTER("potter", listOf(ObjectiveKind.MINE, ObjectiveKind.GATHER, ObjectiveKind.DELIVER)),
    GUARD("guard", listOf(ObjectiveKind.CLEAR, ObjectiveKind.SLAY, ObjectiveKind.HUNT)),
    FISHER("fisher", listOf(ObjectiveKind.SLAY, ObjectiveKind.VISIT, ObjectiveKind.GATHER)),
    DIVINER("diviner", listOf(ObjectiveKind.VISIT, ObjectiveKind.HUNT, ObjectiveKind.CLEAR)),
    BREWER("brewer", listOf(ObjectiveKind.GATHER, ObjectiveKind.DELIVER, ObjectiveKind.SLAY)),
    MASON("mason", listOf(ObjectiveKind.BUILD, ObjectiveKind.MINE, ObjectiveKind.CLEAR)),
}

/** How they put it. */
enum class Temper(val label: String, val opener: List<String>) {
    WARM("warm", listOf("Friend, I am glad you came.", "Sit a moment, traveller.", "Ah, a kind face.")),
    GRUFF("gruff", listOf("You. You look able.", "No time for greetings.", "Listen, and listen once.")),
    WEARY("weary", listOf("I have asked everyone else.", "Forgive me, I have not slept.", "It never ends, does it?")),
    PROUD("proud", listOf("This is beneath me, but here we are.", "My family has never asked twice.", "I would do it myself, of course.")),
    NERVOUS("nervous", listOf("Please, quietly —", "Is anyone listening? Good.", "I should not even be telling you.")),
    JOLLY("jolly", listOf("Ha! Just who I wanted.", "A fine day for a little trouble!", "You'll like this one.")),
    SOLEMN("solemn", listOf("The ancestors are watching this.", "This must be done properly.", "Hear me well.")),
    SHREWD("shrewd", listOf("I have a proposition.", "Let us talk business.", "There is profit in this, for both of us.")),
}

/** Why it matters to them. */
enum class Motive(val reason: String) {
    REVENGE("they took something from me"),
    HUNGER("the town will go hungry otherwise"),
    FESTIVAL("the festival is days away"),
    DEBT("I owe a debt I cannot pay"),
    CURIOSITY("I have to know what is out there"),
    PROTECTION("the children play near there"),
    HONOUR("my family's name depends on it"),
    TRADE("the caravans will not come otherwise"),
    HEALING("someone is sick and waiting"),
    OMEN("the diviner saw it in the signs"),
    RIVALRY("the next town is laughing at us"),
    MEMORY("my father did this every year"),
    SHELTER("the rains are coming"),
    WEDDING("my daughter marries at the new moon"),
    PENANCE("I made a promise I broke"),
    WONDER("I want to see it before I die"),
}

/** What the player has to do. Each is counted by the engine. */
enum class ObjectiveKind(val verb: String) {
    /** Kill a number of one kind of monster. */
    SLAY("slay"),
    /** Kill one strong monster: a champion or a boss of its kind. */
    HUNT("hunt"),
    /** Pick up a number of a foraged or broken block. */
    GATHER("gather"),
    /** Break a number of a stone or ore block. */
    MINE("mine"),
    /** Bring a number of a block to someone in another town. */
    DELIVER("deliver"),
    /** Reach a place and stand there a moment. */
    VISIT("visit"),
    /** Leave no hostile alive within a radius of a place. */
    CLEAR("clear"),
    /** Place a number of a block near the giver's town. */
    BUILD("build"),
}

/** What bends the job. Each changes the fight or the count, not only the telling. */
enum class Twist(val label: String, val phrase: String) {
    NONE("", ""),
    ELITE("Elite", "and they are stronger than they look"),
    SWARM("Swarm", "and there are more of them than anyone said"),
    NIGHT("Night", "and they only come out after dark"),
    FLEEING("Fleeing", "and they run when they are hurt"),
    GUARDED("Guarded", "and something guards them"),
    RIVAL("Rival", "and another hunter is after the same prize"),
    CURSED("Cursed", "and whoever touches them carries a curse a while"),
    FAR("Far", "and it is further than it sounds"),
    STORM("Storm", "and the weather will turn against you"),
}

/** A condition on doing it; failing it fails the quest. */
enum class Constraint(val label: String, val phrase: String) {
    NONE("", ""),
    BEFORE_NIGHT("Before nightfall", "Be back before nightfall."),
    UNTOUCHED("Untouched", "Do not let them so much as scratch you."),
    NO_FIRE("No fire", "No fire — it would ruin everything."),
    QUICK("Quickly", "Be quick about it."),
    QUIET("Quietly", "Do not wake the whole valley."),
    ALONE("Alone", "Go alone; followers would only frighten them."),
}

/** What they give. */
enum class RewardKind(val label: String) {
    COIN("coin"), GEAR("a piece of gear"), STANDING("the town's standing"), ATTACK("a forged attack"),
    MAP("a map to something hidden"), FEAST("a feast in your honour"), TRAINING("training"), CHARM("a charm"),
}

/** A place, relative to the giver's town. */
data class QuestPlace(val dx: Int, val dy: Int, val radius: Int, val name: String)

/** A giver: one townsperson, the same every time the town is visited. */
data class Townsperson(val id: String, val name: String, val trade: Trade, val temper: Temper, val townId: String)

/** The objective with its target, all the engine needs to count it. */
data class QuestObjective(
    val kind: ObjectiveKind,
    /** A monster definition (SLAY, HUNT), a block (GATHER, MINE, DELIVER, BUILD), or null. */
    val targetId: String? = null,
    /** Shown in the text: "sand vipers", "red earth". */
    val targetName: String = "",
    /** A monster role to count instead of a definition, when the world has no fitting monster. */
    val role: CombatRole? = null,
    val minRank: EnemyRank = EnemyRank.MINION,
    val count: Int = 1,
    /** Where, for VISIT, CLEAR, BUILD and SLAY-near; null anywhere. */
    val place: QuestPlace? = null,
    /** For DELIVER: the town it goes to. */
    val toTownId: String? = null,
    val toTownName: String = "",
)

data class QuestReward(val kind: RewardKind, val experience: Int, val coin: Int)

/** One quest, as a board shows it and a log keeps it. */
data class Quest(
    val id: String,
    val giver: Townsperson,
    val motive: Motive,
    val objective: QuestObjective,
    val twist: Twist,
    val constraint: Constraint,
    val reward: QuestReward,
    /** Seconds allowed, for time constraints; 0 is untimed. */
    val timeLimit: Float = 0f,
    /** The quest this one follows on from, in a chain. */
    val follows: String? = null,
    val seed: Long,
) {
    val title: String get() = QuestText.title(this)
    val pitch: String get() = QuestText.pitch(this)
    val goal: String get() = QuestText.goal(this)

    /** What makes this quest itself rather than another: equal fingerprints play the same. */
    val fingerprint: String get() =
        "${objective.kind}|${objective.targetId ?: objective.role}|${objective.minRank}|${objective.count}|${objective.place?.let { "${it.dx},${it.dy}" }}|$twist|$constraint|${reward.kind}"
}

/** What a world offers the generator to aim quests at. */
data class QuestWorld(
    /** Monsters that roam this region: id, name and role. */
    val monsters: List<QuestTarget>,
    /** Blocks that can be gathered: foliage, soil, crops. */
    val gatherables: List<QuestTarget>,
    /** Blocks that can be mined: stone and ore. */
    val mineables: List<QuestTarget>,
    /** Blocks that can be built with. */
    val buildables: List<QuestTarget>,
    /** Other towns a delivery can go to: id and name. */
    val towns: List<QuestTarget>,
)

data class QuestTarget(val id: String, val name: String, val role: CombatRole? = null)

/**
 * Makes quests from a seed. Pure: the same town, day and seed always give the
 * same board, on every device.
 */
object QuestGenerator {

    /** The people of a town who give work, the same on every visit. */
    fun residents(townId: String, townName: String, seed: Long, count: Int): List<Townsperson> {
        val r = Random(seed xor townId.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())
        val trades = Trade.entries.shuffled(r)
        return List(count) { i ->
            Townsperson(
                id = "$townId/p$i", name = Names.person(r), trade = trades[i % trades.size],
                temper = Temper.entries[r.nextInt(Temper.entries.size)], townId = townId,
            )
        }
    }

    /**
     * A town's board: [count] quests from its [givers], none sharing both an
     * objective and a twist, and none sharing a fingerprint.
     *
     * @param difficulty 0 errands .. 3 legendary: counts, ranks, distances and rewards.
     * @param day the world day: boards refresh each morning.
     */
    fun board(
        givers: List<Townsperson>, world: QuestWorld, seed: Long, day: Int, count: Int, difficulty: Int = 1,
        playerLevel: Int = 1, exclude: Set<String> = emptySet(),
    ): List<Quest> {
        if (givers.isEmpty()) return emptyList()
        val r = Random(seed * 31 + day * 7919L + givers.first().townId.hashCode())
        val out = ArrayList<Quest>(count)
        val pairs = HashSet<Pair<ObjectiveKind, Twist>>()
        val prints = HashSet<String>(exclude)
        var tries = 0
        while (out.size < count && tries++ < count * 40) {
            val giver = givers[r.nextInt(givers.size)]
            val q = quest(giver, world, r.nextLong(), difficulty, playerLevel) ?: continue
            if (!pairs.add(q.objective.kind to q.twist) && q.twist != Twist.NONE) continue
            if (!prints.add(q.fingerprint)) continue
            out += q
        }
        return out
    }

    /** The quest that follows [done] in a chain: the same giver, harder, and somewhere else. */
    fun followUp(done: Quest, world: QuestWorld, difficulty: Int, playerLevel: Int): Quest? =
        quest(done.giver, world, done.seed * 6364136223846793005L + 1, (difficulty + 1).coerceAtMost(3), playerLevel)?.copy(follows = done.id)

    /** One quest from [giver]; null when the world has nothing their trade could ask for. */
    fun quest(giver: Townsperson, world: QuestWorld, seed: Long, difficulty: Int = 1, playerLevel: Int = 1): Quest? {
        val r = Random(seed)
        // Mostly what their trade needs, now and then anything at all.
        val kinds = if (r.nextFloat() < 0.8f) giver.trade.needs else ObjectiveKind.entries
        val objective = kinds.shuffled(r).firstNotNullOfOrNull { objective(it, world, r, difficulty) } ?: return null
        val twist = twistFor(objective.kind, r)
        val constraint = constraintFor(objective.kind, twist, r)
        val effort = effort(objective, twist, constraint)
        val reward = QuestReward(
            kind = RewardKind.entries[r.nextInt(RewardKind.entries.size)],
            experience = (40 * effort * (1 + 0.15f * (playerLevel - 1))).toInt(),
            coin = (12 * effort).toInt(),
        )
        val time = when (constraint) {
            Constraint.QUICK -> 120f + 60f * difficulty
            Constraint.BEFORE_NIGHT -> 300f
            else -> 0f
        }
        return Quest(
            id = "q:${giver.id}:${java.lang.Long.toHexString(seed and 0xFFFFFFFFL)}",
            giver = giver, motive = Motive.entries[r.nextInt(Motive.entries.size)], objective = objective,
            twist = twist, constraint = constraint, reward = reward, timeLimit = time, seed = seed,
        )
    }

    private fun objective(kind: ObjectiveKind, w: QuestWorld, r: Random, difficulty: Int): QuestObjective? {
        val d = difficulty.coerceIn(0, 3)
        fun place(minR: Int, maxR: Int): QuestPlace {
            val dist = minR + r.nextInt(maxR - minR + 1) + d * 12
            val a = r.nextDouble() * Math.PI * 2
            return QuestPlace((kotlin.math.cos(a) * dist).toInt(), (kotlin.math.sin(a) * dist).toInt(), 6 + r.nextInt(5), Names.place(r))
        }
        return when (kind) {
            ObjectiveKind.SLAY -> w.monsters.randomOrNull(r)?.let { m ->
                QuestObjective(kind, m.id, m.name, m.role, count = 3 + r.nextInt(4) + d * 3, place = if (r.nextBoolean()) place(30, 60) else null)
            }
            ObjectiveKind.HUNT -> w.monsters.randomOrNull(r)?.let { m ->
                QuestObjective(kind, m.id, m.name, m.role, minRank = if (d >= 2) EnemyRank.BOSS else EnemyRank.CHAMPION, count = 1, place = place(40, 80))
            }
            ObjectiveKind.GATHER -> w.gatherables.randomOrNull(r)?.let { b -> QuestObjective(kind, b.id, b.name, count = 6 + r.nextInt(8) + d * 6) }
            ObjectiveKind.MINE -> w.mineables.randomOrNull(r)?.let { b -> QuestObjective(kind, b.id, b.name, count = 5 + r.nextInt(8) + d * 6) }
            ObjectiveKind.DELIVER -> {
                val to = w.towns.randomOrNull(r) ?: return null
                val what = (w.gatherables + w.mineables).randomOrNull(r) ?: return null
                QuestObjective(kind, what.id, what.name, count = 4 + r.nextInt(6) + d * 3, toTownId = to.id, toTownName = to.name)
            }
            ObjectiveKind.VISIT -> QuestObjective(kind, count = 1, place = place(50, 120))
            ObjectiveKind.CLEAR -> QuestObjective(kind, count = 1, place = place(30, 70).let { it.copy(radius = it.radius + 6) })
            ObjectiveKind.BUILD -> w.buildables.randomOrNull(r)?.let { b -> QuestObjective(kind, b.id, b.name, count = 8 + r.nextInt(10) + d * 8, place = QuestPlace(0, 0, 24, "the town")) }
        }
    }

    private fun twistFor(kind: ObjectiveKind, r: Random): Twist {
        if (r.nextFloat() < 0.25f) return Twist.NONE
        val fits = when (kind) {
            ObjectiveKind.SLAY -> listOf(Twist.ELITE, Twist.SWARM, Twist.NIGHT, Twist.FLEEING, Twist.CURSED, Twist.RIVAL)
            ObjectiveKind.HUNT -> listOf(Twist.GUARDED, Twist.NIGHT, Twist.FLEEING, Twist.RIVAL, Twist.FAR, Twist.STORM)
            ObjectiveKind.CLEAR -> listOf(Twist.SWARM, Twist.GUARDED, Twist.ELITE, Twist.NIGHT, Twist.STORM)
            ObjectiveKind.GATHER, ObjectiveKind.MINE -> listOf(Twist.GUARDED, Twist.FAR, Twist.STORM, Twist.CURSED, Twist.NIGHT)
            ObjectiveKind.DELIVER -> listOf(Twist.FAR, Twist.STORM, Twist.GUARDED, Twist.RIVAL)
            ObjectiveKind.VISIT -> listOf(Twist.FAR, Twist.GUARDED, Twist.NIGHT, Twist.STORM, Twist.CURSED)
            ObjectiveKind.BUILD -> listOf(Twist.STORM, Twist.GUARDED, Twist.RIVAL, Twist.NIGHT)
        }
        return fits[r.nextInt(fits.size)]
    }

    private fun constraintFor(kind: ObjectiveKind, twist: Twist, r: Random): Constraint {
        if (r.nextFloat() < 0.4f) return Constraint.NONE
        val fits = buildList {
            add(Constraint.QUICK)
            if (twist != Twist.NIGHT) add(Constraint.BEFORE_NIGHT)
            if (kind in setOf(ObjectiveKind.SLAY, ObjectiveKind.HUNT, ObjectiveKind.CLEAR)) { add(Constraint.UNTOUCHED); add(Constraint.NO_FIRE); add(Constraint.ALONE) }
            if (kind in setOf(ObjectiveKind.VISIT, ObjectiveKind.GATHER, ObjectiveKind.DELIVER)) { add(Constraint.QUIET); add(Constraint.UNTOUCHED) }
            if (kind == ObjectiveKind.MINE || kind == ObjectiveKind.BUILD) add(Constraint.QUIET)
        }
        return fits[r.nextInt(fits.size)]
    }

    /** How much work a quest is, which its reward follows. */
    fun effort(o: QuestObjective, twist: Twist, constraint: Constraint): Float {
        val base = when (o.kind) {
            ObjectiveKind.SLAY -> 0.6f + 0.25f * o.count
            ObjectiveKind.HUNT -> if (o.minRank == EnemyRank.BOSS) 6f else 3.5f
            ObjectiveKind.GATHER, ObjectiveKind.MINE -> 0.8f + 0.12f * o.count
            ObjectiveKind.DELIVER -> 1.5f + 0.15f * o.count
            ObjectiveKind.VISIT -> 1.2f + (o.place?.let { kotlin.math.hypot(it.dx.toFloat(), it.dy.toFloat()) } ?: 0f) / 60f
            ObjectiveKind.CLEAR -> 3f
            ObjectiveKind.BUILD -> 1f + 0.1f * o.count
        }
        val twistBonus = if (twist == Twist.NONE) 1f else 1.35f
        val constraintBonus = if (constraint == Constraint.NONE) 1f else 1.3f
        return base * twistBonus * constraintBonus
    }

    /**
     * How many distinct quests the grammar can make for a world with [monsters]
     * monster kinds, [blocks] block kinds and [towns] towns, counting only parts
     * that change play: objective and target, count, rank, place, twist,
     * constraint and reward.
     */
    fun varieties(monsters: Int, blocks: Int, towns: Int): BigInteger {
        fun n(x: Int) = BigInteger.valueOf(x.toLong())
        val places = n(8 * 4) // eight bearings at four distance bands that play differently
        val objectives = n(monsters) * (n(4 * 4) * (places + BigInteger.ONE)) + // slay: counts x difficulty, near a place or anywhere
            n(monsters) * n(2) * places + // hunt: champion or boss, somewhere
            n(blocks) * n(8 * 4) * n(2) + // gather and mine
            n(blocks) * n(towns.coerceAtLeast(1)) * n(6 * 4) + // deliver
            places * n(2) + // visit and clear
            n(blocks) * n(10 * 4) // build
        val twists = n(Twist.entries.size)
        val constraints = n(Constraint.entries.size)
        val rewards = n(RewardKind.entries.size)
        val givers = n(Trade.entries.size * Temper.entries.size)
        return objectives * twists * constraints * rewards * givers
    }

    private fun <T> List<T>.randomOrNull(r: Random): T? = if (isEmpty()) null else this[r.nextInt(size)]
}

/** Names for people and places, built from syllables so a world never runs out. */
object Names {
    private val first = listOf("A", "Chi", "Ngo", "O", "E", "Nne", "Uche", "Ike", "Obi", "Ada", "Eze", "Ama", "Nka", "Ife", "Uzo", "Kene", "Ebu", "Oge")
    private val middle = listOf("", "di", "ka", "nwa", "lu", "ra", "me", "bu", "fu", "go", "zi", "na", "ri", "che")
    private val last = listOf("ma", "nna", "ka", "ze", "chi", "dike", "lu", "ne", "ro", "obi", "nwa", "mma", "dinma", "kwe")
    private val placeA = listOf("Red", "Old", "Hollow", "Twin", "Crooked", "Silent", "Burnt", "High", "Sunken", "Bright", "Bitter", "Long")
    private val placeB = listOf("Ridge", "Pool", "Grove", "Stones", "Ford", "Hill", "Pit", "Shrine", "Crossing", "Termite Mound", "Baobab", "Clearing", "Gully", "Spring")

    fun person(r: Random): String = (first.random(r) + middle.random(r) + last.random(r)).replaceFirstChar(Char::uppercaseChar)

    fun place(r: Random): String = "the ${placeA.random(r)} ${placeB.random(r)}"
}

/** How a quest reads, from its parts. */
object QuestText {
    fun title(q: Quest): String {
        val o = q.objective
        val core = when (o.kind) {
            ObjectiveKind.SLAY -> "${o.count} ${o.targetName}"
            ObjectiveKind.HUNT -> "The ${if (o.minRank == EnemyRank.BOSS) "great" else "old"} ${o.targetName}"
            ObjectiveKind.GATHER -> "${o.targetName.replaceFirstChar(Char::uppercaseChar)} for ${q.giver.name}"
            ObjectiveKind.MINE -> "${o.targetName.replaceFirstChar(Char::uppercaseChar)} from the ground"
            ObjectiveKind.DELIVER -> "To ${o.toTownName}"
            ObjectiveKind.VISIT -> o.place?.name?.replaceFirstChar(Char::uppercaseChar) ?: "A long walk"
            ObjectiveKind.CLEAR -> "Clear ${o.place?.name ?: "the way"}"
            ObjectiveKind.BUILD -> "${q.giver.name}'s wall"
        }
        val tag = listOf(q.twist.label, q.constraint.label).filter(String::isNotEmpty).joinToString(", ")
        return if (tag.isEmpty()) core else "$core ($tag)"
    }

    fun goal(q: Quest): String {
        val o = q.objective
        val where = o.place?.let { " near ${it.name}" }.orEmpty()
        return when (o.kind) {
            ObjectiveKind.SLAY -> "Slay ${o.count} ${o.targetName}$where."
            ObjectiveKind.HUNT -> "Hunt down a ${o.minRank.name.lowercase()} ${o.targetName}$where."
            ObjectiveKind.GATHER -> "Gather ${o.count} ${o.targetName}."
            ObjectiveKind.MINE -> "Mine ${o.count} ${o.targetName}."
            ObjectiveKind.DELIVER -> "Carry ${o.count} ${o.targetName} to ${o.toTownName}."
            ObjectiveKind.VISIT -> "Reach ${o.place?.name ?: "the place"} and look around."
            ObjectiveKind.CLEAR -> "Leave nothing hostile alive at ${o.place?.name ?: "the place"}."
            ObjectiveKind.BUILD -> "Place ${o.count} ${o.targetName} in the town."
        }
    }

    fun pitch(q: Quest): String {
        val r = Random(q.seed)
        val opener = q.giver.temper.opener.random(r)
        val ask = goal(q).removeSuffix(".").replaceFirstChar(Char::lowercaseChar)
        val twist = q.twist.phrase.takeIf(String::isNotEmpty)?.let { ", $it" }.orEmpty()
        val constraint = q.constraint.phrase.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
        return "$opener I need you to $ask$twist — ${q.motive.reason}.$constraint I can offer ${q.reward.kind.label}."
    }
}
