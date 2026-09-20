package com.stratum.feature.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.feature.studio.event.CombatUiAction
import com.stratum.feature.studio.state.CombatArenaUiState
import com.stratum.legacy.domain.ActiveLootDrop
import com.stratum.legacy.domain.ActiveProjectile
import com.stratum.legacy.domain.CharacterAnimationState
import com.stratum.legacy.domain.CombatImpactSpark
import com.stratum.legacy.domain.ArpgWorldState
import com.stratum.legacy.domain.DungeonMapData
import com.stratum.legacy.domain.FloatingCombatText
import com.stratum.legacy.domain.GameMode
import com.stratum.legacy.domain.HeroCombatState
import com.stratum.legacy.domain.MonsterArchetype
import com.stratum.legacy.domain.MonsterEntity
import com.stratum.legacy.domain.PreloadedSpritePacks
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.TileType
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.NamingSystem
import com.stratum.legacy.domain.GameThemeProfile
import com.stratum.legacy.domain.CustomStyleOptions
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CHAPTER 15: PRESENTATION VIEW - ACTION RPG COMBAT ARENA SCREEN
 *
 * Real-time isometric/top-down canvas featuring:
 * - Smooth camera following the Igbo hero
 * - Virtual joystick with auto-centering
 * - Tactile Tactile-inspired punchy ability action buttons
 * - Classic Action RPG red (Life) and blue (Mana/Spirit) liquid globes
 * - Floating damage numbers, lightning bolts, solar novas
 * - Colored loot drop beams with tap-to-pickup
 * - Mini-status bar & wave indicators
 */

@Composable
fun CombatArenaScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val hudStats by viewModel.combatHudStats.collectAsState()
    val renderTick by viewModel.renderTick.collectAsState()
    val activeSprite by viewModel.activeSprite.collectAsState()
    val isIsometricView by viewModel.isIsometricView.collectAsState()
    val spriteScale by viewModel.spriteScale.collectAsState()
    val currentMap by viewModel.currentMap.collectAsState()
    val currentGameMode by viewModel.currentGameMode.collectAsState()
    val isCombatPaused by viewModel.isCombatPaused.collectAsState()
    val diabloEra by viewModel.diabloEra.collectAsState()
    val namingSystem by viewModel.namingSystem.collectAsState()
    val activeTheme by viewModel.activeTheme.collectAsState()
    val customStyle by viewModel.customStyle.collectAsState()
    val potionBeltCounts by viewModel.potionBeltCounts.collectAsState()

    val heroBitmap = remember(activeSprite.id, activeSprite.rawImageUriOrBase64) {
        viewModel.getSpriteBitmap(activeSprite)
    }

    val monsterBitmaps = remember {
        viewModel.getMonsterBitmaps()
    }

    val state = CombatArenaUiState(
        hudStats = hudStats,
        renderTick = renderTick,
        activeSprite = activeSprite,
        isIsometricView = isIsometricView,
        spriteScale = spriteScale,
        currentMap = currentMap,
        currentGameMode = currentGameMode,
        isCombatPaused = isCombatPaused,
        diabloEra = diabloEra,
        namingSystem = namingSystem,
        activeTheme = activeTheme,
        customStyle = customStyle,
        potionBeltCounts = potionBeltCounts,
        engineState = viewModel.engineState,
        heroBitmap = heroBitmap,
        monsterBitmaps = monsterBitmaps
    )

    CombatArenaScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is CombatUiAction.JoystickMove -> viewModel.updateJoystick(action.x, action.y)
                CombatUiAction.PrimaryAttack -> viewModel.triggerAttack()
                CombatUiAction.ThunderSkill -> viewModel.triggerThunder()
                CombatUiAction.SunNovaSkill -> viewModel.triggerSunNova()
                CombatUiAction.Dash -> viewModel.triggerDash()
                is CombatUiAction.DrinkPotion -> viewModel.drinkBeltPotion(action.slot)
                CombatUiAction.TogglePause -> viewModel.pauseCombat()
                CombatUiAction.ResumeCombat -> viewModel.resumeCombat()
                is CombatUiAction.ToggleDummyMode -> viewModel.toggleDummyMode(action.isDummy)
                CombatUiAction.ResetCombatStats -> viewModel.resetCombatStats()
                CombatUiAction.CycleEra -> {
                    val eras = DiabloEra.values()
                    val nextEra = eras[(diabloEra.ordinal + 1) % eras.size]
                    viewModel.setDiabloEra(nextEra)
                }
                CombatUiAction.CycleNaming -> {
                    val namings = NamingSystem.values()
                    val nextNaming = namings[(namingSystem.ordinal + 1) % namings.size]
                    viewModel.setNamingSystem(nextNaming)
                }
                is CombatUiAction.SetEra -> viewModel.setDiabloEra(action.era)
                is CombatUiAction.SetNaming -> viewModel.setNamingSystem(action.system)
                CombatUiAction.ToggleIsometric -> viewModel.toggleIsometricView()
                is CombatUiAction.SetSpriteScale -> viewModel.setSpriteScale(action.scale)
                CombatUiAction.VacuumLoot -> viewModel.pickUpAllNearbyLoot()
                is CombatUiAction.PickupLoot -> viewModel.pickUpLoot(action.dropId)
                CombatUiAction.RestartWave -> viewModel.resetWholeGame()
                CombatUiAction.ResetGame -> viewModel.resetWholeGame()
                is CombatUiAction.NavigateTo -> viewModel.navigateTo(action.screen)
                CombatUiAction.OpenSettings -> viewModel.setSettingsMenuOpen(true)
                CombatUiAction.DismissLoot -> {}
            }
        },
        pauseOverlayContent = {
            CombatArenaPauseOverlay(
                viewModel = viewModel,
                onResume = { viewModel.resumeCombat() }
            )
        },
        modifier = modifier
    )
}

