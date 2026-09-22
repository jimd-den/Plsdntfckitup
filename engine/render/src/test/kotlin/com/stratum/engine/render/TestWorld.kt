package com.stratum.engine.render

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World

/**
 * A world made of a height function.
 *
 * The renderer needs somewhere to walk, not a simulation: using the real
 * streaming world here would test the terrain generator's opinions about groves
 * as much as the renderer's about drawing them, and a failure would not say
 * which.
 */
class TestWorld(
    private val span: Int = 12,
    private val heightAt: (Int, Int) -> Int = { x, y -> 2 + ((x + y) / 4).coerceIn(0, 3) },
    private val propAt: (Int, Int) -> Boolean = { x, y -> (x + y * 3) % 7 == 0 },
) : World {

    val grass = BlockType("test:grass", "Grass", BlockMaterial.SOIL, topColor = 0xFF4E8B45, sideColor = 0xFF6B4A2A)
    val stone = BlockType("test:stone", "Stone", BlockMaterial.STONE, topColor = 0xFF6E6E78, sideColor = 0xFF4A4A52)
    val tree = BlockType(
        "test:tree", "Tree", BlockMaterial.FOLIAGE,
        glyph = "T", isOpaque = false, topColor = 0xFF2F6B37, sideColor = 0xFF23502A,
    )
    val lantern = BlockType(
        "test:lantern", "Lantern", BlockMaterial.RITUAL,
        glyph = "L", lightEmission = 12, isOpaque = false,
        topColor = 0xFFCD7F32, sideColor = 0xFF8A4F1E, accentColor = 0xFFF0C862,
    )

    override val registry: BlockRegistry = BlockRegistry.build(listOf(grass, stone, tree, lantern))

    override val loadedChunks: Collection<Chunk> = emptyList()

    override fun chunkAt(pos: ChunkPos): Chunk? = null

    override fun blockAt(pos: BlockPos): BlockType {
        if (pos.x !in -span..span || pos.y !in -span..span || pos.z < 0) return BlockType.AIR
        val surface = heightAt(pos.x, pos.y)
        return when {
            pos.z == surface + 1 && propAt(pos.x, pos.y) -> if ((pos.x + pos.y) % 21 == 0) lantern else tree
            pos.z == surface -> grass
            pos.z < surface -> stone
            else -> BlockType.AIR
        }
    }

    override fun blockIndexAt(pos: BlockPos): Int = registry.indexOf(blockAt(pos).id)

    override fun lightAt(pos: BlockPos): Int = 15

    override fun surfaceAt(x: Int, y: Int): Int {
        if (x !in -span..span || y !in -span..span) return -1
        val surface = heightAt(x, y)
        return if (propAt(x, y)) surface + 1 else surface
    }

    override fun isLoaded(pos: ChunkPos): Boolean = true
}

/** Counts what a frame asked for, which is all a renderer test needs to know. */
class CountingSink : FrameSink {
    var backdrops = 0
    var cubes = 0
    var polygons = 0
    var ellipses = 0
    var rings = 0
    var glows = 0
    var washes = 0
    var vignettes = 0

    /** In call order, so a test can assert that weather landed on top of terrain. */
    val order = mutableListOf<String>()
    val cubeTops = mutableListOf<Long>()
    val ellipseFills = mutableListOf<Long>()
    val ellipseCenters = mutableListOf<Pair<Float, Float>>()

    override fun backdrop(top: Long, bottom: Long) {
        backdrops++
        order += "backdrop"
    }

    override fun cube(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        lift: Float,
        style: com.stratum.core.domain.art.TerrainStyle,
        highlight: Long,
    ) {
        cubes++
        cubeTops += style.top
        if (order.lastOrNull() != "cube") order += "cube"
    }

    override fun polygon(points: FloatArray, count: Int, fill: Long, outline: Long, outlineWidth: Float) {
        polygons++
        if (order.lastOrNull() != "polygon") order += "polygon"
    }

    override fun ellipse(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float, fill: Long) {
        ellipses++
        ellipseFills += fill
        ellipseCenters += centerX to centerY
        if (order.lastOrNull() != "ellipse") order += "ellipse"
    }

    override fun ring(centerX: Float, centerY: Float, radius: Float, color: Long, width: Float) {
        rings++
        if (order.lastOrNull() != "ring") order += "ring"
    }

    override fun glow(centerX: Float, centerY: Float, radius: Float, color: Long) {
        glows++
        if (order.lastOrNull() != "glow") order += "glow"
    }

    override fun wash(color: Long) {
        washes++
        order += "wash"
    }

    override fun vignette(strength: Float, color: Long) {
        vignettes++
        order += "vignette"
    }
}
