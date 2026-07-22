package com.tapcast.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TapCast — podcasts for the RayNeo X3 Pro.
 *
 * Interaction model (family style: TapReader/SmartView):
 *   right pad ......... cursor; click = activate under cursor
 *   single tap ........ (player screen) play/pause
 *   double tap ........ player: toggle the bottom menu bar (focus-driven:
 *                       swipe moves the highlight, tap activates)
 *   left edge pull .... back
 *   top/bottom band ... scroll lists
 *   keyboard 🎤 ....... voice search via Groq Whisper
 */
class MainActivity : Activity(), CustomKeyboardView.OnKeyboardActionListener {

    private enum class Screen { HOME, SEARCH, PODCAST, PLAYER, SETTINGS }

    private lateinit var store: PodcastStore
    private lateinit var art: ArtStore
    private lateinit var player: PlayerEngine
    private lateinit var recorder: GroqSpeech.Recorder

    private lateinit var binocular: BinocularSbsLayout
    private lateinit var viewport: FrameLayout
    private lateinit var homeList: LinearLayout
    private lateinit var homePanel: ScrollView
    private lateinit var searchList: LinearLayout
    private lateinit var searchPanel: ScrollView
    private lateinit var podcastList: LinearLayout
    private lateinit var podcastPanel: ScrollView
    private lateinit var playerPanel: LinearLayout
    private lateinit var settingsList: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var controlBar: LinearLayout
    private lateinit var statusPill: TextView
    private lateinit var topHud: TextView
    private lateinit var keyboardContainer: FrameLayout
    private var keyboardView: CustomKeyboardView? = null

    private val main = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME

    private var currentPodcast: Podcast? = null
    private var currentEpisodes: List<Episode> = emptyList()
    private var searchResults: List<Podcast> = emptyList()
    private var searchHeading = "Search podcasts"
    private var inputBuffer = StringBuilder()
    private var templeKeyDownAtMs = 0L
    private var templeKeyHeld = false

