package com.voicecommand.partner.engine

import ai.picovoice.porcupine.Porcupine
import android.content.Context

class PorcupineWakeEngine private constructor(
    private val porcupine: Porcupine,
    override val phraseIds: List<String>
) : WakeDetector {

    override fun process(frame: ShortArray): Int = try {
        val index = porcupine.process(frame)
        if (index >= 0) index else -1
    } catch (e: Exception) {
        -1
    }

    override fun close() {
        try {
            porcupine.delete()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun create(
            context: Context,
            accessKey: String,
            sensitivity: Float,
            keywords: List<Pair<String, String>>
        ): PorcupineWakeEngine? {
            if (accessKey.isBlank() || keywords.isEmpty()) return null
            return try {
                val porcupine = Porcupine.Builder()
                    .setAccessKey(accessKey.trim())
                    .setKeywordPaths(keywords.map { it.second }.toTypedArray())
                    .setSensitivities(FloatArray(keywords.size) { sensitivity })
                    .build(context)
                PorcupineWakeEngine(porcupine, keywords.map { it.first })
            } catch (e: Exception) {
                null
            }
        }
    }
}
