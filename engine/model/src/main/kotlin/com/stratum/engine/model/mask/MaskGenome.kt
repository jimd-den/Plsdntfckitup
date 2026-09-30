package com.stratum.engine.model.mask

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The masquerade tradition a mask is drawn from. Each one is a family of
 * habits -- which crest, which eyes, which colours -- that [MaskGenome.random]
 * leans toward, so a roll "in the maiden style" reads as a maiden mask and not
 * as noise. Every dial stays free: a tradition is where a mask starts, never a
 * cage.
 *
 * These are real Igbo masquerades, and the names are kept in Igbo on purpose;
 * the notes say what each is known for, so the look is grounded in the
 * carving it honours rather than in a vague "tribal" pastiche.
 */
enum class MaskTradition(val label: String) {
    /**
     * Agbogho Mmuo, the maiden spirit of the northern Igbo: a whitened face
     * (nzu, kaolin) with small, delicate features -- narrow slit eyes under
     * swelling lids, a fine straight nose, a closed mouth -- crowned by an
     * elaborate crested coiffure of combs, and painted with uli, the fine
     * black linework women draw on the body and on house walls.
     */
    MAIDEN("Agbogho Mmuo"),

    /**
     * Mgbedike, "the time of the brave": the fierce, dark male counterpart to
     * the maiden. Horns, bared teeth, heavy brows and projecting tubular eyes;
     * danced with aggression to show strength.
     */
    MGBEDIKE("Mgbedike"),

    /**
     * Okoroshi of the Owerri Igbo: the spirits of the dry season, where the
     * beautiful Okoroshi Oma are white and the ugly, dangerous Okoroshi Ojo
     * are black. Masks here play the two against each other: black and white,
     * nothing else but a single accent.
     */
    OKOROSHI("Okoroshi"),

    /**
     * Ogbodo Enyi, the elephant spirit of the north-eastern Igbo and Izzi: a
     * face with great fan ears, tusks curving from the mouth, and a crest of
     * horns or a small comb. Heavy, earthy, ochre and clay.
     */
    ELEPHANT("Ogbodo Enyi"),

    /**
     * Ijele, the king of masks: a towering, many-tiered crown several metres
     * high, covered in figures and mirrors, danced only at the greatest
     * occasions. Here it becomes a stepped ziggurat of colour bands above the
     * face -- the tier is also the most Art Deco shape there is.
     */
    IJELE("Ijele"),

    /**
     * After Ikenga, the horned shrine figure of a man's right hand and his
     * achievement. Not a mask itself, but its sweeping ram horns and the ichi
     * scarification of titled men make a proud, graphic crest.
     */
    IKENGA("Ikenga"),

    /**
     * After Mbari, the Owerri houses of painted clay sculpture built for Ala,
     * the earth goddess: saturated yellows, blues, reds and greens in flat
     * fields. Here it is a colour attitude more than a shape: sunbursts, discs
     * and round popping eyes.
     */
    MBARI("Mbari"),
}

/** The outline of the face itself, seen from the front. */
enum class FaceShape(val label: String) { OVAL("Oval"), HEART("Heart"), LONG("Long"), ROUND("Round"), SQUARE_JAW("Square jaw") }

/** How the eyes are carved. */
enum class EyeForm(val label: String) {
    /** Narrow slits in swelling almond lids: the maiden's downcast calm. */
    ALMOND("Almond slits"),

    /** Closed crescents, a line curving down: sleep, serenity, the dead. */
    CRESCENT("Closed crescents"),

    /** Round concentric eyes, ringed like a target: the Mbari stare. */
    ROUND("Round"),

    /** Tubes projecting from the face, bored through: Mgbedike's fierceness. */
    TUBULAR("Tubular"),
}

enum class NoseForm(val label: String) { LONG_STRAIGHT("Long straight"), BROAD("Broad"), ARCHED("Arched") }

enum class MouthForm(val label: String) { CLOSED_SMILE("Closed smile"), OPEN_TEETH("Bared teeth"), PURSED("Pursed") }

/**
 * What rises above the brow. Each maps to a masquerade habit: the maiden's
 * crested combs, Ijele's tiers, Mgbedike and Ikenga horns, feather plumes,
 * and a sun disc behind the head.
 */
enum class CrestForm(val label: String) { NONE("None"), COMBS("Combs"), TIERS("Tiers"), HORNS("Horns"), PLUMES("Plumes"), DISC("Disc") }

