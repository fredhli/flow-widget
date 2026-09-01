package com.fredhli.flowwidget

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tall bucket's row meta line: "kind · relative age". The feed has no source field,
 * so the kind word stands where the mockup shows a source; the age half shares its stem
 * with the header's "ago" label, so the two never spell the same duration two ways.
 */
class MetaLineTest {

    private val now = 1_756_700_000_000L

    private fun stampAgo(ageMs: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now - ageMs), ZoneId.systemDefault()).toString()

    private fun item(ts: String?, kind: String = "headline", epochMs: Long? = null) =
        FeedItem(id = "fx01", title = "a title", ts = ts, kind = kind, epochMs = epochMs)

    @Test
    fun `kind and relative age joined with a middle dot`() {
        assertEquals("headline · 32min", metaLine(item(stampAgo(32 * 60_000L)), now))
        assertEquals("progress · 2h", metaLine(item(stampAgo(2 * 60 * 60_000L), kind = "progress"), now))
    }

    @Test
    fun `a missing or unparseable timestamp leaves just the kind`() {
        assertEquals("headline", metaLine(item(null), now))
        assertEquals("progress", metaLine(item("not-a-timestamp", kind = "progress"), now))
    }

    @Test
    fun `a just-now item reads now`() {
        assertEquals("headline · now", metaLine(item(stampAgo(10_000L)), now))
    }

    // ------------------------------------------------------------ epoch preference

    @Test
    fun `epoch wins over the naive ts when both are present`() {
        // A ts string that would read 6h if parsed naively against `now`, beside an epoch
        // that says 32min: the server's absolute stamp must win — the naive string hangs
        // on the SERVER's wall clock, which is what put the ages 6 hours off on HKT.
        val i = item(stampAgo(6 * 60 * 60_000L), epochMs = now - 32 * 60_000L)
        assertEquals("headline · 32min", metaLine(i, now))
    }

    @Test
    fun `epoch alone is enough — no ts string needed`() {
        assertEquals("headline · 2h", metaLine(item(null, epochMs = now - 2 * 60 * 60_000L), now))
    }

    @Test
    fun `no epoch falls back to the naive ts`() {
        assertEquals("headline · 32min", metaLine(item(stampAgo(32 * 60_000L), epochMs = null), now))
    }
}
