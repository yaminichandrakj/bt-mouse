package com.btmouse.app.connection

import kotlinx.coroutines.flow.StateFlow

data class SystemStats(
    val cpu: Float = 0f,
    val ram: Float = 0f,
    val battery: Float = -1f,
    val powerPlugged: Boolean = true,
    val disk: Float = 0f
)

data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val cpu: Float = 0f,
    val ram: Float = 0f
)

data class AppInfo(
    val name: String = "",
    val path: String = "",
    val cmd: String = ""
)

data class CustomAppEntry(
    val name: String = "",
    val cmd: String = "",
    val icon: String = "🚀"
)

interface InputConnection {
    val isConnected: StateFlow<Boolean>
    val statusMessage: StateFlow<String>
    val connectedDeviceName: StateFlow<String?>

    fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int = 0, hScroll: Int = 0)
    fun sendMouseButton(buttons: Int)
    fun sendKeyPress(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyRelease(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyStroke(keyCode: Byte, modifiers: Byte = 0)
    fun sendMediaKey(usageCode: Int)
    fun sendText(text: String)
    fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float = 1.0f, isEraser: Boolean = false) {}

    val mediaStatus: StateFlow<com.btmouse.app.data.MediaStatus>? get() = null
    fun queryMedia() {}
    fun sendMediaSeek(position: Float) {}
    fun sendMediaToggle() {}
    fun sendMediaNext() {}
    fun sendMediaPrev() {}
    fun setVolume(volume: Float) {}
    fun setMute(mute: Boolean) {}

    fun launchApp(appId: String) {}
    fun systemPower(action: String) {}

    // Rich Desktop, Process & Hardware queries
    val systemStats: StateFlow<SystemStats?>? get() = null
    val runningProcesses: StateFlow<List<ProcessInfo>>? get() = null
    val installedApps: StateFlow<List<AppInfo>>? get() = null
    val latestCameraFrame: StateFlow<ByteArray?>? get() = null
    val isCameraStreaming: StateFlow<Boolean>? get() = null
    val isAudioRecording: StateFlow<Boolean>? get() = null

    fun querySystemStats() {}
    fun queryRunningProcesses() {}
    fun killProcess(pid: Int) {}
    fun queryInstalledApps() {}
    fun requestCameraSnapshot() {}
    fun startCameraStream() {}
    fun stopCameraStream() {}
    fun startAudioRecord() {}
    fun stopAudioRecord() {}
}