@Composable
fun CombatArenaScreen(
    state: CombatArenaUiState,
    onAction: (CombatUiAction) -> Unit,
    pauseOverlayContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val hudStats = state.hudStats
    val renderTick = state.renderTick
    val activeSprite = state.activeSprite
    val isIsometricView = state.isIsometricView
    val spriteScale = state.spriteScale
    val currentMap = state.currentMap
    val currentGameMode = state.currentGameMode
    val engineState = state.engineState
    val isCombatPaused = state.isCombatPaused
    val diabloEra = state.diabloEra
    val namingSystem = state.namingSystem
    val activeTheme = state.activeTheme
    val customStyle = state.customStyle
    val potionBeltCounts = state.potionBeltCounts
    val heroBitmap = state.heroBitmap
    val monsterBitmaps = state.monsterBitmaps

    var selectedLootDrop by remember { mutableStateOf<ActiveLootDrop?>(null) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // Pre-allocated text paint for zero allocation during combat text rendering
    val combatTextPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .testTag("combat_arena_screen")
    ) {
        val arenaWidth = constraints.maxWidth.toFloat()
        val arenaHeight = constraints.maxHeight.toFloat()
        val centerX = arenaWidth / 2f
        val centerY = arenaHeight / 2f

        // Real-Time Combat Canvas - redraws at high FPS without triggering Compose tree recompositions
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val (heroScreenX, heroScreenY) = if (isIsometricView) {
                                DungeonMapData.toIsometric(engineState.hero.x, engineState.hero.y)
                            } else {
                                Pair(engineState.hero.x, engineState.hero.y)
                            }
                            val camOffsetX = centerX - heroScreenX
                            val camOffsetY = centerY - heroScreenY
                            val worldTouchX = offset.x - camOffsetX
                            val worldTouchY = offset.y - camOffsetY
                            val (calcX, calcY) = if (isIsometricView) {
                                DungeonMapData.fromIsometric(worldTouchX, worldTouchY)
                            } else {
                                Pair(worldTouchX, worldTouchY)
                            }
                            val drop = engineState.lootDrops.find { d ->
                                val dx = d.x - calcX
                                val dy = d.y - calcY
                                (dx * dx + dy * dy) < (60f * 60f)
                            }
                            if (drop != null) {
                                selectedLootDrop = drop
                                onAction(CombatUiAction.PickupLoot(drop.id))
                            }
                        },
                        onDrag = { _, _ -> }
                    )
                }
        ) {
            // Reading renderTick inside Canvas draw scope triggers ONLY the hardware draw pass
            renderTick.let { _ -> }

            val hero = engineState.hero
            var shakeOffsetX = 0f
            var shakeOffsetY = 0f
            if (engineState.screenShakeIntensity > 0.05f) {
                shakeOffsetX = (kotlin.random.Random.nextFloat() - 0.5f) * engineState.screenShakeIntensity * 2.2f
                shakeOffsetY = (kotlin.random.Random.nextFloat() - 0.5f) * engineState.screenShakeIntensity * 2.2f
            }

            val (heroScreenPosFst, heroScreenPosSnd) = if (isIsometricView) {
                DungeonMapData.toIsometric(hero.x, hero.y)
            } else {
                Pair(hero.x, hero.y)
            }
            val camX = centerX - heroScreenPosFst + shakeOffsetX
            val camY = centerY - heroScreenPosSnd + shakeOffsetY

            // 1. Draw Ancient Igbo Dungeon Ground (Isometric diamonds or Ortho tiles)
            drawArenaGround(currentMap, isIsometricView, spriteScale, camX, camY)

            // 2. Draw Loot Drops with Beams of Light
            drawLootDrops(engineState.lootDrops, camX, camY, isIsometricView)

            // 3. Draw Projectiles (Amadioha Lightning Bolts)
            drawProjectiles(engineState.projectiles, camX, camY, isIsometricView)

            // 4. Draw Monsters (With animated pixel-art sprites, hit flash, and stagger)
            drawMonsters(engineState.monsters, camX, camY, isIsometricView, spriteScale, monsterBitmaps)

            // 5. Draw Hero Avatar (Animated Pixel Art Sprite Sheet)
            drawHero(hero, camX, camY, isIsometricView, spriteScale, activeSprite, heroBitmap)

            // 6. Draw Slash Arc cleave effect
            drawSlashArc(hero, engineState.lastSlashAngleRad, engineState.slashArcTimerMs, camX, camY, isIsometricView)

            // 7. Draw Impact Sparks & Combat Debris
            drawImpactSparks(engineState.impactSparks, camX, camY, isIsometricView)

            // 8. Draw Floating Combat Text
            drawCombatTexts(engineState.combatTexts, camX, camY, isIsometricView, combatTextPaint)
        }

        // Top Status & Control Bars (Respecting cutout camera & safe drawing bounds)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 4.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ArenaTopHud(
                stats = hudStats,
                gameMode = currentGameMode,
                currentEra = diabloEra,
                currentNaming = namingSystem,
                onCycleEra = {
                    val eras = DiabloEra.values()
                    val nextEra = eras[(diabloEra.ordinal + 1) % eras.size]
                    onAction(CombatUiAction.SetEra(nextEra))
                },
                onCycleNaming = {
                    val namings = NamingSystem.values()
                    val nextNaming = namings[(namingSystem.ordinal + 1) % namings.size]
                    onAction(CombatUiAction.SetNaming(nextNaming))
                },
                onPause = { onAction(CombatUiAction.TogglePause) },
                onToggleDummy = { onAction(CombatUiAction.ToggleDummyMode(!hudStats.isDummy)) },
                onResetStats = { onAction(CombatUiAction.ResetCombatStats) }
            )

            // Quick View & Engine Control Bar
            ArenaQuickControlsBar(
                isIsometric = isIsometricView,
                scale = spriteScale,
                lootCount = engineState.lootDrops.size,
                onToggleIsometric = { onAction(CombatUiAction.ToggleIsometric) },
                onScaleChange = { onAction(CombatUiAction.SetSpriteScale(it)) },
                onVacuumLoot = { onAction(CombatUiAction.VacuumLoot) },
                onResetGame = { showResetConfirmDialog = true }
            )
        }

        // Bottom Action RPG Orbs & Virtual Controls (Adaptive Diablo Era HUD Chameleon)
        DiabloEraHudOverlay(
            era = diabloEra,
            customStyle = customStyle,
            activeTheme = activeTheme,
            namingSystem = namingSystem,
            stats = hudStats,
            potionBeltCounts = potionBeltCounts,
            onDrinkPotion = { onAction(CombatUiAction.DrinkPotion(it)) },
            onJoystickMove = { x, y -> onAction(CombatUiAction.JoystickMove(x, y)) },
            onAttack = { onAction(CombatUiAction.PrimaryAttack) },
            onThunder = { onAction(CombatUiAction.ThunderSkill) },
            onSunNova = { onAction(CombatUiAction.SunNovaSkill) },
            onDash = { onAction(CombatUiAction.Dash) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = 6.dp, start = 8.dp, end = 8.dp)
        )

        // Reset Game Confirmation Dialog
        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text("Reset Entire Game Session?", color = IgboArtColors.AnyanwuGold, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will reset your hero to Wave 1, restore full health and spirit, clear arena monsters, and respawn initial spawns. Continue?",
                        color = IgboArtColors.SacredIvory
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onAction(CombatUiAction.ResetGame)
                            showResetConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.LifeGlobeRed)
                    ) {
                        Text("Reset Now", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) {
                        Text("Cancel", color = IgboArtColors.SacredSand)
                    }
                },
                containerColor = IgboArtColors.NsibidiCard
            )
        }

        // Loot Pickup Modal / Toast
        selectedLootDrop?.let { drop ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold))),
                    modifier = Modifier.clickable { selectedLootDrop = null }
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "LOOT DISCOVERED!",
                            style = MaterialTheme.typography.labelLarge,
                            color = IgboArtColors.AnyanwuGold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = drop.weapon.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(android.graphics.Color.parseColor(drop.weapon.rarity.colorHex))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${drop.weapon.rarity.displayName} • Lv.${drop.weapon.itemLevel} • ${drop.weapon.minDamage}-${drop.weapon.maxDamage} Dmg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = IgboArtColors.SacredIvory
                        )
                        if (drop.weapon.specialMechanic.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = drop.weapon.specialMechanic,
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.BronzeLight
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "✓ Added to Inventory (Tap to close)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF81C784)
                        )
                    }
                }
            }
        }

        // Dedicated Pause Overlay
        if (isCombatPaused) {
            pauseOverlayContent()
        }
    }
}

