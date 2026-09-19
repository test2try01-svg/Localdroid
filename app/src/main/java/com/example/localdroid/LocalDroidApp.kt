package com.example.localdroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalDroidApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initEngines()
    }

    /** يستخرج ثنائيات yt-dlp و ffmpeg من داخل الـ APK مرة واحدة فقط */
    private fun initEngines() {
        appScope.launch {
            try {
                YoutubeDL.getInstance().init(this@LocalDroidApp)
                Log.i("LocalDroid", "yt-dlp engine ready")
            } catch (e: Exception) {
                Log.e("LocalDroid", "yt-dlp init failed", e)
            }
            try {
                FFmpeg.getInstance().init(this@LocalDroidApp)
                Log.i("LocalDroid", "ffmpeg engine ready")
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
