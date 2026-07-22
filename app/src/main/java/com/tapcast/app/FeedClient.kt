package com.tapcast.app

import android.util.Log
import android.util.Xml
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Podcast discovery and feeds. Discovery uses the iTunes Search API (free, no
 * key, the ecosystem's de-facto directory); shows themselves are plain RSS,
 * parsed with the platform XmlPullParser. Feed XML is cached on disk so the
 * gallery and episode lists work offline and load instantly.
 */
object FeedClient {
    private const val TAG = "TapCast"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // ---- Discovery (iTunes Search API) --------------------------------------

    fun search(term: String, limit: Int = 24): List<Podcast> {
        val url = "https://itunes.apple.com/search?media=podcast&limit=$limit&term=" +
            URLEncoder.encode(term.trim(), "UTF-8")
        return itunesResults(url)
    }

    /** Top charts → batch lookup (charts alone don't carry feed URLs). */
    fun topCharts(limit: Int = 20): List<Podcast> {
        return try {
            val charts = get("https://itunes.apple.com/us/rss/toppodcasts/limit=$limit/json") ?: return emptyList()
            val entries = JSONObject(charts).optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
            val ids = (0 until entries.length()).mapNotNull { i ->
                entries.optJSONObject(i)?.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")
                    ?.takeIf { it.isNotBlank() }
            }
            if (ids.isEmpty()) return emptyList()
            itunesResults("https://itunes.apple.com/lookup?id=" + ids.joinToString(","))
        } catch (e: Exception) {
            Log.w(TAG, "top charts failed: ${e.message}")
            emptyList()
        }
    }

    private fun itunesResults(url: String): List<Podcast> {
        return try {
            val body = get(url) ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val feed = o.optString("feedUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Podcast(
                    feedUrl = feed,
                    title = o.optString("collectionName").ifBlank { o.optString("trackName", "Untitled") },
                    author = o.optString("artistName"),
                    artUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }
                        .takeIf { it.isNotBlank() },
                    description = o.optString("primaryGenreName")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "itunes query failed: ${e.message}")
            emptyList()
        }
    }

    // ---- RSS feed → episodes -------------------------------------------------

    /**
     * Fetch a show's feed (network first, cached copy as fallback so episode
     * lists still open offline). Returns show metadata refinements + episodes,
     * newest first.
     */
    fun episodes(podcast: Podcast, cacheFile: File, refresh: Boolean): Pair<Podcast, List<Episode>> {
        var xml: String? = null
        if (refresh || !cacheFile.isFile) {
            xml = get(podcast.feedUrl)
            if (xml != null) runCatching { cacheFile.writeText(xml) }
        }
        if (xml == null && cacheFile.isFile) xml = runCatching { cacheFile.readText() }.getOrNull()
        if (xml == null) return podcast to emptyList()
        return runCatching { parseRss(podcast, xml) }.getOrElse {
            Log.w(TAG, "rss parse failed: ${it.message}")
            podcast to emptyList()
        }
    }

    private fun parseRss(podcast: Podcast, xml: String): Pair<Podcast, List<Episode>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val episodes = ArrayList<Episode>()
        var showTitle = podcast.title
        var showAuthor = podcast.author
        var showArt = podcast.artUrl
        var showDesc = podcast.description

        var inItem = false
        var title = ""; var guid = ""; var audio = ""; var pub = 0L
        var dur = 0; var desc = ""; var size = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        name == "item" -> {
                            inItem = true
                            title = ""; guid = ""; audio = ""; pub = 0L; dur = 0; desc = ""; size = 0L
                        }
                        name == "enclosure" && inItem -> {
                            if (audio.isBlank()) {
                                audio = parser.getAttributeValue(null, "url").orEmpty()
                                size = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                            }
                        }
                        name == "itunes:image" -> {
                            if (!inItem && showArt.isNullOrBlank())
                                showArt = parser.getAttributeValue(null, "href")
                        }
                        else -> {
                            val text = { runCatching { parser.nextText() }.getOrDefault("").trim() }
                            if (inItem) when (name) {
                                "title" -> title = text()
                                "guid" -> guid = text()
                                "pubdate" -> pub = parseDate(text())
                                "itunes:duration" -> dur = parseDuration(text())
                                "description", "itunes:summary" ->
                                    if (desc.isBlank()) desc = stripHtml(text()).take(1_200)
                            } else when (name) {
                                "title" -> if (showTitle.isBlank()) showTitle = text()
                                "itunes:author" -> if (showAuthor.isBlank()) showAuthor = text()
                                "description" -> if (showDesc.isBlank()) showDesc = stripHtml(text()).take(600)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                    inItem = false
                    if (audio.isNotBlank()) episodes.add(Episode(
                        guid = guid.ifBlank { audio },
                        feedUrl = podcast.feedUrl,
                        title = title.ifBlank { "Untitled episode" },
                        audioUrl = audio,
                        pubDateMs = pub,
                        durationSec = dur,
                        description = desc,
                        sizeBytes = size
                    ))
                }
            }
            event = parser.next()
        }
        episodes.sortByDescending { it.pubDateMs }
        return podcast.copy(title = showTitle, author = showAuthor, artUrl = showArt, description = showDesc) to episodes
    }

    /** RFC-822 pubDate variants seen in the wild. */
    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z"
    )

    private fun parseDate(s: String): Long {
        for (f in DATE_FORMATS) {
            runCatching { return SimpleDateFormat(f, Locale.US).parse(s)!!.time }
        }
        return 0L
    }

    /** itunes:duration is "HH:MM:SS", "MM:SS", or plain seconds. */
    fun parseDuration(s: String): Int {
        val t = s.trim()
        if (t.isEmpty()) return 0
        if (':' !in t) return t.toIntOrNull() ?: 0
        return t.split(':').fold(0) { acc, part -> acc * 60 + (part.trim().toIntOrNull() ?: 0) }
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("\\s+"), " ").trim()

    fun get(url: String): String? = fetch(url) { it.string() }
    fun getBytes(url: String): ByteArray? = fetch(url) { it.bytes() }

    private fun <T> fetch(url: String, extract: (okhttp3.ResponseBody) -> T): T? {
        for (candidate in fallbackUrls(url)) {
            try {
                val req = Request.Builder().url(candidate).header("User-Agent", "TapCast/1.0").build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.let(extract)
                }
            } catch (e: Exception) {
                Log.w(TAG, "GET failed ($candidate): ${e.message}")
            }
        }
        return null
    }

    /**
     * The URL to try, plus a fallback for a common broken-server case: some feeds
     * (e.g. DigiBarn Radio) are served on https://www.HOST but the TLS certificate
     * only covers the bare HOST, so the handshake fails hostname verification and
     * NO podcast app can fetch them. Dropping "www." succeeds where the cert is
     * valid for the apex domain.
     */
    private fun fallbackUrls(url: String): List<String> {
        val alt = Regex("^(https?://)www\\.").find(url)?.let { url.replaceFirst("www.", "") }
        return if (alt != null && alt != url) listOf(url, alt) else listOf(url)
    }
}
