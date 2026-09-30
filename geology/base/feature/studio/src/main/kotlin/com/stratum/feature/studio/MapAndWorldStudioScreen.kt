package com.stratum.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.legacy.domain.DungeonMapData
import com.stratum.legacy.domain.GameMode
import com.stratum.legacy.domain.MapBiome
import com.stratum.legacy.domain.TileType
import com.stratum.feature.studio.event.MapStudioUiAction
import com.stratum.feature.studio.state.MapStudioUiState

/**
 * CHAPTER 23: PRESENTATION VIEW - MAP GENERATOR & WORLD STUDIO
 *
 * Full control over:
 * - Procedural Map Generation (Biomes, Seeds, Dungeons)
 * - Camera Projection (2.5D Isometric vs 2D Orthographic Top-Down)
 * - Sprite Scaling Options (1.0x, 1.5x, 2.0x, 2.5x, 3.0x)
 * - Game Modes (Survival Arena, Dungeon Crawl, Horde Siege, Sandbox)
 * - Full Game Session Reset
 */

@Composable
fun MapAndWorldStudio(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val currentMap by viewModel.currentMap.collectAsState()
    val currentBiome by viewModel.currentBiome.collectAsState()
    val isIsometric by viewModel.isIsometricView.collectAsState()
    val spriteScale by viewModel.spriteScale.collectAsState()
    val currentGameMode by viewModel.currentGameMode.collectAsState()

    val uiState = MapStudioUiState(
        currentMap = currentMap,
        currentBiome = currentBiome,
        isIsometric = isIsometric,
        spriteScale = spriteScale,
        currentGameMode = currentGameMode
    )

    MapAndWorldStudio(
        state = uiState,
        onAction = { action ->
            when (action) {
                is MapStudioUiAction.GenerateNewMap -> viewModel.generateNewMap()
                is MapStudioUiAction.NavigateToArena -> viewModel.selectMainTab(MainNavigationTab.ARENA)
                is MapStudioUiAction.ToggleIsometric -> viewModel.toggleIsometricView()
                is MapStudioUiAction.SetSpriteScale -> viewModel.setSpriteScale(action.scale)
                is MapStudioUiAction.SelectGameMode -> viewModel.selectGameMode(action.mode)
                is MapStudioUiAction.SelectBiome -> viewModel.selectBiome(action.biome)
                is MapStudioUiAction.ResetWholeGame -> viewModel.resetWholeGame()
            }
        },
        modifier = modifier
    )
}

