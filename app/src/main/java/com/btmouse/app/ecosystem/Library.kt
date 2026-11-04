package com.btmouse.app.ecosystem

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
        // Another phone or tablet may also browse the photo gallery, if this phone shares it.
        if (request.get("gallery")?.asBoolean == true && Gallery.shared(context)) {
            Gallery.recent(context).forEach { items.add(it) }
        }
        val reply = JsonObject().apply {
            addProperty("type", "library_items")
            add("items", items)
        }
        request.get("from")?.takeIf { !it.isJsonNull }?.asString?.let { if (it != Ecosystem.PC_ID) reply.addProperty("to", it) }
        send(reply)
    }

    fun answerFetch(context: Context, request: JsonObject) {
        val id = request.get("id")?.asString ?: return
        if (id.startsWith(Gallery.PREFIX)) return Gallery.send(context, request, id)
        val name = File(id).name
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

/**
 * This phone's photos and videos (the camera roll), shared with the user's other phones
 * and tablets at full quality once they turn it on. Items are offered by MediaStore id
 * ("gallery:image:123"), and only ids that are still in the gallery can be fetched.
 */
object Gallery {
    const val PREFIX = "gallery:"
    private const val LIMIT = 300

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPermission(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun shared(context: Context): Boolean = Ecosystem.sharesGallery() && hasPermission(context)

    /** The newest photos and videos, as library items. */
    fun recent(context: Context): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        listOf("image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .forEach { (kind, collection) ->
                runCatching {
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                        null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        while (c.moveToNext() && out.count { it.get("type").asString == if (kind == "image") "photo" else "gallery_video" } < LIMIT) {
                            val name = c.getString(1) ?: continue
                            // Files other phones sent here are theirs already; don't offer them back.
                            if (PeerLibrary.isFromAnotherDevice(context, name)) continue
                            out.add(JsonObject().apply {
                                addProperty("id", "$PREFIX$kind:${c.getLong(0)}")
                                addProperty("name", name)
                                addProperty("size", c.getLong(2))
                                addProperty("modified", c.getLong(3))
                                addProperty("type", if (kind == "image") "photo" else "gallery_video")
                                addProperty("gallery", true)
                            })
                        }
                    }
                }
            }
        return out.sortedByDescending { it.get("modified").asLong }.take(LIMIT)
    }

    fun send(context: Context, request: JsonObject, id: String) {
        if (!shared(context)) return
        val to = request.get("from")?.takeIf { !it.isJsonNull }?.asString ?: return
        // Shared camera roll: PC companion and other devices can browse and fetch photos/videos.
        // Allow both PC and other devices to fetch gallery items
        val parts = id.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return
        val collection = when (parts[0]) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return
        }
        val uri = ContentUris.withAppendedId(collection, parts[1].toLongOrNull() ?: return)
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return
        val peerName = Ecosystem.devices.value.firstOrNull { it.id == to }?.name ?: "Device"
        Ecosystem.files.sendGalleryItem(to, peerName, uri, name)
    }
}

/** A recording or snapshot on another phone or tablet. */
data class PeerLibraryItem(
    val deviceId: String,
    val deviceName: String,
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val type: String,
    /** A photo or video from that device's gallery (not one of its recordings). */
    val gallery: Boolean = false
) {
    /** The Media tab filter it belongs under. */
    val category: String get() = when (type) {
        "photo" -> "snapshot"
        "gallery_video" -> "video"
        else -> type
    }
}

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
            Ecosystem.sendTo(d.id, JsonObject().apply {
                addProperty("type", "library_list")
                addProperty("gallery", true)
            })
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
                type = o.get("type")?.asString ?: "video",
                gallery = o.get("gallery")?.asBoolean ?: false
            )
        } ?: emptyList()
        publish()
    }

    private fun publish() {
        val all = byDevice.values.flatten().sortedByDescending { it.modified }
        _items.value = all
        appContext?.let { ctx -> _saved.value = all.filter { localUri(ctx, it) != null }.map { it.name }.toSet() }
    }

    /** Keep a copy of [item] on this device without opening it. */
    fun save(context: Context, item: PeerLibraryItem) {
        appContext = context.applicationContext
        if (localUri(context, item) != null) return publish()
        saving.add(item.name)
        Ecosystem.sendTo(item.deviceId, JsonObject().apply {
            addProperty("type", "library_fetch")
            addProperty("id", item.id)
        })
    }

    /** Copy everything other devices have that this one doesn't (of the given type, or all). */
    fun saveAll(context: Context, type: String? = null) {
        _items.value.filter { !it.gallery && (type == null || it.category == type) }
            .filter { localUri(context, it) == null && it.name !in saving }
            .distinctBy { it.name }
            .forEach { save(context, it) }
    }

    /** Files being saved right now. */
    fun savingCount(): Int = saving.size

    /** Play [item]: at once if this device already has it, otherwise fetch it first. */
    fun play(context: Context, item: PeerLibraryItem) {
        localUri(context, item)?.let {
            _ready.value = it to mimeFor(item)
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
                val item = byDevice.values.flatten().firstOrNull { it.name == want }
                val uri = item?.let { localUri(context, it) } ?: Library.localCopy(context, want)?.let { Library.uriFor(context, it) } ?: return
                _ready.value = uri to mimeFor(item)
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
        "snapshot", "photo" -> "image/*"
        else -> "video/*"
    }

    // Gallery items saved here live in the system gallery, so remember where each went.
    private const val GALLERY_PREFS = "peer_gallery_saves"

    fun rememberGallerySave(context: Context, name: String, size: Long, uri: Uri) {
        context.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, "$size|$uri").apply()
    }

    fun isFromAnotherDevice(context: Context, name: String): Boolean =
        context.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE).contains(name)

    /** Where this device's copy of [item] is, if it has one. */
    private fun localUri(context: Context, item: PeerLibraryItem): Uri? {
        if (!item.gallery) return Library.localCopy(context, item.name, item.size)?.let { Library.uriFor(context, it) }
        val saved = context.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE).getString(item.name, null) ?: return null
        val size = saved.substringBefore('|').toLongOrNull() ?: 0L
        val uri = Uri.parse(saved.substringAfter('|'))
        if (item.size > 0 && size != item.size) return null
        // Still there? (The user may have deleted it from the gallery.)
        val exists = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        return if (exists) uri else null
    }
}
