package com.voicecommand.partner.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voicecommand.partner.engine.VoskModelHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object CommandEngine {

    suspend fun recognize(context: Context): String? {
        val systemResult = try {
            recognizeViaSystem(context)
        } catch (e: Exception) {
            null
        }
        if (!systemResult.isNullOrBlank()) return systemResult.trim()
        return withContext(Dispatchers.IO) {
            try {
                val model = VoskModelHolder.get(context) ?: return@withContext null
                VoskOneShot(model).listen()
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun recognizeViaSystem(context: Context): String? =
        withContext(Dispatchers.Main.immediate) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
            val hasOnDevice = Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            suspendCancellableCoroutine { cont ->
                val sr = try {
                    if (hasOnDevice) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val handler = Handler(Looper.getMainLooper())
                var finished = false

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        sr.destroy()
                    } catch (e: Exception) {
                    }
                    if (cont.isActive) cont.resume(result)
                }

                val timeoutRunnable = Runnable { finish(null) }

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(list?.firstOrNull { it.isNotBlank() })
                    }

                    override fun onError(error: Int) {
                        finish(null)
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                cont.invokeOnCancellation {
                    handler.post {
                        try {
                            sr.destroy()
                        } catch (e: Exception) {
                        }
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                handler.postDelayed(timeoutRunnable, 8000)
                sr.startListening(intent)
            }
        }
}
