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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.MessageDigest

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
        files = FileTransfers(appContext, scope) { send(it) }
        Family.init(appContext)
        PeerLinks.init(appContext, scope)
        AndroidHub.init(appContext, scope)
        scope.launch { PeerLinks.peers.collect { mergeDevices() } }

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
                    watching?.let { watch(it) }
                    delay(10_000)
                }
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
            add("caps", JsonObject().apply {
                addProperty("control", allowControl && RemoteControlService.instance != null)
                addProperty("keyboard", RemoteKeyboardService.instance != null)
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
        "device_hello_ack", "control_state", "remote_input", "clipboard_push", "devices_update",
        "file_offer", "file_accept", "file_chunk", "file_chunk_ack", "file_done", "file_result", "file_cancel",
        "streams_update", "stream_frame", "stream_demand", "library_list", "library_fetch",
        "stream_watch", "screen_request", "stream_audio"
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
            }
            "stream_watch" -> onStreamWatch(json)
            "stream_audio" -> onStreamAudio(json)
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
            "clipboard_push" -> applyRemoteClipboard(json)
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
        _devices.value = pcDevices.filter { it.id !in ids } + direct
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
            directWatchers.entries.removeIf { now - it.value > 25_000 }
            directWatchers.keys.forEach { watcher ->
                sendTo(watcher, payload.deepCopy())
            }
        }
        if (kind != "screen" || pcScreenViewers > 0) send(payload)
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
        watching = streamId
        sendTo(streamId.substringBefore(':'), JsonObject().apply {
            addProperty("type", "stream_watch")
            addProperty("stream_id", streamId)
            addProperty("on", true)
        })
    }

    fun unwatch() {
        val id = watching ?: return
        watching = null
        _watchedFrame.value = null
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
        directWatchers.keys.forEach { sendTo(it, payload.deepCopy()) }
        if (pcScreenViewers > 0) send(payload)
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
        if (json.get("on")?.asBoolean != false) directWatchers[from] = System.currentTimeMillis() else directWatchers.remove(from)
        updateScreenViewers()
    }

    private fun updateScreenViewers() {
        ScreenShare.viewers = pcScreenViewers + directWatchers.size
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
        main.post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                suppressNextLocalClip = true
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied on ${json.get("from_name")?.asString ?: "your PC"}", text))
                _lastClipboard.value = "Clipboard from ${json.get("from_name")?.asString ?: "your PC"}"
            }
        }
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
        val push = JsonObject().apply {
            addProperty("type", "clipboard_push")
            addProperty("text", clipped)
            addProperty("id", id)
        }
        AndroidHub.broadcast(push.deepCopy().apply { addProperty("from_name", DeviceIdentity.name(appContext)) })
        send(push)
        return true
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
