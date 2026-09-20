package com.stratum.core.domain.ai

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.ContentPackException
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import kotlinx.serialization.json.Json

/**
 * Turns a sentence from the player into a playable content pack.
 *
 * Prompt construction, parsing and validation all live here rather than in the
 * network adapter, because all three are about what makes a *pack* valid, not
 * about how bytes reach a provider. That also means they can be tested against
 * a canned response with no network and no Android.
 */
class GenerateContentPackUseCase(
    private val languageModel: LanguageModelPort,
    private val assembler: ContentPackAssembler = ContentPackAssembler(),
) {

    suspend operator fun invoke(
        request: PackGenerationRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<ContentPack> {
        val completion = languageModel.complete(
            CompletionRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildUserPrompt(request),
                temperature = request.temperature,
                modelId = request.modelId,
            ),
            observer,
        )

        val raw = completion.getOrElse { return Result.failure(it) }

        return runCatching {
            val dto = JSON.decodeFromString(GeneratedPackDto.serializer(), raw.extractJsonObject())
            val pack = dto.toDomain(fallbackId = request.suggestedId)
            verifyPlayable(pack)
            pack
        }.recoverCatching { cause ->
            throw GenerationException(
                "The model did not return a usable content pack: ${cause.message}",
                cause,
            )
        }
    }

    /**
     * Runs the pack through the same assembler the game uses, so a pack that
     * would crash at world generation fails here instead, while the player is
     * still looking at the generate button.
     */
    private fun verifyPlayable(pack: ContentPack) {
        if (pack.blocks.isEmpty()) {
            throw ContentPackException("The generated pack defines no blocks")
        }
        if (pack.biomes.isEmpty()) {
            throw ContentPackException("The generated pack defines no regions")
        }
        assembler.assemble(listOf(pack))
    }

    private fun buildUserPrompt(request: PackGenerationRequest): String = buildString {
        appendLine("Theme: ${request.theme}")
        appendLine("Pack id: ${request.suggestedId}")
        appendLine("Produce ${request.blockCount} blocks, ${request.biomeCount} regions and ${request.classCount} classes.")
        if (request.loreCount > 0) {
            appendLine("Include ${request.loreCount} lore entries.")
        }
        if (request.extraDirection.isNotBlank()) {
            appendLine("Additional direction: ${request.extraDirection}")
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val SYSTEM_PROMPT = """
            You design content packs for an isometric voxel action RPG. The world is
            a grid of cubic blocks the player mines and builds with.

            Reply with a single JSON object and nothing else. No prose, no markdown.

            Schema:
            {
              "id": "short_lowercase_namespace",
              "name": "Pack Name",
              "description": "One sentence.",
              "palette": { "surface": "#RRGGBB", "surfaceRaised": "#RRGGBB", "ink": "#RRGGBB",
                           "inkMuted": "#RRGGBB", "accent": "#RRGGBB", "accentAlt": "#RRGGBB",
                           "danger": "#RRGGBB" },
              "blocks": [ { "id": "ns:block_id", "name": "Block Name",
                            "material": "SOIL|STONE|ORE|WOOD|FOLIAGE|LIQUID|CLOTH|METAL|RITUAL",
                            "hardness": 0.5, "requiredTier": 0, "solid": true, "opaque": true,
                            "gravity": false, "needsSupport": false, "light": 0,
                            "topColor": "#RRGGBB", "sideColor": "#RRGGBB", "accentColor": "#RRGGBB",
                            "drop": null } ],
              "biomes": [ { "id": "ns:region_id", "name": "Region Name", "description": "One sentence.",
                            "surfaceBlock": "ns:block_id", "subsurfaceBlock": "ns:block_id",
                            "fillerBlock": "ns:block_id", "heightBias": 0, "roughness": 1.0,
                            "scatter": [ { "block": "ns:block_id", "chance": 0.05, "height": 3 } ],
                            "deposits": [ { "block": "ns:block_id", "minZ": 2, "maxZ": 12,
                                            "chance": 0.1, "clusterSize": 4 } ] } ],
              "classes": [ { "id": "ns:class_id", "name": "Class Name", "title": "Epithet",
                             "description": "Two sentences.", "health": 240, "resource": 100,
                             "resourceName": "Focus", "strength": 14, "agility": 11, "insight": 9,
                             "startingBlocks": ["ns:block_id"] } ],
              "lore": [ { "id": "ns:lore_id", "title": "Title", "body": "A paragraph.",
                          "category": "HISTORY|DEITY|ARTIFACT|BESTIARY|PLACE|RITUAL",
                          "subject": "ns:block_id" } ]
            }

            Rules that matter:
            - Every block, region and class id must start with the pack id and a colon.
            - Every block a region names must exist in the blocks array.
            - hardness is seconds to mine: 0.4 for soil, 2 for stone, 5 for hard ore.
            - Give ores a higher requiredTier than stone so tool upgrades matter.
            - sideColor should be a darker shade of topColor.
            - Light-emitting blocks use 1-15; most blocks use 0.
        """.trimIndent()
    }
}

data class PackGenerationRequest(
    val theme: String,
    val suggestedId: String = "generated",
    val blockCount: Int = 12,
    val biomeCount: Int = 3,
    val classCount: Int = 2,
    val loreCount: Int = 4,
    val extraDirection: String = "",
    val temperature: Float = 0.9f,
    val modelId: String? = null,
)

/**
 * Writes codex entries for content that already exists.
 *
 * Separate from pack generation because it is the far more common request: a
 * player who likes their world wants more written about it, not a different one.
 */
class GenerateLoreUseCase(
    private val languageModel: LanguageModelPort,
) {

    suspend operator fun invoke(
        request: LoreGenerationRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<List<LoreEntry>> {
        val completion = languageModel.complete(
            CompletionRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildString {
                    appendLine("World: ${request.worldSummary}")
                    appendLine("Write ${request.count} entries.")
                    if (request.subjects.isNotEmpty()) {
                        appendLine("Cover these subjects, using their exact ids:")
                        request.subjects.forEach { appendLine("- ${it.id} (${it.label})") }
                    }
                    if (request.tone.isNotBlank()) appendLine("Tone: ${request.tone}")
                },
                temperature = request.temperature,
                modelId = request.modelId,
            ),
            observer,
        )

        val raw = completion.getOrElse { return Result.failure(it) }

        return runCatching {
            val entries = JSON.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(GeneratedLoreDto.serializer()),
                raw.extractJsonArray(),
            )
            entries
                .filter { it.title.isNotBlank() && it.body.isNotBlank() }
                .mapIndexed { index, dto ->
                    LoreEntry(
                        id = dto.id.ifBlank { "${request.namespace}:lore_$index" },
                        title = dto.title,
                        body = dto.body,
                        category = runCatching { LoreCategory.valueOf(dto.category.uppercase()) }
                            .getOrDefault(LoreCategory.HISTORY),
                        subjectId = dto.subject?.takeIf { it.isNotBlank() },
                    )
                }
                .also {
                    if (it.isEmpty()) throw GenerationException("The model returned no usable lore entries")
                }
        }.recoverCatching { cause ->
            if (cause is GenerationException) throw cause
            throw GenerationException("The model did not return usable lore: ${cause.message}", cause)
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val SYSTEM_PROMPT = """
            You write codex entries for a voxel action RPG.

            Reply with a single JSON array and nothing else. No prose, no markdown.
            Each element: { "id": "ns:lore_id", "title": "Title", "body": "One or two
            paragraphs.", "category": "HISTORY|DEITY|ARTIFACT|BESTIARY|PLACE|RITUAL",
            "subject": "ns:subject_id or null" }

            Write with specificity. Name things. Avoid generic fantasy filler and
            avoid describing the player.
        """.trimIndent()
    }
}

data class LoreGenerationRequest(
    val worldSummary: String,
    val namespace: String = "generated",
    val count: Int = 4,
    val subjects: List<LoreSubject> = emptyList(),
    val tone: String = "",
    val temperature: Float = 0.95f,
    val modelId: String? = null,
)

data class LoreSubject(val id: String, val label: String)

/**
 * Pulls the JSON object out of a reply.
 *
 * Models wrap JSON in markdown fences and add a sentence of commentary no matter
 * how firmly the prompt forbids it. Rejecting those replies would fail most
 * generations for a reason the player cannot act on, so find the object instead.
 */
internal fun String.extractJsonObject(): String = extractBetween('{', '}')

internal fun String.extractJsonArray(): String = extractBetween('[', ']')

private fun String.extractBetween(open: Char, close: Char): String {
    val start = indexOf(open)
    val end = lastIndexOf(close)
    if (start < 0 || end <= start) {
        throw GenerationException("No JSON found in the model's reply")
    }
    return substring(start, end + 1)
}
