package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImageRegion
import kotlin.math.roundToInt

/**
 * Finds sprite animations declared in a Flame game's Dart code.
 *
 * Most Flame games never export animation data: they cut sheets in code,
 * with `SpriteAnimationData.sequenced(...)` or `SpriteSheet(...).createAnimation(...)`.
 * This reads those calls when their arguments are literals, which is how
 * almost every tutorial and template writes them. It does not run Dart, so
 * an animation built from computed values is reported rather than guessed.
 */
internal class DartAnimationScanner(private val resolveImage: (String) -> String?) {

    data class Scan(val animations: List<SourceAnimation>, val warnings: List<String>)

    fun scan(code: String, path: String): Scan {
        val warnings = mutableListOf<String>()
        val images = imageVariables(code)
        val animations = sequenced(code, path, images, warnings) + fromSheets(code, path, warnings)
        return Scan(animations, warnings)
    }

    /**
     * Variables holding a loaded image, such as `final coins = await images.load('coins.png')`,
     * so an animation cut from `coins` knows which file that is.
     */
    private fun imageVariables(code: String): Map<String, String> =
        IMAGE_VARIABLE.findAll(code).associate { it.groupValues[1] to it.groupValues[2] }

    // ---- SpriteAnimationData.sequenced ------------------------------------

    private fun sequenced(code: String, path: String, variables: Map<String, String>, warnings: MutableList<String>): List<SourceAnimation> =
        SEQUENCED.findAll(code).mapNotNull { match ->
            val args = argumentsFrom(code, match.range.last + 1)
            val prefix = statementBefore(code, match.range.first)
            val images = IMAGE_LITERAL.findAll(prefix).toList()
            val imageRef = images.lastOrNull()?.groupValues?.get(1) ?: lastImageVariable(prefix, variables)
            // In a map of animations the statement holds the entries before this one too; start after the last one's image.
            val ownEntry = images.getOrNull(images.size - 2)?.let { prefix.substring(it.range.last + 1) } ?: prefix
            val label = labelOf(ownEntry, imageRef)
            sequencedAnimation(args, imageRef, label) ?: null.also {
                warnings += "$path: an animation near '${label.take(40)}' is built from values that are not literals and was left out"
            }
        }.toList()

    private fun sequencedAnimation(args: String, imageRef: String?, label: String): SourceAnimation? {
        val image = imageRef?.let(resolveImage) ?: return null
        val amount = intArg(args, "amount") ?: return null
        val stepTime = numberArg(args, "stepTime") ?: return null
        val (width, height) = vectorArg(args, "textureSize") ?: return null
        val (startX, startY) = vectorArg(args, "texturePosition") ?: (0 to 0)
        val perRow = intArg(args, "amountPerRow") ?: amount
        val frames = (0 until amount).map { index ->
            ImageRegion(image, startX + (index % perRow) * width, startY + (index / perRow) * height, width, height)
        }
        return SourceAnimation(label, frames, (stepTime * 1000).roundToInt())
    }

    /** The image variable named last before the call, when the image was loaded into one. */
    private fun lastImageVariable(prefix: String, variables: Map<String, String>): String? =
        IDENTIFIER.findAll(prefix).map { it.value }.lastOrNull(variables::containsKey)?.let(variables::getValue)

    // ---- SpriteSheet(...).createAnimation ----------------------------------

    private data class Sheet(val image: String, val width: Int, val height: Int)

    private fun fromSheets(code: String, path: String, warnings: MutableList<String>): List<SourceAnimation> {
        val sheets = SHEET_DECLARATION.findAll(code).mapNotNull { match ->
            val args = argumentsFrom(code, match.range.last + 1)
            val image = IMAGE_LITERAL.find(args)?.groupValues?.get(1)?.let(resolveImage) ?: return@mapNotNull null
            val (width, height) = vectorArg(args, "srcSize") ?: return@mapNotNull null
            match.groupValues[1] to Sheet(image, width, height)
        }.toMap()
        return sheets.flatMap { (variable, sheet) -> animationsOf(code, variable, sheet, path, warnings) }
    }

