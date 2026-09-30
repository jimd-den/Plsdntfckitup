package com.stratum.plugins.schema

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.world.DungeonLayout
import com.stratum.core.domain.world.PieceConnector
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructureAnchor
import com.stratum.core.domain.world.StructurePiece
import com.stratum.core.domain.world.StructurePlacement
import com.stratum.core.domain.world.StructureTemplate
import kotlinx.serialization.Serializable

// Dungeons, ruins and shrines the world generator builds: `structureTemplates` in pack.json.

private val PLACEMENT = StructurePlacement()
private val DUNGEON = DungeonLayout(floorBlockId = "", wallBlockId = "")

@Serializable
internal data class PlacementSchema(
    val biomes: List<String> = emptyList(),
    val spacing: Int = PLACEMENT.spacing,
    val chance: Float = PLACEMENT.chance,
    val anchor: String = SchemaValues.name(PLACEMENT.anchor),
    val minZ: Int = PLACEMENT.minZ,
    val maxZ: Int = PLACEMENT.maxZ,
) {
    fun toDomain(owner: String) =
        StructurePlacement(biomes, spacing, chance, SchemaValues.enum<StructureAnchor>(anchor, "structure '$owner' anchor"), minZ, maxZ)

    companion object {
        fun of(p: StructurePlacement) = PlacementSchema(p.biomeIds, p.spacing, p.chance, SchemaValues.name(p.anchor), p.minZ, p.maxZ)
    }
}

@Serializable
internal data class DungeonSchema(
    val floor: String,
    val wall: String,
    val ceiling: String? = null,
    val stair: String? = null,
    val light: String? = null,
    val minRooms: Int = DUNGEON.minRooms,
    val maxRooms: Int = DUNGEON.maxRooms,
    val minRoomSize: Int = DUNGEON.minRoomSize,
    val maxRoomSize: Int = DUNGEON.maxRoomSize,
    val roomHeight: Int = DUNGEON.roomHeight,
    val depth: Int = DUNGEON.depth,
    val extent: Int = DUNGEON.extent,
    val corridorWidth: Int = DUNGEON.corridorWidth,
    val entrance: Boolean = DUNGEON.entrance,
    val spawnsPerRoom: Int = DUNGEON.spawnsPerRoom,
    val lootChance: Float = DUNGEON.lootChance,
    val bossRoom: Boolean = DUNGEON.bossRoom,
    val enemies: List<String> = emptyList(),
    val boss: String? = null,
    val loot: String? = null,
) {
    fun toDomain() = DungeonLayout(
        floor, wall, ceiling, stair, light, minRooms, maxRooms, minRoomSize, maxRoomSize, roomHeight, depth, extent,
        corridorWidth, entrance, spawnsPerRoom, lootChance, bossRoom, enemies, boss, loot,
    )

    companion object {
        fun of(d: DungeonLayout) = DungeonSchema(
            d.floorBlockId, d.wallBlockId, d.ceilingBlockId, d.stairBlockId, d.lightBlockId, d.minRooms, d.maxRooms,
            d.minRoomSize, d.maxRoomSize, d.roomHeight, d.depth, d.extent, d.corridorWidth, d.entrance, d.spawnsPerRoom,
            d.lootChance, d.bossRoom, d.enemyIds, d.bossId, d.lootRef,
        )
    }
}

@Serializable
internal data class ConnectorSchema(val side: String, val offset: Int) {
    fun toDomain(owner: String) = PieceConnector(SchemaValues.enum<PieceSide>(side, "piece '$owner' connector side"), offset)

    companion object {
        fun of(c: PieceConnector) = ConnectorSchema(SchemaValues.name(c.side), c.offset)
    }
}

/** A piece: `palette` maps one character to a block id or a marker token; `layers` are rows of characters, bottom first. */
@Serializable
internal data class PieceSchema(
    val id: String,
    val palette: Map<String, String> = emptyMap(),
    val layers: List<List<String>>,
    val connectors: List<ConnectorSchema> = emptyList(),
    val weight: Int = 1,
    val start: Boolean = false,
) {
    fun toDomain(): StructurePiece {
        val keys = palette.mapKeys { (key, _) ->
            key.singleOrNull() ?: throw ImportException("piece '$id' palette key '$key' must be a single character")
        }
        return StructurePiece(id, keys, layers, connectors.map { it.toDomain(id) }, weight, start)
    }

    companion object {
        fun of(p: StructurePiece) = PieceSchema(
            p.id, p.palette.mapKeys { it.key.toString() }, p.layers, p.connectors.map(ConnectorSchema::of), p.weight, p.start,
        )
    }
}

@Serializable
internal data class StructureTemplateSchema(
    val id: String,
    val name: String,
    val placement: PlacementSchema = PlacementSchema(),
    val dungeon: DungeonSchema? = null,
    val pieces: List<PieceSchema> = emptyList(),
    val maxPieces: Int = 1,
    val foundation: String? = null,
) {
    fun toDomain() = StructureTemplate(id, name, placement.toDomain(id), dungeon?.toDomain(), pieces.map { it.toDomain() }, maxPieces, foundation)

    companion object {
        fun of(t: StructureTemplate) = StructureTemplateSchema(
            t.id, t.name, PlacementSchema.of(t.placement), t.dungeon?.let(DungeonSchema::of), t.pieces.map(PieceSchema::of),
            t.maxPieces, t.foundationBlockId,
        )
    }
}
