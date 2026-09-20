package com.stratum.feature.play

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumJoystick
import com.stratum.core.designsystem.component.StratumMeter
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumProgressSliver
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.safeBottomPadding
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.designsystem.theme.safeTop
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.engine.world.BuildTool
import com.stratum.core.domain.world.World

/**
 * The play screen: world on top, controls below.
 *
 * The HUD deliberately sits in a band under the viewport rather than floating
 * over it. On a phone held in one hand, controls overlaid on an isometric world
 * cover the thing you are trying to aim at.
 */
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlayScreenContent(
        state = state,
        world = viewModel.world,
        modifier = modifier,
        onTapBlock = viewModel::beginMining,
        onLongPressBlock = viewModel::place,
        onMoveInput = viewModel::setMoveInput,
        onDodge = viewModel::dodge,
        onToggleBuild = viewModel::toggleBuildMode,
        onSelectBuildTool = viewModel::selectBuildTool,
        onBuildDrag = viewModel::previewBuild,
        onBuildCommit = viewModel::commitBuild,
        onSelectSlot = viewModel::selectSlot,
        onZoom = viewModel::zoom,
        onStopMining = viewModel::stopMining,
        onAttack = viewModel::attack,
        onCastSkill = viewModel::castSkill,
        onRevive = viewModel::revive,
        onNewRun = viewModel::newRun,
        onToggleSatchel = viewModel::toggleSatchel,
        onEquip = viewModel::equip,
        onDiscard = viewModel::discard,
        onToggleAnvil = viewModel::toggleAnvil,
        onSelectAnvilItem = viewModel::selectAnvilItem,
        onSlotInsert = viewModel::slotInsert,
        onUnslotInsert = viewModel::unslotInsert,
        onOpenMenu = onOpenMenu,
    )
}

