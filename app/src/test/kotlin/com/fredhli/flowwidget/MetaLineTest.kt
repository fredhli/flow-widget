package com.fredhli.flowwidget

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tall bucket's row meta line: "topic · source · relative age" since 2.0.1, with the
 * kind word standing in when an item carries neither chip (older payloads); the age half
 * shares its stem with the header's "ago" label, so the two never spell the same duration
 * two ways.
 */
class MetaLineTest {

    private val now = 1_756_700_000_000L

    private fun stampAgo(ageMs: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now - ageMs), ZoneId.systemDefault()).toString()

    private fun item(
        ts: String?,
        kind: String = "headline",
        epochMs: Long? = null,
        topic: String? = null,
        source: String? = null,
    ) = FeedItem(id = "fx01", title = "a title", ts = ts, kind = kind, epochMs = epochMs, topic = topic, source = source)

    @Test
    fun `the page's two chips lead the meta line and the kind word is only the fallback`() {
        val ago = stampAgo(5 * 60 * 60_000L)
        assertEquals("Quant · HKEX · 5h", metaLine(item(ago, topic = "Quant", source = "HKEX"), now))
        assertEquals("HKEX · 5h", metaLine(item(ago, source = "HKEX"), now))
        assertEquals("Quant · 5h", metaLine(item(ago, topic = "Quant"), now))
        assertEquals("Quant · HKEX", metaLine(item(null, topic = "Quant", source = "HKEX"), now))
        // blank chips are no chips
        assertEquals("headline · 5h", metaLine(item(ago, topic = " ", source = ""), now))
        // a progress item's module key is spelled the way the page spells it
        assertEquals("JHT · 5h", metaLine(item(ago, kind = "progress", source = "jht"), now))
        assertEquals("Smart Beta · 5h", metaLine(item(ago, kind = "progress", source = "smart-beta"), now))
        assertEquals("Morning · 5h", metaLine(item(ago, kind = "progress", source = "morning"), now))
    }

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
