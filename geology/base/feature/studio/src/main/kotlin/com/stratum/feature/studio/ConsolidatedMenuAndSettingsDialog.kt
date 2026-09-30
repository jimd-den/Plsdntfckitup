package com.stratum.feature.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stratum.legacy.domain.AiModelItem

/**
 * CONSOLIDATED MENU & SETTINGS DIALOG
 *
 * Merges engine settings, AI model selection, sprite sheet transparency policing,
 * and game navigation into a single intuitive interface.
 * - Dynamic AI Connect: Prioritizes latest available free models for image generation,
 *   with paid models listed second.
 * - Sprite Sheet Transparency Police: Chroma key background removal and 4x4 grid integrity.
 * - Consolidated Game Menu: Rapid navigation across all game systems.
 */
@Composable
fun ConsolidatedMenuAndSettingsDialog(
    viewModel: ArpgEngineViewModel,
    onDismiss: () -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    val sections = listOf("🎮 Diablo Eras", "✨ AI Theme", "🎨 Spritesheets", "🛡️ AI Connect", "🧭 Menu")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(IgboArtColors.NsibidiNight)
                .testTag("consolidated_settings_menu_dialog"),
            color = IgboArtColors.NsibidiNight
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚙️", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ENGINE MENU & SETTINGS",
                                style = MaterialTheme.typography.titleMedium,
                                color = IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "AI Connect, Sprite Policing & Navigation",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.BronzeLight,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(IgboArtColors.NsibidiCard)
                            .testTag("settings_close_button")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close Settings",
                            tint = IgboArtColors.SacredIvory
                        )
                    }
                }

                // Consolidated Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedSection,
                    containerColor = IgboArtColors.NsibidiSurface,
                    contentColor = IgboArtColors.AnyanwuGold,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedSection]),
                            color = IgboArtColors.AnyanwuGold,
                            height = 3.dp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    sections.forEachIndexed { index, title ->
                        val isSelected = selectedSection == index
                        Tab(
                            selected = isSelected,
                            onClick = { selectedSection = index },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) IgboArtColors.AnyanwuGold else IgboArtColors.SacredSand,
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.testTag("settings_tab_$index")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Content Body
                when (selectedSection) {
                    0 -> DiabloErasSettingsSection(viewModel = viewModel)
                    1 -> AiThemeWeaverSettingsSection(viewModel = viewModel)
                    2 -> OpenRouterSpritesheetPushSection(viewModel = viewModel)
                    3 -> AiConnectSettingsSection(viewModel = viewModel)
                    4 -> GameMenuNavigationSection(viewModel = viewModel, onDismiss = onDismiss)
                }

            }
        }
    }
}

/**
 * SECTION 1: DYNAMIC AI CONNECT & MODELS
 * Prioritizes latest free models (image generation models first), paid models second.
 */