/** Stateless body, so it can be previewed and screenshot-tested without a view model. */
@Composable
fun PlayScreenContent(
    state: PlayUiState,
    world: World,
    modifier: Modifier = Modifier,
    onTapBlock: (com.stratum.core.domain.world.BlockPos) -> Unit = {},
    onLongPressBlock: (com.stratum.core.domain.world.BlockPos) -> Unit = {},
    onMoveInput: (Float, Float) -> Unit = { _, _ -> },
    onDodge: () -> Unit = {},
    onToggleBuild: () -> Unit = {},
    onSelectBuildTool: (BuildTool) -> Unit = {},
    onBuildDrag: (com.stratum.core.domain.world.BlockPos, com.stratum.core.domain.world.BlockPos) -> Unit = { _, _ -> },
    onBuildCommit: () -> Unit = {},
    onSelectSlot: (Int) -> Unit = {},
    onZoom: (Float) -> Unit = {},
    onStopMining: () -> Unit = {},
    onAttack: () -> Unit = {},
    onCastSkill: (String) -> Unit = {},
    onRevive: () -> Unit = {},
    onNewRun: () -> Unit = {},
    onToggleSatchel: () -> Unit = {},
    onEquip: (String) -> Unit = {},
    onDiscard: (String) -> Unit = {},
    onToggleAnvil: () -> Unit = {},
    onSelectAnvilItem: (String) -> Unit = {},
    onSlotInsert: (String, String) -> Unit = { _, _ -> },
    onUnslotInsert: (String, Int) -> Unit = { _, _ -> },
    onOpenMenu: () -> Unit = {},
) {
    val colors = StratumTheme.colors

    Column(modifier = modifier.fillMaxSize().background(colors.surface)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            WorldCanvas(
                world = world,
                camera = state.camera,
                projection = state.projection,
                highlight = state.miningTarget,
                playerPosition = state.player.position,
                playerFacing = state.player.facing,
                playerAccent = colors.accent,
                enemies = state.enemies,
                groundLoot = state.groundLoot,
                groundInserts = state.groundInserts,
                insertColor = { state.insertOrNull(it)?.color },
                insertGlyph = { state.insertOrNull(it)?.glyph },
                feedback = state.feedback,
                playerFlash = state.playerFlash,
                isRolling = state.isRolling,
                isInvulnerable = state.isInvulnerable,
                flashFor = state.flashFor,
                impactFor = state.impactFor,
                spriteFor = state.spriteFor,
                playerAnimation = state.playerAnimation,
                animationFor = state.animationFor,
                buildPreview = state.buildPreview,
                buildAffordable = state.buildAffordable,
                buildMode = state.buildMode,
                onBuildDrag = onBuildDrag,
                onBuildCommit = onBuildCommit,
                revision = state.worldRevision,
                frame = state.frame,
                modifier = Modifier.fillMaxSize(),
                onTapBlock = onTapBlock,
                onLongPressBlock = onLongPressBlock,
            )

            // The world draws under the status bar on purpose; the meters over
            // it do not, or a camera hole lands in the middle of the health bar.
            VitalsOverlay(
                state = state,
                modifier = Modifier.align(Alignment.TopStart).safeTop().padding(Space.medium),
            )

            Column(
                modifier = Modifier.align(Alignment.TopEnd).safeTop().padding(Space.medium),
                horizontalAlignment = Alignment.End,
            ) {
                StratumAction(
                    label = "Menu",
                    onClick = onOpenMenu,
                    emphasis = ActionEmphasis.SECONDARY,
                )
                Spacer(Modifier.height(Space.small))
                // Paired rather than stacked: five controls down the right edge
                // covers the part of the world the player is walking into.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    StratumAction(
                        label = if (state.player.bag.isEmpty()) "Bag" else "Bag ${state.player.bag.size}",
                        onClick = onToggleSatchel,
                        emphasis = if (state.satchelOpen) ActionEmphasis.PRIMARY else ActionEmphasis.SECONDARY,
                    )
                    StratumAction(
                        label = if (state.heldInserts.isEmpty()) "Anvil" else "Anvil ${state.heldInserts.sumOf { it.count }}",
                        onClick = onToggleAnvil,
                        emphasis = if (state.anvilOpen) ActionEmphasis.PRIMARY else ActionEmphasis.SECONDARY,
                    )
                }
                Spacer(Modifier.height(Space.small))
                ZoomControls(onZoom = onZoom)
            }

            if (state.satchelOpen && !state.anvilOpen && !state.isDead) {
                SatchelOverlay(
                    state = state,
                    world = world,
                    onEquip = onEquip,
                    onDiscard = onDiscard,
                    // The two panels are one errand: read the item here, socket
                    // it next door, without going back out to the world first.
                    onOpenAnvil = {
                        onToggleSatchel()
                        onToggleAnvil()
                    },
                    onClose = onToggleSatchel,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (state.anvilOpen && !state.isDead) {
                AnvilOverlay(
                    state = state,
                    onSelectItem = onSelectAnvilItem,
                    onSlot = onSlotInsert,
                    onUnslot = onUnslotInsert,
                    onClose = onToggleAnvil,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (state.isDead) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.surface.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .safeContent()
                            .padding(horizontal = Space.large),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "YOU HAVE FALLEN",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.danger,
                        )
                        Spacer(Modifier.height(Space.small))
                        Text(
                            text = "Level ${state.player.level} · ${state.biomeName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                        Spacer(Modifier.height(Space.large))
                        // Getting back up is the primary action. Being sent to
                        // a menu to restart is the part that makes people put a
                        // game down rather than try the fight again.
                        StratumAction(
                            label = "Rise",
                            onClick = onRevive,
                            emphasis = ActionEmphasis.PRIMARY,
                        )
                        Spacer(Modifier.height(Space.tight))
                        Text(
                            text = "Keep everything. Lose a quarter of the way to your " +
                                "next level, and walk back.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Space.medium))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                            StratumAction(
                                label = "New run",
                                onClick = onNewRun,
                                emphasis = ActionEmphasis.SECONDARY,
                            )
                            StratumAction(
                                label = "Menu",
                                onClick = onOpenMenu,
                                emphasis = ActionEmphasis.QUIET,
                            )
                        }
                    }
                }
            }

            if (state.miningTarget != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Space.large)
                        .fillMaxWidth(0.6f),
                ) {
                    Text(
                        text = "Mining ${world.blockAt(state.miningTarget).displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(Space.tight))
                    StratumProgressSliver(fraction = state.miningFraction)
                }
            }
        }

        ControlBand(
            state = state,
            world = world,
            onMoveInput = onMoveInput,
            onDodge = onDodge,
            onToggleBuild = onToggleBuild,
            onSelectBuildTool = onSelectBuildTool,
            onSelectSlot = onSelectSlot,
            onStopMining = onStopMining,
            onAttack = onAttack,
            onCastSkill = onCastSkill,
        )
    }
}

