package com.stratum.core.domain.art

/**
 * One nameable thing a style can be, and what it does to the look.
 *
 * Traits compose. "dark kawaii woodblock" is three edits applied in order to
 * the house style, not a fourth style somebody had to write — which is the only
 * way a promptable art system stays finite: fifty traits are more combinations
 * than anyone could author as presets, and they all degrade to something
 * playable because each one only moves numbers the renderer already respects.
 */
data class StyleTrait(
    val id: String,
    /** Words that select it. Matched on whole words, lowercased. */
    val words: Set<String>,
    val summary: String,
    /** Appended to the prompt words handed to image models. */
    val flavour: String = "",
    val apply: (ArtDirection) -> ArtDirection,
)

/** What a sentence turned into, and what it could not account for. */
data class StyleReading(
    val direction: ArtDirection,
    val matched: List<StyleTrait>,
    /** Words nothing recognised. Handed to a model when one is available. */
    val unmatched: List<String>,
) {
    val isConfident: Boolean get() = matched.isNotEmpty()
}

/**
 * Reads a sentence and returns a world.
 *
 * This is the offline half of the promptable pipeline and it is the half that
 * always works: no key, no network, no latency, no cost, and it cannot fail in
 * a way that leaves the player without a world. A model ([StyleBrief]) reaches
 * further — it knows what a named painter's manner looks like, and the lexicon
 * never will — but it answers into the same [ArtDirection], so the model is an
 * upgrade to this path rather than a replacement for it, and the game is
 * playable while the request is still in flight.
 */
object StyleLexicon {

