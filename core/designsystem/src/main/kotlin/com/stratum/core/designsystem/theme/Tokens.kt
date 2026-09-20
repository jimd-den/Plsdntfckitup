package com.stratum.core.designsystem.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measurements the whole interface is built from.
 *
 * One scale, used everywhere. A screen that needs a value not on this scale is
 * usually a screen that needs rethinking, not a new constant.
 */
object Space {
    val hair: Dp = 2.dp
    val tight: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val wide: Dp = 24.dp
    val huge: Dp = 32.dp
    val gutter: Dp = 16.dp
}

/**
 * Shapes are cut, not rounded.
 *
 * The world is made of stacked blocks seen at a 2:1 angle, so the interface uses
 * the same chamfered geometry rather than the pill shapes every other Material
 * app uses. This is the single strongest carrier of the game's identity, which
 * is why it lives in one place instead of being redrawn per screen.
 */
object Cut {
    val tiny = CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp)
    val small = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    val medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val large = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)

    /** For meters and rails, where a cut corner would clip the fill misleadingly. */
    val bar = RoundedCornerShape(2.dp)
}

object Stroke {
    val hairline: Dp = 1.dp
    val edge: Dp = 2.dp
    /** The lit top edge that makes a panel read as a cut slab. */
    val bevel: Dp = 1.dp
}

object Elevation {
    val flat: Dp = 0.dp
    val raised: Dp = 2.dp
    val floating: Dp = 8.dp
}
