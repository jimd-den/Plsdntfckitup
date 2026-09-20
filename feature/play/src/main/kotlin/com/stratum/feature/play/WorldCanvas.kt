package com.stratum.feature.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.sprite.ProceduralMotion
import com.stratum.core.domain.sprite.SpriteFacing
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.WeaponLayer
import com.stratum.core.domain.sprite.WeaponRig
import com.stratum.core.domain.sprite.WeaponSprite
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.FeedbackKind
import com.stratum.engine.world.FeedbackMark
import com.stratum.engine.world.GroundInsert
import com.stratum.engine.world.GroundLoot
import com.stratum.engine.world.IsometricProjection

/**
 * Draws the voxel world in isometric projection.
 *
 * The renderer is a pure function of the world and the camera. It holds no state
 * and mutates nothing, so a frame can be reasoned about entirely from its inputs.
 */
@Composable
fun WorldCanvas(
    world: World,
    camera: WorldPoint,
    projection: IsometricProjection,
    highlight: BlockPos?,
    playerPosition: WorldPoint,
    playerFacing: Direction = Direction.SOUTH,
    playerAccent: Color = PLAYER_RING,
    enemies: List<EnemyInstance> = emptyList(),
    groundLoot: List<GroundLoot> = emptyList(),
    groundInserts: List<GroundInsert> = emptyList(),
    /** Resolves a dropped insert's colour; nulls fall back to a neutral tint. */
    insertColor: (String) -> Long? = { null },
    /** Resolves a dropped insert's glyph, so a rune on the ground reads as one. */
    insertGlyph: (String) -> String? = { null },
    feedback: List<FeedbackMark> = emptyList(),
    /** 0..1, how recently the player was hit. Drives the hurt tint. */
    playerFlash: Float = 0f,
    /** Rolling players are drawn flattened and trailing. */
    isRolling: Boolean = false,
    isInvulnerable: Boolean = false,
    flashFor: (String) -> Float = { 0f },
    /** How hard an actor is being knocked back, 0..1. Drives the recoil. */
    impactFor: (String) -> Float = { 0f },
    /**
     * Supplies the drawn sheet for an actor, or null to fall back to shapes.
     * Every actor without art still renders, which is what lets sprites arrive
     * one generation at a time rather than all or nothing.
     */
    spriteFor: (SpriteKey) -> DrawableSprite? = { null },
    playerAnimation: AnimationPlayback = AnimationPlayback(),
    animationFor: (String) -> AnimationPlayback = { AnimationPlayback() },
    /** Cells a pending build would fill, drawn as a ghost before committing. */
    buildPreview: List<BlockPos> = emptyList(),
    buildAffordable: Boolean = true,
    buildMode: Boolean = false,
    onBuildDrag: (from: BlockPos, to: BlockPos) -> Unit = { _, _ -> },
    onBuildCommit: () -> Unit = {},
    /** Redrawn whenever this changes; the world itself is mutable and not a Compose state. */
    revision: Int,
    /** Changes every frame while a fight is running, to force a redraw. */
    frame: Int = 0,
    modifier: Modifier = Modifier,
    onTapBlock: (BlockPos) -> Unit = {},
    onLongPressBlock: (BlockPos) -> Unit = {},
) {
    Canvas(
        modifier = modifier
            .pointerInput(buildMode, projection, revision, world) {
                if (!buildMode) return@pointerInput
                // Build drags are their own gesture: mining and building share a
                // surface, and a drag that both dug and built would be unusable.
                var anchor: BlockPos? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        anchor = pick(world, projection, camera, size.width.toFloat(), size.height.toFloat(), offset)
                        anchor?.let { onBuildDrag(it, it) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val start = anchor ?: return@detectDragGestures
                        pick(
                            world, projection, camera,
                            size.width.toFloat(), size.height.toFloat(), change.position,
                        )?.let { onBuildDrag(start, it) }
                    },
                    onDragEnd = {
                        onBuildCommit()
                        anchor = null
                    },
                    onDragCancel = { anchor = null },
                )
            }
            .pointerInput(projection, revision, world) {
            detectTapGestures(
                onTap = { offset ->
                    pick(world, projection, camera, size.width.toFloat(), size.height.toFloat(), offset)
                        ?.let(onTapBlock)
                },
                onLongPress = { offset ->
                    pick(world, projection, camera, size.width.toFloat(), size.height.toFloat(), offset)
                        ?.let(onLongPressBlock)
                },
            )
        },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        revision
        @Suppress("UNUSED_EXPRESSION")
        frame

        val originX = size.width / 2f - projection.project(camera).x
        val originY = size.height / 2f - projection.project(camera).y

        // Ground at the player's own height is fully lit; everything lower is
        // shaded towards it, so depth reads relative to where you are standing
        // rather than to an absolute sea level you cannot see.
        val eyeLevel = kotlin.math.floor(camera.z).toInt()

        val range = projection.visibleRange(size.width, size.height, originX, originY)

        // Allocated once and rewound per block. A path per face per block per
        // frame was tens of thousands of short-lived objects a second, and the
        // collector spent more time on them than the renderer did drawing.
        val faces = BlockFaces()

        range.forEachColumnInDrawOrder { x, y ->
            val surface = world.surfaceAt(x, y)
            if (surface < 0) return@forEachColumnInDrawOrder

            // Draw a few levels below the surface so cliff faces have sides
            // rather than floating tops.
            val floor = maxOf(0, surface - VISIBLE_DEPTH)

            // The range is a generous box; this is the exact test. Without it
            // every loaded column is drawn, on screen or not.
            if (!projection.isColumnOnScreen(
                    x, y, surface, floor,
                    originX, originY, size.width, size.height,
                )
            ) {
                return@forEachColumnInDrawOrder
            }

            // Props stand on the ground but are not the ground. Shading and
            // ledge shadows read the terrain beneath them, or a tree would make
            // its own column look like a cliff.
            var ground = surface
            while (ground > 0 && world.blockAt(BlockPos(x, y, ground)).glyph != null) ground--

            // How far below the player this column sits. Lower ground is drawn
            // darker, which is the whole of "am I down a level" — without it an
            // isometric field of one material reads as a flat UI background no
            // matter how much geometry is in it.
            val depthBelow = (eyeLevel - ground).coerceIn(0, DEPTH_SHADE_RANGE)
            val depthShade = 1f - depthBelow.toFloat() / DEPTH_SHADE_RANGE * DEPTH_SHADE_STRENGTH

            // A ledge casts onto the cell in front of it. Two heightmap lookups,
            // and it is what turns a plateau edge into something you can see.
            val shadowed = world.surfaceAt(x - 1, y) > ground || world.surfaceAt(x, y - 1) > ground

            // Deterministic per-cell jitter. A large plain of one block is
            // perfectly uniform otherwise, which reads as paper, not ground.
            val grain = 1f + (((x * 73856093) xor (y * 19349663)) and 0xFF) / 255f * GRAIN - GRAIN / 2f

            // The column's prop, if any: drawn once at the top of its run, so a
            // four-block trunk is one tree rather than four stacked emoji.
            var propGlyph: String? = null
            var propScale = 1f
            var propAt = 0

            for (z in floor..surface) {
                val pos = BlockPos(x, y, z)
                val block = world.blockAt(pos)
                if (block.isAir) continue

                val glyph = block.glyph
                if (glyph != null) {
                    propGlyph = glyph
                    propScale = block.glyphScale
                    propAt = z
                    continue
                }

                // Fully buried blocks are invisible; skipping them is the single
                // biggest saving in the draw loop.
                if (z < ground && isEnclosed(world, pos)) continue

                val isTop = z == ground
                val lit = depthShade * if (isTop) grain else 1f
                val screen = projection.project(pos)
                drawBlock(
                    centerX = originX + screen.x,
                    centerY = originY + screen.y,
                    projection = projection,
                    topColor = Color(block.topColor).scaleRgb(lit),
                    sideColor = Color(block.sideColor).scaleRgb(depthShade),
                    highlighted = pos == highlight,
                    accent = Color(block.accentColor),
                    faces = faces,
                    shadowTop = isTop && shadowed,
                )
            }

            propGlyph?.let { glyph ->
                val screen = projection.project(BlockPos(x, y, propAt))
                drawGlyph(
                    x = originX + screen.x,
                    y = originY + screen.y,
                    projection = projection,
                    glyph = glyph,
                    scale = PROP_GLYPH_SCALE * propScale,
                    shade = depthShade,
                )
            }
        }

        // The ghost sits above terrain but below actors, so the player is never
        // hidden behind their own plan.
        if (buildPreview.isNotEmpty()) {
            val ghost = if (buildAffordable) GHOST_OK else GHOST_SHORT
            buildPreview.sortedBy { projection.depthKey(it) }.forEach { pos ->
                val screen = projection.project(pos)
                drawGhost(originX + screen.x, originY + screen.y, projection, ghost)
            }
        }

        // Actors are drawn after the terrain and sorted among themselves, so a
        // monster standing behind a pillar is still covered by it but a monster
        // in front of another draws over it.
        val actors = buildList {
            groundLoot.forEach { add(Actor.Loot(it)) }
            groundInserts.forEach { add(Actor.Insert(it)) }
            enemies.filter { it.isAlive }.forEach { add(Actor.Monster(it)) }
            add(Actor.Player(playerPosition))
        }.sortedBy { projection.depthKey(it.position) }

        actors.forEach { actor ->
            val screen = projection.project(actor.position)
            val x = originX + screen.x
            val y = originY + screen.y
            when (actor) {
                // A beam in the rarity colour says "loot and how good"; the
                // glyph on top says "and it is an axe". Neither alone answers
                // both questions.
                is Actor.Loot -> {
                    drawLoot(x, y, projection, Color(actor.loot.item.rarity.beamColor()))
                    drawGlyph(x, y, projection, actor.loot.item.glyph, LOOT_GLYPH_SCALE)
                }
                is Actor.Insert -> {
                    drawInsert(
                        x, y, projection,
                        Color(insertColor(actor.ground.insertId) ?: DEFAULT_INSERT_TINT),
                    )
                    insertGlyph(actor.ground.insertId)?.let {
                        drawGlyph(x, y, projection, it, LOOT_GLYPH_SCALE)
                    }
                }
                is Actor.Monster -> {
                    // A struck body dips and squashes for as long as it is
                    // being shoved. Without it a hit is only a number, and a
                    // heavy blow looks exactly like a glancing one.
                    val recoil = impactFor(actor.enemy.instanceId)
                    // Dipped into the blow while it is being shoved.
                    val dip = recoil * RECOIL_DIP * projection.tileHeight * projection.zoom
                    val sprite = spriteFor(SpriteKey.Monster(actor.enemy.definitionId))
                    if (sprite != null) {
                        drawSprite(
                            x, y + dip, projection, sprite,
                            animationFor(actor.enemy.instanceId),
                            // The way it is actually going. This was one fixed
                            // direction for every monster, so a character with
                            // drawn back art still ran at you face-first while
                            // heading north, and the second half of its sheet
                            // was never reached.
                            facingOf(actor.enemy.facingX, actor.enemy.facingY),
                            flashFor(actor.enemy.instanceId),
                        )
                        drawEnemyOverlay(x, y, projection, actor.enemy)
                    } else {
                        drawEnemy(
                            x, y + dip, projection, actor.enemy,
                            flashFor(actor.enemy.instanceId),
                            squash = 1f - recoil * RECOIL_SQUASH,
                        )
                    }
                }
                is Actor.Player -> {
                    val sprite = spriteFor(SpriteKey.Player)
                    if (sprite != null) {
                        drawSprite(
                            x, y, projection, sprite, playerAnimation,
                            SpriteFacing.of(playerFacing.dx, playerFacing.dy),
                            playerFlash,
                        )
                        if (isInvulnerable) {
                            val r = projection.tileWidth * projection.zoom * 0.22f
                            drawCircle(
                                INVULNERABLE_RING,
                                r * 1.9f,
                                Offset(x, y - projection.blockHeight * projection.zoom * 0.5f),
                                style = Stroke(3f),
                            )
                        }
                    } else {
                        drawPlayer(
                            x, y, projection, playerFacing, playerAccent,
                            hurt = playerFlash, rolling = isRolling, invulnerable = isInvulnerable,
                        )
                    }
                }
            }
        }

        // Feedback last and unsorted by depth: a damage number must never be
        // hidden behind the thing it refers to.
        feedback.sortedBy { it.id }.forEach { mark ->
            val screen = projection.project(mark.origin)
            drawFeedback(originX + screen.x, originY + screen.y, projection, mark)
        }
    }
}

