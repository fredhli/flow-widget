package com.fredhli.flowwidget.app

import android.net.Uri
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.pow

/**
 * The page ↔ shell bridge (spec §3). Transport is WebViewCompat.addWebMessageListener,
 * which is origin-scoped: only documents on [Routes.appOrigins] ever see a `NativeBridge`
 * object, and the callback re-checks the source origin and the frame anyway. There is
 * deliberately no addJavascriptInterface fallback — that API is exposed to every frame
 * of every origin the WebView ever loads, and this WebView can be steered off-origin by a
 * link. Without WEB_MESSAGE_LISTENER there is simply no bridge; the page guards every
 * `window.Native` use.
 *
 * The façade ([FACADE_JS]) turns the raw postMessage object into the `window.Native` API
 * the dashboard calls (`share`, `openExternal`, `themeColor`, `metrics`). It is injected at
 * document start where the WebView supports it, else evaluated from onPageStarted (best
 * effort: a page that calls Native before that moment sees nothing, by design).
 *
 * The parsing half ([parse], [isLightColor]) is pure so it can be unit-tested; the
 * dispatching half needs the activity. Nothing here ever sees the token.
 */
class Bridge(private val host: MainActivity) {

    sealed class Msg {
        data class Share(val url: String, val name: String?) : Msg()
        data class Open(val url: String) : Msg()
        data class Theme(val hex: String) : Msg()
        object Metrics : Msg()
    }

    /** Origins the listener accepts — the set handed to [install], lowercase, no trailing slash. */
    private var allowedOrigins: Set<String> = emptySet()

    /** True once addWebMessageListener succeeded — without it the façade is pointless. */
    private var listenerInstalled = false

    /** True when the façade rides on addDocumentStartJavaScript; else onPageStarted evaluates it. */
    private var facadeAtDocumentStart = false