enum class EarForm(val label: String) { NONE("None"), SMALL("Small"), ELEPHANT("Elephant") }

enum class BeardForm(val label: String) { NONE("None"), STRIPED("Striped"), POINTED("Pointed") }

/** Hair on an emoji head, in the crest's colour: a close cap, a ring of afro puffs, a bun, a crown banded in zigzag, cornrow braids. */
enum class HairStyle(val label: String) { NONE("None"), CAP("Cap"), PUFFS("Puffs"), BUN("Bun"), CROWN("Zigzag crown"), BRAIDS("Braids") }

/** Face paint in the second colour: half the face, an ochre T over brow and nose, the brow, the chin, a band across the eyes. */
enum class FacePaint(val label: String) { NONE("None"), HALF("Half"), T_ZONE("T-zone"), BROW("Brow"), CHIN("Chin"), EYE_BAND("Eye band") }

enum class Earrings(val label: String) { NONE("None"), HOOPS("Hoops"), DROPS("Drops") }

/**
 * Everything that makes one mask different from another: a few dozen plain
 * values the studio's dials edit and [IgboMaskGenerator] turns into voxels.
 *
 * A genome is a value, never a model: it is tiny, it can be saved or shared as
 * a line of text, and regenerating from it is deterministic -- the same genome
 * always carves the same mask, cell for cell. That is what lets a player drag a
 * slider and see the mask change live without the studio keeping any history
 * of how it got there.
 *
 * Continuous dials run 0..1 so a slider maps straight onto them; the
 * generator decides what 0 and 1 mean in voxels.
 */
