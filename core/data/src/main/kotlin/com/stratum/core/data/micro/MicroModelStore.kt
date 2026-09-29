package com.stratum.core.data.micro

import com.stratum.core.domain.micro.MicroModel
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The player's microvoxel models: what the model studio saves, the play
 * screen places, and a plugin shares. One JSON file per model, written to a
 * temporary name and renamed into place, so a crash mid-save never leaves a
 * half model in the library.
 */
class MicroModelStore(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private val dir: File get() = root.apply { mkdirs() }

    /** Every readable model, newest first. One that will not read is left out, not fatal. */
    fun all(): List<MicroModel> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> runCatching { json.decodeFromString(MicroModelSchema.serializer(), f.readText()).toDomain() }.getOrNull() }

    fun find(id: String): MicroModel? = file(id).takeIf(File::isFile)?.let { f ->
        runCatching { json.decodeFromString(MicroModelSchema.serializer(), f.readText()).toDomain() }.getOrNull()
    }

    fun save(model: MicroModel) {
        val target = file(model.id)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(json.encodeToString(MicroModelSchema.serializer(), MicroModelSchema.of(model)))
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun file(id: String): File = File(dir, id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + SUFFIX)

    companion object {
        const val SUFFIX = ".micro.json"
    }
}
