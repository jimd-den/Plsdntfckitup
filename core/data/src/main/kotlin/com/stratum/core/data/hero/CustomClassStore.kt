package com.stratum.core.data.hero

import android.content.Context
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.HeroClassDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stores the classes the player has built.
 *
 * One file per class rather than one list: a class that fails to parse costs
 * the player that class, not every class they have ever made.
 *
 * The stored shape is this module's own [ClassDto] rather than the domain type.
 * A saved class has to survive the domain type gaining a field, and it cannot do
 * that if the two are the same declaration.
 */
class CustomClassStore(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Classes on disk, newest first. */
    fun all(): List<HeroClassDefinition> =
        root.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching { json.decodeFromString<ClassDto>(file.readText()).toDomain() }.getOrNull()
            }

    fun save(hero: HeroClassDefinition) {
        File(root, "${slugFor(hero.id)}$SUFFIX").writeText(json.encodeToString(hero.toDto()))
    }

    fun delete(heroId: String) {
        File(root, "${slugFor(heroId)}$SUFFIX").delete()
    }

    /** Ids are namespaced, and a colon is not a filename everywhere. */
    private fun slugFor(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private companion object {
        const val DIRECTORY = "classes"
        const val SUFFIX = ".class.json"
    }
}

@Serializable
internal data class ClassDto(
    val id: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val baseHealth: Int = 100,
    val baseResource: Int = 50,
    val resourceName: String = "Focus",
    val strength: Int = 10,
    val agility: Int = 10,
    val insight: Int = 10,
    val startingBlockIds: List<String> = emptyList(),
    val abilityIds: List<String> = emptyList(),
    val spriteSetId: String? = null,
    val startingWeaponId: String? = null,
    val attackPower: Int = 0,
    val armour: Int = 0,
    val critChance: Float = 0f,
    val critMultiplier: Float = 1.5f,
    val attackSpeed: Float = 0f,
    val attackRange: Int = 1,
) {
    fun toDomain() = HeroClassDefinition(
        id = id,
        name = name,
        title = title,
        description = description,
        baseHealth = baseHealth,
        baseResource = baseResource,
        resourceName = resourceName,
        strength = strength,
        agility = agility,
        insight = insight,
        startingBlockIds = startingBlockIds,
        abilityIds = abilityIds,
        spriteSetId = spriteSetId,
        startingWeaponId = startingWeaponId,
        baseStats = CombatStats(
            maxHealth = baseHealth,
            attackPower = attackPower,
            armour = armour,
            critChance = critChance,
            critMultiplier = critMultiplier,
            attackSpeed = attackSpeed,
            attackRange = attackRange,
        ),
    )
}

internal fun HeroClassDefinition.toDto() = ClassDto(
    id = id,
    name = name,
    title = title,
    description = description,
    baseHealth = resolvedStats.maxHealth,
    baseResource = baseResource,
    resourceName = resourceName,
    strength = strength,
    agility = agility,
    insight = insight,
    startingBlockIds = startingBlockIds,
    abilityIds = abilityIds,
    spriteSetId = spriteSetId,
    startingWeaponId = startingWeaponId,
    attackPower = resolvedStats.attackPower,
    armour = resolvedStats.armour,
    critChance = resolvedStats.critChance,
    critMultiplier = resolvedStats.critMultiplier,
    attackSpeed = resolvedStats.attackSpeed,
    attackRange = resolvedStats.attackRange,
)
