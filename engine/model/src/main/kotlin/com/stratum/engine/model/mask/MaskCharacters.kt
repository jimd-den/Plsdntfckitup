package com.stratum.engine.model.mask

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.motion.MotionBody
import com.stratum.core.domain.motion.MotionProfile
import com.stratum.core.domain.motion.MotionProfiles
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.engine.scene.MaskCast
import com.stratum.engine.scene.SpiritInstance
import com.stratum.engine.scene.SpiritMesh
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Something a fight reports that a mask shows: read by the game from its
 * combat feedback (a critical, a block, a kill) and handed to [MaskCharacters.cue].
 */
enum class MaskCue {
    /** A blow thrown: the mask strikes with itself. */
    STRIKE,

    /** A skill or spell: the eyes flare and the fringe whips. */
    CAST,

    /** A blow taken: a flinch and a crack of light. */
    HIT,

    /** A critical landed: the striker's eyes flash white-hot, and the struck takes a heavy hit. */
    CRIT,

    /** A blow turned aside: a shield of light. */
    BLOCK,

    /** Killed: the mask shatters into shards of light. */
    DIE,
}

/**
 * The characters as masks: the hero wears the player's chosen mask, every
 * monster a mask derived from what it is, and the fight drives how they move.
 *
 * Sits between the game and [MaskCast]. The game reports, every frame, where
 * each character truly is and what the simulation says it is doing -- its
 * animation state, its hit flash, its knockback -- and this turns the *edges*
 * of those signals into motion events: a swing starting is a strike, a cast
 * starting is a flare, a flash rising is a flinch from whoever is nearest,
 * a body the simulation stops reporting shatters. So nothing new is threaded
 * through the combat engine; the masks read what the sprites already read.
 *
 * Only presentation: positions go in and never come back out.
 *
 * ## Meshes
 *
 * A mask's mesh is built once per genome and budget ([MaskSpiritMesher.cached]).
 * With a [builder] executor the first sight of a new mask builds it off the
 * caller's thread and [monster] returns null until it is ready -- the game
 * draws its ordinary body for those few frames rather than hitching. Without
 * one (tests, previews) meshes are built on the spot.
 */
