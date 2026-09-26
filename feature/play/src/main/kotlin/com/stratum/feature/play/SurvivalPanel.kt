package com.stratum.feature.play

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumProgressSliver
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.survival.ConsumableDefinition
import com.stratum.core.domain.survival.NeedDefinition
import com.stratum.engine.world.Held
import com.stratum.engine.world.RecipeOption

/** What the camp and the survival HUD can ask for. */
data class SurvivalActions(
    val onToggleCamp: () -> Unit = {},
    val onEat: (String) -> Unit = {},
    val onDrink: () -> Unit = {},
    val onMake: (String) -> Unit = {},
)

/** One need as the HUD shows it. */
data class NeedView(val need: NeedDefinition, val value: Float) {
    val fraction: Float get() = (value / com.stratum.core.domain.survival.Survival.MAX).coerceIn(0f, 1f)
    val low: Boolean get() = value < need.lowBelow
}

/** Everything the HUD and camp panel show about the body and its surroundings. */
data class SurvivalPanel(
    val active: Boolean = false,
    val needs: List<NeedView> = emptyList(),
    val night: Boolean = false,
    val day: Int = 1,
    val sheltered: Boolean = false,
    val nearFire: Boolean = false,
    val canDrink: Boolean = false,
    val food: List<Held<ConsumableDefinition>> = emptyList(),
    val recipes: List<RecipeOption> = emptyList(),
) {
    val anyLow: Boolean get() = needs.any { it.low }

    /** The surroundings in one short line: "🌙 Night 2 · ⌂ roof · 🔥 fire". */
    val surroundings: String
        get() = listOfNotNull(
            if (night) "🌙 Night $day" else "☀ Day $day",
            "⌂ roof".takeIf { sheltered },
            "🔥 fire".takeIf { nearFire },
        ).joinToString(" · ")
}

/** The needs as small meters under the vitals: glyph, bar, and red when low. */
@Composable
internal fun NeedMeters(panel: SurvivalPanel, modifier: Modifier = Modifier) {
    if (!panel.active) return
    val colors = StratumTheme.colors
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight), verticalAlignment = Alignment.CenterVertically) {
            panel.needs.forEach { need ->
                Column(Modifier.width(NEED_WIDTH)) {
                    Text(need.need.glyph, style = MaterialTheme.typography.labelSmall)
                    StratumProgressSliver(fraction = need.fraction, tint = if (need.low) colors.danger else Color(need.need.color))
                }
            }
        }
        Text(panel.surroundings, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, maxLines = 1)
    }
}

/**
 * The camp: what the body needs, what the player carries to eat, and what
 * can be cooked or made here. Every button says why it cannot be pressed,
 * so a player learns "needs a fire" from the panel, not from a wiki.
 */
@Composable
fun CampOverlay(
    panel: SurvivalPanel,
    onEat: (String) -> Unit,
    onDrink: () -> Unit,
    onMake: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    Box(modifier.fillMaxSize().background(colors.surface.copy(alpha = 0.9f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        StratumPanel(
            modifier = Modifier.safeContent().fillMaxWidth(0.94f).widthIn(max = PANEL_MAX_WIDTH).verticalScroll(rememberScrollState()).clickable(enabled = false) {},
            shape = Cut.large,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Camp")
                Spacer(Modifier.weight(1f))
                StratumAction(label = "Close", onClick = onClose, emphasis = ActionEmphasis.QUIET)
            }
            Text(panel.surroundings, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            Spacer(Modifier.height(Space.small))
            panel.needs.forEach { need ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.hair)) {
                    Text("${need.need.glyph} ${need.need.name}", style = MaterialTheme.typography.labelMedium, color = colors.ink, modifier = Modifier.width(110.dp))
                    StratumProgressSliver(fraction = need.fraction, tint = if (need.low) colors.danger else Color(need.need.color), modifier = Modifier.weight(1f))
                    Text(" ${need.value.toInt()}", style = MaterialTheme.typography.labelSmall, color = if (need.low) colors.danger else colors.inkMuted)
                }
            }
            if (panel.canDrink) {
                Spacer(Modifier.height(Space.small))
                StratumAction(label = "💧 Drink from the water", onClick = onDrink, emphasis = ActionEmphasis.PRIMARY)
            }

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Food")
            if (panel.food.isEmpty()) Hint("Forage plants, hunt, and cook what you kill. Food goes straight into your bag.")
            panel.food.forEach { held ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Space.hair)) {
                    Column(Modifier.weight(1f)) {
                        Text("${held.definition.glyph} ${held.definition.name} ×${held.count}", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Text(effectLine(held.definition), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                    StratumAction(label = "Eat", onClick = { onEat(held.definition.id) }, emphasis = ActionEmphasis.SECONDARY)
                }
            }

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Make")
            panel.recipes.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Space.hair)) {
                    Column(Modifier.weight(1f)) {
                        Text(option.recipe.name, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Text(
                            option.recipe.inputs.entries.joinToString { (id, n) -> "$n ${id.substringAfter(':').replace('_', ' ')}" } +
                                when {
                                    !option.atStation -> " · needs " + if (option.recipe.station == com.stratum.core.domain.survival.Recipes.FIRE) "a fire" else option.recipe.station!!.substringAfter(':').replace('_', ' ')
                                    !option.haveIngredients -> " · missing ingredients"
                                    else -> ""
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (option.craftable) colors.inkMuted else colors.danger,
                        )
                    }
                    StratumAction(label = "Make", onClick = { onMake(option.recipe.id) }, enabled = option.craftable, emphasis = ActionEmphasis.SECONDARY)
                }
            }
        }
    }
}

private fun effectLine(food: ConsumableDefinition): String =
    (food.restores.entries.map { (id, n) -> "+${n.toInt()} ${id.substringAfter(':')}" } +
        listOfNotNull(food.heals.takeIf { it > 0 }?.let { "+$it health" }) +
        food.modifiers.map { it.describe() }).joinToString(" · ")

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
}

private val NEED_WIDTH = 52.dp
