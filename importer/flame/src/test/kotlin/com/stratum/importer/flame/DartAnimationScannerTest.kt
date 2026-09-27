package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImageRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DartAnimationScannerTest {

    private val scanner = DartAnimationScanner { reference -> "assets/images/${reference.removePrefix("assets/images/")}" }

    @Test
    fun `an image loaded into a variable is followed to its file`() {
        // As Flame's own flame_tiled example writes it.
        val code = """
            final coins = await Flame.images.load('assets/images/coins.png');
            for (final object in objectGroup!.objects) {
              world.add(SpriteAnimationComponent(
                animation: SpriteAnimation.fromFrameData(
                  coins,
                  SpriteAnimationData.sequenced(amount: 8, stepTime: 0.15, textureSize: Vector2.all(20)),
                ),
              ));
            }
        """.trimIndent()

        val scan = scanner.scan(code, "lib/main.dart")
        val coins = scan.animations.single()

        assertEquals(8, coins.frames.size)
        assertEquals(ImageRegion("assets/images/coins.png", 140, 0, 20, 20), coins.frames.last())
        assertTrue(scan.warnings.isEmpty())
    }

    @Test
    fun `entries of an animation map are named by their own key, not the one before`() {
        val code = """
            animations = {
              State.running: await game.loadSpriteAnimation('hero/a.png', SpriteAnimationData.sequenced(amount: 2, stepTime: 0.1, textureSize: Vector2.all(8))),
              State.idle: await game.loadSpriteAnimation('hero/b.png', SpriteAnimationData.sequenced(amount: 3, stepTime: 0.1, textureSize: Vector2.all(8))),
            };
        """.trimIndent()

        val names = scanner.scan(code, "lib/hero.dart").animations.map { AnimationNames.stateFor(it.name) }

        assertEquals(listOf(com.stratum.core.domain.sprite.AnimationState.WALK, com.stratum.core.domain.sprite.AnimationState.IDLE), names)
    }
}