private fun DrawScope.drawArenaGround(
    map: DungeonMapData,
    isIsometric: Boolean,
    scale: Float,
    camX: Float,
    camY: Float
) {
    val halfW = map.halfWidthPixels
    val halfH = map.halfHeightPixels
    val tileSize = map.tileSize

    val floorBg = try {
        Color(android.graphics.Color.parseColor(map.biome.floorColorHex))
    } catch (_: Exception) {
        Color(0xFF1B2E1E)
    }
    drawRect(color = floorBg)

    for (tile in map.tiles) {
        val worldX = (tile.gridX * tileSize + tileSize / 2f) - halfW
        val worldY = (tile.gridY * tileSize + tileSize / 2f) - halfH

        val (posX, posY) = if (isIsometric) {
            DungeonMapData.toIsometric(worldX, worldY)
        } else {
            Pair(worldX, worldY)
        }

        val screenX = posX + camX
        val screenY = posY + camY

        val tileColor = when (tile.type) {
            TileType.FLOOR_BASIC -> floorBg
            TileType.FLOOR_ORNATE -> try {
                Color(android.graphics.Color.parseColor(map.biome.primaryColorHex)).copy(alpha = 0.35f)
            } catch (_: Exception) {
                IgboArtColors.BronzePrimary.copy(alpha = 0.35f)
            }
            TileType.FLOOR_NSIBIDI_SEAL -> IgboArtColors.AnyanwuGold.copy(alpha = 0.45f)
            TileType.WALL_STONE -> try {
                Color(android.graphics.Color.parseColor(map.biome.wallColorHex))
            } catch (_: Exception) {
                Color(0xFF0D1F10)
            }
            TileType.PILLAR_BRONZE -> IgboArtColors.BronzePrimary
            TileType.TORCH_BRAZIER -> Color(0xFFFF6D00)
            TileType.SHRINE_OFO -> IgboArtColors.AmadiohaCyan
            TileType.WATER_POOL -> Color(0xFF00B0FF).copy(alpha = 0.55f)
        }

        if (isIsometric) {
            val halfTileW = (tileSize * 0.866f) / 2f
            val halfTileH = (tileSize * 0.5f) / 2f
            val path = Path().apply {
                moveTo(screenX, screenY - halfTileH)
                lineTo(screenX + halfTileW, screenY)
                lineTo(screenX + halfTileW * 0f, screenY + halfTileH)
                lineTo(screenX - halfTileW, screenY)
                close()
            }
            drawPath(path, color = tileColor)
            drawPath(path, color = Color.Black.copy(alpha = 0.2f), style = Stroke(width = 1f))
        } else {
            drawRect(
                color = tileColor,
                topLeft = Offset(screenX - tileSize / 2f, screenY - tileSize / 2f),
                size = Size(tileSize - 2f, tileSize - 2f)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.15f),
                topLeft = Offset(screenX - tileSize / 2f, screenY - tileSize / 2f),
                size = Size(tileSize - 2f, tileSize - 2f),
                style = Stroke(width = 1f)
            )
        }
    }
}

