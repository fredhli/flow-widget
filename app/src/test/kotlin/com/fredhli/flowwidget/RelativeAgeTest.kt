package com.fredhli.flowwidget

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeAgeTest {

    private val zone = ZoneId.of("Australia/Sydney")

    /** 2026-09-01T12:00:00 in the fixed test zone, as epoch millis. */
    private val noon = RelativeAge.parseTs("2026-09-01T12:00:00", zone)!!

    // ------------------------------------------------------------ parseTs

    @Test
    fun `parses naive local timestamp in the given zone`() {
        val a = RelativeAge.parseTs("2026-09-01T12:00:00", zone)!!
        val b = RelativeAge.parseTs("2026-09-01T13:00:00", zone)!!
        assertEquals(60 * 60 * 1000L, b - a)
    }

    @Test
    fun `parses zulu timestamp`() {
        val t = RelativeAge.parseTs("2026-09-01T02:00:00Z", zone)!!
        // Sydney is UTC+10 in September; 02:00Z == 12:00 local.
        assertEquals(noon, t)
    }

    @Test
    fun `parses offset timestamp`() {
        val t = RelativeAge.parseTs("2026-09-01T12:00:00+10:00", zone)!!
        assertEquals(noon, t)
    }

    @Test
    fun `null and garbage parse to null`() {
        assertNull(RelativeAge.parseTs(null, zone))
        assertNull(RelativeAge.parseTs("", zone))
        assertNull(RelativeAge.parseTs("   ", zone))
        assertNull(RelativeAge.parseTs("yesterday-ish", zone))
        assertNull(RelativeAge.parseTs("2026-13-45T99:00:00", zone))
    }

    // ------------------------------------------------------------ format (row meta)

    // Same stem as `ago`, without the word: "32min", "5h", "3d".

    @Test
    fun `under a minute is now`() {
        assertEquals("now", RelativeAge.format(noon, noon))
        assertEquals("now", RelativeAge.format(noon, noon + 59_000))
    }

    @Test
    fun `future timestamp clamps to now`() {
        assertEquals("now", RelativeAge.format(noon + 120_000, noon))
    }

    @Test
    fun `minutes below an hour`() {
        assertEquals("1min", RelativeAge.format(noon, noon + 60_000))
        assertEquals("32min", RelativeAge.format(noon, noon + 32 * 60_000))
        assertEquals("59min", RelativeAge.format(noon, noon + 59 * 60_000))
    }

    @Test
    fun `hours below a day`() {
        assertEquals("1h", RelativeAge.format(noon, noon + 60 * 60_000))
        assertEquals("5h", RelativeAge.format(noon, noon + 5 * 60 * 60_000 + 30 * 60_000))
        assertEquals("23h", RelativeAge.format(noon, noon + 23 * 60 * 60_000 + 59 * 60_000))
    }

    @Test
    fun `days from 24h on`() {
        assertEquals("1d", RelativeAge.format(noon, noon + 24 * 60 * 60_000L))
        assertEquals("3d", RelativeAge.format(noon, noon + 3 * 24 * 60 * 60_000L + 60_000))
    }

    @Test
    fun `null timestamp formats as em dash`() {
        assertEquals("—", RelativeAge.format(null, noon))
    }

    // ------------------------------------------------------------ ago (header label)

    private val minMs = 60_000L
    private val hourMs = 60 * minMs
    private val dayMs = 24 * hourMs

    @Test
    fun `ago under a minute reads just now`() {
        assertEquals("just now", RelativeAge.ago(noon, noon))
        assertEquals("just now", RelativeAge.ago(noon, noon + 59_000))
    }

    @Test
    fun `ago clamps a future timestamp instead of counting up`() {
        assertEquals("just now", RelativeAge.ago(noon + 120_000, noon))
    }

    @Test
    fun `ago in minutes up to the 59-minute boundary`() {
        assertEquals("1min ago", RelativeAge.ago(noon, noon + minMs))
        assertEquals("32min ago", RelativeAge.ago(noon, noon + 32 * minMs))
        assertEquals("59min ago", RelativeAge.ago(noon, noon + 59 * minMs))
        // 59:59 is still the minute bucket — the unit only steps at a whole hour.
        assertEquals("59min ago", RelativeAge.ago(noon, noon + 59 * minMs + 59_000))
    }

    @Test
    fun `the 60-minute boundary flips to hours`() {
        assertEquals("1h ago", RelativeAge.ago(noon, noon + 60 * minMs))
    }

    @Test
    fun `ago never shows a fraction of a unit`() {
        // The amendment's example: 90 minutes is "1h ago", never "1.5h".
        assertEquals("1h ago", RelativeAge.ago(noon, noon + 90 * minMs))
        assertEquals("5h ago", RelativeAge.ago(noon, noon + 5 * hourMs + 45 * minMs))
        assertEquals("2d ago", RelativeAge.ago(noon, noon + 2 * dayMs + 18 * hourMs))
    }

    @Test
    fun `the 23h and 24h boundaries`() {
        assertEquals("23h ago", RelativeAge.ago(noon, noon + 23 * hourMs))
        assertEquals("23h ago", RelativeAge.ago(noon, noon + 23 * hourMs + 59 * minMs))
        assertEquals("1d ago", RelativeAge.ago(noon, noon + 24 * hourMs))
        assertEquals("3d ago", RelativeAge.ago(noon, noon + 3 * dayMs + minMs))
    }

    @Test
    fun `ago output is plural-free and carries no decimal point`() {
        val samples = listOf(
            1 * minMs, 59 * minMs, 60 * minMs, 90 * minMs,
            23 * hourMs, 24 * hourMs, 3 * dayMs, 400 * dayMs,
        ).map { RelativeAge.ago(noon, noon + it) }
        for (s in samples) {
            assertTrue("'$s' should end in ' ago'", s.endsWith(" ago"))
            assertFalse("'$s' must not contain a decimal point", s.contains('.'))
            assertFalse("'$s' must not spell a plural unit", s.contains("s ago"))
            // Integer + unit symbol + " ago", nothing else: 32min ago / 1h ago / 3d ago.
            assertTrue("'$s' is not integer+unit", Regex("^\\d+(min|h|d) ago$").matches(s))
        }
    }

    @Test
    fun `ago with no timestamp is the em dash`() {
        assertEquals("—", RelativeAge.ago(null, noon))
    }

    // ------------------------------------------------------------ isStale

    @Test
    fun `stale flips just past 24 hours`() {
        val day = 24 * 60 * 60 * 1000L
        assertFalse(RelativeAge.isStale(noon, noon + day))          // exactly 24h: not yet
        assertTrue(RelativeAge.isStale(noon, noon + day + 1))       // one ms past: stale
        assertFalse(RelativeAge.isStale(noon, noon + day - 60_000)) // just under
    }

    @Test
    fun `null latest is not stale`() {
        assertFalse(RelativeAge.isStale(null, noon))
    }
}
