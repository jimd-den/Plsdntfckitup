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
        renderer.submitTextures(loaded.all)
        surface?.requestRender()
    }

    val builder = remember(input.director, library) {
        SceneBuilder(input.director, library, input.biomeAt)
    }

    val camera = SceneCamera(
        target = Vec3(input.camera.x, input.camera.y, input.camera.z),
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

private fun actorsOf(input: Scene3DInput): List<SceneActor> = buildList {
    add(
        SceneActor(
            input.player.x, input.player.y, input.player.z,
            ActorPresentation("player", ActorRole.PLAYER, accent = input.playerAccent, flash = input.playerFlash),
            input.playerFacingX, input.playerFacingY,
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
            ),
        )
    }
    input.groundLoot.forEach { add(SceneActor(it.position.x, it.position.y, it.position.z, ActorPresentation(it.item.instanceId, ActorRole.LOOT))) }
    input.groundInserts.forEach { add(SceneActor(it.position.x, it.position.y, it.position.z, ActorPresentation(it.insertId, ActorRole.INTERACTABLE))) }
}

private const val BASE_DISTANCE = 34f
private const val TEXT_SIZE = 38f
