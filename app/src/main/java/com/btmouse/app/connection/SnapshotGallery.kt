package com.btmouse.app.connection

import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class SnapshotEntry(val path: String, val capturedAt: Long)

/** Gallery for full-quality PC webcam snapshots stored in accessible Pictures directory. */
class SnapshotGallery(context: Context) {
    private val directory: File = run {
        val external = context.applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val dir = if (external != null) File(external, "Snapshots") else File(context.applicationContext.filesDir, "camera_snapshots")
        dir.apply { mkdirs() }
    }

    val directoryPath: String get() = directory.absolutePath

    private val _snapshots = MutableStateFlow(scan())
    val snapshots: StateFlow<List<SnapshotEntry>> = _snapshots.asStateFlow()

    init {
        try {
            val legacy = File(context.applicationContext.filesDir, "camera_snapshots")
            if (legacy.exists() && legacy.isDirectory && legacy.canonicalPath != directory.canonicalPath) {
                legacy.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.equals("jpg", ignoreCase = true)) {
                        val dest = File(directory, file.name)
                        if (!dest.exists()) file.renameTo(dest) else file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun refresh(): StateFlow<List<SnapshotEntry>> {
        _snapshots.value = scan()
        return snapshots
    }

    @Synchronized
    fun delete(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists() && file.parentFile?.canonicalPath == directory.canonicalPath) {
                val deleted = file.delete()
                _snapshots.value = scan()
                deleted
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun save(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return false
        return try {
            val timestamp = System.currentTimeMillis()
            val target = File(directory, "snapshot_$timestamp.jpg")
            val temporary = File(directory, "snapshot_$timestamp.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return false
            }
            val oldFiles = directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            oldFiles.drop(40).forEach { it.delete() }
            _snapshots.value = scan()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scan(): List<SnapshotEntry> = directory.listFiles()
        ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.take(40)
        ?.map { SnapshotEntry(it.absolutePath, it.lastModified()) }
        .orEmpty()
}
