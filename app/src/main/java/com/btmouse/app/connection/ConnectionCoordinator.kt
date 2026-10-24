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
        wifiClientManager.isConnectionHealthy
    ) { connected, healthy ->
        connected to healthy
    }

    private val btState = combine(
        bluetoothRfcommManager.isConnected,
        bluetoothRfcommManager.isConnectionHealthy,
        bluetoothHidManager.isConnected
    ) { rfcommConn, rfcommHealthy, hidConn ->
        Triple(rfcommConn, rfcommHealthy, hidConn)
    }

    private val wanState = combine(
        remoteWanManager.isConnected,
        remoteWanManager.isConnectionHealthy
    ) { connected, healthy ->
        connected to healthy
    }

    val activeTransport: StateFlow<ActiveTransport> = combine(
        settings.failoverEnabled,
        settings.connectionMode,
        wifiState,
        btState,
        wanState
    ) { failover, mode, (wifiConn, wifiHealthy), (btRfcommConn, btRfcommHealthy, btHidConn), (wanConn, wanHealthy) ->
        if (failover) {
            when {
                wifiConn && wifiHealthy -> ActiveTransport.WIFI
                btRfcommConn && btRfcommHealthy -> ActiveTransport.BLUETOOTH_RFCOMM
                btHidConn -> ActiveTransport.BLUETOOTH_HID
                wanConn && wanHealthy -> ActiveTransport.REMOTE_WAN
                wifiConn -> ActiveTransport.WIFI
                btRfcommConn -> ActiveTransport.BLUETOOTH_RFCOMM
                wanConn -> ActiveTransport.REMOTE_WAN
                else -> ActiveTransport.NONE
            }
        } else {
            when (mode) {
                ConnectionMode.BLUETOOTH -> {
                    if (btRfcommConn) ActiveTransport.BLUETOOTH_RFCOMM
                    else if (btHidConn) ActiveTransport.BLUETOOTH_HID
                    else ActiveTransport.NONE
                }
                ConnectionMode.WIFI -> if (wifiConn) ActiveTransport.WIFI else ActiveTransport.NONE
                ConnectionMode.REMOTE_WAN -> if (wanConn) ActiveTransport.REMOTE_WAN else ActiveTransport.NONE
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, ActiveTransport.NONE)

    override val isConnected: StateFlow<Boolean> = combine(
        settings.failoverEnabled,
        settings.connectionMode,
        wifiState,
        btState,
        wanState
    ) { failover, mode, (wifiConn, _), (btRfcommConn, _, btHidConn), (wanConn, _) ->
        if (failover) {
            wifiConn || btRfcommConn || btHidConn || wanConn
        } else {
            when (mode) {
                ConnectionMode.BLUETOOTH -> btRfcommConn || btHidConn
                ConnectionMode.WIFI -> wifiConn
                ConnectionMode.REMOTE_WAN -> wanConn
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
        bluetoothRfcommManager.statusMessage,
        bluetoothHidManager.statusMessage,
        wifiClientManager.statusMessage,
        remoteWanManager.statusMessage
    ) { transport, btRfcommStatus, btHidStatus, wifiStatus, wanStatus ->
        when (transport) {
            ActiveTransport.WIFI -> wifiStatus
            ActiveTransport.BLUETOOTH_RFCOMM -> btRfcommStatus
            ActiveTransport.BLUETOOTH_HID -> btHidStatus
            ActiveTransport.REMOTE_WAN -> wanStatus
            ActiveTransport.NONE -> when (settings.connectionMode.value) {
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

    private val _reconnectStatus = MutableStateFlow<String?>(null)
    val reconnectStatus: StateFlow<String?> = _reconnectStatus.asStateFlow()

    private var reconnectJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var supervisionJob: Job? = null
    private var transportTransitionJob: Job? = null
    private var lastObservedTransport: ActiveTransport = ActiveTransport.NONE
    @Volatile private var wasConnected: Boolean = false
    @Volatile private var intentionalDisconnect: Boolean = false

    fun initialize() {
        activeInstance = this
        wifiClientManager.clientSessionId = clientSessionId
        wifiClientManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        bluetoothRfcommManager.clientSessionId = clientSessionId
        bluetoothRfcommManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        remoteWanManager.clientSessionId = clientSessionId
        remoteWanManager.nextSeqSupplier = { commandSequence.getAndIncrement() }

        phoneCameraStreamer.onFrameAvailable = { bytes, width, height, lens ->
            currentInputConnection.sendPhoneCameraFrame(bytes, width, height, lens)
        }

        bluetoothHidManager.initialize()
        bluetoothRfcommManager.refreshPairedDevices()
        monitorTransportTransitions()
        monitorSessionAndMedia()
        monitorConnectionSupervision()
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

    fun setMode(mode: ConnectionMode) {
        val previousMode = settings.connectionMode.value
        if (mode == previousMode) return

        intentionalDisconnect = true
        cancelReconnect()

        // A pending socket can otherwise complete after the UI has switched
        // transports, leaving a hidden connection active in the background.
        when (previousMode) {
            ConnectionMode.BLUETOOTH -> {
                bluetoothRfcommManager.disconnect()
                bluetoothHidManager.disconnect()
            }
            ConnectionMode.WIFI -> wifiClientManager.disconnect()
            ConnectionMode.REMOTE_WAN -> remoteWanManager.disconnect()
        }
        settings.setConnectionMode(mode)
        intentionalDisconnect = false
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
        currentInputConnection.sendPenEvent(action, xRatio, yRatio, pressure, isEraser)
    }

    override val mediaStatus: StateFlow<MediaStatus>
        get() = currentInputConnection.mediaStatus ?: bluetoothRfcommManager.mediaStatus

    override fun queryMedia(includePreview: Boolean, includeVideoPreview: Boolean) {
        currentInputConnection.queryMedia(includePreview, includeVideoPreview)
    }

    override fun sendMediaSeek(position: Float) {
        currentInputConnection.sendMediaSeek(position)
    }

    override fun sendMediaToggle() {
        if (settings.connectionMode.value == ConnectionMode.WIFI ||
            settings.connectionMode.value == ConnectionMode.REMOTE_WAN ||
            bluetoothRfcommManager.isConnected.value) {
            currentInputConnection.sendMediaToggle()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_PLAY_PAUSE)
        }
    }

    override fun sendMediaNext() {
        if (settings.connectionMode.value == ConnectionMode.WIFI ||
            settings.connectionMode.value == ConnectionMode.REMOTE_WAN ||
            bluetoothRfcommManager.isConnected.value) {
            currentInputConnection.sendMediaNext()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_NEXT)
        }
    }

    override fun sendMediaPrev() {
        if (settings.connectionMode.value == ConnectionMode.WIFI ||
            settings.connectionMode.value == ConnectionMode.REMOTE_WAN ||
            bluetoothRfcommManager.isConnected.value) {
            currentInputConnection.sendMediaPrev()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_PREV)
        }
    }

    override fun setVolume(volume: Float) {
        currentInputConnection.setVolume(volume)
    }

    override fun setMute(mute: Boolean) {
        currentInputConnection.setMute(mute)
    }

    override fun launchApp(appId: String) {
        currentInputConnection.launchApp(appId)
    }

    override fun systemPower(action: String) {
        currentInputConnection.systemPower(action)
    }

    // Rich Desktop, Hardware & Process queries
    override val systemStats: StateFlow<SystemStats?>
        get() = currentInputConnection.systemStats ?: bluetoothRfcommManager.systemStats

    override val runningProcesses: StateFlow<List<ProcessInfo>>
        get() = currentInputConnection.runningProcesses ?: bluetoothRfcommManager.runningProcesses

    override val installedApps: StateFlow<List<AppInfo>>
        get() = currentInputConnection.installedApps ?: bluetoothRfcommManager.installedApps

    override val latestCameraFrame: StateFlow<ByteArray?>
        get() = currentInputConnection.latestCameraFrame ?: bluetoothRfcommManager.latestCameraFrame

    override val cameraSnapshots: StateFlow<List<SnapshotEntry>>
        get() = currentInputConnection.cameraSnapshots ?: bluetoothRfcommManager.cameraSnapshots

    override val isCameraStreaming: StateFlow<Boolean>
        get() = currentInputConnection.isCameraStreaming ?: bluetoothRfcommManager.isCameraStreaming

    override val isCameraRecording: StateFlow<Boolean>
        get() = currentInputConnection.isCameraRecording ?: bluetoothRfcommManager.isCameraRecording

    override val isAudioRecording: StateFlow<Boolean>
        get() = currentInputConnection.isAudioRecording ?: bluetoothRfcommManager.isAudioRecording

    override val isPcLiveAudioActive: StateFlow<Boolean>
        get() = currentInputConnection.isPcLiveAudioActive ?: bluetoothRfcommManager.isPcLiveAudioActive

    override val isPhoneLiveAudioActive: StateFlow<Boolean>
        get() = currentInputConnection.isPhoneLiveAudioActive ?: bluetoothRfcommManager.isPhoneLiveAudioActive

    override val isPcLiveAudioRecording: StateFlow<Boolean>
        get() = currentInputConnection.isPcLiveAudioRecording ?: bluetoothRfcommManager.isPcLiveAudioRecording

    override val isPhoneLiveAudioRecording: StateFlow<Boolean>
        get() = currentInputConnection.isPhoneLiveAudioRecording ?: bluetoothRfcommManager.isPhoneLiveAudioRecording

    override fun querySystemStats() {
        currentInputConnection.querySystemStats()
    }

    override fun queryRunningProcesses() {
        currentInputConnection.queryRunningProcesses()
    }

    override fun killProcess(pid: Int) {
        currentInputConnection.killProcess(pid)
    }

    override fun queryInstalledApps() {
        currentInputConnection.queryInstalledApps()
    }

    override fun updateQuickApps(paths: List<String>) {
        currentInputConnection.updateQuickApps(paths)
    }

    override val recordings: StateFlow<List<RecordingEntry>>?
        get() = currentInputConnection.recordings
    override val recordingTransferStatus: StateFlow<String>?
        get() = currentInputConnection.recordingTransferStatus
    override val recordingStreamUrl: StateFlow<String?>?
        get() = currentInputConnection.recordingStreamUrl

    override fun queryRecordings() {
        currentInputConnection.queryRecordings()
    }

    override fun downloadRecording(id: String) {
        currentInputConnection.downloadRecording(id)
    }

    override fun streamRecording(id: String) {
        currentInputConnection.streamRecording(id)
    }

    override fun stopRecordingStream() {
        currentInputConnection.stopRecordingStream()
    }

    override fun deleteRecording(id: String) {
        currentInputConnection.deleteRecording(id)
    }

    override fun deleteSnapshot(path: String): Boolean {
        return currentInputConnection.deleteSnapshot(path)
    }

    override fun startPcLiveAudio(record: Boolean) {
        currentInputConnection.startPcLiveAudio(record)
    }

    override fun stopPcLiveAudio() {
        currentInputConnection.stopPcLiveAudio()
    }

    override fun startPhoneLiveAudio(record: Boolean): Boolean = currentInputConnection.startPhoneLiveAudio(record)

    override fun stopPhoneLiveAudio() {
        currentInputConnection.stopPhoneLiveAudio()
    }

    override fun requestCameraSnapshot() {
        currentInputConnection.requestCameraSnapshot()
    }

    override fun startCameraStream() {
        currentInputConnection.startCameraStream()
    }

    override fun stopCameraStream() {
        currentInputConnection.stopCameraStream()
    }

    override fun startAudioRecord() {
        currentInputConnection.startAudioRecord()
    }

    override fun stopAudioRecord() {
        currentInputConnection.stopAudioRecord()
    }

    override val isPhoneCameraStreaming: StateFlow<Boolean> = phoneCameraStreamer.isStreaming
    override val phoneCameraLocalPreview: StateFlow<ByteArray?> = phoneCameraStreamer.localPreviewFrame
    override val phoneCameraStats: StateFlow<PhoneCameraTelemetry> = phoneCameraStreamer.telemetry
    override val phoneCameraLens: StateFlow<String> = phoneCameraStreamer.currentLens
    override val phoneCameraResolution: StateFlow<Pair<Int, Int>> = phoneCameraStreamer.currentResolution
    override val phoneCameraFps: StateFlow<Int> = phoneCameraStreamer.targetFps
    override val phoneCameraStatusMessage: StateFlow<String> = phoneCameraStreamer.statusMessage

    override fun startPhoneCameraStream(lens: String, width: Int, height: Int, fps: Int) {
        currentInputConnection.startPhoneCameraStream(lens, width, height, fps)
        phoneCameraStreamer.start(lens, width, height, fps)
        CompanionSessionService.start(context)
    }

    override fun switchPhoneCameraLens() {
        phoneCameraStreamer.switchLens()
        currentInputConnection.startPhoneCameraStream(
            phoneCameraStreamer.currentLens.value,
            phoneCameraStreamer.currentResolution.value.first,
            phoneCameraStreamer.currentResolution.value.second,
            phoneCameraStreamer.targetFps.value
        )
    }

    override fun setPhoneCameraResolution(width: Int, height: Int) {
        phoneCameraStreamer.setResolution(width, height)
        if (phoneCameraStreamer.isStreaming.value) {
            currentInputConnection.startPhoneCameraStream(
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
        currentInputConnection.stopPhoneCameraStream()
        CompanionSessionService.update(context)
    }

    override fun sendPhoneCameraFrame(data: ByteArray, width: Int, height: Int, lens: String) {
        currentInputConnection.sendPhoneCameraFrame(data, width, height, lens)
    }

    fun cleanup() {
        if (activeInstance === this) {
            activeInstance = null
        }
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
