package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import java.io.File
import javax.imageio.ImageIO

/**
 * The 3D world, drawn at every style, from one camera.
 *
 * Also a small demonstration of building: a laterite compound with thin walls,
 * a doorway and braziers is placed into the generated grove before anything is
 * drawn, because a style sheet that never shows the thing players make is only
 * half a style sheet.
 */
object ScenePreview {

    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val SEED = 20260922L
    private const val VANTAGE_X = 56
    private const val VANTAGE_Y = 104

    /**
     * Scene name, style prompt, and the forged kit it draws with.
     *
     * Styles without a kit of their own borrow the house kit, which is itself a
     * demonstration: the same painted assets under different light, fog and
     * grading already read as different places.
     */
    val SCENES: List<Triple<String, String, String>> = listOf(
        Triple("01-house", "stratum house style", "house"),
        Triple("02-dark-diablo", "dark grimdark diablo", "dark"),
        Triple("03-hades-inked", "inked chiaroscuro sacred", "hades"),
        Triple("04-kawaii", "kawaii pastel cute", "kawaii"),
        Triple("05-toxic", "toxic corrupted moody", "house"),
        Triple("06-neon", "neon synthwave", "house"),
        Triple("07-frozen", "frozen arctic winter", "house"),
        Triple("08-untextured", "stratum house style", ""),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "build/scene-preview").also { it.mkdirs() }
        val forged = args.getOrNull(1)?.let(::File)
        val only = args.getOrNull(2)
        SCENES.filter { only == null || it.first.contains(only) }.forEach { (name, prompt, kit) ->
            val started = System.currentTimeMillis()
            val file = File(target, "$name.png")
            val kitDir = forged?.let { root ->
                if (kit.isEmpty()) null else File(root, kit).takeIf { it.isDirectory } ?: File(root, "house")
            }
            ImageIO.write(render(prompt, kitDir), "png", file)
            println("wrote ${file.absolutePath} in ${System.currentTimeMillis() - started}ms")
        }
    }

    fun render(prompt: String, forged: File?): java.awt.image.BufferedImage {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        val config = WorldConfig(seed = SEED, simulationRadius = 3)
        val generator = StratumTerrain.create(TerrainContext(config, content.biomes, content.terrain))
        val world = StreamingWorld(content.registry, generator, config)
        world.focusOn(BlockPos(VANTAGE_X, VANTAGE_Y, 0))
        val biomes = generator as? BiomeSource

        val ground = world.surfaceAt(VANTAGE_X, VANTAGE_Y).let { z ->
            // The vantage column may hold a prop; stand on the ground under it.
            var g = z
            while (g > 0 && world.blockAt(BlockPos(VANTAGE_X, VANTAGE_Y, g)).glyph != null) g--
            g
        }
        buildCompound(world, content.registry, ground)

        val direction = StyleLexicon.interpret(prompt, ArtDirection.HOUSE, SEED).direction
        val director = StyleSheetArtDirector(direction, BiomeArtKit.deriveAll(IgboContentPack.pack))
        val textures = TextureLibrary()
        forged?.let { ForgedTextures.loadInto(it, textures) }

        val builder = SceneBuilder(director, textures, biomeAt = { x, y -> biomes?.biomeAt(x, y) })
        val camera = SceneCamera(
            target = Vec3(VANTAGE_X + 0.5f, VANTAGE_Y + 0.5f, ground + 1f),
            aspect = WIDTH.toFloat() / HEIGHT,
        )
        val stand = ground + 1f
        val actors = listOf(
            SceneActor(VANTAGE_X + 0.5f, VANTAGE_Y + 0.5f, stand, ActorPresentation("p", ActorRole.PLAYER), 1f, -0.3f),
            SceneActor(VANTAGE_X + 4.5f, VANTAGE_Y - 2.5f, surfaceAt(world, VANTAGE_X + 4, VANTAGE_Y - 3), ActorPresentation("m1", ActorRole.ENEMY, EnemyRank.MINION), -1f, 0.5f),
            SceneActor(VANTAGE_X + 5.5f, VANTAGE_Y - 0.5f, surfaceAt(world, VANTAGE_X + 5, VANTAGE_Y - 1), ActorPresentation("m2", ActorRole.ENEMY, EnemyRank.MINION), -1f, 0f),
            SceneActor(VANTAGE_X + 6.5f, VANTAGE_Y + 2.5f, surfaceAt(world, VANTAGE_X + 6, VANTAGE_Y + 2), ActorPresentation("e", ActorRole.ENEMY, EnemyRank.ELITE), -1f, -0.4f),
            SceneActor(VANTAGE_X - 1.5f, VANTAGE_Y + 3.5f, surfaceAt(world, VANTAGE_X - 2, VANTAGE_Y + 3), ActorPresentation("l", ActorRole.LOOT)),
        )
        val frame = builder.build(world, camera, actors, WorldTime(dayFraction = 0.42f, elapsedSeconds = 7f))
        return SceneRasterizer(WIDTH, HEIGHT, textures).render(frame)
    }

    /** Standing height at a column: the ground, not whatever grows on it. */
    private fun surfaceAt(world: StreamingWorld, x: Int, y: Int): Float {
        var z = world.surfaceAt(x, y)
        while (z > 0 && world.blockAt(BlockPos(x, y, z)).glyph != null) z--
        return z + 1f
    }

    /**
     * A small walled compound north-west of the player: laterite walls a
     * third of a block thick, two high, with a gap for a door and a brazier
     * either side of it.
     */
    private fun buildCompound(world: StreamingWorld, registry: BlockRegistry, ground: Int) {
        val wall = registry.indexOf("igbo:mud_wall")
        val floor = registry.indexOf("igbo:red_earth")
        val brazier = registry.indexOf("igbo:bronze_brazier")
        val minX = VANTAGE_X - 7; val maxX = VANTAGE_X - 2
        val minY = VANTAGE_Y - 7; val maxY = VANTAGE_Y - 2
        for (y in minY..maxY) for (x in minX..maxX) {
            world.setBlock(BlockPos(x, y, ground), floor)
            for (z in ground + 1..ground + 4) world.setBlock(BlockPos(x, y, z), BlockRegistry.AIR_INDEX)
            val edge = x == minX || x == maxX || y == minY || y == maxY
            val door = y == maxY && (x == minX + 2 || x == minX + 3)
            if (edge && !door) {
                world.setBlock(BlockPos(x, y, ground + 1), wall)
                world.setBlock(BlockPos(x, y, ground + 2), wall)
            }
        }
        world.setBlock(BlockPos(minX + 1, maxY + 1, ground + 1), brazier)
        world.setBlock(BlockPos(minX + 4, maxY + 1, ground + 1), brazier)
    }
}
