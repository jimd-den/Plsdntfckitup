package com.stratum.importer.tiled

/**
 * What a flat 2D layer becomes in a world with height.
 *
 * Tiled has no depth: a floor, a hedge and a roof are three layers drawn in
 * order. The importer has to decide which is which, and it asks the author
 * first -- layer properties `elevation`, `thickness` and `solid` -- and only
 * then falls back on the names Flame RPGs conventionally give their layers.
 */
data class LayerRole(
    val kind: Kind,
    val elevation: Int,
    val thickness: Int,
    /** Whether cells on this layer block movement, unless a tile says otherwise. */
    val solid: Boolean,
) {
    enum class Kind { FLOOR, WALL, DECORATION, OVERHEAD }

    /** Overhead layers draw over the player in 2D; in 3D they would bury the camera. */
    val isPlaced: Boolean get() = kind != Kind.OVERHEAD
}

object LayerRoles {

    private val WALL_WORDS = listOf("wall", "collision", "collide", "obstacle", "block", "building", "house", "cliff", "fence")
    private val OVERHEAD_WORDS = listOf("roof", "above", "over", "overhead", "canopy", "top", "sky", "foreground")

    /**
     * @param isFirstTileLayer the bottom tile layer is the floor unless it says otherwise.
     */
    fun roleOf(name: String, properties: TiledProperties, isFirstTileLayer: Boolean): LayerRole {
        val elevation = properties.firstInt("elevation", "z", "level")?.coerceAtLeast(0)
        val kind = kindOf(name, properties, isFirstTileLayer, placedByAuthor = elevation != null)
        val defaults = defaultsFor(kind)
        return defaults.copy(
            elevation = elevation ?: defaults.elevation,
            thickness = properties.firstInt("thickness", "height")?.coerceAtLeast(1) ?: defaults.thickness,
            solid = properties.firstBool("solid", "collides", "collision") ?: defaults.solid,
        )
    }

    /** @param placedByAuthor an explicit elevation means the author wants the layer in the world. */
    private fun kindOf(name: String, properties: TiledProperties, isFirstTileLayer: Boolean, placedByAuthor: Boolean): LayerRole.Kind {
        properties.string("role")?.let { explicit ->
            LayerRole.Kind.entries.firstOrNull { it.name.equals(explicit, ignoreCase = true) }?.let { return it }
        }
        return when {
            name.mentions(OVERHEAD_WORDS) -> if (placedByAuthor) LayerRole.Kind.DECORATION else LayerRole.Kind.OVERHEAD
            name.mentions(WALL_WORDS) || properties.firstBool("solid", "collides", "collision") == true -> LayerRole.Kind.WALL
            isFirstTileLayer -> LayerRole.Kind.FLOOR
            else -> LayerRole.Kind.DECORATION
        }
    }

    private fun defaultsFor(kind: LayerRole.Kind): LayerRole = when (kind) {
        LayerRole.Kind.FLOOR -> LayerRole(kind, elevation = 0, thickness = 1, solid = true)
        LayerRole.Kind.WALL -> LayerRole(kind, elevation = 1, thickness = 2, solid = true)
        LayerRole.Kind.DECORATION -> LayerRole(kind, elevation = 1, thickness = 1, solid = false)
        LayerRole.Kind.OVERHEAD -> LayerRole(kind, elevation = 3, thickness = 1, solid = false)
    }

    /** Object layers and objects that mean "you cannot walk here". */
    fun isCollision(name: String, properties: TiledProperties): Boolean =
        properties.firstBool("solid", "collides", "collision") ?: name.mentions(COLLISION_WORDS)

    private val COLLISION_WORDS = listOf("collision", "collide", "wall", "obstacle", "block")

    /** Whether any word in the name starts with one of [words], so `Walls_2` is a wall and `desktop` is not a top. */
    private fun String.mentions(words: List<String>): Boolean =
        lowercase().split(Regex("[^a-z]+")).any { token -> words.any(token::startsWith) }
}
