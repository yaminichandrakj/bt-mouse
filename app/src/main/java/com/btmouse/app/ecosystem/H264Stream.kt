package com.btmouse.app.ecosystem

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Surface
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Smooth mirroring (beta)": real H.264 video instead of a run of JPEG pictures, for
 * viewers that ask for it. The shared screen's pictures are drawn onto a hardware
 * encoder's input surface; each encoded frame goes out in ~24 KB parts
 * (`stream_h264`), with the codec config re-sent before every key frame so a viewer
 * can start or recover at any key frame. Viewers that lose a part ask for one
 * (`stream_keyframe`).
 */
object H264Encoder {
    private const val TAG = "H264Encoder"
    private const val PART = 24_000

    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var thread: Thread? = null
    private var width = 0
    private var height = 0
    @Volatile private var config: ByteArray? = null
    @Volatile private var seq = 0L
    @Volatile var deviceW = 0
    @Volatile var deviceH = 0

    val running: Boolean get() = codec != null

    @Synchronized
    fun feed(bmp: Bitmap, devW: Int, devH: Int) {
        deviceW = devW
        deviceH = devH
        // Encoders want even sizes; restart when the picture changes shape (rotation).
        val w = bmp.width and 1.inv()
        val h = bmp.height and 1.inv()
        if (codec == null || w != width || h != height) {
            stop()
            if (!start(w, h)) return
        }
        val surface = input ?: return
        runCatching {
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure { Log.w(TAG, "Draw failed", it) }
    }

    private fun start(w: Int, h: Int): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Repeat the last frame when the screen is still, so viewers don't stall.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = c.createInputSurface()
        c.start()
        codec = c
        width = w
        height = h
        config = null
        thread = Thread({ drain(c) }, "H264Encoder").apply { isDaemon = true; start() }
        true
    }.getOrElse {
        Log.w(TAG, "No H.264 encoder", it)
        codec = null
        false
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (codec === c) {
            val index = runCatching { c.dequeueOutputBuffer(info, 50_000) }.getOrDefault(-1)
            if (index < 0) continue
            runCatching {
                val buffer = c.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (isConfig) {
                        config = bytes
                    } else {
                        if (isKey) config?.let { send(it, key = false, isConfig = true) }
                        send(bytes, key = isKey, isConfig = false)
                    }
                }
                c.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun send(bytes: ByteArray, key: Boolean, isConfig: Boolean) {
        val id = ++seq
        val parts = (bytes.size + PART - 1) / PART
        for (p in 0 until parts) {
            val from = p * PART
            val to = minOf(bytes.size, from + PART)
            Ecosystem.publishH264(JsonObject().apply {
                addProperty("seq", id)
                addProperty("part", p)
                addProperty("parts", parts)
                addProperty("key", key)
                addProperty("config", isConfig)
                addProperty("w", width)
                addProperty("h", height)
                addProperty("dw", deviceW)
                addProperty("dh", deviceH)
                addProperty("data", Base64.encodeToString(bytes, from, to - from, Base64.NO_WRAP))
            })
        }
    }

    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    @Synchronized
    fun stop() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
        runCatching { input?.release() }
        input = null
        thread = null
        config = null
    }
}

/** The viewer's decoder: rebuilds frames from parts and renders them to a TextureView's surface. */
object H264Viewer {
    private const val TAG = "H264Viewer"

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var thread: Thread? = null
    private val pending = HashMap<Long, Array<ByteArray?>>()
    private var lastDelivered = 0L
    @Volatile private var needKey = true
    private var lastKeyRequest = 0L
    private var streamId: String? = null
    private var codecW = 0
    private var codecH = 0

    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Encoded picture size once video is playing; null while there is none. */
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()
    private val _deviceSize = MutableStateFlow(0 to 0)
    /** The sharing device's real screen size, for mapping touches. */
    val deviceSize: StateFlow<Pair<Int, Int>> = _deviceSize.asStateFlow()

    @Synchronized
    fun setSurface(s: Surface?) {
        if (s == surface) return
        stopCodec()
        surface = s
        needKey = true
    }

    @Synchronized
    fun reset(id: String?) {
        streamId = id
        pending.clear()
        lastDelivered = 0
        needKey = true
        stopCodec()
        _videoSize.value = null
    }

    @Synchronized
    fun accept(json: JsonObject) {
        if (json.get("stream_id")?.asString != streamId) return
        val seq = json.get("seq")?.asLong ?: return
        if (seq <= lastDelivered) return
        val parts = json.get("parts")?.asInt ?: return
        val part = json.get("part")?.asInt ?: return
        if (parts !in 1..64 || part !in 0 until parts) return
        val bytes = runCatching { Base64.decode(json.get("data").asString, Base64.DEFAULT) }.getOrNull() ?: return
        val slots = pending.getOrPut(seq) { arrayOfNulls(parts) }
        if (slots.size != parts) return
        slots[part] = bytes
        if (slots.any { it == null }) {
            // Something newer than an incomplete frame means a part was lost.
            if (pending.size > 6) lost()
            return
        }
        pending.remove(seq)
        pending.keys.filter { it < seq }.forEach { pending.remove(it) }
        val gap = lastDelivered != 0L && seq != lastDelivered + 1
        lastDelivered = seq
        val isConfig = json.get("config")?.asBoolean == true
        val isKey = json.get("key")?.asBoolean == true
        if (gap && !isConfig && !isKey) {
            lost()
            return
        }
        val frame = slots.fold(ByteArray(0)) { acc, b -> acc + b!! }
        val w = json.get("w")?.asInt ?: 0
        val h = json.get("h")?.asInt ?: 0
        _deviceSize.value = (json.get("dw")?.asInt ?: 0) to (json.get("dh")?.asInt ?: 0)
        if (isConfig) {
            if (codec == null || w != codecW || h != codecH) startCodec(w, h)
            queue(frame, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            return
        }
        if (needKey && !isKey) return
        if (isKey) needKey = false
        queue(frame, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
    }

    private fun lost() {
        pending.clear()
        needKey = true
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest > 1000) {
            lastKeyRequest = now
            streamId?.let { Ecosystem.requestKeyframe(it) }
        }
    }

    private fun startCodec(w: Int, h: Int) {
        val s = surface ?: return
        stopCodec()
        runCatching {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h), s, null, 0)
            c.start()
            codec = c
            codecW = w
            codecH = h
            _videoSize.value = w to h
            thread = Thread({ render(c) }, "H264Viewer").apply { isDaemon = true; start() }
        }.onFailure {
            Log.w(TAG, "No H.264 decoder", it)
            codec = null
            Ecosystem.h264Unavailable()
        }
    }

    private fun queue(frame: ByteArray, flags: Int) {
        val c = codec ?: return
        runCatching {
            val index = c.dequeueInputBuffer(20_000)
            if (index < 0) {
                needKey = true
                return
            }
            val buffer = c.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(frame)
            c.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, flags)
        }.onFailure { needKey = true }
    }

    private fun render(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (codec === c) {
            val index = runCatching { c.dequeueOutputBuffer(info, 50_000) }.getOrDefault(-1)
            if (index >= 0) runCatching { c.releaseOutputBuffer(index, true) }
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
        thread = null
        _videoSize.value = null
    }
}
