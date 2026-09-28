package com.stratum.engine.world

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.KeyedTrigger
import com.stratum.core.domain.combat.TriggerEvent
import kotlin.random.Random

/**
 * Decides which triggers fire, and refuses the ones that would loop forever.
 *
 * Three guards, all enforced here and nowhere else, so no path around them
 * exists:
 *
 * - **Depth.** Every cast carries how many triggers deep it is. A cast at the
 *   world's [CombatRules.triggerDepth] triggers nothing, so "on hit, cast a
 *   nova that hits" stops after a few generations however it is written.
 * - **Internal cooldown.** Each trigger waits its own cooldown between
 *   firings, never less than the world's floor, so a trigger cannot fire
 *   twice for the same instant even across different branches.
 * - **Budget.** One action -- a swing, a cast, a tick -- may cause at most
 *   [CombatRules.triggerBudget] triggered casts in total, so even a wide,
 *   shallow tree (ten projectiles, each triggering ten more) is bounded.
 *
 * Depth and cooldown are what make broken builds possible without making
 * them fatal to the phone; the budget is the backstop for the cases nobody
 * thought of.
 */
internal class TriggerEngine(private val rules: CombatRules) {

    private val cooldowns = HashMap<String, Float>()
    private var spent = 0

    /** How many triggered casts the current action has caused. */
    val spentThisAction: Int get() = spent

    /** Starts a new top-level action: the budget refills. */
    fun beginAction() {
        spent = 0
    }

    fun advance(deltaSeconds: Float) {
        val iterator = cooldowns.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val left = entry.value - deltaSeconds
            if (left <= 0f) iterator.remove() else entry.setValue(left)
        }
    }

    fun isCooling(key: String): Boolean = (cooldowns[key] ?: 0f) > 0f

    /**
     * The triggers among [candidates] that fire for [event], in order, each
     * already charged its cooldown and its share of the budget.
     *
     * [depth] is the depth of the cast that caused the event; [tags] are its
     * skill's tags, for triggers that only answer some skills.
     */
    fun fire(event: TriggerEvent, candidates: List<KeyedTrigger>, depth: Int, tags: Set<String>, random: Random): List<KeyedTrigger> {
        if (depth >= rules.triggerDepth) return emptyList()
        return candidates.filter { keyed ->
            val trigger = keyed.trigger
            if (trigger.event != event) return@filter false
            if (trigger.requiresTags.isNotEmpty() && trigger.requiresTags.none { it in tags }) return@filter false
            if (isCooling(keyed.key)) return@filter false
            if (spent >= rules.triggerBudget) return@filter false
            // Rolled only once everything else allows it, so a trigger that could not fire spends no randomness.
            if (trigger.chance < 1f && random.nextFloat() >= trigger.chance) return@filter false
            cooldowns[keyed.key] = maxOf(trigger.cooldownSeconds, rules.triggerCooldownFloor)
            spent++
            true
        }
    }

    /**
     * Whether a cast at [depth] may cause another -- the explosion a
     * projectile leaves. It obeys the same depth and budget as a trigger,
     * since a skill that casts itself is a trigger by another name.
     */
    fun allowChainedCast(depth: Int): Boolean {
        if (depth >= rules.triggerDepth || spent >= rules.triggerBudget) return false
        spent++
        return true
    }

    fun clear() {
        cooldowns.clear()
        spent = 0
    }
}
