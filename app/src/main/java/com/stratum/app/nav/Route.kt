package com.stratum.app.nav

import com.stratum.app.world.WorldLaunch

/**
 * Every place in the app, as a value.
 *
 * Sealed so a `when` over routes is checked for completeness: a new screen
 * that nobody draws is a compile error rather than a blank page. The
 * hierarchy mirrors the menu -- the three doors, and the tools behind them --
 * so where a route sits in the source says where it sits in the game.
 */
sealed interface Route {
    data object Title : Route

    /** The gear icon's destination: every procedural option, in one place. */
    data object Settings : Route

    /** Everything behind the Play door. */
    sealed interface Play : Route {
        data object Hub : Play
        data object NewWorld : Play

        /** In a world. Carries what to play, so the world is built from the route alone. */
        data class World(val launch: WorldLaunch) : Play
    }

    /** Everything behind the Create door: the studio and its tools. */
    sealed interface Create : Route {
        data object Hub : Create
        data object Classes : Create
        data object Voxels : Create

        /** Make your own Igbo mask, and wear it. */
        data object Masks : Create

        /** Carve a sculpted African mask with every part open, and wear it. */
        data object Carver : Create

        /** The Forge of Will: forge attacks from a core, catalysts and resonators. */
        data object Attacks : Create
        data object Mapper : Create
    }

    /** Everything behind the Import & Share door. */
    sealed interface Share : Route {
        data object Hub : Share

        /** The installed-plugin manager, with load order and switches. */
        data object Plugins : Share
    }
}
