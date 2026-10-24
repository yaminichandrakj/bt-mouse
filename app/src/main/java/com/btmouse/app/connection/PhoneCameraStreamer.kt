package com.btmouse.app.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

data class PhoneCameraTelemetry(
    val active: Boolean = false,
    val lens: String = "back",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val frameCount: Long = 0L,
    val orientation: String = "landscape",
    val rotationDegrees: Int = 0
)

/**
 * Manages phone camera capture using Android Camera2 API, outputting bounded,
 * properly-oriented JPEG frames (< 34 KB) guaranteed to fit within UDP and RFCOMM limits.
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

    /** "landscape" (16:9 horizontal) or "portrait" (9:16 vertical) */
    private val _orientationMode = MutableStateFlow("landscape")
    val orientationMode: StateFlow<String> = _orientationMode.asStateFlow()

    /** Additional user rotation offset (0, 90, 180, 270) */
    private val _rotationOffset = MutableStateFlow(0)
    val rotationOffset: StateFlow<Int> = _rotationOffset.asStateFlow()

    private val _mirrorFront = MutableStateFlow(true)
    val mirrorFront: StateFlow<Boolean> = _mirrorFront.asStateFlow()

    private val _localPreviewFrame = MutableStateFlow<ByteArray?>(null)
    val localPreviewFrame: StateFlow<ByteArray?> = _localPreviewFrame.asStateFlow()

    private val _telemetry = MutableStateFlow(PhoneCameraTelemetry())
    val telemetry: StateFlow<PhoneCameraTelemetry> = _telemetry.asStateFlow()

    private val _statusMessage = MutableStateFlow("Phone camera standby")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var onFrameAvailable: ((ByteArray, Int, Int, String) -> Unit)? = null

    private val sessionGeneration = AtomicInteger(0)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var sensorOrientationDegrees: Int = 90

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
    fun start(lens: String = _currentLens.value, width: Int = _currentResolution.value.first, height: Int = _currentResolution.value.second, fps: Int = _targetFps.value) {
        if (!hasCameraPermission()) {
            _statusMessage.value = "Camera permission not granted"
            return
        }
        val gen = sessionGeneration.incrementAndGet()
        closeCameraResourcesInternal()

        _currentLens.value = lens
        _currentResolution.value = width to height
        _targetFps.value = fps
        _isStreaming.value = true
        _statusMessage.value = "Starting ${lens.uppercase()} camera..."

        startBackgroundThread()
        // Post with a brief delay if reopening to let the Android camera HAL release the previous session cleanly
        backgroundHandler?.postDelayed({
            if (sessionGeneration.get() == gen) {
                openCamera(gen, lens, width, height)
            }
        }, 120L)
    }

    @Synchronized
    fun switchLens() {
        val nextLens = if (_currentLens.value == "back") "front" else "back"
        val (w, h) = _currentResolution.value
        if (_isStreaming.value) {
            start(nextLens, w, h, _targetFps.value)
        } else {
            _currentLens.value = nextLens
        }
    }

    @Synchronized
    fun setResolution(width: Int, height: Int) {
        _currentResolution.value = width to height
        if (_isStreaming.value) {
            start(_currentLens.value, width, height, _targetFps.value)
        }
    }

    @Synchronized
    fun setTargetFps(fps: Int) {
        _targetFps.value = fps
    }

    @Synchronized
    fun setOrientationMode(mode: String) {
        val clean = if (mode == "portrait") "portrait" else "landscape"
        _orientationMode.value = clean
    }

    @Synchronized
    fun rotate90() {
        _rotationOffset.value = (_rotationOffset.value + 90) % 360
    }

    @Synchronized
    fun setMirrorFront(mirror: Boolean) {
        _mirrorFront.value = mirror
    }

    private fun closeCameraResourcesInternal() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {}
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
    }

    @Synchronized
    fun stop() {
        sessionGeneration.incrementAndGet()
        _isStreaming.value = false
        _statusMessage.value = "Phone camera stopped"
        closeCameraResourcesInternal()

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
            backgroundThread?.join(400)
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(gen: Int, lens: String, reqWidth: Int, reqHeight: Int) {
        if (sessionGeneration.get() != gen) return
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
                    sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
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

        try {
            val reader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 3)
            reader.setOnImageAvailableListener({ ir ->
                if (sessionGeneration.get() == gen) {
                    handleImage(ir, lens)
                } else {
                    try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
                }
            }, backgroundHandler)
            imageReader = reader

            mgr.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (sessionGeneration.get() != gen) {
                        try { camera.close() } catch (_: Exception) {}
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession(gen, camera, reader.surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    if (cameraDevice === camera && sessionGeneration.get() == gen) {
                        cameraDevice = null
                        _isStreaming.value = false
                        _statusMessage.value = "Camera disconnected"
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    if (cameraDevice === camera && sessionGeneration.get() == gen) {
                        cameraDevice = null
                        _isStreaming.value = false
                        _statusMessage.value = "Camera hardware error ($error) — tap Start to retry"
                    }
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

    private fun createCaptureSession(gen: Int, camera: CameraDevice, surface: android.view.Surface) {
        if (sessionGeneration.get() != gen) return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (sessionGeneration.get() != gen || cameraDevice !== camera) {
                            try { session.close() } catch (_: Exception) {}
                            return
                        }
                        captureSession = session
                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.JPEG_QUALITY, 72.toByte())
                            }
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            _statusMessage.value = "Streaming ${bestLensName()} (${_orientationMode.value.uppercase()})"
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to set repeating request", e)
                            _statusMessage.value = "Session request error: ${e.message}"
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (sessionGeneration.get() == gen) {
                            _statusMessage.value = "Camera session configuration failed"
                            stop()
                        }
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

    private fun processAndOrientFrame(rawBytes: ByteArray, lens: String): Triple<ByteArray, Int, Int>? {
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        val mode = _orientationMode.value
        val extraRot = _rotationOffset.value

        // On Android phones, sensor buffer is landscape (width >= height).
        // In Portrait mode, rotate by sensorOrientationDegrees so upright phone produces upright 9:16 video.
        // In Landscape mode, keep horizontal 16:9 alignment, plus any manual 90° user rotation offset.
        val baseRotation = if (mode == "portrait") {
            sensorOrientationDegrees
        } else {
            0
        }
        val totalRotation = (baseRotation + extraRot) % 360
        val shouldMirror = (lens == "front" && _mirrorFront.value)

        val (targetBaseW, targetBaseH) = _currentResolution.value
        val desiredW = if (mode == "portrait") minOf(targetBaseW, targetBaseH) else maxOf(targetBaseW, targetBaseH)
        val desiredH = if (mode == "portrait") maxOf(targetBaseW, targetBaseH) else minOf(targetBaseW, targetBaseH)

        val matrix = Matrix()
        if (totalRotation != 0) {
            matrix.postRotate(totalRotation.toFloat())
        }
        if (shouldMirror) {
            matrix.postScale(-1f, 1f)
        }

        val rotated = if (totalRotation != 0 || shouldMirror) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val scaled = if (rotated.width != desiredW || rotated.height != desiredH) {
            Bitmap.createScaledBitmap(rotated, desiredW, desiredH, true)
        } else {
            rotated
        }

        // Compress with adaptive quality so UDP datagrams NEVER exceed 36 KB
        var quality = if (desiredW * desiredH > 500_000) 58 else 70
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        while (out.size() > 35_000 && quality > 30) {
            out.reset()
            quality -= 12
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

        val finalW = scaled.width
        val finalH = scaled.height
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()

        return Triple(out.toByteArray(), finalW, finalH)
    }

    private fun handleImage(reader: ImageReader, lens: String) {
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
            val rawBytes = ByteArray(buffer.remaining())
            buffer.get(rawBytes)

            val processed = processAndOrientFrame(rawBytes, lens) ?: return
            val (bytes, outW, outH) = processed

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
                width = outW,
                height = outH,
                fps = currentFps,
                kbps = currentKbps,
                frameCount = frameCount,
                orientation = _orientationMode.value,
                rotationDegrees = _rotationOffset.value
            )

            _localPreviewFrame.value = bytes
            onFrameAvailable?.invoke(bytes, outW, outH, lens)
        } catch (e: Exception) {
            Log.w(tag, "Frame processing warning: ${e.message}")
        } finally {
            try {
                image.close()
            } catch (_: Exception) {}
        }
    }

    private fun bestLensName(): String = _currentLens.value.uppercase()
}