@Composable
private fun VitalsOverlay(
    state: PlayUiState,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    // Meters sit on a panel rather than straight on the world: terrain colour
    // changes with the biome, and text over bare terrain stops being readable
    // the moment the player walks somewhere pale.
    StratumPanel(
        modifier = modifier.width(176.dp),
        shape = Cut.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.small),
    ) {
        // Glyphed, so the bars are readable before the words are. At a glance
        // in a fight nobody reads "VITALITY"; they see a red bar with a heart.
        StratumMeter(
            label = "♥ Vitality",
            value = state.player.health,
            max = state.player.maxHealth,
            tint = colors.danger,
        )
        Spacer(Modifier.height(Space.hair))
        StratumMeter(
            label = "✦ ${state.player.resourceName}",
            value = state.player.resource,
            max = state.player.maxResource,
            tint = colors.accentAlt,
        )
        Spacer(Modifier.height(Space.tight))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "LV ${state.player.level}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
            )
            Text(
                text = "${(state.player.experienceFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.height(Space.hair))
        StratumProgressSliver(
            fraction = state.player.experienceFraction,
            tint = colors.accent,
        )
    }
}

@Composable
private fun ZoomControls(onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        StratumAction(label = "+", onClick = { onZoom(ZOOM_STEP) }, emphasis = ActionEmphasis.QUIET)
        Spacer(Modifier.height(Space.tight))
        StratumAction(label = "-", onClick = { onZoom(-ZOOM_STEP) }, emphasis = ActionEmphasis.QUIET)
    }
}

/**
 * Movement pad, hotbar and status. Grouped in one band so the thumb never has to
 * leave the bottom third of the screen.
 */
