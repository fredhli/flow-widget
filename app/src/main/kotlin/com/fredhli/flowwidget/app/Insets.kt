package com.fredhli.flowwidget.app

/**
 * Pure. Decides who owns the IME (keyboard) inset — the WebView or the shell.
 *
 * The page runs edge-to-edge and pads itself with env(safe-area-inset-*) for the system
 * bars, but the keyboard is a different animal: a 100dvh layout has to *shrink* when the
 * keyboard comes up or the reader's memo textarea ends up underneath it. Chromium 144+
 * handles that inside the WebView (the IME shrinks the visual viewport, the same
 * interactive-widget semantics Chrome uses), so the shell must hand the ime() inset
 * through untouched. Older WebViews do not, so the shell pads the container natively
 * instead (spec §5). Doing both at once would double the gap; doing neither hides the
 * caret. The version string comes from WebViewCompat.getCurrentWebViewPackage; a missing
 * or garbled one means the conservative, native path.
 */
object Insets {

    enum class ImeMode { WEBVIEW, NATIVE }

    /** First Chromium major that shrinks the visual viewport for the IME on its own. */
    const val IME_IN_WEBVIEW_FROM_MAJOR = 144

    /** "144.0.7559.24" → 144; null/garbage → null. Leading whitespace tolerated. */
    fun majorVersion(versionName: String?): Int? {
        val digits = versionName?.trim()?.takeWhile { it.isDigit() } ?: return null
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()
    }

    /** ≥ 144 → WEBVIEW (Chromium shrinks the visual viewport itself), else NATIVE. */
    fun imeModeFor(versionName: String?): ImeMode {
        val major = majorVersion(versionName) ?: return ImeMode.NATIVE
        return if (major >= IME_IN_WEBVIEW_FROM_MAJOR) ImeMode.WEBVIEW else ImeMode.NATIVE
    }
}
