package com.voicecommand.partner.engine

import android.util.Log
import com.voicecommand.partner.audio.MicLoop
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskWakeEngine(
    model: Model,
    phrases: List<Pair<String, List<String>>>
) : WakeDetector {

    private data class Matcher(val id: String, val text: String, val tokens: List<String>)

    private val matchers: List<Matcher> = phrases.flatMap { (id, texts) ->
        texts.map { text -> Matcher(id, text, text.split(" ").filter { it.isNotBlank() }) }
    }
    private val buffer = ByteArray(4096)
    private var recognizer: Recognizer? = null

    override val phraseIds: List<String> = phrases.map { it.first }.distinct()

    init {
        recognizer = try {
            Recognizer(model, MicLoop.SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            Log.e("VoskWakeEngine", "recognizer init failed", e)
            null
        }
    }

    override fun process(frame: ShortArray): Int {
        val rec = recognizer ?: return -1
        if (frame.size * 2 > buffer.size) return -1
        var offset = 0
        for (s in frame) {
            buffer[offset++] = (s.toInt() and 0xFF).toByte()
            buffer[offset++] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        return try {
            val json = if (rec.acceptWaveForm(buffer, offset)) rec.finalResult else rec.partialResult
            val text = transcript(json) ?: return -1
            val words = text.split(" ").filter { it.isNotBlank() }
            if (words.size > 40) {
                rec.reset()
                return -1
            }
            val index = match(words)
            if (index >= 0) rec.reset()
            index
        } catch (e: Exception) {
            -1
        }
    }

    override fun close() {
        try {
            recognizer?.close()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun transcript(json: String): String? {
        val obj = JSONObject(json)
        val partial = obj.optString("partial")
        if (partial.isNotBlank()) return partial
        val text = obj.optString("text")
        return text.ifBlank { null }
    }

    private fun match(words: List<String>): Int {
        for (m in matchers) {
            val contained = m.tokens.all { token ->
                words.any { word -> word.contains(token) }
            }
            if (contained) return phraseIds.indexOf(m.id)
            val tail = words.takeLast(m.tokens.size).joinToString(" ")
            val maxDistance = if (m.tokens.size >= 2) 2 else 1
            if (levenshtein(tail, m.text) <= maxDistance) return phraseIds.indexOf(m.id)
        }
        return -1
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
