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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.stratum.legacy.domain.CodexCategory
import com.stratum.legacy.domain.IgboCodexEntry
import com.stratum.legacy.domain.IgboHistoryCodexArchive

/**
 * CHAPTER 20: PRESENTATION VIEW - ANCIENT IGBO HISTORY & ART CODEX
 *
 * Implements a cultural encyclopedia exploring:
 * - 9th-century Igbo-Ukwu lost-wax bronze casting
 * - Nsibidi indigenous writing system and ideograms
 * - Uli geometric body and shrine painting tradition
 * - Kingdom of Nri divine spiritual commonwealth
 * - Igbo cosmology (Amadioha, Anyanwu, Ala, Ikenga)
 * - Integration of ancient history directly into Action RPG game mechanics.
 */

@Composable
fun IgboHistoryCodexScreen(
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf<CodexCategory?>(null) }
    val allEntries = IgboHistoryCodexArchive.entries

    val filteredEntries = if (selectedCategory == null) {
        allEntries
    } else {
        allEntries.filter { it.category == selectedCategory }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(IgboArtColors.NsibidiNight)
            .padding(16.dp)
            .testTag("igbo_history_codex_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "📜 ANCIENT IGBO ART & HISTORY CODEX",
                style = MaterialTheme.typography.titleMedium,
                color = IgboArtColors.AnyanwuGold,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Archaeological, metallurgical, philosophical and artistic foundations behind the ARPG engine.",
                style = MaterialTheme.typography.bodySmall,
                color = IgboArtColors.SacredSand
            )
        }

        // Category Filter Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    CategoryChip(
                        label = "All Artifacts",
                        isSelected = selectedCategory == null,
                        onClick = { selectedCategory = null }
                    )
                }
                items(CodexCategory.values()) { cat ->
                    CategoryChip(
                        label = cat.label,
                        isSelected = selectedCategory == cat,
                        onClick = { selectedCategory = cat }
                    )
                }
            }
        }

        items(filteredEntries) { entry ->
            CodexEntryCard(entry = entry)
        }
    }
}

@Composable
fun CategoryChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) IgboArtColors.BronzePrimary else IgboArtColors.NsibidiCard)
            .border(
                1.5.dp,
                if (isSelected) IgboArtColors.AnyanwuGold else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) Color.White else IgboArtColors.SacredSand,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CodexEntryCard(entry: IgboCodexEntry) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IgboArtColors.NsibidiCard),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(IgboArtColors.BronzeDark, IgboArtColors.BronzePrimary)
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.visualSymbol,
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = IgboArtColors.SacredIvory,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${entry.igboTitle} • ${entry.era}",
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.AnyanwuGold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E2622)
                ) {
                    Text(
                        text = entry.category.label.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = IgboArtColors.BronzeLight,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = IgboArtColors.SacredSand
            )

            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF37474F))
                Text(
                    text = "HISTORICAL CONTEXT",
                    style = MaterialTheme.typography.labelSmall,
                    color = IgboArtColors.BronzeLight,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.detailedHistory,
                    style = MaterialTheme.typography.bodySmall,
                    color = IgboArtColors.SacredIvory,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF15201A))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "⚔️ ARPG COMBAT ENGINE INTEGRATION",
                            style = MaterialTheme.typography.labelSmall,
                            color = IgboArtColors.AnyanwuGold,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.gameLoopMechanicTieIn,
                            style = MaterialTheme.typography.bodySmall,
                            color = IgboArtColors.AmadiohaCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isExpanded) "▲ Tap to collapse" else "▼ Tap to read full history & ARPG mechanics",
                style = MaterialTheme.typography.labelSmall,
                color = IgboArtColors.BronzePrimary,
                fontSize = 10.sp
            )
        }
    }
}
