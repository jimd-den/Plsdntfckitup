package com.stratum.core.domain.art

import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asks a model what a world should look like, and gets numbers back.
 *
 * The important restraint is what this does *not* do. It does not generate
 * terrain, it does not generate images, and it does not decide what is in the
 * world. It fills in one small record of knobs — the same record the offline
 * lexicon fills in — because the thing a language model is genuinely better at
 * than a lookup table is exactly this: knowing that a named painter's manner
 * means broken complementary colour and a restless stroke, or that a player
 * asking for "the light in an old chapel" wants one warm source and a lot of
 * dark.
 *
 * Everything else stays deterministic, local and free. One call per *world*,
 * not one per tree, is the whole economics of the pipeline.
 */
class StyleBriefUseCase(
    private val languageModel: LanguageModelPort,
) {

    /**
     * [base] is the style the request is an edit to, so a player can say "now
     * make it colder" and get their own world back colder rather than a
     * stranger's. The lexicon reading is sent along as the starting point,
     * which also means a failed or nonsense reply degrades to a world the
     * player asked for rather than to no world at all.
     */
    suspend operator fun invoke(
        prompt: String,
        base: ArtDirection = ArtDirection.HOUSE,
        seed: Long = prompt.lowercase().hashCode().toLong(),
        modelId: String? = null,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<ArtDirection> {
        val reading = StyleLexicon.interpret(prompt, base, seed)

        val completion = languageModel.complete(
            CompletionRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildString {
                    appendLine("Requested style: $prompt")
                    appendLine("Starting point, as JSON: ${reading.direction.toBriefJson()}")
                    if (reading.matched.isNotEmpty()) {
                        appendLine("Already understood: ${reading.matched.joinToString { it.id }}")
                    }
                    if (reading.unmatched.isNotEmpty()) {
                        appendLine(
                            "Not understood, and the reason you are being asked: " +
                                reading.unmatched.joinToString(),
                        )
                    }
                },
                temperature = TEMPERATURE,
                modelId = modelId,
            ),
            observer,
        )

        val raw = completion.getOrElse { return Result.failure(it) }

        return runCatching {
            val dto = JSON.decodeFromString(StyleBriefDto.serializer(), raw.extractJson())
            dto.applyTo(reading.direction).copy(seed = seed).enforcePlayable()
        }.recoverCatching {
            // A model that answered badly is not a reason to have no world.
            // The lexicon's reading is always playable, so it is what ships.
            reading.direction
        }
    }

    private companion object {
        const val TEMPERATURE = 0.8f

        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val SYSTEM_PROMPT = """
            You are the art director for an isometric voxel action RPG. You do not
            draw anything and you do not invent content. You translate a description
            of a mood, a genre or an artistic manner into rendering parameters.

            Reply with a single JSON object and nothing else. Every field is optional;
            omit anything the request does not call for, and the engine keeps its
            current value. Colours are "#RRGGBB".

            {
              "name": "Short name for this look",
              "summary": "One sentence a player would recognise their request in.",
              "sun": "#RRGGBB", "shadow": "#RRGGBB", "ambient": "#RRGGBB",
              "fog": "#RRGGBB", "ink": "#RRGGBB",
              "hostile": "#RRGGBB", "sacred": "#RRGGBB", "resource": "#RRGGBB",
              "heroRim": "#RRGGBB",
              "ramp": ["#RRGGBB", ...],
              "sunAzimuth": 315, "sunStrength": 0.42, "ambientStrength": 0.58,
              "shadowTint": 0.35, "depthFalloff": 0.45, "ledgeOcclusion": 0.22,
              "terrainSaturation": 0.72, "featureSaturation": 1.35,
              "terrainValueFloor": 0.10, "terrainValueCeiling": 0.68,
              "outlineWidth": 3.0, "outlineDarkness": 0.82, "rimStrength": 0.55,
              "heroScale": 1.6, "grain": 0.07, "seam": 0.10,
              "terraceEmphasis": 1.0, "propVariance": 0.30,
              "hazeColor": "#RRGGBB", "hazeStrength": 0.22, "vignette": 0.28,
              "skyTop": "#RRGGBB", "skyBottom": "#RRGGBB",
              "moteKind": "NONE|FIREFLIES|SPORES|MIST|ASH|EMBERS|SNOW|RAIN|LEAVES|DUST|PETALS|SPARKS",
              "moteDensity": 36, "moteColor": "#RRGGBB",
              "flavour": "prompt words for an image model, comma separated",
              "forbidden": "prompt words an image model must avoid"
            }

            Rules that matter more than the request:
            - Terrain must never be the loudest thing on screen. Keep
              terrainSaturation below 1.0 and terrainValueCeiling below 0.8, whatever
              mood is asked for, so the player, the monsters and the loot stay
              readable. A style that cannot be played is not the style that was asked for.
            - Keep hostile, resource and heroRim clearly distinct from each other and
              from the ground: they are the game's warning lights.
            - Describe an artistic manner through its technique — its colour, its
              edges, its light — rather than by asking for an imitation of a
              particular living artist's work.
            - Ranges: strengths and shares are 0..1, sunAzimuth is 0..360 degrees
              clockwise from screen up, scales are 0.5..3.
        """.trimIndent()
    }
}