/** Anything drawn on top of the terrain, so they can be depth sorted together. */
private sealed interface Actor {
    val position: WorldPoint

    data class Player(override val position: WorldPoint) : Actor
    data class Monster(val enemy: EnemyInstance) : Actor {
        override val position: WorldPoint get() = enemy.position
    }
    data class Loot(val loot: GroundLoot) : Actor {
        override val position: WorldPoint get() = loot.position
    }
    data class Insert(val ground: GroundInsert) : Actor {
        override val position: WorldPoint get() = ground.position
    }
}

/**
 * One cell of a pending build: the top face outlined and washed, so the shape
 * reads without hiding the ground it will sit on.
 */
private fun DrawScope.drawGhost(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    tint: Color,
) {
    val halfWidth = projection.tileWidth * projection.zoom / 2f
    val halfHeight = projection.tileHeight * projection.zoom / 2f

    val lift = projection.blockHeight * projection.zoom

    // The whole cube, not just its lid. A room's walls are stacked cells, and
    // drawing only top faces made a wall look like a floating grid rather than
    // something with height.
    val left = Path().apply {
        moveTo(x - halfWidth, y)
        lineTo(x, y + halfHeight)
        lineTo(x, y + halfHeight + lift)
        lineTo(x - halfWidth, y + lift)
        close()
    }
    val right = Path().apply {
        moveTo(x + halfWidth, y)
        lineTo(x, y + halfHeight)
        lineTo(x, y + halfHeight + lift)
        lineTo(x + halfWidth, y + lift)
        close()
    }
    val top = Path().apply {
        moveTo(x, y - halfHeight)
        lineTo(x + halfWidth, y)
        lineTo(x, y + halfHeight)
        lineTo(x - halfWidth, y)
        close()
    }

    drawPath(left, tint.copy(alpha = 0.16f))
    drawPath(right, tint.copy(alpha = 0.10f))
    drawPath(top, tint.copy(alpha = 0.30f))
    drawPath(top, tint, style = Stroke(width = 2f))
}

