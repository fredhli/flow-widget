package com.fredhli.flowwidget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the bridge (spec §3): message parsing and the theme-colour luminance
 * test. org.json is the real implementation on the test classpath.
 */
class BridgeTest {

    @Test
    fun `parse share with name`() {
        val m = Bridge.parse("""{"t":"share","url":"https://dashboard.fredhli.com/api/x.pdf","name":"cv.pdf"}""")
        assertEquals(Bridge.Msg.Share("https://dashboard.fredhli.com/api/x.pdf", "cv.pdf"), m)
    }

    @Test
    fun `parse share without name gives a null name`() {
        val m = Bridge.parse("""{"t":"share","url":"https://dashboard.fredhli.com/api/x.pdf"}""")
        assertEquals(Bridge.Msg.Share("https://dashboard.fredhli.com/api/x.pdf", null), m)
        val n = Bridge.parse("""{"t":"share","url":"https://dashboard.fredhli.com/api/x.pdf","name":null}""")
        assertEquals(Bridge.Msg.Share("https://dashboard.fredhli.com/api/x.pdf", null), n)
    }

    @Test
    fun `parse share keeps an empty name for the caller to blank`() {
        // The façade sends String(name || "") — an empty string, not an absent key.
        val m = Bridge.parse("""{"t":"share","url":"https://h/api/x.pdf","name":""}""") as Bridge.Msg.Share
        assertEquals("", m.name)
    }

    @Test
    fun `parse open`() {
        assertEquals(Bridge.Msg.Open("https://example.com/"), Bridge.parse("""{"t":"open","url":"https://example.com/"}"""))
    }

    @Test
    fun `parse theme`() {
        assertEquals(Bridge.Msg.Theme("#131C2E"), Bridge.parse("""{"t":"theme","hex":"#131C2E"}"""))
    }

    @Test
    fun `parse metrics`() {
        assertEquals(Bridge.Msg.Metrics, Bridge.parse("""{"t":"metrics"}"""))
    }

    @Test
    fun `parse rejects unknown, malformed and incomplete messages`() {
        assertNull(Bridge.parse("""{"t":"reboot"}"""))
        assertNull(Bridge.parse("""{"url":"https://example.com/"}"""))
        assertNull(Bridge.parse("not json"))
        assertNull(Bridge.parse(""))
        assertNull(Bridge.parse("[1,2,3]"))
        assertNull(Bridge.parse("""{"t":"share"}"""))
        assertNull(Bridge.parse("""{"t":"share","url":""}"""))
        assertNull(Bridge.parse("""{"t":"open","url":42}"""))
        assertNull(Bridge.parse("""{"t":"theme"}"""))
    }

    @Test
    fun `isLightColor on full and short forms`() {
        assertEquals(true, Bridge.isLightColor("#FFFFFF"))
        assertEquals(false, Bridge.isLightColor("#0B1220"))
        assertEquals(true, Bridge.isLightColor("#fff"))
        assertEquals(false, Bridge.isLightColor("#000"))
        assertEquals(true, Bridge.isLightColor("#F4F6FB"))
        assertEquals(false, Bridge.isLightColor("#131C2E"))
        assertEquals(true, Bridge.isLightColor("FFFFFF"))
        assertEquals(true, Bridge.isLightColor("#FFFFFF80"))
        assertEquals(false, Bridge.isLightColor("#0B1220FF"))
        assertEquals(false, Bridge.isLightColor("#000F"))
    }

    @Test
    fun `isLightColor is null for anything else`() {
        assertNull(Bridge.isLightColor("red"))
        assertNull(Bridge.isLightColor(""))
        assertNull(Bridge.isLightColor("#"))
        assertNull(Bridge.isLightColor("#12345"))
        assertNull(Bridge.isLightColor("#GGGGGG"))
        assertNull(Bridge.isLightColor("rgb(0,0,0)"))
    }

    @Test
    fun `constants match the contract`() {
        assertEquals("NativeBridge", Bridge.OBJECT_NAME)
        assertEquals(" DashboardApp/2.0", Bridge.UA_SUFFIX)
        assertTrue(Bridge.FACADE_JS.contains("window.NativeBridge"))
        assertTrue(Bridge.FACADE_JS.contains("version: \"2.0\""))
        assertFalse(Bridge.FACADE_JS.contains('$'))
    }
}
