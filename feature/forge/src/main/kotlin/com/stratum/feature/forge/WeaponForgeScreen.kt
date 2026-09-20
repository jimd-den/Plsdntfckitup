package com.stratum.feature.forge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.WeaponFit
import com.stratum.core.domain.sprite.WeaponKind
import com.stratum.core.domain.sprite.WeaponLayer
import com.stratum.core.domain.sprite.WeaponPosing
import com.stratum.core.domain.sprite.WeaponSprite

/**
 * Draw a weapon on its own, so anyone can carry it.
 *
 * The argument for a screen of its own is the same as the argument for the
 * mechanism: a weapon drawn into a character belongs to that character forever.
 * It cannot be dropped, cannot be swapped for a better one, and has to be drawn
 * again for every actor that carries one — which in a game whose loop is
 * picking up better loot is exactly backwards.
 *
 * It is also how the vanishing sword got fixed. A weapon held in a character's
 * reference pose disappeared the moment the pose changed, because an image
 * editor reads a held object as part of the pose and drops it with the old one.
 * A weapon that was never in the reference cannot be lost from it.
 */
@Composable
fun WeaponForgeScreen(
    viewModel: WeaponForgeViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onEquip: (String?) -> Unit = {},
    equippedId: String? = null,
    previewFor: (String) -> ImageBitmap? = { null },
    sheetImageFor: (String) -> ImageBitmap? = { null },
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    WeaponForgeContent(
        state = state.copy(equippedId = equippedId),
        modifier = modifier,
        onSubjectChange = viewModel::updateSubject,
        onKindChange = viewModel::selectKind,
        onStyleChange = viewModel::updateStyle,
        onGenerate = viewModel::generate,
        onDelete = viewModel::delete,
        onEquip = onEquip,
        onSelectFittingSheet = viewModel::selectFittingSheet,
        onStepPreview = viewModel::stepPreview,
        onNudgeFit = viewModel::nudgeFit,
        onToggleMirror = viewModel::toggleMirror,
        onResetFit = viewModel::resetFit,
        onDismiss = viewModel::dismissMessage,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        previewFor = previewFor,
        sheetImageFor = sheetImageFor,
    )
}