    /**
     * The traits, in application order.
     *
     * Order matters: a palette trait that runs after a lighting trait can undo
     * it. Broad moods come first, then palettes, then rendering manners, then
     * weather, so a late, specific trait always gets the last word.
     */
    val traits: List<StyleTrait> = listOf(
        // ---- Moods -------------------------------------------------------
        trait(
            "dark", setOf("dark", "grimdark", "grim", "diablo", "gothic", "bleak", "cursed", "dread"),
            "Low light, heavy ink, colour spent only on threat and treasure.",
            "grimdark, oppressive shadow, heavy black contours, sparse desaturated palette",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFC9A87C,
                    shadow = 0xFF0B0D14,
                    ambient = 0xFF2A2C38,
                    fog = 0xFF07080C,
                    ink = 0xFF05060A,
                    hostile = 0xFFB3352C,
                    sacred = 0xFFB87333,
                    resource = 0xFFE0B145,
                    heroRim = 0xFF9FC6D8,
                ),
                light = direction.light.copy(
                    sunStrength = 0.55f,
                    ambientStrength = 0.34f,
                    shadowTint = 0.55f,
                    depthFalloff = 0.62f,
                    ledgeOcclusion = 0.34f,
                ),
                contrast = direction.contrast.copy(
                    terrainSaturation = 0.42f,
                    terrainValueCeiling = 0.52f,
                    outlineDarkness = 0.92f,
                    outlineWidth = 3.5f,
                ),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF07080C,
                    hazeStrength = 0.4f,
                    vignette = 0.46f,
                    skyTop = 0xFF05060A,
                    skyBottom = 0xFF12141C,
                    moteKind = MoteKind.ASH,
                    moteDensity = 30,
                    moteColor = 0xFF8A7A6A,
                ),
            )
        },
        trait(
            "kawaii", setOf("kawaii", "cute", "pastel", "soft", "chibi", "adorable", "sweet", "cosy", "cozy"),
            "High-key pastels, soft shadows, rounded forms, nothing frightening.",
            "kawaii pastel palette, rounded soft forms, gentle cheerful lighting, chunky cute proportions",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFFFF4E6,
                    shadow = 0xFFB9A7D6,
                    ambient = 0xFFFFE2EC,
                    fog = 0xFFFFE9F2,
                    ink = 0xFF6B4F63,
                    hostile = 0xFFFF8FB1,
                    sacred = 0xFFFFD6E8,
                    resource = 0xFFFFE08A,
                    heroRim = 0xFF9BE8FF,
                ),
                light = direction.light.copy(
                    sunStrength = 0.3f,
                    ambientStrength = 0.74f,
                    shadowTint = 0.28f,
                    depthFalloff = 0.26f,
                    ledgeOcclusion = 0.12f,
                ),
                contrast = direction.contrast.copy(
                    terrainSaturation = 0.95f,
                    terrainValueFloor = 0.42f,
                    terrainValueCeiling = 0.9f,
                    outlineDarkness = 0.55f,
                    outlineWidth = 3f,
                    heroScale = 1.75f,
                ),
                shape = direction.shape.copy(grain = 0.04f, propVariance = 0.36f, propScale = 0.68f),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFFFFEAF4,
                    hazeStrength = 0.18f,
                    vignette = 0.1f,
                    skyTop = 0xFFBFE9FF,
                    skyBottom = 0xFFFFE3F0,
                    moteKind = MoteKind.PETALS,
                    moteDensity = 42,
                    moteColor = 0xFFFFC2DC,
                ),
            )
        },
        trait(
            "sacred", setOf("sacred", "holy", "ritual", "ancestral", "bronze", "temple", "shrine"),
            "Bronze and warm ochre, ritual light, deep green shade.",
            "sacred bronze and ochre ritual accents, ancestral carved forms",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFFFE9C0,
                    shadow = 0xFF1C2B26,
                    sacred = 0xFFCD7F32,
                    resource = 0xFFF0C862,
                ),
                atmosphere = direction.atmosphere.copy(
                    moteKind = MoteKind.FIREFLIES,
                    moteDensity = 34,
                    moteColor = 0xFFF0C862,
                ),
            )
        },
        trait(
            "toxic", setOf("toxic", "corrupted", "corruption", "plague", "poison", "blight", "rot", "fungal"),
            "Acid green against violet rot; the ground itself looks unsafe.",
            "toxic acid-green corruption, violet rot, sickly emissive spores",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    shadow = 0xFF2B1638,
                    ambient = 0xFF4A3A58,
                    fog = 0xFF1A1024,
                    hostile = 0xFFBFFF4D,
                    sacred = 0xFFB07CFF,
                    resource = 0xFFDFFF7A,
                ),
                contrast = direction.contrast.copy(terrainSaturation = 0.6f),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF1A1024,
                    hazeStrength = 0.34f,
                    moteKind = MoteKind.SPORES,
                    moteDensity = 48,
                    moteColor = 0xFFBFFF4D,
                ),
            )
        },
        trait(
            "volcanic", setOf("volcanic", "ashen", "infernal", "hell", "molten", "lava", "scorched", "burnt"),
            "Black rock, ember light, ash in the air.",
            "volcanic black basalt, molten ember light, drifting ash",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFFF9A44,
                    shadow = 0xFF190D0C,
                    ambient = 0xFF3A211C,
                    fog = 0xFF140A08,
                    hostile = 0xFFFF6A3C,
                    resource = 0xFFFFB347,
                ),
                light = direction.light.copy(sunStrength = 0.6f, ambientStrength = 0.36f, emissiveStrength = 1f),
                contrast = direction.contrast.copy(terrainSaturation = 0.5f, terrainValueCeiling = 0.5f),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF1A0B08,
                    hazeStrength = 0.4f,
                    skyTop = 0xFF2A0F0A,
                    skyBottom = 0xFF5A1E10,
                    moteKind = MoteKind.EMBERS,
                    moteDensity = 46,
                    moteColor = 0xFFFF8A3C,
                ),
            )
        },
        trait(
            "frozen", setOf("frozen", "arctic", "ice", "icy", "winter", "snow", "glacial", "tundra"),
            "Blue-white ground, low cold sun, snow in the air.",
            "glacial blue-white, pale cold sunlight, frost-rimed edges",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFE8F4FF,
                    shadow = 0xFF2A3F63,
                    ambient = 0xFF7E97BD,
                    fog = 0xFFC9DCEF,
                    hostile = 0xFF7FD4FF,
                    heroRim = 0xFFFFD9A0,
                ),
                light = direction.light.copy(sunAzimuth = 300f, sunStrength = 0.3f, ambientStrength = 0.72f),
                contrast = direction.contrast.copy(terrainSaturation = 0.45f, terrainValueFloor = 0.34f),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFFC9DCEF,
                    hazeStrength = 0.36f,
                    skyTop = 0xFF9FC3E8,
                    skyBottom = 0xFFDCEBF8,
                    moteKind = MoteKind.SNOW,
                    moteDensity = 52,
                    moteColor = 0xFFFFFFFF,
                ),
            )
        },
        trait(
            "abyssal", setOf("abyssal", "underwater", "drowned", "deep", "oceanic", "sunken"),
            "Teal murk, light from below, everything half-dissolved.",
            "sunken drowned world, teal murk, bioluminescent light from below",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFF9FF0E0,
                    shadow = 0xFF06222B,
                    ambient = 0xFF16505C,
                    fog = 0xFF0A2E38,
                    hostile = 0xFFFF7A9C,
                    resource = 0xFF9FF0E0,
                    heroRim = 0xFFFFD08A,
                ),
                light = direction.light.copy(sunAzimuth = 180f, ambientStrength = 0.5f),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF0A2E38,
                    hazeStrength = 0.5f,
                    skyTop = 0xFF05171E,
                    skyBottom = 0xFF0E3C48,
                    moteKind = MoteKind.DUST,
                    moteDensity = 40,
                    moteColor = 0xFF9FF0E0,
                ),
            )
        },
        trait(
            "autumn", setOf("autumn", "fall", "harvest", "amber", "golden"),
            "Amber canopy, low gold light, leaves coming down.",
            "deep autumn amber and rust, low golden hour light",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(sun = 0xFFFFD79A, shadow = 0xFF3A2436, resource = 0xFFFFC04D),
                light = direction.light.copy(sunAzimuth = 300f, sunStrength = 0.5f),
                atmosphere = direction.atmosphere.copy(
                    skyTop = 0xFF6A4A3A,
                    skyBottom = 0xFFC98A52,
                    moteKind = MoteKind.LEAVES,
                    moteDensity = 36,
                    moteColor = 0xFFE08A3C,
                ),
            )
        },
        trait(
            "celestial", setOf("celestial", "astral", "cosmic", "starlit", "dream", "oneiric", "ethereal"),
            "Indigo ground, violet shadow, everything faintly luminous.",
            "astral indigo and violet, faint luminous glow, star-scattered depth",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFDCC6FF,
                    shadow = 0xFF1A1440,
                    ambient = 0xFF3B2F72,
                    fog = 0xFF150F33,
                    sacred = 0xFF9F7BFF,
                    resource = 0xFF8FE8FF,
                    heroRim = 0xFFFFE08A,
                ),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF150F33,
                    skyTop = 0xFF0A0722,
                    skyBottom = 0xFF241A54,
                    moteKind = MoteKind.SPARKS,
                    moteDensity = 44,
                    moteColor = 0xFFCFC0FF,
                ),
            )
        },

        // ---- Rendering manners -------------------------------------------
        trait(
            "inked", setOf("inked", "hades", "comic", "graphic", "bold", "cel", "celshaded", "cartoon"),
            "Hard bands of flat colour inside heavy contours.",
            "cel-shaded flat colour bands, thick inked contours, illustrated poster look",
        ) { direction ->
            direction.copy(
                contrast = direction.contrast.copy(outlineWidth = 4.5f, outlineDarkness = 0.95f, rimStrength = 0.8f),
                shape = direction.shape.copy(grain = 0.03f, seam = 0.16f, terraceEmphasis = 1.6f, propOutline = 3f),
                light = direction.light.copy(shadowTint = 0.5f),
            )
        },
        trait(
            "painterly", setOf("painterly", "impasto", "brushed", "oil", "vangogh", "gogh", "expressionist", "impressionist"),
            "Broken colour and visible strokes; the shade is not one colour.",
            "thick visible brushwork, broken complementary colour, swirling directional strokes",
        ) { direction ->
            direction.copy(
                contrast = direction.contrast.copy(terrainSaturation = 1.05f, outlineDarkness = 0.6f, outlineWidth = 2f),
                shape = direction.shape.copy(grain = 0.18f, seam = 0.05f, propVariance = 0.45f),
                light = direction.light.copy(shadowTint = 0.45f, sunStrength = 0.5f),
            )
        },
        trait(
            "woodblock", setOf("woodblock", "ukiyo", "ukiyoe", "print", "linocut", "engraving", "etched"),
            "Few flat inks, hard edges, almost no gradient.",
            "woodblock print, flat ink areas, hard carved edges, limited ink palette",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    ramp = listOf(
                        0xFF1B1A17, 0xFF3E4A42, 0xFF6E7A63, 0xFFA8A183,
                        0xFFD9CDA8, 0xFFF2E7C9, 0xFF8C4A32, 0xFFC9702F,
                    ),
                ),
                light = direction.light.copy(sunStrength = 0.2f, ambientStrength = 0.8f, shadowTint = 0.6f),
                contrast = direction.contrast.copy(outlineWidth = 2.5f, outlineDarkness = 1f),
                shape = direction.shape.copy(grain = 0.02f, seam = 0.2f),
            )
        },
        trait(
            "chiaroscuro", setOf("chiaroscuro", "baroque", "tenebrism", "caravaggio", "rembrandt", "candlelit"),
            "One warm source in a mass of near-black.",
            "chiaroscuro, single warm light source, deep enveloping darkness, dramatic falloff",
        ) { direction ->
            direction.copy(
                light = direction.light.copy(
                    sunStrength = 0.75f,
                    ambientStrength = 0.18f,
                    shadowTint = 0.72f,
                    depthFalloff = 0.7f,
                    emissiveStrength = 1f,
                    emissiveRadius = 5f,
                ),
                contrast = direction.contrast.copy(terrainValueCeiling = 0.58f, rimStrength = 0.9f),
                atmosphere = direction.atmosphere.copy(vignette = 0.55f),
            )
        },
        trait(
            "watercolour", setOf("watercolour", "watercolor", "storybook", "illustrated", "picturebook", "gouache"),
            "Pale washes, soft edges, paper light.",
            "watercolour wash, soft bleeding edges, warm paper tone, storybook illustration",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(fog = 0xFFF6EEDD, ink = 0xFF4A3F36),
                light = direction.light.copy(sunStrength = 0.25f, ambientStrength = 0.8f, ledgeOcclusion = 0.1f),
                contrast = direction.contrast.copy(
                    terrainSaturation = 0.75f,
                    terrainValueFloor = 0.45f,
                    terrainValueCeiling = 0.92f,
                    outlineDarkness = 0.5f,
                    outlineWidth = 2f,
                ),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFFF6EEDD,
                    hazeStrength = 0.3f,
                    vignette = 0.06f,
                    skyTop = 0xFFDCE9F2,
                    skyBottom = 0xFFF6EEDD,
                ),
            )
        },
        trait(
            "noir", setOf("noir", "monochrome", "greyscale", "grayscale", "ink", "charcoal", "silhouette"),
            "No colour but the one that matters.",
            "monochrome charcoal values, single spot colour, stark silhouette reading",
        ) { direction ->
            direction.copy(
                contrast = direction.contrast.copy(
                    terrainSaturation = 0.06f,
                    featureSaturation = 1.8f,
                    outlineDarkness = 1f,
                    outlineWidth = 4f,
                ),
                atmosphere = direction.atmosphere.copy(vignette = 0.5f, skyTop = 0xFF0B0B0D, skyBottom = 0xFF26262B),
            )
        },
        trait(
            "retro", setOf("retro", "pixel", "8bit", "16bit", "gameboy", "crt", "demake", "lowfi", "lofi"),
            "A fixed tiny palette and nothing outside it.",
            "limited retro palette, chunky pixel forms, flat dithered shading",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    ramp = listOf(
                        0xFF0F180F, 0xFF1E3A1E, 0xFF2F5C33, 0xFF4E8B45,
                        0xFF7CB35C, 0xFFB5D08A, 0xFFE3E7C0, 0xFF6B4A2A,
                    ),
                ),
                shape = direction.shape.copy(grain = 0f, seam = 0.22f, propOutline = 2f),
                light = direction.light.copy(sunStrength = 0.24f, ambientStrength = 0.78f),
            )
        },
        trait(
            "neon", setOf("neon", "synthwave", "vaporwave", "cyber", "cyberpunk", "outrun", "holographic"),
            "Black ground, magenta and cyan light doing all the work.",
            "neon magenta and cyan rim light, black reflective ground, synthwave grid glow",
        ) { direction ->
            direction.copy(
                palette = direction.palette.copy(
                    sun = 0xFFFF4FD8,
                    shadow = 0xFF10022B,
                    ambient = 0xFF2A0A4A,
                    fog = 0xFF0A0118,
                    hostile = 0xFFFF2E63,
                    sacred = 0xFF7A5CFF,
                    resource = 0xFF2EF5FF,
                    heroRim = 0xFF2EF5FF,
                ),
                light = direction.light.copy(sunStrength = 0.5f, ambientStrength = 0.3f, emissiveStrength = 1f),
                contrast = direction.contrast.copy(
                    terrainSaturation = 0.35f,
                    terrainValueCeiling = 0.42f,
                    rimStrength = 1f,
                    featureSaturation = 1.8f,
                ),
                atmosphere = direction.atmosphere.copy(
                    hazeColor = 0xFF0A0118,
                    hazeStrength = 0.42f,
                    skyTop = 0xFF12002E,
                    skyBottom = 0xFF4A0A66,
                    moteKind = MoteKind.SPARKS,
                    moteDensity = 40,
                    moteColor = 0xFF2EF5FF,
                ),
            )
        },

        // ---- Direct knobs, so a player can correct rather than restyle -----
        trait("brighter", setOf("bright", "brighter", "sunny", "daylight", "vivid"), "More light, more colour.") { direction ->
            direction.copy(
                light = direction.light.copy(ambientStrength = (direction.light.ambientStrength + 0.15f).coerceAtMost(0.9f)),
                contrast = direction.contrast.copy(
                    terrainSaturation = direction.contrast.terrainSaturation * 1.25f,
                    terrainValueCeiling = (direction.contrast.terrainValueCeiling + 0.12f).coerceAtMost(0.95f),
                ),
            )
        },
        trait("moodier", setOf("moody", "moodier", "foggy", "misty", "hazy", "atmospheric"), "More air between you and everything.") { direction ->
            direction.copy(
                atmosphere = direction.atmosphere.copy(
                    hazeStrength = (direction.atmosphere.hazeStrength + 0.18f).coerceAtMost(0.75f),
                    vignette = (direction.atmosphere.vignette + 0.12f).coerceAtMost(0.8f),
                    moteKind = if (direction.atmosphere.moteKind == MoteKind.NONE) MoteKind.MIST else direction.atmosphere.moteKind,
                    moteDensity = maxOf(direction.atmosphere.moteDensity, 30),
                ),
            )
        },
        trait("weird", setOf("weird", "strange", "surreal", "uncanny", "wrong", "eerie"), "Off-kilter light and restless shapes.") { direction ->
            direction.copy(
                light = direction.light.copy(sunAzimuth = 200f, shadowTint = 0.6f),
                shape = direction.shape.copy(propVariance = 0.6f, grain = 0.14f),
                atmosphere = direction.atmosphere.copy(vignette = direction.atmosphere.vignette + 0.1f),
            )
        },
    )

    private val byWord: Map<String, StyleTrait> = buildMap {
        traits.forEach { trait -> trait.words.forEach { word -> put(word, trait) } }
    }

    /** Every word the lexicon knows, for autocomplete and for showing the player what is on offer. */
    val vocabulary: Set<String> get() = byWord.keys

    /**
     * Turns a sentence into a world.
     *
     * Unrecognised words are returned rather than ignored, because they are the
     * most valuable output of the whole call: they are exactly the part of the
     * player's intent this build cannot serve, which is what a model should be
     * asked about and what a designer should read when deciding which trait to
     * write next.
     */
    fun interpret(
        prompt: String,
        base: ArtDirection = ArtDirection.HOUSE,
        seed: Long = prompt.lowercase().hashCode().toLong(),
    ): StyleReading {
        val words = prompt.lowercase()
            .split(*SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val matched = LinkedHashSet<StyleTrait>()
        val unmatched = mutableListOf<String>()
        words.forEach { word ->
            val hit = byWord[word] ?: byWord[word.removeSuffix("s")] ?: byWord[word.removeSuffix("y")]
            if (hit != null) matched += hit else if (word.length > 2 && word !in STOP_WORDS) unmatched += word
        }

        // Applied in lexicon order rather than in the order they were typed, so
        // "kawaii dark" and "dark kawaii" are the same world. A prompt is a
        // description, not a program, and players do not order adjectives.
        val ordered = traits.filter { it in matched }
        var direction = ordered.fold(base) { acc, trait -> trait.apply(acc) }

        direction = direction.copy(
            id = if (ordered.isEmpty()) base.id else "prompt:" + ordered.joinToString("+") { it.id },
            name = if (prompt.isBlank()) base.name else prompt.trim(),
            summary = ordered.joinToString(" ") { it.summary }.ifBlank { base.summary },
            diction = direction.diction.copy(
                flavour = ordered.mapNotNull { it.flavour.takeIf(String::isNotBlank) }.joinToString(", "),
            ),
            seed = seed,
        )

        // Clamped here rather than per trait, so a trait author cannot
        // accidentally ship an unplayable one and a combination of two
        // reasonable traits cannot add up to one either.
        return StyleReading(jitter(direction, seed).enforcePlayable(), ordered, unmatched)
    }

    /**
     * Nudges a style so two worlds asked for with the same words are not the
     * same world.
     *
     * Small on purpose: enough that a reroll is visibly a different place,
     * never enough that "dark" comes back bright. The bounds are the point —
     * unbounded variation is not variety, it is a broken style.
     */
    private fun jitter(direction: ArtDirection, seed: Long): ArtDirection {
        if (seed == 0L) return direction
        fun roll(salt: Int): Float {
            var hash = (seed * 6364136223846793005L + salt * 1442695040888963407L) ushr 17
            hash = hash xor (hash shr 27)
            return ((hash and 0xFFFF).toFloat() / 0xFFFF) * 2f - 1f
        }
        return direction.copy(
            palette = direction.palette.copy(
                sun = Tint.temperature(direction.palette.sun, roll(1) * TEMPERATURE_JITTER),
                shadow = Tint.temperature(direction.palette.shadow, roll(2) * TEMPERATURE_JITTER),
            ),
            light = direction.light.copy(
                sunAzimuth = direction.light.sunAzimuth + roll(3) * AZIMUTH_JITTER,
            ),
            atmosphere = direction.atmosphere.copy(
                moteDensity = (direction.atmosphere.moteDensity +
                    (roll(4) * MOTE_JITTER).toInt()).coerceAtLeast(0),
            ),
        )
    }

    private fun trait(
        id: String,
        words: Set<String>,
        summary: String,
        flavour: String = "",
        apply: (ArtDirection) -> ArtDirection,
    ) = StyleTrait(id, words, summary, flavour, apply)

    private val SEPARATORS = arrayOf(" ", ",", ".", ";", "/", "-", "_", "\n", "\t", "!", "?")

    /** Words a player writes to be polite to the machine. They mean nothing here. */
    private val STOP_WORDS = setOf(
        "the", "and", "with", "make", "like", "style", "look", "world", "please", "very",
        "more", "less", "kind", "sort", "feel", "feels", "give", "want", "into", "some",
        "that", "this", "have", "but", "for", "art", "very", "really", "bit",
    )

    private const val TEMPERATURE_JITTER = 0.18f
    private const val AZIMUTH_JITTER = 18f
    private const val MOTE_JITTER = 8f
}
