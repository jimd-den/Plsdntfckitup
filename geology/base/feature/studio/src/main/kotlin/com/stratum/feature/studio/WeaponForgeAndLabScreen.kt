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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.CustomGameMechanic
import com.stratum.legacy.domain.MechanicTriggerType
import com.stratum.legacy.domain.WeaponItem
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.legacy.domain.DpsSimulationReport
import com.stratum.feature.studio.event.WeaponForgeUiAction
import com.stratum.feature.studio.state.WeaponForgeUiState

/**
 * CHAPTER 16: PRESENTATION VIEW - WEAPON FORGE & MECHANICS LAB
 *
 * Provides:
 * - ARPG itemization randomizer with affix rolls and Nsibidi runes.
 * - Inventable combat mechanics workshop (configure proc chances, triggers).
 * - Live DPS calculation engine and armor simulation.
 * - Inventory equipment manager.
 */

@Composable
fun WeaponForgeAndLabScreen(
    state: WeaponForgeUiState,
    onAction: (WeaponForgeUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🎲 Randomizer & Forge", "⚙️ Invent Mechanics", "🎒 Inventory & DPS")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("weapon_forge_and_lab_screen")
    ) {
        Text(
            text = "ỌKÀ UZU: WEAPON FORGE & MECHANICS LAB",
            style = MaterialTheme.typography.titleMedium,
            color = IgboArtColors.AnyanwuGold,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Roll ARPG affixes, calculate damage formulas, and invent game loop triggers.",
            style = MaterialTheme.typography.bodySmall,
            color = IgboArtColors.SacredSand
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Sub-tabs
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = IgboArtColors.NsibidiSurface,
            contentColor = IgboArtColors.BronzePrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                    color = IgboArtColors.AnyanwuGold
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedSubTab == index) IgboArtColors.AnyanwuGold else IgboArtColors.SacredSand,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedSubTab) {
            0 -> RandomizerAndForgeTab(
                equippedWeapon = state.worldState.hero.equippedWeapon,
                onRollWeapon = { base, rarity, level ->
                    onAction(WeaponForgeUiAction.RollWeapon(base, rarity, level))
                }
            )
            1 -> InventMechanicsTab(
                activeMechanics = state.worldState.activeMechanics,
                onInventMechanic = { name, igbo, desc, proc, trigger ->
                    onAction(WeaponForgeUiAction.InventMechanic(name, igbo, trigger, proc, desc))
                },
                onToggleMechanic = { id, enabled ->
                    onAction(WeaponForgeUiAction.ToggleMechanic(id, enabled))
                }
            )
            2 -> InventoryAndDpsTab(
                inventory = state.inventory,
                dpsReport = state.dpsReport,
                onEquip = { onAction(WeaponForgeUiAction.EquipWeapon(it)) },
                onDelete = { onAction(WeaponForgeUiAction.DeleteWeapon(it)) }
            )
        }
    }
}

/**
 * Route Adapter Composable: Connects WeaponForgeAndLabScreen to ViewModel.
 */
@Composable
fun WeaponForgeAndLabScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val worldState by viewModel.worldState.collectAsState()
    val inventory by viewModel.inventory.collectAsState()
    val dpsReport by viewModel.dpsReport.collectAsState()

    val state = remember(worldState, inventory, dpsReport) {
        WeaponForgeUiState(
            worldState = worldState,
            inventory = inventory,
            dpsReport = dpsReport
        )
    }

    WeaponForgeAndLabScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is WeaponForgeUiAction.RollWeapon -> viewModel.rollRandomWeapon(action.base, action.rarity, action.level)
                is WeaponForgeUiAction.InventMechanic -> viewModel.inventNewMechanic(
                    action.name,
                    action.igboName,
                    action.desc,
                    action.chance,
                    action.trigger
                )
                is WeaponForgeUiAction.ToggleMechanic -> viewModel.toggleMechanic(action.id, action.enabled)
                is WeaponForgeUiAction.EquipWeapon -> viewModel.equipWeapon(action.id)
                is WeaponForgeUiAction.DeleteWeapon -> viewModel.deleteWeapon(action.id)
            }
        },
        modifier = modifier
    )
}

