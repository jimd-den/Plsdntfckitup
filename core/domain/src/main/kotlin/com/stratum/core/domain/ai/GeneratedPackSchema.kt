package com.stratum.core.domain.ai

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.content.PackPalette
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape a model is asked to produce.
 *
 * Kept separate from the domain types on purpose. A generated pack is untrusted
 * input: every field is optional with a sane default here, and [toDomain]
 * decides what a valid pack looks like. Deserializing straight into the domain
 * model would let one missing field from a model fail the whole run.
 */
@Serializable
data class GeneratedPackDto(
    val id: String = "",
    val name: String = "Generated Pack",
    val description: String = "",
    val palette: GeneratedPaletteDto = GeneratedPaletteDto(),
    val blocks: List<GeneratedBlockDto> = emptyList(),
    val biomes: List<GeneratedBiomeDto> = emptyList(),
    @SerialName("classes") val heroClasses: List<GeneratedClassDto> = emptyList(),
    val lore: List<GeneratedLoreDto> = emptyList(),
)

@Serializable
data class GeneratedPaletteDto(
    val surface: String = "#12131A",
    val surfaceRaised: String = "#1C1E28",
    val ink: String = "#F2EFE6",
    val inkMuted: String = "#9A96A8",
    val accent: String = "#D9A441",
    val accentAlt: String = "#5AC8B0",
    val danger: String = "#D2544B",
)

@Serializable
data class GeneratedBlockDto(
    val id: String = "",
    val name: String = "",
    val material: String = "STONE",
    val hardness: Float = 1f,
    val requiredTier: Int = 0,
    val solid: Boolean = true,
    val opaque: Boolean = true,
    val gravity: Boolean = false,
    val needsSupport: Boolean = false,
    val light: Int = 0,
    val topColor: String = "#9E9E9E",
    val sideColor: String = "#6E6E6E",
    val accentColor: String = "#000000",
    val drop: String? = null,
)

@Serializable
data class GeneratedBiomeDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val surfaceBlock: String = "",
    val subsurfaceBlock: String = "",
    val fillerBlock: String = "",
    val heightBias: Int = 0,
    val roughness: Float = 1f,
    val scatter: List<GeneratedScatterDto> = emptyList(),
    val deposits: List<GeneratedDepositDto> = emptyList(),
)

@Serializable
data class GeneratedScatterDto(
    val block: String = "",
    val chance: Float = 0f,
    val height: Int = 1,
)

@Serializable
data class GeneratedDepositDto(
    val block: String = "",
    val minZ: Int = 1,
    val maxZ: Int = 10,
    val chance: Float = 0.1f,
    val clusterSize: Int = 3,
)

@Serializable
data class GeneratedClassDto(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val health: Int = 200,
    val resource: Int = 100,
    val resourceName: String = "Focus",
    val strength: Int = 10,
    val agility: Int = 10,
    val insight: Int = 10,
    val startingBlocks: List<String> = emptyList(),
)

@Serializable
data class GeneratedLoreDto(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val category: String = "HISTORY",
    val subject: String? = null,
)

/**
 * Converts generated data into a pack, repairing what can be repaired and
 * dropping what cannot.
 *
 * Models are inconsistent: they forget namespaces, invent colours in three
 * different notations, and reference blocks they never defined. Everything that
 * can be corrected is corrected here, so a usable pack survives a sloppy
 * response rather than being rejected wholesale.
 */
fun GeneratedPackDto.toDomain(fallbackId: String): ContentPack {
    val namespace = id.ifBlank { fallbackId }.namespaceSafe()

    val blocks = blocks
        .filter { it.id.isNotBlank() }
        .map { it.toDomain(namespace) }
        .distinctBy { it.id }

    val knownBlockIds = blocks.map { it.id }.toSet()

    // A biome pointing at a block nobody defined would fail assembly, so those
    // references are repaired against the first block of a plausible material
    // before the pack is ever handed to the assembler.
    val fallbackBlock = blocks.firstOrNull { it.material != BlockMaterial.AIR }?.id

    val biomes = biomes
        .filter { it.id.isNotBlank() }
        .mapNotNull { it.toDomain(namespace, knownBlockIds, fallbackBlock) }
        .distinctBy { it.id }

    return ContentPack(
        id = namespace,
        name = name.ifBlank { "Generated Pack" },
        author = "AI",
        description = description,
        origin = PackOrigin.AI_GENERATED,
        palette = palette.toDomain(),
        blocks = blocks,
        biomes = biomes,
        heroClasses = heroClasses
            .filter { it.id.isNotBlank() }
            .map { it.toDomain(namespace, knownBlockIds) }
            .distinctBy { it.id },
        loreEntries = lore
            .filter { it.title.isNotBlank() }
            .mapIndexed { index, entry -> entry.toDomain(namespace, index) },
    )
}

private fun GeneratedBlockDto.toDomain(namespace: String) = BlockType(
    id = id.qualify(namespace),
    displayName = name.ifBlank { id.substringAfter(':').replace('_', ' ') },
    material = material.toMaterial(),
    hardness = hardness.coerceIn(0f, MAX_HARDNESS),
    requiredTier = requiredTier.coerceIn(0, MAX_TIER),
    isSolid = solid,
    isOpaque = opaque,
    hasGravity = gravity,
    needsSupport = needsSupport,
    lightEmission = light.coerceIn(0, Chunk.MAX_LIGHT),
    dropId = drop?.takeIf { it.isNotBlank() }?.qualify(namespace),
    topColor = topColor.toArgb(0xFF9E9E9E),
    sideColor = sideColor.toArgb(0xFF6E6E6E),
    accentColor = accentColor.toArgb(0xFF000000),
)

