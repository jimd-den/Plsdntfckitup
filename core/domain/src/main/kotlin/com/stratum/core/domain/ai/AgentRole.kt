package com.stratum.core.domain.ai

/**
 * One member of the studio crew: a loremaster, a cartographer, an architect.
 * Each writes some sections of a content pack, after the roles it depends on
 * have written theirs, so the bestiary can put monsters in the loremaster's
 * factions and the architect can garrison towns with them.
 *
 * Plain data, so a plugin can bring its own crew -- a Warhammer pack might add
 * a "chapter master" who writes only factions and units, with a brief about
 * chapters and heresy -- and nobody writes code to do it.
 */
data class AgentRoleDefinition(
    val id: String,
    val name: String,
    val glyph: String = "✎",
    /** What this role is for, in a sentence the person reading the journal understands. */
    val description: String = "",
    /** Pack sections it writes, by their plugin JSON names: `factions`, `enemies`, `settlements`... */
    val sections: List<String>,
    /** Roles whose work must be in the draft before this one starts. */
    val dependsOn: List<String> = emptyList(),
    /** Standing instructions, added to every prompt this role sends. */
    val brief: String = "",
    /** Stop for a person to approve, revise or skip the result before going on. */
    val requiresApproval: Boolean = false,
    /** Tries per step, the later ones told what was wrong with the earlier. */
    val maxAttempts: Int = 3,
    val temperature: Float = 0.8f,
) {
    init {
        require(sections.isNotEmpty()) { "agent role '$id' writes no sections" }
        require(maxAttempts in 1..MAX_ATTEMPTS) { "agent role '$id' maxAttempts $maxAttempts is outside 1..$MAX_ATTEMPTS" }
    }

    companion object {
        const val MAX_ATTEMPTS = 6
    }
}

/** The order a crew works in, or why it cannot work at all. */
sealed interface CrewPlan {
    data class Ordered(val roles: List<AgentRoleDefinition>) : CrewPlan
    data class Invalid(val problems: List<String>) : CrewPlan

    companion object {

        /**
         * Orders roles so each comes after everything it depends on, keeping
         * the given order where dependencies allow. Unknown dependencies and
         * cycles are problems, named so a pack author can fix them.
         */
        fun of(roles: List<AgentRoleDefinition>): CrewPlan {
            val byId = roles.associateBy { it.id }
            val problems = duplicates(roles) + roles.flatMap { role ->
                role.dependsOn.filter { it !in byId }.map { "agent role '${role.id}' depends on unknown role '$it'" }
            }
            if (problems.isNotEmpty()) return Invalid(problems)
            val ordered = LinkedHashMap<String, AgentRoleDefinition>()
            var remaining = roles
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { role -> role.dependsOn.all { it in ordered } }
                if (ready.isEmpty()) return Invalid(listOf("agent roles depend on each other in a circle: " + remaining.joinToString { it.id }))
                ready.forEach { ordered[it.id] = it }
                remaining = remaining - ready.toSet()
            }
            return Ordered(ordered.values.toList())
        }

        private fun duplicates(roles: List<AgentRoleDefinition>): List<String> =
            roles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "agent role '$it' is defined twice" }
    }
}
