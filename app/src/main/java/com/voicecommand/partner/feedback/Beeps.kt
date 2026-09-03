package com.voicecommand.partner.feedback

import android.media.AudioManager
import android.media.ToneGenerator

object Beeps {
    private var tone: ToneGenerator? = null

    private fun ensure() {
        if (tone == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    fun ack() {
        ensure()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun deny() {
        ensure()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
