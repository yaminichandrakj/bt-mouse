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

class ConnectionCoordinator(
    private val context: Context,
    val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    val bluetoothHidManager = BluetoothHidManager(context, scope)
    val bluetoothRfcommManager = BluetoothRfcommManager(context, scope)
    val wifiClientManager = WifiClientManager(context.applicationContext, settings, scope)
    val remoteWanManager = RemoteWanManager(context.applicationContext, settings, scope)

    val activeMode: StateFlow<ConnectionMode> = settings.connectionMode

    override val isConnected: StateFlow<Boolean> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.isConnected,
        bluetoothHidManager.isConnected,
        wifiClientManager.isConnected,
        remoteWanManager.isConnected
    ) { mode, btRfcommConnected, btHidConnected, wifiConnected, wanConnected ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> btRfcommConnected || btHidConnected
            ConnectionMode.WIFI -> wifiConnected
            ConnectionMode.REMOTE_WAN -> wanConnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val statusMessage: StateFlow<String> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.statusMessage,
        bluetoothHidManager.statusMessage,
        wifiClientManager.statusMessage,
        remoteWanManager.statusMessage
    ) { mode, btRfcommStatus, btHidStatus, wifiStatus, wanStatus ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> {
                if (bluetoothRfcommManager.isConnected.value) btRfcommStatus
                else if (bluetoothHidManager.isConnected.value) btHidStatus
                else btRfcommStatus
            }
            ConnectionMode.WIFI -> wifiStatus
            ConnectionMode.REMOTE_WAN -> wanStatus
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Initializing...")

    override val connectedDeviceName: StateFlow<String?> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.connectedDeviceName,
        bluetoothHidManager.connectedDeviceName,
        wifiClientManager.connectedDeviceName,
        remoteWanManager.connectedDeviceName
    ) { mode, btRfcommName, btHidName, wifiName, wanName ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> btRfcommName ?: btHidName
            ConnectionMode.WIFI -> wifiName
            ConnectionMode.REMOTE_WAN -> wanName
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val currentInputConnection: InputConnection
        get() = when (settings.connectionMode.value) {
            ConnectionMode.BLUETOOTH -> {
                if (bluetoothRfcommManager.isConnected.value) bluetoothRfcommManager
                else if (bluetoothHidManager.isConnected.value) bluetoothHidManager
                else bluetoothRfcommManager
            }
            ConnectionMode.WIFI -> wifiClientManager
            ConnectionMode.REMOTE_WAN -> remoteWanManager
        }

    private val _reconnectStatus = MutableStateFlow<String?>(null)
    val reconnectStatus: StateFlow<String?> = _reconnectStatus.asStateFlow()

    private var reconnectJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var supervisionJob: Job? = null
    @Volatile private var wasConnected: Boolean = false
    @Volatile private var intentionalDisconnect: Boolean = false

    fun initialize() {
        activeInstance = this
        bluetoothHidManager.initialize()
        bluetoothRfcommManager.refreshPairedDevices()
        monitorSessionAndMedia()
        monitorConnectionSupervision()
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
        sessionMonitorJob?.cancel()
        sessionMonitorJob = scope.launch {
            combine(
                isPcLiveAudioActive,
                isPhoneLiveAudioActive,
                isCameraStreaming,
                isConnected,
                settings.backgroundSessionEnabled
            ) { pcAudio, phoneAudio, camera, connected, bgEnabled ->
                val hasActiveMedia = pcAudio || phoneAudio || camera
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
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.mediaStatus
            // Direct HID has no return channel for PC media metadata. Keep its idle state
            // isolated instead of displaying data from a previously used Wi-Fi connection.
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.mediaStatus
            ConnectionMode.WIFI -> wifiClientManager.mediaStatus
        }

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
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.systemStats
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.systemStats
            ConnectionMode.WIFI -> wifiClientManager.systemStats
        }

    override val runningProcesses: StateFlow<List<ProcessInfo>>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.runningProcesses
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.runningProcesses
            ConnectionMode.WIFI -> wifiClientManager.runningProcesses
        }

    override val installedApps: StateFlow<List<AppInfo>>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.installedApps
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.installedApps
            ConnectionMode.WIFI -> wifiClientManager.installedApps
        }

    override val latestCameraFrame: StateFlow<ByteArray?>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.latestCameraFrame
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.latestCameraFrame
            ConnectionMode.WIFI -> wifiClientManager.latestCameraFrame
        }

    override val cameraSnapshots: StateFlow<List<SnapshotEntry>>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.cameraSnapshots
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.cameraSnapshots
            ConnectionMode.WIFI -> wifiClientManager.cameraSnapshots
        }

    override val isCameraStreaming: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isCameraStreaming
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isCameraStreaming
            ConnectionMode.WIFI -> wifiClientManager.isCameraStreaming
        }

    override val isCameraRecording: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isCameraRecording
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isCameraRecording
            ConnectionMode.WIFI -> wifiClientManager.isCameraRecording
        }

    override val isAudioRecording: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isAudioRecording
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isAudioRecording
            ConnectionMode.WIFI -> wifiClientManager.isAudioRecording
        }

    override val isPcLiveAudioActive: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isPcLiveAudioActive
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isPcLiveAudioActive
            ConnectionMode.WIFI -> wifiClientManager.isPcLiveAudioActive
        }

    override val isPhoneLiveAudioActive: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isPhoneLiveAudioActive
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isPhoneLiveAudioActive
            ConnectionMode.WIFI -> wifiClientManager.isPhoneLiveAudioActive
        }

    override val isPcLiveAudioRecording: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isPcLiveAudioRecording
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isPcLiveAudioRecording
            ConnectionMode.WIFI -> wifiClientManager.isPcLiveAudioRecording
        }

    override val isPhoneLiveAudioRecording: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isPhoneLiveAudioRecording
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isPhoneLiveAudioRecording
            ConnectionMode.WIFI -> wifiClientManager.isPhoneLiveAudioRecording
        }

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

    fun cleanup() {
        if (activeInstance === this) {
            activeInstance = null
        }
        cancelReconnect()
        sessionMonitorJob?.cancel()
        supervisionJob?.cancel()
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
