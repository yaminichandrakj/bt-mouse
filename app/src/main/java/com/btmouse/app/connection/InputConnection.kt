package com.btmouse.app.connection

import android.util.Base64
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.StateFlow

data class SystemStats(
    val cpu: Float = 0f,
    val ram: Float = 0f,
    val battery: Float = -1f,
    val powerPlugged: Boolean = true,
    val disk: Float = 0f,
    val diskActivity: Float = -1f,
    val gpus: List<GpuStat> = emptyList(),
    val npu: Float = -1f,
    /** Whether Windows shows the lock screen; null when the PC cannot tell. */
    val locked: Boolean? = null
)

data class GpuStat(
    val index: Int = 0,
    val name: String = "GPU",
    val utilization: Float = -1f,
    val engine: String = ""
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
    val cmd: String = "",
    val isQuick: Boolean = false,
    val iconBase64: String = "",
    val brandKey: String = ""
)

data class CustomAppEntry(
    val name: String = "",
    val cmd: String = "",
    val icon: String = "🚀",
    val iconBase64: String = "",
    val brandKey: String = ""
)

/** One picture of the PC screen, or why the PC would not send one. */
class ScreenFrame(
    val jpeg: ByteArray?,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: JsonObject): ScreenFrame {
            val error = json.get("error")?.asString
            val bytes = json.get("frame")?.asString?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            return ScreenFrame(
                jpeg = bytes,
                width = json.get("width")?.asInt ?: 0,
                height = json.get("height")?.asInt ?: 0,
                error = error ?: if (bytes == null) "The PC sent an empty picture" else null
            )
        }
    }
}

interface InputConnection {
    val isConnected: StateFlow<Boolean>
    val statusMessage: StateFlow<String>
    val connectedDeviceName: StateFlow<String?>
    val measuredLatencyMs: StateFlow<Long?>? get() = null
    val isConnectionHealthy: StateFlow<Boolean>? get() = null
    fun notifyPathSwitch() {}
    /** Send an ecosystem message (device_hello, clipboard, files, remote control) over this link. */
    fun sendJsonObject(obj: JsonObject) {}

    fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int = 0, hScroll: Int = 0)
    fun sendMouseButton(buttons: Int)
    fun sendKeyPress(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyRelease(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyStroke(keyCode: Byte, modifiers: Byte = 0)
    fun sendMediaKey(usageCode: Int)
    fun sendText(text: String)
    fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float = 1.0f, isEraser: Boolean = false) {}

    val mediaStatus: StateFlow<com.btmouse.app.data.MediaStatus>? get() = null
    fun queryMedia(includePreview: Boolean = false, includeVideoPreview: Boolean = false) {}
    fun sendMediaSeek(position: Float) {}
    fun sendMediaToggle() {}
    fun sendMediaNext() {}
    fun sendMediaPrev() {}
    fun setVolume(volume: Float) {}
    fun setMute(mute: Boolean) {}

    /** Latest PC screen picture for the screen preview. */
    val screenFrame: StateFlow<ScreenFrame?>? get() = null
    /** Ask for one screen picture; resume = true clears a "stop sharing" from the PC. */
    fun queryScreen(maxWidth: Int, maxBytes: Int, resume: Boolean = false) {}

    fun launchApp(appId: String) {}
    fun systemPower(action: String) {}

    // Rich Desktop, Process & Hardware queries
    val systemStats: StateFlow<SystemStats?>? get() = null
    val runningProcesses: StateFlow<List<ProcessInfo>>? get() = null
    val installedApps: StateFlow<List<AppInfo>>? get() = null
    val appIcons: StateFlow<Map<String, String>>? get() = null
    val latestCameraFrame: StateFlow<ByteArray?>? get() = null
    val cameraSnapshots: StateFlow<List<SnapshotEntry>>? get() = null
    val isCameraStreaming: StateFlow<Boolean>? get() = null
    val isCameraRecording: StateFlow<Boolean>? get() = null
    val isAudioRecording: StateFlow<Boolean>? get() = null
    val recordings: StateFlow<List<RecordingEntry>>? get() = null
    val recordingTransferStatus: StateFlow<String>? get() = null
    val recordingStreamUrl: StateFlow<String?>? get() = null
    val isPcLiveAudioActive: StateFlow<Boolean>? get() = null
    val isPhoneLiveAudioActive: StateFlow<Boolean>? get() = null
    val isPcLiveAudioRecording: StateFlow<Boolean>? get() = null
    val isPhoneLiveAudioRecording: StateFlow<Boolean>? get() = null

    fun querySystemStats() {}
    fun queryRunningProcesses() {}
    fun killProcess(pid: Int) {}
    fun queryInstalledApps() {}
    fun queryAppIcons(items: List<String>) {}
    fun updateQuickApps(paths: List<String>) {}
    fun requestCameraSnapshot() {}
    fun startCameraStream() {}
    fun stopCameraStream() {}
    fun startAudioRecord() {}
    fun stopAudioRecord() {}
    fun queryRecordings() {}
    fun downloadRecording(id: String) {}
    fun streamRecording(id: String) {}
    fun stopRecordingStream() {}
    fun deleteRecording(id: String) {}
    fun deleteSnapshot(path: String): Boolean = false
    fun startPcLiveAudio(record: Boolean = false) {}
    fun stopPcLiveAudio() {}
    fun startPhoneLiveAudio(record: Boolean = false): Boolean = false
    fun stopPhoneLiveAudio() {}
    fun sendPhoneAudioFrame(pcm: ByteArray) {}

    // Phone Camera as PC Webcam
    val isPhoneCameraStreaming: StateFlow<Boolean>? get() = null
    val phoneCameraLocalPreview: StateFlow<ByteArray?>? get() = null
    val phoneCameraStats: StateFlow<PhoneCameraTelemetry>? get() = null
    val phoneCameraLens: StateFlow<String>? get() = null
    val phoneCameraResolution: StateFlow<Pair<Int, Int>>? get() = null
    val phoneCameraFps: StateFlow<Int>? get() = null
    val phoneCameraOrientation: StateFlow<String>? get() = null
    val phoneCameraRotation: StateFlow<Int>? get() = null
    val phoneCameraStatusMessage: StateFlow<String>? get() = null
    fun startPhoneCameraStream(lens: String = "back", width: Int = 640, height: Int = 360, fps: Int = 24) {}
    fun switchPhoneCameraLens() {}
    fun setPhoneCameraResolution(width: Int, height: Int) {}
    fun setPhoneCameraFps(fps: Int) {}
    fun setPhoneCameraOrientation(mode: String) {}
    fun rotatePhoneCamera90() {}
    fun stopPhoneCameraStream() {}
    fun sendPhoneCameraFrame(data: ByteArray, width: Int, height: Int, lens: String) {}
}
