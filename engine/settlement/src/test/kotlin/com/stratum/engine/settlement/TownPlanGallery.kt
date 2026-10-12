package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.RoadGeometry
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.settlement.culture.CityGenerator
import com.stratum.core.domain.settlement.culture.Form
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Draws one town of each pattern from above, for the docs: roads, walls, and buildings coloured by what they are for. */
class TownPlanGallery {

    private val house = BuildingTemplate("t:house", "House", BuildingRole.HOUSE, 5, 5, 3, "t:mud", roofBlockId = "t:thatch")
    private val recipe = SettlementRecipe(
        id = "t:town", name = "Town", layoutId = SettlementRecipe.AFRICAN, minRadius = 22, maxRadius = 28, chance = 1f,
        roadBlockId = "t:laterite", foundationBlockId = "t:earth", buildings = listOf(house),
    )

    private val towns = listOf(
        Triple(Form.KRAAL, "zulu", 4L), Triple(Form.HOMESTEAD, "kassena", 3L), Triple(Form.VILLAGE_GROUP, "igbo", 7L),
        Triple(Form.CLIFF_VILLAGE, "dogon", 2L), Triple(Form.KSAR, "amazigh", 11L), Triple(Form.WALLED_CITY, "hausa", 5L),
        Triple(Form.ROYAL_CAPITAL, "yoruba", 9L), Triple(Form.STONE_TOWN, "swahili", 8L), Triple(Form.HILL_CITADEL, "great_zimbabwe", 6L),
        Triple(Form.RIVER_PORT, "nubian", 12L),
    )

    private val colours = mapOf(
        BuildingRole.HOUSE to Color(0xC8, 0x8A, 0x5A), BuildingRole.HALL to Color(0xE0, 0xB0, 0x30), BuildingRole.TEMPLE to Color(0xF2, 0xF0, 0xE6),
        BuildingRole.SHOP to Color(0x4F, 0x7C, 0xD8), BuildingRole.SMITHY to Color(0x40, 0x40, 0x48), BuildingRole.WAREHOUSE to Color(0xD9, 0xC2, 0x7A),
        BuildingRole.FARM to Color(0x6E, 0x9C, 0x4A), BuildingRole.TAVERN to Color(0xB0, 0x4A, 0x8A), BuildingRole.TOWER to Color(0x8A, 0x30, 0x20),
        BuildingRole.BARRACKS to Color(0x70, 0x20, 0x20),
    )

    @Test
    fun `draw one town of every pattern`() {
        val tile = 420; val cols = 5; val rows = 2
        val image = BufferedImage(tile * cols, tile * rows + 46, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1E, 0x17, 0x12); g.fillRect(0, 0, image.width, image.height)
        towns.forEachIndexed { i, (form, culture, seed) ->
            val genome = CityGenerator.roll(seed, culture, form)
            val radius = (25 * form.scale.radius).toInt().coerceIn(SettlementRecipe.MIN_RADIUS, SettlementRecipe.MAX_RADIUS)
            val layout = AfricanLayout.arrange(SettlementSite(0, 0, 10, radius, genome), recipe, Random(seed))
            val ox = (i % cols) * tile; val oy = (i / cols) * tile
            val span = 2 * (40 + 8)
            val px = (tile - 20) / span.toFloat()
            fun sx(x: Float) = (ox + tile / 2f + x * px).toInt()
            fun sy(y: Float) = (oy + tile / 2f + 14 + y * px).toInt()
            // Ground inside the town, then roads cell by cell.
            g.color = Color(0x6B, 0x4A, 0x2E)
            g.fillOval(sx(-radius.toFloat()), sy(-radius.toFloat()), (2 * radius * px).toInt(), (2 * radius * px).toInt())
            g.color = Color(0xB5, 0x6A, 0x3C)
            for (y in -radius - 8..radius + 8) for (x in -radius - 8..radius + 8) {
                if (layout.roads.any { RoadGeometry.covers(it, x, y) }) g.fillRect(sx(x.toFloat()), sy(y.toFloat()), px.toInt() + 1, px.toInt() + 1)
            }
            if (layout.recipe?.wallBlockId != null) {
                g.color = Color(0xE8, 0xD8, 0xB8); g.stroke = BasicStroke(3f)
                if (layout.square) g.drawRect(sx(-radius.toFloat()), sy(-radius.toFloat()), (2 * radius * px).toInt(), (2 * radius * px).toInt())
                else g.drawOval(sx(-radius.toFloat()), sy(-radius.toFloat()), (2 * radius * px).toInt(), (2 * radius * px).toInt())
            }
            layout.buildings.forEach { b ->
                g.color = colours[b.template.role] ?: Color.GRAY
                g.fillRect(sx(b.x.toFloat()), sy(b.y.toFloat()), (b.width * px).toInt(), (b.depth * px).toInt())
                g.color = Color(0, 0, 0, 120); g.stroke = BasicStroke(1f)
                g.drawRect(sx(b.x.toFloat()), sy(b.y.toFloat()), (b.width * px).toInt(), (b.depth * px).toInt())
                // The door, as a pale notch on its wall.
                g.color = Color(0xFF, 0xF5, 0xD0)
                g.fillRect(sx(b.doorX.toFloat()), sy(b.doorY.toFloat()), (px * 0.9f).toInt().coerceAtLeast(2), (px * 0.9f).toInt().coerceAtLeast(2))
            }
            g.color = Color(0xF4, 0xE6, 0xCC); g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
            g.drawString(genome.name, ox + 10, oy + 20)
            g.color = Color(0xC9, 0xB8, 0x9A); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            g.drawString("${genome.people.name} ${form.label.lowercase()} · ${layout.buildings.size} buildings", ox + 10, oy + 36)
        }
        // A key along the bottom.
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        var x = 12
        colours.forEach { (role, c) ->
            g.color = c; g.fillRect(x, image.height - 30, 14, 14)
            g.color = Color(0xE0, 0xD0, 0xB8); g.drawString(role.name.lowercase(), x + 18, image.height - 18)
            x += 18 + g.fontMetrics.stringWidth(role.name.lowercase()) + 18
        }
        g.dispose()
        val out = File("build/town-plans.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 10_000)
    }
}
