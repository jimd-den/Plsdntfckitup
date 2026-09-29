package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.motion.MotionBody
import com.stratum.core.domain.motion.MotionProfiles
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.model.mask.CharacterMasks
import com.stratum.engine.model.mask.MaskGenome
import com.stratum.engine.model.mask.MaskSpiritMesher
import com.stratum.engine.scene.MaskCast
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Mask characters in the world: frame strips of each thing a mask does, and
 * the hero mask fighting monster masks. Driven at sixty steps a second
 * through the same [MaskCast] and scene builder the game uses, and drawn by
 * the software twin of its renderer.
 */
internal object MaskSpiritShots {
    private const val SEED = 20260922L
    private const val VX = -460
    private const val VY = 470

    private class Stage(val world: StreamingWorld, val builder: SceneBuilder, val ground: Float, val textures: TextureLibrary)

    private val stage: Stage by lazy {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        val config = WorldConfig(seed = SEED, simulationRadius = 5)
        val generator = StratumTerrain.create(content.terrainContext(config))
        val world = StreamingWorld(content.registry, generator, config)
        world.focusOn(BlockPos(VX, VY, 0))
        val biomes = generator as? BiomeSource
        // A clear, level floor to fight on: laterite paving.
        val paving = content.registry.indexOf("igbo:laterite_paving")
        var g = world.surfaceAt(VX, VY)
        while (g > 0 && world.blockAt(BlockPos(VX, VY, g)).glyph != null) g--
        for (y in VY - 7..VY + 7) for (x in VX - 7..VX + 10) {
            world.setBlock(BlockPos(x, y, g), paving)
            for (z in g + 1..g + 6) world.setBlock(BlockPos(x, y, z), BlockRegistry.AIR_INDEX)
        }
        val textures = TextureLibrary()
        File("content/igbo/src/main/resources/forge/house").takeIf { it.isDirectory }?.let { ForgedTextures.loadInto(it, textures) }
        val builder = SceneBuilder(MaskSpiritPreview.director, textures, biomeAt = { x, y -> biomes?.biomeAt(x, y) })
        Stage(world, builder, g + 1f, textures)
    }

    private fun shot(cast: MaskCast, actors: List<SceneActor>, camera: SceneCamera, width: Int, height: Int, t: Float): BufferedImage {
        val frame = stage.builder.build(stage.world, camera, actors, WorldTime(dayFraction = 0.36f, elapsedSeconds = t), spirits = cast.spirits)
        return SceneRasterizer(width, height, stage.textures, supersample = 2, shadowSize = 1024).render(frame)
    }

    /** The footing the scene lays under a floating mask: shadow, and a rank ring for monsters. */
    private fun footing(id: String, x: Float, y: Float, rank: EnemyRank?, fx: Float = 0f, fy: Float = 1f) = SceneActor(
        x, y, stage.ground, ActorPresentation(id, if (rank == null) ActorRole.PLAYER else ActorRole.ENEMY, rank), fx, fy, drawnElsewhere = true,
    )

    private fun meshFor(genome: MaskGenome, rank: EnemyRank?) =
        MaskSpiritMesher.cached(genome, if (rank == null) MaskSpiritMesher.COMPANION_BUDGET else MaskSpiritMesher.MONSTER_BUDGET)

    private fun profileFor(genome: MaskGenome) = MotionProfiles.resolve(CharacterMasks.profileIdFor(genome))

    private class Shot(
        val name: String, val caption: String, val genome: MaskGenome, val rank: EnemyRank?, val times: List<Float>,
        /** Moves the true position (pos[0], pos[1] from the start) and fires events, at time t. */
        val script: (Float, MotionBody, FloatArray) -> Unit,
    )

