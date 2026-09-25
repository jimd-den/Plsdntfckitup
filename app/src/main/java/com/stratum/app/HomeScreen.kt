package com.stratum.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.feature.play.DrawableSprite

/**
 * The landing screen. It reports what the loaded pack actually contains, so the
 * customization story is visible before the player ever enters a world.
 */
@Composable
internal fun HomeScreen(
    packName: String,
    blockCount: Int,
    biomeCount: Int,
    classCount: Int,
    heroClasses: List<HeroClassDefinition>,
    selectedClassId: String?,
    onSelectClass: (String) -> Unit,
    /** Art the player can wear, whichever class they are playing. */
    characterSheets: List<SpriteSheet> = emptyList(),
    selectedSheetId: String? = null,
    onSelectSheet: (String) -> Unit = {},
    /** The chosen character's art, so the picker shows who rather than what. */
    idleFrameFor: (String) -> DrawableSprite? = { null },
    unpackedCharacterCount: Int = 0,
    onPoseForge: () -> Unit = {},
    onBuildClass: () -> Unit,
    onDescend: () -> Unit,
    onForge: () -> Unit,
    onSprites: () -> Unit,
    spriteCount: Int,
    onSettings: () -> Unit,
    onStudio: () -> Unit,
    onLibrary: () -> Unit = {},
    importedCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            // Inset before the scroll, so the content scrolls under nothing and
            // the first line is never behind the status bar on a tall phone.
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
    ) {
        Spacer(Modifier.height(Space.huge))

        Text(
            text = "STRATUM",
            style = MaterialTheme.typography.displaySmall,
            color = colors.ink,
        )
        Text(
            text = "An isometric world you dig apart and rebuild.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        Spacer(Modifier.height(Space.wide))

        StratumSection(
            title = "Loaded pack",
            subtitle = packName,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                Stat("Blocks", blockCount, Modifier.weight(1f))
                Stat("Regions", biomeCount, Modifier.weight(1f))
                Stat("Classes", classCount, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.medium))
            StratumDivider()
            Spacer(Modifier.height(Space.medium))
            Text(
                text = "Every block, region, class and line of lore above comes from a content " +
                    "pack. The engine ships with none of its own, so a generated pack sits beside " +
                    "the built-in one as an equal.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Begin")
            Spacer(Modifier.height(Space.medium))

            // The class is chosen before the run, not after: it decides the
            // spawn, the starting weapon and the skill bar.
            // The character itself, first and unconditionally.
            //
            // This used to live inside the hero-class block, which is why it
            // never appeared: it rendered only when a class in the list
            // matched the selected id, so art a person had drawn was hidden
            // behind a lookup that had nothing to do with it. What you look
            // like is not a property of what you are playing.
            val drawn = remember(selectedSheetId, selectedClassId, idleFrameFor) {
                idleFrameFor(selectedClassId.orEmpty())
            }
            if (drawn != null) {
                IdlePortrait(drawn, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.medium))
            } else if (characterSheets.isEmpty()) {
                // Said rather than left blank. An empty space where a
                // character should be reads as the feature being broken;
                // naming the reason turns it into the next thing to do.
                Text(
                    text = if (unpackedCharacterCount > 0) {
                        "Character art has been drawn but not packed into a sprite sheet yet. " +
                            "Pack it in the pose forge to wear it here."
                    } else {
                        "No character art yet — you will be drawn as a shape. " +
                            "Make one in the pose forge and it appears here."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(Space.medium))
            }

            if (unpackedCharacterCount > 0) {
                StratumPanel(
                    raised = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (unpackedCharacterCount == 1) {
                            "1 character drawn but not packed into a sprite sheet yet."
                        } else {
                            "$unpackedCharacterCount characters drawn but not packed into sprite sheets yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(Space.small))
                    StratumAction(
                        label = "Open pose forge to pack",
                        onClick = onPoseForge,
                        emphasis = ActionEmphasis.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Space.medium))
            }

            // The look, before the class. Two separate choices: what you are
            // playing and what you look like. They used to be one, so the only
            // way to wear a character you had drawn was to go and bind it to a
            // class somewhere else first.
            if (characterSheets.isNotEmpty()) {
                Text(
                    text = "Character",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(Space.small))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                ) {
                    items(characterSheets, key = SpriteSheet::id) { sheet ->
                        StratumChip(
                            label = sheet.name,
                            selected = sheet.id == selectedSheetId,
                            // Tapping the chosen one again clears it, which is
                            // how a player goes back to the class's own art
                            // without hunting for a "none" entry.
                            onClick = { onSelectSheet(sheet.id) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.medium))
            }

            if (heroClasses.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                ) {
                    items(heroClasses, key = HeroClassDefinition::id) { hero ->
                        StratumChip(
                            label = hero.name,
                            selected = hero.id == selectedClassId,
                            onClick = { onSelectClass(hero.id) },
                        )
                    }
                }
                heroClasses.firstOrNull { it.id == selectedClassId }?.let { hero ->
                    Spacer(Modifier.height(Space.small))
                    Text(
                        text = "${hero.resolvedStats.maxHealth} hp · " +
                            "${hero.resolvedStats.attackPower} attack · " +
                            "${hero.baseResource} ${hero.resourceName.lowercase()}" +
                            if (hero.title.isNotBlank()) " · ${hero.title}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
                Spacer(Modifier.height(Space.medium))
            }

            StratumAction(
                label = "Descend",
                onClick = onDescend,
                emphasis = ActionEmphasis.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (unpackedCharacterCount > 0) "Pose forge ($unpackedCharacterCount unpacked)" else "Pose forge",
                onClick = onPoseForge,
                emphasis = if (unpackedCharacterCount > 0) ActionEmphasis.PRIMARY else ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = "Build a class",
                onClick = onBuildClass,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = "Forge a pack with AI",
                onClick = onForge,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (spriteCount > 0) "Sprite forge ($spriteCount)" else "Sprite forge",
                onClick = onSprites,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (importedCount > 0) "Plugins and games ($importedCount loaded)" else "Plugins and games",
                onClick = onLibrary,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = "Creator studio",
                onClick = onStudio,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = "Model provider",
                onClick = onSettings,
                emphasis = ActionEmphasis.QUIET,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Space.huge))
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    StratumWell(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = StratumTheme.colors.accent,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
        )
    }
}

/**
 * One idle frame of a character, drawn the way the world draws it.
 *
 * Deliberately the first frame rather than a running animation: this is a menu
 * and a looping character in it competes with the thing the person came here
 * to press. The point is recognition -- which of the characters you made is
 * this -- and one frame settles that.
 */
@Composable
private fun IdlePortrait(sprite: DrawableSprite, modifier: Modifier = Modifier) {
    val sheet = sprite.sheet
    val frame = sheet.clip(AnimationState.IDLE)?.firstFrame ?: 0
    val rect = sheet.frameRect(frame)
    if (rect.width <= 0 || rect.height <= 0) return

    Canvas(
        modifier = modifier.height(PORTRAIT_HEIGHT),
    ) {
        // Fitted by height and centred: the frame's proportions belong to the
        // character, and squeezing them to a fixed box would make a lunging
        // stance a different person from a standing one.
        val drawHeight = size.height
        val drawWidth = drawHeight * rect.width / rect.height.coerceAtLeast(1)
        drawImage(
            image = sprite.image,
            srcOffset = IntOffset(rect.left, rect.top),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset(((size.width - drawWidth) / 2f).toInt(), 0),
            dstSize = IntSize(drawWidth.toInt().coerceAtLeast(1), drawHeight.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

private val PORTRAIT_HEIGHT = 132.dp
