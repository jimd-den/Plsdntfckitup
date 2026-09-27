package com.stratum.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * View models that live as long as [content] is on screen, and no longer.
 *
 * Without this every `viewModel()` belongs to the activity, so a play session
 * outlived the screen it was for: leaving to the menu and pressing play again
 * handed back the old world, however many times the seed changed, and every
 * world ever entered stayed in memory. Scoped here, leaving play clears the
 * session -- which is what saves the hero -- and the next visit starts fresh.
 */
@Composable
internal fun ScopedViewModels(key: Any, content: @Composable () -> Unit) {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
