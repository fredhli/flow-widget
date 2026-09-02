package com.fredhli.flowwidget.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.fredhli.flowwidget.BuildConfig
import com.fredhli.flowwidget.R

/**
 * Builds the one WebView the shell shows (spec §6, "WebSettings"). A factory rather than a
 * subclass because the view has to be rebuilt from scratch after a renderer crash
 * (`onRenderProcessGone`): a crashed WebView must be removed and destroyed, never reused,
 * so everything that makes "our" WebView ours lives here and is applied again on the
 * replacement. The activity owns the view, the bridge and the state machine; this file only
 * knows how to configure a WebView and how to translate WebViewClient/WebChromeClient
 * callbacks into calls on [MainActivity].
 */
object DashboardWebView {

    /**
     * A configured, client-attached WebView with no page loaded. The caller installs the
     * bridge (it needs the allowed origins) and then calls loadUrl — in that order, because
     * addWebMessageListener only affects navigations that start after it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(activity: MainActivity): WebView {
        // Chrome DevTools over USB for debug builds only. A release APK must never expose
        // the page (and the session cookie) to anything plugged into the phone.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val webView = WebView(activity)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // The page runs edge-to-edge and pads itself; the Android overscroll glow on top of
        // it looks like a bug. The page's own scroll containers keep their behaviour.
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // The dashboard's --bg for the current theme, so the frame between "window shown"
        // and "first paint" is not a white flash on a dark page.
        webView.setBackgroundColor(ContextCompat.getColor(activity, R.color.shell_background))

        val s = webView.settings
        // The dashboard is a JS app; without DOM storage it cannot keep its last route.
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // window.open()/target=_blank must reach onCreateWindow (PopupCatcher) instead of
        // being loaded in place or dropped — but a page may not open windows unprompted.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        // Pinch-zoom for the reader; never the +/- overlay buttons.
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // Respect the page's <meta viewport> (width=device-width, viewport-fit=cover) and do
        // not zoom out to "fit" — the page lays itself out for the real width.
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        // Lockdown: the page is https (or a private http host the user typed); no mixed
        // content, no local files, no content:// providers, no location, no autoplay.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // The only thing the dashboard reads the UA for is the "\bDashboardApp/" marker.
        s.userAgentString = WebSettings.getDefaultUserAgent(activity) + Bridge.UA_SUFFIX
        applyTextZoom(webView, activity.prefs.textZoom, activity.resources.configuration.fontScale)
        // The page owns dark mode (prefers-color-scheme + its own tokens). Algorithmic
        // darkening would recolour a page that already has a dark theme.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }

        webView.webViewClient = ShellWebViewClient(activity)
        webView.webChromeClient = ShellChromeClient(activity)
        // <a download> and Content-Disposition: attachment responses land here.
        webView.setDownloadListener(Files.downloadListener(activity))
        return webView
    }

    /** WebSettings.textZoom from the stored pref and the live font scale (spec §2.3). */
    fun applyTextZoom(webView: WebView, prefTextZoom: Int, fontScale: Float) {
        webView.settings.textZoom = ShellPrefs.effectiveTextZoom(prefTextZoom, fontScale)
    }

    /**
     * Navigation policy and page-state plumbing. Every callback is forwarded to the
     * activity, which owns the state machine; the client itself decides only one thing:
     * whether a navigation may happen inside the WebView at all.
     */
    private class ShellWebViewClient(private val activity: MainActivity) : WebViewClient() {

        /**
         * Spec §6: IN_APP navigates; everything else is handed to Links.leave. The return is
         * `true` for every non-IN_APP case regardless of what `leave` reports: the WebView
         * must never carry the session cookie's origin to an off-origin page, and a URL
         * `leave` could not open is dropped rather than loaded. Subframes: Chromium only
         * consults this for non-http(s) subframe navigations, which are blocked or handed
         * to an app exactly like the main frame's would be.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (val nav = Links.classify(url, activity.appOrigins)) {
                Links.Nav.IN_APP -> false
                else -> {
                    Links.leave(activity, url, activity.prefs.linkPolicy, nav)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            activity.onPageStarted(view, url)
        }

        /** First paint of the new document: the moment the splash may go. */
        override fun onPageCommitVisible(view: WebView, url: String?) {
            super.onPageCommitVisible(view, url)
            activity.onPageCommitVisible()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            activity.onPageFinished(view, url)
        }

        /** Fires for hash changes too, which is how the back callback tracks the route history. */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            activity.onHistoryChanged(view, url)
        }

        /** Main frame only: a failed image or script is the page's problem, not the shell's. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            activity.showError(error.description ?: "", request.url.toString())
        }

        /**
         * 5xx on the main document only. 4xx is left to the page (a 401 is the dashboard's
         * own login/redirect flow; a 404 renders the server's page), and a subresource
         * status is never the shell's business.
         */
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (!request.isForMainFrame) return
            val code = errorResponse.statusCode
            if (code < 500) return
            activity.showError(activity.getString(R.string.shell_http_error, code), request.url.toString())
        }

        /**
         * Always cancel. The session cookie must never be sent over a connection whose
         * certificate does not verify, and there is no user-facing "proceed anyway" here
         * by design. The panel shows only when the failing URL is on an app origin — an
         * off-origin subresource with a bad certificate is simply dropped.
         */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            val url = error.url
            if (Routes.isAppOrigin(url, activity.appOrigins)) {
                activity.showError(activity.getString(R.string.shell_ssl_error), url)
            }
        }

        /**
         * The renderer died (crash or OOM kill). Returning false would kill the app; true
         * means "handled" — but the WebView is now unusable and must be removed from the
         * hierarchy and destroyed, which the activity does before creating a fresh one.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            activity.onRendererGone(view)
            return true
        }
    }

    /** Only the popup plumbing; everything else is the framework default. */
    private class ShellChromeClient(private val activity: MainActivity) : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean = activity.popupCatcher.onCreateWindow(view, resultMsg)

        override fun onCloseWindow(window: WebView?) {
            activity.popupCatcher.onCloseWindow(window)
        }
    }
}
