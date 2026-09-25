package com.stratum.plugins.schema

import com.stratum.core.domain.importing.ImportException

/**
 * How plain values are spelled in plugin files: colours as `#AARRGGBB`, the
 * way artists write them, and enums by name, case ignored. Both refuse bad
 * input by name rather than guessing, so a typo in a plugin is an error its
 * author can fix, not a grey block nobody can explain.
 */
internal object SchemaValues {

    fun color(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

    fun color(text: String, field: String): Long {
        val hex = text.trim().removePrefix("#")
        val value = hex.toLongOrNull(16) ?: throw ImportException("$field: '$text' is not a colour like #FF3A7D44")
        return when (hex.length) {
            6 -> OPAQUE or value
            8 -> value
            else -> throw ImportException("$field: '$text' is not a colour like #FF3A7D44")
        }
    }

    inline fun <reified E : Enum<E>> enum(name: String, field: String): E =
        enumValues<E>().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: throw ImportException("$field: '$name' is not one of ${enumValues<E>().joinToString { it.name.lowercase() }}")

    fun name(value: Enum<*>): String = value.name.lowercase()

    private const val OPAQUE = 0xFF000000L
}
