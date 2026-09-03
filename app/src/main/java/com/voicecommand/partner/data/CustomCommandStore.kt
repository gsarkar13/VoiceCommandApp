package com.voicecommand.partner.data

import android.content.Context
import com.voicecommand.partner.command.CommandType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomCommand(
    val id: String,
    val phrase: String,
    val type: CommandType,
    val arg: String?
)

object CustomCommandStore {
    private const val FILE = "partner_data"
    private const val KEY = "custom_commands_json"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<CustomCommand> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<CustomCommand>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    CustomCommand(
                        id = o.getString("id"),
                        phrase = o.getString("phrase"),
                        type = CommandType.valueOf(o.getString("type")),
                        arg = if (o.isNull("arg")) null else o.getString("arg")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, phrase: String, type: CommandType, arg: String?) {
        val cmd = CustomCommand(UUID.randomUUID().toString(), phrase.trim(), type, arg?.trim()?.ifEmpty { null })
        save(context, all(context) + cmd)
    }

    fun remove(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
    }

    private fun save(context: Context, list: List<CustomCommand>) {
        val array = JSONArray()
        list.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("phrase", c.phrase)
                    .put("type", c.type.name)
                    .put("arg", c.arg ?: JSONObject.NULL)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