/**
 * Draws one frame of a sprite sheet, sized to the world grid.
 *
 * Frames are cut with nearest-neighbour filtering: sprite art is pixel art, and
 * smoothing it on scale-up is the difference between crisp and mushy.
 */
private fun DrawScope.drawSprite(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    sprite: DrawableSprite,
    playback: AnimationPlayback,
    facing: SpriteFacing,
    flash: Float,
) {
    val sheet = sprite.sheet
    val clip = sheet.clipOrFallback(playback.state)
    val frame = sheet.frameFor(playback.frameIn(sheet), facing)
    val rect = sheet.frameRect(frame)

    // Sized by height, not width.
    //
    // Width was the anchor while every frame was square, and it stopped being
    // safe the moment frames were cut to the figure: a lunging character
    // packs into a wide cell and a standing one into a narrow cell, so
    // anchoring on width made the same person a different height in the world
    // depending on which poses their sheet happened to contain. Height is
    // what a viewer reads as scale -- a character is "about two tiles tall"
    // -- and it is the measurement that stays put across a whole set.
    val baseHeight = projection.tileWidth * projection.zoom * SPRITE_HEIGHT_TILES
    val baseWidth = baseHeight * (rect.width.toFloat() / rect.height.coerceAtLeast(1))

    // Motion the art does not supply. A clip playing frames drawn for another
    // state, or a still held as an animation, gets the difference made up here;
    // a real animation is left alone, because a drawn attack does not need a
    // procedural lunge fighting it.
    val motion = ProceduralMotion.forFrame(
        state = playback.state,
        elapsedMs = playback.elapsedMs,
        clipDurationMs = clip?.durationMs ?: 0,
        frameCount = clip?.frameCount ?: 1,
        standsIn = clip == null || clip.state != playback.state || clip.standsInFor != null,
    )

    val drawWidth = baseWidth * motion.scaleX
    val drawHeight = baseHeight * motion.scaleY
    // Anchored at the feet rather than the centre, so a tall sprite grows
    // upward instead of sinking into the ground -- and so a body that sags as
    // it dies keeps its contact with the floor while it does.
    val groundY = y + projection.tileHeight * projection.zoom * 0.25f
    val left = (x - drawWidth / 2f + motion.offsetX * baseWidth).toInt()
    val top = (groundY - drawHeight + motion.offsetY * baseHeight).toInt()

    // A contact shadow, drawn before the body and sized from the body.
    //
    // The fallback shape has had one all along and the real art never did,
    // which is most of why a drawn character read as a sticker on the world
    // rather than as something standing in it: with nothing under the feet
    // there is no evidence the feet are on the ground, and the eye reads the
    // figure as floating in front of the scene.
    //
    // Width comes from the sprite rather than from the tile, so a wide stance
    // casts a wide shadow, and it tightens as the body leaves the ground --
    // a roll or a death lifts off, and a shadow that stayed the same size
    // through that would nail the character to the floor it is leaving.
    val lifted = ((groundY - (top + drawHeight)) / drawHeight).coerceIn(0f, 1f)
    val shadowWidth = drawWidth * SHADOW_WIDTH * (1f - lifted * 0.45f)
    drawOval(
        color = Color.Black.copy(alpha = SHADOW_ALPHA * (1f - lifted * 0.6f)),
        topLeft = Offset(x - shadowWidth / 2f, groundY - shadowWidth * SHADOW_SQUASH / 2f),
        size = Size(shadowWidth, shadowWidth * SHADOW_SQUASH),
    )

    val blit: (Float, ColorFilter?) -> Unit = { alpha, tint ->
        drawImage(
            image = sprite.image,
            srcOffset = IntOffset(rect.left, rect.top),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt()),
            filterQuality = FilterQuality.None,
            alpha = alpha,
            colorFilter = tint,
        )
    }

    val weapon = sprite.weapon
    val anchor = weapon?.rig?.anchorFor(frame)
    val blitWeapon: (Float) -> Unit = { alpha ->
        if (weapon != null && anchor != null) {
            // The weapon is sized against the character rather than against its
            // own drawing, so a dagger and a spear read as a dagger and a spear
            // whatever canvases they happened to be generated on.
            val height = drawHeight * weapon.sprite.kind.reach * anchor.scale
            val width = height *
                (weapon.image.width.toFloat() / weapon.image.height.coerceAtLeast(1))
            val handX = left + anchor.xFraction * drawWidth
            val handY = top + anchor.yFraction * drawHeight

            // Rotated about the grip, not about the middle of the drawing: a
            // sword turns in the hand, and pivoting anywhere else swings the
            // hilt out of the fist on every frame of an attack.
            withTransform({ rotate(anchor.rotationDegrees, Offset(handX, handY)) }) {
                drawImage(
                    image = weapon.image,
                    dstOffset = IntOffset(
                        (handX - weapon.sprite.gripX * width).toInt(),
                        (handY - weapon.sprite.gripY * height).toInt(),
                    ),
                    dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                    alpha = alpha,
                )
            }
        }
    }

    // Order is the whole difference between a weapon that is held and one that
    // is stuck on. A wind-up goes behind the shoulder and a strike comes across
    // the front; getting it backwards is instantly legible as wrong even to
    // someone who could not say why.
    val paint: () -> Unit = {
        if (anchor?.layer == WeaponLayer.BEHIND) blitWeapon(motion.alpha)
        blit(motion.alpha, null)
        if (anchor?.layer == WeaponLayer.IN_FRONT) blitWeapon(motion.alpha)
        if (flash > 0f) blit(flash * 0.75f * motion.alpha, ColorFilter.tint(Color.White))
    }

    // A generated sheet reliably holds one facing, not four. Mirroring buys the
    // other side for nothing and reads correctly at this camera angle, which is
    // more dependable than asking a model for four consistent angles. Art that
    // says not to -- anything with a readable asymmetry on it -- keeps its one
    // drawn angle in every direction instead.
    //
    // The mirror is taken about the actor's own position, which is also what
    // turns the embellishment's forward offset into a real forward: a lunge
    // reflects along with the body it belongs to.
    val mirrored = facing.mirrored && sheet.mirrorsFacings
    if (mirrored) {
        // The weapon is painted inside the mirror with the body, so a character
        // facing the other way holds it in the other hand for free.
        withTransform({
            scale(scaleX = -1f, scaleY = 1f, pivot = Offset(x, top + drawHeight / 2f))
        }) {
            paint()
        }
    } else {
        paint()
    }
}