    private fun animationsOf(code: String, variable: String, sheet: Sheet, path: String, warnings: MutableList<String>): List<SourceAnimation> =
        Regex("\\b${Regex.escape(variable)}\\s*\\.\\s*createAnimation\\s*\\(").findAll(code).mapNotNull { match ->
            val args = argumentsFrom(code, match.range.last + 1)
            // Likewise, start after the previous entry's closing bracket.
            val label = labelOf(statementBefore(code, match.range.first).substringAfterLast(')'), null)
            val row = intArg(args, "row") ?: 0
            val from = intArg(args, "from") ?: 0
            val to = intArg(args, "to")
            val stepTime = numberArg(args, "stepTime")
            if (to == null || stepTime == null || to <= from) {
                warnings += "$path: '$variable.createAnimation' near '${label.take(40)}' needs literal 'to' and 'stepTime' and was left out"
                return@mapNotNull null
            }
            val frames = (from until to).map { column ->
                ImageRegion(sheet.image, column * sheet.width, row * sheet.height, sheet.width, sheet.height)
            }
            SourceAnimation(label, frames, (stepTime * 1000).roundToInt())
        }.toList()

    // ---- reading Dart --------------------------------------------------------

    /** The text of a call's argument list, from just after its `(` to the matching `)`. */
    private fun argumentsFrom(code: String, start: Int): String {
        var depth = 1
        for (index in start until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(start, index)
            }
        }
        return code.substring(start)
    }

    /** The statement leading up to [end], which is where its name and image are. */
    private fun statementBefore(code: String, end: Int): String {
        val start = code.lastIndexOfAny(charArrayOf(';', '{', '}'), end - 1) + 1
        return code.substring(start, end)
    }

    /** What the author called the animation: the names in its statement, and its image's name. */
    private fun labelOf(prefix: String, imageRef: String?): String {
        val names = IDENTIFIER.findAll(prefix.replace(IMAGE_LITERAL, " ")).map { it.value }.filterNot { it in NOISE }
        return (names + listOfNotNull(imageRef?.substringAfterLast('/')?.substringBeforeLast('.'))).joinToString(" ").trim()
    }

    private fun intArg(args: String, name: String): Int? = numberArg(args, name)?.roundToInt()

    private fun numberArg(args: String, name: String): Double? =
        Regex("\\b$name\\s*:\\s*(-?[0-9]*\\.?[0-9]+)").find(args)?.groupValues?.get(1)?.toDoubleOrNull()

    /** `Vector2(32, 48)` or `Vector2.all(32)`. */
    private fun vectorArg(args: String, name: String): Pair<Int, Int>? {
        Regex("\\b$name\\s*:\\s*Vector2\\s*\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)").find(args)?.let { match ->
            return match.groupValues[1].toDouble().roundToInt() to match.groupValues[2].toDouble().roundToInt()
        }
        Regex("\\b$name\\s*:\\s*Vector2\\.all\\s*\\(\\s*([0-9.]+)\\s*\\)").find(args)?.let { match ->
            val size = match.groupValues[1].toDouble().roundToInt()
            return size to size
        }
        return null
    }

    private companion object {
        val SEQUENCED = Regex("SpriteAnimationData\\s*\\.\\s*sequenced\\s*\\(")
        val SHEET_DECLARATION = Regex("\\b(\\w+)\\s*=\\s*SpriteSheet\\s*\\(")
        val IMAGE_VARIABLE = Regex("\\b(\\w+)\\s*=\\s*(?:await\\s+)?[\\w.]*\\bload\\s*\\(\\s*['\"]([^'\"]+\\.(?:png|webp|jpg|jpeg))['\"]", RegexOption.IGNORE_CASE)
        val IMAGE_LITERAL = Regex("['\"]([^'\"]+\\.(?:png|webp|jpg|jpeg))['\"]", RegexOption.IGNORE_CASE)
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val NOISE = setOf(
            "final", "var", "late", "const", "await", "return", "this", "game", "gameRef", "images", "load", "fromCache",
            "SpriteAnimation", "fromFrameData", "loadSpriteAnimation", "Flame", "animation", "animations", "new",
            "SpriteAnimationData", "sequenced", "amount", "stepTime", "textureSize", "texturePosition", "amountPerRow",
            "Vector2", "all", "loop", "true", "false", "row", "from", "to", "createAnimation", "sheet", "spriteSheet",
        )
    }
}
