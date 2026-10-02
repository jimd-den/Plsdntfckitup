package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.model.mask.sculpt.Spirit.Core as C
import com.stratum.engine.model.mask.sculpt.Spirit.Fracture as F
import com.stratum.engine.model.mask.sculpt.Spirit.Relic as Rl
import com.stratum.engine.model.mask.sculpt.Spirit.Role as R
import com.stratum.engine.model.mask.sculpt.Spirit.Temper as T
import kotlin.random.Random

/**
 * What a carved African mask is made of, as choices a generator can make.
 *
 * Every part of a mask is named here in the carvers' own terms: the form it
 * takes on the dancer (a face mask, a helmet worn over the head, a crest
 * carried on top), its outline, how its eyes are cut, its nose and mouth,
 * the scarification carved into it, the coiffure, the horns or
 * superstructure, what hangs from it, the wood and how it is finished, the
 * pigments painted on it and the patterns they make.
 *
 * [MaskCulture.traditions] then says, tradition by tradition, which of
 * these each people carves and how often: a grammar the generator samples,
 * so an Mgbedike comes out horned, dark and fierce and a Punu maiden white,
 * serene, lobe-coiffed and scarred with keloid diamonds. Traditions can be
 * mixed for new spirits that still belong to the family.
 */
object Anatomy {
    enum class Form(val label: String) { FACE("Face mask"), HELMET("Helmet mask") }
    enum class Outline(val label: String) { OVAL("Oval"), HEART("Heart"), SHIELD("Shield"), LONG("Long"), ROUND("Round"), SQUARE("Square"), CONCAVE("Concave heart plane"), DISC("Flat disc") }
    enum class Brow(val label: String) { NONE("Smooth"), ARCH("Arched ridges"), HEART("Heart ridge"), SHELF("Brow shelf"), BULGE("Domed forehead") }
    enum class Eyes(val label: String) {
        SLIT("Heavy-lidded slits"), BEAN("Coffee-bean lids"), TUBE("Projecting tubes"), ROUND("Round holes"),
        CRESCENT("Downcast crescents"), DIAMOND("Diamond cuts"), TROUGH("Deep troughs"), RINGED("Ringed holes"),
        DOWNCAST("Heavy downcast lids"), BULGING("Bulging domes"),
    }
    enum class Nose(val label: String) { LONG("Long ridge"), BROAD("Broad"), TRIANGLE("Triangular"), BEAK("Beak"), NONE("None"), UPTURNED("Upturned") }
    enum class Mouth(val label: String) {
        CLOSED("Closed lips"), OPEN("Open, protruding"), TEETH("Bared teeth"), PURSED("Pursed"), BOX("Box mouth"), TUBE("Tube spout"),
        GRIN("Wide grin"),
    }
    enum class Ears(val label: String) { NONE("None"), SMALL("Small"), ELEPHANT("Elephant") }
    enum class Scar(val label: String) {
        ICHI("Ichi lines"), TEMPLES("Temple bars"), CHEEKS("Cheek cuts"), KELOID_DIAMONDS("Keloid diamonds"),
        CENTRE_RIDGE("Centre ridge"), STRIATIONS("Striations"), CHEEK_KELOIDS("Cheek keloids"), TEARS("Tear lines"),
        NSIBIDI("Nsibidi-style signs"),
    }
    enum class Coiffure(val label: String) {
        NONE("Bare"), CAP("Smooth cap"), CORNROWS("Cornrows"), CREST("Sagittal crest"), TRIPLE_CREST("Three crests"),
        LOBES("Lobed coiffure"), KNOTS("Hair knots"), COMBS("Crested combs"), TOPKNOT("Topknot"),
        ARCHES("Arched crests"),
    }
    enum class Crown(val label: String) {
        NONE("None"), ANTELOPE("Antelope horns"), RAM("Ram horns"), BUFFALO("Buffalo horns"), TIERS("Tiered superstructure"),
        PLANK("Plank"), KANAGA("Kanaga"), BIRD("Hornbill"),
        HORN_ROW("Row of horns"), FRAME_HORNS("Horns framing the face"),
    }
    enum class Beard(val label: String) { NONE("None"), CARVED("Carved"), RAFFIA("Raffia") }
    enum class Adorn(val label: String) { COWRIES("Cowrie band"), BEADS("Bead strands"), EARRINGS("Earrings"), BRASS_STUDS("Brass studs"), RAFFIA_COLLAR("Raffia collar"), LIP_PLUG("Lip plug"), NECK_RINGS("Neck rings") }
    enum class Finish(val label: String) {
        RAW("Oiled wood"), BLACKENED("Blackened and polished"), KAOLIN("Whitened with kaolin"), CAMWOOD("Rubbed with camwood"),
        POLYCHROME("Painted in pigments"), BRASS("Cast brass"),
    }
    enum class Pattern(val label: String) {
        EYE_RINGS("Kaolin round the eyes"), SPLIT("Split face"), BANDS("Painted bands"), CHECKER("Checkers"), DOTS("Dots"),
        TRIANGLES("Triangles"), CONCENTRIC("Concentric rings"), STRIATED("Painted striations"), ULI("Uli lines"),
    }

    /** What part of the mask a dial shapes, for grouping them in an editor. */
    enum class Part(val label: String) {
        FACE("Face"), EYES("Eyes"), NOSE("Nose"), MOUTH("Mouth"), BROW_EARS("Brow & ears"), MARKS("Marks & paint"),
        HAIR("Coiffure"), HORNS("Horns & crest"), RAFFIA("Raffia"), ADORN("Adornment"), SURFACE("Wood & wear"),
    }

    /**
     * A carver's hand on a continuous choice: each 0..1, [default] as the
     * tradition carves it. Together with the named choices they make every
     * proportion of the mask the player's to set.
     */
    enum class Dial(val label: String, val part: Part, val default: Float = 0.5f) {
        FACE_DEPTH("Depth", Part.FACE), CONVEXITY("Curve across", Part.FACE), JAW("Jaw", Part.FACE),
        CHEEKS("Cheeks", Part.FACE), FOREHEAD("Forehead", Part.FACE), ASYMMETRY("Asymmetry", Part.FACE, 0f),
        EYE_SIZE("Size", Part.EYES), EYE_SPACING("Spacing", Part.EYES), EYE_TILT("Tilt", Part.EYES), EYE_DEPTH("Lids", Part.EYES), ORBITS("Sockets", Part.EYES),
        NOSE_LENGTH("Length", Part.NOSE), NOSE_SIZE("Size", Part.NOSE), NOSE_BRIDGE("Bridge", Part.NOSE),
        MOUTH_SIZE("Size", Part.MOUTH), MOUTH_HEIGHT("Height", Part.MOUTH), LIP_FULLNESS("Lips", Part.MOUTH), MUZZLE("Muzzle", Part.MOUTH),
        BROW_WEIGHT("Brow weight", Part.BROW_EARS), EAR_SIZE("Ear size", Part.BROW_EARS),
        SCAR_DEPTH("Cut depth", Part.MARKS), PATTERN_SCALE("Pattern scale", Part.MARKS),
        HAIRLINE("Hairline", Part.HAIR), HAIR_VOLUME("Volume", Part.HAIR), HAIR_TEXTURE("Rows", Part.HAIR),
        HORN_CURL("Curl", Part.HORNS), HORN_SPREAD("Spread", Part.HORNS), HORN_GIRTH("Girth", Part.HORNS), HORN_RIDGES("Ridges", Part.HORNS),
        RAFFIA_LENGTH("Length", Part.RAFFIA),
        WEAR("Wear", Part.SURFACE), GRAIN("Grain", Part.SURFACE), PATINA("Patina", Part.SURFACE), TOOL_MARKS("Adze marks", Part.SURFACE),
    }
}

/**
 * The spirit inside a mask, and how it breaks the mask open.
 *
 * In the traditions the generator draws on, a mask is not an object to hang
 * on a wall: danced, it is the vessel of a spirit (among the Igbo, *mmuo*).
 * A floating mask that has cracked into pieces held round a glowing core
 * shows that power as too much for the wood to hold. Each tradition decides
 * what the spirit is for ([Role]), how its pieces move ([Temper]), how it
 * tends to break ([Fracture]), what burns inside ([Core]) and what drifts
 * round it ([Relic]).
 */
object Spirit {
    /** What the spirit is for among the people who dance it. */
    enum class Role(val label: String) {
        ANCESTRAL_PURITY("Ancestral purity"), MARTIAL_FEROCITY("Martial ferocity"), COSMIC_POWER("Cosmic power"),
        JUDICIAL_JUSTICE("Judgement"), AXIS_MUNDI("Order of the world"), ANCESTRAL_MATRIARCH("Ancestral mother"),
        ROYAL_MAJESTY("Royal majesty"), WATER_SPIRIT("Water and season"), PERSONAL_POWER("Personal achievement"),
        SATIRE("Satire and social order"), BUSH_SPIRIT("Spirits of the bush"), MEDIATION("Mediation and grace"),
        INITIATION("Initiation"), OUR_MOTHERS("Honouring the Mothers"),
    }

    /** How its pieces move: see [com.stratum.engine.scene.SpiritTemperament]. */
    enum class Temper(val label: String) {
        AUSTERE("Austere: still and aligned"), STORM("Storm: crackling, flaring"), MONUMENTAL("Monumental: heavy sway"),
        BREATHING("Breathing: solemn swell"), RESTLESS("Restless: never still"),
    }

    /** How the mask breaks. */
    enum class Fracture(val label: String, val about: String) {
        SUSPENDED_FACETS("Suspended facets", "Brows, cheeks, bridge, jaw and crest each float apart."),
        SPLIT_VISAGE("Split visage", "Cleft down the middle, the halves drifting apart round the core."),
        SHATTERED_CROWN("Shattered crown", "The face holds; the crown, combs and crest break into floating pieces."),
        FLOATING_QUADRANTS("Floating quadrants", "Quartered across the eyes and down the nose."),
        DRIFTING_JAW("Drifting jaw", "The jaw breaks away at the mouth and hangs below."),
        DISSOLVED_CHIN("Dissolved chin", "The jaw crumbles to embers held in the core's light."),
    }

    /** What burns inside: the light through the cracks, ARGB [glow] and [second]. */
    enum class Core(val label: String, val glow: Int, val second: Int) {
        THUNDER("Thunder wisp", 0xFF9CD6FF.toInt(), 0xFFF2FAFF.toInt()),
        SOLAR("Solar ember", 0xFFFFAE45.toInt(), 0xFFFFE28A.toInt()),
        LEOPARD("Leopard void", 0xFFB58CFF.toInt(), 0xFF5FFFC4.toInt()),
        MARSH("Marsh emerald", 0xFF58F2A2.toInt(), 0xFFC8FF8A.toInt()),
        FIREFLY("Firefly swarm", 0xFFFFE468.toInt(), 0xFFB6FF6A.toInt()),
        MOON("Kaolin moon", 0xFFDCE9FF.toInt(), 0xFFFFFFFF.toInt()),
        CAMWOOD("Camwood blood", 0xFFFF5636.toInt(), 0xFFFFA36E.toInt()),
        INDIGO("Indigo tide", 0xFF6A86FF.toInt(), 0xFF9CE6FF.toInt()),
    }

    /** What drifts round the head. */
    enum class Relic(val label: String) {
        BRONZE_PLAQUE("Bronze plaques"), BRASS_BELL("Brass bells"), HALO("Halo disc"), COWRIE("Cowries"), KANAGA("Kanaga cross"), MIRROR("Mirrors"),
    }
}

/** A mask's spirit, fully specified: how it breaks, what burns inside, how it moves. */
data class SpiritSpec(
    val fracture: Spirit.Fracture = Spirit.Fracture.SUSPENDED_FACETS,
    val core: Spirit.Core = Spirit.Core.SOLAR,
    val temper: Spirit.Temper = Spirit.Temper.BREATHING,
    /** 0 pieces nearly touching .. 1 flung wide. */
    val drift: Float = 0.5f,
    /** 0 fewest pieces .. 1 most. */
    val pieces: Float = 0.5f,
    /** 0 clean splits along the grain .. 1 splintered. */
    val jag: Float = 0.5f,
    val relics: Set<Spirit.Relic> = emptySet(),
    val seed: Long = 0L,
)