@Composable
fun MapAndWorldStudio(
    state: MapStudioUiState,
    onAction: (MapStudioUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMap = state.currentMap
    val currentBiome = state.currentBiome
    val isIsometric = state.isIsometric
    val spriteScale = state.spriteScale
    val currentGameMode = state.currentGameMode
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(16.dp)
            .testTag("map_and_world_studio"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "🗺️ PROCEDURAL MAP & WORLD STUDIO",
                    style = MaterialTheme.typography.titleMedium,
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Generate biomes, adjust isometric projection, scale sprites, and choose game modes",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand
                )
            }
        }

        // 1. Live Dungeon Map Visualizer
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DUNGEON BLUEPRINT: ${currentMap.biome.displayName.uppercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Seed: ${currentMap.seed} • ${currentMap.tiles.size} Tiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2D/Isometric Minimap Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.4f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0C130E))
                            .border(2.dp, IgboArtColors.BronzeDark, RoundedCornerShape(12.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val mapCols = currentMap.columns
                            val mapRows = currentMap.rows
                            val cellW = size.width / mapCols.toFloat()
                            val cellH = size.height / mapRows.toFloat()

                            // Draw tiles in minimap
                            for (tile in currentMap.tiles) {
                                val tileColor = when (tile.type) {
                                    TileType.FLOOR_BASIC -> Color(0xFF2E3D30)
                                    TileType.FLOOR_ORNATE -> IgboArtColors.BronzePrimary
                                    TileType.FLOOR_NSIBIDI_SEAL -> IgboArtColors.AnyanwuGold
                                    TileType.WALL_STONE -> Color(0xFF141C16)
                                    TileType.PILLAR_BRONZE -> IgboArtColors.BronzeLight
                                    TileType.TORCH_BRAZIER -> Color(0xFFFF6D00)
                                    TileType.SHRINE_OFO -> IgboArtColors.AmadiohaCyan
                                    TileType.WATER_POOL -> Color(0xFF00B0FF)
                                }

                                if (isIsometric) {
                                    val centerX = (tile.gridX + 0.5f) * cellW
                                    val centerY = (tile.gridY + 0.5f) * cellH
                                    val path = Path().apply {
                                        moveTo(centerX, centerY - cellH * 0.45f)
                                        lineTo(centerX + cellW * 0.45f, centerY)
                                        lineTo(centerX, centerY + cellH * 0.45f)
                                        lineTo(centerX - cellW * 0.45f, centerY)
                                        close()
                                    }
                                    drawPath(path, color = tileColor)
                                } else {
                                    drawRect(
                                        color = tileColor,
                                        topLeft = Offset(tile.gridX * cellW, tile.gridY * cellH),
                                        size = Size(cellW - 1f, cellH - 1f)
                                    )
                                }
                            }

                            // Draw Spawners & Chests
                            for (room in currentMap.rooms) {
                                val roomCx = (room.centerX) * cellW
                                val roomCy = (room.centerY) * cellH
                                drawCircle(
                                    color = IgboArtColors.AnyanwuGold,
                                    radius = 5f,
                                    center = Offset(roomCx, roomCy)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onAction(MapStudioUiAction.GenerateNewMap) },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_roll_new_map")
                        ) {
                            Text("🎲 Re-roll Procedural Map", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { onAction(MapStudioUiAction.NavigateToArena) },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.AmadiohaCyan),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_enter_combat_arena")
                        ) {
                            Text("⚔️ Enter Arena", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 2. Camera Projection: Isometric 2.5D vs 2D Top-Down
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzePrimary)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📐 CAMERA PROJECTION PERSPECTIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.SacredSand,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isIsometric) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiNight,
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = Brush.linearGradient(
                                    if (isIsometric) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                                    else listOf(Color.Transparent, Color.Transparent)
                                )
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (!isIsometric) onAction(MapStudioUiAction.ToggleIsometric)
                                }
                                .testTag("perspective_iso_btn")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("📐 2.5D ISOMETRIC", color = if (isIsometric) Color.White else IgboArtColors.SacredSand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Classic Diablo / ARPG angle", color = IgboArtColors.SacredSand, fontSize = 10.sp)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isIsometric) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiNight,
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = Brush.linearGradient(
                                    if (!isIsometric) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                                    else listOf(Color.Transparent, Color.Transparent)
                                )
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (isIsometric) onAction(MapStudioUiAction.ToggleIsometric)
                                }
                                .testTag("perspective_topdown_btn")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("🎯 2D TOP-DOWN", color = if (!isIsometric) Color.White else IgboArtColors.SacredSand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("True orthographic grid", color = IgboArtColors.SacredSand, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. Sprite Scaling Options
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzePrimary)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔍 SPRITE & ENTITY SCALING FACTOR",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.SacredSand,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${String.format("%.1f", spriteScale)}x",
                            style = MaterialTheme.typography.titleMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { s ->
                            val isSel = (spriteScale == s)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) IgboArtColors.AnyanwuGold else IgboArtColors.NsibidiNight,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onAction(MapStudioUiAction.SetSpriteScale(s)) }
                                    .testTag("scale_option_$s")
                            ) {
                                Text(
                                    text = "${s}x",
                                    fontSize = 11.sp,
                                    color = if (isSel) Color.Black else IgboArtColors.SacredSand,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = spriteScale,
                        onValueChange = { onAction(MapStudioUiAction.SetSpriteScale(it)) },
                        valueRange = 0.8f..3.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = IgboArtColors.AnyanwuGold,
                            activeTrackColor = IgboArtColors.AnyanwuGold
                        )
                    )
                }
            }
        }

        // 4. Game Modes Selection
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzePrimary)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🎮 ACTIVE GAME MODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.SacredSand,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameMode.values().forEach { mode ->
                            val isSelected = (currentGameMode == mode)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiNight,
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = Brush.linearGradient(
                                        if (isSelected) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                                        else listOf(Color.Transparent, Color.Transparent)
                                    )
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAction(MapStudioUiAction.SelectGameMode(mode)) }
                                    .testTag("gamemode_${mode.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(mode.icon, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = mode.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelected) Color.White else IgboArtColors.SacredIvory,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = mode.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = IgboArtColors.SacredSand,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Biome Selector
        item {
            Text(
                text = "🌿 SELECT DUNGEON BIOME",
                style = MaterialTheme.typography.labelMedium,
                color = IgboArtColors.SacredSand,
                fontWeight = FontWeight.Bold
            )
        }

        items(MapBiome.values()) { biome ->
            val isSelected = (currentBiome == biome)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) IgboArtColors.BronzeDark.copy(alpha = 0.5f) else IgboArtColors.NsibidiCard
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        if (isSelected) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                        else listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzeDark)
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(MapStudioUiAction.SelectBiome(biome)) }
                    .testTag("biome_card_${biome.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(biome.primaryColorHex)))
                            .border(2.dp, IgboArtColors.AnyanwuGold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🏛️", fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = biome.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) IgboArtColors.AnyanwuGold else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = biome.loreSnippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand,
                            fontSize = 11.sp
                        )
                    }

                    if (isSelected) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = IgboArtColors.AnyanwuGold
                        ) {
                            Text(
                                "ACTIVE",
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 6. Reset Whole Game Session Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF261214)),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.LifeGlobeRed, Color(0xFF8E0000))))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ RESET WHOLE GAME SESSION",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Resets the active game state: returns hero to Wave 1, clears monsters, generates a fresh map, and restores full Ndụ & Mmụọ pools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showResetConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.LifeGlobeRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_reset_whole_game")
                    ) {
                        Text("Reset Entire Game Session", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Confirm Session Reset", color = IgboArtColors.AnyanwuGold, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to reset the whole game? All current wave kills and ground loot drops will be cleared.",
                    color = IgboArtColors.SacredIvory
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAction(MapStudioUiAction.ResetWholeGame)
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.LifeGlobeRed)
                ) {
                    Text("Confirm Reset", color = Color.White)
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
}