private fun DrawScope.drawLootDrops(
    drops: List<ActiveLootDrop>,
    camX: Float,
    camY: Float,
    isIsometric: Boolean
) {
    drops.forEach { drop ->
        val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(drop.x, drop.y) else Pair(drop.x, drop.y)
        val screenX = posX + camX
        val screenY = posY + camY
        val rarityColor = Color(drop.weapon.rarity.colorArgb)

        // Vertical Action RPG-style glowing loot beam
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(rarityColor.copy(alpha = 0.9f), rarityColor.copy(alpha = 0f)),
                startY = screenY,
                endY = screenY - 90f
            ),
            start = Offset(screenX, screenY),
            end = Offset(screenX, screenY - 90f),
            strokeWidth = 5f
        )

        // Drop item icon/gem
        drawCircle(
            color = rarityColor,
            radius = 10f,
            center = Offset(screenX, screenY)
        )
        drawCircle(
            color = Color.White,
            radius = 4f,
            center = Offset(screenX, screenY)
        )
    }
}

private fun DrawScope.drawProjectiles(
    projs: List<ActiveProjectile>,
    camX: Float,
    camY: Float,
    isIsometric: Boolean
) {
    projs.forEach { proj ->
        val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(proj.currentX, proj.currentY) else Pair(proj.currentX, proj.currentY)
        val screenX = posX + camX
        val screenY = posY + camY
        val color = if (proj.isHostile) {
            try {
                Color(android.graphics.Color.parseColor(proj.colorHex))
            } catch (e: Exception) {
                Color(0xFFAB47BC)
            }
        } else {
            Color(proj.damageType.colorArgb)
        }

        // Hostile or Friendly Projectile Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = if (proj.isHostile) 0.45f else 0.2f), Color.Transparent),
                center = Offset(screenX, screenY),
                radius = proj.radius * 2.4f
            ),
            radius = proj.radius * 2.4f,
            center = Offset(screenX, screenY)
        )
        // Core bolt
        drawCircle(
            color = if (proj.isHostile) Color(0xFFFFEB3B) else Color.White,
            radius = proj.radius * 0.7f,
            center = Offset(screenX, screenY)
        )
    }
}

