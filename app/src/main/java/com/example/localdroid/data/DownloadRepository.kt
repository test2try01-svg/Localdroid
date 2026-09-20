package com.example.localdroid.data

import android.content.Context
import com.example.localdroid.Engine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadRepository(private val context: Context) {

    companion object {
        private const val PREFS = "engine"
        private const val KEY_CLIENT = "working_youtube_client"

        /** سلسلة عملاء التشغيل التي تتجاوز فحص البوت بدون كوكيز */
        val CLIENT_CHAIN = listOf("", "tv", "tv_embedded", "android", "mweb", "web_embedded", "ios")

        fun savedClient(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CLIENT, "") ?: ""

        private fun saveClient(context: Context, client: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CLIENT, client).apply()
        }

        private fun isBotError(msg: String): Boolean =
            msg.contains("Sign in", true) ||
            msg.contains("not a bot", true) ||
            msg.contains("confirm you", true)
    }

    /** يضيف عميل التشغيل المختار للأمر */
    private fun YoutubeDLRequest.withClient(client: String): YoutubeDLRequest {
        if (client.isNotBlank())
            addOption("--extractor-args", "youtube:player_client=$client")
        return this
    }

    /**
     * جلب المعلومات مع التدوير الذكي:
     * يجرّب العميل المحفوظ أولًا، ثم بقية السلسلة طالما الخطأ من نوع «فحص بوت»
     */
    suspend fun fetchInfo(rawUrl: String): VideoInfo = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        require(url.isNotBlank()) { "URL is empty" }

        Engine.ensure(context)

        val saved = savedClient(context)
        val chain = if (saved.isBlank()) CLIENT_CHAIN
                    else listOf(saved) + CLIENT_CHAIN.filter { it != saved }

        var lastError: Exception? = null
        for (client in chain) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "15")
                addOption("-j")
            }.withClient(client)

            try {
                val response = YoutubeDL.getInstance().execute(request)
                saveClient(context, client)
                return@withContext parseInfo(JSONObject(response.out))
            } catch (e: Exception) {
                lastError = e
                if (!isBotError(e.message ?: "")) break
            }
        }
        throw IllegalArgumentException(mapError(lastError?.message ?: ""))
    }

    /** أمر التحميل يستخدم العميل الناجح المحفوظ تلقائيًا */
    fun buildRequest(url: String, quality: QualityOption, outputDir: String): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "30")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("-o", "$outputDir/%(title).80s.%(ext)s")

            when (quality.formatCode) {
                "audio_only" -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "192K")
                }
                "best" -> addOption("-f", "bestvideo+bestaudio/best")
                else -> addOption(
                    "-f",
                    "bestvideo[height<=${quality.formatCode}]+bestaudio/best[height<=${quality.formatCode}]"
                )
            }
            addOption("--merge-output-format", "mp4")
        }.withClient(savedClient(context))
    }

    private fun parseInfo(json: JSONObject): VideoInfo {
        val qualities = listOf(
            QualityOption("1080p", "1080"),
            QualityOption("720p", "720"),
            QualityOption("480p", "480"),
            QualityOption("360p", "360"),
            QualityOption("Audio only (MP3)", "audio_only"),
            QualityOption("Best available", "best")
        )
        return VideoInfo(
            id = json.optString("id", ""),
            title = json.optString("title", "Untitled"),
            thumbnailUrl = json.optString("thumbnail").takeIf { it.isNotBlank() },
            durationSeconds = json.optInt("duration", 0),
            uploader = json.optString("uploader").takeIf { it.isNotBlank() }
                ?: json.optString("channel").takeIf { it.isNotBlank() },
            qualities = qualities
        )
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun mapError(msg: String): String = when {
        msg.contains("Unable to download", true) -> "Network error — check your connection."
        msg.contains("Private video", true) -> "This video is private."
        msg.contains("age-restricted", true) -> "Age-restricted video — try again or use cookies."
        msg.contains("Sign in", true) -> "All player clients blocked on this network — try another network (Wi-Fi/mobile data) or cookies."
        msg.contains("removed", true) -> "Video has been removed."
        msg.contains("instance not initialized", true) -> "Engine still starting… wait 20 seconds and retry."
        else -> "Unsupported URL or platform: ${msg.take(200)}"
    }
}
