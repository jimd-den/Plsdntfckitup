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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import com.stratum.legacy.domain.ActionLogicNode
import com.stratum.legacy.domain.ConditionLogicNode
import com.stratum.legacy.domain.DamageType
import com.stratum.legacy.domain.HeroClassDefinition
import com.stratum.legacy.domain.ModifierLogicNode
import com.stratum.legacy.domain.SkillCastType
import com.stratum.legacy.domain.SkillDefinition
import com.stratum.legacy.domain.StatusEffectType
import com.stratum.legacy.domain.TriggerLogicNode
import com.stratum.legacy.domain.VisualLogicScript
import com.stratum.feature.studio.event.ClassesStudioUiAction
import com.stratum.feature.studio.state.ClassesStudioUiState

/**
 * CHAPTER 22: PRESENTATION VIEW - CLASS BUILDER & VISUAL LOGIC STUDIO
 *
 * Allows users to:
 * 1. Choose from preloaded Hero Classes or forge their own custom class (Ike, Uche, Ndu, Mmuo stats)
 * 2. Craft Custom Powers & Attacks (Igbo incantation, cast type, damage multiplier, cooldown)
 * 3. Visually program game logic via nodes:
 *    [TRIGGER] -> [CONDITION] -> [ACTION] -> [MODIFIER]
 */

@Composable
fun ClassesAndPowersStudio(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val heroClasses by viewModel.heroClasses.collectAsState()
    val activeClass by viewModel.activeHeroClass.collectAsState()
    val availableSkills by viewModel.availableSkills.collectAsState()
    val activeScript by viewModel.activeLogicScript.collectAsState()

    val uiState = ClassesStudioUiState(
        heroClasses = heroClasses,
        activeClass = activeClass,
        availableSkills = availableSkills,
        activeScript = activeScript
    )

    ClassesAndPowersStudio(
        state = uiState,
        onAction = { action ->
            when (action) {
                is ClassesStudioUiAction.SelectClass -> viewModel.selectHeroClass(action.heroClass)
                is ClassesStudioUiAction.CreateClass -> viewModel.createCustomHeroClass(action.heroClass)
                is ClassesStudioUiAction.CreateSkill -> viewModel.createCustomSkill(action.skill)
                is ClassesStudioUiAction.SaveScript -> viewModel.saveLogicScript(action.script)
            }
        },
        modifier = modifier
    )
}

