package com.btmouse.app.connection

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

data class RecordingEntry(
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val mediaType: String,
    val localPath: String? = null,
    val folder: String = when (mediaType) {
        "audio" -> "Audios"
        "snapshot" -> "Snapshots"
        else -> "Videos"
    }
)

/**
 * Receives the authenticated recording catalog, local downloads, and media playback.
 * Automatically organizes downloaded files into dedicated Videos/, Audios/, and Snapshots/ folders on the phone,
 * and serves completed files over a multi-connection HTTP Range bridge so Android VideoView / MediaPlayer never fail.
 */
class RecordingTransferClient(context: Context) {
    private val baseDir: File = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
    val videosDir: File = File(baseDir, "Videos").apply { mkdirs() }
    val audiosDir: File = File(baseDir, "Audios").apply { mkdirs() }
    val snapshotsDir: File = File(baseDir, "Snapshots").apply { mkdirs() }

    init {
        try {
            val legacyDir = File(context.applicationContext.filesDir, "recordings")
            if (legacyDir.exists() && legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { file ->
                    if (file.isFile && !file.name.endsWith(".part")) {
                        val destFolder = targetFolderForName(file.name)
                        val dest = File(destFolder, file.name)
                        if (!dest.exists()) file.renameTo(dest) else file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun targetFolderForName(fileName: String): File {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".mp3") -> audiosDir
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> snapshotsDir
            else -> videosDir
        }
    }

    private fun mediaTypeForName(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".mp3") -> "audio"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> "snapshot"
            else -> "video"
        }
    }

    private fun findLocalFile(idOrName: String): File? {
        val clean = File(idOrName).name
        for (dir in listOf(videosDir, audiosDir, snapshotsDir)) {
            val candidate = File(dir, clean)
            if (candidate.exists() && candidate.isFile && candidate.length() > 0L) {
                return candidate
            }
            val phoneConverted = File(dir, "${candidate.nameWithoutExtension}.phone.mp4")
            if (phoneConverted.exists() && phoneConverted.isFile && phoneConverted.length() > 0L) {
                return phoneConverted
            }
        }
        return null
    }

    private val _recordings = MutableStateFlow<List<RecordingEntry>>(scanLocalFiles())
    val recordings: StateFlow<List<RecordingEntry>> = _recordings.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _streamUrl = MutableStateFlow<String?>(null)
    val streamUrl: StateFlow<String?> = _streamUrl.asStateFlow()

    private var remoteFiles: List<RecordingEntry> = emptyList()
    private var transferId: String? = null
    private var outputFile: File? = null
    private var randomAccess: RandomAccessFile? = null
    private var expectedSize = 0L
    private var expectedChunks = 0
    private var expectedChunkSize = 0
    private var expectedSha256 = ""
    private var received: BooleanArray = booleanArrayOf()
    private var receivedCount = 0
    private var pendingRecordingId: String? = null
    private var autoPlayAfterDownload = false
    private var streamBridge: RecordingStreamBridge? = null

    @Synchronized fun query(send: (JsonObject) -> Unit) {
        send(JsonObject().apply { addProperty("type", "recordings_list") })
        _status.value = "Loading media library from PC…"
    }

    @Synchronized fun download(id: String, send: (JsonObject) -> Unit) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        cancelDownloadTransfer(send)
        resetTransfer(deletePartial = true)
        autoPlayAfterDownload = false
        pendingRecordingId = id
        send(JsonObject().apply {
            addProperty("type", "recording_download")
            addProperty("id", id)
        })
        _status.value = "Preparing $id…"
    }

