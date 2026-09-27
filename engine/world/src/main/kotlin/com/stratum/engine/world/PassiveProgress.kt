package com.stratum.engine.world

import com.stratum.core.domain.passive.PassiveBuild
import com.stratum.core.domain.passive.PassiveNode
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.StatSheet

/** What happened when the player tapped a node on the tree. */
sealed interface PassiveResult {
    /** Every node taken, in path order: one for a neighbour, several for a far tap. */
    data class Allocated(val nodes: List<PassiveNode>) : PassiveResult

    data class Refunded(val node: PassiveNode) : PassiveResult

    data class NotEnoughPoints(val needed: Int, val available: Int) : PassiveResult

    /** Nothing taken touches it, or it is another class's start. */
    data object Unreachable : PassiveResult

    /** Refunding it would cut other taken nodes off from the start. */
    data object HoldsOthers : PassiveResult

    data object AlreadyTaken : PassiveResult

    data object NoTree : PassiveResult
}

/**
 * The player's passive tree: spending points, giving them back, and keeping
 * the player's resolved modifiers in step with what is taken.
 *
 * Refunds are free. Path of Exile charges for them, which mostly teaches
 * players to follow a guide; letting people try a keystone and change their
 * mind is how a build becomes theirs.
 */
internal class PassiveProgress(private val tree: PassiveTree?) {

    fun buildFor(player: PlayerState): PassiveBuild? =
        tree?.let { PassiveBuild.startingOn(it, player.heroClassId) }?.copy(allocated = player.passives)

    /** Takes [nodeId], and every node on the cheapest path to it. */
    fun allocate(player: PlayerState, nodeId: String): Pair<PlayerState, PassiveResult> {
        val build = buildFor(player) ?: return player to PassiveResult.NoTree
        if (build.isAllocated(nodeId)) return player to PassiveResult.AlreadyTaken
        val path = build.pathTo(nodeId) ?: return player to PassiveResult.Unreachable
        if (path.size > player.unspentPassivePoints) return player to PassiveResult.NotEnoughPoints(path.size, player.unspentPassivePoints)
        val taken = build.allocatingPath(path) ?: return player to PassiveResult.Unreachable
        return rebuilt(player, taken) to PassiveResult.Allocated(path.mapNotNull(build.tree::node))
    }

    fun refund(player: PlayerState, nodeId: String): Pair<PlayerState, PassiveResult> {
        val build = buildFor(player) ?: return player to PassiveResult.NoTree
        val node = build.tree.node(nodeId) ?: return player to PassiveResult.Unreachable
        val kept = build.refunding(nodeId) ?: return player to PassiveResult.HoldsOthers
        return rebuilt(player, kept) to PassiveResult.Refunded(node)
    }

    /**
     * The player with their allocation checked against the tree loaded now
     * and their modifiers resolved from it. Run when a character arrives in a
     * world, since the tree may have changed since they last played.
     */
    fun settle(player: PlayerState): PlayerState = buildFor(player)?.let { rebuilt(player, it.pruned()) } ?: player.copy(passives = emptySet(), build = StatSheet.EMPTY)

    /** Health and resource are capped to the new ceilings, so refunding a life node takes the life with it. */
    private fun rebuilt(player: PlayerState, build: PassiveBuild): PlayerState {
        val updated = player.copy(passives = build.allocated, build = build.sheet)
        return updated.copy(
            health = updated.health.coerceAtMost(updated.maxHealthWithGear),
            resource = updated.resource.coerceAtMost(updated.resourceCeiling),
        )
    }
}