private fun GeneratedBiomeDto.toDomain(
    namespace: String,
    knownBlockIds: Set<String>,
    fallbackBlock: String?,
): BiomeDefinition? {
    fun resolve(candidate: String): String? {
        val qualified = candidate.qualify(namespace)
        return if (qualified in knownBlockIds) qualified else fallbackBlock
    }

    val surface = resolve(surfaceBlock) ?: return null
    val subsurface = resolve(subsurfaceBlock) ?: surface
    val filler = resolve(fillerBlock) ?: subsurface

    return BiomeDefinition(
        id = id.qualify(namespace),
        name = name.ifBlank { id },
        description = description,
        surfaceBlockId = surface,
        subsurfaceBlockId = subsurface,
        bedrockFillerBlockId = filler,
        heightBias = heightBias.coerceIn(-MAX_BIAS, MAX_BIAS),
        roughness = roughness.coerceIn(0f, MAX_ROUGHNESS),
        // Rules naming a block that does not exist are dropped rather than
        // repaired: a tree made of the wrong block is worse than no tree.
        scatter = scatter.mapNotNull { rule ->
            val block = rule.block.qualify(namespace).takeIf { it in knownBlockIds } ?: return@mapNotNull null
            ScatterRule(block, rule.chance.coerceIn(0f, 1f), rule.height.coerceIn(1, MAX_SCATTER_HEIGHT))
        },
        deposits = deposits.mapNotNull { rule ->
            val block = rule.block.qualify(namespace).takeIf { it in knownBlockIds } ?: return@mapNotNull null
            val minZ = rule.minZ.coerceIn(1, Chunk.HEIGHT - 2)
            DepositRule(
                blockId = block,
                minZ = minZ,
                maxZ = rule.maxZ.coerceIn(minZ + 1, Chunk.HEIGHT - 1),
                chance = rule.chance.coerceIn(0f, 1f),
                clusterSize = rule.clusterSize.coerceIn(1, MAX_CLUSTER),
            )
        },
    )
}

private fun GeneratedClassDto.toDomain(namespace: String, knownBlockIds: Set<String>) =
    HeroClassDefinition(
        id = id.qualify(namespace),
        name = name.ifBlank { id },
        title = title,
        description = description,
        baseHealth = health.coerceIn(MIN_HEALTH, MAX_HEALTH),
        baseResource = resource.coerceIn(0, MAX_RESOURCE),
        resourceName = resourceName.ifBlank { "Focus" },
        strength = strength.coerceIn(1, MAX_STAT),
        agility = agility.coerceIn(1, MAX_STAT),
        insight = insight.coerceIn(1, MAX_STAT),
        startingBlockIds = startingBlocks
            .map { it.qualify(namespace) }
            .filter { it in knownBlockIds },
    )

private fun GeneratedLoreDto.toDomain(namespace: String, index: Int) = LoreEntry(
    id = id.ifBlank { "$namespace:lore_$index" }.qualify(namespace),
    title = title,
    body = body,
    category = runCatching { LoreCategory.valueOf(category.uppercase()) }
        .getOrDefault(LoreCategory.HISTORY),
    subject?.takeIf { it.isNotBlank() }?.qualify(namespace),
)

private fun GeneratedPaletteDto.toDomain() = PackPalette(
    surface = surface.toArgb(0xFF12131A),
    surfaceRaised = surfaceRaised.toArgb(0xFF1C1E28),
    ink = ink.toArgb(0xFFF2EFE6),
    inkMuted = inkMuted.toArgb(0xFF9A96A8),
    accent = accent.toArgb(0xFFD9A441),
    accentAlt = accentAlt.toArgb(0xFF5AC8B0),
    danger = danger.toArgb(0xFFD2544B),
)

/** Models routinely omit the namespace, so add it rather than rejecting the id. */
private fun String.qualify(namespace: String): String =
    if (contains(':')) this else "$namespace:${trim().lowercase().replace(WHITESPACE, "_")}"

private fun String.namespaceSafe(): String =
    trim().lowercase().replace(NON_ID, "").ifBlank { "generated" }

private fun String.toMaterial(): BlockMaterial =
    runCatching { BlockMaterial.valueOf(trim().uppercase()) }.getOrDefault(BlockMaterial.STONE)

/**
 * Accepts `#RRGGBB`, `#AARRGGBB`, bare hex and `0x` forms, because models use
 * all of them interchangeably even when the prompt asks for one.
 */
internal fun String.toArgb(fallback: Long): Long {
    val cleaned = trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return fallback
    return when (cleaned.length) {
        6 -> 0xFF000000L or cleaned.toLong(16)
        8 -> cleaned.toLong(16)
        3 -> {
            // #abc is shorthand for #aabbcc.
            val expanded = cleaned.map { "$it$it" }.joinToString("")
            0xFF000000L or expanded.toLong(16)
        }
        else -> fallback
    }
}

private val WHITESPACE = Regex("\\s+")
private val NON_ID = Regex("[^a-z0-9_]")

private const val MAX_HARDNESS = 20f
private const val MAX_TIER = 5
private const val MAX_BIAS = 20
private const val MAX_ROUGHNESS = 3f
private const val MAX_SCATTER_HEIGHT = 8
private const val MAX_CLUSTER = 8
private const val MIN_HEALTH = 1
private const val MAX_HEALTH = 2000
private const val MAX_RESOURCE = 2000
private const val MAX_STAT = 99
