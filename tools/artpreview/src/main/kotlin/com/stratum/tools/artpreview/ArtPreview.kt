package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.render.WorldFrameRenderer
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import com.stratum.core.domain.world.BiomeSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the same scene at every built-in style.
 *
 * This exists because art direction that cannot be looked at does not get
 * directed. A style is a few dozen numbers, and the only honest way to know
 * whether a change to them helped is to see the world drawn with it — beside
 * the world drawn without it, at the same seed, from the same camera, with the
 * same monsters standing in the same places.
 */
object ArtPreview {

    /** The scene every style is judged on. One camera, one seed, one moment. */
    private const val WIDTH = 1080
    private const val HEIGHT = 720
    private const val SEED = 20260922L

    /**
     * A terraced corner of the sacred grove, found by walking this seed.
     *
     * The origin, at this seed, is unlit catacombs — and a style sheet shot in
     * a grey room says nothing about the style.
     */
    private const val VANTAGE_X = 56
    private const val VANTAGE_Y = 104

    /**
     * The prompts rendered by default.
     *
     * Chosen to cover the extremes of the lexicon rather than to be pretty: if
     * a change to the lighting model holds up across grimdark, pastel and a
     * flat woodblock print, it holds up.
     */
    val SCENES: List<Pair<String, String>> = listOf(
        "00-before" to "",
        "01-house" to "stratum house style",
        "02-dark-diablo" to "dark grimdark diablo",
        "03-kawaii" to "kawaii pastel cute",
        "04-painterly" to "painterly impasto expressionist weird",
        "05-woodblock" to "woodblock ukiyo-e print",
        "06-chiaroscuro" to "chiaroscuro candlelit baroque",
        "07-toxic" to "toxic corrupted blight moody",
        "08-neon" to "neon synthwave cyberpunk",
        "09-frozen" to "frozen arctic winter",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "build/art-preview")
        target.mkdirs()

        val written = SCENES.map { (name, prompt) ->
            val file = File(target, "$name.png")
            ImageIO.write(render(prompt, unstyled = name == "00-before"), "png", file)
            file
        }

        written.forEach { println("wrote ${it.absolutePath}") }
        println("${written.size} frames at ${WIDTH}x$HEIGHT")
    }

    /**
     * One frame.
     *
     * [unstyled] draws with a director whose contract is switched off — flat
     * block colours, no lighting model, no atmosphere, no contrast budget. That
     * is what the renderer did before there was an art layer, and having it as
     * a style rather than as a deleted branch of code is what makes the
     * comparison honest.
     */
    fun render(prompt: String, unstyled: Boolean = false, elapsed: Float = 6f): BufferedImage {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        // The game's own defaults, not flattering ones. A style sheet shot at
        // settings nobody plays at is a style sheet for a different game.
        val config = WorldConfig(seed = SEED, simulationRadius = 3)
        val generator = StratumTerrain.create(content.terrainContext(config))
        val world = StreamingWorld(content.registry, generator, config)

        // A terraced corner of the sacred grove rather than wherever the
        // origin happens to land, which at this seed is unlit catacombs: a
        // style sheet shot in a grey room says nothing about the style.
        val camera = WorldPoint(VANTAGE_X.toFloat(), VANTAGE_Y.toFloat(), 0f)
        world.focusOn(camera.toBlockPos())
        val standing = world.surfaceAt(VANTAGE_X, VANTAGE_Y).coerceAtLeast(0)
        val eye = WorldPoint(camera.x, camera.y, standing.toFloat() + 1f)

        val kits = BiomeArtKit.deriveAll(IgboContentPack.pack)
        val director: WorldArtDirector = if (unstyled) {
            FlatArtDirector()
        } else {
            StyleSheetArtDirector(
                direction = StyleLexicon.interpret(prompt, ArtDirection.HOUSE, SEED).direction,
                kits = kits,
            )
        }

        val projection = IsometricProjection(zoom = 1.15f)
        val renderer = WorldFrameRenderer(projection, director)
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val sink = ImageFrameSink(image)
        val time = WorldTime(dayFraction = 0.46f, elapsedSeconds = elapsed)

        val biomeSource = generator as? BiomeSource
        val view = renderer.render(
            world = world,
            camera = eye,
            width = WIDTH.toFloat(),
            height = HEIGHT.toFloat(),
            sink = sink,
            highlight = BlockPos(VANTAGE_X + 2, VANTAGE_Y - 1, world.surfaceAt(VANTAGE_X + 2, VANTAGE_Y - 1)),
            time = time,
            biomeAt = { x, y -> biomeSource?.biomeAt(x, y) },
        )

        // A cast of the things the contrast contract is actually about: the
        // player, a pack of minions, one elite, and some loot on the floor.
        val cast = listOf(
            0 to 0 to ActorRole.PLAYER,
            3 to -2 to ActorRole.ENEMY,
            4 to 0 to ActorRole.ENEMY,
            5 to 2 to ActorRole.ENEMY,
            -3 to 3 to ActorRole.LOOT,
            -2 to -3 to ActorRole.INTERACTABLE,
        ).mapIndexed { index, (offset, role) ->
            val x = VANTAGE_X + offset.first
            val y = VANTAGE_Y + offset.second
            Triple(
                WorldPoint(x.toFloat(), y.toFloat(), world.surfaceAt(x, y).coerceAtLeast(0).toFloat()),
                role,
                if (role == ActorRole.ENEMY && index == 3) EnemyRank.ELITE else null,
            )
        }.sortedBy { projection.depthKey(it.first) }

        cast.forEach { (position, role, rank) ->
            val screen = projection.project(position)
            renderer.actor(
                sink = sink,
                x = view.originX + screen.x,
                y = view.originY + screen.y,
                presentation = ActorPresentation(
                    id = "$role",
                    role = role,
                    rank = rank,
                    depthBelowEye = (eye.z.toInt() - position.z.toInt()).coerceAtLeast(0),
                ),
                facingX = if (role == ActorRole.PLAYER) 1f else -1f,
                facingY = if (role == ActorRole.PLAYER) 0f else -0.4f,
            )
        }

        renderer.finish(sink, view, time, biomeSource?.biomeAt(4, 4))
        sink.dispose()
        return image
    }
}
