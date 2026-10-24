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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest

class WifiClientManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    private val tag = "WifiClientManager"
    private val gson = Gson()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusMessage = MutableStateFlow("Wi-Fi disconnected")
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

    private val recordingTransfer = RecordingTransferClient(context)
    private val liveAudioPlayer = LiveAudioPlayer()
    private val liveAudioRecorder = LiveAudioRecorder()
    private val _isPcLiveAudioActive = MutableStateFlow(false)
    override val isPcLiveAudioActive: StateFlow<Boolean> = _isPcLiveAudioActive.asStateFlow()
    private val _isPhoneLiveAudioActive = MutableStateFlow(false)
    override val isPhoneLiveAudioActive: StateFlow<Boolean> = _isPhoneLiveAudioActive.asStateFlow()
    override val recordings: StateFlow<List<RecordingEntry>> = recordingTransfer.recordings
    override val recordingTransferStatus: StateFlow<String> = recordingTransfer.status
    override val recordingStreamUrl: StateFlow<String?> = recordingTransfer.streamUrl

    private var udpSocket: DatagramSocket? = null
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null
    private var receiverJob: Job? = null
    private var senderJob: Job? = null
    private var connectJob: Job? = null
    @Volatile private var pendingConnect: PendingConnect? = null
    @Volatile private var lastPongAtMs: Long = 0L

    private data class PendingConnect(
        val address: InetAddress,
        val port: Int,
        val acknowledgement: CompletableDeferred<JsonObject>
    )

    private data class SnapshotAssembly(
        val id: String,
        val expectedBytes: Int,
        val expectedSha256: String,
        val chunks: Array<ByteArray?>,
        var receivedBytes: Int = 0,
        var receivedCount: Int = 0,
    )

    @Volatile private var pendingSnapshot: SnapshotAssembly? = null
    private var snapshotRetryJob: Job? = null

    // High-performance, zero-jitter non-blocking send queue
    private val sendQueue = Channel<DatagramPacket>(capacity = 1000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        try {
            udpSocket = DatagramSocket()
            startSender()
            startReceiver()
        } catch (e: Exception) {
            Log.e(tag, "Failed to create DatagramSocket", e)
        }
    }

    private fun startSender() {
        senderJob?.cancel()
        senderJob = scope.launch(Dispatchers.IO) {
            for (packet in sendQueue) {
                try {
                    udpSocket?.send(packet)
                } catch (e: Exception) {
                    Log.e(tag, "Error sending queued UDP packet", e)
                }
            }
        }
    }

    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65507)
            while (isActive) {
                val socket = udpSocket ?: break
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val jsonStr = String(packet.data, 0, packet.length).trim()
                    handleIncomingPacket(jsonStr, packet.address, packet.port)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    delay(200)
                }
            }
        }
    }

    private fun handleIncomingPacket(jsonStr: String, sourceAddress: InetAddress, sourcePort: Int) {
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            recordingTransfer.handleMessage(json) { ack ->
                sendPacket(mapOf(
                    "type" to ack.get("type").asString,
                    "transfer_id" to ack.get("transfer_id").asString,
                    "index" to ack.get("index").asInt
                ))
            }
            val type = json.get("type")?.asString ?: ""
            when (type) {
                "pong" -> lastPongAtMs = android.os.SystemClock.elapsedRealtime()
                "connect_ack" -> {
                    val pending = pendingConnect
                    if (pending != null && pending.address == sourceAddress && pending.port == sourcePort) {
                        pending.acknowledgement.complete(json)
                    }
                }
                "auth_error" -> {
                    _isConnected.value = false
                    _connectedDeviceName.value = null
                    clearSessionState()
                    heartbeatJob?.cancel()
                    _statusMessage.value = json.get("message")?.asString ?: "Pairing PIN rejected"
                }
                "error" -> {
                    _statusMessage.value = json.get("message")?.asString ?: "PC server could not complete the request"
                }
                "media_status" -> {
                    val hasMedia = json.get("has_media")?.asBoolean ?: false
                    val title = json.get("title")?.asString ?: ""
                    val artist = json.get("artist")?.asString ?: ""
                    val album = json.get("album")?.asString ?: ""
                    val status = json.get("status")?.asString ?: "Stopped"
                    val position = json.get("position")?.asFloat ?: 0f
                    val duration = json.get("duration")?.asFloat ?: 0f
                    val volume = json.get("volume")?.asFloat ?: 0.5f
                    val muted = (json.get("is_muted") ?: json.get("muted"))?.asBoolean ?: false
                    val old = _mediaStatus.value

                    _mediaStatus.value = MediaStatus(
                        hasMedia = hasMedia,
                        title = title,
                        artist = artist,
                        album = album,
                        playerName = json.get("player")?.asString ?: "",
                        status = status,
                        position = position,
                        duration = duration,
                        volume = volume,
                        isMuted = muted,
                        artworkBase64 = json.get("artwork")?.asString ?: if (old.title == title && old.artist == artist) old.artworkBase64 else "",
                        videoPreviewBase64 = json.get("video_preview")?.asString ?: old.videoPreviewBase64,
                        volumeAvailable = json.get("volume_available")?.asBoolean ?: true,
                        volumeError = json.get("volume_error")?.asString ?: ""
                    )
                }
                "system_stats_response" -> {
                    val cpu = json.get("cpu")?.asFloat ?: 0f
                    val ram = json.get("ram")?.asFloat ?: 0f
                    val battery = json.get("battery")?.asFloat ?: -1f
                    val powerPlugged = json.get("power_plugged")?.asBoolean ?: true
                    val disk = json.get("disk")?.asFloat ?: 0f
                    val diskActivity = json.get("disk_activity")?.asFloat ?: -1f
                    val gpus = json.getAsJsonArray("gpus")?.mapNotNull { item ->
                        runCatching {
                            val gpu = item.asJsonObject
                            GpuStat(gpu.get("index")?.asInt ?: 0, gpu.get("name")?.asString ?: "GPU",
                                gpu.get("utilization")?.asFloat ?: -1f, gpu.get("engine")?.asString ?: "")
                        }.getOrNull()
                    } ?: emptyList()
                    val npu = json.get("npu")?.asFloat ?: -1f
                    _systemStats.value = SystemStats(cpu, ram, battery, powerPlugged, disk, diskActivity, gpus, npu)
                }
                "process_list_response" -> {
                    val array = json.getAsJsonArray("processes") ?: JsonArray()
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
                    val array = json.getAsJsonArray("apps") ?: JsonArray()
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
                    val loading = json.get("loading")?.asBoolean ?: false
                    val message = json.get("message")?.asString.orEmpty()
                    _statusMessage.value = when {
                        loading -> "Loading installed PC apps…"
                        message.isNotBlank() -> message
                        _statusMessage.value.startsWith("Loading installed PC apps") -> ""
                        else -> _statusMessage.value
                    }
                }
                "quick_apps_update_response" -> {
                    val success = json.get("success")?.asBoolean ?: false
                    val paths = json.getAsJsonArray("paths")?.mapNotNull { it.asString }?.toSet().orEmpty()
                    if (success) _installedApps.value = _installedApps.value.map { it.copy(isQuick = it.path in paths) }
                    _statusMessage.value = json.get("message")?.asString ?: "Could not update PC Quick Apps"
                }
                "camera_frame_response" -> {
                    val b64 = json.get("frame")?.asString
                    if (!b64.isNullOrEmpty()) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            _latestCameraFrame.value = bytes
                            if (_statusMessage.value.startsWith("Camera stream started")) _statusMessage.value = ""
                        } catch (e: Exception) {}
                    }
                }
                "camera_snapshot_start" -> {
                    val id = json.get("snapshot_id")?.asString.orEmpty()
                    val count = json.get("chunk_count")?.asInt ?: 0
                    val totalBytes = json.get("total_bytes")?.asInt ?: 0
                    val sha256 = json.get("sha256")?.asString.orEmpty()
                    if (id.isBlank() || id.length > 32 || count !in 1..10_000 || totalBytes !in 1..(8 * 1024 * 1024) ||
                        !sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                        _statusMessage.value = "PC sent invalid snapshot transfer details"
                    } else {
                        snapshotRetryJob?.cancel()
                        val assembly = SnapshotAssembly(id, totalBytes, sha256, arrayOfNulls(count))
                        pendingSnapshot = assembly
                        _statusMessage.value = "Receiving full-quality snapshot…"
                        snapshotRetryJob = scope.launch(Dispatchers.IO) {
                            repeat(5) {
                                delay(700)
                                if (pendingSnapshot !== assembly) return@launch
                                val bitmap = synchronized(assembly) {
                                    ByteArray((assembly.chunks.size + 7) / 8).also { bits ->
                                        assembly.chunks.forEachIndexed { index, bytes ->
                                            if (bytes != null) bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte()
                                        }
                                    }
                                }
                                sendPacket(mapOf(
                                    "type" to "camera_snapshot_ack",
                                    "snapshot_id" to assembly.id,
                                    "received_bitmap" to Base64.encodeToString(bitmap, Base64.NO_WRAP),
                                ))
                            }
                            if (pendingSnapshot === assembly) {
                                pendingSnapshot = null
                                _statusMessage.value = "Snapshot transfer was incomplete. Please capture it again."
                            }
                        }
                    }
                }
                "camera_snapshot_chunk" -> {
                    val assembly = pendingSnapshot
                    val id = json.get("snapshot_id")?.asString.orEmpty()
                    val index = json.get("index")?.asInt ?: -1
                    val encoded = json.get("data")?.asString.orEmpty()
                    if (assembly != null && assembly.id == id && index in assembly.chunks.indices && encoded.length <= 1400) {
                        try {
                            val chunk = Base64.decode(encoded, Base64.DEFAULT)
                            var complete = false
                            synchronized(assembly) {
                                if (assembly.chunks[index] == null && chunk.size <= 900 && assembly.receivedBytes + chunk.size <= assembly.expectedBytes) {
                                    assembly.chunks[index] = chunk
                                    assembly.receivedBytes += chunk.size
                                    assembly.receivedCount++
                                    complete = assembly.receivedCount == assembly.chunks.size
                                }
                            }
                            if (complete) {
                                val image = synchronized(assembly) {
                                    if (assembly.receivedBytes != assembly.expectedBytes) null
                                    else ByteArrayOutputStream(assembly.expectedBytes).apply {
                                        assembly.chunks.forEach { part -> if (part != null) write(part) }
                                    }.toByteArray()
                                }
                                val digest = image?.let {
                                    MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                                }
                                if (image != null && image.size == assembly.expectedBytes && digest.equals(assembly.expectedSha256, ignoreCase = true)) {
                                    _latestCameraFrame.value = image
                                    val fullBitmap = ByteArray((assembly.chunks.size + 7) / 8) { 0xFF.toByte() }
                                    val excessBits = fullBitmap.size * 8 - assembly.chunks.size
                                    if (excessBits > 0) {
                                        fullBitmap[fullBitmap.lastIndex] = ((1 shl (8 - excessBits)) - 1).toByte()
                                    }
                                    sendPacket(mapOf(
                                        "type" to "camera_snapshot_ack",
                                        "snapshot_id" to assembly.id,
                                        "received_bitmap" to Base64.encodeToString(fullBitmap, Base64.NO_WRAP),
                                    ))
                                    pendingSnapshot = null
                                    snapshotRetryJob?.cancel()
                                    _statusMessage.value = "Full-quality snapshot received"
                                } else {
                                    _statusMessage.value = "Snapshot data was incomplete. Please capture it again."
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Could not decode snapshot chunk", e)
                        }
                    }
                }
                "camera_stream_status" -> {
                    val streaming = json.get("streaming")?.asBoolean ?: false
                    _isCameraStreaming.value = streaming
                    if (!streaming) _latestCameraFrame.value = null
                    val message = json.get("message")?.asString.orEmpty()
                    _statusMessage.value = when {
                        streaming && message.isNotBlank() -> "Camera is live, but recording was skipped: $message"
                        streaming -> "Camera stream started. Waiting for a live frame…"
                        message.isNotBlank() -> "Camera stream stopped: $message"
                        _statusMessage.value.startsWith("Camera stream") -> ""
                        else -> _statusMessage.value
                    }
                }
                "audio_status_response" -> {
                    val recording = json.get("recording")?.asBoolean ?: false
                    _isAudioRecording.value = recording
                    val message = json.get("message")?.asString.orEmpty()
                    if (recording) {
                        _statusMessage.value = "Microphone recording started on PC"
                    } else if (message.isNotBlank()) {
                        _statusMessage.value = "Microphone error: $message"
                    }
                }
                "live_audio_frame" -> {
                    if (json.get("direction")?.asString == "pc_to_phone") {
                        val encoded = json.get("data")?.asString.orEmpty()
                        if (encoded.isNotEmpty()) try {
                            liveAudioPlayer.accept(Base64.decode(encoded, Base64.DEFAULT))
                        } catch (_: Exception) {}
                    }
                }
                "live_audio_status" -> {
                    val active = json.get("active")?.asBoolean ?: false
                    if (json.get("direction")?.asString == "phone_to_pc") {
                        _isPhoneLiveAudioActive.value = active
                        if (!active) liveAudioRecorder.stop()
                    } else {
                        _isPcLiveAudioActive.value = active
                        if (!active) liveAudioPlayer.stop()
                    }
                    json.get("message")?.asString?.takeIf { it.isNotBlank() }?.let { _statusMessage.value = it }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming packet: $jsonStr", e)
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastPongAtMs = android.os.SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isConnected.value) {
                    if (android.os.SystemClock.elapsedRealtime() - lastPongAtMs > 30_000L) {
                        _isConnected.value = false
                        _connectedDeviceName.value = null
                        clearSessionState()
                        _statusMessage.value = "PC server has not responded for 30 seconds"
                        break
                    }
                    sendPacket(mapOf("type" to "ping"))
                }
                delay(1500)
            }
        }
    }

    fun discoverServer(onFound: (host: String, port: Int, name: String) -> Unit) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch(Dispatchers.IO) {
            _statusMessage.value = "Searching for PC server..."
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 4000
                }
                val msg = "BT_MOUSE_DISCOVER"
                val sendData = msg.toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, 53535)
                socket.send(sendPacket)

                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)

                val reply = String(receivePacket.data, 0, receivePacket.length).trim()
                Log.d(tag, "Discovery reply received: $reply from ${receivePacket.address.hostAddress}")
                if (reply.startsWith("BT_MOUSE_SERVER:")) {
                    val parts = reply.split(":")
                    val name = if (parts.size >= 2) parts[1] else "PC Server"
                    val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 53530 else 53530
                    val host = receivePacket.address.hostAddress ?: ""

                    settings.setWifiHost(host)
                    settings.setWifiPort(port)
                    _statusMessage.value = "Found server: $name ($host:$port)"
                    connectManual(host, port, name)
                    onFound(host, port, name)
                }
            } catch (e: SocketTimeoutException) {
                _statusMessage.value = "Auto-discovery timed out. Enter IP manually."
            } catch (e: Exception) {
                Log.e(tag, "Discovery error", e)
                _statusMessage.value = "Discovery error: ${e.message}"
            } finally {
                socket?.close()
            }
        }
    }

    fun connectManual(host: String, port: Int, serverName: String = "PC Server") {
        connectJob?.cancel()
        pendingConnect = null
        heartbeatJob?.cancel()
        _isConnected.value = false
        _connectedDeviceName.value = null
        val pin = settings.serverPin.value.trim()
        if (host.isBlank() || port !in 1..65535) {
            _statusMessage.value = "Enter a valid PC address and port"
            return
        }
        if (pin.isBlank()) {
            _statusMessage.value = "Enter the pairing PIN shown by the PC companion"
            return
        }
        if (pin.length != 6 || pin.any { !it.isDigit() }) {
            _statusMessage.value = "The PC companion pairing PIN must be six digits"
            return
        }

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                _statusMessage.value = "Connecting to $host:$port..."
                val address = InetAddress.getByName(host)
                val acknowledgement = CompletableDeferred<JsonObject>()
                pendingConnect = PendingConnect(address, port, acknowledgement)
                val connectPacket = mapOf("type" to "connect", "client" to "Android BT Mouse", "token" to pin)
                var accepted = false
                var confirmedServerName = serverName
                for (attempt in 0 until 3) {
                    sendPacket(connectPacket, host, port, allowDisconnected = true)
                    val reply = withTimeoutOrNull(1200) { acknowledgement.await() }
                    if (reply != null) {
                        accepted = reply.get("success")?.asBoolean == true
                        confirmedServerName = reply.get("server_name")?.asString ?: serverName
                        if (!accepted) {
                            _statusMessage.value = reply.get("error")?.asString ?: "Pairing PIN rejected"
                        }
                        break
                    }
                }
                pendingConnect = null
                if (!accepted) {
                    _isConnected.value = false
                    if (_statusMessage.value.startsWith("Connecting")) {
                        _statusMessage.value = "PC server did not respond. Check the address, PIN, and firewall."
                    }
                    return@launch
                }

                settings.setWifiHost(host)
                settings.setWifiPort(port)
                _isConnected.value = true
                _connectedDeviceName.value = "$confirmedServerName ($host)"
                _statusMessage.value = "Connected to $confirmedServerName"
                startHeartbeat()
                queryMedia()
            } catch (e: Exception) {
                Log.e(tag, "Connection failed", e)
                _isConnected.value = false
                _statusMessage.value = "Connection failed: ${e.message}"
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        pendingConnect = null
        scope.launch(Dispatchers.IO) {
            if (_isConnected.value) sendPacket(mapOf("type" to "disconnect"))
            _isConnected.value = false
            _connectedDeviceName.value = null
            _statusMessage.value = "Disconnected"
            clearSessionState()
            heartbeatJob?.cancel()
        }
    }

    private fun clearSessionState() {
        recordingTransfer.cancel()
        snapshotRetryJob?.cancel()
        snapshotRetryJob = null
        pendingSnapshot = null
        _mediaStatus.value = MediaStatus()
        _systemStats.value = null
        _runningProcesses.value = emptyList()
        _installedApps.value = emptyList()
        _latestCameraFrame.value = null
        _isCameraStreaming.value = false
        _isAudioRecording.value = false
        liveAudioPlayer.stop()
        _isPcLiveAudioActive.value = false
        liveAudioRecorder.stop()
        _isPhoneLiveAudioActive.value = false
    }

    private fun sendPacket(
        data: Map<String, Any?>,
        hostOverride: String? = null,
        portOverride: Int? = null,
        allowDisconnected: Boolean = false
    ) {
        if (!_isConnected.value && !allowDisconnected && data["type"] != "connect") return
        val targetHost = hostOverride ?: settings.wifiHost.value
        val targetPort = portOverride ?: settings.wifiPort.value
        if (targetHost.isEmpty() || targetPort <= 0) return

        try {
            val authenticatedData = data + ("token" to settings.serverPin.value.trim())
            val json = gson.toJson(authenticatedData)
            val bytes = json.toByteArray()
            val address = InetAddress.getByName(targetHost)
            val packet = DatagramPacket(bytes, bytes.size, address, targetPort)
            val result = sendQueue.trySend(packet)
            if (result.isFailure) Log.w(tag, "Dropped UDP packet because the send queue is full")
        } catch (e: Exception) {
            Log.e(tag, "Error enqueuing UDP packet", e)
        }
    }

    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        val packet = mapOf(
            "type" to "mouse",
            "dx" to dx,
            "dy" to dy,
            "btn" to buttons,
            "v_scroll" to vScroll,
            "h_scroll" to hScroll
        )
        sendPacket(packet)
    }

    override fun sendMouseButton(buttons: Int) {
        sendMouseMove(0, 0, buttons, 0, 0)
    }

    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        sendPacket(
            mapOf(
                "type" to "key_down",
                "code" to keyCode.toInt(),
                "mod" to modifiers.toInt()
            )
        )
    }

    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        sendPacket(
            mapOf(
                "type" to "key_up",
                "code" to keyCode.toInt(),
                "mod" to modifiers.toInt()
            )
        )
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        sendPacket(
            mapOf(
                "type" to "key_stroke",
                "code" to keyCode.toInt(),
                "mod" to modifiers.toInt()
            )
        )
    }

    override fun sendMediaKey(usageCode: Int) {
        sendPacket(
            mapOf(
                "type" to "media",
                "code" to usageCode
            )
        )
    }

    override fun queryMedia(includePreview: Boolean, includeVideoPreview: Boolean) {
        sendPacket(mapOf("type" to "media_query", "include_preview" to includePreview, "include_video_preview" to includeVideoPreview))
    }

    override fun sendMediaSeek(position: Float) {
        sendPacket(
            mapOf(
                "type" to "media_seek",
                "position" to position
            )
        )
    }

    override fun sendMediaToggle() {
        sendPacket(mapOf("type" to "media_toggle"))
    }

    override fun sendMediaNext() {
        sendPacket(mapOf("type" to "media_next"))
    }

    override fun sendMediaPrev() {
        sendPacket(mapOf("type" to "media_prev"))
    }

    override fun setVolume(volume: Float) {
        sendPacket(
            mapOf(
                "type" to "set_volume",
                "volume" to volume
            )
        )
    }

    override fun setMute(mute: Boolean) {
        sendPacket(
            mapOf(
                "type" to "set_mute",
                "mute" to mute
            )
        )
    }

    override fun sendText(text: String) {
        sendPacket(
            mapOf(
                "type" to "text",
                "text" to text
            )
        )
    }

    override fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) {
        sendPacket(
            mapOf(
                "type" to "pen",
                "action" to action,
                "x" to xRatio,
                "y" to yRatio,
                "pressure" to pressure,
                "eraser" to isEraser
            )
        )
    }

    override fun launchApp(appId: String) {
        sendPacket(
            mapOf(
                "type" to "launch_app",
                "app" to appId
            )
        )
    }

    override fun systemPower(action: String) {
        sendPacket(
            mapOf(
                "type" to "system_power",
                "action" to action
            )
        )
    }

    override fun querySystemStats() {
        sendPacket(mapOf("type" to "query_system_stats"))
    }

    override fun queryRunningProcesses() {
        sendPacket(mapOf("type" to "query_processes"))
    }

    override fun killProcess(pid: Int) {
        sendPacket(mapOf("type" to "kill_process", "pid" to pid))
    }

    override fun queryInstalledApps() {
        sendPacket(mapOf("type" to "query_apps"))
    }

    override fun updateQuickApps(paths: List<String>) {
        sendPacket(mapOf("type" to "update_quick_apps", "paths" to paths))
    }

    override fun queryRecordings() {
        sendPacket(mapOf("type" to "recordings_list"))
    }

    override fun downloadRecording(id: String) {
        sendPacket(mapOf("type" to "recording_download", "id" to id))
    }

    override fun streamRecording(id: String) = recordingTransfer.stream(id) { obj ->
        sendPacket(mapOf("type" to obj.get("type").asString, "id" to (obj.get("id")?.asString ?: ""),
            "transfer_id" to (obj.get("transfer_id")?.asString ?: "")))
    }

    override fun stopRecordingStream() = recordingTransfer.stopStream { obj ->
        sendPacket(mapOf("type" to obj.get("type").asString, "id" to (obj.get("id")?.asString ?: ""),
            "transfer_id" to (obj.get("transfer_id")?.asString ?: "")))
    }

    override fun requestCameraSnapshot() {
        snapshotRetryJob?.cancel()
        pendingSnapshot = null
        sendPacket(mapOf("type" to "camera_snapshot"))
    }

    override fun startCameraStream() {
        sendPacket(mapOf("type" to "camera_stream_start"))
    }

    override fun stopCameraStream() {
        sendPacket(mapOf("type" to "camera_stream_stop"))
    }

    override fun startAudioRecord() {
        sendPacket(mapOf("type" to "audio_record_start"))
    }

    override fun stopAudioRecord() {
        sendPacket(mapOf("type" to "audio_record_stop"))
    }

    override fun startPcLiveAudio(record: Boolean) {
        if (!liveAudioPlayer.start()) {
            _statusMessage.value = "Could not start phone audio playback"
            return
        }
        _isPcLiveAudioActive.value = true
        sendPacket(mapOf("type" to "live_audio_start", "record" to record))
    }

    override fun stopPcLiveAudio() {
        sendPacket(mapOf("type" to "live_audio_stop"))
        liveAudioPlayer.stop()
        _isPcLiveAudioActive.value = false
    }

    override fun startPhoneLiveAudio(record: Boolean): Boolean {
        sendPacket(mapOf("type" to "phone_audio_start", "record" to record))
        val started = liveAudioRecorder.start(::sendPhoneAudioFrame) {
            _isPhoneLiveAudioActive.value = false
            sendPacket(mapOf("type" to "phone_audio_stop"))
        }
        _isPhoneLiveAudioActive.value = started
        if (!started) {
            sendPacket(mapOf("type" to "phone_audio_stop"))
            _statusMessage.value = "Could not start phone microphone. Check microphone permission."
        }
        return started
    }

    override fun stopPhoneLiveAudio() {
        liveAudioRecorder.stop()
        sendPacket(mapOf("type" to "phone_audio_stop"))
        _isPhoneLiveAudioActive.value = false
    }

    override fun sendPhoneAudioFrame(pcm: ByteArray) {
        sendPacket(mapOf(
            "type" to "phone_audio_frame",
            "data" to Base64.encodeToString(pcm, Base64.NO_WRAP)
        ))
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        connectJob?.cancel()
        discoveryJob?.cancel()
        receiverJob?.cancel()
        senderJob?.cancel()
        udpSocket?.close()
        udpSocket = null
    }
}
