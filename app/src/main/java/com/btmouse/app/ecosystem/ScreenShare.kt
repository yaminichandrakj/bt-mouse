package com.btmouse.app.ecosystem

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Shares this screen as a live stream. Android asks the user every time (the system
 * "Start recording or casting?" prompt), and the session service must already be in the
 * foreground with the mediaProjection type before capture starts.
 *
 * Pictures are only taken while someone is watching (the PC reports the viewer count).
 */
object ScreenShare {
    private const val TAG = "ScreenShare"
    private const val TARGET_WIDTH = 720
    private const val FPS = 12
    // Sealed and base64 twice it must still fit one 64 KB datagram.
    private const val MAX_JPEG_BYTES = 30_000

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile var viewers = 0
    @Volatile private var pending: Pair<Int, Intent>? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastSent = 0L
    /** Latest picture, for a hub client's Screen preview. */
    @Volatile var lastJpeg: ByteArray? = null
        private set
    @Volatile var lastWidth = 0
        private set
    @Volatile var lastHeight = 0
        private set
    private var appContext: Context? = null

    /** True while the service must run with the mediaProjection type. */
    val needsForeground: Boolean get() = pending != null || _active.value

    private var audioRecord: android.media.AudioRecord? = null
    private var audioThread: Thread? = null

    /**
     * Mirrors the sound apps play (Android 10+, apps that allow capture) as 16 kHz mono,
     * so whoever watches this screen hears it too. Needs the microphone permission.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startAudio(context: Context, mp: MediaProjection) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                .build()
            val record = android.media.AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(16000)
                .build()
            record.startRecording()
            audioRecord = record
            audioThread = Thread({
                val frame = ByteArray(1280) // 40 ms
                while (!Thread.currentThread().isInterrupted) {
                    val n = runCatching { record.read(frame, 0, frame.size) }.getOrDefault(-1)
                    if (n <= 0) break
                    if (viewers > 0) Ecosystem.publishAudio(frame.copyOf(n))
                }
            }, "ScreenAudio").apply { isDaemon = true; start() }
        }.onFailure { Log.w(TAG, "No audio capture", it) }
    }

    fun captureIntent(context: Context): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    /** The user allowed capture; the session service will call [beginIfPending] once in the foreground. */
    fun setPermission(resultCode: Int, data: Intent) {
        pending = resultCode to data
    }

    fun beginIfPending(context: Context) {
        val (code, data) = pending ?: return
        pending = null
        appContext = context.applicationContext
        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data) ?: return
            projection = mp
            val t = HandlerThread("ScreenShare").apply { start() }
            thread = t
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, Handler(t.looper))
            createDisplay(context)
            startAudio(context, mp)
            _active.value = true
            Ecosystem.announceStream("screen", "Screen", true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start screen capture", e)
            stop()
        }
    }

    private fun createDisplay(context: Context) {
        val mp = projection ?: return
        val looper = thread?.looper ?: return
        val (sw, sh) = DeviceIdentity.screenSize(context)
        val scale = TARGET_WIDTH.toFloat() / minOf(sw, sh)
        val w = ((sw * scale).toInt() / 2) * 2
        val h = ((sh * scale).toInt() / 2) * 2
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it, sw, sh) }, Handler(looper))
        val dpi = context.resources.displayMetrics.densityDpi
        val old = display
        if (old != null) {
            old.surface = r.surface
            old.resize(w, h, dpi)
        } else {
            display = mp.createVirtualDisplay("LanyardScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        }
        reader?.close()
        reader = r
    }

    /** Called on rotation so the picture keeps the right shape. */
    fun onConfigurationChanged() {
        val ctx = appContext ?: return
        if (_active.value) thread?.looper?.let { Handler(it).post { runCatching { createDisplay(ctx) } } }
    }

    private fun onImage(r: ImageReader, deviceW: Int, deviceH: Int) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (viewers + AndroidHub.screenViewers() <= 0 || now - lastSent < 1000L / FPS) return
            lastSent = now
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            var bmp = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            var jpeg = encode(bmp)
            while (jpeg.size > MAX_JPEG_BYTES && bmp.width > 240) {
                val smaller = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 4, bmp.height * 3 / 4, true)
                bmp.recycle()
                bmp = smaller
                jpeg = encode(bmp)
            }
            bmp.recycle()
            if (jpeg.size <= MAX_JPEG_BYTES) {
                lastJpeg = jpeg
                lastWidth = deviceW
                lastHeight = deviceH
                Ecosystem.publishFrame("screen", jpeg, deviceW, deviceH)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame failed", e)
        } finally {
            image.close()
        }
    }

    private fun encode(bmp: Bitmap): ByteArray {
        var quality = 60
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_JPEG_BYTES || quality <= 25) return out.toByteArray()
            quality -= 12
        }
    }

    fun stop() {
        val wasActive = _active.value
        _active.value = false
        pending = null
        runCatching { display?.release() }
        runCatching { reader?.close() }
        audioThread?.interrupt()
        audioThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
        thread?.quitSafely()
        thread = null
        viewers = 0
        lastJpeg = null
        if (wasActive) Ecosystem.announceStream("screen", "Screen", false)
    }
}
