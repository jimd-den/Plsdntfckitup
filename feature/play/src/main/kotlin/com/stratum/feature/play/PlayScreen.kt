package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.stratum.core.designsystem.component.GameEmphasis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.GameButton
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumJoystick
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.world.World
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.world.BuildTool

/**
 * The play screen: the world full screen, in either orientation, with the HUD
 * floating over it the way handheld games lay one out -- the stick under the
 * left thumb, one big action under the right with the rest fanned around it,
 * the hero's vitals in the top corner, and every other system behind one
 * menu button that badges itself when something inside is waiting.
 *
 * The system back gesture closes whatever panel is open, then opens the menu;
 * it never drops the player out of a world by accident. Leaving is "Save &
 * quit" in the menu, which calls [onOpenMenu].
 */
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit = {},
    /** Hints already taught, so each appears once ever. */
    seenHints: Set<String> = emptySet(),
    onHintSeen: (String) -> Unit = {},
    /** The player's looks, worn and changed from the hero panel. */
    looks: HeroLooks = HeroLooks(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        when {
            menuOpen -> menuOpen = false
            closeOpenPanel(state, viewModel) -> Unit
            else -> menuOpen = true
        }
    }
    // The app going into the background is the last moment it is sure to be
    // alive: a phone may kill it from there without another word.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    val heroActions = remember(viewModel) {
        HeroActions(
            onClose = viewModel::toggleHero,
            onSelectTab = viewModel::selectHeroTab,
            onSelectNode = viewModel::selectPassive,
            onAllocate = viewModel::allocatePassive,
            onRefund = viewModel::refundPassive,
            onSelectSkill = viewModel::selectSkill,
            onLinkSupport = viewModel::linkSupport,
            onUnlinkSupport = viewModel::unlinkSupport,
            onEnterTier = viewModel::enterTier,
            onOpenWaystone = viewModel::openWaystone,
            onExplain = viewModel::explainHeroStat,
        )
    }
    val gearActions = remember(viewModel) {
        GearActions(onSelectSlot = viewModel::selectGearSlot, onInspect = viewModel::inspectItem, onReroll = viewModel::rerollItem)
    }
    val sandboxActions = remember(viewModel) {
        SandboxActions(
            onToggle = viewModel::toggleSandbox,
            onSelectTab = viewModel::selectSandboxTab,
            onFilterSlot = viewModel::filterSandboxSlot,
            onItemLevel = viewModel::setSandboxItemLevel,
            onRarity = viewModel::setSandboxRarity,
            onSpawnBase = viewModel::spawnBase,
            onSpawnUnique = viewModel::spawnUnique,
            onLevel = viewModel::setHeroLevel,
            onRespec = viewModel::respec,
            onGrantCurrency = viewModel::grantCurrency,
            onGrantSupport = viewModel::grantSupport,
            onGrantEverything = viewModel::grantEverything,
            onToggleCaps = viewModel::toggleCaps,
            onDummy = viewModel::setDummy,
            onSpawnDummy = viewModel::spawnDummy,
            onHealDummies = viewModel::healDummies,
            onClearDummies = viewModel::clearDummies,
            onSpawnMonster = viewModel::spawnMonster,
            onResetMeter = viewModel::resetMeter,
            onExplain = viewModel::explainSandboxStat,
            onExport = { viewModel.exportBuild() },
            onImport = viewModel::importBuild,
        )
    }
    val survivalActions = remember(viewModel) {
        SurvivalActions(onToggleCamp = viewModel::toggleCamp, onEat = viewModel::eat, onDrink = viewModel::drink, onMake = viewModel::make)
    }
    val realmActions = remember(viewModel) {
        RealmActions(
            onToggle = viewModel::toggleRealm,
            onFound = viewModel::foundOutpost,
            onDeposit = viewModel::deposit,
            onBuild = { viewModel.buildStructure(it) },
            onRecruit = { viewModel.recruit(it) },
            onMuster = viewModel::muster,
            onOrder = viewModel::command,
        )
    }
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
        onUseFlask = viewModel::useFlask,
        onRevive = viewModel::revive,
        onNewRun = viewModel::newRun,
        onToggleSatchel = viewModel::toggleSatchel,
        onEquip = viewModel::equip,
        onUnequip = viewModel::unequip,
        onDiscard = viewModel::discard,
        onToggleAnvil = viewModel::toggleAnvil,
        onSelectAnvilItem = viewModel::selectAnvilItem,
        onSlotInsert = viewModel::slotInsert,
        onUnslotInsert = viewModel::unslotInsert,
        onRestyle = viewModel::restyle,
        onRerollStyle = viewModel::rerollStyle,
        onToggleStyle = viewModel::toggleStyle,
        onToggleTable = viewModel::toggleTable,
        onRollCheck = viewModel::rollCheck,
        onToggle3D = viewModel::toggle3D,
        onForgeStyle = viewModel::forgeStyle,
        onChooseQuality = viewModel::chooseQuality,
        onOpenMenu = onOpenMenu,
        onCraft = viewModel::craft,
        menuOpen = menuOpen,
        onMenuOpenChange = { menuOpen = it },
        seenHints = seenHints,
        onHintSeen = onHintSeen,
        heroActions = heroActions,
        looks = looks,
        survivalActions = survivalActions,
        realmActions = realmActions,
        gearActions = gearActions,
        sandboxActions = sandboxActions,
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
    onUseFlask: (Int) -> Unit = {},
    onRevive: () -> Unit = {},
    onNewRun: () -> Unit = {},
    onToggleSatchel: () -> Unit = {},
    onEquip: (String) -> Unit = {},
    onUnequip: (com.stratum.core.domain.item.EquipmentSlot) -> Unit = {},
    onDiscard: (String) -> Unit = {},
    onToggleAnvil: () -> Unit = {},
    onSelectAnvilItem: (String) -> Unit = {},
    onSlotInsert: (String, String) -> Unit = { _, _ -> },
    onUnslotInsert: (String, Int) -> Unit = { _, _ -> },
    onRestyle: (String) -> Unit = {},
    onRerollStyle: () -> Unit = {},
    onToggleStyle: () -> Unit = {},
    onToggleTable: () -> Unit = {},
    onRollCheck: (String) -> Unit = {},
    onToggle3D: () -> Unit = {},
    onForgeStyle: () -> Unit = {},
    onChooseQuality: (QualityTier?) -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onCraft: (String) -> Unit = {},
    /** The pause menu; hoisted so a test can show it and back can close it. */
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    seenHints: Set<String> = emptySet(),
    onHintSeen: (String) -> Unit = {},
    heroActions: HeroActions = HeroActions(),
    looks: HeroLooks = HeroLooks(),
    survivalActions: SurvivalActions = SurvivalActions(),
    realmActions: RealmActions = RealmActions(),
    gearActions: GearActions = GearActions(),
    sandboxActions: SandboxActions = SandboxActions(),
) {
    val colors = StratumTheme.colors

    // The world runs edge to edge in either orientation; the HUD floats over
    // it inside the safe area, so a notch or a gesture bar never sits on a
    // button, and nothing is a band stealing a quarter of the screen.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.surface).pinchToZoom(onZoom)) {
        val landscape = maxWidth > maxHeight
        if (state.use3D) {
            com.stratum.feature.play.gl.Scene3DView(
                world = world,
                input = com.stratum.feature.play.gl.Scene3DInput(
                    camera = state.camera,
                    zoom = state.projection.zoom,
                    player = state.player.position,
                    playerFacingX = state.player.facing.dx.toFloat(),
                    playerFacingY = state.player.facing.dy.toFloat(),
                    playerAccent = null,
                    playerClassId = state.player.heroClassId,
                    playerFlash = state.playerFlash,
                    enemies = state.enemies,
                    groundLoot = state.groundLoot,
                    groundInserts = state.groundInserts,
                    feedback = state.feedback,
                    flashFor = state.flashFor,
                    impactFor = state.impactFor,
                    highlight = state.miningTarget,
                    buildPreview = state.buildPreview,
                    buildAffordable = state.buildAffordable,
                    buildMode = state.buildMode,
                    director = state.artDirector,
                    kit = state.kit,
                    kitOverlays = state.kitOverlays,
                    quality = state.quality,
                    time = state.worldTime,
                    biomeAt = state.biomeAt,
                    revision = state.worldRevision,
                    frame = state.frame,
                    spriteFor = state.spriteFor,
                    playerAnimation = state.playerAnimation,
                    animationFor = state.animationFor,
                    propModels = state.propModels,
                    projectiles = state.projectiles,
                    zones = state.zones,
                    telegraphs = state.telegraphs,
                ),
                modifier = Modifier.fillMaxSize(),
                onTapBlock = onTapBlock,
                onLongPressBlock = onLongPressBlock,
                onBuildDrag = onBuildDrag,
                onBuildCommit = onBuildCommit,
            )
        } else {
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
                projectiles = state.projectiles,
                zones = state.zones,
                telegraphs = state.telegraphs,
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
                artDirector = state.artDirector,
                worldTime = state.worldTime,
                biomeAt = state.biomeAt,
                revision = state.worldRevision,
                frame = state.frame,
                modifier = Modifier.fillMaxSize(),
                onTapBlock = onTapBlock,
                onLongPressBlock = onLongPressBlock,
            )
        }

        val menuEntries = dockEntries(state, onToggleSatchel, onToggleAnvil, heroActions.onClose, onToggleTable, onToggleStyle, onToggle3D, survivalActions.onToggleCamp, realmActions.onToggle) +
            listOfNotNull(DockEntry("🧪", "Sandbox", sandboxActions.onToggle, active = state.sandbox.open).takeIf { state.sandbox.active })

        if (!state.isDead) {
            Hud(
                state = state,
                world = world,
                landscape = landscape,
                onMoveInput = onMoveInput,
                onStopMining = onStopMining,
                onDodge = onDodge,
                onAttack = onAttack,
                onCastSkill = onCastSkill,
                onUseFlask = onUseFlask,
                onToggleBuild = onToggleBuild,
                onSelectBuildTool = onSelectBuildTool,
                onSelectSlot = onSelectSlot,
                onOpenMenu = { onMenuOpenChange(true) },
                menuBadge = menuBadge(menuEntries),
                onDrink = survivalActions.onDrink,
                onOpenSandbox = sandboxActions.onToggle,
                // Nothing to teach while paused: the hint waits for the world.
                seenHints = if (menuOpen) Hints.all else seenHints,
                onHintSeen = onHintSeen,
            )
        }

        if (state.tableOpen && !state.isDead) {
            TableOverlay(state = state, onRoll = onRollCheck, onClose = onToggleTable)
        }

        if (state.styleOpen && !state.isDead) {
            StyleOverlay(
                state = state,
                onRestyle = onRestyle,
                onReroll = onRerollStyle,
                onClose = onToggleStyle,
                onForge = onForgeStyle,
                onChooseQuality = onChooseQuality,
            )
        }

        if (state.satchelOpen && !state.anvilOpen && !state.isDead) {
            SatchelOverlay(
                state = state,
                world = world,
                onEquip = onEquip,
                onUnequip = onUnequip,
                onDiscard = onDiscard,
                // The two panels are one errand: read the item here, socket
                // it next door, without going back out to the world first.
                onOpenAnvil = {
                    onToggleSatchel()
                    onToggleAnvil()
                },
                onClose = onToggleSatchel,
                modifier = Modifier.fillMaxSize(),
                actions = gearActions,
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
                onCraft = onCraft,
            )
        }

        if (state.campOpen && !state.isDead) {
            CampOverlay(
                panel = state.survival,
                onEat = survivalActions.onEat,
                onDrink = survivalActions.onDrink,
                onMake = survivalActions.onMake,
                onClose = survivalActions.onToggleCamp,
            )
        }

        if (state.realmOpen && !state.isDead) {
            RealmOverlay(panel = state.realm, actions = realmActions)
        }

        if (state.hero.open && !state.isDead) {
            HeroOverlay(state = state, actions = heroActions, looks = looks)
        }

        if (state.sandbox.open && !state.isDead) {
            SandboxOverlay(state = state, actions = sandboxActions)
        }

        if (menuOpen && !state.isDead) {
            PauseMenu(
                state = state,
                // Choosing a system closes the menu and opens that system's panel.
                entries = menuEntries.map { entry -> entry.copy(onClick = { onMenuOpenChange(false); entry.onClick() }) },
                onZoom = onZoom,
                onResume = { onMenuOpenChange(false) },
                onQuit = {
                    onMenuOpenChange(false)
                    onOpenMenu()
                },
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
                            label = "Save & quit",
                            onClick = onOpenMenu,
                            emphasis = ActionEmphasis.QUIET,
                        )
                    }
                }
            }
        }

        SavedCue(
            notice = state.saveNotice,
            // Under the menu button, where "Save & quit" lives: out of both thumbs' way.
            modifier = Modifier.align(Alignment.TopEnd).safeContent().padding(top = SAVED_CUE_TOP, end = Space.medium),
        )
    }
}

