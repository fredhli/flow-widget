package com.fredhli.flowwidget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Who owns the keyboard inset, decided from the WebView provider's version string. */
class InsetsTest {

    @Test
    fun `majorVersion reads the leading number`() {
        assertEquals(144, Insets.majorVersion("144.0.7559.24"))
        assertEquals(143, Insets.majorVersion("143.0.1"))
        assertEquals(144, Insets.majorVersion("144"))
        assertEquals(150, Insets.majorVersion(" 150.0.0.0 "))
    }

    @Test
    fun `majorVersion is null for garbage`() {
        assertNull(Insets.majorVersion(null))
        assertNull(Insets.majorVersion(""))
        assertNull(Insets.majorVersion("abc"))
        assertNull(Insets.majorVersion("v144"))
        assertNull(Insets.majorVersion("99999999999999999999.1"))
    }

    @Test
    fun `imeModeFor is WEBVIEW from 144 and NATIVE below or unknown`() {
        assertEquals(Insets.ImeMode.NATIVE, Insets.imeModeFor("143.0.1"))
        assertEquals(Insets.ImeMode.WEBVIEW, Insets.imeModeFor("144"))
        assertEquals(Insets.ImeMode.WEBVIEW, Insets.imeModeFor("144.0.7559.24"))
        assertEquals(Insets.ImeMode.WEBVIEW, Insets.imeModeFor("151.0.1"))
        assertEquals(Insets.ImeMode.NATIVE, Insets.imeModeFor(null))
        assertEquals(Insets.ImeMode.NATIVE, Insets.imeModeFor("abc"))
        assertEquals(144, Insets.IME_IN_WEBVIEW_FROM_MAJOR)
    }
}
