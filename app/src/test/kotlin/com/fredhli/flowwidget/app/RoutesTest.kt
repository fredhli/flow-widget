package com.fredhli.flowwidget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's "is this URL ours?" and route helpers (spec §2.1, §9.1). Pure, plain JVM. */
class RoutesTest {

    // ---- originOf ---------------------------------------------------------------------------

    @Test
    fun `https default port dropped`() {
        assertEquals("https://dashboard.fredhli.com", Routes.originOf("https://dashboard.fredhli.com/#/flow"))
        assertEquals("https://dashboard.fredhli.com", Routes.originOf("https://dashboard.fredhli.com:443/x"))
        assertEquals("http://192.168.1.50", Routes.originOf("http://192.168.1.50:80/"))
    }

    @Test
    fun `explicit non-default port kept`() {
        assertEquals("https://dashboard.fredhli.com:8443", Routes.originOf("https://dashboard.fredhli.com:8443/"))
        assertEquals("http://192.168.1.50:8000", Routes.originOf("http://192.168.1.50:8000/#/flow"))
    }

    @Test
    fun `http private ip`() {
        assertEquals("http://100.102.21.29", Routes.originOf("http://100.102.21.29/api/x.pdf?y=1"))
    }

    @Test
    fun `uppercase scheme and host lowercased`() {
        assertEquals("https://dashboard.fredhli.com", Routes.originOf("HTTPS://Dashboard.FredHLI.com/"))
    }

    @Test
    fun `no authority or non-http scheme is null`() {
        assertNull(Routes.originOf("about:blank"))
        assertNull(Routes.originOf("javascript:void(0)"))
        assertNull(Routes.originOf("mailto:someone@example.com"))
        assertNull(Routes.originOf("intent://scan#Intent;scheme=zxing;end"))
        assertNull(Routes.originOf("https://"))
        assertNull(Routes.originOf("https://:8080/"))
    }

    @Test
    fun `garbage is null`() {
        assertNull(Routes.originOf(null))
        assertNull(Routes.originOf(""))
        assertNull(Routes.originOf("not a url"))
        assertNull(Routes.originOf("https://host:notaport/"))
        assertNull(Routes.originOf("https://host:70000/"))
    }

    @Test
    fun `userinfo dropped and ipv6 kept`() {
        assertEquals("https://dashboard.fredhli.com", Routes.originOf("https://user:pw@dashboard.fredhli.com/"))
        assertEquals("http://[::1]:8000", Routes.originOf("http://[::1]:8000/"))
        assertEquals("http://[fd7a::1]", Routes.originOf("http://[FD7A::1]/"))
    }

    @Test
    fun `backslash ends the authority like chromium`() {
        // WHATWG: '\' in an http(s) URL is a path separator, so Chromium navigates this to
        // evil.com — the origin must say so, or "evil.com\" would pass for userinfo.
        assertEquals("https://evil.com", Routes.originOf("https://evil.com\\@dashboard.fredhli.com/"))
        assertFalse(
            Routes.isAppOrigin(
                "https://evil.com\\@dashboard.fredhli.com/",
                setOf("https://dashboard.fredhli.com"),
            ),
        )
        // The other way round the host is still ours; the backslash just starts the path.
        assertEquals("https://dashboard.fredhli.com", Routes.originOf("https://dashboard.fredhli.com\\evil.com/"))
        // An empty authority is no origin at all.
        assertNull(Routes.originOf("https://\\evil.com/"))
    }

    // ---- appOrigins / isAppOrigin -----------------------------------------------------------

    @Test
    fun `appOrigins of a shipped host has two entries`() {
        val o = Routes.appOrigins("https://dashboard.fredhli.com")
        assertEquals(2, o.size)
        assertTrue("https://dashboard.fredhli.com" in o)
        assertTrue("https://dashboard-chl.fredhli.com" in o)
    }

    @Test
    fun `appOrigins of a private http base has three entries`() {
        val o = Routes.appOrigins("http://192.168.1.50:8000")
        assertEquals(3, o.size)
        assertTrue("http://192.168.1.50:8000" in o)
        assertTrue(Routes.isAppOrigin("http://192.168.1.50:8000/#/jht", o))
        assertTrue(Routes.isAppOrigin("https://dashboard-chl.fredhli.com/", o))
        assertFalse(Routes.isAppOrigin("http://192.168.1.50:8001/", o))
        assertFalse(Routes.isAppOrigin("https://example.com/", o))
        assertFalse(Routes.isAppOrigin(null, o))
    }

    @Test
    fun `appOrigins tolerates a trailing slash and an unparsable base`() {
        assertEquals(2, Routes.appOrigins("https://dashboard.fredhli.com/").size)
        assertEquals(2, Routes.appOrigins("garbage").size)
    }

