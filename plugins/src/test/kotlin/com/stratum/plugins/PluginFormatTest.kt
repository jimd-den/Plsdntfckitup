package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.crafting.StandardCrafting
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveTreeGenerator
import com.stratum.core.domain.plugin.PluginDependency
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.Version
import com.stratum.core.domain.plugin.VersionRange
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.tabletop.Attribute
import com.stratum.importer.common.ZipImportSource
import com.stratum.plugins.schema.PackJson
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginFormatTest {

    @Test
    fun `the whole built-in pack survives a trip through plugin JSON unchanged`() {
        val original = IgboContentPack.pack
        val decoded = PackJson.decode(PackJson.encode(original))

        val expected = original.copy(
            origin = PackOrigin.IMPORTED,
            spriteSheets = original.spriteSheets.map { it.copy(origin = SpriteOrigin.IMPORTED) },
        )
        assertEquals(expected, decoded)
    }

    @Test
    fun `maps and checks survive too, and encoding is stable`() {
        val map = TileMap(
            id = "demo:room", name = "Room", width = 2, height = 2, groundBlockId = "demo:dirt", biomeId = "demo:vale",
            layers = listOf(TileLayer.of("walls", 2, 2, listOf("demo:wall", null, null, "demo:wall"), elevation = 1, thickness = 2)),
            markers = listOf(MapMarker(MarkerKind.ENEMY_SPAWN, 1.5f, 0.5f, "rat", refId = "demo:rat")),
        )
        val pack = IgboContentPack.pack.copy(maps = listOf(map))
        val once = PackJson.encode(pack)
        val decoded = PackJson.decode(once)

        assertEquals(once, PackJson.encode(decoded))
        val layer = decoded.maps.single().layers.single()
        assertEquals("demo:wall", layer.blockIdAt(1, 1))
        assertEquals(null, layer.blockIdAt(1, 0))
        assertEquals(2, layer.thickness)
        assertEquals("demo:rat", decoded.maps.single().markers.single().refId)
    }

    @Test
    fun `a whole passive tree survives the trip, and a hand-drawn one reads plainly`() {
        val tree = PassiveTreeGenerator.generate(IgboContentPack.pack.heroClasses)
        val pack = IgboContentPack.pack.copy(passiveTrees = listOf(tree))

        assertEquals(tree, PackJson.decode(PackJson.encode(pack)).passiveTrees.single())

        val drawn = PackJson.decode(
            """
            {
              "id": "nri", "name": "Nri",
              "passiveTrees": [{
                "id": "nri:paths", "name": "Paths of Nri",
                "nodes": [
                  { "id": "nri:gate", "name": "Gate", "kind": "start" },
                  { "id": "nri:ofo", "name": "Ofo", "kind": "keystone",
                    "modifiers": [{ "stat": "damage", "kind": "more", "value": 0.3 }, { "stat": "resistance", "kind": "flat", "value": -0.2, "damageType": "nri:spirit" }] }
                ],
                "links": [["nri:gate", "nri:ofo"]]
              }]
            }
            """.trimIndent(),
        ).passiveTrees.single()

        assertEquals(PassiveKind.KEYSTONE, drawn.node("nri:ofo")!!.kind)
        assertEquals(listOf("30% more damage", "-20% resistance to spirit"), drawn.node("nri:ofo")!!.modifiers.map { it.describe() })
        assertEquals(setOf("nri:ofo"), drawn.neighboursOf("nri:gate"))
        assertFailsWith<ImportException> {
            PackJson.decode("""{ "id": "x", "name": "x", "passiveTrees": [{ "id": "t", "name": "t", "nodes": [{ "id": "a", "name": "a", "modifiers": [{ "stat": "luck", "value": 1 }] }] }] }""")
        }
    }

    @Test
    fun `the standard crafting set and waystone mods survive the trip`() {
        val pack = IgboContentPack.pack.copy(
            currencies = StandardCrafting.currencies,
            supports = StandardCrafting.supports,
            waystoneMods = WaystoneMods.standard,
        )

        val decoded = PackJson.decode(PackJson.encode(pack))

        assertEquals(StandardCrafting.currencies, decoded.currencies)
        assertEquals(StandardCrafting.supports, decoded.supports)
        assertEquals(WaystoneMods.standard, decoded.waystoneMods)
    }

    /** What a person writes by hand: only what differs from the defaults. */
    private val handWritten = """
        {
          "id": "nri",
          "name": "Chronicles of Nri",
          "damageTypes": [{ "id": "nri:spirit", "name": "Spirit", "color": "#9C7BD4" }],
          "enemies": [{ "id": "nri:mmuo", "name": "Masked Spirit", "damageType": "nri:spirit", "rank": "elite", "stats": { "maxHealth": 90 } }],
          "checks": [{
            "id": "nri:afa", "name": "Afa Divination", "dice": "1d20", "attribute": "insight", "difficulty": 13,
            "boon": { "name": "Vision of Chukwu", "durationSeconds": 60, "critChance": 0.3 }
          }]
        }
    """.trimIndent()

    @Test
    fun `a hand-written pack needs only what differs from the defaults, and plays`() {
        val pack = PackJson.decode(handWritten)

        assertEquals(0xFF9C7BD4, pack.damageTypes.single().color)
        assertEquals(90, pack.enemies.single().baseStats.maxHealth)
        assertEquals(Attribute.INSIGHT, pack.checks.single().attribute)
        assertEquals(0.3f, pack.checks.single().boon!!.critChance)
        ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
    }

    @Test
    fun `mistakes are reported by field, so the author can find them`() {
        val badRank = assertFailsWith<ImportException> { PackJson.decode(handWritten.replace("\"elite\"", "\"legendary\"")) }
        assertTrue("enemy 'nri:mmuo' rank" in badRank.message.orEmpty() && "minion" in badRank.message.orEmpty())

        val badColour = assertFailsWith<ImportException> { PackJson.decode(handWritten.replace("#9C7BD4", "purple")) }
        assertTrue("damage type 'nri:spirit' color" in badColour.message.orEmpty())

        assertFailsWith<ImportException> { PackJson.decode("{ \"name\": \"no id\" }") }
        assertFailsWith<ImportException> { PackJson.decode("not json") }
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    private val manifest = PluginManifest(
        id = "nri", name = "Chronicles of Nri", version = Version(1, 2, 0), author = "A. Author", license = "CC-BY-4.0",
        dependencies = listOf(PluginDependency("igbo", VersionRange.parse("^1.0")!!)),
    )

    private val sheet = com.stratum.core.domain.sprite.SpriteSheet(
        id = "nri:dibia", name = "Dibia", columns = 4, rows = 2, frameWidth = 32, frameHeight = 48,
        clips = listOf(com.stratum.core.domain.sprite.AnimationClip(com.stratum.core.domain.sprite.AnimationState.IDLE, 0, 4)),
    )

    @Test
    fun `a plugin file installs through the standard registry with its manifest and art`() {
        val pack = PackJson.decode(handWritten).copy(spriteSheets = listOf(sheet))
        val archive = PluginArchive.write(
            manifest, pack,
            textures = mapOf("nri:shrine/top" to png(64, 64)),
            sheets = mapOf(sheet.id to png(sheet.columns * sheet.frameWidth, sheet.rows * sheet.frameHeight)),
        )

        val source = ZipImportSource.read("nri.stratum", archive)
        val importer = Importers.standard().importerFor(source)
        val result = importer.import(source)

        assertEquals("stratum", importer.id)
        assertEquals(manifest, result.manifest)
        assertEquals(64, result.textures.single { it.key == "nri:shrine/top" }.region.width)
        val frames = result.spriteSheets.single().frames
        assertEquals(sheet.frameCount, frames.size)
        assertEquals(32 * 3, frames[3].x, "frame four is the fourth cell of the first row")
        assertEquals(48, frames[4].y, "frame five starts the second row")
    }

    @Test
    fun `a sheet without its image installs with a warning rather than failing`() {
        val pack = PackJson.decode(handWritten).copy(spriteSheets = listOf(sheet))
        val source = ZipImportSource.read("nri.stratum", PluginArchive.write(manifest, pack))

        val result = PluginImporter().import(source)
        assertTrue(result.spriteSheets.isEmpty())
        assertTrue(result.warnings.any { "no image" in it })
    }

    @Test
    fun `the manifest round-trips, and a pack must carry the plugin's id`() {
        assertEquals(manifest, ManifestJson.decode(ManifestJson.encode(manifest)))
        assertFailsWith<IllegalArgumentException> { PluginArchive.write(manifest.copy(id = "other"), PackJson.decode(handWritten)) }
        assertFailsWith<ImportException> { ManifestJson.decode("""{"id":"x","name":"X","version":"soon"}""") }
    }

    @Test
    fun `png sizes are read from the header, and anything else is not a png`() {
        assertEquals(12 to 34, PngSize.of(png(12, 34)))
        assertEquals(null, PngSize.of("hello".toByteArray()))
    }
}
