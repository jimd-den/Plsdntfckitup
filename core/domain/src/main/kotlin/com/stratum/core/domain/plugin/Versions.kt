package com.stratum.core.domain.plugin

/**
 * A semantic version, `major.minor.patch`. Anything after a `-` or `+` is
 * kept for display and ignored for ordering, which is all a plugin loader
 * needs of pre-release and build tags.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val label: String = "") : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch$label"

    companion object {
        /** `1`, `1.2` and `1.2.3-beta` all parse; missing parts are zero. */
        fun parse(text: String): Version? {
            val trimmed = text.trim().removePrefix("v")
            val core = trimmed.takeWhile { it.isDigit() || it == '.' }
            val parts = core.split('.').filter(String::isNotEmpty).map { it.toIntOrNull() ?: return null }
            if (parts.isEmpty() || parts.size > 3) return null
            return Version(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 }, trimmed.removePrefix(core))
        }
    }
}

/**
 * Which versions of a dependency a plugin accepts, in the notation package
 * managers use: `*`, `1.2.3` (exactly), `>=1.2`, `^1.2` (same major), `~1.2`
 * (same minor).
 */
class VersionRange private constructor(private val text: String, private val accepts: (Version) -> Boolean) {

    operator fun contains(version: Version): Boolean = accepts(version)

    override fun toString(): String = text

    companion object {
        val ANY = VersionRange("*") { true }

        fun parse(text: String): VersionRange? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == "*") return ANY
            val operator = trimmed.takeWhile { it in "^~>=" }
            val base = Version.parse(trimmed.removePrefix(operator)) ?: return null
            val accepts: (Version) -> Boolean = when (operator) {
                "" , "=" -> { v -> v.compareTo(base) == 0 }
                ">=" -> { v -> v >= base }
                "^" -> { v -> v >= base && v.major == base.major }
                "~" -> { v -> v >= base && v.major == base.major && v.minor == base.minor }
                else -> return null
            }
            return VersionRange(trimmed, accepts)
        }
    }
}
