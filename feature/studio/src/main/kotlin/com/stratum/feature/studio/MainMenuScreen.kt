package com.stratum.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.NamingSystem
import com.stratum.feature.studio.event.MainMenuUiAction
import com.stratum.feature.studio.state.MainMenuUiState

/**
 * MAIN MENU & TITLE START SCREEN
 *
 * Rooted in authentic Igbo-Ukwu lost-wax bronze aesthetics and pure clean architecture.
 * Completely decoupled: Pure stateless Composable with MVI unidirectional data flow.
 */
@Composable
fun MainMenuScreen(
    state: MainMenuUiState,
    onAction: (MainMenuUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = IgboArtColors.NsibidiNight,
        modifier = modifier
            .fillMaxSize()
            .testTag("main_menu_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Atmospheric Hero Title Section
            item {
                Spacer(modifier = Modifier.height(10.dp))
                HeroTitleHeader()
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Hero Profile Ribbon
            item {
                HeroSummaryRibbon(
                    level = state.playerStats.level,
                    gold = state.playerStats.gold,
                    activeSpriteName = state.activeSpriteName,
                    weaponName = state.equippedWeaponName,
                    weaponRarityColor = Color(state.equippedWeaponRarityColorArgb.toInt())
                )
            }

            // Primary Navigation Cards
            item {
                MainMenuCard(
                    title = "ENTER COMBAT ARENA",
                    subtitle = "Real-time action RPG combat. Amadioha lightning, solar novas, monster waves & legendary loot.",
                    icon = Icons.Default.PlayArrow,
                    badge = "ACTION RPG",
                    accentColor = IgboArtColors.Terracotta,
                    testTag = "menu_enter_arena_button",
                    onClick = { onAction(MainMenuUiAction.NavigateTo(AppScreen.ARENA)) }
                )
            }

            item {
                MainMenuCard(
                    title = "DIABLO ERAS & AI THEME WEAVER",
                    subtitle = "Switch between Diablo 1, 2, 3, 4 HUD styles or prompt AI to dynamically forge universe themes, items & classes.",
                    icon = Icons.Default.AutoAwesome,
                    badge = "CHAMELEON HUD",
                    accentColor = IgboArtColors.AnyanwuGold,
                    testTag = "menu_diablo_theme_weaver_button",
                    onClick = { onAction(MainMenuUiAction.OpenSettings) }
                )
            }

            item {
                MainMenuCard(
                    title = "ARMORY & WEAPON FORGE",
                    subtitle = "Craft sacred bronze blades, reroll Nsibidi affixes, and simulate combat DPS against armor tiers.",
                    icon = Icons.Default.FitnessCenter,
                    badge = "FORGE LAB",
                    accentColor = IgboArtColors.AnyanwuGold,
                    testTag = "menu_forge_lab_button",
                    onClick = { onAction(MainMenuUiAction.NavigateTo(AppScreen.FORGE_LAB)) }
                )
            }

            item {
                MainMenuCard(
                    title = "CREATOR STUDIO",
                    subtitle = "Synthesize raw sprite sheets with OpenRouter image models, build custom classes & design biomes.",
                    icon = Icons.Default.ColorLens,
                    badge = "AI & SPRITES",
                    accentColor = Color(0xFF2E7D5B), // Igbo-Ukwu Verdigris Patina
                    testTag = "menu_creator_studio_button",
                    onClick = { onAction(MainMenuUiAction.NavigateTo(AppScreen.CREATOR_STUDIO)) }
                )
            }

            item {
                MainMenuCard(
                    title = "PEN & PAPER RPG",
                    subtitle = "Ancient Ala Igbo tabletop rulebooks, attribute modifiers, skill checks & interactive D20 rolls.",
                    icon = Icons.Default.Casino,
                    badge = "TABLETOP",
                    accentColor = IgboArtColors.AmadiohaCyan,
                    testTag = "menu_pnp_rpg_button",
                    onClick = { onAction(MainMenuUiAction.NavigateTo(AppScreen.PEN_AND_PAPER)) }
                )
            }

            item {
                MainMenuCard(
                    title = "SACRED CODEX",
                    subtitle = "Explore authentic 9th-century Igbo-Ukwu bronze casting, Nsibidi script, and spiritual cosmology.",
                    icon = Icons.Default.MenuBook,
                    badge = "HISTORY & LORE",
                    accentColor = IgboArtColors.SacredSand,
                    testTag = "menu_codex_button",
                    onClick = { onAction(MainMenuUiAction.NavigateTo(AppScreen.LORE_CODEX)) }
                )
            }

            item {
                MainMenuCard(
                    title = "SETTINGS & AI CONNECT",
                    subtitle = "Configure OpenRouter API keys, model catalog, audio parameters & sprite transparency police.",
                    icon = Icons.Default.Settings,
                    badge = "SYSTEM",
                    accentColor = Color(0xFF546E7A),
                    testTag = "menu_settings_button",
                    onClick = { onAction(MainMenuUiAction.OpenSettings) }
                )
            }

            // Authentic Cultural Footer
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "❖  EG-BE BE-RE, U-GO BE-RE  ❖",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.BronzeLight.copy(alpha = 0.7f),
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Honoring authentic Igbo-Ukwu metallurgy & Nsibidi indigenous script",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

/**
 * Route Adapter Composable: Connects the decoupled pure MainMenuScreen to the ViewModel.
 */
@Composable
fun MainMenuScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val playerStats by viewModel.playerHeaderStats.collectAsState()
    val activeSprite by viewModel.activeSprite.collectAsState()
    val era by viewModel.diabloEra.collectAsState()
    val naming by viewModel.namingSystem.collectAsState()
    val activeTheme by viewModel.activeTheme.collectAsState()
    val equippedWeapon = viewModel.engineState.hero.equippedWeapon

    val state = remember(playerStats, activeSprite, era, naming, activeTheme, equippedWeapon) {
        MainMenuUiState(
            currentEra = era,
            namingSystem = naming,
            activeTheme = activeTheme,
            playerStats = playerStats,
            activeSpriteName = activeSprite.name,
            equippedWeaponName = equippedWeapon?.name ?: "Bronze Blade",
            equippedWeaponRarityColorArgb = (equippedWeapon?.rarity?.colorArgb ?: 0xFF9E9E9E.toInt()).toLong()
        )
    }

    MainMenuScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is MainMenuUiAction.NavigateTo -> viewModel.navigateTo(action.screen)
                is MainMenuUiAction.OpenSettings -> viewModel.setSettingsMenuOpen(true)
                is MainMenuUiAction.CycleEra -> {
                    val eras = DiabloEra.values()
                    viewModel.setDiabloEra(eras[(era.ordinal + 1) % eras.size])
                }
                is MainMenuUiAction.CycleNaming -> {
                    val namings = NamingSystem.values()
                    viewModel.setNamingSystem(namings[(naming.ordinal + 1) % namings.size])
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun HeroTitleHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Bronze Rope Medallion
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary, Color(0xFF1E150B))
                    )
                )
                .border(2.dp, IgboArtColors.AnyanwuGold, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ỌFỌ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                fontSize = 18.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "ỌFỌ & BRONZE",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = IgboArtColors.AnyanwuGold,
            letterSpacing = 3.sp
        )

        Text(
            text = "ANCIENT IGBO ACTION RPG & CREATOR ENGINE",
            style = MaterialTheme.typography.labelMedium,
            color = IgboArtColors.BronzeLight,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HeroSummaryRibbon(
    level: Int,
    gold: Int,
    activeSpriteName: String,
    weaponName: String,
    weaponRarityColor: Color
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(IgboArtColors.BronzePrimary.copy(alpha = 0.6f), Color(0xFF2E7D5B).copy(alpha = 0.4f))
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hero Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(IgboArtColors.BronzePrimary.copy(alpha = 0.25f))
                        .border(1.dp, IgboArtColors.AnyanwuGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚔️", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = activeSpriteName,
                        style = MaterialTheme.typography.labelLarge,
                        color = IgboArtColors.SacredIvory,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = weaponName,
                        style = MaterialTheme.typography.bodySmall,
                        color = weaponRarityColor,
                        fontSize = 11.sp
                    )
                }
            }

            // Level & Gold Badges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2A1C12)
                ) {
                    Text(
                        text = "LVL $level",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2D2512)
                ) {
                    Text(
                        text = "🪙 $gold",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MainMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(accentColor.copy(alpha = 0.7f), Color.Transparent)
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = IgboArtColors.SacredIvory,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