    // ---- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PodcastStore(this)
        art = ArtStore(store.artDir)
        player = PlayerEngine(this, store)
        recorder = GroqSpeech.Recorder(this)
        buildUi()
        wirePlayer()
        wireGestures()
        importSeedOpml()
        showHome()
        main.post(hudTick)
    }

    /** Keep the X3 system hold gesture outside TapCast. The Activity must own
     *  this sequence: Android may route the long-repeat around the focused root
     *  view, which made an old view-level handler see only the final UP and
     *  misread a hold as a tap. All DOWN/repeat events continue through the
     *  platform; only a proven short UP becomes a TapCast tap. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val templeKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        if (!templeKey) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    templeKeyDownAtMs = SystemClock.uptimeMillis()
                    templeKeyHeld = event.isLongPress
                } else {
                    templeKeyHeld = true
                }
                return super.dispatchKeyEvent(event)
            }
            KeyEvent.ACTION_UP -> {
                val eventHeldMs = event.eventTime - event.downTime
                val wallHeldMs = if (templeKeyDownAtMs > 0L) {
                    SystemClock.uptimeMillis() - templeKeyDownAtMs
                } else 0L
                val isHold = templeKeyHeld || event.isCanceled ||
                    maxOf(eventHeldMs, wallHeldMs) >= SYSTEM_HOLD_MS
                templeKeyDownAtMs = 0L
                templeKeyHeld = false
                if (isHold) return super.dispatchKeyEvent(event)
                binocular.onTempleTap()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * One-shot OPML import: any file pushed to filesDir/import.opml is consumed
     * and deleted on launch. Works with a Pocket Casts, Apple Podcasts, Overcast,
     * or AntennaPod export alike (OPML is the shared format). Off the UI thread
     * because a large list means parsing hundreds of outline elements.
     */
    private fun importSeedOpml() {
        val f = java.io.File(filesDir, "import.opml")
        if (!f.isFile) return
        Thread {
            val added = runCatching { store.importOpml(f.readText()) }.getOrDefault(0)
            f.delete()
            main.post {
                if (added > 0) { flash("Imported $added podcasts"); if (screen == Screen.HOME) refreshHome() }
            }
        }.start()
    }

    override fun onDestroy() {
        player.stop(savePosition = true)
        super.onDestroy()
    }

    // ---- UI construction -------------------------------------------------------

    private fun buildUi() {
        homeList = column(); homePanel = scroll(homeList)
        searchList = column(); searchPanel = scroll(searchList)
        podcastList = column(); podcastPanel = scroll(podcastList)
        settingsList = column(); settingsPanel = scroll(settingsList)
        playerPanel = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { if (player.episode != null) player.togglePause() }
        }

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC0A0A0A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52))
            lp.gravity = Gravity.BOTTOM
            layoutParams = lp
        }
        rebuildControlBar()

        statusPill = TextView(this).apply {
            setBackgroundColor(0xE6101418.toInt()); setTextColor(Color.WHITE)
            textSize = 13f; setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = dp(34)
            layoutParams = lp
        }

        topHud = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFD8DEE9.toInt()); gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true); setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(0xC9000000.toInt())
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(26))
            lp.gravity = Gravity.TOP; layoutParams = lp
        }

        keyboardContainer = FrameLayout(this).apply {
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150))
            lp.gravity = Gravity.BOTTOM
            layoutParams = lp
        }

        buildConfirmOverlay()

        viewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(homePanel, match())
            addView(searchPanel, match())
            addView(podcastPanel, match())
            addView(playerPanel, match())
            addView(settingsPanel, match())
            addView(controlBar)
            addView(topHud)
            addView(statusPill)
            addView(keyboardContainer)
            addView(confirmOverlay, match())
        }

        binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(viewport, 0)
            setContentTarget(viewport)
        }
        setContentView(binocular)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    // ---- Confirm overlay ---------------------------------------------------------

    private lateinit var confirmOverlay: FrameLayout
    private lateinit var confirmMessage: TextView
    private lateinit var confirmYes: Button
    private lateinit var confirmCancel: Button
    private var confirmFocus = 0   // 0 = Cancel (safe default), 1 = Confirm

    private fun buildConfirmOverlay() {
        confirmMessage = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        confirmYes = Button(this).apply {
            text = "Confirm"; isAllCaps = false; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        confirmCancel = Button(this).apply {
            text = "Cancel"; isAllCaps = false; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { confirmOverlay.visibility = View.GONE }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF161B22.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF30363D.toInt())
            }
            val lp = FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER; layoutParams = lp
            addView(confirmMessage)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; addView(confirmCancel); addView(confirmYes)
            })
        }
        confirmOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            visibility = View.GONE
            addView(card)
        }
    }

    private fun showConfirm(message: String, label: String, onConfirm: () -> Unit) {
        confirmMessage.text = message
        confirmYes.text = label
        confirmYes.setOnClickListener { confirmOverlay.visibility = View.GONE; onConfirm() }
        confirmFocus = 0   // land on Cancel — a stray tap never destroys anything
        applyConfirmFocus()
        confirmOverlay.visibility = View.VISIBLE
        confirmOverlay.bringToFront()
    }

    private fun confirmButtons() = listOf(confirmCancel, confirmYes)

    private fun applyConfirmFocus() {
        confirmButtons().forEachIndexed { i, b ->
            b.foreground = if (i == confirmFocus) hoverRing() else null
        }
    }

    private fun stepConfirmFocus(delta: Int) {
        confirmFocus = (confirmFocus + delta).mod(2)
        applyConfirmFocus()
    }

    // ---- Hover selection (the whole app is swipe-driven, no cursor) ----------------
    //
    // Every list screen registers its actionable views in visual order. Swiping
    // moves an amber hover ring: horizontal = one item, vertical = a fast jump
    // (a full grid row on Home, a whole episode on show pages). Tap activates
    // whatever is hovered. The crosshair cursor only exists for the keyboard.

    private val hoverItems = mutableListOf<View>()
    private var hoverFocus = 0

    private fun hoverRegister(v: View) { hoverItems += v }

    private fun hoverReset() {
        hoverItems.clear()
        hoverFocus = 0
    }

    /** Register every actionable view under [root] in visual (tree) order. A
     *  clickable container with clickable children defers to the children —
     *  e.g. an episode row yields its Play/Download/Mark buttons, while an
     *  artwork tile (no inner buttons) registers as one target. */
    private fun hoverRegisterTree(root: View) {
        if (root.visibility != View.VISIBLE) return
        if (root is ViewGroup) {
            if (root.isClickable && !hasClickableDescendant(root)) { hoverRegister(root); return }
            for (i in 0 until root.childCount) hoverRegisterTree(root.getChildAt(i))
        } else if (root.isClickable) hoverRegister(root)
    }

    private fun hasClickableDescendant(g: ViewGroup): Boolean {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isClickable) return true
            if (c is ViewGroup && hasClickableDescendant(c)) return true
        }
        return false
    }

    /** Rebuild the hover registry for the currently visible list screen. The
     *  ring's position is PRESERVED — refreshes fire mid-navigation (download
     *  progress, sort/view toggles) and must not snap focus back to the top.
     *  Screen entry points call [hoverReset] explicitly for a fresh start. */
    private fun rebuildHover(root: View) {
        val keep = hoverFocus
        hoverItems.clear()
        hoverRegisterTree(root)
        hoverFocus = keep.coerceIn(0, (hoverItems.size - 1).coerceAtLeast(0))
        applyHoverFocus(scrollTo = false)
    }

    /** Center of a hover item in its screen's scroll-content coordinate space. */
    private fun hoverCenter(v: View): Pair<Float, Float> {
        var x = 0; var y = 0; var cur: View = v
        val content = activeScroll()?.getChildAt(0)
        while (cur !== content && cur.parent is View) {
            x += cur.left; y += cur.top
            cur = cur.parent as View
        }
        return (x + v.width / 2f) to (y + v.height / 2f)
    }

    /** Vertical navigation by GEOMETRY, like d-pad focus search: go to the
     *  nearest item in the next row down/up, preferring the same column. Index
     *  arithmetic (±grid columns) moved sideways whenever header items sat
     *  between grid rows — that's the "jumps to unpredictable objects" bug. */
    private fun stepHoverVertical(dir: Int) {
        val cur = hoverItems.getOrNull(hoverFocus) ?: return
        val (cx, cy) = hoverCenter(cur)
        // Same-row neighbors can differ in height (wrapped titles), putting
        // their centers a few px "below" — a real next-row candidate must
        // clear half the current item's height.
        val rowClear = cur.height * 0.45f
        var best = -1
        var bestKey = Float.MAX_VALUE
        hoverItems.forEachIndexed { i, v ->
            if (i == hoverFocus) return@forEachIndexed
            val (x, y) = hoverCenter(v)
            if (dir > 0 && y <= cy + rowClear) return@forEachIndexed
            if (dir < 0 && y >= cy - rowClear) return@forEachIndexed
            // Row distance dominates; column distance breaks the tie.
            val key = kotlin.math.abs(y - cy) * 1000f + kotlin.math.abs(x - cx)
            if (key < bestKey) { bestKey = key; best = i }
        }
        if (best >= 0) { hoverFocus = best; applyHoverFocus() }
        // No candidate = top/bottom edge: stay put, never wrap-teleport.
    }

    /** Short human-readable identity of a hover target, for the input log. */
    private fun hoverLabel(v: View): String =
        v.contentDescription?.toString()?.take(30)
            ?: (v as? Button)?.text?.toString()?.take(30)
            ?: v.javaClass.simpleName

    /** The amber selection ring drawn over the hovered/focused view. */
    private fun hoverRing() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(0x22FFC466)
        setStroke(dp(2), 0xFFFFC466.toInt())
    }

    private fun applyHoverFocus(scrollTo: Boolean = true) {
        if (hoverItems.isEmpty()) return
        hoverFocus = hoverFocus.coerceIn(0, hoverItems.lastIndex)
        hoverItems.forEachIndexed { i, v ->
            v.foreground = if (i == hoverFocus) hoverRing() else null
        }
        android.util.Log.i("TapCastInput", "hover $hoverFocus/${hoverItems.size} on ${hoverLabel(hoverItems[hoverFocus])}")
        if (!scrollTo) return
        // Keep the hovered item comfortably on screen.
        val panel = activeScroll() ?: return
        hoverItems.getOrNull(hoverFocus)?.let { v ->
            var y = 0; var cur: View = v
            val content = panel.getChildAt(0) ?: return
            while (cur !== content && cur.parent is View) { y += cur.top; cur = cur.parent as View }
            panel.smoothScrollTo(0, (y - dp(90)).coerceAtLeast(0))
        }
    }

    private fun stepHover(delta: Int) {
        if (hoverItems.isEmpty()) return
        // Clamped at both ends — wrap-around teleports read as random jumps.
        hoverFocus = (hoverFocus + delta).coerceIn(0, hoverItems.lastIndex)
        applyHoverFocus()
    }

    private fun activateHover() {
        hoverItems.getOrNull(hoverFocus)?.performClick()
    }

    // ---- Focus-driven player menu bar ----------------------------------------------

    private val ctlButtons = mutableListOf<Button>()
    private var ctlFocus = 0

    private fun rebuildControlBar() {
        controlBar.removeAllViews()
        ctlButtons.clear()
        fun add(b: Button) { ctlButtons += b; controlBar.addView(b) }
        val back = store.getInt(PodcastStore.K_SKIP_BACK, 15)
        val fwd = store.getInt(PodcastStore.K_SKIP_FWD, 30)
        add(ctlButton(if (player.isPlaying) "⏸ Pause" else "▶ Play") { player.togglePause() })
        add(ctlButton("⏪ $back") { player.seekBy(-back) })
        add(ctlButton("$fwd ⏩") { player.seekBy(fwd) })
        add(ctlButton("${player.speed}×") { flash("Speed ${player.cycleSpeed()}×"); rebuildControlBar() })
        add(ctlButton(if (player.sleepRemainingMs > 0) "😴 ${player.sleepRemainingMs / 60_000}m" else "😴 Sleep") {
            val m = player.cycleSleepTimer()
            flash(if (m == 0) "Sleep timer off" else "Sleeping in $m min")
            rebuildControlBar()
        })
        add(ctlButton("Home") { hideControlBar(); showHome() })
        ctlFocus = ctlFocus.coerceIn(0, ctlButtons.lastIndex)
        applyCtlFocus()
    }

    private fun applyCtlFocus() {
        ctlButtons.forEachIndexed { i, b ->
            val focused = i == ctlFocus
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
            }
            b.setTextColor(if (focused) 0xFFFFF3C4.toInt() else 0xFFD8DEE9.toInt())
        }
    }

    private fun stepCtlFocus(delta: Int) {
        if (ctlButtons.isEmpty()) return
        ctlFocus = (ctlFocus + delta).mod(ctlButtons.size)
        applyCtlFocus()
        resetControlBarTimer()
    }

    private val controlBarHideRunnable = Runnable { hideControlBar() }

    private fun showControlBar() {
        ctlFocus = 0
        rebuildControlBar()
        controlBar.visibility = View.VISIBLE
        controlBar.bringToFront()
        resetControlBarTimer()
    }

    private fun hideControlBar() {
        controlBar.visibility = View.GONE
        main.removeCallbacks(controlBarHideRunnable)
    }

    private fun resetControlBarTimer() {
        main.removeCallbacks(controlBarHideRunnable)
        main.postDelayed(controlBarHideRunnable, 7_000L)
    }

    // ---- Gestures ---------------------------------------------------------------

    private fun wireGestures() {
        binocular.apply {
            logicalClickHandler = { x, y -> handleClick(x, y) }
            edgeScrollHandler = { dy -> activeScroll()?.scrollBy(0, dy * 2) }
            leftEdgeBackHandler = { onBack() }
            doubleTapHandler = { onDoubleTap() }
            tripleTapHandler = { showSettings() }
            systemHoldHandler = { openRayNeoControlCenter() }
            tapInterceptor = { if (recorder.isRecording) { finishVoiceSearch(); true } else false }
            // Hover selection everywhere: all pad movement is consumed as hover
            // steps (except while the keyboard is up — its keys are position-
            // targeted, so the cursor briefly returns for typing).
            menuNavigationActive = { keyboardContainer.visibility != View.VISIBLE }
            horizontalStepHandler = { delta ->
                when {
                    confirmOverlay.visibility == View.VISIBLE -> stepConfirmFocus(delta)
                    screen == Screen.PLAYER ->
                        if (controlBar.visibility == View.VISIBLE) stepCtlFocus(delta) else showControlBar()
                    else -> stepHover(delta)
                }
            }
            verticalStepHandler = { delta ->
                when {
                    confirmOverlay.visibility == View.VISIBLE -> stepConfirmFocus(delta)
                    screen == Screen.PLAYER ->
                        // Down summons the bar, up dismisses it — then double-tap backs out.
                        if (delta > 0 && controlBar.visibility != View.VISIBLE) showControlBar()
                        else if (delta < 0) hideControlBar()
                        else Unit
                    else -> stepHoverVertical(delta)
                }
            }
            cursorSuppressed = { keyboardContainer.visibility != View.VISIBLE }
            contentInteractionBlocked = { true }   // no cursor-driven content anywhere
        }
    }

    /**
     * RayNeo's exported launcher protocol opens the genuine system Control
     * Center (the same panel as the global right-pad hold).  Keeping this local
     * route means the user can always escape TapCast even if Android has just
     * restarted the launcher's global input-monitor service.
     */
    private fun openRayNeoControlCenter() {
        val controlCenter = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mercury://com.ffalconxr.mercury.launcher/openApp/shortcut")
        ).addCategory(Intent.CATEGORY_DEFAULT)
        runCatching { startActivity(controlCenter) }
            .onFailure {
                android.util.Log.e("TapCastInput", "Control Center route failed", it)
                // Firmware-safe escape hatch: never leave the wearer trapped.
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(home); finishAndRemoveTask() }
            }
    }

    private fun handleClick(x: Float, y: Float): Boolean {
        if (confirmOverlay.visibility == View.VISIBLE) {
            confirmButtons().getOrNull(confirmFocus)?.performClick()
            return true
        }
        val kb = keyboardView
        if (keyboardContainer.visibility == View.VISIBLE && kb != null) {
            val top = keyboardContainer.top.toFloat()
            if (y >= top) { kb.handleAnchoredTap(x, y - top); return true }
        }
        if (screen == Screen.PLAYER) {
            if (controlBar.visibility == View.VISIBLE) ctlButtons.getOrNull(ctlFocus)?.performClick()
            else player.togglePause()   // bare player: tap = play/pause, precisely
            return true
        }
        // Hover model: a tap activates whatever the ring is on.
        activateHover()
        return true
    }

    private fun activeScroll(): ScrollView? = when (screen) {
        Screen.HOME -> homePanel
        Screen.SEARCH -> searchPanel
        Screen.PODCAST -> podcastPanel
        Screen.SETTINGS -> settingsPanel
        else -> null
    }

    private fun onBack() {
        when {
            confirmOverlay.visibility == View.VISIBLE -> confirmOverlay.visibility = View.GONE
            keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
            screen == Screen.PLAYER -> if (currentPodcast != null) showPodcast() else showHome()
            screen == Screen.PODCAST || screen == Screen.SEARCH || screen == Screen.SETTINGS -> showHome()
            else -> { /* home root */ }
        }
    }

    private fun onDoubleTap() {
        if (confirmOverlay.visibility == View.VISIBLE) { confirmOverlay.visibility = View.GONE; return }
        when (screen) {
            // Double-tap is BACK everywhere. On the player it first collapses the
            // bar if it's up (swipe summons it; double-tap again leaves).
            Screen.PLAYER -> if (controlBar.visibility == View.VISIBLE) hideControlBar() else onBack()
            Screen.HOME -> if (player.episode != null) showPlayer()   // jump to the mini-player
            else -> onBack()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { onBack() }

    // ---- Screens -----------------------------------------------------------------

    private fun showOnly(v: View) {
        for (p in listOf(homePanel, searchPanel, podcastPanel, playerPanel, settingsPanel)) {
            p.visibility = if (p === v) View.VISIBLE else View.GONE
        }
        if (v !== playerPanel) hideControlBar()
    }

    // Fresh ring at the top on real screen ENTRY; back-navigation to a podcast
    // page (showPodcast) keeps the ring where the user left it.
    private fun showHome() { screen = Screen.HOME; showOnly(homePanel); hideKeyboard(); hoverReset(); refreshHome(); refreshHud() }
    private fun showSearch() { screen = Screen.SEARCH; showOnly(searchPanel); hoverReset(); refreshSearch(); refreshHud() }
    private fun showPodcast() { screen = Screen.PODCAST; showOnly(podcastPanel); refreshHud() }
    private fun showPlayer() { screen = Screen.PLAYER; showOnly(playerPanel); refreshPlayer(); refreshHud() }
    private fun showSettings() { screen = Screen.SETTINGS; showOnly(settingsPanel); hideKeyboard(); hoverReset(); refreshSettings(); refreshHud() }

    // ---- HOME: 2-column card gallery (art banner + title + author per tile) --------

    // Tiles awaiting artwork, keyed by feed URL — the background resolver fills
    // them in live as it works through the list.
    private val pendingArtTiles = HashMap<String, Pair<ImageView, TextView>>()

    // Home is split into a cheap header (rebuilt every visit: now-playing state
    // changes constantly) and the heavy 179-tile gallery, rebuilt ONLY when the
    // subscription list or sort order actually changes. Rebuilding everything on
    // each entry was the 12-second double-tap-home freeze.
    private var homeHeader: LinearLayout? = null
    private var artStatus: TextView? = null
    private var galleryContainer: LinearLayout? = null
    private var gallerySignature = ""

    private fun refreshHome() {
        if (homeHeader == null) {
            homeHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            artStatus = hint("")
            galleryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            homeList.addView(homeHeader)
            homeList.addView(artStatus)
            homeList.addView(galleryContainer)
        }
        rebuildHomeHeader()
        val sorted = sortedSubs()
        val signature = store.getString("gallery_sort", "az") + "|" +
            store.getString("gallery_view", "cards") + "|" + sorted.joinToString("|") { it.feedUrl }
        if (signature != gallerySignature) {
            gallerySignature = signature
            rebuildGallery(sorted)
        }
        updateArtStatus()
        resolveMissingArt()
        rebuildHover(homeList)
    }

    private fun rebuildHomeHeader() {
        val header = homeHeader ?: return
        header.removeAllViews()
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "🎙 TapCast"; textSize = 19f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pillButton("🔍 Search") { openSearch() })
            addView(pillButton("🏆 Charts") { openTopCharts() })
            addView(pillButton("⚙") { showSettings() })
        })
        val last = player.episode ?: store.lastPlayed()
        if (last != null) {
            val show = store.subscriptions().firstOrNull { it.feedUrl == last.feedUrl }
            header.addView(nowPlayingRow(last, show))
        }
        // Gallery controls, all clearly labeled and amber when active:
        // sort (Name / Added / Played) on the left, view style (Cards / Mini
        // art grid) on the right.
        val subCount = store.subscriptions().size
        header.addView(sectionTitle(if (subCount == 0) "Your shows" else "Your shows — $subCount"))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sortChip("🔤", "Name", "az", "Sort by name"))
            addView(sortChip("🆕", "Added", "recent", "Sort by recently added"))
            addView(sortChip("🎧", "Played", "played", "Sort by recently opened"))
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(viewChip("▤", "Cards", "cards", "Card gallery — art, title and author"))
            addView(viewChip("▦", "Mini", "mini", "Mini art grid — compact thumbnails"))
        })
    }

    /** Small labeled pill; glows amber when it's the active mode. */
    private fun chip(label: String, active: Boolean, desc: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false
        stateListAnimator = null
        setTextColor(if (active) 0xFFFFE2B0.toInt() else 0xFFB9C2CC.toInt())
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (active) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
            setStroke(dp(1), if (active) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(5) }
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    private fun sortChip(icon: String, label: String, mode: String, desc: String) =
        chip("$icon $label", store.getString("gallery_sort", "az") == mode, desc) {
            store.putString("gallery_sort", mode)
            refreshHome()   // signature changes → gallery re-sorts
            flash(desc)
        }

    private fun viewChip(icon: String, label: String, mode: String, desc: String) =
        chip("$icon $label", store.getString("gallery_view", "cards") == mode, desc) {
            store.putString("gallery_view", mode)
            refreshHome()   // signature changes → gallery rebuilds in the new style
            flash(desc)
        }

    private fun sortedSubs(): List<Podcast> {
        val subs = store.subscriptions()
        return when (store.getString("gallery_sort", "az")) {
            "recent" -> subs.reversed()   // append order → newest additions first
            "played" -> subs.sortedByDescending { store.lastOpenedShow(it.feedUrl) }
            else -> subs.sortedBy { it.title.lowercase() }
        }
    }

    private fun rebuildGallery(sorted: List<Podcast>) {
        val container = galleryContainer ?: return
        container.removeAllViews()
        pendingArtTiles.clear()
        if (sorted.isEmpty()) {
            container.addView(hint("No subscriptions yet. Search or browse the charts, then subscribe — new episodes appear here."))
            return
        }
        val cols = galleryCols()
        val mini = cols != 2
        var row: LinearLayout? = null
        sorted.forEachIndexed { i, p ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                container.addView(row)
            }
            row!!.addView(if (mini) miniTile(p) else showTile(p))
        }
        val rem = sorted.size % cols
        if (rem != 0) repeat(cols - rem) {
            row!!.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    /** Gallery columns for the active view: 2 wide cards or 4 mini art tiles. */
    private fun galleryCols(): Int =
        if (store.getString("gallery_view", "cards") == "mini") 4 else 2

    /** Artwork-thumbnail resume row: art, episode title, progress, play state. */
    private fun nowPlayingRow(ep: Episode, show: Podcast?): View {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        val initials = TextView(this).apply {
            text = (show?.title ?: ep.title).take(2).uppercase(); textSize = 16f
            setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        loadArt(show?.artUrl, image, initials)
        val pos = if (player.episode == ep) player.positionMs else store.positionMs(ep)
        val dur = ep.durationSec * 1000L
        val state = when {
            player.isPreparing -> "Buffering…"
            player.isPlaying -> "▶ Playing"
            pos > 0 -> "⏸ ${fmtTime(pos)}${if (dur > 0) " / ${fmtTime(dur)}" else ""}"
            else -> "▶ Start"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(8), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A222B.toInt()); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF31527A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, dp(2)) }
            addView(FrameLayout(this@MainActivity).apply {
                addView(initials); addView(image)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF10151B.toInt()); cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = ep.title; textSize = 13f; setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD); maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = "$state${show?.let { " · ${it.title.take(24)}" } ?: ""}"
                    textSize = 11f; setTextColor(0xFF9DB8D6.toInt()); maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = if (player.isPlaying) "⏸" else "▶"; textSize = 20f
                setTextColor(0xFFFFC466.toInt())
            })
            setOnClickListener {
                if (player.episode == null) player.play(ep)
                showPlayer()
            }
        }
    }

    /** Compact artwork tile. If a show has no cover, its real title becomes the
     *  cover so the four-column gallery never degrades into cryptic initials. */
    private fun miniTile(p: Podcast): View {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val initials = TextView(this).apply {
            text = p.title; textSize = 13f
            setTextColor(0xFFFFE2B0.toInt()); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF182A3B.toInt(), 0xFF10161D.toInt())
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF6E5227.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        if (p.artUrl.isNullOrBlank()) pendingArtTiles[p.feedUrl] = image to initials
        loadArt(p.artUrl, image, initials)
        return FrameLayout(this).apply {
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(132), 1f)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF12171C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF262D34.toInt())
            }
            clipToOutline = true
            addView(initials); addView(image)
            contentDescription = p.title
            setOnClickListener { openPodcast(p) }
        }
    }

    /** Card show tile (the original gallery format): art banner, bold title, author. */
    private fun showTile(p: Podcast): View {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val initials = TextView(this).apply {
            text = p.title.take(2).uppercase(); textSize = 26f
            setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        if (p.artUrl.isNullOrBlank()) pendingArtTiles[p.feedUrl] = image to initials
        loadArt(p.artUrl, image, initials)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(6), dp(6), dp(6), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF12171C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF262D34.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
                clipToOutline = true
                addView(initials); addView(image)
            })
            addView(TextView(this@MainActivity).apply {
                text = p.title; textSize = 13f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD); maxLines = 1
                setPadding(dp(2), dp(6), dp(2), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = p.author.ifBlank { " " }; textSize = 11f
                setTextColor(0xFF8B949E.toInt()); maxLines = 1
                setPadding(dp(2), dp(1), dp(2), 0)
            })
            setOnClickListener { openPodcast(p) }
        }
    }

    // ---- Background artwork resolver -------------------------------------------------

    // OPML imports carry only feed URL + title. This works through subscriptions
    // missing artwork (cached feed first, network otherwise), persists what it
    // finds, and drops each cover into the gallery live — with visible progress.
    @Volatile private var artResolverRunning = false
    @Volatile private var artDone = 0
    @Volatile private var artTotal = 0
    @Volatile private var artFailed = 0

    private fun updateArtStatus() {
        artStatus?.text = when {
            artResolverRunning -> "⬇ Fetching artwork… $artDone of $artTotal" +
                if (artFailed > 0) " ($artFailed unavailable)" else ""
            artFailed > 0 -> "Artwork done — $artFailed show${if (artFailed == 1) "" else "s"} had none available"
            else -> ""
        }
        artStatus?.visibility = if (artStatus?.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // Feeds that resolved to "no artwork anywhere" this launch — do NOT re-fetch
    // their XML on every home visit (they're retried once per app start, which
    // is plenty for a feed that might someday add a cover).
    private val artResolveFailed = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun resolveMissingArt() {
        if (artResolverRunning) return
        val missing = store.subscriptions()
            .filter { it.artUrl.isNullOrBlank() && it.feedUrl !in artResolveFailed }
        if (missing.isEmpty()) return
        artResolverRunning = true
        artDone = 0; artTotal = missing.size; artFailed = 0
        main.post { updateArtStatus() }
        Thread {
            for (p in missing) {
                if (isFinishing) break
                val meta = runCatching {
                    FeedClient.episodes(p, store.feedCacheFile(p.feedUrl), refresh = true).first
                }.getOrNull()
                val artUrl = meta?.artUrl
                if (meta != null && !artUrl.isNullOrBlank()) {
                    store.subscribe(meta)   // persist art + author
                    val bmp = art.loadOrFetch(artUrl)
                    if (bmp != null) main.post {
                        pendingArtTiles.remove(p.feedUrl)?.let { (img, init) ->
                            img.setImageBitmap(bmp); init.visibility = View.GONE
                        }
                    } else { artFailed++; artResolveFailed += p.feedUrl }
                } else { artFailed++; artResolveFailed += p.feedUrl }
                artDone++
                if (artDone % 3 == 0 || artDone == artTotal) main.post { updateArtStatus() }
            }
            artResolverRunning = false
            main.post { updateArtStatus() }
        }.start()
    }

    // ---- SEARCH -------------------------------------------------------------------

    private fun openSearch() {
        searchResults = emptyList()
        searchHeading = "Search podcasts"
        showSearch()
        startSearchInput()
    }

    private fun openTopCharts() {
        searchHeading = "🏆 Top charts"
        searchResults = emptyList()
        showSearch()
        flash("Loading top charts…", persist = true)
        Thread {
            val results = FeedClient.topCharts()
            main.post {
                flash(if (results.isEmpty()) "Charts unavailable — check the connection" else "Top ${results.size} podcasts")
                searchResults = results
                if (screen == Screen.SEARCH) refreshSearch()
            }
        }.start()
    }

    private fun refreshSearch() {
        searchList.removeAllViews()
        searchList.addView(sectionTitle(searchHeading))
        searchList.addView(bigButton("⌨  Type a search") { startSearchInput() })
        searchList.addView(hint("Or tap 🎤 on the keyboard and speak — release with another tap."))
        if (searchResults.isEmpty()) {
            searchList.addView(hint("Results appear here."))
            rebuildHover(searchList)
            return
        }
        for (p in searchResults) searchList.addView(resultRow(p))
        rebuildHover(searchList)
    }

    private fun resultRow(p: Podcast): View {
        val subscribed = store.isSubscribed(p.feedUrl)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF12171C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF262D34.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(3), 0, dp(3)) }
            setOnClickListener { openPodcast(p) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        val initials = TextView(this).apply {
            text = p.title.take(2).uppercase(); textSize = 15f
            setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        loadArt(p.artUrl, image, initials)
        row.addView(FrameLayout(this).apply { addView(initials); addView(image) })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = p.title; textSize = 14f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD); maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                text = listOf(p.author, p.description).filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 11f; setTextColor(0xFF8B949E.toInt()); maxLines = 1
            })
        })
        // Passive badge only — one hover target per result. Tapping the row opens
        // the show, where the Subscribe button lives (Apple Podcasts pattern).
        if (subscribed) row.addView(TextView(this).apply {
            text = "✓"; textSize = 16f; setTextColor(0xFFFFC466.toInt())
            setPadding(dp(8), 0, dp(4), 0)
        })
        return row
    }

    private fun runSearch(term: String) {
        val q = term.trim()
        if (q.isEmpty()) return
        searchHeading = "“${q.take(34)}”"
        flash("Searching for “${q.take(30)}”…", persist = true)
        Thread {
            val results = FeedClient.search(q)
            main.post {
                flash(if (results.isEmpty()) "No podcasts matched" else "${results.size} podcasts")
                searchResults = results
                if (screen != Screen.SEARCH) showSearch() else refreshSearch()
            }
        }.start()
    }

    // ---- Voice search ---------------------------------------------------------------

    private fun startVoiceSearch() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 9)
            return
        }
        if (store.getString(PodcastStore.K_GROQ_KEY, "").isBlank()) {
            flash("Add a Groq API key in Settings for voice search"); return
        }
        if (recorder.start()) flash("🎤 Listening… tap anywhere to finish", persist = true)
        else flash("Microphone unavailable")
    }

    private fun finishVoiceSearch() {
        val audio = recorder.stop() ?: run { flash("Didn't catch that — try again"); return }
        flash("Transcribing…", persist = true)
        GroqSpeech.transcribe(store.getString(PodcastStore.K_GROQ_KEY, ""), audio) { text, error ->
            if (text != null) {
                hideKeyboard()
                runSearch(text)
            } else flash(error ?: "Speech error")
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 9 && results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoiceSearch()
    }

    // ---- PODCAST detail ---------------------------------------------------------------

    private fun openPodcast(p: Podcast) {
        store.touchOpenedShow(p.feedUrl)   // powers the 🎧 recently-opened sort
        currentPodcast = p
        hoverReset()   // a NEW show page starts at the top (back-nav preserves)
        showPodcast()
        podcastList.removeAllViews()
        podcastList.addView(sectionTitle(p.title))
        podcastList.addView(hint("Loading episodes…"))
        Thread {
            val (meta, eps) = FeedClient.episodes(p, store.feedCacheFile(p.feedUrl), refresh = true)
            main.post {
                currentPodcast = meta
                currentEpisodes = eps
                // Persist real art/author back onto the subscription, so an
                // OPML-imported show's gallery tile stops showing bare initials
                // once it's been opened.
                if (store.isSubscribed(meta.feedUrl)) store.subscribe(meta)
                if (screen == Screen.PODCAST) refreshPodcast()
            }
        }.start()
    }

    private fun refreshPodcast() {
        val p = currentPodcast ?: return
        podcastList.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
        }
        val initials = TextView(this).apply {
            text = p.title.take(2).uppercase(); textSize = 22f
            setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
        }
        loadArt(p.artUrl, image, initials)
        header.addView(FrameLayout(this).apply { addView(initials); addView(image) })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = p.title; textSize = 17f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD); maxLines = 2
            })
            addView(TextView(this@MainActivity).apply {
                text = p.author; textSize = 12f; setTextColor(0xFF8B949E.toInt()); maxLines = 1
            })
            addView(Button(this@MainActivity).apply {
                val subd = store.isSubscribed(p.feedUrl)
                text = if (subd) "✓ Subscribed" else "＋ Subscribe"
                isAllCaps = false; textSize = 12f
                setOnClickListener {
                    if (store.isSubscribed(p.feedUrl)) {
                        showConfirm("Unsubscribe from “${p.title.take(36)}”?", "Unsubscribe") {
                            store.unsubscribe(p.feedUrl); text = "＋ Subscribe"; flash("Unsubscribed")
                        }
                    } else { store.subscribe(p); text = "✓ Subscribed"; flash("Subscribed") }
                }
            })
        })
        podcastList.addView(header)
        if (p.description.isNotBlank()) podcastList.addView(hint(p.description.take(280)))
        podcastList.addView(sectionTitle("Episodes — ${currentEpisodes.size}"))
        if (currentEpisodes.isEmpty()) podcastList.addView(hint("No episodes found in this feed."))
        for (ep in currentEpisodes.take(60)) podcastList.addView(episodeRow(ep))
        rebuildHover(podcastList)
    }

    private fun episodeRow(ep: Episode): View {
        val played = store.isPlayed(ep)
        val pos = store.positionMs(ep)
        val downloaded = store.isDownloaded(ep)
        val status = buildString {
            append(fmtDate(ep.pubDateMs))
            if (ep.durationSec > 0) append(" · ${ep.durationSec / 60} min")
            if (downloaded) append(" · 💾")
            if (played) append(" · ✓ played")
            else if (pos > 60_000L) append(" · resume ${fmtTime(pos)}")
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (played) 0xFF0E1114.toInt() else 0xFF12171C.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF262D34.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(3), 0, dp(3)) }
            setOnClickListener { player.play(ep); showPlayer() }
        }
        row.addView(TextView(this).apply {
            text = ep.title; textSize = 14f
            setTextColor(if (played) 0xFF8B949E.toInt() else Color.WHITE)
            maxLines = 2
        })
        row.addView(TextView(this).apply {
            text = status; textSize = 11f; setTextColor(0xFF6E7A85.toInt())
            setPadding(0, dp(2), 0, dp(4))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 10f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { onClick(this) }
        }
        actions.addView(small("▶ Play") { player.play(ep); showPlayer() })
        if (downloaded) {
            actions.addView(small("Remove 💾") { store.deleteDownload(ep); refreshPodcast(); flash("Download removed") })
        } else if (Downloader.isDownloading(ep)) {
            actions.addView(small("↓ ${Downloader.active[ep.key] ?: 0}%") { })
        } else {
            actions.addView(small("↓ Download") { btn ->
                btn.text = "↓ 0%"
                Downloader.download(ep, store.downloadFile(ep),
                    onProgress = { pct -> btn.text = "↓ $pct%" },
                    onDone = { ok ->
                        flash(if (ok) "Downloaded: ${ep.title.take(30)}" else "Download failed")
                        if (screen == Screen.PODCAST) refreshPodcast()
                    })
            })
        }
        actions.addView(small(if (played) "Mark unplayed" else "Mark played") {
            store.setPlayed(ep, !played); refreshPodcast()
        })
        row.addView(actions)
        return row
    }

    // ---- PLAYER (now playing) -----------------------------------------------------------

    private fun wirePlayer() {
        player.onState = { if (screen == Screen.PLAYER) refreshPlayer(); refreshHud() }
        player.onError = { flash(it) }
        player.onCompleted = { ep ->
            if (store.getInt(PodcastStore.K_AUTOPLAY, 1) == 1) {
                val idx = currentEpisodes.indexOfFirst { it.key == ep.key }
                val next = currentEpisodes.getOrNull(idx + 1)
                if (next != null && idx >= 0) {
                    flash("▶ Next: ${next.title.take(34)}")
                    player.play(next)
                } else flash("🎉 Episode finished")
            } else flash("🎉 Episode finished")
        }
    }

    private fun refreshPlayer() {
        val ep = player.episode ?: store.lastPlayed()
        playerPanel.removeAllViews()
        playerPanel.addView(spacer(dp(26)))
        if (ep == null) {
            playerPanel.addView(sectionTitle("Nothing playing"))
            playerPanel.addView(hint("Pick an episode from one of your shows."))
            return
        }
        val show = store.subscriptions().firstOrNull { it.feedUrl == ep.feedUrl } ?: currentPodcast
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150))
        }
        val initials = TextView(this).apply {
            text = (show?.title ?: ep.title).take(2).uppercase(); textSize = 30f
            setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150))
        }
        loadArt(show?.artUrl, image, initials)
        playerPanel.addView(FrameLayout(this).apply { addView(initials); addView(image) })
        playerPanel.addView(TextView(this).apply {
            text = ep.title; textSize = 16f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            maxLines = 2; setPadding(dp(30), dp(12), dp(30), dp(2))
        })
        playerPanel.addView(TextView(this).apply {
            text = show?.title.orEmpty(); textSize = 12f
            setTextColor(0xFF8B949E.toInt()); gravity = Gravity.CENTER; maxLines = 1
        })

        val pos = player.positionMs
        val dur = if (player.durationMs > 0) player.durationMs else ep.durationSec * 1000L
        // Progress with elapsed/remaining flanking the bar, podcast-app style.
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(430), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        fun timeLabel(t: String) = TextView(this).apply {
            text = t; textSize = 11f; setTextColor(0xFF9AA6B2.toInt())
            typeface = Typeface.MONOSPACE
        }
        barRow.addView(timeLabel(fmtTime(pos)))
        barRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFC466.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f).apply {
                marginStart = dp(10); marginEnd = dp(10)
            }
        })
        barRow.addView(timeLabel(if (dur > 0) "−${fmtTime((dur - pos).coerceAtLeast(0))}" else "live"))
        playerPanel.addView(barRow)
        val stateLine = buildString {
            append("${player.speed}×")
            if (player.sleepRemainingMs > 0) append("   ·   😴 ${player.sleepRemainingMs / 60_000 + 1}m")
            if (store.isDownloaded(ep)) append("   ·   💾 offline")
        }
        playerPanel.addView(TextView(this).apply {
            text = stateLine; textSize = 12f; setTextColor(0xFFD8DEE9.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        })
        playerPanel.addView(TextView(this).apply {
            text = when {
                player.isPreparing -> "Buffering…"
                player.isPlaying -> "tap: pause · swipe: controls · double-tap: back"
                else -> "tap: play · swipe: controls · double-tap: back"
            }
            textSize = 11f; setTextColor(0xFF6E7A85.toInt()); gravity = Gravity.CENTER
        })
        if (ep.description.isNotBlank()) {
            playerPanel.addView(TextView(this).apply {
                text = ep.description.take(300); textSize = 11f
                setTextColor(0xFF8B949E.toInt()); gravity = Gravity.CENTER
                setPadding(dp(46), dp(10), dp(46), 0); maxLines = 4
            })
        }
    }

    // ---- SETTINGS --------------------------------------------------------------------

    private fun refreshSettings() {
        settingsList.removeAllViews()
        settingsList.addView(sectionTitle("⚙  Settings"))

        settingsList.addView(sectionTitle("🎤  Voice search (Groq Whisper)"))
        settingsList.addView(keyField("Groq API key", PodcastStore.K_GROQ_KEY))
        settingsList.addView(hint("Free key at console.groq.com — powers the keyboard 🎤 button. Stored only on the glasses, never shown once saved."))

        settingsList.addView(sectionTitle("⏯  Playback"))
        settingsList.addView(stepper("Skip back", store.getInt(PodcastStore.K_SKIP_BACK, 15), "s", 5, 60, 5) {
            store.putInt(PodcastStore.K_SKIP_BACK, it)
        })
        settingsList.addView(stepper("Skip forward", store.getInt(PodcastStore.K_SKIP_FWD, 30), "s", 5, 90, 5) {
            store.putInt(PodcastStore.K_SKIP_FWD, it)
        })
        settingsList.addView(bigButton(
            if (store.getInt(PodcastStore.K_AUTOPLAY, 1) == 1) "🔁 Autoplay next: on" else "🔁 Autoplay next: off") {
            store.putInt(PodcastStore.K_AUTOPLAY, 1 - store.getInt(PodcastStore.K_AUTOPLAY, 1))
            refreshSettings()
        })

        settingsList.addView(sectionTitle("💾  Storage"))
        val mb = store.downloadsBytes() / (1024 * 1024)
        settingsList.addView(hint("Downloaded episodes: $mb MB"))
        settingsList.addView(bigButton("🗑  Delete all downloads") {
            showConfirm("Delete all downloaded episodes? Streaming still works.", "Delete") {
                store.episodesDir.listFiles()?.forEach { it.delete() }
                refreshSettings(); flash("Downloads cleared")
            }
        })
        settingsList.addView(spacer())
        settingsList.addView(bigButton("← Back") { showHome() })
        rebuildHover(settingsList)
    }

    /** Masked key field, family policy: a saved secret is never displayed. */
    private fun keyField(label: String, key: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        row.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(0xFF8B949E.toInt()) })
        val saved = store.getString(key, "")
        val et = EditText(this).apply {
            textSize = 15f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF161B22.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
            hint = if (saved.isNotBlank()) "•••••••• saved · paste to replace" else "not set · paste with scrcpy (Ctrl+V)"
        }
        row.addView(et)
        row.addView(Button(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener {
                val v = et.text.toString().trim()
                if (v.isBlank()) { flash(if (saved.isBlank()) "Nothing pasted yet" else "Kept the saved key") }
                else { store.putString(key, v); et.setText(""); et.hint = "•••••••• saved · paste to replace"; flash("Key saved ✓") }
                et.clearFocus()
            }
        })
        return row
    }

    // ---- Keyboard (typed search) --------------------------------------------------------

    private fun startSearchInput() {
        inputBuffer = StringBuilder()
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(this).apply {
                setOnKeyboardActionListener(this@MainActivity)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
        updateInputPill()
    }

    private fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
        statusPill.visibility = View.GONE
    }

    private fun updateInputPill() { flash("Search: $inputBuffer▏  (🎤 = voice)", persist = true) }

    override fun onKeyPressed(key: String) { inputBuffer.append(key); updateInputPill() }
    override fun onBackspacePressed() { if (inputBuffer.isNotEmpty()) inputBuffer.deleteCharAt(inputBuffer.length - 1); updateInputPill() }
    override fun onEnterPressed() { val q = inputBuffer.toString(); hideKeyboard(); runSearch(q) }
    override fun onHideKeyboard() { hideKeyboard() }
    override fun onClearPressed() { inputBuffer.clear(); updateInputPill() }
    override fun onMoveCursorLeft() {}
    override fun onMoveCursorRight() {}
    override fun onMicrophonePressed() {
        if (recorder.isRecording) finishVoiceSearch() else startVoiceSearch()
    }

    // ---- HUD -------------------------------------------------------------------------

    private val hudTick = object : Runnable {
        override fun run() { refreshHud(); main.postDelayed(this, 30_000L) }
    }

    private fun refreshHud() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        val playingBit = player.episode?.let {
            (if (player.isPlaying) "▶ " else "⏸ ") + it.title.take(34)
        } ?: "Nothing playing"
        topHud.text = "$time · $batt%   ·   $playingBit"
    }

    // ---- Helpers -----------------------------------------------------------------------

    private fun flash(text: String, persist: Boolean = false) {
        statusPill.text = text
        statusPill.visibility = View.VISIBLE
        statusPill.bringToFront()
        main.removeCallbacks(hidePill)
        if (!persist) main.postDelayed(hidePill, 2_600L)
    }
    private val hidePill = Runnable { if (keyboardContainer.visibility != View.VISIBLE) statusPill.visibility = View.GONE }

    // Bounded pool for artwork disk-decodes/downloads. Synchronous decodes on
    // the UI thread were the "double-tap home takes 12 seconds" freeze: 179
    // tiles × ~60ms per cover decode, serialized on the main thread.
    private val artExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)

    private fun loadArt(url: String?, image: ImageView, initials: TextView) {
        if (url.isNullOrBlank()) return
        // Memory hit is free; anything touching disk or network goes off-thread.
        art.memCached(url)?.let { image.setImageBitmap(it); initials.visibility = View.GONE; return }
        artExecutor.execute {
            val bmp = art.cached(url) ?: art.loadOrFetch(url)
            if (bmp != null) main.post {
                image.setImageBitmap(bmp); initials.visibility = View.GONE
            }
        }
    }

    private fun ctlButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        stateListAnimator = null
        setOnClickListener { resetControlBarTimer(); onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun stepper(label: String, value: Int, unit: String, min: Int, max: Int, step: Int, onChange: (Int) -> Unit): View {
        var v = value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        val valueLabel = TextView(this).apply {
            text = "$label: $v$unit"; textSize = 14f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun btn(lbl: String, delta: Int) = Button(this).apply {
            text = lbl; isAllCaps = false; textSize = 14f
            minWidth = 0; minimumWidth = 0
            setOnClickListener {
                v = (v + delta).coerceIn(min, max)
                valueLabel.text = "$label: $v$unit"
                onChange(v)
            }
        }
        row.addView(valueLabel); row.addView(btn("−", -step)); row.addView(btn("＋", step))
        return row
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(30), dp(16), dp(30))
    }
    private fun scroll(child: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun match() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 17f; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(14), dp(2), dp(6))
    }
    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFF8B949E.toInt())
        setPadding(dp(2), dp(2), dp(2), dp(8))
    }
    /** Compact rounded header action (Search / Charts / Settings). */
    private fun pillButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f
        stateListAnimator = null
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setTextColor(0xFFD8DEE9.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1B2026.toInt()); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFF30363D.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener { onClick() }
    }
    private fun spacer(h: Int = dp(10)) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmtDate(ms: Long): String {
        if (ms <= 0L) return ""
        val days = (System.currentTimeMillis() - ms) / 86_400_000L
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 30L -> "${days}d ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))
        }
    }

    private companion object {
        const val SYSTEM_HOLD_MS = 450L
    }
}
