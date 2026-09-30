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
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A microvoxel world through the game's own renderer.
 *
 * Builds frames with the same [SceneBuilder] the phone uses and draws them
 * with [SceneRasterizer], the software copy of the GLES pipeline -- so what
 * this shows is what the game draws, block chunks and microvoxel chunks
 * side by side at the ARPG camera. It also measures what matters on a
 * phone: chunk generation, meshing on the first frame and while walking,
 * triangles and vertex memory, per quality tier.
 *
 *   args: <outDir> [forgeDir] [seed]
 */
object MicroScenePreview {

    private const val WIDTH = 960
    private const val HEIGHT = 540

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "build/micro-scene-preview" }).apply { mkdirs() }
        val forged = args.getOrNull(1)?.let(::File)?.let { File(it, "house") }?.takeIf { it.isDirectory }
        val seed = args.getOrNull(2)?.toLongOrNull() ?: 20260928L

        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
            .let { it.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) }
        val config = WorldConfig(seed = seed, simulationRadius = 3)
        // From a real session, so the home town is the one a player starts in: the session decides
        // which towns are welcoming by the player's standing with each faction.
        val session = com.stratum.engine.world.WorldSession(content, config)
        val hot = requireNotNull(session.hotTerrain) { "a microvoxel world is hot" }
        val generator = hot.current

        val director = StyleSheetArtDirector(
            StyleLexicon.interpret("stratum house style", ArtDirection.HOUSE, seed).direction,
            BiomeArtKit.deriveAll(IgboContentPack.pack),
        )
        val textures = TextureLibrary().also { lib -> forged?.let { ForgedTextures.loadInto(it, lib) } }

        // `traditions [ids...]`: only the building traditions, one home town each.
        if (args.getOrNull(3) == "parametric") {
            val ids = com.stratum.engine.microvoxel.arch.ParametricTradition.ID
            val looks = PARAMETRIC_LOOKS.filter { args.size <= 4 || it.first in args.drop(4) }
            traditions(out, content, config, hot, director, textures, looks.map { it.first }, looks.associate { it.first to (mapOf("homeStyle" to ids) + it.second) })
            return
        }
        if (args.getOrNull(3) == "stamps") {
            stamps(out, content, config, hot, director, textures, args.getOrNull(1)?.let(::File))
            return
        }
        if (args.getOrNull(3) == "diorama") {
            // `diorama [bench] [shots...]`: the before/after shots, optionally only some, and the benchmark.
            val rest = args.drop(4)
            diorama(out, content, config, generator, director, textures, rest.filter { it != "bench" })
            if ("bench" in rest) File(out, "micro-benchmark.txt").writeText(benchmark(content, config, generator, director, textures, homeVantage(generator)).also(::println))
            return
        }
        // `splats [bench] [shots...]`: the land drawn as voxel splats against the greedy mesh, and what each costs.
        if (args.getOrNull(3) == "splats") {
            val rest = args.drop(4)
            splatShots(out, content, config, generator, director, textures, rest.filter { it != "bench" })
            if ("bench" in rest) File(out, "splat-benchmark.txt").writeText(splatBenchmark(content, config, generator, director, textures, homeVantage(generator)).also(::println))
            return
        }
        // `geology [province ids...]`: the wild land of each province, seen from a hillside away from towns.
        if (args.getOrNull(3) == "geology") {
            geology(out, content, config, hot, director, textures, args.drop(4))
            return
        }
        if (args.getOrNull(3) == "traditions") {
            traditions(out, content, config, hot, director, textures, args.drop(4))
            return
        }
        val vantages = listOf("home" to homeVantage(generator), "wilds" to wildVantage(generator))
        val shots = listOf(Triple("home", vantages[0].second, 34f), Triple("home-overview", vantages[0].second, 62f), Triple("wilds", vantages[1].second, 34f))
        for ((name, v, distance) in shots) {
            val world = StreamingWorld(content.registry, generator, config)
            world.focusOn(BlockPos(v.first, v.second, 0))
            val ground = world.surfaceAt(v.first, v.second)
            val camera = SceneCamera(target = Vec3(v.first + 0.5f, v.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = distance)
            val actors = actorsAround(world, v.first, v.second)
            val blocks = SceneBuilder(director, textures, biomeAt = { x, y -> generator.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH))
            val micro = SceneBuilder(director, textures, biomeAt = { x, y -> generator.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = generator)
            val time = WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)
            val a = SceneRasterizer(WIDTH, HEIGHT, textures).render(blocks.build(world, camera, actors, time))
            val b = SceneRasterizer(WIDTH, HEIGHT, textures).render(settled(micro) { micro.build(world, camera, actors, time) })
            ImageIO.write(b, "png", File(out, "micro-$name.png"))
            ImageIO.write(sideBySide(a, b), "png", File(out, "micro-$name-vs-blocks.png"))
            println("wrote micro-$name.png (vantage ${v.first},${v.second} ground $ground)")
        }
        hotEdits(out, content, config, hot, director, textures)
        reveal(out, content, config, generator, director, textures)
        val report = benchmark(content, config, generator, director, textures, vantages.first().second)
        File(out, "micro-benchmark.txt").writeText(report)
        println(report)
    }

    /**
     * The World panel's edits, rendered: the same world and vantage, the
     * generator retuned between shots exactly as the in-game panel retunes it.
     */
    private fun hotEdits(
        out: File, content: AssembledContent, config: WorldConfig, hot: com.stratum.engine.microbridge.HotTerrain,
        director: StyleSheetArtDirector, textures: TextureLibrary,
    ) {
        val base = hot.passes
        fun with(id: String, options: Map<String, String>) = { passes: List<com.stratum.engine.microvoxel.gen.StageSpec> ->
            val old = passes.firstOrNull { it.id == id } ?: com.stratum.engine.microvoxel.gen.StageSpec(id)
            MicrovoxelTerrainGenerator.withStage(passes, old.copy(options = old.options + options))
        }
        val land = "micro:terrain"; val towns = "micro:settlements"
        val edits = listOf(
            "as-shipped" to { p: List<com.stratum.engine.microvoxel.gen.StageSpec> -> p },
            "highlands" to with(land, mapOf("height" to "0.75", "mountains" to "1.4", "scale" to "0.6")),
            "terraces" to with(land, mapOf("height" to "0.4", "mountains" to "0.5", "terrace" to "6")),
            "home-city-walled" to with(towns, mapOf("homeSize" to "1.8", "homeWalls" to "on", "homeLayout" to "stratum:grid")),
            "home-hamlet" to with(towns, mapOf("homeSize" to "0.75", "homeWalls" to "off", "homeVariant" to "3")),
            "home-plain-style" to with(towns, mapOf("style" to "plain", "sacredTree" to "false")),
        )
        for ((name, edit) in edits) {
            val t0 = System.nanoTime()
            hot.retune(edit(base))?.let { error("$name: $it") }
            val ms = (System.nanoTime() - t0) / 1e6
            val v = homeVantage(hot.current)
            val world = StreamingWorld(content.registry, hot, config)
            world.focusOn(BlockPos(v.first, v.second, 0))
            val ground = world.surfaceAt(v.first, v.second)
            val camera = SceneCamera(target = Vec3(v.first + 0.5f, v.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = 62f)
            val micro = SceneBuilder(director, textures, biomeAt = { x, y -> hot.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = hot)
            val frame = settled(micro) { micro.build(world, camera, actorsAround(world, v.first, v.second), WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)) }
            ImageIO.write(SceneRasterizer(WIDTH, HEIGHT, textures).render(frame), "png", File(out, "hot-$name.png"))
            println("wrote hot-$name.png (retune ${"%.0f".format(ms)} ms)")
        }
        hot.retune(base)
    }

    /**
     * The hero indoors, under a thatched roof: without the reveal the roof
     * hides them, with it the roof and front wall open in a soft circle.
     */
    private fun reveal(
        out: File, content: AssembledContent, config: WorldConfig, gen: MicrovoxelTerrainGenerator,
        director: StyleSheetArtDirector, textures: TextureLibrary,
    ) {
        val home = (gen as? com.stratum.core.domain.settlement.SettlementAtlas)?.settlementsNear(0, 0, 0)?.firstOrNull() ?: return
        val b = home.buildings.maxByOrNull { it.width * it.depth } ?: return
        val x = b.x + b.width / 2; val y = b.y + b.depth / 2
        val world = StreamingWorld(content.registry, gen, config)
        world.focusOn(BlockPos(x, y, 0))
        val feet = home.groundZ + 1f
        val actors = listOf(
            SceneActor(x + 0.5f, y + 0.5f, feet, ActorPresentation("p", ActorRole.PLAYER), 1f, -0.3f, spriteKey = "actor:igbo:dike_ozo"),
            SceneActor(b.doorX + 0.5f, b.doorY + 0.5f, feet, ActorPresentation("e", ActorRole.ENEMY, EnemyRank.ELITE), -1f, -0.4f, spriteKey = "actor:igbo:shadow_leopard"),
        )
        val camera = SceneCamera(target = Vec3(x + 0.5f, y + 0.5f, feet), aspect = WIDTH.toFloat() / HEIGHT, distance = 30f)
        val time = WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)
        fun shot(radius: Float): BufferedImage {
            val builder = SceneBuilder(director, textures, biomeAt = { bx, by -> gen.biomeAt(bx, by) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = gen)
            builder.revealRadius = radius
            return SceneRasterizer(WIDTH, HEIGHT, textures).render(settled(builder) { builder.build(world, camera, actors, time) })
        }
        val off = shot(0f); val on = shot(com.stratum.engine.scene.Reveal.DEFAULT_RADIUS)
        ImageIO.write(on, "png", File(out, "reveal-indoors.png"))
        ImageIO.write(sideBySide(off, on, "Reveal off", "Reveal on: the hero is never covered"), "png", File(out, "reveal-indoors-off-vs-on.png"))
        println("wrote reveal-indoors.png (${b.template.id} at $x,$y)")
    }

    /** The home town built in each tradition in turn, seen from above its main street. */
    private fun traditions(
        out: File, content: AssembledContent, config: WorldConfig, hot: com.stratum.engine.microbridge.HotTerrain,
        director: StyleSheetArtDirector, textures: TextureLibrary, only: List<String>,
        optionsFor: Map<String, Map<String, String>> = emptyMap(),
    ) {
        val base = hot.passes
        val ids = only.ifEmpty { com.stratum.engine.microvoxel.arch.Traditions.ids }
        for (id in ids) {
            val towns = base.firstOrNull { it.id == "micro:settlements" } ?: com.stratum.engine.microvoxel.gen.StageSpec("micro:settlements")
            val options = optionsFor[id] ?: mapOf("homeStyle" to id)
            hot.retune(MicrovoxelTerrainGenerator.withStage(base, towns.copy(options = towns.options + options)))?.let { error("$id: $it") }
            val v = homeVantage(hot.current)
            val world = StreamingWorld(content.registry, hot, config)
            world.focusOn(BlockPos(v.first, v.second, 0))
            val ground = world.surfaceAt(v.first, v.second)
            val camera = SceneCamera(target = Vec3(v.first + 0.5f, v.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = 40f)
            val micro = SceneBuilder(director, textures, biomeAt = { x, y -> hot.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = hot)
            val frame = settled(micro) { micro.build(world, camera, actorsAround(world, v.first, v.second), WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)) }
            val img = SceneRasterizer(WIDTH, HEIGHT, textures).render(frame)
            val name = com.stratum.engine.microvoxel.arch.Traditions.all(hot.palette).firstOrNull { it.id == id }?.name
                ?: options["homeStyle"]?.takeIf { it.startsWith("parametric:") }?.let { v ->
                    val t = v.removePrefix("parametric:")
                    "Invented within the " + (com.stratum.engine.microvoxel.arch.Traditions.all(hot.palette).firstOrNull { it.id == t }?.name ?: t) + " grammar"
                }
                ?: ("Parametric: " + options.filterKeys { it != "homeStyle" }.entries.joinToString("  ") { "${it.key}=${it.value}" })
            runCatching {
                val g = img.createGraphics()
                g.color = java.awt.Color(0, 0, 0, 150); g.fillRect(0, HEIGHT - 34, WIDTH, 34)
                g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18); g.color = java.awt.Color.WHITE
                g.drawString(name, 14, HEIGHT - 11); g.dispose()
            }
            val file = if (id in optionsFor) "parametric-$id.png" else "tradition-$id.png"
            ImageIO.write(img, "png", File(out, file))
            println("wrote $file")
        }
        hot.retune(base)
    }

    /** The provinces the geology shots show by default: rivers, dunes, erosion, scree, rifts and folds. */
    private val GEOLOGY_SHOTS = listOf(
        "africa", "forest_hills", "rainforest_basin", "erg", "namib", "karoo", "drakensberg",
        "rift_valley", "atlas_folds", "sandstone_escarpment", "highland_traps", "tsingy",
    )

    /**
     * The wild land of each province: the world retuned to that one geology,
     * and a vantage away from towns where the land does the most within a
     * stone's throw -- a valley side, a dune field, a scarp -- seen from the
     * game's camera and from further out.
     */
    private fun geology(
        out: File, content: AssembledContent, config: WorldConfig, hot: com.stratum.engine.microbridge.HotTerrain,
        director: StyleSheetArtDirector, textures: TextureLibrary, only: List<String>,
    ) {
        val base = hot.passes
        // An entry is a province id, or `id@x,y` to look from a fixed block, so two builds compare like for like.
        for (entry in only.ifEmpty { GEOLOGY_SHOTS }) {
            val id = entry.substringBefore('@')
            val fixed = entry.substringAfter('@', "").split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }
            val land = base.firstOrNull { it.id == "micro:terrain" } ?: com.stratum.engine.microvoxel.gen.StageSpec("micro:terrain")
            hot.retune(MicrovoxelTerrainGenerator.withStage(base, land.copy(options = land.options + mapOf("geology" to id))))?.let { error("$id: $it") }
            val v = fixed?.let { it[0] to it[1] } ?: varied(hot.current)
            for ((suffix, distance) in listOf("" to 40f, "-far" to 90f)) {
                val world = StreamingWorld(content.registry, hot, config)
                world.focusOn(BlockPos(v.first, v.second, 0))
                val ground = world.surfaceAt(v.first, v.second)
                val camera = SceneCamera(target = Vec3(v.first + 0.5f, v.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = distance)
                val micro = SceneBuilder(director, textures, biomeAt = { x, y -> hot.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = hot)
                val frame = settled(micro) { micro.build(world, camera, emptyList(), WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)) }
                val img = SceneRasterizer(WIDTH, HEIGHT, textures).render(frame)
                runCatching {
                    val g = img.createGraphics()
                    g.color = java.awt.Color(0, 0, 0, 150); g.fillRect(0, HEIGHT - 34, WIDTH, 34)
                    g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18); g.color = java.awt.Color.WHITE
                    g.drawString("$id  (${v.first}, ${v.second})", 14, HEIGHT - 11); g.dispose()
                }
                ImageIO.write(img, "png", File(out, "geo-$id$suffix.png"))
                println("wrote geo-$id$suffix.png (vantage ${v.first},${v.second} ground $ground)")
            }
        }
        hot.retune(base)
    }

    /** Open land away from towns, above the sea, where the ground rises and falls the most nearby. */
    private fun varied(gen: MicrovoxelTerrainGenerator): Pair<Int, Int> {
        val footprint = gen.micro.fields.get(Fields.FOOTPRINT)
        val surface = gen.micro.fields.require(Fields.SURFACE)
        val sea = gen.micro.fields.require(Fields.SEA_LEVEL)
        var best = 200 to 200; var bestScore = -1f
        for (d in 3..14) for (k in 0 until 8) {
            val x = (d * 48 * kotlin.math.cos(k * 0.785 + d)).toInt(); val y = (d * 48 * kotlin.math.sin(k * 0.785 + d)).toInt()
            val mx = x * 4; val my = y * 4
            if ((footprint?.urban(mx, my) ?: 0f) > 0f) continue
            val h = surface.heightAt(mx, my)
            if (h < sea + 6) continue
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (j in -3..3) for (i in -3..3) { val q = surface.heightAt(mx + i * 24, my + j * 24); lo = minOf(lo, q); hi = maxOf(hi, q) }
            // Some relief, but not a sheer wall: the most varied ground that still reads as land.
            val score = minOf(hi - lo, 120f)
            if (score > bestScore) { bestScore = score; best = x to y }
        }
        return best
    }

    /**
     * The cozy builder in the world: a model-studio building, a picture turned
     * into a statue, a chiselled pit and a heaped mound, stamped beside the
     * home street and drawn in full microvoxel detail, before and after.
     */
    private fun stamps(
        out: File, content: AssembledContent, config: WorldConfig, hot: com.stratum.engine.microbridge.HotTerrain,
        director: StyleSheetArtDirector, textures: TextureLibrary, forge: File?,
    ) {
        val v = homeVantage(hot.current)
        fun shot(name: String) {
            val world = StreamingWorld(content.registry, hot, config)
            world.focusOn(BlockPos(v.first, v.second, 0))
            val ground = world.surfaceAt(v.first, v.second)
            val camera = SceneCamera(target = Vec3(v.first + 3.5f, v.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = 36f)
            val micro = SceneBuilder(director, textures, biomeAt = { x, y -> hot.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.HIGH), microTerrain = hot)
            val frame = settled(micro) { micro.build(world, camera, actorsAround(world, v.first, v.second), WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)) }
            ImageIO.write(SceneRasterizer(WIDTH, HEIGHT, textures).render(frame), "png", File(out, "stamps-$name.png"))
            println("wrote stamps-$name.png")
        }
        shot("before")
        val probe = StreamingWorld(content.registry, hot, config).also { it.focusOn(BlockPos(v.first, v.second, 0)) }
        fun groundAt(x: Int, y: Int) = probe.surfaceAt(x, y)
        val r = 4
        // A Lamu house from the model studio's generator.
        val (house, _) = com.stratum.engine.microbridge.ModelFactory.building(20260929L, tradition = "swahili", widthBlocks = 5, depthBlocks = 4)
        val hx = v.first + 6; val hy = v.second - 8
        hot.stamp(com.stratum.core.domain.micro.MicroStamp(house.id, hx * r, hy * r, (groundAt(hx + 3, hy + 3) + 1) * r - 4), house)
        // The hero's own sprite, turned into a statue on a plinth.
        val sprite = forge?.let { File(it, "hades/actor~igbo~dike_ozo.png") }?.takeIf { it.isFile }?.let { ImageIO.read(it) }
        if (sprite != null) {
            val px = IntArray(sprite.width * sprite.height).also { sprite.getRGB(0, 0, sprite.width, sprite.height, it, 0, sprite.width) }
            val statue = com.stratum.engine.model.ImageVoxelizer.voxelize("statue", "Dike Ozo", com.stratum.engine.model.ArgbImage(sprite.width, sprite.height, px), com.stratum.engine.model.ImageVoxelizer.Options(height = 40))
            val sx = v.first + 2; val sy = v.second + 3
            val plinth = com.stratum.core.domain.micro.MicroBrushes.id("cube", 4, "arch:drystone")
            val base = (groundAt(sx, sy) + 1) * r
            hot.stamp(com.stratum.core.domain.micro.MicroStamp(plinth, sx * r - 2, sy * r - 2, base - 6))
            hot.stamp(com.stratum.core.domain.micro.MicroStamp(statue.id, sx * r + 2 - statue.sizeX / 2, sy * r + 2 - statue.sizeY / 2, base + 3), statue)
        }
        // A chiselled pond and a heaped mound.
        val cx = v.first + 9; val cy = v.second + 4
        hot.stamp(com.stratum.core.domain.micro.MicroStamp(com.stratum.core.domain.micro.MicroBrushes.id("sphere", 9, "stone"), cx * r - 9, cy * r - 9, groundAt(cx, cy) * r - 7, carve = true))
        val mx = v.first - 4; val my = v.second + 7
        hot.stamp(com.stratum.core.domain.micro.MicroStamp(com.stratum.core.domain.micro.MicroBrushes.id("dome", 10, "#5E9B3A"), mx * r - 10, my * r - 10, groundAt(mx, my) * r - 8))
        shot("after")
    }

    /**
     * The handcrafted-diorama finish, before and after: the home street, the
     * wilds, a building close up, and the town at night with its windows lit.
     * "before" is HIGH with every [com.stratum.engine.scene.quality.DioramaLook]
     * ingredient off -- the renderer as it was; "high" is the HIGH tier's
     * look; "ultra" is HIGH's world with ULTRA's look (wider surfels and the
     * tilt-shift), so the three differ in nothing but the finish.
     */
    private fun diorama(
        out: File, content: AssembledContent, config: WorldConfig, gen: MicrovoxelTerrainGenerator,
        director: StyleSheetArtDirector, textures: TextureLibrary, only: List<String> = emptyList(),
    ) {
        val home = homeVantage(gen)
        val wild = wildVantage(gen)
        val building = (gen as? com.stratum.core.domain.settlement.SettlementAtlas)?.settlementsNear(0, 0, 0)?.firstOrNull()
            ?.buildings?.minByOrNull { kotlin.math.abs(it.x + it.width / 2 - home.first) + kotlin.math.abs(it.y + it.depth / 2 - home.second) }
            ?.let { (it.x + it.width / 2) to (it.y + it.depth / 2) } ?: home
        val noon = WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)
        val night = WorldTime(dayFraction = 0.02f, elapsedSeconds = 7f)
        data class Shot(val name: String, val at: Pair<Int, Int>, val distance: Float, val time: WorldTime, val actors: Boolean = true)
        val shots = listOf(
            Shot("home", home, 34f, noon),
            Shot("wilds", wild, 34f, noon),
            Shot("building", building, 19f, noon, actors = false),
            Shot("night", glowNear(gen, home), 26f, night),
        )
        val high = RenderSettings.of(QualityTier.HIGH)
        val looks = listOf(
            "before" to high.copy(diorama = com.stratum.engine.scene.quality.DioramaLook.OFF),
            "high" to high,
            "ultra" to high.copy(diorama = com.stratum.engine.scene.quality.DioramaLook.of(QualityTier.ULTRA)),
        )
        for (shot in shots.filter { only.isEmpty() || it.name in only }) {
            val images = LinkedHashMap<String, BufferedImage>()
            for ((lookName, settings) in looks) {
                val world = StreamingWorld(content.registry, gen, config)
                world.focusOn(BlockPos(shot.at.first, shot.at.second, 0))
                val ground = world.surfaceAt(shot.at.first, shot.at.second)
                val camera = SceneCamera(target = Vec3(shot.at.first + 0.5f, shot.at.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = shot.distance)
                val actors = if (shot.actors) actorsAround(world, shot.at.first, shot.at.second) else emptyList()
                val builder = SceneBuilder(director, textures, biomeAt = { x, y -> gen.biomeAt(x, y) }, settings = settings, microTerrain = gen)
                val frame = settled(builder) { builder.build(world, camera, actors, shot.time) }
                val t = System.nanoTime()
                val img = SceneRasterizer(WIDTH, HEIGHT, textures).render(frame)
                val ms = (System.nanoTime() - t) / 1e6
                images[lookName] = img
                ImageIO.write(img, "png", File(out, "diorama-${shot.name}-$lookName.png"))
                println("wrote diorama-${shot.name}-$lookName.png (${frame.surfels.sumOf { it.count }} surfels drawn, rasterised in ${"%.0f".format(ms)} ms)")
            }
            ImageIO.write(sideBySide(images.getValue("before"), images.getValue("ultra"), "Before: flat microvoxels", "After: the diorama finish (ULTRA)"), "png", File(out, "diorama-${shot.name}-before-vs-after.png"))
            ImageIO.write(sideBySide(images.getValue("high"), images.getValue("ultra"), "HIGH tier", "ULTRA tier"), "png", File(out, "diorama-${shot.name}-high-vs-ultra.png"))
            // A crop at twice the size from the middle, where the surfels and bevels live.
            ImageIO.write(sideBySide(zoom(images.getValue("before")), zoom(images.getValue("ultra")), "Before (2x crop)", "After (2x crop)"), "png", File(out, "diorama-${shot.name}-closeup.png"))
        }
    }

    /**
     * The same shots drawn three ways: the greedy mesh, [SplatMode.FAST]
     * splats and [SplatMode.EXACT] splats, at HIGH; and LOW's mesh against
     * LOW with fast splats, the phone the splats are for. Each shot also
     * leaves what the GPU harness (`tools/splatgl`) needs to draw the same
     * splats through the game's own GLSL.
     */
    private fun splatShots(
        out: File, content: AssembledContent, config: WorldConfig, gen: MicrovoxelTerrainGenerator,
        director: StyleSheetArtDirector, textures: TextureLibrary, only: List<String> = emptyList(),
    ) {
        val home = homeVantage(gen)
        val building = (gen as? com.stratum.core.domain.settlement.SettlementAtlas)?.settlementsNear(0, 0, 0)?.firstOrNull()
            ?.buildings?.minByOrNull { kotlin.math.abs(it.x + it.width / 2 - home.first) + kotlin.math.abs(it.y + it.depth / 2 - home.second) }
            ?.let { (it.x + it.width / 2) to (it.y + it.depth / 2) } ?: home
        val noon = WorldTime(dayFraction = 0.40f, elapsedSeconds = 7f)
        val night = WorldTime(dayFraction = 0.02f, elapsedSeconds = 7f)
        data class Shot(val name: String, val at: Pair<Int, Int>, val distance: Float, val time: WorldTime, val actors: Boolean = true)
        val shots = listOf(
            Shot("home", home, 34f, noon),
            Shot("wilds", wildVantage(gen), 34f, noon),
            Shot("building", building, 12f, noon, actors = false),
            Shot("night", glowNear(gen, home), 26f, night),
        )
        val mode = com.stratum.engine.scene.SplatMode.MESH
        fun tier(t: QualityTier, m: com.stratum.engine.scene.SplatMode) = RenderSettings.of(t).copy(splats = m)
        val looks = listOf(
            "high-mesh" to tier(QualityTier.HIGH, mode),
            "high-exact" to tier(QualityTier.HIGH, com.stratum.engine.scene.SplatMode.EXACT),
            "medium-mesh" to tier(QualityTier.MEDIUM, mode),
            "medium-fast" to tier(QualityTier.MEDIUM, com.stratum.engine.scene.SplatMode.FAST),
            "medium-exact" to tier(QualityTier.MEDIUM, com.stratum.engine.scene.SplatMode.EXACT),
            "low-mesh" to tier(QualityTier.LOW, mode),
            "low-fast" to tier(QualityTier.LOW, com.stratum.engine.scene.SplatMode.FAST),
        )
        for (shot in shots.filter { only.isEmpty() || it.name in only }) {
            val images = LinkedHashMap<String, BufferedImage>()
            for ((lookName, settings) in looks) {
                val world = StreamingWorld(content.registry, gen, config)
                world.focusOn(BlockPos(shot.at.first, shot.at.second, 0))
                val ground = world.surfaceAt(shot.at.first, shot.at.second)
                val camera = SceneCamera(target = Vec3(shot.at.first + 0.5f, shot.at.second + 0.5f, ground + 1f), aspect = WIDTH.toFloat() / HEIGHT, distance = shot.distance)
                val actors = if (shot.actors) actorsAround(world, shot.at.first, shot.at.second) else emptyList()
                val builder = SceneBuilder(director, textures, biomeAt = { x, y -> gen.biomeAt(x, y) }, settings = settings, microTerrain = gen)
                val frame = settled(builder) { builder.build(world, camera, actors, shot.time) }
                val t = System.nanoTime()
                val img = SceneRasterizer(WIDTH, HEIGHT, textures).render(frame)
                val ms = (System.nanoTime() - t) / 1e6
                images[lookName] = img
                ImageIO.write(img, "png", File(out, "splats-${shot.name}-$lookName.png"))
                val splats = frame.splats.sumOf { it.count.toLong() }
                val tris = frame.terrain.sumOf { it.triangleCount.toLong() }
                println("wrote splats-${shot.name}-$lookName.png ($splats splats, $tris terrain triangles, rasterised in ${"%.0f".format(ms)} ms)")
                if (lookName == "medium-fast") SplatGpuDump.write(File(out, "gpu/${shot.name}"), frame, WIDTH, HEIGHT)
            }
            ImageIO.write(sideBySide(images.getValue("high-mesh"), images.getValue("high-exact"), "HIGH: greedy mesh", "HIGH: exact voxel splats"), "png", File(out, "splats-${shot.name}-high.png"))
            ImageIO.write(sideBySide(images.getValue("medium-mesh"), images.getValue("medium-fast"), "MEDIUM: greedy mesh", "MEDIUM: fast voxel splats"), "png", File(out, "splats-${shot.name}-medium.png"))
            ImageIO.write(sideBySide(images.getValue("low-mesh"), images.getValue("low-fast"), "LOW: greedy mesh", "LOW: fast voxel splats"), "png", File(out, "splats-${shot.name}-low.png"))
            ImageIO.write(sideBySide(zoom(images.getValue("medium-fast")), zoom(images.getValue("medium-exact")), "Fast splats (2x crop)", "Exact splats (2x crop)"), "png", File(out, "splats-${shot.name}-fast-vs-exact.png"))
            ImageIO.write(sideBySide(zoom(images.getValue("high-mesh")), zoom(images.getValue("high-exact")), "Mesh (2x crop)", "Exact splats (2x crop)"), "png", File(out, "splats-${shot.name}-closeup.png"))
        }
    }

    /**
     * Greedy mesh against splats, where each is paid for: building a chunk,
     * remaking one layer after an edit, what it holds on the GPU, and how
     * many vertices the GPU runs through its vertex shader per frame.
     */
    private fun splatBenchmark(
        content: AssembledContent, config: WorldConfig, generator: MicrovoxelTerrainGenerator,
        director: StyleSheetArtDirector, textures: TextureLibrary, start: Pair<Int, Int>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Voxel splats against the greedy mesh (seed ${config.seed}, this JVM, ${Runtime.getRuntime().availableProcessors()} cores)")
        sb.appendLine()
        val world = StreamingWorld(content.registry, generator, config)
        world.focusOn(BlockPos(start.first, start.second, 0))
        val chunks = world.loadedChunks.map { it.pos }.take(16)
        val mesher = com.stratum.engine.scene.MicroDetailMesher(generator)
        val splatter = com.stratum.engine.scene.MicroDetailMesher(generator, splatMode = com.stratum.engine.scene.SplatMode.FAST)
        fun time(m: com.stratum.engine.scene.MicroDetailMesher, lod: Int): Double {
            repeat(2) { chunks.forEach { m.mesh(world, it, lod) } }
            val t = System.nanoTime()
            repeat(3) { chunks.forEach { m.mesh(world, it, lod) } }
            return (System.nanoTime() - t) / 1e6 / (3 * chunks.size)
        }
        fun meshBytes(r: com.stratum.engine.scene.TerrainMesher.Result?) = r?.mesh?.let { it.vertexFloats.toLong() * 4 + it.indexCount.toLong() * 4 } ?: 0L
        sb.appendLine("Per 16x16x48 chunk (average of ${chunks.size} around the home town)")
        sb.appendLine("detail       build: mesh   splats    GPU bytes: mesh   splats    vertices run: mesh   splats   primitives: triangles   points")
        for (lod in listOf(1, 2)) {
            val m = chunks.map { mesher.mesh(world, it, lod) }
            val s = chunks.map { splatter.mesh(world, it, lod) }
            val mb = m.sumOf(::meshBytes) / chunks.size
            // Splat chunks still carry their water as a small mesh.
            val sbytes = s.sumOf { (it?.splats?.bytes?.toLong() ?: 0L) + meshBytes(it) } / chunks.size
            val mv = m.sumOf { it?.mesh?.vertexCount?.toLong() ?: 0L } / chunks.size
            val sp = s.sumOf { it?.splats?.count?.toLong() ?: 0L } / chunks.size
            val tris = m.sumOf { it?.mesh?.triangleCount?.toLong() ?: 0L } / chunks.size
            sb.appendLine("%-10s %9.1f ms %6.1f ms %13.0f KB %6.0f KB %15s %9s %16s %9s".format(
                if (lod == 1) "full" else "half-block", time(mesher, lod), time(splatter, lod), mb / 1024.0, sbytes / 1024.0,
                "%,d".format(mv), "%,d".format(sp), "%,d".format(tris), "%,d".format(sp),
            ))
        }
        sb.appendLine()
        // An edit: one layer of one chunk remade, as the edit worker does after a block is placed.
        val layers = mesher.layers
        fun editMs(m: com.stratum.engine.scene.MicroDetailMesher): Double {
            val snaps = chunks.map { com.stratum.engine.scene.BlockSnapshot.of(world, it) }
            val prev = chunks.indices.map { m.meshLayers(snaps[it], chunks[it], 1)!! }
            val only = BooleanArray(layers).also { it[1] = true }
            repeat(3) { chunks.indices.forEach { i -> m.meshLayers(snaps[i], chunks[i], 1, only, prev[i]) } }
            val t = System.nanoTime()
            repeat(5) { chunks.indices.forEach { i -> m.meshLayers(snaps[i], chunks[i], 1, only, prev[i]) } }
            return (System.nanoTime() - t) / 1e6 / (5 * chunks.size)
        }
        sb.appendLine("One edit (a chunk layer remade at full detail): mesh %.2f ms, splats %.2f ms".format(editMs(mesher), editMs(splatter)))
        sb.appendLine()
        sb.appendLine("Whole view, per tier, settled (every chunk in detail)")
        sb.appendLine("tier    splat  GPU bytes: mesh   splats    vertices run: mesh   splats   frame build: mesh   splats")
        for (tier in QualityTier.values()) {
            val base = RenderSettings.of(tier)
            fun measure(mode: com.stratum.engine.scene.SplatMode): Triple<Long, Long, Double> {
                val w = StreamingWorld(content.registry, generator, config)
                w.focusOn(BlockPos(start.first, start.second, 0))
                val builder = SceneBuilder(director, textures, biomeAt = { x, y -> generator.biomeAt(x, y) }, settings = base.copy(splats = mode), microTerrain = generator)
                val cam = SceneCamera(target = Vec3(start.first + 0.5f, start.second + 0.5f, w.surfaceAt(start.first, start.second) + 1f), aspect = 16f / 9f)
                val f = settled(builder) { builder.build(w, cam, emptyList(), WorldTime(dayFraction = 0.4f)) }
                val bytes = f.residentTerrain.sumOf { it.vertexFloats.toLong() * 4 + it.indexCount.toLong() * 4 } + f.residentSplats.sumOf { it.bytes.toLong() }
                val verts = f.terrain.sumOf { it.vertexCount.toLong() } + f.splats.sumOf { it.count.toLong() }
                repeat(10) { builder.build(w, cam, emptyList(), WorldTime(dayFraction = 0.4f)).release() }
                val t = System.nanoTime()
                repeat(30) { builder.build(w, cam, emptyList(), WorldTime(dayFraction = 0.4f)).release() }
                return Triple(bytes, verts, (System.nanoTime() - t) / 1e6 / 30)
            }
            val m = measure(com.stratum.engine.scene.SplatMode.MESH)
            val s = measure(base.splats)
            sb.appendLine("%-7s %-5s %12.1f MB %6.1f MB %16s %11s %13.2f ms %6.2f ms".format(
                tier.name, base.splatDraw.name.lowercase(), m.first / 1e6, s.first / 1e6, "%,d".format(m.second), "%,d".format(s.second), m.third, s.third,
            ))
        }
        sb.appendLine()
        sb.appendLine("GPU bytes = vertex and index buffers held for the terrain (mesh: ${com.stratum.engine.scene.Vertex.STRIDE} floats a vertex + 4-byte indices; splats: 8 bytes a voxel).")
        sb.appendLine("vertices run = vertex shader invocations per frame for the terrain in view; a splat is one, lit there once for its three faces.")
        sb.appendLine("Splat chunks keep water as a small mesh; blocks beyond the detail rings are meshed either way.")
        sb.appendLine("Desktop JVM numbers; a low-end phone core is roughly 3-6x slower.")
        return sb.toString()
    }

    /**
     * The block column, near [home], with the most glowing voxels (lit
     * windows, lamps) in the chunk around it: where the night shot looks.
     * [home] when the town has none.
     */
    private fun glowNear(gen: MicrovoxelTerrainGenerator, home: Pair<Int, Int>, chunks: Int = 6): Pair<Int, Int> {
        val size = com.stratum.engine.microvoxel.MicroChunk.SIZE
        val layers = com.stratum.core.domain.world.Chunk.HEIGHT / (size / gen.microPerBlock)
        val hx = Math.floorDiv(home.first, 16); val hy = Math.floorDiv(home.second, 16)
        var best = home; var bestScore = 0f
        for (cy in hy - chunks..hy + chunks) for (cx in hx - chunks..hx + chunks) {
            var n = 0; var sx = 0L; var sy = 0L
            for (cz in 0 until layers) {
                val mpos = com.stratum.engine.microvoxel.MicroChunkPos(cx, cy, cz)
                val grid = gen.microChunk(mpos)
                if (grid.isEmpty()) continue
                for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
                    val m = gen.palette[grid[x, y, z]]
                    if (m.emission < 1f) continue
                    // Lamps, braziers and lit windows light a street; a shrine glows only indoors.
                    val k = if ("lamp" in m.name || "brazier" in m.name || "glass" in m.name) 3 else 1
                    n += k; sx += (mpos.originX + x) * k.toLong(); sy += (mpos.originY + y) * k.toLong()
                }
            }
            if (n == 0) continue
            // Many glowing voxels, not too far from home.
            val gx = (sx / n / gen.microPerBlock).toInt(); val gy = (sy / n / gen.microPerBlock).toInt()
            val score = n / (1f + (kotlin.math.abs(gx - home.first) + kotlin.math.abs(gy - home.second)) / 48f)
            if (score > bestScore) { bestScore = score; best = gx to gy }
        }
        println("night vantage $best (glow score ${"%.0f".format(bestScore)})")
        return best
    }

    /** The middle half of an image, doubled, pixel for pixel. */
    private fun zoom(img: BufferedImage): BufferedImage {
        val z = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = z.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(img, 0, 0, img.width, img.height, img.width / 4, img.height / 4, img.width * 3 / 4, img.height * 3 / 4, null)
        g.dispose()
        return z
    }

    /** Parametric home towns, each from a different corner of the genome's space. */
    private val PARAMETRIC_LOOKS: List<Pair<String, Map<String, String>>> = listOf(
        "any" to emptyMap(),
        "lime-domes" to mapOf("materials" to "lime", "roofs" to "dome,onion,flat,terrace", "storeys" to "1-3", "towers" to "0.6", "ornament" to "0.9"),
        "stone-hall" to mapOf("materials" to "stone", "roofs" to "gable,hip,cone,pyramid", "storeys" to "0-1", "ornament" to "0.4"),
        "painted-round" to mapOf("materials" to "painted", "roofs" to "cone,terrace,dome", "plans" to "round,octagon,rect,cross", "ornament" to "1"),
        "brick-mill" to mapOf("materials" to "brick,modern", "roofs" to "sawtooth,flat,mansard,barrel", "storeys" to "2-4", "variety" to "0.8"),
        "earth-courts" to mapOf("materials" to "earth", "roofs" to "flat,terrace", "plans" to "courtyard,u,l,stepped", "towers" to "0.4", "ornament" to "0.8"),
        "timber-town" to mapOf("materials" to "timber", "roofs" to "gable,pyramid,cone,mansard", "storeys" to "1-2", "variety" to "0.9"),
    ) + com.stratum.engine.microvoxel.arch.Traditions.ids.map { "vernacular-$it" to mapOf("homeStyle" to "parametric:$it") }

    /** Builds frames until the background detail meshes are all in, as a player standing still would see. */
    private fun settled(builder: SceneBuilder, build: () -> SceneFrame): SceneFrame {
        var frame = build()
        var waited = 0
        while (builder.detailPending > 0 && waited < 30_000) { Thread.sleep(5); waited += 5; frame = build() }
        return frame
    }

    /** The home town, from a street near its centre: where every new hero starts. */
    private fun homeVantage(gen: MicrovoxelTerrainGenerator): Pair<Int, Int> {
        val home = (gen as? com.stratum.core.domain.settlement.SettlementAtlas)?.settlementsNear(0, 0, 0)?.firstOrNull() ?: return 0 to 0
        val road = home.roads.minByOrNull { r -> kotlin.math.abs(r.fromX + r.toX - 2 * home.centerX) + kotlin.math.abs(r.fromY + r.toY - 2 * home.centerY) }
        return if (road == null) home.centerX to home.centerY else ((road.fromX + road.toX) / 2) to ((road.fromY + road.toY) / 2)
    }

    /** Wooded open land away from town. */
    private fun wildVantage(gen: MicrovoxelTerrainGenerator): Pair<Int, Int> {
        val climate = gen.micro.fields.require(Fields.CLIMATE)
        val footprint = gen.micro.fields.get(Fields.FOOTPRINT)
        val surface = gen.micro.fields.require(Fields.SURFACE)
        val sea = gen.micro.fields.require(Fields.SEA_LEVEL)
        for (d in 1..40) for (k in 0 until 8) {
            val x = (d * 37 * kotlin.math.cos(k * 0.785)).toInt(); val y = (d * 37 * kotlin.math.sin(k * 0.785)).toInt()
            val mx = x * 4; val my = y * 4
            if ((footprint?.urban(mx, my) ?: 0f) > 0f) continue
            if (surface.heightAt(mx, my) < sea + 8) continue
            if (climate.moisture(mx, my) > 0.55f) return x to y
        }
        return 200 to 200
    }

    private fun actorsAround(world: StreamingWorld, x: Int, y: Int): List<SceneActor> {
        fun z(ax: Int, ay: Int) = world.surfaceAt(ax, ay) + 1f
        return listOf(
            SceneActor(x + 0.5f, y + 0.5f, z(x, y), ActorPresentation("p", ActorRole.PLAYER), 1f, -0.3f, spriteKey = "actor:igbo:dike_ozo"),
            SceneActor(x + 4.5f, y - 2.5f, z(x + 4, y - 3), ActorPresentation("m1", ActorRole.ENEMY, EnemyRank.MINION), -1f, 0.5f, spriteKey = "actor:igbo:ogu_brute"),
            SceneActor(x + 5.5f, y + 1.5f, z(x + 5, y + 1), ActorPresentation("e", ActorRole.ENEMY, EnemyRank.ELITE), -1f, -0.4f, spriteKey = "actor:igbo:shadow_leopard"),
        )
    }

    /**
     * What the phone pays, per tier, block mesher against microvoxel detail:
     * the first frame (everything meshed, as on entering a world) and a walk
     * of 96 blocks across chunk borders, one block per frame, where the view
     * keeps meshing new ground -- the frames a player feels.
     */
    private fun benchmark(
        content: AssembledContent, config: WorldConfig, generator: MicrovoxelTerrainGenerator,
        director: StyleSheetArtDirector, textures: TextureLibrary, start: Pair<Int, Int>,
    ): String {
        val sb = StringBuilder()
        val cores = Runtime.getRuntime().availableProcessors()
        sb.appendLine("Microvoxel ARPG benchmark (seed ${config.seed}, this JVM, $cores cores)")
        sb.appendLine()

        // Generation: fresh chunks, as streaming meets them.
        val fresh = MicrovoxelTerrainGenerator(content.terrainContext(config))
        val n = 64
        for (i in 0 until n) fresh.generate(ChunkPos(-300 + i % 8, -300 + i / 8), content.registry) // warm the JIT
        val t0 = System.nanoTime()
        for (i in 0 until n) fresh.generate(ChunkPos(40 + i % 8, 40 + i / 8), content.registry)
        val genMs = (System.nanoTime() - t0) / 1e6 / n
        val layered = StratumTerrain.create(content.copy(terrain = TerrainRecipe()).terrainContext(config))
        for (i in 0 until n) layered.generate(ChunkPos(-300 + i % 8, -300 + i / 8), content.registry)
        val t1 = System.nanoTime()
        for (i in 0 until n) layered.generate(ChunkPos(40 + i % 8, 40 + i / 8), content.registry)
        val layeredMs = (System.nanoTime() - t1) / 1e6 / n
        sb.appendLine("Generation per 16x16x48 block chunk: microvoxel %.1f ms (microvoxels + block conversion) vs the pack's default generator %.1f ms".format(genMs, layeredMs))
        sb.appendLine()
        // Meshing one chunk from microvoxels, at each level of detail, and with the chunk read block by block (the old path).
        run {
            val world = StreamingWorld(content.registry, generator, config)
            world.focusOn(BlockPos(start.first, start.second, 0))
            val chunks = world.loadedChunks.map { it.pos }.take(16)
            val slow = object : com.stratum.engine.microvoxel.MicroTerrainSource by generator {
                override fun generatedChunk(x: Int, y: Int): ShortArray? = null
            }
            fun time(mesher: com.stratum.engine.scene.MicroDetailMesher, lod: Int): Double {
                repeat(2) { chunks.forEach { mesher.mesh(world, it, lod) } } // warm
                val t = System.nanoTime()
                repeat(3) { chunks.forEach { mesher.mesh(world, it, lod) } }
                return (System.nanoTime() - t) / 1e6 / (3 * chunks.size)
            }
            val fast = com.stratum.engine.scene.MicroDetailMesher(generator)
            sb.appendLine("Meshing one 16x16x48 chunk from microvoxels: full detail %.1f ms (%.1f ms reading blocks one by one), half-block %.1f ms"
                .format(time(fast, 1), time(com.stratum.engine.scene.MicroDetailMesher(slow), 1), time(fast, 2)))
            // The same, scattering surfels as HIGH and ULTRA do; they are made on the meshing threads, never per frame.
            val looks = listOf(QualityTier.HIGH, QualityTier.ULTRA).map { com.stratum.engine.scene.quality.DioramaLook.of(it) }
            val scatter = looks.map { com.stratum.engine.scene.MicroDetailMesher(generator, scatterSurfels = true, surfelDensity = it.surfelDensity) }
            val perChunk = scatter.map { m -> chunks.map { m.mesh(world, it)?.surfels?.count ?: 0 }.average() }
            sb.appendLine("With surfels scattered: HIGH %.1f ms, ULTRA %.1f ms a chunk; %,.0f and %,.0f surfels a chunk (%.0f and %.0f KB)"
                .format(time(scatter[0], 1), time(scatter[1], 1), perChunk[0], perChunk[1], perChunk[0] * 12 / 1024, perChunk[1] * 12 / 1024))
            sb.appendLine()
        }
        sb.appendLine("tier    renderer  detail  look     first frame   walk avg/max per frame   detail catch-up   terrain tris   vertex MB   surfels drawn   surfel MB")
        // Every configuration runs twice and reports the second, so JIT warm-up does not land on whichever ran first.
        for (pass in 0..1) for (tier in QualityTier.values()) {
            val settings = RenderSettings.of(tier)
            // Mode 3 is mode 2 with the diorama finish off: what the finish itself costs the CPU.
            for (mode in 0..3) {
                if (mode == 3 && settings.diorama == com.stratum.engine.scene.quality.DioramaLook.OFF) continue
                val detail = mode > 0
                val tierSettings = when (mode) {
                    1 -> settings.copy(microFarRadius = 0)
                    3 -> settings.copy(diorama = com.stratum.engine.scene.quality.DioramaLook.OFF)
                    else -> settings
                }
                val world = StreamingWorld(content.registry, generator, config)
                world.focusOn(BlockPos(start.first, start.second, 0))
                val builder = SceneBuilder(director, textures, biomeAt = { x, y -> generator.biomeAt(x, y) }, settings = tierSettings, microTerrain = if (detail) generator else null)
                fun frame(x: Int, y: Int): Pair<Double, SceneFrame> {
                    world.focusOn(BlockPos(x, y, 0))
                    val cam = SceneCamera(target = Vec3(x + 0.5f, y + 0.5f, world.surfaceAt(x, y) + 1f), aspect = 16f / 9f)
                    val s = System.nanoTime()
                    val f = builder.build(world, cam, emptyList(), WorldTime(dayFraction = 0.4f))
                    return (System.nanoTime() - s) / 1e6 to f
                }
                val (first, f0) = frame(start.first, start.second)
                var total = 0.0; var worst = 0.0
                for (step in 1..96) {
                    val (ms, _) = frame(start.first + step, start.second + step / 3)
                    total += ms; if (ms > worst) worst = ms
                }
                // How long detail takes to catch up once the hero stops: the time ground stays blocky.
                val stop = System.nanoTime()
                val end = start.first + 96 to start.second + 32
                val settledFrame = settled(builder) { frame(end.first, end.second).second }
                val catchUp = (System.nanoTime() - stop) / 1e6
                val tris = settledFrame.terrain.sumOf { it.triangleCount.toLong() }
                val bytes = settledFrame.terrain.sumOf { it.vertexFloats.toLong() * 4 + it.indexCount.toLong() * 4 }
                val drawn = settledFrame.surfels.sumOf { it.count.toLong() }
                val surfelBytes = settledFrame.residentSurfels.sumOf { it.bytes.toLong() }
                if (pass == 1) sb.appendLine(
                    "%-7s %-9s %-7s %-7s %8.1f ms   %8.2f / %6.1f ms        %8.0f ms      %,11d   %8.1f   %,13d   %8.1f".format(
                        tier.name, when (mode) { 0 -> "blocks"; 1 -> "near"; else -> "all" },
                        when (mode) { 0 -> "-"; 1 -> "${settings.microDetailRadius} blk"; else -> "${settings.microDetailRadius}+${settings.microFarRadius}" },
                        if (tierSettings.diorama == com.stratum.engine.scene.quality.DioramaLook.OFF) "off" else "on",
                        first, total / 96, worst, catchUp, tris, bytes / 1e6, drawn, surfelBytes / 1e6,
                    ),
                )
            }
        }
        sb.appendLine()
        sb.appendLine("first frame = every chunk in view meshed at once (entering a world, behind a loading moment).")
        sb.appendLine("walk = one block per frame diagonally for 96 blocks; the mesh cache paces off-screen chunks.")
        sb.appendLine("near = microvoxels only within the detail ring, blocks beyond; all = true microvoxels to the view's edge, half-block past the ring.")
        sb.appendLine("detail is meshed on background threads; catch-up = time after stopping until every chunk in view shows it.")
        sb.appendLine("look = the diorama finish (DioramaLook) of the tier, or off; surfels are scattered while meshing and thinned per frame to the tier's budget.")
        sb.appendLine("Desktop JVM numbers; a low-end phone core is roughly 3-6x slower.")
        return sb.toString()
    }

    private fun sideBySide(
        a: BufferedImage, b: BufferedImage,
        left: String = "Blocks (the game today)", right: String = "Microvoxel detail near the hero",
    ): BufferedImage {
        val img = BufferedImage(a.width + b.width + 6, a.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(20, 20, 24); g.fillRect(0, 0, img.width, img.height)
        g.drawImage(a, 0, 0, null); g.drawImage(b, a.width + 6, 0, null)
        runCatching {
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18)
            g.color = java.awt.Color.WHITE
            g.drawString(left, 14, 28)
            g.drawString(right, a.width + 20, 28)
        }
        g.dispose()
        return img
    }
}