    fun strips(out: File) {
        val hero = MaskGenome.presets[0]
        val foe = CharacterMasks.genomeFor("igbo:ogu_brute", EnemyRank.ELITE)
        val shots = listOf(
            Shot("move", "Move: hover, lean into the run, fringe streaming, turning ahead of the path", hero, null, listOf(0.45f, 0.75f, 1.05f, 1.35f, 1.65f, 1.95f)) { t, _, pos ->
                if (t < 1.2f) { pos[0] = -2.2f + (t - 0.3f).coerceAtLeast(0f) * 4f; pos[1] = 0f } else { pos[0] = 1.4f; pos[1] = -(t - 1.2f) * 4f }
            },
            Shot("attack", "Strike: wind-up, the mask throws itself head first, trail and ring in its palette, recoil", foe, EnemyRank.ELITE, listOf(0f, 0.07f, 0.13f, 0.19f, 0.27f, 0.45f)) { t, b, _ ->
                if (t >= 0f && b.strikeAge < 0f) b.strike(1.2f)
            },
            Shot("attack-lunge", "Strike (maiden): a clean lunge along the aim and a spring back", hero, null, listOf(0f, 0.08f, 0.16f, 0.22f, 0.32f, 0.55f)) { t, b, _ ->
                if (t >= 0f && b.strikeAge < 0f) b.strike(1.2f)
            },
            Shot("cast", "Cast: the eyes flare, the mask rises and swells, the hands spread", foe, EnemyRank.ELITE, listOf(0f, 0.1f, 0.2f, 0.3f, 0.42f, 0.6f)) { t, b, _ ->
                if (t >= 0f && b.castAge < 0f) b.cast()
            },
            Shot("crit", "Critical: the eyes flash white-hot as the blow lands", hero, null, listOf(0f, 0.05f, 0.12f, 0.2f, 0.35f, 0.6f)) { t, b, _ ->
                if (t >= 0f && b.strikeAge < 0f) { b.strike(1.5f); b.crit() }
            },
            Shot("hit", "Hit: a flinch away from the blow, a white flash and a crack of light", hero, null, listOf(0f, 0.03f, 0.07f, 0.12f, 0.2f, 0.4f)) { t, b, _ ->
                if (t >= 0f && b.hitAge < 0f) b.hit(b.x + 1.2f, b.y - 0.4f, 1f)
            },
            Shot("block", "Block: a shield of light, flickering as it fades", hero, null, listOf(0f, 0.05f, 0.1f, 0.2f, 0.35f, 0.6f)) { t, b, _ ->
                if (t >= 0f && b.blockAge < 0f) b.block()
            },
            Shot("death", "Death: a last flare, a slow turn, a dissolve into shards of light", foe, EnemyRank.ELITE, listOf(0f, 0.15f, 0.35f, 0.55f, 0.8f, 1.0f)) { t, b, _ ->
                if (t >= 0f && !b.dying) b.die()
            },
            Shot("spawn", "Spawn: rising into the world and fading in", foe, EnemyRank.ELITE, listOf(0.05f, 0.15f, 0.25f, 0.35f, 0.5f, 0.8f)) { _, _, _ -> },
        )
        shots.forEach { s ->
            val started = System.currentTimeMillis()
            val cast = MaskCast()
            val mesh = meshFor(s.genome, s.rank)
            val profile = profileFor(s.genome)
            val height = CharacterMasks.heightFor(s.rank)
            val pos = floatArrayOf(0f, 0f)
            val baseX = VX + 0.5f; val baseY = VY + 0.5f
            val dt = 1f / 60f
            var t = if (s.name == "spawn" || s.name == "move") 0f else -1.5f
            val frames = ArrayList<BufferedImage>()
            var next = 0
            var first = true
            while (next < s.times.size) {
                cast.begin()
                val moving = s.name == "move"
                val fx = if (moving && t >= 1.2f) 0f else 1f
                val fy = if (moving && t >= 1.2f) -1f else if (moving) 0f else -0.6f
                val body = cast.track("hero", mesh, profile, baseX + pos[0], baseY + pos[1], stage.ground, fx, fy, height, spawning = s.name == "spawn")!!
                if (first) { first = false }
                body.aim(baseX + pos[0] + 2f, baseY + pos[1] - 1.2f, stage.ground + 1f)
                s.script(t, body, pos)
                cast.advance(dt)
                if (t + 1e-4f >= s.times[next]) {
                    val focus = cast.spirits.first().pose
                    val camera = SceneCamera(target = Vec3(focus.x, focus.y, stage.ground + 0.9f), distance = 12f, aspect = 1f)
                    frames += shot(cast, listOf(footing("hero", body.trueX, body.trueY, s.rank)), camera, 250, 250, t)
                    next++
                }
                t += dt
            }
            ImageIO.write(MaskSpiritPreview.strip(frames, s.caption), "png", File(out, "strip-${s.name}.png"))
            println("strip ${s.name} in ${System.currentTimeMillis() - started}ms")
        }
    }

