package com.fredhli.flowwidget.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * `WebChromeClient.onCreateWindow` helper: turns a `window.open()` / `target=_blank` into a
 * URL the shell can route.
 *
 * Why this exists at all: the main WebView has `setSupportMultipleWindows(true)` so that
 * `_blank` navigations reach `onCreateWindow` instead of being silently loaded in place or
 * dropped — and Chromium does NOT pass the URL to `onCreateWindow`. The only way to learn
 * where the popup wanted to go is to give Chromium a real child WebView to load it in, and
 * read the URL off the child's first navigation. The child is never attached to any view
 * hierarchy: it exists to receive one callback, hand the URL to [onUrl], and be destroyed.
 * The dashboard's own `linkPolicy` marks every off-origin anchor `_blank` + `noopener` and
 * `viewPDF()` uses `window.open`, so this is the path every external link and every PDF
 * takes — a leaked child here is a leak on every tap.
 *
 * Two callbacks capture, because Chromium is not consistent about which one fires first
 * for a popup's initial load: `shouldOverrideUrlLoading` (script-driven `location = …` in
 * an empty popup, and most `_blank` anchors) and `onPageStarted` (a popup opened straight
 * onto its URL on some WebView versions skips the override). A per-child `captured` flag
 * makes the pair deliver exactly one URL. `about:blank` is what an empty `window.open()`
 * starts on and is never a destination.
 *
 * Destroying the child happens on the main looper via `post { }`, never inside the callback
 * that delivered the URL: Chromium is still inside that child's navigation stack when the
 * callback runs, and `destroy()` from within it is a native crash. A 30 s watchdog releases
 * a child that never navigates (a popup blocked by the page's own script, a `window.open()`
 * followed by nothing), so a stuck child cannot outlive a session.
 */
class PopupCatcher(private val context: Context, private val onUrl: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    /** Children handed to Chromium and not yet destroyed. UI thread only. */
    private val children = mutableListOf<WebView>()

    /**
     * Create the child, attach the capturing client, hand it over through the transport
     * and return true. `resultMsg == null` → false (nothing to hand the child to, so the
     * popup is refused, which is what returning false means to Chromium).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun onCreateWindow(parent: WebView, resultMsg: Message?): Boolean {
        if (resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val child = WebView(context)
        // JavaScript on, everything else default. Off, a popup that navigates via script
        // (`var w = window.open(); w.location = url`) never navigates and is never caught.
        // The child loads at most one navigation start before it is torn down, and its
        // script runs on the popup's own origin with no bridge attached, so this is the
        // same exposure the main page already has, for one page load.
        child.settings.javaScriptEnabled = true
        child.webViewClient = CapturingClient(child)

        children += child
        // Watchdog: a child that never reports a URL is released anyway.
        main.postDelayed({ release(child) }, WATCHDOG_MS)

        transport.webView = child
        resultMsg.sendToTarget()
        return true
    }

    /**
     * `WebChromeClient.onCloseWindow`: the popup called `window.close()`. Only our own
     * children are touched — Chromium may report a window we never created. Deferred to
     * the looper for the same reason capture is: the callback is inside the child's stack.
     */
    fun onCloseWindow(window: WebView?) {
        if (window != null && window in children) main.post { release(window) }
    }

    /** Activity teardown: destroy every child still alive and drop pending watchdogs. */
    fun destroy() {
        main.removeCallbacksAndMessages(null)
        for (child in children.toList()) release(child)
    }

    /**
     * Tear one child down. Idempotent: the capture, the watchdog and [destroy] may all ask
     * for the same child, and only the first one still finds it in [children].
     */
    private fun release(child: WebView) {
        if (!children.remove(child)) return
        // A plain client first, so nothing Chromium fires during stopLoading/destroy
        // re-enters CapturingClient on a child that is going away.
        child.webViewClient = WebViewClient()
        child.stopLoading()
        child.destroy()
    }

    /** One URL per child, from whichever callback Chromium fires first. */
    private inner class CapturingClient(private val child: WebView) : WebViewClient() {

        private var captured = false

        private fun capture(url: String?) {
            if (captured) return
            if (url.isNullOrEmpty() || url == "about:blank") return
            captured = true
            // The URL is routed right here (loadUrl on the main WebView, or an Intent —
            // both are fine from inside a child's callback); only the destroy is deferred,
            // because that one is not.
            main.post { release(child) }
            onUrl(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            capture(request.url?.toString())
            // Always true: the child never loads anything itself. Returning false for a
            // second navigation would let the child render the popup's page in a WebView
            // nobody can see, with its own cookies and network.
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // The load that has started is stopped by release() a moment later, on the
            // looper; nothing here touches the child while Chromium is inside it.
            capture(url)
        }
    }

    private companion object {
        const val WATCHDOG_MS = 30_000L
    }
}
