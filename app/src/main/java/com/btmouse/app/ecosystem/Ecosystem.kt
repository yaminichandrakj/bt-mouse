package com.btmouse.app.ecosystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.control.RemoteControlService
import com.btmouse.app.control.RemoteKeyboardService
import com.btmouse.app.data.SettingsRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** Another device in the ecosystem, as the PC last described it. */
data class PeerDevice(
    val id: String,
    val name: String,
    val kind: String,
    val screenW: Int = 0,
    val screenH: Int = 0,
    val canBeControlled: Boolean = false,
    val acceptsFiles: Boolean = true
) {
    val isPc: Boolean get() = kind == "pc"
}

/** A live stream somewhere in the ecosystem (a phone's screen or camera). */
data class LiveStream(
    val id: String,
    val kind: String,
    val label: String,
    val owner: String,
    val ownerName: String,
    val viewers: Int
)

data class ClipboardHistoryEntry(val id: String, val text: String, val fromName: String, val timestamp: Long)

class StreamFrame(val streamId: String, val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * This device's part of the ecosystem: says hello to the PC, answers remote control,
 * syncs the clipboard, and moves files. Everything travels over whichever link the
 * coordinator is using; messages for another phone or tablet carry ``to`` and the PC
 * relays them.
 */
object Ecosystem {
    const val PC_ID = "pc"

    private lateinit var appContext: Context
    private var coordinator: ConnectionCoordinator? = null
    private var settings: SettingsRepository? = null
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    lateinit var files: FileTransfers
        private set

    private val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    /** Other devices reachable through the PC, the PC itself first. */
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()

    private val _controlledBy = MutableStateFlow<String?>(null)
    /** Name of the device currently driving this one, or null. */
    val controlledBy: StateFlow<String?> = _controlledBy.asStateFlow()

    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    /** Streams other devices are sharing. */
    val streams: StateFlow<List<LiveStream>> = _streams.asStateFlow()

    private val _watchedFrame = MutableStateFlow<StreamFrame?>(null)
    val watchedFrame: StateFlow<StreamFrame?> = _watchedFrame.asStateFlow()
    @Volatile private var watching: String? = null
    @Volatile private var cameraViewers = 0
    @Volatile private var pcScreenViewers = 0
    private var cameraAnnounced = false

    private val _lastClipboard = MutableStateFlow<String?>(null)
    val lastClipboardNotice: StateFlow<String?> = _lastClipboard.asStateFlow()
    private val _clipboardHistory = MutableStateFlow<List<ClipboardHistoryEntry>>(emptyList())
    val clipboardHistory: StateFlow<List<ClipboardHistoryEntry>> = _clipboardHistory.asStateFlow()

    private var lastClipId: String? = null
    private var lastClipImage: String? = null
    private var suppressNextLocalClip = false

    val selfId: String get() = DeviceIdentity.id(appContext)

    fun init(context: Context, coordinator: ConnectionCoordinator, settings: SettingsRepository, scope: CoroutineScope) {
        this.coordinator = coordinator
        this.settings = settings
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        loadClipboardHistory()
        files = FileTransfers(appContext, scope) { send(it) }
        Family.init(appContext)
        PeerLinks.init(appContext, scope)
        AndroidHub.init(appContext, scope)
        RelayLinks.init(appContext, scope, settings)
        scope.launch { PeerLinks.peers.collect { mergeDevices() } }
        scope.launch { RelayLinks.peers.collect { mergeDevices() } }
        scope.launch { files.transfers.collect { PeerLibrary.onTransfers(appContext, it) } }

        scope.launch {
            coordinator.isConnected.collectLatest { connected ->
                if (!connected) {
                    _controlledBy.value = null
                    RemoteControlService.instance?.setControlled(false)
                    return@collectLatest
                }
                delay(400)
                while (true) {
                    sendHello()
                    send(JsonObject().apply { addProperty("type", "devices_query") })
                    send(JsonObject().apply { addProperty("type", "streams_query") })
                    // The PC may have restarted: say again what this device is sharing.
                    if (ScreenShare.active.value) announceStream("screen", "Screen", true)
                    if (cameraAnnounced) announceStream("camera", "Camera", true)
                    delay(10_000)
                }
            }
        }

        // Keep telling the device we watch that we are still here, with or without a PC.
        scope.launch {
            while (true) {
                delay(8_000)
                watching?.let { watch(it) }
            }
        }

        appContext.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                main.postDelayed({ sendHello() }, 300)
                ScreenShare.onConfigurationChanged()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })

        // The phone camera (already streamed to the PC as a webcam) is also offered as a live stream.
        scope.launch {
            coordinator.phoneCameraStreamer.isStreaming.collect { on ->
                if (on != cameraAnnounced) {
                    cameraAnnounced = on
                    announceStream("camera", "Camera", on)
                }
            }
        }

        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            if (suppressNextLocalClip) {
                suppressNextLocalClip = false
                return@addPrimaryClipChangedListener
            }
            // Android only lets the focused app or the active keyboard read this; elsewhere it is null.
            pushClipboardIfNew()
        }
    }

    // --- device group ----------------------------------------------------------
    /** Another member changed the group (someone joined, was removed, or it was restored). */
    private fun onGroupUpdate(json: JsonObject) {
        // Packets from the PC carry no "from".
        val from = json.get("from")?.takeIf { !it.isJsonNull }?.asString ?: PC_ID
        when (Group.applyUpdate(json)) {
            // Pass it on so every member hears it; members that already have it ignore it.
            "applied" -> broadcastGroup(json.deepCopy().apply { remove("from"); remove("to") }, except = from)
            "removed" -> {
                _lastClipboard.value = "This device was removed from your devices"
                coordinator?.disconnectAll()
            }
        }
    }

    /** Send a group update to every member reachable right now: the PC, this hub's clients, peers and the relay. */
    private fun broadcastGroup(update: JsonObject, except: String? = null) {
        if (except != PC_ID && coordinator?.isConnected?.value == true) coordinator?.sendJsonObject(update.deepCopy())
        AndroidHub.broadcast(update, except = except)
        PeerLinks.peers.value.filter { it.id != except }.forEach { PeerLinks.sendTo(it.id, update.deepCopy()) }
        RelayLinks.broadcast(update)
    }

    /** Someone joined through this phone's live code: tell the other members. */
    fun onGroupJoined(name: String) {
        _lastClipboard.value = "$name joined your devices"
        broadcastGroup(Group.rosterUpdate())
        uploadGroupBackup()
    }

    /** Remove a device: everyone else gets a new key it never sees. */
    fun removeFromGroup(deviceId: String): Boolean {
        val update = Group.remove(deviceId) ?: return false
        broadcastGroup(update)
        Group.relink()
        uploadGroupBackup()
        return true
    }

    /** Rejoin with the recovery words. Null on success, otherwise a message. */
    suspend fun restoreGroup(words: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val worker = relayUrl() ?: return@withContext "Set up internet access in Lanyard for PC first: the backup lives on your Worker."
        val gid = Group.groupIdForWords(words) ?: return@withContext "Those words don't check out. Look for a typo."
        val blob = runCatching {
            val body = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
                .url("$worker/g/${GroupCrypto.backupId(gid)}").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            com.google.gson.JsonParser.parseString(body ?: return@runCatching null).asJsonObject.get("blob").asString
        }.getOrNull() ?: return@withContext "No backup found for these words on your Worker."
        val update = Group.restore(words, blob) ?: return@withContext "The backup couldn't be opened with these words."
        broadcastGroup(update)
        Group.relink()
        uploadGroupBackup()
        null
    }

    /** Keep the sealed recovery backup on the user's Worker. */
    fun uploadGroupBackup() {
        val worker = relayUrl() ?: return
        Thread {
            runCatching {
                val record = Group.backupRecord()
                val body = record.toString().toRequestBody("application/json".toMediaTypeOrNull())
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
                    .url("$worker/g/${record.get("id").asString}").put(body).build()).execute().close()
            }
        }.start()
    }

    /** Lanyard for PC called this phone from [host]: connect to it now. */
    fun onPcCall(host: String, port: Int) {
        main.post { coordinator?.answerPcCall(host, port) }
    }

    /** The relay Worker this phone uses, to hand on to devices that pair with it. */
    fun relayUrl(): String? = settings?.relayUrl?.value?.takeIf { it.isNotBlank() }
        ?: settings?.rvWorker?.value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun adoptRelayUrl(url: String) {
        if (url.isNotBlank() && settings?.relayUrl?.value.isNullOrBlank()) settings?.setRelayUrl(url)
    }

    /** The PC's LAN address while the Wi-Fi link is up, for the HTTP file fast path. */
    fun pcLanHost(): String? {
        val c = coordinator ?: return null
        if (!c.wifiClientManager.isConnected.value) return null
        return settings?.wifiHost?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun onControlCapabilityChanged() {
        if (initialized) main.post { sendHello() }
    }

    // --- outgoing ------------------------------------------------------
    fun send(obj: JsonObject) {
        // Devices connected straight to this phone (no PC) are reached through its hub.
        val to = obj.get("to")?.takeIf { !it.isJsonNull }?.asString
        if (to != null && (AndroidHub.sendTo(to, obj) || PeerLinks.sendTo(to, obj))) return
        // Off the network the PC knows, or with no PC at all: go through the internet relay.
        val viaPc = coordinator?.isConnected?.value == true && pcDevices.any { it.id == to }
        if (to != null && !viaPc && RelayLinks.sendTo(to, obj)) return
        coordinator?.sendJsonObject(obj)
    }

    fun onHubClientsChanged() {
        mergeDevices()
    }

    /** Address a message to another device; the PC needs no ``to``. */
    fun sendTo(deviceId: String, obj: JsonObject) {
        if (deviceId != PC_ID) obj.addProperty("to", deviceId)
        send(obj)
    }

    fun sendHello() {
        if (!initialized) return
        send(helloPayload())
    }

    /** What this device tells others about itself: name, screen and what it allows. */
    fun helloPayload(): JsonObject {
        val (w, h) = DeviceIdentity.screenSize(appContext)
        val allowControl = settings?.allowRemoteControl?.value ?: true
        return JsonObject().apply {
            addProperty("type", "device_hello")
            addProperty("device_id", selfId)
            addProperty("name", DeviceIdentity.name(appContext))
            addProperty("kind", if (DeviceIdentity.isTablet(appContext)) "tablet" else "phone")
            addProperty("screen_w", w)
            addProperty("screen_h", h)
            addProperty("density", appContext.resources.displayMetrics.density)
            addProperty("rotation", DeviceIdentity.rotationDegrees(appContext))
            add("clipboard_history_ids", JsonArray().apply {
                _clipboardHistory.value.forEach { add(it.id) }
            })
            add("caps", JsonObject().apply {
                addProperty("control", allowControl && RemoteControlService.instance != null)
                addProperty("lock", allowControl && RemoteControlService.instance != null)
                // Android 13+ types every key through Lanyard control, no keyboard switch needed.
                addProperty("keyboard", RemoteKeyboardService.instance != null ||
                    (android.os.Build.VERSION.SDK_INT >= 33 && RemoteControlService.instance != null))
                addProperty("clipboard", settings?.clipboardSync?.value ?: true)
                addProperty("files", true)
                addProperty("clipboard_image", settings?.clipboardSync?.value ?: true)
                addProperty("library", true)
                addProperty("streams", true)
                addProperty("mirror", true)
            })
        }
    }

    // --- incoming ------------------------------------------------------
    private val handledTypes = setOf(
        "device_hello_ack", "control_state", "remote_input", "clipboard_push", "clipboard_history", "devices_update",
        "file_offer", "file_accept", "file_chunk", "file_chunk_ack", "file_done", "file_result", "file_cancel",
        "streams_update", "stream_frame", "stream_demand", "library_list", "library_fetch", "library_items",
        "stream_watch", "screen_request", "stream_audio", "stream_h264", "stream_keyframe", "group_update", "lock_device"
    )

    /** Called by every transport for every packet; returns true when the packet was ours. */
    fun handleIncoming(json: JsonObject): Boolean {
        val type = json.get("type")?.asString ?: return false
        if (type !in handledTypes || !initialized) return false
        when (type) {
            "device_hello_ack" -> {
                // Only the PC hands out the family key in its hello answer.
                val gid = json.get("group_id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val gkey = json.get("group_key")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (gkey.isNotEmpty()) Family.adoptFromPc(gid, gkey)
                json.get("relay_url")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
                    settings?.setRelayUrl(it)
                }
            }
            "stream_watch" -> onStreamWatch(json)
            "stream_audio" -> onStreamAudio(json)
            "stream_h264" -> H264Viewer.accept(json)
            "stream_keyframe" -> if (json.get("from")?.asString in h264Watchers) H264Encoder.requestKeyframe()
            "screen_request" -> onScreenRequest(json)
            "control_state" -> {
                val active = json.get("active")?.asBoolean ?: false
                val allowed = settings?.allowRemoteControl?.value ?: true
                _controlledBy.value = if (active && allowed) (json.get("controller")?.asString ?: json.get("from_name")?.asString ?: "Another device") else null
                RemoteControlService.instance?.setControlled(active && allowed)
            }
            "remote_input" -> if (settings?.allowRemoteControl?.value != false) {
                RemoteControlService.instance?.handle(json)
            }
            "lock_device" -> if (settings?.allowRemoteControl?.value != false) {
                RemoteControlService.instance?.lockScreen()
            }
            "clipboard_push" -> applyRemoteClipboard(json)
            "clipboard_history" -> mergeClipboardHistory(json.getAsJsonArray("entries"))
            "devices_update" -> parseDevices(json.getAsJsonArray("devices"))
            "streams_update" -> parseStreams(json.getAsJsonArray("streams"))
            "stream_frame" -> onStreamFrame(json)
            "stream_demand" -> {
                val id = json.get("stream_id")?.asString ?: ""
                val n = json.get("viewers")?.asInt ?: 0
                when {
                    id.endsWith(":screen") -> {
                        pcScreenViewers = n
                        updateScreenViewers()
                    }
                    id.endsWith(":camera") -> cameraViewers = n
                }
            }
            "library_list" -> Library.answerList(appContext, json) { send(it) }
            "library_fetch" -> Library.answerFetch(appContext, json)
            "library_items" -> PeerLibrary.onItems(json)
            "group_update" -> onGroupUpdate(json)
            else -> files.handle(type, json)
        }
        return true
    }

    private var pcDevices: List<PeerDevice> = emptyList()

    /** Devices known through the PC plus devices connected straight to this phone's hub. */
    private fun mergeDevices() {
        val hub = AndroidHub.connected.value.mapNotNull { c ->
            val id = c.deviceId ?: return@mapNotNull null
            PeerDevice(
                id = id, name = c.name, kind = c.kind, screenW = c.screenW, screenH = c.screenH,
                canBeControlled = c.caps.get("control")?.asBoolean ?: false,
                acceptsFiles = c.caps.get("files")?.asBoolean ?: true
            )
        }
        val direct = hub + PeerLinks.peers.value.filter { p -> hub.none { it.id == p.id } }
        val ids = direct.map { it.id }.toSet()
        // A device reachable both ways keeps the PC's details but is listed once.
        val viaPc = pcDevices.filter { it.id !in ids }
        val known = ids + viaPc.map { it.id }
        _devices.value = viaPc + direct + RelayLinks.peers.value.filter { it.id !in known }
    }

    private fun parseDevices(list: JsonArray?) {
        val me = selfId
        pcDevices = list?.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("device_id")?.asString ?: return@mapNotNull null
            if (id == me) return@mapNotNull null
            val caps = o.getAsJsonObject("caps")
            PeerDevice(
                id = id,
                name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "Device",
                kind = o.get("kind")?.takeIf { !it.isJsonNull }?.asString ?: "phone",
                screenW = o.get("screen_w")?.asInt ?: 0,
                screenH = o.get("screen_h")?.asInt ?: 0,
                canBeControlled = caps?.get("control")?.asBoolean ?: false,
                acceptsFiles = caps?.get("files")?.asBoolean ?: true
            )
        }?.sortedBy { if (it.isPc) 0 else 1 } ?: emptyList()
        mergeDevices()
    }

    // --- streams -------------------------------------------------------
    private fun parseStreams(list: JsonArray?) {
        val me = selfId
        _streams.value = list?.mapNotNull { el ->
            val o = el.asJsonObject
            val owner = o.get("owner")?.asString ?: return@mapNotNull null
            if (owner == me) return@mapNotNull null
            LiveStream(
                id = o.get("id")?.asString ?: return@mapNotNull null,
                kind = o.get("kind")?.asString ?: "screen",
                label = o.get("label")?.asString ?: "Stream",
                owner = owner,
                ownerName = o.get("owner_name")?.asString ?: "Device",
                viewers = o.get("viewers")?.asInt ?: 0
            )
        } ?: emptyList()
    }

    fun announceStream(kind: String, label: String, active: Boolean) {
        if (!initialized) return
        send(JsonObject().apply {
            addProperty("type", "stream_announce")
            addProperty("stream_id", "$selfId:$kind")
            addProperty("kind", kind)
            addProperty("label", label)
            addProperty("active", active)
        })
        AndroidHub.announceStreams()
    }

    /** One picture from this device's screen or camera; dropped when nobody watches. */
    fun publishFrame(kind: String, jpeg: ByteArray, width: Int, height: Int) {
        if (kind == "camera" && cameraViewers <= 0) return
        val payload = JsonObject().apply {
            addProperty("type", "stream_frame")
            addProperty("stream_id", "$selfId:$kind")
            addProperty("frame", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
            addProperty("width", width)
            addProperty("height", height)
        }
        if (kind == "screen") {
            val now = System.currentTimeMillis()
            if (directWatchers.entries.removeIf { now - it.value > 25_000 }) updateScreenViewers()
            // The PC's own window can watch directly too; it gets one copy through the PC path below.
            directWatchers.keys.filter { it !in h264Watchers && it != PC_ID }.forEach { watcher ->
                sendTo(watcher, payload.deepCopy())
            }
        }
        val pcWantsJpeg = directWatchers.containsKey(PC_ID) && PC_ID !in h264Watchers
        if (kind != "screen" || pcScreenViewers > 0 || pcWantsJpeg) send(payload)
    }

    fun onCameraFrame(jpeg: ByteArray, width: Int, height: Int) {
        if (cameraViewers > 0) publishFrame("camera", jpeg, width, height)
    }

    /**
     * Watching goes straight to the device that owns the stream (directly when linked,
     * otherwise passed along by the PC), and frames come straight back.
     */
    fun watch(streamId: String) {
        val previous = watching
        if (previous != null && previous != streamId) unwatch()
        if (previous != streamId) {
            H264Viewer.reset(streamId)
            watchStartedAt = System.currentTimeMillis()
        }
        watching = streamId
        // No video a few seconds after asking (older app, no encoder): settle for pictures.
        if (smoothFallbackFor != streamId && previous == streamId && H264Viewer.lastPartAt == 0L &&
            System.currentTimeMillis() - watchStartedAt > 6_000 && _watchedFrame.value != null) {
            smoothFallbackFor = streamId
        }
        val smooth = settings?.smoothMirroring?.value == true && !h264Broken && smoothFallbackFor != streamId
        sendTo(streamId.substringBefore(':'), JsonObject().apply {
            addProperty("type", "stream_watch")
            addProperty("stream_id", streamId)
            addProperty("on", true)
            if (smooth) addProperty("codec", "h264")
        })
    }

    fun unwatch() {
        val id = watching ?: return
        watching = null
        _watchedFrame.value = null
        H264Viewer.reset(null)
        stopStreamAudio()
        sendTo(id.substringBefore(':'), JsonObject().apply {
            addProperty("type", "stream_watch")
            addProperty("stream_id", id)
            addProperty("on", false)
        })
    }

    private val directWatchers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val audioPlayer = com.btmouse.app.connection.LiveAudioPlayer()
    @Volatile private var audioStarted = false
    private val _streamMuted = MutableStateFlow(false)
    val streamMuted: StateFlow<Boolean> = _streamMuted.asStateFlow()

    fun setStreamMuted(muted: Boolean) {
        _streamMuted.value = muted
        if (muted) stopStreamAudio()
    }

    /** Sound that goes with this screen, to everyone watching it. */
    fun publishAudio(pcm: ByteArray) {
        val payload = JsonObject().apply {
            addProperty("type", "stream_audio")
            addProperty("stream_id", "$selfId:screen")
            addProperty("pcm", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP))
        }
        directWatchers.keys.filter { it != PC_ID }.forEach { sendTo(it, payload.deepCopy()) }
        if (pcScreenViewers > 0 || directWatchers.containsKey(PC_ID)) send(payload)
    }

    private fun onStreamAudio(json: JsonObject) {
        if (json.get("stream_id")?.asString != watching || _streamMuted.value) return
        val pcm = runCatching { android.util.Base64.decode(json.get("pcm")?.asString ?: "", android.util.Base64.DEFAULT) }.getOrNull() ?: return
        if (!audioStarted) audioStarted = audioPlayer.start()
        audioPlayer.accept(pcm)
    }

    private fun stopStreamAudio() {
        if (audioStarted) {
            audioPlayer.stop()
            audioStarted = false
        }
    }

    private fun onStreamWatch(json: JsonObject) {
        val streamId = json.get("stream_id")?.asString ?: return
        val from = json.get("from")?.takeIf { !it.isJsonNull }?.asString ?: return
        if (streamId != "$selfId:screen" || from == selfId) return
        if (json.get("on")?.asBoolean != false) {
            directWatchers[from] = System.currentTimeMillis()
            if (json.get("codec")?.asString == "h264" && !H264Encoder.unavailable) {
                if (h264Watchers.add(from)) H264Encoder.requestKeyframe()
            } else {
                h264Watchers.remove(from)
            }
        } else {
            directWatchers.remove(from)
            h264Watchers.remove(from)
        }
        updateScreenViewers()
    }

    private val h264Watchers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var h264Broken = false
    @Volatile private var watchStartedAt = 0L
    /** A stream that sent no video when asked; watched as pictures from then on. */
    @Volatile private var smoothFallbackFor: String? = null

    private fun updateScreenViewers() {
        h264Watchers.retainAll(directWatchers.keys)
        if (h264Watchers.isEmpty()) H264Encoder.stop()
        ScreenShare.viewers = pcScreenViewers + directWatchers.size
    }

    /** Someone watches this screen as H.264 video (and this phone can encode it). */
    fun wantsH264(): Boolean = h264Watchers.isNotEmpty() && !H264Encoder.unavailable

    /** No encoder here: the video watchers get pictures instead. */
    fun onH264EncoderUnavailable() {
        h264Watchers.clear()
    }

    /** Viewers that still want JPEG pictures (the PC, older apps, smooth mirroring off). */
    fun jpegViewers(): Int = pcScreenViewers + directWatchers.keys.count { it !in h264Watchers }

    fun publishH264(part: JsonObject) {
        part.addProperty("type", "stream_h264")
        part.addProperty("stream_id", "$selfId:screen")
        h264Watchers.forEach { sendTo(it, part.deepCopy()) }
    }

    fun requestKeyframe(streamId: String) {
        sendTo(streamId.substringBefore(':'), JsonObject().apply {
            addProperty("type", "stream_keyframe")
            addProperty("stream_id", streamId)
        })
    }

    /** This device can't decode H.264: watch with pictures instead. */
    fun h264Unavailable() {
        h264Broken = true
        watching?.let { watch(it) }
    }

    /** Another device asked to mirror this screen: start sharing (Android asks the user once). */
    private fun onScreenRequest(json: JsonObject) {
        if (ScreenShare.active.value) return
        val who = json.get("from_name")?.takeIf { !it.isJsonNull }?.asString ?: "Another device"
        ScreenRequestActivity.ask(appContext, who)
    }

    /** "Mirror & control": ask the device to share its screen, and start watching it. */
    fun requestMirror(device: PeerDevice): LiveStream {
        sendTo(device.id, JsonObject().apply {
            addProperty("type", "screen_request")
            addProperty("from_name", DeviceIdentity.name(appContext))
        })
        val stream = LiveStream("${device.id}:screen", "screen", "Screen", device.id, device.name, 0)
        watch(stream.id)
        return stream
    }

    private fun onStreamFrame(json: JsonObject) {
        val id = json.get("stream_id")?.asString ?: return
        if (id != watching) return
        val bytes = runCatching {
            android.util.Base64.decode(json.get("frame")?.asString ?: "", android.util.Base64.DEFAULT)
        }.getOrNull() ?: return
        _watchedFrame.value = StreamFrame(id, bytes, json.get("width")?.asInt ?: 0, json.get("height")?.asInt ?: 0)
    }

    // --- clipboard -----------------------------------------------------
    private fun clipId(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun setClipboardImage(uri: android.net.Uri, fromName: String) {
        if (settings?.clipboardSync?.value == false) return
        main.post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                suppressNextLocalClip = true
                clipboard.setPrimaryClip(ClipData.newUri(appContext.contentResolver, "Image from $fromName", uri))
                _lastClipboard.value = "Picture from $fromName"
            }
        }
    }

    private fun applyRemoteClipboard(json: JsonObject) {
        if (settings?.clipboardSync?.value == false) return
        val text = json.get("text")?.asString ?: return
        val id = clipId(text)
        if (id == lastClipId) return
        lastClipId = id
        rememberClipboard(id, text, json.get("from_name")?.asString ?: "your PC", System.currentTimeMillis())
        main.post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                suppressNextLocalClip = true
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied on ${json.get("from_name")?.asString ?: "your PC"}", text))
                _lastClipboard.value = "Clipboard from ${json.get("from_name")?.asString ?: "your PC"}"
            }
        }
    }

    /** Whether this phone lets its other devices browse its photo gallery. */
    fun sharesGallery(): Boolean = settings?.shareGallery?.value == true

    /** Whether a copy seen in another app should be read and sent (see ClipboardCatcher). */
    fun wantsCopiesCaught(): Boolean {
        val s = settings ?: return false
        if (!initialized || !s.clipboardSync.value || !s.catchCopies.value) return false
        // The Lanyard keyboard already sees every copy while it's the active keyboard.
        if (RemoteKeyboardService.active.value) return false
        return coordinator?.isConnected?.value == true || _devices.value.isNotEmpty()
    }

    /** Send the clipboard if it changed. Only works while the app or the Lanyard keyboard has focus. */
    fun pushClipboardIfNew(force: Boolean = false): Boolean {
        if (!initialized || (settings?.clipboardSync?.value == false && !force)) return false
        val clip = runCatching {
            (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        }.getOrNull() ?: return false
        if (clip.itemCount == 0) return false
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (item.text.isNullOrEmpty() && uri != null) {
            // A copied picture: send it as a file the PC puts on its clipboard (never echo our own).
            if (uri.authority == "${appContext.packageName}.fileprovider") return false
            val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: return false
            if (!mime.startsWith("image/")) return false
            val key = uri.toString()
            if (key == lastClipImage && !force) return false
            lastClipImage = key
            files.sendUri(PC_ID, "your PC", uri, clipboard = true)
            return true
        }
        val text = item.coerceToText(appContext)?.toString()
        if (text.isNullOrEmpty()) return false
        return pushText(text, force)
    }

    fun pushText(text: String, force: Boolean = true): Boolean {
        val clipped = text.take(30_000)
        val id = clipId(clipped)
        if (id == lastClipId && !force) return false
        lastClipId = id
        rememberClipboard(id, clipped, DeviceIdentity.name(appContext), System.currentTimeMillis())
        val push = JsonObject().apply {
            addProperty("type", "clipboard_push")
            addProperty("text", clipped)
            addProperty("id", id)
        }
        val named = push.deepCopy().apply { addProperty("from_name", DeviceIdentity.name(appContext)) }
        AndroidHub.broadcast(named)
        // Devices on other networks too; a copy that also arrives another way is ignored by its id.
        RelayLinks.broadcast(named)
        send(push)
        return true
    }

    fun restoreClipboard(entry: ClipboardHistoryEntry) {
        if (!initialized) return
        main.post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                suppressNextLocalClip = true
                clipboard.setPrimaryClip(ClipData.newPlainText("Clipboard history", entry.text))
                pushText(entry.text)
            }
        }
    }

    private fun loadClipboardHistory() {
        val prefs = appContext.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
        val entries = runCatching {
            JsonParser.parseString(prefs.getString("entries", "[]")).asJsonArray
                .mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject }
                .mapNotNull { item ->
                    val text = item.get("text")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    ClipboardHistoryEntry(item.get("id")?.asString ?: clipId(text), text,
                        item.get("from_name")?.asString ?: "Device", item.get("timestamp")?.asLong ?: 0L)
                }
        }.getOrDefault(emptyList())
        _clipboardHistory.value = entries.takeLast(200).asReversed()
    }

    private fun mergeClipboardHistory(entries: JsonArray?) {
        if (entries == null) return
        entries.forEach { value ->
            if (!value.isJsonObject) return@forEach
            val item = value.asJsonObject
            val text = item.get("text")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            rememberClipboard(item.get("id")?.asString ?: clipId(text), text,
                item.get("from_name")?.asString ?: "Device", item.get("timestamp")?.asLong ?: System.currentTimeMillis())
        }
    }

    private fun rememberClipboard(id: String, text: String, fromName: String, timestamp: Long) {
        val merged = (listOf(ClipboardHistoryEntry(id, text, fromName, timestamp)) + _clipboardHistory.value)
            .distinctBy { it.id }.take(200)
        _clipboardHistory.value = merged
        val json = JsonArray()
        merged.asReversed().forEach { entry ->
            json.add(JsonObject().apply {
                addProperty("id", entry.id)
                addProperty("text", entry.text)
                addProperty("from_name", entry.fromName)
                addProperty("timestamp", entry.timestamp)
            })
        }
        appContext.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE).edit()
            .putString("entries", json.toString()).apply()
    }

    // --- controlling another Android device ----------------------------
    fun startControlling(deviceId: String) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "control_state")
            addProperty("active", true)
            addProperty("controller", DeviceIdentity.name(appContext))
        })
    }

    fun stopControlling(deviceId: String) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "control_state")
            addProperty("active", false)
            addProperty("controller", DeviceIdentity.name(appContext))
        })
    }

    fun remoteInput(deviceId: String, ev: String, build: JsonObject.() -> Unit = {}) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "remote_input")
            addProperty("ev", ev)
            build()
        })
    }

    /** Ask the PC to hand keyboard and mouse back to itself. */
    fun releaseControl() {
        send(JsonObject().apply { addProperty("type", "control_release") })
        _controlledBy.value = null
        RemoteControlService.instance?.setControlled(false)
    }
}