/**
 * The wire shape of a brief.
 *
 * Every field is nullable and every field is optional, which is the single most
 * important property of this class: a model that answers with three fields
 * produces a three-field edit rather than a validation failure, and a model
 * that invents a field it likes is ignored instead of crashing a world.
 */
@Serializable
internal data class StyleBriefDto(
    val name: String? = null,
    val summary: String? = null,
    val sun: String? = null,
    val shadow: String? = null,
    val ambient: String? = null,
    val fog: String? = null,
    val ink: String? = null,
    val hostile: String? = null,
    val sacred: String? = null,
    val resource: String? = null,
    val heroRim: String? = null,
    val ramp: List<String>? = null,
    val sunAzimuth: Float? = null,
    val sunStrength: Float? = null,
    val ambientStrength: Float? = null,
    val shadowTint: Float? = null,
    val depthFalloff: Float? = null,
    val ledgeOcclusion: Float? = null,
    val terrainSaturation: Float? = null,
    val featureSaturation: Float? = null,
    val terrainValueFloor: Float? = null,
    val terrainValueCeiling: Float? = null,
    val outlineWidth: Float? = null,
    val outlineDarkness: Float? = null,
    val rimStrength: Float? = null,
    val heroScale: Float? = null,
    val grain: Float? = null,
    val seam: Float? = null,
    val terraceEmphasis: Float? = null,
    val propVariance: Float? = null,
    val hazeColor: String? = null,
    val hazeStrength: Float? = null,
    val vignette: Float? = null,
    val skyTop: String? = null,
    val skyBottom: String? = null,
    val moteKind: String? = null,
    val moteDensity: Int? = null,
    val moteColor: String? = null,
    val flavour: String? = null,
    val forbidden: String? = null,
) {
    /**
     * Folds the brief onto a style, clamping as it goes.
     *
     * The clamps are not defensive programming, they are the contract. A model
     * that returns a terrain saturation of 3 has not made a bold choice, it has
     * made a world where you cannot see the monsters — and the one thing the
     * art layer may never do is take the game away from the player.
     */
    fun applyTo(base: ArtDirection): ArtDirection {
        val floor = terrainValueFloor?.coerceIn(0f, 0.7f) ?: base.contrast.terrainValueFloor
        val ceiling = (terrainValueCeiling?.coerceIn(0.2f, TERRAIN_CEILING_CAP) ?: base.contrast.terrainValueCeiling)
            .coerceAtLeast(floor + MIN_BAND)

        return base.copy(
            name = name?.takeIf(String::isNotBlank) ?: base.name,
            summary = summary?.takeIf(String::isNotBlank) ?: base.summary,
            palette = base.palette.copy(
                sun = sun.color(base.palette.sun),
                shadow = shadow.color(base.palette.shadow),
                ambient = ambient.color(base.palette.ambient),
                fog = fog.color(base.palette.fog),
                ink = ink.color(base.palette.ink),
                hostile = hostile.color(base.palette.hostile),
                sacred = sacred.color(base.palette.sacred),
                resource = resource.color(base.palette.resource),
                heroRim = heroRim.color(base.palette.heroRim),
                ramp = ramp?.mapNotNull { it.colorOrNull() }?.take(RAMP_CAP) ?: base.palette.ramp,
            ),
            light = base.light.copy(
                sunAzimuth = sunAzimuth?.let { ((it % 360f) + 360f) % 360f } ?: base.light.sunAzimuth,
                sunStrength = sunStrength?.coerceIn(0f, 1f) ?: base.light.sunStrength,
                ambientStrength = ambientStrength?.coerceIn(0.1f, 1f) ?: base.light.ambientStrength,
                shadowTint = shadowTint?.coerceIn(0f, 1f) ?: base.light.shadowTint,
                depthFalloff = depthFalloff?.coerceIn(0f, 0.9f) ?: base.light.depthFalloff,
                ledgeOcclusion = ledgeOcclusion?.coerceIn(0f, 0.8f) ?: base.light.ledgeOcclusion,
            ),
            contrast = base.contrast.copy(
                terrainSaturation = terrainSaturation?.coerceIn(0f, TERRAIN_SATURATION_CAP)
                    ?: base.contrast.terrainSaturation,
                featureSaturation = featureSaturation?.coerceIn(0.5f, 2.5f) ?: base.contrast.featureSaturation,
                terrainValueFloor = floor,
                terrainValueCeiling = ceiling,
                outlineWidth = outlineWidth?.coerceIn(0f, 8f) ?: base.contrast.outlineWidth,
                outlineDarkness = outlineDarkness?.coerceIn(0f, 1f) ?: base.contrast.outlineDarkness,
                rimStrength = rimStrength?.coerceIn(0f, 1f) ?: base.contrast.rimStrength,
                heroScale = heroScale?.coerceIn(1f, 3f) ?: base.contrast.heroScale,
            ),
            shape = base.shape.copy(
                grain = grain?.coerceIn(0f, 0.4f) ?: base.shape.grain,
                seam = seam?.coerceIn(0f, 0.5f) ?: base.shape.seam,
                terraceEmphasis = terraceEmphasis?.coerceIn(0f, 3f) ?: base.shape.terraceEmphasis,
                propVariance = propVariance?.coerceIn(0f, 1f) ?: base.shape.propVariance,
            ),
            atmosphere = base.atmosphere.copy(
                hazeColor = hazeColor.color(base.atmosphere.hazeColor),
                hazeStrength = hazeStrength?.coerceIn(0f, 0.8f) ?: base.atmosphere.hazeStrength,
                vignette = vignette?.coerceIn(0f, 0.8f) ?: base.atmosphere.vignette,
                skyTop = skyTop.color(base.atmosphere.skyTop),
                skyBottom = skyBottom.color(base.atmosphere.skyBottom),
                moteKind = moteKind?.let { name ->
                    MoteKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                } ?: base.atmosphere.moteKind,
                moteDensity = moteDensity?.coerceIn(0, MOTE_CAP) ?: base.atmosphere.moteDensity,
                moteColor = moteColor.color(base.atmosphere.moteColor),
            ),
            diction = base.diction.copy(
                flavour = flavour?.takeIf(String::isNotBlank) ?: base.diction.flavour,
                forbidden = forbidden?.takeIf(String::isNotBlank) ?: base.diction.forbidden,
            ),
        )
    }

}

