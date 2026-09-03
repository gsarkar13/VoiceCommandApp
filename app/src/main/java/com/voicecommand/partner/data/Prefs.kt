package com.voicecommand.partner.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "partner_settings"
    private const val KEY_ENABLED = "service_enabled"
    private const val KEY_ACCESS_KEY = "porcupine_access_key"
    private const val KEY_VOSK_PATH = "vosk_model_path"
    private const val KEY_PORCUPINE_SENSITIVITY = "porcupine_sensitivity"
    private const val KEY_GATE_ENABLED = "gate_enabled"
    private const val KEY_GATE_PHRASE_ID = "gate_phrase_id"
    private const val KEY_GATE_PROFILE = "gate_profile_json"
    private const val KEY_GATE_MULTIPLIER = "gate_multiplier"
    private const val KEY_LAST_STATUS = "last_status"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun accessKey(context: Context): String = prefs(context).getString(KEY_ACCESS_KEY, "") ?: ""

    fun setAccessKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ACCESS_KEY, value.trim()).apply()
    }

    fun voskPath(context: Context): String = prefs(context).getString(KEY_VOSK_PATH, "") ?: ""

    fun setVoskPath(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VOSK_PATH, value.trim()).apply()
    }

    fun porcupineSensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_PORCUPINE_SENSITIVITY, 0.55f)

    fun setPorcupineSensitivity(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_PORCUPINE_SENSITIVITY, value.coerceIn(0.3f, 1.0f)).apply()
    }

    fun gateEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GATE_ENABLED, false)

    fun setGateEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GATE_ENABLED, value).apply()
    }

    fun gatePhraseId(context: Context): String? = prefs(context).getString(KEY_GATE_PHRASE_ID, null)

    fun setGatePhraseId(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_GATE_PHRASE_ID, value).apply()
    }

    fun gateProfileJson(context: Context): String? = prefs(context).getString(KEY_GATE_PROFILE, null)

    fun setGateProfileJson(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_GATE_PROFILE, value).apply()
    }

    fun gateMultiplier(context: Context): Float = prefs(context).getFloat(KEY_GATE_MULTIPLIER, 2.0f)

    fun setGateMultiplier(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_GATE_MULTIPLIER, value).apply()
    }

    fun lastStatus(context: Context): String = prefs(context).getString(KEY_LAST_STATUS, "") ?: ""

    fun setLastStatus(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_STATUS, value).apply()
    }
}
