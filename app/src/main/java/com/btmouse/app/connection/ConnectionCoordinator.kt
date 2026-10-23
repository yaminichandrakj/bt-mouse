package com.btmouse.app.connection

import android.content.Context
import com.btmouse.app.data.ConnectionMode
import com.btmouse.app.data.MediaStatus
import com.btmouse.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
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
    val wifiClientManager = WifiClientManager(settings, scope)
    val remoteWanManager = RemoteWanManager(settings, scope)

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

    fun initialize() {
        bluetoothHidManager.initialize()
        bluetoothRfcommManager.refreshPairedDevices()
    }

    fun setMode(mode: ConnectionMode) {
        val previousMode = settings.connectionMode.value
        if (mode == previousMode) return

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

    override fun queryMedia() {
        currentInputConnection.queryMedia()
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

    override val isCameraStreaming: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isCameraStreaming
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isCameraStreaming
            ConnectionMode.WIFI -> wifiClientManager.isCameraStreaming
        }

    override val isAudioRecording: StateFlow<Boolean>
        get() = when (settings.connectionMode.value) {
            ConnectionMode.REMOTE_WAN -> remoteWanManager.isAudioRecording
            ConnectionMode.BLUETOOTH -> bluetoothRfcommManager.isAudioRecording
            ConnectionMode.WIFI -> wifiClientManager.isAudioRecording
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
        remoteWanManager.disconnect()
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.cleanup()
        wifiClientManager.cleanup()
    }
}
