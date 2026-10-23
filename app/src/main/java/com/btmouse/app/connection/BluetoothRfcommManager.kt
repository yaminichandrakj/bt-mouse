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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

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
                _isConnected.value = true
                _connectedDeviceName.value = devName
                _statusMessage.value = "Connected to $devName (Bluetooth)"
                Log.d(tag, "Connected to $devName via RFCOMM")

                // Handshake
                val handshake = JsonObject().apply {
                    addProperty("type", "connect")
                    addProperty("client", "Android Phone (BT)")
                }
                sendJson(handshake)
                queryMedia()

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

    fun disconnect() {
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
        _statusMessage.value = "Bluetooth disconnected"
    }

    private fun processIncomingPacket(line: String) {
        try {
            val root = gson.fromJson(line, JsonObject::class.java) ?: return
            val type = root.get("type")?.asString ?: ""
            when (type) {
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
                                cmd = obj.get("cmd")?.asString ?: "",
                                isQuick = obj.get("is_quick")?.asBoolean ?: false
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
                        } catch (e: Exception) {}
                    }
                }
                "camera_stream_status" -> {
                    val streaming = root.get("streaming")?.asBoolean ?: false
                    _isCameraStreaming.value = streaming
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
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing incoming packet", e)
        }
    }

    private fun sendJson(obj: JsonObject) {
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

    override fun queryMedia() {
        val obj = JsonObject().apply {
            addProperty("type", "media_query")
        }
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
