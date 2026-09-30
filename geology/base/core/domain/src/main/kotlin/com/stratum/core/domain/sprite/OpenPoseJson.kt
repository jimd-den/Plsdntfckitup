package com.stratum.core.domain.sprite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the pose files the OpenPose ecosystem actually produces.
 *
 * Parsing lives in the domain for the same reason generated content parsing
 * does: what comes back is a content problem rather than a transport one, and
 * the awkward cases — a file with three people in it, pixel coordinates with no
 * canvas size, a bare array of numbers — are exactly what wants testing without
 * a device.
 *
 * Tolerant on purpose. There is no single OpenPose JSON schema; there is
 * OpenPose's own output, and then a dozen tools that each kept the parts they
 * needed. Refusing everything but the canonical shape would reject most of the
 * files a person actually has.
 */
object OpenPoseJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The first usable body in a file, or null.
     *
     * First rather than all, because a pose reference with several people in it
     * is a photograph of a crowd, and the character being drawn is one person.
     * Picking the most complete detection rather than literally the first is
     * what makes that choice usually right: a bystander half out of frame
     * should not win over the subject.
     */
    fun parse(text: String): OpenPoseBody? = parseAll(text).maxByOrNull { it.presentCount }

    fun parseAll(text: String): List<OpenPoseBody> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val canvas = canvasOf(root)
        return bodiesIn(root, canvas.first, canvas.second)
    }

    private fun bodiesIn(element: JsonElement, width: Float, height: Float): List<OpenPoseBody> =
        when (element) {
            // Some tools wrap the whole thing in an array of frames.
            is JsonArray -> {
                val numbers = element.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                if (numbers.size == element.size && numbers.isNotEmpty()) {
                    // A bare array of numbers is a single flattened skeleton.
                    listOfNotNull(
                        OpenPoseImport.fromFlatArray(numbers, imageWidth = width, imageHeight = height),
                    )
                } else {
                    element.flatMap { bodiesIn(it, width, height) }
                }
            }

            is JsonObject -> {
                val people = element["people"] as? JsonArray
                when {
                    people != null -> people.mapNotNull { person ->
                        val values = (person as? JsonObject)
                            ?.get("pose_keypoints_2d")
                            ?.let { flatten(it) }
                            ?: return@mapNotNull null
                        OpenPoseImport.fromFlatArray(values, imageWidth = width, imageHeight = height)
                    }
                    // ControlNet editors tend to emit a bare keypoints array.
                    element["pose_keypoints_2d"] != null || element["keypoints"] != null -> {
                        val values = flatten(element["pose_keypoints_2d"] ?: element["keypoints"]!!)
                        listOfNotNull(
                            OpenPoseImport.fromFlatArray(values, imageWidth = width, imageHeight = height),
                        )
                    }
                    else -> emptyList()
                }
            }

            else -> emptyList()
        }

    /**
     * Flattens every shape a keypoint list turns up in.
     *
     * `[x, y, c, ...]`, `[[x, y], ...]`, `[[x, y, c], ...]` and
     * `[{"x": .., "y": ..}, ...]`. All four are in circulation, and the last
     * was found the only way it could be — in a real pose library's page, which
     * stores exactly that and would have been rejected by the first three
     * readers as not a pose at all.
     *
     * The forms without a confidence get 1 invented for them, which is right: a
     * tool that omitted confidence did so because every point it kept was one
     * it was sure of.
     */
    private fun flatten(element: JsonElement): List<Float> {
        val array = element as? JsonArray ?: return emptyList()
        val direct = array.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
        if (direct.size == array.size) return direct

        return array.flatMap { entry ->
            when (entry) {
                is JsonArray -> {
                    val inner = entry.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                    when (inner.size) {
                        2 -> listOf(inner[0], inner[1], 1f)
                        3 -> inner
                        else -> emptyList()
                    }
                }

                is JsonObject -> {
                    val x = entry.floatField("x") ?: return@flatMap emptyList()
                    val y = entry.floatField("y") ?: return@flatMap emptyList()
                    listOf(x, y, entry.floatField("confidence", "c", "score") ?: 1f)
                }

                else -> emptyList()
            }
        }
    }

    /**
     * The canvas the coordinates are in, when the file says.
     *
     * Files disagree about this more than about anything else: some store
     * fractions, some store pixels and name the canvas, some store pixels and
     * do not. Where it is not named, the coordinates are assumed already
     * normalised — and [looksNormalised] checks that assumption rather than
     * trusting it, because a pixel-coordinate file read as fractions produces a
     * skeleton several hundred frames tall and the failure is silent.
     */
    private fun canvasOf(root: JsonElement): Pair<Float, Float> {
        val obj = (root as? JsonObject)
            ?: (root as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
            ?: return 1f to 1f
        val width = obj.positiveField("canvas_width", "width", "image_width")
        val height = obj.positiveField("canvas_height", "height", "image_height")
        return (width ?: 1f) to (height ?: 1f)
    }

    /** Any numeric value. Canvas sizes use [positiveField]; coordinates may be zero or negative. */
    private fun JsonObject.floatField(vararg names: String): Float? = names.firstNotNullOfOrNull {
        (this[it] as? JsonPrimitive)?.floatOrNull
    }

    /**
     * A canvas size, which must be positive.
     *
     * Kept apart from [floatField] because a keypoint legitimately sits at zero
     * — or outside the frame entirely, which real library poses do for arms
     * raised above the head — and a reader that discarded those would quietly
     * drop the most extreme poses, which are the ones worth importing.
     */
    private fun JsonObject.positiveField(vararg names: String): Float? =
        floatField(*names)?.takeIf { it > 0f }

    /**
     * Whether a parsed body is already in fractions of a frame.
     *
     * A file that stored pixels without naming its canvas comes out with
     * coordinates in the hundreds. Rescaling by the body's own bounds is the
     * only repair available, and it is a good one: the pose is what matters and
     * its absolute size never did.
     */
    fun looksNormalised(body: OpenPoseBody): Boolean =
        body.keypoints.values.filter { it.isPresent }
            .all { it.x in -1f..2f && it.y in -1f..2f }
}