    /** The hero mask fighting monster masks, at the game's own camera and closer. */
    fun fight(out: File) {
        val cast = MaskCast()
        val heroGenome = MaskGenome.presets[0]
        class Foe(val id: String, val def: String, val rank: EnemyRank, val dx: Float, val dy: Float)
        val foes = listOf(
            Foe("m1", "igbo:ogu_brute", EnemyRank.MINION, 2.1f, -1.3f),
            Foe("m2", "igbo:shadow_leopard", EnemyRank.MINION, 2.5f, 1.4f),
            Foe("e1", "igbo:catacomb_guardian", EnemyRank.ELITE, 4.4f, 0.1f),
            Foe("b1", "igbo:ijele_king", EnemyRank.BOSS, 6.6f, -2.4f),
        )
        val bx = VX + 0.5f; val by = VY + 0.5f
        val heroMesh = meshFor(heroGenome, null)
        val heroProfile = profileFor(heroGenome)
        val dt = 1f / 60f
        var step = 0
        val steps = 150
        while (step < steps) {
            cast.begin()
            val hero = cast.track("hero", heroMesh, heroProfile, bx, by, stage.ground, 1f, -0.6f, CharacterMasks.HERO_HEIGHT, spawning = false)!!
            hero.aim(bx + foes[0].dx, by + foes[0].dy, stage.ground + 1f)
            foes.forEach { f ->
                val g = CharacterMasks.genomeFor(f.def, f.rank)
                val b = cast.track(f.id, meshFor(g, f.rank), profileFor(g), bx + f.dx, by + f.dy, stage.ground, -f.dx, -f.dy, CharacterMasks.heightFor(f.rank), spawning = false)!!
                b.aim(bx, by, stage.ground + 1f)
            }
            // The moment: the hero strikes the brute and crits; the brute reels; the elite casts; the boss winds up.
            if (step == steps - 12) { hero.strike(1.3f); hero.crit() }
            if (step == steps - 6) cast.body("m1")?.hit(bx, by, 1f)
            if (step == steps - 16) cast.body("e1")?.cast()
            if (step == steps - 5) cast.body("b1")?.strike(1f)
            if (step == steps - 30) cast.body("m2")?.strike(1f)
            cast.advance(dt)
            step++
        }
        val t = steps * dt
        val actors = listOf(footing("hero", bx, by, null, 1f, -0.6f)) + foes.map { footing(it.id, bx + it.dx, by + it.dy, it.rank, -it.dx, -it.dy) }
        val game = SceneCamera(target = Vec3(bx + 3f, by - 0.5f, stage.ground + 0.5f), aspect = 16f / 9f)
        ImageIO.write(shot(cast, actors, game, 1280, 720, t), "png", File(out, "world-fight.png"))
        val close = SceneCamera(target = Vec3(bx + 2.6f, by - 0.6f, stage.ground + 1f), aspect = 16f / 9f, distance = 17f)
        ImageIO.write(shot(cast, actors, close, 1280, 720, t), "png", File(out, "world-fight-close.png"))
        println("world fight written")
    }
}
