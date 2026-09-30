package com.stratum.core.domain.world

/**
 * Something built into the world by the generator: a dungeon, a ruin, a
 * shrine. Pack data, so a plugin that ships only a structure adds it to
 * every world it is loaded into.
 *
 * A template is either a room-and-corridor [dungeon] or a set of [pieces]
 * joined at their connectors. Where it may stand, how far apart and how
 * often is its [placement]. What it marks -- monster spawns, the boss room,
 * loot -- comes out as [WorldMarker]s, so the action RPG layer can people it
 * without the generator knowing any monster by name.
 */
data class StructureTemplate(
    val id: String,
    val name: String,
    val placement: StructurePlacement = StructurePlacement(),
    val dungeon: DungeonLayout? = null,
    /** Jigsaw pieces; the first marked [StructurePiece.start], or the first, begins it. */
    val pieces: List<StructurePiece> = emptyList(),
    /** At most this many pieces are joined, the start included. */
    val maxPieces: Int = 1,
    /** Laid under a surface structure where the ground falls away, so it never floats. */
    val foundationBlockId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A structure needs an id" }
        require((dungeon == null) != pieces.isEmpty()) { "Structure '$id' must be either a dungeon or a set of pieces" }
        require(maxPieces >= 1) { "Structure '$id' must allow at least one piece" }
    }

    /** Every block this template can place, for validation at load. */
    fun referencedBlockIds(): Set<String> = buildSet {
        foundationBlockId?.let(::add)
        dungeon?.let { d -> addAll(listOfNotNull(d.floorBlockId, d.wallBlockId, d.ceilingBlockId, d.stairBlockId, d.lightBlockId)) }
        pieces.forEach { piece -> piece.palette.values.filterNot(StructurePiece::isMarker).forEach(::add) }
    }

    /** Everything wrong with the template's own shape, as messages naming it. */
    fun problems(): List<String> = pieces.flatMap { it.problems(id) } +
        if (pieces.size > 1 && pieces.none { it.connectors.isNotEmpty() }) listOf("structure '$id' has several pieces and no connectors to join them") else emptyList()
}

/** How a structure sits relative to the ground. */
enum class StructureAnchor {
    /** On the surface, its footprint levelled. */
    SURFACE,

    /** Underground at a depth in [StructurePlacement.minZ]..[StructurePlacement.maxZ], sealed in rock. */
    UNDERGROUND,
}

/**
 * Where and how often a structure appears.
 *
 * The world is cut into cells [spacing] blocks across and each cell holds at
 * most one of this structure, jittered inside the cell and kept off its edge.
 * Whether a cell has one is a roll against [chance] seeded by the world, the
 * structure and the cell alone, so every chunk agrees without asking another.
 */
data class StructurePlacement(
    /** Only where the ground at the anchor is one of these; empty for anywhere. */
    val biomeIds: List<String> = emptyList(),
    val spacing: Int = 160,
    /** The rarity: the share of cells that hold one. */
    val chance: Float = 0.5f,
    val anchor: StructureAnchor = StructureAnchor.SURFACE,
    /** The depth band of an [StructureAnchor.UNDERGROUND] structure's floor. */
    val minZ: Int = 4,
    val maxZ: Int = 16,
) {
    init {
        require(spacing in MIN_SPACING..MAX_SPACING) { "Structure spacing must be $MIN_SPACING..$MAX_SPACING blocks" }
        require(chance in 0f..1f) { "Structure chance of $chance is not a share" }
        require(minZ >= 2 && maxZ >= minZ) { "Structure depth band $minZ..$maxZ is empty or inside bedrock" }
    }

    companion object {
        const val MIN_SPACING = 32
        const val MAX_SPACING = 2048
    }
}

/**
 * A room-and-corridor dungeon under the ground, reached by a stair from the
 * surface.
 *
 * Rooms are laid out around the entrance, joined by corridors into a tree so
 * every room is reachable, and the room farthest from the stair is the boss
 * room. Rooms that would break through the ground are left out rather than
 * poking a box of wall out of a hillside.
 */
