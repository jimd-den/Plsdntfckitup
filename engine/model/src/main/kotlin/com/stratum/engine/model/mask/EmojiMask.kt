package com.stratum.engine.model.mask

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Igbo masks as emoji: a character rig in glossy 2D vector art that turns
 * like a 3D head.
 *
 * A mask is two things kept apart:
 *  - its [Look]: what makes it this mask. Face shape and pigment; the
 *    lacquered coiffure and its hairline, braids and cowries; a crest
 *    (combs, lobes, ridges, knots, a cone, horns, tiers, plumes); the eye,
 *    brow, nose and mouth styles; ichi and temple marks, a forehead star,
 *    keloids; paint (split face, eye band, uli spirals, painted lids and
 *    lips); ears, earrings, a beard.
 *  - its [Face]: what it feels right now. Continuous controls -- each eye's
 *    opening, the squint of a smile, where it looks, the brows' raise and
 *    tilt, the mouth's opening, smile and width, teeth, tongue, blush, tears,
 *    sweat, sleep, hearts, anger, sparkle -- so any two expressions blend
 *    ([blend]) and any mask makes every expression ([expressions]).
 *
 * Everything is one shaded volume, as an emoji is. The head, coiffure, nose
 * and crest are lit as rounded glossy forms. Eyes, brows, mouth, marks and
 * paint are placed on the curved surface, so they wrap round it as the head
 * turns, while the nose and crest stand off it and shift with the turn.
 *
 * [draw] paints onto a [VectorCanvas], the same pixels on a phone and in the
 * preview tools; [image] is the one-call version.
 */
object EmojiMask {

    enum class FaceShape(val label: String) { OVAL("Oval"), ROUND("Round"), LONG("Long"), HEART("Heart") }
    enum class Hairline(val label: String) { PEAK("Peaked"), HIGH("High dome"), SCALLOP("Scalloped"), LOW("Low"), BALD("None") }
    enum class Crest(val label: String) {
        NONE("None"), COMBS("Combs"), LOBES("Lobes"), RIDGES("Ridges"), KNOTS("Knots"), CONE("Cone"), HORNS("Horns"), TIERS("Tiers"), PLUMES("Plumes"),
    }
    enum class EyeStyle(val label: String) { SLIT("Heavy-lidded slits"), ALMOND("Almond"), ROUND("Round spirit eyes"), TUBE("Mgbedike tubes"), BEAN("Coffee-bean lids") }
    enum class BrowStyle(val label: String) { ARCH("Arched"), RIDGE("Heavy ridge"), THIN("Thin"), NONE("None") }
    enum class NoseStyle(val label: String) { LONG("Long"), BROAD("Broad"), BUTTON("Button"), NONE("None") }
    enum class MouthStyle(val label: String) { SMALL("Small"), TEETH("Bared teeth"), POUT("Pout"), WIDE("Wide") }
    enum class Ears(val label: String) { NONE("None"), SMALL("Small"), ELEPHANT("Elephant") }
    enum class Mark(val label: String) { ICHI("Ichi lines"), TEMPLE("Temple bars"), CHEEK("Cheek cuts"), STAR("Forehead star"), LINE("Centre line"), KELOID("Keloids") }
    /** What the mask is made of and how it is finished. */
    enum class Finish(val label: String) { CARVED("Carved wood"), PAINTED("Painted wood"), BRASS("Cast brass"), GLOSS("Glossy") }
    enum class Paint(val label: String) { HEART("Heart-shaped face"), SPLIT("Split face"), EYE_BAND("Eye band"), SPIRALS("Uli spirals"), CHIN("Painted chin"), LIDS("Painted lids"), LIPS("Painted lips") }

    /** What makes a mask this mask. Colours are [AfricanMaskArt.PIGMENTS] indices. */
    data class Look(
        val name: String = "Mask",
        val shape: FaceShape = FaceShape.OVAL,
        /** 0 narrow .. 1 wide. */
        val width: Float = 0.5f,
        val ground: Int = KAOLIN,
        val hair: Int = LAMPBLACK,
        val accent: Int = VERMILION,
        val accent2: Int = LAPIS,
        val crestColour: Int = LAMPBLACK,
        val hairline: Hairline = Hairline.HIGH,
        val braids: Boolean = true,
        val cowries: Boolean = false,
        val crest: Crest = Crest.COMBS,
        val crestCount: Int = 3,
        /** 0 small .. 1 tall. */
        val crestSize: Float = 0.5f,
        val eyes: EyeStyle = EyeStyle.BEAN,
        val brows: BrowStyle = BrowStyle.ARCH,
        val nose: NoseStyle = NoseStyle.LONG,
        val mouth: MouthStyle = MouthStyle.SMALL,
        val ears: Ears = Ears.NONE,
        val marks: Set<Mark> = setOf(Mark.LINE),
        val paint: Set<Paint> = emptySet(),
        val earrings: Boolean = false,
        val beard: Boolean = false,
        val finish: Finish = Finish.PAINTED,
        /** The wood under the paint, for a painted mask. */
        val wood: Int = BLACKWOOD,
    )

    enum class EyeKind { NORMAL, HEART, STAR, X, SPIRAL }
    enum class MouthKind { NORMAL, O, KISS }

    /** What a mask feels: every control continuous, so faces blend. */
    data class Face(
        val eyeOpenL: Float = 0.4f,
        val eyeOpenR: Float = 0.4f,
        /** 0 plain .. 1 the squint of a smile: the lower lid rises into ^. */
        val eyeSmile: Float = 0.1f,
        val eyeKind: EyeKind = EyeKind.NORMAL,
        val lookX: Float = 0f,
        val lookY: Float = 0f,
        /** -1 down .. 1 up. */
        val browRaise: Float = 0f,
        /** -1 worried (inner ends up) .. 1 angry (inner ends down). */
        val browTilt: Float = 0f,
        /** One brow higher than the other: a sceptic's. */
        val browAsym: Float = 0f,
        val mouthOpen: Float = 0f,
        /** -1 frown .. 1 smile. */
        val mouthSmile: Float = 0.15f,
        val mouthWide: Float = 0.4f,
        /** The mouth pulled to one side. */
        val mouthSide: Float = 0f,
        val mouthKind: MouthKind = MouthKind.NORMAL,
        val teeth: Float = 0f,
        /** 0 none, 0.5 in the mouth, 1 stuck out. */
        val tongue: Float = 0f,
        val blush: Float = 0f,
        val tears: Float = 0f,
        val sweat: Float = 0f,
        val zzz: Float = 0f,
        val hearts: Float = 0f,
        val anger: Float = 0f,
        val sparkle: Float = 0f,
        /** Head roll, radians. */
        val tilt: Float = 0f,
        /** How much it bounces. */
        val bounce: Float = 0f,
    )

    /** Twenty feelings, by name, in the order a picker shows them. */
    val expressions: Map<String, Face> = linkedMapOf(
        "Serene" to Face(),
        "Smile" to Face(eyeOpenL = 0.45f, eyeOpenR = 0.45f, eyeSmile = 0.55f, mouthSmile = 0.85f, blush = 0.35f),
        "Grin" to Face(eyeOpenL = 0.55f, eyeOpenR = 0.55f, eyeSmile = 0.35f, mouthOpen = 0.32f, mouthSmile = 0.9f, mouthWide = 0.95f, teeth = 1f, blush = 0.2f),
        "Laugh" to Face(eyeOpenL = 0f, eyeOpenR = 0f, eyeSmile = 1f, mouthOpen = 0.85f, mouthSmile = 1f, mouthWide = 0.9f, teeth = 1f, tongue = 0.5f, blush = 0.45f, bounce = 1f),
        "Joy" to Face(eyeOpenL = 0f, eyeOpenR = 0f, eyeSmile = 1f, mouthOpen = 0.8f, mouthSmile = 1f, mouthWide = 0.95f, teeth = 1f, tongue = 0.5f, tears = 0.5f, tilt = 0.18f, bounce = 1f),
        "Wink" to Face(eyeOpenL = 0.55f, eyeOpenR = 0f, eyeSmile = 0.7f, mouthSmile = 0.85f, mouthOpen = 0.1f, mouthSide = 0.3f, blush = 0.25f),
        "Cheeky" to Face(eyeOpenL = 0.6f, eyeOpenR = 0f, eyeSmile = 0.7f, mouthOpen = 0.3f, mouthSmile = 0.7f, tongue = 1f, tilt = -0.12f),
        "Love" to Face(eyeKind = EyeKind.HEART, mouthOpen = 0.3f, mouthSmile = 1f, mouthWide = 0.8f, teeth = 0.6f, blush = 0.5f),
        "Adore" to Face(eyeOpenL = 0f, eyeOpenR = 0f, eyeSmile = 1f, mouthSmile = 0.95f, blush = 0.8f, hearts = 1f, tilt = 0.1f),
        "Starstruck" to Face(eyeKind = EyeKind.STAR, mouthOpen = 0.65f, mouthSmile = 1f, mouthWide = 0.9f, teeth = 1f, sparkle = 1f),
        "Kiss" to Face(eyeOpenL = 0.5f, eyeOpenR = 0f, eyeSmile = 0.6f, mouthKind = MouthKind.KISS, blush = 0.5f, hearts = 0.4f),
        "Surprise" to Face(eyeOpenL = 1.3f, eyeOpenR = 1.3f, eyeSmile = 0f, browRaise = 1f, mouthKind = MouthKind.O, mouthOpen = 0.8f),
        "Nervous" to Face(eyeOpenL = 0.7f, eyeOpenR = 0.7f, eyeSmile = 0.1f, browRaise = 0.5f, browTilt = -0.7f, mouthOpen = 0.22f, mouthSmile = 0.45f, mouthWide = 0.9f, teeth = 1f, sweat = 1f),
        "Angry" to Face(eyeOpenL = 0.6f, eyeOpenR = 0.6f, eyeSmile = 0f, browRaise = -0.7f, browTilt = 1f, mouthOpen = 0.25f, mouthSmile = -0.8f, mouthWide = 0.8f, teeth = 1f, anger = 1f, blush = 0.5f),
        "Sad" to Face(eyeOpenL = 0.5f, eyeOpenR = 0.5f, eyeSmile = 0f, lookY = -0.5f, browRaise = 0.3f, browTilt = -1f, mouthSmile = -0.75f, tears = 0.35f),
        "Crying" to Face(eyeOpenL = 0f, eyeOpenR = 0f, eyeSmile = 0f, browRaise = 0.4f, browTilt = -1f, mouthOpen = 0.75f, mouthSmile = -0.8f, mouthWide = 0.8f, tears = 1f),
        "Sleepy" to Face(eyeOpenL = 0f, eyeOpenR = 0f, eyeSmile = 0f, mouthKind = MouthKind.O, mouthOpen = 0.15f, zzz = 1f, tilt = 0.15f),
        "Dizzy" to Face(eyeKind = EyeKind.SPIRAL, mouthKind = MouthKind.O, mouthOpen = 0.35f, tilt = -0.1f),
        "Knocked out" to Face(eyeKind = EyeKind.X, mouthKind = MouthKind.O, mouthOpen = 0.45f, tongue = 1f),
        "Smug" to Face(eyeOpenL = 0.32f, eyeOpenR = 0.32f, eyeSmile = 0.25f, lookX = 0.6f, browAsym = 0.6f, mouthSmile = 0.55f, mouthSide = 0.6f),
    )

