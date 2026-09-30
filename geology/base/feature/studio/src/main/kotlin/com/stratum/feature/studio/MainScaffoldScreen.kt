package com.stratum.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * CHAPTER 21: PRESENTATION VIEW - MAIN SCAFFOLD & ARPG NAVIGATION
 *
 * Clean Architecture container integrating:
 * - Start Menu / Title Hub (MainMenuScreen)
 * - Pure Edge-to-Edge Combat Arena (CombatArenaScreen with pause overlay)
 * - Sensible Creator Studio Hub (Sprites, Classes, Maps, AI Lab)
 * - Armory & Weapon Forge Screen
 * - Pen & Paper RPG Studio
 * - Sacred Codex Screen
 */
@Composable
fun MainScaffold(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val playerStats by viewModel.playerHeaderStats.collectAsState()
    val isSettingsOpen by viewModel.isSettingsMenuOpen.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen_content"
        ) { screen ->
            when (screen) {
                AppScreen.START_MENU -> {
                    MainMenuScreen(viewModel = viewModel)
                }
                AppScreen.ARENA -> {
                    CombatArenaScreen(viewModel = viewModel)
                }
                AppScreen.CREATOR_STUDIO -> {
                    CreatorStudioScreen(viewModel = viewModel)
                }
                AppScreen.FORGE_LAB -> {
                    ScreenWithHeaderScaffold(
                        title = "ARMORY & FORGE LAB",
                        subtitle = "Weapon Crafting & DPS Simulator",
                        level = playerStats.level,
                        gold = playerStats.gold,
                        onBack = { viewModel.navigateTo(AppScreen.START_MENU) },
                        onOpenSettings = { viewModel.setSettingsMenuOpen(true) }
                    ) {
                        WeaponForgeAndLabScreen(viewModel = viewModel)
                    }
                }
                AppScreen.PEN_AND_PAPER -> {
                    ScreenWithHeaderScaffold(
                        title = "PEN & PAPER RPG",
                        subtitle = "Ancient Ala Igbo Tabletop Rules",
                        level = playerStats.level,
                        gold = playerStats.gold,
                        onBack = { viewModel.navigateTo(AppScreen.START_MENU) },
                        onOpenSettings = { viewModel.setSettingsMenuOpen(true) }
                    ) {
                        PenAndPaperRpgStudioScreen(viewModel = viewModel)
                    }
                }
                AppScreen.LORE_CODEX -> {
                    ScreenWithHeaderScaffold(
                        title = "SACRED CODEX",
                        subtitle = "Igbo-Ukwu Metallurgy & Nsibidi Lore",
                        level = playerStats.level,
                        gold = playerStats.gold,
                        onBack = { viewModel.navigateTo(AppScreen.START_MENU) },
                        onOpenSettings = { viewModel.setSettingsMenuOpen(true) }
                    ) {
                        IgboHistoryCodexScreen()
                    }
                }
            }
        }

        if (isSettingsOpen) {
            ConsolidatedMenuAndSettingsDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.setSettingsMenuOpen(false) }
            )
        }
    }
}

@Composable
fun ScreenWithHeaderScaffold(
    title: String,
    subtitle: String,
    level: Int,
    gold: Int,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SubScreenTopHeader(
                title = title,
                subtitle = subtitle,
                level = level,
                gold = gold,
                onBack = onBack,
                onOpenSettings = onOpenSettings
            )
        },
        containerColor = IgboArtColors.NsibidiNight,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        ) {
            content()
        }
    }
}

@Composable
fun SubScreenTopHeader(
    title: String,
    subtitle: String,
    level: Int,
    gold: Int,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = IgboArtColors.NsibidiSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("subscreen_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Menu",
                        tint = IgboArtColors.AnyanwuGold
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = IgboArtColors.AnyanwuGold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.BronzeLight,
                        fontSize = 10.sp
                    )
                }
            }

            // Stats pills & Settings Trigger
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Gold Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF263228),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzeDark))
                    )
                ) {
                    Text(
                        text = "🪙 $gold",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Level Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = IgboArtColors.BronzePrimary
                ) {
                    Text(
                        text = "Lv.$level",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Menu & Settings Trigger
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = IgboArtColors.BronzeDark,
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary))
                    ),
                    modifier = Modifier
                        .clickable { onOpenSettings() }
                        .testTag("top_menu_settings_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("⚙️", fontSize = 12.sp)
                        Text(
                            text = "MENU",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainTopHeader(
    level: Int,
    gold: Int,
    currentTab: MainNavigationTab,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = IgboArtColors.NsibidiSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Title + Ancient Igbo Logo Medallion
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                            )
                        )
                        .border(2.dp, IgboArtColors.SacredIvory, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("☩", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "ỌFỌ & BRONZE",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = IgboArtColors.SacredIvory,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Ancient Igbo ARPG Engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.BronzeLight,
                        fontSize = 10.sp
                    )
                }
            }

            // Stats pills & Settings Trigger
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Gold Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF263228),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzeDark))
                    )
                ) {
                    Text(
                        text = "🪙 $gold",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Level Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = IgboArtColors.BronzePrimary
                ) {
                    Text(
                        text = "Lv.$level",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Consolidated Menu & Settings Button
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = IgboArtColors.BronzeDark,
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary))
                    ),
                    modifier = Modifier
                        .clickable { onOpenSettings() }
                        .testTag("top_menu_settings_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("⚙️", fontSize = 12.sp)
                        Text(
                            text = "MENU",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArpgBottomNav(
    currentTab: MainNavigationTab,
    onTabSelect: (MainNavigationTab) -> Unit
) {
    Surface(
        color = IgboArtColors.NsibidiSurface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainNavigationTab.values().forEach { tab ->
                val isSelected = currentTab == tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) IgboArtColors.BronzeDark.copy(alpha = 0.45f) else Color.Transparent)
                        .clickable { onTabSelect(tab) }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("nav_tab_${tab.name.lowercase()}")
                ) {
                    Text(
                        text = tab.iconSymbol,
                        fontSize = if (isSelected) 18.sp else 16.sp
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) IgboArtColors.AnyanwuGold else IgboArtColors.SacredSand,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
