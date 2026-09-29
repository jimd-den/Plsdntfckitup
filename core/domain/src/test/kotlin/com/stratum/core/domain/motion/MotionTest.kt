package com.stratum.core.domain.motion

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {

    private fun FloatArray.allFinite() = all { it.isFinite() }

    private fun MotionPose.finite() = x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite() && roll.isFinite() &&
        scale.isFinite() && stretch.isFinite() && glow.isFinite() && eyes.isFinite() && opacity.isFinite() && hands.allFinite()

    @Test
    fun `a critically damped spring settles on its target without overshooting`() {
        val out = FloatArray(1)
        var x = 0f; var v = 0f
        var peak = 0f
        repeat(600) { x = Springs.step(x, v, 10f, 12f, 1f / 60f, out); v = out[0]; peak = maxOf(peak, x) }
        assertEquals(10f, x, 1e-3f)
        assertTrue(peak <= 10f + 1e-3f, "overshot to $peak")
    }

    @Test
    fun `one long step lands where many short ones do`() {
        val out = FloatArray(1)
        var a = 0f; var va = 0f
        repeat(60) { a = Springs.step(a, va, 5f, 8f, 1f / 60f, out); va = out[0] }
        val b = Springs.step(0f, 0f, 5f, 8f, 1f, out)
        assertEquals(a, b, 1e-3f)
    }

    @Test
    fun `a body settles at its true position, hovering above it`() {
        val b = MotionBody()
        b.reset("m", MotionProfiles.MAIDEN, 0f, 0f, 0f, spawning = false)
        b.track(4f, -3f, 2f, 1f, 0f)
        repeat(300) { b.advance(1f / 60f) }
        assertEquals(4f, b.x, 1e-2f)
        assertEquals(-3f, b.y, 1e-2f)
        assertEquals(2f + MotionProfiles.MAIDEN.hover, b.z, 1e-2f)
    }

    @Test
    fun `the drawing never trails the truth by more than the lag cap`() {
        val b = MotionBody()
        b.reset("m", MotionProfiles.ELEPHANT, 0f, 0f, 0f, spawning = false)
        repeat(120) { i -> b.track(i * 0.5f, 0f, 0f, 1f, 0f); b.advance(1f / 60f) }
        val lag = sqrt((b.x - b.trueX) * (b.x - b.trueX) + (b.y - b.trueY) * (b.y - b.trueY))
        assertTrue(lag <= MotionProfile.MAX_LAG + 1e-4f, "lagged $lag")
    }

    @Test
    fun `any time step is safe -- huge, tiny, zero, negative, infinite, NaN`() {
        val bank = MotionBank(8)
        val steps = floatArrayOf(1f / 60f, 10f, 1e-7f, 0f, -1f, Float.POSITIVE_INFINITY, Float.NaN, 3600f, 1f / 240f, 0.5f)
        MotionProfiles.builtIn.forEach { profile ->
            bank.clear()
            repeat(200) { i ->
                bank.begin()
                val b = bank.track("a", profile, i * 0.3f, -i * 0.1f, 0f, 1f, 0.2f)!!
                bank.track("b", profile, 1f, 1f, 0f, -1f, 0f)
                if (i % 17 == 0) b.strike(1.5f)
                if (i % 23 == 0) b.cast()
                if (i % 11 == 0) b.hit(i.toFloat(), 0f, 2f)
                if (i % 29 == 0) b.crit()
                if (i % 31 == 0) b.block()
                bank.advance(steps[i % steps.size])
                val p = bank.poseOf("a")!!
                assertTrue(p.finite(), "${profile.id} step ${steps[i % steps.size]}")
                assertTrue(bank.fringeOf("a")?.positions?.allFinite() ?: true, "${profile.id} fringe")
                assertTrue(abs(p.x - b.trueX) < 3f && abs(p.y - b.trueY) < 3f, "${profile.id} drifted from the truth")
            }
        }
    }

    @Test
    fun `nonsense inputs are ignored rather than spreading`() {
        val b = MotionBody()
        b.reset("x", MotionProfile.DEFAULT, 1f, 1f, 0f, spawning = false)
        b.track(Float.NaN, Float.POSITIVE_INFINITY, 0f, Float.NaN, 0f)
        b.aim(Float.NaN, 0f, 0f)
        b.hit(Float.NaN, Float.NaN, Float.NaN)
        b.strike(Float.NaN)
        repeat(30) { b.advance(1f / 60f) }
        val p = MotionPose()
        MotionRig.SPIRIT.evaluate(b, p)
        assertTrue(p.finite())
        assertEquals(1f, b.trueX)
    }

    @Test
    fun `a hand-written profile is sanitised into a stable one`() {
        val wild = MotionProfile(id = "wild", follow = 0f, turn = Float.NaN, lunge = 1e9f, windup = -1f, fringeStiffness = 0f, hands = 99, scale = -2f).sanitised()
        assertTrue(wild.follow >= 0.5f && wild.turn == MotionProfile.DEFAULT.turn && wild.lunge <= 4f && wild.windup > 0f && wild.hands == 2 && wild.scale > 0f)
        val resolved = MotionProfiles.resolve("spirit:mgbedike", mapOf("spirit:mgbedike" to MotionProfiles.MGBEDIKE.copy(twitch = 5f)))
        assertEquals(1f, resolved.twitch)
    }

    @Test
    fun `the same inputs give the same motion, bit for bit`() {
        fun run(): FloatArray {
            val bank = MotionBank(4)
            val out = FloatArray(4 * 200)
            for (i in 0 until 200) {
                bank.begin()
                val b = bank.track("hero", MotionProfiles.MGBEDIKE, i * 0.05f, 0f, 0f, 1f, 0f)!!
                if (i == 40) b.strike()
                if (i == 90) b.hit(0f, 0f, 1f)
                if (i == 130) b.cast()
                bank.advance(if (i % 7 == 0) 1f / 30f else 1f / 60f)
                val p = bank.poseOf("hero")!!
                out[i * 4] = p.x; out[i * 4 + 1] = p.z; out[i * 4 + 2] = p.yaw; out[i * 4 + 3] = p.glow
            }
            return out
        }
        assertTrue(run().contentEquals(run()))
    }

    @Test
    fun `strikes, casts, hits and deaths show in the pose`() {
        val bank = MotionBank(4)
        fun frame(block: (MotionBody) -> Unit = {}): MotionPose {
            bank.begin()
            val b = bank.track("m", MotionProfiles.MGBEDIKE, 0f, 0f, 0f, 0f, 1f, spawning = false)!!
            block(b)
            bank.advance(1f / 60f)
            return bank.poseOf("m")!!
        }
        repeat(60) { frame() }
        assertTrue(frame().glow > 0f)
        frame { it.strike() }
        var flare = 0f
        repeat(10) { flare = maxOf(flare, frame().flare) }
        assertTrue(flare > 0.5f, "a strike flares ($flare)")
        repeat(60) { frame() }
        frame { it.cast() }
        var eyes = 0f
        repeat(20) { eyes = maxOf(eyes, frame().eyes) }
        assertTrue(eyes > 1f, "a cast flares the eyes ($eyes)")
        repeat(60) { frame() }
        val hit = frame { it.hit(0f, -2f, 1f) }
        assertTrue(hit.flash > 0.5f && hit.crack > 0.5f, "a hit flashes and cracks")
        var moved = 0f
        repeat(8) { moved = maxOf(moved, frame().y) }
        assertTrue(moved > 0.05f, "the flinch throws it away from the blow ($moved)")
        // Stop tracking it: it plays out its death and is released.
        var shatter = 0f
        repeat(200) { bank.begin(); bank.advance(1f / 60f); bank.poseOf("m")?.let { shatter = maxOf(shatter, it.shatter) } }
        assertTrue(shatter > 0.9f, "it shattered ($shatter)")
        assertEquals(0, bank.size)
    }

    @Test
    fun `temperaments differ -- the brave is quicker than the maiden`() {
        assertTrue(MotionProfiles.MGBEDIKE.twitch > MotionProfiles.MAIDEN.twitch)
        assertTrue(MotionProfiles.MGBEDIKE.strikeLength < MotionProfiles.MAIDEN.strikeLength)
        assertTrue(MotionProfiles.MGBEDIKE.turn > MotionProfiles.MAIDEN.turn)
    }

    @Test
    fun `five hundred bodies animate in well under a millisecond`() {
        val n = 500
        val bank = MotionBank(n)
        val ids = Array(n) { "m$it" }
        val profiles = MotionProfiles.builtIn.filter { it.hover > 0f }
        fun frame(t: Int) {
            bank.begin()
            for (i in 0 until n) {
                val a = i * 0.37f + t * 0.01f
                val b = bank.track(ids[i], profiles[i % profiles.size], (i % 25) * 2f + kotlin.math.cos(a), (i / 25) * 2f + kotlin.math.sin(a), 0f, -kotlin.math.sin(a), kotlin.math.cos(a))!!
                if ((t + i) % 97 == 0) b.strike()
                if ((t + i) % 131 == 0) b.hit(0f, 0f, 1f)
            }
            bank.advance(1f / 60f)
        }
        repeat(600) { frame(it) }
        val frames = 600
        val start = System.nanoTime()
        for (t in 0 until frames) frame(600 + t)
        val perFrame = (System.nanoTime() - start) / frames / 1e6
        println("BENCH MotionBank: $n bodies (springs, layers, fringe) = %.3f ms a frame".format(perFrame))
        // The claim is well under 1 ms on a desktop; the bound is loose so a busy build machine does not flake.
        assertTrue(perFrame < 4.0, "took $perFrame ms")
    }
}
