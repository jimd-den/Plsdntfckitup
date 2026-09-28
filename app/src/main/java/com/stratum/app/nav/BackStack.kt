package com.stratum.app.nav

import androidx.compose.runtime.mutableStateListOf

/**
 * The app's history: a list of routes, the last one on screen.
 *
 * In-house rather than Navigation-Compose. The routes carry Kotlin values --
 * a world launch holds its rules -- that a nav graph would make us encode as
 * strings or serialisable arguments; there are no deep links to parse; and
 * the play screen's view models are already scoped by `ScopedViewModels`
 * rather than by nav back-stack entries. What is left is a list with push
 * and pop, small enough to read in one sitting and to test without Android.
 *
 * Snapshot state, so the shell recomposes when it changes. The root is never
 * popped: back on the title screen leaves the app, which is the system's job.
 */
class BackStack(root: Route = Route.Title) {

    private val entries = mutableStateListOf(root)

    val current: Route get() = entries.last()

    /** Whether back has somewhere to go inside the app. */
    val canPop: Boolean get() = entries.size > 1

    /** A snapshot of the history, oldest first. */
    val routes: List<Route> get() = entries.toList()

    fun push(route: Route) {
        // Pressing the same way in twice (a double tap) should not need two backs to undo.
        if (entries.last() != route) entries.add(route)
    }

    /** Goes up one level; false when already at the root. */
    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Swaps the screen on top for [route], so back skips the one being left. */
    fun replace(route: Route) {
        entries[entries.lastIndex] = route
    }

    /**
     * Pops back to the nearest [route] already in the history, or, when there
     * is none, swaps the top for it: leaving a world continued straight from
     * the title lands on the play hub, and back from there is the title.
     */
    fun popTo(route: Route) {
        val index = entries.lastIndexOf(route)
        if (index < 0) {
            replace(route)
        } else {
            while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        }
    }
}
