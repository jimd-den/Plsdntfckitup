package com.stratum.feature.play

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Closes the topmost open panel, as the back gesture should, and says
 * whether there was one. Build mode counts: back leaves it, like Done.
 */
internal fun closeOpenPanel(state: PlayUiState, viewModel: PlayViewModel): Boolean {
    when {
        state.sandbox.open -> viewModel.toggleSandbox()
        state.hero.open -> viewModel.toggleHero()
        state.anvilOpen -> viewModel.toggleAnvil()
        state.satchelOpen -> viewModel.toggleSatchel()
        state.realmOpen -> viewModel.toggleRealm()
        state.worldShaper.open -> viewModel.toggleWorldShaper()
        state.campOpen -> viewModel.toggleCamp()
        state.styleOpen -> viewModel.toggleStyle()
        state.tableOpen -> viewModel.toggleTable()
        state.buildMode -> viewModel.toggleBuildMode()
        else -> return false
    }
    return true
}

/**
 * Two fingers pinch the camera in and out, over whatever the world draws.
 *
 * Watches the initial pass and never consumes, so the world underneath
 * still gets every tap and hold; it acts only while two or more pointers are
 * down, which a dig or a place never is. Replaces the permanent zoom buttons.
 */
internal fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(onZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom((zoom - 1f) * PINCH_GAIN)
            }
        } while (event.changes.any { it.pressed })
    }
}

/** How much a pinch moves the zoom per unit of finger spread; tuned so a full pinch is a few button steps. */
private const val PINCH_GAIN = 1.5f
