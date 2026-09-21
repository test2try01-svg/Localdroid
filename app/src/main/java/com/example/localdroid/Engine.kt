package com.example.localdroid

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Stage { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class EngineState(
    val ytdlpInit: Stage = Stage.PENDING,
    val ytdlpUpdate: Stage = Stage.PENDING,
    val ffmpeg: Stage = Stage.PENDING,
    val gecko: Stage = Stage.PENDING,
    val message: String = ""
)

/**
 * بوابة المحرك مع حالة مرئية للواجهة:
 * كل مكوّن (yt-dlp / تحديثه / FFmpeg / GeckoView) له مرحلة واضحة تظهر للمستخدم.
 */
object Engine {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    var ready: Boolean = false
        private set

    private fun update(block: EngineState.() -> EngineState) {
        _state.value = _state.value.block()
    }

    suspend fun ensure(context: Context) {
        if (ready) return
        mutex.withLock {
            if (ready) return
            val app = context.applicationContext

            // 1) استخراج محرك yt-dlp من الـ APK
            update { copy(ytdlpInit = Stage.RUNNING, message = "Extracting yt-dlp engine…") }
            var initOk = false
            for (attempt in 1..2) {
                try {
                    YoutubeDL.getInstance().init(app)
                    initOk = true
                    break
                } catch (e: Throwable) {
                    Log.e("Engine", "init attempt $attempt failed", e)
                    if (attempt == 1) cleanEngineFiles(app)
                }
            }
            if (!initOk) {
                update { copy(ytdlpInit = Stage.FAILED, message = "yt-dlp init failed") }
                throw IllegalStateException("yt-dlp init failed")
            }
            update { copy(ytdlpInit = Stage.DONE, message = "yt-dlp engine ready") }
            ready = true

            // 2) تحديث yt-dlp لأحدث إصدار رسمي (تنزيل شبكي مرئي)
            val prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
            if (prefs.getBoolean("ytdlp_updated_v3", false)) {
                update { copy(ytdlpUpdate = Stage.DONE, message = "yt-dlp is up to date") }
            } else {
                update { copy(ytdlpUpdate = Stage.RUNNING, message = "Downloading latest yt-dlp…") }
                val updated = tryUpdateYtdlp(app)
                update {
                    if (updated) copy(ytdlpUpdate = Stage.DONE, message = "yt-dlp updated to latest ✔")
                    else copy(ytdlpUpdate = Stage.SKIPPED, message = "Update unavailable — using bundled yt-dlp")
                }
                if (updated) prefs.edit().putBoolean("ytdlp_updated_v3", true).apply()
            }

            // 3) FFmpeg
            update { copy(ffmpeg = Stage.RUNNING, message = "Initializing FFmpeg…") }
            try {
                FFmpeg.getInstance().init(app)
                update { copy(ffmpeg = Stage.DONE, message = "FFmpeg ready") }
            } catch (e: Exception) {
                update { copy(ffmpeg = Stage.FAILED, message = "FFmpeg failed: ${e.message}") }
            }

            // 4) GeckoView (محرك Firefox الاحتياطي)
            update { copy(gecko = Stage.RUNNING, message = "Starting GeckoView (Firefox)…") }
            val geckoOk = GeckoViewExtractor.ensureRuntime(app)
            update {
                if (geckoOk) copy(gecko = Stage.DONE, message = "All components ready ✔")
                else copy(gecko = Stage.FAILED, message = "GeckoView unavailable — yt-dlp still works")
            }
        }
    }

    private fun tryUpdateYtdlp(context: Context): Boolean {
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
                }
            }
        }
        return done
    }

    private fun cleanEngineFiles(context: Context) {
        val keep = setOf("downloads")
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.deleteRecursively() }
        }
    }
}