/** Health bar and rank ring, drawn over a sprite that has no such affordances. */
private fun DrawScope.drawEnemyOverlay(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    enemy: EnemyInstance,
) {
    if (enemy.healthFraction >= 1f) return
    val scale = projection.tileWidth * projection.zoom
    val barWidth = scale * 0.42f
    val barTop = y - scale * 0.95f

    drawRect(
        color = Color.Black.copy(alpha = 0.6f),
        topLeft = Offset(x - barWidth / 2f, barTop),
        size = Size(barWidth, HEALTH_BAR_HEIGHT),
    )
    drawRect(
        color = ENEMY_HEALTH,
        topLeft = Offset(x - barWidth / 2f, barTop),
        size = Size(barWidth * enemy.healthFraction, HEALTH_BAR_HEIGHT),
    )
}

/**
 * A monster, with a health bar above it once it has been hurt.
 *
 * The bar only appears after the first hit: showing a full bar over every
 * monster turns the screen into a spreadsheet.
 */
private fun DrawScope.drawEnemy(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    enemy: EnemyInstance,
    flash: Float = 0f,
    /** Under 1 while the body is being knocked back, so a hit flattens it. */
    squash: Float = 1f,
) {
    val scale = projection.tileWidth * projection.zoom
    val radius = scale * 0.16f * enemy.rank.sizeMultiplier()
    val lift = projection.blockHeight * projection.zoom * 0.5f

    drawOval(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(x - radius, y - radius * 0.5f),
        size = Size(radius * 2f, radius),
    )
    // Lerping toward white rather than overlaying keeps the silhouette
    // readable at the moment of impact, which is when it matters most.
    val body = Color(enemy.bodyColor)
    val lit = Color(
        red = body.red + (1f - body.red) * flash,
        green = body.green + (1f - body.green) * flash,
        blue = body.blue + (1f - body.blue) * flash,
        alpha = 1f,
    )
    val swollen = radius * (1f + flash * HIT_SWELL)
    // Squashed vertically rather than scaled down: a body absorbing a blow
    // widens as it compresses, which is what makes the hit look like weight
    // rather than the monster simply getting smaller.
    drawOval(
        color = lit,
        topLeft = Offset(x - swollen, y - lift - swollen * squash),
        size = Size(swollen * 2f, swollen * 2f * squash),
    )
    drawOval(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(x - radius, y - lift - radius * squash),
        size = Size(radius * 2f, radius * 2f * squash),
        style = Stroke(1.5f),
    )

    // Rank ring, so an elite is legible at a glance rather than by its name.
    enemy.rank.ringColor()?.let { ring ->
        drawCircle(Color(ring), radius * 1.35f, Offset(x, y - lift), style = Stroke(2f))
    }

    if (enemy.healthFraction < 1f) {
        val barWidth = radius * 2.4f
        val barTop = y - lift - radius - scale * 0.14f
        drawRect(
            color = Color.Black.copy(alpha = 0.6f),
            topLeft = Offset(x - barWidth / 2f, barTop),
            size = Size(barWidth, HEALTH_BAR_HEIGHT),
        )
        drawRect(
            color = ENEMY_HEALTH,
            topLeft = Offset(x - barWidth / 2f, barTop),
            size = Size(barWidth * enemy.healthFraction, HEALTH_BAR_HEIGHT),
        )
    }
}

