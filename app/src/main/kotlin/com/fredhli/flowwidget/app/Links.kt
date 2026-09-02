package com.fredhli.flowwidget.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.fredhli.flowwidget.R
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The shell's navigation gate: every URL the WebView wants to leave the page for — a tap,
 * a `window.open`, a bridge `open` message, an App Link that is not ours — comes through
 * [classify] and then [leave]. The page's own `linkPolicy` already marks off-origin anchors
 * `_blank`, but that is the page's promise; this file is the shell's, and it holds even for
 * a link the page never saw (a redirect chain, a `javascript:` injected by an extension, an
 * `intent://` in a brief).
 *
 * Two halves, kept apart on purpose:
 *
 * - The pure half — [classify], [isIntentUri], [intentFallbackUrl] — imports nothing from
 *   `android.*`, so a plain-JVM unit test can pin the whole decision matrix (LinksTest).
 *   Local unit tests run against the `android.jar` stub whose every method throws, which is
 *   why the classification does not touch `android.net.Uri` and why the URL is parsed by
 *   hand where `java.net.URI` is too strict.
 * - The Android half — [leave], [openExternal], [openInBrowser], [openIntentUri],
 *   [openOtherScheme] — only dispatches on a decision already made.
 *
 * Secrets: a URL that reaches here may carry `?k=<token>` (the dashboard's `apiURL()`
 * appends it), so nothing in this file logs a URL, puts one in an exception message, or
 * shows one in a toast. Toasts are the fixed strings in strings_links.xml and nothing else.
 */
object Links {

    /** Chrome's package. Named explicitly because Samsung Internet may be the default browser. */
    const val CHROME_PACKAGE = "com.android.chrome"

    enum class Nav {
        /** App origin, path not under /api/ → let the WebView navigate. */
        IN_APP,

        /** App origin, path starts with "/api/" → a document (PDF, raw brief) → Files.openOrShare(VIEW). */
        APP_DOCUMENT,

        /** http(s) elsewhere → [openExternal] per the user's LinkPolicy. */
        EXTERNAL_HTTP,

        /** "intent://…#Intent;…;end" → [openIntentUri]. */
        INTENT_URI,

        /** mailto: tel: sms: smsto: geo: market: → [openOtherScheme]. */
        OTHER_SCHEME,

        /** javascript: file: content: data: blob: about: and anything else → drop silently. */
        BLOCKED,
    }

