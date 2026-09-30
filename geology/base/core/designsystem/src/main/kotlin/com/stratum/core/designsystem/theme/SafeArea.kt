package com.stratum.core.designsystem.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

/**
 * Where it is safe to put something the player has to read or press.
 *
 * The app draws edge to edge, which is right for an isometric world — terrain
 * under the status bar looks deliberate. It is wrong for everything else: a
 * phone with a camera hole will happily punch it through a health bar, and the
 * gesture bar will eat a button sitting at the very bottom.
 *
 * These are the one place that decision lives, so a new screen cannot forget it
 * in a way that only shows up on somebody else's handset.
 */
object SafeArea {

    /** Everything the system occupies: bars, gesture handle and display cutout. */
    val insets: WindowInsets
        @Composable get() = LocalSafeAreaInsets.current ?: WindowInsets.safeDrawing

    /** Top and sides, for anything anchored under the status bar or a cutout. */
    val top: WindowInsets
        @Composable get() = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    /** Bottom and sides, for anything the thumb reaches for. */
    val bottom: WindowInsets
        @Composable get() = insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
}

/**
 * Overrides what the app treats as unsafe.
 *
 * Null, the default, means ask the window — which is what a running app always
 * wants. A screenshot test or a preview provides a value instead, because
 * neither has a real status bar or camera hole to measure, and a layout that has
 * never been rendered against a cutout is a layout nobody has checked.
 */
val LocalSafeAreaInsets = compositionLocalOf<WindowInsets?> { null }

/**
 * Insets a whole screen. The right default for anything that is not a world
 * viewport: a settings list, a menu, a form.
 */
@Composable
fun Modifier.safeContent(): Modifier = windowInsetsPadding(SafeArea.insets)

/**
 * Insets only the top and sides, leaving the bottom edge to the caller.
 *
 * For a HUD element floating over a full-bleed viewport: the world keeps running
 * under the status bar, the meter over it does not.
 */
@Composable
fun Modifier.safeTop(): Modifier = windowInsetsPadding(SafeArea.top)

/** Insets only the bottom and sides, for a control band pinned to the floor. */
@Composable
fun Modifier.safeBottom(): Modifier = windowInsetsPadding(SafeArea.bottom)

/**
 * A uniform padding of [base], grown on the bottom and sides by whatever the
 * system occupies there.
 *
 * For a surface pinned to the bottom edge: padding the surface itself would
 * leave a strip of bare background under it, so the surface stays full bleed and
 * its *contents* move up out of the gesture bar instead.
 */
@Composable
fun safeBottomPadding(base: Dp): PaddingValues {
    val inset = SafeArea.bottom.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = base + inset.calculateStartPadding(direction),
        top = base,
        end = base + inset.calculateEndPadding(direction),
        bottom = base + inset.calculateBottomPadding(),
    )
}
