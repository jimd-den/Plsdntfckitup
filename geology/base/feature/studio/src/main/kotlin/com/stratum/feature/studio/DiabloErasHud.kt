package com.stratum.feature.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.legacy.domain.CustomStyleOptions
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.GameThemeProfile
import com.stratum.legacy.domain.GlobeRenderStyle
import com.stratum.legacy.domain.NamingSystem

/**
 * CHAPTER 22: PRESENTATION - DIABLO ERAS & MIYAMOTO NINTENDO HUD CHAMELEON
 *
 * Implements intentional, authentic HUD rendering for:
 * 1. Diablo I (1996): Gothic cathedral archways, heavy iron bezel, gargoyle brackets, retro pixel text.
 * 2. Diablo II (2000): Hammered brass filigree, 4-potion utility belt (1-4), stamina gauge.
 * 3. Diablo III (2012): High-fantasy curved obsidian wings, fluid arc meters, arcade kill-streak tracker.
 * 4. Diablo IV (2023): Brutalist slate dock, visceral fluid blood physics, gothic runic badges.
 * 5. Custom Style: Full player personalization of layout, orb shaders, and colors.
 *
 * Designed with Nintendo/Miyamoto touch standards: minimum 48dp tactile touch targets,
 * visual feedback on every tap, zero clutter.
 */
