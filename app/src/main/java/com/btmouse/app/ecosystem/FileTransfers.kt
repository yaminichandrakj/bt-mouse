package com.btmouse.app.ecosystem

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.btmouse.app.R
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TransferInfo(
    val id: String,
    val name: String,
    val size: Long,
    val peerName: String,
    val outgoing: Boolean,
    val state: String,
    val progress: Float,
    val error: String? = null,
    val openUri: String? = null
)

/**
 * The phone's half of the chunked file protocol in pc_server/sync.py: 32 KB chunks,
 * each acked, unacked chunks resent, and resume from what was already received.
 */
class FileTransfers(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendRaw: (JsonObject) -> Unit
) {
    companion object {
        const val CHUNK_SIZE = 32 * 1024
        private const val WINDOW = 8
        private const val RESEND_AFTER_MS = 1500L
        private const val GIVE_UP_MS = 30_000L
        private const val CHANNEL_ID = "file_transfers"
    }

    private class Outgoing(
        val id: String, val name: String, val size: Long, val peer: String, val peerName: String,
        val chunks: Int
    ) {
        val done = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var accepted = false
        @Volatile var result: Boolean? = null
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var lastProgress = System.currentTimeMillis()
    }

    private class Incoming(
        val id: String, val name: String, val size: Long, val sha256: String, val replyTo: String?,
        val peerName: String, val part: File, val meta: File, val library: Boolean = false,
        val clipboard: Boolean = false
    ) {
        val chunks = maxOf(1, ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt())
        val done = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var state = "receiving"
        @Volatile var resultOk: Boolean? = null
    }

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val infos = LinkedHashMap<String, TransferInfo>()
    private val _transfers = MutableStateFlow<List<TransferInfo>>(emptyList())
    val transfers: StateFlow<List<TransferInfo>> = _transfers.asStateFlow()

    private fun publish(info: TransferInfo) {
        synchronized(infos) {
            infos[info.id] = info
            while (infos.size > 30) infos.remove(infos.keys.first())
            _transfers.value = infos.values.reversed()
        }
    }

    private fun addressed(obj: JsonObject, replyTo: String?): JsonObject {
        if (replyTo != null && replyTo != Ecosystem.PC_ID) obj.addProperty("to", replyTo)
        return obj
    }

    // --- sending -------------------------------------------------------
    /** Send one of this device's recordings for the shared library (no notification, filed by type). */
    fun sendLibraryFile(deviceId: String, file: File) {
        sendUri(deviceId, "your PC", Uri.fromFile(file), library = true, displayName = file.name)
    }

    fun sendUri(
        deviceId: String, peerName: String, uri: Uri, library: Boolean = false, displayName: String? = null,
        clipboard: Boolean = false
    ) {
        scope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            var name = displayName ?: "file"
            if (displayName == null) runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            }
            publish(TransferInfo(id, name, 0, peerName, true, "preparing", 0f))
            // A local copy gives a seekable file whatever the source app hands us.
            val local = File(File(context.cacheDir, "outgoing").apply { mkdirs() }, "$id.bin")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("Could not open the file")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val size = local.length()
                val t = Outgoing(id, name, size, deviceId, peerName, maxOf(1, ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()))
                outgoing[id] = t
                runSend(t, local, sha, library, clipboard)
            } catch (e: Exception) {
                publish(TransferInfo(id, name, local.length(), peerName, true, "failed", 0f, e.message ?: "Failed"))
            } finally {
                outgoing.remove(id)
                local.delete()
            }
        }
    }

    private suspend fun runSend(t: Outgoing, file: File, sha: String, library: Boolean, clipboard: Boolean = false) {
        val offer = JsonObject().apply {
            addProperty("type", "file_offer")
            addProperty("transfer_id", t.id)
            addProperty("name", t.name)
            addProperty("size", t.size)
            addProperty("sha256", sha)
            addProperty("chunk_size", CHUNK_SIZE)
            addProperty("chunks", t.chunks)
            addProperty("from_name", DeviceIdentity.name(context))
            if (library) addProperty("library", true)
            if (clipboard) addProperty("clipboard", true)
        }
        publish(TransferInfo(t.id, t.name, t.size, t.peerName, true, "waiting", 0f))
        val offerDeadline = System.currentTimeMillis() + GIVE_UP_MS
        while (!t.accepted) {
            if (t.cancelled || System.currentTimeMillis() > offerDeadline) throw IllegalStateException("The device did not answer")
            sendRaw(addressed(offer.deepCopy(), t.peer))
            repeat(20) { if (!t.accepted) delay(100) }
        }
        val sentAt = HashMap<Int, Long>()
        RandomAccessFile(file, "r").use { raf ->
            val channel: FileChannel = raf.channel
            val buffer = ByteBuffer.allocate(CHUNK_SIZE)
            var lastPublish = 0L
            while (t.done.size < t.chunks) {
                if (t.cancelled) throw IllegalStateException("Cancelled")
                val now = System.currentTimeMillis()
                if (now - t.lastProgress > GIVE_UP_MS) throw IllegalStateException("The connection was lost")
                var inFlight = sentAt.count { (i, ts) -> i !in t.done && now - ts < RESEND_AFTER_MS }
                for (i in 0 until t.chunks) {
                    if (inFlight >= WINDOW) break
                    if (i in t.done) continue
                    val ts = sentAt[i]
                    if (ts != null && now - ts < RESEND_AFTER_MS) continue
                    buffer.clear()
                    channel.read(buffer, i.toLong() * CHUNK_SIZE)
                    buffer.flip()
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    sendRaw(addressed(JsonObject().apply {
                        addProperty("type", "file_chunk")
                        addProperty("transfer_id", t.id)
                        addProperty("index", i)
                        addProperty("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }, t.peer))
                    sentAt[i] = now
                    inFlight++
                }
                delay(15)
                if (now - lastPublish > 250) {
                    lastPublish = now
                    publish(TransferInfo(t.id, t.name, t.size, t.peerName, true, "sending", t.done.size.toFloat() / t.chunks))
                }
            }
        }
        publish(TransferInfo(t.id, t.name, t.size, t.peerName, true, "verifying", 1f))
        val deadline = System.currentTimeMillis() + 15_000
        while (t.result == null && System.currentTimeMillis() < deadline && !t.cancelled) {
            sendRaw(addressed(JsonObject().apply {
                addProperty("type", "file_done")
                addProperty("transfer_id", t.id)
            }, t.peer))
            repeat(20) { if (t.result == null) delay(100) }
        }
        val ok = t.result == true
        publish(TransferInfo(t.id, t.name, t.size, t.peerName, true, if (ok) "done" else "failed", 1f,
            if (ok) null else (t.error ?: "The device did not confirm the file")))
    }

    fun cancel(id: String) {
        outgoing[id]?.cancelled = true
        incoming[id]?.let { inc ->
            inc.state = "failed"
            sendRaw(addressed(JsonObject().apply {
                addProperty("type", "file_cancel")
                addProperty("transfer_id", id)
            }, inc.replyTo))
            publish(TransferInfo(id, inc.name, inc.size, inc.peerName, false, "failed", inc.done.size.toFloat() / inc.chunks, "Cancelled"))
        }
    }

    // --- receiving -----------------------------------------------------
    fun handle(type: String, json: JsonObject) {
        val id = json.get("transfer_id")?.asString ?: return
        val from = json.get("from")?.takeIf { !it.isJsonNull }?.asString
        when (type) {
            "file_offer" -> onOffer(id, from, json)
            "file_chunk" -> onChunk(id, json)
            "file_done" -> scope.launch(Dispatchers.IO) { onDone(id) }
            "file_accept" -> outgoing[id]?.let { t ->
                (json.get("have") as? JsonArray)?.forEach { runCatching { t.done.add(it.asInt) } }
                t.lastProgress = System.currentTimeMillis()
                t.accepted = true
            }
            "file_chunk_ack" -> outgoing[id]?.let { t ->
                json.get("index")?.asInt?.let { t.done.add(it) }
                t.lastProgress = System.currentTimeMillis()
            }
            "file_result" -> outgoing[id]?.let { t ->
                t.error = json.get("error")?.takeIf { !it.isJsonNull }?.asString
                t.result = json.get("ok")?.asBoolean ?: false
            }
            "file_cancel" -> {
                outgoing[id]?.let { it.cancelled = true; it.error = "Cancelled by the other device" }
                incoming[id]?.let { inc ->
                    inc.state = "failed"
                    saveMeta(inc)
                    publish(TransferInfo(id, inc.name, inc.size, inc.peerName, false, "failed",
                        inc.done.size.toFloat() / inc.chunks, "Cancelled by the sender"))
                }
            }
        }
    }

    private fun safeName(raw: String?): String =
        (raw ?: "file").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[<>:\"|?*\\u0000-\\u001f]"), "_").trim(' ', '.').take(180).ifBlank { "file" }

    private fun onOffer(id: String, from: String?, json: JsonObject) {
        val sha = json.get("sha256")?.asString ?: return
        if (!sha.matches(Regex("[0-9a-f]{64}"))) return
        if ((json.get("chunk_size")?.asInt ?: CHUNK_SIZE) != CHUNK_SIZE) return
        val inc = incoming.getOrPut(id) {
            val size = json.get("size")?.asLong ?: 0L
            val dir = File(context.cacheDir, "partial").apply { mkdirs() }
            val key = "${sha.take(24)}_$size"
            val part = File(dir, "$key.part")
            val meta = File(dir, "$key.json")
            val created = Incoming(
                id, safeName(json.get("name")?.asString), size, sha, from,
                json.get("from_name")?.takeIf { !it.isJsonNull }?.asString ?: "your PC", part, meta,
                library = json.get("library")?.asBoolean ?: false,
                clipboard = json.get("clipboard")?.asBoolean ?: false
            )
            if (part.exists() && part.length() == size) {
                runCatching { meta.readText().split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { created.done.add(it) } }
            } else {
                RandomAccessFile(part, "rw").use { it.setLength(size) }
            }
            publish(TransferInfo(id, created.name, size, created.peerName, false, "receiving", created.done.size.toFloat() / created.chunks))
            created
        }
        sendRaw(addressed(JsonObject().apply {
            addProperty("type", "file_accept")
            addProperty("transfer_id", id)
            add("have", JsonArray().apply { inc.done.sorted().forEach { add(it) } })
        }, inc.replyTo))
    }

    private var lastIncomingPublish = 0L

    private fun onChunk(id: String, json: JsonObject) {
        val inc = incoming[id] ?: return
        if (inc.state != "receiving") return
        val index = json.get("index")?.asInt ?: return
        if (index !in 0 until inc.chunks) return
        if (index !in inc.done) {
            val bytes = runCatching { Base64.decode(json.get("data")?.asString ?: "", Base64.DEFAULT) }.getOrNull() ?: return
            runCatching {
                RandomAccessFile(inc.part, "rw").use { raf ->
                    raf.seek(index.toLong() * CHUNK_SIZE)
                    raf.write(bytes)
                }
                inc.done.add(index)
                if (inc.done.size % 32 == 0) saveMeta(inc)
            }.onFailure { return }
        }
        sendRaw(addressed(JsonObject().apply {
            addProperty("type", "file_chunk_ack")
            addProperty("transfer_id", id)
            addProperty("index", index)
        }, inc.replyTo))
        val now = System.currentTimeMillis()
        if (now - lastIncomingPublish > 250) {
            lastIncomingPublish = now
            publish(TransferInfo(id, inc.name, inc.size, inc.peerName, false, "receiving", inc.done.size.toFloat() / inc.chunks))
        }
    }

    private fun saveMeta(inc: Incoming) {
        runCatching { inc.meta.writeText(inc.done.sorted().joinToString(",")) }
    }

    @Synchronized
    private fun onDone(id: String) {
        val inc = incoming[id] ?: return
        inc.resultOk?.let { ok ->
            sendResult(inc, ok, if (ok) null else "Checksum mismatch")
            return
        }
        if (inc.done.size < inc.chunks) return
        val sha = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(inc.part).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
        val ok = sha == inc.sha256
        var openUri: Uri? = null
        if (ok) openUri = runCatching {
            when {
                inc.clipboard -> saveToClipboard(inc)
                inc.library -> saveToLibrary(inc)
                else -> saveToDownloads(inc)
            }
        }.getOrNull()
        val saved = ok && openUri != null
        inc.resultOk = saved
        inc.state = if (saved) "done" else "failed"
        sendResult(inc, saved, if (saved) null else if (!ok) "Checksum mismatch" else "Could not save the file")
        inc.meta.delete()
        if (!ok) inc.part.delete()
        publish(TransferInfo(id, inc.name, inc.size, inc.peerName, false, inc.state, 1f,
            if (saved) null else "Could not save the file", openUri?.toString()))
        if (saved && !inc.library && !inc.clipboard) notifyReceived(inc, openUri!!)
    }

    private fun sendResult(inc: Incoming, ok: Boolean, error: String?) {
        sendRaw(addressed(JsonObject().apply {
            addProperty("type", "file_result")
            addProperty("transfer_id", inc.id)
            addProperty("ok", ok)
            if (error != null) addProperty("error", error)
        }, inc.replyTo))
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** A picture copied on another device: keep the latest one and put it on the clipboard. */
    private fun saveToClipboard(inc: Incoming): Uri {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, inc.name)
        if (!inc.part.renameTo(dest)) {
            inc.part.copyTo(dest, overwrite = true)
            inc.part.delete()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        Ecosystem.setClipboardImage(uri, inc.peerName)
        return uri
    }

    /** Library files go next to this device's own recordings so the Library tab lists them. */
    private fun saveToLibrary(inc: Incoming): Uri {
        val dest = File(Library.folderFor(context, inc.name), inc.name)
        if (dest.exists() && dest.length() == inc.size) {
            inc.part.delete()
        } else if (!inc.part.renameTo(dest)) {
            inc.part.copyTo(dest, overwrite = true)
            inc.part.delete()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    }

    /** Downloads/Lanyard on Android 10+, the app's own Received folder before that. */
    private fun saveToDownloads(inc: Incoming): Uri {
        val mime = mimeFor(inc.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, inc.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lanyard")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No Downloads folder")
            resolver.openOutputStream(uri)?.use { out -> inc.part.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IllegalStateException("Could not write")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            inc.part.delete()
            return uri
        }
        val dir = File(context.getExternalFilesDir(null), "Received").apply { mkdirs() }
        var dest = File(dir, inc.name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, "${inc.name.substringBeforeLast('.')} ($n)${inc.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}")
            n++
        }
        if (!inc.part.renameTo(dest)) {
            inc.part.copyTo(dest, overwrite = true)
            inc.part.delete()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    }

    private fun notifyReceived(inc: Incoming, uri: Uri) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeFor(inc.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(context, inc.id.hashCode(), view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle("Received ${inc.name}")
                .setContentText("From ${inc.peerName} · saved to Downloads/Lanyard")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(inc.id.hashCode(), notification)
        }
    }
}
