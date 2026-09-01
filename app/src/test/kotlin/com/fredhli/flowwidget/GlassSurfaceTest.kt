package com.fredhli.flowwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the glass surface: the two per-theme opacity contracts the config
 * sliders and the DataStore values funnel through, and the level grids the generated
 * drawables are indexed by. (The drawables themselves are the generated
 * `res/drawable[-night]/glass_light_NN.xml` / `glass_dark_NN.xml` families, written by
 * `tools/gen-glass-drawables.py`; the alpha tables below are the contract between the
 * two, so a drift in either shows up here as well as in the generator's `--check`.)
 */
class GlassSurfaceTest {

    private val eps = 1e-6f

    // ---------------------------------------------------------------- clamps

    @Test
    fun `absent values fall back to each theme's default`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.clampLight(null), eps)
        assertEquals(GlassSurface.DEFAULT_OPACITY_DARK, GlassSurface.clampDark(null), eps)
    }

    @Test
    fun `NaN falls back to the default in both themes`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.clampLight(Float.NaN), eps)
        assertEquals(GlassSurface.DEFAULT_OPACITY_DARK, GlassSurface.clampDark(Float.NaN), eps)
    }

    @Test
    fun `values below each floor clamp to that floor`() {
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampLight(0.2f), eps)
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampLight(Float.NEGATIVE_INFINITY), eps)
        // Dark's floor is 0.30 — Fred's explicit device-feedback call, the only guard.
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.clampDark(0.1f), eps)
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.clampDark(Float.NEGATIVE_INFINITY), eps)
    }

    @Test
    fun `values above the shared ceiling clamp to it`() {
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampLight(0.99f), eps)
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampDark(0.99f), eps)
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampLight(Float.POSITIVE_INFINITY), eps)
    }

    @Test
    fun `in-range values pass through, boundaries included`() {
        assertEquals(0.74f, GlassSurface.clampLight(0.74f), eps)
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampLight(GlassSurface.MIN_OPACITY), eps)
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampLight(GlassSurface.MAX_OPACITY), eps)
        assertEquals(0.74f, GlassSurface.clampDark(0.74f), eps)
        // 0.50 is below the LIGHT floor's old shared role no more: dark passes it through.
        assertEquals(0.50f, GlassSurface.clampDark(0.50f), eps)
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.clampDark(GlassSurface.MIN_OPACITY_DARK), eps)
    }

    @Test
    fun `both defaults sit inside their slider ranges`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.clampLight(GlassSurface.DEFAULT_OPACITY), eps)
        assertEquals(GlassSurface.DEFAULT_OPACITY_DARK, GlassSurface.clampDark(GlassSurface.DEFAULT_OPACITY_DARK), eps)
    }

    // ---------------------------------------------------------------- the level grids

    @Test
    fun `the light grid spans the documented slider range end to end`() {
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.lightOpacityAtLevel(0), 1e-4f)
        assertEquals(
            GlassSurface.MAX_OPACITY,
            GlassSurface.lightOpacityAtLevel(GlassSurface.LIGHT_LEVELS - 1),
            1e-4f,
        )
    }

    @Test
    fun `the dark grid spans Fred's 0_30 floor to the same ceiling`() {
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.darkOpacityAtLevel(0), 1e-4f)
        assertEquals(
            GlassSurface.MAX_OPACITY,
            GlassSurface.darkOpacityAtLevel(GlassSurface.DARK_LEVELS - 1),
            1e-4f,
        )
    }

    @Test
    fun `both grids rise monotonically`() {
        for (k in 1 until GlassSurface.LIGHT_LEVELS) {
            assertTrue(GlassSurface.lightOpacityAtLevel(k) > GlassSurface.lightOpacityAtLevel(k - 1))
        }
        for (k in 1 until GlassSurface.DARK_LEVELS) {
            assertTrue(GlassSurface.darkOpacityAtLevel(k) > GlassSurface.darkOpacityAtLevel(k - 1))
        }
    }

    @Test
    fun `the 0_74 defaults land on a level exactly in both grids, not between two`() {
        val light = GlassSurface.lightLevelFor(GlassSurface.DEFAULT_OPACITY)
        assertEquals(8, light)
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.lightOpacityAtLevel(light), 1e-4f)
        // 1% dark steps exist for exactly this: it is the only uniform grid that lands
        // 0.30, 0.74 and 0.95 all on a level.
        val dark = GlassSurface.darkLevelFor(GlassSurface.DEFAULT_OPACITY_DARK)
        assertEquals(44, dark)
        assertEquals(GlassSurface.DEFAULT_OPACITY_DARK, GlassSurface.darkOpacityAtLevel(dark), 1e-4f)
    }

    @Test
    fun `levelFor round-trips every level of both grids`() {
        for (k in 0 until GlassSurface.LIGHT_LEVELS) {
            assertEquals(k, GlassSurface.lightLevelFor(GlassSurface.lightOpacityAtLevel(k)))
        }
        for (k in 0 until GlassSurface.DARK_LEVELS) {
            assertEquals(k, GlassSurface.darkLevelFor(GlassSurface.darkOpacityAtLevel(k)))
        }
    }

    @Test
    fun `levelFor stays inside the resource tables for absent and wild values`() {
        for (raw in listOf(null, Float.NaN, -5f, 0f, 5f, Float.POSITIVE_INFINITY)) {
            val light = GlassSurface.lightLevelFor(raw)
            assertTrue("lightLevelFor($raw) = $light", light in 0 until GlassSurface.LIGHT_LEVELS)
            val dark = GlassSurface.darkLevelFor(raw)
            assertTrue("darkLevelFor($raw) = $dark", dark in 0 until GlassSurface.DARK_LEVELS)
        }
        assertEquals(0, GlassSurface.lightLevelFor(0f))
        assertEquals(GlassSurface.LIGHT_LEVELS - 1, GlassSurface.lightLevelFor(1f))
        assertEquals(0, GlassSurface.darkLevelFor(0f))
        assertEquals(GlassSurface.DARK_LEVELS - 1, GlassSurface.darkLevelFor(1f))
    }

    // ---------------------------------------------------------------- stopColors

    @Test
    fun `stop alphas scale by the opacity and rgb stays untouched`() {
        for (dark in listOf(false, true)) {
            val base = if (dark) GlassSurface.DARK_STOPS else GlassSurface.LIGHT_STOPS
            val scaled = GlassSurface.stopColors(dark, 0.74f)
            assertEquals(base.size, scaled.size)
            for (i in base.indices) {
                // Base stops are fully opaque; 255 * 0.74 rounds to 189.
                assertEquals("stop $i alpha (dark=$dark)", 189, (scaled[i] ushr 24) and 0xFF)
                assertEquals("stop $i rgb (dark=$dark)", base[i] and 0xFFFFFF, scaled[i] and 0xFFFFFF)
            }
        }
    }

    @Test
    fun `stopColors clamps a wild opacity instead of overflowing the alpha byte`() {
        for (c in GlassSurface.stopColors(dark = false, opacity = 5f)) {
            assertEquals(255, (c ushr 24) and 0xFF)
        }
        for (c in GlassSurface.stopColors(dark = false, opacity = -1f)) {
            assertEquals(0, (c ushr 24) and 0xFF)
        }
    }

    @Test
    fun `three stops in both themes — the grey-tint-grey and navy-violet-navy ramps`() {
        assertEquals(3, GlassSurface.LIGHT_STOPS.size)
        assertEquals(3, GlassSurface.DARK_STOPS.size)
    }

    /**
     * The alpha bytes the generated drawables must carry, level by level. This is the
     * seam between Kotlin and `tools/gen-glass-drawables.py`: the script writes
     * `#AARRGGBB` into the XML files, and nothing at build time proves the two agree.
     * Re-running the generator with `--check` proves the files match the script; this
     * proves the script's grids match the app's.
     */
    @Test
    fun `the generated drawable alphas`() {
        val light = intArrayOf(128, 135, 143, 150, 158, 166, 173, 181, 189, 196, 204, 212, 219, 227, 235, 242)
        val dark = intArrayOf(
            77, 79, 82, 84, 87, 89, 92, 94, 97, 99, 102,
            105, 107, 110, 112, 115, 117, 120, 122, 125, 128, 130,
            133, 135, 138, 140, 143, 145, 148, 150, 153, 156, 158,
            161, 163, 166, 168, 171, 173, 176, 179, 181, 184, 186,
            189, 191, 194, 196, 199, 201, 204, 207, 209, 212, 214,
            217, 219, 222, 224, 227, 230, 232, 235, 237, 240, 242,
        )
        assertEquals(GlassSurface.LIGHT_LEVELS, light.size)
        assertEquals(GlassSurface.DARK_LEVELS, dark.size)
        for (k in 0 until GlassSurface.LIGHT_LEVELS) {
            val l = GlassSurface.stopColors(false, GlassSurface.lightOpacityAtLevel(k))
            assertEquals("light level $k", light[k], (l[0] ushr 24) and 0xFF)
        }
        for (k in 0 until GlassSurface.DARK_LEVELS) {
            val d = GlassSurface.stopColors(true, GlassSurface.darkOpacityAtLevel(k))
            assertEquals("dark level $k", dark[k], (d[0] ushr 24) and 0xFF)
        }
    }
}