@Composable
fun DiabloEraHudOverlay(
    era: DiabloEra,
    customStyle: CustomStyleOptions,
    activeTheme: GameThemeProfile,
    namingSystem: NamingSystem,
    stats: CombatHudStats,
    potionBeltCounts: List<Int>,
    onDrinkPotion: (Int) -> Unit,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifeName = when (namingSystem) {
        NamingSystem.GENERIC_RPG -> "HEALTH"
        NamingSystem.MYTHIC_ANCIENT -> "NDỤ"
        NamingSystem.AI_THEMED -> activeTheme.lifeResourceName.uppercase()
    }
    val manaName = when (namingSystem) {
        NamingSystem.GENERIC_RPG -> "MANA"
        NamingSystem.MYTHIC_ANCIENT -> "MMỤỌ"
        NamingSystem.AI_THEMED -> activeTheme.spiritResourceName.uppercase()
    }

    val skill1Name = when (namingSystem) {
        NamingSystem.GENERIC_RPG -> "Lightning"
        NamingSystem.MYTHIC_ANCIENT -> "Amadioha"
        NamingSystem.AI_THEMED -> activeTheme.heroSkill1Name.take(9)
    }

    val skill2Name = when (namingSystem) {
        NamingSystem.GENERIC_RPG -> "Nova"
        NamingSystem.MYTHIC_ANCIENT -> "Anyanwu"
        NamingSystem.AI_THEMED -> activeTheme.heroSkill2Name.take(9)
    }

    val dashName = when (namingSystem) {
        NamingSystem.GENERIC_RPG -> "Dash"
        NamingSystem.MYTHIC_ANCIENT -> "Agụ"
        NamingSystem.AI_THEMED -> activeTheme.heroDashName.take(9)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("diablo_era_hud_${era.id}")
    ) {
        when (era) {
            DiabloEra.DIABLO_1 -> Diablo1GothicHud(
                stats = stats,
                lifeName = lifeName,
                manaName = manaName,
                skill1Name = skill1Name,
                skill2Name = skill2Name,
                dashName = dashName,
                onJoystickMove = onJoystickMove,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
            DiabloEra.DIABLO_2 -> Diablo2LoDHud(
                stats = stats,
                lifeName = lifeName,
                manaName = manaName,
                skill1Name = skill1Name,
                skill2Name = skill2Name,
                dashName = dashName,
                potionBeltCounts = potionBeltCounts,
                onDrinkPotion = onDrinkPotion,
                onJoystickMove = onJoystickMove,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
            DiabloEra.DIABLO_3 -> Diablo3ArcadeHud(
                stats = stats,
                lifeName = lifeName,
                manaName = manaName,
                skill1Name = skill1Name,
                skill2Name = skill2Name,
                dashName = dashName,
                onJoystickMove = onJoystickMove,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
            DiabloEra.DIABLO_4 -> Diablo4GrimdarkHud(
                stats = stats,
                lifeName = lifeName,
                manaName = manaName,
                skill1Name = skill1Name,
                skill2Name = skill2Name,
                dashName = dashName,
                onJoystickMove = onJoystickMove,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
            DiabloEra.CUSTOM_STYLE -> CustomMiyamotoHud(
                customStyle = customStyle,
                activeTheme = activeTheme,
                stats = stats,
                lifeName = lifeName,
                manaName = manaName,
                skill1Name = skill1Name,
                skill2Name = skill2Name,
                dashName = dashName,
                potionBeltCounts = potionBeltCounts,
                onDrinkPotion = onDrinkPotion,
                onJoystickMove = onJoystickMove,
                onAttack = onAttack,
                onThunder = onThunder,
                onSunNova = onSunNova,
                onDash = onDash
            )
        }
    }
}

// ==================== 1. DIABLO 1 (1996 GOTHIC CATHEDRAL) ====================
@Composable
private fun Diablo1GothicHud(
    stats: CombatHudStats,
    lifeName: String,
    manaName: String,
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xDD121010), Color(0xFF1E1A18))
                )
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Era Emblem Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "❖ SANCTUARY 1996 GOTHIC CATHEDRAL ❖",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD4AF37),
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: Heavy Iron & Stone Gothic Health Globe + Retro Pad
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GothicCathedralGlobe(
                    current = stats.currentHp,
                    max = stats.maxHp,
                    label = lifeName,
                    liquidColor = Color(0xFFB71C1C),
                    borderColor = Color(0xFF757575)
                )
                Spacer(modifier = Modifier.height(6.dp))
                TactileD1Thumbstick(onMove = onJoystickMove)
            }

            // Center: Classic Belt Plaque
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(CutCornerShape(4.dp))
                    .background(Color(0xFF2B2622))
                    .border(2.dp, Color(0xFF8D6E63), CutCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "DPS: ${stats.dps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "WAVE ${stats.wave}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB0BEC5),
                    fontSize = 9.sp
                )
            }

            // Right: Mana Globe + Gothic Stone Cast Cluster
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GothicCathedralGlobe(
                    current = stats.currentSpirit,
                    max = stats.maxSpirit,
                    label = manaName,
                    liquidColor = Color(0xFF0D47A1),
                    borderColor = Color(0xFF757575)
                )
                Spacer(modifier = Modifier.height(6.dp))
                D1ActionButtons(
                    skill1Name = skill1Name,
                    skill2Name = skill2Name,
                    dashName = dashName,
                    stats = stats,
                    onAttack = onAttack,
                    onThunder = onThunder,
                    onSunNova = onSunNova,
                    onDash = onDash
                )
            }
        }
    }
}

