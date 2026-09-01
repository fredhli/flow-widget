package com.fredhli.flowwidget

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {

    /**
     * The exact shape the live server (and mock_server.py) sends since 2026-09-01:
     * `epoch`/`latest_epoch` are absolute seconds beside the naive European-local
     * strings. 1788279540 is the report's own case — 18:19 Europe = 00:19 HKT.
     */
    private val full = """
        {
          "latest": "2026-09-01T18:19:00",
          "latest_epoch": 1788279540,
          "refreshing": false,
          "items": [
            {"id": "a1b2c3d4e5f60718", "title": "JHT: 3 new tier-A postings overnight",
             "ts": "2026-09-01T18:19:00", "epoch": 1788279540, "kind": "progress"},
            {"id": "0123456789abcdef", "title": "Fed holds; long end steepens",
             "ts": "2026-09-01T18:19:00", "epoch": 1788279540, "kind": "headline"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the live payload shape`() {
        val feed = FeedParser.parse(full)
        assertEquals("2026-09-01T18:19:00", feed.latest)
        assertEquals(1_788_279_540_000L, feed.latestEpochMs)
        assertFalse(feed.refreshing)
        assertEquals(2, feed.items.size)
        assertEquals("a1b2c3d4e5f60718", feed.items[0].id)
        assertEquals("JHT: 3 new tier-A postings overnight", feed.items[0].title)
        assertEquals("progress", feed.items[0].kind)
        assertEquals(1_788_279_540_000L, feed.items[0].epochMs)
        assertEquals("headline", feed.items[1].kind)
        assertEquals("2026-09-01T18:19:00", feed.items[1].ts)
    }

    // ------------------------------------------------------------ epoch tolerance

    @Test
    fun `an old payload without epochs parses with null epochs`() {
        val feed = FeedParser.parse(
            """
            {"latest": "2026-08-31T07:30:00", "refreshing": false,
             "items": [{"id": "a1b2c3d4e5f60718", "title": "t", "ts": "2026-08-31T07:30:00"}]}
            """.trimIndent()
        )
        assertNull(feed.latestEpochMs)
        assertNull(feed.items[0].epochMs)
    }

    @Test
    fun `null and junk epochs read as absent, not zero`() {
        val feed = FeedParser.parse(
            """
            {"latest_epoch": null, "items": [
              {"id": "aaaaaaaaaaaaaaaa", "title": "null epoch", "epoch": null},
              {"id": "bbbbbbbbbbbbbbbb", "title": "junk epoch", "epoch": "soon"},
              {"id": "cccccccccccccccc", "title": "zero epoch", "epoch": 0},
              {"id": "dddddddddddddddd", "title": "negative epoch", "epoch": -12}
            ]}
            """.trimIndent()
        )
        assertNull(feed.latestEpochMs)
        for (item in feed.items) assertNull("'${item.title}'", item.epochMs)
    }

    @Test
    fun `epoch seconds convert to millis`() {
        val feed = FeedParser.parse("""{"latest_epoch": 1, "items": []}""")
        assertEquals(1_000L, feed.latestEpochMs)
    }

    @Test
    fun `refreshing true comes through`() {
        val feed = FeedParser.parse("""{"latest": null, "refreshing": true, "items": []}""")
        assertTrue(feed.refreshing)
    }

    @Test
    fun `null latest maps to null not the string null`() {
        val feed = FeedParser.parse("""{"latest": null, "refreshing": false, "items": []}""")
        assertNull(feed.latest)
    }

    @Test
    fun `missing keys all fall back`() {
        val feed = FeedParser.parse("{}")
        assertNull(feed.latest)
        assertFalse(feed.refreshing)
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `item without ts or kind survives with defaults`() {
        val feed = FeedParser.parse(
            """{"items": [{"id": "deadbeefdeadbeef", "title": "Bare item"}]}"""
        )
        assertEquals(1, feed.items.size)
        assertNull(feed.items[0].ts)
        assertEquals("headline", feed.items[0].kind)
    }

    @Test
    fun `item missing id or title is skipped, not fatal`() {
        val feed = FeedParser.parse(
            """
            {"items": [
              {"title": "no id"},
              {"id": "aaaaaaaaaaaaaaaa"},
              {"id": "", "title": "empty id"},
              {"id": "bbbbbbbbbbbbbbbb", "title": "kept"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, feed.items.size)
        assertEquals("kept", feed.items[0].title)
    }

    @Test
    fun `non-object entries in items are skipped`() {
        val feed = FeedParser.parse(
            """{"items": [42, "nope", null, {"id": "cccccccccccccccc", "title": "ok"}]}"""
        )
        assertEquals(1, feed.items.size)
    }

    @Test
    fun `extra keys are ignored at both levels`() {
        val feed = FeedParser.parse(
            """
            {"latest": "2026-08-31T07:30:00", "refreshing": false, "server_version": 9,
             "items": [{"id": "dddddddddddddddd", "title": "t", "ts": "2026-08-31T07:30:00",
                        "kind": "progress", "body": "should not be here", "url": null}]}
            """.trimIndent()
        )
        assertEquals("2026-08-31T07:30:00", feed.latest)
        assertEquals(1, feed.items.size)
    }

    @Test
    fun `items not an array falls back to empty`() {
        val feed = FeedParser.parse("""{"items": "surprise"}""")
        assertTrue(feed.items.isEmpty())
    }

    @Test(expected = JSONException::class)
    fun `non-json body throws`() {
        FeedParser.parse("<html>502 Bad Gateway</html>")
    }

    @Test(expected = JSONException::class)
    fun `json array body throws`() {
        FeedParser.parse("[1,2,3]")
    }
}
