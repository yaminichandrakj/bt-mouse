package com.btmouse.app.connection

import android.util.Log
import com.btmouse.app.data.MediaStatus
import com.btmouse.app.data.SettingsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class WifiClientManager(
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

    private var udpSocket: DatagramSocket? = null
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null
    private var receiverJob: Job? = null
    private var senderJob: Job? = null

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
            val buffer = ByteArray(4096)
            while (isActive) {
                val socket = udpSocket ?: break
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val jsonStr = String(packet.data, 0, packet.length).trim()
                    handleIncomingPacket(jsonStr)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    delay(200)
                }
            }
        }
    }

    private fun handleIncomingPacket(jsonStr: String) {
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            val type = json.get("type")?.asString ?: ""
            if (type == "media_status") {
                val hasMedia = json.get("has_media")?.asBoolean ?: false
                val title = json.get("title")?.asString ?: ""
                val artist = json.get("artist")?.asString ?: ""
                val album = json.get("album")?.asString ?: ""
                val status = json.get("status")?.asString ?: "Stopped"
                val position = json.get("position")?.asFloat ?: 0f
                val duration = json.get("duration")?.asFloat ?: 0f
                val volume = json.get("volume")?.asFloat ?: 0.5f
                val muted = json.get("muted")?.asBoolean ?: false

                _mediaStatus.value = MediaStatus(
                    hasMedia = hasMedia,
                    title = title,
                    artist = artist,
                    album = album,
                    status = status,
                    position = position,
                    duration = duration,
                    volume = volume,
                    isMuted = muted
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming packet: $jsonStr", e)
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isConnected.value) {
                    sendPacket(mapOf("type" to "ping"))
                    // Periodically poll PC media state
                    queryMedia()
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
        scope.launch(Dispatchers.IO) {
            try {
                _statusMessage.value = "Connecting to $host:$port..."
                val connectPacket = mapOf("type" to "connect", "client" to "Android BT Mouse")
                sendPacket(connectPacket, host, port)
                _isConnected.value = true
                _connectedDeviceName.value = "$serverName ($host)"
                _statusMessage.value = "Connected to $serverName"
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
        scope.launch(Dispatchers.IO) {
            sendPacket(mapOf("type" to "disconnect"))
            _isConnected.value = false
            _connectedDeviceName.value = null
            _statusMessage.value = "Disconnected"
            _mediaStatus.value = MediaStatus()
            heartbeatJob?.cancel()
        }
    }

    private fun sendPacket(data: Map<String, Any>, hostOverride: String? = null, portOverride: Int? = null) {
        val targetHost = hostOverride ?: settings.wifiHost.value
        val targetPort = portOverride ?: settings.wifiPort.value
        if (targetHost.isEmpty() || targetPort <= 0) return

        try {
            val json = gson.toJson(data)
            val bytes = json.toByteArray()
            val address = InetAddress.getByName(targetHost)
            val packet = DatagramPacket(bytes, bytes.size, address, targetPort)
            sendQueue.trySend(packet)
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

    override fun queryMedia() {
        sendPacket(mapOf("type" to "media_query"))
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

    override fun unlockWorkstation(password: String) {
        sendPacket(
            mapOf(
                "type" to "unlock",
                "password" to password
            )
        )
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        discoveryJob?.cancel()
        receiverJob?.cancel()
        senderJob?.cancel()
        udpSocket?.close()
        udpSocket = null
    }
}