@Composable
fun ClassesAndPowersStudio(
    state: ClassesStudioUiState,
    onAction: (ClassesStudioUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedStudioSubTab by remember { mutableIntStateOf(0) }
    val heroClasses = state.heroClasses
    val activeClass = state.activeClass
    val availableSkills = state.availableSkills
    val activeScript = state.activeScript

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .testTag("classes_and_powers_studio")
    ) {
        // Studio Header
        Surface(
            color = IgboArtColors.NsibidiCard,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                Text(
                    text = "⚙️ CLASS & POWERS STUDIO",
                    style = MaterialTheme.typography.titleMedium,
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Forge warrior classes, design custom abilities, and visually script combat procs",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand
                )

                Spacer(modifier = Modifier.height(10.dp))

                TabRow(
                    selectedTabIndex = selectedStudioSubTab,
                    containerColor = Color.Transparent,
                    contentColor = IgboArtColors.AnyanwuGold,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedStudioSubTab]),
                            color = IgboArtColors.AnyanwuGold
                        )
                    }
                ) {
                    Tab(
                        selected = selectedStudioSubTab == 0,
                        onClick = { selectedStudioSubTab = 0 },
                        text = { Text("Hero Classes", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_hero_classes")
                    )
                    Tab(
                        selected = selectedStudioSubTab == 1,
                        onClick = { selectedStudioSubTab = 1 },
                        text = { Text("Power Builder", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_power_builder")
                    )
                    Tab(
                        selected = selectedStudioSubTab == 2,
                        onClick = { selectedStudioSubTab = 2 },
                        text = { Text("Visual Logic Nodes", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_visual_logic")
                    )
                }
            }
        }

        when (selectedStudioSubTab) {
            0 -> HeroClassBuilderTab(
                classes = heroClasses,
                activeClass = activeClass,
                onSelectClass = { onAction(ClassesStudioUiAction.SelectClass(it)) },
                onCreateClass = { onAction(ClassesStudioUiAction.CreateClass(it)) }
            )
            1 -> PowerAttackBuilderTab(
                skills = availableSkills,
                onCreateSkill = { onAction(ClassesStudioUiAction.CreateSkill(it)) }
            )
            2 -> VisualLogicNodeTab(
                script = activeScript,
                onSaveScript = { onAction(ClassesStudioUiAction.SaveScript(it)) }
            )
        }
    }
}

@Composable
fun HeroClassBuilderTab(
    classes: List<HeroClassDefinition>,
    activeClass: HeroClassDefinition,
    onSelectClass: (HeroClassDefinition) -> Unit,
    onCreateClass: (HeroClassDefinition) -> Unit
) {
    var isCreatingNew by remember { mutableStateOf(false) }
    var newClassName by remember { mutableStateOf("") }
    var newClassLore by remember { mutableStateOf("") }
    var newIke by remember { mutableIntStateOf(16) }
    var newUche by remember { mutableIntStateOf(14) }
    var newNdu by remember { mutableIntStateOf(130) }
    var newMmuo by remember { mutableIntStateOf(100) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AVAILABLE HERO CLASSES (${classes.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = IgboArtColors.SacredSand,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = { isCreatingNew = !isCreatingNew },
                    colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                    modifier = Modifier.testTag("btn_toggle_create_class")
                ) {
                    Text(if (isCreatingNew) "Close Builder" else "+ Forge New Class", fontSize = 12.sp)
                }
            }
        }

        // Creator Form
        if (isCreatingNew) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🛠️ Forge Custom Hero Archetype",
                            style = MaterialTheme.typography.titleMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = newClassName,
                            onValueChange = { newClassName = it },
                            label = { Text("Class Title (e.g. Odogwu Vanguard)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_custom_class_title"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = IgboArtColors.AnyanwuGold,
                                focusedLabelColor = IgboArtColors.AnyanwuGold
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = newClassLore,
                            onValueChange = { newClassLore = it },
                            label = { Text("Lore & Ancestral Background") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = IgboArtColors.AnyanwuGold,
                                focusedLabelColor = IgboArtColors.AnyanwuGold
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Stats Sliders
                        Text("Ike (Physical Might / Strength): $newIke", color = IgboArtColors.SacredIvory, fontSize = 12.sp)
                        Slider(
                            value = newIke.toFloat(),
                            onValueChange = { newIke = it.toInt() },
                            valueRange = 5f..35f,
                            colors = SliderDefaults.colors(thumbColor = IgboArtColors.BronzePrimary, activeTrackColor = IgboArtColors.BronzePrimary)
                        )

                        Text("Uche (Ancestral Wisdom / Spell Power): $newUche", color = IgboArtColors.SacredIvory, fontSize = 12.sp)
                        Slider(
                            value = newUche.toFloat(),
                            onValueChange = { newUche = it.toInt() },
                            valueRange = 5f..35f,
                            colors = SliderDefaults.colors(thumbColor = IgboArtColors.AmadiohaCyan, activeTrackColor = IgboArtColors.AmadiohaCyan)
                        )

                        Text("Ndụ (Starting Health Pool): $newNdu HP", color = IgboArtColors.LifeGlobeRed, fontSize = 12.sp)
                        Slider(
                            value = newNdu.toFloat(),
                            onValueChange = { newNdu = it.toInt() },
                            valueRange = 80f..250f,
                            colors = SliderDefaults.colors(thumbColor = IgboArtColors.LifeGlobeRed, activeTrackColor = IgboArtColors.LifeGlobeRed)
                        )

                        Text("Mmụọ (Starting Spirit Pool): $newMmuo MP", color = IgboArtColors.SpiritGlobeBlue, fontSize = 12.sp)
                        Slider(
                            value = newMmuo.toFloat(),
                            onValueChange = { newMmuo = it.toInt() },
                            valueRange = 50f..200f,
                            colors = SliderDefaults.colors(thumbColor = IgboArtColors.SpiritGlobeBlue, activeTrackColor = IgboArtColors.SpiritGlobeBlue)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (newClassName.isNotBlank()) {
                                    val forged = HeroClassDefinition(
                                        id = "custom_class_${System.currentTimeMillis()}",
                                        name = newClassName,
                                        title = newClassName,
                                        patronDeity = "Ancestral Idemili",
                                        loreDescription = newClassLore.ifEmpty { "A self-forged warrior of the sacred forest." },
                                        baseHealth = newNdu.toFloat(),
                                        baseSpirit = newMmuo.toFloat(),
                                        baseArmor = newIke.toFloat(),
                                        critRate = (newUche / 100f).coerceIn(0.05f, 0.75f),
                                        primaryElement = DamageType.PHYSICAL,
                                        primarySkillId = "skill_ozo_slash",
                                        secondarySkillId = "skill_amadioha_bolt",
                                        colorHex = "#D4AF37",
                                        isCustomClass = true
                                    )
                                    onCreateClass(forged)
                                    newClassName = ""
                                    newClassLore = ""
                                    isCreatingNew = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_save_custom_class"),
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary)
                        ) {
                            Text("Enshrine Class in Game Engine", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // List of Hero Classes
        items(classes) { heroClass ->
            val isCurrent = (heroClass.id == activeClass.id)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) IgboArtColors.BronzeDark.copy(alpha = 0.5f) else IgboArtColors.NsibidiCard
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(
                        if (isCurrent) listOf(IgboArtColors.AnyanwuGold, IgboArtColors.BronzePrimary)
                        else listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzeDark)
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectClass(heroClass) }
                    .testTag("class_card_${heroClass.id}")
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(IgboArtColors.BronzeDark)
                            .border(2.dp, IgboArtColors.AnyanwuGold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(heroClass.icon, fontSize = 24.sp)
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = heroClass.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isCurrent) IgboArtColors.AnyanwuGold else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (isCurrent) {
                                Spacer(modifier = Modifier.width(8.dp))
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

                        Text(
                            text = heroClass.lore,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand,
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Ike: ${heroClass.baseIke}", fontSize = 11.sp, color = IgboArtColors.BronzeLight)
                            Text("Uche: ${heroClass.baseUche}", fontSize = 11.sp, color = IgboArtColors.AmadiohaCyan)
                            Text("HP: ${heroClass.baseNdu}", fontSize = 11.sp, color = IgboArtColors.LifeGlobeRed)
                            Text("MP: ${heroClass.baseMmuo}", fontSize = 11.sp, color = IgboArtColors.SpiritGlobeBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PowerAttackBuilderTab(
    skills: List<SkillDefinition>,
    onCreateSkill: (SkillDefinition) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var incantation by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedCastType by remember { mutableStateOf(SkillCastType.MELEE_CLEAVE) }
    var selectedDmgType by remember { mutableStateOf(DamageType.THUNDER) }
    var cooldownSec by remember { mutableFloatStateOf(3.0f) }
    var spiritCost by remember { mutableIntStateOf(25) }
    var dmgMultiplier by remember { mutableFloatStateOf(1.8f) }
    var procEffect by remember { mutableStateOf("15% Chance to trigger Lightning Arc") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "⚡ CRAFT CUSTOM POWER ATTACK",
                style = MaterialTheme.typography.titleMedium,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Design melee slashes, incantation spells, projectile bolts, or buffs",
                style = MaterialTheme.typography.bodySmall,
                color = IgboArtColors.SacredSand
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.AmadiohaCyan, IgboArtColors.BronzePrimary)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Power Name (e.g. Celestial Crescent Cleave)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_power_name"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IgboArtColors.AmadiohaCyan,
                            focusedLabelColor = IgboArtColors.AmadiohaCyan
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = incantation,
                        onValueChange = { incantation = it },
                        label = { Text("Igbo Sacred Chant (e.g. 'Amadioha nke Igwe!')") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IgboArtColors.AmadiohaCyan,
                            focusedLabelColor = IgboArtColors.AmadiohaCyan
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Visual FX & Combat Effect Description") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IgboArtColors.AmadiohaCyan,
                            focusedLabelColor = IgboArtColors.AmadiohaCyan
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Cast Geometry:", color = IgboArtColors.SacredIvory, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SkillCastType.values().take(3).forEach { castType ->
                            val isSel = (selectedCastType == castType)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) IgboArtColors.AmadiohaCyan else IgboArtColors.NsibidiNight,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedCastType = castType }
                            ) {
                                Text(
                                    text = castType.name.replace("_", " "),
                                    fontSize = 10.sp,
                                    color = if (isSel) Color.Black else IgboArtColors.SacredSand,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Damage Element:", color = IgboArtColors.SacredIvory, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(DamageType.PHYSICAL, DamageType.THUNDER, DamageType.SOLAR_FIRE, DamageType.DIVINE).forEach { dmg ->
                            val isSel = (selectedDmgType == dmg)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(dmg.colorArgb) else IgboArtColors.NsibidiNight,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedDmgType = dmg }
                            ) {
                                Text(
                                    text = dmg.displayName,
                                    fontSize = 10.sp,
                                    color = if (isSel) Color.Black else IgboArtColors.SacredSand,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Cooldown: ${String.format("%.1f", cooldownSec)}s", color = IgboArtColors.SacredIvory, fontSize = 12.sp)
                    Slider(
                        value = cooldownSec,
                        onValueChange = { cooldownSec = it },
                        valueRange = 0.5f..12.0f,
                        colors = SliderDefaults.colors(thumbColor = IgboArtColors.AnyanwuGold, activeTrackColor = IgboArtColors.AnyanwuGold)
                    )

                    Text("Spirit Cost: $spiritCost Mmụọ", color = IgboArtColors.SpiritGlobeBlue, fontSize = 12.sp)
                    Slider(
                        value = spiritCost.toFloat(),
                        onValueChange = { spiritCost = it.toInt() },
                        valueRange = 0f..80f,
                        colors = SliderDefaults.colors(thumbColor = IgboArtColors.SpiritGlobeBlue, activeTrackColor = IgboArtColors.SpiritGlobeBlue)
                    )

                    Text("Damage Multiplier: ${String.format("%.1f", dmgMultiplier)}x", color = IgboArtColors.AnyanwuGold, fontSize = 12.sp)
                    Slider(
                        value = dmgMultiplier,
                        onValueChange = { dmgMultiplier = it },
                        valueRange = 1.0f..4.5f,
                        colors = SliderDefaults.colors(thumbColor = IgboArtColors.AnyanwuGold, activeTrackColor = IgboArtColors.AnyanwuGold)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                val forged = SkillDefinition(
                                    id = "power_${System.currentTimeMillis()}",
                                    name = name,
                                    incantation = incantation.ifEmpty { "Amadioha Vengeance!" },
                                    description = description.ifEmpty { "Custom crafted ancestral ability." },
                                    damageType = selectedDmgType,
                                    castType = selectedCastType,
                                    baseDamage = dmgMultiplier * 30f,
                                    manaCost = spiritCost.toFloat(),
                                    cooldownSec = cooldownSec,
                                    statusEffect = StatusEffectType.NONE,
                                    isCustomCreated = true,
                                    specialProc = procEffect
                                )
                                onCreateSkill(forged)
                                name = ""
                                incantation = ""
                                description = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_save_custom_power"),
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.AmadiohaCyan)
                    ) {
                        Text("Add Power to Hero Arsenal", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                text = "EXISTING ABILITIES (${skills.size})",
                style = MaterialTheme.typography.labelMedium,
                color = IgboArtColors.SacredSand,
                fontWeight = FontWeight.Bold
            )
        }

        items(skills) { skill ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(skill.primaryDamageType.colorArgb), IgboArtColors.BronzeDark))),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${skill.icon} ${skill.name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(skill.primaryDamageType.colorArgb),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${skill.cooldownMs / 1000f}s CD • ${skill.spiritCost} MP",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.SacredSand
                        )
                    }
                    Text(
                        text = "Chant: \"${skill.igboIncantationChant}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.AnyanwuGold
                    )
                    Text(
                        text = "${skill.castType.name} • ${skill.damageMultiplier}x Multiplier • ${skill.specialProcEffect}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredIvory
                    )
                }
            }
        }
    }
}

@Composable
fun VisualLogicNodeTab(
    script: VisualLogicScript,
    onSaveScript: (VisualLogicScript) -> Unit
) {
    var trigger by remember(script) { mutableStateOf(script.trigger) }
    var condition by remember(script) { mutableStateOf(script.condition) }
    var action by remember(script) { mutableStateOf(script.action) }
    var modifier by remember(script) { mutableStateOf(script.modifier) }
    var simulationResult by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "🧠 VISUAL COMBAT LOGIC SCRIPTING",
                style = MaterialTheme.typography.titleMedium,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Assemble reactive combat node chains: Trigger ➔ Condition ➔ Action ➔ Modifier",
                style = MaterialTheme.typography.bodySmall,
                color = IgboArtColors.SacredSand
            )
        }

        // NODE 1: TRIGGER
        item {
            NodeSelectorCard(
                title = "1. TRIGGER NODE (WHEN THIS HAPPENS)",
                color = IgboArtColors.BronzePrimary,
                currentValue = trigger.displayName,
                options = TriggerLogicNode.values().map { it.displayName },
                onSelect = { name ->
                    trigger = TriggerLogicNode.values().first { it.displayName == name }
                }
            )
        }

        // NODE 2: CONDITION
        item {
            NodeSelectorCard(
                title = "2. CONDITION NODE (ONLY IF)",
                color = IgboArtColors.AnyanwuGold,
                currentValue = condition.displayName,
                options = ConditionLogicNode.values().map { it.displayName },
                onSelect = { name ->
                    condition = ConditionLogicNode.values().first { it.displayName == name }
                }
            )
        }

        // NODE 3: ACTION
        item {
            NodeSelectorCard(
                title = "3. ACTION NODE (THEN EXECUTE)",
                color = IgboArtColors.AmadiohaCyan,
                currentValue = action.displayName,
                options = ActionLogicNode.values().map { it.displayName },
                onSelect = { name ->
                    action = ActionLogicNode.values().first { it.displayName == name }
                }
            )
        }

        // NODE 4: MODIFIER
        item {
            NodeSelectorCard(
                title = "4. MODIFIER NODE (AUGMENTATION)",
                color = IgboArtColors.Terracotta,
                currentValue = modifier.displayName,
                options = ModifierLogicNode.values().map { it.displayName },
                onSelect = { name ->
                    modifier = ModifierLogicNode.values().first { it.displayName == name }
                }
            )
        }

        // Live Visual Chain Diagram Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IgboArtColors.AnyanwuGold, IgboArtColors.AmadiohaCyan)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔗 COMPILED LOGIC CHAIN",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.SacredSand,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "WHEN [${trigger.displayName}] ➜ IF [${condition.displayName}] ➜ UNLEASH [${action.displayName}] ➜ AUGMENT WITH [${modifier.displayName}]",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val newScript = script.copy(
                                    trigger = trigger,
                                    condition = condition,
                                    action = action,
                                    modifier = modifier
                                )
                                onSaveScript(newScript)
                                simulationResult = "✓ Logic script compiled & uploaded to Game Engine runtime!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_save_logic_script")
                        ) {
                            Text("Enact Script", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                simulationResult = "⚡ SIMULATION: Hero attacks with slash ➜ Trigger [${trigger.displayName}] fired ➜ Condition [${condition.displayName}] passed ➜ Discharged [${action.displayName}] with [${modifier.displayName}] proc! (Dealt 240 Crit)"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.AmadiohaCyan),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_simulate_logic_script")
                        ) {
                            Text("Simulate Run", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    simulationResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E281F)
                        ) {
                            Text(
                                text = res,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF81C784),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NodeSelectorCard(
    title: String,
    color: Color,
    currentValue: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(color, color.copy(alpha = 0.3f))))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentValue,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { opt ->
                    val isSel = (opt == currentValue)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSel) color else IgboArtColors.NsibidiNight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(opt) }
                    ) {
                        Text(
                            text = opt,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSel) (if (color == IgboArtColors.AnyanwuGold || color == IgboArtColors.AmadiohaCyan) Color.Black else Color.White) else IgboArtColors.SacredSand,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
