package com.example.localdroid.data

import android.content.Context
import com.example.localdroid.CookiesStore
import com.example.localdroid.Engine
import com.example.localdroid.GeckoViewExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadRepository(private val context: Context) {

    companion object {
        private const val PREFS = "engine"
        private const val KEY_CLIENT = "working_youtube_client"

        val CLIENT_CHAIN = listOf(
            "", "tv_embedded", "tv", "mweb",
            "web_embedded", "ios", "web_safari", "android_vr", "android"
        )

        fun savedClient(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CLIENT, "") ?: ""

        private fun saveClient(context: Context, client: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CLIENT, client).apply()

        private fun isRotatableError(msg: String): Boolean =
            msg.contains("Sign in", true) ||
            msg.contains("not a bot", true) ||
            msg.contains("confirm you", true) ||
            msg.contains("needs to be reloaded", true) ||
            msg.contains("reload the page", true) ||
            msg.contains("please reload", true) ||
            msg.contains("Try again", true) ||
            msg.contains("bot check", true) ||
            msg.contains("no longer supported", true) ||
            msg.contains("this application or device", true)
    }

    /** يضيف الكوكيز (إن وجدت) + عميل التشغيل + تخطي HLS */
    private fun YoutubeDLRequest.decorate(client: String): YoutubeDLRequest {
        CookiesStore.path(context)?.let { addOption("--cookies", it) }
        val args = if (client.isBlank()) "youtube:skip=hls"
                   else "youtube:player_client=$client;skip=hls"
        addOption("--extractor-args", args)
        return this
    }

    /** محاولة yt-dlp مع تدوير العملاء — تُرجع null عند فشل الحجب */
    private fun tryRotation(url: String): VideoInfo? {
        val saved = savedClient(context)
        val chain = if (saved.isBlank()) CLIENT_CHAIN
                    else listOf(saved) + CLIENT_CHAIN.filter { it != saved }
        for (client in chain) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "15")
                addOption("-j")
            }.decorate(client)
            try {
                val response = YoutubeDL.getInstance().execute(request)
                saveClient(context, client)
                return parseInfo(JSONObject(response.out))
            } catch (e: Exception) {
                if (!isRotatableError(e.message ?: "")) return null
            }
        }
        return null
    }

    /**
     * نظام ثلاثي المستويات:
     * 1) yt-dlp + تدوير العملاء (+ الكوكيز المحصودة إن وجدت)
     * 2) GeckoView: تجاوز فحص البوت + حصد الكوكيز
     * 3) إعادة yt-dlp بالكوكيز الجديدة لجودات كاملة
     */
    suspend fun fetchInfo(rawUrl: String): VideoInfo = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        require(url.isNotBlank()) { "URL is empty" }
        Engine.ensure(context)

        tryRotation(url)?.let { return@withContext it }

        try {
            val geckoInfo = GeckoViewExtractor.extractVideoInfo(context, url)
            tryRotation(url)?.let { return@withContext it }
            return@withContext geckoInfo
        } catch (e: Exception) {
            throw IllegalArgumentException(mapError(e.message ?: "All engines failed"))
        }
    }

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
        }.decorate(savedClient(context))
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
        msg.contains("age-restricted", true) -> "Age-restricted video — cookies may help."
        msg.contains("Sign in", true) || msg.contains("reloaded", true) ->
            "YouTube blocked all engines on this network — try another network."
        msg.contains("removed", true) -> "Video has been removed."
        msg.contains("instance not initialized", true) -> "Engine still starting… wait 20 seconds and retry."
        else -> "Unsupported URL or platform: ${msg.take(200)}"
    }
}