data class DungeonLayout(
    val floorBlockId: String,
    val wallBlockId: String,
    /** Null uses the wall block. */
    val ceilingBlockId: String? = null,
    /** The treads of the entrance stair; null uses the floor block. */
    val stairBlockId: String? = null,
    /** Stood in each room's corners; null for dark rooms. */
    val lightBlockId: String? = null,
    val minRooms: Int = 4,
    val maxRooms: Int = 7,
    val minRoomSize: Int = 5,
    val maxRoomSize: Int = 9,
    /** Air inside a room, floor to ceiling. */
    val roomHeight: Int = 3,
    /** How far below the surface at the entrance the floor lies. */
    val depth: Int = 10,
    /** How far from the entrance rooms may spread, in blocks. */
    val extent: Int = 28,
    val corridorWidth: Int = 2,
    /** Whether a stair leads down from the surface. */
    val entrance: Boolean = true,
    val spawnsPerRoom: Int = 2,
    /** Chance each ordinary room holds loot; the boss room always does. */
    val lootChance: Float = 0.5f,
    val bossRoom: Boolean = true,
    /** Monsters named on spawn markers, picked per marker; empty leaves it to the region. */
    val enemyIds: List<String> = emptyList(),
    val bossId: String? = null,
    /** Named on loot markers, for whatever table the loot layer keeps. */
    val lootRef: String? = null,
) {
    init {
        require(minRooms in 1..maxRooms) { "Dungeon room count $minRooms..$maxRooms is inverted" }
        require(maxRooms <= MAX_ROOMS) { "A dungeon has at most $MAX_ROOMS rooms" }
        require(minRoomSize in 3..maxRoomSize) { "Dungeon room size $minRoomSize..$maxRoomSize is inverted or too small for walls" }
        require(roomHeight in 2..12) { "Room height must be 2..12" }
        require(depth in 3 until Chunk.HEIGHT) { "Dungeon depth must be 3..${Chunk.HEIGHT - 1}" }
        require(extent in maxRoomSize..MAX_EXTENT) { "Dungeon extent must fit a room and be at most $MAX_EXTENT" }
        require(corridorWidth in 1..3) { "Corridor width must be 1..3" }
        require(spawnsPerRoom >= 0) { "spawnsPerRoom cannot be negative" }
        require(lootChance in 0f..1f) { "Loot chance of $lootChance is not a share" }
    }

    companion object {
        const val MAX_ROOMS = 16
        const val MAX_EXTENT = 64
    }
}

/** A side of a piece, on the world's axes: north is -y. */
enum class PieceSide(val dx: Int, val dy: Int) {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    val opposite: PieceSide get() = entries[(ordinal + 2) % 4]
}

/** A doorway on a piece's edge where another piece may join, [offset] cells along that edge. */
data class PieceConnector(val side: PieceSide, val offset: Int)

/**
 * A small block template: layers of rows of characters, bottom layer first.
 *
 * Each character is looked up in [palette]: a block id, or a marker token
 * such as `@enemy`, `@boss:pack:lord`, `@loot` or `@poi`, which leaves air
 * and marks the spot. A space leaves the world as it was and `.` is air.
 * Layer 0 is laid at ground level, replacing the surface block, so a floor
 * row is a floor.
 */
data class StructurePiece(
    val id: String,
    val palette: Map<Char, String>,
    val layers: List<List<String>>,
    val connectors: List<PieceConnector> = emptyList(),
    /** Chance weight when a piece is picked to join a connector. */
    val weight: Int = 1,
    val start: Boolean = false,
) {
    val width: Int get() = layers.firstOrNull()?.maxOfOrNull { it.length } ?: 0
    val depth: Int get() = layers.firstOrNull()?.size ?: 0
    val height: Int get() = layers.size

    /** The palette entry for [c], or null for a cell that leaves the world alone. */
    fun entryAt(x: Int, y: Int, z: Int): String? {
        val c = layers.getOrNull(z)?.getOrNull(y)?.getOrNull(x) ?: return null
        return when (c) {
            ' ' -> null
            '.' -> AIR
            else -> palette[c]
        }
    }

    fun problems(owner: String): List<String> = buildList {
        if (layers.isEmpty() || width == 0) add("structure '$owner' piece '$id' is empty")
        if (layers.any { it.size != depth }) add("structure '$owner' piece '$id' has layers of different depths")
        if (layers.any { layer -> layer.any { it.length != width } }) add("structure '$owner' piece '$id' has rows of different widths")
        val used = layers.flatten().flatMap { it.toList() }.toSet() - ' ' - '.'
        (used - palette.keys).forEach { add("structure '$owner' piece '$id' uses '$it' with no palette entry") }
        connectors.forEach { c ->
            val along = if (c.side == PieceSide.NORTH || c.side == PieceSide.SOUTH) width else depth
            if (c.offset !in 0 until along) add("structure '$owner' piece '$id' has a connector off its ${c.side.name.lowercase()} edge")
        }
        if (weight < 0) add("structure '$owner' piece '$id' has a negative weight")
    }

    companion object {
        const val AIR = "@air"
        const val MARKER_PREFIX = "@"

        fun isMarker(entry: String): Boolean = entry.startsWith(MARKER_PREFIX)
    }
}