/** A dropped item: a small diamond under a coloured beam in its rarity colour. */
private fun DrawScope.drawLoot(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    color: Color,
) {
    val scale = projection.tileWidth * projection.zoom
    val size = scale * 0.11f

    drawRect(
        color = color.copy(alpha = 0.25f),
        topLeft = Offset(x - size * 0.35f, y - scale * 0.9f),
        size = Size(size * 0.7f, scale * 0.9f),
    )
    val diamond = Path().apply {
        moveTo(x, y - size)
        lineTo(x + size, y)
        lineTo(x, y + size)
        lineTo(x - size, y)
        close()
    }
    drawPath(diamond, color)
    drawPath(diamond, Color.Black.copy(alpha = 0.6f), style = Stroke(1.5f))
}

/** A dropped insert: a small bright bead, under a short beam of its own colour. */
private fun DrawScope.drawInsert(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    color: Color,
) {
    val scale = projection.tileWidth * projection.zoom
    val radius = scale * 0.07f

    drawRect(
        color = color.copy(alpha = 0.22f),
        topLeft = Offset(x - radius * 0.4f, y - scale * 0.45f),
        size = Size(radius * 0.8f, scale * 0.45f),
    )
    drawCircle(color, radius, Offset(x, y))
    drawCircle(Color.Black.copy(alpha = 0.55f), radius, Offset(x, y), style = Stroke(1.5f))
}

/** Bigger ranks are literally bigger, which reads faster than any label. */
private fun com.stratum.core.domain.actor.EnemyRank.sizeMultiplier(): Float = when (this) {
    com.stratum.core.domain.actor.EnemyRank.MINION -> 1f
    com.stratum.core.domain.actor.EnemyRank.ELITE -> 1.25f
    com.stratum.core.domain.actor.EnemyRank.CHAMPION -> 1.5f
    com.stratum.core.domain.actor.EnemyRank.BOSS -> 1.9f
}

