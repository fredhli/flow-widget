package com.fredhli.flowwidget.app

import android.content.Context
import android.content.Intent
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
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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

    /** Last app-origin main-frame URL started or visited — what Retry and a crash reload. */
    private var lastUrl: String? = null

    /** Main-frame URL whose load failed; onPageFinished for it means "error page shown". */
    private var errorUrl: String? = null

    /** Intent that arrived before the WebView existed, and whether its extras are stale. */
    private var deferredIntent: Intent? = null
    private var deferredIntentStale = false

    /** lastUrl from a previous incarnation (process death), applied once. */
    private var restoredUrl: String? = null

    // ---- splash ---------------------------------------------------------------------------
    private var keepSplash = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseSplash = Runnable { keepSplash = false }

    // ---- insets (spec §5) -----------------------------------------------------------------
    private var imeMode = Insets.ImeMode.NATIVE
    private var lastBars: GraphicsInsets = GraphicsInsets.NONE
    private var lastIme: GraphicsInsets = GraphicsInsets.NONE
    private var safeVarFallback = false

    /** Run the env() probe on the next READY — set by every document load. */
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
        // Nothing loaded yet but everything needed is here (can happen when the store
        // emitted while the unconfigured panel was up and no intent was pending).
        val cfg = config
        if (webView != null && cfg != null && state == PageState.NONE && pendingUrl == null) {
            pendingUrl = homeUrl(cfg)
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
        // uiMode flipped: re-derive the bar icon colours from the new night state (the page
        // will also report its own colour through Native.themeColor shortly).
        enableEdgeToEdge()
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
        try {
            ta.getColorStateList(0)?.let { errorTitle.setTextColor(it) }
            ta.getColorStateList(1)?.let { errorText.setTextColor(it) }
        } finally {
            ta.recycle()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(releaseSplash)
        if (::popupCatcher.isInitialized) popupCatcher.destroy()
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
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
        return attachFreshWebView()
    }

    /** onRenderProcessGone: the crashed view is unusable; rebuild and reload where we were. */
    internal fun onRendererGone(view: WebView) {
        if (view !== webView) return // a view already replaced; nothing left to do for it
        val fresh = replaceWebView()
        seedCookie(fresh)
        hidePanel()
        Toast.makeText(this, R.string.shell_renderer_crashed, Toast.LENGTH_SHORT).show()
        val target = pendingUrl ?: lastUrl ?: homeUrl() ?: return
        load(fresh, target)
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
        if (!fromHistory) {
            if (intent?.getBooleanExtra(EXTRA_DIAGNOSTICS, false) == true) pendingDiagnostics = true
            val route = intent?.getStringExtra(EXTRA_ROUTE)
            val data = intent?.data
            when {
                route != null -> pendingUrl = Routes.pageUrl(cfg.baseUrl, route)
                intent?.action == Intent.ACTION_VIEW && data != null -> {
                    val target = data.toString()
                    if (Routes.isAppOrigin(target, appOrigins)) {
                        pendingUrl = target
                    } else {
                        // Not ours (the manifest filter should make this impossible, but a
                        // caller can address the component directly): the browser has it,
                        // and the shell still shows a page below.
                        Links.openExternal(this, target, prefs.linkPolicy)
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
        if (Routes.isAppOrigin(url, appOrigins)) seedCookie(target)
        state = PageState.LOADING
        target.loadUrl(url)
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
        if (Routes.isAppOrigin(url, appOrigins)) lastUrl = url
        bridge.onPageStarted(view, url)
    }

    internal fun onPageCommitVisible() {
        keepSplash = false
    }

    internal fun onPageFinished(view: WebView, url: String?) {
        if (view !== webView) return
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
                // The cold load of the target itself needs no push; a warm arrival does.
                if (url != target) pushRoute(view, target)
            } else {
                load(view, target)
                return
            }
        }
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
            Diagnostics.show(this, "null", diagnosticsNativeJson())
            return
        }
        current.evaluateJavascript(Diagnostics.JS_METRICS) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            Diagnostics.show(this, Diagnostics.unquote(raw), diagnosticsNativeJson())
        }
    }
}