/** A tradition's spirit: its role and temper, and weighted ways it breaks and burns. */
class SpiritGrammar(
    val role: Spirit.Role,
    val temper: Spirit.Temper,
    val fractures: List<Pair<Spirit.Fracture, Int>>,
    val cores: List<Pair<Spirit.Core, Int>>,
    /** Each relic with its chance of drifting round the head. */
    val relics: List<Pair<Spirit.Relic, Float>> = emptyList(),
    val drift: ClosedFloatingPointRange<Float> = 0.3f..0.75f,
)

/** A mask, fully specified: every choice the sculptor needs, in the carvers' terms. */
data class MaskSpec(
    val name: String,
    val tradition: String,
    val form: Anatomy.Form = Anatomy.Form.FACE,
    val outline: Anatomy.Outline = Anatomy.Outline.OVAL,
    /** 0 narrow .. 1 wide. */
    val width: Float = 0.5f,
    /** 0 short .. 1 long. */
    val length: Float = 0.5f,
    val brow: Anatomy.Brow = Anatomy.Brow.ARCH,
    val eyes: Anatomy.Eyes = Anatomy.Eyes.SLIT,
    val nose: Anatomy.Nose = Anatomy.Nose.LONG,
    val mouth: Anatomy.Mouth = Anatomy.Mouth.CLOSED,
    val ears: Anatomy.Ears = Anatomy.Ears.NONE,
    val scars: Set<Anatomy.Scar> = emptySet(),
    val coiffure: Anatomy.Coiffure = Anatomy.Coiffure.CAP,
    val crown: Anatomy.Crown = Anatomy.Crown.NONE,
    /** 0 small .. 1 towering: horn length, crest and superstructure height. */
    val crownSize: Float = 0.5f,
    /** How many combs, knots, tiers or crests. */
    val count: Int = 3,
    val beard: Anatomy.Beard = Anatomy.Beard.NONE,
    val adorn: Set<Anatomy.Adorn> = emptySet(),
    val finish: Anatomy.Finish = Anatomy.Finish.RAW,
    val patterns: Set<Anatomy.Pattern> = emptySet(),
    /** Colours as [com.stratum.engine.model.mask.AfricanMaskArt.PIGMENTS] indices. */
    val wood: Int = Pigments.IROKO,
    val hair: Int = Pigments.BLACKWOOD,
    val accent: Int = Pigments.KAOLIN,
    val accent2: Int = Pigments.CAMWOOD,
    val hornColour: Int = Pigments.BONE,
    /** The light in the eyes, ARGB. */
    val glow: Int = 0xFFFFA23A.toInt(),
    /** How much raffia hangs from it, 0..1: its swing in play comes from the fringe physics. */
    val raffia: Float = 0f,
    /** The raffia's dye as a pigment index, or [NATURAL] for undyed straw. */
    val raffiaDye: Int = NATURAL,
    /** The beads' colour, a pigment index, strung with the accents. */
    val beads: Int = Pigments.VERMILION,
    /** Continuous proportions off their defaults; a missing dial is its [Anatomy.Dial.default]. */
    val dials: Map<Anatomy.Dial, Float> = emptyMap(),
    val seed: Long = 0L,
) {
    fun dial(d: Anatomy.Dial): Float = dials[d] ?: d.default

    fun withDial(d: Anatomy.Dial, v: Float): MaskSpec = copy(dials = dials + (d to v.coerceIn(0f, 1f)))

    companion object { const val NATURAL = -1 }
}

/** The pigment indices the masks use, by name. */
object Pigments {
    const val KAOLIN = 0; const val BONE = 1; const val OCHRE = 2; const val GOLD = 3; const val CAMWOOD = 4; const val VERMILION = 5
    const val TERRACOTTA = 6; const val UMBER = 7; const val CHARCOAL = 8; const val LAMPBLACK = 9; const val INDIGO = 10; const val LAPIS = 11
    const val JADE = 12; const val MALACHITE = 13; const val BRASS = 14; const val CORAL = 15; const val IROKO = 16; const val BLACKWOOD = 17; const val MAHOGANY = 18

    fun argb(i: Int): Int = com.stratum.engine.model.mask.AfricanMaskArt.PIGMENTS[Math.floorMod(i, com.stratum.engine.model.mask.AfricanMaskArt.PIGMENTS.size)].rgb
}

/**
 * One people's way of carving: who carves it, where, what the mask does, and
 * the grammar of choices its masks are made from. A grammar is a list of
 * weighted options for each part; the generator rolls within it.
 */
class Tradition(
    val id: String,
    val name: String,
    val people: String,
    val region: String,
    /** What the mask is for, in a sentence or two. */
    val about: String,
    /** The motion profile its spirits move with ([com.stratum.core.domain.motion.MotionProfiles]). */
    val motion: String,
    val grammar: Grammar,
    /** The spirit its masks hold, and how it breaks them. */
    val spirit: SpiritGrammar,
)

/** Weighted options for every part of a mask. */
class Grammar(
    val forms: List<Pair<Anatomy.Form, Int>>,
    val outlines: List<Pair<Anatomy.Outline, Int>>,
    val width: ClosedFloatingPointRange<Float>,
    val length: ClosedFloatingPointRange<Float>,
    val brows: List<Pair<Anatomy.Brow, Int>>,
    val eyes: List<Pair<Anatomy.Eyes, Int>>,
    val noses: List<Pair<Anatomy.Nose, Int>>,
    val mouths: List<Pair<Anatomy.Mouth, Int>>,
    val ears: List<Pair<Anatomy.Ears, Int>> = listOf(Anatomy.Ears.NONE to 1),
    /** Each scar with its chance of appearing. */
    val scars: List<Pair<Anatomy.Scar, Float>> = emptyList(),
    val coiffures: List<Pair<Anatomy.Coiffure, Int>>,
    val crowns: List<Pair<Anatomy.Crown, Int>> = listOf(Anatomy.Crown.NONE to 1),
    val crownSize: ClosedFloatingPointRange<Float> = 0.3f..0.8f,
    val counts: IntRange = 1..5,
    val beards: List<Pair<Anatomy.Beard, Int>> = listOf(Anatomy.Beard.NONE to 1),
    val adorn: List<Pair<Anatomy.Adorn, Float>> = emptyList(),
    val finishes: List<Pair<Anatomy.Finish, Int>>,
    val patterns: List<Pair<Anatomy.Pattern, Float>> = emptyList(),
    val woods: List<Int> = listOf(Pigments.IROKO, Pigments.BLACKWOOD, Pigments.MAHOGANY),
    val hairs: List<Int> = listOf(Pigments.BLACKWOOD, Pigments.LAMPBLACK),
    val accents: List<Int> = listOf(Pigments.KAOLIN, Pigments.CAMWOOD, Pigments.OCHRE),
    val horns: List<Int> = listOf(Pigments.BONE, Pigments.UMBER, Pigments.BLACKWOOD),
    val glows: List<Int> = listOf(0xFFFFA23A.toInt()),
    val raffia: ClosedFloatingPointRange<Float> = 0f..0.6f,
    /** Proportions this people always carve a certain way (an Idiok's twisted face, Grassfields cheeks), rolled in these ranges instead. */
    val dials: Map<Anatomy.Dial, ClosedFloatingPointRange<Float>> = emptyMap(),
)

object MaskCulture {
    private fun <T> w(vararg p: Pair<T, Int>) = p.toList()
    private fun <T> c(vararg p: Pair<T, Float>) = p.toList()
    private const val EMBER = 0xFFFFA23A.toInt()
    private const val BLOOD = 0xFFFF4A26.toInt()
    private const val MOON = 0xFFBFE4FF.toInt()
    private const val GOLDEN = 0xFFFFD36A.toInt()

