package com.stratum.core.data.sprite

import java.io.File

/**
 * Writes a file so that it is either the old contents or the new, never half.
 *
 * Generated art is expensive and its presence on disk is how the pipeline
 * knows what is done. A plain write interrupted by the process being killed
 * leaves a truncated file that still exists — and "exists" is read as "done".
 * Writing beside it and renaming over it makes the swap a single step.
 */
internal object AtomicFiles {

    fun write(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        parent?.mkdirs()
        val temp = File(parent, ".${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // Some filesystems refuse to rename over an existing file.
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                target.writeBytes(bytes)
            }
        }
    }
}
