package com.btmouse.app.connection

import android.content.Context
import com.btmouse.app.data.ConnectionMode
import com.btmouse.app.data.MediaStatus
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.service.CompanionSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActiveTransport {
    WIFI,
    BLUETOOTH_RFCOMM,
    BLUETOOTH_HID,
    REMOTE_WAN,
    NONE
}

private data class TransportTelemetry(
    val connected: Boolean,
    val healthy: Boolean,
    val latencyMs: Long?
)

private data class BtTransportTelemetry(
    val rfcommConnected: Boolean,
    val rfcommHealthy: Boolean,
    val rfcommLatencyMs: Long?,
    val hidConnected: Boolean
)

class ConnectionCoordinator(
    private val context: Context,
    val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    val bluetoothHidManager = BluetoothHidManager(context, scope)
    val bluetoothRfcommManager = BluetoothRfcommManager(context, scope)
    val wifiClientManager = WifiClientManager(context.applicationContext, settings, scope)
    val remoteWanManager = RemoteWanManager(context.applicationContext, settings, scope)
    val phoneCameraStreamer = PhoneCameraStreamer(context.applicationContext, scope)

    val clientSessionId: String = java.util.UUID.randomUUID().toString()
    private val commandSequence = java.util.concurrent.atomic.AtomicLong(1L)

    val activeMode: StateFlow<ConnectionMode> = settings.connectionMode

    private val wifiState = combine(
        wifiClientManager.isConnected,
        wifiClientManager.isConnectionHealthy,
        wifiClientManager.measuredLatencyMs
    ) { connected, healthy, lat ->
        TransportTelemetry(connected, healthy, lat)
    }

    private val btState = combine(
        bluetoothRfcommManager.isConnected,
        bluetoothRfcommManager.isConnectionHealthy,
        bluetoothRfcommManager.measuredLatencyMs,
        bluetoothHidManager.isConnected
    ) { rfcommConn, rfcommHealthy, rfcommLat, hidConn ->
        BtTransportTelemetry(rfcommConn, rfcommHealthy, rfcommLat, hidConn)
    }

    private val wanState = combine(
        remoteWanManager.isConnected,
        remoteWanManager.isConnectionHealthy,
        remoteWanManager.measuredLatencyMs
    ) { connected, healthy, lat ->
        TransportTelemetry(connected, healthy, lat)
    }

    val activeTransport: StateFlow<ActiveTransport> = combine(
        settings.failoverEnabled,
        settings.connectionMode,
        wifiState,
        btState,
        wanState
    ) { failover, mode, wifi, bt, wan ->
        if (failover) {
            // Dynamically rank healthy companion-capable transports by measured RTT latency.
            val healthyCompanionCandidates = buildList {
                if (wifi.connected && wifi.healthy) {
                    add(ActiveTransport.WIFI to (wifi.latencyMs ?: 15L))
                }
                if (bt.rfcommConnected && bt.rfcommHealthy) {
                    add(ActiveTransport.BLUETOOTH_RFCOMM to (bt.rfcommLatencyMs ?: 35L))
                }
                if (wan.connected && wan.healthy) {
                    add(ActiveTransport.REMOTE_WAN to (wan.latencyMs ?: 80L))
                }
            }
            if (healthyCompanionCandidates.isNotEmpty()) {
                healthyCompanionCandidates.minByOrNull { it.second }!!.first
            } else {
                when {
                    wifi.connected -> ActiveTransport.WIFI
                    bt.rfcommConnected -> ActiveTransport.BLUETOOTH_RFCOMM
                    wan.connected -> ActiveTransport.REMOTE_WAN
                    bt.hidConnected -> ActiveTransport.BLUETOOTH_HID
                    else -> ActiveTransport.NONE
                }
            }
        } else {
            when (mode) {
                ConnectionMode.BLUETOOTH -> {
                    if (bt.rfcommConnected) ActiveTransport.BLUETOOTH_RFCOMM
                    else if (bt.hidConnected) ActiveTransport.BLUETOOTH_HID
                    else ActiveTransport.NONE
                }
                ConnectionMode.WIFI -> if (wifi.connected) ActiveTransport.WIFI else ActiveTransport.NONE
                ConnectionMode.REMOTE_WAN -> if (wan.connected) ActiveTransport.REMOTE_WAN else ActiveTransport.NONE
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, ActiveTransport.NONE)

    override val isConnected: StateFlow<Boolean> = combine(
        settings.failoverEnabled,
        settings.connectionMode,
        wifiState,
        btState,
        wanState
    ) { failover, mode, wifi, bt, wan ->
        if (failover) {
            wifi.connected || bt.rfcommConnected || bt.hidConnected || wan.connected
        } else {
            when (mode) {
                ConnectionMode.BLUETOOTH -> bt.rfcommConnected || bt.hidConnected
                ConnectionMode.WIFI -> wifi.connected
                ConnectionMode.REMOTE_WAN -> wan.connected
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val measuredLatencyMs: StateFlow<Long?> = combine(
        activeTransport,
        wifiClientManager.measuredLatencyMs,
        bluetoothRfcommManager.measuredLatencyMs,
        remoteWanManager.measuredLatencyMs
    ) { transport, wifiLat, btLat, wanLat ->
        when (transport) {
            ActiveTransport.WIFI -> wifiLat
            ActiveTransport.BLUETOOTH_RFCOMM -> btLat
            ActiveTransport.REMOTE_WAN -> wanLat
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val isConnectionHealthy: StateFlow<Boolean> = combine(
        activeTransport,
        wifiClientManager.isConnectionHealthy,
        bluetoothRfcommManager.isConnectionHealthy,
        remoteWanManager.isConnectionHealthy
    ) { transport, wifiH, btH, wanH ->
        when (transport) {
            ActiveTransport.WIFI -> wifiH
            ActiveTransport.BLUETOOTH_RFCOMM -> btH
            ActiveTransport.REMOTE_WAN -> wanH
            ActiveTransport.BLUETOOTH_HID -> true
            ActiveTransport.NONE -> true
        }
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val activeTransportLabel: StateFlow<String> = combine(
        activeTransport,
        measuredLatencyMs,
        isConnectionHealthy
    ) { transport, latency, healthy ->
        val suffix = if (latency != null) " • ${latency}ms" else ""
        val warning = if (!healthy) " (High Jitter)" else ""
        when (transport) {
            ActiveTransport.WIFI -> "Wi-Fi$suffix$warning"
            ActiveTransport.BLUETOOTH_RFCOMM -> "Bluetooth RFCOMM$suffix$warning"
            ActiveTransport.BLUETOOTH_HID -> "Bluetooth HID (Direct)"
            ActiveTransport.REMOTE_WAN -> "Remote WAN$suffix$warning"
            ActiveTransport.NONE -> "Disconnected"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Disconnected")

    override val statusMessage: StateFlow<String> = combine(
        activeTransport,
        settings.connectionMode,
        bluetoothRfcommManager.statusMessage,
        bluetoothHidManager.statusMessage,
        wifiClientManager.statusMessage,
        remoteWanManager.statusMessage
    ) { values ->
        val transport = values[0] as ActiveTransport
        val mode = values[1] as ConnectionMode
        val btRfcommStatus = values[2] as String
        val btHidStatus = values[3] as String
        val wifiStatus = values[4] as String
        val wanStatus = values[5] as String

        // When browsing a specific connection tab that is not currently connected,
        // show that tab's status message so pairing/handshake feedback is visible.
        when (mode) {
            ConnectionMode.WIFI -> if (!wifiClientManager.isConnected.value && wifiStatus != "Wi-Fi disconnected") {
                return@combine wifiStatus
            }
            ConnectionMode.REMOTE_WAN -> if (!remoteWanManager.isConnected.value && wanStatus != "Disconnected") {
                return@combine wanStatus
            }
            ConnectionMode.BLUETOOTH -> if (!bluetoothRfcommManager.isConnected.value && !bluetoothHidManager.isConnected.value) {
                return@combine if (btRfcommStatus != "Bluetooth disconnected") btRfcommStatus else btHidStatus
            }
        }

        when (transport) {
            ActiveTransport.WIFI -> wifiStatus
            ActiveTransport.BLUETOOTH_RFCOMM -> btRfcommStatus
            ActiveTransport.BLUETOOTH_HID -> btHidStatus
            ActiveTransport.REMOTE_WAN -> wanStatus
            ActiveTransport.NONE -> when (mode) {
                ConnectionMode.BLUETOOTH -> btRfcommStatus
                ConnectionMode.WIFI -> wifiStatus
                ConnectionMode.REMOTE_WAN -> wanStatus
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Initializing...")

    override val connectedDeviceName: StateFlow<String?> = combine(
        activeTransport,
        bluetoothRfcommManager.connectedDeviceName,
        bluetoothHidManager.connectedDeviceName,
        wifiClientManager.connectedDeviceName,
        remoteWanManager.connectedDeviceName
    ) { transport, btRfcommName, btHidName, wifiName, wanName ->
        when (transport) {
            ActiveTransport.WIFI -> wifiName
            ActiveTransport.BLUETOOTH_RFCOMM -> btRfcommName
            ActiveTransport.BLUETOOTH_HID -> btHidName
            ActiveTransport.REMOTE_WAN -> wanName
            ActiveTransport.NONE -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Used for basic HID input (mouse, keyboard, consumer media keys).
     * Can use Bluetooth HID when no companion protocol transport is active.
     */
    private val currentInputConnection: InputConnection
        get() = when (activeTransport.value) {
            ActiveTransport.WIFI -> wifiClientManager
            ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
            ActiveTransport.BLUETOOTH_HID -> bluetoothHidManager
            ActiveTransport.REMOTE_WAN -> remoteWanManager
            ActiveTransport.NONE -> when (settings.connectionMode.value) {
                ConnectionMode.BLUETOOTH -> {
                    if (bluetoothRfcommManager.isConnected.value) bluetoothRfcommManager
                    else if (bluetoothHidManager.isConnected.value) bluetoothHidManager
                    else bluetoothRfcommManager
                }
                ConnectionMode.WIFI -> wifiClientManager
                ConnectionMode.REMOTE_WAN -> remoteWanManager
            }
        }

    /**
     * Used for PC Companion protocol commands (media state, volume, quick apps,
     * system stats, processes, recordings, camera & audio streaming).
     * Never routes to Bluetooth HID (which does not support companion JSON packets).
     */
    private val currentCompanionConnection: InputConnection
        get() = when (activeTransport.value) {
            ActiveTransport.WIFI -> wifiClientManager
            ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
            ActiveTransport.REMOTE_WAN -> remoteWanManager
            ActiveTransport.BLUETOOTH_HID, ActiveTransport.NONE -> when {
                wifiClientManager.isConnected.value -> wifiClientManager
                bluetoothRfcommManager.isConnected.value -> bluetoothRfcommManager
                remoteWanManager.isConnected.value -> remoteWanManager
                settings.connectionMode.value == ConnectionMode.WIFI -> wifiClientManager
                settings.connectionMode.value == ConnectionMode.REMOTE_WAN -> remoteWanManager
                else -> bluetoothRfcommManager
            }
        }

    private val _reconnectStatus = MutableStateFlow<String?>(null)
    val reconnectStatus: StateFlow<String?> = _reconnectStatus.asStateFlow()

    private var reconnectJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var supervisionJob: Job? = null
    private var transportTransitionJob: Job? = null
    private var lastObservedTransport: ActiveTransport = ActiveTransport.NONE
    @Volatile private var wasConnected: Boolean = false
    @Volatile private var intentionalDisconnect: Boolean = false
    @Volatile private var isInitialized: Boolean = false

    fun initialize() {
        activeInstance = this
        wifiClientManager.clientSessionId = clientSessionId
        wifiClientManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        bluetoothRfcommManager.clientSessionId = clientSessionId
        bluetoothRfcommManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        remoteWanManager.clientSessionId = clientSessionId
        remoteWanManager.nextSeqSupplier = { commandSequence.getAndIncrement() }

        phoneCameraStreamer.onFrameAvailable = { bytes, width, height, lens ->
            currentCompanionConnection.sendPhoneCameraFrame(bytes, width, height, lens)
        }

        onPermissionsUpdated()

        if (!isInitialized) {
            isInitialized = true
            monitorTransportTransitions()
            monitorSessionAndMedia()
            monitorConnectionSupervision()
        }
    }

    fun onPermissionsUpdated() {
        bluetoothHidManager.initialize()
        bluetoothHidManager.refreshPairedDevices()
        bluetoothRfcommManager.refreshPairedDevices()
    }

    private fun monitorTransportTransitions() {
        transportTransitionJob?.cancel()
        transportTransitionJob = scope.launch {
            activeTransport.collect { newTransport ->
                val oldTransport = lastObservedTransport
                if (oldTransport != ActiveTransport.NONE && oldTransport != newTransport) {
                    getTransportConnection(oldTransport)?.sendMouseButton(0)
                    getTransportConnection(newTransport)?.notifyPathSwitch()
                    getTransportConnection(newTransport)?.queryMedia()
                }
                lastObservedTransport = newTransport
            }
        }
    }

    private fun getTransportConnection(transport: ActiveTransport): InputConnection? = when (transport) {
        ActiveTransport.WIFI -> wifiClientManager
        ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
        ActiveTransport.BLUETOOTH_HID -> bluetoothHidManager
        ActiveTransport.REMOTE_WAN -> remoteWanManager
        ActiveTransport.NONE -> null
    }

    /**
     * Switches the selected connection mode/tab.
     * By default, switching tabs in the UI does not sever existing active sockets,
     * allowing users to inspect other tabs and enabling multi-transport failover.
     */
    fun setMode(mode: ConnectionMode, disconnectPrevious: Boolean = false) {
        val previousMode = settings.connectionMode.value
        if (mode == previousMode) return

        if (disconnectPrevious && !settings.failoverEnabled.value) {
            intentionalDisconnect = true
            cancelReconnect()
            when (previousMode) {
                ConnectionMode.BLUETOOTH -> {
                    bluetoothRfcommManager.disconnect()
                    bluetoothHidManager.disconnect()
                }
                ConnectionMode.WIFI -> wifiClientManager.disconnect()
                ConnectionMode.REMOTE_WAN -> remoteWanManager.disconnect()
            }
            intentionalDisconnect = false
        }
        settings.setConnectionMode(mode)
    }

    fun prepareForManualConnect() {
        intentionalDisconnect = false
        cancelReconnect()
    }

    fun disconnectAll() {
        intentionalDisconnect = true
        cancelReconnect()
        CompanionSessionService.stop(context)
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.disconnect()
        wifiClientManager.disconnect()
        remoteWanManager.disconnect()
    }

    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectStatus.value = null
    }

    private fun monitorSessionAndMedia() {
        val hasActiveMediaFlow = combine(
            isPcLiveAudioActive,
            isPhoneLiveAudioActive,
            isCameraStreaming,
            phoneCameraStreamer.isStreaming
        ) { pcAudio, phoneAudio, camera, phoneCam ->
            pcAudio || phoneAudio || camera || phoneCam
        }

        sessionMonitorJob?.cancel()
        sessionMonitorJob = scope.launch {
            combine(
                hasActiveMediaFlow,
                isConnected,
                settings.backgroundSessionEnabled
            ) { hasActiveMedia, connected, bgEnabled ->
                Triple(hasActiveMedia, connected, bgEnabled)
            }.collect { (hasActiveMedia, connected, bgEnabled) ->
                if (bgEnabled && hasActiveMedia) {
                    CompanionSessionService.start(context)
                } else if (!hasActiveMedia && !connected) {
                    CompanionSessionService.stop(context)
                } else if (hasActiveMedia) {
                    CompanionSessionService.update(context)
                }
            }
        }
    }

    private fun monitorConnectionSupervision() {
        supervisionJob?.cancel()
        supervisionJob = scope.launch {
            isConnected.collect { connected ->
                if (connected) {
                    wasConnected = true
                    cancelReconnect()
                } else {
                    if (wasConnected && !intentionalDisconnect && settings.autoReconnect.value) {
                        startAutoReconnect()
                    }
                }
            }
        }
    }

    fun startAutoReconnect() {
        cancelReconnect()
        reconnectJob = scope.launch {
            val delays = listOf(2000L, 4000L, 8000L, 16000L, 30000L)
            for (attempt in 1..delays.size) {
                if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                    _reconnectStatus.value = null
                    return@launch
                }
                val delaySec = delays[attempt - 1] / 1000
                for (sec in delaySec downTo 1) {
                    if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                        _reconnectStatus.value = null
                        return@launch
                    }
                    _reconnectStatus.value = "Reconnecting in ${sec}s (attempt $attempt/${delays.size})..."
                    delay(1000L)
                }
                if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                    _reconnectStatus.value = null
                    return@launch
                }
                _reconnectStatus.value = "Attempting reconnection ($attempt/${delays.size})..."

                val mode = settings.connectionMode.value
                when (mode) {
                    ConnectionMode.WIFI -> {
                        val host = settings.wifiHost.value
                        val port = settings.wifiPort.value
                        val pin = settings.serverPin.value.trim()
                        if (host.isNotBlank() && port in 1..65535 && pin.length == 6 && pin.all { it.isDigit() }) {
                            wifiClientManager.connectManual(host, port)
                        } else {
                            _reconnectStatus.value = "Auto-reconnect cancelled: check Wi-Fi host & PIN"
                            return@launch
                        }
                    }
                    ConnectionMode.REMOTE_WAN -> {
                        val url = settings.wanUrl.value.trim()
                        val token = settings.wanToken.value.trim()
                        if (url.isNotBlank()) {
                            remoteWanManager.connect(url, token)
                        } else {
                            _reconnectStatus.value = "Auto-reconnect cancelled: missing WAN URL"
                            return@launch
                        }
                    }
                    ConnectionMode.BLUETOOTH -> {
                        val device = bluetoothRfcommManager.lastConnectedDevice
                        if (device != null) {
                            bluetoothRfcommManager.connectToDevice(device)
                            bluetoothHidManager.connectToDevice(device)
                        } else {
                            bluetoothRfcommManager.refreshPairedDevices()
                            val paired = bluetoothRfcommManager.pairedDevices.value
                            if (paired.isNotEmpty()) {
                                bluetoothRfcommManager.connectToDevice(paired.first())
                                bluetoothHidManager.connectToDevice(paired.first())
                            } else {
                                _reconnectStatus.value = "Auto-reconnect cancelled: no paired device"
                                return@launch
                            }
                        }
                    }
                }

                var elapsed = 0
                while (elapsed < 4000 && !isConnected.value && !intentionalDisconnect) {
                    delay(250)
                    elapsed += 250
                }
                if (isConnected.value) {
                    _reconnectStatus.value = null
                    return@launch
                }
            }
            _reconnectStatus.value = "Reconnect timed out. Tap to retry."
        }
    }

    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        currentInputConnection.sendMouseMove(dx, dy, buttons, vScroll, hScroll)
    }

    override fun sendMouseButton(buttons: Int) {
        currentInputConnection.sendMouseButton(buttons)
    }

    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyPress(keyCode, modifiers)
    }

    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyRelease(keyCode, modifiers)
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyStroke(keyCode, modifiers)
    }

    override fun sendMediaKey(usageCode: Int) {
        currentInputConnection.sendMediaKey(usageCode)
    }

    override fun sendText(text: String) {
        currentInputConnection.sendText(text)
    }

    override fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) {
        currentCompanionConnection.sendPenEvent(action, xRatio, yRatio, pressure, isEraser)
    }

    override val mediaStatus: StateFlow<MediaStatus>
        get() = currentCompanionConnection.mediaStatus ?: bluetoothRfcommManager.mediaStatus

    override fun queryMedia(includePreview: Boolean, includeVideoPreview: Boolean) {
        currentCompanionConnection.queryMedia(includePreview, includeVideoPreview)
    }

    override fun sendMediaSeek(position: Float) {
        currentCompanionConnection.sendMediaSeek(position)
    }

    override fun sendMediaToggle() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaToggle()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_PLAY_PAUSE)
        }
    }

    override fun sendMediaNext() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaNext()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_NEXT)
        }
    }

    override fun sendMediaPrev() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaPrev()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_PREV)
        }
    }

    override fun setVolume(volume: Float) {
        currentCompanionConnection.setVolume(volume)
    }

    override fun setMute(mute: Boolean) {
        currentCompanionConnection.setMute(mute)
    }

    override fun launchApp(appId: String) {
        currentCompanionConnection.launchApp(appId)
    }

    override fun systemPower(action: String) {
        currentCompanionConnection.systemPower(action)
    }

    // Rich Desktop, Hardware & Process queries
    override val systemStats: StateFlow<SystemStats?>
        get() = currentCompanionConnection.systemStats ?: bluetoothRfcommManager.systemStats

    override val runningProcesses: StateFlow<List<ProcessInfo>>
        get() = currentCompanionConnection.runningProcesses ?: bluetoothRfcommManager.runningProcesses

    override val installedApps: StateFlow<List<AppInfo>>
        get() = currentCompanionConnection.installedApps ?: bluetoothRfcommManager.installedApps

    override val latestCameraFrame: StateFlow<ByteArray?>
        get() = currentCompanionConnection.latestCameraFrame ?: bluetoothRfcommManager.latestCameraFrame

    override val cameraSnapshots: StateFlow<List<SnapshotEntry>>
        get() = currentCompanionConnection.cameraSnapshots ?: bluetoothRfcommManager.cameraSnapshots

    override val isCameraStreaming: StateFlow<Boolean>
        get() = currentCompanionConnection.isCameraStreaming ?: bluetoothRfcommManager.isCameraStreaming

    override val isCameraRecording: StateFlow<Boolean>
        get() = currentCompanionConnection.isCameraRecording ?: bluetoothRfcommManager.isCameraRecording

    override val isAudioRecording: StateFlow<Boolean>
        get() = currentCompanionConnection.isAudioRecording ?: bluetoothRfcommManager.isAudioRecording

    override val isPcLiveAudioActive: StateFlow<Boolean>
        get() = currentCompanionConnection.isPcLiveAudioActive ?: bluetoothRfcommManager.isPcLiveAudioActive

    override val isPhoneLiveAudioActive: StateFlow<Boolean>
        get() = currentCompanionConnection.isPhoneLiveAudioActive ?: bluetoothRfcommManager.isPhoneLiveAudioActive

    override val isPcLiveAudioRecording: StateFlow<Boolean>
        get() = currentCompanionConnection.isPcLiveAudioRecording ?: bluetoothRfcommManager.isPcLiveAudioRecording

    override val isPhoneLiveAudioRecording: StateFlow<Boolean>
        get() = currentCompanionConnection.isPhoneLiveAudioRecording ?: bluetoothRfcommManager.isPhoneLiveAudioRecording

    override fun querySystemStats() {
        currentCompanionConnection.querySystemStats()
    }

    override fun queryRunningProcesses() {
        currentCompanionConnection.queryRunningProcesses()
    }

    override fun killProcess(pid: Int) {
        currentCompanionConnection.killProcess(pid)
    }

    override fun queryInstalledApps() {
        currentCompanionConnection.queryInstalledApps()
    }

    override fun updateQuickApps(paths: List<String>) {
        currentCompanionConnection.updateQuickApps(paths)
    }

    override val recordings: StateFlow<List<RecordingEntry>>?
        get() = currentCompanionConnection.recordings
    override val recordingTransferStatus: StateFlow<String>?
        get() = currentCompanionConnection.recordingTransferStatus
    override val recordingStreamUrl: StateFlow<String?>?
        get() = currentCompanionConnection.recordingStreamUrl

    override fun queryRecordings() {
        currentCompanionConnection.queryRecordings()
    }

    override fun downloadRecording(id: String) {
        currentCompanionConnection.downloadRecording(id)
    }

    override fun streamRecording(id: String) {
        currentCompanionConnection.streamRecording(id)
    }

    override fun stopRecordingStream() {
        currentCompanionConnection.stopRecordingStream()
    }

    override fun deleteRecording(id: String) {
        currentCompanionConnection.deleteRecording(id)
    }

    override fun deleteSnapshot(path: String): Boolean {
        return currentCompanionConnection.deleteSnapshot(path)
    }

    override fun startPcLiveAudio(record: Boolean) {
        currentCompanionConnection.startPcLiveAudio(record)
    }

    override fun stopPcLiveAudio() {
        currentCompanionConnection.stopPcLiveAudio()
    }

    override fun startPhoneLiveAudio(record: Boolean): Boolean = currentCompanionConnection.startPhoneLiveAudio(record)

    override fun stopPhoneLiveAudio() {
        currentCompanionConnection.stopPhoneLiveAudio()
    }

    override fun requestCameraSnapshot() {
        currentCompanionConnection.requestCameraSnapshot()
    }

    override fun startCameraStream() {
        currentCompanionConnection.startCameraStream()
    }

    override fun stopCameraStream() {
        currentCompanionConnection.stopCameraStream()
    }

    override fun startAudioRecord() {
        currentCompanionConnection.startAudioRecord()
    }

    override fun stopAudioRecord() {
        currentCompanionConnection.stopAudioRecord()
    }

    override val isPhoneCameraStreaming: StateFlow<Boolean> = phoneCameraStreamer.isStreaming
    override val phoneCameraLocalPreview: StateFlow<ByteArray?> = phoneCameraStreamer.localPreviewFrame
    override val phoneCameraStats: StateFlow<PhoneCameraTelemetry> = phoneCameraStreamer.telemetry
    override val phoneCameraLens: StateFlow<String> = phoneCameraStreamer.currentLens
    override val phoneCameraResolution: StateFlow<Pair<Int, Int>> = phoneCameraStreamer.currentResolution
    override val phoneCameraFps: StateFlow<Int> = phoneCameraStreamer.targetFps
    override val phoneCameraStatusMessage: StateFlow<String> = phoneCameraStreamer.statusMessage

    override fun startPhoneCameraStream(lens: String, width: Int, height: Int, fps: Int) {
        currentCompanionConnection.startPhoneCameraStream(lens, width, height, fps)
        phoneCameraStreamer.start(lens, width, height, fps)
        if (phoneCameraStreamer.isStreaming.value) {
            CompanionSessionService.start(context)
        }
    }

    override fun switchPhoneCameraLens() {
        phoneCameraStreamer.switchLens()
        currentCompanionConnection.startPhoneCameraStream(
            phoneCameraStreamer.currentLens.value,
            phoneCameraStreamer.currentResolution.value.first,
            phoneCameraStreamer.currentResolution.value.second,
            phoneCameraStreamer.targetFps.value
        )
    }

    override fun setPhoneCameraResolution(width: Int, height: Int) {
        phoneCameraStreamer.setResolution(width, height)
        if (phoneCameraStreamer.isStreaming.value) {
            currentCompanionConnection.startPhoneCameraStream(
                phoneCameraStreamer.currentLens.value,
                width,
                height,
                phoneCameraStreamer.targetFps.value
            )
        }
    }

    override fun setPhoneCameraFps(fps: Int) {
        phoneCameraStreamer.setTargetFps(fps)
    }

    override fun stopPhoneCameraStream() {
        phoneCameraStreamer.stop()
        currentCompanionConnection.stopPhoneCameraStream()
        CompanionSessionService.update(context)
    }

    override fun sendPhoneCameraFrame(data: ByteArray, width: Int, height: Int, lens: String) {
        currentCompanionConnection.sendPhoneCameraFrame(data, width, height, lens)
    }

    fun cleanup() {
        if (activeInstance === this) {
            activeInstance = null
        }
        isInitialized = false
        cancelReconnect()
        transportTransitionJob?.cancel()
        sessionMonitorJob?.cancel()
        supervisionJob?.cancel()
        phoneCameraStreamer.stop()
        CompanionSessionService.stop(context)
        remoteWanManager.disconnect()
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.cleanup()
        wifiClientManager.cleanup()
    }

    companion object {
        @Volatile
        var activeInstance: ConnectionCoordinator? = null
            internal set
    }
}