    val traditions: List<Tradition> = listOf(
        Tradition(
            "agbogho_mmuo", "Agbogho Mmuo", "Igbo", "South-eastern Nigeria",
            "The maiden spirit: the ideal of a young woman's beauty, danced by young men at the dry-season festivals. White-faced with kaolin, fine-featured, crowned with a crested coiffure and painted with uli.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 3, Anatomy.Form.FACE to 1), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.HEART to 2, Anatomy.Outline.LONG to 2),
                width = 0.3f..0.55f, length = 0.45f..0.8f,
                brows = w(Anatomy.Brow.ARCH to 4, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.SLIT to 4, Anatomy.Eyes.BEAN to 1),
                noses = w(Anatomy.Nose.LONG to 4, Anatomy.Nose.TRIANGLE to 1), mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.OPEN to 2, Anatomy.Mouth.TEETH to 1),
                ears = w(Anatomy.Ears.NONE to 2, Anatomy.Ears.SMALL to 1),
                scars = c(Anatomy.Scar.ICHI to 0.4f, Anatomy.Scar.TEMPLES to 0.5f, Anatomy.Scar.CENTRE_RIDGE to 0.3f),
                coiffures = w(Anatomy.Coiffure.ARCHES to 4, Anatomy.Coiffure.COMBS to 3, Anatomy.Coiffure.TRIPLE_CREST to 2, Anatomy.Coiffure.KNOTS to 1), counts = 3..5,
                adorn = c(Anatomy.Adorn.COWRIES to 0.3f, Anatomy.Adorn.EARRINGS to 0.3f, Anatomy.Adorn.BEADS to 0.2f),
                finishes = w(Anatomy.Finish.KAOLIN to 6, Anatomy.Finish.POLYCHROME to 2),
                patterns = c(Anatomy.Pattern.ULI to 0.7f, Anatomy.Pattern.EYE_RINGS to 0.2f),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), hairs = listOf(Pigments.LAMPBLACK, Pigments.BLACKWOOD),
                accents = listOf(Pigments.VERMILION, Pigments.OCHRE, Pigments.LAMPBLACK, Pigments.CAMWOOD), glows = listOf(EMBER, GOLDEN), raffia = 0.1f..0.5f,
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_PURITY, temper = T.BREATHING, fractures = w(F.SHATTERED_CROWN to 4, F.SUSPENDED_FACETS to 2, F.SPLIT_VISAGE to 1), cores = w(C.MOON to 3, C.SOLAR to 2, C.FIREFLY to 1), relics = c(Rl.MIRROR to 0.3f, Rl.HALO to 0.3f)),
        ),
        Tradition(
            "mgbedike", "Mgbedike", "Igbo", "South-eastern Nigeria",
            "\"The time of the brave\": the fierce male spirit, heavy and dark, with great horns, bulging tube eyes and bared teeth. Its dance is a show of strength.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 4), outlines = w(Anatomy.Outline.SQUARE to 3, Anatomy.Outline.ROUND to 2),
                width = 0.65f..1f, length = 0.4f..0.7f,
                brows = w(Anatomy.Brow.SHELF to 3, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.TUBE to 5, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.BROAD to 4, Anatomy.Nose.TRIANGLE to 1), mouths = w(Anatomy.Mouth.TEETH to 6, Anatomy.Mouth.BOX to 1),
                ears = w(Anatomy.Ears.SMALL to 3, Anatomy.Ears.NONE to 1),
                scars = c(Anatomy.Scar.CHEEKS to 0.5f, Anatomy.Scar.ICHI to 0.3f, Anatomy.Scar.NSIBIDI to 0.12f),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 2, Anatomy.Coiffure.CORNROWS to 1),
                crowns = w(Anatomy.Crown.ANTELOPE to 4, Anatomy.Crown.BUFFALO to 3, Anatomy.Crown.RAM to 1), crownSize = 0.55f..1f,
                beards = w(Anatomy.Beard.RAFFIA to 3, Anatomy.Beard.CARVED to 1, Anatomy.Beard.NONE to 1),
                adorn = c(Anatomy.Adorn.RAFFIA_COLLAR to 0.5f),
                finishes = w(Anatomy.Finish.BLACKENED to 4, Anatomy.Finish.RAW to 2, Anatomy.Finish.POLYCHROME to 1),
                patterns = c(Anatomy.Pattern.EYE_RINGS to 0.3f),
                woods = listOf(Pigments.MAHOGANY, Pigments.BLACKWOOD, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.OCHRE),
                horns = listOf(Pigments.BONE, Pigments.KAOLIN, Pigments.UMBER), glows = listOf(BLOOD, EMBER), raffia = 0.4f..0.9f,
            ),
            spirit = SpiritGrammar(role = R.MARTIAL_FEROCITY, temper = T.STORM, fractures = w(F.SUSPENDED_FACETS to 3, F.DRIFTING_JAW to 3, F.SPLIT_VISAGE to 1), cores = w(C.THUNDER to 4, C.CAMWOOD to 2), relics = c(Rl.BRASS_BELL to 0.2f), drift = 0.45f..0.9f),
        ),
        Tradition(
            "okoroshi", "Okoroshi", "Igbo", "Owerri region, south-eastern Nigeria",
            "Water spirits who come out in the rainy season: the beautiful white Okoroshi Ocha and the dark, menacing Okoroshi Ojo, often with teeth bared.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3, Anatomy.Form.HELMET to 1), outlines = w(Anatomy.Outline.LONG to 3, Anatomy.Outline.CONCAVE to 2, Anatomy.Outline.OVAL to 1),
                width = 0.3f..0.6f, length = 0.55f..0.95f,
                brows = w(Anatomy.Brow.HEART to 3, Anatomy.Brow.ARCH to 2), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.CRESCENT to 2),
                noses = w(Anatomy.Nose.LONG to 5), mouths = w(Anatomy.Mouth.TEETH to 3, Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.OPEN to 1),
                scars = c(Anatomy.Scar.ICHI to 0.5f, Anatomy.Scar.CENTRE_RIDGE to 0.4f, Anatomy.Scar.NSIBIDI to 0.15f),
                coiffures = w(Anatomy.Coiffure.CREST to 2, Anatomy.Coiffure.CAP to 2, Anatomy.Coiffure.NONE to 1),
                finishes = w(Anatomy.Finish.KAOLIN to 3, Anatomy.Finish.BLACKENED to 3),
                patterns = c(Anatomy.Pattern.EYE_RINGS to 0.4f, Anatomy.Pattern.BANDS to 0.2f),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.OCHRE, Pigments.LAMPBLACK),
                glows = listOf(MOON, EMBER), raffia = 0.2f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.WATER_SPIRIT, temper = T.RESTLESS, fractures = w(F.SPLIT_VISAGE to 2, F.DISSOLVED_CHIN to 2, F.FLOATING_QUADRANTS to 1), cores = w(C.INDIGO to 3, C.MOON to 2, C.MARSH to 1), relics = c(Rl.COWRIE to 0.3f)),
        ),
        Tradition(
            "ikenga", "Ikenga", "Igbo", "South-eastern Nigeria",
            "The spirit of a man's right hand: his strength, achievement and success. Ram horns curl from its head, for the ram that butts forward.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 3), outlines = w(Anatomy.Outline.SQUARE to 2, Anatomy.Outline.SHIELD to 2),
                width = 0.5f..0.8f, length = 0.4f..0.7f,
                brows = w(Anatomy.Brow.SHELF to 3, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.BEAN to 2, Anatomy.Eyes.TUBE to 2, Anatomy.Eyes.SLIT to 1),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 2), mouths = w(Anatomy.Mouth.CLOSED to 2, Anatomy.Mouth.TEETH to 2, Anatomy.Mouth.BOX to 1),
                scars = c(Anatomy.Scar.ICHI to 0.8f, Anatomy.Scar.TEMPLES to 0.6f),
                coiffures = w(Anatomy.Coiffure.CAP to 2, Anatomy.Coiffure.NONE to 1),
                crowns = w(Anatomy.Crown.RAM to 6), crownSize = 0.6f..1f,
                beards = w(Anatomy.Beard.CARVED to 3, Anatomy.Beard.NONE to 1),
                finishes = w(Anatomy.Finish.BLACKENED to 3, Anatomy.Finish.RAW to 2),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER, Pigments.MAHOGANY), horns = listOf(Pigments.UMBER, Pigments.BLACKWOOD, Pigments.BONE),
                glows = listOf(GOLDEN, EMBER), raffia = 0f..0.3f,
            ),
            spirit = SpiritGrammar(role = R.PERSONAL_POWER, temper = T.MONUMENTAL, fractures = w(F.SHATTERED_CROWN to 2, F.SUSPENDED_FACETS to 2), cores = w(C.SOLAR to 3, C.CAMWOOD to 1), relics = c(Rl.BRONZE_PLAQUE to 0.3f)),
        ),
        Tradition(
            "ijele", "Ijele", "Igbo", "Anambra, south-eastern Nigeria",
            "The king of masks: the grandest masquerade, with a towering superstructure of tiers, figures and mirrors. It appears rarely, for great occasions.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 3), outlines = w(Anatomy.Outline.OVAL to 2, Anatomy.Outline.ROUND to 1),
                width = 0.5f..0.8f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.BEAN to 2),
                noses = w(Anatomy.Nose.LONG to 3), mouths = w(Anatomy.Mouth.CLOSED to 3),
                scars = c(Anatomy.Scar.ICHI to 0.4f),
                coiffures = w(Anatomy.Coiffure.CAP to 1), crowns = w(Anatomy.Crown.TIERS to 6), crownSize = 0.6f..1f, counts = 3..4,
                adorn = c(Anatomy.Adorn.COWRIES to 0.6f, Anatomy.Adorn.BRASS_STUDS to 0.7f, Anatomy.Adorn.RAFFIA_COLLAR to 0.6f),
                finishes = w(Anatomy.Finish.POLYCHROME to 4, Anatomy.Finish.KAOLIN to 1),
                patterns = c(Anatomy.Pattern.BANDS to 0.5f, Anatomy.Pattern.TRIANGLES to 0.5f),
                woods = listOf(Pigments.MAHOGANY, Pigments.UMBER), accents = listOf(Pigments.VERMILION, Pigments.OCHRE, Pigments.JADE, Pigments.KAOLIN),
                glows = listOf(GOLDEN), raffia = 0.5f..1f,
            ),
            spirit = SpiritGrammar(role = R.ROYAL_MAJESTY, temper = T.MONUMENTAL, fractures = w(F.SHATTERED_CROWN to 5, F.SUSPENDED_FACETS to 1), cores = w(C.SOLAR to 2, C.FIREFLY to 2), relics = c(Rl.MIRROR to 0.7f, Rl.BRASS_BELL to 0.5f, Rl.BRONZE_PLAQUE to 0.3f)),
        ),
        Tradition(
            "afikpo", "Okumkpa", "Afikpo Igbo (Ehugbo)", "Ebonyi, south-eastern Nigeria",
            "Masks of the Okumkpa satirical plays: light, long and narrow faces, often split in two colours, with small features and a knob at the chin.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 4), outlines = w(Anatomy.Outline.LONG to 5), width = 0.15f..0.4f, length = 0.75f..1f,
                brows = w(Anatomy.Brow.ARCH to 2, Anatomy.Brow.NONE to 1), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.DIAMOND to 1, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.LONG to 3, Anatomy.Nose.BEAK to 1), mouths = w(Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.TEETH to 1, Anatomy.Mouth.OPEN to 1),
                scars = c(Anatomy.Scar.ICHI to 0.3f),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CREST to 1),
                finishes = w(Anatomy.Finish.POLYCHROME to 3, Anatomy.Finish.BLACKENED to 1),
                patterns = c(Anatomy.Pattern.SPLIT to 0.6f, Anatomy.Pattern.BANDS to 0.3f),
                woods = listOf(Pigments.IROKO, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.OCHRE, Pigments.LAMPBLACK),
                glows = listOf(EMBER, MOON), raffia = 0.4f..0.9f,
            ),
            spirit = SpiritGrammar(role = R.SATIRE, temper = T.RESTLESS, fractures = w(F.SPLIT_VISAGE to 4, F.FLOATING_QUADRANTS to 2, F.DRIFTING_JAW to 1), cores = w(C.FIREFLY to 2, C.SOLAR to 1, C.MOON to 1)),
        ),
        Tradition(
            "ogbodo_enyi", "Ogbodo Enyi", "Igbo", "Northern Igbo, south-eastern Nigeria",
            "The elephant spirit: a massive helmet with great flat ears and a bold, gaping mouth, danced to show the might of a youth's age grade.",
            "spirit:ogbodo-enyi",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 4), outlines = w(Anatomy.Outline.ROUND to 3, Anatomy.Outline.SQUARE to 1), width = 0.7f..1f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.TUBE to 2),
                noses = w(Anatomy.Nose.BROAD to 3), mouths = w(Anatomy.Mouth.TEETH to 3, Anatomy.Mouth.OPEN to 1),
                ears = w(Anatomy.Ears.ELEPHANT to 6), coiffures = w(Anatomy.Coiffure.CAP to 1, Anatomy.Coiffure.KNOTS to 1), counts = 1..3,
                crowns = w(Anatomy.Crown.NONE to 2, Anatomy.Crown.BUFFALO to 1),
                finishes = w(Anatomy.Finish.BLACKENED to 3, Anatomy.Finish.POLYCHROME to 1),
                patterns = c(Anatomy.Pattern.EYE_RINGS to 0.6f),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.CAMWOOD), glows = listOf(EMBER, BLOOD), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.MARTIAL_FEROCITY, temper = T.MONUMENTAL, fractures = w(F.DRIFTING_JAW to 4, F.SUSPENDED_FACETS to 2), cores = w(C.CAMWOOD to 2, C.SOLAR to 2), relics = c(Rl.BRASS_BELL to 0.3f)),
        ),
        Tradition(
            "punu", "Okuyi", "Punu", "Southern Gabon",
            "White maiden masks of the ancestors, danced on stilts: a serene kaolin face, coffee-bean eyes under arched brows, keloid diamonds on brow and temples, and a lobed coiffure.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.HEART to 1), width = 0.4f..0.6f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.ARCH to 5), eyes = w(Anatomy.Eyes.BEAN to 6), noses = w(Anatomy.Nose.LONG to 2, Anatomy.Nose.BROAD to 1),
                mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.PURSED to 1),
                scars = c(Anatomy.Scar.KELOID_DIAMONDS to 0.9f, Anatomy.Scar.CHEEK_KELOIDS to 0.3f),
                coiffures = w(Anatomy.Coiffure.LOBES to 6), finishes = w(Anatomy.Finish.KAOLIN to 6),
                patterns = c(Anatomy.Pattern.EYE_RINGS to 0.2f),
                woods = listOf(Pigments.UMBER, Pigments.BLACKWOOD), hairs = listOf(Pigments.LAMPBLACK), accents = listOf(Pigments.CAMWOOD, Pigments.LAMPBLACK), glows = listOf(MOON),
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_PURITY, temper = T.AUSTERE, fractures = w(F.SHATTERED_CROWN to 3, F.SPLIT_VISAGE to 1, F.SUSPENDED_FACETS to 1), cores = w(C.MOON to 4), relics = c(Rl.HALO to 0.3f), drift = 0.2f..0.55f),
        ),
        Tradition(
            "fang", "Ngil", "Fang", "Gabon and Equatorial Guinea",
            "The long white mask of the Ngil society, which once sought out wrongdoers: a concave heart-shaped face plane, a long thin nose and narrow eyes, whitened with kaolin.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.CONCAVE to 5, Anatomy.Outline.LONG to 1), width = 0.25f..0.45f, length = 0.8f..1f,
                brows = w(Anatomy.Brow.HEART to 6), eyes = w(Anatomy.Eyes.CRESCENT to 3, Anatomy.Eyes.SLIT to 2), noses = w(Anatomy.Nose.LONG to 5),
                mouths = w(Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.CLOSED to 2),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.6f),
                coiffures = w(Anatomy.Coiffure.CREST to 3, Anatomy.Coiffure.NONE to 1), finishes = w(Anatomy.Finish.KAOLIN to 6),
                woods = listOf(Pigments.UMBER, Pigments.BLACKWOOD), accents = listOf(Pigments.LAMPBLACK), glows = listOf(MOON),
            ),
            spirit = SpiritGrammar(role = R.JUDICIAL_JUSTICE, temper = T.AUSTERE, fractures = w(F.SPLIT_VISAGE to 4, F.SUSPENDED_FACETS to 2), cores = w(C.MOON to 3, C.LEOPARD to 1), drift = 0.2f..0.5f),
        ),
        Tradition(
            "dan", "Deangle and Gunye Ge", "Dan", "Liberia and Côte d'Ivoire",
            "Smooth, glossy masks with a calm oval face: gentle slit-eyed Deangle who carry messages, and the round-eyed runners of the Gunye Ge races.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 4), outlines = w(Anatomy.Outline.OVAL to 5), width = 0.4f..0.65f, length = 0.4f..0.65f,
                brows = w(Anatomy.Brow.BULGE to 3, Anatomy.Brow.NONE to 1), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.RINGED to 3, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 1), mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.OPEN to 1),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.5f),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1), finishes = w(Anatomy.Finish.BLACKENED to 6),
                adorn = c(Anatomy.Adorn.RAFFIA_COLLAR to 0.5f), patterns = c(Anatomy.Pattern.EYE_RINGS to 0.4f),
                woods = listOf(Pigments.BLACKWOOD), accents = listOf(Pigments.KAOLIN), glows = listOf(EMBER), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.MEDIATION, temper = T.AUSTERE, fractures = w(F.FLOATING_QUADRANTS to 2, F.SUSPENDED_FACETS to 2, F.DRIFTING_JAW to 1), cores = w(C.SOLAR to 2, C.MOON to 2), drift = 0.2f..0.55f),
        ),
        Tradition(
            "songye", "Kifwebe", "Songye", "Democratic Republic of the Congo",
            "Kifwebe masks of a powerful society: the whole face carved in parallel striations painted white, a boxy projecting mouth, deep eye cuts and a tall sagittal crest.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.SHIELD to 3, Anatomy.Outline.LONG to 1), width = 0.45f..0.7f, length = 0.5f..0.8f,
                brows = w(Anatomy.Brow.SHELF to 3), eyes = w(Anatomy.Eyes.CRESCENT to 2, Anatomy.Eyes.BEAN to 2), noses = w(Anatomy.Nose.TRIANGLE to 3, Anatomy.Nose.LONG to 1),
                mouths = w(Anatomy.Mouth.BOX to 6),
                scars = c(Anatomy.Scar.STRIATIONS to 1f),
                coiffures = w(Anatomy.Coiffure.CREST to 6), finishes = w(Anatomy.Finish.POLYCHROME to 4),
                patterns = c(Anatomy.Pattern.STRIATED to 1f),
                woods = listOf(Pigments.UMBER, Pigments.MAHOGANY), accents = listOf(Pigments.KAOLIN), glows = listOf(BLOOD, EMBER), raffia = 0.5f..1f,
            ),
            spirit = SpiritGrammar(role = R.COSMIC_POWER, temper = T.STORM, fractures = w(F.FLOATING_QUADRANTS to 3, F.SUSPENDED_FACETS to 3, F.SHATTERED_CROWN to 1), cores = w(C.THUNDER to 3, C.LEOPARD to 2, C.CAMWOOD to 1), drift = 0.45f..0.9f),
        ),
        Tradition(
            "bwa", "Nwantantay", "Bwa", "Burkina Faso",
            "Plank masks of the spirits of the bush: a small round-eyed face under a tall board painted with checkers, triangles and concentric circles, topped by a crescent hook.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.ROUND to 3, Anatomy.Outline.OVAL to 1), width = 0.45f..0.6f, length = 0.3f..0.5f,
                brows = w(Anatomy.Brow.NONE to 2, Anatomy.Brow.SHELF to 1), eyes = w(Anatomy.Eyes.RINGED to 6), noses = w(Anatomy.Nose.BEAK to 4, Anatomy.Nose.TRIANGLE to 1),
                mouths = w(Anatomy.Mouth.TUBE to 3, Anatomy.Mouth.PURSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.PLANK to 6, Anatomy.Crown.BIRD to 1), crownSize = 0.6f..1f,
                finishes = w(Anatomy.Finish.POLYCHROME to 6),
                patterns = c(Anatomy.Pattern.CHECKER to 0.8f, Anatomy.Pattern.CONCENTRIC to 0.9f, Anatomy.Pattern.TRIANGLES to 0.6f),
                woods = listOf(Pigments.UMBER, Pigments.IROKO), accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.VERMILION), glows = listOf(EMBER), raffia = 0.6f..1f,
            ),
            spirit = SpiritGrammar(role = R.BUSH_SPIRIT, temper = T.MONUMENTAL, fractures = w(F.SHATTERED_CROWN to 5, F.DISSOLVED_CHIN to 1), cores = w(C.FIREFLY to 3, C.MARSH to 2), relics = c(Rl.HALO to 0.2f)),
        ),
        Tradition(
            "dogon", "Kanaga", "Dogon", "Bandiagara, Mali",
            "Masks danced at the dama that sends the dead on their way: a rectangular face with deep eye troughs under the kanaga, a double-barred cross read as a bird in flight or the order of the world.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.SQUARE to 5), width = 0.35f..0.55f, length = 0.6f..0.9f,
                brows = w(Anatomy.Brow.SHELF to 4), eyes = w(Anatomy.Eyes.TROUGH to 6), noses = w(Anatomy.Nose.TRIANGLE to 3, Anatomy.Nose.LONG to 1),
                mouths = w(Anatomy.Mouth.BOX to 2, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.KANAGA to 6), crownSize = 0.6f..1f,
                finishes = w(Anatomy.Finish.POLYCHROME to 3, Anatomy.Finish.RAW to 2),
                patterns = c(Anatomy.Pattern.TRIANGLES to 0.4f, Anatomy.Pattern.DOTS to 0.4f),
                woods = listOf(Pigments.IROKO, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.VERMILION), glows = listOf(EMBER), raffia = 0.6f..1f,
            ),
            spirit = SpiritGrammar(role = R.AXIS_MUNDI, temper = T.MONUMENTAL, fractures = w(F.SHATTERED_CROWN to 4, F.FLOATING_QUADRANTS to 2), cores = w(C.SOLAR to 3, C.MOON to 1), relics = c(Rl.KANAGA to 0.5f)),
        ),
        Tradition(
            "kuba", "Ngady aMwaash", "Kuba", "Democratic Republic of the Congo",
            "The royal wife of the Kuba kingship masks: a face painted in bands of triangles, tear lines down the cheeks, and beads and cowries across the brow.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.OVAL to 2, Anatomy.Outline.SHIELD to 1), width = 0.45f..0.65f, length = 0.45f..0.65f,
                brows = w(Anatomy.Brow.ARCH to 2, Anatomy.Brow.NONE to 1), eyes = w(Anatomy.Eyes.BEAN to 2, Anatomy.Eyes.SLIT to 1),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 1), mouths = w(Anatomy.Mouth.CLOSED to 2, Anatomy.Mouth.OPEN to 1),
                scars = c(Anatomy.Scar.TEARS to 0.9f),
                coiffures = w(Anatomy.Coiffure.CAP to 3), adorn = c(Anatomy.Adorn.COWRIES to 0.9f, Anatomy.Adorn.BEADS to 0.7f),
                finishes = w(Anatomy.Finish.POLYCHROME to 6),
                patterns = c(Anatomy.Pattern.TRIANGLES to 0.9f, Anatomy.Pattern.BANDS to 0.4f),
                woods = listOf(Pigments.UMBER, Pigments.MAHOGANY), accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.OCHRE), glows = listOf(GOLDEN),
            ),
            spirit = SpiritGrammar(role = R.ROYAL_MAJESTY, temper = T.BREATHING, fractures = w(F.SUSPENDED_FACETS to 3, F.DISSOLVED_CHIN to 2), cores = w(C.SOLAR to 3, C.FIREFLY to 1), relics = c(Rl.COWRIE to 0.8f, Rl.BRONZE_PLAQUE to 0.2f)),
        ),
        Tradition(
            "chokwe", "Mwana Pwo", "Chokwe", "Angola, DRC and Zambia",
            "The young woman ancestor, danced by men to honour women's fertility: a gentle face with half-closed eyes, a cruciform mark on the brow, scarified cheeks and tears.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.HEART to 1), width = 0.4f..0.6f, length = 0.45f..0.65f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.BEAN to 4, Anatomy.Eyes.SLIT to 1), noses = w(Anatomy.Nose.LONG to 2, Anatomy.Nose.BROAD to 1),
                mouths = w(Anatomy.Mouth.TEETH to 2, Anatomy.Mouth.OPEN to 2),
                scars = c(Anatomy.Scar.TEARS to 0.8f, Anatomy.Scar.CHEEK_KELOIDS to 0.7f, Anatomy.Scar.CENTRE_RIDGE to 0.4f),
                coiffures = w(Anatomy.Coiffure.CORNROWS to 4), finishes = w(Anatomy.Finish.CAMWOOD to 5, Anatomy.Finish.RAW to 1),
                adorn = c(Anatomy.Adorn.EARRINGS to 0.6f),
                woods = listOf(Pigments.CAMWOOD, Pigments.MAHOGANY), hairs = listOf(Pigments.LAMPBLACK, Pigments.UMBER), glows = listOf(EMBER),
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_MATRIARCH, temper = T.BREATHING, fractures = w(F.SUSPENDED_FACETS to 3, F.DISSOLVED_CHIN to 2, F.SPLIT_VISAGE to 1), cores = w(C.CAMWOOD to 3, C.SOLAR to 1), relics = c(Rl.HALO to 0.2f)),
        ),
        Tradition(
            "senufo", "Kpeliye'e", "Senufo", "Côte d'Ivoire, Mali and Burkina Faso",
            "A face of the Poro society: a small, calm face framed by flat side flanges like legs, with horns curving over the brow and a hornbill above.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.FACE to 3), outlines = w(Anatomy.Outline.HEART to 3, Anatomy.Outline.OVAL to 1), width = 0.35f..0.55f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.BEAN to 3, Anatomy.Eyes.SLIT to 2), noses = w(Anatomy.Nose.LONG to 3),
                mouths = w(Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.CLOSED to 2),
                scars = c(Anatomy.Scar.CHEEKS to 0.6f, Anatomy.Scar.CENTRE_RIDGE to 0.5f),
                coiffures = w(Anatomy.Coiffure.TOPKNOT to 2, Anatomy.Coiffure.CAP to 1), crowns = w(Anatomy.Crown.BIRD to 3, Anatomy.Crown.ANTELOPE to 2), crownSize = 0.3f..0.6f,
                finishes = w(Anatomy.Finish.BLACKENED to 3, Anatomy.Finish.RAW to 2),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), glows = listOf(GOLDEN),
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.AUSTERE, fractures = w(F.SHATTERED_CROWN to 3, F.SUSPENDED_FACETS to 2), cores = w(C.SOLAR to 2, C.FIREFLY to 2, C.MARSH to 1)),
        ),
        Tradition(
            "gelede", "Gelede", "Yoruba", "South-western Nigeria and Benin",
            "Danced in pairs to honour and appease the Mothers (awon iya wa), the elder women whose power can heal or harm: a calm helmet face with full cheeks and heavy-lidded eyes under a carved superstructure, brightly painted.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 4), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.ROUND to 1), width = 0.55f..0.8f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.BEAN to 4, Anatomy.Eyes.SLIT to 1), noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 1),
                mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.PURSED to 1), ears = w(Anatomy.Ears.SMALL to 3, Anatomy.Ears.NONE to 1),
                scars = c(Anatomy.Scar.CHEEKS to 0.6f, Anatomy.Scar.TEMPLES to 0.4f),
                coiffures = w(Anatomy.Coiffure.CAP to 2, Anatomy.Coiffure.KNOTS to 1, Anatomy.Coiffure.CREST to 1),
                crowns = w(Anatomy.Crown.TIERS to 4, Anatomy.Crown.BIRD to 2, Anatomy.Crown.NONE to 1), crownSize = 0.4f..0.9f, counts = 2..3,
                finishes = w(Anatomy.Finish.POLYCHROME to 6), patterns = c(Anatomy.Pattern.BANDS to 0.5f, Anatomy.Pattern.DOTS to 0.3f, Anatomy.Pattern.EYE_RINGS to 0.2f),
                woods = listOf(Pigments.IROKO, Pigments.UMBER), accents = listOf(Pigments.VERMILION, Pigments.INDIGO, Pigments.KAOLIN, Pigments.OCHRE, Pigments.JADE),
                glows = listOf(GOLDEN, MOON), raffia = 0.2f..0.6f,
            ),
            spirit = SpiritGrammar(role = R.OUR_MOTHERS, temper = T.MONUMENTAL, fractures = w(F.SHATTERED_CROWN to 4, F.SUSPENDED_FACETS to 2), cores = w(C.MOON to 2, C.SOLAR to 2, C.INDIGO to 1), relics = c(Rl.BRASS_BELL to 0.5f, Rl.MIRROR to 0.2f)),
        ),
        Tradition(
            "mwaash_ambooy", "Mwaash aMbooy", "Kuba", "Democratic Republic of the Congo",
            "The royal mask of Woot, the founding ancestor, danced by or for the king: a helmet of raffia cloth and hide worked with cowries, beads and copper, its features built up in relief.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 5), outlines = w(Anatomy.Outline.ROUND to 2, Anatomy.Outline.OVAL to 2), width = 0.6f..0.85f, length = 0.45f..0.65f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.BEAN to 2, Anatomy.Eyes.ROUND to 1, Anatomy.Eyes.SLIT to 1),
                noses = w(Anatomy.Nose.BROAD to 3), mouths = w(Anatomy.Mouth.CLOSED to 2, Anatomy.Mouth.BOX to 1), ears = w(Anatomy.Ears.SMALL to 2, Anatomy.Ears.NONE to 1),
                scars = c(Anatomy.Scar.TEARS to 0.3f),
                coiffures = w(Anatomy.Coiffure.CAP to 3), crowns = w(Anatomy.Crown.NONE to 3, Anatomy.Crown.BIRD to 1),
                beards = w(Anatomy.Beard.RAFFIA to 2, Anatomy.Beard.NONE to 1),
                adorn = c(Anatomy.Adorn.COWRIES to 1f, Anatomy.Adorn.BEADS to 0.9f, Anatomy.Adorn.BRASS_STUDS to 0.8f, Anatomy.Adorn.RAFFIA_COLLAR to 0.4f),
                finishes = w(Anatomy.Finish.BRASS to 3, Anatomy.Finish.POLYCHROME to 3), patterns = c(Anatomy.Pattern.TRIANGLES to 0.8f, Anatomy.Pattern.BANDS to 0.5f),
                woods = listOf(Pigments.UMBER, Pigments.MAHOGANY), accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.OCHRE, Pigments.LAPIS),
                glows = listOf(GOLDEN), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.ROYAL_MAJESTY, temper = T.MONUMENTAL, fractures = w(F.SUSPENDED_FACETS to 3, F.DISSOLVED_CHIN to 2), cores = w(C.SOLAR to 3, C.FIREFLY to 1), relics = c(Rl.COWRIE to 0.9f, Rl.BRONZE_PLAQUE to 0.5f)),
        ),
        Tradition(
            "bugle", "Bugle", "Dan and Kran", "Liberia and Côte d'Ivoire",
            "The fierce masks once tied to war and the settling of disputes: projecting tube eyes, a heavy jutting mouth and nose, darkened wood and coarse fibre, the opposite of the gentle Deangle.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 4), outlines = w(Anatomy.Outline.OVAL to 2, Anatomy.Outline.SQUARE to 1, Anatomy.Outline.SHIELD to 1), width = 0.5f..0.75f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.SHELF to 3, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.TUBE to 6, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.TRIANGLE to 3, Anatomy.Nose.BROAD to 2), mouths = w(Anatomy.Mouth.TEETH to 3, Anatomy.Mouth.OPEN to 2, Anatomy.Mouth.BOX to 1),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.3f),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1),
                beards = w(Anatomy.Beard.RAFFIA to 3, Anatomy.Beard.NONE to 1), adorn = c(Anatomy.Adorn.RAFFIA_COLLAR to 0.6f, Anatomy.Adorn.BRASS_STUDS to 0.3f),
                finishes = w(Anatomy.Finish.BLACKENED to 5, Anatomy.Finish.RAW to 1), patterns = c(Anatomy.Pattern.EYE_RINGS to 0.3f),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), accents = listOf(Pigments.KAOLIN, Pigments.VERMILION), glows = listOf(BLOOD, EMBER), raffia = 0.5f..1f,
            ),
            spirit = SpiritGrammar(role = R.MARTIAL_FEROCITY, temper = T.STORM, fractures = w(F.DRIFTING_JAW to 3, F.SUSPENDED_FACETS to 2, F.SPLIT_VISAGE to 1), cores = w(C.THUNDER to 2, C.CAMWOOD to 2, C.LEOPARD to 1), drift = 0.45f..0.9f),
        ),        Tradition(
            "baule_mblo", "Mblo", "Baule", "Central Côte d'Ivoire",
            "Portrait masks danced in entertainments to honour an admired person by name: a serene oval face, downcast eyes under arched brows, a small mouth, marks at the temples and a finely carved coiffure.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.LONG to 2), width = 0.3f..0.5f, length = 0.55f..0.85f,
                brows = w(Anatomy.Brow.ARCH to 4), eyes = w(Anatomy.Eyes.SLIT to 3, Anatomy.Eyes.DOWNCAST to 2, Anatomy.Eyes.CRESCENT to 1),
                noses = w(Anatomy.Nose.LONG to 4), mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.PURSED to 2),
                scars = c(Anatomy.Scar.TEMPLES to 0.6f, Anatomy.Scar.KELOID_DIAMONDS to 0.3f, Anatomy.Scar.CHEEK_KELOIDS to 0.3f),
                coiffures = w(Anatomy.Coiffure.ARCHES to 2, Anatomy.Coiffure.CREST to 2, Anatomy.Coiffure.KNOTS to 2, Anatomy.Coiffure.TRIPLE_CREST to 1),
                crowns = w(Anatomy.Crown.NONE to 4, Anatomy.Crown.BIRD to 1), counts = 2..4,
                finishes = w(Anatomy.Finish.BLACKENED to 5, Anatomy.Finish.RAW to 1),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), glows = listOf(GOLDEN, MOON), raffia = 0f..0.2f,
            ),
            spirit = SpiritGrammar(role = R.MEDIATION, temper = T.BREATHING, fractures = w(F.SUSPENDED_FACETS to 2, F.SHATTERED_CROWN to 2), cores = w(C.MOON to 2, C.SOLAR to 1)),
        ),
        Tradition(
            "baule_goli", "Kple Kple", "Baule", "Central Côte d'Ivoire",
            "The first mask of the Goli dance, which came to the Baule from the Wan: a flat disc face with short horns, round eyes and a square mouth, painted red or black.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.DISC to 5), width = 0.7f..1f, length = 0.35f..0.55f,
                brows = w(Anatomy.Brow.NONE to 3, Anatomy.Brow.SHELF to 1), eyes = w(Anatomy.Eyes.ROUND to 4, Anatomy.Eyes.TUBE to 1),
                noses = w(Anatomy.Nose.NONE to 3, Anatomy.Nose.TRIANGLE to 1), mouths = w(Anatomy.Mouth.BOX to 4),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.ANTELOPE to 3, Anatomy.Crown.BUFFALO to 1), crownSize = 0.2f..0.45f,
                finishes = w(Anatomy.Finish.POLYCHROME to 3, Anatomy.Finish.BLACKENED to 2), patterns = c(Anatomy.Pattern.SPLIT to 0.2f),
                accents = listOf(Pigments.VERMILION, Pigments.LAMPBLACK, Pigments.KAOLIN), horns = listOf(Pigments.BLACKWOOD, Pigments.VERMILION), glows = listOf(BLOOD, EMBER), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.COSMIC_POWER, temper = T.RESTLESS, fractures = w(F.FLOATING_QUADRANTS to 3, F.SPLIT_VISAGE to 2), cores = w(C.CAMWOOD to 2, C.SOLAR to 2)),
        ),
        Tradition(
            "guro_gu", "Gu", "Guro", "Central Côte d'Ivoire",
            "Gu, the wife of the bush spirit Zamble: a refined, elongated face with a high forehead, slit eyes and a small mouth, painted, under a carved coiffure.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 4, Anatomy.Outline.OVAL to 1), width = 0.25f..0.45f, length = 0.65f..0.95f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.SLIT to 3, Anatomy.Eyes.CRESCENT to 1),
                noses = w(Anatomy.Nose.LONG to 4), mouths = w(Anatomy.Mouth.CLOSED to 2, Anatomy.Mouth.PURSED to 2),
                scars = c(Anatomy.Scar.TEMPLES to 0.4f, Anatomy.Scar.CENTRE_RIDGE to 0.3f),
                coiffures = w(Anatomy.Coiffure.CREST to 2, Anatomy.Coiffure.KNOTS to 2, Anatomy.Coiffure.COMBS to 1), counts = 2..4,
                finishes = w(Anatomy.Finish.POLYCHROME to 3, Anatomy.Finish.BLACKENED to 2), patterns = c(Anatomy.Pattern.BANDS to 0.3f, Anatomy.Pattern.TRIANGLES to 0.3f),
                accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.OCHRE), glows = listOf(MOON, GOLDEN),
                dials = mapOf(Anatomy.Dial.FOREHEAD to 0.65f..0.95f),
            ),
            spirit = SpiritGrammar(role = R.MEDIATION, temper = T.BREATHING, fractures = w(F.SHATTERED_CROWN to 2, F.SUSPENDED_FACETS to 2), cores = w(C.MOON to 2, C.FIREFLY to 1)),
        ),
        Tradition(
            "guro_zamble", "Zamble", "Guro", "Central Côte d'Ivoire",
            "A bush spirit of the Guro, part antelope and part leopard: a long muzzle with bared teeth, horns, and painted spots or bands.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 3, Anatomy.Outline.SHIELD to 1), width = 0.35f..0.6f, length = 0.6f..0.95f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.TUBE to 2),
                noses = w(Anatomy.Nose.BEAK to 2, Anatomy.Nose.BROAD to 1), mouths = w(Anatomy.Mouth.TEETH to 4, Anatomy.Mouth.GRIN to 2),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.ANTELOPE to 4), crownSize = 0.5f..0.9f,
                finishes = w(Anatomy.Finish.POLYCHROME to 4), patterns = c(Anatomy.Pattern.DOTS to 0.6f, Anatomy.Pattern.BANDS to 0.3f),
                accents = listOf(Pigments.OCHRE, Pigments.LAMPBLACK, Pigments.KAOLIN, Pigments.VERMILION), glows = listOf(EMBER, BLOOD),
                dials = mapOf(Anatomy.Dial.MUZZLE to 0.7f..1f),
            ),
            spirit = SpiritGrammar(role = R.BUSH_SPIRIT, temper = T.RESTLESS, fractures = w(F.DRIFTING_JAW to 3, F.SUSPENDED_FACETS to 1), cores = w(C.LEOPARD to 3, C.MARSH to 1)),
        ),
        Tradition(
            "we", "Gla", "We (Guere)", "Western Côte d'Ivoire and Liberia",
            "Masks of the We that judge and keep order: a mass of projecting forms, bulging eyes, a jutting toothed mouth and horns, hung with nails, cloth, fur and cowries.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.SQUARE to 2, Anatomy.Outline.OVAL to 2), width = 0.55f..0.85f, length = 0.5f..0.8f,
                brows = w(Anatomy.Brow.SHELF to 3, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.BULGING to 4, Anatomy.Eyes.TUBE to 2),
                noses = w(Anatomy.Nose.BROAD to 3), mouths = w(Anatomy.Mouth.TEETH to 3, Anatomy.Mouth.OPEN to 1, Anatomy.Mouth.GRIN to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1),
                crowns = w(Anatomy.Crown.ANTELOPE to 2, Anatomy.Crown.BUFFALO to 1, Anatomy.Crown.HORN_ROW to 1, Anatomy.Crown.NONE to 1), crownSize = 0.3f..0.7f,
                beards = w(Anatomy.Beard.RAFFIA to 3, Anatomy.Beard.NONE to 1),
                adorn = c(Anatomy.Adorn.BRASS_STUDS to 0.6f, Anatomy.Adorn.RAFFIA_COLLAR to 0.5f, Anatomy.Adorn.COWRIES to 0.4f),
                finishes = w(Anatomy.Finish.POLYCHROME to 3, Anatomy.Finish.BLACKENED to 2), patterns = c(Anatomy.Pattern.EYE_RINGS to 0.4f, Anatomy.Pattern.BANDS to 0.2f),
                accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.INDIGO), glows = listOf(BLOOD, EMBER), raffia = 0.4f..0.9f,
            ),
            spirit = SpiritGrammar(role = R.JUDICIAL_JUSTICE, temper = T.STORM, fractures = w(F.SUSPENDED_FACETS to 3, F.DRIFTING_JAW to 2), cores = w(C.THUNDER to 2, C.CAMWOOD to 2), relics = c(Rl.COWRIE to 0.4f, Rl.BRASS_BELL to 0.3f), drift = 0.45f..0.9f),
        ),
        Tradition(
            "bamana_ntomo", "N'tomo", "Bamana", "Southern Mali",
            "The mask of n'tomo, the society of boys before initiation: a long face under a row of upright horns, its small mouth a reminder to master speech, often covered in cowries or red seeds.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 4), width = 0.3f..0.5f, length = 0.6f..0.9f,
                brows = w(Anatomy.Brow.HEART to 2, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.DIAMOND to 1),
                noses = w(Anatomy.Nose.LONG to 4), mouths = w(Anatomy.Mouth.PURSED to 3, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.HORN_ROW to 6), crownSize = 0.3f..0.7f, counts = 3..6,
                adorn = c(Anatomy.Adorn.COWRIES to 0.5f, Anatomy.Adorn.BRASS_STUDS to 0.4f),
                finishes = w(Anatomy.Finish.BLACKENED to 3, Anatomy.Finish.RAW to 2),
                woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER, Pigments.IROKO), horns = listOf(Pigments.BLACKWOOD, Pigments.UMBER), glows = listOf(GOLDEN, EMBER),
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.AUSTERE, fractures = w(F.SHATTERED_CROWN to 3, F.SPLIT_VISAGE to 1), cores = w(C.SOLAR to 2, C.FIREFLY to 1), relics = c(Rl.COWRIE to 0.5f)),
        ),
        Tradition(
            "mossi", "Karanga", "Mossi", "Yatenga, Burkina Faso",
            "Mossi masks of the Yatenga: an oval face divided by a vertical ridge, with triangular eye holes, under a tall painted plank or a pair of antelope horns.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.SHIELD to 1), width = 0.4f..0.65f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.NONE to 2, Anatomy.Brow.SHELF to 1), eyes = w(Anatomy.Eyes.DIAMOND to 3, Anatomy.Eyes.TROUGH to 2),
                noses = w(Anatomy.Nose.NONE to 2, Anatomy.Nose.LONG to 1), mouths = w(Anatomy.Mouth.CLOSED to 1, Anatomy.Mouth.BOX to 1, Anatomy.Mouth.PURSED to 1),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.9f), coiffures = w(Anatomy.Coiffure.NONE to 3),
                crowns = w(Anatomy.Crown.PLANK to 4, Anatomy.Crown.ANTELOPE to 2), crownSize = 0.6f..1f,
                finishes = w(Anatomy.Finish.POLYCHROME to 4), patterns = c(Anatomy.Pattern.TRIANGLES to 0.6f, Anatomy.Pattern.CHECKER to 0.5f, Anatomy.Pattern.BANDS to 0.3f),
                accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.LAMPBLACK, Pigments.OCHRE), glows = listOf(GOLDEN, EMBER),
            ),
            spirit = SpiritGrammar(role = R.AXIS_MUNDI, temper = T.MONUMENTAL, fractures = w(F.SPLIT_VISAGE to 3, F.SHATTERED_CROWN to 2), cores = w(C.SOLAR to 2, C.CAMWOOD to 1)),
        ),
        Tradition(
            "mende_sowei", "Sowei", "Mende", "Sierra Leone and Liberia",
            "The helmet mask of Sande, the women's society, and among the few masks danced by women: glossy black, a small serene face set low, rings of the neck for beauty and plenty, and an elaborate coiffure.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 5), outlines = w(Anatomy.Outline.OVAL to 3), width = 0.5f..0.75f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.ARCH to 2, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.SLIT to 4, Anatomy.Eyes.CRESCENT to 1),
                noses = w(Anatomy.Nose.LONG to 2, Anatomy.Nose.TRIANGLE to 1), mouths = w(Anatomy.Mouth.CLOSED to 3, Anatomy.Mouth.PURSED to 1),
                scars = c(Anatomy.Scar.TEMPLES to 0.3f),
                coiffures = w(Anatomy.Coiffure.ARCHES to 3, Anatomy.Coiffure.CREST to 2, Anatomy.Coiffure.LOBES to 2, Anatomy.Coiffure.KNOTS to 1), counts = 3..5,
                crowns = w(Anatomy.Crown.NONE to 4, Anatomy.Crown.BIRD to 1),
                adorn = c(Anatomy.Adorn.NECK_RINGS to 1f, Anatomy.Adorn.COWRIES to 0.2f),
                finishes = w(Anatomy.Finish.BLACKENED to 6),
                woods = listOf(Pigments.BLACKWOOD, Pigments.LAMPBLACK), hairs = listOf(Pigments.LAMPBLACK), glows = listOf(MOON, GOLDEN), raffia = 0.4f..0.9f,
                dials = mapOf(Anatomy.Dial.FOREHEAD to 0.6f..0.9f),
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_MATRIARCH, temper = T.BREATHING, fractures = w(F.SHATTERED_CROWN to 3, F.DISSOLVED_CHIN to 1), cores = w(C.INDIGO to 2, C.MOON to 2), relics = c(Rl.MIRROR to 0.2f)),
        ),
        Tradition(
            "toma", "Landai", "Toma (Loma)", "Guinea and Liberia",
            "The great mask of the Toma men's society: a long flat face under a broad jutting shelf of forehead, a long straight nose, and horns or feathers above.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 4, Anatomy.Outline.SQUARE to 1), width = 0.35f..0.6f, length = 0.75f..1f,
                brows = w(Anatomy.Brow.SHELF to 5), eyes = w(Anatomy.Eyes.TROUGH to 2, Anatomy.Eyes.SLIT to 2),
                noses = w(Anatomy.Nose.LONG to 5), mouths = w(Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.ANTELOPE to 2, Anatomy.Crown.NONE to 2, Anatomy.Crown.BUFFALO to 1),
                finishes = w(Anatomy.Finish.BLACKENED to 4, Anatomy.Finish.RAW to 1), woods = listOf(Pigments.BLACKWOOD, Pigments.UMBER), glows = listOf(MOON, EMBER),
                dials = mapOf(Anatomy.Dial.BROW_WEIGHT to 0.75f..1f, Anatomy.Dial.CONVEXITY to 0f..0.3f),
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.MONUMENTAL, fractures = w(F.SPLIT_VISAGE to 2, F.SUSPENDED_FACETS to 2), cores = w(C.THUNDER to 1, C.MOON to 1)),
        ),
        Tradition(
            "makonde", "Lipiko", "Makonde", "Tanzania and Mozambique",
            "Helmet masks danced at initiation: a naturalistic face with raised scarification, real hair set in the crown, filed teeth and often a lip plug.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 5), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.ROUND to 1), width = 0.5f..0.75f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.ARCH to 2, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.BEAN to 2, Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.BROAD to 3), mouths = w(Anatomy.Mouth.OPEN to 2, Anatomy.Mouth.TEETH to 2, Anatomy.Mouth.CLOSED to 1),
                ears = w(Anatomy.Ears.SMALL to 4),
                scars = c(Anatomy.Scar.CHEEK_KELOIDS to 0.7f, Anatomy.Scar.KELOID_DIAMONDS to 0.5f, Anatomy.Scar.CENTRE_RIDGE to 0.3f),
                coiffures = w(Anatomy.Coiffure.CAP to 3, Anatomy.Coiffure.CORNROWS to 1),
                adorn = c(Anatomy.Adorn.LIP_PLUG to 0.7f, Anatomy.Adorn.EARRINGS to 0.3f),
                finishes = w(Anatomy.Finish.RAW to 3, Anatomy.Finish.CAMWOOD to 2), woods = listOf(Pigments.IROKO, Pigments.MAHOGANY), glows = listOf(EMBER, GOLDEN),
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.RESTLESS, fractures = w(F.DRIFTING_JAW to 2, F.SUSPENDED_FACETS to 2), cores = w(C.SOLAR to 2, C.CAMWOOD to 1)),
        ),
        Tradition(
            "pende", "Mbuya", "Pende", "Democratic Republic of the Congo",
            "The village masquerade characters of the Pende: heavy-lidded downcast eyes under brows that meet above the nose, a short triangular nose and a fibre beard.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.HEART to 2, Anatomy.Outline.OVAL to 2, Anatomy.Outline.SHIELD to 1), width = 0.45f..0.7f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.HEART to 3, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.DOWNCAST to 6),
                noses = w(Anatomy.Nose.TRIANGLE to 3, Anatomy.Nose.UPTURNED to 1), mouths = w(Anatomy.Mouth.OPEN to 2, Anatomy.Mouth.TEETH to 2, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.CAP to 2, Anatomy.Coiffure.KNOTS to 1, Anatomy.Coiffure.LOBES to 1),
                beards = w(Anatomy.Beard.RAFFIA to 3, Anatomy.Beard.NONE to 1),
                finishes = w(Anatomy.Finish.CAMWOOD to 3, Anatomy.Finish.POLYCHROME to 2), patterns = c(Anatomy.Pattern.EYE_RINGS to 0.2f),
                accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.CAMWOOD), glows = listOf(EMBER, GOLDEN), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.SATIRE, temper = T.RESTLESS, fractures = w(F.DRIFTING_JAW to 2, F.FLOATING_QUADRANTS to 1), cores = w(C.CAMWOOD to 2, C.FIREFLY to 1)),
        ),
        Tradition(
            "lega", "Lukwakongo", "Lega", "Democratic Republic of the Congo",
            "Small masks of the Bwami society, held, worn on the arm or hung on a fence as much as worn: a concave heart-shaped face whitened with kaolin, slit eyes and a fibre beard.",
            "spirit:agbogho-mmuo",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.CONCAVE to 5, Anatomy.Outline.HEART to 2), width = 0.3f..0.5f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.HEART to 3), eyes = w(Anatomy.Eyes.SLIT to 3, Anatomy.Eyes.ROUND to 1),
                noses = w(Anatomy.Nose.LONG to 3), mouths = w(Anatomy.Mouth.CLOSED to 2, Anatomy.Mouth.PURSED to 2),
                coiffures = w(Anatomy.Coiffure.NONE to 3), beards = w(Anatomy.Beard.RAFFIA to 4),
                finishes = w(Anatomy.Finish.KAOLIN to 5), woods = listOf(Pigments.UMBER, Pigments.IROKO), glows = listOf(MOON), raffia = 0.4f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_PURITY, temper = T.AUSTERE, fractures = w(F.SPLIT_VISAGE to 2, F.DISSOLVED_CHIN to 2), cores = w(C.MOON to 3), relics = c(Rl.COWRIE to 0.5f)),
        ),
        Tradition(
            "yaka", "N-khanda", "Yaka", "Democratic Republic of the Congo",
            "Masks of the n-khanda initiation: bulging eyes, an upturned nose and an open mouth, framed in a raffia ruff under a painted superstructure.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.ROUND to 2, Anatomy.Outline.OVAL to 2), width = 0.55f..0.85f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.ARCH to 1), eyes = w(Anatomy.Eyes.BULGING to 4, Anatomy.Eyes.TUBE to 1),
                noses = w(Anatomy.Nose.UPTURNED to 6), mouths = w(Anatomy.Mouth.OPEN to 3, Anatomy.Mouth.TEETH to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1),
                crowns = w(Anatomy.Crown.TIERS to 2, Anatomy.Crown.BIRD to 2, Anatomy.Crown.PLANK to 1, Anatomy.Crown.NONE to 1), counts = 2..3,
                adorn = c(Anatomy.Adorn.RAFFIA_COLLAR to 0.9f),
                finishes = w(Anatomy.Finish.POLYCHROME to 6), patterns = c(Anatomy.Pattern.DOTS to 0.4f, Anatomy.Pattern.BANDS to 0.4f, Anatomy.Pattern.SPLIT to 0.2f),
                accents = listOf(Pigments.KAOLIN, Pigments.VERMILION, Pigments.INDIGO, Pigments.OCHRE), glows = listOf(EMBER, GOLDEN), raffia = 0.6f..1f,
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.RESTLESS, fractures = w(F.SHATTERED_CROWN to 2, F.SUSPENDED_FACETS to 2), cores = w(C.FIREFLY to 2, C.SOLAR to 1)),
        ),
        Tradition(
            "teke", "Kidumu", "Teke (Tsaayi)", "Republic of the Congo",
            "The flat disc mask of the kidumu dance, painted in a balanced geometry of colours and divided across by a ridge, with narrow slit eyes.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.DISC to 6), width = 0.8f..1f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.NONE to 3), eyes = w(Anatomy.Eyes.SLIT to 3, Anatomy.Eyes.CRESCENT to 2),
                noses = w(Anatomy.Nose.NONE to 2, Anatomy.Nose.LONG to 1), mouths = w(Anatomy.Mouth.BOX to 2, Anatomy.Mouth.CLOSED to 1),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.5f), coiffures = w(Anatomy.Coiffure.NONE to 3),
                finishes = w(Anatomy.Finish.POLYCHROME to 6), patterns = c(Anatomy.Pattern.TRIANGLES to 0.6f, Anatomy.Pattern.BANDS to 0.5f, Anatomy.Pattern.SPLIT to 0.4f, Anatomy.Pattern.CHECKER to 0.3f),
                accents = listOf(Pigments.KAOLIN, Pigments.LAMPBLACK, Pigments.CAMWOOD, Pigments.OCHRE), glows = listOf(MOON, GOLDEN), raffia = 0.3f..0.8f,
            ),
            spirit = SpiritGrammar(role = R.COSMIC_POWER, temper = T.AUSTERE, fractures = w(F.FLOATING_QUADRANTS to 3, F.SPLIT_VISAGE to 2), cores = w(C.SOLAR to 2, C.INDIGO to 1), relics = c(Rl.HALO to 0.4f)),
        ),
        Tradition(
            "kwele", "Ekuk", "Kwele", "Gabon and the Republic of the Congo",
            "Masks of the beete cult that renewed the village's vital force: a concave heart-shaped face, whitened with kaolin, between horns that sweep down round it.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.CONCAVE to 5, Anatomy.Outline.HEART to 2), width = 0.3f..0.5f, length = 0.5f..0.75f,
                brows = w(Anatomy.Brow.HEART to 3), eyes = w(Anatomy.Eyes.SLIT to 3, Anatomy.Eyes.CRESCENT to 2),
                noses = w(Anatomy.Nose.LONG to 3, Anatomy.Nose.TRIANGLE to 1), mouths = w(Anatomy.Mouth.PURSED to 2, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.FRAME_HORNS to 6), crownSize = 0.5f..0.9f,
                finishes = w(Anatomy.Finish.KAOLIN to 5, Anatomy.Finish.POLYCHROME to 1), patterns = c(Anatomy.Pattern.EYE_RINGS to 0.3f),
                accents = listOf(Pigments.LAMPBLACK, Pigments.KAOLIN), horns = listOf(Pigments.UMBER, Pigments.BLACKWOOD, Pigments.KAOLIN), glows = listOf(MOON),
            ),
            spirit = SpiritGrammar(role = R.COSMIC_POWER, temper = T.BREATHING, fractures = w(F.SPLIT_VISAGE to 3, F.SHATTERED_CROWN to 1), cores = w(C.MOON to 3, C.MARSH to 1)),
        ),
        Tradition(
            "bamileke", "Grassfields", "Bamileke", "Cameroon Grassfields",
            "Masks of the Grassfields kingdoms, worn on top of the head: swelling cheeks, great bulging eyes, a wide open mouth with teeth and a crested coiffure.",
            "spirit:ijele",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 4, Anatomy.Form.FACE to 1), outlines = w(Anatomy.Outline.ROUND to 3, Anatomy.Outline.SQUARE to 1), width = 0.7f..1f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.ARCH to 2), eyes = w(Anatomy.Eyes.BULGING to 3, Anatomy.Eyes.ROUND to 2),
                noses = w(Anatomy.Nose.BROAD to 5), mouths = w(Anatomy.Mouth.GRIN to 4, Anatomy.Mouth.TEETH to 2), ears = w(Anatomy.Ears.SMALL to 3, Anatomy.Ears.ELEPHANT to 1),
                coiffures = w(Anatomy.Coiffure.COMBS to 2, Anatomy.Coiffure.ARCHES to 2, Anatomy.Coiffure.CREST to 1), counts = 3..6,
                adorn = c(Anatomy.Adorn.BEADS to 0.3f),
                finishes = w(Anatomy.Finish.RAW to 3, Anatomy.Finish.BLACKENED to 2, Anatomy.Finish.POLYCHROME to 1),
                woods = listOf(Pigments.UMBER, Pigments.BLACKWOOD, Pigments.MAHOGANY), glows = listOf(GOLDEN, EMBER),
                dials = mapOf(Anatomy.Dial.CHEEKS to 0.75f..1f),
            ),
            spirit = SpiritGrammar(role = R.ROYAL_MAJESTY, temper = T.MONUMENTAL, fractures = w(F.SUSPENDED_FACETS to 2, F.SHATTERED_CROWN to 2), cores = w(C.SOLAR to 2, C.CAMWOOD to 1), relics = c(Rl.BRASS_BELL to 0.3f)),
        ),
        Tradition(
            "idoma", "Idoma", "Idoma", "Benue, central Nigeria",
            "Idoma face masks of the ancestral and funerary masquerades: a kaolin-white face with raised marks at the temples, an open mouth showing teeth, and dark hair.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.OVAL to 3, Anatomy.Outline.SQUARE to 1), width = 0.4f..0.65f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.ARCH to 3), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.BEAN to 2),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 1), mouths = w(Anatomy.Mouth.TEETH to 4, Anatomy.Mouth.OPEN to 1),
                scars = c(Anatomy.Scar.TEMPLES to 0.7f, Anatomy.Scar.KELOID_DIAMONDS to 0.5f, Anatomy.Scar.CHEEK_KELOIDS to 0.5f),
                coiffures = w(Anatomy.Coiffure.CAP to 3, Anatomy.Coiffure.CORNROWS to 2),
                finishes = w(Anatomy.Finish.KAOLIN to 5), hairs = listOf(Pigments.LAMPBLACK), glows = listOf(MOON, EMBER),
            ),
            spirit = SpiritGrammar(role = R.ANCESTRAL_PURITY, temper = T.BREATHING, fractures = w(F.DISSOLVED_CHIN to 2, F.SUSPENDED_FACETS to 2), cores = w(C.MOON to 2, C.INDIGO to 1)),
        ),
        Tradition(
            "ibibio_idiok", "Idiok", "Ibibio", "Akwa Ibom, south-eastern Nigeria",
            "The dark masks of Ekpo, the society of the ancestors: faces twisted by sickness and decay, the restless dead, set against the beautiful Mfon.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.OVAL to 2, Anatomy.Outline.LONG to 1), width = 0.4f..0.7f, length = 0.45f..0.75f,
                brows = w(Anatomy.Brow.BULGE to 2, Anatomy.Brow.SHELF to 1), eyes = w(Anatomy.Eyes.BULGING to 2, Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.TROUGH to 1),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.BEAK to 1, Anatomy.Nose.NONE to 1), mouths = w(Anatomy.Mouth.GRIN to 2, Anatomy.Mouth.TEETH to 2, Anatomy.Mouth.OPEN to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1),
                finishes = w(Anatomy.Finish.BLACKENED to 5), woods = listOf(Pigments.BLACKWOOD, Pigments.CHARCOAL), glows = listOf(BLOOD, EMBER), raffia = 0.4f..0.9f,
                dials = mapOf(Anatomy.Dial.ASYMMETRY to 0.45f..0.95f, Anatomy.Dial.CHEEKS to 0f..0.3f),
            ),
            spirit = SpiritGrammar(role = R.JUDICIAL_JUSTICE, temper = T.STORM, fractures = w(F.DRIFTING_JAW to 3, F.DISSOLVED_CHIN to 2), cores = w(C.CAMWOOD to 2, C.LEOPARD to 1), drift = 0.45f..0.9f),
        ),
        Tradition(
            "ogoni", "Karikpo", "Ogoni", "Rivers State, south-eastern Nigeria",
            "Antelope masks of the Ogoni, worn by young men leaping and tumbling in harvest displays: long sweeping horns over a small face.",
            "spirit:okoroshi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 2, Anatomy.Outline.OVAL to 2), width = 0.3f..0.55f, length = 0.4f..0.65f,
                brows = w(Anatomy.Brow.ARCH to 2, Anatomy.Brow.SHELF to 1), eyes = w(Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.SLIT to 2),
                noses = w(Anatomy.Nose.LONG to 2), mouths = w(Anatomy.Mouth.OPEN to 2, Anatomy.Mouth.CLOSED to 1),
                coiffures = w(Anatomy.Coiffure.NONE to 3), crowns = w(Anatomy.Crown.ANTELOPE to 6), crownSize = 0.7f..1f,
                finishes = w(Anatomy.Finish.RAW to 2, Anatomy.Finish.BLACKENED to 2), horns = listOf(Pigments.BLACKWOOD, Pigments.UMBER), glows = listOf(EMBER),
            ),
            spirit = SpiritGrammar(role = R.BUSH_SPIRIT, temper = T.RESTLESS, fractures = w(F.SHATTERED_CROWN to 3, F.SUSPENDED_FACETS to 1), cores = w(C.MARSH to 2, C.FIREFLY to 1)),
        ),
        Tradition(
            "ejagham", "Ejagham", "Ejagham (Ekoi)", "Cross River, Nigeria and Cameroon",
            "Startlingly lifelike heads of the Cross River, once covered in stretched skin: open mouths with inset teeth, coiled hair or horns, worn for the Ekpe and other societies.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.HELMET to 4), outlines = w(Anatomy.Outline.OVAL to 3), width = 0.5f..0.75f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.ARCH to 2), eyes = w(Anatomy.Eyes.BEAN to 2, Anatomy.Eyes.ROUND to 2),
                noses = w(Anatomy.Nose.BROAD to 2, Anatomy.Nose.LONG to 2), mouths = w(Anatomy.Mouth.TEETH to 3, Anatomy.Mouth.OPEN to 2),
                scars = c(Anatomy.Scar.TEMPLES to 0.5f, Anatomy.Scar.NSIBIDI to 0.3f),
                coiffures = w(Anatomy.Coiffure.KNOTS to 2, Anatomy.Coiffure.LOBES to 1, Anatomy.Coiffure.CREST to 1), crowns = w(Anatomy.Crown.RAM to 2, Anatomy.Crown.NONE to 3),
                finishes = w(Anatomy.Finish.RAW to 3, Anatomy.Finish.CAMWOOD to 2), woods = listOf(Pigments.IROKO, Pigments.MAHOGANY, Pigments.UMBER), glows = listOf(EMBER, GOLDEN),
            ),
            spirit = SpiritGrammar(role = R.JUDICIAL_JUSTICE, temper = T.BREATHING, fractures = w(F.SUSPENDED_FACETS to 2, F.DRIFTING_JAW to 1), cores = w(C.LEOPARD to 2, C.SOLAR to 1)),
        ),
        Tradition(
            "lwalwa", "Mvondo", "Lwalwa", "Democratic Republic of the Congo and Angola",
            "Lwalwa masks danced at initiation: angular planes, a long nose running straight from a high forehead, narrow slit eyes and a jutting mouth.",
            "spirit:ikenga",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.LONG to 3, Anatomy.Outline.SHIELD to 2), width = 0.35f..0.6f, length = 0.55f..0.85f,
                brows = w(Anatomy.Brow.SHELF to 2, Anatomy.Brow.NONE to 2), eyes = w(Anatomy.Eyes.SLIT to 4, Anatomy.Eyes.TROUGH to 1),
                noses = w(Anatomy.Nose.BEAK to 3, Anatomy.Nose.LONG to 2), mouths = w(Anatomy.Mouth.BOX to 2, Anatomy.Mouth.OPEN to 2),
                scars = c(Anatomy.Scar.CENTRE_RIDGE to 0.5f, Anatomy.Scar.TEMPLES to 0.3f), coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1),
                finishes = w(Anatomy.Finish.CAMWOOD to 3, Anatomy.Finish.BLACKENED to 2), glows = listOf(EMBER, GOLDEN),
                dials = mapOf(Anatomy.Dial.FOREHEAD to 0.65f..1f, Anatomy.Dial.TOOL_MARKS to 0.6f..0.9f),
            ),
            spirit = SpiritGrammar(role = R.INITIATION, temper = T.AUSTERE, fractures = w(F.SPLIT_VISAGE to 2, F.FLOATING_QUADRANTS to 2), cores = w(C.CAMWOOD to 2, C.SOLAR to 1)),
        ),
        Tradition(
            "salampasu", "Mukinka", "Salampasu", "Democratic Republic of the Congo",
            "Masks of the Salampasu warrior societies: a bulging forehead, bared teeth and a skin of copper sheet, framed by a raffia mane.",
            "spirit:mgbedike",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.ROUND to 2, Anatomy.Outline.OVAL to 2), width = 0.5f..0.8f, length = 0.45f..0.7f,
                brows = w(Anatomy.Brow.BULGE to 5), eyes = w(Anatomy.Eyes.SLIT to 2, Anatomy.Eyes.TROUGH to 2),
                noses = w(Anatomy.Nose.TRIANGLE to 3), mouths = w(Anatomy.Mouth.TEETH to 6),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CORNROWS to 1), beards = w(Anatomy.Beard.RAFFIA to 2, Anatomy.Beard.NONE to 1),
                adorn = c(Anatomy.Adorn.RAFFIA_COLLAR to 0.7f, Anatomy.Adorn.BRASS_STUDS to 0.4f),
                finishes = w(Anatomy.Finish.BRASS to 4, Anatomy.Finish.BLACKENED to 2), glows = listOf(BLOOD, EMBER), raffia = 0.5f..1f,
            ),
            spirit = SpiritGrammar(role = R.MARTIAL_FEROCITY, temper = T.STORM, fractures = w(F.DRIFTING_JAW to 3, F.SUSPENDED_FACETS to 2), cores = w(C.CAMWOOD to 2, C.THUNDER to 2), drift = 0.45f..0.9f),
        ),
        Tradition(
            "hemba", "Soko Mutu", "Hemba", "Democratic Republic of the Congo",
            "The chimpanzee mask, half ape and half human, danced at funerals: a wide crescent grin, a heavy brow and ears, laughing at the border between the wild and the village.",
            "spirit:ogbodo-enyi",
            Grammar(
                forms = w(Anatomy.Form.FACE to 5), outlines = w(Anatomy.Outline.ROUND to 3, Anatomy.Outline.OVAL to 1), width = 0.55f..0.85f, length = 0.4f..0.6f,
                brows = w(Anatomy.Brow.SHELF to 3, Anatomy.Brow.BULGE to 1), eyes = w(Anatomy.Eyes.ROUND to 2, Anatomy.Eyes.SLIT to 2),
                noses = w(Anatomy.Nose.BROAD to 3, Anatomy.Nose.UPTURNED to 1), mouths = w(Anatomy.Mouth.GRIN to 6), ears = w(Anatomy.Ears.SMALL to 3),
                coiffures = w(Anatomy.Coiffure.NONE to 2, Anatomy.Coiffure.CAP to 1), beards = w(Anatomy.Beard.RAFFIA to 3, Anatomy.Beard.NONE to 1),
                finishes = w(Anatomy.Finish.RAW to 3, Anatomy.Finish.BLACKENED to 2), glows = listOf(EMBER, GOLDEN),
                dials = mapOf(Anatomy.Dial.MUZZLE to 0.7f..1f),
            ),
            spirit = SpiritGrammar(role = R.SATIRE, temper = T.RESTLESS, fractures = w(F.DRIFTING_JAW to 3, F.SPLIT_VISAGE to 1), cores = w(C.FIREFLY to 2, C.MARSH to 1)),
        ),
    )

    fun tradition(id: String): Tradition = traditions.firstOrNull { it.id == id } ?: traditions.first()

    /**
     * How many different masks the traditions' own grammars can roll: every
     * named choice, mark and colour each people allows, summed over the
     * peoples. Before blends of two traditions and before any proportion,
     * both of which multiply it many times over.
     */
    fun rollable(): java.math.BigInteger = traditions.fold(java.math.BigInteger.ZERO) { sum, t ->
        val g = t.grammar
        fun n(x: Int) = java.math.BigInteger.valueOf(x.toLong())
        val shape = n(g.forms.size) * n(g.outlines.size) * n(g.brows.size) * n(g.eyes.size) * n(g.noses.size) * n(g.mouths.size) * n(g.ears.size) * n(g.coiffures.size) * n(g.crowns.size) * n(g.beards.size)
        val marks = java.math.BigInteger.TWO.pow(g.scars.size + g.adorn.size + g.patterns.size) * n(g.counts.last - g.counts.first + 1)
        val colours = n(g.finishes.size) * n(g.woods.size) * n(g.hairs.size) * n(g.accents.size) * n(g.accents.size) * n(g.horns.size) * n(g.glows.size)
        sum + shape * marks * colours
    }

    /**
     * A mask of [tradition] from [seed]: every part rolled within its
     * grammar, so it could only have come from that people's carvers.
     */
    fun generate(tradition: Tradition, seed: Long, name: String = tradition.name): MaskSpec {
        val r = Random(seed * 0x9E3779B1L + tradition.id.hashCode())
        val g = tradition.grammar
        fun <T> pick(xs: List<Pair<T, Int>>): T {
            var n = r.nextInt(xs.sumOf { it.second }.coerceAtLeast(1))
            for ((v, wt) in xs) { if (n < wt) return v; n -= wt }
            return xs.first().first
        }
        fun <T> chance(xs: List<Pair<T, Float>>): Set<T> = xs.filter { r.nextFloat() < it.second }.map { it.first }.toSet()
        fun range(x: ClosedFloatingPointRange<Float>) = x.start + r.nextFloat() * (x.endInclusive - x.start)
        val finish = pick(g.finishes)
        return MaskSpec(
            name = name, tradition = tradition.id,
            form = pick(g.forms), outline = pick(g.outlines), width = range(g.width), length = range(g.length),
            brow = pick(g.brows), eyes = pick(g.eyes), nose = pick(g.noses), mouth = pick(g.mouths), ears = pick(g.ears),
            scars = chance(g.scars), coiffure = pick(g.coiffures), crown = pick(g.crowns), crownSize = range(g.crownSize),
            count = g.counts.first + r.nextInt(g.counts.last - g.counts.first + 1),
            beard = pick(g.beards), adorn = chance(g.adorn), finish = finish, patterns = chance(g.patterns),
            wood = g.woods[r.nextInt(g.woods.size)], hair = g.hairs[r.nextInt(g.hairs.size)],
            accent = g.accents[r.nextInt(g.accents.size)], accent2 = g.accents[r.nextInt(g.accents.size)],
            hornColour = g.horns[r.nextInt(g.horns.size)], glow = g.glows[r.nextInt(g.glows.size)],
            raffia = range(g.raffia), seed = seed,
        ).let { spec ->
            // The carver's hand: every proportion nudged off the tradition's own, now and then a face pulled askew.
            spec.copy(
                dials = Anatomy.Dial.entries.associateWith { d ->
                    val fixed = g.dials[d]
                    if (fixed != null) range(fixed)
                    else if (d == Anatomy.Dial.ASYMMETRY) (if (r.nextFloat() < 0.12f) 0.2f + 0.5f * r.nextFloat() else 0f)
                    else (d.default + (r.nextFloat() - 0.5f) * VARIATION).coerceIn(0f, 1f)
                },
                raffiaDye = if (r.nextFloat() < 0.2f) g.accents[r.nextInt(g.accents.size)] else MaskSpec.NATURAL,
                beads = BEADS[r.nextInt(BEADS.size)],
            )
        }
    }

    /** How far a roll moves each dial off its tradition's own setting. */
    private const val VARIATION = 0.6f
    private val BEADS = listOf(Pigments.VERMILION, Pigments.LAPIS, Pigments.CORAL, Pigments.GOLD, Pigments.JADE, Pigments.KAOLIN)

    /**
     * The spirit inside a mask of [tradition], from [seed]: how it breaks,
     * what burns in it and what drifts round it, rolled within the
     * tradition's spirit grammar; its temper is the tradition's own.
     */
    fun spirit(tradition: Tradition, seed: Long): SpiritSpec {
        val r = Random(seed * 0x2545F491L + tradition.id.hashCode() * 31L)
        val g = tradition.spirit
        fun <T> pick(xs: List<Pair<T, Int>>): T {
            var n = r.nextInt(xs.sumOf { it.second }.coerceAtLeast(1))
            for ((v, wt) in xs) { if (n < wt) return v; n -= wt }
            return xs.first().first
        }
        return SpiritSpec(
            fracture = pick(g.fractures), core = pick(g.cores), temper = g.temper,
            drift = g.drift.start + r.nextFloat() * (g.drift.endInclusive - g.drift.start),
            pieces = r.nextFloat(), jag = 0.2f + 0.7f * r.nextFloat(),
            relics = g.relics.filter { r.nextFloat() < it.second }.map { it.first }.toSet(),
            seed = seed,
        )
    }

    /** The spirit of a mask already carved: its (first) tradition's, from its seed. */
    fun spiritOf(spec: MaskSpec): SpiritSpec = spirit(tradition(spec.tradition.substringBefore('+')), spec.seed)

    /** A spirit that belongs to two traditions: its parts drawn from each in turn. */
    fun blend(a: Tradition, b: Tradition, seed: Long, name: String = "${a.name}–${b.name}"): MaskSpec {
        val x = generate(a, seed, name); val y = generate(b, seed + 1, name)
        val r = Random(seed)
        fun <T> one(p: T, q: T) = if (r.nextBoolean()) p else q
        return x.copy(
            outline = one(x.outline, y.outline), eyes = one(x.eyes, y.eyes), mouth = one(x.mouth, y.mouth), nose = one(x.nose, y.nose),
            coiffure = one(x.coiffure, y.coiffure), crown = one(x.crown, y.crown), scars = one(x.scars, y.scars),
            finish = one(x.finish, y.finish), patterns = one(x.patterns, y.patterns), tradition = "${a.id}+${b.id}",
            dials = Anatomy.Dial.entries.associateWith { d -> (x.dial(d) + y.dial(d)) / 2f },
        )
    }
}
