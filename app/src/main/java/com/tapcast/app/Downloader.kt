package com.tapcast.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Offline episodes: simple sequential downloader with progress callbacks.
 * Downloads go to a .part file and are moved into place only when complete,
 * so a dropped connection never leaves a half-episode that "plays" for
 * three minutes and dies.
 */
object Downloader {
    private const val TAG = "TapCast"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    /** episode key → progress 0..100 while a download is running. */
    val active = ConcurrentHashMap<String, Int>()

    fun isDownloading(ep: Episode): Boolean = active.containsKey(ep.key)

    fun download(ep: Episode, dest: File, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        if (isDownloading(ep)) return
        active[ep.key] = 0
        Thread {
            val part = File(dest.parentFile, dest.name + ".part")
            val ok = runCatching {
                val req = Request.Builder().url(ep.audioUrl).header("User-Agent", "TapCast/1.0").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching false
                    val body = resp.body ?: return@runCatching false
                    val total = body.contentLength().takeIf { it > 0 } ?: ep.sizeBytes.takeIf { it > 0 } ?: -1L
                    var read = 0L
                    var lastPct = -1
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt().coerceIn(0, 99)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        active[ep.key] = pct
                                        main.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                    part.length() > 0L && part.renameTo(dest)
                }
            }.getOrElse {
                Log.w(TAG, "download failed: ${it.message}")
                false
            }
            part.delete()
            active.remove(ep.key)
            main.post { onDone(ok) }
        }.start()
    }
}