private fun DrawScope.drawMonsters(
    monsters: List<MonsterEntity>,
    camX: Float,
    camY: Float,
    isIsometric: Boolean,
    spriteScale: Float,
    monsterBitmaps: Map<String, android.graphics.Bitmap>
) {
    monsters.forEach { monster ->
        val staggerJitterX = if (monster.staggerMs > 0L) (kotlin.random.Random.nextFloat() - 0.5f) * 6f else 0f
        val staggerJitterY = if (monster.staggerMs > 0L) (kotlin.random.Random.nextFloat() - 0.5f) * 6f else 0f

        val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(monster.x, monster.y) else Pair(monster.x, monster.y)
        val screenX = posX + camX + staggerJitterX
        val screenY = posY + camY + staggerJitterY
        val bodyRadius = (if (monster.isBoss) 30f else 18f) * spriteScale

        // Tactical AI Ground Telegraphs (Visual Warning Indicators)
        if (monster.telegraphMs > 0L) {
            val telegraphProgress = (1f - (monster.telegraphMs.toFloat() / monster.telegraphMaxMs.coerceAtLeast(1L).toFloat())).coerceIn(0f, 1f)
            when (monster.archetype) {
                MonsterArchetype.IKENGA_GOLEM -> {
                    // Bronze Earthquake Ground Slam Warning Zone
                    val slamRadius = 75f * spriteScale
                    drawCircle(
                        color = Color(0xFFFF8F00).copy(alpha = 0.22f + telegraphProgress * 0.35f),
                        radius = slamRadius,
                        center = Offset(screenX, screenY)
                    )
                    drawCircle(
                        color = Color(0xFFFFD54F),
                        radius = slamRadius * telegraphProgress,
                        center = Offset(screenX, screenY),
                        style = Stroke(width = 3.5f)
                    )
                }
                MonsterArchetype.ANCIENT_BRONZE_COLOSSUS -> {
                    // Boss Titan Stomp Danger Zone
                    val stompRadius = 95f * spriteScale
                    drawCircle(
                        color = Color(0xFFFF1744).copy(alpha = 0.25f + telegraphProgress * 0.40f),
                        radius = stompRadius,
                        center = Offset(screenX, screenY)
                    )
                    drawCircle(
                        color = Color(0xFFFF1744),
                        radius = stompRadius * telegraphProgress,
                        center = Offset(screenX, screenY),
                        style = Stroke(width = 4.5f)
                    )
                }
                MonsterArchetype.CORRUPTED_DIBIA -> {
                    // Shaman Void Spellcast Concentric Rune
                    val runeRadius = 42f * spriteScale
                    drawCircle(
                        color = Color(0xFFAB47BC).copy(alpha = 0.30f + telegraphProgress * 0.35f),
                        radius = runeRadius,
                        center = Offset(screenX, screenY)
                    )
                    drawCircle(
                        color = Color(0xFFE1BEE7),
                        radius = runeRadius * (0.4f + telegraphProgress * 0.6f),
                        center = Offset(screenX, screenY),
                        style = Stroke(width = 2.5f)
                    )
                }
                MonsterArchetype.EKPE_LEOPARD_WARRIOR -> {
                    // Ambush Predator Pounce Leap Vector
                    val leapLen = 100f * spriteScale
                    val endX = screenX + cos(monster.facingAngleRad) * leapLen * telegraphProgress
                    val endY = screenY + sin(monster.facingAngleRad) * leapLen * telegraphProgress
                    drawLine(
                        color = Color(0xFFFF1744).copy(alpha = 0.5f + telegraphProgress * 0.5f),
                        start = Offset(screenX, screenY),
                        end = Offset(endX, endY),
                        strokeWidth = 3.5f
                    )
                    drawCircle(
                        color = Color(0xFFFF1744),
                        radius = 8f * spriteScale,
                        center = Offset(endX, endY)
                    )
                }
                else -> {}
            }
        }

        // Shadow
        drawOval(
            color = Color.Black.copy(alpha = 0.45f),
            topLeft = Offset(screenX - bodyRadius * 1.1f, screenY + bodyRadius * 0.7f),
            size = Size(bodyRadius * 2.2f, bodyRadius * 0.7f)
        )

        // Animated Pixel-Art Monster Sprite
        val mBitmap = monsterBitmaps[monster.archetypeId] ?: monsterBitmaps["arch_forest_mmo"]
        if (mBitmap != null) {
            val cols = 4
            val rows = 4
            val frameW = mBitmap.width / cols
            val frameH = mBitmap.height / rows
            val animRow = when (monster.animationState) {
                CharacterAnimationState.IDLE -> 0
                CharacterAnimationState.WALK -> 1
                CharacterAnimationState.ATTACK -> 2
                CharacterAnimationState.HURT -> 3
                else -> 0
            }
            val curFrame = ((System.currentTimeMillis() / 150) % cols).toInt()
            val srcRect = android.graphics.Rect(curFrame * frameW, animRow * frameH, (curFrame + 1) * frameW, (animRow + 1) * frameH)
            val scaledW = (bodyRadius * 2.2f).toInt()
            val scaledH = (bodyRadius * 2.2f).toInt()
            val dstRect = android.graphics.Rect(
                (screenX - scaledW / 2).toInt(),
                (screenY - scaledH / 2).toInt(),
                (screenX + scaledW / 2).toInt(),
                (screenY + scaledH / 2).toInt()
            )
            drawContext.canvas.nativeCanvas.drawBitmap(mBitmap, srcRect, dstRect, null)
        } else {
            val archColor = Color(monster.archetype.colorArgb)
            drawCircle(color = archColor, radius = bodyRadius, center = Offset(screenX, screenY))
        }

        // Impact flash red/gold ring glow
        if (monster.hitFlashMs > 0L) {
            val flashAlpha = (monster.hitFlashMs / 180f).coerceIn(0f, 1f)
            drawCircle(
                color = Color(0xFFFF1744).copy(alpha = flashAlpha * 0.8f),
                radius = bodyRadius * 1.4f,
                center = Offset(screenX, screenY),
                style = Stroke(width = 4f)
            )
        }

        // Health Bar above monster
        val barWidth = bodyRadius * 2.4f
        val barHeight = 5f
        val barX = screenX - barWidth / 2f
        val barY = screenY - bodyRadius - 14f

        drawRect(
            color = Color(0xFF37474F),
            topLeft = Offset(barX, barY),
            size = Size(barWidth, barHeight)
        )
        val hpRatio = (monster.currentHp.toFloat() / monster.maxHp.toFloat()).coerceIn(0f, 1f)
        drawRect(
            color = if (hpRatio > 0.4f) Color(0xFFE53935) else Color(0xFFFFB300),
            topLeft = Offset(barX, barY),
            size = Size(barWidth * hpRatio, barHeight)
        )
    }
}

