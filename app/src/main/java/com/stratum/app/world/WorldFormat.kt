package com.stratum.app.world

import com.stratum.core.domain.session.WorldSummary

/**
 * How a saved world is described on a card: short, human numbers rather than
 * timestamps. Pure functions of their inputs, with the clock handed in, so a
 * card reads the same in a screenshot every time it is taken.
 */
object WorldFormat {

    /** "4h 12m", "35m", "under a minute". */
    fun playTime(seconds: Long): String {
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            else -> "under a minute"
        }
    }

    /** "just now", "12 minutes ago", "yesterday", "5 days ago", "3 months ago". */
    fun lastPlayed(at: Long, now: Long): String {
        val minutes = ((now - at).coerceAtLeast(0)) / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> plural(minutes, "minute") + " ago"
            hours < 24 -> plural(hours, "hour") + " ago"
            days == 1L -> "yesterday"
            days < 31 -> "$days days ago"
            days < 365 -> plural(days / 30, "month") + " ago"
            else -> plural(days / 365, "year") + " ago"
        }
    }

    /** The one line under a world's name: who is in it and how far along. */
    fun heroLine(world: WorldSummary): String = "${world.heroName} · Lv ${world.level}"

    /** The Continue button's label: which world, which hero, what level. */
    fun continueLine(world: WorldSummary): String = "${world.name} · ${world.heroName} Lv ${world.level}"

    private fun plural(count: Long, unit: String) = if (count == 1L) "1 $unit" else "$count ${unit}s"
}