@Composable
fun AiConnectSettingsSection(viewModel: ArpgEngineViewModel) {
    val currentApiKey by viewModel.openRouterApiKey.collectAsState()
    val currentBaseUrl by viewModel.customBaseUrl.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val availableModels by viewModel.availableAiModels.collectAsState()
    val isLoading by viewModel.isLoadingAiModels.collectAsState()

    var tempApiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var tempBaseUrl by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var searchQuery by remember { mutableStateOf("") }
    var filterOnlyFree by remember { mutableStateOf(false) }

    val filteredModels = remember(availableModels, searchQuery, filterOnlyFree) {
        availableModels.filter { model ->
            val matchesQuery = searchQuery.isEmpty() ||
                    model.name.contains(searchQuery, ignoreCase = true) ||
                    model.id.contains(searchQuery, ignoreCase = true)
            val matchesFree = !filterOnlyFree || model.isFree
            matchesQuery && matchesFree
        }
    }

    val freeModels = remember(filteredModels) { filteredModels.filter { it.isFree } }
    val paidModels = remember(filteredModels) { filteredModels.filter { !it.isFree } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ai_connect_settings_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // API Configuration Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "CONNECTIVITY & ENDPOINTS",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connects dynamically to OpenRouter or OpenAI-compatible gateways. Fetches live available models without hardcoding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        label = { Text("API Key (sk-or-... or blank for free models)") },
                        placeholder = { Text("Optional for free OpenRouter models") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_api_key_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tempBaseUrl,
                        onValueChange = { tempBaseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_base_url_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.setOpenRouterSettings(tempApiKey, selectedModel, tempBaseUrl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_api_settings_button")
                        ) {
                            Text("SAVE CONFIG", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.fetchAvailableAiModels() },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzeDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("refresh_models_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = IgboArtColors.AnyanwuGold,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = IgboArtColors.AnyanwuGold
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FETCH LIVE", color = IgboArtColors.AnyanwuGold)
                        }
                    }
                }
            }
        }

        // Active Model Summary Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B231D)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.AnyanwuGold, IgboArtColors.AmadiohaCyan)
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE SELECTED MODEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedModel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    val isCurFree = selectedModel.contains("free", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isCurFree) Color(0xFF1B5E20) else IgboArtColors.BronzeDark
                    ) {
                        Text(
                            text = if (isCurFree) "FREE" else "PAID",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurFree) Color(0xFF81C784) else IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Search & Filter Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search models (e.g. flash, free, vision)...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = IgboArtColors.SacredSand) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("model_search_input")
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (filterOnlyFree) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiCard,
                    modifier = Modifier.clickable { filterOnlyFree = !filterOnlyFree }
                ) {
                    Text(
                        text = if (filterOnlyFree) "✓ Free Only" else "All Models",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (filterOnlyFree) Color.White else IgboArtColors.SacredSand,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // GROUP 1: FREE MODELS (Image & Text)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🌟 FREE MODELS (${freeModels.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF81C784),
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Prioritized for image & sprite generation",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand,
                    fontSize = 10.sp
                )
            }
        }

        items(freeModels, key = { it.id }) { model ->
            ModelCardItem(
                model = model,
                isSelected = model.id == selectedModel,
                onSelect = { viewModel.setSelectedModel(model.id) }
            )
        }

        // GROUP 2: PAID MODELS (Listed Second)
        if (!filterOnlyFree && paidModels.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💳 PAID MODELS (${paidModels.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = IgboArtColors.BronzeLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Requires OpenRouter API credit",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand,
                        fontSize = 10.sp
                    )
                }
            }

            items(paidModels, key = { it.id }) { model ->
                ModelCardItem(
                    model = model,
                    isSelected = model.id == selectedModel,
                    onSelect = { viewModel.setSelectedModel(model.id) }
                )
            }
        }
    }
}

/**
 * Clean Model Card Item displaying badges, context length, pricing, and 1-tap select.
 */
