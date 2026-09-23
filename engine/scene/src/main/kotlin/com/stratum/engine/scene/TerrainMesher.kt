package com.stratum.engine.scene

import com.stratum.core.domain.art.SceneArtDirector
import com.stratum.core.domain.art.SurfaceFace
import com.stratum.core.domain.art.Tint
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockShapes
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.World

/** A block drawn as a sprite standing on the ground rather than as a cube. */
data class PropInstance(val x: Int, val y: Int, val z: Int, val block: BlockType, val biomeId: String?)

/**
 * A small painted thing lying flat on the ground — leaves, pebbles, flowers —
 * at a place, a turn and a size of its own.
 */
data class GroundDetail(val x: Float, val y: Float, val z: Float, val layer: Int, val angle: Float, val size: Float)

/** A light-emitting block, as a point light the backends can use. */
data class PointLight(val x: Float, val y: Float, val z: Float, val color: Long, val strength: Float, val radius: Float)

/**
 * Turns voxels into triangles.
 *
 * Only faces that can be seen are emitted — a face against an opaque cube is
 * skipped, which on ordinary terrain removes all but a few per cent of them —
 * and every corner gets ambient occlusion from the three cells around it. The
 * occlusion is what makes a voxel world read as solid in 3D: without it every
 * inside corner, every ledge foot and every doorway is lit exactly like open
 * ground, and the whole thing looks like it is made of paper.
 *
 * Shaped blocks — thin walls — are emitted from the same boxes collision uses,
 * so what the player sees is exactly what stops them.
 */
