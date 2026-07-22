package com.tapcast.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Podcast playback with the table-stakes features of a modern podcast app:
 * resume-where-you-left-off, playback speed, skip ±, a sleep timer, and
 * autoplay-next. Streams from the enclosure URL, or plays the downloaded file
 * when one exists (offline-first). Positions persist through [store] every few
 * seconds so a crash or battery death never loses the spot.
 */
class PlayerEngine(private val context: Context, private val store: PodcastStore) {
    companion object { private const val TAG = "TapCast" }

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null

    var episode: Episode? = null
        private set
    var isPreparing = false
        private set
    var sleepRemainingMs: Long = 0L
        private set

    /** UI callbacks — all invoked on the main thread. */
    var onState: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Fires when an episode plays to the end (after played-flag bookkeeping). */
    var onCompleted: ((Episode) -> Unit)? = null

    // All three MUST be inert while preparing: querying a streaming MediaPlayer
    // before onPrepared triggers native error -38 through the error CALLBACK
    // (runCatching can't intercept it), which used to kill every streamed
    // episode within the first UI tick — "tap to play doesn't work".
    val isPlaying: Boolean get() = !isPreparing && runCatching { mp?.isPlaying == true }.getOrDefault(false)
    val positionMs: Long get() = if (isPreparing) 0L else runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    val durationMs: Long get() = if (isPreparing) 0L else runCatching { mp?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    var speed: Float = store.getFloat(PodcastStore.K_SPEED, 1.0f)
        private set

    // ---- Core ----------------------------------------------------------------

    /** Start (or restart) an episode, resuming from its saved position. */
    fun play(ep: Episode) {
        stopInternal(savePosition = true)
        episode = ep
        isPreparing = true
        onState?.invoke()
        val source = if (store.isDownloaded(ep)) store.downloadFile(ep).absolutePath else ep.audioUrl
        val player = MediaPlayer()
        mp = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            player.setDataSource(source)
            player.setOnPreparedListener {
                isPreparing = false
                val resume = store.positionMs(ep)
                if (resume > 4_000 && resume < player.duration - 4_000) player.seekTo(resume.toInt())
                applySpeed(player)
                player.start()
                store.saveNowPlaying(ep)
                startTicker()
                onState?.invoke()
            }
            player.setOnCompletionListener {
                store.setPlayed(ep, true)
                stopTicker()
                onState?.invoke()
                onCompleted?.invoke(ep)
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "player error $what/$extra for ${ep.title}")
                isPreparing = false
                onError?.invoke("Playback failed — check the connection or download the episode")
                stopInternal(savePosition = false)
                onState?.invoke()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
            isPreparing = false
            onError?.invoke("Could not start this episode")
            stopInternal(savePosition = false)
        }
    }

    fun togglePause() {
        val p = mp ?: return
        if (isPreparing) return   // let onPrepared start it; poking now = error -38
        runCatching {
            if (p.isPlaying) { p.pause(); persistPosition() } else { applySpeed(p); p.start() }
        }
        onState?.invoke()
    }

    fun seekBy(deltaSec: Int) {
        val p = mp ?: return
        if (isPreparing) return
        runCatching {
            val target = (p.currentPosition + deltaSec * 1000).coerceIn(0, p.duration)
            p.seekTo(target)
            persistPosition()
        }
        onState?.invoke()
    }

    fun stop(savePosition: Boolean = true) {
        stopInternal(savePosition)
        onState?.invoke()
    }

    private fun stopInternal(savePosition: Boolean) {
        if (savePosition) persistPosition()
        stopTicker()
        cancelSleep()
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        isPreparing = false
    }

    // ---- Speed ----------------------------------------------------------------

    /** Cycle 1.0 → 1.2 → 1.5 → 1.8 → 2.0 → 0.8 → 1.0 (persisted). */
    fun cycleSpeed(): Float {
        val steps = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 0.8f)
        speed = steps[(steps.indexOf(speed).coerceAtLeast(0) + 1) % steps.size]
        store.putFloat(PodcastStore.K_SPEED, speed)
        mp?.let { applySpeed(it) }
        onState?.invoke()
        return speed
    }

    private fun applySpeed(p: MediaPlayer) {
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(speed)
            // Setting params starts playback on some devices; honor paused state.
            if (!wasPlaying) p.pause()
        }
    }

    // ---- Sleep timer -----------------------------------------------------------

    private var sleepRunnable: Runnable? = null

    /** Cycle off → 15 → 30 → 45 → 60 min → off. Returns the new minutes (0 = off). */
    fun cycleSleepTimer(): Int {
        val steps = listOf(0, 15, 30, 45, 60)
        val currentMin = (sleepRemainingMs / 60_000L).toInt()
        val next = steps[(steps.indexOfFirst { it >= currentMin && currentMin != 0 }
            .let { if (currentMin == 0) 0 else it } + 1) % steps.size]
        cancelSleep()
        if (next > 0) {
            sleepRemainingMs = next * 60_000L
            val r = object : Runnable {
                override fun run() {
                    sleepRemainingMs -= 1_000L
                    if (sleepRemainingMs <= 0L) {
                        if (isPlaying) togglePause()
                        cancelSleep()
                        onState?.invoke()
                    } else main.postDelayed(this, 1_000L)
                }
            }
            sleepRunnable = r
            main.postDelayed(r, 1_000L)
        }
        onState?.invoke()
        return next
    }

    private fun cancelSleep() {
        sleepRunnable?.let { main.removeCallbacks(it) }
        sleepRunnable = null
        sleepRemainingMs = 0L
    }

    // ---- Position persistence ---------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            persistPosition()
            onState?.invoke()
            main.postDelayed(this, 5_000L)
        }
    }

    private fun startTicker() { stopTicker(); main.postDelayed(ticker, 5_000L) }
    private fun stopTicker() { main.removeCallbacks(ticker) }

    private fun persistPosition() {
        val ep = episode ?: return
        val pos = positionMs
        if (pos > 0L) store.savePosition(ep, pos)
    }
}
