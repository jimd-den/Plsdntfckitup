package com.stratum.plugins.schema

import com.stratum.core.domain.survival.ConsumableDefinition
import com.stratum.core.domain.survival.ForageRule
import com.stratum.core.domain.survival.NeedDefinition
import com.stratum.core.domain.survival.NeedKind
import com.stratum.core.domain.survival.RecipeDefinition
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldRules
import kotlinx.serialization.Serializable

private val NEED = NeedDefinition(id = "", name = "")
private val FOOD = ConsumableDefinition(id = "", name = "")
private val RULES = WorldRules()

@Serializable
internal data class NeedSchema(
    val id: String,
    val name: String,
    val glyph: String = NEED.glyph,
    val color: String = SchemaValues.color(NEED.color),
    val kind: String = SchemaValues.name(NEED.kind),
    val drainPerMinute: Float = NEED.drainPerMinute,
    val lowBelow: Float = NEED.lowBelow,
    val lowModifiers: List<ModifierSchema> = emptyList(),
    val emptyHealthLossPerMinute: Float = NEED.emptyHealthLossPerMinute,
) {
    fun toDomain() = NeedDefinition(
        id, name, glyph, SchemaValues.color(color, "need '$id' color"), SchemaValues.enum<NeedKind>(kind, "need '$id' kind"),
        drainPerMinute, lowBelow, lowModifiers.map { it.toDomain("need '$id'") }, emptyHealthLossPerMinute,
    )

    companion object {
        fun of(n: NeedDefinition) = NeedSchema(
            n.id, n.name, n.glyph, SchemaValues.color(n.color), SchemaValues.name(n.kind), n.drainPerMinute, n.lowBelow,
            n.lowModifiers.map(ModifierSchema::of), n.emptyHealthLossPerMinute,
        )
    }
}

@Serializable
internal data class ConsumableSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val glyph: String = FOOD.glyph,
    val color: String = SchemaValues.color(FOOD.color),
    val restores: Map<String, Float> = emptyMap(),
    val heals: Int = 0,
    val modifiers: List<ModifierSchema> = emptyList(),
    val durationSeconds: Float = 0f,
) {
    fun toDomain() = ConsumableDefinition(
        id, name, description, glyph, SchemaValues.color(color, "food '$id' color"), restores, heals, modifiers.map { it.toDomain("food '$id'") }, durationSeconds,
    )

    companion object {
        fun of(c: ConsumableDefinition) = ConsumableSchema(
            c.id, c.name, c.description, c.glyph, SchemaValues.color(c.color), c.restores, c.heals, c.modifiers.map(ModifierSchema::of), c.durationSeconds,
        )
    }
}

/** A harvest yield: from one block by id, or from every block of a material. */
@Serializable
internal data class ForageSchema(val item: String, val chance: Float, val block: String? = null, val material: String? = null) {
    fun toDomain() = ForageRule(item, chance, block, material?.let { SchemaValues.enum<BlockMaterial>(it, "forage '$item' material") })

    companion object {
        fun of(f: ForageRule) = ForageSchema(f.itemId, f.chance, f.blockId, f.material?.let(SchemaValues::name))
    }
}

/** `station` is a block id, `stratum:fire` for any fire, or absent for anywhere. */
@Serializable
internal data class RecipeSchema(
    val id: String,
    val name: String,
    val inputs: Map<String, Int>,
    val output: String,
    val count: Int = 1,
    val station: String? = null,
) {
    fun toDomain() = RecipeDefinition(id, name, inputs, output, count, station)

    companion object {
        fun of(r: RecipeDefinition) = RecipeSchema(r.id, r.name, r.inputs, r.outputId, r.outputCount, r.station)
    }
}

/** How a pack suggests its worlds be played. */
@Serializable
internal data class RulesSchema(
    val survival: String = SchemaValues.name(RULES.survival),
    val townDensity: Float = RULES.townDensity,
    val startInTown: Boolean = RULES.startInTown,
    val monsterDensity: Float = RULES.monsterDensity,
    val raids: Boolean = RULES.raids,
    val dayLengthMinutes: Float = RULES.dayLengthMinutes,
    val lootMultiplier: Float = RULES.lootMultiplier,
    val experienceMultiplier: Float = RULES.experienceMultiplier,
    val deathPenalty: Float = RULES.deathPenalty,
) {
    fun toDomain() = WorldRules(
        SchemaValues.enum<SurvivalMode>(survival, "rules survival"), townDensity, startInTown, monsterDensity, raids, dayLengthMinutes,
        lootMultiplier, experienceMultiplier, deathPenalty,
    )

    companion object {
        fun of(r: WorldRules) = RulesSchema(
            SchemaValues.name(r.survival), r.townDensity, r.startInTown, r.monsterDensity, r.raids, r.dayLengthMinutes,
            r.lootMultiplier, r.experienceMultiplier, r.deathPenalty,
        )
    }
}
