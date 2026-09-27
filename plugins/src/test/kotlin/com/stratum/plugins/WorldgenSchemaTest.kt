package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.ContentPackException
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.world.LiquidTarget
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructureAnchor
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.plugins.schema.PackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The world generator's half of pack.json: passes, climate, carvers, ores, trees, liquids and structures. */
class WorldgenSchemaTest {

    private val pack = """
        {
          "id": "deep:pack", "name": "Deep",
          "terrain": {
            "generator": "stratum:caverns",
            "passes": [
              { "id": "stratum:climate", "options": { "blend": "0.2" } },
              { "id": "stratum:hills", "options": { "base": "36" } },
              { "id": "stratum:surface" },
              { "id": "stratum:carvers" },
              { "id": "stratum:liquids" }
            ],
            "climate": { "points": [ { "biome": "igbo:mist_marsh", "temperature": 0.3, "moisture": 0.9 } ], "scale": 0.004 },
            "carvers": [ { "kind": "caverns", "minZ": 2, "maxZ": 16, "amount": 0.5, "size": 6 } ],
            "ores": [ { "block": "igbo:iron_ore", "minZ": 2, "maxZ": 10, "veinsPerChunk": 2, "biomes": ["igbo:mist_marsh"] } ],
            "trees": [ { "trunk": "igbo:iroko_trunk", "leaves": "igbo:iroko_canopy", "chance": 0.02 } ],
            "liquids": [ { "block": "igbo:spirit_water", "maxZ": 6, "target": "caves", "share": 0.5 } ]
          },
          "structureTemplates": [
            {
              "id": "deep:vault", "name": "Vault",
              "placement": { "spacing": 96, "chance": 0.4, "anchor": "underground", "minZ": 4, "maxZ": 10 },
              "dungeon": { "floor": "igbo:granite", "wall": "igbo:catacomb_masonry", "boss": "igbo:agbara_priest" }
            },
            {
              "id": "deep:cairn", "name": "Cairn",
              "pieces": [ {
                "id": "deep:cairn_base", "palette": { "#": "igbo:granite", "L": "@loot" },
                "layers": [ ["###", "#L#", "###"] ],
                "connectors": [ { "side": "east", "offset": 1 } ]
              } ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a pack can describe its whole generator, and it assembles`() {
        val decoded = PackJson.decode(pack)
        val terrain = decoded.terrain!!
        assertEquals(TerrainRecipe.CAVERNS, terrain.generatorId)
        assertEquals(listOf("stratum:climate", "stratum:hills", "stratum:surface", "stratum:carvers", "stratum:liquids"), terrain.passes.map { it.id })
        assertEquals("36", terrain.passes[1].options["base"])
        assertEquals(LiquidTarget.CAVES, terrain.liquids.single().target)
        assertEquals(StructureAnchor.UNDERGROUND, decoded.structureTemplates.first().placement.anchor)
        assertEquals(PieceSide.EAST, decoded.structureTemplates.last().pieces.single().connectors.single().side)
        ContentPackAssembler().assemble(listOf(IgboContentPack.pack, decoded))
        assertEquals(PackJson.encode(decoded), PackJson.encode(PackJson.decode(PackJson.encode(decoded))))
    }

    @Test
    fun `dangling references in the generator are refused at load, by name`() {
        val bad = PackJson.decode(
            pack.replace("\"igbo:iron_ore\"", "\"deep:mithril\"").replace("\"boss\": \"igbo:agbara_priest\"", "\"boss\": \"deep:nobody\""),
        )
        val failure = assertFailsWith<ContentPackException> { ContentPackAssembler().assemble(listOf(IgboContentPack.pack, bad)) }
        assertTrue("unknown block 'deep:mithril'" in failure.message.orEmpty(), failure.message)
        assertTrue("structure 'deep:vault' spawns unknown enemy 'deep:nobody'" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a malformed piece is reported by the structure it belongs to`() {
        val bad = PackJson.decode(pack.replace("\"#L#\"", "\"#X#\""))
        val failure = assertFailsWith<ContentPackException> { ContentPackAssembler().assemble(listOf(IgboContentPack.pack, bad)) }
        assertTrue("piece 'deep:cairn_base' uses 'X'" in failure.message.orEmpty(), failure.message)
        assertFailsWith<ImportException> { PackJson.decode(pack.replace("\"#\": \"igbo:granite\"", "\"##\": \"igbo:granite\"")) }
    }
}
