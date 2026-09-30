package com.stratum.engine.model.mask

/**
 * The Igbo emoji set: masks as if Igbo artists had made the emoji -- camwood
 * and kaolin faces, ichi and uli marks, afro puffs and zigzag crowns, gold
 * earrings, a bucket hat, a poppy -- each a [MaskGenome] and a feeling. Built
 * from the same dials the creator edits, so every one is a starting point.
 */
object IgboEmoji {
    class Emoji(val name: String, val genome: MaskGenome, val feeling: String)

    private fun emoji(name: String, feeling: String, g: MaskGenome) = Emoji(name, g.copy(name = name).normalised(), feeling)

    private val base = MaskGenome(
        tradition = MaskTradition.MAIDEN, face = FaceShape.OVAL, width = 0.5f, height = 56, brow = 0.2f,
        eyes = EyeForm.ALMOND, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
        crest = CrestForm.NONE, crestHeight = 0.4f, ears = EarForm.SMALL, ornament = 0.2f, relief = 0.5f, features = 0.5f,
    )

    val set: List<Emoji> = listOf(
        emoji("Elder", "Calm", base.copy(palette = MaskPalettes.CAMWOOD, hair = HairStyle.CAP, ichi = 3, cheekMarks = 3, face = FaceShape.OVAL)),
        emoji("Maiden", "Wink", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, hair = HairStyle.BUN, eyes = EyeForm.CRESCENT, ornament = 0.6f, cheekMarks = 3, earrings = Earrings.DROPS)),
        emoji("Ochre Sun", "Smile", base.copy(palette = MaskPalettes.CAMWOOD, paint = FacePaint.T_ZONE, hair = HairStyle.PUFFS, eyes = EyeForm.CRESCENT, ornament = 0.9f, face = FaceShape.ROUND)),
        emoji("Mbari Kid", "Wink", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, paint = FacePaint.BROW, hair = HairStyle.PUFFS, ornament = 0.8f, earrings = Earrings.DROPS, face = FaceShape.ROUND)),
        emoji("Poppy", "Wink", base.copy(palette = MaskPalettes.POPPY, flower = true, ears = EarForm.NONE, eyes = EyeForm.ROUND, mouth = MouthForm.CLOSED_SMILE)),
        emoji("Zigzag", "Wink", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, hair = HairStyle.CROWN, cheekMarks = 3, eyes = EyeForm.ROUND, ornament = 0.1f)),
        emoji("Golden", "Smile", base.copy(palette = MaskPalettes.OCHRE, paint = FacePaint.BROW, hair = HairStyle.PUFFS, eyes = EyeForm.CRESCENT, chain = true, ornament = 0.1f)),
        emoji("Bucket Hat", "Calm", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, paint = FacePaint.EYE_BAND, hat = true, cheekMarks = 3, ornament = 0.1f)),
        emoji("Camwood", "Calm", base.copy(palette = MaskPalettes.CAMWOOD, hair = HairStyle.BRAIDS, ichi = 3, cheekMarks = 2)),
        emoji("Owl Eyes", "Calm", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, paint = FacePaint.EYE_BAND, hair = HairStyle.PUFFS, eyes = EyeForm.TUBULAR, ornament = 0.6f)),
        emoji("Laughing Kaolin", "Laugh", base.copy(palette = MaskPalettes.KAOLIN_CAMWOOD, hair = HairStyle.CAP, ichi = 0, ornament = 0.6f, eyes = EyeForm.CRESCENT, earrings = Earrings.HOOPS)),
        emoji("Shades", "Smile", base.copy(palette = MaskPalettes.CAMWOOD, hair = HairStyle.CAP, ichi = 3, cheekMarks = 3, shades = true, earrings = Earrings.HOOPS)),
    )
}
