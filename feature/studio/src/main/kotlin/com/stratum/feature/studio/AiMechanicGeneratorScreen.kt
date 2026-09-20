package com.stratum.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.stratum.feature.studio.event.AiLabUiAction
import com.stratum.feature.studio.state.AiLabUiState

/**
 * CHAPTER 19: PRESENTATION VIEW - OPEN AI GENERATOR (WEAPONS, RULES & MECHANICS)
 *
 * Provides an open prompt interface where players send natural language
 * prompts to generate Action RPG weapons, invent combat mechanics, or compose
 * tabletop Pen & Paper RPG rulebooks, immediately injected into the live engine.
 */

@Composable
fun AiMechanicGeneratorScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val isGenerating by viewModel.isAiGenerating.collectAsState()
    val statusMessage by viewModel.aiStatusMessage.collectAsState()
    val activeModel by viewModel.selectedModel.collectAsState()
    val availableModels by viewModel.availableAiModels.collectAsState()

    val uiState = AiLabUiState(
        isGenerating = isGenerating,
        statusMessage = statusMessage ?: "",
        activeModel = activeModel,
        availableModels = availableModels
    )

    AiMechanicGeneratorScreen(
        state = uiState,
        onAction = { action ->
            when (action) {
                is AiLabUiAction.GenerateBundle -> viewModel.generateAgenticAssetBundle(action.prompt)
                is AiLabUiAction.GenerateSprite -> viewModel.generateAiSprite(action.prompt)
                is AiLabUiAction.GenerateMap -> viewModel.generateAiMap(action.prompt)
                is AiLabUiAction.GenerateWeapon -> viewModel.generateAiWeapon(action.prompt)
                is AiLabUiAction.InventMechanic -> viewModel.inventAiMechanic(action.prompt)
            }
        },
        modifier = modifier
    )
}

@Composable
fun AiMechanicGeneratorScreen(
    state: AiLabUiState,
    onAction: (AiLabUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var userPrompt by remember { mutableStateOf("") }
    val isGenerating = state.isGenerating
    val statusMessage = state.statusMessage

    val samplePrompts = listOf(
        "Thunder champion of Amadioha with storm crag sanctuary and spark javelin",
        "Ekpe shadow leopard warrior in ancient lost bronze catacombs with claw daggers",
        "Idemili sacred python priestess in mist swamp shrine with poison staff",
        "Anyanwu sun archer in radiant solar citadel with blazing uli bow"
    )

    val activeModel = state.activeModel
    val availableModels = state.availableModels
    val currentModelItem = availableModels.find { it.id == activeModel }
    val isImageModel = currentModelItem?.isImageCapable == true ||
            activeModel.contains("flux", true) ||
            activeModel.contains("image", true) ||
            activeModel.contains("sdxl", true)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(16.dp)
            .testTag("ai_mechanic_generator_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "✨ AGENTIC AI GAME & ASSET GENERATOR",
                        style = MaterialTheme.typography.titleMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Send agentic prompts to generate sprites, maps, weapon assets, and game systems.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isImageModel) Color(0xFF2C2210) else Color(0xFF1E2622),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            if (isImageModel) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                            else listOf(IgboArtColors.AmadiohaCyan, Color(0xFF00897B))
                        )
                    )
                ) {
                    Text(
                        text = if (isImageModel) "🎨 Image Gen: ${activeModel.split("/").lastOrNull() ?: activeModel}"
                               else "🤖 Model: ${activeModel.split("/").lastOrNull() ?: activeModel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isImageModel) IgboArtColors.AnyanwuGold else IgboArtColors.AmadiohaCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Open Prompt Input Box
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DESCRIBE SPRITES, MAPS, ASSETS OR WORLD",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        if (isImageModel) {
                            Text(
                                text = "🎨 Visual Asset Mode",
                                fontSize = 11.sp,
                                color = IgboArtColors.AnyanwuGold,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = userPrompt,
                        onValueChange = { userPrompt = it },
                        placeholder = { Text("e.g. Ancient horned bronze warrior with a lightning scepter in a lost underground catacomb...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .testTag("ai_prompt_input"),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Quick Agentic Prompts (Tap to use):",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.BronzeLight
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    samplePrompts.forEach { prompt ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1B231F),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { userPrompt = prompt }
                        ) {
                            Text(
                                text = "💡 $prompt",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isGenerating) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = IgboArtColors.AnyanwuGold,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Synthesizing with AI ($activeModel)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.AnyanwuGold
                            )
                        }
                    } else {
                        // Generator Action Buttons
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Agentic Bundle Synthesis (All in one)
                            Button(
                                onClick = {
                                    if (userPrompt.isNotEmpty()) {
                                        onAction(AiLabUiAction.GenerateBundle(userPrompt))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.AnyanwuGold),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_agentic_bundle_button")
                            ) {
                                Text(
                                    text = "🚀 AGENTIC BUNDLE: SPRITES + MAP + ASSET",
                                    color = Color.Black,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (userPrompt.isNotEmpty()) {
                                            onAction(AiLabUiAction.GenerateSprite(userPrompt))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_generate_sprite_button")
                                ) {
                                    Text("🎨 SPRITE SHEET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        if (userPrompt.isNotEmpty()) {
                                            onAction(AiLabUiAction.GenerateMap(userPrompt))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_generate_map_button")
                                ) {
                                    Text("🗺️ DUNGEON MAP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (userPrompt.isNotEmpty()) {
                                            onAction(AiLabUiAction.GenerateWeapon(userPrompt))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.Terracotta),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_generate_weapon_button")
                                ) {
                                    Text("⚔️ WEAPON ASSET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        if (userPrompt.isNotEmpty()) {
                                            onAction(AiLabUiAction.InventMechanic(userPrompt))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_invent_mechanic_button")
                                ) {
                                    Text("⚙️ INVENT RULES", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Status Response Banner
        statusMessage?.let { msg ->
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
