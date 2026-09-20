package com.stratum.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.feature.studio.event.PnpUiAction
import com.stratum.feature.studio.state.PenAndPaperUiState
import com.stratum.legacy.domain.DiceRollResult
import com.stratum.legacy.domain.DiceType
import com.stratum.legacy.domain.PnpCharacterClass
import com.stratum.legacy.domain.PnpRulebook
import com.stratum.legacy.domain.PnpSkillCheck

/**
 * CHAPTER 18: PRESENTATION VIEW - PEN & PAPER RPG STUDIO
 *
 * Implements:
 * - Interactive 3D tactile dice roller (D4, D6, D8, D10, D12, D20, D100)
 * - Igbo tabletop campaign rules viewer ("Ọfọ & Bronze")
 * - Live skill checks that grant real-time blessings in the Action RPG combat loop
 * - Tabletop campaign module importer
 */

@Composable
fun PenAndPaperRpgStudioScreen(
    viewModel: ArpgEngineViewModel,
    modifier: Modifier = Modifier
) {
    val activeRulebook by viewModel.activeRulebook.collectAsState()
    val lastRoll by viewModel.lastDiceRoll.collectAsState()

    val state = remember(activeRulebook, lastRoll) {
        PenAndPaperUiState(
            activeRulebook = activeRulebook,
            lastRoll = lastRoll
        )
    }

    PenAndPaperRpgStudioScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is PnpUiAction.RollDice -> viewModel.rollPnpDice(action.diceType, action.modifier)
                is PnpUiAction.ExecuteSkillCheck -> viewModel.executeSkillCheck(action.check, action.modifier)
            }
        },
        modifier = modifier
    )
}

@Composable
fun PenAndPaperRpgStudioScreen(
    state: PenAndPaperUiState,
    onAction: (PnpUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeRulebook = state.activeRulebook
    val lastRoll = state.lastRoll
    var selectedModifier by remember { mutableIntStateOf(3) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(16.dp)
            .testTag("pen_and_paper_studio_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "🎲 PEN & PAPER RPG STUDIO: ỌFỌ & BRONZE",
                style = MaterialTheme.typography.titleMedium,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Roll tabletop polyhedral dice and resolve skill checks that bless your Action RPG ARPG character.",
                style = MaterialTheme.typography.bodySmall,
                color = IgboArtColors.SacredSand
            )
        }

        // Tactile Polyhedral Dice Roller Card
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
                    Text(
                        text = "TACTILE POLYHEDRAL DICE ROLLER",
                        style = MaterialTheme.typography.labelMedium,
                        color = IgboArtColors.AnyanwuGold,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Dice Result Display Box
                    DiceRollDisplayBox(lastRoll = lastRoll)

                    Spacer(modifier = Modifier.height(14.dp))

                    // Polyhedral Dice Selection Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DiceType.values().forEach { dType ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .shadow(4.dp, RoundedCornerShape(8.dp))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(IgboArtColors.BronzePrimary)
                                    .clickable { onAction(PnpUiAction.RollDice(dType, selectedModifier)) }
                                    .padding(vertical = 10.dp)
                                    .testTag("roll_${dType.name.lowercase()}_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dType.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Attribute Modifier: +$selectedModifier",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredIvory
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 2, 3, 5).forEach { mod ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selectedModifier == mod) IgboArtColors.AnyanwuGold else Color(0xFF1E2622))
                                        .clickable { selectedModifier = mod }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "+$mod",
                                        color = if (selectedModifier == mod) IgboArtColors.NsibidiNight else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Tabletop Skill Checks Card
        activeRulebook?.let { book ->
            item {
                Text(
                    text = "ACTIVE CAMPAIGN: ${book.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = IgboArtColors.BronzeLight,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${book.settingName} • ${book.authorNotes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredSand
                )
            }

            item {
                Text(
                    text = "PERFORM TABLETOP SKILL CHECKS (BLESS ARPG ENGINE)",
                    style = MaterialTheme.typography.labelMedium,
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.Bold
                )
            }

            items(book.skillChecks) { check ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(
                            listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzePrimary)
                        )
                    ),
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
                                    text = check.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = IgboArtColors.SacredIvory,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${check.igboTerm})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IgboArtColors.AnyanwuGold
                                )
                            }
                            Text(
                                text = "${check.governingAttribute} Check • Difficulty Class (DC) ${check.difficultyClass}",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.AmadiohaCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Blessing: ${check.successBuffEffect}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF81C784)
                            )
                        }

                        Button(
                            onClick = { onAction(PnpUiAction.ExecuteSkillCheck(check, selectedModifier)) },
                            colors = ButtonDefaults.buttonColors(containerColor = IgboArtColors.BronzePrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("roll_check_${check.id}")
                        ) {
                            Text("ROLL D20", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Character Classes in Rulebook
            item {
                Text(
                    text = "P&P CHARACTER CLASSES",
                    style = MaterialTheme.typography.titleSmall,
                    color = IgboArtColors.AnyanwuGold,
                    fontWeight = FontWeight.Bold
                )
            }

            items(book.classes) { pClass ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = pClass.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = IgboArtColors.AnyanwuGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = pClass.igboTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = IgboArtColors.SacredSand
                            )
                        }
                        Text(
                            text = pClass.loreRole,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.BronzeLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pClass.startingBonusDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.SacredIvory
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Primary Skill: ${pClass.primarySkill}",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AmadiohaCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiceRollDisplayBox(lastRoll: DiceRollResult?) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF0F1512))
            .border(3.dp, if (lastRoll?.isCriticalSuccess == true) IgboArtColors.AnyanwuGold else IgboArtColors.BronzePrimary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = lastRoll,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "dice_roll"
        ) { roll ->
            if (roll != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${roll.total}",
                        style = MaterialTheme.typography.displayLarge,
                        color = when {
                            roll.isCriticalSuccess -> IgboArtColors.AnyanwuGold
                            roll.isCriticalFailure -> IgboArtColors.LifeGlobeRed
                            else -> Color.White
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 34.sp
                    )
                    Text(
                        text = if (roll.modifier != 0) "(${roll.rawValue} + ${roll.modifier})" else "Natural ${roll.rawValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IgboArtColors.SacredSand,
                        fontSize = 9.sp
                    )
                }
            } else {
                Text(
                    text = "D20",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