private fun com.stratum.core.domain.actor.EnemyRank.ringColor(): Long? = when (this) {
    com.stratum.core.domain.actor.EnemyRank.MINION -> null
    com.stratum.core.domain.actor.EnemyRank.ELITE -> 0xFF42A5F5
    com.stratum.core.domain.actor.EnemyRank.CHAMPION -> 0xFFFFCA28
    com.stratum.core.domain.actor.EnemyRank.BOSS -> 0xFFFF7043
}

private fun com.stratum.core.domain.item.ItemRarity.beamColor(): Long = when (this) {
    com.stratum.core.domain.item.ItemRarity.COMMON -> 0xFFB0BEC5
    com.stratum.core.domain.item.ItemRarity.UNCOMMON -> 0xFF42A5F5
    com.stratum.core.domain.item.ItemRarity.RARE -> 0xFFFFCA28
    com.stratum.core.domain.item.ItemRarity.EPIC -> 0xFFFF7043
    com.stratum.core.domain.item.ItemRarity.RELIC -> 0xFF26A69A
}

private const val DEFAULT_INSERT_TINT = 0xFF7FD4E0L

private val ENEMY_HEALTH = Color(0xFFD2544B)
private const val HEALTH_BAR_HEIGHT = 3f

private fun pick(
    world: World,
    projection: IsometricProjection,
    camera: WorldPoint,
    width: Float,
    height: Float,
    offset: Offset,
): BlockPos? {
    val originX = width / 2f - projection.project(camera).x
    val originY = height / 2f - projection.project(camera).y
    return projection.pickColumn(
        screenX = offset.x - originX,
        screenY = offset.y - originY,
        isSolidAt = { pos -> !world.blockAt(pos).isAir },
        maxZ = Chunk.HEIGHT - 1,
    )
}

/** A block is invisible when every face that could be seen is covered. */
private fun isEnclosed(world: World, pos: BlockPos): Boolean =
    !world.blockAt(pos.above()).isAir &&
        !world.blockAt(BlockPos(pos.x + 1, pos.y, pos.z)).isAir &&
        !world.blockAt(BlockPos(pos.x, pos.y + 1, pos.z)).isAir

/**
 * One block: the top rhombus plus the two side faces the camera can see.
 *
 * Sides are drawn at fixed brightness rather than from a light source, which
 * keeps blocks readable at a glance and costs nothing per frame.
 */
private fun DrawScope.drawBlock(
    centerX: Float,
    centerY: Float,
    projection: IsometricProjection,
    topColor: Color,
    sideColor: Color,
    accent: Color,
    highlighted: Boolean,
    faces: BlockFaces,
    /** True when a taller neighbour is casting onto this cell. */
    shadowTop: Boolean = false,
) {
    val halfWidth = projection.tileWidth * projection.zoom / 2f
    val halfHeight = projection.tileHeight * projection.zoom / 2f
    val lift = projection.blockHeight * projection.zoom

    faces.shapeFor(centerX, centerY, halfWidth, halfHeight, lift)

    drawPath(faces.left, sideColor.scaleRgb(LEFT_FACE_SHADE))
    drawPath(faces.right, sideColor.scaleRgb(RIGHT_FACE_SHADE))
    drawPath(faces.top, topColor)

    if (shadowTop) {
        drawPath(faces.top, LEDGE_SHADOW)
    }

    // A seam on every top face. Individually almost invisible; together they
    // are what makes a field of tiles read as cells you could dig or build on
    // rather than as one painted surface.
    drawPath(faces.top, TILE_SEAM, style = Stroke(width = 1f))

    if (highlighted) {
        // A target you can actually find. The old wash was the same value as
        // the terrain under it, so the cell you were about to act on was
        // indistinguishable from the ones you were not.
        drawPath(faces.top, TARGET_FILL)
        drawPath(faces.top, TARGET_EDGE, style = Stroke(width = 4f))
        drawPath(faces.top, accent, style = Stroke(width = 2f))
    }
}

/**
 * The three faces of a block, reused across every block in a frame.
 *
 * A block is always the same six-sided shape in a different place, so the paths
 * are rewound and refilled rather than rebuilt. At a thousand-odd blocks a frame
 * and sixty frames a second, allocating them was the single largest source of
 * garbage in the app.
 */
private class BlockFaces {
    val top = Path()
    val left = Path()
    val right = Path()

    fun shapeFor(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float, lift: Float) {
        top.rewind()
        top.moveTo(cx, cy - halfHeight)
        top.lineTo(cx + halfWidth, cy)
        top.lineTo(cx, cy + halfHeight)
        top.lineTo(cx - halfWidth, cy)
        top.close()

        left.rewind()
        left.moveTo(cx - halfWidth, cy)
        left.lineTo(cx, cy + halfHeight)
        left.lineTo(cx, cy + halfHeight + lift)
        left.lineTo(cx - halfWidth, cy + lift)
        left.close()

        right.rewind()
        right.moveTo(cx + halfWidth, cy)
        right.lineTo(cx, cy + halfHeight)
        right.lineTo(cx, cy + halfHeight + lift)
        right.lineTo(cx + halfWidth, cy + lift)
        right.close()
    }
}

/**
 * The player.
 *
 * Deliberately larger than any monster and ringed in the pack accent, with a
 * wedge showing which way they face. In a crowd of monsters the one thing that
 * must never be ambiguous is which dot is you.
 */
