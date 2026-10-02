package com.stratum.engine.model.mask

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.motion.MotionProfiles
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaskSpiritTest {

    // ---- Meshes --------------------------------------------------------------

    @Test
    fun `every preset meshes within the phone budget, lit and watertight enough to read`() {
        MaskGenome.presets.forEach { g ->
            for (budget in intArrayOf(MaskSpiritMesher.MONSTER_BUDGET, MaskSpiritMesher.COMPANION_BUDGET)) {
                val mesh = MaskSpiritMesher.build(g, budget)
                assertTrue(mesh.triangleCount <= budget, "${g.name} at $budget: ${mesh.triangleCount} triangles")
                assertTrue(mesh.triangleCount >= budget / 3, "${g.name} at $budget spent only ${mesh.triangleCount} triangles")
                assertTrue(mesh.positions.all { it.isFinite() } && mesh.normals.all { it.isFinite() }, g.name)
                assertTrue((0 until mesh.vertexCount).any { mesh.channels[it] == GlowChannel.EYES }, "${g.name} has eyes that light")
                // About one unit tall, centred on the face.
                val zs = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + 2] }
                assertEquals(1f, zs.max() - zs.min(), 0.12f, g.name)
            }
        }
    }

    @Test
    fun `a symmetric genome meshes symmetric`() {
        MaskGenome.presets.filter { it.symmetric }.forEach { g ->
            val mesh = MaskSpiritMesher.build(g, MaskSpiritMesher.MONSTER_BUDGET)
            var left = 0.0; var right = 0.0; var leftArea = 0.0; var rightArea = 0.0
            for (t in 0 until mesh.triangleCount) {
                val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
                val cx = (mesh.positions[a * 3] + mesh.positions[b * 3] + mesh.positions[c * 3]) / 3f
                val area = area(mesh, a, b, c)
                if (cx < 0f) { left += -cx * area; leftArea += area } else { right += cx * area; rightArea += area }
            }
            assertEquals(1.0, leftArea / rightArea, 0.04, "${g.name}: surface area left against right")
            assertEquals(1.0, left / right, 0.05, "${g.name}: spread left against right")
            // And the eyes sit mirrored.
            assertEquals(-mesh.eyes[0], mesh.eyes[3], 0.03f, "${g.name} eyes")
        }
    }

    @Test
    fun `a mesh is built once per genome and shared`() {
        val g = MaskGenome.presets[2]
        assertSame(MaskSpiritMesher.cached(g, 1600), MaskSpiritMesher.cached(g, 1600))
        val a = MaskSpiritMesher.build(g, 1600); val b = MaskSpiritMesher.build(g, 1600)
        assertTrue(a.positions.contentEquals(b.positions) && a.indices.contentEquals(b.indices), "deterministic")
    }

    private fun area(m: SpiritMesh, a: Int, b: Int, c: Int): Double {
        val p = m.positions
        val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
        val x = uy * vz - uz * vy; val y = uz * vx - ux * vz; val z = ux * vy - uy * vx
        return 0.5 * kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
    }

    // ---- Which mask a monster wears ----------------------------------------

    @Test
    fun `a monster's mask is the same every time, and differs between monsters`() {
        val ids = listOf("igbo:ogu_brute", "igbo:shadow_leopard", "igbo:catacomb_guardian", "igbo:mmuo_wraith", "igbo:ram_hunter", "pack:thing_1")
        ids.forEach { id -> assertEquals(CharacterMasks.genomeFor(id), CharacterMasks.genomeFor(id), id) }
        assertTrue(ids.map { CharacterMasks.genomeFor(it) }.toSet().size == ids.size, "distinct")
        assertEquals(MaskTradition.MGBEDIKE, CharacterMasks.genomeFor("igbo:ogu_brute").tradition)
        assertEquals(MaskTradition.IJELE, CharacterMasks.genomeFor("igbo:catacomb_guardian").tradition)
        // The seed is fixed, not String.hashCode's: this exact genome, forever.
        assertEquals(MaskCodec.encode(CharacterMasks.genomeFor("pack:thing_1")), MaskCodec.encode(CharacterMasks.genomeFor("pack:thing_1")))
    }

    @Test
    fun `rank makes a mask more ornate and bigger`() {
        val minion = CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.MINION)
        val boss = CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.BOSS)
        assertTrue(boss.ornament >= minion.ornament && boss.crestHeight >= minion.crestHeight)
        assertTrue(boss.crest != CrestForm.NONE)
        assertTrue(CharacterMasks.heightFor(EnemyRank.BOSS) > CharacterMasks.heightFor(EnemyRank.ELITE))
        assertTrue(CharacterMasks.heightFor(EnemyRank.ELITE) > CharacterMasks.heightFor(EnemyRank.MINION))
    }

    @Test
    fun `a pack can pin a monster's mask by preset name or genome code`() {
        val preset = MaskGenome.presets[3]
        assertEquals(preset, CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.MINION, preset.name.uppercase()))
        val code = MaskCodec.encode(MaskGenome.random(7L, MaskTradition.ELEPHANT))
        assertEquals(MaskTradition.ELEPHANT, CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.MINION, code).tradition)
        // Nonsense falls back to the derived mask rather than failing.
        assertEquals(CharacterMasks.genomeFor("igbo:ogu_brute"), CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.MINION, "no such mask"))
    }

    @Test
    fun `the tradition picks the temperament`() {
        assertEquals(MotionProfiles.MGBEDIKE.id, CharacterMasks.profileIdFor(MaskGenome.random(1L, MaskTradition.MGBEDIKE)))
        assertEquals(MotionProfiles.MAIDEN.id, CharacterMasks.profileIdFor(MaskGenome.random(1L, MaskTradition.MAIDEN)))
    }

    // ---- The fight, read into motion ------------------------------------------

    private class Fight {
        val masks = MaskCharacters()
        var heroState = AnimationState.IDLE
        var foeState = AnimationState.IDLE
        var foeFlash = 0f
        var heroFlash = 0f
        var foeCasting = false
        var foeAlive = true

        fun frame(): Fight {
            masks.begin()
            masks.hero("player", 0f, 0f, 0f, 1f, 0f, heroState, heroFlash, aimX = 1.5f, aimY = 0f)
            if (foeAlive) masks.monster("m1", "igbo:ogu_brute", EnemyRank.MINION, 1.5f, 0f, 0f, -1f, 0f, foeState, foeFlash, casting = foeCasting, aimX = 0f, aimY = 0f)
            masks.advance(1f / 60f)
            return this
        }

        fun pose(id: String) = masks.cast.spiritOf(id)!!.pose
        fun body(id: String) = masks.cast.body(id)!!
    }

    @Test
    fun `a swing starting makes the mask strike, and a skill makes its eyes flare`() {
        val f = Fight()
        repeat(60) { f.frame() }
        f.heroState = AnimationState.ATTACK; f.frame()
        assertTrue(f.body("player").strikeAge >= 0f, "struck")
        var flare = 0f
        repeat(12) { flare = maxOf(flare, f.frame().pose("player").flare) }
        assertTrue(flare > 0.5f, "flared $flare")
        f.heroState = AnimationState.IDLE; repeat(60) { f.frame() }
        f.heroState = AnimationState.SPECIAL; f.frame()
        var eyes = 0f
        repeat(20) { eyes = maxOf(eyes, f.frame().pose("player").eyes) }
        assertTrue(eyes > 1f, "eyes $eyes")
    }

    @Test
    fun `a monster winding up a skill casts`() {
        val f = Fight()
        repeat(30) { f.frame() }
        f.foeCasting = true; f.frame()
        assertTrue(f.body("m1").castAge >= 0f)
    }

    @Test
    fun `a hit flash rising makes the struck mask flinch away from its attacker`() {
        val f = Fight()
        repeat(60) { f.frame() }
        val restX = f.pose("m1").x
        f.foeFlash = 1f; f.frame()
        assertTrue(f.body("m1").hitAge >= 0f, "flinched")
        assertTrue(f.body("m1").hitDirX > 0.9f, "away from the hero, who is at -x")
        var furthest = 0f; var crack = 0f
        repeat(10) { f.frame(); furthest = maxOf(furthest, f.pose("m1").x - restX); crack = maxOf(crack, f.pose("m1").crack) }
        assertTrue(furthest > 0.08f, "thrown back $furthest")
        assertTrue(crack > 0.5f, "cracked")
        // A flash that is already up is not a new blow.
        val age = f.body("m1").hitAge
        f.frame()
        assertTrue(f.body("m1").hitAge > age)
    }

    @Test
    fun `feedback cues reach the nearest mask -- a crit flashes the striker's eyes, a kill shatters`() {
        val f = Fight()
        repeat(60) { f.frame() }
        val struck = f.masks.cue(MaskCue.CRIT, 1.4f, 0.1f, fromX = 0f, fromY = 0f)
        assertEquals("m1", struck?.id)
        assertTrue(f.body("player").critAge >= 0f, "the hero's eyes flash")
        assertNull(f.masks.cue(MaskCue.BLOCK, 40f, 40f), "nothing that far away")
        f.masks.cue(MaskCue.BLOCK, "player")
        assertTrue(f.body("player").blockAge >= 0f)
        f.foeAlive = false
        var shatter = 0f
        repeat(120) { f.frame(); f.masks.cast.spiritOf("m1")?.let { shatter = maxOf(shatter, it.pose.shatter) } }
        assertTrue(shatter > 0.9f, "shattered $shatter")
        assertNull(f.masks.cast.spiritOf("m1"), "and gone")
    }

    @Test
    fun `the hero's mask comes back after a respawn instead of staying a finished death`() {
        val masks = MaskCharacters()
        fun frame(state: AnimationState, dt: Float = 0.1f) {
            masks.begin(); masks.hero("player", 0f, 0f, 0f, 0f, 1f, state); masks.advance(dt)
        }
        frame(AnimationState.IDLE)
        repeat(60) { frame(AnimationState.DIE) }
        val dead = assertNotNull(masks.spirits.firstOrNull { it.id == "player" })
        assertTrue(dead.pose.opacity < 0.1f || dead.pose.shatter > 0.9f, "the death played out")
        // Respawned: the game reports the hero alive again under the same id.
        repeat(40) { frame(AnimationState.IDLE) }
        val back = assertNotNull(masks.spirits.firstOrNull { it.id == "player" })
        assertTrue(back.pose.opacity > 0.9f, "the mask is drawn again after the respawn, opacity ${back.pose.opacity}")
        assertTrue(back.pose.shatter < 0.05f, "and whole, shatter ${back.pose.shatter}")
    }

    @Test
    fun `the hero wears the chosen mask`() {
        val masks = MaskCharacters()
        masks.heroGenome = MaskGenome.presets[4]
        masks.begin(); masks.hero("player", 0f, 0f, 0f, 0f, 1f); masks.advance(1f / 60f)
        assertSame(MaskSpiritMesher.cached(MaskGenome.presets[4], MaskSpiritMesher.COMPANION_BUDGET), masks.spirits.single().mesh)
    }

    @Test
    fun `with a builder, a new mask builds off the frame and draws once it is ready`() {
        val queue = ArrayList<Runnable>()
        val masks = MaskCharacters(builder = { queue += it })
        masks.begin()
        assertNull(masks.monster("m1", "pack:slow_to_build", EnemyRank.ELITE, 0f, 0f, 0f, 0f, 1f))
        assertEquals(1, queue.size)
        assertNull(masks.monster("m1", "pack:slow_to_build", EnemyRank.ELITE, 0f, 0f, 0f, 0f, 1f))
        assertEquals(1, queue.size, "asked once")
        queue.single().run()
        assertTrue(masks.ready("pack:slow_to_build", EnemyRank.ELITE))
        assertNotNull(masks.monster("m1", "pack:slow_to_build", EnemyRank.ELITE, 0f, 0f, 0f, 0f, 1f))
    }

    @Test
    fun `five hundred mask characters, fight and all, in under a millisecond of CPU`() {
        val n = 500
        val masks = MaskCharacters(capacity = n + 1)
        val defs = listOf("igbo:ogu_brute", "igbo:shadow_leopard", "igbo:catacomb_guardian", "igbo:mmuo_wraith", "igbo:ram_hunter")
        val ids = Array(n) { "m$it" }
        val states = arrayOf(AnimationState.IDLE, AnimationState.WALK, AnimationState.ATTACK)
        fun frame(t: Int) {
            masks.begin()
            masks.hero("player", 25f, 10f, 0f, 1f, 0f, if (t % 40 < 5) AnimationState.ATTACK else AnimationState.IDLE)
            for (i in 0 until n) {
                val a = i * 0.37f + t * 0.01f
                val x = (i % 25) * 2f + kotlin.math.cos(a); val y = (i / 25) * 2f + kotlin.math.sin(a)
                masks.monster(
                    ids[i], defs[i % defs.size], if (i % 50 == 0) EnemyRank.ELITE else EnemyRank.MINION, x, y, 0f, -kotlin.math.sin(a), kotlin.math.cos(a),
                    states[((t + i) / 30) % 3], flash = if ((t + i) % 90 < 3) 1f else 0f, aimX = 25f, aimY = 10f,
                )
            }
            masks.advance(1f / 60f)
        }
        repeat(600) { frame(it) }
        val frames = 600
        val start = System.nanoTime()
        for (t in 0 until frames) frame(600 + t)
        val perFrame = (System.nanoTime() - start) / frames / 1e6
        println("BENCH MaskCharacters: $n monsters + hero (signals, springs, layers, fringe, scene poses) = %.3f ms a frame".format(perFrame))
        assertEquals(n + 1, masks.spirits.size)
        assertTrue(perFrame < 4.0, "took $perFrame ms")
    }

    @Test
    fun `mesh build time`() {
        val times = MaskGenome.presets.map { g ->
            val s = System.nanoTime(); MaskSpiritMesher.build(g, MaskSpiritMesher.MONSTER_BUDGET); (System.nanoTime() - s) / 1e6
        }
        println("BENCH MaskSpiritMesher: monster budget, per mask ms: " + times.joinToString { "%.0f".format(it) })
        assertTrue(times.all { abs(it) < 5000.0 })
    }
}
