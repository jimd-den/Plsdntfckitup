package com.stratum.app.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stratum.app.PoseGenerationService
import com.stratum.app.nav.BackStack
import com.stratum.app.world.NewWorldDraft
import com.stratum.feature.forge.PoseRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The app's one activity-scoped owner: the wiring, the state every screen
 * shares, and where the player is.
 *
 * Holding these here rather than in composition is what lets the shell be a
 * thin `when` over routes, and lets a screen be left and come back to with
 * everything it was showing still there.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val graph = AppGraph(application, viewModelScope)

    val game = GameState(graph, viewModelScope)

    val backStack = BackStack()

    /** The world being set up, kept across the detour to the class forge. */
    val newWorld = MutableStateFlow(NewWorldDraft())

    init {
        // Loaded after the first frame, since it re-reads every archive; the
        // content reassembles when it arrives.
        viewModelScope.launch { graph.plugins.repository.refresh() }
        graph.worlds.refresh()

        // The service is started by a pose run beginning, not by the forge
        // opening: it exists to protect work in flight, and one that started
        // with the screen would be a permanent notification about nothing.
        PoseRun.running.filter { it }.onEach { PoseGenerationService.start(application) }.launchIn(viewModelScope)
    }
}
