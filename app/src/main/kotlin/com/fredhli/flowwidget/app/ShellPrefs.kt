package com.fredhli.flowwidget.app

import kotlin.math.roundToInt

/**
 * The two settings the app shell owns, and the pure functions that read them back out of
 * storage. (2.0.0 had a third, where a widget tap lands; since 2.0.1 a widget tap always
 * opens the app — see OpenItemActivity — and the setting is gone.)
 *
 * Everything in this file is deliberately free of `android.*`. Local unit tests run against
 * the `android.jar` stub whose every method throws, so anything that has to be *tested*
 * — and these are the settings a wrong value silently ruins — has to be expressible
 * in plain Kotlin. The Android side (DataStore, WebSettings, the settings screen) only ever
 * calls into here; it never re-implements a rule.
 *
 * The storage strings are part of the on-disk format: DataStore keeps whatever an older
 * build wrote, so `storageValue` is the name that must never change once shipped, and
 * `fromStorage` is the one gate that turns a stored string back into an enum. It is
 * deliberately forgiving (trimmed, case-insensitive, unknown → the default) because the
 * alternative — throwing on an unexpected value — turns a hand-edited or downgraded
 * preferences file into an app that cannot start.
 */

/** Where an off-origin link goes. Stored as the lowercase `storageValue`. */
enum class LinkPolicy(val storageValue: String) {
    /** ACTION_VIEW aimed at com.android.chrome. The decided default (plan §10 Phase 1). */
    CHROME("chrome"),

    /** A Chrome Custom Tab: stays inside the app's task, back arrow returns here. */
    CUSTOM_TAB("custom_tab"),

    /** Plain ACTION_VIEW — whatever the user set as the system browser, or the chooser. */
    DEFAULT_BROWSER("default_browser");

    companion object {
        val DEFAULT = CHROME

        /** Pure. Unknown/null → DEFAULT. Case-insensitive, trimmed. */
        fun fromStorage(value: String?): LinkPolicy {
            val v = value?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.storageValue == v } ?: DEFAULT
        }
    }
}

/**
 * The shell's settings as one value, so a caller reads them in a single DataStore snapshot
 * rather than two.
 *
 * textZoom: 0 = follow the system font scale; otherwise a percent, clamped 50..200.
 * Zero is a distinct state rather than "100", because "follow the system" has to keep
 * following it when Android's font-size slider moves — which is why it is resolved late,
 * by [effectiveTextZoom], against the live `Configuration.fontScale` instead of being
 * frozen into the stored number.
 */
data class ShellPrefs(
    val linkPolicy: LinkPolicy = LinkPolicy.DEFAULT,
    val textZoom: Int = 0,
) {
    companion object {
        const val TEXT_ZOOM_SYSTEM = 0

        /**
         * What the settings screen offers, in order. Five rungs, not a slider: WebView's
         * `textZoom` reflows the page, so the useful move is "a bit bigger than the system"
         * and not a continuous dial nobody can place twice.
         */
        val TEXT_ZOOM_CHOICES = listOf(TEXT_ZOOM_SYSTEM, 90, 100, 115, 130)

        /**
         * Pure. 0 stays 0 (the "follow the system" sentinel); anything else is pinned to
         * 50..200 — WebView accepts wilder numbers and turns the page into either a wall of
         * unreadable glyphs or a single word per line.
         */
        fun clampZoom(v: Int): Int = if (v == 0) 0 else v.coerceIn(50, 200)

        /**
         * Pure. Effective `WebSettings.textZoom` for a stored pref and the live
         * `Configuration.fontScale`.
         *
         * WebView does NOT apply the system font scale on its own — `textZoom` defaults to
         * 100 whatever the phone's font-size setting is — so "follow the system" is this
         * multiplication, done by us. Fred runs a Fold with a non-default font size; without
         * this the page would be the one surface on the phone that ignores it.
         */
        fun effectiveTextZoom(pref: Int, fontScale: Float): Int =
            if (pref == TEXT_ZOOM_SYSTEM) (fontScale * 100).roundToInt().coerceIn(50, 200)
            else clampZoom(pref)
    }
}
