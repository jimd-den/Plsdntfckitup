package com.stratum.feature.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoFixHigh
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.NamingSystem
import com.stratum.feature.studio.event.PauseOverlayUiAction
import com.stratum.feature.studio.state.PauseOverlayUiState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * COMBAT ARENA PAUSE OVERLAY
 *
 * Dedicated pause screen invoked during live combat.
 * Completely pauses the background tick loop, provides hero battle overview,
 * and allows clean resumption, wave restart, or return to the main menu.
 */
@Composable
fun CombatArenaPauseOverlay(
    state: PauseOverlayUiState,
    onAction: (PauseOverlayUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val hudStats = state.hudStats
    val activeSprite = state.activeSprite
    val diabloEra = state.diabloEra
    val namingSystem = state.namingSystem
    val activeTheme = state.activeTheme
    val equippedWeapon = state.equippedWeapon

    // Semi-transparent backdrop blocking touches
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("combat_pause_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.linearGradient(
                    listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary, Color(0xFF1E150B))
                )
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(IgboArtColors.BronzePrimary.copy(alpha = 0.3f))
                            .border(1.dp, IgboArtColors.AnyanwuGold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⏸️", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "GAME PAUSED",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = IgboArtColors.AnyanwuGold,
                        letterSpacing = 2.sp
                    )
                }

                Divider(color = IgboArtColors.BronzePrimary.copy(alpha = 0.3f))

                // Hero & Vitals Overview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF16120E))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hero: ${activeSprite.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Wave ${hudStats.wave}",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // HP Bar
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Health (Life)", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF5252), fontSize = 11.sp)
                            Text("${hudStats.currentHp} / ${hudStats.maxHp}", style = MaterialTheme.typography.bodySmall, color = IgboArtColors.SacredIvory, fontSize = 11.sp)
                        }
                        val hpRatio = (hudStats.currentHp.toFloat() / hudStats.maxHp.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { hpRatio },
                            color = Color(0xFFD32F2F),
                            trackColor = Color(0xFF371616),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    // Spirit Bar
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Spirit (Mana)", style = MaterialTheme.typography.bodySmall, color = IgboArtColors.AmadiohaCyan, fontSize = 11.sp)
                            Text("${hudStats.currentSpirit} / ${hudStats.maxSpirit}", style = MaterialTheme.typography.bodySmall, color = IgboArtColors.SacredIvory, fontSize = 11.sp)
                        }
                        val spiritRatio = (hudStats.currentSpirit.toFloat() / hudStats.maxSpirit.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { spiritRatio },
                            color = IgboArtColors.AmadiohaCyan,
                            trackColor = Color(0xFF0D2533),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }

                // Diablo Era Quick Selector (Play D1, D2, D3, D4, Custom on the fly!)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16120E)),
                    border = BorderStroke(1.dp, IgboArtColors.AnyanwuGold.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DIABLO ERA HUD STYLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.AnyanwuGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = diabloEra.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DiabloEra.values().forEach { era ->
                                val isChosen = diabloEra == era
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isChosen) IgboArtColors.BronzePrimary else Color(0xFF221A15),
                                    border = BorderStroke(1.dp, if (isChosen) IgboArtColors.AnyanwuGold else Color(0xFF382B22)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onAction(PauseOverlayUiAction.SelectEra(era)) }
                                ) {
                                    Text(
                                        text = when (era) {
                                            DiabloEra.DIABLO_1 -> "D1"
                                            DiabloEra.DIABLO_2 -> "D2"
                                            DiabloEra.DIABLO_3 -> "D3"
                                            DiabloEra.DIABLO_4 -> "D4"
                                            DiabloEra.CUSTOM_STYLE -> "Custom"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isChosen) Color.White else Color.Gray,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        fontSize = 10.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Equipped Weapon & Combat Stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Weapon Badge
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF16120E),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Equipped Weapon",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.SacredSand.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                            Text(
                                text = equippedWeapon?.name ?: "Bronze Blade",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(equippedWeapon?.rarity?.colorArgb ?: 0xFF9E9E9E.toInt()),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${equippedWeapon?.rarity?.displayName ?: "Common"} • DPS: ${hudStats.dps}",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Combat Stats
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF16120E),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Session Score",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.SacredSand.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                            Text(
                                text = "Kills: ${hudStats.kills}",
                                style = MaterialTheme.typography.labelMedium,
                                color = IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hudStats.isDummy) "Test Dummy Mode" else "Wave Combat",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hudStats.isDummy) IgboArtColors.AmadiohaCyan else IgboArtColors.AnyanwuGold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Primary Action: Resume Combat
                Button(
                    onClick = { onAction(PauseOverlayUiAction.Resume) },
                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pause_resume_button")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Resume")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RESUME COMBAT",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Secondary Row: Restart Wave & Toggle Dummy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAction(PauseOverlayUiAction.RestartWave) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_restart_wave_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Restart Wave", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restart Wave", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { onAction(PauseOverlayUiAction.ToggleDummy(!hudStats.isDummy)) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_toggle_dummy_button")
                    ) {
                        Icon(imageVector = Icons.Default.Science, contentDescription = "Dummy Lab", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (hudStats.isDummy) "Live Waves" else "Dummy Lab", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                    }
                }

                // Tertiary Row: Settings & Return to Main Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAction(PauseOverlayUiAction.OpenSettings) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_settings_button")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Settings", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                    }

                    Button(
                        onClick = { onAction(PauseOverlayUiAction.QuitToMenu) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_main_menu_button")
                    ) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Main Menu", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Main Menu", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Route Adapter Composable: Connects pure CombatArenaPauseOverlay to ViewModel.
 */
@Composable
fun CombatArenaPauseOverlay(
    viewModel: ArpgEngineViewModel,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hudStats by viewModel.combatHudStats.collectAsState()
    val activeSprite by viewModel.activeSprite.collectAsState()
    val diabloEra by viewModel.diabloEra.collectAsState()
    val namingSystem by viewModel.namingSystem.collectAsState()
    val activeTheme by viewModel.activeTheme.collectAsState()
    val equippedWeapon = viewModel.engineState.hero.equippedWeapon

    val state = remember(hudStats, activeSprite, diabloEra, namingSystem, activeTheme, equippedWeapon) {
        PauseOverlayUiState(
            hudStats = hudStats,
            activeSprite = activeSprite,
            diabloEra = diabloEra,
            namingSystem = namingSystem,
            activeTheme = activeTheme,
            equippedWeapon = equippedWeapon
        )
    }

    CombatArenaPauseOverlay(
        state = state,
        onAction = { action ->
            when (action) {
                is PauseOverlayUiAction.Resume -> onResume()
                is PauseOverlayUiAction.RestartWave -> {
                    viewModel.restartArenaWave()
                    onResume()
                }
                is PauseOverlayUiAction.ToggleDummy -> viewModel.toggleDummyMode(action.enabled)
                is PauseOverlayUiAction.OpenSettings -> viewModel.setSettingsMenuOpen(true)
                is PauseOverlayUiAction.QuitToMenu -> viewModel.navigateTo(AppScreen.START_MENU)
                is PauseOverlayUiAction.SelectEra -> viewModel.setDiabloEra(action.era)
            }
        },
        modifier = modifier
    )
}
