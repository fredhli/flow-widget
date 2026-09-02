package com.fredhli.flowwidget.app

import com.fredhli.flowwidget.app.Links.Nav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation gate's decision matrix, on the plain JVM. Only the pure half of Links is
 * exercised (classify / isIntentUri / intentFallbackUrl); the Android half dispatches
 * Intents and cannot run against the android.jar stub.
 */
class LinksTest {

    private val origins = setOf("https://dashboard.fredhli.com", "https://dashboard-chl.fredhli.com")

    @Test
    fun `same origin stays in app`() {
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/", origins))
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com", origins))
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/#/flow", origins))
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/#/flow/i/abc?x=1", origins))
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/login?next=%2F", origins))
    }

    @Test
    fun `api paths on the app origin are documents`() {
        assertEquals(Nav.APP_DOCUMENT, Links.classify("https://dashboard.fredhli.com/api/x.pdf", origins))
        assertEquals(
            Nav.APP_DOCUMENT,
            Links.classify("https://dashboard.fredhli.com/api/jht/jobs/42/cl.pdf?k=secret", origins),
        )
        // "/api" without the trailing slash and "/apix" are not the API prefix.
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/apix/x.pdf", origins))
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/api", origins))
    }

    @Test
    fun `the other app host is in app too`() {
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard-chl.fredhli.com/#/morning", origins))
        assertEquals(Nav.APP_DOCUMENT, Links.classify("https://dashboard-chl.fredhli.com/api/brief/raw", origins))
    }