@Composable
private fun ControlBand(
    state: PlayUiState,
    world: World,
    onMoveInput: (Float, Float) -> Unit,
    onDodge: () -> Unit,
    onToggleBuild: () -> Unit,
    onSelectBuildTool: (BuildTool) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onStopMining: () -> Unit,
    onAttack: () -> Unit,
    onCastSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors

    // The panel itself runs to the screen edge so its surface sits behind the
    // gesture bar; only what is inside it moves up out of the way.
    StratumPanel(
        modifier = modifier.fillMaxWidth(),
        shape = Cut.large,
        // Tighter than a settings panel. The band is a thumb rest, not a
        // window, and every pixel it takes is a pixel of world you cannot see.
        contentPadding = safeBottomPadding(Space.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(state.biomeName.ifBlank { "Uncharted" })
            Text(
                text = state.message ?: if (state.buildMode) {
                    "Drag to place"
                } else {
                    "Tap to mine, hold to build"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.height(Space.small))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StratumJoystick(
                size = STICK_SIZE,
                onDirection = { x, y ->
                    // Any stick movement cancels a dig: walking away from a
                    // block you were mining should not keep mining it.
                    if (x != 0f || y != 0f) onStopMining()
                    onMoveInput(x, y)
                },
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = state.player.equippedWeapon
                        ?.let { "${it.glyph} ${it.name}" }
                        ?: "Bare hands",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(Space.tight))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    StratumAction(
                        label = if (state.rollCooldownFraction > 0f) "Roll…" else "Roll",
                        onClick = onDodge,
                        emphasis = ActionEmphasis.SECONDARY,
                        enabled = state.rollCooldownFraction <= 0f,
                    )
                    // One primary action, and it is whichever one the current
                    // mode means. Two equally weighted verbs side by side is a
                    // desktop toolbar, not a thumb control.
                    StratumAction(
                        label = if (state.buildMode) "Build" else "Strike",
                        onClick = if (state.buildMode) onToggleBuild else onAttack,
                        emphasis = ActionEmphasis.DESTRUCTIVE,
                    )
                }
                Spacer(Modifier.height(Space.hair))
                StratumProgressSliver(
                    fraction = 1f - state.rollCooldownFraction,
                    tint = colors.accentAlt,
                )
                Spacer(Modifier.height(Space.small))
                Hotbar(state = state, world = world, onSelectSlot = onSelectSlot)
            }
        }

        Spacer(Modifier.height(Space.small))
        StratumAction(
            label = if (state.buildMode) "Building — tap to stop" else "Build",
            onClick = onToggleBuild,
            // Not QUIET: the mode toggle has to be findable, and at quiet
            // weight it disappeared into the band entirely.
            emphasis = if (state.buildMode) ActionEmphasis.PRIMARY else ActionEmphasis.SECONDARY,
        )
        // Tools get their own row: five chips beside the toggle overflowed the
        // band and clipped the last one, which reads as broken rather than
        // scrollable.
        if (state.buildMode) {
            Spacer(Modifier.height(Space.small))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                items(BuildTool.entries, key = { it.name }) { tool ->
                    StratumChip(
                        label = tool.label,
                        selected = state.buildTool == tool,
                        onClick = { onSelectBuildTool(tool) },
                    )
                }
            }
        }

        // The skill bar is hidden while building: it is the wrong tool set, and
        // the band gets too tall on a phone with both.
        if (state.skills.isNotEmpty() && !state.buildMode) {
            Spacer(Modifier.height(Space.small))
            SkillBar(state = state, onCastSkill = onCastSkill)
        }
    }
}

/**
 * The skill row. A skill on cooldown stays visible and dimmed rather than
 * disappearing, so the bar does not reflow under the player's thumb mid-fight.
 */
@Composable
private fun SkillBar(
    state: PlayUiState,
    onCastSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(state.skills, key = SkillDefinition::id) { skill ->
            val cooling = state.cooldownFraction(skill)
            val ready = cooling <= 0f && state.canAfford(skill)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StratumChip(
                    label = skill.name,
                    selected = ready,
                    onClick = { onCastSkill(skill.id) },
                    swatch = Color(skill.color),
                )
                Spacer(Modifier.height(Space.hair))
                StratumProgressSliver(
                    fraction = 1f - cooling,
                    tint = if (state.canAfford(skill)) colors.accentAlt else colors.danger,
                )
            }
        }
    }
}

@Composable
private fun Hotbar(
    state: PlayUiState,
    world: World,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.player.hotbar.isEmpty()) {
        Text(
            text = "Bag empty",
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
            modifier = modifier,
        )
        return
    }

    LazyRow(
        modifier = modifier.width(200.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        itemsIndexed(state.player.hotbar) { index, blockId ->
            val type = world.registry.indexOrNull(blockId)?.let(world.registry::typeOf)
            // The same glyph the block wears in the world, so what you are
            // holding and what you are about to place are visibly one thing.
            val mark = type?.glyph?.let { "$it " }.orEmpty()
            StratumChip(
                label = "$mark${type?.displayName ?: blockId} ${state.player.countOf(blockId)}",
                selected = index == state.player.selectedSlot,
                onClick = { onSelectSlot(index) },
                swatch = type?.let { Color(it.topColor) },
            )
        }
    }
}

private const val ZOOM_STEP = 0.2f
/** Down from 132dp: the stick was taking a fifth of the screen to say "north-east". */
private val STICK_SIZE = 104.dp
