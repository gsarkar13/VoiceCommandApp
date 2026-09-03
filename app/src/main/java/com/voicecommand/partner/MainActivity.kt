package com.voicecommand.partner

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.voicecommand.partner.admin.MyDeviceAdminReceiver
import com.voicecommand.partner.command.CommandType
import com.voicecommand.partner.data.CustomCommandStore
import com.voicecommand.partner.data.Prefs
import com.voicecommand.partner.data.WakePhrase
import com.voicecommand.partner.data.WakePhraseStore
import com.voicecommand.partner.engine.VoskModelHolder
import com.voicecommand.partner.feedback.Speaker
import com.voicecommand.partner.gate.Mfcc
import com.voicecommand.partner.gate.VoiceGate
import com.voicecommand.partner.service.VoiceCommandAccessibilityService
import com.voicecommand.partner.service.WakeWordService
import com.voicecommand.partner.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var pendingImportPhraseId: String? = null

    private val updateCheckIntervalMs = 30L * 60 * 1000

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pendingImportPhraseId
            pendingImportPhraseId = null
            if (uri != null && id != null) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val ok = WakePhraseStore.importKeyword(this, id, stream)
                        toast(if (ok) R.string.toast_keyword_imported else R.string.toast_import_failed)
                    }
                } catch (e: Exception) {
                    toast(R.string.toast_import_failed)
                }
                WakeWordService.reload(this)
                refreshPhrases()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
            refreshServiceStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.btnToggleService).setOnClickListener { toggleService() }
        findViewById<View>(R.id.btnReloadEngines).setOnClickListener {
            saveEngineFields()
            WakeWordService.reload(this)
            toast(R.string.toast_service_reloading)
            refreshVoskStatus()
        }
        findViewById<View>(R.id.btnAddPhrase).setOnClickListener { showAddPhraseDialog() }
        findViewById<View>(R.id.btnAddCommand).setOnClickListener { showAddCommandDialog() }
        findViewById<View>(R.id.btnEnrollVoice).setOnClickListener { startEnrollment() }
        findViewById<View>(R.id.btnClearGate).setOnClickListener {
            VoiceGate.clear(this)
            refreshGate()
        }
        findViewById<View>(R.id.btnPermMic).setOnClickListener { requestRuntimePermissions() }
        findViewById<View>(R.id.btnPermContacts).setOnClickListener { requestRuntimePermissions() }
        findViewById<View>(R.id.btnPermNotif).setOnClickListener { requestRuntimePermissions() }
        findViewById<View>(R.id.btnOpenAccessibility).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.btnOpenAdmin).setOnClickListener { openDeviceAdmin() }
        findViewById<View>(R.id.btnOpenDnd).setOnClickListener { openDndSettings() }
        findViewById<View>(R.id.btnOpenWriteSettings).setOnClickListener {
            open(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<View>(R.id.btnOpenBattery).setOnClickListener {
            open(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<View>(R.id.btnOpenAppDetails).setOnClickListener {
            open(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate(true) }

        findViewById<MaterialSwitch>(R.id.switchGate).setOnCheckedChangeListener { _, checked ->
            Prefs.setGateEnabled(this, checked)
        }

        val strictness = findViewById<Spinner>(R.id.spinnerGateStrictness)
        strictness.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.gate_strict),
                getString(R.string.gate_balanced),
                getString(R.string.gate_loose)
            )
        )
        strictness.setSelection(
            when (Prefs.gateMultiplier(this)) {
                in 0f..1.7f -> 0
                in 2.3f..10f -> 2
                else -> 1
            }
        )
        strictness.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val multiplier = when (position) {
                    0 -> 1.4f
                    2 -> 2.6f
                    else -> 2.0f
                }
                Prefs.setGateMultiplier(this@MainActivity, multiplier)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadEngineFields()
        if (Prefs.isEnabled(this) && !WakeWordService.isRunning && hasMicPermission()) {
            WakeWordService.start(this)
        }
        checkForUpdate(false)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        saveEngineFields()
    }

    private fun refreshAll() {
        refreshServiceStatus()
        refreshVoskStatus()
        refreshPhrases()
        refreshGate()
        refreshCommands()
        refreshPermissions()
        refreshUpdateStatus()
    }

    private fun refreshServiceStatus() {
        val running = WakeWordService.isRunning
        findViewById<TextView>(R.id.statusService).text = if (running) {
            val last = Prefs.lastStatus(this)
            getString(R.string.status_running) + if (last.isNotBlank()) ": $last" else ""
        } else {
            getString(R.string.status_stopped)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleService)
            .setText(if (running) R.string.btn_stop else R.string.btn_start)
    }

    private fun refreshVoskStatus() {
        val path = VoskModelHolder.detectPath(this)
        findViewById<TextView>(R.id.textVoskStatus).text =
            if (path != null) getString(R.string.vosk_detected_fmt, path)
            else getString(R.string.vosk_not_detected)
    }

    private fun loadEngineFields() {
        findViewById<EditText>(R.id.editAccessKey).setText(Prefs.accessKey(this))
        findViewById<EditText>(R.id.editVoskPath).setText(Prefs.voskPath(this))
    }

    private fun saveEngineFields() {
        Prefs.setAccessKey(this, findViewById<EditText>(R.id.editAccessKey).text.toString())
        Prefs.setVoskPath(this, findViewById<EditText>(R.id.editVoskPath).text.toString())
    }

    private fun toggleService() {
        if (WakeWordService.isRunning) {
            WakeWordService.stop(this)
            refreshServiceStatus()
        } else if (!hasMicPermission()) {
            requestRuntimePermissions()
            toast(R.string.toast_no_mic)
        } else {
            saveEngineFields()
            WakeWordService.start(this)
            refreshServiceStatus()
        }
    }

    private fun showAddPhraseDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_phrase, null)
        val label = view.findViewById<EditText>(R.id.editPhraseLabel)
        val variants = view.findViewById<EditText>(R.id.editPhraseVariants)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_phrase_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = label.text.toString().trim()
                if (text.isNotEmpty()) {
                    WakePhraseStore.add(
                        this,
                        text,
                        variants.text.toString().split(",").map { it.trim() }
                    )
                    WakeWordService.reload(this)
                    refreshPhrases()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshPhrases() {
        val container = findViewById<LinearLayout>(R.id.listPhrases)
        container.removeAllViews()
        WakePhraseStore.all(this).forEach { phrase ->
            val row = layoutInflater.inflate(R.layout.item_phrase, container, false)
            row.findViewById<TextView>(R.id.textPhraseLabel).text = phrase.label
            row.findViewById<TextView>(R.id.textPhraseSource).text =
                if (phrase.hasKeyword()) getString(R.string.source_porcupine)
                else getString(R.string.source_porcupine_pending)
            val enabledSwitch = row.findViewById<MaterialSwitch>(R.id.switchPhraseEnabled)
            enabledSwitch.isChecked = phrase.enabled
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                WakePhraseStore.setEnabled(this, phrase.id, checked)
                WakeWordService.reload(this)
            }
            row.findViewById<ImageButton>(R.id.btnDeletePhrase).setOnClickListener {
                WakePhraseStore.remove(this, phrase.id)
                WakeWordService.reload(this)
                refreshPhrases()
            }
            row.findViewById<ImageButton>(R.id.btnImportKeyword).setOnClickListener {
                pendingImportPhraseId = phrase.id
                importLauncher.launch(arrayOf("*/*"))
            }
            container.addView(row)
        }
    }

    private fun showAddCommandDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_command, null)
        val phraseEdit = view.findViewById<EditText>(R.id.editCommandPhrase)
        val argEdit = view.findViewById<EditText>(R.id.editCommandArg)
        val spinner = view.findViewById<Spinner>(R.id.spinnerCommandAction)
        val types = CommandType.values().filter { it != CommandType.UNKNOWN }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            types.map { it.label }
        )
        val argTypes = setOf(
            CommandType.CALL,
            CommandType.OPEN_APP,
            CommandType.TIMER,
            CommandType.BRIGHTNESS
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                argEdit.visibility =
                    if (types.getOrNull(position) in argTypes) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_command_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val phrase = phraseEdit.text.toString().trim()
                if (phrase.isNotEmpty()) {
                    val type = types.getOrNull(spinner.selectedItemPosition) ?: CommandType.UNKNOWN
                    CustomCommandStore.add(this, phrase, type, argEdit.text.toString())
                    refreshCommands()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCommands() {
        val container = findViewById<LinearLayout>(R.id.listCommands)
        container.removeAllViews()
        CustomCommandStore.all(this).forEach { command ->
            val row = layoutInflater.inflate(R.layout.item_command, container, false)
            row.findViewById<TextView>(R.id.textCommandPhrase).text = command.phrase
            row.findViewById<TextView>(R.id.textCommandAction).text =
                command.type.label + (command.arg?.let { " — $it" } ?: "")
            row.findViewById<ImageButton>(R.id.btnDeleteCommand).setOnClickListener {
                CustomCommandStore.remove(this, command.id)
                refreshCommands()
            }
            container.addView(row)
        }
    }

    private fun refreshGate() {
        findViewById<MaterialSwitch>(R.id.switchGate).isChecked = Prefs.gateEnabled(this)
        val label = VoiceGate.enrolledPhraseLabel(this)
        findViewById<TextView>(R.id.textGateStatus).text =
            label?.let { getString(R.string.gate_status_fmt, it) }
                ?: getString(R.string.gate_status_none)
    }

    private fun startEnrollment() {
        if (!hasMicPermission()) {
            requestRuntimePermissions()
            toast(R.string.toast_no_mic)
            return
        }
        val phrases = WakePhraseStore.all(this).filter { it.enabled }
        if (phrases.isEmpty()) {
            Toast.makeText(this, "Add and enable a wake phrase first.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = phrases.map { it.label }.toTypedArray()
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.gate_pick_phrase)
            .setSingleChoiceItems(labels, 0) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ -> runEnrollment(phrases[selected]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runEnrollment(phrase: WakePhrase) {
        val status = findViewById<TextView>(R.id.textGateStatus)
        lifecycleScope.launch {
            val samples = ArrayList<ShortArray>()
            for (i in 1..3) {
                status.text = getString(R.string.gate_prompt_fmt, phrase.label, i)
                Speaker.say(this@MainActivity, getString(R.string.gate_prompt_fmt, phrase.label, i))
                delay(500)
                val sample = withContext(Dispatchers.IO) { recordSample(2800) }
                if (sample == null || Mfcc.rms(sample) < 0.004) {
                    status.text = getString(R.string.gate_failed_quiet)
                    return@launch
                }
                samples.add(sample)
                delay(400)
            }
            val ok = withContext(Dispatchers.Default) {
                VoiceGate.enroll(this@MainActivity, phrase.id, samples)
            }
            status.text =
                if (ok) getString(R.string.gate_status_fmt, phrase.label)
                else getString(R.string.gate_failed_quiet)
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordSample(durationMs: Long): ShortArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val chunks = ArrayList<ShortArray>()
        val frame = ShortArray(1600)
        val start = SystemClock.elapsedRealtime()
        try {
            record.startRecording()
            while (SystemClock.elapsedRealtime() - start < durationMs) {
                val n = record.read(frame, 0, frame.size)
                if (n > 0) chunks.add(frame.copyOf(n)) else if (n < 0) break
            }
        } catch (e: Exception) {
            return null
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
            }
            record.release()
        }
        val total = chunks.sumOf { it.size }
        if (total < 16000) return null
        val out = ShortArray(total)
        var offset = 0
        chunks.forEach {
            System.arraycopy(it, 0, out, offset, it.size)
            offset += it.size
        }
        return out
    }

    private fun refreshUpdateStatus() {
        findViewById<TextView>(R.id.textUpdateStatus).text =
            getString(R.string.update_current_fmt, UpdateManager.currentVersion(this))
    }

    private fun checkForUpdate(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.lastUpdateCheck(this) < updateCheckIntervalMs) return
        Prefs.setLastUpdateCheck(this, now)
        val status = findViewById<TextView>(R.id.textUpdateStatus)
        status.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            val current = UpdateManager.currentVersion(this@MainActivity)
            val info = withContext(Dispatchers.IO) { UpdateManager.fetchLatest() }
            if (info == null) {
                status.text = getString(R.string.update_check_failed)
                if (force) {
                    Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!UpdateManager.isNewer(info.tag, current)) {
                status.text = getString(R.string.update_up_to_date_fmt, current)
                if (force) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_up_to_date_fmt, current),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            status.text = getString(R.string.update_available_fmt, versionDigits(info.tag), current)
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateManager.ReleaseInfo) {
        val current = UpdateManager.currentVersion(this)
        val message = buildString {
            append(getString(R.string.update_available_fmt, versionDigits(info.tag), current))
            append("\n")
            append(getString(R.string.update_size_mb_fmt, maxOf(1, (info.apkSize / 1048576L).toInt())))
            info.notes?.let { notes ->
                append("\n\n")
                append(notes.take(800))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(info.title ?: info.tag)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstall(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateManager.ReleaseInfo) {
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading_fmt, 0))
            .setView(progressBar)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .show()
        lifecycleScope.launch {
            val dest = File(cacheDir, "updates/partner-update.apk")
            val ok = withContext(Dispatchers.IO) {
                UpdateManager.downloadApk(info, dest) { percent ->
                    runOnUiThread {
                        progressBar.progress = percent
                        dialog.setTitle(getString(R.string.update_downloading_fmt, percent))
                    }
                }
            }
            dialog.dismiss()
            if (!ok) {
                dest.delete()
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!UpdateManager.install(this@MainActivity, dest)) {
                Toast.makeText(this@MainActivity, R.string.update_install_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun versionDigits(tag: String): String = tag.trim().trimStart('v', 'V')

    private fun refreshPermissions() {
        permStatus(R.id.textPermMic, hasMicPermission())
        permStatus(R.id.textPermContacts, hasContactsPermission() && hasCallPermission())
        if (Build.VERSION.SDK_INT >= 33) {
            permStatus(
                R.id.textPermNotif,
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        } else {
            findViewById<View>(R.id.rowPermNotif).visibility = View.GONE
        }
        permStatus(R.id.textPermAccessibility, isAccessibilityEnabled())
        permStatus(R.id.textPermAdmin, isAdminActive())
        permStatus(
            R.id.textPermDnd,
            getSystemService(android.app.NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
        )
        permStatus(R.id.textPermWrite, Settings.System.canWrite(this))
        permStatus(
            R.id.textPermBattery,
            getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
        )
    }

    private fun permStatus(textId: Int, granted: Boolean) {
        val text = findViewById<TextView>(textId)
        text.text = getString(if (granted) R.string.granted else R.string.not_granted)
        text.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.status_ok else R.color.status_bad)
        )
    }

    private fun requestRuntimePermissions() {
        val permissions = ArrayList<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissions.add(Manifest.permission.READ_CONTACTS)
        permissions.add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openDeviceAdmin() {
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.perm_admin))
        open(intent)
    }

    private fun openDndSettings() {
        val direct = Intent(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            android.net.Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "No app handled that setting.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        if (VoiceCommandAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun isAdminActive(): Boolean =
        getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(this, MyDeviceAdminReceiver::class.java))

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
