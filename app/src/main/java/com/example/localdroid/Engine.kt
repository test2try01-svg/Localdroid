package com.example.localdroid

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * بوابة المحرك: تضمن أن تهيئة yt-dlp تعمل مرة واحدة فقط وبترتيب،
 * مهما كان عدد الجهات التي تطلبها (بدء التطبيق / Fetch / Service).
 * عند الفشل: تحذف الملفات المستخرجة التالفة وتحاول مرة ثانية.
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

    /** يحذف ملفات المحرك المستخرجة فقط — مجلد تنزيلاتنا يبقى آمنًا */
    private fun cleanEngineFiles(context: Context) {
        val keep = setOf("downloads")
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.deleteRecursively() }
        }
    }
}
