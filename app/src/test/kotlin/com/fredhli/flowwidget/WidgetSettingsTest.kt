package com.fredhli.flowwidget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round-3 settings contract: absent = the shipped default = the pre-round-3
 * behaviour (which IS the upgrade migration — an old install has none of these keys and
 * must change nothing), junk normalises to the default instead of throwing or
 * half-applying, and the valid values pass through untouched.
 */
class WidgetSettingsTest {

    // ---------------------------------------------------------------- defaults / migration

    @Test
    fun `absent keys are the defaults - the upgrade migration`() {
        val s = deriveState(mutablePreferencesOf(), 0L)
        assertEquals(WidgetSettings.FONT_DEFAULT, s.titleFont)
        assertEquals(WidgetSettings.TAP_DASHBOARD, s.tapMode)
        assertNull(s.expandedId)
        assertTrue(s.readIds.isEmpty())
    }

    @Test
    fun `junk values normalise to the defaults`() {
        assertEquals(WidgetSettings.FONT_DEFAULT, WidgetSettings.titleFont("comic-sans"))
        assertEquals(WidgetSettings.FONT_DEFAULT, WidgetSettings.titleFont(""))
        assertEquals(WidgetSettings.TAP_DASHBOARD, WidgetSettings.tapMode("explode"))
    }

    @Test
    fun `valid values pass through untouched`() {
        for (f in WidgetSettings.FONTS) assertEquals(f, WidgetSettings.titleFont(f))
        for (t in WidgetSettings.TAP_MODES) assertEquals(t, WidgetSettings.tapMode(t))
    }

    @Test
    fun `stored settings reach the derived state`() {
        val p = mutablePreferencesOf()
        p[FlowStore.KEY_TITLE_FONT] = WidgetSettings.FONT_SERIF
        p[FlowStore.KEY_TAP_MODE] = WidgetSettings.TAP_EXPAND
        p[FlowStore.KEY_EXPANDED_ID] = "fx03"
        p[FlowStore.KEY_READ_IDS] = "fx01\nfx03"
        val s = deriveState(p, 0L)
        assertEquals(WidgetSettings.FONT_SERIF, s.titleFont)
        assertEquals(WidgetSettings.TAP_EXPAND, s.tapMode)
        assertEquals("fx03", s.expandedId)
        assertEquals(setOf("fx01", "fx03"), s.readIds)
    }

    // ---------------------------------------------------------------- font mapping

    @Test
    fun `the default font names no family so DeviceDefault survives`() {
        // RESEARCH §2: naming any family replaces One UI Sans; null is what keeps it.
        assertNull(WidgetSettings.fontFamilyFor(WidgetSettings.FONT_DEFAULT))
        assertNull(WidgetSettings.fontFamilyFor("junk"))
    }

    @Test
    fun `medium and serif map to the platform family strings Glance passes verbatim`() {
        assertEquals("sans-serif-medium", WidgetSettings.fontFamilyFor(WidgetSettings.FONT_MEDIUM))
        assertEquals("serif", WidgetSettings.fontFamilyFor(WidgetSettings.FONT_SERIF))
    }

    // ---------------------------------------------------------------- read ids

    @Test
    fun `read ids decode tolerantly`() {
        assertTrue(WidgetSettings.decodeReadIds(null).isEmpty())
        assertTrue(WidgetSettings.decodeReadIds("").isEmpty())
        assertEquals(setOf("a", "b"), WidgetSettings.decodeReadIds("a\n\nb\n"))
    }

    @Test
    fun `adding a read id dedupes and keeps insertion order`() {
        var raw = WidgetSettings.addReadId(null, "a")
        raw = WidgetSettings.addReadId(raw, "b")
        raw = WidgetSettings.addReadId(raw, "a") // re-read: moves to most-recent
        assertEquals("b\na", raw)
    }

    @Test
    fun `the read set trims oldest-first at the cap`() {
        var raw: String? = null
        for (i in 1..WidgetSettings.MAX_READ_IDS + 5) raw = WidgetSettings.addReadId(raw, "id$i")
        val ids = WidgetSettings.decodeReadIds(raw)
        assertEquals(WidgetSettings.MAX_READ_IDS, ids.size)
        assertFalse("id1" in ids)
        assertTrue("id${WidgetSettings.MAX_READ_IDS + 5}" in ids)
    }

    // ---------------------------------------------------------------- unread rule

    @Test
    fun `expanding marks exactly that item read and leaves the others dotted`() {
        val item = { id: String -> FeedItem(id, "t", null, "headline", epochMs = 1_000L) }
        val readIds = setOf("fx02")
        assertTrue(isUnread(item("fx01"), 0L, readIds))
        assertFalse(isUnread(item("fx02"), 0L, readIds))
        // and the timestamp rule still floors it: an old item is read either way
        assertFalse(isUnread(item("fx01"), 2_000L, readIds))
    }
}
