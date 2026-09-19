package com.example.localdroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalDroidApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // تسخين المحرك في الخلفية — عبر البوابة المؤمّنة فقط (لا تعارض بعد الآن)
        appScope.launch {
            try {
                Engine.ensure(this@LocalDroidApp)
                Log.i("LocalDroid", "engine ready at startup")
            } catch (e: Exception) {
                Log.w("LocalDroid", "warm-up deferred: ${e.message}")
            }
            try {
                FFmpeg.getInstance().init(this@LocalDroidApp)
                Log.i("LocalDroid", "ffmpeg ready")
            } catch (e: Exception) {
                Log.e("LocalDroid", "ffmpeg init failed", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live download progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "localdroid_downloads"
    }
}
