package com.tapcast.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File

/**
 * Podcast artwork: disk cache (survives restarts, works offline) fronted by a
 * small in-memory LRU so the gallery scrolls without re-decoding.
 */
class ArtStore(private val dir: File) {
    /**
     * LruCache's limit is an arbitrary unit unless sizeOf is overridden.  The
     * old cache used "64" as an entry count, so 64 full-resolution podcast
     * covers could occupy gigabytes on the X3 Pro.  Keep a real byte-bounded
     * cache instead; views may retain their own small thumbnails safely.
     */
    private val mem = object : LruCache<String, Bitmap>(MEM_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    /** Memory-cache only — safe on the UI thread (no disk I/O, no decode). */
    fun memCached(url: String): Bitmap? = mem.get(url)

    /** Cached bitmap or null; hits DISK (decode) — call off the UI thread.
     *  Never blocks on the network. */
    fun cached(url: String): Bitmap? {
        mem.get(url)?.let { return it }
        val f = fileFor(url)
        if (!f.isFile) return null
        return decodeScaledFile(f)?.also { mem.put(url, it) }
    }

    // One network fetch per URL even when several tiles race for the same
    // cover (e.g. a gallery rebuild re-queues while the first pass is still in
    // flight): the loser blocks on the per-URL lock, then finds it cached.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Fetch + cache. Call off the UI thread. */
    fun loadOrFetch(url: String): Bitmap? {
        cached(url)?.let { return it }
        val lock = inFlight.computeIfAbsent(url) { Any() }
        synchronized(lock) {
            try {
                cached(url)?.let { return it }
                Log.i("TapCast", "art fetch $url")
                val bytes = FeedClient.getBytes(url) ?: return null
                val bmp = decodeScaled(bytes) ?: return null
                runCatching { fileFor(url).writeBytes(bytes) }
                    .onFailure { Log.w("TapCast", "art cache failed: ${it.message}") }
                mem.put(url, bmp)
                return bmp
            } finally {
                inFlight.remove(url)
            }
        }
    }

    /** Podcast art ships at up to 3000px; X3 Pro tiles need only a small cover. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, decodeOptions(bounds.outWidth, bounds.outHeight)
        ) ?: return null
        return scaleToThumbnail(decoded)
    }

    /** Disk entries contain the original response, so disk hits must be sampled
     * too.  Decoding them at source resolution caused the native-heap blowout. */
    private fun decodeScaledFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath, decodeOptions(bounds.outWidth, bounds.outHeight)
        ) ?: return null
        return scaleToThumbnail(decoded)
    }

    private fun decodeOptions(width: Int, height: Int): BitmapFactory.Options {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            // Podcast covers are opaque in practice. RGB_565 halves their
            // resident memory and is visually indistinguishable on the glasses.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    }

    private fun scaleToThumbnail(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= THUMBNAIL_PX) return source
        val scale = THUMBNAIL_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun fileFor(url: String) = File(dir, url.hashCode().toUInt().toString(16) + ".art")

    private companion object {
        const val THUMBNAIL_PX = 256
        const val MEM_CACHE_KB = 12 * 1024
    }
}