class TerrainMesher(
    private val director: SceneArtDirector,
    private val textures: TextureLibrary,
    private val biomeAt: (Int, Int) -> BiomeDefinition? = { _, _ -> null },
) {

    /** Result of meshing a region: the geometry, plus what stands and glows in it. */
    class Result(
        val mesh: MeshBatch,
        val props: List<PropInstance>,
        val lights: List<PointLight>,
        val details: List<GroundDetail> = emptyList(),
    )

    fun mesh(world: World, minX: Int, maxX: Int, minY: Int, maxY: Int): Result {
        val out = MeshBuilder(MaterialKind.OPAQUE)
        val props = ArrayList<PropInstance>()
        val lights = ArrayList<PointLight>()
        val details = ArrayList<GroundDetail>()

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val surface = world.surfaceAt(x, y)
                if (surface < 0) continue
                val biome = biomeAt(x, y)
                val biomeId = biome?.id
                if (biome != null) scatterDetail(world, x, y, surface, biome, details)
                val floor = maxOf(0, surface - DEPTH)
                for (z in floor..surface) {
                    val block = world.blockAt(BlockPos(x, y, z))
                    if (block.isAir) continue
                    if (block.lightEmission > 0) {
                        lights += PointLight(
                            x + 0.5f, y + 0.5f, z + 1.2f,
                            if (Tint.alpha(block.accentColor) > 0) block.accentColor else block.topColor,
                            block.lightEmission / 15f,
                            LIGHT_RADIUS * block.lightEmission / 15f + 2f,
                        )
                    }
                    if (block.glyph != null) {
                        props += PropInstance(x, y, z, block, biomeId)
                        continue
                    }
                    when (block.shape) {
                        BlockShape.CUBE -> cube(world, out, x, y, z, block, biomeId)
                        BlockShape.WALL -> wall(world, out, x, y, z, block, biomeId)
                        BlockShape.FLOOR -> floor(world, out, x, y, z, block, biomeId)
                    }
                }
            }
        }
        return Result(out.build(), props, lights, details)
    }

    /**
     * Litter on open ground: a leaf fall, a few stones, a tuft of flowers.
     *
     * Only on the region's own surface block with open sky above it, so paths,
     * paving and walls stay clean, and only where the forge has painted some.
     * Placement is a hash of the cell, so the same ground always has the same
     * litter and nothing flickers when the terrain is remeshed.
     */
    private fun scatterDetail(world: World, x: Int, y: Int, surface: Int, biome: BiomeDefinition, out: MutableList<GroundDetail>) {
        val paintings = textures.variantsOf("detail:${biome.id}")
        if (paintings.isEmpty()) return
        val h = hash(x, y)
        if (h % 100 >= DETAIL_PERCENT) return
        val ground = world.blockAt(BlockPos(x, y, surface))
        if (ground.id != biome.surfaceBlockId || ground.shape != BlockShape.CUBE || ground.glyph != null) return
        val jitterX = ((h ushr 8) and 0xFF) / 255f
        val jitterY = ((h ushr 16) and 0xFF) / 255f
        out += GroundDetail(
            x + 0.2f + jitterX * 0.6f,
            y + 0.2f + jitterY * 0.6f,
            surface + 1f,
            paintings[(h / 100) % paintings.size],
            angle = ((h ushr 4) and 0x3FF) / 1023f * TAU,
            size = DETAIL_MIN_SIZE + ((h ushr 20) and 0xFF) / 255f * (DETAIL_MAX_SIZE - DETAIL_MIN_SIZE),
        )
    }

    private fun hash(x: Int, y: Int): Int {
        var h = x * 668265263 + y * 374761393
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    private fun cube(world: World, out: MeshBuilder, x: Int, y: Int, z: Int, block: BlockType, biomeId: String?) {
        val top = director.surfaceFor(block, SurfaceFace.TOP, biomeId)
        val side = director.surfaceFor(block, SurfaceFace.SIDE, biomeId)
        val topLayer = textures.layerOf(top.texture).toFloat()
        val sideLayer = textures.layerOf(side.texture).toFloat()
        val emissive = top.emissive
        // Ground has sister paintings, keyed "<top>#1" and "<top>#2".
        val variantA = textures.layerOf(top.texture?.let { "$it#1" }).toFloat()
        val variantB = textures.layerOf(top.texture?.let { "$it#2" }).toFloat()
        FACES.forEach { face ->
            val neighbour = world.blockAt(BlockPos(x + face.dx, y + face.dy, z + face.dz))
            // This camera never sees an underside, and neither does the sun.
            if (face.dz == -1) return@forEach
            if (neighbour.isOpaque && neighbour.shape == BlockShape.CUBE) return@forEach
            val surface = if (face.dz == 1) top else side
            val layer = if (face.dz == 1) topLayer else sideLayer
            val base = if (layer >= 0f) textured(surface.albedo) else surface.albedo
            val albedo = if (emissive > 0f) Tint.mix(base, surface.emissiveColor, emissive * 0.5f) else base
            emitFace(
                out, face,
                x.toFloat(), y.toFloat(), z.toFloat(), x + 1f, y + 1f, z + 1f,
                albedo, layer, emissive,
                if (face.dz == 1) variantA else -1f,
                if (face.dz == 1) variantB else -1f,
            ) { cornerX, cornerY, cornerZ -> occlusion(world, x, y, z, face, cornerX, cornerY, cornerZ) }
        }
    }

    private fun wall(world: World, out: MeshBuilder, x: Int, y: Int, z: Int, block: BlockType, biomeId: String?) {
        val top = director.surfaceFor(block, SurfaceFace.TOP, biomeId)
        val side = director.surfaceFor(block, SurfaceFace.SIDE, biomeId)
        val topLayer = textures.layerOf(top.texture).toFloat()
        val sideLayer = textures.layerOf(side.texture).toFloat()
        val onWallBelow = world.blockAt(BlockPos(x, y, z - 1)).shape == BlockShape.WALL
        val onWallAbove = world.blockAt(BlockPos(x, y, z + 1)).shape == BlockShape.WALL
        BlockShapes.boxes(block.shape, BlockShapes.connections(world, BlockPos(x, y, z))).forEach { box ->
            FACES.forEach { face ->
                if (face.dz == 1 && onWallAbove) return@forEach
                if (face.dz == -1) return@forEach
                emitFace(
                    out, face,
                    x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ,
                    (if (face.dz == 1) top else side).let { if ((if (face.dz == 1) topLayer else sideLayer) >= 0f) textured(it.albedo) else it.albedo },
                    if (face.dz == 1) topLayer else sideLayer,
                    0f,
                ) { _, _, cornerZ ->
                    // A wall's foot is darker than its head, so it sits on the
                    // ground instead of hovering over it.
                    if (cornerZ == 0 && !onWallBelow) WALL_FOOT_AO else 1f
                }
            }
        }
    }

    /**
     * A paving tile: a thin slab on the ground. Edges against another tile are
     * dropped, so a paved courtyard is one continuous surface with a lip only
     * where it meets the grass.
     */
    private fun floor(world: World, out: MeshBuilder, x: Int, y: Int, z: Int, block: BlockType, biomeId: String?) {
        val top = director.surfaceFor(block, SurfaceFace.TOP, biomeId)
        val side = director.surfaceFor(block, SurfaceFace.SIDE, biomeId)
        val topLayer = textures.layerOf(top.texture).toFloat()
        val sideLayer = textures.layerOf(side.texture).toFloat()
        val box = BlockShapes.boxes(BlockShape.FLOOR, 0).first()
        FACES.forEach { face ->
            if (face.dz == -1) return@forEach
            if (face.dz == 0) {
                val neighbour = world.blockAt(BlockPos(x + face.dx, y + face.dy, z))
                if (neighbour.shape == BlockShape.FLOOR || (neighbour.isOpaque && neighbour.shape == BlockShape.CUBE)) return@forEach
            }
            val layer = if (face.dz == 1) topLayer else sideLayer
            val albedo = (if (face.dz == 1) top else side).albedo
            emitFace(
                out, face,
                x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ,
                if (layer >= 0f) textured(albedo) else albedo,
                layer,
                0f,
            ) { cornerX, cornerY, cornerZ ->
                // The top takes the same corner occlusion as the ground it lies
                // on, so a paved alley is as dark in its corners as a bare one.
                if (face.dz == 1) occlusion(world, x, y, z - 1, face, cornerX, cornerY, cornerZ)
                else if (cornerZ == 0) WALL_FOOT_AO else 1f
            }
        }
    }

    /**
     * One quad. Corners come from [Face.corners] as 0/1 choices of the box's
     * min and max on each axis; [ao] is asked for each corner by those choices.
     */
    private inline fun emitFace(
        out: MeshBuilder,
        face: Face,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        albedo: Long,
        layer: Float,
        emissive: Float,
        variantA: Float = -1f,
        variantB: Float = -1f,
        ao: (Int, Int, Int) -> Float,
    ) {
        val c = face.corners
        val idx = IntArray(4)
        val occ = FloatArray(4)
        for (i in 0 until 4) {
            val cx = c[i * 3]; val cy = c[i * 3 + 1]; val cz = c[i * 3 + 2]
            val px = if (cx == 0) x0 else x1
            val py = if (cy == 0) y0 else y1
            val pz = if (cz == 0) z0 else z1
            // World-space texture coordinates, so a texture runs continuously
            // across neighbouring blocks instead of repeating per cube: a
            // painted ground reads as ground, a tiled one reads as a grid.
            val u: Float
            val v: Float
            when {
                face.dz != 0 -> { u = px * TEXTURE_SCALE; v = py * TEXTURE_SCALE }
                face.dx != 0 -> { u = py * TEXTURE_SCALE; v = -pz * TEXTURE_SCALE }
                else -> { u = px * TEXTURE_SCALE; v = -pz * TEXTURE_SCALE }
            }
            occ[i] = ao(cx, cy, cz)
            idx[i] = out.vertex(
                px, py, pz,
                face.dx.toFloat(), face.dy.toFloat(), face.dz.toFloat(),
                albedo, occ[i], u, v, layer, emissive, variantA, variantB,
            )
        }
        // Split along the brighter diagonal. The other split draws a visible
        // crease across any face whose corners are unevenly occluded.
        if (occ[0] + occ[2] >= occ[1] + occ[3]) out.quad(idx[0], idx[1], idx[2], idx[3])
        else out.quad(idx[1], idx[2], idx[3], idx[0])
    }

    /**
     * The tint a textured face is drawn with.
     *
     * A painted texture already carries its colour; multiplying it by the
     * block's own colour as well darkens it twice and drowns the painting. It
     * keeps a quarter of the block colour, so a style's grading and a pack's
     * palette still pull the painted surface their way.
     */
    private fun textured(albedo: Long): Long = Tint.mix(0xFFFFFFFF, albedo, TEXTURE_TINT)

    /**
     * Classic voxel corner occlusion: look at the two edge neighbours and the
     * diagonal neighbour in the layer the face looks into.
     */
    private fun occlusion(world: World, x: Int, y: Int, z: Int, face: Face, cx: Int, cy: Int, cz: Int): Float {
        val ox = x + face.dx
        val oy = y + face.dy
        val oz = z + face.dz
        // The two tangent axes are the ones the face does not point along; a
        // corner at the max end of an axis looks +1 along it, at the min end -1.
        val sx = if (face.dx != 0) 0 else if (cx == 1) 1 else -1
        val sy = if (face.dy != 0) 0 else if (cy == 1) 1 else -1
        val sz = if (face.dz != 0) 0 else if (cz == 1) 1 else -1
        val axes = ArrayList<IntArray>(2)
        if (sx != 0) axes += intArrayOf(sx, 0, 0)
        if (sy != 0) axes += intArrayOf(0, sy, 0)
        if (sz != 0) axes += intArrayOf(0, 0, sz)
        val a = axes[0]; val b = axes[1]
        val side1 = solid(world, ox + a[0], oy + a[1], oz + a[2])
        val side2 = solid(world, ox + b[0], oy + b[1], oz + b[2])
        val corner = solid(world, ox + a[0] + b[0], oy + a[1] + b[1], oz + a[2] + b[2])
        val level = if (side1 && side2) 3 else (if (side1) 1 else 0) + (if (side2) 1 else 0) + (if (corner) 1 else 0)
        return AO_LEVELS[level]
    }

    private fun solid(world: World, x: Int, y: Int, z: Int): Boolean {
        val block = world.blockAt(BlockPos(x, y, z))
        return block.isOpaque && block.shape == BlockShape.CUBE && block.glyph == null
    }

    /** One face direction, and its corners as 0/1 picks of min or max per axis. */
    class Face(val dx: Int, val dy: Int, val dz: Int, val corners: IntArray)

    companion object {
        /** Levels under the surface still meshed, so cliffs and pits have walls. */
        const val DEPTH = 10

        /** Blocks per texture repeat. Two, so one painted tile covers a small patch. */
        const val TEXTURE_SCALE = 0.5f

        const val LIGHT_RADIUS = 7f

        /** Share of open ground cells, in per cent, that get a piece of litter. */
        const val DETAIL_PERCENT = 6
        const val DETAIL_MIN_SIZE = 0.7f
        const val DETAIL_MAX_SIZE = 1.25f
        const val TAU = 6.2831855f
        const val WALL_FOOT_AO = 0.72f
        const val TEXTURE_TINT = 0.25f

        /** Brightness at 0, 1, 2 and 3 occluding neighbours. */
        val AO_LEVELS = floatArrayOf(1f, 0.8f, 0.64f, 0.5f)

        val FACES = listOf(
            Face(0, 0, 1, intArrayOf(0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1)),
            Face(-1, 0, 0, intArrayOf(0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1)),
            Face(1, 0, 0, intArrayOf(1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1)),
            Face(0, -1, 0, intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1)),
            Face(0, 1, 0, intArrayOf(0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1)),
            Face(0, 0, -1, intArrayOf(0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0)),
        )
    }
}