    /** [a] toward [b] by [t]: every control in between, kinds switching halfway. */
    fun blend(a: Face, b: Face, t: Float): Face {
        fun l(x: Float, y: Float) = x + (y - x) * t
        return Face(
            eyeOpenL = l(a.eyeOpenL, b.eyeOpenL), eyeOpenR = l(a.eyeOpenR, b.eyeOpenR), eyeSmile = l(a.eyeSmile, b.eyeSmile),
            eyeKind = if (t < 0.5f) a.eyeKind else b.eyeKind, lookX = l(a.lookX, b.lookX), lookY = l(a.lookY, b.lookY),
            browRaise = l(a.browRaise, b.browRaise), browTilt = l(a.browTilt, b.browTilt), browAsym = l(a.browAsym, b.browAsym),
            mouthOpen = l(a.mouthOpen, b.mouthOpen), mouthSmile = l(a.mouthSmile, b.mouthSmile), mouthWide = l(a.mouthWide, b.mouthWide),
            mouthSide = l(a.mouthSide, b.mouthSide), mouthKind = if (t < 0.5f) a.mouthKind else b.mouthKind,
            teeth = l(a.teeth, b.teeth), tongue = l(a.tongue, b.tongue), blush = l(a.blush, b.blush), tears = l(a.tears, b.tears),
            sweat = l(a.sweat, b.sweat), zzz = l(a.zzz, b.zzz), hearts = l(a.hearts, b.hearts), anger = l(a.anger, b.anger),
            sparkle = l(a.sparkle, b.sparkle), tilt = l(a.tilt, b.tilt), bounce = l(a.bounce, b.bounce),
        )
    }

    // ---- Pigments ------------------------------------------------------------------

    const val KAOLIN = 0; const val BONE = 1; const val OCHRE = 2; const val GOLD = 3; const val CAMWOOD = 4; const val VERMILION = 5
    const val TERRACOTTA = 6; const val UMBER = 7; const val CHARCOAL = 8; const val LAMPBLACK = 9; const val INDIGO = 10; const val LAPIS = 11
    const val JADE = 12; const val MALACHITE = 13; const val BRASS = 14; const val CORAL = 15
    const val IROKO = 16; const val BLACKWOOD = 17; const val MAHOGANY = 18

    fun pigment(i: Int): Int = AfricanMaskArt.PIGMENTS[Math.floorMod(i, AfricanMaskArt.PIGMENTS.size)].rgb

    // ---- Masks from the carvers ----------------------------------------------------------

    /** Masks after the carvers' own: maidens, spirits, the brave. */
    val presets: List<Look> = listOf(
        Look("Agbogho Mmuo", shape = FaceShape.LONG, width = 0.4f, crestColour = BLACKWOOD, crestCount = 3, crestSize = 0.6f, hairline = Hairline.HIGH,
            marks = setOf(Mark.LINE, Mark.STAR, Mark.TEMPLE), paint = setOf(Paint.LIDS)),
        Look("Fang Ngil", finish = Finish.CARVED, shape = FaceShape.LONG, ground = BLACKWOOD, wood = BLACKWOOD, hair = BLACKWOOD, accent = KAOLIN, width = 0.3f,
            hairline = Hairline.BALD, crest = Crest.RIDGES, crestCount = 1, crestSize = 0.7f, crestColour = BLACKWOOD, eyes = EyeStyle.SLIT, brows = BrowStyle.NONE,
            paint = setOf(Paint.HEART), marks = setOf(Mark.LINE)),
        Look("Okoroshi Ojo", finish = Finish.CARVED, ground = BLACKWOOD, wood = BLACKWOOD, hair = LAMPBLACK, accent = KAOLIN, accent2 = GOLD, hairline = Hairline.LOW,
            crest = Crest.NONE, eyes = EyeStyle.ROUND, brows = BrowStyle.RIDGE, nose = NoseStyle.BROAD, mouth = MouthStyle.TEETH, marks = setOf(Mark.ICHI), width = 0.7f),
        Look("Mgbedike", finish = Finish.CARVED, shape = FaceShape.ROUND, ground = MAHOGANY, wood = MAHOGANY, hair = BLACKWOOD, accent = KAOLIN, accent2 = GOLD, crestColour = BONE,
            hairline = Hairline.BALD, crest = Crest.HORNS, crestSize = 0.8f, eyes = EyeStyle.TUBE, brows = BrowStyle.RIDGE, nose = NoseStyle.BROAD, mouth = MouthStyle.TEETH,
            ears = Ears.SMALL, marks = setOf(Mark.CHEEK), beard = true, width = 0.8f),
        Look("Beaded Maiden", wood = IROKO, accent = VERMILION, accent2 = INDIGO, crest = Crest.RIDGES, crestCount = 1, crestSize = 0.9f, crestColour = INDIGO, cowries = true,
            brows = BrowStyle.THIN, paint = setOf(Paint.SPLIT), marks = emptySet(), width = 0.35f, shape = FaceShape.LONG, hairline = Hairline.HIGH),
        Look("Conical Crest", wood = UMBER, crest = Crest.CONE, crestColour = IROKO, accent = OCHRE, accent2 = VERMILION, hairline = Hairline.HIGH,
            marks = setOf(Mark.LINE, Mark.KELOID), paint = setOf(Paint.LIDS)),
        Look("Afikpo Elder", finish = Finish.CARVED, shape = FaceShape.LONG, ground = IROKO, wood = IROKO, hair = BLACKWOOD, accent = VERMILION, accent2 = KAOLIN,
            hairline = Hairline.BALD, crest = Crest.NONE, paint = setOf(Paint.EYE_BAND), marks = setOf(Mark.ICHI), width = 0.25f, brows = BrowStyle.THIN),
        Look("Ijele Crown", ground = OCHRE, wood = MAHOGANY, accent = VERMILION, accent2 = JADE, crestColour = JADE, crest = Crest.TIERS, hairline = Hairline.HIGH,
            marks = setOf(Mark.ICHI), cowries = true),
        Look("Knot Maiden", hair = INDIGO, crest = Crest.KNOTS, crestColour = INDIGO, crestCount = 3, eyes = EyeStyle.BEAN,
            marks = setOf(Mark.TEMPLE, Mark.KELOID), paint = setOf(Paint.SPIRALS), earrings = true, accent = CORAL, shape = FaceShape.HEART, hairline = Hairline.HIGH),
        Look("Elephant Spirit", ground = TERRACOTTA, wood = BLACKWOOD, hair = LAMPBLACK, hairline = Hairline.LOW, crest = Crest.KNOTS, crestCount = 1, crestColour = BRASS,
            eyes = EyeStyle.ROUND, mouth = MouthStyle.TEETH, ears = Ears.ELEPHANT, accent = KAOLIN, marks = setOf(Mark.ICHI), shape = FaceShape.ROUND, width = 0.8f),
        Look("Benin Queen", finish = Finish.BRASS, ground = BRASS, wood = BRASS, hair = BRASS, crest = Crest.CONE, crestColour = BRASS, crestSize = 0.9f, hairline = Hairline.HIGH,
            braids = false, cowries = true, brows = BrowStyle.THIN, accent = CORAL, accent2 = CORAL, marks = setOf(Mark.TEMPLE), earrings = true,
            shape = FaceShape.LONG, width = 0.45f),
        Look("Camwood Maiden", finish = Finish.CARVED, ground = CAMWOOD, wood = CAMWOOD, hair = BLACKWOOD, crest = Crest.LOBES, crestCount = 3, crestColour = BLACKWOOD,
            accent = KAOLIN, accent2 = GOLD, paint = setOf(Paint.SPIRALS), marks = setOf(Mark.STAR), cowries = true),
        Look("Brass Warrior", finish = Finish.BRASS, ground = BRASS, wood = BRASS, hair = BRASS, crest = Crest.HORNS, crestColour = BRASS, crestSize = 0.6f, hairline = Hairline.BALD,
            eyes = EyeStyle.TUBE, brows = BrowStyle.RIDGE, nose = NoseStyle.BROAD, mouth = MouthStyle.TEETH, marks = setOf(Mark.CHEEK), beard = true, shape = FaceShape.ROUND, width = 0.7f,
            accent = CORAL),
    )

    /** A mask from [seed]: the same seed, the same mask. */
    fun generate(seed: Long, name: String = "Mask"): Look {
        val r = Random(seed * 0x5DEECE66DL + 11)
        fun <T> one(xs: List<T>): T = xs[r.nextInt(xs.size)]
        fun <T> some(xs: List<T>, most: Int): Set<T> = xs.shuffled(r).take(r.nextInt(0, most + 1)).toSet()
        val finish = one(listOf(Finish.CARVED, Finish.CARVED, Finish.PAINTED, Finish.PAINTED, Finish.PAINTED, Finish.BRASS))
        val wood = one(listOf(IROKO, BLACKWOOD, MAHOGANY, UMBER, CAMWOOD))
        val ground = when (finish) {
            Finish.CARVED -> wood
            Finish.BRASS -> BRASS
            else -> one(listOf(KAOLIN, KAOLIN, KAOLIN, BONE, OCHRE, CAMWOOD, TERRACOTTA, INDIGO))
        }
        val dark = ground in setOf(CAMWOOD, UMBER, CHARCOAL, INDIGO)
        val eyes = one(listOf(EyeStyle.BEAN, EyeStyle.BEAN, EyeStyle.BEAN, EyeStyle.TUBE, EyeStyle.TUBE, EyeStyle.ROUND, EyeStyle.ROUND, EyeStyle.SLIT, EyeStyle.ALMOND))
        return Look(
            name = name,
            shape = one(FaceShape.entries),
            width = 0.2f + r.nextFloat() * 0.6f,
            ground = ground,
            hair = if (finish == Finish.BRASS) BRASS else one(listOf(LAMPBLACK, BLACKWOOD, BLACKWOOD, INDIGO, wood)),
            accent = one(listOf(VERMILION, CAMWOOD, OCHRE, GOLD, CORAL, KAOLIN).filter { it != ground }),
            accent2 = one(listOf(LAPIS, INDIGO, JADE, MALACHITE, GOLD, KAOLIN, VERMILION).filter { it != ground }),
            crestColour = if (finish == Finish.BRASS) BRASS else one(listOf(BLACKWOOD, BLACKWOOD, wood, IROKO, BONE, BRASS, INDIGO, VERMILION)),
            hairline = one(listOf(Hairline.HIGH, Hairline.HIGH, Hairline.HIGH, Hairline.BALD, Hairline.BALD, Hairline.SCALLOP, Hairline.PEAK)),
            braids = r.nextFloat() < 0.6f,
            cowries = r.nextFloat() < 0.35f,
            crest = one(Crest.entries),
            crestCount = 1 + r.nextInt(6),
            crestSize = r.nextFloat(),
            eyes = eyes,
            brows = if (eyes == EyeStyle.TUBE) BrowStyle.RIDGE else one(BrowStyle.entries),
            nose = one(listOf(NoseStyle.LONG, NoseStyle.LONG, NoseStyle.BROAD, NoseStyle.BUTTON, NoseStyle.NONE)),
            mouth = if (eyes == EyeStyle.TUBE || eyes == EyeStyle.ROUND) one(listOf(MouthStyle.TEETH, MouthStyle.WIDE)) else one(MouthStyle.entries),
            ears = one(listOf(Ears.NONE, Ears.NONE, Ears.NONE, Ears.SMALL, Ears.ELEPHANT)),
            marks = some(Mark.entries, 3),
            paint = some(Paint.entries - Paint.LIPS, 2).let { if (dark) it - Paint.LIDS else it },
            earrings = r.nextFloat() < 0.25f,
            beard = r.nextFloat() < 0.15f,
            finish = finish,
            wood = wood,
        )
    }

    // ---- Drawing -------------------------------------------------------------------

    /** The logical square [draw] fills: x from -[HALF] to [HALF], y from [BOTTOM]. */
    const val HALF = 1.7f
    const val BOTTOM = -1.75f

