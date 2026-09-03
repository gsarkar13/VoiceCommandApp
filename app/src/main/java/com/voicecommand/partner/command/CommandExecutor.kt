package com.voicecommand.partner.command

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.voicecommand.partner.admin.MyDeviceAdminReceiver
import com.voicecommand.partner.data.Prefs
import com.voicecommand.partner.feedback.Speaker
import com.voicecommand.partner.service.VoiceCommandAccessibilityService
import com.voicecommand.partner.service.WakeWordService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandExecutor(private val context: Context) {

    private val audioManager: AudioManager
        get() = context.getSystemService(AudioManager::class.java)

    fun execute(cmd: ParsedCommand, rawText: String) {
        when (cmd.type) {
            CommandType.LOCK -> lockScreen()
            CommandType.TOGGLE_PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            CommandType.NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            CommandType.PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            CommandType.CALL -> callContact(cmd.arg)
            CommandType.OPEN_APP -> openApp(cmd.arg)
            CommandType.FLASHLIGHT_ON -> torch(true)
            CommandType.FLASHLIGHT_OFF -> torch(false)
            CommandType.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            CommandType.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            CommandType.VOLUME_MAX -> volumeMax()
            CommandType.VOLUME_MUTE -> volumeMute()
            CommandType.SCREENSHOT -> screenshot()
            CommandType.HOME -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            CommandType.RECENTS -> globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            CommandType.NOTIFICATIONS -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            CommandType.QUICK_SETTINGS -> globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            CommandType.TIME -> sayTime()
            CommandType.DATE -> sayDate()
            CommandType.BATTERY -> sayBattery()
            CommandType.ALARM -> setAlarm(cmd.arg)
            CommandType.TIMER -> setTimer(cmd.arg)
            CommandType.FIND_PHONE -> findPhone()
            CommandType.SILENT_ON -> silentMode(true)
            CommandType.SILENT_OFF -> silentMode(false)
            CommandType.BRIGHTNESS -> brightness(cmd.arg)
            CommandType.HELP -> help()
            CommandType.SLEEP -> sleep()
            CommandType.UNKNOWN -> unknown(rawText, cmd.arg)
        }
    }

    private fun lockScreen() {
        val acc = VoiceCommandAccessibilityService.instance
        if (acc != null && Build.VERSION.SDK_INT >= 28) {
            acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            return
        }
        Speaker.say(
            context,
            "To lock by voice, enable the accessibility service or device admin in the Partner app."
        )
    }

    private fun globalAction(action: Int) {
        val acc = VoiceCommandAccessibilityService.instance
        if (acc == null) {
            Speaker.say(context, "Enable the accessibility service for this action.")
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        acc.performGlobalAction(action)
    }

    private fun screenshot() {
        if (Build.VERSION.SDK_INT < 28) {
            Speaker.say(context, "Screenshots by voice need Android 9 or newer.")
            return
        }
        globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    @Suppress("DEPRECATION")
    private fun mediaKey(keyCode: Int) {
        val am = audioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun callContact(name: String?) {
        if (name.isNullOrBlank()) {
            Speaker.say(context, "Who should I call?")
            return
        }
        val contact = ContactLookup.findBest(context, name)
        if (contact == null) {
            Speaker.say(context, "I couldn't find $name in your contacts.")
            return
        }
        val canCall = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:${contact.number}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Speaker.say(context, "Calling ${contact.name}.")
        } catch (e: Exception) {
            Speaker.say(context, "I couldn't place the call.")
        }
    }

    private fun openApp(target: String?) {
        if (target.isNullOrBlank()) {
            Speaker.say(context, "Which app should I open?")
            return
        }
        if (AppLauncher.open(context, target)) {
            Speaker.say(context, "Opening $target.")
        } else {
            Speaker.say(context, "I couldn't find an app called $target.")
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: throw IllegalStateException("no camera")
            cameraManager.setTorchMode(cameraId, on)
            Speaker.say(context, if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Speaker.say(context, "Flashlight is not available.")
        }
    }

    private fun volume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun volumeMax() {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun volumeMute() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
    }

    private fun sayTime() {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        Speaker.say(context, "It is $time.")
    }

    private fun sayDate() {
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        Speaker.say(context, "Today is $date.")
    }

    private fun sayBattery() {
        val battery = context.registerReceiver(
            null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) {
            Speaker.say(context, "I couldn't read the battery.")
            return
        }
        val percent = level * 100 / scale
        val suffix = if (charging) ", and charging." else "."
        Speaker.say(context, "Battery is at $percent percent$suffix")
    }

    private fun setAlarm(arg: String?) {
        if (arg.isNullOrBlank()) {
            Speaker.say(context, "Tell me a time, for example: set alarm for 7 30 am.")
            return
        }
        val parts = arg.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour == null || minute !in 0..59) {
            Speaker.say(context, "Tell me a time, for example: set alarm for 7 30 am.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Partner voice")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryOrReport(intent, "Alarm set for ${arg.replace(":", " ")}.")
    }

    private fun setTimer(arg: String?) {
        val seconds = arg?.toIntOrNull()
        if (seconds == null) {
            Speaker.say(context, "Tell me a duration, for example: timer for 5 minutes.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Partner voice")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val spoken = if (seconds >= 60) {
            "${seconds / 60} minute timer set."
        } else {
            "$seconds second timer set."
        }
        tryOrReport(intent, spoken)
    }

    private fun tryOrReport(intent: Intent, successText: String) {
        try {
            context.startActivity(intent)
            Speaker.say(context, successText)
        } catch (e: Exception) {
            Speaker.say(context, "No clock app handled that.")
        }
    }

    private fun findPhone() {
        val am = audioManager
        try {
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (e: Exception) {
        }
        try {
            am.setStreamVolume(
                AudioManager.STREAM_RING,
                am.getStreamMaxVolume(AudioManager.STREAM_RING), 0
            )
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        } catch (e: Exception) {
        }
        Speaker.say(context, "Here I am!")
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun silentMode(on: Boolean) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            Speaker.say(context, "Allow Do Not Disturb access in the Partner app to control silent mode.")
            return
        }
        try {
            audioManager.ringerMode =
                if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            Speaker.say(context, if (on) "Silent mode on." else "Silent mode off.")
        } catch (e: Exception) {
            Speaker.say(context, "I couldn't change the ringer mode.")
        }
    }

    private fun brightness(arg: String?) {
        if (!Settings.System.canWrite(context)) {
            Speaker.say(context, "Allow write system settings in the Partner app to change brightness.")
            return
        }
        val percent = (arg?.toIntOrNull() ?: 50).coerceIn(1, 100)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.BRIGHTNESS_MODE_OFF
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (percent * 255 / 100).coerceIn(1, 255)
            )
            Speaker.say(context, "Brightness $percent percent.")
        } catch (e: Exception) {
            Speaker.say(context, "I couldn't change brightness.")
        }
    }

    private fun help() {
        Speaker.say(
            context,
            "You can say: lock my phone, play or pause, next song, call someone, open an app, " +
                "flashlight on or off, take a screenshot, silent mode on, set an alarm, " +
                "timer for five minutes, volume up, or stop listening."
        )
    }

    private fun sleep() {
        Speaker.say(context, "Going to sleep. Open the app to wake me.")
        Prefs.setEnabled(context, false)
        val intent = Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_STOP)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
        }
    }

    private fun unknown(rawText: String, arg: String?) {
        if (arg == "unlock" || rawText.contains("unlock")) {
            Speaker.say(context, "Android security does not allow unlocking by voice.")
        } else {
            Speaker.say(context, "Sorry, I didn't catch that. Say help for examples.")
        }
    }
}