@Composable
private fun GothicCathedralGlobe(
    current: Int,
    max: Int,
    label: String,
    liquidColor: Color,
    borderColor: Color
) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(72.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF151515))
            .border(3.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillH = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(listOf(liquidColor.copy(alpha = 0.9f), liquidColor)),
                topLeft = Offset(0f, size.height - fillH),
                size = Size(size.width, fillH)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                fontFamily = FontFamily.Serif
            )
            Text(
                text = "$current",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun TactileD1Thumbstick(onMove: (Float, Float) -> Unit) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxR = 40f
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(Color(0xFF212121))
            .border(2.dp, Color(0xFF616161), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val drag = offset - center
                        val dist = drag.getDistance()
                        val clamped = if (dist > maxR) drag * (maxR / dist) else drag
                        knobOffset = clamped
                        onMove(clamped.x / maxR, clamped.y / maxR)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newO = knobOffset + dragAmount
                        val dist = newO.getDistance()
                        val clamped = if (dist > maxR) newO * (maxR / dist) else newO
                        knobOffset = clamped
                        onMove(clamped.x / maxR, clamped.y / maxR)
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.toInt(), knobOffset.y.toInt()) }
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF424242))
                .border(2.dp, Color(0xFFBDBDBD), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("✛", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun D1ActionButtons(
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    stats: CombatHudStats,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NintendoTactileKey(label = skill1Name, keyTag = "I", color = Color(0xFF00B0FF), cd = stats.thunderCd, onClick = onThunder)
            NintendoTactileKey(label = skill2Name, keyTag = "II", color = Color(0xFFFFB300), cd = stats.sunNovaCd, onClick = onSunNova)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NintendoTactileKey(label = dashName, keyTag = "III", color = Color(0xFFFF5722), cd = stats.dashCd, onClick = onDash)
            // Primary Attack
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFC6772E), Color(0xFF4E342E))))
                    .border(2.dp, Color(0xFFFFD54F), CircleShape)
                    .clickable { onAttack() }
                    .testTag("primary_attack_button"),
                contentAlignment = Alignment.Center
            ) {
                Text("⚔️", fontSize = 20.sp)
            }
        }
    }
}

// ==================== 2. DIABLO 2 (LORD OF DESTRUCTION GOLDEN AGE) ====================
@Composable
private fun Diablo2LoDHud(
    stats: CombatHudStats,
    lifeName: String,
    manaName: String,
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    potionBeltCounts: List<Int>,
    onDrinkPotion: (Int) -> Unit,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xF0181512), Color(0xFF262018))
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xFFB8860B), Color.Transparent)
                    )
                )
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Diablo 2 Quick-Sip Utility Belt (1-4)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BELT:",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD4AF37),
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            potionBeltCounts.forEachIndexed { idx, count ->
                val (label, icon, color) = when (idx) {
                    0 -> Triple("1", "🧪", Color(0xFFE53935)) // Life
                    1 -> Triple("2", "💧", Color(0xFF1E88E5)) // Mana
                    2 -> Triple("3", "✨", Color(0xFFAB47BC)) // Rejuv
                    else -> Triple("4", "⚡", Color(0xFFFFB300)) // Elixir
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1F1A15))
                        .border(1.5.dp, if (count > 0) color else Color.Gray, RoundedCornerShape(6.dp))
                        .clickable { onDrinkPotion(idx) }
                        .testTag("potion_slot_$idx"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(icon, fontSize = 12.sp)
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (count > 0) Color.White else Color.DarkGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: D2 Brass Health Globe + Stamina Bar + Virtual Thumbstick
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo2FiligreeGlobe(
                    current = stats.currentHp,
                    max = stats.maxHp,
                    label = lifeName,
                    liquidColors = listOf(Color(0xFFFF3D00), Color(0xFFB71C1C), Color(0xFF5D0000))
                )
                // Stamina Gauge
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .width(68.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1B1B1B))
                        .border(1.dp, Color(0xFF795548), RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF64DD17))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TactileD1Thumbstick(onMove = onJoystickMove)
            }

            // Right: D2 Brass Mana Globe + Tactile Action Cluster
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo2FiligreeGlobe(
                    current = stats.currentSpirit,
                    max = stats.maxSpirit,
                    label = manaName,
                    liquidColors = listOf(Color(0xFF00E5FF), Color(0xFF0D47A1), Color(0xFF001970))
                )
                Spacer(modifier = Modifier.height(8.dp))
                TactileActionCluster(
                    weapon = stats.weaponName,
                    attackCd = stats.attackCd,
                    thunderCd = stats.thunderCd,
                    sunCd = stats.sunNovaCd,
                    dashCd = stats.dashCd,
                    onAttack = onAttack,
                    onThunder = onThunder,
                    onSunNova = onSunNova,
                    onDash = onDash
                )
            }
        }
    }
}

