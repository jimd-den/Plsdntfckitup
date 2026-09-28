package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.ModelDefinition
import com.stratum.plugins.schema.PackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSchemaTest {

    @Test
    fun `a hand-written model reference reads plainly and survives the trip`() {
        val shrine = IgboContentPack.pack.blocks.first { it.glyph != null }.id
        val text = """
            { "id": "statues", "name": "Statues",
              "models": [{ "id": "statues:idol", "source": "asset:idol", "height": 2.5, "block": "$shrine" }] }
        """.trimIndent()
        val pack = PackJson.decode(text)

        assertEquals(ModelDefinition("statues:idol", "asset:idol", height = 2.5f, blockId = shrine), pack.models.single())
        assertEquals(pack.models, PackJson.decode(PackJson.encode(pack)).models)
        ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
    }

    @Test
    fun `packs without models still load, and encode no models field`() {
        val encoded = PackJson.encode(IgboContentPack.pack)
        assertTrue("\"models\"" !in encoded)
        assertTrue(PackJson.decode(encoded).models.isEmpty())
    }
}
