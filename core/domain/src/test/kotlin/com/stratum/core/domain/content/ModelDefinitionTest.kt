package com.stratum.core.domain.content

import com.stratum.core.domain.world.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDefinitionTest {

    private val stone = BlockType(id = "base:stone", displayName = "Stone")
    private val shrine = BlockType(id = "base:shrine", displayName = "Shrine", glyph = "S", isOpaque = false)
    private val plains = BiomeDefinition("base:plains", "Plains", surfaceBlockId = "base:stone", subsurfaceBlockId = "base:stone", bedrockFillerBlockId = "base:stone")

    private fun pack(vararg models: ModelDefinition) =
        ContentPack(id = "base", name = "base", author = "test", blocks = listOf(stone, shrine), biomes = listOf(plains), models = models.toList())

    @Test
    fun `models layer by id like everything else and name their asset`() {
        val first = ModelDefinition("m:idol", "asset:idol_v1", blockId = "base:shrine")
        val second = first.copy(source = "asset:idol_v2")
        val content = ContentPackAssembler().assemble(listOf(pack(first), pack(second).copy(id = "later")))

        assertEquals("idol_v2", content.models.single().assetId)
        assertNull(ModelDefinition("m:x", "models/x.glb").assetId)
    }

    @Test
    fun `a model dressing something nobody defined fails at load, by name`() {
        val failure = assertFailsWith<ContentPackException> {
            ContentPackAssembler().assemble(listOf(pack(ModelDefinition("m:ghost", "asset:g", blockId = "base:nope", enemyId = "base:nobody"))))
        }
        assertTrue("model 'm:ghost' dresses unknown block 'base:nope'" in failure.message.orEmpty())
        assertTrue("unknown enemy 'base:nobody'" in failure.message.orEmpty())
    }

    @Test
    fun `a model needs a source and a sensible height`() {
        assertFailsWith<IllegalArgumentException> { ModelDefinition("m:a", " ") }
        assertFailsWith<IllegalArgumentException> { ModelDefinition("m:a", "asset:a", height = 0f) }
        assertFailsWith<IllegalArgumentException> { ModelDefinition("m:a", "asset:a", height = 500f) }
    }
}
