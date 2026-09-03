package com.voicecommand.partner.command

import com.voicecommand.partner.data.CustomCommand

enum class CommandType(val label: String) {
    LOCK("Lock screen"),
    TOGGLE_PLAY("Play / pause media"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    CALL("Call a contact"),
    OPEN_APP("Open an app"),
    FLASHLIGHT_ON("Flashlight on"),
    FLASHLIGHT_OFF("Flashlight off"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    VOLUME_MAX("Volume max"),
    VOLUME_MUTE("Mute media volume"),
    SCREENSHOT("Take a screenshot"),
    HOME("Go home"),
    RECENTS("Show recents"),
    NOTIFICATIONS("Open notifications"),
    QUICK_SETTINGS("Open quick settings"),
    TIME("Say the time"),
    DATE("Say the date"),
    BATTERY("Say battery level"),
    ALARM("Set an alarm"),
    TIMER("Set a timer"),
    FIND_PHONE("Find my phone"),
    SILENT_ON("Silent mode on"),
    SILENT_OFF("Silent mode off"),
    BRIGHTNESS("Set brightness"),
    HELP("Help"),
    SLEEP("Stop listening"),
    UNKNOWN("Unknown")
}

data class ParsedCommand(val type: CommandType, val arg: String? = null)

object CommandParser {

    private val alarmRegex =
        Regex("(?:alarm|wake me).*?(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?")
    private val timerRegex =
        Regex("(?:timer|remind me in|countdown).*?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?)?")
    private val callRegex = Regex("^(?:call|dial)\\s+(.+)$")
    private val openRegex = Regex("^(?:open|launch|start)\\s+(.+)$")
    private val numberRegex = Regex("(\\d{1,3})")

    fun parse(raw: String, customs: List<CustomCommand>): ParsedCommand {
        val text = fixMishears(normalize(raw))
        if (text.isEmpty()) return ParsedCommand(CommandType.UNKNOWN)
        val custom = customs.firstOrNull { normalize(it.phrase) == text }
        if (custom != null) return ParsedCommand(custom.type, custom.arg)
        return parseBuiltIn(text)
    }

    private val misheard = mapOf(
        "look" to "lock", "lok" to "lock", "luck" to "lock", "loch" to "lock",
        "scream" to "screen", "skrin" to "screen",
        "flash light" to "flashlight",
        "bettery" to "battery", "battry" to "battery",
        "bolume" to "volume", "valume" to "volume",
        "alaram" to "alarm", "timmer" to "timer"
    )

    private fun fixMishears(t: String): String {
        var s = " $t "
        misheard.forEach { (bad, good) -> s = s.replace(" $bad ", " $good ") }
        return s.trim()
    }

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9: ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseBuiltIn(t: String): ParsedCommand {
        if (t.contains("unlock")) return ParsedCommand(CommandType.UNKNOWN, "unlock")
        if (t.contains("lock")) return ParsedCommand(CommandType.LOCK)
        if (t.contains("find") && t.contains("phone")) return ParsedCommand(CommandType.FIND_PHONE)
        if (t.contains("where are you")) return ParsedCommand(CommandType.FIND_PHONE)
        if (t.contains("alarm") || t.contains("wake me")) {
            return ParsedCommand(CommandType.ALARM, parseAlarmTime(t))
        }
        if (t.contains("timer") || t.contains("remind me in") || t.contains("countdown")) {
            return ParsedCommand(CommandType.TIMER, parseTimerSeconds(t))
        }
        if (t.contains("pause")) return ParsedCommand(CommandType.TOGGLE_PLAY)
        if (t.contains("next")) return ParsedCommand(CommandType.NEXT)
        if (t.contains("previous") || t.contains("last song")) {
            return ParsedCommand(CommandType.PREVIOUS)
        }
        if (t.contains("play") || t.contains("resume") || t == "music" || t.contains("music on")) {
            return ParsedCommand(CommandType.TOGGLE_PLAY)
        }
        val callName = callRegex.find(t)?.groupValues?.get(1)?.trim()
        if (!callName.isNullOrBlank()) return ParsedCommand(CommandType.CALL, cleanName(callName))
        if (t.contains("flashlight") || t.contains("torch")) {
            return if (t.contains("off")) ParsedCommand(CommandType.FLASHLIGHT_OFF)
            else ParsedCommand(CommandType.FLASHLIGHT_ON)
        }
        if (t.contains("mute")) return ParsedCommand(CommandType.VOLUME_MUTE)
        if (t.contains("volume") || t.contains("louder") || t.contains("quieter")) {
            return when {
                t.contains("max") || t.contains("full") || t.contains("highest") ->
                    ParsedCommand(CommandType.VOLUME_MAX)
                t.contains("up") || t.contains("louder") || t.contains("raise") ||
                    t.contains("increase") -> ParsedCommand(CommandType.VOLUME_UP)
                t.contains("down") || t.contains("quieter") || t.contains("lower") ||
                    t.contains("decrease") -> ParsedCommand(CommandType.VOLUME_DOWN)
                else -> ParsedCommand(CommandType.UNKNOWN)
            }
        }
        if (t.contains("screenshot") || t.contains("screen shot")) {
            return ParsedCommand(CommandType.SCREENSHOT)
        }
        if (t == "home" || t.contains("go home") || t.contains("home screen")) {
            return ParsedCommand(CommandType.HOME)
        }
        if (t.contains("recent")) return ParsedCommand(CommandType.RECENTS)
        if (t.contains("quick settings") || t.contains("control center")) {
            return ParsedCommand(CommandType.QUICK_SETTINGS)
        }
        if (t.contains("notification")) return ParsedCommand(CommandType.NOTIFICATIONS)
        val openTarget = openRegex.find(t)?.groupValues?.get(1)?.trim()
        if (!openTarget.isNullOrBlank()) return ParsedCommand(CommandType.OPEN_APP, openTarget)
        if (t.contains("silent") || t.contains("do not disturb") || t == "ring") {
            return when {
                t.contains("off") || t.contains("normal") || t.contains("disable") ||
                    t.contains("ring") -> ParsedCommand(CommandType.SILENT_OFF)
                else -> ParsedCommand(CommandType.SILENT_ON)
            }
        }
        if (t.contains("brightness")) return ParsedCommand(CommandType.BRIGHTNESS, parsePercent(t))
        if (t.contains("go to sleep") || t.contains("stop listening") || t == "sleep") {
            return ParsedCommand(CommandType.SLEEP)
        }
        if (t.contains("help") || t.contains("what can you do") || t.contains("commands")) {
            return ParsedCommand(CommandType.HELP)
        }
        if (t.contains("battery") || (t.contains("charge") && !t.contains("charger"))) {
            return ParsedCommand(CommandType.BATTERY)
        }
        if (t.contains("time")) return ParsedCommand(CommandType.TIME)
        if (t.contains("date") || t.contains("day is it")) return ParsedCommand(CommandType.DATE)
        return ParsedCommand(CommandType.UNKNOWN)
    }

    private fun parseAlarmTime(t: String): String? {
        val m = alarmRegex.find(t) ?: return null
        val hourRaw = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3]
        if (minute !in 0..59) return null
        var hour = hourRaw
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour !in 0..23) return null
        return "$hour:$minute"
    }

    private fun parseTimerSeconds(t: String): String? {
        val m = timerRegex.find(t) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val seconds = when {
            unit.startsWith("hour") -> value * 3600
            unit.startsWith("min") -> value * 60
            unit.startsWith("sec") -> value
            else -> value * 60
        }
        return seconds.toInt().coerceAtLeast(1).toString()
    }

    private fun parsePercent(t: String): String? {
        if (t.contains("max") || t.contains("full")) return "100"
        if (t.contains("min") || t.contains("lowest")) return "5"
        return numberRegex.find(t)?.groupValues?.get(1)
    }

    private fun cleanName(name: String): String =
        name.trim()
            .removeSuffix("on phone").trim()
            .removeSuffix("please").trim()
            .removeSuffix("now").trim()
            .removePrefix("the").trim()
            .removePrefix("my").trim()
}
