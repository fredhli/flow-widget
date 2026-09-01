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

    private fun item(ts: String?, kind: String = "headline") =
        FeedItem(id = "fx01", title = "a title", ts = ts, kind = kind)

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
}
