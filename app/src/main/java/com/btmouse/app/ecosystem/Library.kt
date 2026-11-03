package com.btmouse.app.ecosystem

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * This device's recordings and snapshots as part of the shared library. The PC asks
 * what is here (library_list) and fetches what it is missing (library_fetch), so it
 * holds a copy of everything and every device can browse and play it from there.
 */
object Library {
    private val audio = setOf("wav", "m4a", "mp3", "aac", "ogg")
    private val image = setOf("jpg", "jpeg", "png", "webp")

    private fun base(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun folderFor(context: Context, name: String): File {
        val ext = name.substringAfterLast('.', "").lowercase()
        val folder = when (ext) {
            in audio -> "Audios"
            in image -> "Snapshots"
            else -> "Videos"
        }
        return File(base(context), folder).apply { mkdirs() }
    }

    private fun localFiles(context: Context): List<File> =
        listOf("Videos", "Audios", "Snapshots").flatMap { dir ->
            File(base(context), dir).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0 } ?: emptyList()
        }

    fun answerList(context: Context, request: JsonObject, send: (JsonObject) -> Unit) {
        val items = JsonArray()
        localFiles(context).sortedByDescending { it.lastModified() }.take(400).forEach { f ->
            items.add(JsonObject().apply {
                addProperty("id", f.name)
                addProperty("name", f.name)
                addProperty("size", f.length())
                addProperty("modified", f.lastModified() / 1000)
                addProperty("type", when (f.parentFile?.name) { "Audios" -> "audio"; "Snapshots" -> "snapshot"; else -> "video" })
            })
        }
        val reply = JsonObject().apply {
            addProperty("type", "library_items")
            add("items", items)
        }
        request.get("from")?.takeIf { !it.isJsonNull }?.asString?.let { if (it != Ecosystem.PC_ID) reply.addProperty("to", it) }
        send(reply)
    }

    fun answerFetch(context: Context, request: JsonObject) {
        val name = File(request.get("id")?.asString ?: return).name
        val file = localFiles(context).firstOrNull { it.name == name } ?: return
        val to = request.get("from")?.takeIf { !it.isJsonNull }?.asString ?: Ecosystem.PC_ID
        val peerName = Ecosystem.devices.value.firstOrNull { it.id == to }?.name ?: "your PC"
        Ecosystem.files.sendLibraryFile(to, file, peerName)
    }

