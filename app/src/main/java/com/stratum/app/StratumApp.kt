package com.stratum.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.nav.Route
import com.stratum.app.shell.AppViewModel
import com.stratum.app.shell.CreateRoutes
import com.stratum.app.shell.MenuRoutes
import com.stratum.app.shell.PlayRoute
import com.stratum.core.designsystem.component.LocalJobsTray

/**
 * The app shell: which route is on top, drawn, with the system back gesture
 * popping one level everywhere.
 *
 * Deliberately only a dispatcher. The state every screen shares lives in
 * [AppViewModel]; each route's wiring lives beside the screen it wires. What
 * is left here is the whole map of the app on one page.
 */
@Composable
fun StratumApp(
    modifier: Modifier = Modifier,
    /** The creation-jobs tray, shown in every top bar. Empty until the jobs system provides one. */
    jobsTray: @Composable () -> Unit = {},
) {
    val app: AppViewModel = viewModel()
    val stack = app.backStack
    // Play handles back itself -- it opens the pause menu -- so the shell
    // steps aside there rather than dropping the player out of a fight.
    BackHandler(enabled = stack.canPop && stack.current !is Route.Play.World) { stack.pop() }

    CompositionLocalProvider(LocalJobsTray provides jobsTray) {
        when (val route = stack.current) {
            is Route.Play.World -> PlayRoute(
                app = app,
                launch = route.launch,
                onExit = { stack.popTo(Route.Play.Hub) },
                modifier = modifier,
            )
            is Route.Create -> CreateRoutes(app, route, stack, modifier)
            Route.Title, Route.Settings, Route.Play.Hub, Route.Play.NewWorld, Route.Share.Hub, Route.Share.Plugins ->
                MenuRoutes(app, route, stack, modifier)
        }
    }
}
