package com.stratum.core.data.model

import com.stratum.core.domain.ai.ModelAsset
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelSubjectKind
import com.stratum.core.domain.content.VoxelBlueprint
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelAssetStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { ModelAssetStore(folder.root) }

    private val asset = ModelAsset("statue_idol", "Idol", prompt = "a bronze idol", kind = ModelSubjectKind.STATUE, format = ModelFormat.GLB, heightBlocks = 2f)

    @Test
    fun `a model survives a save with its bytes, preview and bindings`() {
        store.save(asset, byteArrayOf(1, 2, 3), previewPng = byteArrayOf(9))
        store.update(asset.copy(propBlockId = "igbo:shrine"))

        val loaded = store.find("statue_idol")!!
        assertEquals("igbo:shrine", loaded.propBlockId)
        assertTrue(store.bytesFor("statue_idol")!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(store.previewFor("statue_idol")!!.contentEquals(byteArrayOf(9)))
        assertEquals(listOf(loaded), store.all())

        val definition = loaded.toDefinition()!!
        assertEquals("asset:statue_idol", definition.source)
        assertEquals("igbo:shrine", definition.blockId)
        assertNull(asset.toDefinition(), "an unbound model dresses nothing")
    }

    @Test
    fun `deleting a model removes its folder and its baked sprite`() {
        store.save(asset.copy(enemyId = "igbo:mmuo"), byteArrayOf(1))
        store.saveTexture("actor:igbo:mmuo", byteArrayOf(5))
        assertEquals(1, store.textureDirectory.listFiles()!!.size)

        store.delete("statue_idol")

        assertNull(store.find("statue_idol"))
        assertEquals(0, store.textureDirectory.listFiles()!!.size)
    }

    @Test
    fun `blueprints round-trip`() {
        val blueprint = VoxelBlueprint("bp:idol", "Idol", 2, 1, 2, listOf("a", "b"), intArrayOf(1, 0, 2, 2), anchorX = 1, anchorY = 0)
        store.saveBlueprint(blueprint)
        assertEquals(listOf(blueprint), store.blueprints())
        store.deleteBlueprint("bp:idol")
        assertTrue(store.blueprints().isEmpty())
    }
}