    /** [look] feeling [face], turned by [turn] radians, at [time] seconds, as [size]-pixel ARGB pixels. */
    fun image(look: Look, face: Face, size: Int, turn: Float = 0f, time: Float = 0f, blink: Boolean = false): IntArray {
        val canvas = VectorCanvas(size, size, -HALF, BOTTOM, HALF * 2, HALF * 2)
        draw(canvas, look, face, turn, time, blink)
        return canvas.pixels()
    }

    /** Paints [look] feeling [face] onto [canvas], turned by [turn] radians (about -0.8..0.8 reads best), at [time] seconds. */
    fun draw(canvas: VectorCanvas, look: Look, face: Face, turn: Float = 0f, time: Float = 0f, blink: Boolean = false) =
        Rig(canvas, look, face, turn.coerceIn(-1.1f, 1.1f), time, blink).draw()

    /** A glossy form's colours: lit, its own, in shade; how shiny; the warm light bounced up from below. */
    private class Material(val light: Int, val base: Int, val shade: Int, val spec: Float, val bounce: Int)

    private fun material(rgb: Int, spec: Float = 0.45f): Material {
        val l = luma(rgb)
        return Material(
            light = mix(rgb, c(0xFFF8EE), 0.3f + 0.2f * (1f - l)),
            base = rgb,
            shade = mix(rgb, c(0x2A1830), 0.38f + 0.1f * l),
            spec = spec,
            bounce = mix(rgb, c(0xFFA070), 0.4f),
        )
    }

    private class Rig(val cv: VectorCanvas, val look: Look, val f: Face, val yaw: Float, val t: Float, blink: Boolean) {
        // ---- the head's form -------------------------------------------------------
        val rx = (0.74f + 0.16f * look.width) * when (look.shape) { FaceShape.ROUND -> 1.08f; FaceShape.LONG -> 0.88f; FaceShape.HEART -> 1.02f; FaceShape.OVAL -> 1f }
        val ry = when (look.shape) { FaceShape.LONG -> 1.1f; FaceShape.ROUND -> 0.95f; else -> 1f }
        val headY = -0.2f + 0.018f * sin(t * 1.7f)
        val squash = f.bounce * 0.045f * sin(t * 9f)
        val roll = f.tilt + 0.025f * sin(t * 0.9f)
        val cr = cos(roll); val sr = sin(roll)
        val sx = 1f + squash; val sy = 1f - squash
        val blinkK = if (blink && ((t + 0.7f) % 3.4f) < 0.13f) 0.05f else 1f

        val wooden = look.finish == Finish.CARVED || look.finish == Finish.PAINTED
        val metal = look.finish == Finish.BRASS
        val gloss = look.finish == Finish.GLOSS
        /** How shiny a surface of this mask is: waxed wood is satin, chalk is matte, brass gleams. */
        val sheen = when (look.finish) { Finish.CARVED -> 0.2f; Finish.PAINTED -> 0.12f; Finish.BRASS -> 0.95f; Finish.GLOSS -> 0.42f }
        val faceMat = moody(material(pigment(look.ground), sheen))
        val woodMat = moody(material(pigment(look.wood), 0.22f))
        val hairMat = moody(material(pigment(look.hair), if (gloss) 0.75f else if (metal) 0.9f else 0.35f))
        val crestMat = moody(material(pigment(look.crestColour), if (gloss) 0.6f else if (metal) 0.9f else 0.3f))

        /** Deeper, cooler shadows and warmer light, as a painter lights a figure; the glossy emoji keeps its own. */
        fun moody(m: Material): Material = if (gloss) m else Material(
            light = mix(m.base, c(0xFFE6BE), 0.34f), base = m.base, shade = mix(m.base, c(0x160C14), 0.58f), spec = m.spec, bounce = mix(m.base, c(0xD8764A), 0.35f),
        )
        val accentMat = material(pigment(look.accent), 0.5f)
        val accent2Mat = material(pigment(look.accent2), 0.45f)
        val ink = mix(pigment(look.hair), c(0x140C0C), 0.5f)
        val carved = mix(pigment(look.ground), c(0x1E1212), if (luma(pigment(look.ground)) > 0.5f) 0.62f else 0.3f)
        /** A dark mask draws its lines in kaolin, as the carvers paint round its eyes and mouth. */
        val darkGround = luma(pigment(look.ground)) < 0.3f
        val eyeLine = if (darkGround) c(0xF1E8DA) else EYE_DARK
        val mouthLine = if (darkGround) c(0xF1E8DA) else MOUTH_INK

        /** On a dark mask, a kaolin rim round a dark opening so it reads. */
        fun rim(shape: FloatArray) { if (darkGround) cv.stroke(shape, 0.035f, c(0xF1E8DA), closed = true) }

        /** Half the head's width at height [v] (-1 chin .. 1 crown), as a fraction of [rx]. */
        fun w(v: Float): Float {
            val a = abs(v).coerceAtMost(1f)
            return when (look.shape) {
                FaceShape.ROUND -> (1f - a.pow(2.4f)).coerceAtLeast(0f).pow(1f / 2.4f)
                FaceShape.HEART -> sqrt(1f - a * a) * (if (v < 0f) 1f - 0.3f * a.pow(1.6f) else 1f)
                else -> sqrt(1f - a * a)
            }
        }

        /** Relative head coordinates to the canvas: roll, squash, then place. */
        fun tx(x: Float, y: Float): Float = (x * cr - y * sr) * sx
        fun ty(x: Float, y: Float): Float = (x * sr + y * cr) * sy + headY

        /** A point [lift] off the head at world longitude [th] and height [v], into [out] at [o]. */
        fun ang(th: Float, v: Float, lift: Float, out: FloatArray, o: Int) {
            val tp = (th + yaw).coerceIn(-HALF_PI, HALF_PI)
            val x = (rx * w(v) + lift) * sin(tp)
            val y = ry * v + lift * 0.2f * v
            out[o] = tx(x, y); out[o + 1] = ty(x, y)
        }

        /** Face point (u across in fractions of the head's half width, v up) to its longitude. */
        fun theta(u: Float, v: Float): Float = asin((u / w(v).coerceAtLeast(1e-3f)).coerceIn(-1f, 1f))

        fun facing(u: Float, v: Float): Float = cos(theta(u, v) + yaw)

        /** Face points (u, v pairs) laid on the head, [lift] off it. */
        fun onFace(uv: FloatArray, lift: Float = 0f): FloatArray {
            val out = FloatArray(uv.size)
            for (i in 0 until uv.size / 2) ang(theta(uv[i * 2], uv[i * 2 + 1]), uv[i * 2 + 1], lift, out, i * 2)
            return out
        }

        fun onFaceLift(uvl: FloatArray): FloatArray {
            val n = uvl.size / 3
            val out = FloatArray(n * 2)
            for (i in 0 until n) ang(theta(uvl[i * 3], uvl[i * 3 + 1]), uvl[i * 3 + 1], uvl[i * 3 + 2], out, i * 2)
            return out
        }

        fun point(u: Float, v: Float, lift: Float = 0f): FloatArray = FloatArray(2).also { ang(theta(u, v), v, lift, it, 0) }

        /** A thing in the head's own space -- x across, y up, z toward the eye -- turned with it. */
        fun world(x: Float, y: Float, z: Float): FloatArray {
            val X = x * cos(yaw) + z * sin(yaw)
            return floatArrayOf(tx(X, y), ty(X, y))
        }

        fun worldPoly(pts: FloatArray, z: Float): FloatArray {
            val out = FloatArray(pts.size)
            val cy = cos(yaw); val syw = sin(yaw)
            for (i in 0 until pts.size / 2) {
                val X = pts[i * 2] * cy + z * syw; val y = pts[i * 2 + 1]
                out[i * 2] = tx(X, y); out[i * 2 + 1] = ty(X, y)
            }
            return out
        }

        fun depth(x: Float, z: Float): Float = -x * sin(yaw) + z * cos(yaw)

        /** The region of the head between longitudes [t0]..[t1] and heights [lo]..[hi], as far as it can be seen. */
        fun region(t0: Float, t1: Float, lo: (Float) -> Float, hi: (Float) -> Float, steps: Int = 40): FloatArray? {
            val a0 = max(t0, -HALF_PI - yaw); val a1 = min(t1, HALF_PI - yaw)
            if (a1 - a0 < 1e-3f) return null
            val side = 10
            val out = FloatArray((steps + 1) * 4 + (side - 1) * 4)
            var o = 0
            for (i in 0..steps) { val th = a0 + (a1 - a0) * i / steps; ang(th, lo(th), 0f, out, o); o += 2 }
            for (i in 1 until side) { val v = lo(a1) + (hi(a1) - lo(a1)) * i / side; ang(a1, v, 0f, out, o); o += 2 }
            for (i in steps downTo 0) { val th = a0 + (a1 - a0) * i / steps; ang(th, hi(th), 0f, out, o); o += 2 }
            for (i in side - 1 downTo 1) { val v = lo(a0) + (hi(a0) - lo(a0)) * i / side; ang(a0, v, 0f, out, o); o += 2 }
            return out
        }

        // ---- paints --------------------------------------------------------------------

        /** A shaded paint that follows the head's own curve, so paint on the head is lit as the head is. */
        fun headPaint(m: Material, over: Material? = null) = VectorCanvas.Paint { x, y ->
            val dx = x / sx; val dy = (y - headY) / sy
            val hx = dx * cr + dy * sr; val hy = -dx * sr + dy * cr
            val v = (hy / ry).coerceIn(-1f, 1f)
            val wv = w(v)
            val s = if (wv > 1e-3f) (hx / (rx * wv)).coerceIn(-1f, 1f) else 0f
            val nx = s * wv; val ny = v; val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            // Where on the head this is, so grain and wear stay put as it turns.
            val th = asin(s) - yaw
            var col = texture(shade(m, nx, ny, nz), th, v, nz)
            if (over != null) {
                val cover = paintCover(th, v, nz)
                if (cover > 0f) col = mix(col, texture(shade(over, nx, ny, nz), th, v, nz, paint = true), cover)
            }
            col
        }

        /** Wood grain running down the mask, a painter's brush over it, brass's green in its hollows. */
        fun texture(col: Int, th: Float, v: Float, nz: Float, paint: Boolean = false): Int {
            if (gloss) return col
            if (metal) {
                val patina = (fbm(th * 2.2f + 3f, v * 2.6f) - 0.45f).coerceIn(0f, 0.4f) * 1.6f * (1f - nz * 0.6f)
                return mix(col, c(0x3F7A62), patina)
            }
            // Long fibres down the mask, bending round slow figure in the wood.
            val figure = fbm(th * 1.1f + 5f, v * 0.9f)
            val rings = 0.5f + 0.5f * sin((th * 7f + 3f * figure) * 3.4f)
            val fibre = noise(th * 55f + figure * 4f, v * 1.6f)
            val mottle = fbm(th * 2.4f + 11f, v * 2.4f)
            val k = if (paint) 0.94f + 0.1f * mottle + 0.03f * rings
                else 0.84f + 0.12f * rings.pow(1.5f) + 0.08f * fibre + 0.08f * mottle
            return scale(col, k)
        }

        /** How much of a painted mask's paint is left here: worn through at its edges and high points. */
        fun paintCover(th: Float, v: Float, nz: Float): Float {
            if (look.finish != Finish.PAINTED) return 0f
            // Worn back to the wood at the rim, on the high points and in patches.
            val n = fbm(th * 3.1f + 7f, v * 3.4f) + 0.35f * nz - 0.05f * noise(th * 20f, v * 20f)
            return ((n - 0.49f) / 0.05f).coerceIn(0f, 1f)
        }