@Composable
private fun ModelCardItem(
    model: AiModelItem,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) IgboArtColors.BronzeDark.copy(alpha = 0.5f) else IgboArtColors.NsibidiCard
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                if (isSelected) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                else if (model.isFree) listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
                else listOf(Color(0xFF37474F), Color(0xFF263238))
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("model_card_${model.id.replace('/', '_').replace(':', '_')}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) IgboArtColors.AnyanwuGold else IgboArtColors.SacredIvory,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.BronzeLight,
                        fontSize = 10.sp
                    )
                }

                // Badges
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (model.isImageCapable) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = IgboArtColors.BronzePrimary
                        ) {
                            Text(
                                text = "🎨 IMAGE / SPRITES",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (model.isFree) Color(0xFF2E7D32) else Color(0xFF37474F)
                    ) {
                        Text(
                            text = if (model.isFree) "FREE" else "PAID",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (model.isFree) Color(0xFFA5D6A7) else Color.LightGray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${model.pricingDisplay} • ${model.description.ifEmpty { "General AI model" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) IgboArtColors.AnyanwuGold else IgboArtColors.BronzePrimary
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isSelected) "✓ ACTIVE" else "SELECT",
                        color = if (isSelected) IgboArtColors.NsibidiNight else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * SECTION 2: SPRITE SHEET TRANSPARENCY POLICE
 * Transparently polices background chroma-keying and 4x4 grid compliance.
 */
@Composable
fun SpritePoliceSettingsSection(viewModel: ArpgEngineViewModel) {
    val autoChromaKey by viewModel.autoChromaKey.collectAsState()
    val tolerance by viewModel.chromaTolerance.collectAsState()
    val activeSprite by viewModel.activeSprite.collectAsState()
    val report by viewModel.spritePoliceReport.collectAsState()

    var localTolerance by remember(tolerance) { mutableFloatStateOf(tolerance) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("sprite_police_settings_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Police Pipeline Overview Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🛡️ SPRITE SHEET TRANSPARENCY & INTEGRITY POLICE",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Guarantees sprite sheets seamlessly render in the ARPG loop without solid background boxes. Automatically detects background corners, applies chroma-key transparency, and enforces 4x4 grid layout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Configuration Controls
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Auto-police switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Police Sprite Pipeline",
                                style = MaterialTheme.typography.labelMedium,
                                color = IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Apply chroma key and grid check to all AI generations and imports",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                fontSize = 10.sp
                            )
                        }

                        Switch(
                            checked = autoChromaKey,
                            onCheckedChange = { viewModel.setAutoChromaKey(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = IgboArtColors.AnyanwuGold,
                                checkedTrackColor = IgboArtColors.BronzePrimary
                            ),
                            modifier = Modifier.testTag("auto_police_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = IgboArtColors.NsibidiSurface)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Chroma-key tolerance slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chroma-Key Color Tolerance",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(localTolerance * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Slider(
                        value = localTolerance,
                        onValueChange = { localTolerance = it },
                        onValueChangeFinished = { viewModel.setChromaTolerance(localTolerance) },
                        valueRange = 0.05f..0.35f,
                        colors = SliderDefaults.colors(
                            thumbColor = IgboArtColors.AnyanwuGold,
                            activeTrackColor = IgboArtColors.BronzePrimary
                        ),
                        modifier = Modifier.testTag("chroma_tolerance_slider")
                    )

                    Text(
                        text = "Lower tolerance preserves edge details; higher tolerance aggressively removes solid shadows or compression artifacts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.BronzeLight,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Active Sprite & Manual Trigger
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF19211D)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "CURRENT BOUND SPRITE: ${activeSprite.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Grid: ${activeSprite.columns} cols x ${activeSprite.rows} rows (${activeSprite.totalFrames} frames)",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { viewModel.policeCurrentSpriteSheet() },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("police_current_sprite_button")
                    ) {
                        Text("🛡️ POLICE ACTIVE SPRITE SHEET NOW", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Inspection Report Banner
        report?.let { rep ->
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            if (rep.isTransparentPoliced) listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
                            else listOf(IgboArtColors.LifeGlobeRed, Color(0xFF8E0000))
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LATEST POLICE AUDIT REPORT",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (rep.isTransparentPoliced) Color(0xFF81C784) else IgboArtColors.LifeGlobeRed,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (rep.isTransparentPoliced) Color(0xFF1B5E20) else Color(0xFF4A1010)
                            ) {
                                Text(
                                    text = if (rep.isTransparentPoliced) "PASSED" else "WARNING",
                                    color = if (rep.isTransparentPoliced) Color(0xFFA5D6A7) else Color(0xFFFF8A80),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = rep.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredIvory
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Grid integrity: ${rep.detectedGridCols}x${rep.detectedGridRows} (${rep.totalFrames} frames)\n• Frame size: ${rep.frameWidth}x${rep.frameHeight}px\n• Status: ${if (rep.isTransparentPoliced) "Chroma key transparent & verified" else "Pending or partial"}",
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

/**
 * SECTION 3: CONSOLIDATED GAME MENU & QUICK NAVIGATION
 * Rapid navigation shortcuts and player engine controls.
 */
@Composable
fun GameMenuNavigationSection(
    viewModel: ArpgEngineViewModel,
    onDismiss: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("game_menu_navigation_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "ENGINE DESTINATIONS",
                style = MaterialTheme.typography.titleSmall,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1C12)),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary))),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.navigateTo(AppScreen.START_MENU)
                        onDismiss()
                    }
                    .testTag("menu_nav_start_menu")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🏠", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Start Screen & Main Menu",
                                style = MaterialTheme.typography.labelLarge,
                                color = IgboArtColors.AnyanwuGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Return to the main title hub",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text("➔", color = IgboArtColors.AnyanwuGold, fontWeight = FontWeight.Bold)
                }
            }
        }

        items(MainNavigationTab.values()) { tab ->
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (tab) {
                            MainNavigationTab.ARENA -> viewModel.navigateTo(AppScreen.ARENA)
                            MainNavigationTab.FORGE_LAB -> viewModel.navigateTo(AppScreen.FORGE_LAB)
                            MainNavigationTab.SPRITES -> {
                                viewModel.setCreatorSubTab(CreatorSubTab.SPRITES)
                                viewModel.navigateTo(AppScreen.CREATOR_STUDIO)
                            }
                            MainNavigationTab.CLASS_STUDIO -> {
                                viewModel.setCreatorSubTab(CreatorSubTab.CLASSES)
                                viewModel.navigateTo(AppScreen.CREATOR_STUDIO)
                            }
                            MainNavigationTab.MAP_STUDIO -> {
                                viewModel.setCreatorSubTab(CreatorSubTab.MAPS)
                                viewModel.navigateTo(AppScreen.CREATOR_STUDIO)
                            }
                            MainNavigationTab.AI_STUDIO -> {
                                viewModel.setCreatorSubTab(CreatorSubTab.AI_MECHANICS)
                                viewModel.navigateTo(AppScreen.CREATOR_STUDIO)
                            }
                            MainNavigationTab.PEN_AND_PAPER -> viewModel.navigateTo(AppScreen.PEN_AND_PAPER)
                            MainNavigationTab.CODEX -> viewModel.navigateTo(AppScreen.LORE_CODEX)
                        }
                        viewModel.setNavigationTab(tab)
                        onDismiss()
                    }
                    .testTag("menu_nav_${tab.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = tab.iconSymbol, fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (tab) {
                                    MainNavigationTab.ARENA -> "Real-time ARPG combat loop & enemy waves"
                                    MainNavigationTab.FORGE_LAB -> "Damage calculator, weapon rolls & custom procs"
                                    MainNavigationTab.SPRITES -> "Import, edit & animate 2D sprite sheets"
                                    MainNavigationTab.CLASS_STUDIO -> "Build warrior classes & visually script node logic"
                                    MainNavigationTab.MAP_STUDIO -> "Procedural dungeon generator & isometric projection"
                                    MainNavigationTab.PEN_AND_PAPER -> "D20 skill checks, Igbo rulebooks & dice rolls"
                                    MainNavigationTab.AI_STUDIO -> "Prompt-based synthesis for weapons and lore"
                                    MainNavigationTab.CODEX -> "Ancient Igbo history, Nsibidi glyphs & artifacts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Text("➔", color = IgboArtColors.AnyanwuGold, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF221A15))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "QUICK CHEATS & TESTING CONTROLS",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.engineState.hero.gold += 500
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+500 Gold", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                viewModel.engineState.hero.currentHealth = viewModel.engineState.hero.maxHealth
                                viewModel.engineState.hero.currentSpirit = viewModel.engineState.hero.maxSpirit
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Full Heal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
