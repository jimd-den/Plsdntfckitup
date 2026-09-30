package com.stratum.feature.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.legacy.domain.CharacterAnimationState
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.feature.studio.event.SpriteStudioUiAction
import com.stratum.feature.studio.state.SpriteStudioUiState

/**
 * CHAPTER 17: PRESENTATION VIEW - SPRITE STUDIO (AI GENERATOR, IMPORT & EXPORT)
 *
 * Full-featured sprite management suite:
 * 1. OpenRouter & Open API integration to generate sprites with ANY model.
 * 2. Upload / Import: Android Photo Picker image import + JSON sprite importer.
 * 3. Download / Export: Export 4x4 animated sprite sheets to PNG and JSON.
 * 4. System Share Sheet integration.
 * 5. Real-time sliced frame preview canvas.
 */

@Composable
fun SpriteSheetImporterScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val spriteSheets by viewModel.spriteSheets.collectAsState()
    val activeSprite by viewModel.activeSprite.collectAsState()
    val isAiGenerating by viewModel.isAiGenerating.collectAsState()
    val statusMessage by viewModel.aiStatusMessage.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val availableAiModels by viewModel.availableAiModels.collectAsState()

    val uiState = SpriteStudioUiState(
        spriteSheets = spriteSheets,
        activeSprite = activeSprite,
        isAiGenerating = isAiGenerating,
        statusMessage = statusMessage ?: "",
        selectedModel = selectedModel,
        availableAiModels = availableAiModels
    )

    SpriteSheetImporterScreen(
        state = uiState,
        onAction = { action ->
            when (action) {
                is SpriteStudioUiAction.SelectSprite -> viewModel.setActiveSpriteSheet(action.sheet)
                is SpriteStudioUiAction.GenerateAiSprite -> viewModel.generateAiSprite(action.prompt)
                is SpriteStudioUiAction.GenerateRawImageSprite -> viewModel.generateRawImageSprite(action.prompt)
                is SpriteStudioUiAction.ExportPng -> viewModel.exportSpritePng(action.context, action.sheet)
                is SpriteStudioUiAction.ExportJson -> viewModel.exportSpriteJson(action.context, action.sheet)
                is SpriteStudioUiAction.ShareSprite -> viewModel.shareSprite(action.context, action.sheet, action.asPng)
                is SpriteStudioUiAction.ImportFromJson -> viewModel.importSpriteFromJson(action.json)
                is SpriteStudioUiAction.ImportFromImageDetailed -> viewModel.importSpriteFromImage(
                    context = action.context,
                    uri = action.uri,
                    name = action.name,
                    desc = action.desc,
                    cols = action.cols,
                    rows = action.rows
                )
                is SpriteStudioUiAction.ImportCustomSheet -> viewModel.importSpriteSheet(
                    name = action.name,
                    description = action.desc,
                    frameWidth = action.frameWidth,
                    frameHeight = action.frameHeight,
                    columns = action.cols,
                    rows = action.rows,
                    uri = action.uri?.toString()
                )
                is SpriteStudioUiAction.ImportFromImage -> {}
                is SpriteStudioUiAction.ImportProcedural -> viewModel.importSpriteSheet(action.sheet.name, action.sheet.description, 48, 48, action.sheet.columns, action.sheet.rows, null)
                is SpriteStudioUiAction.OpenSettings -> viewModel.setSettingsMenuOpen(true)
            }
        },
        modifier = modifier
    )
}

