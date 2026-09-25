package com.stratum.importer.flame

/**
 * The few things the importer needs from a Flutter `pubspec.yaml`.
 *
 * Read line by line rather than with a YAML library: only top-level scalars
 * and the names of dependencies are wanted, and those are one per line in
 * every pubspec anyone writes.
 */
data class Pubspec(
    val name: String?,
    val description: String?,
    val version: String?,
    val dependencies: Set<String>,
) {
    val usesFlame: Boolean get() = "flame" in dependencies

    companion object {
        fun parse(text: String): Pubspec {
            val lines = text.lines()
            return Pubspec(
                name = scalar(lines, "name"),
                description = scalar(lines, "description"),
                version = scalar(lines, "version"),
                dependencies = dependencyNames(lines),
            )
        }

        private fun scalar(lines: List<String>, key: String): String? =
            lines.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')?.takeIf(String::isNotEmpty)

        /** Names directly under `dependencies:` and `dev_dependencies:`. */
        private fun dependencyNames(lines: List<String>): Set<String> {
            val names = mutableSetOf<String>()
            var inDependencies = false
            lines.forEach { line ->
                when {
                    line.isBlank() || line.trimStart().startsWith("#") -> Unit
                    !line.startsWith(" ") -> inDependencies = line.startsWith("dependencies:") || line.startsWith("dev_dependencies:")
                    inDependencies && DEPENDENCY.matches(line) -> names += line.trim().substringBefore(':')
                }
            }
            return names
        }

        /** Exactly one level of indentation: a dependency, not one of its settings. */
        private val DEPENDENCY = Regex("^ {2}[A-Za-z0-9_]+:.*")
    }
}
