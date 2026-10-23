package com.btmouse.app.connection

import android.util.Log
import com.btmouse.app.data.SettingsRepository
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var udpSocket: DatagramSocket? = null
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null

    init {
        try {
            udpSocket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(tag, "Failed to create DatagramSocket", e)
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isConnected.value) {
                    sendPacket(mapOf("type" to "ping"))
                }
                delay(3000)
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
            heartbeatJob?.cancel()
        }
    }

    private fun sendPacket(data: Map<String, Any>, hostOverride: String? = null, portOverride: Int? = null) {
        val socket = udpSocket ?: return
        val targetHost = hostOverride ?: settings.wifiHost.value
        val targetPort = portOverride ?: settings.wifiPort.value

        try {
            val json = gson.toJson(data)
            val bytes = json.toByteArray()
            val address = InetAddress.getByName(targetHost)
            val packet = DatagramPacket(bytes, bytes.size, address, targetPort)
            socket.send(packet)
        } catch (e: Exception) {
            Log.e(tag, "Error sending UDP packet", e)
        }
    }

    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        scope.launch(Dispatchers.IO) {
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
    }

    override fun sendMouseButton(buttons: Int) {
        sendMouseMove(0, 0, buttons, 0, 0)
    }

    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        scope.launch(Dispatchers.IO) {
            sendPacket(
                mapOf(
                    "type" to "key_down",
                    "code" to keyCode.toInt(),
                    "mod" to modifiers.toInt()
                )
            )
        }
    }

    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        scope.launch(Dispatchers.IO) {
            sendPacket(
                mapOf(
                    "type" to "key_up",
                    "code" to keyCode.toInt(),
                    "mod" to modifiers.toInt()
                )
            )
        }
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        scope.launch(Dispatchers.IO) {
            sendPacket(
                mapOf(
                    "type" to "key_stroke",
                    "code" to keyCode.toInt(),
                    "mod" to modifiers.toInt()
                )
            )
        }
    }

    override fun sendMediaKey(usageCode: Int) {
        scope.launch(Dispatchers.IO) {
            sendPacket(
                mapOf(
                    "type" to "media",
                    "code" to usageCode
                )
            )
        }
    }

    override fun sendText(text: String) {
        scope.launch(Dispatchers.IO) {
            sendPacket(
                mapOf(
                    "type" to "text",
                    "text" to text
                )
            )
        }
    override fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) {
        scope.launch(Dispatchers.IO) {
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
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        discoveryJob?.cancel()
        udpSocket?.close()
        udpSocket = null
    }
}
