package com.macrobase.app.data.cache

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** How one kind of figures is written to and read from a saved snapshot. */
interface SnapshotCodec<T> {
    /** Bumped when the layout changes, so snapshots in the old layout are worked out again. */
    val version: Int
    fun write(out: DataOutputStream, value: T)
    fun read(input: DataInputStream): T
}

/** Figures read back from a snapshot, with the tag they were saved under. */
class SavedSnapshot<T>(val tag: String, val value: T)

/**
 * Saved snapshots of figures worked out from the diary (the rank, statistics, calendar months), so
 * a screen can show them at once, even right after the app starts.
 *
 * They are only copies of figures the app can always work out again, so they live in no-backup
 * storage and are never part of a backup or of the diary database. A missing, damaged or
 * older-version file is dropped and the figures are worked out again. Writes go to a temporary
 * file that replaces the old one in one step, so a snapshot is never left half-written.
 */
class SnapshotStore(
    private val directory: File,
    /** The app's version: figures saved by another version are worked out again. */
    private val appVersion: Long,
    private val maxFiles: Int = 48
) {

    @Synchronized
    fun <T> read(key: String, codec: SnapshotCodec<T>): SavedSnapshot<T>? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val matches = input.readInt() == MAGIC &&
                    input.readInt() == FORMAT &&
                    input.readLong() == appVersion &&
                    input.readInt() == codec.version &&
                    input.readUTF() == key
                if (!matches) {
                    null
                } else {
                    val tag = input.readUTF()
                    val value = codec.read(input)
                    if (input.readInt() == END) SavedSnapshot(tag, value) else null
                }
            } ?: run {
                file.delete()
                null
            }
        } catch (e: Exception) {
            // Damaged or from a layout this version cannot read: worked out again
            Log.w(TAG, "Dropped snapshot $key: ${e.message}")
            file.delete()
            null
        }
    }

    @Synchronized
    fun <T> write(key: String, tag: String, value: T, codec: SnapshotCodec<T>) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val target = fileFor(key)
            val temp = File(directory, target.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT)
                out.writeLong(appVersion)
                out.writeInt(codec.version)
                out.writeUTF(key)
                out.writeUTF(tag)
                codec.write(out, value)
                out.writeInt(END)
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
            trim()
        } catch (e: Exception) {
            // A snapshot is only a shortcut: failing to save one never affects the figures
            Log.w(TAG, "Could not save snapshot $key: ${e.message}")
        }
    }

    /** Drops every snapshot whose key matches [predicate]. */
    @Synchronized
    fun delete(predicate: (String) -> Boolean) {
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(SUFFIX) && predicate(file.name.removeSuffix(SUFFIX))) file.delete()
        }
    }

    /** Drops every snapshot, for example after a backup is restored. */
    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /** Keeps the newest [maxFiles] snapshots; older ranges and months are worked out again if asked for. */
    private fun trim() {
        val files = directory.listFiles { file -> file.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "Snapshot key $key" }
        return File(directory, key + SUFFIX)
    }

    private companion object {
        const val TAG = "SnapshotStore"
        const val SUFFIX = ".snap"
        const val MAGIC = 0x4D425353 // "MBSS"
        const val FORMAT = 1
        const val END = 0x454E4421 // "END!"
    }
}
