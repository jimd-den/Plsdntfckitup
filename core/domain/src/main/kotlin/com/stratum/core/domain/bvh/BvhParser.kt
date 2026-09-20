package com.stratum.core.domain.bvh

/**
 * Parses motion capture files in the BioVision Hierarchy (BVH) format used by the
 * Bandai Namco Research Motiondataset.
 *
 * BVH is structured into two main sections:
 * 1. HIERARCHY: Defines the bone structure, joint offsets, and rotation/translation channels.
 * 2. MOTION: Defines frame count, frame delta time, and lines of channel data floats.
 *
 * This parser is robust against differences in line endings, extra spacing, and optional
 * formatting variations common across motion capture exports.
 */
object BvhParser {

    fun parse(text: String, clipName: String = "mocap_clip"): Result<BvhClip> = runCatching {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) {
            throw IllegalArgumentException("BVH text is empty")
        }

        var index = 0

        fun peek(): String? = if (index < tokens.size) tokens[index] else null

        fun next(): String {
            if (index >= tokens.size) throw IllegalArgumentException("Unexpected end of BVH stream")
            return tokens[index++]
        }

        fun expect(expected: String) {
            val token = next()
            if (!token.equals(expected, ignoreCase = true)) {
                throw IllegalArgumentException("Expected '$expected' but found '$token' at token index ${index - 1}")
            }
        }

        // Section 1: HIERARCHY
        expect("HIERARCHY")

        val rootHeader = next()
        if (!rootHeader.equals("ROOT", ignoreCase = true)) {
            throw IllegalArgumentException("Expected ROOT joint but found '$rootHeader'")
        }

        val rootJoint = parseJoint(rootHeader, ::next, ::peek, ::expect)

        // Section 2: MOTION
        expect("MOTION")

        var frameCount = 0
        var frameTime = 1f / 30f // default 30 FPS

        while (peek() != null && !peek()!!.first().isDigit() && peek() != "-") {
            val token = next()
            when {
                token.equals("Frames:", ignoreCase = true) || token.equals("Frames", ignoreCase = true) -> {
                    frameCount = next().toIntOrNull() ?: throw IllegalArgumentException("Invalid frame count")
                }
                token.equals("Frame", ignoreCase = true) -> {
                    val nextTok = next()
                    if (nextTok.equals("Time:", ignoreCase = true) || nextTok.equals("Time", ignoreCase = true)) {
                        frameTime = next().toFloatOrNull() ?: (1f / 30f)
                    }
                }
                token.equals("Time:", ignoreCase = true) -> {
                    frameTime = next().toFloatOrNull() ?: (1f / 30f)
                }
            }
        }

        val totalChannels = rootJoint.totalChannels
        val frames = mutableListOf<BvhFrame>()

        while (index < tokens.size && (frameCount == 0 || frames.size < frameCount)) {
            val frameValues = FloatArray(totalChannels)
            var readValues = 0
            while (readValues < totalChannels && index < tokens.size) {
                val valueToken = next()
                val floatVal = valueToken.toFloatOrNull()
                if (floatVal != null) {
                    frameValues[readValues++] = floatVal
                }
            }
            if (readValues == totalChannels) {
                frames.add(BvhFrame(frameValues))
            } else if (readValues > 0) {
                // Incomplete frame at the end of the file
                break
            }
        }

        BvhClip(
            name = clipName,
            root = rootJoint,
            frames = frames,
            frameTime = frameTime,
        )
    }

    private fun parseJoint(
        headerToken: String,
        next: () -> String,
        peek: () -> String?,
        expect: (String) -> Unit,
    ): BvhJoint {
        val jointName = next()
        expect("{")

        var offset = FloatArray(3)
        val channels = mutableListOf<BvhChannelType>()
        val children = mutableListOf<BvhJoint>()
        var endSiteOffset: FloatArray? = null

        while (peek() != "}" && peek() != null) {
            val token = next()
            when {
                token.equals("OFFSET", ignoreCase = true) -> {
                    val ox = next().toFloatOrNull() ?: 0f
                    val oy = next().toFloatOrNull() ?: 0f
                    val oz = next().toFloatOrNull() ?: 0f
                    offset = floatArrayOf(ox, oy, oz)
                }
                token.equals("CHANNELS", ignoreCase = true) -> {
                    val count = next().toIntOrNull() ?: 0
                    for (i in 0 until count) {
                        val chName = next()
                        val chType = BvhChannelType.fromString(chName)
                            ?: throw IllegalArgumentException("Unknown channel type: $chName")
                        channels.add(chType)
                    }
                }
                token.equals("JOINT", ignoreCase = true) -> {
                    children.add(parseJoint(token, next, peek, expect))
                }
                token.equals("End", ignoreCase = true) -> {
                    val siteTok = next()
                    if (siteTok.equals("Site", ignoreCase = true)) {
                        expect("{")
                        while (peek() != "}" && peek() != null) {
                            val innerTok = next()
                            if (innerTok.equals("OFFSET", ignoreCase = true)) {
                                val ex = next().toFloatOrNull() ?: 0f
                                val ey = next().toFloatOrNull() ?: 0f
                                val ez = next().toFloatOrNull() ?: 0f
                                endSiteOffset = floatArrayOf(ex, ey, ez)
                            }
                        }
                        expect("}")
                    }
                }
            }
        }
        expect("}")

        return BvhJoint(
            name = jointName,
            offset = offset,
            channels = channels,
            children = children,
            endSiteOffset = endSiteOffset,
        )
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                    i++
                }
                c == '{' || c == '}' -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                    tokens.add(c.toString())
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }
}
