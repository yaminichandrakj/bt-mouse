package com.btmouse.app.connection

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class SnapshotEntry(val path: String, val capturedAt: Long, val folder: String = "Snapshots")

/** Gallery for full-quality webcam snapshots stored in the phone's dedicated Snapshots folder. */
class SnapshotGallery(context: Context) {
    private val directory: File = run {
        val baseExternal = context.applicationContext.getExternalFilesDir(null)
        val dir = if (baseExternal != null) {
            File(baseExternal, "Snapshots")
        } else {
            val picExternal = context.applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (picExternal != null) File(picExternal, "Snapshots") else File(context.applicationContext.filesDir, "Snapshots")
        }
        dir.apply { mkdirs() }
    }

    val directoryPath: String get() = directory.absolutePath

    private val _snapshots = MutableStateFlow(scan())
    val snapshots: StateFlow<List<SnapshotEntry>> = _snapshots.asStateFlow()

    init {
        // Migrate snapshots from legacy internal or Pictures locations into files/Snapshots
        try {
            val legacyDirs = listOfNotNull(
                File(context.applicationContext.filesDir, "camera_snapshots"),
                context.applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "Snapshots") }
            )
            for (legacy in legacyDirs) {
                if (legacy.exists() && legacy.isDirectory && legacy.canonicalPath != directory.canonicalPath) {
                    legacy.listFiles()?.forEach { file ->
                        if (file.isFile && file.extension.lowercase() in setOf("jpg", "jpeg", "png")) {
                            val dest = File(directory, file.name)
                            if (!dest.exists()) file.renameTo(dest) else file.delete()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        _snapshots.value = scan()
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
                ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            oldFiles.drop(60).forEach { it.delete() }
            _snapshots.value = scan()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scan(): List<SnapshotEntry> = directory.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        ?.sortedByDescending { it.lastModified() }
        ?.take(60)
        ?.map { SnapshotEntry(it.absolutePath, it.lastModified(), "Snapshots") }
        .orEmpty()
}
