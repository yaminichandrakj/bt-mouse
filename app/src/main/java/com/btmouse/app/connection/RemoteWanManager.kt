package com.btmouse.app.connection

import android.content.Context
import android.util.Base64
import android.util.Log
import com.btmouse.app.data.MediaStatus
import com.btmouse.app.data.SettingsRepository
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Pings go out every 3 s; after this long with no pong the link is treated as dead. */
private const val NO_RESPONSE_DISCONNECT_MS = 20_000L

class RemoteWanManager(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    private val tag = "RemoteWanManager"
    private val gson = Gson()
    private val secure = SecureTransport { settings.serverPin.value }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _measuredLatencyMs = MutableStateFlow<Long?>(null)
    override val measuredLatencyMs: StateFlow<Long?> = _measuredLatencyMs.asStateFlow()

    private val _isConnectionHealthy = MutableStateFlow(true)
    override val isConnectionHealthy: StateFlow<Boolean> = _isConnectionHealthy.asStateFlow()

    var clientSessionId: String = ""
    var nextSeqSupplier: (() -> Long)? = null
    @Volatile private var lastPongAtMs: Long = 0L

    private val _mediaStatus = MutableStateFlow(MediaStatus())
    private val _screenFrame = MutableStateFlow<ScreenFrame?>(null)
    override val screenFrame: StateFlow<ScreenFrame?> = _screenFrame.asStateFlow()
    override val mediaStatus: StateFlow<MediaStatus> = _mediaStatus.asStateFlow()

    private val _systemStats = MutableStateFlow<SystemStats?>(null)
    override val systemStats: StateFlow<SystemStats?> = _systemStats.asStateFlow()

    private val _runningProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList())
    override val runningProcesses: StateFlow<List<ProcessInfo>> = _runningProcesses.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    override val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _appIcons = MutableStateFlow<Map<String, String>>(emptyMap())
    override val appIcons: StateFlow<Map<String, String>> = _appIcons.asStateFlow()

    private val _latestCameraFrame = MutableStateFlow<ByteArray?>(null)
    override val latestCameraFrame: StateFlow<ByteArray?> = _latestCameraFrame.asStateFlow()

    private val snapshotGallery = SnapshotGallery(context)
    override val cameraSnapshots: StateFlow<List<SnapshotEntry>> get() = snapshotGallery.refresh()

    private val _isCameraStreaming = MutableStateFlow(false)
    override val isCameraStreaming: StateFlow<Boolean> = _isCameraStreaming.asStateFlow()

    private val _isCameraRecording = MutableStateFlow(false)
    override val isCameraRecording: StateFlow<Boolean> = _isCameraRecording.asStateFlow()

    private val _isAudioRecording = MutableStateFlow(false)
    override val isAudioRecording: StateFlow<Boolean> = _isAudioRecording.asStateFlow()

    private val recordingTransfer = RecordingTransferClient(context)
    private val liveAudioPlayer = LiveAudioPlayer()
    private val liveAudioRecorder = LiveAudioRecorder()
    private val _isPcLiveAudioActive = MutableStateFlow(false)
    override val isPcLiveAudioActive: StateFlow<Boolean> = _isPcLiveAudioActive.asStateFlow()
    private val _isPhoneLiveAudioActive = MutableStateFlow(false)
    override val isPhoneLiveAudioActive: StateFlow<Boolean> = _isPhoneLiveAudioActive.asStateFlow()
    private val _isPcLiveAudioRecording = MutableStateFlow(false)
    override val isPcLiveAudioRecording: StateFlow<Boolean> = _isPcLiveAudioRecording.asStateFlow()
    private val _isPhoneLiveAudioRecording = MutableStateFlow(false)
    override val isPhoneLiveAudioRecording: StateFlow<Boolean> = _isPhoneLiveAudioRecording.asStateFlow()
    override val recordings: StateFlow<List<RecordingEntry>> = recordingTransfer.recordings
    override val recordingTransferStatus: StateFlow<String> = recordingTransfer.status
    override val recordingStreamUrl: StateFlow<String?> = recordingTransfer.streamUrl

    private var heartbeatJob: Job? = null

    fun connect(url: String, token: String) {
        disconnect()
        if (token.length != 6 || token.any { !it.isDigit() }) {
            _statusMessage.value = "Enter the six-digit pairing PIN shown by the PC companion"
            return
        }
        _statusMessage.value = "Connecting to $url..."

        val req = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            _statusMessage.value = "Invalid URL: ${e.message}"
            return
        }
        val host = req.url.host
        if (req.url.scheme == "ws" && !isLocalHost(host)) {
            _statusMessage.value = "Use wss:// for Internet connections. ws:// is allowed only on a local network."
            return
        }

        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (webSocket !== ws) return
                Log.d(tag, "WebSocket opened, sending authentication token")
                _statusMessage.value = "Authenticating with server..."
                val authPacket = JsonObject().apply {
                    addProperty("type", "auth")
                    addProperty("token", token)
                }
                ws.send(authPacket.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (webSocket !== ws) return
                handleIncomingMessage(text, ws)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                _isConnected.value = false
                heartbeatJob?.cancel()
                _connectedDeviceName.value = null
                clearRemoteSession()
                _statusMessage.value = "Server disconnected: $reason"
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                Log.e(tag, "WebSocket failure: ${t.message}", t)
                _isConnected.value = false
                heartbeatJob?.cancel()
                _connectedDeviceName.value = null
                clearRemoteSession()
                _statusMessage.value = "Connection failed: ${t.message}"
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                _isConnected.value = false
                heartbeatJob?.cancel()
                _connectedDeviceName.value = null
                clearRemoteSession()
                _statusMessage.value = "Disconnected"
            }
        })
    }

    fun disconnect() {
        secure.reset()
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {}
        webSocket = null
        _isConnected.value = false
        _statusMessage.value = "Disconnected"
        _connectedDeviceName.value = null
        clearRemoteSession()
    }

    private fun clearRemoteSession() {
        recordingTransfer.cancel()
        _mediaStatus.value = MediaStatus()
        _isCameraStreaming.value = false
        _isCameraRecording.value = false
        _isAudioRecording.value = false
        liveAudioPlayer.stop()
        _isPcLiveAudioActive.value = false
        _isPcLiveAudioRecording.value = false
        liveAudioRecorder.stop()
        _isPhoneLiveAudioActive.value = false
        _isPhoneLiveAudioRecording.value = false
        _systemStats.value = null
        _runningProcesses.value = emptyList()
        _installedApps.value = emptyList()
        _latestCameraFrame.value = null
        _measuredLatencyMs.value = null
        _isConnectionHealthy.value = true
    }

    override fun notifyPathSwitch() {
        sendMouseButton(0)
        sendPacket(mapOf("type" to "path_switch"), includeSeq = false)
    }

    private fun handleIncomingMessage(text: String, source: WebSocket) {
        try {
            val parsed = gson.fromJson(text, JsonObject::class.java) ?: return
            val root = secure.unwrap(parsed) ?: return
            if (root.get("type")?.asString == "key_exchange_ack") {
                secure.onAck(root)
                return
            }
            if (_isConnected.value && com.btmouse.app.ecosystem.Ecosystem.handleIncoming(root)) return
            val type = root.get("type")?.asString ?: ""
            if (_isConnected.value) recordingTransfer.handleMessage(root) { ack -> sendJson(ack, includeSeq = false) }

            when (type) {
                "pong" -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    lastPongAtMs = now
                    val clientTime = root.get("client_time")?.asLong
                    if (clientTime != null && clientTime > 0) {
                        val rtt = now - clientTime
                        if (rtt >= 0) {
                            _measuredLatencyMs.value = rtt
                            _isConnectionHealthy.value = rtt < 2500L
                        }
                    } else {
                        _isConnectionHealthy.value = true
                    }
                }
                "auth_response" -> {
                    val authOk = root.get("authenticated")?.asBoolean ?: false
                    val msg = root.get("message")?.asString ?: ""
                    if (authOk) {
                        _isConnected.value = true
                        _statusMessage.value = "Connected over WAN (Secured)"
                        _connectedDeviceName.value = "Remote PC (WAN)"
                        startHeartbeat()
                        secure.reset()
                        secure.begin()?.let { sendJson(it, includeSeq = false) }
                        queryMedia()
                    } else {
                        _isConnected.value = false
                        _connectedDeviceName.value = null
                        clearRemoteSession()
                        _statusMessage.value = "Authentication failed: $msg"
                        source.close(1008, "Authentication failed")
                    }
                }
                "screen_frame" -> { _screenFrame.value = ScreenFrame.fromJson(root) }
                "media_status" -> {
                    val hasMedia = root.get("has_media")?.asBoolean ?: false
                    val title = root.get("title")?.asString ?: ""
                    val artist = root.get("artist")?.asString ?: ""
                    val album = root.get("album")?.asString ?: ""
                    val status = root.get("status")?.asString ?: "Stopped"
                    val position = root.get("position")?.asFloat ?: 0f
                    val duration = root.get("duration")?.asFloat ?: 0f
                    val volume = root.get("volume")?.asFloat ?: 0.5f
                    val isMuted = (root.get("is_muted") ?: root.get("muted"))?.asBoolean ?: false
                    val old = _mediaStatus.value

                    _mediaStatus.value = MediaStatus(
                        hasMedia = hasMedia,
                        title = title,
                        artist = artist,
                        album = album,
                        playerName = root.get("player")?.asString ?: "",
                        playerKey = root.get("player_key")?.asString ?: "",
                        playerIconBase64 = root.get("player_icon")?.asString?.takeIf { it.isNotBlank() }
                            ?: if (old.playerName == (root.get("player")?.asString ?: "")) old.playerIconBase64 else "",
                        status = status,
                        position = position,
                        duration = duration,
                        volume = volume,
                        isMuted = isMuted,
                        artworkBase64 = root.get("artwork")?.asString ?: if (old.title == title && old.artist == artist) old.artworkBase64 else "",
                        videoPreviewBase64 = root.get("video_preview")?.asString ?: old.videoPreviewBase64,
                        volumeAvailable = root.get("volume_available")?.asBoolean ?: true,
                        volumeError = root.get("volume_error")?.asString ?: ""
                    )
                }
                "system_stats_response" -> {
                    val cpu = root.get("cpu")?.asFloat ?: 0f
                    val ram = root.get("ram")?.asFloat ?: 0f
                    val battery = root.get("battery")?.asFloat ?: -1f
                    val powerPlugged = root.get("power_plugged")?.asBoolean ?: true
                    val disk = root.get("disk")?.asFloat ?: 0f
                    val diskActivity = root.get("disk_activity")?.asFloat ?: -1f
                    val gpus = root.getAsJsonArray("gpus")?.mapNotNull { item ->
                        runCatching {
                            val gpu = item.asJsonObject
                            GpuStat(gpu.get("index")?.asInt ?: 0, gpu.get("name")?.asString ?: "GPU",
                                gpu.get("utilization")?.asFloat ?: -1f, gpu.get("engine")?.asString ?: "")
                        }.getOrNull()
                    } ?: emptyList()
                    val npu = root.get("npu")?.asFloat ?: -1f
                    val locked = root.get("locked")?.takeIf { !it.isJsonNull }?.asBoolean
                    _systemStats.value = SystemStats(cpu, ram, battery, powerPlugged, disk, diskActivity, gpus, npu, locked)
                }
                "process_list_response" -> {
                    val array = root.getAsJsonArray("processes") ?: JsonArray()
                    val list = mutableListOf<ProcessInfo>()
                    for (elem in array) {
                        val obj = elem.asJsonObject
                        list.add(
                            ProcessInfo(
                                pid = obj.get("pid")?.asInt ?: 0,
                                name = obj.get("name")?.asString ?: "",
                                cpu = obj.get("cpu")?.asFloat ?: 0f,
                                ram = obj.get("ram")?.asFloat ?: 0f
                            )
                        )
                    }
                    _runningProcesses.value = list
                }
                "installed_apps_response" -> {
                    val array = root.getAsJsonArray("apps") ?: JsonArray()
                    val list = mutableListOf<AppInfo>()
                    val iconMap = _appIcons.value.toMutableMap()
                    for (elem in array) {
                        val obj = elem.asJsonObject
                        val name = obj.get("name")?.asString ?: ""
                        val path = obj.get("path")?.asString ?: ""
                        val cmd = obj.get("cmd")?.asString ?: ""
                        val isQuick = obj.get("is_quick")?.asBoolean ?: false
                        val iconB64 = obj.get("icon")?.asString?.takeIf { it.isNotBlank() } ?: iconMap[path] ?: iconMap[name.lowercase()] ?: ""
                        val brandKey = obj.get("brand_key")?.asString ?: ""
                        if (iconB64.isNotBlank()) {
                            if (path.isNotBlank()) iconMap[path] = iconB64
                            if (name.isNotBlank()) iconMap[name.lowercase()] = iconB64
                        }
                        list.add(
                            AppInfo(
                                name = name,
                                path = path,
                                cmd = cmd,
                                isQuick = isQuick,
                                iconBase64 = iconB64,
                                brandKey = brandKey
                            )
                        )
                    }
                    _appIcons.value = iconMap
                    // Long app lists arrive in parts; part 0 starts a fresh list.
                    val part = root.get("part")?.asInt ?: 0
                    _installedApps.value = if (part > 0) (_installedApps.value + list).distinctBy { it.path.lowercase() } else list
                    val loading = root.get("loading")?.asBoolean ?: false
                    val message = root.get("message")?.asString.orEmpty()
                    _statusMessage.value = when {
                        loading -> "Loading installed PC apps…"
                        message.isNotBlank() -> message
                        _statusMessage.value.startsWith("Loading installed PC apps") -> ""
                        else -> _statusMessage.value
                    }
                }
                "app_icons_response" -> {
                    val iconsObj = root.getAsJsonObject("icons")
                    if (iconsObj != null) {
                        val updatedMap = _appIcons.value.toMutableMap()
                        for ((k, v) in iconsObj.entrySet()) {
                            if (v.isJsonObject) {
                                val iconStr = v.asJsonObject.get("icon")?.asString.orEmpty()
                                if (iconStr.isNotBlank()) {
                                    updatedMap[k] = iconStr
                                    updatedMap[k.lowercase()] = iconStr
                                }
                            } else if (v.isJsonPrimitive) {
                                val iconStr = v.asString
                                if (iconStr.isNotBlank()) {
                                    updatedMap[k] = iconStr
                                    updatedMap[k.lowercase()] = iconStr
                                }
                            }
                        }
                        _appIcons.value = updatedMap
                        _installedApps.value = _installedApps.value.map { app ->
                            val hit = updatedMap[app.path] ?: updatedMap[app.name.lowercase()] ?: app.iconBase64
                            if (hit.isNotBlank() && hit != app.iconBase64) app.copy(iconBase64 = hit) else app
                        }
                    }
                }
                "quick_apps_update_response" -> {
                    val success = root.get("success")?.asBoolean ?: false
                    val paths = root.getAsJsonArray("paths")?.mapNotNull { it.asString }?.toSet().orEmpty()
                    if (success) _installedApps.value = _installedApps.value.map { it.copy(isQuick = it.path in paths) }
                    _statusMessage.value = root.get("message")?.asString ?: "Could not update PC Quick Apps"
                }
                "launch_app_response" -> {
                    val success = root.get("success")?.asBoolean ?: false
                    val app = root.get("app")?.asString.orEmpty()
                    _statusMessage.value = if (success) {
                        "Launched $app"
                    } else {
                        "Could not launch $app. The shortcut may have been moved or removed."
                    }
                }
                "camera_frame_response" -> {
                    val b64 = root.get("frame")?.asString
                    if (!b64.isNullOrEmpty()) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            if (root.get("is_snapshot")?.asBoolean == true) {
                                if (!snapshotGallery.save(bytes)) _statusMessage.value = "Could not save the camera snapshot"
                                else _statusMessage.value = "Snapshot saved to the phone gallery"
                            } else {
                                _latestCameraFrame.value = bytes
                            }
                            if (_statusMessage.value.startsWith("Camera stream started")) _statusMessage.value = ""
                        } catch (e: Exception) {
                            Log.e(tag, "Error decoding camera frame", e)
                        }
                    }
                }
                "camera_stream_status" -> {
                    val streaming = root.get("streaming")?.asBoolean ?: false
                    _isCameraStreaming.value = streaming
                    _isCameraRecording.value = root.get("recording")?.asBoolean ?: false
                    if (!streaming) _latestCameraFrame.value = null
                    val message = root.get("message")?.asString.orEmpty()
                    _statusMessage.value = when {
                        streaming && message.isNotBlank() -> "Camera is live, but recording was skipped: $message"
                        streaming -> "Camera stream started. Waiting for a live frame…"
                        message.isNotBlank() -> "Camera stream stopped: $message"
                        _statusMessage.value.startsWith("Camera stream") -> ""
                        else -> _statusMessage.value
                    }
                }
                "camera_recording_status" -> {
                    _isCameraRecording.value = root.get("recording")?.asBoolean ?: false
                    root.get("message")?.asString?.takeIf { it.isNotBlank() }?.let { _statusMessage.value = "Camera recording: $it" }
                }
                "audio_status_response" -> {
                    val recording = root.get("recording")?.asBoolean ?: false
                    _isAudioRecording.value = recording
                    val message = root.get("message")?.asString.orEmpty()
                    if (recording) {
                        _statusMessage.value = "Microphone recording started on PC"
                    } else if (message.isNotBlank()) {
                        _statusMessage.value = "Microphone error: $message"
                    }
                }
                "live_audio_frame" -> {
                    if (root.get("direction")?.asString == "pc_to_phone") {
                        val encoded = root.get("data")?.asString.orEmpty()
                        if (encoded.isNotEmpty()) try {
                            liveAudioPlayer.accept(Base64.decode(encoded, Base64.DEFAULT))
                        } catch (_: Exception) {}
                    }
                }
                "live_audio_status" -> {
                    val active = root.get("active")?.asBoolean ?: false
                    if (root.get("direction")?.asString == "phone_to_pc") {
                        _isPhoneLiveAudioActive.value = active
                        _isPhoneLiveAudioRecording.value = active && (root.get("recording")?.asBoolean ?: false)
                        if (!active) liveAudioRecorder.stop()
                    } else {
                        _isPcLiveAudioActive.value = active
                        _isPcLiveAudioRecording.value = active && (root.get("recording")?.asBoolean ?: false)
                        if (!active) liveAudioPlayer.stop()
                    }
                    root.get("message")?.asString?.takeIf { it.isNotBlank() }?.let { _statusMessage.value = it }
                }
                "kill_process_response" -> {
                    val success = root.get("success")?.asBoolean ?: false
                    _statusMessage.value = root.get("message")?.asString
                        ?: if (success) "Task ended" else "Could not end task"
                    if (success) queryRunningProcesses()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming WAN packet", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastPongAtMs = android.os.SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isConnected.value) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastPongAtMs > 0 && now - lastPongAtMs > 10_000L) {
                        _isConnectionHealthy.value = false
                    }
                    if (now - lastPongAtMs > NO_RESPONSE_DISCONNECT_MS) {
                        // A half-open TCP link (PC asleep, network gone) never fires onClosed; cancel
                        // so onFailure clears the session and the UI stops claiming we are connected.
                        _statusMessage.value = "PC stopped responding"
                        webSocket?.cancel()
                        break
                    }
                    sendPacket(mapOf("type" to "ping", "client_time" to now), includeSeq = false)
                }
                delay(3000L)
            }
        }
    }

    override fun sendJsonObject(obj: JsonObject) {
        sendJson(obj.deepCopy())
    }

    private fun sendJson(obj: JsonObject, includeSeq: Boolean = true) {
        if (!_isConnected.value) return
        val socket = webSocket ?: return
        if (clientSessionId.isNotEmpty() && !obj.has("client_session")) {
            obj.addProperty("client_session", clientSessionId)
        }
        if (includeSeq) {
            nextSeqSupplier?.invoke()?.let { seq ->
                if (seq > 0 && !obj.has("seq")) {
                    obj.addProperty("seq", seq)
                }
            }
        }
        if (!socket.send(secure.wrap(obj.toString()))) {
            Log.w(tag, "WebSocket rejected an outgoing packet")
        }
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        // Tailscale MagicDNS names: the tailnet is already WireGuard-encrypted end to end.
        if (normalized.endsWith(".ts.net")) return true
        val octets = normalized.split('.')
        if (octets.size == 4) {
            val ip = octets.map { it.toIntOrNull() ?: return false }
            return ip[0] == 10 || ip[0] == 127 ||
                (ip[0] == 172 && ip[1] in 16..31) ||
                (ip[0] == 192 && ip[1] == 168) ||
                (ip[0] == 169 && ip[1] == 254) ||
                (ip[0] == 100 && ip[1] in 64..127) // Tailscale / CGNAT 100.64.0.0/10
        }
        return normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")
    }

    private fun sendPacket(data: Map<String, Any?>, includeSeq: Boolean = true) {
        val obj = JsonObject()
        data.forEach { (k, v) ->
            when (v) {
                is String -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
            }
        }
        sendJson(obj, includeSeq = includeSeq)
    }

    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        val obj = JsonObject().apply {
            addProperty("type", "mouse")
            addProperty("dx", dx)
            addProperty("dy", dy)
            addProperty("btn", buttons)
            addProperty("v_scroll", vScroll)
            addProperty("h_scroll", hScroll)
        }
        sendJson(obj)
    }

    override fun sendMouseButton(buttons: Int) {
        val obj = JsonObject().apply {
            addProperty("type", "button")
            addProperty("btn", buttons)
        }
        sendJson(obj)
    }

    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        val obj = JsonObject().apply {
            addProperty("type", "key_down")
            addProperty("code", keyCode.toInt())
            addProperty("mod", modifiers.toInt())
        }
        sendJson(obj)
    }

    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        val obj = JsonObject().apply {
            addProperty("type", "key_up")
            addProperty("code", keyCode.toInt())
            addProperty("mod", modifiers.toInt())
        }
        sendJson(obj)
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        val obj = JsonObject().apply {
            addProperty("type", "key_stroke")
            addProperty("code", keyCode.toInt())
            addProperty("mod", modifiers.toInt())
        }
        sendJson(obj)
    }

    override fun sendMediaKey(usageCode: Int) {
        val obj = JsonObject().apply {
            addProperty("type", "media")
            addProperty("code", usageCode)
        }
        sendJson(obj)
    }

    override fun sendText(text: String) {
        val obj = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
        sendJson(obj)
    }

    override fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) {
        val obj = JsonObject().apply {
            addProperty("type", "pen")
            addProperty("action", action)
            addProperty("x", xRatio)
            addProperty("y", yRatio)
            addProperty("pressure", pressure)
            addProperty("eraser", isEraser)
        }
        sendJson(obj)
    }

    override fun queryMedia(includePreview: Boolean, includeVideoPreview: Boolean) {
        val obj = JsonObject().apply {
            addProperty("type", "media_query")
            addProperty("include_preview", includePreview)
            addProperty("include_video_preview", includeVideoPreview)
        }
        sendJson(obj, includeSeq = false)
    }

    override fun queryScreen(maxWidth: Int, maxBytes: Int, resume: Boolean) {
        val obj = JsonObject().apply {
            addProperty("type", "screen_query")
            addProperty("max_width", maxWidth)
            addProperty("max_bytes", maxBytes)
            addProperty("resume", resume)
        }
        sendJson(obj, includeSeq = false)
    }

    override fun sendMediaSeek(position: Float) {
        val obj = JsonObject().apply {
            addProperty("type", "media_seek")
            addProperty("position", position)
        }
        sendJson(obj)
    }

    override fun sendMediaToggle() {
        val obj = JsonObject().apply { addProperty("type", "media_toggle") }
        sendJson(obj)
    }

    override fun sendMediaNext() {
        val obj = JsonObject().apply { addProperty("type", "media_next") }
        sendJson(obj)
    }

    override fun sendMediaPrev() {
        val obj = JsonObject().apply { addProperty("type", "media_prev") }
        sendJson(obj)
    }

    override fun setVolume(volume: Float) {
        val obj = JsonObject().apply {
            addProperty("type", "set_volume")
            addProperty("volume", volume)
        }
        sendJson(obj)
    }

    override fun setMute(mute: Boolean) {
        val obj = JsonObject().apply {
            addProperty("type", "set_mute")
            addProperty("mute", mute)
        }
        sendJson(obj)
    }

    override fun launchApp(appId: String) {
        val obj = JsonObject().apply {
            addProperty("type", "launch_app")
            addProperty("app", appId)
        }
        sendJson(obj)
    }

    override fun systemPower(action: String) {
        val obj = JsonObject().apply {
            addProperty("type", "system_power")
            addProperty("action", action)
        }
        sendJson(obj)
    }

    override fun querySystemStats() {
        val obj = JsonObject().apply { addProperty("type", "query_stats") }
        sendJson(obj, includeSeq = false)
    }

    override fun queryRunningProcesses() {
        val obj = JsonObject().apply { addProperty("type", "query_processes") }
        sendJson(obj, includeSeq = false)
    }

    override fun killProcess(pid: Int) {
        val obj = JsonObject().apply {
            addProperty("type", "kill_process")
            addProperty("pid", pid)
        }
        sendJson(obj)
    }

    override fun queryInstalledApps() {
        val obj = JsonObject().apply { addProperty("type", "query_apps") }
        sendJson(obj, includeSeq = false)
    }

    override fun queryAppIcons(items: List<String>) {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(40)
        if (clean.isNotEmpty()) {
            val obj = JsonObject().apply {
                addProperty("type", "query_app_icons")
                add("items", com.google.gson.Gson().toJsonTree(clean))
            }
            sendJson(obj, includeSeq = false)
        }
    }

    override fun updateQuickApps(paths: List<String>) {
        val obj = JsonObject().apply {
            addProperty("type", "update_quick_apps")
            add("paths", com.google.gson.Gson().toJsonTree(paths))
        }
        sendJson(obj)
    }

    override fun queryRecordings() {
        sendJson(JsonObject().apply { addProperty("type", "recordings_list") }, includeSeq = false)
    }

    override fun downloadRecording(id: String) {
        sendJson(JsonObject().apply {
            addProperty("type", "recording_download")
            addProperty("id", id)
        })
    }

    override fun streamRecording(id: String) = recordingTransfer.stream(id) { obj -> sendJson(obj, includeSeq = false) }

    override fun stopRecordingStream() = recordingTransfer.stopStream { obj -> sendJson(obj, includeSeq = false) }

    override fun deleteRecording(id: String) = recordingTransfer.delete(id, ::sendJson)

    override fun deleteSnapshot(path: String): Boolean = snapshotGallery.delete(path)

    override fun requestCameraSnapshot() {
        val obj = JsonObject().apply { addProperty("type", "camera_snapshot") }
        sendJson(obj)
    }

    override fun startCameraStream() {
        val obj = JsonObject().apply { addProperty("type", "camera_stream_start") }
        sendJson(obj)
    }

    override fun stopCameraStream() {
        val obj = JsonObject().apply { addProperty("type", "camera_stream_stop") }
        sendJson(obj)
    }

    override fun startAudioRecord() {
        val obj = JsonObject().apply { addProperty("type", "audio_record_start") }
        sendJson(obj)
    }

    override fun stopAudioRecord() {
        val obj = JsonObject().apply { addProperty("type", "audio_record_stop") }
        sendJson(obj)
    }

    override fun startPcLiveAudio(record: Boolean) {
        if (!liveAudioPlayer.start()) {
            _statusMessage.value = "Could not start phone audio playback"
            return
        }
        _isPcLiveAudioActive.value = true
        sendJson(JsonObject().apply {
            addProperty("type", "live_audio_start")
            addProperty("record", record)
        })
    }

    override fun stopPcLiveAudio() {
        sendJson(JsonObject().apply { addProperty("type", "live_audio_stop") })
        liveAudioPlayer.stop()
        _isPcLiveAudioActive.value = false
    }

    override fun startPhoneLiveAudio(record: Boolean): Boolean {
        sendJson(JsonObject().apply {
            addProperty("type", "phone_audio_start")
            addProperty("record", record)
        })
        val started = liveAudioRecorder.start(::sendPhoneAudioFrame) {
            _isPhoneLiveAudioActive.value = false
            sendJson(JsonObject().apply { addProperty("type", "phone_audio_stop") })
        }
        _isPhoneLiveAudioActive.value = started
        if (!started) {
            sendJson(JsonObject().apply { addProperty("type", "phone_audio_stop") })
            _statusMessage.value = "Could not start phone microphone. Check microphone permission."
        }
        return started
    }

    override fun stopPhoneLiveAudio() {
        liveAudioRecorder.stop()
        sendJson(JsonObject().apply { addProperty("type", "phone_audio_stop") })
        _isPhoneLiveAudioActive.value = false
    }

    override fun sendPhoneAudioFrame(pcm: ByteArray) {
        sendJson(JsonObject().apply {
            addProperty("type", "phone_audio_frame")
            addProperty("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
        }, includeSeq = false)
    }

    override fun startPhoneCameraStream(lens: String, width: Int, height: Int, fps: Int) {
        sendJson(JsonObject().apply {
            addProperty("type", "phone_cam_start")
            addProperty("lens", lens)
            addProperty("width", width)
            addProperty("height", height)
            addProperty("fps", fps)
        })
    }

    override fun stopPhoneCameraStream() {
        sendJson(JsonObject().apply {
            addProperty("type", "phone_cam_stop")
        })
    }

    override fun sendPhoneCameraFrame(data: ByteArray, width: Int, height: Int, lens: String) {
        if (!_isConnected.value || data.isEmpty()) return
        sendJson(JsonObject().apply {
            addProperty("type", "phone_cam_frame")
            addProperty("lens", lens)
            addProperty("w", width)
            addProperty("h", height)
            addProperty("frame", Base64.encodeToString(data, Base64.NO_WRAP))
        }, includeSeq = false)
    }
}