    // ---- routeOf / pathOf --------------------------------------------------------------------

    @Test
    fun `routeOf picks the hash route`() {
        assertEquals("#/flow/i/abc", Routes.routeOf("https://dashboard.fredhli.com/#/flow/i/abc"))
        assertEquals("#/flow", Routes.routeOf("https://dashboard.fredhli.com/?k=x#/flow"))
        assertNull(Routes.routeOf("https://dashboard.fredhli.com/"))
        assertNull(Routes.routeOf("https://dashboard.fredhli.com/#top"))
        assertNull(Routes.routeOf("https://dashboard.fredhli.com/#"))
        assertNull(Routes.routeOf(null))
    }

    @Test
    fun `pathOf strips query and fragment`() {
        assertEquals("/", Routes.pathOf("https://dashboard.fredhli.com"))
        assertEquals("/", Routes.pathOf("https://dashboard.fredhli.com/#/flow"))
        assertEquals("/api/x.pdf", Routes.pathOf("https://dashboard.fredhli.com/api/x.pdf?v=1#x"))
        assertEquals("", Routes.pathOf("about:blank"))
        assertEquals("", Routes.pathOf(null))
    }

    @Test
    fun `stripQuery and stripFragment`() {
        assertEquals("https://h/p#/flow", Routes.stripQuery("https://h/p?k=x#/flow"))
        assertEquals("https://h/p", Routes.stripQuery("https://h/p?k=x"))
        assertEquals("https://h/p#/a?b", Routes.stripQuery("https://h/p#/a?b"))
        assertEquals("https://h/p", Routes.stripQuery("https://h/p"))
        assertEquals("https://h/p?k=x", Routes.stripFragment("https://h/p?k=x#/flow"))
        assertNull(Routes.stripFragment(null))
    }

    // ---- normaliseRoute ---------------------------------------------------------------------

    @Test
    fun `normaliseRoute defaults for null and blank`() {
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute(null))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute(""))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("   "))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/"))
    }

    @Test
    fun `normaliseRoute accepts the usual spellings`() {
        assertEquals("#/flow", Routes.normaliseRoute("flow"))
        assertEquals("#/flow", Routes.normaliseRoute("/flow"))
        assertEquals("#/flow", Routes.normaliseRoute("#flow"))
        assertEquals("#/flow", Routes.normaliseRoute("#/flow"))
        assertEquals("#/flow", Routes.normaliseRoute(" #/flow "))
        assertEquals("#/flow/i/abc", Routes.normaliseRoute("#/flow/i/abc"))
        assertEquals("#/smart-beta", Routes.normaliseRoute("smart-beta"))
    }

    @Test
    fun `normaliseRoute refuses anything that could not be a hash`() {
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/x y"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/a\"b"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/a'b"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/<script>"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/a\\b"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/a\nb"))
        assertEquals(Routes.DEFAULT_ROUTE, Routes.normaliseRoute("#/a\u0000b"))
    }

    // ---- pageUrl ------------------------------------------------------------------------------

    @Test
    fun `pageUrl with and without trailing slash`() {
        assertEquals("https://dashboard.fredhli.com/#/flow", Routes.pageUrl("https://dashboard.fredhli.com", null))
        assertEquals("https://dashboard.fredhli.com/#/flow", Routes.pageUrl("https://dashboard.fredhli.com/", "#/flow"))
        assertEquals("https://dashboard.fredhli.com/#/jht", Routes.pageUrl("https://dashboard.fredhli.com//", "jht"))
        assertEquals("http://192.168.1.50:8000/#/flow/i/abc", Routes.pageUrl(" http://192.168.1.50:8000 ", "/flow/i/abc"))
    }

    // ---- jsStringLiteral -----------------------------------------------------------------------

    @Test
    fun `jsStringLiteral escapes what could break out of a literal`() {
        assertEquals("\"plain\"", Routes.jsStringLiteral("plain"))
        assertEquals("\"a\\\"b\"", Routes.jsStringLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", Routes.jsStringLiteral("a\\b"))
        assertEquals("\"a\\nb\\rc\"", Routes.jsStringLiteral("a\nb\rc"))
        assertEquals("\"\\u003C/script>\"", Routes.jsStringLiteral("</script>"))
        assertEquals("\"\\u2028\\u2029\"", Routes.jsStringLiteral("\u2028\u2029"))
        assertEquals("\"\\u0001\"", Routes.jsStringLiteral("\u0001"))
        assertEquals("\"https://h/#/flow?q='x'\"", Routes.jsStringLiteral("https://h/#/flow?q='x'"))
    }
}
