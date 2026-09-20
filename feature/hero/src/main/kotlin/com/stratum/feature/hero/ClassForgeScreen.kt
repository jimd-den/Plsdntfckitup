package com.stratum.feature.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.content.ClassDraft
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.item.WeaponBase
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.world.BlockType
import kotlin.math.roundToInt

@Composable
fun ClassForgeScreen(
    viewModel: ClassForgeViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ClassForgeScreenContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onName = viewModel::setName,
        onTitle = viewModel::setTitle,
        onDescription = viewModel::setDescription,
        onResourceName = viewModel::setResourceName,
        onAdjust = viewModel::adjust,
        onToggleSkill = viewModel::toggleSkill,
        onSelectSprite = viewModel::selectSprite,
        onToggleBlock = viewModel::toggleBlock,
        onSelectWeapon = viewModel::selectWeapon,
        onSave = viewModel::save,
        onReset = viewModel::reset,
        onEditExisting = viewModel::editExisting,
        onDelete = viewModel::delete,
    )
}

/**
 * The class forge.
 *
 * Built around one idea: every choice on this screen costs something, and the
 * cost is visible while you make it. The stat readout sits directly under the
 * attribute rows rather than on a summary page, because a build you cannot see
 * the consequences of is a build made by guessing.
 */