    @Test
    fun `private http base url is in app only when the origin set says so`() {
        val lan = origins + "http://192.168.1.50:8000"
        assertEquals(Nav.IN_APP, Links.classify("http://192.168.1.50:8000/#/flow", lan))
        assertEquals(Nav.APP_DOCUMENT, Links.classify("http://192.168.1.50:8000/api/x.csv", lan))
        // Same host, different port: a different origin.
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("http://192.168.1.50:8001/#/flow", lan))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("http://192.168.1.50:8000/#/flow", origins))
    }

    @Test
    fun `other http and https hosts are external`() {
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://example.com", origins))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("http://example.com", origins))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://example.com/api/x.pdf", origins))
        // Userinfo spoof: the host is evil.com, whatever is before the '@'.
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://dashboard.fredhli.com@evil.com/", origins))
        // A subdomain or a lookalike is not the origin.
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://x.dashboard.fredhli.com/", origins))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://dashboard.fredhli.com.evil.com/", origins))
    }

    @Test
    fun `explicit default port matches the origin and a non-default one does not`() {
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com:443/#/flow", origins))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://dashboard.fredhli.com:8443/#/flow", origins))
    }

    @Test
    fun `scheme and host are case-insensitive`() {
        assertEquals(Nav.IN_APP, Links.classify("HTTPS://DASHBOARD.FREDHLI.COM/#/flow", origins))
        assertEquals(Nav.APP_DOCUMENT, Links.classify("Https://Dashboard.Fredhli.Com/api/x.pdf", origins))
        assertEquals(Nav.INTENT_URI, Links.classify("INTENT://scan/#Intent;scheme=zxing;end", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("MAILTO:someone@example.com", origins))
    }

    @Test
    fun `characters java net URI rejects still classify`() {
        // '|' raw in a query is a URISyntaxException for java.net.URI and a normal link for Chromium.
        assertEquals(Nav.IN_APP, Links.classify("https://dashboard.fredhli.com/?q=a|b", origins))
        assertEquals(Nav.EXTERNAL_HTTP, Links.classify("https://example.com/?q=a|b", origins))
        assertEquals(Nav.APP_DOCUMENT, Links.classify("https://dashboard.fredhli.com/api/x?q=a|b", origins))
    }

    @Test
    fun `intent uris`() {
        assertEquals(
            Nav.INTENT_URI,
            Links.classify("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end", origins),
        )
        assertEquals(Nav.INTENT_URI, Links.classify("intent:#Intent;action=android.intent.action.VIEW;end", origins))
    }

    @Test
    fun `other schemes with a stock handler`() {
        assertEquals(Nav.OTHER_SCHEME, Links.classify("mailto:someone@example.com?subject=hi", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("tel:+61400000000", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("sms:+61400000000", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("smsto:+61400000000", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("geo:0,0?q=Sydney", origins))
        assertEquals(Nav.OTHER_SCHEME, Links.classify("market://details?id=com.android.chrome", origins))
    }

    @Test
    fun `blocked schemes and garbage`() {
        assertEquals(Nav.BLOCKED, Links.classify("javascript:alert(1)", origins))
        assertEquals(Nav.BLOCKED, Links.classify("JavaScript:alert(1)", origins))
        assertEquals(Nav.BLOCKED, Links.classify("file:///etc/hosts", origins))
        assertEquals(Nav.BLOCKED, Links.classify("content://com.android.contacts/contacts", origins))
        assertEquals(Nav.BLOCKED, Links.classify("data:text/html,<script>1</script>", origins))
        assertEquals(Nav.BLOCKED, Links.classify("blob:https://dashboard.fredhli.com/uuid", origins))
        assertEquals(Nav.BLOCKED, Links.classify("about:blank", origins))
        assertEquals(Nav.BLOCKED, Links.classify("ftp://example.com/x", origins))
        assertEquals(Nav.BLOCKED, Links.classify(null, origins))
        assertEquals(Nav.BLOCKED, Links.classify("", origins))
        assertEquals(Nav.BLOCKED, Links.classify("   ", origins))
        assertEquals(Nav.BLOCKED, Links.classify("/relative/path", origins))
        assertEquals(Nav.BLOCKED, Links.classify("not a url", origins))
        assertEquals(Nav.BLOCKED, Links.classify("https:///nohost", origins))
    }

    @Test
    fun `isIntentUri`() {
        assertTrue(Links.isIntentUri("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(Links.isIntentUri("INTENT:#Intent;end"))
        assertTrue(Links.isIntentUri("  intent:x"))
        assertFalse(Links.isIntentUri("https://example.com/intent:"))
        assertFalse(Links.isIntentUri("intents://x"))
        assertFalse(Links.isIntentUri(""))
        assertFalse(Links.isIntentUri(null))
    }

    @Test
    fun `intent fallback url present`() {
        val url = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fget%3Fa%3D1%26b%3Dx%2By;end"
        assertEquals("https://example.com/get?a=1&b=x+y", Links.intentFallbackUrl(url))
    }

    @Test
    fun `intent fallback url may be unencoded and http`() {
        val url = "intent://x/#Intent;scheme=foo;S.browser_fallback_url=http://example.com/a;end"
        assertEquals("http://example.com/a", Links.intentFallbackUrl(url))
    }

    @Test
    fun `intent fallback url absent`() {
        assertNull(Links.intentFallbackUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(Links.intentFallbackUrl("intent://scan/#Intent;S.browser_fallback_url=;end"))
        assertNull(Links.intentFallbackUrl("https://example.com/no-intent-fragment"))
        assertNull(Links.intentFallbackUrl(""))
    }

    @Test
    fun `intent fallback url that is not http is dropped`() {
        assertNull(
            Links.intentFallbackUrl("intent://x/#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"),
        )
        assertNull(
            Links.intentFallbackUrl("intent://x/#Intent;S.browser_fallback_url=intent%3A%23Intent%3Bend;end"),
        )
        assertNull(Links.intentFallbackUrl("intent://x/#Intent;S.browser_fallback_url=file%3A%2F%2F%2Fetc;end"))
        // Malformed percent-escape: not decodable, so not a URL we open.
        assertNull(Links.intentFallbackUrl("intent://x/#Intent;S.browser_fallback_url=https%ZZ;end"))
    }
}