@Composable
fun SpriteSheetImporterScreen(
    state: SpriteStudioUiState,
    onAction: (SpriteStudioUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spriteSheets = state.spriteSheets
    val activeSprite = state.activeSprite
    val isAiGenerating = state.isAiGenerating
    val statusMessage = state.statusMessage

    // OpenRouter Settings State
    val selectedModel = state.selectedModel
    val availableAiModels = state.availableAiModels
    val activeModelObj = availableAiModels.find { it.id == selectedModel }
    val clipboardManager = LocalClipboardManager.current
    var promptCopied by remember { mutableStateOf(false) }

    // Sprite Generation State
    var aiPrompt by remember { mutableStateOf("") }

    // Manual / Upload Form State
    var sheetName by remember { mutableStateOf("") }
    var sheetDesc by remember { mutableStateOf("") }
    var columns by remember { mutableFloatStateOf(4f) }
    var rows by remember { mutableFloatStateOf(4f) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    // Preview controls
    var selectedAnimState by remember { mutableStateOf(CharacterAnimationState.IDLE) }
    var fpsSlider by remember { mutableFloatStateOf(8f) }

    // Dialogs
    var showJsonImportDialog by remember { mutableStateOf(false) }
    var pastedJsonText by remember { mutableStateOf("") }

    // Modern Zero-Permission Photo Picker Launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            if (sheetName.isEmpty()) {
                sheetName = "Uploaded Sprite ${System.currentTimeMillis() % 1000}"
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(16.dp)
            .testTag("sprite_sheet_importer_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header & Model Status
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🎨 SPRITE STUDIO",
                        style = MaterialTheme.typography.titleLarge,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Import, Export & AI Generation with Open APIs",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E2622),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)
                        )
                    ),
                    modifier = Modifier.clickable { onAction(SpriteStudioUiAction.OpenSettings) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "AI & Police Settings",
                            tint = IgboArtColors.AnyanwuGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = selectedModel.split("/").lastOrNull() ?: selectedModel,
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⚙️",
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Live Animation Preview Card & Export / Download Bar
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE ANIMATION LOOP PREVIEW",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeSprite.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Animated Canvas (Supports both uploaded/generated Bitmaps & procedural styles)
                    LiveSpriteAnimationCanvas(
                        sprite = activeSprite,
                        animState = selectedAnimState,
                        fps = fpsSlider
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Animation state switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            CharacterAnimationState.IDLE,
                            CharacterAnimationState.WALK,
                            CharacterAnimationState.ATTACK,
                            CharacterAnimationState.CAST_SPELL
                        ).forEach { state ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedAnimState == state) IgboArtColors.BronzePrimary else Color(0xFF1E2622))
                                    .clickable { selectedAnimState = state }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = state.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speed: ${fpsSlider.toInt()} FPS",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredIvory
                        )
                        Slider(
                            value = fpsSlider,
                            onValueChange = { fpsSlider = it },
                            valueRange = 2f..24f,
                            modifier = Modifier.width(180.dp),
                            colors = SliderDefaults.colors(thumbColor = IgboArtColors.AnyanwuGold)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // DOWNLOAD / EXPORT ACTION BAR
                    Text(
                        text = "EXPORT / DOWNLOAD CURRENT SPRITE:",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.BronzeLight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onAction(SpriteStudioUiAction.ExportPng(context, activeSprite)) },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_png_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PNG (SHEET)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onAction(SpriteStudioUiAction.ExportJson(context, activeSprite)) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_json_button")
                        ) {
                            Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { onAction(SpriteStudioUiAction.ShareSprite(context, activeSprite, asPng = true)) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("share_sprite_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = IgboArtColors.AnyanwuGold)
                        }
                    }
                }
            }
        }

        // AI SPRITE GENERATOR (OPENROUTER / OPEN APIS)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.AnyanwuGold, IgboArtColors.AmadiohaCyan)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "✨ AI SPRITE GENERATOR (OPEN APIS)",
                            style = MaterialTheme.typography.titleSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Using: $selectedModel",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.BronzeLight,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (activeModelObj?.isImageCapable == true) Color(0xFF1B3D2F) else Color(0xFF261D13)
                        ) {
                            Text(
                                text = if (activeModelObj?.isImageCapable == true) "🎨 Image Model" else "📐 Code / JSON",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activeModelObj?.isImageCapable == true) Color(0xFF81C784) else IgboArtColors.AnyanwuGold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        placeholder = { Text("e.g. Celestial Amadioha lightning warrior with glowing horn crest and bronze halberd") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_sprite_prompt_input"),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    // Quick prompts
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val prompts = listOf(
                            "Amadioha Thunder Shaman",
                            "Ancient Igbo-Ukwu Bronze Golem",
                            "Eze Nri Solar King with Staff",
                            "Mmanwu Ancestral Grove Spirit",
                            "Ekpe Leopard Clan Infiltrator"
                        )
                        items(prompts) { p ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1F2823),
                                modifier = Modifier.clickable { aiPrompt = p }
                            ) {
                                Text(
                                    text = p,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IgboArtColors.SacredSand,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isAiGenerating) {
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
                                text = statusMessage?.ifEmpty { "Synthesizing 4x4 Sprite Sheet..." } ?: "Synthesizing 4x4 Sprite Sheet...",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.AnyanwuGold
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Primary: Raw Image Sprite Synthesis (supports OpenRouter flux / SDXL / Dall-E)
                            Button(
                                onClick = {
                                    if (aiPrompt.isNotBlank()) {
                                        onAction(SpriteStudioUiAction.GenerateRawImageSprite(aiPrompt))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("generate_raw_image_sprite_button")
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🎨 GENERATE RAW IMAGE SPRITE SHEET", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                            }

                            // Secondary: Procedural synthesis
                            OutlinedButton(
                                onClick = {
                                    if (aiPrompt.isNotBlank()) {
                                        onAction(SpriteStudioUiAction.GenerateAiSprite(aiPrompt))
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("generate_procedural_sprite_button")
                            ) {
                                Text("✨ PROCEDURAL VECTOR SPRITE SYNTHESIS", fontWeight = FontWeight.Bold, color = IgboArtColors.AnyanwuGold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // DEDICATED EXTERNAL LLM / IMAGE MODEL PROMPT GENERATOR
        item {
            val externalPromptTemplate = remember(aiPrompt) {
                val subject = if (aiPrompt.isNotBlank()) aiPrompt.trim() else "Ancient Igbo-Ukwu Bronze Warrior King with horned crown, ceremonial bronze staff, and sacred Nsibidi etched breastplate"
                """2D game sprite sheet, 4 rows and 4 columns grid, 16 frames total.
Subject: $subject.
Art style: Authentic West African Igbo mythic fantasy, high quality detailed pixel art, rich bronze, terracotta, and gold palette.
Grid Layout:
- Row 1 (Top): 4 walking animation frames facing FRONT / SOUTH.
- Row 2: 4 walking animation frames facing LEFT / WEST.
- Row 3: 4 walking animation frames facing RIGHT / EAST.
- Row 4 (Bottom): 4 walking animation frames facing BACK / NORTH.
Framing: Exactly 16 uniform 64x64 pixel cells arranged in a 4x4 matrix, completely isolated on solid flat green screen (#00FF00) background, consistent character proportions across all 16 frames, no perspective distortion, game asset sheet.""".trimIndent()
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1410)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.AnyanwuGold.copy(alpha = 0.6f), IgboArtColors.BronzePrimary)
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "📋 EXTERNAL SPRITE PROMPT ARCHITECT",
                                style = MaterialTheme.typography.titleSmall,
                                color = IgboArtColors.AnyanwuGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Export tested prompts to Midjourney, Flux, SDXL, or OpenRouter",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredSand,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Prompt Preview Box
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F0C0A),
                        border = CardDefaults.outlinedCardBorder(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = externalPromptTemplate,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    // Copy Button with tactile feedback
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(externalPromptTemplate))
                            promptCopied = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (promptCopied) Color(0xFF2E7D32) else IgboArtColors.BronzePrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_external_prompt_button")
                    ) {
                        Icon(
                            imageVector = if (promptCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (promptCopied) "✓ PROMPT COPIED TO CLIPBOARD!" else "📋 COPY PROMPT TO CLIPBOARD",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // UPLOAD & IMPORT CUSTOM SPRITE SHEET (IMAGE / PHOTO PICKER & JSON)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📥 UPLOAD / IMPORT SPRITE SHEET",
                        style = MaterialTheme.typography.titleSmall,
                        color = IgboArtColors.BronzeLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Upload custom sprite images from device or import JSON animations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("upload_image_picker_button")
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("UPLOAD IMAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showJsonImportDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_json_dialog_button")
                        ) {
                            Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("IMPORT JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    selectedImageUri?.let { uri ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1C271F),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🖼️", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Selected image ready to slice into grid frames",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IgboArtColors.AnyanwuGold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sheetName,
                        onValueChange = { sheetName = it },
                        label = { Text("Sprite Sheet Name (e.g. Mmanwu Sorcerer)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sprite_name_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sheetDesc,
                        onValueChange = { sheetDesc = it },
                        label = { Text("Description / Lore") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Columns: ${columns.toInt()}", style = MaterialTheme.typography.bodySmall, color = IgboArtColors.SacredIvory)
                            Slider(
                                value = columns,
                                onValueChange = { columns = it },
                                valueRange = 1f..16f,
                                colors = SliderDefaults.colors(thumbColor = IgboArtColors.BronzePrimary)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Rows: ${rows.toInt()}", style = MaterialTheme.typography.bodySmall, color = IgboArtColors.SacredIvory)
                            Slider(
                                value = rows,
                                onValueChange = { rows = it },
                                valueRange = 1f..16f,
                                colors = SliderDefaults.colors(thumbColor = IgboArtColors.BronzePrimary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (sheetName.isNotEmpty()) {
                                if (selectedImageUri != null) {
                                    onAction(
                                        SpriteStudioUiAction.ImportFromImageDetailed(
                                            context = context,
                                            uri = selectedImageUri!!,
                                            name = sheetName,
                                            desc = sheetDesc,
                                            cols = columns.toInt(),
                                            rows = rows.toInt()
                                        )
                                    )
                                    selectedImageUri = null
                                } else {
                                    onAction(
                                        SpriteStudioUiAction.ImportCustomSheet(
                                            name = sheetName,
                                            desc = sheetDesc,
                                            frameWidth = 48,
                                            frameHeight = 48,
                                            cols = columns.toInt(),
                                            rows = rows.toInt(),
                                            uri = null
                                        )
                                    )
                                }
                                sheetName = ""
                                sheetDesc = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_sprite_sheet_button")
                    ) {
                        Text("SAVE & BIND TO LIVE HERO", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // Status Notification
        statusMessage?.let { msg ->
            item {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2822)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                        )
                    )
                ) {
                    Text(
                        text = "⚡ $msg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IgboArtColors.SacredIvory,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Available Sprite Library
        item {
            Text(
                text = "SPRITE LIBRARY (${spriteSheets.size})",
                style = MaterialTheme.typography.titleSmall,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
        }

        items(spriteSheets) { sheet ->
            val isCurrent = sheet.id == activeSprite.id
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) IgboArtColors.BronzeDark.copy(alpha = 0.5f) else IgboArtColors.NsibidiCard
                ),
                border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                    )
                ) else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sheet.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCurrent) IgboArtColors.AnyanwuGold else IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${sheet.columns}x${sheet.rows} Grid (${sheet.totalFrames} frames) • ${sheet.igboThemeRole}",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.BronzeLight
                            )
                        }

                        if (isCurrent) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = IgboArtColors.AnyanwuGold,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "ACTIVE HERO",
                                    color = IgboArtColors.NsibidiNight,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Button(
                                onClick = { onAction(SpriteStudioUiAction.SelectSprite(sheet)) },
                                colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("BIND", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sheet.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    // Item download/export buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { onAction(SpriteStudioUiAction.ExportPng(context, sheet)) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⬇️ PNG", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { onAction(SpriteStudioUiAction.ExportJson(context, sheet)) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📄 JSON", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { onAction(SpriteStudioUiAction.ShareSprite(context, sheet, asPng = true)) },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    // JSON Import Dialog
    if (showJsonImportDialog) {
        AlertDialog(
            onDismissRequest = { showJsonImportDialog = false },
            title = {
                Text(
                    text = "Import Sprite Sheet JSON",
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste complete SpriteSheetData JSON representation below:",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pastedJsonText,
                        onValueChange = { pastedJsonText = it },
                        placeholder = { Text("{\n  \"name\": \"Custom Hero\",\n  \"columns\": 4,\n  ...\n}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pastedJsonText.isNotEmpty()) {
                            onAction(SpriteStudioUiAction.ImportFromJson(pastedJsonText))
                            pastedJsonText = ""
                            showJsonImportDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary)
                ) {
                    Text("IMPORT & BIND")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJsonImportDialog = false }) {
                    Text("CANCEL", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1B231F)
        )
    }
}

@Composable
fun LiveSpriteAnimationCanvas(
    sprite: SpriteSheetData,
    animState: CharacterAnimationState,
    fps: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sprite_anim")
    val frameIndexFloat by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (4000 / fps).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "frame"
    )

    // Decode bitmap once if base64 is present
    val customBitmap = remember(sprite.rawImageUriOrBase64) {
        sprite.rawImageUriOrBase64
            ?.takeIf { it.isNotEmpty() }
            ?.let(::decodeBase64ToBitmap)
    }

    Box(
        modifier = Modifier
            .size(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C110E))
            .border(2.dp, IgboArtColors.BronzeDark, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val curFrame = (frameIndexFloat.toInt() % 4).coerceAtLeast(0)

            if (customBitmap != null) {
                // Sliced frame rendering from uploaded / AI-generated bitmap!
                val cols = sprite.columns.coerceAtLeast(1)
                val rows = sprite.rows.coerceAtLeast(1)
                val frameW = customBitmap.width / cols
                val frameH = customBitmap.height / rows

                val row = when (animState) {
                    CharacterAnimationState.IDLE -> 0 % rows
                    CharacterAnimationState.WALK -> 1 % rows
                    CharacterAnimationState.ATTACK -> 2 % rows
                    CharacterAnimationState.CAST_SPELL -> 3 % rows
                    else -> 0
                }
                val col = curFrame % cols

                val srcLeft = col * frameW
                val srcTop = row * frameH
                val srcRect = android.graphics.Rect(srcLeft, srcTop, srcLeft + frameW, srcTop + frameH)
                val dstRect = android.graphics.Rect(10, 10, size.width.toInt() - 10, size.height.toInt() - 10)

                drawContext.canvas.nativeCanvas.drawBitmap(customBitmap, srcRect, dstRect, null)
            } else {
                // Procedural stylized drawing
                // Draw Sprite Shadow
                drawOval(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(cx - 24f, cy + 28f),
                    size = Size(48f, 14f)
                )

                val bounceY = if (animState == CharacterAnimationState.WALK) (curFrame % 2) * 4f else 0f
                val heroY = cy + bounceY

                // Body
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(IgboArtColors.BronzeLight, IgboArtColors.BronzePrimary, IgboArtColors.BronzeDark),
                        center = Offset(cx, heroY),
                        radius = 24f
                    ),
                    radius = 24f,
                    center = Offset(cx, heroY)
                )

                // Feathers / Mask depending on character
                when {
                    sprite.name.contains("Ikenga", true) -> {
                        drawCircle(color = IgboArtColors.SacredSand, radius = 8f, center = Offset(cx - 16f, heroY - 18f))
                        drawCircle(color = IgboArtColors.SacredSand, radius = 8f, center = Offset(cx + 16f, heroY - 18f))
                    }
                    sprite.name.contains("Mmanwu", true) -> {
                        drawCircle(color = IgboArtColors.Terracotta, radius = 10f, center = Offset(cx, heroY - 8f))
                    }
                    else -> {
                        drawCircle(color = IgboArtColors.SacredIvory, radius = 8f, center = Offset(cx, heroY - 16f))
                    }
                }

                // Weapon slash if attack state
                if (animState == CharacterAnimationState.ATTACK) {
                    val slashAngle = (curFrame * 30f) * (Math.PI.toFloat() / 180f)
                    val swordX = cx + kotlin.math.cos(slashAngle) * 36f
                    val swordY = heroY + kotlin.math.sin(slashAngle) * 36f
                    drawLine(
                        color = IgboArtColors.AnyanwuGold,
                        start = Offset(cx, heroY),
                        end = Offset(swordX, swordY),
                        strokeWidth = 5f
                    )
                }
            }
        }

        // Frame indicator overlay
        Text(
            text = "Frame ${(frameIndexFloat.toInt() % 4) + 1}/4",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        )
    }
}

private fun decodeBase64ToBitmap(base64: String): android.graphics.Bitmap? {
    return try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

