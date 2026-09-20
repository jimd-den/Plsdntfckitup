package com.stratum.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.stratum.feature.studio.state.CreatorStudioUiState

/**
 * CREATOR STUDIO HUB
 *
 * Sensibly organizes the 4 creative developer suites:
 * 1. Sprites & AI (Raw OpenRouter Sprite Sheets & Transparency Police)
 * 2. Classes & Powers (Node-based visual ability graph)
 * 3. Map Generator (Cellular automata & Nsibidi dungeon generation)
 * 4. AI Lab (Custom mechanic prompt generator)
 */
@Composable
fun CreatorStudioScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val creatorSubTab by viewModel.creatorSubTab.collectAsState()
    CreatorStudioScreen(
        state = CreatorStudioUiState(currentSubTab = creatorSubTab),
        onNavigateBack = { viewModel.navigateTo(AppScreen.START_MENU) },
        onOpenSettings = { viewModel.setSettingsMenuOpen(true) },
        onSelectSubTab = { viewModel.setCreatorSubTab(it) },
        contentForSubTab = { subTab ->
            when (subTab) {
                CreatorSubTab.SPRITES -> SpriteSheetImporterScreen(viewModel = viewModel)
                CreatorSubTab.CLASSES -> ClassesAndPowersStudio(viewModel = viewModel)
                CreatorSubTab.MAPS -> MapAndWorldStudio(viewModel = viewModel)
                CreatorSubTab.AI_MECHANICS -> AiMechanicGeneratorScreen(viewModel = viewModel)
            }
        },
        modifier = modifier
    )
}

@Composable
fun CreatorStudioScreen(
    state: CreatorStudioUiState,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSubTab: (CreatorSubTab) -> Unit,
    contentForSubTab: @Composable (CreatorSubTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val creatorSubTab = state.currentSubTab

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .testTag("creator_studio_screen")
    ) {
        // Top Navigation Bar (Accounting for camera cutouts & status bar)
        Surface(
            color = IgboArtColors.NsibidiSurface,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("creator_studio_back_button")
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
                            text = "CREATOR STUDIO",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = IgboArtColors.AnyanwuGold
                        )
                        Text(
                            text = "AI Sprites • Classes • Maps • Mechanics",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand,
                            fontSize = 10.sp
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("creator_studio_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = IgboArtColors.SacredSand
                    )
                }
            }
        }

        // Sub-Tab Segmented Selector
        TabRow(
            selectedTabIndex = CreatorSubTab.values().indexOf(creatorSubTab),
            containerColor = Color(0xFF16120E),
            contentColor = IgboArtColors.AnyanwuGold,
            indicator = { tabPositions ->
                val index = CreatorSubTab.values().indexOf(creatorSubTab)
                if (index in tabPositions.indices) {
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[index]),
                        color = IgboArtColors.AnyanwuGold,
                        height = 3.dp
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            CreatorSubTab.values().forEach { subTab ->
                val isSelected = creatorSubTab == subTab
                Tab(
                    selected = isSelected,
                    onClick = { onSelectSubTab(subTab) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(subTab.icon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = subTab.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) IgboArtColors.AnyanwuGold else IgboArtColors.SacredSand,
                                fontSize = 11.sp
                            )
                        }
                    },
                    modifier = Modifier.testTag("creator_tab_${subTab.name.lowercase()}")
                )
            }
        }

        // Sub-Screen Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        ) {
            AnimatedContent(
                targetState = creatorSubTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "creator_content"
            ) { tab ->
                contentForSubTab(tab)
            }
        }
    }
}