private fun DrawScope.drawPlayer(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    facing: Direction = Direction.SOUTH,
    accent: Color = PLAYER_RING,
    hurt: Float = 0f,
    rolling: Boolean = false,
    invulnerable: Boolean = false,
) {
    val scale = projection.tileWidth * projection.zoom
    // Deliberately larger than any monster. In a field of coloured markers the
    // one thing that must never be ambiguous is which one is you, and at the
    // old size the player read as one more dot among the enemies.
    val radius = scale * PLAYER_RADIUS
    val lift = projection.blockHeight * projection.zoom * 0.5f
    val center = Offset(x, y - lift)

    // A tight, dark contact shadow: without one the player floats above the
    // terrain instead of standing on it.
    drawOval(
        color = Color.Black.copy(alpha = 0.55f),
        topLeft = Offset(x - radius * 0.9f, y - radius * 0.45f),
        size = Size(radius * 1.8f, radius * 0.9f),
    )

    drawCircle(accent.copy(alpha = 0.25f), radius * 1.5f, center)

    // Taking a hit washes the body red; a roll squashes it, which reads as
    // ducking without needing an animation frame.
    val body = Color(
        red = PLAYER_BODY.red + (1f - PLAYER_BODY.red) * 0f + hurt * (1f - PLAYER_BODY.red) * 0f,
        green = PLAYER_BODY.green * (1f - hurt * 0.55f),
        blue = PLAYER_BODY.blue * (1f - hurt * 0.55f),
        alpha = 1f,
    )
    val squash = if (rolling) ROLL_SQUASH else 1f
    drawOval(
        color = body,
        topLeft = Offset(center.x - radius, center.y - radius * squash),
        size = Size(radius * 2f, radius * 2f * squash),
    )
    // A heavy outline is most of the silhouette: it holds the shape against
    // both bright grass and dark stone without needing two palettes.
    drawCircle(PLAYER_EDGE, radius, center, style = Stroke(4f))

    // An i-frame ring: the player needs to know the window is still open.
    if (invulnerable) {
        drawCircle(INVULNERABLE_RING, radius * 1.9f, center, style = Stroke(3f))
    }
    drawCircle(accent, radius * 1.5f, center, style = Stroke(2f))

    // Facing wedge, projected onto the isometric axes so "east" points where
    // east actually is on screen rather than to the right of the screen.
    val tip = projection.project(
        facing.dx.toFloat() * FACING_REACH,
        facing.dy.toFloat() * FACING_REACH,
        0f,
    )
    val origin = projection.project(0f, 0f, 0f)
    val dx = tip.x - origin.x
    val dy = tip.y - origin.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
    val nx = dx / length
    val ny = dy / length

    val wedge = Path().apply {
        moveTo(center.x + nx * radius * 2.1f, center.y + ny * radius * 2.1f)
        lineTo(center.x + (-ny) * radius * 0.5f, center.y + nx * radius * 0.5f)
        lineTo(center.x + ny * radius * 0.5f, center.y + (-nx) * radius * 0.5f)
        close()
    }
    drawPath(wedge, accent)
}

/**
 * One floating mark: rises, drifts and fades over its lifetime.
 *
 * Drawn with a dark backing pass so a number stays readable over pale terrain
 * without needing a panel behind it.
 */
private fun DrawScope.drawFeedback(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    mark: FeedbackMark,
) {
    val progress = mark.progress
    val scale = projection.tileWidth * projection.zoom

    // Ease out: fast at the moment of the hit, settling as it fades.
    val rise = (1f - (1f - progress) * (1f - progress)) * scale * RISE_FRACTION
    val alpha = (1f - progress * progress).coerceIn(0f, 1f)
    val size = scale * BASE_TEXT_FRACTION * mark.emphasis

    // Marks born in the same instant would otherwise stack pixel-perfect and
    // read as one number. Spread them by id: deterministic, so a replay draws
    // the same frame, and enough to separate a nova's worth of hits.
    val spread = ((mark.id % SPREAD_BUCKETS) - SPREAD_BUCKETS / 2) * scale * SPREAD_FRACTION
    val cx = x + spread
    val cy = y - scale * 0.5f - rise - (mark.id % 3) * scale * 0.06f

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        paint.color = android.graphics.Color.argb((alpha * 200).toInt().coerceIn(0, 255), 0, 0, 0)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = size * 0.16f
        drawText(mark.text, cx, cy, paint)

        val base = mark.color.toInt()
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            android.graphics.Color.red(base),
            android.graphics.Color.green(base),
            android.graphics.Color.blue(base),
        )
        drawText(mark.text, cx, cy, paint)
    }
}

/**
 * An emoji standing on a cell, with a contact shadow.
 *
 * Emoji are colour fonts, so they cannot be tinted; depth is conveyed by the
 * shadow and by a scrim behind the glyph instead, which keeps a prop on a dark
 * lower terrace from looking like it is floating at the player's level.
 */
