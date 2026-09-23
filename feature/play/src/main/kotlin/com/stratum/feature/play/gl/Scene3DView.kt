package com.stratum.feature.play.gl

import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.ScenePicker
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.world.FeedbackMark
import com.stratum.engine.world.GroundInsert
import com.stratum.engine.world.GroundLoot
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.world.WorldPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the 3D view draws, gathered so the screen can pass one thing. */
data class Scene3DInput(
    val camera: WorldPoint,
    val zoom: Float,
    val player: WorldPoint,
    val playerFacingX: Float,
    val playerFacingY: Float,
    val playerAccent: Long?,
    /** The hero class, which names the player's forged sprite. */
    val playerClassId: String? = null,
    val playerFlash: Float,
    val enemies: List<EnemyInstance>,
    val groundLoot: List<GroundLoot>,
    val groundInserts: List<GroundInsert>,
    val feedback: List<FeedbackMark>,
    val flashFor: (String) -> Float,
    val impactFor: (String) -> Float,
    val highlight: BlockPos?,
    val buildPreview: List<BlockPos>,
    val buildAffordable: Boolean,
    val buildMode: Boolean,
    val director: WorldArtDirector,
    val kit: String,
    val time: WorldTime,
    val biomeAt: (Int, Int) -> BiomeDefinition?,
    val revision: Int,
    val frame: Int,
    /** Animated character art, when an actor has any. Null falls back to the stand-in body. */
    val spriteFor: (com.stratum.feature.play.SpriteKey) -> com.stratum.feature.play.DrawableSprite? = { null },
    val playerAnimation: com.stratum.core.domain.sprite.AnimationPlayback = com.stratum.core.domain.sprite.AnimationPlayback(),
    val animationFor: (String) -> com.stratum.core.domain.sprite.AnimationPlayback = { com.stratum.core.domain.sprite.AnimationPlayback() },
)

/**
 * The world in 3D: a GL surface for the scene and a Compose layer on top for
 * touch and floating combat numbers.
 *
 * The scene is built here, on the UI thread, because that is where the world
 * may be read; the GL thread only ever sees finished [com.stratum.engine.scene.SceneFrame]s.
 * Taps are resolved by casting a ray through the same camera that drew the
 * frame, so what the finger is on is what gets dug or built against.
 */
