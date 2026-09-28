package com.stratum.engine.microvoxel

/**
 * Microvoxel detail behind a block world, as a renderer reads it.
 *
 * The game plays on blocks -- collision, pathing, combat, saves -- and a
 * generator that works in microvoxels also keeps the microvoxels it made, so
 * the renderer can draw the fine version wherever the block world still
 * matches what was generated. Where the player has dug or built, the block
 * world wins and the renderer draws the block.
 *
 * Coordinates: block (x, y, z) covers microvoxels [x*r, x*r+r) on each axis,
 * r = [microPerBlock]; micro chunk (cx, cy, cz) covers blocks
 * [cx*64/r, (cx+1)*64/r) horizontally.
 */
interface MicroTerrainSource {
    val palette: MaterialPalette
    val microPerBlock: Int get() = 4

    /** The generated microvoxels of a chunk. Read-only: callers copy before changing it. */
    fun microChunk(pos: MicroChunkPos): MicroChunk

    /** The block index the generator put at a block position, before anything changed it. */
    fun generatedBlock(x: Int, y: Int, z: Int): Int

    /** The micro material a block index is drawn as when it is not the generated one; AIR for air. */
    fun materialForBlock(blockIndex: Int): Short

    /**
     * Every generated block of the 16 x 16 block chunk at ([x], [y]), indexed
     * as the block world's chunks are (x fastest, then y, then z); null when
     * only [generatedBlock] is offered. A renderer comparing a whole chunk
     * reads it once instead of asking block by block.
     */
    fun generatedChunk(x: Int, y: Int): ShortArray? = null
}