        /** A shaded paint for a rounded piece centred at ([cx], [cy]) with radii [rw], [rh], turned by [angle]. */
        fun blobPaint(m: Material, cx: Float, cy: Float, rw: Float, rh: Float, angle: Float = 0f) = VectorCanvas.Paint { x, y ->
            val ca = cos(-angle); val sa = sin(-angle)
            val lx = (x - cx) * ca - (y - cy) * sa; val ly = (x - cx) * sa + (y - cy) * ca
            var nx = lx / rw; var ny = ly / rh
            val l2 = nx * nx + ny * ny
            if (l2 > 0.998f) { val k = sqrt(0.998f / l2); nx *= k; ny *= k }
            val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            texture(shade(m, nx, ny, nz), x * 1.3f, y * 0.9f, nz)
        }

        fun shade(m: Material, nx: Float, ny: Float, nz: Float): Int {
            val d = nx * LX + ny * LY + nz * LZ
            val k = d * 0.5f + 0.5f
            var col = if (k < 0.55f) mix(m.shade, m.base, k / 0.55f) else mix(m.base, m.light, (k - 0.55f) / 0.45f)
            col = mix(col, m.bounce, max(0f, -ny) * max(0f, nz) * 0.3f)
            col = mix(col, m.shade, (1f - nz).pow(4f) * 0.35f)
            val sp = max(0f, nx * HX + ny * HY + nz * HZ).pow(28f) * m.spec
            return mix(col, WHITE, sp.coerceIn(0f, 1f))
        }