/**
 * A quiet word that the world was written down, shown for a moment after
 * each save lands. Keyed on the notice's serial, so two saves in a row both
 * show, and nothing shows until the first save of this screen.
 */
@Composable
private fun SavedCue(notice: SaveNotice?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<SaveNotice?>(null) }
    LaunchedEffect(notice?.serial) {
        if (notice == null) return@LaunchedEffect
        shown = notice
        kotlinx.coroutines.delay(SAVED_CUE_MILLIS)
        shown = null
    }
    val visible = shown ?: return
    val colors = StratumTheme.colors
    Text(
        text = "✓ ${visible.message}",
        style = MaterialTheme.typography.labelSmall,
        color = colors.accentAlt,
        modifier = modifier
            .background(colors.surface.copy(alpha = 0.78f), MaterialTheme.shapes.small)
            .padding(horizontal = Space.small, vertical = Space.tight),
    )
}

private const val SAVED_CUE_MILLIS = 1_500L

/** Below the menu button and its label. */
private val SAVED_CUE_TOP = 88.dp

/**
 * The play HUD, laid out for the way the phone is held: vitals and where you
 * are in the top-left corner, the one menu button top-right, the stick and
 * Build under the left thumb, Strike with the skills around it under the
 * right. Hints and news fade in above the world and fade out again.
 */