@Composable
private fun Diablo2FiligreeGlobe(
    current: Int,
    max: Int,
    label: String,
    liquidColors: List<Color>
) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(72.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF120E0A))
            .border(3.dp, Color(0xFFD4AF37), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillHeight = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(liquidColors),
                topLeft = Offset(0f, size.height - fillHeight),
                size = Size(size.width, fillHeight)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                fontFamily = FontFamily.Serif
            )
            Text(
                text = "$current",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ==================== 3. DIABLO 3 (HIGH FANTASY ARCADE) ====================
@Composable
private fun Diablo3ArcadeHud(
    stats: CombatHudStats,
    lifeName: String,
    manaName: String,
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xEE0D1117), Color(0xFF161B22))
                )
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Dynamic Arcade Massacre Kill-Streak Ribbon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFBF360C).copy(alpha = 0.35f))
                    .border(1.dp, Color(0xFFFF5722), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "⚔️ MASSACRE! ${stats.kills} KILLS — DPS: ${stats.dps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFCC80),
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: Glowing Arc Curved Health Wing
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo3CurvedArcMeter(current = stats.currentHp, max = stats.maxHp, label = lifeName, isLife = true)
                Spacer(modifier = Modifier.height(6.dp))
                VirtualJoystick(onMove = onJoystickMove)
            }

            // Right: Glowing Arc Mana Wing + Neon Action Cluster
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo3CurvedArcMeter(current = stats.currentSpirit, max = stats.maxSpirit, label = manaName, isLife = false)
                Spacer(modifier = Modifier.height(6.dp))
                TactileActionCluster(
                    weapon = stats.weaponName,
                    attackCd = stats.attackCd,
                    thunderCd = stats.thunderCd,
                    sunCd = stats.sunNovaCd,
                    dashCd = stats.dashCd,
                    onAttack = onAttack,
                    onThunder = onThunder,
                    onSunNova = onSunNova,
                    onDash = onDash
                )
            }
        }
    }
}

@Composable
private fun Diablo3CurvedArcMeter(current: Int, max: Int, label: String, isLife: Boolean) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val colorPrimary = if (isLife) Color(0xFFFF1744) else Color(0xFF00E5FF)
    val colorDark = if (isLife) Color(0xFF880E4F) else Color(0xFF01579B)

    Box(
        modifier = Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(Color(0xFF0B0E14))
            .border(2.5.dp, colorPrimary.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillHeight = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(listOf(colorPrimary, colorDark)),
                topLeft = Offset(0f, size.height - fillHeight),
                size = Size(size.width, fillHeight)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 8.sp)
            Text("$current", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
    }
}

// ==================== 4. DIABLO 4 (SANCTUARY GRIMDARK BRUTALIST) ====================
@Composable
private fun Diablo4GrimdarkHud(
    stats: CombatHudStats,
    lifeName: String,
    manaName: String,
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xF50A0A0C), Color(0xFF111114))
                )
            )
            .border(1.dp, Color(0xFF2A2A30))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: Visceral Blood Fluid Globe + Slate Bezel
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo4VisceralGlobe(current = stats.currentHp, max = stats.maxHp, label = lifeName, isBlood = true)
                Spacer(modifier = Modifier.height(6.dp))
                TactileD1Thumbstick(onMove = onJoystickMove)
            }

            // Center: Brutalist Runic Combat Stat Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF18181D))
                    .border(1.dp, Color(0xFF42424F), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "DPS ${stats.dps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE0E0E0),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "TIER ${stats.wave}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8E24AA),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp
                )
            }

            // Right: Visceral Shadow Spirit Globe + Action Buttons
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Diablo4VisceralGlobe(current = stats.currentSpirit, max = stats.maxSpirit, label = manaName, isBlood = false)
                Spacer(modifier = Modifier.height(6.dp))
                TactileActionCluster(
                    weapon = stats.weaponName,
                    attackCd = stats.attackCd,
                    thunderCd = stats.thunderCd,
                    sunCd = stats.sunNovaCd,
                    dashCd = stats.dashCd,
                    onAttack = onAttack,
                    onThunder = onThunder,
                    onSunNova = onSunNova,
                    onDash = onDash
                )
            }
        }
    }
}

