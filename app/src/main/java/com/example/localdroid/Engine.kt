package com.example.localdroid

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * بوابة المحرك (مستوحاة من Seal):
 * - تهيئة مؤمّنة ضد التعارض (Mutex)
 * - تحديث ذاتي لـ yt-dlp يستدعي كل تواقيع updateYoutubeDL الممكنة
 * - تنظيف الملفات التالفة وإعادة المحاولة
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

    /** تحديث yt-dlp لأحدث إصدار — مرة واحدة عند النجاح فقط */
    private fun updateOnce(context: Context) {
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (prefs.getBoolean("ytdlp_updated_v3", false)) return
        var done = false
        runCatching {
            val inst = YoutubeDL.getInstance()
            val methods = inst.javaClass.methods.filter { it.name == "updateYoutubeDL" }
            for (m in methods) {
                if (done) break
                runCatching {
                    when (m.parameterCount) {
                        1 -> { m.invoke(inst, context); done = true }
                        2 -> {
                            val second = m.parameterTypes[1]
                            if (second.isEnum) {
                                m.invoke(inst, context, second.enumConstants.first())
                                done = true
                            }
                        }
                    }
                }.onFailure { Log.w("Engine", "signature ${m.parameterCount} failed: ${it.message}") }
            }
        }.onFailure { Log.w("Engine", "update crash: ${it.message}") }

        if (done) {
            Log.i("Engine", "yt-dlp updated to latest release ✔")
            prefs.edit().putBoolean("ytdlp_updated_v3", true).apply()
        } else {
            Log.w("Engine", "update will retry on next launch")
        }
    }

    private fun cleanEngineFiles(context: Context) {
        val keep = setOf("downloads")
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.deleteRecursively() }
        }
    }
}