    /**
     * The schemes a phone has a stock handler for. Deliberately a short allow-list rather
     * than "anything that is not blocked": an unknown scheme handed to ACTION_VIEW is an
     * unknown app being launched with attacker-chosen data, and the dashboard's own content
     * never needs more than these.
     */
    private val OTHER_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "market")

    /** RFC 3986 scheme, anchored at the start. A URL without one is unparsable here. */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /**
     * Pure. Case-insensitive scheme/host. `appOrigins` entries look like
     * "https://dashboard.fredhli.com" or "http://192.168.1.50:8000" (Routes.appOrigins —
     * lowercase, explicit port only when non-default), so an http URL on a private host
     * that is the stored baseUrl origin is IN_APP because the set contains it.
     *
     * The scheme is taken by regex first and only http(s) URLs are parsed further: an
     * `intent://` URL carries `;`-delimited fields and a `mailto:` has no authority, and
     * neither needs anything but its scheme to be classified. Null, blank, or no scheme →
     * BLOCKED, which is the safe answer for a URL nobody can explain.
     */
    fun classify(url: String?, appOrigins: Set<String>): Nav {
        if (url.isNullOrBlank()) return Nav.BLOCKED
        val s = url.trim()
        val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase() ?: return Nav.BLOCKED
        return when (scheme) {
            "http", "https" -> {
                val parsed = parseHttp(s, scheme) ?: return Nav.BLOCKED
                when {
                    parsed.origin !in appOrigins -> Nav.EXTERNAL_HTTP
                    parsed.path.startsWith("/api/") -> Nav.APP_DOCUMENT
                    else -> Nav.IN_APP
                }
            }
            "intent" -> Nav.INTENT_URI
            in OTHER_SCHEMES -> Nav.OTHER_SCHEME
            else -> Nav.BLOCKED
        }
    }

    /** Pure. True when url starts with "intent:" (case-insensitive, leading whitespace ignored). */
    fun isIntentUri(url: String?): Boolean =
        url?.trimStart()?.startsWith("intent:", ignoreCase = true) == true

    /**
     * Pure. The `S.browser_fallback_url=…` value inside "#Intent;…;end", percent-decoded,
     * only if http(s); else null.
     *
     * Mirrors what `Intent.parseUri` does with the same field (it looks for the LAST
     * "#Intent;" and reads `;`-separated `key=value` pairs up to "end"), so the fallback we
     * open is the one Chrome would have opened. Decoding is percent-only: `Uri.decode` leaves
     * '+' alone, and a fallback URL with a literal '+' in its query must survive the trip.
     * A fallback that decodes to anything but http(s) (a `javascript:`, a second `intent:`)
     * is dropped — the whole point of the fallback is "a web page instead", not "any URL
     * the author likes".
     */
    fun intentFallbackUrl(url: String): String? {
        val start = url.lastIndexOf("#Intent;")
        if (start < 0) return null
        val body = url.substring(start + "#Intent;".length)
        val end = body.indexOf(";end")
        val fields = (if (end < 0) body else body.substring(0, end)).split(';')
        val raw = fields.firstOrNull { it.startsWith("S.browser_fallback_url=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() } ?: return null
        val decoded = percentDecode(raw) ?: return null
        val scheme = SCHEME.find(decoded)?.groupValues?.get(1)?.lowercase()
        return if (scheme == "http" || scheme == "https") decoded else null
    }

    // ---------------------------------------------------------------- pure helpers

    private class HttpParts(val origin: String, val path: String)

    /**
     * "scheme://host[:port]" + path for an http(s) URL, or null when even a hand parse
     * cannot find a host.
     *
     * `java.net.URI` is the first attempt because it gets IPv6 literals and percent-escapes
     * right; but it is stricter than Chromium (a `|` or `^` left raw in a query is a
     * URISyntaxException there and an ordinary link here), and for some hosts it parses but
     * reports `host == null` (an underscore in a label). Either way the URL is still a real
     * navigation that has to be classified, so the fallback takes the authority by hand:
     * everything between "://" and the first of "/?#\", userinfo dropped at the LAST '@' —
     * which is exactly the trick "https://dashboard.fredhli.com@evil.com/" relies on, and
     * why the split is at the last one.
     *
     * The backslash is in that terminator set on purpose. Chromium parses http(s) URLs per
     * the WHATWG URL Standard, where `\` in a special-scheme URL is a path separator: it
     * navigates "https://evil.com\@dashboard.fredhli.com/" to evil.com. `java.net.URI`
     * rejects the backslash (which is how such a URL reaches this branch at all), and a
     * hand parse that stopped only at "/?#" would take "evil.com\" for userinfo and call
     * the URL IN_APP — a cookie-scoped fetch and the bridge would then follow the WebView
     * to evil.com. Routes.originOf makes the same cut for the same reason.
     */
    private fun parseHttp(url: String, scheme: String): HttpParts? {
        try {
            val u = URI(url)
            val host = u.host
            if (!host.isNullOrEmpty()) {
                return HttpParts(
                    origin = origin(scheme, host.lowercase(), u.port),
                    path = u.rawPath.orEmpty().ifEmpty { "/" },
                )
            }
        } catch (_: URISyntaxException) {
            // fall through to the manual parse
        }
        val afterScheme = url.substring(scheme.length + 1)
        if (!afterScheme.startsWith("//")) return null
        val rest = afterScheme.substring(2)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#', '\\')).let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authorityEnd)
        authority = authority.substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            portText = authority.substring(close + 1).removePrefix(":")
        } else {
            host = authority.substringBefore(':')
            portText = authority.substringAfter(':', "")
        }
        if (host.isEmpty()) return null
        val port = if (portText.isEmpty()) -1 else (portText.toIntOrNull() ?: return null)
        val tail = rest.substring(authorityEnd)
        val path = tail.takeWhile { it != '?' && it != '#' }.ifEmpty { "/" }
        return HttpParts(origin(scheme, host.lowercase(), port), path)
    }

    /** Same shape as Routes.originOf: the default port is dropped, any other is kept. */
    private fun origin(scheme: String, host: String, port: Int): String {
        val default = if (scheme == "https") 443 else 80
        return if (port <= 0 || port == default) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** Percent-decoding that leaves '+' alone (Uri.decode semantics, not form semantics). */
    private fun percentDecode(s: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------- android half

    /**
     * Dispatch on nav. IN_APP is the caller's business (loadUrl) and returns false here.
     * Every other branch returns true — something was started, or the URL was dropped or
     * toasted — because a `true` from `shouldOverrideUrlLoading` is what keeps the main
     * WebView on its page. ActivityNotFoundException / SecurityException from any branch →
     * toast R.string.links_no_handler; a `SecurityException` is what a target that is
     * `exported=false`, or that demands a permission we lack, throws instead of "not found".
     */
    fun leave(context: Context, url: String, policy: LinkPolicy, nav: Nav): Boolean {
        try {
            when (nav) {
                Nav.IN_APP -> return false
                Nav.APP_DOCUMENT -> Files.openOrShare(context, url, null, Files.Mode.VIEW)
                Nav.EXTERNAL_HTTP -> if (!openExternal(context, url, policy)) noHandler(context)
                Nav.INTENT_URI -> if (!openIntentUri(context, url, policy)) noHandler(context)
                Nav.OTHER_SCHEME -> openOtherScheme(context, url) // toasts on its own
                Nav.BLOCKED -> Unit // dropped silently: nothing a user can act on
            }
        } catch (_: ActivityNotFoundException) {
            noHandler(context)
        } catch (_: SecurityException) {
            noHandler(context)
        }
        return true
    }

    /**
     * http(s) only. Order:
     *  1. ACTION_VIEW + FLAG_ACTIVITY_REQUIRE_NON_BROWSER — a verified native app for that
     *     link wins (YouTube, Maps, a bank); the flag makes Android throw
     *     ActivityNotFoundException instead of opening a browser, which is the signal that
     *     there is none. Skipped when `allowSelf` is false, see below.
     *  2. Per policy — CHROME: ACTION_VIEW aimed at [CHROME_PACKAGE]; CUSTOM_TAB: a
     *     CustomTabsIntent pinned to Chrome when it is installed, else to whatever
     *     CustomTabsClient names; DEFAULT_BROWSER: straight to step 3.
     *  3. Plain ACTION_VIEW whenever Chrome / a Custom Tabs provider is missing or disabled
     *     — or, when `allowSelf` is false, [openBrowserOnly]: an ACTION_VIEW pinned to a
     *     real browser package, else a chooser that excludes MainActivity.
     *
     * `allowSelf`: this app is the VERIFIED App Links handler for its own two hosts, so for
     * an app-origin URL step 1 resolves to… MainActivity, and a plain ACTION_VIEW in step 3
     * does the same. That is right for the WebView's own off-origin taps (the caller only
     * asks for URLs that are not ours). It is wrong for the two callers whose whole point
     * is "leave the app": the page's `Native.openExternal` on its own origin (spec §3
     * promises the browser, even for the app's own URLs) and the widget's Browser target.
     * Those pass `allowSelf = false` and never re-enter the shell — a `singleTask`
     * MainActivity would otherwise just get an onNewIntent and load the URL in place,
     * which from the user's side is a tap that did nothing.
     *
     * Flags: every intent started from a non-Activity context carries
     * FLAG_ACTIVITY_NEW_TASK, because off an Activity the framework throws
     * AndroidRuntimeException without it (which [leave] does not catch). The Custom Tab is
     * the one case where the flag is NOT added from an Activity: see the branch. Returns
     * false only when nothing could open it.
     */
    fun openExternal(context: Context, url: String, policy: LinkPolicy, allowSelf: Boolean = true): Boolean {
        val uri = Uri.parse(url)
        if (allowSelf) {
            val nonBrowser = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            if (tryStart(context, nonBrowser)) return true
        }

        when (policy) {
            LinkPolicy.CHROME -> {
                val chrome = Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(CHROME_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStart(context, chrome)) return true
            }
            LinkPolicy.CUSTOM_TAB -> {
                val provider = if (isPackageEnabled(context, CHROME_PACKAGE)) CHROME_PACKAGE
                else runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
                if (provider != null) {
                    val tab = CustomTabsIntent.Builder().build()
                    tab.intent.setPackage(provider)
                    // A Custom Tab is meant to live in OUR task: Chrome's CustomTabActivity
                    // declares taskAffinity="" precisely so it stacks on the caller and
                    // Back/the X returns to the page with no second Recents card. Adding
                    // NEW_TASK from an Activity would make Android spawn a separate task
                    // for it — a second "Dashboard Flow" card in Recents and a tab that
                    // survives the app being swiped away. The flag is added only when the
                    // caller is not an Activity at all (a WebView callback wrapped in an
                    // application context, a Service), where it is mandatory: without it
                    // startActivity throws AndroidRuntimeException, which is not one of
                    // the exceptions leave() catches.
                    if (activityOf(context) == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        tab.launchUrl(context, uri)
                        return true
                    } catch (_: ActivityNotFoundException) {
                        // provider named but gone/disabled between the query and now
                    } catch (_: SecurityException) {
                        // a provider that refuses us; the plain browser will not
                    }
                }
            }
            LinkPolicy.DEFAULT_BROWSER -> Unit // step 3 is the whole policy
        }

        if (!allowSelf) return openBrowserOnly(context, uri)
        val plain = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, plain)
    }

    /**
     * The widget's "Browser" target and anything else that must open a URL in a browser
     * and NEVER in this app, whatever App Links say. [openExternal] with `allowSelf = false`
     * — the policy ladder without the two steps that can resolve to MainActivity.
     *
     * Started from the application context on purpose, so every intent (the Custom Tab
     * included) carries NEW_TASK: the caller is OpenItemActivity, a `noHistory`,
     * `taskAffinity=""` trampoline that finishes as soon as it has fired. A Custom Tab
     * allowed to stack on THAT task would be left as the sole activity of a task built to
     * be thrown away (excluded from Recents, no affinity) — the browser page belongs in
     * the browser's own task, the way a tap on a link in any other app puts it there.
     */
    fun openInBrowser(context: Context, url: String, policy: LinkPolicy): Boolean =
        openExternal(context.applicationContext ?: context, url, policy, allowSelf = false)

    /**
     * ACTION_VIEW on [uri] aimed at a browser package, resolved WITHOUT the URL's host, so
     * that App Links verification (which is what makes this app the handler for its own
     * hosts) cannot take part. The probe is `ACTION_VIEW https:` + BROWSABLE — a bare
     * scheme with no host: browsers declare `<data android:scheme="https"/>` with no host
     * and match it; this app's own filter names its hosts and does not. Resolution order:
     *
     *  1. The user's default browser (`resolveActivity` + MATCH_DEFAULT_ONLY). When there is
     *     none, the framework answers with its own resolver activity (package "android"),
     *     which is not a browser and is skipped.
     *  2. Every browser that answers the probe: Chrome first when it is among them (the
     *     decided default for this app), else whichever is first.
     *  3. A system chooser with MainActivity struck off it (EXTRA_EXCLUDE_COMPONENTS) —
     *     the phone has no visible browser, or nothing answered; the user picks.
     *
     * Visibility: the manifest's `<queries>` already declares ACTION_VIEW + BROWSABLE +
     * https, which is what lets `queryIntentActivities` see browsers at all on API 30+.
     * `CATEGORY_BROWSABLE` on the real intent would NOT do on its own: this app's own
     * filter carries BROWSABLE too, so it would still be a candidate — the package is
     * what has to be pinned.
     */
    private fun openBrowserOnly(context: Context, uri: Uri): Boolean {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.fromParts(uri.scheme ?: "https", "", null))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val self = context.packageName
        val candidates = LinkedHashSet<String>()
        runCatching { pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != self }
            ?.let { candidates.add(it) }
        val all = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull().orEmpty()
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != self }
        if (CHROME_PACKAGE in all) candidates.add(CHROME_PACKAGE)
        candidates.addAll(all)

        for (pkg in candidates) {
            val pinned = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, pinned)) return true
        }

        val plain = Intent(Intent.ACTION_VIEW, uri)
        val chooser = Intent.createChooser(plain, null)
            .putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, MainActivity::class.java)),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, chooser)
    }

    /**
     * `Intent.parseUri(url, URI_INTENT_SCHEME)`, then hardened before it is started:
     * BROWSABLE added (only activities that opted into being launched from web content),
     * explicit component and selector cleared (an intent URI may name any activity in any
     * package — the URL's `package=` hint survives as `setPackage`, which is the intended
     * routing), flags reduced to FLAG_ACTIVITY_NEW_TASK (a `launchFlags=` field could
     * otherwise ask for CLEAR_TASK or grant-URI flags), and the "browser_fallback_url" extra
     * removed so the target does not receive it as data.
     *
     * The start is attempted directly rather than pre-checked with `resolveActivity`: with
     * package visibility (API 30+) a custom-scheme target the manifest's `<queries>` does not
     * name resolves to null even when the app is installed, while `startActivity` itself is
     * not subject to visibility filtering and throws ActivityNotFoundException when nothing
     * matches — the same signal, without the false negative. On failure the URL's
     * `S.browser_fallback_url` (http(s) only) goes through [openExternal] per policy, which
     * is how an "open in app, else the website" link behaves in Chrome.
     */
    fun openIntentUri(context: Context, url: String, policy: LinkPolicy): Boolean {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            null
        }
        if (intent != null) {
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.removeExtra("browser_fallback_url")
            if (tryStart(context, intent)) return true
        }
        return intentFallbackUrl(url)?.let { openExternal(context, it, policy) } ?: false
    }

    /**
     * ACTION_VIEW on the raw scheme URL + NEW_TASK — the dialer, the mail app, Maps, Play.
     * Toasts R.string.links_no_handler itself when nothing claims the scheme (a tablet
     * without telephony tapping a tel: link), and returns whether something started.
     */
    fun openOtherScheme(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = tryStart(context, intent)
        if (!started) noHandler(context)
        return started
    }

    /** startActivity that answers "did it start" instead of throwing. */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Installed AND enabled. A disabled Chrome still answers `getPackageInfo`, but every
     * intent aimed at it throws, so the enabled bit is the one that matters. Relies on the
     * manifest's `<queries><package android:name="com.android.chrome"/>` for visibility.
     */
    private fun isPackageEnabled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The Activity behind a Context, unwrapping ContextWrappers (a ContextThemeWrapper, the
     * context a WebView hands its callbacks), or null for an application / service context.
     * `context is Activity` alone is not enough — the WebView's context is the activity's
     * only by convention, and a wrapper around it is still "from an Activity" as far as
     * startActivity's NEW_TASK requirement is concerned.
     */
    private tailrec fun activityOf(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activityOf(context.baseContext)
        else -> null
    }

    private fun noHandler(context: Context) = toastOnMain(context, R.string.links_no_handler)
}

/**
 * A toast from any thread. Files fetches on a background Thread and the bridge callback may
 * not be on the UI thread either; `Toast.makeText` off the main looper throws on some OEM
 * builds ("Can't create handler inside thread that has not called Looper.prepare()").
 * Application context, so a finishing Activity is not what the toast holds on to. The
 * message is always a fixed resource string: never a URL, a header, or an exception text.
 */
internal fun toastOnMain(context: Context, resId: Int) {
    val app = context.applicationContext ?: context
    val show = Runnable { Toast.makeText(app, resId, Toast.LENGTH_SHORT).show() }
    if (Looper.myLooper() == Looper.getMainLooper()) show.run()
    else Handler(Looper.getMainLooper()).post(show)
}