/**
 * Bounds on what a model is allowed to hand back.
 *
 * Top level rather than in a companion: a `@Serializable` class needs its
 * generated `Companion` to be reachable, and a private one of our own takes the
 * name.
 */
/** However dark or garish the request, the ground stays under the actors. */
private const val TERRAIN_SATURATION_CAP = 1.1f
private const val TERRAIN_CEILING_CAP = 0.8f
private const val MIN_BAND = 0.15f
private const val RAMP_CAP = 16
private const val MOTE_CAP = 120

/** `#RRGGBB` or `#AARRGGBB`, or null for anything else a model felt like sending. */
internal fun String?.colorOrNull(): Long? {
    val text = this?.trim()?.removePrefix("#") ?: return null
    return when (text.length) {
        6 -> text.toLongOrNull(16)?.or(Tint.OPAQUE)
        8 -> text.toLongOrNull(16)
        else -> null
    }
}

private fun String?.color(fallback: Long): Long = colorOrNull() ?: fallback

/** Models fence their JSON and apologise around it however firmly they are told not to. */
private fun String.extractJson(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    require(start >= 0 && end > start) { "No JSON object in the model's reply" }
    return substring(start, end + 1)
}

/**
 * The style as the model will see it.
 *
 * Hand-written rather than serialized from [ArtDirection] because the record
 * holds more than a model should be asked to reason about, and a prompt padded
 * with fields nobody will change is a prompt that gets worse answers.
 */
internal fun ArtDirection.toBriefJson(): String = buildString {
    fun hex(color: Long) = "#%06X".format(color and 0xFFFFFF)
    append("{")
    append("\"sun\":\"${hex(palette.sun)}\",")
    append("\"shadow\":\"${hex(palette.shadow)}\",")
    append("\"fog\":\"${hex(palette.fog)}\",")
    append("\"hostile\":\"${hex(palette.hostile)}\",")
    append("\"sacred\":\"${hex(palette.sacred)}\",")
    append("\"resource\":\"${hex(palette.resource)}\",")
    append("\"heroRim\":\"${hex(palette.heroRim)}\",")
    append("\"sunAzimuth\":${light.sunAzimuth},")
    append("\"sunStrength\":${light.sunStrength},")
    append("\"ambientStrength\":${light.ambientStrength},")
    append("\"terrainSaturation\":${contrast.terrainSaturation},")
    append("\"terrainValueCeiling\":${contrast.terrainValueCeiling},")
    append("\"hazeStrength\":${atmosphere.hazeStrength},")
    append("\"moteKind\":\"${atmosphere.moteKind}\"")
    append("}")
}