    /**
     * Register the listener and the façade on a fresh WebView, BEFORE its first loadUrl:
     * addWebMessageListener only applies to navigations that start after the call. Both
     * calls throw IllegalArgumentException on an origin rule they cannot parse; a base URL
     * that Chromium rejects (an odd IPv6 form, say) must not take the two shipped hosts
     * down with it, so the fallback is the https hosts alone.
     */
    fun install(webView: WebView, allowedOrigins: Set<String>) {
        // A fresh WebView (first one, or the replacement after a renderer crash) starts
        // with nothing registered, whatever the previous one had.
        listenerInstalled = false
        facadeAtDocumentStart = false
        this.allowedOrigins = allowedOrigins.map { it.lowercase().trimEnd('/') }.toSet()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val rules = tryRules(allowedOrigins) { WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, it, listener) }
            ?: return
        listenerInstalled = true
        this.allowedOrigins = rules.map { it.lowercase().trimEnd('/') }.toSet()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            facadeAtDocumentStart =
                tryRules(rules) { WebViewCompat.addDocumentStartJavaScript(webView, FACADE_JS, it) } != null
        }
    }

    /** Run [op] with the full rule set, then with the shipped https hosts only; the set that worked, or null. */
    private inline fun tryRules(rules: Set<String>, op: (Set<String>) -> Unit): Set<String>? {
        val https = Routes.APP_HOSTS.map { "https://$it" }.toSet()
        for (candidate in listOf(rules, https)) {
            try {
                op(candidate)
                return candidate
            } catch (_: IllegalArgumentException) {
                // fall through to the narrower set
            }
        }
        return null
    }

    /**
     * Called from WebViewClient.onPageStarted. When the WebView lacks DOCUMENT_START_SCRIPT
     * the façade is evaluated here instead — later than document start, so an inline script
     * at the top of the page might miss it, but the dashboard only touches window.Native
     * from event handlers. Off-origin pages get nothing (they have no NativeBridge either).
     */
    fun onPageStarted(webView: WebView, url: String?) {
        if (facadeAtDocumentStart || !listenerInstalled) return
        if (!Routes.isAppOrigin(url, allowedOrigins)) return
        webView.evaluateJavascript(FACADE_JS, null)
    }

    private val listener = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy,
        ) {
            // Belt and braces over the origin rules: an iframe on an allowed origin, or an
            // origin string that does not match byte-for-byte, is dropped here.
            if (!isMainFrame) return
            val origin = sourceOrigin.toString().trimEnd('/').lowercase()
            if (origin !in allowedOrigins) return
            val msg = parse(message.data ?: return) ?: return
            handle(msg, replyProxy)
        }
    }

    private fun handle(msg: Msg, reply: JavaScriptReplyProxy) {
        when (msg) {
            is Msg.Share -> {
                // Only app-origin URLs: the fetch carries the session cookie, and that
                // cookie must never be sent anywhere else.
                if (Routes.isAppOrigin(msg.url, host.appOrigins)) {
                    Files.openOrShare(host, msg.url, msg.name?.ifBlank { null }, Files.Mode.SHARE)
                }
            }
            is Msg.Open -> {
                val url = msg.url
                if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    // The page asked for the browser explicitly — even for its own origin.
                    Links.openExternal(host, url, host.prefs.linkPolicy)
                } else {
                    Links.leave(host, url, host.prefs.linkPolicy, Links.classify(url, host.appOrigins))
                }
            }
            is Msg.Theme -> {
                // The page reports the surface colour it paints under the status bar; the
                // shell flips the bar icons to contrast with it. Unparsable → leave as is.
                val light = isLightColor(msg.hex) ?: return
                val controller = WindowCompat.getInsetsController(host.window, host.window.decorView)
                controller.isAppearanceLightStatusBars = light
                controller.isAppearanceLightNavigationBars = light
            }
            Msg.Metrics -> reply.postMessage(host.metricsJson().toString())
        }
    }

    companion object {
        const val OBJECT_NAME = "NativeBridge"

        /** Appended to the default UA; the dashboard detects the app by /\bDashboardApp\// only. */
        const val UA_SUFFIX = " DashboardApp/2.0"

        /** Spec §3, verbatim. No `$` in here — this is a Kotlin raw string. */
        val FACADE_JS: String = """
            (function () {
              if (window.Native || !window.NativeBridge) return;
              var B = window.NativeBridge, waiting = [];
              function send(o) { try { B.postMessage(JSON.stringify(o)); } catch (e) {} }
              B.onmessage = function (ev) {
                var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
                if (m && m.t === "metrics") { var r = waiting.shift(); if (r) r(m); }
              };
              window.Native = {
                version: "2.0",
                share: function (url, name) { send({ t: "share", url: String(url || ""), name: String(name || "") }); },
                openExternal: function (url) { send({ t: "open", url: String(url || "") }); },
                themeColor: function (hex) { send({ t: "theme", hex: String(hex || "") }); },
                metrics: function () { return new Promise(function (res) { waiting.push(res); send({ t: "metrics" }); }); }
              };
            })();
        """.trimIndent()

        /**
         * Pure (org.json is on the test classpath). Unknown "t", non-object, non-JSON, or a
         * message missing its one required field → null. `name` on share is optional and
         * comes back null when absent or JSON null.
         */
        fun parse(json: String): Msg? {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            return when (o.optString("t")) {
                "share" -> {
                    val url = requiredString(o, "url") ?: return null
                    val name = if (o.isNull("name")) null else o.optString("name")
                    Msg.Share(url, name)
                }
                "open" -> Msg.Open(requiredString(o, "url") ?: return null)
                "theme" -> Msg.Theme(requiredString(o, "hex") ?: return null)
                "metrics" -> Msg.Metrics
                else -> null
            }
        }

        private fun requiredString(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            val v = o.opt(key) as? String ?: return null
            return v.ifBlank { null }
        }

        /**
         * Pure. "#RRGGBB" / "#RGB" (alpha digits tolerated and ignored) → relative luminance
         * (WCAG, linearised sRGB) > 0.5. Unparsable → null.
         */
        fun isLightColor(hex: String): Boolean? {
            val h = hex.trim().removePrefix("#")
            if (h.isEmpty() || h.any { Character.digit(it, 16) < 0 }) return null
            val rgb: List<Int> = when (h.length) {
                3, 4 -> h.take(3).map { Character.digit(it, 16) * 17 }
                6, 8 -> (0 until 3).map { i -> h.substring(i * 2, i * 2 + 2).toInt(16) }
                else -> return null
            }
            fun linear(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            val y = 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])
            return y > 0.5
        }
    }
}
