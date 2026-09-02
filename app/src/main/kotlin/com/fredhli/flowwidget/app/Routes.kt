package com.fredhli.flowwidget.app

/**
 * Pure URL / route helpers for the shell. No android.* import on purpose: these run in
 * plain-JVM unit tests where the android.jar stubs throw, and they are the one place the
 * shell decides "is this URL ours?" — so they must be testable without a device.
 *
 * Everything here is string work. java.net.URI was considered and rejected: it throws on
 * URLs the WebView happily reports (a space in a fragment, an underscore in a host), and
 * the hot-path comparison `originOf(webView.url) == originOf(pendingUrl)` must never throw.
 */
object Routes {

    /** The two hosts the APK is shipped for; both are "app origins" on every install. */
    val APP_HOSTS: Set<String> = setOf("dashboard.fredhli.com", "dashboard-chl.fredhli.com")

    const val DEFAULT_ROUTE = "#/flow"

    /** `scheme://authority` — the part of a URL an origin is made of. */
    private val AUTHORITY = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")

    /**
     * "https://host[:port]" — lowercased scheme+host, explicit port kept only if non-default;
     * null when unparsable or without a host (about:blank, javascript:, mailto:). Only http
     * and https have origins the shell cares about: an `intent://scan` URL parses to a
     * "host" but must never compare equal to anything.
     */
    fun originOf(url: String?): String? {
        val m = AUTHORITY.find(url?.trim() ?: return null) ?: return null
        val scheme = m.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return null
        // userinfo@ is legal in a URL and irrelevant to the origin.
        val authority = m.groupValues[2].substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            // IPv6 literal: the colon inside the brackets is not a port separator.
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                portText = authority.substring(colon + 1)
            } else {
                host = authority
                portText = null
            }
        }
        if (host.isEmpty()) return null
        val port = if (portText.isNullOrEmpty()) -1
        else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val default = if (scheme == "https") 443 else 80
        val h = host.lowercase()
        return if (port == -1 || port == default) "$scheme://$h" else "$scheme://$h:$port"
    }

    /** originOf(baseUrl) ∪ { "https://$h" for h in APP_HOSTS }. baseUrl may be http://<private ip>. */
    fun appOrigins(baseUrl: String): Set<String> {
        val out = LinkedHashSet<String>()
        originOf(baseUrl)?.let { out.add(it) }
        for (h in APP_HOSTS) out.add("https://$h")
        return out
    }

    fun isAppOrigin(url: String?, appOrigins: Set<String>): Boolean {
        val o = originOf(url) ?: return false
        return o in appOrigins
    }

    /**
     * The dashboard route of a URL: its fragment with the leading '#', only when the fragment
     * starts with "/" ("#/flow/i/abc"). Null when there is no fragment or it does not start
     * with '/'. Query is ignored.
     */
    fun routeOf(url: String?): String? {
        val u = url ?: return null
        val hash = u.indexOf('#')
        if (hash < 0) return null
        val fragment = u.substring(hash + 1)
        if (!fragment.startsWith("/")) return null
        return "#$fragment"
    }

    /** The path of a URL ("/" when empty), query and fragment stripped; "" when not a URL. */
    fun pathOf(url: String?): String {
        val u = url?.trim() ?: return ""
        val m = AUTHORITY.find(u) ?: return ""
        var rest = u.substring(m.range.last + 1)
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        return if (rest.isEmpty()) "/" else rest
    }

    /** The URL without its "#fragment"; null stays null. Same-document comparisons use this. */
    fun stripFragment(url: String?): String? = url?.substringBefore('#')

    /**
     * The URL without its "?query" (fragment kept): "https://h/p?k=x#/flow" → "https://h/p#/flow".
     * Used before a URL is shown or saved anywhere — a query may carry a token.
     */
    fun stripQuery(url: String): String {
        val hash = url.indexOf('#')
        val q = url.indexOf('?')
        if (q < 0 || (hash in 0 until q)) return url
        return if (hash < 0) url.substring(0, q) else url.substring(0, q) + url.substring(hash)
    }

    /**
     * Anything → a route: null/blank → DEFAULT_ROUTE; "flow" → "#/flow"; "/flow" → "#/flow";
     * "#/flow" unchanged; "#flow" → "#/flow"; trimmed; any whitespace, quote, '<', '>',
     * backslash or control char inside → DEFAULT_ROUTE. Routes are interpolated into JS and
     * URLs, so a route that could not have come from the dashboard's own hash is refused
     * wholesale rather than sanitised piecemeal.
     */
    fun normaliseRoute(route: String?): String {
        val t = route?.trim().orEmpty()
        if (t.isEmpty()) return DEFAULT_ROUTE
        if (t.any { it.isWhitespace() || it in "\"'<>\\`" || it.code < 0x20 || it.code == 0x7f }) {
            return DEFAULT_ROUTE
        }
        val body = t.trimStart('#', '/')
        if (body.isEmpty()) return DEFAULT_ROUTE
        return "#/$body"
    }

    /** baseUrl.trimEnd('/') + "/" + normaliseRoute(route) → "https://dashboard.fredhli.com/#/flow". */
    fun pageUrl(baseUrl: String, route: String?): String =
        baseUrl.trim().trimEnd('/') + "/" + normaliseRoute(route)

    /**
     * A double-quoted JS string literal: backslash, double quote, \n, \r, U+2028/U+2029 and
     * '<' escaped (so "</script>" can never terminate an inline script). Other control
     * characters go out as \uXXXX so the literal is always a single line.
     */
    fun jsStringLiteral(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003C")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04X", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
