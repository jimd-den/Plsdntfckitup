package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.model.mask.sculpt.Anatomy.Part
import com.stratum.engine.model.mask.sculpt.MaskCarver.Control
import com.stratum.engine.scene.SpiritMesh
import java.math.BigInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaskCarverTest {

    @Test
    fun `there are billions of masks before a single slider`() {
        val named = MaskCarver.designs()
        assertTrue(named > BigInteger.valueOf(1_000_000_000L), "named choices alone: $named")
        assertTrue(MaskCarver.designs(sliders = true) > named * BigInteger.TEN.pow(40))
    }

    @Test
    fun `the peoples' traditions alone roll over a billion different masks`() {
        assertTrue(MaskCulture.traditions.size >= 40, "traditions: ${MaskCulture.traditions.size}")
        assertTrue(MaskCulture.traditions.map { it.id }.toSet().size == MaskCulture.traditions.size, "every tradition has its own id")
        val rollable = MaskCulture.rollable()
        assertTrue(rollable > BigInteger.valueOf(1_000_000_000L), "rollable from the grammars: $rollable")
    }

    @Test
    fun `every part of the mask has controls, and every dial is among them`() {
        for (p in Part.entries) assertTrue(MaskCarver.controls(p).isNotEmpty(), "$p has controls")
        for (d in Anatomy.Dial.entries) assertNotNull(MaskCarver.control("dial." + d.name.lowercase()), "$d is a control")
    }

    @Test
    fun `a share code carries the whole mask`() {
        for (k in 1..60) {
            val spec = MaskCarver.roll(k * 977L)
            val code = MaskCarver.encode(spec)
            assertTrue(MaskCarver.isCode(code))
            val back = assertNotNull(MaskCarver.decode(code), code)
            assertEquals(code, MaskCarver.encode(back), "a code survives a round trip")
            assertEquals(spec.copy(width = 0f, length = 0f, crownSize = 0f, raffia = 0f, dials = emptyMap()), back.copy(width = 0f, length = 0f, crownSize = 0f, raffia = 0f, dials = emptyMap()))
            for (d in Anatomy.Dial.entries) assertTrue(abs(spec.dial(d) - back.dial(d)) <= 0.5f / 255f + 1e-6f, "$d kept to a step")
        }
        assertEquals(null, MaskCarver.decode("im1:nope"))
        assertEquals(null, MaskCarver.decode("sm1:%%%"))
    }

    @Test
    fun `a roll keeps the locked parts`() {
        val from = MaskCarver.roll(5L)
        val locked = setOf(Part.EYES, Part.HORNS, Part.SURFACE)
        repeat(10) { k ->
            val next = MaskCarver.roll(100L + k, locked = locked, from = from)
            for (c in MaskCarver.controls.filter { it.part in locked }) assertEquals(value(c, from), value(c, next), "${c.id} is locked")
        }
    }

    @Test
    fun `variations change only unlocked parts, and each changes something`() {
        val spec = MaskCarver.roll(8L)
        val locked = setOf(Part.FACE, Part.MOUTH)
        val vs = MaskCarver.variations(spec, 3L, 12, locked)
        assertEquals(12, vs.size)
        for (v in vs) for (c in MaskCarver.controls.filter { it.part in locked }) assertEquals(value(c, spec), value(c, v), c.id)
        assertTrue(vs.count { it != spec } >= 10)
    }

    /**
     * Customisable means seen: every control, set another way, carves a
     * different mask, on one of a few masks that wear every part.
     */
    @Test
    fun `every control changes the carved mask`() {
        val a = MaskSpec(
            name = "All", tradition = "ikenga", crown = Anatomy.Crown.RAM, coiffure = Anatomy.Coiffure.LOBES, beard = Anatomy.Beard.RAFFIA,
            ears = Anatomy.Ears.SMALL, scars = setOf(Anatomy.Scar.ICHI, Anatomy.Scar.CHEEKS), patterns = setOf(Anatomy.Pattern.DOTS, Anatomy.Pattern.TRIANGLES, Anatomy.Pattern.EYE_RINGS),
            adorn = setOf(Anatomy.Adorn.BEADS), finish = Anatomy.Finish.KAOLIN, brow = Anatomy.Brow.ARCH, raffia = 0.5f, seed = 1L,
        )
        val b = a.copy(crown = Anatomy.Crown.TIERS, coiffure = Anatomy.Coiffure.COMBS, finish = Anatomy.Finish.BLACKENED, adorn = setOf(Anatomy.Adorn.RAFFIA_COLLAR), beard = Anatomy.Beard.NONE)
        val c = a.copy(crown = Anatomy.Crown.ANTELOPE, coiffure = Anatomy.Coiffure.CORNROWS, finish = Anatomy.Finish.POLYCHROME)
        val bases = listOf(a, b, c)
        val carved = HashMap<MaskSpec, Long>()
        fun print(s: MaskSpec): Long = carved.getOrPut(s) { fingerprint(MaskSculptor.carve(s, MaskSculptor.Detail.FAR)) }
        val dull = ArrayList<String>()
        for (control in MaskCarver.controls) {
            if (control.id == "glow") continue // Light, not carving: checked on the mesh's glow colours below.
            val changes = bases.any { base -> alternatives(control, base).any { print(it) != print(base) } }
            if (!changes) dull += control.id
        }
        assertTrue(dull.isEmpty(), "controls that carve nothing: $dull")
        val lit = MaskCarver.control("glow") as Control.Colour
        val other = lit.set(a, (lit.get(a) + 1) % lit.options.size)
        assertTrue(MaskSculptor.carve(other, MaskSculptor.Detail.FAR).glowColors.toSet() != MaskSculptor.carve(a, MaskSculptor.Detail.FAR).glowColors.toSet())
    }

    @Test
    fun `a portrait shows the mask with its eyes alight`() {
        val mesh = MaskSculptor.carve(MaskCarver.roll(21L, MaskCulture.tradition("mgbedike")), MaskSculptor.Detail.FAR)
        val px = MaskPortrait.render(mesh, 120, 140)
        assertEquals(120 * 140, px.size)
        val bg = px[0]
        assertTrue(px.count { it != bg } > px.size / 5, "the mask fills the picture")
        // The eyes burn: with their light out, the picture is darker where they are.
        val dark = MaskPortrait.render(mesh, 120, 140, glow = 0f)
        fun lum(p: Int) = ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
        val lit = px.indices.count { lum(px[it]) > lum(dark[it]) + 60 }
        assertTrue(lit > 10, "eyes burn: $lit")
    }

    private fun value(c: Control, s: MaskSpec): Any = when (c) {
        is Control.Pick -> c.get(s)
        is Control.Flags -> c.get(s)
        is Control.Colour -> c.get(s)
        is Control.Slider -> c.get(s)
    }

    /** A couple of other settings of [control] than [base] has. */
    private fun alternatives(control: Control, base: MaskSpec): List<MaskSpec> = when (control) {
        is Control.Pick -> { val at = control.get(base); listOf(1, 2).map { control.set(base, (at + it) % control.options.size) } }
        is Control.Colour -> { val at = control.get(base); listOf(3, 7).map { control.set(base, (at + it) % control.options.size) } }
        is Control.Flags -> { val on = control.get(base); listOf(0, control.options.size - 1).map { i -> control.set(base, if (i in on) on - i else on + i) } }
        is Control.Slider -> listOf(0f, 1f).map { control.set(base, it) }
    }

    private fun fingerprint(m: SpiritMesh): Long {
        var h = m.indices.size.toLong()
        for (i in m.positions.indices step 7) h = h * 31 + (m.positions[i] * 4096f).toInt()
        for (i in m.colors.indices step 5) h = h * 31 + m.colors[i]
        return h
    }
}