        /** A soft glow or shadow: [rgb] at [alpha] in the middle, fading to nothing at the edge. */
        fun soft(cx: Float, cy: Float, rw: Float, rh: Float, rgb: Int, alpha: Float) {
            if (alpha <= 0.003f || rw <= 1e-4f) return
            cv.fill(Shapes.ellipse(cx, cy, rw, rh, 32), VectorCanvas.Paint { x, y ->
                val r = sqrt(((x - cx) / rw).let { it * it } + ((y - cy) / rh).let { it * it }).coerceIn(0f, 1f)
                val a = alpha * (1f - r * r * (3f - 2f * r))
                ((a * 255f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
            })
        }

        fun softOnFace(u: Float, v: Float, rw: Float, rh: Float, rgb: Int, alpha: Float) {
            val fc = facing(u, v)
            if (fc <= 0.05f) return
            val p = point(u, v)
            soft(p[0], p[1], rw * rx * (0.3f + 0.7f * fc), rh, rgb, alpha * min(1f, fc * 2f))
        }

        fun linear(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int) = VectorCanvas.Paint { x, y ->
            val dx = x1 - x0; val dy = y1 - y0
            val k = (((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            mixA(c0, c1, k)
        }

        fun bounds(pts: FloatArray): FloatArray {
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until pts.size / 2) { x0 = min(x0, pts[i * 2]); x1 = max(x1, pts[i * 2]); y0 = min(y0, pts[i * 2 + 1]); y1 = max(y1, pts[i * 2 + 1]) }
            return floatArrayOf(x0, y0, x1, y1)
        }

        // ---- the order of painting ----------------------------------------------------------

        fun draw() {
            val pieces = crestPieces()
            // Behind the head: what the turn carries round the back.
            pieces.filter { it.depth < -0.12f }.forEach { it.draw() }
            ears(front = false)
            earrings(front = false)
            if (look.beard) beard()
            head()
            facePaint()
            marks()
            blush()
            hair()
            ears(front = true)
            pieces.filter { it.depth >= -0.12f }.forEach { it.draw() }
            nose()
            eyes()
            brows()
            mouth()
            brushwork()
            earrings(front = true)
            effects()
        }

        // ---- the head -------------------------------------------------------------------

        fun head() {
            val n = 72
            val pts = FloatArray(n * 4)
            var o = 0
            for (i in 0 until n) { val v = -1f + 2f * i / (n - 1); val x = rx * w(v); pts[o++] = tx(x, ry * v); pts[o++] = ty(x, ry * v) }
            for (i in n - 1 downTo 0) { val v = -1f + 2f * i / (n - 1); val x = -rx * w(v); pts[o++] = tx(x, ry * v); pts[o++] = ty(x, ry * v) }
            cv.fill(pts, if (look.finish == Finish.PAINTED) headPaint(woodMat, faceMat) else headPaint(faceMat))
            silhouette = pts
            if (!gloss) sculpt()
        }

        var silhouette: FloatArray? = null

        /** The carving's forms, painted in light and shade: a domed brow, a ridge over the eyes, cheekbones, the hollows. */
        fun sculpt() {
            softOnFace(0f, 0.52f, 0.7f, 0.32f, c(0xFFF0DA), 0.12f)
            sides { sd ->
                softOnFace(sd * EYE_U, EYE_V + 0.17f, 0.3f, 0.07f, c(0xFFF0DA), 0.12f)
                softOnFace(sd * EYE_U, EYE_V - 0.02f, 0.3f, 0.12f, c(0x160A10), 0.2f)
                softOnFace(sd * 0.52f, -0.16f, 0.2f, 0.11f, c(0xFFF0DA), 0.1f)
                softOnFace(sd * 0.5f, -0.46f, 0.18f, 0.2f, c(0x160A10), 0.14f)
            }
            softOnFace(0f, MOUTH_V - 0.16f, 0.2f, 0.06f, c(0x160A10), 0.2f)
        }

        /** A painter's last pass: brush marks across the whole mask, and a dark painted edge round it. */
        fun brushwork() {
            val pts = silhouette ?: return
            if (gloss) return
            // Broad strokes of a loaded brush: soft patches a little warmer or deeper.
            cv.fill(pts, VectorCanvas.Paint { x, y ->
                val n = fbm(x * 2.2f + y * 0.8f, y * 3.2f - x * 0.6f) - 0.5f
                val a = (abs(n) * 0.3f * 255f).toInt().coerceIn(0, 255)
                (a shl 24) or (if (n > 0f) 0xFFE8C8 else 0x1A0C10)
            })
            cv.stroke(pts, 0.035f, c(0x1A0E0C), closed = true, opacity = 0.7f)
        }

        /** Where the coiffure meets the face, at world longitude [th]: its height. */
        fun hairV(th: Float): Float {
            val c = cos(th); val front = max(0f, c).pow(1.5f); val back = max(0f, -c).pow(1.5f)
            val (hf, hs, hb) = when (look.hairline) {
                Hairline.PEAK -> Triple(0.5f, 0.18f, -0.35f)
                Hairline.HIGH -> Triple(0.68f, 0.35f, -0.15f)
                Hairline.SCALLOP -> Triple(0.5f, 0.2f, -0.3f)
                Hairline.LOW -> Triple(0.4f, 0.06f, -0.45f)
                Hairline.BALD -> Triple(2f, 2f, 2f)
            }
            var v = hs + (hf - hs) * front + (hb - hs) * back
            if (look.hairline == Hairline.PEAK) v -= 0.13f * exp(-(th / 0.24f).let { it * it })
            if (look.hairline == Hairline.SCALLOP) v -= 0.07f * (1f - abs(sin(th * 5f)))
            return v
        }

        fun foreheadTop(): Float = if (look.hairline == Hairline.BALD) 0.8f else hairV(0f) - 0.05f

        fun hair() {
            if (look.hairline == Hairline.BALD) return
            val cap = region(-PI.toFloat(), PI.toFloat(), { hairV(it) }, { 1f }, 64) ?: return
            // The coiffure's soft shadow on the brow, then the coiffure itself.
            val lower = region(-PI.toFloat(), PI.toFloat(), { hairV(it) - 0.07f }, { hairV(it) }, 64)
            if (lower != null) cv.fill(lower, VectorCanvas.Paint { _, _ -> (0x30 shl 24) or 0x1A0E10 })
            cv.fill(cap, headPaint(hairMat))
            if (look.braids) braids()
            if (look.cowries) cowrieBand()
        }

        fun braids() {
            if (!gloss) { grooves(); return }
            // Braided rows running back over the crown, each a line of glossy knots.
            val rows = 9
            for (k in 0 until rows) {
                val th = (k - (rows - 1) / 2f) * 0.42f
                val v0 = hairV(th) + 0.08f
                var v = v0
                while (v < 0.93f) {
                    val tp = th + yaw
                    val fc = cos(tp) * w(v)
                    if (fc > 0.12f) {
                        val p = FloatArray(2); ang(th, v, 0f, p, 0)
                        val rw = 0.075f * (0.3f + 0.7f * cos(tp).coerceAtLeast(0f)) * (0.55f + 0.45f * w(v)); val rh = 0.042f
                        val knot = Shapes.ellipse(p[0], p[1], rw, rh, 16)
                        cv.fill(knot, blobPaint(material(mix(pigment(look.hair), c(0x6A5A58), 0.18f), 0.7f), p[0], p[1], rw, rh))
                    }
                    v += 0.062f
                }
            }
        }

        /**
         * A carved coiffure: bands stacked up the crown, each following the
         * hairline a little higher, with rows of triangular cuts between them.
         */
        fun grooves() {
            val lit = mix(pigment(look.hair), c(0xE8D8C0), 0.35f)
            var dv = 0.1f; var band = 0
            while (true) {
                val pts = ArrayList<Float>()
                var th = -HALF_PI - yaw
                while (th <= HALF_PI - yaw) {
                    val v = hairV(th) + dv
                    if (v < 0.95f && cos(th + yaw) * w(v) > 0.05f) { val p = FloatArray(2); ang(th, v, 0f, p, 0); pts += p[0]; pts += p[1] }
                    th += 0.05f
                }
                if (pts.size < 4) break
                val line = pts.toFloatArray()
                cv.stroke(line, 0.03f, c(0x0C0606), opacity = 0.85f)
                cv.stroke(Shapes.offset(line, 0.01f, -0.012f), 0.012f, lit, opacity = 0.6f)
                // Triangular cuts along every other band: a carver's zigzag.
                if (band % 2 == 1) {
                    var tt = -1.4f
                    while (tt < 1.4f) {
                        val v = hairV(tt) + dv - 0.05f
                        if (cos(tt + yaw) * w(v) > 0.2f) {
                            val a = FloatArray(2); ang(tt, v + 0.035f, 0f, a, 0)
                            val b = FloatArray(2); ang(tt + 0.06f, v - 0.02f, 0f, b, 0)
                            val cc = FloatArray(2); ang(tt - 0.06f, v - 0.02f, 0f, cc, 0)
                            cv.fill(floatArrayOf(a[0], a[1], b[0], b[1], cc[0], cc[1]), c(0x0C0606), 0.75f)
                        }
                        tt += 0.18f
                    }
                }
                dv += 0.11f; band++
                if (band > 9) break
            }
        }

        fun cowrieBand() {
            var th = -PI.toFloat()
            while (th < PI) {
                val v = hairV(th) + 0.035f
                val fc = cos(th + yaw) * w(v)
                if (fc > 0.15f) {
                    val p = FloatArray(2); ang(th, v, 0.01f, p, 0)
                    cowrie(p[0], p[1], 0.045f * (0.4f + 0.6f * cos(th + yaw)), 0.035f)
                }
                th += 0.2f
            }
        }

        fun cowrie(x: Float, y: Float, rw: Float, rh: Float) {
            cv.fill(Shapes.ellipse(x, y, rw, rh, 16), blobPaint(material(c(0xF3EAD6), 0.9f), x, y, rw, rh))
            cv.stroke(floatArrayOf(x - rw * 0.6f, y, x + rw * 0.6f, y), rh * 0.3f, c(0x5A4030))
        }

        // ---- paint and marks -----------------------------------------------------------

        fun facePaint() {
            val top = { th: Float -> if (look.hairline == Hairline.BALD) 1f else hairV(th) }
            for (p in look.paint) when (p) {
                Paint.SPLIT -> region(-PI.toFloat(), 0f, { -1f }, top, 48)?.let { cv.fill(it, headPaint(accent2Mat)) }
                Paint.EYE_BAND -> region(-HALF_PI, HALF_PI, { EYE_V - 0.15f }, { EYE_V + 0.15f }, 48)?.let { cv.fill(it, headPaint(accentMat)) }
                Paint.CHIN -> region(-HALF_PI, HALF_PI, { -1f }, { MOUTH_V - 0.17f }, 48)?.let { cv.fill(it, headPaint(accent2Mat)) }
                Paint.SPIRALS -> sides { sd ->
                    val pts = FloatArray(82)
                    for (i in 0..40) { val k = i / 40f; val a = k * 4.2f * PI.toFloat() * sd; val r = 0.012f + 0.085f * k; pts[i * 2] = sd * 0.52f + cos(a) * r; pts[i * 2 + 1] = -0.2f + sin(a) * r }
                    if (facing(sd * 0.52f, -0.2f) > 0.1f) cv.stroke(onFace(pts), 0.02f, ink)
                }
                Paint.HEART -> {
                    // The Fang heart: a concave plane from the brows down to the chin, framed by a carved ridge.
                    val right = Shapes.quad(0f, BROW_V + 0.02f, 0.5f, BROW_V + 0.34f, 0.6f, BROW_V - 0.06f, 12) +
                        Shapes.quad(0.6f, BROW_V - 0.06f, 0.62f, -0.5f, 0f, -0.93f, 14).copyOfRange(2, 30)
                    val left = FloatArray(right.size) { k -> if (k % 2 == 0) -right[right.size - 2 - k] else right[right.size - k] }
                    val heart = onFace(right + left.copyOfRange(2, left.size - 2))
                    cv.fill(heart, headPaint(accentMat))
                    groove(heart)
                }
                Paint.LIDS, Paint.LIPS -> Unit
            }
        }

        fun sides(draw: (Float) -> Unit) { draw(-1f); draw(1f) }

        /** An incised line: dark in its cut, its lower edge catching the light. */
        fun groove(pts: FloatArray) {
            cv.stroke(pts, 0.024f, carved)
            if (!gloss) cv.stroke(Shapes.offset(pts, 0.008f, -0.01f), 0.01f, mix(pigment(look.ground), c(0xFFF2DC), 0.45f), opacity = 0.7f)
        }

        fun marks() {
            val top = foreheadTop()
            for (m in look.marks) when (m) {
                Mark.ICHI -> {
                    val v0 = BROW_V + 0.14f; val v1 = min(top, BROW_V + 0.34f)
                    if (v1 - v0 > 0.05f) for (i in -2..2) { val u = i * 0.07f; groove(onFace(floatArrayOf(u, v0, u, v1))) }
                }
                Mark.TEMPLE -> sides { sd ->
                    val v0 = BROW_V + 0.1f; val v1 = min(foreheadTop() + 0.02f, BROW_V + 0.26f)
                    if (facing(sd * 0.66f, v0) > 0.1f && v1 - v0 > 0.05f) for (i in 0 until 3) {
                        val u = sd * (0.6f + i * 0.055f)
                        groove(onFace(floatArrayOf(u, v0 - i * 0.02f, u, v1 - i * 0.03f)))
                    }
                }
                Mark.CHEEK -> sides { sd ->
                    if (facing(sd * 0.5f, -0.2f) > 0.1f) for (i in 0 until 3) {
                        val v = -0.14f - i * 0.065f
                        groove(onFace(floatArrayOf(sd * 0.44f, v - 0.08f, sd * 0.64f, v - 0.11f)))
                    }
                }
                Mark.STAR -> {
                    val v = min(top - 0.08f, BROW_V + 0.22f)
                    val star = FloatArray(16) { k -> val i = k / 2; val a = PI.toFloat() / 2f + i * PI.toFloat() / 4f; val r = if (i % 2 == 0) 0.085f else 0.026f; if (k % 2 == 0) cos(a) * r else v + sin(a) * r }
                    cv.fill(onFace(star), carved)
                    cv.fill(onFace(FloatArray(16) { k -> if (k % 2 == 0) star[k] * 0.5f else v + (star[k] - v) * 0.5f }), pigment(look.accent))
                }
                Mark.LINE -> {
                    val v1 = min(top, 0.8f)
                    groove(onFace(floatArrayOf(0f, BROW_V - 0.06f, 0f, v1)))
                }
                Mark.KELOID -> {
                    val v = min(top - 0.1f, BROW_V + 0.2f)
                    for (i in -2..2) for (j in -2..2) if (abs(i) + abs(j) <= 2) {
                        val p = point(i * 0.045f, v + j * 0.04f)
                        cv.fill(Shapes.ellipse(p[0], p[1], 0.017f, 0.017f, 10), blobPaint(material(mix(pigment(look.ground), c(0x2A1A18), 0.35f), 0.8f), p[0], p[1], 0.017f, 0.017f))
                    }
                }
            }
        }

        fun blush() {
            val amount = (f.blush + f.anger * 0.3f).coerceIn(0f, 1.2f)
            if (amount <= 0f || !gloss) return
            val col = if (luma(pigment(look.ground)) > 0.5f) c(0xE8584A) else c(0xFF6A5A)
            sides { sd -> softOnFace(sd * 0.56f, -0.26f, 0.24f, 0.14f, col, 0.55f * amount) }
        }

        // ---- the face ----------------------------------------------------------------

        fun nose() {
            val top = 0.08f; val tip = -0.25f
            fun lift(v: Float) = 0.02f + (top - v) / (top - tip) * 0.13f
            val half = when (look.nose) { NoseStyle.LONG -> 0.07f; NoseStyle.BROAD -> 0.11f; NoseStyle.BUTTON -> 0.07f; NoseStyle.NONE -> return }
            if (look.nose == NoseStyle.BUTTON) {
                val p = point(0f, -0.2f, 0.1f)
                softOnFace(0.04f, -0.25f, 0.1f, 0.05f, c(0x1A0A10), 0.18f)
                cv.fill(Shapes.ellipse(p[0], p[1], 0.07f, 0.055f, 24), blobPaint(faceMat, p[0], p[1], 0.07f, 0.055f))
                return
            }
            // Its shadow falls down and to the right, away from the light.
            softOnFace(0.05f, -0.1f, 0.06f, 0.2f, c(0x1A0A10), 0.12f)
            softOnFace(0.06f, tip - 0.04f, half * 0.9f, 0.05f, c(0x1A0A10), 0.2f)
            val uvl = ArrayList<Float>()
            fun add(u: Float, v: Float) { uvl += u; uvl += v; uvl += lift(v) }
            val n = 10
            for (i in 0..n) { val k = i / n.toFloat(); val v = top + (tip - top) * k; add(-(0.022f + (half - 0.022f) * k.pow(2.2f)), v) }
            for (i in 1 until 8) { val a = PI.toFloat() * i / 8f; add(-half * cos(a), tip - 0.035f * sin(a)) }
            for (i in n downTo 0) { val k = i / n.toFloat(); val v = top + (tip - top) * k; add(0.022f + (half - 0.022f) * k.pow(2.2f), v) }
            val shape = onFaceLift(uvl.toFloatArray())
            val b = bounds(shape)
            cv.fill(shape, blobPaint(faceMat, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f - 0.04f, (b[2] - b[0]) / 2f + 0.02f, (b[3] - b[1]) / 1.6f))
            sides { sd -> val p = point(sd * half * 0.5f, tip + 0.005f, lift(tip)); soft(p[0], p[1], 0.03f, 0.018f, c(0x2A1014), 0.55f) }
        }

        fun eyes() {
            sides { sd -> eye(sd) }
        }

        fun eye(sd: Float) {
            val cu = sd * EYE_U; val v = EYE_V
            val fc = facing(cu, v)
            if (fc <= 0.05f) return
            val st = look.eyes
            val ew = when (st) { EyeStyle.SLIT -> 0.22f; EyeStyle.ALMOND -> 0.19f; EyeStyle.ROUND -> 0.15f; EyeStyle.TUBE -> 0.13f; EyeStyle.BEAN -> 0.19f }
            val eh = when (st) { EyeStyle.SLIT -> 0.07f; EyeStyle.ALMOND -> 0.1f; EyeStyle.ROUND -> 0.15f; EyeStyle.TUBE -> 0.13f; EyeStyle.BEAN -> 0.055f }
            // The carved socket: a soft hollow round the eye.
            softOnFace(cu, v + 0.01f, ew * 1.6f, eh * 2.4f + 0.05f, c(0x2A1420), 0.16f)
            if (st == EyeStyle.TUBE) {
                val p = point(cu, v, 0.05f)
                val rw = (ew + 0.06f) * rx * (0.4f + 0.6f * fc); val rh = eh + 0.06f
                cv.fill(Shapes.ellipse(p[0], p[1], rw, rh, 36), blobPaint(faceMat, p[0], p[1], rw, rh))
            }
            if (st == EyeStyle.BEAN) beanMound(cu, v, ew, fc)
            if (st == EyeStyle.ROUND) {
                cv.fill(onFace(Shapes.ellipse(cu, v, ew + 0.05f, eh + 0.05f, 36)), headPaint(material(if (look.ground == KAOLIN) pigment(look.accent) else pigment(KAOLIN), 0.4f)))
            }
            when (f.eyeKind) {
                EyeKind.HEART -> {
                    val p = point(cu, v, 0.04f); val s = 0.27f * (1f + 0.08f * sin(t * 9f))
                    val h = heart(p[0], p[1], s * (0.45f + 0.55f * fc), s)
                    val b = bounds(h)
                    cv.fill(h, blobPaint(material(c(0xE8283C), 0.8f), (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f))
                    return
                }
                EyeKind.STAR -> {
                    val p = point(cu, v, 0.04f); val s = 0.25f * (1f + 0.06f * sin(t * 7f))
                    val st5 = star(p[0], p[1], s * 0.45f, s, 5, t * 0.6f, fc)
                    val b = bounds(st5)
                    cv.fill(st5, blobPaint(material(c(0xFFC21A), 0.9f), (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f))
                    return
                }
                EyeKind.X -> {
                    val d = 0.12f
                    cv.stroke(onFace(floatArrayOf(cu - d, v + d, cu + d, v - d)), 0.065f, eyeLine)
                    cv.stroke(onFace(floatArrayOf(cu - d, v - d, cu + d, v + d)), 0.065f, eyeLine)
                    return
                }
                EyeKind.SPIRAL -> {
                    val pts = FloatArray(90)
                    for (i in 0 until 45) { val k = i / 44f; val a = k * 5.2f * PI.toFloat() * sd + t * 5f * sd; val r = 0.01f + 0.14f * k; pts[i * 2] = cu + cos(a) * r; pts[i * 2 + 1] = v + sin(a) * r }
                    cv.stroke(onFace(pts), 0.038f, eyeLine)
                    return
                }
                EyeKind.NORMAL -> Unit
            }
            val open = (if (sd < 0) f.eyeOpenL else f.eyeOpenR) * blinkK
            val smile = f.eyeSmile.coerceIn(0f, 1f)
            val lidCol = if (Paint.LIDS in look.paint) hairMat else material(mix(pigment(look.ground), c(0x3A2028), 0.16f), 0.4f)
            if (open < 0.12f || (st == EyeStyle.BEAN && open < 0.3f && smile < 0.45f)) { closedEye(cu, v, ew, sd, smile, lidCol); return }
            val o = open.coerceAtMost(1.4f)
            val lift = if (st == EyeStyle.TUBE) 0.05f else 0f
            // Level or drooping at the outer corner, as the carvers cut them -- never slanting up.
            val tilt = when (st) { EyeStyle.SLIT -> -0.012f; EyeStyle.ALMOND -> -0.02f; else -> 0f }
            val ix = cu - sd * ew; val ox = cu + sd * ew
            val up = v + 2f * eh * o * (1f + 0.2f * smile)
            val low = v + 2f * eh * o * (-(1f - smile) + 0.8f * smile)
            val upper = Shapes.quad(ix, v, cu, up, ox, v + tilt, 18)
            val lower = Shapes.quad(ox, v + tilt, cu, low, ix, v, 18)
            val uv = upper + lower.copyOfRange(2, lower.size - 2)
            val shape = onFace(uv, lift)
            val b = bounds(shape)
            rim(shape)
            cv.fill(shape, linear(0f, b[3], EYE_DARK, 0f, b[1], EYE_WARM))
            cv.clipped(shape) {
                val g = point(cu - sd * 0.25f * ew + f.lookX * 0.05f - 0.03f, v + eh * o * 0.5f + f.lookY * 0.03f, lift)
                val gr = 0.048f * (0.6f + 0.4f * min(1f, o))
                cv.fill(Shapes.ellipse(g[0], g[1], gr * (0.5f + 0.5f * fc), gr, 18), c(0xFFFFFF))
                val g2 = point(cu + sd * 0.3f * ew + f.lookX * 0.05f, v - eh * o * 0.25f + f.lookY * 0.03f, lift)
                cv.fill(Shapes.ellipse(g2[0], g2[1], 0.022f, 0.022f, 12), c(0xFFFFFF), 0.8f)
                if (f.tears > 0.15f) {
                    // Welling tears: a bright wet line along the lower lid.
                    cv.fill(onFace(Shapes.ellipse(cu, low * 0.6f + v * 0.4f, ew, eh * 0.9f, 24), lift), c(0x7FC8FF), 0.55f * min(1f, f.tears * 1.5f))
                }
            }
            // The heavy upper lid of the carvers, and the crisp line under it.
            if (st == EyeStyle.SLIT || st == EyeStyle.ALMOND) {
                val lidTop = Shapes.quad(ox + sd * 0.025f, v + tilt + 0.012f, cu, up + 0.075f + (if (st == EyeStyle.SLIT) 0.03f else 0f), ix - sd * 0.025f, v + 0.012f, 18)
                val lid = onFace(upper + lidTop, lift)
                cv.fill(lid, headPaint(lidCol))
            }
            cv.stroke(onFace(upper, lift), 0.034f, EYE_DARK)
        }

        /** The coffee-bean eye of Dan and Punu carving: a swollen lid, the eye a slit across it. */
        fun beanMound(cu: Float, v: Float, ew: Float, fc: Float) {
            val p = point(cu, v + 0.005f, 0.035f)
            val rw = (ew + 0.05f) * rx * (0.4f + 0.6f * fc); val rh = 0.12f
            val mound = Shapes.ellipse(p[0], p[1], rw, rh, 40)
            cv.fill(mound, blobPaint(faceMat, p[0], p[1], rw, rh))
            // Painted lids: a dark line round the lid's edge, not a dark lid.
            if (Paint.LIDS in look.paint) cv.stroke(mound, 0.022f, pigment(look.hair), closed = true, opacity = 0.85f)
        }

        fun closedEye(cu: Float, v: Float, ew: Float, sd: Float, smile: Float, lidCol: Material) {
            if (look.eyes == EyeStyle.BEAN && smile <= 0.45f) {
                // The bean's slit: a straight cut across the mound.
                cv.stroke(onFace(floatArrayOf(cu - sd * ew * 0.95f, v + 0.005f, cu, v - 0.004f, cu + sd * ew * 0.95f, v + 0.005f), 0.036f), 0.034f, if (darkGround) c(0x0C0606) else EYE_DARK)
                return
            }
            if (smile > 0.45f) {
                // Laughing: an arch, like ^.
                cv.stroke(onFace(Shapes.quad(cu - sd * ew * 0.9f, v - 0.04f, cu, v + 0.17f, cu + sd * ew * 0.9f, v - 0.03f, 16)), 0.068f, eyeLine)
                return
            }
            // Serene or asleep: the heavy lid down, a lash line along it.
            val lash = Shapes.quad(cu - sd * ew, v + 0.025f, cu, v - 0.1f, cu + sd * ew, v + 0.035f, 16)
            val lidTop = Shapes.quad(cu + sd * (ew + 0.025f), v + 0.05f, cu, v + 0.12f, cu - sd * (ew + 0.025f), v + 0.04f, 16)
            cv.fill(onFace(lash + lidTop), headPaint(lidCol))
            cv.stroke(onFace(lash), 0.045f, eyeLine)
        }

        fun brows() {
            if (look.brows == BrowStyle.NONE) return
            sides { sd ->
                val cu = sd * EYE_U
                if (facing(cu, BROW_V) <= 0.05f) return@sides
                val raise = f.browRaise + (if (sd > 0) f.browAsym else 0f)
                val base = BROW_V + raise * 0.07f
                val tilt = f.browTilt
                val ax = cu - sd * 0.2f; val ay = base - 0.02f - tilt * 0.07f
                val mx = cu + sd * 0.01f; val my = base + 0.09f - abs(tilt) * 0.02f
                val bx = cu + sd * 0.24f; val by = base - 0.03f + tilt * 0.04f
                val (w0, w1) = when (look.brows) { BrowStyle.ARCH -> 0.085f to 0.035f; BrowStyle.RIDGE -> 0.13f to 0.07f; BrowStyle.THIN -> 0.04f to 0.022f; BrowStyle.NONE -> 0f to 0f }
                val shape = onFace(Shapes.taper(ax, ay, mx, my, bx, by, w0, w1, 16), 0.01f)
                val b = bounds(shape)
                val m = if (look.brows == BrowStyle.RIDGE) material(mix(pigment(look.ground), pigment(look.hair), 0.55f), 0.5f) else hairMat
                cv.fill(shape, blobPaint(m, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f + 0.01f, (b[3] - b[1]) / 2f + 0.01f))
            }
        }

        fun mouth() {
            var open = f.mouthOpen; var teeth = f.teeth
            if (look.mouth == MouthStyle.TEETH && f.mouthKind == MouthKind.NORMAL) { open = max(open, 0.24f); teeth = max(teeth, 1f) }
            val mv = MOUTH_V
            val cu = f.mouthSide * 0.06f
            if (facing(cu, mv) <= 0.05f) return
            val hw = (0.15f + 0.13f * f.mouthWide) * when (look.mouth) { MouthStyle.WIDE -> 1.35f; MouthStyle.POUT -> 0.8f; else -> 1f }
            val smile = f.mouthSmile
            val lips = Paint.LIPS in look.paint || look.mouth == MouthStyle.POUT || wooden || metal
            val lipCol = if (Paint.LIPS in look.paint) pigment(look.accent) else if (gloss) mix(pigment(look.ground), c(0x8A2A2A), 0.45f)
                else mix(pigment(look.ground), c(0x3A1812), if (darkGround) 0.1f else 0.3f)
            when (f.mouthKind) {
                MouthKind.O -> {
                    val shape = onFace(Shapes.ellipse(cu, mv - 0.03f, 0.07f + 0.06f * open, 0.08f + 0.09f * open, 32))
                    if (lips) cv.stroke(shape, 0.05f, lipCol, closed = true) else rim(shape)
                    val b = bounds(shape)
                    cv.fill(shape, linear(0f, b[3], MOUTH_TOP, 0f, b[1], MOUTH_DEEP))
                    if (f.tongue > 0.6f) tongueOut(cu, b)
                    return
                }
                MouthKind.KISS -> {
                    val k = floatArrayOf(cu - 0.015f, mv + 0.1f, cu + 0.06f, mv + 0.06f, cu + 0.005f, mv + 0.015f, cu + 0.06f, mv - 0.035f, cu - 0.015f, mv - 0.07f)
                    if (lips) cv.stroke(onFace(k), 0.07f, lipCol)
                    cv.stroke(onFace(Shapes.quad(k[0], k[1], k[2] + 0.03f, (k[1] + k[5]) / 2f, k[4], k[5], 8) + Shapes.quad(k[4], k[5], k[6] + 0.03f, (k[5] + k[9]) / 2f, k[8], k[9], 8)), 0.045f, mouthLine)
                    return
                }
                MouthKind.NORMAL -> Unit
            }
            val cy = mv + smile * 0.06f
            val lcy = cy + f.mouthSide * 0.03f; val rcy = cy - f.mouthSide * 0.02f
            if (open < 0.06f && teeth < 0.5f) {
                val curve = Shapes.quad(cu - hw, lcy, cu, mv - smile * 0.1f, cu + hw, rcy, 18)
                if (lips) {
                    val lipShape = Shapes.quad(cu - hw - 0.03f, lcy, cu, mv + 0.12f, cu + hw + 0.03f, rcy, 14) + Shapes.quad(cu + hw + 0.03f, rcy, cu, mv - smile * 0.1f - 0.15f, cu - hw - 0.03f, lcy, 14).let { it.copyOfRange(2, it.size - 2) }
                    val s = onFace(lipShape)
                    val b = bounds(s)
                    cv.fill(s, blobPaint(material(lipCol, 0.7f), (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f))
                }
                cv.stroke(onFace(curve), 0.046f, mouthLine)
                // Dimples at the corners of a smile.
                if (smile > 0.5f) sides { sd -> val x = cu + sd * hw; val y = if (sd < 0) lcy else rcy; cv.stroke(onFace(floatArrayOf(x - sd * 0.01f, y + 0.035f, x + sd * 0.012f, y - 0.01f)), 0.022f, mouthLine) }
                if (f.tongue > 0.6f) tongueOut(cu, bounds(onFace(curve)))
                return
            }
            val topCtrl = mv + 0.035f - smile * 0.05f
            val botCtrl = mv - open * 0.42f - smile * 0.14f
            val upper = Shapes.quad(cu - hw, lcy, cu, topCtrl, cu + hw, rcy, 18)
            val lower = Shapes.quad(cu + hw, rcy, cu, botCtrl, cu - hw, lcy, 18)
            val shape = onFace(upper + lower.copyOfRange(2, lower.size - 2))
            if (lips) cv.stroke(shape, 0.075f, lipCol, closed = true) else rim(shape)
            val b = bounds(shape)
            cv.fill(shape, linear(0f, b[3], MOUTH_TOP, 0f, b[1], MOUTH_DEEP))
            cv.clipped(shape) {
                if (teeth > 0f) {
                    val band = upper + Shapes.quad(cu + hw, rcy - 0.07f, cu, topCtrl - 0.075f, cu - hw, lcy - 0.07f, 18)
                    val tb = onFace(band); val bb = bounds(tb)
                    cv.fill(tb, linear(0f, bb[3], c(0xFFFFFF), 0f, bb[1], c(0xD8D2D6)), min(1f, teeth))
                    if (f.anger > 0.3f || look.mouth == MouthStyle.TEETH) {
                        val low = lower + Shapes.quad(cu - hw, lcy + 0.06f, cu, botCtrl + 0.07f, cu + hw, rcy + 0.06f, 18)
                        cv.fill(onFace(low), c(0xF0EAEC), min(1f, teeth))
                        for (i in -2..2) { val u = cu + i * hw * 0.34f; cv.stroke(onFace(floatArrayOf(u, topCtrl, u, botCtrl)), 0.012f, c(0xBDB2B6)) }
                    }
                }
                if (f.tongue > 0.2f) {
                    val tp = point(cu, (botCtrl + mv) / 2f - 0.02f)
                    val rw = hw * rx * 0.62f; val rh = 0.075f + 0.04f * open
                    cv.fill(Shapes.ellipse(tp[0], tp[1] - rh * 0.4f, rw, rh, 28), blobPaint(material(TONGUE, 0.6f), tp[0], tp[1] - rh * 0.4f, rw, rh))
                }
            }
            if (f.tongue > 0.6f) tongueOut(cu, b)
        }

        fun tongueOut(cu: Float, mb: FloatArray) {
            val cx = (mb[0] + mb[2]) / 2f; val top = mb[1] + 0.03f
            val rw = 0.075f; val len = 0.2f + 0.02f * sin(t * 4f)
            val pts = Shapes.roundRect(cx - rw, top - len, cx + rw, top + 0.02f, rw, 8)
            cv.fill(pts, blobPaint(material(TONGUE, 0.7f), cx, top - len / 2f, rw, len / 2f + 0.02f))
            cv.stroke(floatArrayOf(cx, top - 0.02f, cx, top - len * 0.6f), 0.014f, mix(TONGUE, c(0x6A1A2A), 0.5f))
        }

        // ---- ears, earrings, beard --------------------------------------------------------

        fun ears(front: Boolean) {
            if (look.ears == Ears.NONE) return
            sides { sd ->
                val X = sd * rx * 0.97f; val Y = -0.04f
                val d = depth(X, 0f)
                if ((d > -0.05f) != front) return@sides
                val big = look.ears == Ears.ELEPHANT
                val rw0 = if (big) 0.36f else 0.1f; val rh = if (big) 0.44f else 0.16f
                val rw = rw0 * (0.45f + 0.55f * abs(cos(yaw + sd * 0.4f)))
                val p = world(X + sd * rw0 * 0.55f, Y, 0f)
                cv.fill(Shapes.ellipse(p[0], p[1], rw, rh, 36), blobPaint(faceMat, p[0], p[1], rw, rh))
                cv.fill(Shapes.ellipse(p[0] + sd * 0.01f, p[1], rw * 0.55f, rh * 0.62f, 28), blobPaint(material(mix(pigment(look.ground), c(0x6A2A30), 0.35f), 0.2f), p[0], p[1], rw * 0.55f, rh * 0.62f))
            }
        }

        fun earrings(front: Boolean) {
            if (!look.earrings) return
            sides { sd ->
                val X = sd * rx * 0.96f; val d = depth(X, 0f)
                if ((d > -0.05f) != front) return@sides
                val swing = sin(t * 2.4f + sd) * 0.25f
                val top = world(X, -0.22f, 0f)
                val r = 0.085f
                val cx = top[0] + sin(swing) * r; val cy = top[1] - cos(swing) * r
                cv.stroke(Shapes.ellipse(cx, cy, r, r, 28), 0.03f, mix(pigment(GOLD), c(0x5A3A10), 0.4f), closed = true)
                cv.stroke(Shapes.ellipse(cx, cy, r, r, 28), 0.018f, pigment(GOLD), closed = true)
                val bx = cx + sin(swing) * r; val by = cy - cos(swing) * r
                cv.fill(Shapes.ellipse(bx, by, 0.038f, 0.038f, 16), blobPaint(accentMat, bx, by, 0.038f, 0.038f))
            }
        }

        fun beard() {
            val z = 0.35f
            val pts = Shapes.quad(-0.2f, -ry * 0.86f, -0.1f, -ry - 0.28f, 0f, -ry - 0.46f, 10) + Shapes.quad(0f, -ry - 0.46f, 0.1f, -ry - 0.28f, 0.2f, -ry * 0.86f, 10).copyOfRange(2, 22)
            val s = worldPoly(pts, z)
            val b = bounds(s)
            cv.fill(s, blobPaint(hairMat, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f))
            for (k in 1..3) {
                val y = -ry * 0.9f - k * 0.1f; val half = 0.16f - k * 0.04f
                cv.stroke(worldPoly(floatArrayOf(-half, y, 0f, y - 0.04f, half, y), z), 0.02f, pigment(look.accent))
            }
        }

        // ---- the crest ------------------------------------------------------------------

        /** A crest piece: how far toward the eye it is after the turn, and how to paint it. */
        inner class Piece(val depth: Float, val draw: () -> Unit)

        fun crestPieces(): List<Piece> {
            val out = ArrayList<Piece>()
            val size = look.crestSize.coerceIn(0f, 1f)
            val topY = ry * 0.8f
            when (look.crest) {
                Crest.NONE -> Unit
                Crest.COMBS -> {
                    val n = look.crestCount.coerceIn(1, 7)
                    val h = 0.42f + 0.5f * size
                    for (i in 0 until n) {
                        val k = i - (n - 1) / 2f
                        val ang = -k * 0.3f
                        val bx = k * 0.12f
                        val bh = h * (1f - 0.1f * abs(k))
                        out += Piece(depth(bx, -0.1f) - abs(k) * 0.01f) {
                            val local = Shapes.roundRect(-0.1f, 0f, 0.1f, bh, 0.1f, 8)
                            val rot = rotate(local, 0f, 0f, ang)
                            val pts = worldPoly(Shapes.offset(rot, bx, topY - 0.08f), -0.1f)
                            val c0 = world(bx - sin(ang) * bh / 2f, topY - 0.08f + cos(ang) * bh / 2f, -0.1f)
                            cv.fill(pts, blobPaint(crestMat, c0[0], c0[1], 0.1f * (0.45f + 0.55f * abs(cos(yaw))) + 0.01f, bh / 2f + 0.01f, ang))
                            val tip = world(bx - sin(ang) * (bh - 0.1f), topY - 0.08f + cos(ang) * (bh - 0.1f), -0.08f)
                            cv.fill(Shapes.ellipse(tip[0], tip[1], 0.04f, 0.04f, 16), blobPaint(accentMat, tip[0], tip[1], 0.04f, 0.04f))
                        }
                    }
                }
                Crest.LOBES -> {
                    val n = look.crestCount.coerceIn(1, 5)
                    val rw = 0.2f + 0.06f * size; val rh = 0.26f + 0.16f * size
                    for (i in 0 until n) {
                        val k = i - (n - 1) / 2f
                        val X = k * rw * 1.35f; val Y = topY + rh * 0.55f - abs(k) * 0.06f
                        out += Piece(depth(X, 0f) + 0.001f * i) {
                            val p = world(X, Y, 0f)
                            val w0 = rw * (0.5f + 0.5f * abs(cos(yaw))) + 0.02f
                            cv.fill(Shapes.ellipse(p[0], p[1], w0, rh, 36), blobPaint(crestMat, p[0], p[1], w0, rh))
                            // Twisted grooves round the lobe.
                            for (g in -1..1) cv.stroke(Shapes.quad(p[0] - w0 * 0.8f, p[1] + g * rh * 0.4f - 0.05f, p[0], p[1] + g * rh * 0.4f + 0.06f, p[0] + w0 * 0.8f, p[1] + g * rh * 0.4f - 0.03f, 10), 0.018f, mix(pigment(look.crestColour), c(0x8A7A70), 0.35f))
                        }
                    }
                }
                Crest.RIDGES -> {
                    val n = look.crestCount.coerceIn(1, 5)
                    val h = 0.35f + 0.5f * size
                    for (i in 0 until n) {
                        val k = i - (n - 1) / 2f
                        val X = k * 0.3f
                        out += Piece(depth(X, 0f)) {
                            val wScreen = 0.13f * abs(cos(yaw)) + 0.75f * abs(sin(yaw)) * (1f - 0.2f * abs(k)) + 0.02f
                            val p = world(X, topY + h / 2f - 0.05f, 0f)
                            val pts = Shapes.roundRect(p[0] - wScreen / 2f, p[1] - h / 2f, p[0] + wScreen / 2f, p[1] + h / 2f, min(wScreen / 2f, h / 2f), 8)
                            cv.fill(pts, blobPaint(crestMat, p[0], p[1], wScreen / 2f, h / 2f))
                            var y = p[1] - h / 2f + 0.08f
                            while (y < p[1] + h / 2f - 0.05f) {
                                cv.fill(Shapes.ellipse(p[0], y, wScreen * 0.36f, 0.025f, 12), blobPaint(accent2Mat, p[0], y, wScreen * 0.36f, 0.025f))
                                y += 0.11f
                            }
                        }
                    }
                }
                Crest.KNOTS -> {
                    val n = look.crestCount.coerceIn(1, 5)
                    val r = 0.13f + 0.06f * size
                    for (i in 0 until n) {
                        val a = if (n == 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f) * 0.95f
                        val X = sin(a) * 0.45f * rx; val Z = cos(a) * 0.18f - 0.08f
                        val Y = topY + 0.18f + 0.1f * size + 0.06f * cos(a)
                        out += Piece(depth(X, Z)) {
                            val base = world(X * 0.7f, topY - 0.05f, Z)
                            val p = world(X, Y, Z)
                            cv.stroke(floatArrayOf(base[0], base[1], p[0], p[1]), 0.06f, pigment(look.hair))
                            cv.fill(Shapes.ellipse(p[0], p[1], r, r, 32), blobPaint(crestMat, p[0], p[1], r, r))
                            cv.stroke(Shapes.ellipse(p[0], p[1] - r * 0.1f, r * 0.9f, r * 0.3f, 24).copyOfRange(0, 26), 0.024f, pigment(look.accent))
                        }
                    }
                }
                Crest.CONE -> {
                    val h = 0.55f + 0.7f * size; val half = 0.34f * rx
                    out += Piece(0f) {
                        val right = Shapes.quad(half, topY - 0.1f, half * 0.75f, topY + h * 0.55f, 0f, topY + h, 14)
                        val left = Shapes.quad(0f, topY + h, -half * 0.75f, topY + h * 0.55f, -half, topY - 0.1f, 14)
                        val pts = worldPoly(right + left.copyOfRange(2, left.size), 0f)
                        val c0 = world(0f, topY + h * 0.35f, 0f)
                        val paint = blobPaint(crestMat, c0[0], c0[1], half, h * 0.7f)
                        cv.fill(pts, paint)
                        cv.clipped(pts) {
                            val bands = 4
                            for (b in 0 until bands) {
                                val y0 = topY + h * (0.1f + 0.2f * b); val y1 = y0 + h * 0.07f
                                val shift = sin(yaw) * 0.1f
                                val band = worldPoly(floatArrayOf(-1f, y0 - 0.03f, 0f + shift, y0 + 0.03f, 1f, y0 - 0.03f, 1f, y1 - 0.03f, shift, y1 + 0.03f, -1f, y1 - 0.03f), 0f)
                                cv.fill(band, blobPaint(if (b % 2 == 0) accentMat else accent2Mat, c0[0], c0[1], half, h * 0.7f))
                            }
                        }
                    }
                }
                Crest.HORNS -> {
                    val reach = 0.2f + 0.3f * size
                    for (sd in listOf(-1f, 1f)) {
                        val X = sd * 0.42f * rx
                        out += Piece(depth(X, 0f)) {
                            val spine = floatArrayOf(X, topY - 0.05f, sd * (1.1f * rx + 0.1f * size), topY + 0.2f, sd * (0.85f * rx + 0.2f * size), topY + reach + 0.35f)
                            val pts = worldPoly(Shapes.taper(spine[0], spine[1], spine[2], spine[3], spine[4], spine[5], 0.22f, 0.03f, 20), 0f)
                            val mid = world((spine[0] + spine[2] + spine[4]) / 3f, (spine[1] + spine[3] + spine[5]) / 3f, 0f)
                            cv.fill(pts, linear(mid[0] - sd * 0.2f, mid[1] + 0.2f, crestMat.light, mid[0] + sd * 0.25f, mid[1] - 0.25f, crestMat.shade))
                            val streak = worldPoly(Shapes.taper(spine[0] - sd * 0.03f, spine[1] + 0.03f, spine[2] - sd * 0.05f, spine[3], spine[4] - sd * 0.02f, spine[5] - 0.08f, 0.05f, 0.005f, 16), 0f)
                            cv.fill(streak, c(0xFFFFFF), 0.35f)
                            for (k in 0 until 3) {
                                val tt = 0.12f + k * 0.1f
                                val bx = spine[0] + (spine[2] - spine[0]) * tt * 1.6f; val by = spine[1] + (spine[3] - spine[1]) * tt * 1.6f
                                val p = world(bx, by, 0f)
                                cv.stroke(floatArrayOf(p[0] - 0.08f, p[1] - sd * 0.05f, p[0] + 0.08f, p[1] + sd * 0.05f), 0.035f, pigment(look.accent))
                            }
                        }
                    }
                }
                Crest.TIERS -> {
                    for (k in 0 until 3) {
                        val half = (0.9f - 0.22f * k) * rx; val y0 = topY - 0.05f + k * 0.2f * (0.8f + 0.4f * size)
                        out += Piece(0.001f * k) {
                            val bar = worldPoly(Shapes.roundRect(-half, y0, half, y0 + 0.19f, 0.09f, 8), 0f)
                            val c0 = world(0f, y0 + 0.095f, 0f)
                            val m = listOf(crestMat, accentMat, accent2Mat)[k]
                            cv.fill(bar, blobPaint(m, c0[0], c0[1], half * abs(cos(yaw)) + 0.05f, 0.1f))
                            for (j in -3..3) {
                                val p = world(j * half * 0.26f, y0 + 0.095f, 0.05f)
                                cv.fill(Shapes.ellipse(p[0], p[1], 0.03f, 0.03f, 12), blobPaint(if (m === crestMat) accentMat else crestMat, p[0], p[1], 0.03f, 0.03f))
                            }
                        }
                    }
                }
                Crest.PLUMES -> {
                    val n = (look.crestCount.coerceIn(3, 9) / 2) * 2 + 1
                    val len = 0.55f + 0.5f * size
                    for (i in 0 until n) {
                        val k = i - (n - 1) / 2f
                        val a = -k * 0.28f + sin(t * 2.6f + i) * 0.05f
                        out += Piece(-0.05f - abs(k) * 0.01f) {
                            val leaf = leaf(0f, 0f, len * (1f - 0.08f * abs(k)), 0.09f)
                            val pts = worldPoly(Shapes.offset(rotate(leaf, 0f, 0f, a), k * 0.05f, topY - 0.05f), -0.1f)
                            val mid = world(k * 0.05f - sin(a) * len / 2f, topY - 0.05f + cos(a) * len / 2f, -0.1f)
                            cv.fill(pts, blobPaint(if (i % 2 == 0) crestMat else accentMat, mid[0], mid[1], 0.09f, len / 2f, a))
                        }
                    }
                }
            }
            return out.sortedBy { it.depth }
        }

        // ---- effects --------------------------------------------------------------------------

        fun effects() {
            if (f.tears > 0f) tears()
            if (f.sweat > 0f) {
                val p = point(0.6f, 0.42f, 0.05f)
                drop(p[0] + 0.06f, p[1] - ((t * 0.3f) % 1f) * 0.05f, 0.08f * f.sweat)
            }
            if (f.zzz > 0f) for (k in 0 until 3) {
                val ph = (t * 0.35f + k / 3f) % 1f
                val s = (0.06f + 0.08f * ph) * f.zzz
                val x = 0.7f + 0.45f * ph; val y = headY + 0.55f + 0.7f * ph
                val z = floatArrayOf(x - s, y + s, x + s, y + s, x - s, y - s, x + s, y - s)
                cv.stroke(z, s * 0.55f, c(0x2A3A7A), opacity = 1f - ph)
                cv.stroke(z, s * 0.3f, c(0xBFD8FF), opacity = 1f - ph)
            }
            if (f.hearts > 0f) {
                val n = if (f.hearts > 0.6f) 3 else 1
                for (k in 0 until n) {
                    val a = t * 0.9f + k * 2.1f + 0.6f
                    val x = cos(a) * 1.15f; val y = headY + 0.15f + sin(a) * 0.55f
                    val s = 0.12f * f.hearts * (1f + 0.1f * sin(t * 8f + k))
                    val h = heart(x, y, s, s)
                    cv.fill(h, blobPaint(material(c(0xE8283C), 0.8f), x, y, s, s))
                }
            }
            if (f.anger > 0f) {
                val cx = 0.6f; val cy = headY + 0.75f; val s = 0.1f * f.anger * (1f + 0.08f * sin(t * 10f))
                for (q in 0 until 4) {
                    val a = q * PI.toFloat() / 2f + PI.toFloat() / 4f
                    val ox = cx + cos(a) * s * 0.9f; val oy = cy + sin(a) * s * 0.9f
                    val arc = Shapes.quad(ox + cos(a + 1.2f) * s * 0.6f, oy + sin(a + 1.2f) * s * 0.6f, ox - cos(a) * s * 0.35f, oy - sin(a) * s * 0.35f, ox + cos(a - 1.2f) * s * 0.6f, oy + sin(a - 1.2f) * s * 0.6f, 8)
                    cv.stroke(arc, s * 0.32f, c(0xE0242E))
                }
            }
            if (f.sparkle > 0f) {
                val spots = listOf(-1.1f to 0.5f, 1.05f to 0.75f, 0.95f to -0.55f, -1.0f to -0.35f)
                spots.forEachIndexed { i, (x, y) ->
                    val tw = (0.55f + 0.45f * sin(t * 4f + i * 1.7f)) * f.sparkle
                    cv.fill(star(x, headY + y, 0.018f * tw, 0.11f * tw, 4, 0f, 1f), c(0xFFE27A))
                }
            }
        }

        fun tears() {
            val amount = f.tears.coerceIn(0f, 1f)
            sides { sd ->
                if (facing(sd * EYE_U, EYE_V) <= 0.1f) return@sides
                if (amount > 0.6f) {
                    // Streams, as a crying emoji's: from the lower lid out and down past the chin.
                    val a = point(sd * (EYE_U + 0.02f), EYE_V - 0.06f, 0.03f)
                    val spine = floatArrayOf(a[0], a[1], a[0] + sd * 0.28f, a[1] - 0.05f, a[0] + sd * 0.42f, headY - ry - 0.15f)
                    val s = Shapes.taper(spine[0], spine[1], spine[2], spine[3], spine[4], spine[5], 0.07f, 0.16f * amount, 18)
                    cv.fill(s, linear(spine[0], spine[1], c(0xBFE6FF), spine[4], spine[5], c(0x2E8AE6)), 0.92f)
                    val wave = (t * 1.5f) % 1f
                    val wy = spine[1] + (spine[5] - spine[1]) * wave
                    cv.fill(Shapes.ellipse(spine[0] + (spine[4] - spine[0]) * wave, wy, 0.03f, 0.05f, 12), c(0xFFFFFF), 0.7f)
                } else {
                    val ph = ((t * 0.5f) + (sd + 1f) * 0.25f) % 1f
                    val p = point(sd * (EYE_U + 0.1f), EYE_V - 0.1f - ph * 0.45f * amount * 2f, 0.04f)
                    drop(p[0], p[1], 0.05f + 0.02f * amount)
                }
            }
        }

        fun drop(x: Float, y: Float, s: Float) {
            val pts = teardrop(x, y, s)
            val b = bounds(pts)
            cv.fill(pts, blobPaint(material(c(0x5AB4F5), 0.95f), (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, (b[2] - b[0]) / 2f, (b[3] - b[1]) / 2f))
        }

        // ---- shapes -----------------------------------------------------------------------

        fun rotate(pts: FloatArray, px: Float, py: Float, a: Float): FloatArray {
            val ca = cos(a); val sa = sin(a)
            return FloatArray(pts.size) { k -> val i = k - k % 2; val x = pts[i] - px; val y = pts[i + 1] - py; if (k % 2 == 0) px + x * ca - y * sa else py + x * sa + y * ca }
        }

        fun leaf(bx: Float, by: Float, len: Float, wid: Float): FloatArray {
            val right = Shapes.quad(bx, by, bx + wid * 2f, by + len * 0.45f, bx, by + len, 12)
            val left = Shapes.quad(bx, by + len, bx - wid * 2f, by + len * 0.45f, bx, by, 12)
            return right + left.copyOfRange(2, left.size - 2)
        }

        fun heart(cx: Float, cy: Float, sxh: Float, syh: Float): FloatArray = FloatArray(80) { k ->
            val tt = (k / 2) * 2f * PI.toFloat() / 40f
            if (k % 2 == 0) cx + sxh * 16f * sin(tt).pow(3) / 17f
            else cy + syh * (13f * cos(tt) - 5f * cos(2 * tt) - 2f * cos(3 * tt) - cos(4 * tt)) / 17f + syh * 0.1f
        }

        fun star(cx: Float, cy: Float, r0: Float, r1: Float, points: Int, spin: Float, squeeze: Float): FloatArray = FloatArray(points * 4) { k ->
            val i = k / 2; val a = spin + PI.toFloat() / 2f + i * PI.toFloat() / points; val r = if (i % 2 == 0) r1 else r0
            if (k % 2 == 0) cx + cos(a) * r * (0.45f + 0.55f * squeeze) else cy + sin(a) * r
        }

        fun teardrop(cx: Float, cy: Float, r: Float): FloatArray = FloatArray(56) { k ->
            val th = (k / 2) * 2f * PI.toFloat() / 28f
            if (k % 2 == 0) cx + r * sin(th) * sin(th / 2f) else cy + r * 1.4f * cos(th)
        }
    }

    // ---- constants --------------------------------------------------------------------

    private const val HALF_PI = (PI / 2).toFloat()
    private const val EYE_U = 0.37f
    private const val EYE_V = -0.08f
    private const val BROW_V = 0.18f
    private const val MOUTH_V = -0.6f

    // The key light, from the upper left in front; and the highlight's half vector.
    private val L = norm(-0.5f, 0.72f, 0.62f)
    private val LX = L[0]; private val LY = L[1]; private val LZ = L[2]
    private val H = norm(L[0], L[1], L[2] + 1f)
    private val HX = H[0]; private val HY = H[1]; private val HZ = H[2]

    private val WHITE = c(0xFFFFFF)
    private val EYE_DARK = c(0x1E1412)
    private val EYE_WARM = c(0x4A2E28)
    private val MOUTH_INK = c(0x3A1E1A)
    private val MOUTH_TOP = c(0x2A0E10)
    private val MOUTH_DEEP = c(0x6E2626)
    private val TONGUE = c(0xF0607A)

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /** Smooth value noise, 0..1. */
    private fun noise(x: Float, y: Float): Float {
        val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
        val a = hash(xi, yi); val b = hash(xi + 1, yi); val c0 = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
        return (a + (b - a) * ux) + ((c0 + (d - c0) * ux) - (a + (b - a) * ux)) * uy
    }

    private fun fbm(x: Float, y: Float): Float = noise(x, y) * 0.5f + noise(x * 2.03f, y * 2.03f) * 0.3f + noise(x * 4.1f, y * 4.1f) * 0.2f

    private fun scale(argb: Int, k: Float): Int {
        fun m(s: Int) = (ch(argb, s) * k).toInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
    private fun c(rgb: Int) = (0xFF shl 24) or rgb
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun luma(argb: Int) = (0.299f * ch(argb, 16) + 0.587f * ch(argb, 8) + 0.114f * ch(argb, 0)) / 255f
    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    private fun mixA(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
