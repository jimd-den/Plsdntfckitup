package com.stratum.core.data.sprite

import android.content.Context
import com.stratum.core.domain.sprite.Joint
import com.stratum.core.domain.sprite.JointPoint
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.PoseGuideMode
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.PoseGuides
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which poses a character was drawn against.
 *
 * Kept per character rather than per session, and that is the whole point of
 * the file. A character whose art was generated against an imported OpenPose
 * skeleton has to be *rigged* against that same skeleton — otherwise the sword
 * is hung where the built-in pose put the hand, which is not where the drawing
 * put it. That is exactly the class of bug the skeleton was introduced to
 * remove, and it would come straight back the moment the two sources disagreed.
 *
 * Small and read on every resolver rebuild, so it is one file rather than one
 * per character.
 */
class PoseGuideStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private var cache: MutableMap<String, GuidesDto>? = null

    fun guidesFor(setId: String): PoseGuides =
        load()[setId]?.toDomain() ?: PoseGuides()

    fun save(setId: String, guides: PoseGuides) {
        val all = load()
        // Only the exact default is the absence of a record. The first version
        // of this dropped any set with nothing imported yet, which quietly
        // discarded "imported mode, chosen, nothing picked yet" and "words
        // only" alike -- a choice that survives until you leave the screen is
        // worse than one that never appeared.
        //
        // Compared against the default object rather than against a copy of
        // its fields. The copy went stale the moment the default style
        // changed, and the failure was silent and backwards: picking the style
        // that used to be the default looked like picking nothing, so it was
        // dropped and came back as the new default. Asking the type what its
        // default is cannot drift from the type.
        val isDefault = guides == PoseGuides(skeleton = guides.skeleton)
        if (isDefault) all.remove(setId) else all[setId] = guides.toDto()
        runCatching { file.writeText(json.encodeToString(all)) }
    }

    private fun load(): MutableMap<String, GuidesDto> {
        cache?.let { return it }
        val read = if (file.isFile) {
            runCatching {
                json.decodeFromString<Map<String, GuidesDto>>(file.readText()).toMutableMap()
            }.getOrNull()
        } else {
            null
        }
        return (read ?: mutableMapOf()).also { cache = it }
    }

    private companion object {
        const val FILE = "pose_guides.json"
    }
}

@Serializable
private data class GuidesDto(
    val mode: String,
    val style: String,
    /** Pose key to joint name to x,y. */
    val imported: Map<String, Map<String, List<Float>>>,
)

private fun PoseGuides.toDto() = GuidesDto(
    mode = mode.name,
    style = style.name,
    imported = imported.mapValues { (_, pose) ->
        pose.joints.entries.associate { (joint, point) ->
            joint.name to listOf(point.x, point.y)
        }
    },
)

private fun GuidesDto.toDomain() = PoseGuides(
    mode = runCatching { PoseGuideMode.valueOf(mode) }.getOrDefault(PoseGuideMode.BUILT_IN),
    style = runCatching { PoseGuideStyle.valueOf(style) }.getOrDefault(PoseGuideStyle.DIAGRAM),
    imported = imported.mapNotNull { (key, joints) ->
        val points = joints.mapNotNull { (name, xy) ->
            if (xy.size < 2) return@mapNotNull null
            val joint = runCatching { Joint.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            joint to JointPoint(xy[0], xy[1])
        }.toMap()
        // A pose missing joints cannot be posed or rigged from, and a partial
        // one would fail at whichever call site happened to ask first.
        if (points.size < Joint.entries.size) null else key to Pose(points)
    }.toMap(),
)
