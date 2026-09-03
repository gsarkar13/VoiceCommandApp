package com.voicecommand.partner.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class MicLoop(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    private val ringSeconds: Int = 3,
    private val onFrame: (ShortArray) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var capturing = false

    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val ring = ArrayDeque<ShortArray>()
    private val ringFrames: Int = (sampleRate * ringSeconds / frameSize) + 1

    val isCapturing: Boolean
        get() = capturing

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (capturing) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameSize * 2 * 4)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        synchronized(ring) { ring.clear() }
        capturing = true
        record.startRecording()
        thread = Thread {
            val frame = ShortArray(frameSize)
            while (capturing) {
                val n = record.read(frame, 0, frame.size)
                if (n > 0) {
                    val copy = frame.copyOf(n)
                    synchronized(ring) {
                        ring.addLast(copy)
                        while (ring.size > ringFrames) ring.removeFirst()
                    }
                    onFrame(copy)
                } else if (n < 0) {
                    break
                }
            }
        }.apply {
            name = "MicLoop"
            start()
        }
        return true
    }

    fun stopCapture() {
        capturing = false
    }

    fun awaitStopped() {
        thread?.join(2000)
        thread = null
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (e: IllegalStateException) {
            }
            record.release()
        }
        audioRecord = null
    }

    fun stop() {
        stopCapture()
        awaitStopped()
    }

    fun ringSnapshot(): ShortArray {
        synchronized(ring) {
            val out = ShortArray(ring.sumOf { it.size })
            var offset = 0
            ring.forEach {
                System.arraycopy(it, 0, out, offset, it.size)
                offset += it.size
            }
            return out
        }
    }
}
