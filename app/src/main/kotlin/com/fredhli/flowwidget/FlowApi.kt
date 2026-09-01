package com.fredhli.flowwidget

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one call the widget knows, over HttpURLConnection: a GET of the widget feed. It
 * throws [IOException] on transport failure or a non-2xx status. The token goes into the
 * Authorization header and nowhere else — never into a log, an exception message, or a
 * URL.
 *
 * Read-only on purpose. The refresh POST was removed with the header's refresh control
 * (design/BRIEF.md § "The header band"): a generation run is started from the Flow page,
 * and this file no longer has a way to issue one — [request] can only GET, so no future
 * caller can reintroduce a write from the widget by accident.
 */
object FlowApi {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BODY_BYTES = 1 shl 20 // 1 MB — the widget slice is ~1 KB

    /** GET /api/flow/widget -> raw JSON body. */
    @Throws(IOException::class)
    fun getWidgetFeed(baseUrl: String, token: String): String =
        request("$baseUrl/api/flow/widget", token)

    @Throws(IOException::class)
    private fun request(url: String, token: String): String {
        val u = URL(url)
        assertSchemeAllowed(u)
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                // Do not include headers or the URL's userinfo — code and path suffice.
                throw IOException("HTTP $code from ${u.host}${u.path}")
            }
            return conn.inputStream.use { stream ->
                // Bounded read (readNBytes needs API 33; minSdk is 31).
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < MAX_BODY_BYTES) {
                    val n = stream.read(buf, 0, minOf(buf.size, MAX_BODY_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The cleartext rule, enforced at the app's one network choke point (the platform
     * network security config cannot express IP ranges): https always; http only when
     * the host is a private-range, tailnet (CGNAT), or loopback IP LITERAL, or
     * localhost. A plain http hostname is refused outright — no DNS lookup, so no
     * rebinding game.
     */
    @Throws(IOException::class)
    fun assertSchemeAllowed(u: URL) {
        when (u.protocol) {
            "https" -> return
            "http" -> {
                if (!isPrivateHost(u.host)) {
                    throw IOException("http:// is only allowed for private-range hosts")
                }
            }
            else -> throw IOException("unsupported scheme: ${u.protocol}")
        }
    }

    /**
     * True for localhost, loopback, RFC1918, link-local and CGNAT/tailnet 100.64/10
     * (IPv4 literals), and IPv6 loopback/ULA/link-local.
     */
    fun isPrivateHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase().removeSurrounding("[", "]")
        if (h == "localhost") return true
        // IPv6 literals
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        }
        // IPv4 literal, all four octets in range
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
            if (parts[i].length > 1 && parts[i].startsWith("0")) return false
            octets[i] = v
        }
        return when {
            octets[0] == 127 -> true                                  // loopback
            octets[0] == 10 -> true                                   // 10/8
            octets[0] == 172 && octets[1] in 16..31 -> true           // 172.16/12
            octets[0] == 192 && octets[1] == 168 -> true              // 192.168/16
            octets[0] == 169 && octets[1] == 254 -> true              // link-local
            octets[0] == 100 && octets[1] in 64..127 -> true          // CGNAT — Tailscale
            else -> false
        }
    }
}