@Composable
fun ClassForgeScreenContent(
    state: ClassForgeUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onName: (String) -> Unit = {},
    onTitle: (String) -> Unit = {},
    onDescription: (String) -> Unit = {},
    onResourceName: (String) -> Unit = {},
    onAdjust: (ClassDraft.Attribute, Int) -> Unit = { _, _ -> },
    onToggleSkill: (String) -> Unit = {},
    onSelectSprite: (String) -> Unit = {},
    onToggleBlock: (String) -> Unit = {},
    onSelectWeapon: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onReset: () -> Unit = {},
    onEditExisting: (HeroClassDefinition) -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    val colors = StratumTheme.colors
    val draft = state.draft

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Class forge")
            StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                placeholder = { Text("Nsibidi Scribe") },
                singleLine = true,
            )
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(
                value = draft.title,
                onValueChange = onTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                placeholder = { Text("Keeper of the Marks") },
                singleLine = true,
            )
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(
                value = draft.resourceName,
                onValueChange = onResourceName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What skills spend") },
                placeholder = { Text("Focus") },
                singleLine = true,
            )
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(
                value = draft.description,
                onValueChange = onDescription,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Who they are") },
                minLines = 2,
            )
        }

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Attributes")
                Text(
                    text = "${draft.remaining} points left",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draft.remaining > 0) colors.accent else colors.inkMuted,
                )
            }

            Spacer(Modifier.height(Space.medium))
            AttributeRow("Strength", "Health, damage and armour", draft.strength, onAdjust, ClassDraft.Attribute.STRENGTH)
            Spacer(Modifier.height(Space.small))
            AttributeRow("Agility", "Attack speed and crits", draft.agility, onAdjust, ClassDraft.Attribute.AGILITY)
            Spacer(Modifier.height(Space.small))
            AttributeRow("Insight", "Resource, crit damage and a third skill", draft.insight, onAdjust, ClassDraft.Attribute.INSIGHT)

            Spacer(Modifier.height(Space.medium))
            StratumDivider()
            Spacer(Modifier.height(Space.medium))

            // The consequences, next to the choice that caused them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                Figure("HP", draft.stats.maxHealth.toString(), Modifier.weight(1f))
                Figure("ATK", draft.stats.attackPower.toString(), Modifier.weight(1f))
                Figure("ARM", draft.stats.armour.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                Figure("CRIT", "${(draft.stats.critChance * 100).roundToInt()}%", Modifier.weight(1f))
                Figure("SPD", String.format("%.2f", draft.stats.attackSpeed), Modifier.weight(1f))
                Figure(
                    draft.resourceName.take(4).uppercase().ifBlank { "RES" },
                    draft.resourcePool.toString(),
                    Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Skills")
                Text(
                    text = "${draft.skillIds.size} of ${draft.skillCapacity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
            Spacer(Modifier.height(Space.small))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                items(state.skills, key = SkillDefinition::id) { skill ->
                    StratumChip(
                        label = skill.name,
                        selected = skill.id in draft.skillIds,
                        onClick = { onToggleSkill(skill.id) },
                        swatch = Color(skill.color),
                    )
                }
            }

            // Art the sprite forge has drawn. Hidden when there is none rather
            // than showing an empty shelf the player cannot fill from here.
            if (state.sheets.isNotEmpty()) {
                Spacer(Modifier.height(Space.medium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Appearance")
                    Text(
                        text = if (draft.spriteSetId == null) "Shapes" else "Drawn",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
                Spacer(Modifier.height(Space.small))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                ) {
                    items(state.sheets, key = SpriteSheet::id) { sheet ->
                        StratumChip(
                            label = sheet.name,
                            selected = sheet.id == draft.spriteSetId,
                            onClick = { onSelectSprite(sheet.id) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.tight))
                Text(
                    text = "Tap the chosen one again to go back to shapes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Starting weapon")
            Spacer(Modifier.height(Space.small))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                items(state.weapons, key = WeaponBase::id) { weapon ->
                    StratumChip(
                        label = "${weapon.name} ${weapon.minDamage}–${weapon.maxDamage}",
                        selected = weapon.id == draft.startingWeaponId,
                        onClick = { onSelectWeapon(weapon.id) },
                    )
                }
            }

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Starting blocks")
            Spacer(Modifier.height(Space.small))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                items(state.blocks, key = BlockType::id) { block ->
                    StratumChip(
                        label = block.displayName,
                        selected = block.id in draft.startingBlockIds,
                        onClick = { onToggleBlock(block.id) },
                        swatch = Color(block.topColor),
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.large))

        // What is stopping the save, said out loud. A greyed-out button that
        // will not explain itself is the most annoying thing a form can do.
        state.problems.firstOrNull()?.let { problem ->
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
            Spacer(Modifier.height(Space.small))
        }
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
            )
            Spacer(Modifier.height(Space.small))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            StratumAction(
                label = "Save class",
                onClick = onSave,
                emphasis = ActionEmphasis.PRIMARY,
                enabled = state.canSave,
                modifier = Modifier.weight(1f),
            )
            StratumAction(label = "Clear", onClick = onReset, emphasis = ActionEmphasis.QUIET)
        }

        if (state.saved.isNotEmpty()) {
            Spacer(Modifier.height(Space.large))
            SectionLabel("Your classes")
            Spacer(Modifier.height(Space.small))
            state.saved.forEach { hero ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.tight),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hero.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.ink,
                        )
                        Text(
                            text = "${hero.resolvedStats.maxHealth} hp · " +
                                "${hero.resolvedStats.attackPower} atk · " +
                                "${hero.abilityIds.size} skills",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                        )
                    }
                    StratumAction(
                        label = "Edit",
                        onClick = { onEditExisting(hero) },
                        emphasis = ActionEmphasis.SECONDARY,
                    )
                    StratumAction(
                        label = "Delete",
                        onClick = { onDelete(hero.id) },
                        emphasis = ActionEmphasis.QUIET,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.huge))
    }
}

@Composable
private fun AttributeRow(
    label: String,
    explains: String,
    value: Int,
    onAdjust: (ClassDraft.Attribute, Int) -> Unit,
    attribute: ClassDraft.Attribute,
) {
    val colors = StratumTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.ink)
            Text(explains, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        StratumAction(label = "−", onClick = { onAdjust(attribute, -1) }, emphasis = ActionEmphasis.QUIET)
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = colors.accent,
        )
        StratumAction(label = "+", onClick = { onAdjust(attribute, 1) }, emphasis = ActionEmphasis.SECONDARY)
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    StratumWell(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = StratumTheme.colors.accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
        )
    }
}