@Composable
private fun Hud(
    state: PlayUiState,
    world: World,
    landscape: Boolean,
    onMoveInput: (Float, Float) -> Unit,
    onStopMining: () -> Unit,
    onDodge: () -> Unit,
    onAttack: () -> Unit,
    onCastSkill: (String) -> Unit,
    onUseFlask: (Int) -> Unit,
    onToggleBuild: () -> Unit,
    onSelectBuildTool: (BuildTool) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onOpenMenu: () -> Unit,
    menuBadge: String?,
    onDrink: () -> Unit,
    onOpenSandbox: () -> Unit = {},
    seenHints: Set<String> = emptySet(),
    onHintSeen: (String) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeContent().padding(Space.medium)) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.tight)) {
                VitalsCard(state)
                PlaceTag(state)
                // A sandbox shows its meter under the vitals: the one number a build is being tuned for.
                if (state.sandbox.active) MeterChip(state.sandbox.meter, onOpenSandbox)
            }
            Spacer(Modifier.weight(1f))
            GameButton(
                glyph = "☰",
                label = "Menu",
                onClick = onOpenMenu,
                size = DOCK_BUTTON,
                badge = menuBadge,
                emphasis = if (menuBadge != null) GameEmphasis.PRIMARY else GameEmphasis.NORMAL,
            )
        }

        // In landscape the top row has room between vitals and menu; in
        // portrait the news sits a little way down, clear of both.
        HintAndToast(
            state = state,
            seenHints = seenHints,
            onHintSeen = onHintSeen,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (landscape) 0.dp else maxHeight * HINT_TOP_FRACTION)
                .padding(horizontal = if (landscape) VITALS_WIDTH + Space.medium else 0.dp),
        )

        // Building is a mode, not a move, so it sits with the stick rather
        // than among the fight buttons; it shows what is in hand.
        if (!state.buildMode) {
            val held = state.player.selectedBlockId
            GameButton(
                glyph = "⛏",
                label = if (held != null) "Build ${state.player.countOf(held)}" else "Build",
                onClick = onToggleBuild,
                size = DOCK_BUTTON,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = STICK_SIZE + Space.medium),
            )
        }

        // Movement: under the left thumb, see-through so the world shows.
        StratumJoystick(
            size = STICK_SIZE,
            modifier = Modifier.align(Alignment.BottomStart),
            onDirection = { x, y ->
                // Walking away from a block you were digging stops digging it.
                if (x != 0f || y != 0f) onStopMining()
                onMoveInput(x, y)
            },
        )

        if (state.buildMode) {
            // Portrait: across the screen above both thumbs. Landscape:
            // between them, where there is room and the world is not.
            BuildTray(
                state, world, onSelectBuildTool, onSelectSlot,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (landscape) 0.dp else STICK_SIZE + Space.large)
                    .fillMaxWidth(if (landscape) 0.52f else 1f),
            )
        }

        state.miningTarget?.let { target ->
            MiningBar(
                name = world.blockAt(target).displayName,
                fraction = state.miningFraction,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = STICK_SIZE + Space.large),
            )
        }

        if (state.buildMode) {
            ActionCluster(
                primaryGlyph = "✔",
                primaryLabel = "Done",
                onPrimary = onToggleBuild,
                primaryTint = StratumTheme.colors.accentAlt,
                around = emptyList(),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        } else {
            ActionCluster(
                primaryGlyph = "⚔",
                primaryLabel = "Strike",
                onPrimary = onAttack,
                around = fightButtons(state, onDodge, onCastSkill, onUseFlask) +
                    // Water in reach offers a drink where the thumb already is.
                    listOfNotNull(ArcButton("💧", "Drink", onDrink).takeIf { state.survival.canDrink }),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** Roll first, beside the thumb's rest, then each skill up the arc, then the flasks. */
private fun fightButtons(state: PlayUiState, onDodge: () -> Unit, onCastSkill: (String) -> Unit, onUseFlask: (Int) -> Unit): List<ArcButton> {
    val roll = ArcButton("🌀", "Roll", onDodge, cooldown = state.rollCooldownFraction, enabled = state.rollCooldownFraction <= 0f)
    val skills = state.skills.map { skill ->
        ArcButton(
            glyph = skill.name.first().uppercase(),
            label = skill.name.substringBefore(' '),
            onClick = { onCastSkill(skill.id) },
            tint = Color(skill.color),
            cooldown = state.cooldownFraction(skill),
            enabled = state.canAfford(skill),
            // Lit while it winds up, so a cast time reads as the skill working rather than the button ignoring the thumb.
            active = state.isWindingUp(skill),
            badge = skillBadge(state, skill),
        )
    }
    // A flask's ring drains as its charges do, and its badge counts the drinks left.
    val flasks = state.flasks.mapIndexed { slot, flask ->
        ArcButton(
            glyph = flask.definition.glyph,
            label = flask.definition.name.substringBefore(' '),
            onClick = { onUseFlask(slot) },
            tint = Color(flask.definition.color),
            cooldown = 1f - flask.fill,
            enabled = flask.canDrink,
            badge = flask.uses.toString(),
        )
    }
    return listOf(roll) + skills + flasks
}

/**
 * A skill button's corner: charges ready for a skill that stores several,
 * otherwise the life a cast takes when life pays for it -- the one number
 * that decides whether to press now.
 */
internal fun skillBadge(state: PlayUiState, skill: com.stratum.core.domain.actor.SkillDefinition): String? {
    if (skill.charges > 1) return state.chargesLeft(skill).toString()
    val life = state.costOf(skill).life
    return if (life > 0) "♥$life" else null
}

/** The menu's entries, each shown only when the loaded packs give it something to do. */
private fun dockEntries(
    state: PlayUiState,
    onToggleSatchel: () -> Unit,
    onToggleAnvil: () -> Unit,
    onToggleHero: () -> Unit,
    onToggleTable: () -> Unit,
    onToggleStyle: () -> Unit,
    onToggle3D: () -> Unit,
    onToggleCamp: () -> Unit,
    onToggleRealm: () -> Unit,
): List<DockEntry> {
    val points = state.player.unspentPassivePoints
    val craftables = state.heldInserts.sumOf { it.count } + state.heldCurrency.sumOf { it.count }
    return listOfNotNull(
        DockEntry("🎒", "Bag", onToggleSatchel, badge = state.player.bag.size.takeIf { it > 0 }?.toString(), active = state.satchelOpen),
        DockEntry("⚒", "Anvil", onToggleAnvil, badge = craftables.takeIf { it > 0 }?.toString(), active = state.anvilOpen),
        DockEntry("✦", "Hero", onToggleHero, badge = points.takeIf { it > 0 }?.let { "+$it" }, active = state.hero.open, calling = points > 0),
        DockEntry("🎲", "Table", onToggleTable, badge = state.activeBoons.size.takeIf { it > 0 }?.toString(), active = state.tableOpen)
            .takeIf { state.checks.isNotEmpty() },
        DockEntry("🏕", "Camp", onToggleCamp, badge = "!".takeIf { state.survival.anyLow }, active = state.campOpen, calling = state.survival.anyLow)
            .takeIf { state.survival.active },
        DockEntry("🏰", "Realm", onToggleRealm, badge = state.realm.followers.takeIf { it > 0 }?.toString(), active = state.realmOpen)
            .takeIf { state.realm.active },
        DockEntry("🎨", "Style", onToggleStyle, badge = state.forgeProgress?.takeUnless { it.isFinished }?.let { "${(it.fraction * 100).toInt()}%" }, active = state.styleOpen),
        DockEntry(if (state.use3D) "3D" else "2D", "View", onToggle3D),
    )
}

/** How far down a portrait screen the hints sit: under the vitals, above the fight. */
private const val HINT_TOP_FRACTION = 0.26f
