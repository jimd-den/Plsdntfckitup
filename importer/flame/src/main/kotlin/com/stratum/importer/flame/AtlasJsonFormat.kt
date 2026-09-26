package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImageRegion
import com.stratum.importer.common.ProjectPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads the sprite atlas JSON that Aseprite and TexturePacker export, which
 * Flame loads through `flame_aseprite` and `flame_texturepacker`.
 *
 * Both are `{frames, meta}`. Aseprite names its animations with frame tags;
 * a TexturePacker atlas has none, so its animations are the frame names with
 * the frame number taken off -- `knight_walk_03.png` is frame three of
 * `knight_walk`, and `knight` is the character.
 */
internal object AtlasJsonFormat {

    private val json = Json { isLenient = true }

    /** Whether [text] is an atlas export rather than some other JSON. */
    fun recognises(text: String): Boolean =
        Regex("\"frames\"\\s*:").containsMatchIn(text) && Regex("\"meta\"\\s*:").containsMatchIn(text)

    /** Animations per character name. */
    fun read(text: String, path: String): Map<String, List<SourceAnimation>> {
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return emptyMap()
        val meta = root["meta"] as? JsonObject ?: return emptyMap()
        val image = meta.string("image")?.let { ProjectPaths.resolve(path, it) } ?: return emptyMap()
        val frames = framesOf(root, image)
        if (frames.isEmpty()) return emptyMap()
        val tags = (meta["frameTags"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val character = ProjectPaths.baseNameOf(path)
        return if (tags.isNotEmpty()) mapOf(character to tagged(frames, tags)) else byFrameName(frames, character)
    }

    private data class Frame(val name: String, val region: ImageRegion, val durationMs: Int)

    /** Aseprite writes frames as an object keyed by name, or as an array; TexturePacker as an object. */
    private fun framesOf(root: JsonObject, image: String): List<Frame> = when (val frames = root["frames"]) {
        is JsonObject -> frames.entries.mapNotNull { (name, value) -> frameOf(name, value as? JsonObject, image) }
        is JsonArray -> frames.filterIsInstance<JsonObject>().mapNotNull { frameOf(it.string("filename").orEmpty(), it, image) }
        else -> emptyList()
    }

    private fun frameOf(name: String, obj: JsonObject?, image: String): Frame? {
        val rect = obj?.get("frame") as? JsonObject ?: return null
        val width = rect.int("w") ?: return null
        val height = rect.int("h") ?: return null
        if (width <= 0 || height <= 0) return null
        val region = ImageRegion(image, rect.int("x") ?: 0, rect.int("y") ?: 0, width, height)
        return Frame(name, region, obj.int("duration") ?: DEFAULT_FRAME_MS)
    }

    private fun tagged(frames: List<Frame>, tags: List<JsonObject>): List<SourceAnimation> = tags.mapNotNull { tag ->
        val from = tag.int("from") ?: return@mapNotNull null
        val to = (tag.int("to") ?: return@mapNotNull null).coerceAtMost(frames.lastIndex)
        if (from > to) return@mapNotNull null
        val run = ordered(frames.subList(from, to + 1), tag.string("direction"))
        SourceAnimation(tag.string("name").orEmpty(), run.map(Frame::region), run.map(Frame::durationMs).average().toInt())
    }

    /** Aseprite plays a tag forward, backward, or forward then back again. */
    private fun ordered(run: List<Frame>, direction: String?): List<Frame> = when (direction?.lowercase()) {
        "reverse" -> run.reversed()
        "pingpong" -> run + run.reversed().drop(1).dropLast(1)
        else -> run
    }

    private fun byFrameName(frames: List<Frame>, fallbackCharacter: String): Map<String, List<SourceAnimation>> =
        frames.groupBy { animationName(it.name) }
            .map { (animation, run) ->
                val sorted = run.sortedBy { frameNumber(it.name) }
                characterOf(animation, fallbackCharacter) to
                    SourceAnimation(animation, sorted.map(Frame::region), sorted.map(Frame::durationMs).average().toInt())
            }
            .groupBy({ it.first }, { it.second })

    /** `knight_walk_03.png` -> `knight_walk`. */
    private fun animationName(frameName: String): String =
        frameName.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[ _\\-]*\\d+$"), "")

    private fun frameNumber(frameName: String): Int =
        Regex("(\\d+)(\\.[a-zA-Z]+)?$").find(frameName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** The words before the state word: `knight_walk` -> `knight`. */
    private fun characterOf(animation: String, fallback: String): String {
        val words = animation.split(Regex("[ _\\-]+"))
        val stateAt = words.indexOfFirst { AnimationNames.stateFor(it) != null }
        return if (stateAt > 0) words.take(stateAt).joinToString("_") else fallback
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private const val DEFAULT_FRAME_MS = 100
}