private fun DrawScope.drawHero(
    hero: HeroCombatState,
    camX: Float,
    camY: Float,
    isIsometric: Boolean,
    spriteScale: Float,
    sprite: SpriteSheetData,
    heroBitmap: android.graphics.Bitmap?
) {
    val heroJitterX = if (hero.staggerMs > 0L) (kotlin.random.Random.nextFloat() - 0.5f) * 5f else 0f
    val heroJitterY = if (hero.staggerMs > 0L) (kotlin.random.Random.nextFloat() - 0.5f) * 5f else 0f

    val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(hero.x, hero.y) else Pair(hero.x, hero.y)
    val screenX = posX + camX + heroJitterX
    val screenY = posY + camY + heroJitterY
    val radius = 22f * spriteScale

    // Hero shadow
    drawOval(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(screenX - radius * 1.1f, screenY + radius * 0.6f),
        size = Size(radius * 2.2f, radius * 0.7f)
    )

    if (heroBitmap != null) {
        val cols = sprite.columns.coerceAtLeast(1)
        val rows = sprite.rows.coerceAtLeast(1)
        val frameW = heroBitmap.width / cols
        val frameH = heroBitmap.height / rows
        val animRow = when (hero.animationState) {
            CharacterAnimationState.IDLE -> 0 % rows
            CharacterAnimationState.WALK -> 1 % rows
            CharacterAnimationState.ATTACK -> 2 % rows
            CharacterAnimationState.CAST_SPELL -> 3 % rows
            CharacterAnimationState.HURT -> if (rows > 4) 4 else 0
            CharacterAnimationState.DEFEAT -> if (rows > 5) 5 else 0
        }
        val curFrame = ((System.currentTimeMillis() / 140) % cols).toInt()
        val srcLeft = curFrame * frameW
        val srcTop = animRow * frameH
        val srcRect = android.graphics.Rect(srcLeft, srcTop, srcLeft + frameW, srcTop + frameH)
        val heroDrawSize = (26f * spriteScale).toInt()
        val dstRect = android.graphics.Rect(
            (screenX - heroDrawSize).toInt(),
            (screenY - heroDrawSize).toInt(),
            (screenX + heroDrawSize).toInt(),
            (screenY + heroDrawSize).toInt()
        )
        drawContext.canvas.nativeCanvas.drawBitmap(heroBitmap, srcRect, dstRect, null)
    } else {
        val bodyColor = if (hero.hitFlashMs > 0L) Color.White else IgboArtColors.BronzePrimary
        drawCircle(color = bodyColor, radius = radius, center = Offset(screenX, screenY))
    }

    // Hero Hit Flash Red Aura
    if (hero.hitFlashMs > 0L) {
        val flashRatio = (hero.hitFlashMs / 200f).coerceIn(0f, 1f)
        drawCircle(
            color = Color(0xFFFF1744).copy(alpha = flashRatio * 0.65f),
            radius = radius + 6f,
            center = Offset(screenX, screenY),
            style = Stroke(width = 3.5f)
        )
    }

    // Weapon slash blade indicator based on facing angle
    val facing = hero.facingAngleRad
    val bladeEndX = screenX + cos(facing) * (36f * spriteScale)
    val bladeEndY = screenY + sin(facing) * (36f * spriteScale)

    drawLine(
        color = IgboArtColors.AnyanwuGold,
        start = Offset(screenX, screenY),
        end = Offset(bladeEndX, bladeEndY),
        strokeWidth = 6f
    )
    drawCircle(
        color = IgboArtColors.AmadiohaCyan,
        radius = 4f,
        center = Offset(bladeEndX, bladeEndY)
    )

    // Invulnerability Shield Ring
    if (hero.isInvulnerable) {
        drawCircle(
            color = IgboArtColors.AmadiohaCyan.copy(alpha = 0.6f),
            radius = radius + 8f,
            center = Offset(screenX, screenY),
            style = Stroke(width = 3f)
        )
    }
}

private fun DrawScope.drawSlashArc(
    hero: HeroCombatState,
    slashAngleRad: Float,
    slashTimerMs: Long,
    camX: Float,
    camY: Float,
    isIsometric: Boolean
) {
    if (slashTimerMs <= 0L) return

    val progress = 1f - (slashTimerMs / 180f).coerceIn(0f, 1f)
    val alpha = (1f - progress).coerceIn(0f, 1f)
    val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(hero.x, hero.y) else Pair(hero.x, hero.y)
    val screenX = posX + camX
    val screenY = posY + camY

    val arcRadius = 70f + progress * 28f
    val startAngleDeg = Math.toDegrees((slashAngleRad - 1.0f).toDouble()).toFloat()
    val sweepAngleDeg = 115f

    drawArc(
        brush = Brush.sweepGradient(
            colors = listOf(
                IgboArtColors.AnyanwuGold.copy(alpha = alpha * 0.9f),
                IgboArtColors.BronzePrimary.copy(alpha = alpha * 0.7f),
                Color.White.copy(alpha = alpha),
                Color.Transparent
            ),
            center = Offset(screenX, screenY)
        ),
        startAngle = startAngleDeg,
        sweepAngle = sweepAngleDeg,
        useCenter = false,
        topLeft = Offset(screenX - arcRadius, screenY - arcRadius),
        size = Size(arcRadius * 2f, arcRadius * 2f),
        style = Stroke(width = 7f * (1f - progress * 0.4f))
    )
}

private fun DrawScope.drawImpactSparks(
    sparks: List<CombatImpactSpark>,
    camX: Float,
    camY: Float,
    isIsometric: Boolean
) {
    sparks.forEach { spark ->
        val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(spark.x, spark.y) else Pair(spark.x, spark.y)
        val screenX = posX + camX
        val screenY = posY + camY
        val lifeRatio = (spark.ageMs.toFloat() / spark.maxLifetimeMs.toFloat()).coerceIn(0f, 1f)
        val alpha = (1f - lifeRatio).coerceIn(0f, 1f)

        val sparkColor = try {
            Color(android.graphics.Color.parseColor(spark.colorHex)).copy(alpha = alpha)
        } catch (_: Exception) {
            IgboArtColors.AnyanwuGold.copy(alpha = alpha)
        }

        // Spark core
        drawCircle(
            color = sparkColor,
            radius = spark.radius * (1f - lifeRatio * 0.4f),
            center = Offset(screenX, screenY)
        )

        // Spark trail line
        val trailX = screenX - spark.vx * 1.8f
        val trailY = screenY - spark.vy * 1.8f
        drawLine(
            color = sparkColor.copy(alpha = alpha * 0.6f),
            start = Offset(screenX, screenY),
            end = Offset(trailX, trailY),
            strokeWidth = spark.radius * 0.8f
        )
    }
}

