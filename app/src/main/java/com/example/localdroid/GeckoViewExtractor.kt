package com.example.localdroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.localdroid.data.QualityOption
import com.example.localdroid.data.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * محرك الاحتياط عبر GeckoView (Firefox الحقيقي):
 * 1) يفتح صفحة يوتيوب بمحرك متصفح كامل → يتجاوز فحص البوت
 * 2) يحصد الكوكيز من الجلسة ويحفظها لـ yt-dlp
 * 3) يستخرج العنوان/المدة/الصورة من مشغل يوتيوب نفسه
 */
object GeckoViewExtractor {

    private const val TAG = "Gecko"
    private const val TIMEOUT_MS = 45_000L

    @Volatile
    private var runtime: GeckoRuntime? = null

    /** تهيئة GeckoRuntime (يجب على خيط الواجهة الرئيسي) */
    fun ensureRuntime(context: Context): Boolean {
        if (runtime != null) return true
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                runtime = GeckoRuntime.create(context.applicationContext)
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "GeckoRuntime create failed", e)
            }
            latch.countDown()
        }
        return try {
            latch.await(30, TimeUnit.SECONDS) && ok
        } catch (e: InterruptedException) {
            false
        }
    }

    suspend fun extractVideoInfo(context: Context, url: String): VideoInfo =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val rt = runtime
                if (rt == null) {
                    cont.resumeWithException(Exception("GeckoView runtime not ready"))
                    return@suspendCancellableCoroutine
                }

                var resumed = false
                fun finish(block: () -> Unit) {
                    if (!resumed && !cont.isCancelled) {
                        resumed = true
                        block()
                    }
                }

                val session = GeckoSession()
                try {
                    session.open(rt)

                    session.progressDelegate = object : GeckoSession.ProgressDelegate {
                        override fun onPageStop(session: GeckoSession, success: Boolean) {
                            if (!success) return
                            Handler(Looper.getMainLooper()).postDelayed({
                                harvest(session, context, url, ::finish, cont)
                            }, 4000)
                        }
                    }

                    session.loadUri(url)

                    Handler(Looper.getMainLooper()).postDelayed({
                        finish {
                            runCatching { session.close() }
                            cont.resumeWithException(
                                Exception("GeckoView timeout (${TIMEOUT_MS / 1000}s)")
                            )
                        }
                    }, TIMEOUT_MS)
                } catch (e: Exception) {
                    finish {
                        runCatching { session.close() }
                        cont.resumeWithException(e)
                    }
                }

                cont.invokeOnCancellation { runCatching { session.close() } }
            }
        }

    /** حصد الكوكيز + معلومات الفيديو من الصفحة المحمّلة */
    private fun harvest(
        session: GeckoSession,
        context: Context,
        url: String,
        finish: (() -> Unit) -> Unit,
        cont: kotlinx.coroutines.CancellableContinuation<VideoInfo>
    ) {
        val js = """
        (function() {
            var out = { cookie: document.cookie || '', title: '', duration: 0, thumb: '' };
            try {
                var r = window.ytInitialPlayerResponse || null;
                if (r && r.videoDetails) {
                    out.title = r.videoDetails.title || '';
                    out.duration = parseInt(r.videoDetails.lengthSeconds || '0', 10) || 0;
                    var th = r.videoDetails.thumbnail && r.videoDetails.thumbnail.thumbnails;
                    if (th && th.length) out.thumb = th[th.length - 1].url || '';
                }
                if (!out.title) out.title = document.title || 'Video';
            } catch(e) {}
            return JSON.stringify(out);
        })()
        """.trimIndent()

        try {
            // ✅ الإصلاح: تصريح صريح عن نوع result كـ String?
            session.evaluateJavascript(js).accept { result: String? ->
                finish {
                    runCatching { session.close() }
                    try {
                        val json = JSONObject(result ?: "{}")
                        val cookie = json.optString("cookie", "")
                        if (cookie.isNotBlank()) {
                            CookiesStore.fromCookiePairs(context, "youtube.com", cookie)
                            Log.i(TAG, "cookies harvested from GeckoView session")
                        }
                        cont.resume(
                            VideoInfo(
                                id = url.substringAfter("v=", "").substringBefore('&').take(11),
                                title = json.optString("title", "Video"),
                                thumbnailUrl = json.optString("thumb", "").takeIf { it.isNotBlank() },
                                durationSeconds = json.optInt("duration", 0),
                                uploader = null,
                                qualities = DEFAULT_QUALITIES
                            )
                        )
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            }
        } catch (e: Exception) {
            finish {
                runCatching { session.close() }
                cont.resumeWithException(e)
            }
        }
    }

    private val DEFAULT_QUALITIES = listOf(
        QualityOption("1080p", "1080"),
        QualityOption("720p", "720"),
        QualityOption("480p", "480"),
        QualityOption("360p", "360"),
        QualityOption("Audio only (MP3)", "audio_only"),
        QualityOption("Best available", "best")
    )
}
