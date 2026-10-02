package com.stratum.engine.model.mask

/**
 * The mask builder's rules: a spirit's mask is a round head, and everything
 * on it is African mask art -- nothing else.
 *
 * What a player may set is the masks' own vocabulary: the tradition it
 * leans on; carved almond slits, closed crescents, Mbari's ringed eyes or
 * Mgbedike's tubes; the brow's weight; the nose and the lips, closed, pursed
 * or baring teeth; ichi cuts and cheek scarification; uli ornament; painted
 * fields; a crest of combs, Ijele's tiers, horns, plumes or a sun disc;
 * small or elephant ears; tusks; a beard; the palette of kaolin, camwood,
 * ochre, indigo and lampblack; the depth of the carving; symmetry.
 *
 * The emoji dress the sticker heads wear -- hair, hats, glasses, chains,
 * earrings, the poppy -- is not mask art, so [sanitise] takes it off
 * anything the builder is given.
 */
object AfricanMaskBuilder {

    /** [g] with everything that is not mask art taken off. */
    fun sanitise(g: MaskGenome): MaskGenome = g.copy(
        hair = HairStyle.NONE, earrings = Earrings.NONE, hat = false, shades = false, chain = false, flower = false,
    ).normalised()

    /** A fresh mask in [tradition] (any when null), as the builder's dice roll it. */
    fun roll(seed: Long, tradition: MaskTradition? = null): MaskGenome = sanitise(MaskGenome.random(seed, tradition))

    private fun mask(name: String, g: MaskGenome) = sanitise(g.copy(name = name))

    /**
     * What players might make: each built from the builder's dials alone,
     * across every tradition, to show how far they reach.
     */
    val examples: List<MaskGenome> = listOf(
        mask("Kaolin Maiden", MaskGenome(
            tradition = MaskTradition.MAIDEN, face = FaceShape.OVAL, width = 0.35f, brow = 0.15f, eyes = EyeForm.ALMOND,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE, cheekMarks = 2, crest = CrestForm.COMBS,
            crestHeight = 0.85f, crestCount = 3, ears = EarForm.SMALL, palette = MaskPalettes.KAOLIN_VERMILION, ornament = 0.9f,
        )),
        mask("Brave One", MaskGenome(
            tradition = MaskTradition.MGBEDIKE, face = FaceShape.SQUARE_JAW, width = 0.9f, brow = 1f, eyes = EyeForm.TUBULAR,
            nose = NoseForm.BROAD, mouth = MouthForm.OPEN_TEETH, ichi = 3, cheekMarks = 3, crest = CrestForm.HORNS,
            crestHeight = 0.95f, ears = EarForm.SMALL, beard = BeardForm.STRIPED, palette = MaskPalettes.EMBER, relief = 0.85f,
        )),
        mask("White Okoroshi", MaskGenome(
            tradition = MaskTradition.OKOROSHI, face = FaceShape.LONG, width = 0.4f, brow = 0.3f, eyes = EyeForm.CRESCENT,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.PURSED, crest = CrestForm.DISC, crestHeight = 0.6f,
            ears = EarForm.NONE, palette = MaskPalettes.KAOLIN_INK, ornament = 0.7f,
        )),
        mask("Night Okoroshi", MaskGenome(
            tradition = MaskTradition.OKOROSHI, face = FaceShape.OVAL, width = 0.6f, brow = 0.7f, eyes = EyeForm.ROUND,
            nose = NoseForm.ARCHED, mouth = MouthForm.OPEN_TEETH, ichi = 2, crest = CrestForm.NONE, ears = EarForm.NONE,
            palette = MaskPalettes.ONYX, ornament = 0.6f,
        )),
        mask("Elephant Spirit", MaskGenome(
            tradition = MaskTradition.ELEPHANT, face = FaceShape.OVAL, width = 0.5f, brow = 0.7f, eyes = EyeForm.ROUND,
            nose = NoseForm.BROAD, mouth = MouthForm.OPEN_TEETH, crest = CrestForm.HORNS, crestHeight = 0.35f,
            ears = EarForm.ELEPHANT, tusks = true, palette = MaskPalettes.TERRACOTTA, ornament = 0.5f,
        )),
        mask("King of Masks", MaskGenome(
            tradition = MaskTradition.IJELE, face = FaceShape.ROUND, width = 0.6f, brow = 0.4f, eyes = EyeForm.ALMOND,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE, cheekMarks = 1, crest = CrestForm.TIERS,
            crestHeight = 0.9f, crestCount = 4, ears = EarForm.SMALL, palette = MaskPalettes.MBARI, ornament = 0.8f,
        )),
        mask("Ram of Ikenga", MaskGenome(
            tradition = MaskTradition.IKENGA, face = FaceShape.HEART, width = 0.55f, brow = 0.8f, eyes = EyeForm.ALMOND,
            nose = NoseForm.ARCHED, mouth = MouthForm.CLOSED_SMILE, ichi = 4, crest = CrestForm.HORNS, crestHeight = 1f,
            ears = EarForm.NONE, beard = BeardForm.POINTED, palette = MaskPalettes.LAPIS, ornament = 0.4f,
        )),
        mask("Mbari Sun", MaskGenome(
            tradition = MaskTradition.MBARI, face = FaceShape.ROUND, width = 0.7f, brow = 0.4f, eyes = EyeForm.ROUND,
            nose = NoseForm.BROAD, mouth = MouthForm.PURSED, cheekMarks = 3, crest = CrestForm.PLUMES, crestHeight = 0.75f,
            crestCount = 7, ears = EarForm.SMALL, palette = MaskPalettes.MBARI, ornament = 1f,
        )),
        mask("Camwood Elder", MaskGenome(
            tradition = MaskTradition.IKENGA, face = FaceShape.OVAL, width = 0.55f, brow = 0.6f, eyes = EyeForm.CRESCENT,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE, ichi = 5, cheekMarks = 2, crest = CrestForm.COMBS,
            crestHeight = 0.45f, crestCount = 1, ears = EarForm.SMALL, palette = MaskPalettes.CAMWOOD, ornament = 0.3f,
        )),
        mask("Indigo Seer", MaskGenome(
            tradition = MaskTradition.MAIDEN, face = FaceShape.OVAL, width = 0.45f, brow = 0.2f, eyes = EyeForm.CRESCENT,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.PURSED, crest = CrestForm.DISC, crestHeight = 0.8f,
            ears = EarForm.NONE, palette = MaskPalettes.INDIGO_OCHRE, ornament = 0.85f, paint = FacePaint.EYE_BAND,
        )),
        mask("Split Face", MaskGenome(
            tradition = MaskTradition.OKOROSHI, face = FaceShape.OVAL, width = 0.5f, brow = 0.5f, eyes = EyeForm.ALMOND,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE, crest = CrestForm.COMBS, crestHeight = 0.6f,
            crestCount = 5, ears = EarForm.SMALL, palette = MaskPalettes.KAOLIN_CAMWOOD, paint = FacePaint.HALF, ornament = 0.5f,
        )),
        mask("Jade Plume", MaskGenome(
            tradition = MaskTradition.MAIDEN, face = FaceShape.HEART, width = 0.4f, brow = 0.25f, eyes = EyeForm.ALMOND,
            nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.PURSED, cheekMarks = 2, crest = CrestForm.PLUMES,
            crestHeight = 0.9f, crestCount = 5, ears = EarForm.NONE, palette = MaskPalettes.JADE_CORAL, ornament = 0.7f,
        )),
    )
}
