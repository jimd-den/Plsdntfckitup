package com.stratum.core.data.sprite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _sets = MutableStateFlow<List<String>>(emptyList())
    val sets: StateFlow<List<String>> = _sets.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _sets.value = listSetsFromDisk()
    }

    private fun listSetsFromDisk(): List<String> =
        root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { dir -> dir.listFiles().orEmpty().isNotEmpty() }
            .sortedByDescending { it.lastModified() }
            .map { it.name }

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
        refresh()
    }

    fun reference(setId: String): ByteArray? = read(File(setDir(setId), REFERENCE))

    fun savePose(setId: String, key: String, bytes: ByteArray) {
        File(writableDir(setId), "${key.replace(NON_FILE_SAFE, "_")}$SUFFIX").writeBytes(bytes)
        refresh()
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
     * Keeps the clip an animation was cut from.
     *
     * The clip is the expensive artefact, not the frames. It costs about ten
     * times a single still and it holds far more than the sheet takes out of
     * it -- four seconds at twenty-four frames is nearly a hundred pictures,
     * of which a twelve frame row uses ten. Throwing it away after one pass
     * means paying again to change the frame rate, and changing the frame rate
     * is the one thing a person will want to do twice.
     *
     * Kept per character rather than per run, so re-cutting is free and the
     * sheet can be rebuilt at a different rate without the model being asked
     * anything at all.
     */
    fun saveClip(setId: String, key: String, bytes: ByteArray) {
        File(writableDir(setId), clipName(key)).writeBytes(bytes)
        refresh()
    }

    fun clip(setId: String, key: String): ByteArray? = read(File(setDir(setId), clipName(key)))

    fun hasClip(setId: String, key: String): Boolean = File(setDir(setId), clipName(key)).isFile

    /** Which animations have a clip on disk, so a sheet can say what it can re-cut. */
    fun clipKeysIn(setId: String): Set<String> =
        setDir(setId).listFiles { file -> file.name.endsWith(CLIP_SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(CLIP_SUFFIX) }
            .toSet()

    private fun clipName(key: String): String =
        "${key.replace(NON_FILE_SAFE, "_")}$CLIP_SUFFIX"

    /**
     * Sets on disk that have something in them, most recently worked on first.
     *
     * Empty folders are skipped rather than listed. New ones are no longer
     * created by reading, but devices already carry the ones that were, and a
     * character with nothing in it is not a character.
     */
    fun sets(): List<String> = _sets.value.ifEmpty { listSetsFromDisk() }

    /** Removes folders that reading brought into existence and nothing filled. */
    fun forgetEmptySets(): Int {
        val empty = root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { dir -> dir.listFiles().orEmpty().isEmpty() }
        empty.forEach { it.delete() }
        if (empty.isNotEmpty()) refresh()
        return empty.size
    }

    fun deleteSet(setId: String) {
        setDir(setId).deleteRecursively()
        refresh()
    }

    /** Throws away one pose so it will be asked for again. */
    fun deletePose(setId: String, key: String) {
        File(setDir(setId), "${key.replace(NON_FILE_SAFE, "_")}$SUFFIX").delete()
        refresh()
    }

    private fun read(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    private companion object {
        const val DIRECTORY = "pose_sets"
        const val SUFFIX = ".png"

        /**
         * Distinct from the frame suffix so a clip is never mistaken for a
         * pose. [keysIn] lists what is already drawn and a run resumes from
         * it; a clip counted among them would be read as a finished frame and
         * the frame it stands for would never be generated.
         */
        const val CLIP_SUFFIX = ".mp4"
        const val REFERENCE = "reference.png"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
    }
}
