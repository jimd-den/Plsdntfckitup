package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.PoseCell

/**
 * One pose to ask for, as an instruction to an image editor holding the
 * character already.
 *
 * Deliberately says nothing about who the character is. That is the whole
 * point: the identity comes from the reference image, and the prompt changes
 * only the body. A step that re-described the character would invite the model
 * to redraw it, which is exactly the drift this pipeline exists to avoid.
 */
data class PoseStep(
    val state: AnimationState,
    /** Position within its own animation, so a retry knows what it is replacing. */
    val index: Int,
    val instruction: String,
    /** Which way the body is turned. Defaulted, so the front is unchanged. */
    val view: PoseView = PoseView.FRONT,
) {
    /** Stable across runs, so a stored pose can be matched to the step that asked for it. */
    val key: String get() = PoseCell.keyOf(state, index, view.keySuffix)
}

/**
 * The sequence of poses that makes a character animate.
 *
 * The reason this is data rather than a prompt template: animation is a craft
 * with known answers, and they are not things a language model should be
 * improvising per run. A walk cycle is contact, passing, contact, passing --
 * that has been true since before computers, and writing it down once means
 * every character gets a walk that reads as walking rather than as whatever the
 * model associated with the word "walking" that day.
 *
 * Frame counts are small on purpose. Every frame is a separate call to an image
 * model: four frames of a walk is four generations, and a seven-state character
 * at six frames each is forty-two. The counts here are the fewest that read as
 * the motion they name.
 */
data class PoseScript(val steps: List<PoseStep>) {

    val states: List<AnimationState>
        get() = steps.map { it.state }.distinct()

    fun stepsFor(state: AnimationState): List<PoseStep> = steps.filter { it.state == state }

    /** The angles this script draws, in sheet order. */
    val views: List<PoseView>
        get() = steps.map { it.view }.distinct()

    fun stepsFor(state: AnimationState, view: PoseView): List<PoseStep> =
        steps.filter { it.state == state && it.view == view }

    fun stepsFor(view: PoseView): List<PoseStep> = steps.filter { it.view == view }

    /**
     * How many frames each animation actually has drawn, per view.
     *
     * The sheet's column count comes from this, and it has to be counted
     * *within* one view. Counting across them doubles it, and the symptom is
     * brutal and silent: a sheet planned twice as wide as the animation it
     * holds, with every second half-row a cell whose pose was never asked for.
     * A twelve frame character with an away view reported a hundred and
     * sixty-eight unreadable poses, which is exactly twelve missing columns
     * times seven states times two views.
     *
     * Taken as the largest count any single view has, so an away block that
     * came back one frame short still gets a sheet wide enough for the front
     * it was drawn to match, and the one missing frame is reported as missing
     * rather than quietly narrowing every animation.
     */
    fun drawnCounts(drawn: Set<String>): Map<AnimationState, Int> =
        states.associateWith { state ->
            views.maxOfOrNull { view ->
                stepsFor(state, view).count { it.key in drawn }
            } ?: 0
        }.filterValues { it > 0 }

    /** The views with anything drawn in them, in sheet order. */
    fun drawnViews(drawn: Set<String>): List<PoseView> =
        views.filter { view -> stepsFor(view).any { it.key in drawn } }

    /** How many frames each state ends up with, which is what the sheet is planned from. */
    fun frameCounts(): Map<AnimationState, Int> =
        states.associateWith { state ->
            // Counted within one view. Both views hold the same animation at
            // the same length, and counting across them would plan a sheet
            // twice as wide as the walk it is laying out.
            stepsFor(state, views.firstOrNull() ?: PoseView.FRONT).size
        }

    /**
     * What is left to draw, given what is already on disk.
     *
     * The unit of restart. Forty image generations is long enough that
     * something will interrupt it -- a dropped connection, a rate limit, the
     * screen being closed -- and the only acceptable answer to any of those is
     * to carry on from where it stopped.
     */
    fun remaining(done: Set<String>): List<PoseStep> = steps.filterNot { it.key in done }

    fun progress(done: Set<String>): Float {
        if (steps.isEmpty()) return 0f
        return steps.count { it.key in done }.toFloat() / steps.size
    }

