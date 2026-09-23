package com.stratum.engine.scene.forge

import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.AssetKind
import com.stratum.core.domain.art.AssetTier
import com.stratum.core.domain.art.ForgeOrder
import com.stratum.core.domain.art.ForgePlanner
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeTest {

    private val turf = BlockType("t:turf", "Moss Turf", BlockMaterial.SOIL, topColor = 0xFF2E7D32, sideColor = 0xFF5D4033)
    private val soil = BlockType("t:soil", "Red Soil", BlockMaterial.SOIL)
    private val rock = BlockType("t:rock", "Granite", BlockMaterial.STONE)
    private val tree = BlockType("t:tree", "Iroko Tree", BlockMaterial.FOLIAGE, glyph = "T", isOpaque = false)
    private val torch = BlockType("t:torch", "Brazier", BlockMaterial.METAL, glyph = "F", lightEmission = 12)
    private val water = BlockType("t:water", "Spirit Water", BlockMaterial.LIQUID, glyph = "W", lightEmission = 5)
    private val wall = BlockType("t:wall", "Mud Wall", shape = BlockShape.WALL, isOpaque = false)
    private val pack = ContentPack(
        id = "t", name = "T", author = "t",
        blocks = listOf(turf, soil, rock, tree, torch, water, wall),
        biomes = listOf(
            BiomeDefinition(
                "t:grove", "Grove", "A mossy grove",
                surfaceBlockId = turf.id, subsurfaceBlockId = soil.id, bedrockFillerBlockId = rock.id,
                scatter = listOf(ScatterRule(tree.id, 0.05f)),
            ),
        ),
    )

    @Test
    fun `a pack plans a bounded kit, never an order per block`() {
        val orders = ForgePlanner.plan(ArtDirection.HOUSE, pack)
        val keys = orders.map { it.key }.toSet()
        assertTrue("t:turf/top" in keys && "t:turf/side" in keys)
        assertTrue("prop:t:tree" in keys, "scattered props get a sprite")
        assertTrue("t:wall/side" in keys, "buildable walls get a texture")
        assertTrue("prop:t:torch" in keys, "light sources get a sprite")
        assertTrue("prop:t:water" !in keys, "a liquid is a surface, not an object")
        assertEquals(keys.size, orders.size, "no key is ordered twice")
        assertTrue(orders.size < 24)
    }

    @Test
    fun `grounds and props come in several individuals, and every region gets floor clutter`() {
        val keys = ForgePlanner.plan(ArtDirection.HOUSE, pack).map { it.key }.toSet()
        assertTrue(setOf("t:turf/top", "t:turf/top#1", "t:turf/top#2").all { it in keys })
        assertTrue(setOf("prop:t:tree", "prop:t:tree#1", "prop:t:tree#2").all { it in keys })
        assertEquals(ForgePlanner.DETAILS_PER_REGION, keys.count { it.startsWith("detail:t:grove") })
        val single = ForgePlanner.plan(ArtDirection.HOUSE, pack, variants = 1).map { it.key }
        assertTrue(single.none { '#' in it && !it.startsWith("detail:") }, "one variant means no numbered keys")
    }

    @Test
    fun `heroes and monsters get one still sprite each`() {
        val withCast = pack.copy(
            heroClasses = listOf(com.stratum.core.domain.content.HeroClassDefinition("t:hero", "Warden", title = "Keeper")),
            enemies = listOf(
                com.stratum.core.domain.actor.EnemyDefinition(id = "t:brute", name = "Brute", damageTypeId = "t:blunt"),
            ),
        )
        val actors = ForgePlanner.plan(ArtDirection.HOUSE, withCast).filter { it.key.startsWith("actor:") }
        assertEquals(setOf("actor:t:hero", "actor:t:brute"), actors.map { it.key }.toSet())
        assertTrue(actors.all { "#FF00FF" in it.prompt && "full-body" in it.prompt })
        assertTrue(ForgePlanner.plan(ArtDirection.HOUSE, withCast, includeActors = false).none { it.key.startsWith("actor:") })
    }

    @Test
    fun `every prompt carries the style and the prohibitions`() {
        val dark = StyleLexicon.interpret("dark").direction
        ForgePlanner.plan(dark, pack).forEach { order ->
            assertTrue("grimdark" in order.prompt, "${order.key} lost the style")
            assertTrue("no text" in order.prompt, "${order.key} lost the prohibitions")
        }
        val sprite = ForgePlanner.plan(dark, pack).first { it.kind == AssetKind.PROP_SPRITE }
        assertTrue("#FF00FF" in sprite.prompt, "sprites must be drawn on the key colour")
        val tile = ForgePlanner.plan(dark, pack).first { it.key == "t:turf/top" }
        assertTrue("#2E7D32" in tile.prompt, "a tile is anchored to its block's own colour")
    }

    @Test
    fun `keying removes the background and keeps enclosed colour that is not the key`() {
        val w = 40; val h = 40
        val px = IntArray(w * h) { 0xFFFF00FF.toInt() }
        // A brown square with a purple (not magenta) centre.
        for (y in 10 until 30) for (x in 10 until 30) px[y * w + x] = 0xFF7A4A1E.toInt()
        for (y in 18 until 22) for (x in 18 until 22) px[y * w + x] = 0xFF6A3AA0.toInt()
        val keyed = Pixels.keyOut(Texture(w, h, px))
        assertEquals(0, keyed.argb[0] ushr 24, "background is transparent")
        assertEquals(0xFF, keyed.argb[20 * w + 20] ushr 24, "the object's own purple survives")
        val trimmed = assertNotNull(Pixels.trim(keyed))
        assertTrue(trimmed.width in 20..24 && trimmed.height in 20..24)
    }

    @Test
    fun `seamless tiles wrap without a jump at the edges`() {
        val n = 64
        // A hard gradient: its left and right edges could not be further apart.
        val px = IntArray(n * n) { i -> val v = (i % n) * 255 / n; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val tile = Pixels.seamless(Texture(n, n, px))
        for (y in 0 until n) {
            val left = tile.argb[y * n] and 0xFF
            val right = tile.argb[y * n + n - 1] and 0xFF
            assertTrue(kotlin.math.abs(left - right) < 16, "row $y jumps from $right to $left across the wrap")
        }
    }

    @Test
    fun `a flat green screen is caught, a painting is not`() {
        val screen = Texture(32, 32, IntArray(32 * 32) { 0xFF00C800.toInt() })
        assertNotNull(Pixels.defect(screen))
        val painted = Texture(32, 32, IntArray(32 * 32) { i -> (0xFF shl 24) or ((80 + i % 90) shl 16) or ((40 + i % 50) shl 8) or (30 + i % 40) })
        assertNull(Pixels.defect(painted))
    }

    @Test
    fun `a bad image is retried and one failure never costs the kit`() = runTest {
        var calls = 0
        val port = object : ImageModelPort {
            override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> {
                calls++
                return Result.success(GeneratedImage(byteArrayOf(calls.toByte()), "image/png", 0, 0))
            }
        }
        // The first two images are green screens; the third is fine.
        val codec = object : ImageCodec {
            override fun decode(bytes: ByteArray): Texture? = if (bytes[0] < 3) {
                Texture(64, 64, IntArray(64 * 64) { 0xFF00C800.toInt() })
            } else {
                Texture(64, 64, IntArray(64 * 64) { i -> (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 3) and 0xFF) })
            }
            override fun encodePng(texture: Texture) = ByteArray(0)
        }
        val order = ForgeOrder("t:turf/top", AssetKind.GROUND_TILE, AssetTier.SYSTEMIC, "turf", "p")
        val result = AssetForge(port, codec).forge(listOf(order))
        assertTrue(result.single().succeeded)
        assertEquals(3, calls)
        val library = TextureLibrary()
        assertEquals(1, AssetForge.install(result, library))
        assertTrue(library.layerOf("t:turf/top") >= 0)
    }

    @Test
    fun `a large map wraps without a copy of itself inside it`() {
        // A gradient with one bright spot: after wrapping, the spot must appear
        // once, and the left and right edges must meet.
        val n = 64
        val src = Texture(n, n, IntArray(n * n) { i ->
            val x = i % n; val y = i / n
            val v = if (x in 30..33 && y in 30..33) 255 else (x * 2 + y)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
        val wrapped = Pixels.seamlessWide(src)
        assertEquals(n - n / 8, wrapped.width)
        val bright = wrapped.argb.count { (it and 0xFF) == 255 }
        assertEquals(16, bright, "the spot survives exactly once")
        for (y in 0 until wrapped.height) {
            val left = wrapped.argb[y * wrapped.width] and 0xFF
            val right = wrapped.argb[y * wrapped.width + wrapped.width - 1] and 0xFF
            assertTrue(kotlin.math.abs(left - right) <= 6, "row $y: edges $left and $right do not meet")
        }
    }

    @Test
    fun `regions get a large ground map for their ground and their paths`() {
        val withPath = pack.copy(biomes = pack.biomes.map { it.copy(composition = com.stratum.core.domain.content.BiomeComposition(pathBlockId = soil.id)) })
        val maps = ForgePlanner.plan(ArtDirection.HOUSE, withPath).filter { it.kind == AssetKind.GROUND_MAP }
        assertEquals(setOf("t:turf/map", "t:soil/map"), maps.map { it.key }.toSet())
        assertTrue(maps.none { "tileable" in it.prompt.lowercase() }, "asked as one painting, not as a pattern")
    }
}
