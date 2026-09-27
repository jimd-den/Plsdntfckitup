package com.stratum.plugins.schema

import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.content.PackPalette
import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteFacing
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.tabletop.Attribute
import com.stratum.core.domain.tabletop.Boon
import com.stratum.core.domain.tabletop.SkillCheck
import kotlinx.serialization.Serializable

// Who plays, what they read, what they look like, and the table's rules.

private val HERO = HeroClassDefinition(id = "", name = "")

@Serializable
internal data class HeroClassSchema(
    val id: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val baseHealth: Int = HERO.baseHealth,
    val baseResource: Int = HERO.baseResource,
    val resourceName: String = HERO.resourceName,
    val strength: Int = HERO.strength,
    val agility: Int = HERO.agility,
    val insight: Int = HERO.insight,
    val startingBlocks: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    val spriteSet: String? = null,
    val stats: StatsSchema = StatsSchema.of(HERO.baseStats),
    val startingWeapon: String? = null,
) {
    fun toDomain() = HeroClassDefinition(
        id, name, title, description, baseHealth, baseResource, resourceName, strength, agility, insight,
        startingBlocks, abilities, spriteSet, stats.toDomain(), startingWeapon,
    )

    companion object {
        fun of(h: HeroClassDefinition) = HeroClassSchema(
            h.id, h.name, h.title, h.description, h.baseHealth, h.baseResource, h.resourceName, h.strength, h.agility, h.insight,
            h.startingBlockIds, h.abilityIds, h.spriteSetId, StatsSchema.of(h.baseStats), h.startingWeaponId,
        )
    }
}

@Serializable
internal data class LoreSchema(val id: String, val title: String, val body: String, val category: String = "history", val subject: String? = null) {
    fun toDomain() = LoreEntry(id, title, body, SchemaValues.enum<LoreCategory>(category, "lore '$id' category"), subject)

    companion object {
        fun of(l: LoreEntry) = LoreSchema(l.id, l.title, l.body, SchemaValues.name(l.category), l.subjectId)
    }
}

private val PALETTE = PackPalette()

@Serializable
internal data class PaletteSchema(
    val surface: String = SchemaValues.color(PALETTE.surface),
    val surfaceRaised: String = SchemaValues.color(PALETTE.surfaceRaised),
    val ink: String = SchemaValues.color(PALETTE.ink),
    val inkMuted: String = SchemaValues.color(PALETTE.inkMuted),
    val accent: String = SchemaValues.color(PALETTE.accent),
    val accentAlt: String = SchemaValues.color(PALETTE.accentAlt),
    val danger: String = SchemaValues.color(PALETTE.danger),
) {
    fun toDomain() = PackPalette(
        SchemaValues.color(surface, "palette surface"), SchemaValues.color(surfaceRaised, "palette surfaceRaised"),
        SchemaValues.color(ink, "palette ink"), SchemaValues.color(inkMuted, "palette inkMuted"), SchemaValues.color(accent, "palette accent"),
        SchemaValues.color(accentAlt, "palette accentAlt"), SchemaValues.color(danger, "palette danger"),
    )

    companion object {
        fun of(p: PackPalette) = PaletteSchema(
            SchemaValues.color(p.surface), SchemaValues.color(p.surfaceRaised), SchemaValues.color(p.ink), SchemaValues.color(p.inkMuted),
            SchemaValues.color(p.accent), SchemaValues.color(p.accentAlt), SchemaValues.color(p.danger),
        )
    }
}

@Serializable
internal data class ClipSchema(
    val state: String,
    val first: Int,
    val count: Int,
    val frameMs: Int = 120,
    val loops: Boolean = true,
    val standsInFor: String? = null,
) {
    fun toDomain(sheet: String) = AnimationClip(
        SchemaValues.enum<AnimationState>(state, "sheet '$sheet' clip state"), first, count, frameMs, loops,
        standsInFor?.let { SchemaValues.enum<AnimationState>(it, "sheet '$sheet' clip standsInFor") },
    )

    companion object {
        fun of(c: AnimationClip) = ClipSchema(SchemaValues.name(c.state), c.firstFrame, c.frameCount, c.frameDurationMs, c.loops, c.standsInFor?.let(SchemaValues::name))
    }
}

/** A sprite sheet's layout. Its image travels beside it in the plugin's art folder. */
@Serializable
internal data class SheetSchema(
    val id: String,
    val name: String,
    val columns: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val clips: List<ClipSchema> = emptyList(),
    val facingRows: Map<String, Int> = emptyMap(),
    val mirrorsFacings: Boolean = true,
) {
    fun toDomain() = SpriteSheet(
        id, name, columns, rows, frameWidth, frameHeight, clips.map { it.toDomain(id) },
        facingRows.mapKeys { (facing, _) -> SchemaValues.enum<SpriteFacing>(facing, "sheet '$id' facing") }, mirrorsFacings, SpriteOrigin.IMPORTED,
    )

    companion object {
        fun of(s: SpriteSheet) = SheetSchema(
            s.id, s.name, s.columns, s.rows, s.frameWidth, s.frameHeight, s.clips.map(ClipSchema::of),
            s.facingRows.mapKeys { (facing, _) -> SchemaValues.name(facing) }, s.mirrorsFacings,
        )
    }
}

private val BOON = Boon(name = "")
private val CHECK = SkillCheck(id = "", name = "")

@Serializable
internal data class BoonSchema(
    val name: String,
    val durationSeconds: Float = BOON.durationSeconds,
    val attackPower: Float = BOON.attackPowerFraction,
    val attackSpeed: Float = BOON.attackSpeedFraction,
    val armour: Int = BOON.armour,
    val maxHealth: Int = BOON.maxHealth,
    val critChance: Float = BOON.critChance,
    val lifeSteal: Float = BOON.lifeSteal,
) {
    fun toDomain() = Boon(name, durationSeconds, attackPower, attackSpeed, armour, maxHealth, critChance, lifeSteal)

    companion object {
        fun of(b: Boon) = BoonSchema(b.name, b.durationSeconds, b.attackPowerFraction, b.attackSpeedFraction, b.armour, b.maxHealth, b.critChance, b.lifeSteal)
    }
}

@Serializable
internal data class CheckSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val dice: String = CHECK.dice,
    val attribute: String = SchemaValues.name(CHECK.attribute),
    val difficulty: Int = CHECK.difficulty,
    val boon: BoonSchema? = null,
    val bane: BoonSchema? = null,
    val cooldownSeconds: Float = CHECK.cooldownSeconds,
) {
    fun toDomain() = SkillCheck(
        id, name, description, dice, SchemaValues.enum<Attribute>(attribute, "check '$id' attribute"), difficulty,
        boon?.toDomain(), bane?.toDomain(), cooldownSeconds,
    )

    companion object {
        fun of(c: SkillCheck) = CheckSchema(
            c.id, c.name, c.description, c.dice, SchemaValues.name(c.attribute), c.difficulty, c.boon?.let(BoonSchema::of), c.bane?.let(BoonSchema::of), c.cooldownSeconds,
        )
    }
}
