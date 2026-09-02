package com.fredhli.flowwidget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expand-mode body stripper (round 3 item 4a): every word kept, every marker
 * dropped, CJK untouched, never a throw. The widget shows the result as plain clamped
 * text, so link targets and emphasis markers are noise there.
 */
class MarkdownTest {

    @Test
    fun `null and blank are empty`() {
        assertEquals("", Markdown.strip(null))
        assertEquals("", Markdown.strip("   \n  "))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("对账通过 128/128，无新增高分帖。", Markdown.strip("对账通过 128/128，无新增高分帖。"))
    }

    @Test
    fun `bold italic strike and code markers are dropped`() {
        assertEquals("重构规模 为近三年最大", Markdown.strip("**重构规模** 为近三年最大"))
        assertEquals("回落至 18%", Markdown.strip("回落至 *18%*"))
        assertEquals("bold too", Markdown.strip("__bold__ _too_"))
        assertEquals("旧口径", Markdown.strip("~~旧口径~~"))
        assertEquals("CME FedWatch 口径", Markdown.strip("`CME FedWatch` 口径"))
    }

    @Test
    fun `links keep the text and images keep the alt`() {
        assertEquals("官方公告 列出名单", Markdown.strip("[官方公告](https://example.com/x) 列出名单"))
        assertEquals("图表", Markdown.strip("![图表](https://example.com/c.png)"))
        assertEquals("https://example.com", Markdown.strip("<https://example.com>"))
    }

    @Test
    fun `headings quotes and rules are dropped`() {
        assertEquals("摘要\n正文", Markdown.strip("## 摘要\n\n---\n\n> 正文"))
    }

    @Test
    fun `list markers become a plain middle dot`() {
        assertEquals("· 生效时点：9 月 10 日\n· 调仓 38 亿美元", Markdown.strip("- 生效时点：9 月 10 日\n- 调仓 38 亿美元"))
        assertEquals("· first\n· second", Markdown.strip("1. first\n2. second"))
    }

    @Test
    fun `code fences drop the fence lines and keep the code`() {
        assertEquals("uv run x", Markdown.strip("```bash\nuv run x\n```"))
    }

    @Test
    fun `intraword underscores and asterisks survive`() {
        assertEquals("latest_epoch 和 snake_case", Markdown.strip("latest_epoch 和 snake_case"))
        assertEquals("a*b", Markdown.strip("a*b"))
    }

    @Test
    fun `blank runs collapse and edges trim`() {
        assertEquals("第一段\n第二段", Markdown.strip("\n\n第一段\n\n\n第二段  \n"))
    }

    @Test
    fun `the fixture bodies strip to clean plain text`() {
        val s = Markdown.strip(
            "**重构规模**为近三年最大：[官方公告](https://example.com/bbg500) 列出完整名单。\n" +
                "- 生效时点：9 月 10 日开盘前"
        )
        assertEquals("重构规模为近三年最大：官方公告 列出完整名单。\n· 生效时点：9 月 10 日开盘前", s)
    }
}