    companion object {

        /**
         * The script for a set of states, in the canonical order.
         *
         * [frames] is per state, because the states do not need the same
         * number. An idle and a roll are the two that read worst when they are
         * short -- one is a loop the eye watches for minutes at a time, the
         * other passes through a position the body cannot hold -- while a
         * death is seen once and can be four frames without anyone minding.
         * Charging every state the same count means either paying for frames
         * nothing needs or starving the two that do.
         */
        fun of(
            states: Collection<AnimationState>,
            frames: Map<AnimationState, Int> = emptyMap(),
            views: List<PoseView> = listOf(PoseView.FRONT),
        ): PoseScript = PoseScript(
            // Views outermost, so every front frame is asked for before any
            // away frame. A run that is stopped halfway then leaves a complete
            // set of one angle rather than half of each, and a character with
            // no back is playable where a character with half a walk is not.
            views.flatMap { view ->
                AnimationState.generatedRowOrder
                    .filter { it in states }
                    .flatMap { state ->
                        posesFor(state, frames[state] ?: DEFAULT_FRAMES)
                            .mapIndexed { index, instruction ->
                                PoseStep(state, index, instruction, view)
                            }
                    }
            },
        )

        /** Everything a playable character needs. Forty frames is an evening, not a coffee. */
        fun full(
            frames: Map<AnimationState, Int> = emptyMap(),
            views: List<PoseView> = listOf(PoseView.FRONT),
        ): PoseScript = of(AnimationState.entries, frames, views)

        /** Idle, walk, attack, death: what an enemy is actually seen doing. */
        fun enemy(
            frames: Map<AnimationState, Int> = emptyMap(),
            views: List<PoseView> = listOf(PoseView.FRONT),
        ): PoseScript = of(
            listOf(
                AnimationState.IDLE,
                AnimationState.WALK,
                AnimationState.ATTACK,
                AnimationState.DIE,
            ),
            frames,
            views,
        )

        /**
         * The poses of one animation, in play order.
         *
         * Each is written as a change of body, not a mood: "left foot forward,
         * heel touching the ground, right arm swung forward" survives being
         * handed to an image editor in a way "walking confidently" does not.
         */
        fun posesFor(state: AnimationState, frames: Int = DEFAULT_FRAMES): List<String> =
            sample(authored(state), frames)

        /**
         * [count] instructions taken evenly across a full cycle.
         *
         * Every animation is written out at its finest useful granularity and
         * then thinned, rather than written once at one length. Thinning a
         * cycle evenly leaves a cycle; padding a short list does not -- asking
         * for twelve frames from six written ones means paying for six
         * duplicate generations and getting an animation that holds every
         * other frame.
         *
         * Taken from the start, so the first frame of a four frame walk and of
         * a twelve frame walk are the same pose. That is what lets a set be
         * extended later without the frames already drawn becoming wrong.
         */
        private fun sample(authored: List<String>, count: Int): List<String> {
            if (authored.isEmpty()) return emptyList()
            val wanted = count.coerceIn(MIN_FRAMES, authored.size)
            if (wanted == authored.size) return authored
            return (0 until wanted).map { i -> authored[i * authored.size / wanted] }
        }

        /**
         * The poses of one animation, in play order, at full granularity.
         *
         * Each is written as a change of body, not a mood: "left foot forward,
         * heel touching the ground, right arm swung forward" survives being
         * handed to an image editor in a way "walking confidently" does not.
         */
        private fun authored(state: AnimationState): List<String> = when (state) {
            AnimationState.IDLE -> listOf(
                "standing at rest, weight settled evenly on both feet, arms relaxed at the " +
                    "sides, shoulders down",
                "the same stance, the very start of a breath in: chest a fraction fuller, " +
                    "shoulders barely lifted. Feet, hands and weight identical",
                "breathing in: chest lifted, shoulders up and a little back, head a hair " +
                    "higher. Feet identical",
                "further in: chest fuller still, spine very slightly straighter, shoulders " +
                    "near their highest. Feet identical",
                "nearly the top of the breath: chest almost full, chin a fraction up, " +
                    "shoulders high. Feet and hands identical",
                "the top of the breath: chest at its fullest, shoulders at their highest, " +
                    "head very slightly back. Feet and hands identical",
                "holding: the same full chest, shoulders beginning to ease, head level again",
                "starting to breathe out: chest falling a little, shoulders coming down",
                "breathing out: chest noticeably lower, shoulders settling, arms hanging " +
                    "a fraction looser",
                "further out: chest almost settled, shoulders nearly down, head level",
                "nearly at rest: shoulders down, chest settled, the smallest lift still left",
                "a hair above the first frame, so the loop closes without a jump",
            )
            AnimationState.WALK -> listOf(
                "mid-stride contact: left leg forward with the heel touching the ground, " +
                    "right leg straight back with the toes still down, right arm swung " +
                    "forward and left arm back",
                "just past contact: weight rolling onto the left foot, right leg lifting " +
                    "at the toe, body beginning to drop",
                "passing position: the right leg swinging through directly under the body " +
                    "with the knee bent, standing on the left leg, body at its lowest, arms " +
                    "close to the sides",
                "past the pass: right knee driving forward, left heel starting to lift, " +
                    "body beginning to rise",
                "reaching: the right leg swung forward at full extension just before the " +
                    "heel lands, body at its highest, left arm reaching forward, right arm back",
                "the reach falling: right heel about to touch, body coming down onto it, " +
                    "left leg fully extended behind",
                "mid-stride contact the other way: right leg forward with the heel touching " +
                    "the ground, left leg straight back with the toes down, left arm swung " +
                    "forward and right arm back",
                "just past contact: weight rolling onto the right foot, left leg lifting at " +
                    "the toe, body beginning to drop",
                "passing position again: the left leg swinging through under the body with " +
                    "the knee bent, standing on the right leg, body at its lowest, arms close " +
                    "to the sides",
                "past the pass: left knee driving forward, right heel starting to lift, body " +
                    "beginning to rise",
                "reaching again: the left leg swung forward at full extension just before " +
                    "the heel lands, body at its highest, right arm reaching forward, left " +
                    "arm back",
                "the reach falling: left heel about to touch, body coming down onto it, " +
                    "right leg fully extended behind",
            )
            AnimationState.ATTACK -> listOf(
                "the start of the wind-up: weight shifting onto the back foot, torso " +
                    "beginning to turn away, weapon arm lifting",
                "wind-up: weight dropped onto the back foot, torso twisted away from the " +
                    "target, weapon drawn back high behind the shoulder",
                "the top of the wind-up: torso turned as far as it goes, weapon at its " +
                    "highest and furthest back, front foot light",
                "the swing beginning: torso rotating forward, weapon coming over and down " +
                    "past the shoulder, front foot planting",
                "the swing at speed: weapon halfway down its arc, torso square to the " +
                    "target, weight driving forward",
                "impact: weight fully forward over the front foot, arms extended, weapon at " +
                    "the far end of its arc where it would strike",
                "just past impact: weapon continuing past the strike, shoulders carried " +
                    "round by it, weight still forward",
                "recovery: weapon carried low and across the body, shoulders squaring back " +
                    "up, weight returning to centre",
                "further into recovery: weapon low at the far side, torso almost square, " +
                    "weight coming back over both feet",
                "settling: weapon held low at the side, shoulders level, still leaning very " +
                    "slightly forward",
                "almost still: weight even, arms low in front of the body, shoulders square",
                "the end of the follow-through: standing nearly square, arms low and " +
                    "relaxed across the front of the body, knees softly bent",
            )
            AnimationState.SPECIAL -> listOf(
                "beginning to gather: knees softening, arms starting to draw in towards the " +
                    "chest, head lowering",
                "gathering: crouched slightly, both arms drawn in towards the chest, head " +
                    "down, body coiled",
                "fully coiled: crouched lower, arms tight to the chest, head furthest down",
                "beginning to rise: knees starting to straighten, arms starting to open",
                "rising: straightening upward, arms sweeping outward and up, head lifting, " +
                    "heels leaving the ground",
                "nearly at full height: arms wide and climbing, chest opening, on the toes",
                "release: arms thrown wide and forward at full extension, chest open, head " +
                    "back, at the peak of the effort",
                "the peak holding: arms still wide, body at full stretch, head back",
                "follow-through: arms falling, body settling back down onto both feet, " +
                    "shoulders dropping",
                "the last of it: arms nearly at the sides, head coming back level, weight " +
                    "settling evenly",
                "almost standing: arms low, shoulders coming square, knees straightening",
                "standing out of it: upright again, arms at the sides, shoulders square, a " +
                    "fraction of the effort still in the stance",
            )
            AnimationState.HURT -> listOf(
                "the instant of impact: head snapped back, chest caved in, both arms flung " +
                    "outward, weight thrown onto the back foot",
                "still going back: head further back, arms still wide, weight almost off " +
                    "the front foot",
                "reeling: doubled forward over the ribs, one arm across the body, staggering " +
                    "back a step and off balance",
                "the stagger deepening: bent lower, both arms coming in to the body, head down",
                "the worst of it: bent low over the front knee, arms pulled tight in, head " +
                    "at its lowest",
                "beginning to catch it: back foot planted, weight starting to stop moving",
                "catching the balance: torso beginning to come back up, one arm still held " +
                    "across the ribs",
                "coming up: torso halfway up, the arm starting to lower from the ribs",
                "straightening: almost upright, shoulders coming back square",
                "nearly recovered: upright, arms lowering, weight coming back even",
                "recovered: standing again with the weight even, arms at the sides, head up, " +
                    "still tensed",
                "settling out of it: standing square, shoulders dropping, the tension going",
            )
            AnimationState.ROLL -> listOf(
                "dropping into it: knees bending hard, torso pitching forward, arms coming in",
                "crouched low and tucked, chin down, arms wrapped in, about to commit forward",
                "committing: weight thrown forward past the feet, shoulder dropping towards " +
                    "the ground, body curling",
                "the shoulder reaching the ground, hips rising above it, legs folding over",
                "inverted mid-roll, tucked into a ball, rolled over one shoulder with the " +
                    "feet above the head",
                "coming over the top: hips passing the shoulder, feet swinging down towards " +
                    "the ground",
                "the feet reaching the ground, body still tightly curled, one hand down",
                "coming out of the roll, uncurling onto one knee with one hand on the ground",
                "pushing up off the knee, torso lifting, the hand leaving the ground",
                "rising out of it, standing back up with the weight forward, ready to move",
                "fully upright again with the momentum still carrying forward, one foot " +
                    "ahead of the other, arms coming down",
                "settled out of the roll: standing square with the weight even, arms at the " +
                    "sides, ready to move again",
            )
            AnimationState.DIE -> listOf(
                "the first give: knees softening, head dropping, arms going slack",
                "staggering: knees buckling, torso pitching forward, arms loose and falling",
                "the legs failing: one knee dropping towards the ground, torso further over",
                "going down: one knee on the ground, one hand catching the fall, head hanging",
                "the arm giving way: the supporting elbow folding, shoulder dropping towards " +
                    "the ground",
                "collapsing: fallen onto the side, limbs folding, no longer supporting any " +
                    "weight",
                "rolling onto the front, one arm trapped under the body, the other flung out",
                "lying still on the ground, face down, limbs slack and splayed, completely " +
                    "motionless",
                "the body settling a little flatter, one arm having fallen further out from " +
                    "the side",
                "settling further, the head turning to rest on its side",
                "almost entirely at rest, nothing raised off the floor but the shoulder",
                "completely at rest and flat, absolutely motionless",
            )
        }

        /** Six a state: a 6x7 sheet, which is the shape most engines expect. */
        const val DEFAULT_FRAMES = 6

        /** Below four an animation is a slideshow; above twelve nothing is written. */
        const val MIN_FRAMES = 2
        const val MAX_FRAMES = 12

        /** The counts offered, each of which divides the authored cycle evenly. */
        val FRAME_CHOICES = listOf(3, 4, 6, 12)
    }
}
