package com.voicecommand.partner.command

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object AppLauncher {

    fun open(context: Context, target: String): Boolean {
        val label = target.lowercase().trim()
        if (label.isEmpty()) return false
        if (label == "settings" || label == "phone settings" || label == "system settings") {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        }
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var best: Triple<String, Intent, Int>? = null
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(launcherIntent, 0)
        for (info in infos) {
            val appLabel = (info.loadLabel(pm)?.toString() ?: continue).lowercase()
            val launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: continue
            val score = when {
                appLabel == label -> 100
                appLabel.startsWith(label) -> 80
                appLabel.contains(label) -> 60
                else -> continue
            }
            if (best == null || score > best.third) {
                best = Triple(appLabel, launch, score)
            }
        }
        val chosen = best ?: return false
        chosen.second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chosen.second)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
