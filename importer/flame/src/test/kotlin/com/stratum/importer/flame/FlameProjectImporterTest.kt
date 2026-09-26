package com.stratum.importer.flame

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.importer.common.MemoryImportSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlameProjectImporterTest {

    private val importer = FlameProjectImporter()
    private val result = importer.import(FlameFixtures.project())
    private fun sheet(name: String) = result.spriteSheets.single { it.sheet.name == name }

    @Test
    fun `a pubspec that depends on flame is a Flame game`() {
        assertTrue(importer.recognises(FlameFixtures.project()))
        assertFalse(importer.recognises(MemoryImportSource.ofText("app", mapOf("pubspec.yaml" to "name: app\ndependencies:\n  flutter:\n    sdk: flutter"))))
    }

    @Test
    fun `the pack takes its identity from the pubspec`() {
        assertEquals("forest_quest", result.pack.id)
        assertEquals("Forest Quest", result.pack.name)
        assertEquals("0.3.0", result.pack.version)
        assertEquals("A tiny Flame RPG.", result.pack.description)
    }

    @Test
    fun `its Tiled level becomes the world, spawn and all`() {
        assertEquals(TerrainRecipe.TILE_MAP, result.pack.terrain?.generatorId)
        assertEquals(1.5f, result.pack.maps.single().playerSpawn?.x)
        assertNotNull(ContentPackAssembler().assemble(listOf(result.pack)).map(result.pack.maps.single().id))
    }

    @Test
    fun `sequenced animations in Dart become clips, one character per file`() {
        val player = sheet("Player")
        val idle = player.sheet.clip(AnimationState.IDLE)!!
        val walk = player.sheet.clip(AnimationState.WALK)!!

        assertEquals(4, idle.frameCount)
        assertEquals(150, idle.frameDurationMs)
        assertEquals(6, walk.frameCount)
        assertEquals(ImageRegion("assets/images/player/idle.png", 32, 0, 32, 48), player.frames[1])
        // amountPerRow: 3 wraps the fourth run frame onto the second row.
        assertEquals(ImageRegion("assets/images/player/run.png", 0, 32, 32, 32), player.frames[walk.firstFrame + 3])
        assertEquals(48, player.sheet.frameHeight, "cells fit the largest frame")
    }

    @Test
    fun `an animation built from non-literal values is reported, not guessed`() {
        assertEquals(null, sheet("Player").sheet.clip(AnimationState.HURT))
        assertTrue(result.warnings.any { "player.dart" in it && "not literals" in it })
    }

    @Test
    fun `SpriteSheet createAnimation rows become clips`() {
        val slime = sheet("Slime")
        val die = slime.sheet.clip(AnimationState.DIE)!!

        assertEquals(5, slime.sheet.clip(AnimationState.WALK)!!.frameCount)
        assertEquals(3, die.frameCount)
        assertFalse(die.loops, "death plays once")
        assertEquals(ImageRegion("assets/images/slime.png", 24, 16, 24, 16), slime.frames[die.firstFrame])
    }

    @Test
    fun `aseprite tags become clips, ping-pong included, and unknown tags are reported`() {
        val boss = sheet("Boss")
        val idle = boss.sheet.clip(AnimationState.IDLE)!!
        val attack = boss.sheet.clip(AnimationState.ATTACK)!!

        assertEquals(200, idle.frameDurationMs, "durations average")
        assertEquals(4, attack.frameCount, "2,3,4 then back to 3")
        assertEquals(64, boss.frames[attack.firstFrame + 3].y)
        assertEquals(64, boss.frames[attack.firstFrame + 3].x)
        assertTrue(result.warnings.any { "Taunt" in it })
    }

    @Test
    fun `the player character becomes a playable class`() {
        val hero = result.pack.heroClasses.single()

        assertEquals(sheet("Player").sheet.id, hero.spriteSetId)
        assertEquals(hero.spriteSetId, ContentPackAssembler().assemble(listOf(result.pack)).sheetForHero(hero.id)?.id)
    }

    @Test
    fun `a game with characters but no levels still imports`() {
        val charactersOnly = importer.import(FlameFixtures.project(withLevel = false))

        assertTrue(charactersOnly.pack.maps.isEmpty())
        assertEquals(3, charactersOnly.pack.spriteSheets.size)
    }
}
