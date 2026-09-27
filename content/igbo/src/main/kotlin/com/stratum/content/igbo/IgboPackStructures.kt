package com.stratum.content.igbo

import com.stratum.core.domain.world.DungeonLayout
import com.stratum.core.domain.world.PieceConnector
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructurePiece
import com.stratum.core.domain.world.StructurePlacement
import com.stratum.core.domain.world.StructureTemplate

/**
 * What the built-in world has built into it, below and above the ground.
 *
 * The Igbo-Ukwu finds were a burial chamber and a store of ritual bronzes,
 * dug out of the ground by a man digging a cistern. So the pack's dungeon is
 * a masonry catacomb under the land, entered by a stair, held by the dead and
 * the priests who speak for them. Above ground stand the ruins of Mbari
 * houses -- the open shrines built to be left to the weather -- with their
 * low mud walls and a seal at the heart.
 */
internal object IgboPackStructures {

    private const val NS = "igbo"

    val lostCatacomb = StructureTemplate(
        id = "$NS:lost_catacomb",
        name = "Lost Catacomb of Igbo-Ukwu",
        placement = StructurePlacement(spacing = 176, chance = 0.55f),
        dungeon = DungeonLayout(
            floorBlockId = IgboPackBlocks.graniteStone.id,
            wallBlockId = IgboPackBlocks.catacombMasonry.id,
            stairBlockId = IgboPackBlocks.graniteStone.id,
            lightBlockId = IgboPackBlocks.bronzeBrazier.id,
            minRooms = 4, maxRooms = 7,
            depth = 9,
            roomHeight = 3,
            extent = 24,
            enemyIds = listOf("$NS:marsh_revenant", "$NS:catacomb_guardian", "$NS:ogu_brute"),
            bossId = "$NS:agbara_priest",
            lootRef = "$NS:bronze_hoard",
        ),
    )

    private val wall = IgboPackBlocks.mudWall.id
    private val floor = IgboPackBlocks.graniteStone.id

    /** The shrine's heart: a paved square, walls knee-high and broken, a seal at the centre. */
    private val mbariCourt = StructurePiece(
        id = "$NS:mbari_court",
        start = true,
        palette = mapOf('#' to wall, '_' to floor, 'S' to IgboPackBlocks.nsibidiSeal.id, 'L' to "@loot", 'P' to "@poi"),
        layers = listOf(
            listOf(
                "#######",
                "#_____#",
                "#_____#",
                "______#",
                "#_____#",
                "#_____#",
                "#######",
            ),
            listOf(
                "#  #  #",
                "       ",
                "   L   ",
                "   S  #",
                "   P   ",
                "       ",
                "#  #  #",
            ),
        ),
        connectors = listOf(PieceConnector(PieceSide.WEST, 3)),
    )

    /** A side room fallen further into ruin, where something has made its home. */
    private val mbariWing = StructurePiece(
        id = "$NS:mbari_wing",
        palette = mapOf('#' to wall, '_' to floor, 'E' to "@enemy"),
        layers = listOf(
            listOf(
                "#####",
                "#___#",
                "#____",
                "#___#",
                "#####",
            ),
            listOf(
                "#   #",
                "     ",
                "  E  ",
                "     ",
                "#   #",
            ),
        ),
        connectors = listOf(PieceConnector(PieceSide.EAST, 2)),
    )

    val mbariRuin = StructureTemplate(
        id = "$NS:mbari_ruin",
        name = "Ruined Mbari House",
        placement = StructurePlacement(
            biomeIds = listOf(IgboPackBiomes.sacredGrove.id, IgboPackBiomes.ozoCourtyard.id),
            spacing = 120,
            chance = 0.45f,
        ),
        pieces = listOf(mbariCourt, mbariWing),
        maxPieces = 2,
        foundationBlockId = IgboPackBlocks.redEarth.id,
    )

    val all = listOf(lostCatacomb, mbariRuin)
}
