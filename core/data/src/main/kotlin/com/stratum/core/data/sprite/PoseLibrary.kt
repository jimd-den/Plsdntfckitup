package com.stratum.core.data.sprite

import android.content.Context
import java.io.File

/**
 * The generated poses of a character, kept at full size.
 *
 * Two jobs, and both are about not throwing away expensive work. A full
 * character is forty calls to an image model; a run that dies on frame
 * thirty-one must be resumable at frame thirty-one rather than from the start,
 * so every pose is written the moment it arrives. And the composited sheet is a
 * downscale — once a set is on disk at 1024 pixels it can be re-composited at
 * any cell size later, which is the difference between choosing a sprite size
 * once and choosing it forever.
 *
 * The reference pose is stored beside them, because every regeneration of a
 * single frame needs it and losing it would strand the whole set.
 */
class PoseLibrary(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Where a set lives. Does not create it.
     *
     * It used to create it, which made *reading* a set bring it into
     * existence. The forge asks whether a set has a reference on every
     * keystroke of the subject line, so typing "Bronze Warrior" left behind a
     * folder for "B", "Br", "Bro" and every other prefix -- each of which then
     * appeared in the saved list as a character with no poses. Twelve entries
     * to delete by hand after naming one character.
     */
    private fun setDir(setId: String): File = File(root, setId.replace(NON_FILE_SAFE, "_"))

    /** The same folder, made ready to be written into. Only writers call this. */
    private fun writableDir(setId: String): File = setDir(setId).apply { mkdirs() }

    fun saveReference(setId: String, bytes: ByteArray) {
        File(writableDir(setId), REFERENCE).writeBytes(bytes)
    }

    fun reference(setId: String): ByteArray? = read(File(setDir(setId), REFERENCE))

    fun savePose(setId: String, key: String, bytes: ByteArray) {
        File(writableDir(setId), "${key.replace(NON_FILE_SAFE, "_")}$SUFFIX").writeBytes(bytes)
    }

    fun pose(setId: String, key: String): ByteArray? =
        read(File(setDir(setId), "${key.replace(NON_FILE_SAFE, "_")}$SUFFIX"))

    /** Which poses are already drawn, so a run can pick up where it stopped. */
    fun keysIn(setId: String): Set<String> =
        setDir(setId).listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            .filterNot { it == REFERENCE.removeSuffix(SUFFIX) }
            .toSet()

    fun hasReference(setId: String): Boolean = File(setDir(setId), REFERENCE).isFile

    /**
     * Sets on disk that have something in them, most recently worked on first.
     *
     * Empty folders are skipped rather than listed. New ones are no longer
     * created by reading, but devices already carry the ones that were, and a
     * character with nothing in it is not a character.
     */
    fun sets(): List<String> =
        root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { dir -> dir.listFiles().orEmpty().isNotEmpty() }
            .sortedByDescending { it.lastModified() }
            .map { it.name }

    /** Removes folders that reading brought into existence and nothing filled. */
    fun forgetEmptySets(): Int {
        val empty = root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { dir -> dir.listFiles().orEmpty().isEmpty() }
        empty.forEach { it.delete() }
        return empty.size
    }

    fun deleteSet(setId: String) {
        setDir(setId).deleteRecursively()
    }

    /** Throws away one pose so it will be asked for again. */
    fun deletePose(setId: String, key: String) {
        File(setDir(setId), "${key.replace(NON_FILE_SAFE, "_")}$SUFFIX").delete()
    }

    private fun read(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    private companion object {
        const val DIRECTORY = "pose_sets"
        const val SUFFIX = ".png"
        const val REFERENCE = "reference.png"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
    }
}
