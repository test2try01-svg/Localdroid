package com.example.localdroid.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.localdroid.LocalDroidApp
import com.example.localdroid.data.DownloadRepository
import com.example.localdroid.data.QualityOption
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { DownloadRepository(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val qualityCode = intent.getStringExtra(EXTRA_QUALITY) ?: "720"
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Media"

        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        startForegroundCompat(notificationId, buildNotification(0f, "Starting…", label))

        scope.launch {
            try {
                // ✅ الإصلاح: تهيئة مضمونة للمحرك قبل أي تحميل
                YoutubeDL.getInstance().init(applicationContext)

                val request = repo.buildRequest(
                    url,
                    QualityOption(label, qualityCode),
                    filesDir.absolutePath
                )

                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    val eta = if (etaInSeconds > 0) " — ETA ${fmtEta(etaInSeconds.toInt())}" else ""
                    updateNotification(notificationId, progress / 100f, "${progress.toInt()}%$eta", label)
                }

                moveToMediaStore(filesDir)
                updateNotification(notificationId, 1f, "Complete ✔", label)
                stopWithDelay(notificationId, 2_000)
            } catch (e: Exception) {
                Log.e("DownloadService", "Download failed", e)
                updateNotification(notificationId, 0f, "Failed: ${e.message?.take(60)}", label)
                stopWithDelay(notificationId, 4_000)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** نقل الملف من مجلد التطبيق إلى مجلد عام عبر MediaStore (متوافق مع Scoped Storage) */
    private fun moveToMediaStore(privateDir: File) {
        val recent = privateDir.listFiles()?.filter {
            it.isFile && it.lastModified() > System.currentTimeMillis() - 120_000
        } ?: return

        for (file in recent) {
            val isAudio = file.extension.equals("mp3", true)
            val mime = if (isAudio) "audio/mpeg" else "video/mp4"
            val collection = if (isAudio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isAudio) "Music/LocalDroid/" else "Movies/LocalDroid/"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(collection, values) ?: continue
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            file.delete()
        }
    }

    private fun buildNotification(progress: Float, text: String, title: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LocalDroidApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun updateNotification(id: Int, progress: Float, text: String, title: String) {
        getSystemService(NotificationManager::class.java)
            .notify(id, buildNotification(progress, text, title))
    }

    private fun stopWithDelay(id: Int, delayMs: Long) {
        scope.launch {
            delay(delayMs)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun fmtEta(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LABEL = "label"

        fun start(context: Context, url: String, qualityCode: String, label: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY, qualityCode)
                putExtra(EXTRA_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
