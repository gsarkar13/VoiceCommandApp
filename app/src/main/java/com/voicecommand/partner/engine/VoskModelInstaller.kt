package com.voicecommand.partner.engine

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoskModelInstaller {

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MIN_ZIP_BYTES = 1_000_000L

    fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Partner-VoiceCommand")
                connectTimeout = 20000
                readTimeout = 120000
            }
            if (conn.responseCode != 200) return false
            val total = conn.contentLengthLong
            val zipFile = File(context.cacheDir, "vosk-model.zip")
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
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
            if (!zipFile.isFile || zipFile.length() < MIN_ZIP_BYTES) {
                zipFile.delete()
                return false
            }
            val installed = extractZip(context, zipFile)
            zipFile.delete()
            installed
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun installFromUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val zipFile = File(context.cacheDir, "vosk-model.zip")
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
                if (!zipFile.isFile || zipFile.length() < MIN_ZIP_BYTES) {
                    zipFile.delete()
                    return false
                }
                val installed = extractZip(context, zipFile)
                zipFile.delete()
                installed
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun extractZip(context: Context, zipFile: File): Boolean {
        val staging = File(context.filesDir, "vosk-model-staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) return false
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var prefix: String? = null
                val buffer = ByteArray(16384)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name.isBlank()) continue
                    var name = entry.name
                    if (prefix == null && name.contains('/')) {
                        prefix = name.substringBefore('/') + "/"
                    }
                    if (prefix != null && name.startsWith(prefix)) {
                        name = name.removePrefix(prefix)
                    }
                    if (name.isBlank()) continue
                    val dest = safeDest(staging, name) ?: continue
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { output ->
                            while (true) {
                                val n = zis.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            val valid = File(staging, "final.mdl").isFile || File(staging, "am").isDirectory
            if (!valid) {
                staging.deleteRecursively()
                return false
            }
            VoskModelHolder.release()
            val target = File(context.filesDir, "vosk-model")
            target.deleteRecursively()
            return staging.renameTo(target)
        } catch (e: Exception) {
            staging.deleteRecursively()
            return false
        }
    }

    private fun safeDest(root: File, name: String): File? {
        val dest = File(root, name)
        val rootPath = root.canonicalPath + File.separator
        return if (dest.canonicalPath.startsWith(rootPath)) dest else null
    }
}
