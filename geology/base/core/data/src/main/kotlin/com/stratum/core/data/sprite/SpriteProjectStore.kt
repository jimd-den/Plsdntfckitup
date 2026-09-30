package com.stratum.core.data.sprite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.core.domain.sprite.ActorRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.ClipMapping
import com.stratum.core.domain.sprite.FacingLayout
import com.stratum.core.domain.sprite.FramePivot
import com.stratum.core.domain.sprite.FrameRef
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteAtlas
import com.stratum.core.domain.sprite.SpriteOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps hand-mapped atlases and the art they were mapped from.
 *
 * Separate from [SpriteLibrary] on purpose, and the distinction is the whole
 * argument for this feature. The library holds *finished* sheets: baked,
 * flattened, cheap to draw and impossible to take apart again. This holds the
 * working file — the untouched source image and the decisions made about it —
 * so a walk that turned out half a pixel low can be nudged and re-baked a week
 * later without regenerating anything. Baking is a one-way door; this is what
 * keeps a copy of the room you were in before you walked through it.
 */
class SpriteProjectStore(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Projects on disk, most recently worked on first. */
    fun all(): List<SpriteAtlas> =
        root.listFiles { file -> file.name.endsWith(PROJECT_SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { read(it) }

    fun load(atlasId: String): SpriteAtlas? =
        read(File(root, "${slugFor(atlasId)}$PROJECT_SUFFIX"))

    /**
     * Saves the mapping, and the source art the first time it is seen.
     *
     * The image is written only when it is handed over, because the common save
     * is a person tapping a frame off and the pixels have not changed. The
     * mapping is written last for the same reason [SpriteLibrary] does it:
     * a half-written pair should read as a project that is not there rather
     * than as one that is broken.
     */
    fun save(atlas: SpriteAtlas, sourceBytes: ByteArray? = null) {
        val slug = slugFor(atlas.id)
        if (sourceBytes != null) File(root, "$slug$IMAGE_SUFFIX").writeBytes(sourceBytes)
        File(root, "$slug$PROJECT_SUFFIX").writeText(json.encodeToString(atlas.toDto()))
    }

    fun delete(atlasId: String) {
        val slug = slugFor(atlasId)
        File(root, "$slug$PROJECT_SUFFIX").delete()
        File(root, "$slug$IMAGE_SUFFIX").delete()
    }

    fun hasSource(atlasId: String): Boolean =
        File(root, "${slugFor(atlasId)}$IMAGE_SUFFIX").isFile

    /**
     * Whether the project was cut from the art the library now holds.
     *
     * A project keeps its own copy of the source, which is what stops
     * re-cutting from damaging the only copy of an image. The cost is that the
     * copy can go stale, and it does so silently: a generated character keeps
     * its id when it is drawn again, so opening the mapper on a character
     * regenerated yesterday reopened the project made from the *first*
     * version's pixels. The new art existed, was listed, was drawn in the
     * world, and could not be mapped -- the editor simply showed the old one.
     *
     * Compared by content rather than by timestamp. A file copied or restored
     * carries whatever date the copy was made, and the question here is
     * whether these are the same pixels.
     */
    fun matchesSource(atlasId: String, current: ByteArray?): Boolean {
        if (current == null) return true
        val file = File(root, "${slugFor(atlasId)}$IMAGE_SUFFIX")
        if (!file.isFile) return false
        if (file.length() != current.size.toLong()) return false
        return runCatching { file.readBytes().contentEquals(current) }.getOrDefault(false)
    }

    /**
     * The untouched source art. Not cached: it is read when the editor opens
     * and when a bake runs, and holding a full-size bitmap between those is a
     * few megabytes doing nothing.
     */
    fun sourceFor(atlasId: String): Bitmap? {
        val file = File(root, "${slugFor(atlasId)}$IMAGE_SUFFIX")
        if (!file.isFile) return null
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            )
        }.getOrNull()
    }

    private fun read(file: File): SpriteAtlas? = runCatching {
        json.decodeFromString(AtlasDto.serializer(), file.readText()).toDomain()
    }.getOrNull()

    private fun slugFor(atlasId: String): String = atlasId.replace(NON_FILE_SAFE, "_")

    private companion object {
        const val DIRECTORY = "sprite_projects"
        const val PROJECT_SUFFIX = ".atlas.json"
        const val IMAGE_SUFFIX = ".source.png"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
    }
}

// As with the sheet format: the domain type is not serializable, so the stored
// shape can stay still while the model in front of it moves.
@Serializable
private data class AtlasDto(
    val id: String,
    val name: String,
    val sourceId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val frames: List<FrameDto>,
    val clips: List<MappingDto>,
    val facing: String,
    val role: String,
    val origin: String,
)

@Serializable
private data class FrameDto(
    val id: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pivot: String,
    val flippedX: Boolean,
    val enabled: Boolean,
    val label: String,
)

@Serializable
private data class MappingDto(
    val state: String,
    val frameIds: List<String>,
    val frameDurationMs: Int,
    val loops: Boolean,
    /** Null for a clip whose frames were chosen rather than borrowed. */
    val borrowedFrom: String? = null,
)

private fun SpriteAtlas.toDto() = AtlasDto(
    id = id,
    name = name,
    sourceId = sourceId,
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    frames = frames.map {
        FrameDto(
            id = it.id,
            left = it.source.left,
            top = it.source.top,
            width = it.source.width,
            height = it.source.height,
            pivot = it.pivot.name,
            flippedX = it.flippedX,
            enabled = it.enabled,
            label = it.label,
        )
    },
    clips = clips.map {
        MappingDto(
            state = it.state.name,
            frameIds = it.frameIds,
            frameDurationMs = it.frameDurationMs,
            loops = it.loops,
            borrowedFrom = it.borrowedFrom?.name,
        )
    },
    facing = facing.name,
    role = role.name,
    origin = origin.name,
)

private fun AtlasDto.toDomain() = SpriteAtlas(
    id = id,
    name = name,
    sourceId = sourceId,
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    // A frame with no area cannot be constructed, so a corrupt one is dropped
    // rather than taking the whole project down with it: losing one cell of a
    // mapping is recoverable in a tap, losing the mapping is not.
    frames = frames.mapNotNull { dto ->
        runCatching {
            FrameRef(
                id = dto.id,
                source = SourceRect(dto.left, dto.top, dto.width, dto.height),
                pivot = runCatching { FramePivot.valueOf(dto.pivot) }
                    .getOrDefault(FramePivot.BOTTOM_CENTER),
                flippedX = dto.flippedX,
                enabled = dto.enabled,
                label = dto.label,
            )
        }.getOrNull()
    },
    clips = clips.mapNotNull { dto ->
        val state = runCatching { AnimationState.valueOf(dto.state) }.getOrNull()
            ?: return@mapNotNull null
        ClipMapping(
            state = state,
            frameIds = dto.frameIds,
            frameDurationMs = dto.frameDurationMs,
            loops = dto.loops,
            borrowedFrom = dto.borrowedFrom
                ?.let { name -> runCatching { AnimationState.valueOf(name) }.getOrNull() },
        )
    },
    facing = runCatching { FacingLayout.valueOf(facing) }.getOrDefault(FacingLayout.MIRRORED),
    role = runCatching { ActorRole.valueOf(role) }.getOrDefault(ActorRole.MONSTER),
    origin = runCatching { SpriteOrigin.valueOf(origin) }.getOrDefault(SpriteOrigin.IMPORTED),
)
