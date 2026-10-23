package com.btmouse.app.connection

import android.content.Context
import com.btmouse.app.data.ConnectionMode
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

    val activeMode: StateFlow<ConnectionMode> = settings.connectionMode

    override val isConnected: StateFlow<Boolean> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.isConnected,
        bluetoothHidManager.isConnected,
        wifiClientManager.isConnected
    ) { mode, btRfcommConnected, btHidConnected, wifiConnected ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> btRfcommConnected || btHidConnected
            ConnectionMode.WIFI -> wifiConnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val statusMessage: StateFlow<String> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.statusMessage,
        bluetoothHidManager.statusMessage,
        wifiClientManager.statusMessage
    ) { mode, btRfcommStatus, btHidStatus, wifiStatus ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> {
                if (bluetoothRfcommManager.isConnected.value) btRfcommStatus
                else if (bluetoothHidManager.isConnected.value) btHidStatus
                else btRfcommStatus
            }
            ConnectionMode.WIFI -> wifiStatus
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Initializing...")

    override val connectedDeviceName: StateFlow<String?> = combine(
        settings.connectionMode,
        bluetoothRfcommManager.connectedDeviceName,
        bluetoothHidManager.connectedDeviceName,
        wifiClientManager.connectedDeviceName
    ) { mode, btRfcommName, btHidName, wifiName ->
        when (mode) {
            ConnectionMode.BLUETOOTH -> btRfcommName ?: btHidName
            ConnectionMode.WIFI -> wifiName
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
        }

    fun initialize() {
        bluetoothHidManager.initialize()
        bluetoothRfcommManager.refreshPairedDevices()
    }

    fun setMode(mode: ConnectionMode) {
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

    override val mediaStatus: StateFlow<com.btmouse.app.data.MediaStatus>
        get() = if (bluetoothRfcommManager.isConnected.value) bluetoothRfcommManager.mediaStatus else wifiClientManager.mediaStatus

    override fun queryMedia() {
        currentInputConnection.queryMedia()
    }

    override fun sendMediaSeek(position: Float) {
        currentInputConnection.sendMediaSeek(position)
    }

    override fun sendMediaToggle() {
        if (settings.connectionMode.value == ConnectionMode.WIFI || bluetoothRfcommManager.isConnected.value) {
            currentInputConnection.sendMediaToggle()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_PLAY_PAUSE)
        }
    }

    override fun sendMediaNext() {
        if (settings.connectionMode.value == ConnectionMode.WIFI || bluetoothRfcommManager.isConnected.value) {
            currentInputConnection.sendMediaNext()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_NEXT)
        }
    }

    override fun sendMediaPrev() {
        if (settings.connectionMode.value == ConnectionMode.WIFI || bluetoothRfcommManager.isConnected.value) {
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

    override fun unlockWorkstation(password: String) {
        currentInputConnection.unlockWorkstation(password)
    }

    fun cleanup() {
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.cleanup()
        wifiClientManager.cleanup()
    }
}
