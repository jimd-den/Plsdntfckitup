package com.stratum.engine.world

import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.session.PlayerState

/**
 * The satchel and the anvil: equipping, and slotting inserts into sockets.
 *
 * Pure rules over [PlayerState]. Each returns the new player with its result;
 * the session decides what else happens around it.
 */
internal class PlayerGear(private val insertOf: (String) -> InsertDefinition?) {

    data class Changed<R>(val player: PlayerState, val result: R)

    /**
     * Equips something from the bag. What was held goes back into the bag
     * rather than being destroyed, so a swap is always reversible.
     */
    fun equip(player: PlayerState, instanceId: String): Changed<EquipResult> {
        val item = player.bag.firstOrNull { it.instanceId == instanceId } ?: return Changed(player, EquipResult.NotInBag)
        return Changed(clampHealth(player.equipping(item)), EquipResult.Equipped(item, player.equippedWeapon))
    }

    /**
     * Slots one of the player's inserts into an item they are holding. Spends
     * the insert from the pouch and writes the item back wherever it lives, so
     * an equipped weapon changes under the player's hand and a bagged one
     * stays bagged.
     */
    fun slot(player: PlayerState, instanceId: String, insertId: String): Changed<SocketResult> {
        fun refuse(result: SocketResult) = Changed(player, result)
        val item = player.itemById(instanceId) ?: return refuse(SocketResult.NoSuchItem)
        if (insertOf(insertId) == null) return refuse(SocketResult.NoSuchInsert)
        val spent = player.consumingInsert(insertId) ?: return refuse(SocketResult.NoneHeld)
        val sockets = item.sockets.slotting(insertId) ?: return refuse(SocketResult.NoFreeSocket)
        val updated = item.copy(sockets = sockets)
        return Changed(clampHealth(spent.replacing(updated)), SocketResult.Slotted(updated, insertId))
    }

    /**
     * Pulls an insert back out. It returns to the pouch intact, which is the
     * whole point of sockets over fusing: a decision you can take back.
     */
    fun unslot(player: PlayerState, instanceId: String, socketIndex: Int): Changed<SocketResult> {
        val item = player.itemById(instanceId) ?: return Changed(player, SocketResult.NoSuchItem)
        val (sockets, insertId) = item.sockets.unslotting(socketIndex) ?: return Changed(player, SocketResult.EmptySocket)
        val updated = item.copy(sockets = sockets)
        return Changed(clampHealth(player.replacing(updated).withInsert(insertId)), SocketResult.Unslotted(updated, insertId))
    }

    /**
     * Inserts can carry health, so the ceiling moves on a swap. Clamped rather
     * than leaving the player reading more health than they can have.
     */
    fun clampHealth(player: PlayerState): PlayerState =
        player.copy(health = player.health.coerceAtMost(player.maxHealthWith(insertOf)))
}
