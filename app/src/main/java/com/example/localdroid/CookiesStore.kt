package com.example.localdroid

import android.content.Context
import java.io.File

/**
 * تخزين الكوكيز محليًا بصيغة Netscape التي يفهمها yt-dlp.
 * تُملأ تلقائيًا من جلسة GeckoView بعد تجاوز فحص البوت.
 */
object CookiesStore {

    private fun file(context: Context) = File(context.filesDir, "cookies.txt")

    fun exists(context: Context): Boolean = file(context).exists()

    fun path(context: Context): String? =
        if (exists(context)) file(context).absolutePath else null

    fun clear(context: Context) {
        file(context).delete()
    }

    /** تحويل أزواج الكوكيز من جلسة المتصفح إلى صيغة Netscape */
    fun fromCookiePairs(context: Context, domain: String, cookieHeader: String): Boolean {
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val idx = pair.indexOf('=')
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isNotBlank()) {
                    sb.append(".").append(domain).append("\tTRUE\t/\tFALSE\t0\t")
                        .append(name).append('\t').append(value).append('\n')
                }
            }
        if (sb.toString().lines().size <= 1) return false
        file(context).writeText(sb.toString())
        return true
    }
}
