package com.stratum.core.domain.passive

import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet

/** What a node is for, which is also how big the tree screen draws it. */
enum class PassiveKind {
    /** Where a class begins. Free, and never refunded. */
    START,

    /** A small step: one modest modifier. Most of the tree. */
    SMALL,

    /** A destination: several strong modifiers, worth pathing toward. */
    NOTABLE,

    /** A rule change: a big "more" paid for with a "less". Builds are named after these. */
    KEYSTONE,
}

data class PassiveNode(
    val id: String,
    val name: String,
    val kind: PassiveKind = PassiveKind.SMALL,
    val modifiers: List<StatModifier> = emptyList(),
    /** Where the tree screen draws it, in tree units. Only the layout reads these. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** For [PassiveKind.START]: the classes that begin here, or empty for any class. */
    val classIds: List<String> = emptyList(),
    /** Flavour, for a keystone's tooltip. */
    val description: String = "",
)

/** An undirected edge: either end can be allocated from the other. */
data class PassiveLink(val from: String, val to: String)

/**
 * A graph of passive skills, Path of Exile style: every node is a small
 * permanent bonus, and a node can only be taken next to one already taken, so
 * where a character is going decides what they pass through on the way.
 *
 * Pure data a pack can ship. The rules for walking it are in [PassiveBuild].
 */
data class PassiveTree(
    val id: String,
    val name: String,
    val nodes: List<PassiveNode>,
    val links: List<PassiveLink>,
) {
    private val byId: Map<String, PassiveNode> = nodes.associateBy { it.id }

    private val neighbours: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        links.forEach { link ->
            getOrPut(link.from) { LinkedHashSet() } += link.to
            getOrPut(link.to) { LinkedHashSet() } += link.from
        }
    }

    val starts: List<PassiveNode> get() = nodes.filter { it.kind == PassiveKind.START }

    fun node(id: String): PassiveNode? = byId[id]

    fun neighboursOf(id: String): Set<String> = neighbours[id].orEmpty()

    /** Where [classId] begins: its own start, else one open to any class, else the first. */
    fun startFor(classId: String?): PassiveNode? =
        starts.firstOrNull { classId != null && classId in it.classIds }
            ?: starts.firstOrNull { it.classIds.isEmpty() }
            ?: starts.firstOrNull()

    /** What is wrong with this tree, for load-time validation. Empty when it is playable. */
    fun problems(): List<String> {
        val duplicates = nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys
            .map { "passive tree '$id' has two nodes with id '$it'" }
        val dangling = links.flatMap { listOf(it.from, it.to) }.filter { it !in byId }.distinct()
            .map { "passive tree '$id' links unknown node '$it'" }
        val loops = links.filter { it.from == it.to }.map { "passive tree '$id' links node '${it.from}' to itself" }
        val noStart = if (starts.isEmpty()) listOf("passive tree '$id' has no start node") else emptyList()
        return duplicates + dangling + loops + noStart
    }
}

/**
 * One character's allocation on a tree, and the rules for changing it.
 *
 * Immutable: every change returns a new build, so the session can check a
 * change before committing it and the UI can preview a path without owning
 * any state. The start node is always held and costs nothing; [allocated] is
 * only what points were spent on.
 */
data class PassiveBuild(
    val tree: PassiveTree,
    val startId: String,
    val allocated: Set<String> = emptySet(),
) {
    val pointsSpent: Int get() = allocated.size

    fun isAllocated(nodeId: String): Boolean = nodeId == startId || nodeId in allocated

    /** A node can be taken when it touches something already taken. Other classes' starts never can. */
    fun canAllocate(nodeId: String): Boolean {
        val node = tree.node(nodeId) ?: return false
        return node.kind != PassiveKind.START && !isAllocated(nodeId) && tree.neighboursOf(nodeId).any(::isAllocated)
    }

    fun allocating(nodeId: String): PassiveBuild? = if (canAllocate(nodeId)) copy(allocated = allocated + nodeId) else null

    /** A node can be given back when everything else taken still reaches the start without it. */
    fun canRefund(nodeId: String): Boolean {
        if (nodeId !in allocated) return false
        val remaining = allocated - nodeId
        return reachableFromStart(remaining).containsAll(remaining)
    }

    fun refunding(nodeId: String): PassiveBuild? = if (canRefund(nodeId)) copy(allocated = allocated - nodeId) else null

    /**
     * The cheapest run of nodes that ends at [targetId], in the order they
     * must be taken, or null when it cannot be reached. Empty when it is
     * already held.
     *
     * This is what makes a tree of hundreds of nodes playable on a phone: tap
     * the notable you want and the path lights up, rather than tapping every
     * small node on the way with a thumb.
     */
    fun pathTo(targetId: String): List<String>? {
        val target = tree.node(targetId) ?: return null
        if (isAllocated(targetId)) return emptyList()
        if (target.kind == PassiveKind.START) return null
        val cameFrom = HashMap<String, String>()
        val frontier = ArrayDeque(allocated + startId)
        val seen = HashSet(frontier)
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            for (next in tree.neighboursOf(current)) {
                if (next in seen || tree.node(next)?.kind == PassiveKind.START) continue
                seen += next
                cameFrom[next] = current
                if (next == targetId) return walkBack(targetId, cameFrom)
                frontier.addLast(next)
            }
        }
        return null
    }

    /** Takes the whole of [path], or returns null if any step of it cannot be taken. */
    fun allocatingPath(path: List<String>): PassiveBuild? =
        path.fold(this as PassiveBuild?) { build, nodeId -> build?.allocating(nodeId) }

    val nodes: List<PassiveNode> get() = (allocated + startId).mapNotNull(tree::node)

    val modifiers: List<StatModifier> get() = nodes.flatMap { it.modifiers }

    val sheet: StatSheet get() = StatSheet(modifiers)

    /**
     * The same build with anything that no longer fits dropped: nodes a tree
     * update removed, and nodes cut off from the start by that. A character
     * saved against last month's version of a plugin still loads.
     */
    fun pruned(): PassiveBuild {
        val known = allocated.filterTo(HashSet()) { tree.node(it)?.let { node -> node.kind != PassiveKind.START } == true }
        return copy(allocated = reachableFromStart(known).intersect(known))
    }

    private fun reachableFromStart(held: Set<String>): Set<String> {
        val reached = hashSetOf(startId)
        val frontier = ArrayDeque(listOf(startId))
        while (frontier.isNotEmpty()) {
            tree.neighboursOf(frontier.removeFirst()).filter { it in held && reached.add(it) }.forEach(frontier::addLast)
        }
        return reached - startId
    }

    private fun walkBack(targetId: String, cameFrom: Map<String, String>): List<String> {
        val path = ArrayDeque<String>()
        var current: String? = targetId
        while (current != null && !isAllocated(current)) {
            path.addFirst(current)
            current = cameFrom[current]
        }
        return path.toList()
    }

    companion object {
        /** A fresh build for [classId], or null when the tree has nowhere for anyone to start. */
        fun startingOn(tree: PassiveTree, classId: String?): PassiveBuild? =
            tree.startFor(classId)?.let { PassiveBuild(tree, it.id) }
    }
}
