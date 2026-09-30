package com.stratum.core.domain.art

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleSheetArtDirectorTest {

    private val grass = BlockType(
        id = "test:grass",
        displayName = "Grass",
        material = BlockMaterial.SOIL,
        topColor = 0xFF4E8B45,
        sideColor = 0xFF6B4A2A,
    )

    private val brazier = BlockType(
        id = "test:brazier",
        displayName = "Brazier",
        material = BlockMaterial.RITUAL,
        lightEmission = 12,
        glyph = "*",
        topColor = 0xFFCD7F32,
        sideColor = 0xFF7A4A1E,
        accentColor = 0xFFFFB347,
    )

    private val director = StyleSheetArtDirector()

    @Test
    fun `a cube's three faces are three different values`() {
        // If they are not, the cube is a hexagon and the world is a flat
        // pattern however much geometry is behind it.
        val style = director.terrainStyleFor(TerrainCue(grass))
        assertTrue(Tint.luma(style.top) > Tint.luma(style.left))
        assertTrue(Tint.luma(style.left) > Tint.luma(style.right))
    }

    @Test
    fun `ground further below the camera is drawn darker`() {
        val here = director.terrainStyleFor(TerrainCue(grass, depthBelowEye = 0))
        val pit = director.terrainStyleFor(TerrainCue(grass, depthBelowEye = 6))
        assertTrue(
            Tint.luma(pit.top) < Tint.luma(here.top),
            "a level below has to read as a level below, or the terrain is a background",
        )
    }

    @Test
    fun `depth shading stops rather than running to black`() {
        val deep = director.terrainStyleFor(TerrainCue(grass, depthBelowEye = 40))
        val edge = director.terrainStyleFor(TerrainCue(grass, depthBelowEye = director.direction.light.depthRange))
        assertEquals(Tint.luma(edge.top), Tint.luma(deep.top))
    }

    @Test
    fun `a ledge casts and an inside corner casts harder`() {
        val open = director.terrainStyleFor(TerrainCue(grass))
        val under = director.terrainStyleFor(TerrainCue(grass, ledgeShadow = true))
        val corner = director.terrainStyleFor(TerrainCue(grass, ledgeShadow = true, cornerShadow = true))
        assertEquals(0, Tint.alpha(open.occlusion))
        assertTrue(Tint.alpha(under.occlusion) > 0)
        assertTrue(Tint.alpha(corner.occlusion) > Tint.alpha(under.occlusion))
    }

    @Test
    fun `terrain is quieter than the block colour a pack shipped`() {
        val style = director.terrainStyleFor(TerrainCue(grass))
        assertTrue(
            chroma(style.top) < chroma(grass.topColor),
            "the ground is the canvas; a pack's raw colour is not the contract",
        )
    }

    @Test
    fun `no style lets the ground get brighter than actors`() {
        // Checked through the director rather than on the record, because it is
        // the drawn colour that competes with the player, not the setting.
        val bright = BlockType(id = "test:snow", displayName = "Snow", topColor = 0xFFFFFFFF, sideColor = 0xFFF0F0F0)
        StyleLexicon.traits.forEach { trait ->
            val direction = StyleLexicon.interpret(trait.words.first()).direction
            val style = StyleSheetArtDirector(direction).terrainStyleFor(TerrainCue(bright))
            assertTrue(
                Tint.luma(style.top) <= MAX_DRAWN_GROUND,
                "${trait.id} draws white ground at ${Tint.luma(style.top)}",
            )
        }
    }

    @Test
    fun `an emitting block keeps its colour when the ground loses its own`() {
        val ground = director.terrainStyleFor(TerrainCue(grass))
        val lit = director.terrainStyleFor(TerrainCue(brazier.copy(glyph = null)))
        assertTrue(
            chroma(lit.top) > chroma(ground.top),
            "the thing worth walking towards has to out-colour the thing you walk on",
        )
    }

    @Test
    fun `distance drains a face towards the haze`() {
        val near = director.terrainStyleFor(TerrainCue(grass, distance = 0f))
        val far = director.terrainStyleFor(TerrainCue(grass, distance = 1f))
        val haze = director.direction.atmosphere.hazeColor
        assertTrue(distanceTo(far.top, haze) < distanceTo(near.top, haze))
    }

    @Test
    fun `a prop gets a silhouette and terrain does not`() {
        assertEquals(null, director.propStyleFor(PropCue(grass)))
        val prop = director.propStyleFor(PropCue(brazier))
        assertEquals(PropSilhouette.BRAZIER, prop?.silhouette)
        assertTrue(Tint.alpha(prop!!.glow) > 0)
    }

    @Test
    fun `two instances of the same prop are not the same size`() {
        val first = director.propStyleFor(PropCue(brazier, variant = 1))!!
        val second = director.propStyleFor(PropCue(brazier, variant = 2))!!
        assertTrue(first.scale != second.scale, "repetition is what makes a landscape read as wallpaper")
    }

    @Test
    fun `the player is drawn bigger than the monsters`() {
        val player = director.actorStyleFor(ActorPresentation("p", ActorRole.PLAYER))
        val minion = director.actorStyleFor(ActorPresentation("m", ActorRole.ENEMY, EnemyRank.MINION))
        assertTrue(player.scale > minion.scale)
        assertTrue(player.scale > 1f, "an actor at literal grid scale is a speck on a field of blocks")
    }

    @Test
    fun `rank is stated on the floor, not in the furniture`() {
        assertEquals(0, Tint.alpha(director.actorStyleFor(ActorPresentation("m", ActorRole.ENEMY, EnemyRank.MINION)).groundRing))
        val elite = director.actorStyleFor(ActorPresentation("e", ActorRole.ENEMY, EnemyRank.ELITE))
        val boss = director.actorStyleFor(ActorPresentation("b", ActorRole.ENEMY, EnemyRank.BOSS))
        assertTrue(Tint.alpha(elite.groundRing) > 0)
        assertTrue(boss.scale > elite.scale)
    }

    @Test
    fun `loot pulls the eye and monsters do not`() {
        val loot = director.actorStyleFor(ActorPresentation("l", ActorRole.LOOT))
        val enemy = director.actorStyleFor(ActorPresentation("e", ActorRole.ENEMY))
        assertTrue(Tint.alpha(loot.halo) > 0)
        assertEquals(0, Tint.alpha(enemy.halo))
    }

    @Test
    fun `every actor is held against its background by a dark contour`() {
        ActorRole.entries.forEach { role ->
            val style = director.actorStyleFor(ActorPresentation("a", role))
            assertTrue(
                Tint.luma(style.outline) < Tint.luma(style.body),
                "$role has no contour to hold it against bright ground",
            )
            assertTrue(style.contactShadow > 0f, "$role floats above the terrain")
        }
    }

    @Test
    fun `night deepens the world rather than recolouring it`() {
        val noon = director.atmosphereFor(null, WorldTime(dayFraction = 0.5f))
        val midnight = director.atmosphereFor(null, WorldTime(dayFraction = 0f))
        assertTrue(midnight.hazeStrength > noon.hazeStrength)
        assertTrue(midnight.vignette > noon.vignette)
        assertEquals(noon.haze, midnight.haze)
    }

    @Test
    fun `a hit is a ring and a flash before it is a number`() {
        val effects = director.effectsFor(CombatCue(CombatMoment.CRITICAL, emphasis = 1f))
        val kinds = effects.map { it.kind }
        assertTrue(EffectKind.IMPACT_RING in kinds)
        assertTrue(EffectKind.HIT_FLASH in kinds)
        assertTrue(EffectKind.SCREEN_SHAKE in kinds)
    }

    @Test
    fun `every moment of combat produces something to look at`() {
        CombatMoment.entries.forEach { moment ->
            assertTrue(
                director.effectsFor(CombatCue(moment)).isNotEmpty(),
                "$moment happens with no feedback at all",
            )
        }
    }

    /** How far a colour is from its own grey: a stand-in for colourfulness. */
    private fun chroma(color: Long): Int {
        val grey = (Tint.luma(color) * 255f).toInt()
        return maxOf(
            kotlin.math.abs(Tint.red(color) - grey),
            kotlin.math.abs(Tint.green(color) - grey),
            kotlin.math.abs(Tint.blue(color) - grey),
        )
    }

    private fun distanceTo(color: Long, other: Long): Int =
        kotlin.math.abs(Tint.red(color) - Tint.red(other)) +
            kotlin.math.abs(Tint.green(color) - Tint.green(other)) +
            kotlin.math.abs(Tint.blue(color) - Tint.blue(other))

    private companion object {
        /** Bright enough for a snowfield, never bright enough to swallow a rim light. */
        const val MAX_DRAWN_GROUND = 0.9f
    }
}
