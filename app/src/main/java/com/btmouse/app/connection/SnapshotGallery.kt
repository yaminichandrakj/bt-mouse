package com.btmouse.app.connection

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class SnapshotEntry(val path: String, val capturedAt: Long)

/** Small private on-device gallery for full-quality PC webcam snapshots. */
class SnapshotGallery(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "camera_snapshots").apply { mkdirs() }
    private val _snapshots = MutableStateFlow(scan())
    val snapshots: StateFlow<List<SnapshotEntry>> = _snapshots.asStateFlow()

    @Synchronized
    fun refresh(): StateFlow<List<SnapshotEntry>> {
        _snapshots.value = scan()
        return snapshots
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