data class MaskGenome(
    val name: String = "Mask",
    val tradition: MaskTradition = MaskTradition.MAIDEN,
    val face: FaceShape = FaceShape.OVAL,
    /** 0 narrow and long, 1 broad. */
    val width: Float = 0.5f,
    /** Height of the whole mask in microvoxels, crest and plinth included. */
    val height: Int = 56,
    /** 0 a painted hairline brow, 1 a heavy carved ridge. */
    val brow: Float = 0.3f,
    val eyes: EyeForm = EyeForm.ALMOND,
    val nose: NoseForm = NoseForm.LONG_STRAIGHT,
    val mouth: MouthForm = MouthForm.CLOSED_SMILE,
    /** Ichi: parallel cuts across the forehead of a titled man, 0..5. */
    val ichi: Int = 0,
    /** Short parallel marks on each cheek, 0..3. */
    val cheekMarks: Int = 0,
    val crest: CrestForm = CrestForm.COMBS,
    /** 0 a low crest, 1 as tall as the face again. */
    val crestHeight: Float = 0.6f,
    /** Combs, tiers, or plume feathers: how many. Horns and discs ignore it. */
    val crestCount: Int = 3,
    val ears: EarForm = EarForm.SMALL,
    val tusks: Boolean = false,
    val beard: BeardForm = BeardForm.NONE,
    /** An index into [MaskPalettes.all]. */
    val palette: Int = 0,
    /** Mirror-symmetric about the vertical axis. When false, uli and marks fall to one side. */
    val symmetric: Boolean = true,
    /** 0 a plain face, 1 covered in uli, rings and dots. */
    val ornament: Float = 0.5f,
    /** How far the features stand out from the face, 0 shallow to 1 deep. */
    val relief: Float = 0.5f,
    /** Where the features sit: 0 low in the face (a tall brow), 1 high (a long chin). */
    val features: Float = 0.5f,
    /** Emoji dress: hair, face paint and what is worn. The carved masks ignore them. */
    val hair: HairStyle = HairStyle.NONE,
    val paint: FacePaint = FacePaint.NONE,
    val earrings: Earrings = Earrings.NONE,
    /** A bucket hat, cream, an uli knot on its front. */
    val hat: Boolean = false,
    /** Dark glasses over the eyes. */
    val shades: Boolean = false,
    /** A gold chain under the chin. */
    val chain: Boolean = false,
    /** Not a face at all: a poppy, its dark heart the face. */
    val flower: Boolean = false,
) {
    init {
        require(height in MIN_HEIGHT..MAX_HEIGHT) { "a mask is $MIN_HEIGHT..$MAX_HEIGHT voxels tall, not $height" }
    }

    /**
     * Every dial clamped into range and rounded to a thousandth: what a
     * hand-edited or decoded genome passes through. The rounding makes the
     * text form in [MaskCodec] exact, so a kept mask reopens cell for cell.
     */
    fun normalised(): MaskGenome {
        fun q(v: Float) = (v.coerceIn(0f, 1f) * 1000f).roundToInt() / 1000f
        return copy(
            width = q(width), height = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT), brow = q(brow),
            ichi = ichi.coerceIn(0, 5), cheekMarks = cheekMarks.coerceIn(0, 3), crestHeight = q(crestHeight),
            crestCount = crestCount.coerceIn(1, 9), palette = Math.floorMod(palette, MaskPalettes.all.size),
            ornament = q(ornament), relief = q(relief), features = q(features),
        )
    }

    /**
     * "Another like this": the same mask with a few dials nudged. [amount] 0
     * changes nothing and 1 is nearly a fresh roll; around 0.3 keeps the
     * character (tradition, crest, colours mostly) and varies the details.
     * Deterministic in [seed], so the studio can offer the same "another"
     * twice if asked.
     */
    fun mutate(seed: Long, amount: Float = 0.3f): MaskGenome {
        val a = amount.coerceIn(0f, 1f)
        if (a == 0f) return this
        val r = Random(seed)
        fun nudge(v: Float) = (v + (r.nextFloat() * 2f - 1f) * a * 0.6f).coerceIn(0f, 1f)
        fun <T> maybe(current: T, options: List<T>, chance: Float = a * 0.6f): T = if (r.nextFloat() < chance) options[r.nextInt(options.size)] else current
        val nudgedHeight = (height + ((r.nextFloat() * 2f - 1f) * a * 16f).roundToInt()).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        return copy(
            face = maybe(face, FaceShape.entries),
            width = nudge(width),
            height = nudgedHeight,
            brow = nudge(brow),
            eyes = maybe(eyes, EyeForm.entries, a * 0.4f),
            nose = maybe(nose, NoseForm.entries),
            mouth = maybe(mouth, MouthForm.entries, a * 0.4f),
            ichi = (ichi + if (r.nextFloat() < a) r.nextInt(-2, 3) else 0).coerceIn(0, 5),
            cheekMarks = (cheekMarks + if (r.nextFloat() < a) r.nextInt(-1, 2) else 0).coerceIn(0, 3),
            crest = maybe(crest, CrestForm.entries.filter { it != CrestForm.NONE }, a * 0.25f),
            crestHeight = nudge(crestHeight),
            crestCount = (crestCount + if (r.nextFloat() < a) r.nextInt(-1, 2) else 0).coerceIn(1, 9),
            palette = if (r.nextFloat() < a * 0.5f) r.nextInt(MaskPalettes.all.size) else palette,
            ornament = nudge(ornament),
            relief = nudge(relief),
            features = nudge(features),
        ).normalised()
    }

    companion object {
        const val MIN_HEIGHT = 32
        const val MAX_HEIGHT = 72

        /**
         * A fresh mask, deterministic in [seed]. With a [tradition] the roll
         * leans the way that masquerade does -- a maiden gets combs, a pale
         * face and almond eyes; an elephant spirit gets ears and tusks --
         * while the rest is free. Without one, a tradition is rolled first.
         */
        fun random(seed: Long, tradition: MaskTradition? = null): MaskGenome {
            val r = Random(seed * 0x9E3779B97F4A7C15uL.toLong() + 0x51ED27L)
            val t = tradition ?: MaskTradition.entries[r.nextInt(MaskTradition.entries.size)]
            fun <T> pick(vararg options: T): T = options[r.nextInt(options.size)]
            fun between(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
            val base = MaskGenome(
                name = t.label,
                tradition = t,
                width = between(0.25f, 0.75f),
                height = r.nextInt(44, 69),
                relief = between(0.35f, 0.8f),
                features = between(0.3f, 0.7f),
                ornament = between(0.3f, 0.9f),
                symmetric = r.nextFloat() > 0.12f,
            )
            val g = when (t) {
                MaskTradition.MAIDEN -> base.copy(
                    face = pick(FaceShape.OVAL, FaceShape.HEART, FaceShape.LONG),
                    width = between(0.2f, 0.55f),
                    brow = between(0.05f, 0.35f),
                    eyes = pick(EyeForm.ALMOND, EyeForm.ALMOND, EyeForm.CRESCENT),
                    nose = pick(NoseForm.LONG_STRAIGHT, NoseForm.LONG_STRAIGHT, NoseForm.ARCHED),
                    mouth = pick(MouthForm.CLOSED_SMILE, MouthForm.PURSED),
                    cheekMarks = pick(0, 0, 1, 2), ichi = 0,
                    crest = pick(CrestForm.COMBS, CrestForm.COMBS, CrestForm.PLUMES),
                    crestHeight = between(0.45f, 0.95f), crestCount = pick(3, 3, 5),
                    ears = pick(EarForm.NONE, EarForm.SMALL), beard = BeardForm.NONE,
                    palette = pick(*MaskPalettes.lightFaced),
                    ornament = between(0.5f, 1f),
                )
                MaskTradition.MGBEDIKE -> base.copy(
                    face = pick(FaceShape.SQUARE_JAW, FaceShape.ROUND, FaceShape.OVAL),
                    width = between(0.5f, 0.95f),
                    brow = between(0.65f, 1f),
                    eyes = pick(EyeForm.TUBULAR, EyeForm.TUBULAR, EyeForm.ROUND),
                    nose = pick(NoseForm.BROAD, NoseForm.ARCHED),
                    mouth = MouthForm.OPEN_TEETH,
                    ichi = pick(0, 2, 3), cheekMarks = pick(0, 2, 3),
                    crest = CrestForm.HORNS, crestHeight = between(0.5f, 1f),
                    ears = pick(EarForm.NONE, EarForm.SMALL), tusks = r.nextFloat() < 0.25f,
                    beard = pick(BeardForm.NONE, BeardForm.STRIPED, BeardForm.POINTED),
                    palette = pick(*MaskPalettes.darkFaced),
                )
                MaskTradition.OKOROSHI -> base.copy(
                    face = pick(FaceShape.LONG, FaceShape.OVAL, FaceShape.HEART),
                    brow = between(0.2f, 0.7f),
                    eyes = pick(EyeForm.ALMOND, EyeForm.CRESCENT, EyeForm.ROUND),
                    nose = pick(NoseForm.LONG_STRAIGHT, NoseForm.ARCHED),
                    mouth = pick(MouthForm.CLOSED_SMILE, MouthForm.OPEN_TEETH),
                    crest = pick(CrestForm.DISC, CrestForm.COMBS, CrestForm.NONE),
                    crestHeight = between(0.3f, 0.8f), crestCount = pick(1, 3),
                    ears = EarForm.NONE,
                    palette = pick(MaskPalettes.ONYX, MaskPalettes.KAOLIN_INK),
                )
                MaskTradition.ELEPHANT -> base.copy(
                    face = pick(FaceShape.LONG, FaceShape.SQUARE_JAW, FaceShape.OVAL),
                    width = between(0.3f, 0.6f),
                    brow = between(0.4f, 0.9f),
                    eyes = pick(EyeForm.ROUND, EyeForm.TUBULAR, EyeForm.ALMOND),
                    nose = pick(NoseForm.BROAD, NoseForm.LONG_STRAIGHT),
                    mouth = pick(MouthForm.OPEN_TEETH, MouthForm.CLOSED_SMILE),
                    crest = pick(CrestForm.HORNS, CrestForm.COMBS, CrestForm.NONE),
                    crestHeight = between(0.2f, 0.6f), crestCount = pick(1, 3),
                    ears = EarForm.ELEPHANT, tusks = true,
                    palette = pick(MaskPalettes.TERRACOTTA, MaskPalettes.INDIGO_OCHRE, MaskPalettes.EMBER, MaskPalettes.LAPIS),
                )
                MaskTradition.IJELE -> base.copy(
                    face = pick(FaceShape.OVAL, FaceShape.HEART, FaceShape.LONG),
                    width = between(0.3f, 0.6f),
                    brow = between(0.1f, 0.5f),
                    eyes = pick(EyeForm.ALMOND, EyeForm.ROUND, EyeForm.CRESCENT),
                    nose = NoseForm.LONG_STRAIGHT,
                    mouth = pick(MouthForm.CLOSED_SMILE, MouthForm.PURSED),
                    crest = CrestForm.TIERS, crestHeight = between(0.7f, 1f), crestCount = pick(3, 3, 4),
                    ears = EarForm.NONE,
                    palette = pick(MaskPalettes.MBARI, MaskPalettes.JADE_CORAL, MaskPalettes.PLUM_SAFFRON, MaskPalettes.KAOLIN_VERMILION),
                    height = r.nextInt(56, 73),
                )
                MaskTradition.IKENGA -> base.copy(
                    face = pick(FaceShape.SQUARE_JAW, FaceShape.OVAL, FaceShape.LONG),
                    brow = between(0.5f, 0.9f),
                    eyes = pick(EyeForm.ALMOND, EyeForm.ROUND, EyeForm.TUBULAR),
                    nose = pick(NoseForm.LONG_STRAIGHT, NoseForm.ARCHED),
                    mouth = pick(MouthForm.CLOSED_SMILE, MouthForm.OPEN_TEETH),
                    ichi = r.nextInt(2, 6), cheekMarks = pick(0, 1),
                    crest = CrestForm.HORNS, crestHeight = between(0.6f, 1f),
                    ears = pick(EarForm.NONE, EarForm.SMALL),
                    beard = pick(BeardForm.NONE, BeardForm.POINTED, BeardForm.STRIPED),
                    palette = pick(MaskPalettes.LAPIS, MaskPalettes.ONYX, MaskPalettes.EMBER, MaskPalettes.INDIGO_OCHRE),
                )
                MaskTradition.MBARI -> base.copy(
                    face = pick(FaceShape.ROUND, FaceShape.OVAL, FaceShape.HEART),
                    width = between(0.45f, 0.9f),
                    brow = between(0.3f, 0.7f),
                    eyes = pick(EyeForm.ROUND, EyeForm.ROUND, EyeForm.ALMOND),
                    nose = pick(NoseForm.BROAD, NoseForm.LONG_STRAIGHT),
                    mouth = pick(MouthForm.CLOSED_SMILE, MouthForm.OPEN_TEETH, MouthForm.PURSED),
                    cheekMarks = pick(0, 2),
                    crest = pick(CrestForm.DISC, CrestForm.PLUMES, CrestForm.PLUMES),
                    crestHeight = between(0.5f, 1f), crestCount = pick(5, 7, 9),
                    ears = pick(EarForm.NONE, EarForm.SMALL),
                    palette = pick(MaskPalettes.MBARI, MaskPalettes.JADE_CORAL, MaskPalettes.ROSE_MIDNIGHT, MaskPalettes.TERRACOTTA),
                )
            }
            return g.normalised()
        }

        /**
         * Ten masks tuned by hand to look their best: the models a new player
         * finds in the library, and the studio's preset chips. Each one shows
         * a different tradition or crest, and each a different palette, so the
         * set reads as a collection rather than ten variations of one.
         */
        val presets: List<MaskGenome> = listOf(
            MaskGenome(
                name = "Agbogho Mmuo", tradition = MaskTradition.MAIDEN, face = FaceShape.OVAL, width = 0.35f, height = 64,
                brow = 0.15f, eyes = EyeForm.ALMOND, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
                cheekMarks = 0, crest = CrestForm.COMBS, crestHeight = 0.8f, crestCount = 3, ears = EarForm.NONE,
                palette = MaskPalettes.KAOLIN_VERMILION, ornament = 0.85f, relief = 0.55f, features = 0.45f,
            ),
            MaskGenome(
                name = "Saffron Maiden", tradition = MaskTradition.MAIDEN, face = FaceShape.HEART, width = 0.4f, height = 60,
                brow = 0.1f, eyes = EyeForm.CRESCENT, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.PURSED,
                cheekMarks = 1, crest = CrestForm.COMBS, crestHeight = 0.7f, crestCount = 5, ears = EarForm.SMALL,
                palette = MaskPalettes.PLUM_SAFFRON, ornament = 0.7f, relief = 0.5f, features = 0.5f,
            ),
            MaskGenome(
                name = "Mgbedike", tradition = MaskTradition.MGBEDIKE, face = FaceShape.SQUARE_JAW, width = 0.8f, height = 60,
                brow = 0.95f, eyes = EyeForm.TUBULAR, nose = NoseForm.BROAD, mouth = MouthForm.OPEN_TEETH,
                ichi = 0, cheekMarks = 3, crest = CrestForm.HORNS, crestHeight = 0.85f, ears = EarForm.SMALL,
                beard = BeardForm.STRIPED, palette = MaskPalettes.EMBER, ornament = 0.5f, relief = 0.8f, features = 0.5f,
            ),
            MaskGenome(
                name = "Okoroshi Oma", tradition = MaskTradition.OKOROSHI, face = FaceShape.LONG, width = 0.3f, height = 56,
                brow = 0.4f, eyes = EyeForm.ALMOND, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
                crest = CrestForm.DISC, crestHeight = 0.55f, ears = EarForm.NONE,
                palette = MaskPalettes.KAOLIN_INK, ornament = 0.6f, relief = 0.5f, features = 0.55f,
            ),
            MaskGenome(
                name = "Okoroshi Ojo", tradition = MaskTradition.OKOROSHI, face = FaceShape.OVAL, width = 0.45f, height = 52,
                brow = 0.7f, eyes = EyeForm.ROUND, nose = NoseForm.ARCHED, mouth = MouthForm.OPEN_TEETH,
                cheekMarks = 2, crest = CrestForm.NONE, ears = EarForm.SMALL,
                palette = MaskPalettes.ONYX, ornament = 0.75f, relief = 0.65f, features = 0.5f,
            ),
            MaskGenome(
                name = "Ogbodo Enyi", tradition = MaskTradition.ELEPHANT, face = FaceShape.LONG, width = 0.45f, height = 56,
                brow = 0.6f, eyes = EyeForm.ROUND, nose = NoseForm.BROAD, mouth = MouthForm.OPEN_TEETH,
                crest = CrestForm.HORNS, crestHeight = 0.3f, ears = EarForm.ELEPHANT, tusks = true,
                palette = MaskPalettes.TERRACOTTA, ornament = 0.6f, relief = 0.6f, features = 0.55f,
            ),
            MaskGenome(
                name = "Ijele", tradition = MaskTradition.IJELE, face = FaceShape.OVAL, width = 0.4f, height = 72,
                brow = 0.25f, eyes = EyeForm.ALMOND, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
                crest = CrestForm.TIERS, crestHeight = 1f, crestCount = 3, ears = EarForm.NONE,
                palette = MaskPalettes.MBARI, ornament = 0.8f, relief = 0.5f, features = 0.5f,
            ),
            MaskGenome(
                name = "Ikenga", tradition = MaskTradition.IKENGA, face = FaceShape.SQUARE_JAW, width = 0.5f, height = 64,
                brow = 0.75f, eyes = EyeForm.ALMOND, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
                ichi = 4, crest = CrestForm.HORNS, crestHeight = 1f, ears = EarForm.NONE, beard = BeardForm.POINTED,
                palette = MaskPalettes.LAPIS, ornament = 0.4f, relief = 0.6f, features = 0.5f,
            ),
            MaskGenome(
                name = "Mbari Sun", tradition = MaskTradition.MBARI, face = FaceShape.ROUND, width = 0.7f, height = 56,
                brow = 0.4f, eyes = EyeForm.ROUND, nose = NoseForm.BROAD, mouth = MouthForm.PURSED,
                cheekMarks = 2, crest = CrestForm.PLUMES, crestHeight = 0.75f, crestCount = 9, ears = EarForm.NONE,
                palette = MaskPalettes.MBARI, ornament = 0.6f, relief = 0.55f, features = 0.5f,
            ),
            MaskGenome(
                name = "Jade Plume", tradition = MaskTradition.MAIDEN, face = FaceShape.HEART, width = 0.35f, height = 64,
                brow = 0.2f, eyes = EyeForm.ALMOND, nose = NoseForm.ARCHED, mouth = MouthForm.CLOSED_SMILE,
                crest = CrestForm.PLUMES, crestHeight = 0.9f, crestCount = 7, ears = EarForm.NONE,
                palette = MaskPalettes.JADE_CORAL, ornament = 0.75f, relief = 0.5f, features = 0.5f,
            ),
            MaskGenome(
                name = "Indigo Night", tradition = MaskTradition.MAIDEN, face = FaceShape.OVAL, width = 0.4f, height = 60,
                brow = 0.3f, eyes = EyeForm.CRESCENT, nose = NoseForm.LONG_STRAIGHT, mouth = MouthForm.CLOSED_SMILE,
                cheekMarks = 0, crest = CrestForm.DISC, crestHeight = 0.7f, ears = EarForm.SMALL,
                palette = MaskPalettes.INDIGO_OCHRE, ornament = 0.9f, relief = 0.5f, features = 0.5f,
            ),
        )
    }
}
