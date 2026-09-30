package com.stratum.app.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stratum.app.CreationJobs
import com.stratum.app.PoseGenerationService
import com.stratum.app.nav.Route
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.creation.PackDelivery
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

    /** The app's one job centre; jobs outlive every screen, including this view model's activity. */
    val jobs = CreationJobs.center

    /** Whether the player is in a world right now, which is when a finished pack must wait. */
    val inWorld: Boolean get() = backStack.current is Route.Play.World

    /** Installs a pack the crew wrote as an ordinary plugin, and reloads content with it. */
    fun installGenerated(pack: ContentPack) {
        viewModelScope.launch {
            graph.plugins.installGenerated(pack)
            graph.plugins.repository.refresh()
        }
    }

    /**
     * Hands a finished world pack over: installed now outside a world, held
     * in the inbox while one is being played so it is never swapped in under
     * the player.
     */
    fun deliver(pack: ContentPack): PackDelivery = CreationJobs.inbox.arrive(pack, sessionRunning = inWorld, install = ::installGenerated)

    /** Installs every pack that arrived while a world was being played; called whenever play is not on screen. */
    fun releaseHeldPacks() {
        if (!inWorld) CreationJobs.inbox.release(::installGenerated)
    }

    init {
        // Loaded after the first frame, since it re-reads every archive; the
        // content reassembles when it arrives.
        viewModelScope.launch {
            // The plugins inside the APK first, so the first content already has them.
            runCatching { graph.plugins.installBundled() }
            graph.plugins.repository.refresh()
        }
        graph.worlds.refresh()

        // The service is started by a pose run beginning, not by the forge
        // opening: it exists to protect work in flight, and one that started
        // with the screen would be a permanent notification about nothing.
        PoseRun.running.filter { it }.onEach { PoseGenerationService.start(application) }.launchIn(viewModelScope)
    }
}