@Composable
fun WeaponForgeContent(
    state: WeaponForgeUiState,
    modifier: Modifier = Modifier,
    onSubjectChange: (String) -> Unit = {},
    onKindChange: (WeaponKind) -> Unit = {},
    onStyleChange: (String) -> Unit = {},
    onGenerate: () -> Unit = {},
    onDelete: (String) -> Unit = {},
    onEquip: (String?) -> Unit = {},
    onSelectFittingSheet: (String) -> Unit = {},
    onStepPreview: (Boolean) -> Unit = {},
    onNudgeFit: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onToggleMirror: () -> Unit = {},
    onResetFit: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    previewFor: (String) -> ImageBitmap? = { null },
    sheetImageFor: (String) -> ImageBitmap? = { null },
) {
    val colors = StratumTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Weapon forge")
            StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.medium))
        Text(
            text = "Weapons are drawn on their own, upright, and attached to the hand at " +
                "draw time. One sword serves every character, swings with every attack, and " +
                "can be swapped for a better one without redrawing anybody.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        if (!state.providerConfigured) {
            Spacer(Modifier.height(Space.medium))
            StratumPanel(raised = false) {
                Text(
                    text = "No provider key is set, so nothing can be drawn yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                Spacer(Modifier.height(Space.small))
                StratumAction(
                    label = "Settings",
                    onClick = onOpenSettings,
                    emphasis = ActionEmphasis.SECONDARY,
                )
            }
        }

        if (state.message != null || state.error != null) {
            Spacer(Modifier.height(Space.medium))
            StratumPanel(raised = false) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.error ?: state.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) colors.danger else colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    StratumAction(label = "OK", onClick = onDismiss, emphasis = ActionEmphasis.QUIET)
                }
            }
        }

        Spacer(Modifier.height(Space.large))

        StratumSection(
            title = "Draw one",
            subtitle = "The kind decides where the hand grips it and how far it reaches.",
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                WeaponKind.entries.forEach { kind ->
                    StratumChip(
                        label = kind.label,
                        selected = state.kind == kind,
                        onClick = { onKindChange(kind) },
                    )
                }
            }

            Spacer(Modifier.height(Space.medium))
            OutlinedTextField(
                value = state.subject,
                onValueChange = onSubjectChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Weapon") },
                placeholder = { Text("a bronze blade with Nsibidi glyphs down the fuller") },
                enabled = !state.busy,
                minLines = 2,
            )

            Spacer(Modifier.height(Space.small))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                SpriteStyle.entries.forEach { preset ->
                    StratumChip(
                        label = preset.label,
                        selected = state.style == preset.direction,
                        onClick = {
                            onStyleChange(if (state.style == preset.direction) "" else preset.direction)
                        },
                    )
                }
            }

            Spacer(Modifier.height(Space.medium))
            StratumAction(
                label = if (state.busy) "Drawing" else "Draw weapon",
                onClick = onGenerate,
                emphasis = ActionEmphasis.PRIMARY,
                enabled = !state.busy && state.providerConfigured && state.subject.isNotBlank(),
            )
        }

        Spacer(Modifier.height(Space.medium))

        StratumSection(
            title = "Armoury",
            subtitle = if (state.weapons.isEmpty()) {
                "Nothing drawn yet."
            } else {
                "Tap one to put it in the player's hand."
            },
        ) {
            state.weapons.forEach { weapon ->
                val equipped = weapon.id == state.equippedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Cut.small)
                        .clickable { onEquip(if (equipped) null else weapon.id) }
                        .padding(vertical = Space.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.medium),
                ) {
                    Box(
                        modifier = Modifier
                            .size(WEAPON_THUMB)
                            .clip(Cut.tiny)
                            .background(colors.surfaceSunken)
                            .border(
                                Stroke.hairline,
                                if (equipped) colors.accent else colors.hairline,
                                Cut.tiny,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val image = previewFor(weapon.id)
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = weapon.name,
                                modifier = Modifier.fillMaxSize().padding(Space.tight),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = weapon.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${weapon.kind.label} · ${weapon.width}x${weapon.height}" +
                                if (equipped) " · in hand" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (equipped) colors.accent else colors.inkMuted,
                        )
                    }
                    StratumAction(
                        label = "Delete",
                        onClick = { onDelete(weapon.id) },
                        emphasis = ActionEmphasis.DESTRUCTIVE,
                    )
                }
                StratumDivider()
            }

            if (state.weapons.isNotEmpty()) {
                Spacer(Modifier.height(Space.medium))
                StratumWell {
                    Text(
                        text = "A weapon is drawn upright and turned through the swing, so the " +
                            "same drawing reads as a wind-up, a strike and a follow-through. " +
                            "It passes behind the shoulder on the way up and across the front " +
                            "on the way down.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.medium))
        FitPanel(
            state = state,
            previewFor = previewFor,
            sheetImageFor = sheetImageFor,
            onSelectFittingSheet = onSelectFittingSheet,
            onStepPreview = onStepPreview,
            onNudgeFit = onNudgeFit,
            onToggleMirror = onToggleMirror,
            onResetFit = onResetFit,
        )

        Spacer(Modifier.height(Space.huge))
    }
}

/**
 * Where this character's hands actually are.
 *
 * The swing arcs are authored once and are the same for everybody, because the
 * shape of a swing is. Proportions are not: measured on a generated warrior,
 * the authored anchors put the hand a full hand's width outside the character.
 * Rather than ask anyone to place twenty-four anchors per character — work
 * nobody does twice — the character supplies three numbers and the arcs bend to
 * fit.
 */
@Composable
private fun FitPanel(
    state: WeaponForgeUiState,
    previewFor: (String) -> ImageBitmap?,
    sheetImageFor: (String) -> ImageBitmap?,
    onSelectFittingSheet: (String) -> Unit,
    onStepPreview: (Boolean) -> Unit,
    onNudgeFit: (Float, Float, Float) -> Unit,
    onToggleMirror: () -> Unit,
    onResetFit: () -> Unit,
) {
    val colors = StratumTheme.colors
    val weaponId = state.equippedId ?: state.weapons.firstOrNull()?.id
    val weapon = state.weapons.firstOrNull { it.id == weaponId }
    val sheet = state.fittingSheet

    StratumSection(
        title = "Fit to a character",
        subtitle = "Nudge until the grip sits in the hand. Saved per character, so every " +
            "weapon this one picks up is held the same way.",
    ) {
        if (state.sheets.isEmpty() || weapon == null) {
            Text(
                text = "Needs a character sheet and a weapon. Draw one of each first.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            return@StratumSection
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            state.sheets.forEach { candidate ->
                StratumChip(
                    label = candidate.name,
                    selected = candidate.id == state.fittingSheetId,
                    onClick = { onSelectFittingSheet(candidate.id) },
                )
            }
        }

        Spacer(Modifier.height(Space.medium))
        StratumWell {
            val body = sheet?.let { sheetImageFor(it.id) }
            val blade = previewFor(weapon.id)
            if (sheet == null || body == null || blade == null) {
                Text(
                    text = "That character's sheet could not be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(FIT_PREVIEW),
                    contentAlignment = Alignment.Center,
                ) {
                    HeldWeaponPreview(
                        sheet = sheet,
                        body = body,
                        weapon = weapon,
                        blade = blade,
                        frame = state.previewSheetFrame,
                        fit = state.fit,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StratumAction("Prev", { onStepPreview(false) }, emphasis = ActionEmphasis.QUIET)
            Text(
                text = "frame ${state.previewFrame + 1} of ${state.previewFrameCount}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            StratumAction("Next", { onStepPreview(true) }, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Hand",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            StratumAction("←", { onNudgeFit(-NUDGE, 0f, 0f) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("↑", { onNudgeFit(0f, -NUDGE, 0f) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("↓", { onNudgeFit(0f, NUDGE, 0f) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("→", { onNudgeFit(NUDGE, 0f, 0f) }, emphasis = ActionEmphasis.QUIET)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Size",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            StratumAction("−", { onNudgeFit(0f, 0f, -SCALE_STEP) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("+", { onNudgeFit(0f, 0f, SCALE_STEP) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("Reset", onResetFit, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Worth its own control: an image model reproduces the shape of a
            // pose faithfully and then draws it on whichever side it prefers,
            // which no amount of nudging reaches across.
            Text(
                text = "Holds it in the other hand",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            StratumChip(
                label = if (state.fit.mirrored) "Mirrored" else "As drawn",
                selected = state.fit.mirrored,
                onClick = onToggleMirror,
            )
        }

        Spacer(Modifier.height(Space.small))
        Text(
            text = "offset %+.2f, %+.2f · size %.2fx%s".format(
                state.fit.offsetX, state.fit.offsetY, state.fit.scale,
                if (state.fit.mirrored) " · mirrored" else "",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
        )
    }
}

/** The renderer's own arithmetic, so what is tuned here is what gets drawn. */
@Composable
private fun HeldWeaponPreview(
    sheet: SpriteSheet,
    body: ImageBitmap,
    weapon: WeaponSprite,
    blade: ImageBitmap,
    frame: Int,
    fit: WeaponFit,
) {
    val rig = remember(sheet, fit) { WeaponPosing.rigFor(sheet, fit) }
    val rect = remember(sheet, frame) { sheet.frameRect(frame) }
    val anchor = rig.anchorFor(frame)

    Canvas(Modifier.fillMaxWidth().height(FIT_PREVIEW)) {
        val scale = minOf(
            size.width / rect.width.coerceAtLeast(1),
            size.height / rect.height.coerceAtLeast(1),
        ) * PREVIEW_FILL
        val drawWidth = rect.width * scale
        val drawHeight = rect.height * scale
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f

        val paintBody: () -> Unit = {
            drawImage(
                image = body,
                srcOffset = IntOffset(rect.left, rect.top),
                srcSize = IntSize(rect.width, rect.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt()),
                filterQuality = FilterQuality.None,
            )
        }
        val paintWeapon: () -> Unit = {
            if (anchor != null) {
                val height = drawHeight * weapon.kind.reach * anchor.scale
                val width = height * (blade.width.toFloat() / blade.height.coerceAtLeast(1))
                val handX = left + anchor.xFraction * drawWidth
                val handY = top + anchor.yFraction * drawHeight
                withTransform({ rotate(anchor.rotationDegrees, Offset(handX, handY)) }) {
                    drawImage(
                        image = blade,
                        dstOffset = IntOffset(
                            (handX - weapon.gripX * width).toInt(),
                            (handY - weapon.gripY * height).toInt(),
                        ),
                        dstSize = IntSize(
                            width.toInt().coerceAtLeast(1),
                            height.toInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }

        if (anchor?.layer == WeaponLayer.BEHIND) paintWeapon()
        paintBody()
        if (anchor?.layer == WeaponLayer.IN_FRONT) paintWeapon()
    }
}

private val WEAPON_THUMB = 56.dp
private val FIT_PREVIEW = 260.dp

/** One nudge, as a fraction of the frame: small enough to land on a hand. */
private const val NUDGE = 0.01f
private const val SCALE_STEP = 0.05f
private const val PREVIEW_FILL = 0.9f