private fun DrawScope.drawCombatTexts(
    texts: List<FloatingCombatText>,
    camX: Float,
    camY: Float,
    isIsometric: Boolean,
    paint: android.graphics.Paint
) {
    texts.forEach { item ->
        val (posX, posY) = if (isIsometric) DungeonMapData.toIsometric(item.x, item.y) else Pair(item.x, item.y)
        val screenX = posX + camX
        val screenY = posY + camY
        paint.color = item.colorArgb
        paint.textSize = if (item.isCrit) 38f else 28f
        val alpha = (1f - (item.ageMs.toFloat() / item.lifetimeMs.toFloat())).coerceIn(0f, 1f)
        paint.alpha = (alpha * 255).toInt()
        drawContext.canvas.nativeCanvas.drawText(item.text, screenX, screenY, paint)
    }
}

@Composable
fun ArenaTopHud(
    stats: CombatHudStats,
    gameMode: GameMode = GameMode.SURVIVAL_ARENA,
    currentEra: DiabloEra = DiabloEra.DIABLO_2,
    currentNaming: NamingSystem = NamingSystem.GENERIC_RPG,
    onCycleEra: () -> Unit = {},
    onCycleNaming: () -> Unit = {},
    onPause: () -> Unit,
    onToggleDummy: () -> Unit,
    onResetStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Wave & Kill Badge + Era / Naming Switcher Pills
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = IgboArtColors.NsibidiCard,
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzePrimary, IgboArtColors.Terracotta))),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (stats.isDummy) "🧪 LAB" else "${gameMode.icon} W${stats.wave}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = IgboArtColors.AnyanwuGold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "K:${stats.kills} | D:${stats.dps}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredIvory,
                        fontSize = 11.sp
                    )
                }
            }

            // Quick Era Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF261D15),
                border = BorderStroke(1.dp, IgboArtColors.AnyanwuGold),
                modifier = Modifier
                    .clickable { onCycleEra() }
                    .testTag("cycle_era_pill")
            ) {
                Text(
                    text = when (currentEra) {
                        DiabloEra.DIABLO_1 -> "🕯️ D1"
                        DiabloEra.DIABLO_2 -> "📜 D2"
                        DiabloEra.DIABLO_3 -> "⚡ D3"
                        DiabloEra.DIABLO_4 -> "🩸 D4"
                        DiabloEra.CUSTOM_STYLE -> "🎨 CUSTOM"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            // Quick Naming Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1B231D),
                border = BorderStroke(1.dp, Color(0xFF81C784)),
                modifier = Modifier
                    .clickable { onCycleNaming() }
                    .testTag("cycle_naming_pill")
            ) {
                Text(
                    text = when (currentNaming) {
                        NamingSystem.GENERIC_RPG -> "🏷️ RPG"
                        NamingSystem.MYTHIC_ANCIENT -> "🏺 MYTH"
                        NamingSystem.AI_THEMED -> "✨ THEME"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF81C784),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        // Action & Pause Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prominent Pause Button
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = IgboArtColors.BronzePrimary,
                modifier = Modifier
                    .clickable { onPause() }
                    .testTag("arena_pause_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏸️", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PAUSE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (stats.isDummy) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiCard,
                modifier = Modifier
                    .clickable { onToggleDummy() }
                    .testTag("dummy_mode_toggle")
            ) {
                Text(
                    text = if (stats.isDummy) "Wave" else "Dummy",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ArenaQuickControlsBar(
    isIsometric: Boolean,
    scale: Float,
    lootCount: Int,
    onToggleIsometric: () -> Unit,
    onScaleChange: (Float) -> Unit,
    onVacuumLoot: () -> Unit,
    onResetGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(IgboArtColors.NsibidiCard.copy(alpha = 0.9f))
            .border(1.dp, IgboArtColors.BronzePrimary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Isometric Toggle
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isIsometric) IgboArtColors.AnyanwuGold else IgboArtColors.NsibidiNight,
            modifier = Modifier
                .clickable { onToggleIsometric() }
                .testTag("toggle_isometric_view")
        ) {
            Text(
                text = if (isIsometric) "📐 2.5D ISO" else "🗺️ 2D TOP",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isIsometric) Color.Black else IgboArtColors.SacredSand,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }

        // Sprite Scale Cycle
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = IgboArtColors.NsibidiNight,
            modifier = Modifier
                .clickable {
                    val nextScale = when {
                        scale < 1.4f -> 1.5f
                        scale < 1.9f -> 2.0f
                        scale < 2.4f -> 2.5f
                        else -> 1.0f
                    }
                    onScaleChange(nextScale)
                }
                .testTag("cycle_sprite_scale")
        ) {
            Text(
                text = "🔍 ${String.format("%.1f", scale)}x",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = IgboArtColors.AmadiohaCyan,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }

        // Vacuum Loot
        if (lootCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = IgboArtColors.AnyanwuGold.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, IgboArtColors.AnyanwuGold),
                modifier = Modifier
                    .clickable { onVacuumLoot() }
                    .testTag("vacuum_loot_btn")
            ) {
                Text(
                    text = "💎 Pick Up ($lootCount)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = IgboArtColors.AnyanwuGold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        // Reset Game Button
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFB71C1C).copy(alpha = 0.3f),
            modifier = Modifier
                .clickable { onResetGame() }
                .testTag("reset_game_quick_btn")
        ) {
            Text(
                text = "↺ Reset",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF8A80),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun ArenaControlsOverlay(
    stats: CombatHudStats,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // LEFT: Action RPG Red Health Globe + Virtual Thumb Joystick
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ArpgHealthGlobe(current = stats.currentHp, max = stats.maxHp)
            Spacer(modifier = Modifier.height(8.dp))
            VirtualJoystick(onMove = onJoystickMove)
        }

        // RIGHT: Action Buttons (Tactile tactile cluster) + Action RPG Blue Mana/Spirit Globe
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ArpgSpiritGlobe(current = stats.currentSpirit, max = stats.maxSpirit)
            Spacer(modifier = Modifier.height(8.dp))
            TactileActionCluster(
                weapon = stats.weaponName,
                attackCd = stats.attackCd,
                thunderCd = stats.thunderCd,
                sunCd = stats.sunNovaCd,
                dashCd = stats.dashCd,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
        }
    }
}

@Composable
fun ArpgHealthGlobe(current: Int, max: Int) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(68.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF1E0E0E))
            .border(3.dp, IgboArtColors.BronzePrimary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Liquid filling from bottom
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillHeight = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFF5252), IgboArtColors.LifeGlobeRed, Color(0xFF8E0000))
                ),
                topLeft = Offset(0f, size.height - fillHeight),
                size = Size(size.width, fillHeight)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "NDỤ",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
            Text(
                text = "$current",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun ArpgSpiritGlobe(current: Int, max: Int) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(68.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF0E1620))
            .border(3.dp, IgboArtColors.BronzePrimary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillHeight = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(IgboArtColors.AmadiohaCyan, IgboArtColors.SpiritGlobeBlue, Color(0xFF002171))
                ),
                topLeft = Offset(0f, size.height - fillHeight),
                size = Size(size.width, fillHeight)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MMỤỌ",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
            Text(
                text = "$current",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun VirtualJoystick(onMove: (Float, Float) -> Unit) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 42f

    Box(
        modifier = Modifier
            .size(105.dp)
            .clip(CircleShape)
            .background(IgboArtColors.NsibidiCard.copy(alpha = 0.85f))
            .border(2.dp, IgboArtColors.BronzeDark, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val drag = offset - center
                        val dist = drag.getDistance()
                        val clamped = if (dist > maxRadius) drag * (maxRadius / dist) else drag
                        knobOffset = clamped
                        onMove(clamped.x / maxRadius, clamped.y / maxRadius)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newOffset = knobOffset + dragAmount
                        val dist = newOffset.getDistance()
                        val clamped = if (dist > maxRadius) newOffset * (maxRadius / dist) else newOffset
                        knobOffset = clamped
                        onMove(clamped.x / maxRadius, clamped.y / maxRadius)
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Inner Thumb Pad
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.toInt(), knobOffset.y.toInt()) }
                .size(44.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(IgboArtColors.BronzePrimary)
                .border(2.dp, IgboArtColors.AnyanwuGold, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("☩", color = IgboArtColors.SacredIvory, fontSize = 16.sp)
        }
    }
}

@Composable
fun TactileActionCluster(
    weapon: String,
    attackCd: Long,
    thunderCd: Long,
    sunCd: Long,
    dashCd: Long,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top row: Spell 1 (Thunder) & Spell 2 (Sun Nova)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Thunder Skill (Amadioha)
            ArpgTactileButton(
                label = "Egbe",
                subLabel = "⚡ Amadioha",
                color = IgboArtColors.AmadiohaCyan,
                isOnCooldown = thunderCd > 0,
                cooldownSec = (thunderCd / 1000f),
                sizeDp = 52,
                onClick = onThunder,
                testTag = "skill_thunder_button"
            )

            // Sun Nova Skill (Anyanwu)
            ArpgTactileButton(
                label = "Anyanwu",
                subLabel = "🔥 Nova",
                color = IgboArtColors.AnyanwuGold,
                isOnCooldown = sunCd > 0,
                cooldownSec = (sunCd / 1000f),
                sizeDp = 52,
                onClick = onSunNova,
                testTag = "skill_sun_nova_button"
            )
        }

        // Bottom row: Dash & Primary Weapon Attack
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Dash (Ekpe)
            ArpgTactileButton(
                label = "Dash",
                subLabel = "Agụ",
                color = IgboArtColors.Terracotta,
                isOnCooldown = dashCd > 0,
                cooldownSec = (dashCd / 1000f),
                sizeDp = 48,
                onClick = onDash,
                testTag = "skill_dash_button"
            )

            // Primary Attack Button (Large Tactile A-Button)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(IgboArtColors.BronzeLight, IgboArtColors.BronzePrimary, IgboArtColors.BronzeDark)
                        )
                    )
                    .border(3.dp, IgboArtColors.AnyanwuGold, CircleShape)
                    .clickable { onAttack() }
                    .testTag("primary_attack_button"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "⚔️",
                        fontSize = 18.sp
                    )
                    Text(
                        text = "SLASH",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ArpgTactileButton(
    label: String,
    subLabel: String,
    color: Color,
    isOnCooldown: Boolean,
    cooldownSec: Float,
    sizeDp: Int,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .shadow(4.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(if (isOnCooldown) Color(0xFF37474F) else IgboArtColors.NsibidiCard)
            .border(2.dp, if (isOnCooldown) Color.Gray else color, RoundedCornerShape(14.dp))
            .clickable(enabled = !isOnCooldown) { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isOnCooldown) {
                Text(
                    text = String.format("%.1f", cooldownSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand,
                    fontSize = 8.sp
                )
            }
        }
    }
}
