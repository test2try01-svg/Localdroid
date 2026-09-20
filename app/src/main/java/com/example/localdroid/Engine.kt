package com.example.localdroid

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * بوابة المحرك: تهيئة مؤمّنة + تحديث ذاتي لـ yt-dlp.
 * يوتيوب يغيّر دفاعاته باستمرار، لذا نحدّث المحرك لأحدث إصدار رسمي
 * مرة واحدة بعد كل تثبيت — وهذا ما يفتح الفيديوهات الحالية.
 */
object Engine {

    private val mutex = Mutex()

    @Volatile
    var ready: Boolean = false
        private set

    suspend fun ensure(context: Context) {
        if (ready) return
        mutex.withLock {
            if (ready) return
            var lastError: Throwable? = null
            for (attempt in 1..2) {
                try {
                    YoutubeDL.getInstance().init(context)
                    ready = true
                    Log.i("Engine", "yt-dlp initialized (attempt $attempt)")
                    updateOnce(context)
                    return
                } catch (e: Throwable) {
                    lastError = e
                    Log.e("Engine", "init attempt $attempt failed", e)
                    if (attempt == 1) cleanEngineFiles(context)
                }
            }
            val chain = generateSequence(lastError) { it.cause }
                .joinToString("  <-  ") { "${it.javaClass.simpleName}: ${it.message}" }
            throw IllegalStateException("Engine init failed: $chain")
        }
    }

    /**
     * يحدّث yt-dlp لأحدث إصدار رسمي (مرة واحدة لكل تثبيت).
     * عبر Reflection حتى لا ينكسر البناء لو اختلف اسم الدالة بين الإصدارات.
     */
    private fun updateOnce(context: Context) {
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (prefs.getBoolean("ytdlp_updated_v1", false)) return
        runCatching {
            val inst = YoutubeDL.getInstance()
            val method = inst.javaClass.methods.firstOrNull { it.name == "updateYoutubeDL" }
            if (method != null) {
                Log.i("Engine", "updating yt-dlp to latest official release…")
                method.invoke(inst, context)
                Log.i("Engine", "yt-dlp updated successfully")
            } else {
                Log.w("Engine", "updateYoutubeDL not found — skipping")
            }
            prefs.edit().putBoolean("ytdlp_updated_v1", true).apply()
        }.onFailure {
            Log.w("Engine", "yt-dlp update skipped: ${it.message}")
        }
    }

    /** يحذف ملفات المحرك التالفة فقط — مجلد تنزيلاتنا يبقى آمنًا */
    private fun cleanEngineFiles(context: Context) {
        val keep = setOf("downloads")
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.deleteRecursively() }
        }
    }
}