@Composable
fun Scene3DView(
    world: World,
    input: Scene3DInput,
    modifier: Modifier = Modifier,
    onTapBlock: (BlockPos) -> Unit = {},
    onLongPressBlock: (BlockPos) -> Unit = {},
    onBuildDrag: (BlockPos, BlockPos) -> Unit = { _, _ -> },
    onBuildCommit: () -> Unit = {},
) {
    val renderer = remember { SceneGlRenderer() }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var library by remember { mutableStateOf(TextureLibrary()) }
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }

    // The kit is decoded off the main thread; until it arrives the world draws
    // in flat colour, which is still the whole world.
    LaunchedEffect(input.kit) {
        val loaded = withContext(Dispatchers.IO) { ForgedKits.load(input.kit) }
        library = loaded
        renderer.submitTextures(loaded.all, loaded.allMaps)
        surface?.requestRender()
    }

    val builder = remember(input.director, library) {
        SceneBuilder(input.director, library, input.biomeAt)
    }
    val theatre = remember(input.director) { CombatTheatre(input.director) }
    theatre.update(input)

    val camera = SceneCamera(
        // The shake moves the camera's aim, not the world: taps still land
        // where the finger is, because picking uses this same camera.
        target = Vec3(input.camera.x, input.camera.y, input.camera.z) + theatre.track.shake(),
        aspect = size.width.toFloat() / size.height.coerceAtLeast(1),
        distance = (BASE_DISTANCE / input.zoom).coerceIn(SceneCamera.MIN_DISTANCE, SceneCamera.MAX_DISTANCE),
    )

    // One frame per engine tick or world change.
    LaunchedEffect(input.frame, input.revision, builder, size, input.camera, input.zoom) {
        val frame = builder.build(
            world = world,
            camera = camera,
            actors = actorsOf(input),
            time = input.time,
            worldRevision = input.revision,
            ghosts = input.buildPreview,
            ghostsAffordable = input.buildAffordable,
            highlight = input.highlight,
            effects = theatre.track.active,
        )
        renderer.submit(frame)
        surface?.requestRender()
    }

    Box(modifier = modifier.onSizeChanged { size = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    preserveEGLContextOnPause = true
                    surface = this
                }
            },
        )
        DisposableEffect(Unit) { onDispose { surface = null } }

        // Touch and combat text live in Compose, above the GL surface.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(input.buildMode, camera) {
                    if (!input.buildMode) return@pointerInput
                    var anchor: BlockPos? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            anchor = pick(world, camera, offset.x, offset.y, size)
                            anchor?.let { onBuildDrag(it, it) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val start = anchor ?: return@detectDragGestures
                            pick(world, camera, change.position.x, change.position.y, size)?.let { onBuildDrag(start, it) }
                        },
                        onDragEnd = { onBuildCommit(); anchor = null },
                        onDragCancel = { anchor = null },
                    )
                }
                .pointerInput(camera) {
                    detectTapGestures(
                        onTap = { offset -> pick(world, camera, offset.x, offset.y, size)?.let(onTapBlock) },
                        onLongPress = { offset -> pick(world, camera, offset.x, offset.y, size)?.let(onLongPressBlock) },
                    )
                },
        ) {
            // Character art, back to front. Drawn over the lit scene rather
            // than inside it: a sprite sheet is an animation with a weapon rig
            // and a mirror, and the 2D canvas already draws all of that
            // correctly, so the 3D view projects the actor's feet and head
            // through its camera and hands the same drawing the height.
            spritesOf(input).sortedBy { it.depth }.forEach { placed ->
                val feet = ScenePicker.project(camera, placed.x, placed.y, placed.z, this.size.width, this.size.height)
                    ?: return@forEach
                val head = ScenePicker.project(camera, placed.x, placed.y, placed.z + SPRITE_WORLD_HEIGHT * placed.scale, this.size.width, this.size.height)
                    ?: return@forEach
                with(this) {
                    drawActorSpriteFor(placed, feet.first, feet.second, (feet.second - head.second).coerceAtLeast(1f))
                }
            }

            val paint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            input.feedback.forEach { mark ->
                val (x, y) = ScenePicker.project(
                    camera, mark.origin.x, mark.origin.y, mark.origin.z + 1.6f + mark.progress * 0.9f,
                    this.size.width, this.size.height,
                ) ?: return@forEach
                val alpha = ((1f - mark.progress) * 255).toInt().coerceIn(0, 255)
                paint.textSize = TEXT_SIZE * mark.emphasis.coerceIn(0.8f, 1.8f)
                paint.color = android.graphics.Color.argb(alpha, 12, 10, 8)
                drawContext.canvas.nativeCanvas.drawText(mark.text, x + 2f, y + 2f, paint)
                paint.color = (mark.color.toInt() and 0x00FFFFFF) or (alpha shl 24)
                drawContext.canvas.nativeCanvas.drawText(mark.text, x, y, paint)
            }
        }
    }
}

private fun pick(world: World, camera: SceneCamera, x: Float, y: Float, size: IntSize): BlockPos? =
    ScenePicker.pick(world, camera, x, y, size.width.toFloat(), size.height.toFloat())?.block

/** An actor whose art the overlay draws, with what it needs to draw it. */
private class PlacedSprite(
    val x: Float,
    val y: Float,
    val z: Float,
    val sprite: com.stratum.feature.play.DrawableSprite,
    val playback: com.stratum.core.domain.sprite.AnimationPlayback,
    val facing: com.stratum.core.domain.sprite.SpriteFacing,
    val flash: Float,
    val scale: Float,
) {
    /** Painter's order for this camera, which looks towards -x, -y: larger x + y is nearer. */
    val depth: Float get() = x + y
}