class MaskCharacters(
    /** Packs' motion profiles by id, over the built-in ones. */
    private val profileOverrides: Map<String, MotionProfile> = emptyMap(),
    /** Builds meshes off the frame; null builds them inline. */
    private val builder: Executor? = null,
    capacity: Int = com.stratum.core.domain.motion.MotionBank.DEFAULT_CAPACITY,
) {
    val cast = MaskCast(capacity)

    /** What to draw this frame. */
    val spirits: List<SpiritInstance> get() = cast.spirits

    /** The hero's mask. Changing it rebuilds (or fetches) the hero's mesh on the next [hero]. */
    var heroGenome: MaskGenome = CharacterMasks.DEFAULT_HERO
        set(value) { if (field != value) { field = value; heroMesh = null } }

    /**
     * The hero's mask when it was made in the mask maker: a painted head with
     * floating pieces that move on their own. Wins over [heroGenome] while set.
     */
    var heroArt: AfricanMaskArt.Design? = null
        set(value) { if (field != value) { field = value; heroMesh = null; heroPieces = null } }

    /**
     * The hero's mask when it was made in the mask maker: the painted mask
     * worn as a card, its feeling following the fight. Wins over the others.
     */
    var heroMaker: EmojiMask.Look? = null
        set(value) { if (field != value) { field = value; heroMesh = null; heroCards.clear() } }

    private val heroCards = HashMap<String, SpiritMesh>()

    /**
     * The hero's mask when it was carved in the mask carver: a sculpted mask
     * that moves as its tradition's spirits do. Wins over the others. Carved
     * off the frame with a [builder]; the plain mask stands in until it is done.
     */
    var heroCarved: com.stratum.engine.model.mask.sculpt.MaskSpec? = null
        set(value) { if (field != value) { field = value; carvedProfile = null } }

    /**
     * The carved hero flies broken open by its spirit: its pieces floating
     * round a burning core, breathing, dragging behind a dash and flaring on
     * a strike. [heroSpirit] chooses how it breaks; null rolls its own
     * tradition's. False flies the carving whole.
     */
    var heroBroken: Boolean = false
        set(value) { if (field != value) { field = value; carvedFor = null; carving = null } }

    var heroSpirit: com.stratum.engine.model.mask.sculpt.SpiritSpec? = null
        set(value) { if (field != value) { field = value; carvedFor = null; carving = null } }

    @Volatile private var carvedBroken: com.stratum.engine.scene.ShatteredSpirit? = null
    @Volatile private var carvedMesh: SpiritMesh? = null
    @Volatile private var carvedFor: com.stratum.engine.model.mask.sculpt.MaskSpec? = null
    @Volatile private var carving: com.stratum.engine.model.mask.sculpt.MaskSpec? = null
    private var carvedProfile: MotionProfile? = null

    /** The carved hero's mesh when it is ready, starting its carving if it is not. */
    private fun carvedHero(spec: com.stratum.engine.model.mask.sculpt.MaskSpec): SpiritMesh? {
        if (carvedFor == spec) return carvedMesh
        if (carving != spec) {
            carving = spec
            val broken = heroBroken; val spirit = heroSpirit
            val job = Runnable {
                val sculptor = com.stratum.engine.model.mask.sculpt.MaskSculptor
                val shattered = if (!broken) null else runCatching {
                    sculptor.cachedShatter(spec, spirit ?: com.stratum.engine.model.mask.sculpt.MaskCulture.spiritOf(spec), com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.GAME)
                }.getOrNull()
                val mesh = shattered?.core ?: runCatching { sculptor.cached(spec, com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.GAME) }.getOrNull()
                if (carving == spec) { carvedBroken = shattered; carvedMesh = mesh; carvedFor = spec }
            }
            if (builder != null) builder.execute(job) else job.run()
        }
        return if (carvedFor == spec) carvedMesh else null
    }

    /** What the hero feels, from its state: serene, fierce in a strike, afraid when hit, struck down in death. */
    private fun heroFeeling(state: AnimationState, flash: Float): String = when {
        state == AnimationState.DIE -> "Knocked out"
        state == AnimationState.ATTACK || state == AnimationState.SPECIAL -> "Angry"
        flash.isFinite() && flash > 0.3f -> "Nervous"
        else -> "Serene"
    }

    private var heroMesh: SpiritMesh? = null
    private var heroId: String? = null
    private var heroPieces: com.stratum.engine.scene.SpiritFeatures? = null
    private var piecesClock = 0f
    private var piecesAt = -1f
    private var heroProfile: MotionProfile? = null
    private var heroProfileFor: MaskGenome? = null

    /** What each character's signals were last frame, to find their edges. */
    private class Signals {
        var state: AnimationState = AnimationState.IDLE
        var flash = 0f
        var casting = false
        var x = 0f
        var y = 0f
        var seen = 0
    }

    private val signals = HashMap<String, Signals>()
    private var frame = 0

    /** A monster definition's mask, mesh and profile, worked out once. */
    private class Look(val genome: MaskGenome, val profile: MotionProfile, val height: Float) {
        @Volatile var mesh: SpiritMesh? = null
        @Volatile var building = false
    }

    private val looks = ConcurrentHashMap<String, Look>()

    fun begin() {
        frame++
        cast.begin()
    }

    /**
     * The hero this frame. [state] is its animation state (a swing or a skill
     * starting strikes or casts), [flash] its hit flash, [impact] its
     * knockback 0..1; [aimX], [aimY] what it faces up to, if anything.
     */
    fun hero(
        id: String, x: Float, y: Float, z: Float, facingX: Float, facingY: Float,
        state: AnimationState = AnimationState.IDLE, flash: Float = 0f, impact: Float = 0f,
        aimX: Float = Float.NaN, aimY: Float = Float.NaN,
    ): MotionBody? {
        heroId = id
        val maker = heroMaker
        val carved = heroCarved?.let { carvedHero(it) }
        val mesh = if (carved != null) carved else if (maker != null) {
            val feeling = heroFeeling(state, flash)
            heroCards.getOrPut(feeling) { MaskMaker.card(maker, EmojiMask.expressions.getValue(feeling)) }
        } else heroMesh ?: (heroArt?.let { AfricanMaskArt.head(it) } ?: MaskSpiritMesher.cached(heroGenome, MaskSpiritMesher.COMPANION_BUDGET)).also { heroMesh = it }
        if (heroProfileFor != heroGenome) {
            heroProfile = MotionProfiles.resolve(CharacterMasks.profileIdFor(heroGenome), profileOverrides)
            heroProfileFor = heroGenome
        }
        val profile = if (carved != null) {
            carvedProfile ?: MotionProfiles.resolve(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition(heroCarved!!.tradition.substringBefore('+')).motion, profileOverrides).also { carvedProfile = it }
        } else heroProfile!!
        val broken = if (carved != null) carvedBroken else null
        val body = (if (broken != null) cast.track(id, broken, profile, x, y, z, facingX, facingY, CharacterMasks.HERO_HEIGHT, spawning = false)
        else cast.track(id, mesh, profile, x, y, z, facingX, facingY, CharacterMasks.HERO_HEIGHT, spawning = false)) ?: return null
        react(id, body, state, flash, impact, casting = false, aimX, aimY)
        return body
    }

    /**
     * A monster this frame: [definitionId] and [rank] choose its mask (or
     * [maskOverride], a genome code or preset name from its pack) and
     * [motionOverride] its profile. [casting] is true while it winds up a
     * skill. Null while its mask is still being built, or the pool is full.
     */
    fun monster(
        id: String, definitionId: String, rank: EnemyRank, x: Float, y: Float, z: Float, facingX: Float, facingY: Float,
        state: AnimationState = AnimationState.IDLE, flash: Float = 0f, impact: Float = 0f, casting: Boolean = false,
        aimX: Float = Float.NaN, aimY: Float = Float.NaN,
        maskOverride: String? = null, motionOverride: String? = null,
    ): MotionBody? {
        val look = lookFor(definitionId, rank, maskOverride, motionOverride)
        val mesh = look.mesh ?: return null
        val body = cast.track(id, mesh, look.profile, x, y, z, facingX, facingY, look.height) ?: return null
        react(id, body, state, flash, impact, casting, aimX, aimY)
        return body
    }

    /** True once [definitionId]'s mask is built and it will draw. */
    fun ready(definitionId: String, rank: EnemyRank, maskOverride: String? = null): Boolean =
        looks[key(definitionId, rank, maskOverride)]?.mesh != null

    /**
     * Something the fight reported at a place: applied to the character
     * nearest ([x], [y]) within [reach] blocks. [fromX], [fromY] is where a
     * blow came from, for a hit. Returns whose body took it, or null.
     */
    fun cue(cue: MaskCue, x: Float, y: Float, fromX: Float = Float.NaN, fromY: Float = Float.NaN, power: Float = 1f, reach: Float = 1.6f): MotionBody? {
        val body = nearest(x, y, reach, excluding = null) ?: return null
        apply(cue, body, fromX, fromY, power)
        return body
    }

    /** Something the fight reported about a character by id. */
    fun cue(cue: MaskCue, id: String, fromX: Float = Float.NaN, fromY: Float = Float.NaN, power: Float = 1f): MotionBody? {
        val body = cast.body(id) ?: return null
        apply(cue, body, fromX, fromY, power)
        return body
    }

    /** Springs, layers and fringes forward by [dt] seconds; any [dt] is safe. */
    fun advance(dt: Float) {
        cast.advance(dt)
        dressHero(dt)
        // Forget signals of characters gone a while, so the map is as small as the screen.
        if (frame % FORGET_EVERY == 0) signals.values.removeAll { frame - it.seen > FORGET_EVERY }
    }

    /**
     * A mask-maker hero's floating pieces, redrawn [PIECES_RATE] times a
     * second -- often enough for blinks and swings, rarely enough to cost
     * nothing. Every other spirit is kept free of pieces, as slots are reused.
     */
    private fun dressHero(dt: Float) {
        val art = heroArt
        val face = heroMesh?.face
        if (art != null && face != null) {
            piecesClock += if (dt.isFinite()) dt.coerceIn(0f, 0.25f) else 0f
            if (heroPieces == null || piecesClock - piecesAt >= 1f / PIECES_RATE) {
                heroPieces = AfricanMaskArt.features(art, face, piecesClock)
                piecesAt = piecesClock
            }
        }
        for (spirit in cast.spirits) spirit.features = if (art != null && spirit.id == heroId) heroPieces else null
    }

    fun clear() {
        cast.clear()
        signals.clear()
    }

    // ---- Reading the fight ---------------------------------------------------

    private fun react(id: String, body: MotionBody, state: AnimationState, flash: Float, impact: Float, casting: Boolean, aimX: Float, aimY: Float) {
        val s = signals.getOrPut(id) { Signals().also { it.state = state; it.flash = flash; it.casting = casting } }
        s.seen = frame
        s.x = body.trueX; s.y = body.trueY
        body.impact = if (impact.isFinite()) impact.coerceIn(0f, 1f) else 0f
        if (aimX.isFinite() && aimY.isFinite()) body.aim(aimX, aimY, body.trueZ + 1f) else body.clearAim()

        // Out of death under the same id (the hero respawned): the mask comes back, rising in.
        if (s.state == AnimationState.DIE && state != AnimationState.DIE) body.revive()
        if (state != s.state) {
            when (state) {
                AnimationState.ATTACK -> body.strike()
                AnimationState.SPECIAL -> { body.cast(); body.strike(0.8f) }
                AnimationState.DIE -> body.die()
                else -> Unit
            }
        }
        if (casting && !s.casting) body.cast()
        // A flash rising is a blow landing: flinch away from whoever is nearest.
        if (flash.isFinite() && flash > s.flash + FLASH_EDGE) {
            val from = nearest(body.trueX, body.trueY, HIT_REACH, excluding = body)
            if (from != null) body.hit(from.trueX, from.trueY, flash.coerceIn(0.4f, 1f)) else body.hit(body.trueX - body.speedX, body.trueY - body.speedY, flash.coerceIn(0.4f, 1f))
        }
        s.state = state
        s.flash = if (flash.isFinite()) flash else 0f
        s.casting = casting
    }

    private fun apply(cue: MaskCue, body: MotionBody, fromX: Float, fromY: Float, power: Float) {
        val p = if (power.isFinite()) power.coerceIn(0.2f, 2f) else 1f
        when (cue) {
            MaskCue.STRIKE -> body.strike(p)
            MaskCue.CAST -> body.cast()
            MaskCue.HIT, MaskCue.CRIT -> {
                val (fx, fy) = if (fromX.isFinite() && fromY.isFinite()) fromX to fromY else nearest(body.trueX, body.trueY, HIT_REACH, body)
                    ?.let { it.trueX to it.trueY } ?: (body.trueX to body.trueY - 1f)
                body.hit(fx, fy, if (cue == MaskCue.CRIT) p * 1.5f else p)
                // The striker's eyes flash on a critical.
                if (cue == MaskCue.CRIT) nearest(fx, fy, HIT_REACH, body)?.crit()
            }
            MaskCue.BLOCK -> body.block()
            MaskCue.DIE -> body.die()
        }
    }

    /** The tracked, living body nearest a point, within [reach]; allocation-free. */
    private fun nearest(x: Float, y: Float, reach: Float, excluding: MotionBody?): MotionBody? {
        var best: MotionBody? = null
        var bestD = reach * reach
        cast.bank.forEach { _, b, _ ->
            if (b === excluding || b.dying) return@forEach
            val dx = b.trueX - x; val dy = b.trueY - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = b }
        }
        return best
    }

    // ---- Looks ---------------------------------------------------------------

    private fun key(definitionId: String, rank: EnemyRank, maskOverride: String?) = definitionId + "|" + rank.name + "|" + (maskOverride ?: "")

    private fun lookFor(definitionId: String, rank: EnemyRank, maskOverride: String?, motionOverride: String?): Look {
        val key = key(definitionId, rank, maskOverride)
        val look = looks.getOrPut(key) {
            val genome = CharacterMasks.genomeFor(definitionId, rank, maskOverride)
            val profileId = motionOverride ?: CharacterMasks.profileIdFor(genome)
            val profile = MotionProfiles.resolve(profileId, profileOverrides, MotionProfiles.resolve(CharacterMasks.profileIdFor(genome), profileOverrides))
            Look(genome, profile.copy(scale = profile.scale), CharacterMasks.heightFor(rank))
        }
        if (look.mesh == null && !look.building) {
            val budget = budgetFor(rank)
            val exec = builder
            if (exec == null) {
                look.mesh = MaskSpiritMesher.cached(look.genome, budget)
            } else {
                look.building = true
                exec.execute {
                    look.mesh = runCatching { MaskSpiritMesher.cached(look.genome, budget) }.getOrNull()
                    look.building = false
                }
            }
        }
        return look
    }

    companion object {
        /** How many times a second a mask-maker hero's floating pieces are redrawn. */
        const val PIECES_RATE = 20f

        /** A flash has to jump by this much in a frame to count as a new blow. */
        const val FLASH_EDGE = 0.25f

        /** How far away a blow is looked for when a flinch needs a direction. */
        const val HIT_REACH = 4f

        private const val FORGET_EVERY = 600

        /** Triangles a mask of [rank] is built to: bigger ranks stand bigger on screen, so get more. */
        fun budgetFor(rank: EnemyRank?): Int = when (rank) {
            null -> MaskSpiritMesher.COMPANION_BUDGET
            EnemyRank.MINION -> MaskSpiritMesher.MONSTER_BUDGET
            EnemyRank.ELITE -> MaskSpiritMesher.MONSTER_BUDGET
            EnemyRank.CHAMPION -> 2400
            EnemyRank.BOSS -> MaskSpiritMesher.COMPANION_BUDGET
        }
    }
}
