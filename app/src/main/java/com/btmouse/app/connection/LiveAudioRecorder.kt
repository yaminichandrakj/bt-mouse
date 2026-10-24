package com.btmouse.app.connection

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Captures 20 ms mono PCM blocks while a user-visible phone-microphone session is active. */
class LiveAudioRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var recorder: AudioRecord? = null

    @Synchronized fun start(onFrame: (ByteArray) -> Unit, onStopped: () -> Unit = {}): Boolean {
        if (recorder != null) return true
        return try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) return false
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, BLOCK_BYTES * 4)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                return false
            }
            audioRecord.startRecording()
            recorder = audioRecord
            job = scope.launch {
                val buffer = ByteArray(BLOCK_BYTES)
                try {
                    while (true) {
                        val active = synchronized(this@LiveAudioRecorder) { recorder } ?: break
                        var filled = 0
                        while (filled < buffer.size) {
                            val count = active.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                            if (count <= 0) break
                            filled += count
                        }
                        if (filled == buffer.size) onFrame(buffer.copyOf()) else break
                    }
                } catch (_: Exception) {
                } finally {
                    val endedUnexpectedly = synchronized(this@LiveAudioRecorder) {
                        (recorder === audioRecord).also { if (it) recorder = null }
                    }
                    try { audioRecord.stop() } catch (_: Exception) {}
                    try { audioRecord.release() } catch (_: Exception) {}
                    if (endedUnexpectedly) onStopped()
                }
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @Synchronized fun stop() {
        val activeJob = job
        job = null
        val active = recorder
        recorder = null
        try { active?.stop() } catch (_: Exception) {}
        activeJob?.cancel()
        // The capture worker owns final release once it exits its blocking read.
        if (activeJob == null) active?.let { try { it.release() } catch (_: Exception) {} }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BLOCK_BYTES = 640
    }
}
