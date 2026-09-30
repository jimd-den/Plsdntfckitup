package com.stratum.core.domain.content

/**
 * A 3D model a pack gives a body to something with.
 *
 * A list of its own, pointing at what it dresses, rather than a field on every
 * block, weapon and monster: a model is an optional coat of paint over content
 * that already works without one, and keeping the references here means a pack
 * can restyle another pack's monster without redefining it.
 *
 * Each target is optional and at most one of each is usual; naming several is
 * allowed, so one statue can stand as a prop and also be buildable.
 */
data class ModelDefinition(
    val id: String,
    /**
     * Where the bytes are. `asset:<id>` names a model forged on this device;
     * anything else is a path inside the plugin, such as `models/idol.glb`.
     */
    val source: String,
    val name: String = "",
    /** How tall it stands once normalised, in blocks. */
    val height: Float = DEFAULT_HEIGHT,
    /** A prop block drawn as this mesh instead of a sprite. */
    val blockId: String? = null,
    /** An outpost structure raised as this model, voxelised into the pack's blocks. */
    val structureId: String? = null,
    /** A weapon base drawn with this model's baked sprite. */
    val weaponId: String? = null,
    /** A monster drawn with this model's baked sprite. */
    val enemyId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a model needs an id" }
        require(source.isNotBlank()) { "model '$id' needs a source" }
        require(height > 0f && height <= MAX_HEIGHT) { "model '$id' must be between 0 and $MAX_HEIGHT blocks tall, was $height" }
    }

    /** The forged asset this names, or null when the source is a file in the plugin. */
    val assetId: String? get() = source.takeIf { it.startsWith(ASSET_PREFIX) }?.removePrefix(ASSET_PREFIX)

    companion object {
        const val ASSET_PREFIX = "asset:"
        const val DEFAULT_HEIGHT = 1.5f

        /** Taller than this is a building, and belongs in a structure made of blocks. */
        const val MAX_HEIGHT = 32f
    }
}
