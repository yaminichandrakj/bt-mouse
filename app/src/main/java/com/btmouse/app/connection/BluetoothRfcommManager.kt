package com.btmouse.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import com.btmouse.app.data.MediaStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class BluetoothRfcommManager(
    private val context: Context,
    private val scope: CoroutineScope
) : InputConnection {

    private val tag = "BluetoothRfcomm"
    val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusMessage = MutableStateFlow("Bluetooth disconnected")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _measuredLatencyMs = MutableStateFlow<Long?>(null)
    override val measuredLatencyMs: StateFlow<Long?> = _measuredLatencyMs.asStateFlow()

    private val _isConnectionHealthy = MutableStateFlow(true)
    override val isConnectionHealthy: StateFlow<Boolean> = _isConnectionHealthy.asStateFlow()

    var clientSessionId: String = ""
    var nextSeqSupplier: (() -> Long)? = null

    private var heartbeatJob: Job? = null
    @Volatile private var lastPongAtMs: Long = 0L

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    var lastConnectedDevice: BluetoothDevice? = null
        private set

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

    private val sendQueue = Channel<String>(capacity = 256)
    private val gson = Gson()

    init {
        startSenderQueue()
        refreshPairedDevices()
    }

    private fun startSenderQueue() {
        scope.launch(Dispatchers.IO) {
            for (message in sendQueue) {
                try {
                    val out = outputStream ?: continue
                    val payload = (message + "\n").toByteArray(Charsets.UTF_8)
                    out.write(payload)
                    out.flush()
                } catch (e: Exception) {
                    Log.e(tag, "RFCOMM send error", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        try {
            val devices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            _pairedDevices.value = devices
        } catch (e: SecurityException) {
            Log.e(tag, "Permission error reading paired devices", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            disconnect()
            val devName = try { device.name ?: device.address } catch (e: SecurityException) { "PC" }
            _statusMessage.value = "Connecting to $devName over Bluetooth..."

            try {
                // Try standard secure RFCOMM socket first, then fallback to insecure if needed
                var btSocket: BluetoothSocket? = null
                try {
                    btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                    btSocket.connect()
                } catch (e1: Exception) {
                    Log.w(tag, "Secure RFCOMM connect failed, trying fallback: ${e1.message}")
                    try {
                        btSocket?.close()
                    } catch (ignored: Exception) {}
                    btSocket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                    btSocket.connect()
                }

                socket = btSocket
                outputStream = btSocket.outputStream
                lastConnectedDevice = device
                _isConnected.value = true
                _connectedDeviceName.value = devName
                _statusMessage.value = "Connected to $devName (Bluetooth)"
                Log.d(tag, "Connected to $devName via RFCOMM")

                // Handshake
                val handshake = JsonObject().apply {
                    addProperty("type", "connect")
                    addProperty("client", "Android Phone (BT)")
                }
                sendJson(handshake, includeSeq = false)
                queryMedia()
                startHeartbeat()

                // Start receiver loop for responses
                val inputStream = btSocket.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                while (isActive && _isConnected.value) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        processIncomingPacket(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Bluetooth connect failed", e)
                _statusMessage.value = "Bluetooth connection failed: ${e.message}"
            } finally {
                disconnect()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastPongAtMs = android.os.SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isConnected.value) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastPongAtMs > 0 && now - lastPongAtMs > 6000L) {
                    _isConnectionHealthy.value = false
                }
                val ping = JsonObject().apply {
                    addProperty("type", "ping")
                    addProperty("client_time", now)
                }
                sendJson(ping, includeSeq = false)
                delay(2500L)
            }
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _measuredLatencyMs.value = null
        _isConnectionHealthy.value = true
        recordingTransfer.cancel()
        try {
            if (_isConnected.value) sendJson(JsonObject().apply { addProperty("type", "disconnect") }, includeSeq = false)
        } catch (ignored: Exception) {}
        try {
            outputStream?.close()
        } catch (ignored: Exception) {}
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
        outputStream = null
        _isConnected.value = false
        _connectedDeviceName.value = null
        _isCameraStreaming.value = false
        _isCameraRecording.value = false
        _latestCameraFrame.value = null
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
        _statusMessage.value = "Bluetooth disconnected"
    }

    override fun notifyPathSwitch() {
        sendMouseButton(0)
        sendJson(JsonObject().apply { addProperty("type", "path_switch") }, includeSeq = false)
    }

    private fun processIncomingPacket(line: String) {
        try {
            val root = gson.fromJson(line, JsonObject::class.java) ?: return
            recordingTransfer.handleMessage(root) { ack -> sendJson(ack, includeSeq = false) }
            val type = root.get("type")?.asString ?: ""
            when (type) {
                "pong" -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    lastPongAtMs = now
                    val clientTime = root.get("client_time")?.asLong
                    if (clientTime != null && clientTime > 0) {
                        val rtt = now - clientTime
                        if (rtt >= 0) {
                            _measuredLatencyMs.value = rtt
                            _isConnectionHealthy.value = rtt < 1500L
                        }
                    } else {
                        _isConnectionHealthy.value = true
                    }
                }
                "media_status" -> {
                    val hasMedia = root.get("has_media")?.asBoolean ?: false
                    val title = root.get("title")?.asString ?: ""
                    val artist = root.get("artist")?.asString ?: ""
                    val album = root.get("album")?.asString ?: ""
                    val status = root.get("status")?.asString ?: ""
                    val position = root.get("position")?.asFloat ?: 0f
                    val duration = root.get("duration")?.asFloat ?: 0f
                    val volume = root.get("volume")?.asFloat ?: 0.5f
                    val isMuted = root.get("is_muted")?.asBoolean ?: false
                    val old = _mediaStatus.value

                    _mediaStatus.value = MediaStatus(
                        hasMedia = hasMedia,
                        title = title,
                        artist = artist,
                        album = album,
                        playerName = root.get("player")?.asString ?: "",
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
                    _systemStats.value = SystemStats(cpu, ram, battery, powerPlugged, disk, diskActivity, gpus, npu)
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
                                cmd = obj.get("cmd")?.asString ?: "",
                                isQuick = obj.get("is_quick")?.asBoolean ?: false
                            )
                        )
                    }
                    _installedApps.value = list
                    val loading = root.get("loading")?.asBoolean ?: false
                    val message = root.get("message")?.asString.orEmpty()
                    _statusMessage.value = when {
                        loading -> "Loading installed PC apps…"
                        message.isNotBlank() -> message
                        _statusMessage.value.startsWith("Loading installed PC apps") -> ""
                        else -> _statusMessage.value
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
                        } catch (e: Exception) {}
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
            Log.e(tag, "Error parsing incoming packet", e)
        }
    }

    private fun sendJson(obj: JsonObject, includeSeq: Boolean = true) {
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
        sendQueue.trySend(obj.toString())
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

    override fun sendMediaSeek(position: Float) {
        val obj = JsonObject().apply {
            addProperty("type", "media_seek")
            addProperty("position", position)
        }
        sendJson(obj)
    }

    override fun sendMediaToggle() {
        val obj = JsonObject().apply {
            addProperty("type", "media_toggle")
        }
        sendJson(obj)
    }

    override fun sendMediaNext() {
        val obj = JsonObject().apply {
            addProperty("type", "media_next")
        }
        sendJson(obj)
    }

    override fun sendMediaPrev() {
        val obj = JsonObject().apply {
            addProperty("type", "media_prev")
        }
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
