package com.stratum.importer.common

import com.stratum.core.domain.importing.ImportException

/**
 * Path arithmetic for project files, which name each other relatively --
 * a map naming `../tilesets/grass.tsx`, a tileset naming `grass.png`.
 *
 * Always `/`-separated, whatever the platform, and never allowed to climb
 * out of the project root.
 */
object ProjectPaths {

    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw ImportException("'$path' points outside the project") else parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    /** The folder holding [path], or "" at the root. */
    fun parentOf(path: String): String = normalize(path).substringBeforeLast('/', missingDelimiterValue = "")

    /** Resolves [reference] as written inside the file at [from]. */
    fun resolve(from: String, reference: String): String =
        if (reference.startsWith("/")) normalize(reference) else normalize("${parentOf(from)}/$reference")

    fun extensionOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    fun baseNameOf(path: String): String = fileNameOf(path).substringBeforeLast('.')
}
