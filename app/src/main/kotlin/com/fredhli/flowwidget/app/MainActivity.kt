package com.fredhli.flowwidget.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets as GraphicsInsets
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fredhli.flowwidget.BuildConfig
import com.fredhli.flowwidget.ConfigActivity
import com.fredhli.flowwidget.FlowConfig
import com.fredhli.flowwidget.FlowStore
import com.fredhli.flowwidget.R
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The Dashboard app: one WebView, edge-to-edge, showing the dashboard at the stored base
 * URL with the stored token seeded as the `dash_session` cookie (spec §5, §6).
 *
 * Shape of the thing:
 *  - `singleTask` in the manifest, so widget taps, shortcuts and App Links arrive through
 *    [onNewIntent] while the page is alive; [handleIntent] turns each into a `pendingUrl`
 *    and [applyPending] decides cold (loadUrl) / warm (wait for the load in flight) / hot
 *    (push the route into the running page) — the state machine of spec §6.
 *  - Config and prefs are observed from the DataStore, not read once: the WebView cannot
 *    exist before the base URL is known (the bridge is origin-scoped), so the WebView is
 *    built on the first emission that carries a config and any intent that arrived before
 *    that is stashed and replayed. Unconfigured → the error panel doubles as a "set up"
 *    prompt; the emission that follows ConfigActivity's save builds the WebView.
 *  - Insets are never consumed (spec §5): the page pads itself with env(safe-area-inset-*);
 *    the shell only pads for the keyboard on WebViews too old to do it themselves, and
 *    writes `--safe-*` inline when the page's env() turns out to be zero on this device.
 *  - The WebView is created in code and can be thrown away and rebuilt (renderer crash,
 *    base-URL origin change) without touching the rest of the activity.
 *
 * The token is a secret: it goes into one CookieManager.setCookie call and nowhere else —
 * not into a log line, a toast, an exception, a Bundle, or the diagnostics text. URLs are
 * never logged either (an App Link may carry `?k=<token>`).
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** String extra: a dashboard route such as "#/flow/i/abc" (normalised by Routes). */
        const val EXTRA_ROUTE = "com.fredhli.flowwidget.EXTRA_ROUTE"

        /** Boolean extra: run the diagnostics dialog once the page is READY. */
        const val EXTRA_DIAGNOSTICS = "com.fredhli.flowwidget.EXTRA_DIAGNOSTICS"

        /** The splash never outlives this, page or no page. */
        private const val SPLASH_MAX_MS = 3000L

        /**
         * A load() whose navigation has not even STARTED after this long is treated as one
         * that never will (see [loadWatchdog]). Generous on purpose: a slow network still
         * fires onPageStarted within a second or two, because that callback marks the start
         * of the request, not the arrival of the response.
         */
        private const val LOAD_WATCHDOG_MS = 10_000L

        /** Saved-state key: the last app-origin URL, query stripped, restored after process death. */
        private const val STATE_LAST_URL = "lastUrl"

        /**
         * Explicit intent to MainActivity with EXTRA_ROUTE and FLAG_ACTIVITY_NEW_TASK.
         * Callers: OpenItemActivity (App target), shortcuts by hand, AppSettingsActivity.
         * NEW_TASK because the callers are trampolines and settings screens whose own task
         * must never become the app's.
         */
        fun routeIntent(context: Context, route: String?): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, Routes.normaliseRoute(route))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun diagnosticsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DIAGNOSTICS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    enum class PageState { NONE, LOADING, READY, ERROR }

    // ---- views (activity_main.xml) -------------------------------------------------------
    private lateinit var webContainer: FrameLayout
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var errorRetry: Button
    private lateinit var errorSettings: Button

    // ---- what Bridge / DashboardWebView read ---------------------------------------------
    /** The live WebView, or null before the first config / between crash and rebuild. */
    internal var webView: WebView? = null
        private set

    /** Routes.appOrigins(baseUrl); the shipped hosts alone until a config is known. */
    internal var appOrigins: Set<String> = Routes.appOrigins(FlowStore.DEFAULT_BASE_URL)
        private set

    /** Latest ShellPrefs from the store; linkPolicy is read from here at click time. */
    internal var prefs: ShellPrefs = ShellPrefs()
        private set

    internal lateinit var popupCatcher: PopupCatcher
        private set

    private val bridge = Bridge(this)

    // ---- page state machine (spec §6) ----------------------------------------------------
    private var config: FlowConfig? = null
    private var state = PageState.NONE

    /** Full URL waiting to be shown; survives until onPageFinished on its origin confirms it. */
    private var pendingUrl: String? = null
    private var pendingDiagnostics = false

    /**
     * The diagnostics dialog while one is up. Owned here because an AlertDialog is a window
     * on this activity, and one still showing when the activity is destroyed (a config
     * change outside the manifest's list, the task swiped away) leaks it; onDestroy takes
     * it down.
     */
    private var diagnosticsDialog: AlertDialog? = null

    /** Last app-origin main-frame URL started or visited — what Retry and a crash reload. */
    private var lastUrl: String? = null

    /** Main-frame URL whose load failed; onPageFinished for it means "error page shown". */
    private var errorUrl: String? = null

    /**
     * The URL the current load() asked for, and whether Chromium has reported a navigation
     * for it. Together they make LOADING non-terminal: a main-frame request that turns
     * into a download (Content-Disposition: attachment, a PDF under a path the classifier
     * did not recognise as a document) never reaches onPageStarted / onPageFinished /
     * onReceivedError, so without this the state machine would sit in LOADING forever —
     * every later warm intent parked behind a load that ended in the DownloadListener.
     * [onDownloadStarted] is the deterministic way back; [loadWatchdog] the backstop.
     */
    private var loadingUrl: String? = null
    private var navigationStarted = false

    /** Retry-once guard for the origin-mismatch reload in [onPageFinished]. */
    private var mismatchReloads = 0

    /** Intent that arrived before the WebView existed, and whether its extras are stale. */
    private var deferredIntent: Intent? = null
    private var deferredIntentStale = false

    /** lastUrl from a previous incarnation (process death), applied once. */
    private var restoredUrl: String? = null

    // ---- splash ---------------------------------------------------------------------------
    private var keepSplash = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseSplash = Runnable { keepSplash = false }

    /**
     * Backstop for a load() Chromium never reported on (see [loadingUrl]). Posted by every
     * load(), cancelled by onPageStarted (the navigation exists — from there on the
     * WebViewClient reports how it ends), onPageFinished, showError, onDestroy and the
     * next load(). When it fires with the navigation still unstarted, the target is given
     * up on and the page goes back to where it was (or home) — never to NONE, which would
     * only wait for the next onResume.
     */
    private val loadWatchdog = Runnable {
        if (state != PageState.LOADING || navigationStarted) return@Runnable
        val abandoned = loadingUrl
        pendingUrl = null
        loadingUrl = null
        val current = webView ?: return@Runnable
        val fallback = recoveryUrl(abandoned) ?: return@Runnable
        if (fallback == abandoned) {
            // The fallback itself is what never started: nothing left to try. The panel's
            // Retry re-runs load() from lastUrl / home.
            showError(getString(R.string.shell_load_stalled), abandoned)
            return@Runnable
        }
        load(current, fallback)
    }

    /**
     * Bar icon appearance as the page last reported it through Native.themeColor (true =
     * light bars, dark icons), or null before the first report. Kept because
     * enableEdgeToEdge() — run again on every handled uiMode change — re-derives the flags
     * from the system night state, and the page only re-reports on boot, on its own theme
     * cycle and on a prefers-color-scheme change: a page pinned to dark under a system
     * that just flipped to light would otherwise get dark icons on a dark bar until the
     * next report. Cleared when a new document starts (load / WebView replacement): that
     * document reports its own colour on boot, and until then the system-derived value is
     * the better guess for the shell surfaces (error panel, blank WebView) it paints over.
     */
    private var pageBarsLight: Boolean? = null

    // ---- insets (spec §5) -----------------------------------------------------------------
    private var imeMode = Insets.ImeMode.NATIVE
    private var lastBars: GraphicsInsets = GraphicsInsets.NONE
    private var lastIme: GraphicsInsets = GraphicsInsets.NONE
    private var safeVarFallback = false

    /**
     * Run the env() probe on the next READY. Set by load() AND by every main-frame
     * onPageStarted on an app origin: a document can start without load() — the server's
     * 401 → /login redirect and the sign-in that follows, an in-app link the WebView
     * navigates itself, a captured popup routed through loadUrl, pushRoute's location.href
     * for a path change — and each new document starts with a clean <html style>, so the
     * `--safe-*` fallback decided for the previous one has to be re-probed and re-pushed.
     */
    private var envProbePending = false

    /**
     * Back walks the WebView's history (hash routes included) while there is any; when
     * there is none the callback is disabled and the system's predictive back closes the
     * app. A dispatcher callback, never an onBackPressed override: overriding it disables
     * predictive back for the whole activity at targetSdk 36.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            webView?.goBack()
        }
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate: the splash theme is swapped for postSplashScreenTheme
        // here, and the keep-on-screen condition holds the first frame until the page paints.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { keepSplash }
        mainHandler.postDelayed(releaseSplash, SPLASH_MAX_MS)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        webContainer = findViewById(R.id.web_container)
        errorPanel = findViewById(R.id.error_panel)
        errorTitle = findViewById(R.id.error_title)
        errorText = findViewById(R.id.error_text)
        errorRetry = findViewById(R.id.error_retry)
        errorSettings = findViewById(R.id.error_settings)
        errorSettings.setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        // Who owns the keyboard inset is decided once per process from the WebView provider
        // version — the provider updates itself through Play, so this is not a constant.
        imeMode = Insets.imeModeFor(WebViewCompat.getCurrentWebViewPackage(this)?.versionName)
        installInsetsListener()

        onBackPressedDispatcher.addCallback(this, backCallback)

        popupCatcher = PopupCatcher(this) { url ->
            when (val nav = Links.classify(url, appOrigins)) {
                Links.Nav.IN_APP -> webView?.loadUrl(url)
                else -> Links.leave(this, url, prefs.linkPolicy, nav)
            }
        }

        // Recreated by the system (process death, or a config change outside the manifest's
        // list): the intent's extras were consumed by the previous incarnation, so the
        // route to restore is the one it saved, not the one the launcher still carries.
        restoredUrl = savedInstanceState?.getString(STATE_LAST_URL)
        handleIntent(intent, stale = savedInstanceState != null)

        // Fetched documents older than a day go; off the main thread, and the result does
        // not matter to anyone.
        Thread({ Files.pruneCache(this) }, "shell-prune").apply { isDaemon = true }.start()

        // Config + prefs, reactively: the first emission builds the WebView (or shows the
        // set-up prompt); later ones re-seed the cookie, rebuild on an origin change, and
        // re-apply text zoom. A DataStore read failure counts as "not configured".
        lifecycleScope.launch {
            FlowStore.get(this@MainActivity).data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .collect { onPrefs(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, stale = false)
    }

    override fun onResume() {
        super.onResume()
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
        // Nothing loaded yet but everything needed is here: the store emitted while the
        // unconfigured panel was up and no intent was pending (pendingUrl == null), or the
        // renderer died while the activity was stopped and onRendererGone parked its
        // target here instead of loading into a WebView nobody could see (pendingUrl set).
        val cfg = config
        if (webView != null && cfg != null && state == PageState.NONE) {
            if (pendingUrl == null) pendingUrl = homeUrl(cfg)
            applyPending()
        }
    }

    override fun onPause() {
        webView?.let {
            it.onPause()
            it.pauseTimers()
        }
        // The cookie jar is written lazily; persist it before the process can be killed
        // so the next cold start has the session without waiting on the store.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Query stripped: a Bundle is not a log, but a URL with ?k= has no business in one.
        outState.putString(STATE_LAST_URL, lastUrl?.let { Routes.stripQuery(it) })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode flipped: re-derive the bar icon colours from the new night state — then
        // put the page's own report back on top. enableEdgeToEdge() only knows the system
        // theme, and the page does NOT re-report on a fold or rotation (its listener is on
        // prefers-color-scheme, which an unfold does not change): a page pinned to the
        // opposite of the system theme would keep the wrong icons until its next boot.
        enableEdgeToEdge()
        pageBarsLight?.let { applyBarAppearance(it) }
        webView?.let { DashboardWebView.applyTextZoom(it, prefs.textZoom, newConfig.fontScale) }
        // Resources resolved at inflate time do not follow a handled uiMode change on
        // their own; the two surfaces that could show a light colour on a dark page are
        // re-resolved by hand. The WebView keeps its page — that is the point of handling
        // the change here instead of being recreated.
        window.setBackgroundDrawableResource(R.color.shell_background)
        errorPanel.setBackgroundResource(R.color.shell_background)
        webView?.setBackgroundColor(getColor(R.color.shell_background))
        val ta = obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary),
        )
        val primary: ColorStateList?
        try {
            primary = ta.getColorStateList(0)
            primary?.let { errorTitle.setTextColor(it) }
            ta.getColorStateList(1)?.let { errorText.setTextColor(it) }
        } finally {
            ta.recycle()
        }
        // The two buttons likewise: their background and text colour were resolved from
        // ?attr/buttonStyle at inflate time and stay at the old night state — a light
        // button with light text on the re-resolved dark panel. Re-resolve the style's
        // background and textColor for the new configuration; getDrawable hands out a
        // fresh Drawable per call, which matters because a Drawable can have one owner.
        // A style that colours its text through textAppearance alone (Material's does)
        // answers null for textColor, in which case the theme's textColorPrimary — what
        // that textAppearance resolves to — is used. (Attribute ids in ascending order, as
        // obtainStyledAttributes documents.)
        val btnTa = obtainStyledAttributes(
            null,
            intArrayOf(android.R.attr.textColor, android.R.attr.background),
            android.R.attr.buttonStyle,
            0,
        )
        try {
            val textColor = btnTa.getColorStateList(0) ?: primary
            for (b in arrayOf(errorRetry, errorSettings)) {
                btnTa.getDrawable(1)?.let { b.background = it }
                textColor?.let { b.setTextColor(it) }
            }
        } finally {
            btnTa.recycle()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(releaseSplash)
        mainHandler.removeCallbacks(loadWatchdog)
        // A dialog still up is a window on this activity; take it down before the
        // activity goes, or the framework logs a leaked window and keeps the view tree.
        diagnosticsDialog?.dismiss()
        diagnosticsDialog = null
        if (::popupCatcher.isInitialized) popupCatcher.destroy()
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    /**
     * Light (true) or dark (false) status + navigation bar icons, as the page reports its
     * surface colour (Bridge, Native.themeColor). The value is remembered so a later
     * enableEdgeToEdge() — every handled uiMode change runs one — can be overridden again
     * with the page's answer rather than the system theme's guess.
     */
    internal fun applyBarAppearance(light: Boolean) {
        pageBarsLight = light
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    // =========================================================================================
    // Config / prefs
    // =========================================================================================

    private fun onPrefs(p: Preferences) {
        val newConfig = FlowStore.configFrom(p)
        val newPrefs = FlowStore.shellPrefsFrom(p)
        val oldPrefs = prefs
        val oldConfig = config
        prefs = newPrefs
        config = newConfig

        if (newConfig == null) {
            showUnconfigured()
            return
        }
        appOrigins = Routes.appOrigins(newConfig.baseUrl)

        val current = webView
        when {
            current == null -> {
                // First config: the WebView can exist now. Replay whatever intent got here
                // first (the launcher's, a widget tap's) against it.
                val fresh = attachFreshWebView()
                seedCookie(fresh)
                hidePanel()
                val stashed = deferredIntent
                val stale = deferredIntentStale
                deferredIntent = null
                deferredIntentStale = false
                handleIntent(stashed, stale)
            }
            oldConfig != newConfig -> {
                if (Routes.originOf(oldConfig?.baseUrl) != Routes.originOf(newConfig.baseUrl)) {
                    // The bridge's origin rules, the cookie and the history all belong to
                    // the old origin: start over rather than patch three things.
                    val fresh = replaceWebView()
                    seedCookie(fresh)
                    hidePanel()
                    lastUrl = null
                    pendingUrl = homeUrl(newConfig)
                    applyPending()
                } else {
                    // Same origin, new token (or a cosmetic base URL edit): the next
                    // request carries the new cookie; the page decides what to do with it.
                    seedCookie(current)
                }
            }
        }
        if (newPrefs.textZoom != oldPrefs.textZoom) {
            webView?.let { DashboardWebView.applyTextZoom(it, newPrefs.textZoom, resources.configuration.fontScale) }
        }
    }

    /**
     * Cookie seeding (spec §6). Before every app-origin loadUrl and on every config change.
     * HttpOnly so page script cannot read it; Secure only on https (a private http base
     * URL would otherwise never receive it); 90 days like the server's own cookie.
     */
    private fun seedCookie(target: WebView) {
        val cfg = config ?: return
        val origin = Routes.originOf(cfg.baseUrl) ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(target, false)
        val secure = if (origin.startsWith("https://")) "; Secure" else ""
        cm.setCookie(origin, "dash_session=" + cfg.token + "; Path=/; HttpOnly; Max-Age=7776000" + secure)
        cm.flush()
    }

    private fun homeUrl(cfg: FlowConfig? = config): String? =
        cfg?.let { it.baseUrl.trim().trimEnd('/') + "/" }

    // =========================================================================================
    // WebView creation / replacement
    // =========================================================================================

    private fun attachFreshWebView(): WebView {
        val fresh = DashboardWebView.create(this)
        webContainer.addView(
            fresh,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // Before the first loadUrl: the listener only covers navigations started after it.
        bridge.install(fresh, appOrigins)
        webView = fresh
        state = PageState.NONE
        errorUrl = null
        // A WebView is born "resumed"; the activity may not be. Mirror the activity's
        // state onto it, because onResume/onPause only touch the WebView that exists
        // when they run: one created while the activity is stopped (a renderer crash
        // in the background) would otherwise run its timers and JS behind a stopped
        // activity — and, worse, a WebView created while paused and never paused stays
        // out of step with the next onResume's resumeTimers, which is process-global.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            fresh.onResume()
            fresh.resumeTimers()
        } else {
            fresh.onPause()
            fresh.pauseTimers()
        }
        refreshBack()
        return fresh
    }

    private fun replaceWebView(): WebView {
        val old = webView
        webView = null
        if (old != null) {
            webContainer.removeView(old)
            old.destroy()
        }
        // No document any more, so no page-reported bar colour either (see pageBarsLight).
        pageBarsLight = null
        return attachFreshWebView()
    }

    /**
     * onRenderProcessGone: the crashed view is unusable; rebuild and reload where we were.
     * The rebuild happens whatever the activity's state (a crashed WebView must go), but
     * the reload only when the activity is at least STARTED: Chromium may kill a
     * background renderer to reclaim memory precisely because the app is not visible, and
     * loading straight into a fresh WebView behind a stopped activity would spend the
     * network and the renderer on a page nobody sees — and with the page paused (see
     * attachFreshWebView) it may not even finish. Parked in pendingUrl instead, which
     * onResume turns into the load (state is NONE after the rebuild).
     */
    internal fun onRendererGone(view: WebView) {
        if (view !== webView) return // a view already replaced; nothing left to do for it
        val fresh = replaceWebView()
        seedCookie(fresh)
        val target = pendingUrl ?: lastUrl ?: homeUrl()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingUrl = target
            return
        }
        hidePanel()
        Toast.makeText(this, R.string.shell_renderer_crashed, Toast.LENGTH_SHORT).show()
        load(fresh, target ?: return)
    }

    // =========================================================================================
    // Intents → pendingUrl → cold / warm / hot (spec §6)
    // =========================================================================================

    /**
     * @param stale true when the intent's extras were already consumed by a previous
     *   incarnation (recreated with saved state). FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY says
     *   the same thing about a relaunch from Recents: the launcher re-sends the root intent.
     */
    private fun handleIntent(intent: Intent?, stale: Boolean) {
        val cfg = config
        if (cfg == null || webView == null) {
            deferredIntent = intent
            deferredIntentStale = stale
            return
        }
        val fromHistory = stale ||
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val before = pendingUrl
        if (!fromHistory) {
            if (intent?.getBooleanExtra(EXTRA_DIAGNOSTICS, false) == true) pendingDiagnostics = true
            val route = intent?.getStringExtra(EXTRA_ROUTE)
            val data = intent?.data
            when {
                route != null -> pendingUrl = Routes.pageUrl(cfg.baseUrl, route)
                intent?.action == Intent.ACTION_VIEW && data != null -> {
                    // The same gate every tap goes through (spec §6): only an IN_APP URL
                    // becomes the page. An app-origin /api/ URL is a DOCUMENT — the App
                    // Links filter matches it too (the manifest cannot exclude a path
                    // prefix), and loading it would put the WebView on a PDF or a raw
                    // brief with the shell stuck in LOADING; Links.leave fetches and
                    // opens it like a tap would. Anything else (an off-origin URL from a
                    // caller addressing the component directly, an intent: URL) goes to
                    // its handler the same way, and the shell keeps showing a page below.
                    val target = data.toString()
                    when (val nav = Links.classify(target, appOrigins)) {
                        Links.Nav.IN_APP -> pendingUrl = target
                        else -> Links.leave(this, target, prefs.linkPolicy, nav)
                    }
                }
            }
        }
        if (pendingUrl == null && state == PageState.NONE) {
            // Cold start with no target: where we were last time, else the home URL — the
            // page picks its own last route from there (spec §6, "Launcher").
            pendingUrl = restoredUrl?.takeIf { Routes.isAppOrigin(it, appOrigins) } ?: homeUrl(cfg)
        }
        restoredUrl = null
        // A new target gets its own one-shot origin-mismatch retry (onPageFinished).
        if (pendingUrl != before) mismatchReloads = 0
        applyPending()
    }

    private fun applyPending() {
        val current = webView ?: return
        val target = pendingUrl
        if (target == null) {
            maybeRunDiagnostics()
            return
        }
        when (state) {
            PageState.READY -> {
                if (Routes.originOf(current.url) == Routes.originOf(target)) {
                    pendingUrl = null
                    pushRoute(current, target)
                    maybeRunDiagnostics()
                } else {
                    load(current, target)
                }
            }
            PageState.LOADING -> Unit // warm: onPageFinished applies it
            PageState.NONE, PageState.ERROR -> load(current, target)
        }
    }

    /** Cold path. pendingUrl (if any) stays set until onPageFinished on this origin confirms it. */
    private fun load(target: WebView, url: String) {
        hidePanel()
        errorUrl = null
        envProbePending = true
        // A new document: its own boot reports the bar colour (see pageBarsLight).
        pageBarsLight = null
        if (Routes.isAppOrigin(url, appOrigins)) seedCookie(target)
        state = PageState.LOADING
        loadingUrl = url
        navigationStarted = false
        mainHandler.removeCallbacks(loadWatchdog)
        mainHandler.postDelayed(loadWatchdog, LOAD_WATCHDOG_MS)
        target.loadUrl(url)
    }

    /**
     * Where to go when a load() has to be abandoned (its response was a download, or it
     * never started): the last page, unless the last page IS the abandoned URL (a document
     * URL that reached onPageStarted before the DownloadListener took it), else home.
     */
    private fun recoveryUrl(abandoned: String?): String? =
        lastUrl?.takeIf { Routes.stripFragment(it) != Routes.stripFragment(abandoned) } ?: homeUrl()

    /**
     * The WebView's DownloadListener fired (via DashboardWebView). In a READY page that is
     * an `<a download>` or an attachment response and none of the shell's business. While
     * LOADING it is, in practice, the fate of the navigation in flight — a main-frame
     * response Chromium turned into a download (Content-Disposition: attachment, a PDF
     * under a path the classifier did not call a document, a 302 from a page URL onto
     * one), about which it reports nothing more: no onPageFinished, no error. The file
     * has gone to Files through the listener already; here the state machine is put back
     * on a page, or it would wait in LOADING for ever with every later intent parked.
     *
     * "Which page" comes from the WebView itself rather than from URL matching, because a
     * redirect chain ends on a URL nobody asked for: `getUrl()` is the visible URL — the
     * committed document for a renderer-initiated navigation (a tap), the pending one for
     * loadUrl — so an app-origin URL there that is not the download is a page that is
     * shown, or is still on its way and will confirm itself through onPageFinished
     * (setting READY early for it is harmless; a wrong reload would not be). No such
     * page: the pending intent's URL if it is a different one, else the last page, else
     * home — and never the URL whose load produced this download, which would loop.
     */
    internal fun onDownloadStarted(view: WebView, url: String) {
        if (view !== webView || state != PageState.LOADING) return
        val loading = loadingUrl
        mainHandler.removeCallbacks(loadWatchdog)
        loadingUrl = null
        // A pending target that IS the download is done with (pushing it into a page
        // would only fetch the file a second time); any other pending target stands.
        val next = pendingUrl?.takeIf { Routes.stripFragment(it) != Routes.stripFragment(url) }
        pendingUrl = next
        val shown = view.url?.takeIf {
            Routes.isAppOrigin(it, appOrigins) && Routes.stripFragment(it) != Routes.stripFragment(url)
        }
        if (shown != null) {
            state = PageState.READY
            keepSplash = false
            hidePanel()
            refreshBack()
            applyPending()
            return
        }
        if (next != null) {
            load(view, next)
            return
        }
        val fallback = recoveryUrl(url)
        if (fallback == null || fallback == loading) {
            showError(getString(R.string.shell_load_stalled), url)
            return
        }
        load(view, fallback)
    }

    /**
     * Hot path: hand the running page the target. A routed URL goes through
     * window.DashboardApp.route (the page's own hash router), falling back to a plain hash
     * assignment; a route-less URL is a same-origin reload, and only when its path differs.
     */
    private fun pushRoute(target: WebView, url: String) {
        val route = Routes.routeOf(url)
        if (route != null) {
            target.evaluateJavascript(routeJs(url, route), null)
        } else if (Routes.pathOf(target.url) != Routes.pathOf(url)) {
            target.evaluateJavascript("location.href=" + Routes.jsStringLiteral(url), null)
        }
    }

    /** Spec §3, "Route push". */
    private fun routeJs(pageUrl: String, route: String): String =
        "(function(u,h){try{if(window.DashboardApp&&window.DashboardApp.route(u))return;}catch(e){}location.hash=h;})(" +
            Routes.jsStringLiteral(pageUrl) + "," + Routes.jsStringLiteral(route) + ")"

    private fun maybeRunDiagnostics() {
        if (!pendingDiagnostics || state != PageState.READY) return
        pendingDiagnostics = false
        runDiagnostics()
    }

    // =========================================================================================
    // WebViewClient callbacks (via DashboardWebView)
    // =========================================================================================

    internal fun onPageStarted(view: WebView, url: String?) {
        if (view !== webView) return
        state = PageState.LOADING
        // The navigation exists: from here on the WebViewClient says how it ends.
        navigationStarted = true
        mainHandler.removeCallbacks(loadWatchdog)
        // Only a PAGE is worth coming back to. An app-origin document URL (/api/…) can
        // start here too — the App Links path is classified now, but a link inside the
        // page or a redirect can still be one — and remembering it would make Retry and
        // a crash reload fetch the file again instead of showing a page.
        if (Links.classify(url, appOrigins) == Links.Nav.IN_APP) lastUrl = url
        // Every app-origin document gets its own env() probe (see envProbePending): a
        // navigation that did not come through load() — the 401 → /login redirect, the
        // sign-in, an in-app link, a popup routed to loadUrl, pushRoute's location.href —
        // is a new <html> with no inline `--safe-*` on it.
        if (Routes.isAppOrigin(url, appOrigins)) envProbePending = true
        bridge.onPageStarted(view, url)
    }

    /**
     * First paint of the new document: the splash may go. Also the earliest moment the
     * new <html> exists to take the `--safe-*` fallback, when the previous document
     * needed it — pushed here as well as at READY so the bar padding does not visibly
     * jump in on a page that painted first with env() at zero.
     */
    internal fun onPageCommitVisible(view: WebView) {
        keepSplash = false
        if (view === webView && safeVarFallback) pushSafeInsets()
    }

    internal fun onPageFinished(view: WebView, url: String?) {
        if (view !== webView) return
        mainHandler.removeCallbacks(loadWatchdog)
        loadingUrl = null
        refreshBack()
        // Chromium finishes its built-in error page under the URL that failed: that is
        // not READY, and the panel already says why.
        val failed = errorUrl != null && Routes.stripFragment(url) == Routes.stripFragment(errorUrl)
        if (failed) {
            state = PageState.ERROR
            return
        }
        state = PageState.READY
        keepSplash = false
        hidePanel()

        val target = pendingUrl
        if (target != null) {
            if (Routes.originOf(url) == Routes.originOf(target)) {
                pendingUrl = null
                mismatchReloads = 0
                // The cold load of the target itself needs no push; a warm arrival does.
                if (url != target) pushRoute(view, target)
            } else if (mismatchReloads == 0) {
                // The document that finished is not on the target's origin (a redirect
                // off the app, the origin change of a base-URL edit): load the target
                // itself — ONCE. A second finish on the wrong origin means the server
                // sends the target elsewhere every time, and reloading it again would
                // be the loop the panel exists to stop.
                mismatchReloads = 1
                load(view, target)
                return
            } else {
                pendingUrl = null
                mismatchReloads = 0
                showError(getString(R.string.shell_load_stalled), target)
                return
            }
        }
        // Belt and braces for the `--safe-*` fallback: onPageCommitVisible pushed it as
        // soon as the document existed; pushed again now the page is READY, in case the
        // document replaced its <html> style between the two (cheap, idempotent).
        if (safeVarFallback) pushSafeInsets()
        if (envProbePending) {
            envProbePending = false
            runEnvProbe(view)
        }
        maybeRunDiagnostics()
    }

    internal fun onHistoryChanged(view: WebView, url: String?) {
        if (view !== webView) return
        refreshBack()
        if (Routes.isAppOrigin(url, appOrigins)) lastUrl = url
    }

    private fun refreshBack() {
        backCallback.isEnabled = webView?.canGoBack() == true
    }

    // =========================================================================================
    // Error panel
    // =========================================================================================

    /**
     * Overlay over the (kept) WebView. The first message for a document wins: a cancelled
     * SSL handshake is followed by a generic onReceivedError for the same URL, and "not
     * secure" is the one worth reading.
     */
    internal fun showError(text: CharSequence, failedUrl: String?) {
        val already = errorPanel.visibility == View.VISIBLE && errorUrl != null &&
            Routes.stripFragment(errorUrl) == Routes.stripFragment(failedUrl)
        if (already) return
        // The load ended (badly); the watchdog for it has nothing left to catch.
        mainHandler.removeCallbacks(loadWatchdog)
        loadingUrl = null
        errorUrl = failedUrl
        state = PageState.ERROR
        keepSplash = false
        errorTitle.setText(R.string.shell_error_title)
        errorText.text = text
        errorRetry.setText(R.string.shell_error_retry)
        errorRetry.setOnClickListener { retry() }
        errorPanel.visibility = View.VISIBLE
    }

    private fun showUnconfigured() {
        keepSplash = false
        errorTitle.setText(R.string.shell_unconfigured_title)
        errorText.setText(R.string.shell_unconfigured_text)
        errorRetry.setText(R.string.shell_setup)
        // ConfigActivity without an app-widget id: it handles INVALID_APPWIDGET_ID and just
        // saves. The store emission that follows builds the WebView.
        errorRetry.setOnClickListener { startActivity(Intent(this, ConfigActivity::class.java)) }
        errorPanel.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        errorPanel.visibility = View.GONE
    }

    private fun retry() {
        val current = webView ?: return
        hidePanel()
        errorUrl = null
        val target = pendingUrl ?: lastUrl ?: homeUrl() ?: return
        load(current, target)
    }

    // =========================================================================================
    // Insets (spec §5)
    // =========================================================================================

    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(webContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            lastBars = bars
            lastIme = ime
            val out = when (imeMode) {
                // Chromium shrinks its own visual viewport for the keyboard: pass everything
                // through untouched, bars and IME alike.
                Insets.ImeMode.WEBVIEW -> {
                    v.setPadding(0, 0, 0, 0)
                    insets
                }
                // Older WebView: pad the container by the part of the keyboard that sticks
                // out above the nav bar, and hide the IME from the WebView so it does not
                // also try. Never CONSUMED — bars and cutout must still reach the page.
                Insets.ImeMode.NATIVE -> {
                    v.setPadding(0, 0, 0, max(0, ime.bottom - bars.bottom))
                    WindowInsetsCompat.Builder(insets)
                        .setInsets(WindowInsetsCompat.Type.ime(), GraphicsInsets.NONE)
                        .build()
                }
            }
            if (safeVarFallback) pushSafeInsets()
            out
        }
    }

    /**
     * Spec §5.4. On the first READY of a document, ask the page what env(safe-area-inset-*)
     * resolves to. Where the native bar inset is non-zero but the page's env() falls short of
     * it (zero, or only the cutout — both seen from Chromium depending on version and layout),
     * the shell takes over `--safe-*`. A probe that returns nothing leaves the decision alone.
     */
    private fun runEnvProbe(target: WebView) {
        target.evaluateJavascript(Diagnostics.JS_ENV_PROBE) { raw ->
            if (target !== webView) return@evaluateJavascript
            val parts = Diagnostics.unquote(raw).split(',')
            val probeTop = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@evaluateJavascript
            val probeBottom = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@evaluateJavascript
            val density = resources.displayMetrics.density
            val wantTop = (lastBars.top / density).roundToInt()
            val wantBottom = (lastBars.bottom / density).roundToInt()
            safeVarFallback = (wantTop > 0 && probeTop < wantTop - 1) ||
                (wantBottom > 0 && probeBottom < wantBottom - 1)
            if (safeVarFallback) pushSafeInsets()
        }
    }

    /** Inline `--safe-*` on <html> in CSS px; inline beats the :root rule in app.css. */
    private fun pushSafeInsets() {
        val target = webView ?: return
        val density = resources.displayMetrics.density
        fun css(px: Int): String = (px / density).roundToInt().toString() + "px"
        val js = "(function(s){" +
            "s.setProperty('--safe-top','" + css(lastBars.top) + "');" +
            "s.setProperty('--safe-bottom','" + css(lastBars.bottom) + "');" +
            "s.setProperty('--safe-left','" + css(lastBars.left) + "');" +
            "s.setProperty('--safe-right','" + css(lastBars.right) + "');" +
            "})(document.documentElement.style)"
        target.evaluateJavascript(js, null)
    }

    // =========================================================================================
    // Metrics / diagnostics
    // =========================================================================================

    /** The metrics reply of spec §3 — keys and types exactly as documented. No token, no URL. */
    internal fun metricsJson(): JSONObject {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        val cfg = resources.configuration
        return JSONObject()
            .put("t", "metrics")
            .put("webview", pkg?.versionName ?: JSONObject.NULL)
            .put("webviewPackage", pkg?.packageName ?: JSONObject.NULL)
            .put("package", packageName)
            .put("version", BuildConfig.VERSION_NAME)
            .put(
                "insets",
                JSONObject()
                    .put("top", lastBars.top)
                    .put("bottom", lastBars.bottom)
                    .put("left", lastBars.left)
                    .put("right", lastBars.right),
            )
            .put("ime", lastIme.bottom)
            .put("imeMode", imeMode.name)
            .put("safeVarFallback", safeVarFallback)
            .put("fontScale", cfg.fontScale.toDouble())
            .put("textZoom", webView?.settings?.textZoom ?: ShellPrefs.effectiveTextZoom(prefs.textZoom, cfg.fontScale))
            .put("density", resources.displayMetrics.density.toDouble())
            .put("widthDp", cfg.screenWidthDp)
            .put("heightDp", cfg.screenHeightDp)
    }

    /** The native half of the diagnostics dialog: the metrics plus what only the shell knows. */
    internal fun diagnosticsNativeJson(): JSONObject {
        val cfg = resources.configuration
        val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return metricsJson()
            .put("pageState", state.name)
            .put(
                "webViewFeatures",
                JSONObject()
                    .put("WEB_MESSAGE_LISTENER", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                    .put("DOCUMENT_START_SCRIPT", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                    .put("ALGORITHMIC_DARKENING", WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)),
            )
            .put(
                "config",
                JSONObject()
                    .put("uiMode", if (night) "night" else "day")
                    .put(
                        "orientation",
                        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
                    ),
            )
    }

    /** Ask the page for its half, then show both. Without a page the native half alone. */
    internal fun runDiagnostics() {
        val current = webView
        if (current == null) {
            showDiagnostics(Diagnostics.show(this, "null", diagnosticsNativeJson()))
            return
        }
        current.evaluateJavascript(Diagnostics.JS_METRICS) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            showDiagnostics(Diagnostics.show(this, Diagnostics.unquote(raw), diagnosticsNativeJson()))
        }
    }

    /**
     * Take ownership of a diagnostics dialog just shown: at most one is up at a time
     * ("Run again" opens a new one from the old one's button — the old one is already on
     * its way out, but dismissing it explicitly costs nothing), and the reference is
     * dropped when the dialog goes so onDestroy does not dismiss a dead one.
     */
    private fun showDiagnostics(dialog: AlertDialog) {
        diagnosticsDialog?.takeIf { it !== dialog }?.dismiss()
        diagnosticsDialog = dialog
        dialog.setOnDismissListener { if (diagnosticsDialog === it) diagnosticsDialog = null }
    }
}
