package com.voicecommand.partner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    data class ReleaseInfo(
        val tag: String,
        val title: String?,
        val notes: String?,
        val apkUrl: String,
        val apkSize: Long
    )

    private const val LATEST_API =
        "https://api.github.com/repos/gsarkar13/VoiceCommandApp/releases/latest"

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun fetchLatest(): ReleaseInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Partner-VoiceCommand")
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode != 200) return null
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = root.optString("tag_name")
            if (tag.isBlank()) return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return ReleaseInfo(
                        tag = tag,
                        title = root.optString("name").ifBlank { null },
                        notes = root.optString("body").ifBlank { null },
                        apkUrl = asset.optString("browser_download_url"),
                        apkSize = asset.optLong("size")
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        val remote = remoteTag.trim().trimStart('v', 'V').split('.')
            .map { it.toIntOrNull() ?: 0 }
        val current = currentVersion.trim().trimStart('v', 'V').split('.')
            .map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    fun downloadApk(info: ReleaseInfo, dest: File, onProgress: (percent: Int) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Partner-VoiceCommand")
                connectTimeout = 20000
                readTimeout = 60000
            }
            if (conn.responseCode != 200) return false
            val total = if (info.apkSize > 0) info.apkSize else conn.contentLengthLong
            dest.parentFile?.mkdirs()
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            read > 0 && dest.isFile
        } catch (e: Exception) {
            dest.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
