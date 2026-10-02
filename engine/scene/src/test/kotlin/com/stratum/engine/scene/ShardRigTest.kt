package com.stratum.engine.scene

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ShardRigTest {

    private fun spirit(t: SpiritTemperament = SpiritTemperament.BREATHING): ShatteredSpirit {
        val piece = SpiritMesh(floatArrayOf(0f, 0f, 0f, 0.01f, 0f, 0f, 0f, 0f, 0.01f), FloatArray(9) { if (it % 3 == 1) 1f else 0f }, IntArray(3), ByteArray(3), IntArray(3), intArrayOf(0, 1, 2))
        val shards = listOf(
            SpiritShard(piece, ShardRole.JAW, 0f, 0f, -0.2f, 0f, 0f, -0.02f, 0f, 0.005f, 0.003f, 0.006f),
            SpiritShard(piece, ShardRole.BROW_LEFT, -0.1f, 0f, 0.1f, -0.02f, 0f, 0.01f, 1f, 0.005f, 0.003f, 0.006f),
        )
        return ShatteredSpirit(piece, 0f, 0f, 0f, shards, t, intArrayOf(1, 1), FloatArray(6))
    }

    private fun run(rig: ShardRig, pose: SpiritPose, seconds: Float, dt: Float = 1f / 60f, move: (Float) -> Unit = {}) {
        var t = 0f
        while (t < seconds) { move(t); rig.update(pose, dt); t += dt }
    }

    @Test
    fun `pieces trail behind a dash and snap back when it stops`() {
        val rig = ShardRig(spirit()); val pose = SpiritPose().also { it.scale = 1f }
        run(rig, pose, 1f)
        run(rig, pose, 0.3f) { pose.x += 8f / 60f }
        assertTrue(rig.lag[0] < -0.05f, "dragged behind the dash: ${rig.lag[0]}")
        run(rig, pose, 1.5f)
        assertTrue(abs(rig.lag[0]) < 0.01f, "back in place: ${rig.lag[0]}")
    }

    @Test
    fun `stable at any frame time and through a teleport`() {
        for (dt in listOf(1f / 240f, 1f / 30f, 0.5f)) {
            val rig = ShardRig(spirit(SpiritTemperament.STORM)); val pose = SpiritPose()
            run(rig, pose, 3f, dt) { pose.x += 3f * dt; pose.flare = if (it % 1f < 0.3f) 1f else 0f }
            pose.x += 500f
            run(rig, pose, 1f, dt)
            assertTrue(rig.lag.all { it.isFinite() && abs(it) < 1f } && rig.locals.all { l -> l.all { it.isFinite() } }, "dt $dt")
        }
    }

    @Test
    fun `a strike bursts the jaw down and the brows up`() {
        val rig = ShardRig(spirit()); val pose = SpiritPose()
        run(rig, pose, 1f)
        val jaw = rig.locals[0][11]; val brow = rig.locals[1][11]
        run(rig, pose, 0.2f) { pose.flare = 1f }
        assertTrue(rig.locals[0][11] < jaw - 0.02f, "the jaw drops")
        assertTrue(rig.locals[1][11] > brow + 0.02f, "the brows lift")
    }

    @Test
    fun `it breathes, blinks and glances`() {
        val rig = ShardRig(spirit()); val pose = SpiritPose()
        var lo = 1f; var hi = 0f; var dark = 1f; var gx = 0f
        run(rig, pose, 12f) { lo = minOf(lo, rig.breath); hi = maxOf(hi, rig.breath); dark = minOf(dark, rig.eyeLight); gx = maxOf(gx, abs(rig.gazeX)) }
        assertTrue(lo < 0.05f && hi > 0.95f, "a full breath: $lo..$hi")
        assertTrue(dark < 0.2f, "a blink: $dark")
        assertTrue(gx > 0.002f, "the eyes look about: $gx")
    }
}
