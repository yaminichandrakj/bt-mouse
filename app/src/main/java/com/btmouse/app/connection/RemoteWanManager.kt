package com.btmouse.app.connection

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

class RemoteWanManager(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    private val tag = "RemoteWanManager"
    private val gson = Gson()

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

    private val _mediaStatus = MutableStateFlow(MediaStatus())
    override val mediaStatus: StateFlow<MediaStatus> = _mediaStatus.asStateFlow()

    private val _systemStats = MutableStateFlow<SystemStats?>(null)
    override val systemStats: StateFlow<SystemStats?> = _systemStats.asStateFlow()

    private val _runningProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList())
    override val runningProcesses: StateFlow<List<ProcessInfo>> = _runningProcesses.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    override val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _latestCameraFrame = MutableStateFlow<ByteArray?>(null)
    override val latestCameraFrame: StateFlow<ByteArray?> = _latestCameraFrame.asStateFlow()

    private val _isCameraStreaming = MutableStateFlow(false)
    override val isCameraStreaming: StateFlow<Boolean> = _isCameraStreaming.asStateFlow()

    private val _isAudioRecording = MutableStateFlow(false)
    override val isAudioRecording: StateFlow<Boolean> = _isAudioRecording.asStateFlow()

    private var heartbeatJob: Job? = null

    fun connect(url: String, token: String) {
        disconnect()
        if (token.isBlank()) {
            _statusMessage.value = "Enter the pairing PIN shown by the PC companion"
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
                // Send authentication handshake
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
        _mediaStatus.value = MediaStatus()
        _isCameraStreaming.value = false
        _isAudioRecording.value = false
        _systemStats.value = null
        _runningProcesses.value = emptyList()
        _installedApps.value = emptyList()
        _latestCameraFrame.value = null
    }

    private fun handleIncomingMessage(text: String, source: WebSocket) {
        try {
            val root = gson.fromJson(text, JsonObject::class.java) ?: return
            val type = root.get("type")?.asString ?: ""

            when (type) {
                "auth_response" -> {
                    val authOk = root.get("authenticated")?.asBoolean ?: false
                    val msg = root.get("message")?.asString ?: ""
                    if (authOk) {
                        _isConnected.value = true
                        _statusMessage.value = "Connected over WAN (Secured)"
                        _connectedDeviceName.value = "Remote PC (WAN)"
                        startHeartbeat()
                        queryMedia()
                        querySystemStats()
                    } else {
                        _isConnected.value = false
                        _connectedDeviceName.value = null
                        clearRemoteSession()
                        _statusMessage.value = "Authentication failed: $msg"
                        source.close(1008, "Authentication failed")
                    }
                }
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

                    _mediaStatus.value = MediaStatus(
                        hasMedia = hasMedia,
                        title = title,
                        artist = artist,
                        album = album,
                        status = status,
                        position = position,
                        duration = duration,
                        volume = volume,
                        isMuted = isMuted
                    )
                }
                "system_stats_response" -> {
                    val cpu = root.get("cpu")?.asFloat ?: 0f
                    val ram = root.get("ram")?.asFloat ?: 0f
                    val battery = root.get("battery")?.asFloat ?: -1f
                    val powerPlugged = root.get("power_plugged")?.asBoolean ?: true
                    val disk = root.get("disk")?.asFloat ?: 0f
                    _systemStats.value = SystemStats(cpu, ram, battery, powerPlugged, disk)
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
                    for (elem in array) {
                        val obj = elem.asJsonObject
                        list.add(
                            AppInfo(
                                name = obj.get("name")?.asString ?: "",
                                path = obj.get("path")?.asString ?: "",
                                cmd = obj.get("cmd")?.asString ?: ""
                            )
                        )
                    }
                    _installedApps.value = list
                }
                "camera_frame_response" -> {
                    val b64 = root.get("frame")?.asString
                    if (!b64.isNullOrEmpty()) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            _latestCameraFrame.value = bytes
                        } catch (e: Exception) {
                            Log.e(tag, "Error decoding camera frame", e)
                        }
                    }
                }
                "camera_stream_status" -> {
                    val streaming = root.get("streaming")?.asBoolean ?: false
                    _isCameraStreaming.value = streaming
                }
                "audio_status_response" -> {
                    val recording = root.get("recording")?.asBoolean ?: false
                    _isAudioRecording.value = recording
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming WAN packet", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isConnected.value) {
                    sendPacket(mapOf("type" to "ping"))
                    queryMedia()
                    querySystemStats()
                }
                delay(2000)
            }
        }
    }

    private fun sendJson(obj: JsonObject) {
        if (!_isConnected.value) return
        val socket = webSocket ?: return
        if (!socket.send(obj.toString())) {
            Log.w(tag, "WebSocket rejected an outgoing packet")
        }
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        val octets = normalized.split('.')
        if (octets.size == 4) {
            val ip = octets.map { it.toIntOrNull() ?: return false }
            return ip[0] == 10 || ip[0] == 127 ||
                (ip[0] == 172 && ip[1] in 16..31) ||
                (ip[0] == 192 && ip[1] == 168) ||
                (ip[0] == 169 && ip[1] == 254)
        }
        return normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")
    }

    private fun sendPacket(data: Map<String, Any?>) {
        val obj = JsonObject()
        data.forEach { (k, v) ->
            when (v) {
                is String -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
            }
        }
        sendJson(obj)
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

    override fun queryMedia() {
        val obj = JsonObject().apply { addProperty("type", "media_query") }
        sendJson(obj)
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
        sendJson(obj)
    }

    override fun queryRunningProcesses() {
        val obj = JsonObject().apply { addProperty("type", "query_processes") }
        sendJson(obj)
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
        sendJson(obj)
    }

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
}
