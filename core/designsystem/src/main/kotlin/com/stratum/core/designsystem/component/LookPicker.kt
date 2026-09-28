package com.stratum.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.sprite.FrameRect

/**
 * A look the hero can wear: a character sheet the player drew or imported.
 * [portrait] is the sheet's image and [frame] its first idle frame, so the
 * choice shows who rather than a file name.
 */
data class LookChoice(
    val id: String,
    val name: String,
    val portrait: ImageBitmap? = null,
    val frame: FrameRect? = null,
)

/**
 * Every look in one row, the class's own art first so there is always a
 * choice. [selected] null means the class's art; tapping the worn look is
 * the caller's to interpret (the game treats it as going back to class art).
 */
@Composable
fun LookRow(looks: List<LookChoice>, selected: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        item(key = CLASS_ART) { LookTile(name = "Class art", portrait = null, frame = null, selected = selected == null, onClick = { onPick(null) }) }
        items(looks, key = LookChoice::id) { look ->
            LookTile(look.name, look.portrait, look.frame, selected = look.id == selected, onClick = { onPick(look.id) })
        }
    }
}

/** One look: its idle frame fitted and crisp, or a silhouette when it has no art, and its name. */
@Composable
fun LookTile(name: String, portrait: ImageBitmap?, frame: FrameRect?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(LOOK_TILE_WIDTH)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) colors.accent else colors.inkMuted.copy(alpha = 0.35f),
            )
            .clickable(role = Role.RadioButton, onClickLabel = "Wear $name", onClick = onClick)
            .padding(Space.small),
    ) {
        Canvas(Modifier.width(LOOK_TILE_WIDTH - 16.dp).height(LOOK_PORTRAIT_HEIGHT)) {
            if (portrait != null && frame != null && frame.width > 0 && frame.height > 0) {
                // Fitted by height and centred, pixels kept crisp, as the world draws them.
                val drawHeight = size.height
                val drawWidth = (drawHeight * frame.width / frame.height).coerceAtMost(size.width)
                drawImage(
                    image = portrait,
                    srcOffset = IntOffset(frame.left, frame.top),
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset(((size.width - drawWidth) / 2f).toInt(), 0),
                    dstSize = IntSize(drawWidth.toInt().coerceAtLeast(1), drawHeight.toInt()),
                    filterQuality = FilterQuality.None,
                )
            } else {
                // No art: a silhouette placeholder in the theme's muted ink.
                val ink = colors.inkMuted.copy(alpha = 0.5f)
                val cx = size.width / 2f
                drawCircle(ink, radius = size.height * 0.14f, center = Offset(cx, size.height * 0.22f))
                drawRoundRect(
                    ink,
                    topLeft = Offset(cx - size.height * 0.2f, size.height * 0.4f),
                    size = Size(size.height * 0.4f, size.height * 0.55f),
                    cornerRadius = CornerRadius(12f, 12f),
                )
            }
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.accent else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val CLASS_ART = "look:class-art"
private val LOOK_TILE_WIDTH = 104.dp
private val LOOK_PORTRAIT_HEIGHT = 112.dp
