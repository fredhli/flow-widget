package com.fredhli.flowwidget

/**
 * The round-3 behaviour settings (design/BRIEF.md, Round 3): three small enums stored as
 * strings in the DataStore. Pure by design so the defaults-and-migration contract is
 * unit-testable off-device: an ABSENT key is the shipped default (which is exactly the
 * pre-round-3 behaviour, so an upgraded install changes nothing until Fred opens the
 * config screen), and a junk value — a typo'd adb seed, a future build's renamed constant
 * — normalises to the default rather than throwing or half-applying.
 */
object WidgetSettings {

    // ------------------------------------------------------------------ title font

    /**
     * "Title font" (config dropdown). Applied to ROW TITLES only — the "Flow" header
     * stays the system sans at Bold, per the brief.
     *
     * FONT_DEFAULT sets no fontFamily at all, which is what keeps One UI Sans on the
     * Fold (RESEARCH §2: naming any family replaces DeviceDefault). The other two are
     * deliberate replacements, passed verbatim through Glance's FontFamily(String) →
     * TypefaceSpan — the passthrough round 2 verified against the 1.1.1 bytecode.
     * Serif is the visibly-different one in CJK (Noto Serif CJK on stock, a Ming-face
     * on One UI); sans-serif-medium trades One UI Sans for Roboto Medium, worth having
     * as an explicit choice precisely because it looks different from the default.
     */
    const val FONT_DEFAULT = "default"
    const val FONT_MEDIUM = "medium"
    const val FONT_SERIF = "serif"

    val FONTS = listOf(FONT_DEFAULT, FONT_MEDIUM, FONT_SERIF)

    fun titleFont(raw: String?): String = if (raw in FONTS) raw!! else FONT_DEFAULT

    /** The platform family string Glance passes into TypefaceSpan, or null for DeviceDefault. */
    fun fontFamilyFor(font: String): String? = when (titleFont(font)) {
        FONT_MEDIUM -> "sans-serif-medium"
        FONT_SERIF -> "serif"
        else -> null
    }

    // ------------------------------------------------------------------ tap on an item

    /** "Tap on an item": open the dashboard (the pre-round-3 behaviour) … */
    const val TAP_DASHBOARD = "dashboard"

    /** … or expand a plain-text body inline under the row (and mark that item read). */
    const val TAP_EXPAND = "expand"

    val TAP_MODES = listOf(TAP_DASHBOARD, TAP_EXPAND)

    fun tapMode(raw: String?): String = if (raw in TAP_MODES) raw!! else TAP_DASHBOARD

    // ------------------------------------------------------------------ open links with

    /** "Open links with": the plain VIEW intent (whatever the system resolves — default). */
    const val LINK_DASHBOARD = "dashboard"

    /** VIEW pinned to com.android.chrome, falling back to plain VIEW when Chrome is missing. */
    const val LINK_CHROME = "chrome"

    const val CHROME_PACKAGE = "com.android.chrome"

    val LINK_APPS = listOf(LINK_DASHBOARD, LINK_CHROME)

    fun linkApp(raw: String?): String = if (raw in LINK_APPS) raw!! else LINK_DASHBOARD

    // ------------------------------------------------------------------ read ids

    /**
     * Expanding a row marks THAT item read (round 3 item 4a) — which the widget's
     * timestamp-based unread rule cannot express (KEY_LAST_OPEN clears every dot at
     * once). So expand-mode keeps a small per-item read list beside it, stored as one
     * newline-joined string rather than a Set preference: a string preserves insertion
     * order, which makes the cap's oldest-first trim deterministic and testable. The cap
     * only exists so a long-lived install doesn't accumulate ids forever — the feed shows
     * a single batch of ~6 items, so 64 is generous.
     */
    const val MAX_READ_IDS = 64

    fun decodeReadIds(raw: String?): Set<String> =
        raw?.split('\n')?.filterTo(LinkedHashSet()) { it.isNotBlank() } ?: emptySet()

    /** Append [id] (deduplicated, most-recent-last), trimming oldest ids past the cap. */
    fun addReadId(raw: String?, id: String): String {
        val ids = decodeReadIds(raw).toMutableList()
        ids.remove(id)
        ids.add(id)
        while (ids.size > MAX_READ_IDS) ids.removeAt(0)
        return ids.joinToString("\n")
    }
}
