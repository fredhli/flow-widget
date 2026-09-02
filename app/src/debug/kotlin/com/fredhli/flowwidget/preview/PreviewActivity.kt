package com.fredhli.flowwidget.preview

import android.app.Activity
import android.app.UiModeManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import com.fredhli.flowwidget.FlowStore
import com.fredhli.flowwidget.FlowWidget
import com.fredhli.flowwidget.GlassSurface
import com.fredhli.flowwidget.WidgetSettings
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug-only screenshot harness: seeds this build's DataStore with a named fixture,
 * composes the REAL FlowWidget (GlanceAppWidget.compose — no widget binding, no launcher
 * placement) at an exact responsive bucket size, and applies the resulting RemoteViews
 * into an [AppWidgetHostView] — the same view class a launcher hosts widgets in, and the
 * root parent the platform requires before it will honour setRemoteAdapter(collection
 * items), i.e. the LazyColumn body.
 *
 * The store it writes is the DEBUG build's own: `applicationIdSuffix = ".debug"` keeps this
 * variant in a separate package, so seeding a fixture can never overwrite the base URL and
 * token of a shipped widget sitting on the same device. Class names are not suffixed, so
 * the component below is `<applicationId>/<class>` spelled out in full.
 *
 * Driven by adb (see flow-widget-support/widget-shots.sh):
 *
 *   am start -n com.fredhli.flowwidget.debug/com.fredhli.flowwidget.preview.PreviewActivity \
 *       --es state normal --es size 4x2 [--el now <epochMs>] [--ez dark true]
 *
 * Extras:
 *   state  unconfigured|loading|normal|offline|stale|unread|refreshing|empty|unreachable
 *          |short  (a one-line-title batch — the compact bucket's row-height probe)
 *   size   4x2 (250x110dp bucket) | 4x3 (250x180dp bucket)
 *          | fold (397x399dp — the Fold 8 cover cell measured off the round-3 reference
 *          shots; on the 420dpi foldcover AVD this frames 1042x1048px, the reference
 *          widget box to the pixel) | foldwide (490x493dp, the 5-column grid variant)
 *   font   default|medium|serif — the round-3 "Title font" dropdown (absent = default)
 *   tap    dashboard|expand — the round-3 "Tap on an item" mode (absent = dashboard)
 *   link   dashboard|chrome — the round-3 "Open links with" choice (absent = dashboard;
 *          only OpenItemActivity reads it, so it changes no pixel — seeded for tap tests)
 *   expand <itemId> — pre-expand one row the way a real expand-mode tap would
 *          (marks it read too), so the expanded state can be shot without a touch
 *   now    epoch ms the fixture offsets hang off (default: current time — offsets are
 *          fixed, so the rendered text is stable either way)
 *   dark   best-effort per-app night mode; the script prefers `cmd uimode night yes|no`
 *   opacity  --ef opacity 0.50 — the LIGHT glass opacity, so the ladder can be shot
 *          without driving the real config screen through uiautomator. Absent = the
 *          default, which is what a gallery run wants.
 *   opacity_dark  --ef opacity_dark 0.30 — the DARK glass opacity (its own slider and
 *          range since the device-feedback round; 0.30 is the floor Fred asked for).
 *   backdrop --es backdrop D7D2D9 — the colour behind the widget, RRGGBB.
 *
 * `backdrop` exists because its default hid a real defect for a whole round. A translucent
 * surface is only as legible as what is behind it, and the harness's two default grounds
 * are the most flattering ones available: a near-black for dark frames and a pale grey for
 * light. Against those, a dark surface at any opacity looks fine. Against a bright
 * wallpaper — `design/reference-user-homescreen.jpg` measures mean RGB (215, 210, 217),
 * i.e. `D7D2D9` — dark mode at the then-documented 0.50 floor drops the meta line to
 * 2.5:1, and no frame in the gallery could show it. So shoot the ladder against a bright
 * ground, not the flattering one:
 *
 *   --es state normal --es size 4x3 --ef opacity 0.50 --es backdrop D7D2D9
 *
 * When the widget has been composed, laid out and drawn, one log line is emitted:
 *
 *   I/FlowPreview: READY state=<s> size=<sz> theme=<light|dark> bounds=<x>,<y>,<w>,<h>
 *
 * bounds are absolute screen pixels of the widget frame — the crop box for screencap.
 * On any failure it logs "FAILED ..." instead; the script times out and says so.
 */
class PreviewActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide() // no DayNight+NoActionBar framework theme exists; hide it here
        // Screenshots must work on a freshly-booted, still-locked emulator.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val state = intent.getStringExtra("state") ?: PreviewFixtures.STATE_NORMAL
        val sizeSpec = intent.getStringExtra("size") ?: "4x2"
        val nowMs = intent.getLongExtra("now", System.currentTimeMillis())

        if (intent.hasExtra("dark")) {
            // Best-effort; may recreate the activity, in which case the second onCreate
            // renders under the right configuration and emits the READY line.
            val mode = if (intent.getBooleanExtra("dark", false))
                UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
        }

        val size: DpSize = when (sizeSpec) {
            "4x3" -> FlowWidget.TALL
            "4x2" -> FlowWidget.COMPACT
            // The Fold 8 cover cells, measured off design/round3/fold-cover-110pct-*.jpg
            // at 2.625 px/dp: on this 420dpi 1248x1972 AVD, `fold` renders the widget at
            // 1042x1048 px — the reference shots' widget box to the pixel, so a gallery
            // frame lays over the phone screenshot 1:1. `foldwide` is the 5-column grid
            // variant (same px box on the phone, more dp, smaller render). Both exceed
            // FlowWidget.FOLD's 340dp width threshold, which is the point.
            "fold" -> DpSize(397.dp, 399.dp)
            "foldwide" -> DpSize(490.dp, 493.dp)
            else -> {
                Log.e(TAG, "FAILED unknown size '$sizeSpec' (want 4x2|4x3|fold|foldwide)")
                finish(); return
            }
        }

        scope.launch {
            try {
                render(state, sizeSpec, size, nowMs)
            } catch (t: Throwable) {
                Log.e(TAG, "FAILED state=$state size=$sizeSpec: $t", t)
                showNote("Preview failed: $t")
            }
        }
    }

    @OptIn(ExperimentalGlanceApi::class)
    private suspend fun render(state: String, sizeSpec: String, size: DpSize, nowMs: Long) {
        // 1. Seed the DataStore this build's widget reads — the debug package's own, never
        //    the shipped widget's. Every field is written, so no residue from the previous
        //    shot survives. FlowStore's own API is enough:
        //    saveConfig("","") is the unconfigured state, an unparseable feed body is
        //    the no-feed state, markFetchFailed() is the offline mark.
        val seed = PreviewFixtures.seedFor(state, nowMs, ZoneId.systemDefault())
        val store = FlowStore.get(applicationContext)
        store.saveConfig(seed.baseUrl, seed.token)
        store.saveFeed(seed.feedJson) // also sets fetch_ok = true
        if (!seed.fetchOk) store.markFetchFailed()
        store.recordOpen(nowMs - seed.lastOpenAgeMin * 60_000L)
        // Every seeding writes both opacities too, so a ladder run cannot leak its
        // setting into the next gallery run: absent extra = the shipped default, not
        // "whatever the previous shot left in the DataStore". Two extras since the
        // device-feedback round split the slider per theme.
        store.saveOpacity(
            intent.getFloatExtra(EXTRA_OPACITY, GlassSurface.DEFAULT_OPACITY),
            intent.getFloatExtra(EXTRA_OPACITY_DARK, GlassSurface.DEFAULT_OPACITY_DARK),
        )
        // The round-3 settings, seeded on every shot for the same no-residue reason:
        // absent extras = the shipped defaults, not whatever the last run left behind.
        //   --es font default|medium|serif   --es tap dashboard|expand
        //   --es link dashboard|chrome       --es expand <itemId>
        // `expand` pre-opens one row (and, like the real tap, marks it read) so a
        // gallery can shoot the expanded state without driving a touch.
        store.saveSettings(
            WidgetSettings.titleFont(intent.getStringExtra(EXTRA_FONT)),
            WidgetSettings.tapMode(intent.getStringExtra(EXTRA_TAP)),
            WidgetSettings.linkApp(intent.getStringExtra(EXTRA_LINK)),
        )
        store.clearExpandState() // a previous shot's expanded row must not leak in
        val expandId = intent.getStringExtra(EXTRA_EXPAND)
        if (expandId != null) store.toggleExpanded(expandId)

        // 2. Compose the real widget at the exact bucket size. compose() runs the real
        //    provideGlance (fresh DataStore snapshot included) against a fake appwidget
        //    id — no AppWidgetHost binding, no grantbind, no launcher.
        val remoteViews = FlowWidget().compose(context = this, size = size)

        // 3. Apply into an AppWidgetHostView of exactly that dp size.
        val density = resources.displayMetrics.density
        val wPx = (size.width.value * density).roundToInt()
        val hPx = (size.height.value * density).roundToInt()

        val host = AppWidgetHostView(this)
        host.updateAppWidget(remoteViews)

        val dark = isNightMode()
        val root = FrameLayout(this).apply {
            setBackgroundColor(backdropColor(dark))
            addView(host, FrameLayout.LayoutParams(wPx, hPx, Gravity.CENTER))
            addView(label("$state · $sizeSpec · ${themeName(dark)}"), labelParams())
        }
        setContentView(root)

        // 4. Announce readiness only after the frame is actually laid out on screen.
        host.post {
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            // The scrollbar probe (device feedback #5). The fix is a resource-merge
            // override of glance-appwidget's empty Glance.AppWidget.List style, applied
            // when the host inflates the list — so the honest check is the inflated
            // view's own state, not the style file. The emulator's AOSP skin draws no
            // visible bar either way (One UI does, which is where Fred saw it), so a
            // screenshot cannot prove this; the log line can, per frame.
            findListView(host)?.let {
                Log.i(TAG, "LIST scrollbars: vertical=${it.isVerticalScrollBarEnabled}")
            }
            Log.i(
                TAG,
                "READY state=$state size=$sizeSpec theme=${themeName(dark)} " +
                    "bounds=${loc[0]},${loc[1]},$wPx,$hPx"
            )
        }
    }

    /**
     * The ground the translucent widget is composited onto. `--es backdrop RRGGBB`
     * overrides; the defaults are the harness's own flat greys, which are the *kindest*
     * grounds a translucent surface can have and are therefore the wrong ones to judge
     * legibility against. Anything unparseable falls back rather than failing the shot.
     */
    private fun backdropColor(dark: Boolean): Int {
        val raw = intent.getStringExtra(EXTRA_BACKDROP)?.removePrefix("#")
        if (raw != null) {
            val parsed = raw.toLongOrNull(16)
            if (parsed != null && raw.length == 6) return (0xFF000000L or parsed).toInt()
            Log.w(TAG, "backdrop '$raw' is not RRGGBB — using the default ground")
        }
        return if (dark) 0xFF17181C.toInt() else 0xFFE9E7EF.toInt()
    }

    /** Depth-first search for the RemoteViews-inflated list, if this state has one. */
    private fun findListView(root: android.view.View): android.widget.AbsListView? {
        if (root is android.widget.AbsListView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findListView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun themeName(dark: Boolean) = if (dark) "dark" else "light"

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(if (isNightMode()) Color.LTGRAY else Color.DKGRAY)
    }

    private fun labelParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { bottomMargin = (24 * resources.displayMetrics.density).roundToInt() }

    private fun showNote(msg: String) {
        setContentView(TextView(this).apply { text = msg; setPadding(48, 48, 48, 48) })
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        /** The tag widget-shots.sh greps for. */
        const val TAG = "FlowPreview"
        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_OPACITY_DARK = "opacity_dark"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_FONT = "font"
        const val EXTRA_TAP = "tap"
        const val EXTRA_LINK = "link"
        const val EXTRA_EXPAND = "expand"
    }
}
