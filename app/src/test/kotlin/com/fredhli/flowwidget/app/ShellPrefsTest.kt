package com.fredhli.flowwidget.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shell's three settings, read back out of storage. Plain JVM: nothing in ShellPrefs.kt
 * imports `android.*`, which is the point — the stub android.jar in a local unit test throws
 * from every method, so a rule that has to be tested cannot live on the Android side.
 *
 * What these tests actually protect: a preferences file written by another build (or by
 * hand) must never make the app unusable, and "follow the system font scale" must be the
 * multiplication WebView does not do for us.
 */
class ShellPrefsTest {

    @Test
    fun `link policy round trips every stored value`() {
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage("chrome"))
        assertEquals(LinkPolicy.CUSTOM_TAB, LinkPolicy.fromStorage("custom_tab"))
        assertEquals(LinkPolicy.DEFAULT_BROWSER, LinkPolicy.fromStorage("default_browser"))
        // Every enum's own storageValue must map back to it — the check that catches a
        // renamed constant whose stored string was not renamed with it.
        for (policy in LinkPolicy.entries) {
            assertEquals(policy, LinkPolicy.fromStorage(policy.storageValue))
        }
    }

    @Test
    fun `link policy falls back to chrome`() {
        assertEquals(LinkPolicy.DEFAULT, LinkPolicy.CHROME)
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage(null))
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage(""))
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage("CHROME "))
        // Trimmed and case-insensitive, so this one resolves rather than falling back.
        assertEquals(LinkPolicy.CUSTOM_TAB, LinkPolicy.fromStorage("  Custom_Tab  "))
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage("firefox"))
        assertEquals(LinkPolicy.CHROME, LinkPolicy.fromStorage("chrome browser"))
    }

    @Test
    fun `tap target round trips and falls back to app`() {
        assertEquals(TapTarget.APP, TapTarget.fromStorage("app"))
        assertEquals(TapTarget.BROWSER, TapTarget.fromStorage("browser"))
        for (target in TapTarget.entries) {
            assertEquals(target, TapTarget.fromStorage(target.storageValue))
        }
        assertEquals(TapTarget.DEFAULT, TapTarget.APP)
        assertEquals(TapTarget.APP, TapTarget.fromStorage(null))
        assertEquals(TapTarget.APP, TapTarget.fromStorage(""))
        assertEquals(TapTarget.APP, TapTarget.fromStorage(" APP "))
        assertEquals(TapTarget.BROWSER, TapTarget.fromStorage("Browser "))
        assertEquals(TapTarget.APP, TapTarget.fromStorage("chrome"))
    }

    @Test
    fun `clamp zoom keeps the system sentinel and pins the rest`() {
        assertEquals(0, ShellPrefs.clampZoom(0))       // 0 is "follow the system", not a percent
        assertEquals(50, ShellPrefs.clampZoom(49))
        assertEquals(50, ShellPrefs.clampZoom(50))
        assertEquals(200, ShellPrefs.clampZoom(200))
        assertEquals(200, ShellPrefs.clampZoom(201))
        assertEquals(115, ShellPrefs.clampZoom(115))
        assertEquals(50, ShellPrefs.clampZoom(-300))
    }

    @Test
    fun `effective text zoom follows the font scale only when the pref is system`() {
        assertEquals(115, ShellPrefs.effectiveTextZoom(0, 1.15f))
        assertEquals(100, ShellPrefs.effectiveTextZoom(0, 1f))
        assertEquals(130, ShellPrefs.effectiveTextZoom(130, 1.3f))
        // A pinned percent ignores the system scale entirely — that is what pinning means.
        assertEquals(130, ShellPrefs.effectiveTextZoom(130, 1f))
        assertEquals(90, ShellPrefs.effectiveTextZoom(90, 2f))
        // One UI's largest accessibility scales overshoot; the clamp is the guard.
        assertEquals(200, ShellPrefs.effectiveTextZoom(0, 3f))
        assertEquals(50, ShellPrefs.effectiveTextZoom(0, 0.1f))
    }

    @Test
    fun `the offered choices are the ones the settings screen can store`() {
        assertEquals(listOf(0, 90, 100, 115, 130), ShellPrefs.TEXT_ZOOM_CHOICES)
        assertEquals(ShellPrefs.TEXT_ZOOM_SYSTEM, ShellPrefs.TEXT_ZOOM_CHOICES.first())
        for (choice in ShellPrefs.TEXT_ZOOM_CHOICES) {
            assertEquals(choice, ShellPrefs.clampZoom(choice))
        }
    }

    @Test
    fun `the defaults are the ones a 1_1_1 install upgrades into`() {
        val defaults = ShellPrefs()
        assertEquals(TapTarget.APP, defaults.tapTarget)
        assertEquals(LinkPolicy.CHROME, defaults.linkPolicy)
        assertEquals(ShellPrefs.TEXT_ZOOM_SYSTEM, defaults.textZoom)
    }
}
