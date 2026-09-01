package com.fredhli.flowwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the glass surface: the opacity contract the config slider and the
 * DataStore value both funnel through, and the level grid the generated drawables are
 * indexed by. (The drawables themselves are `res/drawable[-night]/glass_NN.xml`, written
 * by `tools/gen-glass-drawables.py`; the alpha table below is the contract between the
 * two, so a drift in either shows up here as well as in the generator's `--check`.)
 */
class GlassSurfaceTest {

    private val eps = 1e-6f

    // ---------------------------------------------------------------- clampOpacity

    @Test
    fun `absent value falls back to the default`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.clampOpacity(null), eps)
    }

    @Test
    fun `NaN falls back to the default`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.clampOpacity(Float.NaN), eps)
    }

    @Test
    fun `values below the floor clamp to the floor`() {
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampOpacity(0.2f), eps)
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampOpacity(Float.NEGATIVE_INFINITY), eps)
    }

    @Test
    fun `values above the ceiling clamp to the ceiling`() {
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampOpacity(0.99f), eps)
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampOpacity(Float.POSITIVE_INFINITY), eps)
    }

    @Test
    fun `in-range values pass through, boundaries included`() {
        assertEquals(0.74f, GlassSurface.clampOpacity(0.74f), eps)
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.clampOpacity(GlassSurface.MIN_OPACITY), eps)
        assertEquals(GlassSurface.MAX_OPACITY, GlassSurface.clampOpacity(GlassSurface.MAX_OPACITY), eps)
    }

    @Test
    fun `the default sits inside the slider range`() {
        assertEquals(
            GlassSurface.DEFAULT_OPACITY,
            GlassSurface.clampOpacity(GlassSurface.DEFAULT_OPACITY),
            eps,
        )
    }

    // ---------------------------------------------------------------- the level grid

    @Test
    fun `the grid spans the documented slider range end to end`() {
        assertEquals(GlassSurface.MIN_OPACITY, GlassSurface.opacityAtLevel(0), 1e-4f)
        assertEquals(
            GlassSurface.MAX_OPACITY,
            GlassSurface.opacityAtLevel(GlassSurface.LEVELS - 1),
            1e-4f,
        )
    }

    @Test
    fun `the dark grid spans the dark floor to the same ceiling`() {
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.darkOpacityAtLevel(0), 1e-4f)
        assertEquals(
            GlassSurface.MAX_OPACITY,
            GlassSurface.darkOpacityAtLevel(GlassSurface.LEVELS - 1),
            1e-4f,
        )
    }

    @Test
    fun `dark never goes below the dark floor, at any level`() {
        for (k in 0 until GlassSurface.LEVELS) {
            assertTrue(
                "level $k dark opacity ${GlassSurface.darkOpacityAtLevel(k)}",
                GlassSurface.darkOpacityAtLevel(k) >= GlassSurface.MIN_OPACITY_DARK - 1e-5f,
            )
        }
    }

    @Test
    fun `both grids rise monotonically`() {
        for (k in 1 until GlassSurface.LEVELS) {
            assertTrue(GlassSurface.opacityAtLevel(k) > GlassSurface.opacityAtLevel(k - 1))
            assertTrue(GlassSurface.darkOpacityAtLevel(k) > GlassSurface.darkOpacityAtLevel(k - 1))
        }
    }

    @Test
    fun `the 0_74 default lands on a level exactly, not between two`() {
        val level = GlassSurface.levelFor(GlassSurface.DEFAULT_OPACITY)
        assertEquals(8, level)
        assertEquals(GlassSurface.DEFAULT_OPACITY, GlassSurface.opacityAtLevel(level), 1e-4f)
    }

    @Test
    fun `the 0_80 dark floor is a level of the light grid too`() {
        // Not required by the code, but it is the number design/RESEARCH.md §4b quotes as
        // the dark floor and the one the evidence ladder is shot at, so the light slider
        // has to be able to sit on it exactly.
        val level = GlassSurface.levelFor(GlassSurface.MIN_OPACITY_DARK)
        assertEquals(10, level)
        assertEquals(GlassSurface.MIN_OPACITY_DARK, GlassSurface.opacityAtLevel(level), 1e-4f)
    }

    @Test
    fun `levelFor round-trips every level`() {
        for (k in 0 until GlassSurface.LEVELS) {
            assertEquals(k, GlassSurface.levelFor(GlassSurface.opacityAtLevel(k)))
        }
    }

    @Test
    fun `levelFor stays inside the resource table for absent and wild values`() {
        for (raw in listOf(null, Float.NaN, -5f, 0f, 5f, Float.POSITIVE_INFINITY)) {
            val level = GlassSurface.levelFor(raw)
            assertTrue("levelFor($raw) = $level", level in 0 until GlassSurface.LEVELS)
        }
        assertEquals(0, GlassSurface.levelFor(0f))
        assertEquals(GlassSurface.LEVELS - 1, GlassSurface.levelFor(1f))
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
    fun `three stops in both themes — the grey, tint, grey diagonal ramp`() {
        assertEquals(3, GlassSurface.LIGHT_STOPS.size)
        assertEquals(3, GlassSurface.DARK_STOPS.size)
    }

    /**
     * The alpha bytes the generated drawables must carry, level by level. This is the
     * seam between Kotlin and `tools/gen-glass-drawables.py`: the script writes
     * `#AARRGGBB` into 32 XML files, and nothing at build time proves the two agree.
     * Re-running the generator with `--check` proves the files match the script; this
     * proves the script's grid matches the app's.
     */
    @Test
    fun `the generated drawable alphas`() {
        val light = intArrayOf(128, 135, 143, 150, 158, 166, 173, 181, 189, 196, 204, 212, 219, 227, 235, 242)
        val dark = intArrayOf(204, 207, 209, 212, 214, 217, 219, 222, 224, 227, 230, 232, 235, 237, 240, 242)
        assertEquals(GlassSurface.LEVELS, light.size)
        assertEquals(GlassSurface.LEVELS, dark.size)
        for (k in 0 until GlassSurface.LEVELS) {
            val l = GlassSurface.stopColors(false, GlassSurface.opacityAtLevel(k))
            val d = GlassSurface.stopColors(true, GlassSurface.darkOpacityAtLevel(k))
            assertEquals("light level $k", light[k], (l[0] ushr 24) and 0xFF)
            assertEquals("dark level $k", dark[k], (d[0] ushr 24) and 0xFF)
        }
    }
}
