package com.voicecommand.partner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

data class WakePhrase(
    val id: String,
    val label: String,
    val variants: List<String>,
    val keywordPath: String?,
    val enabled: Boolean
) {
    val matchTexts: List<String>
        get() = (listOf(label) + variants)
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
            .distinct()

    fun hasKeyword(): Boolean = keywordPath != null && File(keywordPath).isFile

    companion object {
        fun normalizeText(s: String): String = s.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}

object WakePhraseStore {
    private const val FILE = "partner_data"
    private const val KEY = "wake_phrases_json"
    const val DEFAULT_LABEL = "Hey Partner"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<WakePhrase> {
        val raw = prefs(context).getString(KEY, null) ?: run {
            val seeded = listOf(WakePhrase(UUID.randomUUID().toString(), DEFAULT_LABEL, emptyList(), null, true))
            save(context, seeded)
            return seeded
        }
        return try {
            parse(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun byId(context: Context, id: String): WakePhrase? = all(context).firstOrNull { it.id == id }

    fun add(context: Context, label: String, variants: List<String>): WakePhrase {
        val phrase = WakePhrase(
            id = UUID.randomUUID().toString(),
            label = label.trim(),
            variants = variants.map { it.trim() }.filter { it.isNotBlank() },
            keywordPath = null,
            enabled = true
        )
        save(context, all(context) + phrase)
        return phrase
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, all(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(context: Context, id: String) {
        all(context).firstOrNull { it.id == id }?.keywordPath?.let { File(it).delete() }
        save(context, all(context).filter { it.id != id })
    }

    fun importKeyword(context: Context, id: String, stream: InputStream): Boolean {
        val phrase = byId(context, id) ?: return false
        val dir = File(context.filesDir, "keywords")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "$id.ppn")
        return try {
            target.outputStream().use { out -> stream.use { it.copyTo(out) } }
            if (target.length() == 0L) {
                target.delete()
                false
            } else {
                save(context, all(context).map { if (it.id == id) it.copy(keywordPath = target.absolutePath) else it })
                true
            }
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    private fun save(context: Context, list: List<WakePhrase>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("label", p.label)
                    .put("variants", JSONArray(p.variants))
                    .put("keywordPath", p.keywordPath ?: JSONObject.NULL)
                    .put("enabled", p.enabled)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun parse(array: JSONArray): List<WakePhrase> {
        val out = ArrayList<WakePhrase>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val variants = ArrayList<String>()
            val v = o.optJSONArray("variants")
            if (v != null) for (j in 0 until v.length()) variants.add(v.getString(j))
            out.add(
                WakePhrase(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    variants = variants,
                    keywordPath = if (o.isNull("keywordPath")) null else o.getString("keywordPath"),
                    enabled = o.optBoolean("enabled", true)
                )
            )
        }
        return out
    }
}