private fun DrawScope.drawGlyph(
    x: Float,
    y: Float,
    projection: IsometricProjection,
    glyph: String,
    scale: Float,
    shade: Float = 1f,
) {
    val tile = projection.tileWidth * projection.zoom
    val size = tile * scale
    val baseline = y + size * GLYPH_BASELINE

    // Grounds the prop. Without it an emoji hangs in the air above the tile.
    drawOval(
        color = Color.Black.copy(alpha = 0.35f * shade),
        topLeft = Offset(x - size * 0.3f, y - size * 0.1f),
        size = Size(size * 0.6f, size * 0.26f),
    )

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size
        }
        // A scrim under the glyph so it reads on both bright grass and dark
        // stone, and so depth shading still touches it.
        paint.color = android.graphics.Color.argb(
            ((1f - shade) * 130f).toInt().coerceIn(0, 255), 0, 0, 0,
        )
        paint.style = android.graphics.Paint.Style.FILL
        drawText(glyph, x, baseline, paint)

        paint.color = android.graphics.Color.WHITE
        drawText(glyph, x, baseline, paint)
    }
}

private fun Color.scaleRgb(factor: Float) = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)

/** The south-west face reads as turned away from the light. */
/** Levels below the player before depth shading bottoms out. */
private const val DEPTH_SHADE_RANGE = 8
/** How dark the deepest visible level goes. */
private const val DEPTH_SHADE_STRENGTH = 0.45f
/** Per-cell brightness jitter, so a large plain is not perfectly flat. */
private const val GRAIN = 0.07f
private val LEDGE_SHADOW = Color(0xFF000000).copy(alpha = 0.22f)
private val TILE_SEAM = Color(0xFF000000).copy(alpha = 0.10f)
private const val LEFT_FACE_SHADE = 0.72f
private const val RIGHT_FACE_SHADE = 0.52f
private const val VISIBLE_DEPTH = 6
private const val HIT_SWELL = 0.18f
private const val ROLL_SQUASH = 0.62f
private const val RISE_FRACTION = 0.85f
private const val BASE_TEXT_FRACTION = 0.26f
private const val SPREAD_BUCKETS = 5
private const val SPREAD_FRACTION = 0.16f
/**
 * How tall a character stands, in tile widths.
 *
 * A tile width rather than a tile height because the tile is a diamond: its
 * width is the full footprint and its height is the same footprint squashed by
 * the camera, so measuring against the width is measuring against the ground
 * the character is standing on.
 */
private const val SPRITE_HEIGHT_TILES = 2.15f

/** How much of the sprite's width the shadow spans when standing. */
private const val SHADOW_WIDTH = 0.72f

/** The camera's tilt, near enough: a circle on the ground reads this flat. */
private const val SHADOW_SQUASH = 0.42f
private const val SHADOW_ALPHA = 0.42f

/** Fraction of a tile width. Was 0.22; a player you cannot find is not a player. */
private const val PLAYER_RADIUS = 0.34f
/**
 * Props are drawn at roughly two thirds of a tile.
 *
 * Full tile width was wrong: a prop that covers its own cell hides the terrain
 * it is standing on, and a field of them reads as a texture rather than as
 * objects placed on ground you could dig.
 */
private const val PROP_GLYPH_SCALE = 0.62f
/** Sits the glyph's feet on the cell rather than centring it in the air. */
private const val GLYPH_BASELINE = 0.18f
/** Loot is smaller than scenery: bright and specific, not a landmark. */
private const val LOOT_GLYPH_SCALE = 0.42f
/** How far a struck body dips, as a fraction of a tile. */
private const val RECOIL_DIP = 0.22f
/** How much a struck body flattens at full force. */
private const val RECOIL_SQUASH = 0.3f
private val TARGET_FILL = Color(0xFFFFFFFF).copy(alpha = 0.18f)
private val TARGET_EDGE = Color(0xFF14110E).copy(alpha = 0.85f)
private val INVULNERABLE_RING = Color(0xFF7FD4E0)
private val GHOST_OK = Color(0xFF8FB8DE)
private val GHOST_SHORT = Color(0xFFD2544B)
internal val PLAYER_BODY = Color(0xFFF4EBDC)
internal val PLAYER_EDGE = Color(0xFF14110E)
internal val PLAYER_RING = Color(0xFFCD7F32)
private const val FACING_REACH = 1f

/** Identifies which actor a sprite is wanted for. */
sealed interface SpriteKey {
    data object Player : SpriteKey
    data class Monster(val definitionId: String) : SpriteKey
}

/** A sheet paired with its decoded pixels, ready to draw. */
data class DrawableSprite(
    val sheet: SpriteSheet,
    val image: ImageBitmap,
    /** What this actor is holding, if anything. Drawn separately and attached. */
    val weapon: DrawableWeapon? = null,
)

/**
 * A weapon and where it sits across a sheet.
 *
 * The rig travels with the weapon rather than with the sheet because it is
 * about *this* actor holding *this* weapon: the same sword rigged onto a
 * six-frame attack and a four-frame one needs different anchors, and the sheet
 * has no opinion about whether anything is being held at all.
 */
data class DrawableWeapon(
    val sprite: WeaponSprite,
    val image: ImageBitmap,
    val rig: WeaponRig,
)

/**
 * A direction in world space as one of the four drawn angles.
 *
 * Rounded away from zero rather than truncated: a monster drifting north at a
 * fraction of a tile per frame is still going north, and truncating would call
 * that no movement at all and leave it facing whatever the default is.
 */
private fun facingOf(dx: Float, dy: Float): SpriteFacing = SpriteFacing.of(
    if (dx > 0f) 1 else if (dx < 0f) -1 else 0,
    if (dy > 0f) 1 else if (dy < 0f) -1 else 0,
)