    /** This device's own copy of [name], if it has one (made here or fetched before). */
    fun localCopy(context: Context, name: String, size: Long = 0): File? {
        val f = File(folderFor(context, File(name).name), File(name).name)
        return f.takeIf { it.isFile && it.length() > 0 && (size <= 0 || it.length() == size) }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** A recording or snapshot on another phone or tablet. */
data class PeerLibraryItem(
    val deviceId: String,
    val deviceName: String,
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val type: String
)

/**
 * Browsing and playing another phone's recordings straight from it, without the PC.
 * Asks each linked device for its list (library_list → library_items) and fetches a
 * file over whatever link reaches that device: the same Wi-Fi, this phone's hub, the PC
 * passing it along, or the internet relay. Fetched files land in this device's own
 * Videos / Audios / Snapshots, so the second time they play at once.
 */
object PeerLibrary {
    private val byDevice = ConcurrentHashMap<String, List<PeerLibraryItem>>()
    private val _items = MutableStateFlow<List<PeerLibraryItem>>(emptyList())
    val items: StateFlow<List<PeerLibraryItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow<String?>(null)
    /** The file being fetched to play, while it is on its way. */
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val _ready = MutableStateFlow<Pair<Uri, String>?>(null)
    /** A fetched file ready to open (uri, mime type); the screen opens it and calls [consumed]. */
    val ready: StateFlow<Pair<Uri, String>?> = _ready.asStateFlow()

    @Volatile private var waitingFor: String? = null
    /** Files fetched to keep (Save), not to open. */
    private val saving = ConcurrentHashMap.newKeySet<String>()

    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    /** Names of other devices' files this device already has a copy of. */
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()
    @Volatile private var appContext: Context? = null

    fun refresh(context: Context? = null) {
        context?.let { appContext = it.applicationContext }
        val online = Ecosystem.devices.value.filter { !it.isPc }
        byDevice.keys.retainAll(online.map { it.id }.toSet())
        publish()
        online.forEach { d ->
            Ecosystem.sendTo(d.id, JsonObject().apply { addProperty("type", "library_list") })
        }
    }

    /** A device answered library_list. */
    fun onItems(json: JsonObject) {
        val from = json.get("from")?.takeIf { !it.isJsonNull }?.asString ?: return
        if (from == Ecosystem.PC_ID || from == Ecosystem.selfId) return
        val name = Ecosystem.devices.value.firstOrNull { it.id == from }?.name ?: "Device"
        byDevice[from] = json.getAsJsonArray("items")?.mapNotNull { el ->
            val o = el.asJsonObject
            val fileName = o.get("name")?.asString ?: return@mapNotNull null
            PeerLibraryItem(
                deviceId = from, deviceName = name,
                id = o.get("id")?.asString ?: fileName, name = File(fileName).name,
                size = o.get("size")?.asLong ?: 0L,
                modified = o.get("modified")?.asLong ?: 0L,
                type = o.get("type")?.asString ?: "video"
            )
        } ?: emptyList()
        publish()
    }

    private fun publish() {
        val all = byDevice.values.flatten().sortedByDescending { it.modified }
        _items.value = all
        appContext?.let { ctx -> _saved.value = all.filter { Library.localCopy(ctx, it.name, it.size) != null }.map { it.name }.toSet() }
    }

    /** Keep a copy of [item] on this device without opening it. */
    fun save(context: Context, item: PeerLibraryItem) {
        appContext = context.applicationContext
        if (Library.localCopy(context, item.name, item.size) != null) return publish()
        saving.add(item.name)
        Ecosystem.sendTo(item.deviceId, JsonObject().apply {
            addProperty("type", "library_fetch")
            addProperty("id", item.id)
        })
    }

    /** Copy everything other devices have that this one doesn't (of the given type, or all). */
    fun saveAll(context: Context, type: String? = null) {
        _items.value.filter { type == null || it.type == type }
            .filter { Library.localCopy(context, it.name, it.size) == null && it.name !in saving }
            .distinctBy { it.name }
            .forEach { save(context, it) }
    }

    /** Files being saved right now. */
    fun savingCount(): Int = saving.size

    /** Play [item]: at once if this device already has it, otherwise fetch it first. */
    fun play(context: Context, item: PeerLibraryItem) {
        Library.localCopy(context, item.name, item.size)?.let {
            _ready.value = Library.uriFor(context, it) to mimeFor(item)
            return
        }
        waitingFor = item.name
        _loading.value = item.name
        Ecosystem.sendTo(item.deviceId, JsonObject().apply {
            addProperty("type", "library_fetch")
            addProperty("id", item.id)
        })
    }

    /** Called as transfers change: open the file we were waiting for once it has arrived. */
    fun onTransfers(context: Context, transfers: List<TransferInfo>) {
        appContext = context.applicationContext
        if (saving.isNotEmpty()) {
            val finished = transfers.filter { !it.outgoing && it.name in saving && it.state in setOf("done", "failed") }
            if (finished.isNotEmpty()) {
                finished.forEach { saving.remove(it.name) }
                publish()
            }
        }
        val want = waitingFor ?: return
        val t = transfers.lastOrNull { !it.outgoing && it.name == want } ?: return
        when (t.state) {
            "done" -> {
                waitingFor = null
                _loading.value = null
                publish()
                val file = Library.localCopy(context, want) ?: return
                _ready.value = Library.uriFor(context, file) to mimeFor(byDevice.values.flatten().firstOrNull { it.name == want })
            }
            "failed" -> {
                waitingFor = null
                _loading.value = null
            }
        }
    }

    fun consumed() {
        _ready.value = null
    }

    private fun mimeFor(item: PeerLibraryItem?): String = when (item?.type) {
        "audio" -> "audio/*"
        "snapshot" -> "image/*"
        else -> "video/*"
    }
}
