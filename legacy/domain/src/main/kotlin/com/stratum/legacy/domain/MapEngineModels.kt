package com.stratum.legacy.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * CHAPTER 04-B: DOMAIN MODEL - MAP GENERATOR & ISOMETRIC PROJECTION ENGINE
 *
 * Implements:
 * 1. Biomes from Igbo mythology: Sacred Grove, Royal Ozo Palace, Amadioha Thunder Peak,
 *    Lost Bronze Catacombs, and Benue Mist Marsh.
 * 2. Tile grid procedural generation with room layout, obstacles, walls, pillars, and shrines.
 * 3. 2.5D Isometric projection mathematics (30-degree isometric ARPG projection).
 * 4. Scaling options (sprite scale, camera zoom, pixel-snap filter).
 */

enum class MapBiome(
    val title: String,
    val description: String,
    val primaryColorHex: String,
    val floorColorHex: String,
    val wallColorHex: String,
    val accentColorHex: String
) {
    SACRED_GROVE_IDEMILI(
        title = "Sacred Grove of Idemili",
        description = "Mossy stone flagstones enveloped in ancient Iroko trees and emerald spirit mist.",
        primaryColorHex = "#2E7D32",
        floorColorHex = "#1B2E1E",
        wallColorHex = "#0D1F10",
        accentColorHex = "#76FF03"
    ),
    OZO_ROYAL_PALACE(
        title = "Ozo Royal Courtyard",
        description = "Terracotta baked clay tiles, roped bronze columns, and sacred Anyanwu sun seals.",
        primaryColorHex = "#D84315",
        floorColorHex = "#2E1C16",
        wallColorHex = "#4E261A",
        accentColorHex = "#FFD700"
    ),
    AMADIOHA_THUNDER_PEAK(
        title = "Amadioha Thunder Altar",
        description = "Shattered obsidian crags charged with crackling electric runes and storm skies.",
        primaryColorHex = "#00E5FF",
        floorColorHex = "#15222E",
        wallColorHex = "#0A121A",
        accentColorHex = "#00E5FF"
    ),
    LOST_BRONZE_CATACOMBS(
        title = "Lost Catacombs of Igbo-Ukwu",
        description = "Subterranean masonry with ancient roped bronze vessels and torch braziers.",
        primaryColorHex = "#CD7F32",
        floorColorHex = "#262017",
        wallColorHex = "#3D3220",
        accentColorHex = "#FFB300"
    ),
    BENUE_RIVER_MARSH(
        title = "Benue River Mist Marsh",
        description = "Weathered wooden boardwalks over murky spirit waters with glowing reeds.",
        primaryColorHex = "#00B0FF",
        floorColorHex = "#13282C",
        wallColorHex = "#0A191C",
        accentColorHex = "#40C4FF"
    );

    val displayName: String get() = title
    val loreSnippet: String get() = description
}

data class DungeonRoom(
    val centerX: Float,
    val centerY: Float,
    val radius: Float = 3f,
    val isShrineRoom: Boolean = false
)

object ProceduralMapGenerator {
    fun generateDungeon(
        biome: MapBiome = MapBiome.SACRED_GROVE_IDEMILI,
        width: Int = 22,
        height: Int = 22,
        seed: Long = System.currentTimeMillis()
    ): DungeonMapData = DungeonMapData.generateMap(biome, width, height, seed = seed)
}

enum class TileType(val isWalkable: Boolean, val symbol: String) {
    FLOOR_BASIC(true, "·"),
    FLOOR_ORNATE(true, "◇"),
    FLOOR_NSIBIDI_SEAL(true, "☼"),
    WALL_STONE(false, "■"),
    PILLAR_BRONZE(false, "▲"),
    SHRINE_OFO(false, "☩"),
    TORCH_BRAZIER(false, "🔥"),
    WATER_POOL(false, "≈")
}

data class MapTile(
    val gridX: Int,
    val gridY: Int,
    val type: TileType,
    val biome: MapBiome,
    val decorVariant: Int = 0,
    val lightIntensity: Float = 1.0f
) {
    val isWalkable: Boolean get() = type.isWalkable
}

enum class CameraProjection {
    TOP_DOWN,
    ISOMETRIC_2_5D
}

data class ScalingConfig(
    val spriteScale: Float = 1.5f,        // 1.0x, 1.5x, 2.0x, 2.5x, 3.0x
    val cameraZoom: Float = 1.0f,         // 0.75x, 1.0x, 1.25x, 1.5x, 2.0x
    val isPixelSnap: Boolean = true,      // Nearest-neighbor retro look
    val projection: CameraProjection = CameraProjection.TOP_DOWN
)

