package com.example.localdroid.data

import android.content.Context
import com.example.localdroid.Engine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadRepository(private val context: Context) {

    /** جلب معلومات الفيديو (عنوان/صورة/مدة) */
    suspend fun fetchInfo(rawUrl: String): VideoInfo = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        require(url.isNotBlank()) { "URL is empty" }

        // تهيئة مؤمّنة ومُسلسلة عبر البوابة الوحيدة
        Engine.ensure(context)

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "15")
            addOption("-j")
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            parseInfo(JSONObject(response.out))
        } catch (e: Exception) {
            throw IllegalArgumentException(mapError(e.message ?: ""))
        }
    }

    /** بناء أمر التحميل حسب الجودة المختارة */
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
        }
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
        msg.contains("age-restricted", true) -> "Age-restricted video — not downloadable."
        msg.contains("Sign in", true) -> "Sign-in required for this video."
        msg.contains("removed", true) -> "Video has been removed."
        msg.contains("instance not initialized", true) -> "Engine still starting… wait 20 seconds and retry."
        else -> "Unsupported URL or platform: ${msg.take(200)}"
    }
}
