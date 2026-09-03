package com.voicecommand.partner.engine

import android.content.Context
import com.voicecommand.partner.data.Prefs
import org.vosk.Model
import java.io.File

object VoskModelHolder {
    @Volatile
    private var model: Model? = null

    @Volatile
    private var loadedPath: String? = null

    fun detectPath(context: Context): String? {
        val candidates = ArrayList<String>()
        Prefs.voskPath(context).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        candidates.add(File(context.filesDir, "vosk-model").absolutePath)
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, "vosk-model").absolutePath) }
        candidates.add("/sdcard/Download/vosk-model-small-en-us-0.15")
        candidates.add("/sdcard/Download/vosk-model")
        candidates.add("/sdcard/vosk-model")
        return candidates.firstOrNull { looksLikeModel(File(it)) }
    }

    private fun looksLikeModel(dir: File): Boolean =
        dir.isDirectory && (File(dir, "final.mdl").isFile || File(dir, "am").isDirectory)

    fun get(context: Context): Model? {
        val path = detectPath(context) ?: run {
            release()
            return null
        }
        if (model != null && loadedPath == path) return model
        synchronized(this) {
            if (model != null && loadedPath == path) return model
            model?.close()
            model = null
            loadedPath = null
            return try {
                val loaded = Model(path)
                model = loaded
                loadedPath = path
                loaded
            } catch (e: Exception) {
                null
            }
        }
    }

    fun release() {
        synchronized(this) {
            try {
                model?.close()
            } catch (e: Exception) {
            }
            model = null
            loadedPath = null
        }
    }
}
