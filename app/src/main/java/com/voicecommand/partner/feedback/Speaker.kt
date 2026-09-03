package com.voicecommand.partner.feedback

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

object Speaker {
    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun ensure(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            val app = context.applicationContext
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.US
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
            }
        }
    }

    fun say(context: Context, text: String) {
        ensure(context)
        val engine = tts
        if (engine == null || !ready) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "partner_${System.nanoTime()}")
    }

    fun shutdown() {
        synchronized(this) {
            tts?.shutdown()
            tts = null
            ready = false
        }
    }
}
