package com.fredhli.flowwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The round-3 `body` field: optional, tolerant, and accepted under either of the names
 * the dashboard side plausibly ships (`body`, then `summary`) so a server-side rename
 * cannot silently empty the expand-in-widget feature.
 */
class FeedItemBodyTest {

    private fun item(json: String): FeedItem =
        FeedParser.parse("""{"items":[$json]}""").items.single()

    @Test
    fun `body is parsed when present`() {
        val i = item("""{"id":"aaaaaaaaaaaaaaaa","title":"t","body":"**加粗** 的正文"}""")
        assertEquals("**加粗** 的正文", i.body)
    }

    @Test
    fun `summary is the fallback name`() {
        val i = item("""{"id":"aaaaaaaaaaaaaaaa","title":"t","summary":"摘要正文"}""")
        assertEquals("摘要正文", i.body)
    }

    @Test
    fun `body wins over summary when both are present`() {
        val i = item("""{"id":"aaaaaaaaaaaaaaaa","title":"t","body":"正","summary":"副"}""")
        assertEquals("正", i.body)
    }

    @Test
    fun `absent and json-null bodies are null`() {
        assertNull(item("""{"id":"aaaaaaaaaaaaaaaa","title":"t"}""").body)
        assertNull(item("""{"id":"aaaaaaaaaaaaaaaa","title":"t","body":null}""").body)
    }
}
