package com.fredhli.flowwidget

/**
 * The redesign's translucent "glass" container surface (design/BRIEF.md + RESEARCH.md):
 * a diagonal three-stop gradient — grey → blue-tint → grey by day, navy → violet-tinted
 * navy by night — of alpha colors. No blur: a widget is RemoteViews inside the launcher's
 * window and cannot invoke the window-owner blur APIs (RESEARCH.md §1), so legibility
 * comes from the row fill on top of this surface and from the surface's own opacity.
 *
 * **The surface is a drawable resource, not a bitmap.** Round 1 baked it into an
 * ARGB_8888 Bitmap at composition time; three separate defects came out of that at review
 * and all three are cured by moving it into generated `res/drawable[-night]/` shapes
 * (see `tools/gen-glass-drawables.py`'s header for the full argument: the frozen theme at
 * 1.03:1 after a dark-mode flip, the corner radius stretched off the outline clip, and
 * ~2 MB of bitmap marshalled per update).
 *
 * **Two surfaces, two sliders, since the device-feedback round.** Fred's first real-device
 * pass (design/BRIEF.md, round 2 device feedback #1) split the single opacity into a
 * per-theme pair: light spans the brief's 0.50 → 0.95 with 0.74 default, dark spans
 * **0.30** → 0.95 with its own 0.74 default. The old scheme mapped one drag onto both
 * themes and floored dark at 0.80 on a contrast argument; Fred overruled the floor — his
 * wallpaper, his slider — so 0.30 is now the only guard, and the config screen shows two
 * sliders instead of one doing hidden math.
 *
 * Because the stored values are independent, one resource id can no longer encode both
 * levels. Each theme gets its own resource family (`glass_light_NN` / `glass_dark_NN`),
 * present in BOTH qualifier directories — the real gradient in its own theme, fully
 * transparent in the other — and FlowWidget stacks the two layers. The host resolves each
 * id against its own configuration at apply time, so exactly one gradient is visible and
 * a system theme flip still swaps the surface with the text.
 *
 * Each slider quantises to its grid: light keeps 16 levels 3% apart (lands 0.74 exactly);
 * dark uses 66 levels 1% apart — the only uniform step that lands 0.30, 0.74 and 0.95 all
 * on the grid, and fine enough to read as continuous.
 *
 * All of this is pure Kotlin so it can be unit-tested off-device; the resource tables
 * live in FlowWidget.kt, next to the composable that uses them.
 */
object GlassSurface {

    /** Light slider range, design/BRIEF.md § "The surface". */
    const val MIN_OPACITY = 0.50f
    const val MAX_OPACITY = 0.95f
    const val DEFAULT_OPACITY = 0.74f

    /**
     * Dark slider's floor — **0.30, Fred's explicit call** (round 2 device feedback #1).
     *
     * The 0.80 floor this replaces was derived against a bright reference wallpaper
     * (RESEARCH §4b: white row fill has the wrong sign in dark, so the container is the
     * only thing between light ink and a bright wall — 2.5:1 meta at 0.50). That argument
     * still holds *on that wallpaper*; Fred accepts the trade on his own, and the slider
     * is the recovery if a wallpaper change washes the text. The floor exists so the
     * surface never disappears entirely.
     */
    const val MIN_OPACITY_DARK = 0.30f
    const val DEFAULT_OPACITY_DARK = 0.74f

    /** Levels each slider quantises to. Must equal gen-glass-drawables.py's grids. */
    const val LIGHT_LEVELS = 16
    const val DARK_LEVELS = 66

    private const val LIGHT_STEP = 0.03f
    private const val DARK_STEP = 0.01f

    /**
     * Base gradient stops at full alpha, ARGB, top-left → bottom-right. The canonical
     * record of the ramp; `tools/gen-glass-drawables.py` restates these as hex and is
     * what actually writes the drawables.
     *
     * Light: the mockup panel-A ramp (cool grey → soft blue → warm grey).
     * Dark: panel D's ramp, deepened in the device-feedback polish pass — deep navy →
     * violet-tinted navy → violet-black. The previous 181B27/222A44/14161F ends were so
     * dark and desaturated that on the phone the gradient vanished and the rows read as
     * grey slabs; these stops carry enough chroma to survive compositing at any opacity.
     */
    val LIGHT_STOPS = intArrayOf(0xFFF4F5FA.toInt(), 0xFFDFE6F9.toInt(), 0xFFF1F0F6.toInt())
    val DARK_STOPS = intArrayOf(0xFF222B4C.toInt(), 0xFF322A5E.toInt(), 0xFF181530.toInt())

    /** Stored light value -> a safe opacity: absent -> the default, wild -> clamped. */
    fun clampLight(raw: Float?): Float {
        if (raw == null || raw.isNaN()) return DEFAULT_OPACITY
        return raw.coerceIn(MIN_OPACITY, MAX_OPACITY)
    }

    /** Stored dark value -> a safe opacity: absent -> the dark default, wild -> clamped. */
    fun clampDark(raw: Float?): Float {
        if (raw == null || raw.isNaN()) return DEFAULT_OPACITY_DARK
        return raw.coerceIn(MIN_OPACITY_DARK, MAX_OPACITY)
    }

    /** The opacity a light level draws at: 0.50, 0.53, … 0.95. */
    fun lightOpacityAtLevel(level: Int): Float =
        MIN_OPACITY + LIGHT_STEP * level.coerceIn(0, LIGHT_LEVELS - 1)

    /** The opacity a dark level draws at: 0.30, 0.31, … 0.95. */
    fun darkOpacityAtLevel(level: Int): Float =
        MIN_OPACITY_DARK + DARK_STEP * level.coerceIn(0, DARK_LEVELS - 1)

    /** The stored light float -> the glass_light_NN level. Rounds to the nearest level. */
    fun lightLevelFor(raw: Float?): Int {
        val o = clampLight(raw)
        return Math.round((o - MIN_OPACITY) / LIGHT_STEP).coerceIn(0, LIGHT_LEVELS - 1)
    }

    /** The stored dark float -> the glass_dark_NN level. Rounds to the nearest level. */
    fun darkLevelFor(raw: Float?): Int {
        val o = clampDark(raw)
        return Math.round((o - MIN_OPACITY_DARK) / DARK_STEP).coerceIn(0, DARK_LEVELS - 1)
    }

    /**
     * The gradient stops with each alpha scaled by [opacity]. Pure int math, and the
     * specification the generated drawables' alpha bytes have to match — see
     * `GlassSurfaceTest.the generated drawable alphas`.
     */
    fun stopColors(dark: Boolean, opacity: Float): IntArray {
        val base = if (dark) DARK_STOPS else LIGHT_STOPS
        val o = opacity.coerceIn(0f, 1f)
        return IntArray(base.size) { i ->
            val c = base[i]
            val a = (((c ushr 24) and 0xFF) * o + 0.5f).toInt().coerceIn(0, 255)
            (a shl 24) or (c and 0x00FFFFFF)
        }
    }
}