private fun spritesOf(input: Scene3DInput): List<PlacedSprite> = buildList {
    input.spriteFor(com.stratum.feature.play.SpriteKey.Player)?.let { sprite ->
        add(
            PlacedSprite(
                input.player.x, input.player.y, input.player.z, sprite, input.playerAnimation,
                com.stratum.core.domain.sprite.SpriteFacing.of(input.playerFacingX.toInt(), input.playerFacingY.toInt()),
                input.playerFlash, PLAYER_SPRITE_SCALE,
            ),
        )
    }
    input.enemies.filter { it.isAlive }.forEach { enemy ->
        val sprite = input.spriteFor(com.stratum.feature.play.SpriteKey.Monster(enemy.definitionId)) ?: return@forEach
        add(
            PlacedSprite(
                enemy.position.x, enemy.position.y, enemy.position.z, sprite,
                input.animationFor(enemy.instanceId),
                com.stratum.feature.play.facingOf(enemy.facingX, enemy.facingY),
                input.flashFor(enemy.instanceId),
                enemy.rank.spriteScale(),
            ),
        )
    }
}

private fun com.stratum.core.domain.actor.EnemyRank.spriteScale(): Float = when (this) {
    com.stratum.core.domain.actor.EnemyRank.MINION -> 1f
    com.stratum.core.domain.actor.EnemyRank.ELITE -> 1.15f
    com.stratum.core.domain.actor.EnemyRank.CHAMPION -> 1.3f
    com.stratum.core.domain.actor.EnemyRank.BOSS -> 1.7f
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActorSpriteFor(
    placed: PlacedSprite,
    x: Float,
    groundY: Float,
    height: Float,
) {
    with(com.stratum.feature.play.SpriteDrawing) {
        draw(x, groundY, height, placed.sprite, placed.playback, placed.facing, placed.flash)
    }
}

private fun actorsOf(input: Scene3DInput): List<SceneActor> = buildList {
    val playerArt = input.spriteFor(com.stratum.feature.play.SpriteKey.Player) != null
    add(
        SceneActor(
            input.player.x, input.player.y, input.player.z,
            ActorPresentation("player", ActorRole.PLAYER, accent = input.playerAccent, flash = input.playerFlash),
            input.playerFacingX, input.playerFacingY,
            spriteKey = input.playerClassId?.let { "actor:$it" },
            drawnElsewhere = playerArt,
        ),
    )
    input.enemies.filter { it.isAlive }.forEach { enemy ->
        add(
            SceneActor(
                enemy.position.x, enemy.position.y, enemy.position.z,
                ActorPresentation(
                    enemy.instanceId, ActorRole.ENEMY, enemy.rank, accent = enemy.bodyColor,
                    flash = input.flashFor(enemy.instanceId), impact = input.impactFor(enemy.instanceId),
                ),
                enemy.facingX, enemy.facingY,
                spriteKey = "actor:${enemy.definitionId}",
                drawnElsewhere = input.spriteFor(com.stratum.feature.play.SpriteKey.Monster(enemy.definitionId)) != null,
            ),
        )
    }
    input.groundLoot.forEach { add(SceneActor(it.position.x, it.position.y, it.position.z, ActorPresentation(it.item.instanceId, ActorRole.LOOT))) }
    input.groundInserts.forEach { add(SceneActor(it.position.x, it.position.y, it.position.z, ActorPresentation(it.insertId, ActorRole.INTERACTABLE))) }
}

private const val BASE_DISTANCE = 34f

/** How tall a character sprite stands, in blocks, before rank scaling. */
private const val SPRITE_WORLD_HEIGHT = 2.1f
private const val PLAYER_SPRITE_SCALE = 1.1f
private const val TEXT_SIZE = 38f
