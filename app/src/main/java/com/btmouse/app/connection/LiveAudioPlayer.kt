package com.btmouse.app.connection

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Small bounded jitter buffer for the PC microphone's 20 ms PCM frames. */
class LiveAudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var frames = Channel<ByteArray>(12, BufferOverflow.DROP_OLDEST)

    @Synchronized fun start(): Boolean {
        if (audioTrack != null) return true
        return try {
            val minimum = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) return false
            val newFrames = Channel<ByteArray>(12, BufferOverflow.DROP_OLDEST)
            frames = newFrames
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum * 3, SAMPLE_RATE / 2))
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return false
            }
            track.play()
            audioTrack = track
            playbackJob = scope.launch {
                for (pcm in newFrames) {
                    val active = synchronized(this@LiveAudioPlayer) { audioTrack } ?: break
                    var offset = 0
                    while (offset < pcm.size) {
                        val written = active.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                }
            }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun accept(pcm: ByteArray) {
        if (pcm.isNotEmpty()) frames.trySend(pcm)
    }

    @Synchronized fun stop() {
        frames.close()
        playbackJob?.cancel()
        playbackJob = null
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try { track.pause() } catch (_: Exception) {}
            try { track.flush() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
