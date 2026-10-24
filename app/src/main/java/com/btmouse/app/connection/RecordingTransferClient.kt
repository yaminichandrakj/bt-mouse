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
    val localPath: String? = null
)

/** Receives the authenticated recording catalog, local downloads, and online playback chunks. */
class RecordingTransferClient(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "recordings").apply { mkdirs() }
    private val _recordings = MutableStateFlow(scanLocalFiles())
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
    private var streaming = false
    private var streamBridge: RecordingStreamBridge? = null
    private var streamDigest: MessageDigest? = null

    @Synchronized fun query(send: (JsonObject) -> Unit) {
        send(JsonObject().apply { addProperty("type", "recordings_list") })
        _status.value = "Loading recordings from PC…"
    }

    @Synchronized fun download(id: String, send: (JsonObject) -> Unit) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        if (streaming) closeStream(sendStop = true, send = send) else cancelDownloadTransfer(send)
        resetTransfer(deletePartial = true)
        pendingRecordingId = id
        send(JsonObject().apply {
            addProperty("type", "recording_download")
            addProperty("id", id)
        })
        _status.value = "Preparing ${id}…"
    }

    @Synchronized fun stream(id: String, send: (JsonObject) -> Unit) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        if (streaming) closeStream(sendStop = true, send = send) else cancelDownloadTransfer(send)
        resetTransfer(deletePartial = true)
        try {
            val bridge = RecordingStreamBridge(File(id).extension)
            streamBridge = bridge
            streaming = true
            _streamUrl.value = bridge.url
            pendingRecordingId = id
            _status.value = "Opening ${id} from the PC…"
            send(JsonObject().apply {
                addProperty("type", "recording_stream")
                addProperty("id", id)
            })
        } catch (e: Exception) {
            closeStream(sendStop = false, send = send)
            _status.value = "Could not open online playback: ${e.message}"
        }
    }

    @Synchronized fun stopStream(send: (JsonObject) -> Unit) {
        closeStream(sendStop = true, send = send)
        resetTransfer(deletePartial = true)
        _status.value = "Online playback stopped"
    }

    @Synchronized fun delete(id: String, send: ((JsonObject) -> Unit)? = null) {
        if (id.isBlank() || id.length > 255 || File(id).name != id) return
        val local = File(directory, id)
        var localDeleted = false
        if (local.exists()) {
            localDeleted = local.delete()
        }
        if (send != null) {
            send(JsonObject().apply {
                addProperty("type", "recording_delete")
                addProperty("id", id)
            })
            _status.value = if (localDeleted) "Deleted local copy; deleting from PC…" else "Deleting from PC…"
        } else {
            if (localDeleted) {
                _status.value = "Deleted local recording: $id"
            }
        }
        publishRecordings()
    }

    @Synchronized fun handleMessage(message: JsonObject, send: (JsonObject) -> Unit) {
        when (message.get("type")?.asString.orEmpty()) {
            "recordings_list_response" -> {
                val array = message.getAsJsonArray("files")
                remoteFiles = array?.mapNotNull { element ->
                    try {
                        val item = element.asJsonObject
                        val id = item.get("id")?.asString.orEmpty()
                        val name = item.get("name")?.asString.orEmpty()
                        if (id.isBlank() || id.length > 255 || File(id).name != id || name != id) null
                        else RecordingEntry(
                            id = id,
                            name = name,
                            size = item.get("size")?.asLong ?: 0L,
                            modified = item.get("modified")?.asLong ?: 0L,
                            mediaType = item.get("media_type")?.asString ?: "media"
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()
                publishRecordings()
                _status.value = message.get("message")?.asString.orEmpty()
            }
            "recording_delete_response" -> {
                val success = message.get("success")?.asBoolean ?: false
                val msg = message.get("message")?.asString.orEmpty()
                _status.value = msg.ifBlank { if (success) "Recording deleted." else "Could not delete recording." }
                val deletedId = message.get("id")?.asString.orEmpty()
                if (deletedId.isNotBlank()) {
                    remoteFiles = remoteFiles.filter { it.id != deletedId }
                    publishRecordings()
                }
            }
            "recording_delete_error" -> {
                _status.value = message.get("message")?.asString ?: "Could not delete recording"
            }
            "recording_download_start" -> beginTransfer(message, send)
            "recording_download_chunk" -> receiveChunk(message, send)
            "recording_stream_start" -> beginTransfer(message, send, stream = true)
            "recording_stream_chunk" -> receiveChunk(message, send, stream = true)
            "recording_stream_complete" -> if (streaming) completeStream(message)
            "recording_stream_error" -> {
                if (streaming && (transferId == null || message.get("transfer_id")?.asString == transferId)) {
                    resetTransfer(deletePartial = true)
                    closeStream(sendStop = false, send = send)
                    _status.value = message.get("message")?.asString ?: "Online playback failed"
                }
            }
            "recording_download_complete" -> {
                if (message.get("transfer_id")?.asString == transferId) {
                    finalizeTransfer()
                    transferId = null
                    pendingRecordingId = null
                    received = booleanArrayOf()
                    receivedCount = 0
                    expectedChunks = 0
                }
            }
            "recording_download_error" -> {
                if (transferId == null || message.get("transfer_id")?.asString == transferId) {
                    resetTransfer(deletePartial = true)
                    _status.value = message.get("message")?.asString ?: "Recording download failed"
                }
            }
        }
    }

    private fun beginTransfer(message: JsonObject, send: (JsonObject) -> Unit, stream: Boolean = false) {
        if (stream && !streaming) {
            send(JsonObject().apply {
                addProperty("type", "recording_stream_stop")
                addProperty("transfer_id", message.get("transfer_id")?.asString.orEmpty())
            })
            return
        }
        val requestedId = pendingRecordingId
        val keepStream = streaming && stream
        resetTransfer(deletePartial = true)
        streaming = keepStream
        val id = message.get("transfer_id")?.asString.orEmpty()
        val name = message.get("name")?.asString.orEmpty()
        val size = message.get("size")?.asLong ?: 0L
        val chunkSize = message.get("chunk_size")?.asInt ?: 0
        val chunkCount = message.get("chunk_count")?.asInt ?: 0
        val sha = message.get("sha256")?.asString.orEmpty()
        if (id.isBlank() || name.isBlank() || name != requestedId || name.length > 255 || File(name).name != name ||
            size !in 1..(1024L * 1024L * 1024L) || chunkSize !in 1..49152 ||
            chunkCount !in 1..1_200_000 || sha.matches(Regex("[a-fA-F0-9]{64}")).not()) {
            pendingRecordingId = null
            _status.value = "PC sent invalid recording details"
            if (streaming) {
                send(JsonObject().apply { addProperty("type", "recording_stream_stop"); addProperty("transfer_id", id) })
                closeStream(sendStop = false, send = send)
            }
            return
        }
        val expected = (size + chunkSize - 1) / chunkSize
        if (expected != chunkCount.toLong()) {
            pendingRecordingId = null
            _status.value = "PC sent inconsistent recording details"
            if (streaming) {
                send(JsonObject().apply { addProperty("type", "recording_stream_stop"); addProperty("transfer_id", id) })
                closeStream(sendStop = false, send = send)
            }
            return
        }
        try {
            transferId = id
            pendingRecordingId = requestedId
            expectedSize = size
            expectedChunks = chunkCount
            expectedChunkSize = chunkSize
            expectedSha256 = sha.lowercase()
            streamDigest = if (streaming) MessageDigest.getInstance("SHA-256") else null
            received = BooleanArray(chunkCount)
            receivedCount = 0
            if (!streaming) {
                outputFile = File(directory, "$name.part")
                randomAccess = RandomAccessFile(outputFile, "rw").apply { setLength(size) }
            }
            _status.value = if (streaming) "Starting online playback of $name…" else "Downloading $name: 0%"
            send(JsonObject().apply {
                addProperty("type", "recording_download_ack")
                addProperty("transfer_id", id)
                addProperty("index", -1)
            })
        } catch (e: Exception) {
            if (streaming) closeStream(sendStop = true, send = send)
            resetTransfer(deletePartial = true)
            _status.value = "Could not create the downloaded file: ${e.message}"
        }
    }

    private fun receiveChunk(message: JsonObject, send: (JsonObject) -> Unit, stream: Boolean = false) {
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
                if (stream) {
                    val bridge = streamBridge ?: throw IllegalStateException("Online player is no longer available")
                    if (!bridge.offer(bytes)) return
                    streamDigest?.update(bytes)
                } else {
                    val output = randomAccess ?: throw IllegalStateException("Recording file is not open")
                    output.seek(index.toLong() * expectedChunkSize)
                    output.write(bytes)
                }
                received[index] = true
                receivedCount++
                val percent = receivedCount * 100 / expectedChunks
                _status.value = if (stream) "Streaming ${pendingRecordingId}: $percent% sent" else
                    "Downloading ${outputFile?.name?.removeSuffix(".part")}: $percent%"
            }
            send(JsonObject().apply {
                addProperty("type", "recording_download_ack")
                addProperty("transfer_id", id)
                addProperty("index", index)
            })
            if (receivedCount == expectedChunks && !stream) finalizeTransfer()
        } catch (e: Exception) {
            _status.value = "Could not save recording data: ${e.message}"
            if (stream) closeStream(sendStop = true, send = send)
            resetTransfer(deletePartial = true)
        }
    }

    private fun finalizeTransfer() {
        val part = outputFile ?: return
        if (receivedCount != expectedChunks || part.length() != expectedSize) return
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
            val finalFile = File(directory, part.name.removeSuffix(".part"))
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) throw IllegalStateException("Could not finalize the downloaded recording")
            outputFile = null
            publishRecordings()
            _status.value = "Saved ${finalFile.name} to this phone"
        } catch (e: Exception) {
            resetTransfer(deletePartial = true)
            _status.value = e.message ?: "Could not verify the downloaded recording"
        }
    }

    private fun completeStream(message: JsonObject) {
        if (message.get("transfer_id")?.asString != transferId) return
        try {
            val actual = streamDigest?.digest()?.joinToString("") { "%02x".format(it.toInt() and 0xff) }.orEmpty()
            if (receivedCount != expectedChunks || !actual.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException("Online recording failed its integrity check")
            }
            streamBridge?.finish()
            _status.value = "Playing ${pendingRecordingId} from the PC"
            transferId = null
            pendingRecordingId = null
            received = booleanArrayOf()
            receivedCount = 0
            expectedChunks = 0
            streamDigest = null
        } catch (e: Exception) {
            _status.value = e.message ?: "Online playback failed"
            closeStream(sendStop = false, send = {})
            resetTransfer(deletePartial = true)
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
        streamDigest = null
    }

    @Synchronized fun cancel() {
        if (transferId != null) _status.value = "Recording download interrupted"
        resetTransfer(deletePartial = true)
        closeStream(sendStop = false, send = {})
    }

    private fun closeStream(sendStop: Boolean, send: (JsonObject) -> Unit) {
        val activeId = transferId
        val wasStreaming = streaming
        if (sendStop && wasStreaming && (activeId != null || pendingRecordingId != null)) {
            send(JsonObject().apply {
                addProperty("type", "recording_stream_stop")
                if (activeId != null) addProperty("transfer_id", activeId)
                pendingRecordingId?.let { addProperty("id", it) }
            })
        }
        streamBridge?.close()
        streamBridge = null
        streaming = false
        streamDigest = null
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

    private fun scanLocalFiles(): List<RecordingEntry> = directory.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".part") }
        ?.map { file ->
            RecordingEntry(
                id = file.name,
                name = file.name,
                size = file.length(),
                modified = file.lastModified(),
                mediaType = if (file.extension.lowercase() in setOf("wav", "m4a", "mp3")) "audio" else "video",
                localPath = file.absolutePath
            )
        }?.sortedByDescending { it.modified } ?: emptyList()

    private fun publishRecordings() {
        val local = scanLocalFiles().associateBy { it.id }
        val merged = remoteFiles.map { remote ->
            val saved = local[remote.id]
            if (saved != null) remote.copy(localPath = saved.localPath) else remote
        }
        val remoteIds = remoteFiles.mapTo(mutableSetOf()) { it.id }
        _recordings.value = (merged + local.values.filterNot { it.id in remoteIds }).sortedByDescending { it.modified }
    }
}
