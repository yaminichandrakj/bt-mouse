package com.btmouse.app.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhoneCameraTelemetry(
    val active: Boolean = false,
    val lens: String = "back",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val frameCount: Long = 0L
)

/**
 * Manages phone camera capture using Android Camera2 API, outputting bounded JPEG frames
 * for wireless streaming to the PC Companion server.
 */
class PhoneCameraStreamer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "PhoneCameraStreamer"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentLens = MutableStateFlow("back")
    val currentLens: StateFlow<String> = _currentLens.asStateFlow()

    private val _currentResolution = MutableStateFlow(640 to 360)
    val currentResolution: StateFlow<Pair<Int, Int>> = _currentResolution.asStateFlow()

    private val _targetFps = MutableStateFlow(24)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _localPreviewFrame = MutableStateFlow<ByteArray?>(null)
    val localPreviewFrame: StateFlow<ByteArray?> = _localPreviewFrame.asStateFlow()

    private val _telemetry = MutableStateFlow(PhoneCameraTelemetry())
    val telemetry: StateFlow<PhoneCameraTelemetry> = _telemetry.asStateFlow()

    private val _statusMessage = MutableStateFlow("Phone camera standby")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var onFrameAvailable: ((ByteArray, Int, Int, String) -> Unit)? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var lastSentMs: Long = 0L
    private var frameCount: Long = 0L
    private val fpsWindow = mutableListOf<Long>()
    private val bitrateWindow = mutableListOf<Pair<Long, Int>>()

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun start(lens: String = "back", width: Int = 640, height: Int = 360, fps: Int = 24) {
        if (!hasCameraPermission()) {
            _statusMessage.value = "Camera permission not granted"
            return
        }
        stop()

        _currentLens.value = lens
        _currentResolution.value = width to height
        _targetFps.value = fps
        _isStreaming.value = true
        _statusMessage.value = "Starting ${lens.uppercase()} camera..."

        startBackgroundThread()
        openCamera(lens, width, height)
    }

    @Synchronized
    fun switchLens() {
        val nextLens = if (_currentLens.value == "back") "front" else "back"
        val (w, h) = _currentResolution.value
        start(nextLens, w, h, _targetFps.value)
    }

    @Synchronized
    fun setResolution(width: Int, height: Int) {
        if (_isStreaming.value) {
            start(_currentLens.value, width, height, _targetFps.value)
        } else {
            _currentResolution.value = width to height
        }
    }

    @Synchronized
    fun setTargetFps(fps: Int) {
        _targetFps.value = fps
    }

    @Synchronized
    fun stop() {
        _isStreaming.value = false
        _statusMessage.value = "Phone camera stopped"

        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        stopBackgroundThread()

        _localPreviewFrame.value = null
        _telemetry.value = PhoneCameraTelemetry(active = false)
        frameCount = 0L
        fpsWindow.clear()
        bitrateWindow.clear()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("PhoneCamThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(lens: String, reqWidth: Int, reqHeight: Int) {
        val mgr = cameraManager ?: run {
            _statusMessage.value = "CameraManager unavailable"
            _isStreaming.value = false
            return
        }

        val targetFacing = if (lens == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        var selectedCameraId: String? = null
        var bestSize = Size(reqWidth, reqHeight)

        try {
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    selectedCameraId = id
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    bestSize = findClosestSize(sizes, reqWidth, reqHeight)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to enumerate cameras", e)
            _statusMessage.value = "Camera search failed: ${e.message}"
            stop()
            return
        }

        if (selectedCameraId == null) {
            _statusMessage.value = "No ${lens.uppercase()} camera found"
            stop()
            return
        }

        _currentResolution.value = bestSize.width to bestSize.height

        try {
            val reader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ ir ->
                handleImage(ir, bestSize.width, bestSize.height, lens)
            }, backgroundHandler)
            imageReader = reader

            mgr.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, reader.surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isStreaming.value = false
                    _statusMessage.value = "Camera disconnected"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isStreaming.value = false
                    _statusMessage.value = "Camera hardware error: $error"
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(tag, "Error opening camera", e)
            _statusMessage.value = "Could not open camera: ${e.message}"
            stop()
        }
    }

    private fun findClosestSize(sizes: Array<Size>, reqW: Int, reqH: Int): Size {
        if (sizes.isEmpty()) return Size(reqW, reqH)
        val targetArea = reqW * reqH
        return sizes.minByOrNull { s ->
            val areaDiff = kotlin.math.abs(s.width * s.height - targetArea)
            val aspectDiff = kotlin.math.abs((s.width.toDouble() / s.height.toDouble()) - (reqW.toDouble() / reqH.toDouble()))
            (aspectDiff * 100000).toInt() + areaDiff
        } ?: sizes[0]
    }

    private fun createCaptureSession(camera: CameraDevice, surface: android.view.Surface) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            _statusMessage.value = "Streaming ${bestLensName()}"
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to set repeating request", e)
                            _statusMessage.value = "Session request error: ${e.message}"
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _statusMessage.value = "Camera session configuration failed"
                        stop()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to create capture session", e)
            _statusMessage.value = "Failed capture session: ${e.message}"
            stop()
        }
    }

    private fun handleImage(reader: ImageReader, width: Int, height: Int, lens: String) {
        val image = try {
            reader.acquireLatestImage() ?: return
        } catch (_: Exception) {
            return
        }

        try {
            val now = SystemClock.elapsedRealtime()
            val fpsLimit = _targetFps.value
            val minInterval = if (fpsLimit > 0) 1000L / fpsLimit else 40L

            if (now - lastSentMs < minInterval) {
                return
            }
            lastSentMs = now

            val plane = image.planes.getOrNull(0) ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            frameCount++
            fpsWindow.add(now)
            bitrateWindow.add(now to bytes.size)

            val cutoff = now - 2000L
            fpsWindow.removeAll { it < cutoff }
            bitrateWindow.removeAll { it.first < cutoff }

            val dt = if (fpsWindow.size > 1) (fpsWindow.last() - fpsWindow.first()) / 1000.0 else 1.0
            val currentFps = if (dt > 0) kotlin.math.round((fpsWindow.size - 1) / dt * 10.0) / 10.0 else 0.0
            val totalBytes = bitrateWindow.sumOf { it.second }
            val currentKbps = if (dt > 0) kotlin.math.round((totalBytes * 8.0 / 1000.0) / dt * 10.0) / 10.0 else 0.0

            _telemetry.value = PhoneCameraTelemetry(
                active = true,
                lens = lens,
                width = width,
                height = height,
                fps = currentFps,
                kbps = currentKbps,
                frameCount = frameCount
            )

            _localPreviewFrame.value = bytes
            onFrameAvailable?.invoke(bytes, width, height, lens)
        } finally {
            try {
                image.close()
            } catch (_: Exception) {}
        }
    }

    private fun bestLensName(): String = _currentLens.value.uppercase()
}