    /**
     * Play a recording on the phone.
     * - If the file is already saved on the phone, plays it immediately via our local Range HTTP bridge.
     * - If the file is on the PC, downloads it to the phone's Videos/ or Audios/ folder and auto-plays as soon as complete.
     */
    @Synchronized fun stream(id: String, send: (JsonObject) -> Unit) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        val existingLocal = findLocalFile(id)
        if (existingLocal != null) {
            startLocalFilePlayback(existingLocal)
            return
        }
        cancelDownloadTransfer(send)
        resetTransfer(deletePartial = true)
        autoPlayAfterDownload = true
        pendingRecordingId = id
        _status.value = "Loading $id from PC for playback…"
        send(JsonObject().apply {
            addProperty("type", "recording_download")
            addProperty("id", id)
        })
    }

    @Synchronized private fun startLocalFilePlayback(file: File) {
        try {
            streamBridge?.close()
            val bridge = RecordingStreamBridge(file)
            streamBridge = bridge
            _streamUrl.value = bridge.url
            _status.value = "Playing ${file.name}"
        } catch (e: Exception) {
            _status.value = "Could not start player: ${e.message}"
        }
    }

    @Synchronized fun stopStream(send: (JsonObject) -> Unit) {
        if (autoPlayAfterDownload && transferId != null) {
            cancelDownloadTransfer(send)
            resetTransfer(deletePartial = true)
        }
        autoPlayAfterDownload = false
        streamBridge?.close()
        streamBridge = null
        _streamUrl.value = null
        _status.value = "Playback closed"
    }

    @Synchronized fun delete(id: String, send: ((JsonObject) -> Unit)? = null) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        var localDeleted = false
        for (dir in listOf(videosDir, audiosDir, snapshotsDir)) {
            val local = File(dir, id)
            if (local.exists() && local.delete()) {
                localDeleted = true
            }
        }
        if (send != null) {
            send(JsonObject().apply {
                addProperty("type", "recording_delete")
                addProperty("id", id)
            })
            _status.value = if (localDeleted) "Deleted local copy; deleting from PC…" else "Deleting from PC…"
        } else if (localDeleted) {
            _status.value = "Deleted local file: $id"
        }
        publishRecordings()
    }

    @Synchronized fun handleMessage(message: JsonObject, send: (JsonObject) -> Unit) {
        when (message.get("type")?.asString.orEmpty()) {
            "recordings_list_response" -> {
                remoteFiles = message.getAsJsonArray("recordings")?.mapNotNull { element ->
                    try {
                        val item = element.asJsonObject
                        val id = item.get("id")?.asString.orEmpty()
                        val name = item.get("name")?.asString.orEmpty()
                        val mType = item.get("media_type")?.asString ?: mediaTypeForName(name)
                        val folderName = item.get("folder")?.asString ?: when (mType) {
                            "audio" -> "Audios"
                            "snapshot" -> "Snapshots"
                            else -> "Videos"
                        }
                        if (id.isBlank() || name.isBlank() || File(id).name != id) null
                        else {
                            RecordingEntry(
                                id = id,
                                name = name,
                                size = item.get("size")?.asLong ?: 0L,
                                modified = item.get("modified")?.asLong ?: 0L,
                                mediaType = mType,
                                folder = folderName
                            )
                        }
                    } catch (_: Exception) { null }
                } ?: emptyList()
                publishRecordings()
                _status.value = message.get("message")?.asString.orEmpty()
            }
            "recording_delete_response" -> {
                val success = message.get("success")?.asBoolean ?: false
                val msg = message.get("message")?.asString.orEmpty()
                _status.value = msg.ifBlank { if (success) "Deleted." else "Could not delete item." }
                val deletedId = message.get("id")?.asString.orEmpty()
                if (deletedId.isNotBlank()) {
                    remoteFiles = remoteFiles.filter { it.id != deletedId }
                    publishRecordings()
                }
            }
            "recording_delete_error" -> {
                _status.value = message.get("message")?.asString ?: "Could not delete item"
            }
            "recording_download_start", "recording_stream_start" -> beginTransfer(message, send)
            "recording_download_chunk", "recording_stream_chunk" -> receiveChunk(message, send)
            "recording_download_complete", "recording_stream_complete" -> {
                if (message.get("transfer_id")?.asString == transferId) {
                    finalizeTransfer()
                }
            }
            "recording_download_error", "recording_stream_error" -> {
                if (transferId == null || message.get("transfer_id")?.asString == transferId) {
                    resetTransfer(deletePartial = true)
                    autoPlayAfterDownload = false
                    _status.value = message.get("message")?.asString ?: "Could not transfer recording from PC"
                }
            }
        }
    }

    private fun beginTransfer(message: JsonObject, send: (JsonObject) -> Unit) {
        val requestedId = pendingRecordingId
        val shouldAutoPlay = autoPlayAfterDownload
        resetTransfer(deletePartial = true)
        autoPlayAfterDownload = shouldAutoPlay
        val id = message.get("transfer_id")?.asString.orEmpty()
        val name = message.get("name")?.asString.orEmpty()
        val size = message.get("size")?.asLong ?: 0L
        val chunkSize = message.get("chunk_size")?.asInt ?: 0
        val chunkCount = message.get("chunk_count")?.asInt ?: 0
        val sha = message.get("sha256")?.asString.orEmpty()
        val safeName = File(name).name
        if (
            id.isBlank() || safeName.isBlank() || safeName != name ||
            (requestedId != null && safeName != requestedId && !safeName.startsWith(File(requestedId).nameWithoutExtension)) ||
            size <= 0L || size > 1024L * 1024L * 1024L ||
            chunkSize !in 256..65536 || chunkCount !in 1..500000 ||
            sha.length != 64
        ) {
            pendingRecordingId = null
            autoPlayAfterDownload = false
            _status.value = "Rejected invalid recording metadata"
            return
        }
        val expected = (size + chunkSize - 1) / chunkSize
        if (expected != chunkCount.toLong()) {
            pendingRecordingId = null
            autoPlayAfterDownload = false
            _status.value = "PC sent inconsistent recording details"
            return
        }
        try {
            transferId = id
            pendingRecordingId = requestedId
            expectedSize = size
            expectedChunks = chunkCount
            expectedChunkSize = chunkSize
            expectedSha256 = sha.lowercase()
            received = BooleanArray(chunkCount)
            receivedCount = 0
            val targetFolder = targetFolderForName(name)
            outputFile = File(targetFolder, "$name.part")
            randomAccess = RandomAccessFile(outputFile, "rw").apply { setLength(size) }
            _status.value = if (autoPlayAfterDownload) "Loading $name for playback: 0%" else "Downloading $name: 0%"
            send(JsonObject().apply {
                addProperty("type", "recording_download_ack")
                addProperty("transfer_id", id)
                addProperty("index", -1)
            })
        } catch (e: Exception) {
            resetTransfer(deletePartial = true)
            autoPlayAfterDownload = false
            _status.value = "Could not create downloaded file: ${e.message}"
        }
    }

    private fun receiveChunk(message: JsonObject, send: (JsonObject) -> Unit) {
        val id = message.get("transfer_id")?.asString.orEmpty()
        val index = message.get("index")?.asInt ?: -1
        if (id != transferId || index !in received.indices) return
        try {
            val bytes = Base64.decode(message.get("data")?.asString.orEmpty(), Base64.NO_WRAP)
            val expectedLength = if (index == expectedChunks - 1) {
                (expectedSize - index.toLong() * expectedChunkSize).toInt()
            } else expectedChunkSize
            if (bytes.size != expectedLength) throw IllegalArgumentException("Recording chunk has an invalid size")
            if (!received[index]) {
                val output = randomAccess ?: throw IllegalStateException("Recording file is not open")
                output.seek(index.toLong() * expectedChunkSize)
                output.write(bytes)
                received[index] = true
                receivedCount++
                val percent = receivedCount * 100 / expectedChunks
                val cleanName = outputFile?.name?.removeSuffix(".part").orEmpty()
                _status.value = if (autoPlayAfterDownload) {
                    "Loading $cleanName for playback: $percent%"
                } else {
                    "Downloading $cleanName: $percent%"
                }
            }
            send(JsonObject().apply {
                addProperty("type", "recording_download_ack")
                addProperty("transfer_id", id)
                addProperty("index", index)
            })
            if (receivedCount == expectedChunks) finalizeTransfer()
        } catch (e: Exception) {
            _status.value = "Could not save recording data: ${e.message}"
            resetTransfer(deletePartial = true)
            autoPlayAfterDownload = false
        }
    }

    private fun finalizeTransfer() {
        val part = outputFile ?: return
        if (receivedCount != expectedChunks || part.length() != expectedSize) return
        val shouldPlay = autoPlayAfterDownload
        try {
            randomAccess?.close()
            randomAccess = null
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(part).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException("Downloaded recording failed its integrity check")
            }
            val finalName = part.name.removeSuffix(".part")
            val destFolder = targetFolderForName(finalName)
            val finalFile = File(destFolder, finalName)
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) throw IllegalStateException("Could not save downloaded recording")
            outputFile = null
            transferId = null
            pendingRecordingId = null
            autoPlayAfterDownload = false
            received = booleanArrayOf()
            receivedCount = 0
            expectedChunks = 0
            publishRecordings()
            if (shouldPlay) {
                startLocalFilePlayback(finalFile)
            } else {
                _status.value = "Saved ${finalFile.name} to phone ${destFolder.name}/ folder"
            }
        } catch (e: Exception) {
            resetTransfer(deletePartial = true)
            autoPlayAfterDownload = false
            _status.value = e.message ?: "Could not verify the downloaded recording"
        }
    }

    private fun resetTransfer(deletePartial: Boolean) {
        try { randomAccess?.close() } catch (_: Exception) {}
        randomAccess = null
        if (deletePartial) outputFile?.delete()
        outputFile = null
        transferId = null
        pendingRecordingId = null
        received = booleanArrayOf()
        receivedCount = 0
        expectedChunks = 0
    }

    @Synchronized fun cancel() {
        if (transferId != null) _status.value = "Recording transfer interrupted"
        resetTransfer(deletePartial = true)
        autoPlayAfterDownload = false
        streamBridge?.close()
        streamBridge = null
        _streamUrl.value = null
    }

    private fun cancelDownloadTransfer(send: (JsonObject) -> Unit) {
        if (transferId != null || pendingRecordingId != null) {
            send(JsonObject().apply {
                addProperty("type", "recording_download_stop")
                transferId?.let { addProperty("transfer_id", it) }
                pendingRecordingId?.let { addProperty("id", it) }
            })
        }
    }

    private fun scanLocalFiles(): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        for ((dir, folderName) in listOf(videosDir to "Videos", audiosDir to "Audios", snapshotsDir to "Snapshots")) {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") }
                ?.forEach { file ->
                    entries.add(
                        RecordingEntry(
                            id = file.name,
                            name = file.name,
                            size = file.length(),
                            modified = file.lastModified(),
                            mediaType = mediaTypeForName(file.name),
                            localPath = file.absolutePath,
                            folder = folderName
                        )
                    )
                }
        }
        return entries.sortedByDescending { it.modified }
    }

    private fun publishRecordings() {
        val local = scanLocalFiles().associateBy { it.id }
        val merged = remoteFiles.map { remote ->
            val saved = local[remote.id] ?: local["${File(remote.id).nameWithoutExtension}.phone.mp4"]
            if (saved != null) remote.copy(localPath = saved.localPath, folder = saved.folder) else remote
        }
        val remoteIds = remoteFiles.mapTo(mutableSetOf()) { it.id }
        _recordings.value = (merged + local.values.filterNot { it.id in remoteIds }).sortedByDescending { it.modified }
    }
}
