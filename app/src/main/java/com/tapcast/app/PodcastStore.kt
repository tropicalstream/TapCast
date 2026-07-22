package com.tapcast.app

import android.content.Context
import android.util.Xml
import java.io.File
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Persistence: subscriptions + per-episode listening state in SharedPreferences,
 * cached feed XML and downloaded audio in app storage. The store is the single
 * source of truth for "what am I subscribed to / where was I in this episode" —
 * the classic podcast-app trio of resume position, played flag, and queue.
 */
class PodcastStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapcast", Context.MODE_PRIVATE)
    val feedCacheDir: File = File(context.filesDir, "feeds").apply { mkdirs() }
    val episodesDir: File = File(context.filesDir, "episodes").apply { mkdirs() }
    val artDir: File = File(context.filesDir, "art").apply { mkdirs() }

    // ---- Subscriptions ------------------------------------------------------

    fun subscriptions(): List<Podcast> {
        val raw = prefs.getString("subs", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Podcast(
                    feedUrl = o.getString("feed"),
                    title = o.optString("title"),
                    author = o.optString("author"),
                    artUrl = o.optString("art").takeIf { it.isNotBlank() },
                    description = o.optString("desc")
                )
            }.filter { it.feedUrl.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun isSubscribed(feedUrl: String): Boolean = subscriptions().any { it.feedUrl == feedUrl }

    /**
     * Bulk-import subscriptions from an OPML file — the universal podcast-export
     * format (Pocket Casts, Apple Podcasts, Overcast, Castro, AntennaPod all
     * produce it). Only feeds not already subscribed are added, and the whole
     * file is written back in a single commit. Returns the count of NEW shows.
     */
    fun importOpml(xml: String): Int {
        val merged = subscriptions().toMutableList()
        val known = merged.mapTo(HashSet()) { it.feedUrl }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var added = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                val feed = (parser.getAttributeValue(null, "xmlUrl")
                    ?: parser.getAttributeValue(null, "xmlurl"))?.trim()
                if (!feed.isNullOrBlank() && known.add(feed)) {
                    val title = (parser.getAttributeValue(null, "text")
                        ?: parser.getAttributeValue(null, "title") ?: "Untitled").trim()
                    // Art/author fill in the first time each show is opened.
                    merged += Podcast(feedUrl = feed, title = title, author = "", artUrl = null)
                    added++
                }
            }
            event = parser.next()
        }
        if (added > 0) saveSubs(merged)
        return added
    }

    fun subscribe(p: Podcast) {
        val list = subscriptions().filterNot { it.feedUrl == p.feedUrl } + p
        saveSubs(list)
    }

    fun unsubscribe(feedUrl: String) {
        saveSubs(subscriptions().filterNot { it.feedUrl == feedUrl })
        feedCacheFile(feedUrl).delete()
    }

    private fun saveSubs(list: List<Podcast>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("feed", p.feedUrl).put("title", p.title)
                .put("author", p.author).put("art", p.artUrl.orEmpty()).put("desc", p.description))
        }
        prefs.edit().putString("subs", arr.toString()).apply()
    }

    // ---- Per-show recency (powers the 🎧 gallery sort) -----------------------

    fun lastOpenedShow(feedUrl: String): Long =
        prefs.getLong("opened_${feedUrl.hashCode()}", 0L)

    fun touchOpenedShow(feedUrl: String) =
        prefs.edit().putLong("opened_${feedUrl.hashCode()}", System.currentTimeMillis()).apply()

    fun feedCacheFile(feedUrl: String): File =
        File(feedCacheDir, feedUrl.hashCode().toUInt().toString(16) + ".xml")

    // ---- Per-episode listening state ---------------------------------------

    /** Saved resume position in ms; 0 = start. */
    fun positionMs(ep: Episode): Long = prefs.getLong("pos_${ep.key}", 0L)

    fun savePosition(ep: Episode, ms: Long) {
        prefs.edit().putLong("pos_${ep.key}", ms.coerceAtLeast(0L)).apply()
    }

    fun isPlayed(ep: Episode): Boolean = prefs.getBoolean("played_${ep.key}", false)

    fun setPlayed(ep: Episode, played: Boolean) {
        prefs.edit().putBoolean("played_${ep.key}", played)
            .putLong("pos_${ep.key}", 0L).apply()
    }

    // ---- Downloads ----------------------------------------------------------

    fun downloadFile(ep: Episode): File = File(episodesDir, "${ep.key}.mp3")
    fun isDownloaded(ep: Episode): Boolean = downloadFile(ep).let { it.isFile && it.length() > 0L }
    fun deleteDownload(ep: Episode) { downloadFile(ep).delete() }

    /** Total bytes of downloaded audio (for the settings storage line). */
    fun downloadsBytes(): Long = episodesDir.listFiles()?.sumOf { it.length() } ?: 0L

    // ---- Last played (resume across app launches) ---------------------------

    fun saveNowPlaying(ep: Episode?) {
        if (ep == null) { prefs.edit().remove("now_playing").apply(); return }
        prefs.edit().putString("now_playing", JSONObject()
            .put("guid", ep.guid).put("feed", ep.feedUrl).put("title", ep.title)
            .put("audio", ep.audioUrl).put("pub", ep.pubDateMs).put("dur", ep.durationSec)
            .put("desc", ep.description.take(500)).put("size", ep.sizeBytes).toString()).apply()
    }

    fun lastPlayed(): Episode? = prefs.getString("now_playing", null)?.let {
        runCatching {
            val o = JSONObject(it)
            Episode(o.getString("guid"), o.getString("feed"), o.optString("title"),
                o.getString("audio"), o.optLong("pub"), o.optInt("dur"),
                o.optString("desc"), o.optLong("size"))
        }.getOrNull()
    }

    // ---- Settings -----------------------------------------------------------

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)
    fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    companion object {
        const val K_GROQ_KEY = "groq_key"          // voice search (Whisper STT)
        const val K_SPEED = "playback_speed"       // persisted playback rate
        const val K_SKIP_BACK = "skip_back_sec"
        const val K_SKIP_FWD = "skip_fwd_sec"
        const val K_AUTOPLAY = "autoplay_next"     // 1 = play next episode when done
    }
}
