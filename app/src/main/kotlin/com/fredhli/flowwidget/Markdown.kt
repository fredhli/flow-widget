package com.fredhli.flowwidget

/**
 * Markdown → plain text for the expanded row body (design/BRIEF.md, Round 3 item 4a).
 *
 * The widget renders the body as a single clamped Glance Text, so anything that only
 * makes sense with markup — emphasis markers, link targets, heading levels — is noise
 * there. This is a *stripper*, not a renderer: it keeps every word, drops every marker,
 * and never throws. Line structure survives (list items become "· " lines) because the
 * 5-line clamp reads better over real lines than over one run-on paragraph.
 *
 * Deliberately regex-simple and CJK-safe: FLOW_SPEC bodies are 中文 with Latin/digit
 * fragments, and none of the patterns below can split a surrogate pair or eat a han
 * character (they all anchor on ASCII marker characters).
 */
object Markdown {

    private val FENCE = Regex("^```[^\n]*$", RegexOption.MULTILINE)
    private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val AUTOLINK = Regex("""<(https?://[^>\s]+)>""")
    private val BOLD = Regex("""\*\*([^*]+)\*\*|__([^_]+)__""")
    private val ITALIC = Regex("""(?<![\w*])\*([^*\n]+)\*(?![\w*])|(?<![\w_])_([^_\n]+)_(?![\w_])""")
    private val CODE = Regex("`([^`\n]*)`")
    private val STRIKE = Regex("~~([^~\n]+)~~")
    private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val QUOTE = Regex("""^>\s?""", RegexOption.MULTILINE)
    private val BULLET = Regex("""^[ \t]*[-*+]\s+""", RegexOption.MULTILINE)
    private val ORDERED = Regex("""^[ \t]*\d{1,3}\.\s+""", RegexOption.MULTILINE)
    private val RULE = Regex("""^[ \t]*([-*_])\1{2,}[ \t]*$""", RegexOption.MULTILINE)
    private val MANY_BLANKS = Regex("\n{2,}")
    private val TRAILING_WS = Regex("""[ \t]+$""", RegexOption.MULTILINE)

    fun strip(md: String?): String {
        if (md.isNullOrBlank()) return ""
        var s = md.replace("\r\n", "\n")
        s = FENCE.replace(s, "") // drop the fence lines, keep the code between them
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = AUTOLINK.replace(s) { it.groupValues[1] }
        s = BOLD.replace(s) { it.groupValues[1] + it.groupValues[2] }
        s = STRIKE.replace(s) { it.groupValues[1] }
        s = ITALIC.replace(s) { it.groupValues[1] + it.groupValues[2] }
        s = CODE.replace(s) { it.groupValues[1] }
        s = RULE.replace(s, "")
        s = HEADING.replace(s, "")
        s = QUOTE.replace(s, "")
        s = BULLET.replace(s, "· ")
        s = ORDERED.replace(s, "· ")
        s = TRAILING_WS.replace(s, "")
        s = MANY_BLANKS.replace(s, "\n")
        return s.trim()
    }
}