data class DungeonMapData(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val biome: MapBiome,
    val gridWidth: Int = 22,
    val gridHeight: Int = 22,
    val tileSize: Float = 64f,
    val tiles: List<MapTile>,
    val spawnWorldX: Float = 0f,
    val spawnWorldY: Float = 0f,
    val shrineWorldX: Float = 0f,
    val shrineWorldY: Float = 0f,
    val seed: Long = 0L,
    val rooms: List<DungeonRoom> = emptyList()
) {
    val halfWidthPixels: Float get() = (gridWidth * tileSize) / 2f
    val halfHeightPixels: Float get() = (gridHeight * tileSize) / 2f
    val columns: Int get() = gridWidth
    val rows: Int get() = gridHeight

    fun getTileAt(gridX: Int, gridY: Int): MapTile? {
        if (gridX !in 0 until gridWidth || gridY !in 0 until gridHeight) return null
        return tiles.getOrNull(gridY * gridWidth + gridX)
    }

    fun isPositionWalkable(worldX: Float, worldY: Float): Boolean {
        // Convert centered world coords to grid
        val localX = worldX + halfWidthPixels
        val localY = worldY + halfHeightPixels
        val gx = (localX / tileSize).toInt()
        val gy = (localY / tileSize).toInt()
        val tile = getTileAt(gx, gy) ?: return false
        return tile.isWalkable
    }

    companion object {
        private const val COS_30 = 0.8660254f
        private const val SIN_30 = 0.5f

        /**
         * Converts 2D world coords to 2.5D Isometric screen coords.
         */
        fun toIsometric(worldX: Float, worldY: Float): Pair<Float, Float> {
            val isoX = (worldX - worldY) * COS_30
            val isoY = (worldX + worldY) * SIN_30
            return Pair(isoX, isoY)
        }

        /**
         * Converts 2.5D Isometric coords back to 2D world coords.
         */
        fun fromIsometric(isoX: Float, isoY: Float): Pair<Float, Float> {
            val worldX = (isoX / COS_30 + isoY / SIN_30) / 2f
            val worldY = (isoY / SIN_30 - isoX / COS_30) / 2f
            return Pair(worldX, worldY)
        }

        /**
         * Procedural map generator crafting ancient Igbo ARPG arenas and dungeons.
         */
        fun generateMap(
            biome: MapBiome = MapBiome.SACRED_GROVE_IDEMILI,
            gridWidth: Int = 22,
            gridHeight: Int = 22,
            obstacleDensity: Float = 0.12f,
            seed: Long = System.currentTimeMillis()
        ): DungeonMapData {
            val rng = Random(seed)
            val tiles = mutableListOf<MapTile>()
            val tileSize = 64f

            val midX = gridWidth / 2
            val midY = gridHeight / 2

            for (y in 0 until gridHeight) {
                for (x in 0 until gridWidth) {
                    val isBorder = x == 0 || y == 0 || x == gridWidth - 1 || y == gridHeight - 1
                    val isCenter = (x in (midX - 2)..(midX + 2)) && (y in (midY - 2)..(midY + 2))
                    val isShrinePos = (x == midX && y == midY)

                    val type = when {
                        isBorder -> TileType.WALL_STONE
                        isShrinePos -> TileType.SHRINE_OFO
                        isCenter -> {
                            if (x == midX || y == midY) TileType.FLOOR_NSIBIDI_SEAL else TileType.FLOOR_ORNATE
                        }
                        else -> {
                            val roll = rng.nextFloat()
                            if (roll < obstacleDensity) {
                                when {
                                    roll < obstacleDensity * 0.45f -> TileType.PILLAR_BRONZE
                                    roll < obstacleDensity * 0.75f -> TileType.WALL_STONE
                                    roll < obstacleDensity * 0.90f -> TileType.TORCH_BRAZIER
                                    else -> TileType.WATER_POOL
                                }
                            } else if (roll < 0.35f) {
                                TileType.FLOOR_ORNATE
                            } else {
                                TileType.FLOOR_BASIC
                            }
                        }
                    }

                    val decor = rng.nextInt(0, 4)
                    val light = if (type == TileType.TORCH_BRAZIER || type == TileType.SHRINE_OFO) 1.5f else 1.0f
                    tiles.add(
                        MapTile(
                            gridX = x,
                            gridY = y,
                            type = type,
                            biome = biome,
                            decorVariant = decor,
                            lightIntensity = light
                        )
                    )
                }
            }

            val halfW = (gridWidth * tileSize) / 2f
            val halfH = (gridHeight * tileSize) / 2f

            val spawnGridX = midX
            val spawnGridY = midY + 3
            val spawnWorldX = (spawnGridX * tileSize + tileSize / 2f) - halfW
            val spawnWorldY = (spawnGridY * tileSize + tileSize / 2f) - halfH

            val shrineWorldX = (midX * tileSize + tileSize / 2f) - halfW
            val shrineWorldY = (midY * tileSize + tileSize / 2f) - halfH

            return DungeonMapData(
                name = "${biome.title} Chamber",
                biome = biome,
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                tileSize = tileSize,
                tiles = tiles,
                spawnWorldX = spawnWorldX,
                spawnWorldY = spawnWorldY,
                shrineWorldX = shrineWorldX,
                shrineWorldY = shrineWorldY,
                seed = seed,
                rooms = listOf(
                    DungeonRoom(midX.toFloat(), midY.toFloat(), radius = 4f, isShrineRoom = true),
                    DungeonRoom(midX.toFloat() - 5f, midY.toFloat() - 4f, radius = 2.5f),
                    DungeonRoom(midX.toFloat() + 5f, midY.toFloat() + 4f, radius = 2.5f)
                )
            )
        }
    }
}