@Composable
private fun Diablo4VisceralGlobe(current: Int, max: Int, label: String, isBlood: Boolean) {
    val ratio = (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val color = if (isBlood) Color(0xFFD32F2F) else Color(0xFF7E57C2)

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color(0xFF0F0F12))
            .border(2.dp, Color(0xFF373740), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fillH = size.height * ratio
            drawRect(
                brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.6f), Color.Black)),
                topLeft = Offset(0f, size.height - fillH),
                size = Size(size.width, fillH)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color(0xFFB0BEC5), fontWeight = FontWeight.Bold, fontSize = 8.sp)
            Text("$current", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
    }
}

// ==================== 5. CUSTOM MIYAMOTO NINTENDO SANDBOX HUD ====================
@Composable
private fun CustomMiyamotoHud(
    customStyle: CustomStyleOptions,
    activeTheme: GameThemeProfile,
    stats: CombatHudStats,
    lifeName: String,
    manaName: String,
    skill1Name: String,
    skill2Name: String,
    dashName: String,
    potionBeltCounts: List<Int>,
    onDrinkPotion: (Int) -> Unit,
    onJoystickMove: (Float, Float) -> Unit,
    onAttack: () -> Unit,
    onThunder: () -> Unit,
    onSunNova: () -> Unit,
    onDash: () -> Unit
) {
    val primaryColor = try {
        Color(android.graphics.Color.parseColor(activeTheme.primaryHex))
    } catch (e: Exception) {
        IgboArtColors.BronzePrimary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xEE121417), Color(0xFF1E2228))
                )
            )
            .border(1.dp, primaryColor.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Custom Style Title Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🎨 ${activeTheme.themeName.uppercase()} (CUSTOM HUD)",
                style = MaterialTheme.typography.labelSmall,
                color = primaryColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
        }

        // Optional Potion Belt
        if (customStyle.showPotionBelt) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                potionBeltCounts.forEachIndexed { idx, count ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF262B33))
                            .border(1.dp, primaryColor, RoundedCornerShape(8.dp))
                            .clickable { onDrinkPotion(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (idx == 0) "❤️$count" else if (idx == 1) "💧$count" else "⚡$count",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArpgHealthGlobe(current = stats.currentHp, max = stats.maxHp)
                Spacer(modifier = Modifier.height(6.dp))
                VirtualJoystick(onMove = onJoystickMove)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArpgSpiritGlobe(current = stats.currentSpirit, max = stats.maxSpirit)
                Spacer(modifier = Modifier.height(6.dp))
                TactileActionCluster(
                    weapon = stats.weaponName,
                    attackCd = stats.attackCd,
                    thunderCd = stats.thunderCd,
                    sunCd = stats.sunNovaCd,
                    dashCd = stats.dashCd,
                    onAttack = onAttack,
                    onThunder = onThunder,
                    onSunNova = onSunNova,
                    onDash = onDash
                )
            }
        }
    }
}

@Composable
private fun NintendoTactileKey(
    label: String,
    keyTag: String,
    color: Color,
    cd: Long,
    onClick: () -> Unit
) {
    val isOnCd = cd > 0
    Box(
        modifier = Modifier
            .size(50.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (isOnCd) Color(0xFF37474F) else Color(0xFF262320))
            .border(2.dp, if (isOnCd) Color.Gray else color, RoundedCornerShape(12.dp))
            .clickable(enabled = !isOnCd) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isOnCd) {
                Text(
                    text = String.format("%.1f", cd / 1000f),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = keyTag,
                    color = color,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp
                )
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}
