package com.voicecommand.partner.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.voicecommand.partner.audio.MicLoop
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskOneShot(private val model: Model) {

    @SuppressLint("MissingPermission")
    fun listen(maxMs: Long = 7000, silenceMs: Long = 1300): String? {
        val minBuf = AudioRecord.getMinBufferSize(
            MicLoop.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicLoop.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val rec = try {
            Recognizer(model, MicLoop.SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            record.release()
            return null
        }
        try {
            record.startRecording()
            val started = SystemClock.elapsedRealtime()
            var lastChange = started
            var lastPartial = ""
            val collected = StringBuilder()
            val frame = ShortArray(2048)
            val bytes = ByteArray(frame.size * 2)
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - started >= maxMs) break
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                var offset = 0
                for (i in 0 until n) {
                    bytes[offset++] = (frame[i].toInt() and 0xFF).toByte()
                    bytes[offset++] = (frame[i].toInt() shr 8 and 0xFF).toByte()
                }
                if (rec.acceptWaveForm(bytes, offset)) {
                    val text = JSONObject(rec.finalResult).optString("text").trim()
                    if (text.isNotEmpty()) {
                        if (collected.isNotEmpty()) collected.append(' ')
                        collected.append(text)
                        lastPartial = ""
                        lastChange = now
                    }
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial").trim()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (partial.isNotEmpty()) lastChange = now
                    }
                }
                val combined = listOf(collected.toString().trim(), lastPartial)
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                if (combined.isNotEmpty() && now - lastChange >= silenceMs) return combined
            }
            val combined = listOf(collected.toString().trim(), lastPartial)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return combined.ifEmpty { null }
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
            }
            record.release()
            rec.close()
        }
    }
}
