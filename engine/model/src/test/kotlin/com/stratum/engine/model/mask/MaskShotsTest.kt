package com.stratum.engine.model.mask

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes every preset and a dozen random masks to PNGs, with a contact sheet.
 *
 * Masks are judged by eye, so the pictures are part of the work: set
 * `STRATUM_MASK_SHOTS` to a folder (the repo keeps them in
 * `docs/screenshots/masks`) and run the engine's tests to refresh them.
 * Without it they go to the build folder, so the test still proves every
 * mask renders.
 */
class MaskShotsTest {

    @Test
    fun `presets and random masks render to pictures`() {
        val dir = System.getenv("STRATUM_MASK_SHOTS")?.let(::File) ?: File("build/mask-shots")
        val cards = ArrayList<java.awt.image.BufferedImage>()
        MaskGenome.presets.forEach { g ->
            val m = IgboMaskGenerator.generate(g)
            val card = MaskShots.card(m, "${g.name} · ${MaskPalettes[g.palette].name}")
            MaskShots.write(card, File(dir, "preset-" + slug(g.name) + ".png"))
            cards += card
        }
        MaskShots.write(MaskShots.sheet(cards, 4), File(dir, "presets-sheet.png"))
        val randoms = (1..12).map { n ->
            val g = MaskGenome.random(n * 7919L)
            val m = IgboMaskGenerator.generate(g)
            val card = MaskShots.card(m, "seed ${n * 7919} · ${g.tradition.label} · ${MaskPalettes[g.palette].name}")
            MaskShots.write(card, File(dir, "random-%02d.png".format(n)))
            card
        }
        MaskShots.write(MaskShots.sheet(randoms, 4), File(dir, "random-sheet.png"))
        assertTrue(File(dir, "presets-sheet.png").isFile)
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
