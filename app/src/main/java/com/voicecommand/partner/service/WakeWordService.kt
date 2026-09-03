package com.voicecommand.partner.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voicecommand.partner.MainActivity
import com.voicecommand.partner.R
import com.voicecommand.partner.audio.MicLoop
import com.voicecommand.partner.command.CommandExecutor
import com.voicecommand.partner.command.CommandParser
import com.voicecommand.partner.data.CustomCommandStore
import com.voicecommand.partner.data.Prefs
import com.voicecommand.partner.data.WakePhraseStore
import com.voicecommand.partner.engine.PorcupineWakeEngine
import com.voicecommand.partner.engine.VoskModelHolder
import com.voicecommand.partner.engine.VoskWakeEngine
import com.voicecommand.partner.feedback.Beeps
import com.voicecommand.partner.feedback.Speaker
import com.voicecommand.partner.gate.VoiceGate
import com.voicecommand.partner.speech.CommandEngine
import com.voicecommand.partner.util.PendingIntentFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WakeWordService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var micLoop: MicLoop? = null
    private var porcupineEngine: PorcupineWakeEngine? = null
    private var voskEngine: VoskWakeEngine? = null

    @Volatile
    private var processing = false

    @Volatile
    private var pipelineStarted = false

    @Volatile
    private var rebuilding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startInForeground()
        Speaker.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                rebuildPipeline()
                return START_STICKY
            }
        }
        if (!hasMic()) {
            Toast.makeText(this, R.string.toast_no_mic, Toast.LENGTH_LONG).show()
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!pipelineStarted && !rebuilding) rebuildPipeline()
        return START_STICKY
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                buildNotification(getString(R.string.notif_listening_hint)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_listening_hint)))
        }
    }

    private fun rebuildPipeline() {
        if (rebuilding) return
        rebuilding = true
        scope.launch {
            try {
                pipelineStarted = true
                stopPipelineInner()
                val app = applicationContext
                val enabledPhrases = WakePhraseStore.all(app).filter { it.enabled }
                if (enabledPhrases.isEmpty()) {
                    setStatus("No wake phrases are enabled")
                    return@launch
                }
                val accessKey = Prefs.accessKey(app)
                val sensitivity = Prefs.porcupineSensitivity(app)
                val withKeyword = enabledPhrases.filter { it.hasKeyword() }
                porcupineEngine = PorcupineWakeEngine.create(
                    app,
                    accessKey,
                    sensitivity,
                    withKeyword.mapNotNull { p -> p.keywordPath?.let { p.id to it } }
                )
                val voskCandidates =
                    if (porcupineEngine == null) enabledPhrases
                    else enabledPhrases.filter { !it.hasKeyword() }
                val model =
                    if (voskCandidates.isNotEmpty()) VoskModelHolder.get(app) else null
                voskEngine = if (model != null) {
                    VoskWakeEngine(model, voskCandidates.map { it.id to it.matchTexts })
                } else {
                    null
                }
                if (porcupineEngine == null && voskEngine == null) {
                    val reason = if (accessKey.isBlank() && VoskModelHolder.detectPath(app) == null) {
                        "Set a Picovoice AccessKey or add a Vosk model in the app"
                    } else {
                        "Engine init failed. Check the AccessKey in the app."
                    }
                    setStatus(reason)
                    return@launch
                }
                val loop = MicLoop { frame -> onFrame(frame) }
                micLoop = loop
                if (!loop.start()) {
                    setStatus("Microphone unavailable")
                    stopSelf()
                    return@launch
                }
                val parts = ArrayList<String>()
                porcupineEngine?.let { parts.add("Porcupine: ${it.phraseIds.size}") }
                voskEngine?.let { parts.add("Vosk: ${it.phraseIds.size}") }
                setStatus(parts.joinToString("  +  "))
            } finally {
                rebuilding = false
            }
        }
    }

    private fun stopPipelineInner() {
        micLoop?.stop()
        micLoop = null
        porcupineEngine?.close()
        porcupineEngine = null
        voskEngine?.close()
        voskEngine = null
    }

    private fun onFrame(frame: ShortArray) {
        if (processing || rebuilding) return
        val porcupineIndex = porcupineEngine?.process(frame) ?: -1
        val detector: com.voicecommand.partner.engine.WakeDetector?
        val detectedIndex: Int
        if (porcupineIndex >= 0) {
            detector = porcupineEngine
            detectedIndex = porcupineIndex
        } else {
            val voskIndex = voskEngine?.process(frame) ?: -1
            if (voskIndex < 0) return
            detector = voskEngine
            detectedIndex = voskIndex
        }
        if (detector == null) return
        val phraseId = detector.phraseIds.getOrNull(detectedIndex) ?: return
        processing = true
        micLoop?.stopCapture()
        val audio = micLoop?.ringSnapshot() ?: ShortArray(0)
        scope.launch { handleWake(phraseId, audio) }
    }

    private suspend fun handleWake(phraseId: String, audio: ShortArray) {
        micLoop?.awaitStopped()
        try {
            val app = applicationContext
            if (Prefs.gateEnabled(app) &&
                VoiceGate.isEnrolled(app) &&
                VoiceGate.enrolledPhraseId(app) == phraseId &&
                !VoiceGate.verify(app, audio)
            ) {
                Beeps.deny()
                return
            }
            Beeps.ack()
            val text = withTimeoutOrNull(15000) { CommandEngine.recognize(app) }
            if (text.isNullOrBlank()) {
                Beeps.deny()
                return
            }
            val parsed = CommandParser.parse(text, CustomCommandStore.all(app))
            CommandExecutor(app).execute(parsed, text)
        } catch (e: Exception) {
            Log.e(TAG, "wake handling failed", e)
        } finally {
            try {
                val loop = micLoop
                if (loop != null && !loop.start()) {
                    rebuildPipeline()
                }
            } finally {
                processing = false
            }
        }
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun setStatus(text: String) {
        Prefs.setLastStatus(this, text)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntentFlags.forActivity(
            this,
            Intent(this, MainActivity::class.java)
        )
        val stop = PendingIntentFlags.forService(
            this,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopPipelineInner()
        scope.cancel()
        Beeps.release()
        Speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "voice_control"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.voicecommand.partner.action.STOP"
        const val ACTION_RELOAD = "com.voicecommand.partner.action.RELOAD"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java)
            )
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_STOP)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                context.stopService(intent)
            }
        }

        fun reload(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, WakeWordService::class.java).setAction(ACTION_RELOAD)
                )
            } catch (e: Exception) {
            }
        }
    }
}