@Composable
fun RandomizerAndForgeTab(
    equippedWeapon: WeaponItem?,
    onRollWeapon: (BaseWeaponType, WeaponRarity, Int) -> Unit
) {
    var selectedBase by remember { mutableStateOf(BaseWeaponType.MMA_NKWU) }
    var selectedRarity by remember { mutableStateOf(WeaponRarity.RARE) }
    var itemLevel by remember { mutableFloatStateOf(15f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(IgboArtColors.BronzePrimary, IgboArtColors.Terracotta)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. SELECT BASE IGBO WEAPON",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BaseWeaponType.values().take(3).forEach { base ->
                            WeaponPillButton(
                                label = base.igboName,
                                subLabel = base.title,
                                isSelected = selectedBase == base,
                                onClick = { selectedBase = base }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BaseWeaponType.values().drop(3).forEach { base ->
                            WeaponPillButton(
                                label = base.igboName,
                                subLabel = base.title,
                                isSelected = selectedBase == base,
                                onClick = { selectedBase = base }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "2. ITEM RARITY TIER",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WeaponRarity.values().forEach { rarity ->
                            val rColor = Color(android.graphics.Color.parseColor(rarity.colorHex))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedRarity == rarity) rColor.copy(alpha = 0.3f) else Color(0xFF1E2622))
                                    .border(2.dp, if (selectedRarity == rarity) rColor else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { selectedRarity = rarity }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = rarity.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = rColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "3. ITEM LEVEL (ILVL)",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Level ${itemLevel.toInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Slider(
                        value = itemLevel,
                        onValueChange = { itemLevel = it },
                        valueRange = 1f..60f,
                        steps = 59,
                        colors = SliderDefaults.colors(
                            thumbColor = IgboArtColors.AnyanwuGold,
                            activeTrackColor = IgboArtColors.BronzePrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onRollWeapon(selectedBase, selectedRarity, itemLevel.toInt())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("roll_random_weapon_button")
                    ) {
                        Text(
                            text = "🎲 ROLL RANDOM STATS & FORGE",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Currently Equipped Weapon Card
        equippedWeapon?.let { weapon ->
            item {
                Text(
                    text = "ACTIVE EQUIPPED WEAPON",
                    style = MaterialTheme.typography.labelMedium,
                    color = IgboArtColors.BronzeLight,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                WeaponInspectCard(weapon = weapon)
            }
        }
    }
}

@Composable
fun RandomizerAndForgeTab(viewModel: ArpgEngineViewModel) {
    val worldState by viewModel.worldState.collectAsState()
    RandomizerAndForgeTab(
        equippedWeapon = worldState.hero.equippedWeapon,
        onRollWeapon = { base, rarity, level ->
            viewModel.rollRandomWeapon(base, rarity, level)
        }
    )
}

@Composable
fun InventMechanicsTab(
    activeMechanics: List<CustomGameMechanic>,
    onInventMechanic: (String, String, String, Float, MechanicTriggerType) -> Unit,
    onToggleMechanic: (String, Boolean) -> Unit
) {

    var newMechName by remember { mutableStateOf("") }
    var newMechIgbo by remember { mutableStateOf("") }
    var newMechDesc by remember { mutableStateOf("") }
    var newMechProc by remember { mutableFloatStateOf(0.35f) }
    var newMechTrigger by remember { mutableStateOf(MechanicTriggerType.ON_HIT) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(IgboArtColors.BronzePrimary, IgboArtColors.AnyanwuGold)))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CREATE A NEW GAME LOOP MECHANIC",
                        style = MaterialTheme.typography.titleSmall,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Invent procs, triggers, and ancestral effects directly running in the game loop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newMechName,
                        onValueChange = { newMechName = it },
                        label = { Text("Mechanic Name (e.g. Thunder Stun)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mechanic_name_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newMechIgbo,
                        onValueChange = { newMechIgbo = it },
                        label = { Text("Igbo Name (e.g. Egbe Mgbawa)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newMechDesc,
                        onValueChange = { newMechDesc = it },
                        label = { Text("Rule Description (What does it do?)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Trigger Event: ${newMechTrigger.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.SacredIvory
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MechanicTriggerType.values().take(3).forEach { trigger ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (newMechTrigger == trigger) IgboArtColors.BronzePrimary else Color(0xFF1E2622))
                                    .clickable { newMechTrigger = trigger }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = trigger.name.replace("_", " "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Proc Chance: ${(newMechProc * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.SacredIvory
                    )
                    Slider(
                        value = newMechProc,
                        onValueChange = { newMechProc = it },
                        valueRange = 0.05f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = IgboArtColors.AnyanwuGold)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newMechName.isNotEmpty()) {
                                onInventMechanic(
                                    newMechName,
                                    newMechIgbo,
                                    newMechDesc,
                                    newMechProc,
                                    newMechTrigger
                                )
                                newMechName = ""
                                newMechIgbo = ""
                                newMechDesc = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submit_invent_mechanic_button")
                    ) {
                        Text("✨ INJECT MECHANIC INTO ENGINE", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                text = "ACTIVE INVENTED GAME MECHANICS (${activeMechanics.size})",
                style = MaterialTheme.typography.titleSmall,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
        }

        items(activeMechanics) { mech ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = mech.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = IgboArtColors.SacredIvory,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${mech.igboName})",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.AnyanwuGold
                            )
                        }
                        Text(
                            text = "${mech.triggerType.name} • ${(mech.procChance * 100).toInt()}% Chance",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AmadiohaCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mech.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand
                        )
                    }

                    Switch(
                        checked = mech.isEnabled,
                        onCheckedChange = { onToggleMechanic(mech.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = IgboArtColors.AnyanwuGold,
                            checkedTrackColor = IgboArtColors.BronzePrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun InventMechanicsTab(viewModel: ArpgEngineViewModel) {
    val worldState by viewModel.worldState.collectAsState()
    InventMechanicsTab(
        activeMechanics = worldState.activeMechanics,
        onInventMechanic = { name, igbo, desc, proc, trigger ->
            viewModel.inventNewMechanic(name, igbo, desc, proc, trigger)
        },
        onToggleMechanic = { id, enabled ->
            viewModel.toggleMechanic(id, enabled)
        }
    )
}

@Composable
fun InventoryAndDpsTab(
    inventory: List<WeaponItem>,
    dpsReport: DpsSimulationReport?,
    onEquip: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        dpsReport?.let { report ->
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(IgboArtColors.AmadiohaCyan, IgboArtColors.BronzePrimary)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ARPG COMBAT DPS SIMULATOR",
                            style = MaterialTheme.typography.titleSmall,
                            color = IgboArtColors.AmadiohaCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = report.formulaExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredSand
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF37474F))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DpsMetricBox(title = "RAW DPS", value = "${report.rawDps.toInt()}", color = IgboArtColors.AnyanwuGold)
                            DpsMetricBox(title = "VS MED ARMOR", value = "${report.dpsAgainstMediumArmor.toInt()}", color = IgboArtColors.Terracotta)
                            DpsMetricBox(title = "VS HEAVY", value = "${report.dpsAgainstHeavyArmor.toInt()}", color = IgboArtColors.LifeGlobeRed)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Attacks/Sec: ${String.format("%.2f", report.attacksPerSecond)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredIvory
                            )
                            Text(
                                text = "Crit: ${report.critChancePercent.toInt()}% (x${String.format("%.1f", report.critDamageMultiplier)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = IgboArtColors.SacredIvory
                            )
                            Text(
                                text = "Life Leech: ${report.lifeLeechPerSecond.toInt()}/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF81C784)
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "INVENTORY ARSENAL (${inventory.size} ITEMS)",
                style = MaterialTheme.typography.titleSmall,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.Bold
            )
        }

        items(inventory) { weapon ->
            WeaponInspectCard(
                weapon = weapon,
                showEquipButton = !weapon.isEquipped,
                onEquip = { onEquip(weapon.id) },
                onDelete = { onDelete(weapon.id) }
            )
        }
    }
}

@Composable
fun InventoryAndDpsTab(viewModel: ArpgEngineViewModel) {
    val inventory by viewModel.inventory.collectAsState()
    val dpsReport by viewModel.dpsReport.collectAsState()
    InventoryAndDpsTab(
        inventory = inventory,
        dpsReport = dpsReport,
        onEquip = { viewModel.equipWeapon(it) },
        onDelete = { viewModel.deleteWeapon(it) }
    )
}

@Composable
fun DpsMetricBox(title: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun WeaponPillButton(
    label: String,
    subLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) IgboArtColors.BronzePrimary else Color(0xFF1E2622))
            .border(2.dp, if (isSelected) IgboArtColors.AnyanwuGold else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Text(text = subLabel, style = MaterialTheme.typography.bodySmall, color = IgboArtColors.SacredSand, fontSize = 8.sp)
        }
    }
}

@Composable
fun WeaponInspectCard(
    weapon: WeaponItem,
    showEquipButton: Boolean = false,
    onEquip: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val rColor = Color(android.graphics.Color.parseColor(weapon.rarity.colorHex))

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(rColor, IgboArtColors.BronzeDark)))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = weapon.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = rColor
                    )
                    Text(
                        text = "${weapon.rarity.displayName} ${weapon.baseType.title} • Item Lv.${weapon.itemLevel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand
                    )
                }

                if (weapon.isEquipped) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(IgboArtColors.BronzePrimary)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("EQUIPPED", color = Color.White, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Damage: ${weapon.minDamage} - ${weapon.maxDamage}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = IgboArtColors.SacredIvory
                )
                Text(
                    text = "Speed: ${String.format("%.2f", weapon.attackSpeed)}/s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IgboArtColors.SacredIvory
                )
                Text(
                    text = "DPS: ${weapon.theoreticalDps.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = IgboArtColors.AnyanwuGold
                )
            }

            weapon.nsibidiRune?.let { rune ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rune.symbolCharacter,
                        style = MaterialTheme.typography.titleMedium,
                        color = IgboArtColors.AmadiohaCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Nsibidi: ${rune.igboName} (${rune.meaning}) • ${rune.elementalBonus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.AmadiohaCyan
                    )
                }
            }

            if (weapon.specialMechanic.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚡ ${weapon.specialMechanic}",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.AnyanwuGold
                )
            }

            if (weapon.loreNotes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = weapon.loreNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            if (showEquipButton && onEquip != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        Text(
                            text = "Dismantle",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.LifeGlobeRed,
                            modifier = Modifier
                                .clickable { onDelete() }
                                .padding(end = 16.dp)
                        )
                    }
                    Button(
                        onClick = onEquip,
                        colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("EQUIP WEAPON", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
