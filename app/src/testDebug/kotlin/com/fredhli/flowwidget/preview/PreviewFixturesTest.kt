package com.fredhli.flowwidget.preview

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.fredhli.flowwidget.BodyMode
import com.fredhli.flowwidget.FeedParser
import com.fredhli.flowwidget.FlowStore
import com.fredhli.flowwidget.bodyMode
import com.fredhli.flowwidget.deriveState
import com.fredhli.flowwidget.isUnread
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the preview harness: every named fixture, pushed through the REAL
 * parser and the REAL state derivation, must land in exactly the widget state its name
 * promises. This is the part of "screenshot every widget state" that needs no device.
 *
 * The bridge mirrored here (Seed -> Preferences) is the same write set PreviewActivity
 * performs through FlowStore's API, key for key.
 */
class PreviewFixturesTest {

    private val now = 1_756_700_000_000L

    /**
     * The widget interprets the dashboard's naive-local stamps in the PHONE's zone
     * (RelativeAge.parseTs defaults to systemDefault), and PreviewActivity seeds with
     * systemDefault for exactly that reason — so this test must too. The one
     * fixed-zone assertion below pins stampAgo itself.
     */
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Apply a seed exactly the way PreviewActivity's FlowStore calls land in prefs. */
    private fun prefsFor(seed: PreviewFixtures.Seed): Preferences {
        val p: MutablePreferences = mutablePreferencesOf()
        p[FlowStore.KEY_BASE_URL] = seed.baseUrl        // saveConfig
        p[FlowStore.KEY_TOKEN] = seed.token
        p[FlowStore.KEY_FEED_JSON] = seed.feedJson      // saveFeed
        p[FlowStore.KEY_FETCH_OK] = seed.fetchOk        // saveFeed then markFetchFailed
        p[FlowStore.KEY_LAST_OPEN] = now - seed.lastOpenAgeMin * 60_000L
        return p
    }

    private fun state(name: String) =
        deriveState(prefsFor(PreviewFixtures.seedFor(name, now, zone)), now)

    // ---------------------------------------------------------------- the six defaults

    @Test
    fun `unconfigured seeds to SETUP`() {
        val s = state(PreviewFixtures.STATE_UNCONFIGURED)
        assertFalse(s.configured)
        assertEquals(BodyMode.SETUP, bodyMode(s))
    }

    @Test
    fun `loading seeds to LOADING with no offline mark`() {
        val s = state(PreviewFixtures.STATE_LOADING)
        assertEquals(BodyMode.LOADING, bodyMode(s))
        assertFalse(s.offline)
    }

    @Test
    fun `normal seeds to a fresh LIST with no dots`() {
        val s = state(PreviewFixtures.STATE_NORMAL)
        assertEquals(BodyMode.LIST, bodyMode(s))
        assertFalse(s.offline)
        assertFalse(s.stale)
        assertFalse(s.refreshing)
        assertEquals("32min ago", s.ageText)
        assertEquals(PreviewFixtures.ITEMS.size, s.feed!!.items.size)
        assertTrue(s.feed!!.items.none { isUnread(it, s.lastOpenMs) })
    }

    @Test
    fun `offline keeps the cached LIST and raises the offline mark`() {
        val s = state(PreviewFixtures.STATE_OFFLINE)
        assertEquals(BodyMode.LIST, bodyMode(s))
        assertTrue(s.offline)
        assertFalse(s.stale)
    }

    @Test
    fun `stale is a LIST past the 24h grey-out`() {
        val s = state(PreviewFixtures.STATE_STALE)
        assertEquals(BodyMode.LIST, bodyMode(s))
        assertTrue(s.stale)
        assertEquals("1d ago", s.ageText)
    }

    @Test
    fun `unread dots exactly the items newer than the last open`() {
        val s = state(PreviewFixtures.STATE_UNREAD)
        assertEquals(BodyMode.LIST, bodyMode(s))
        val unread = s.feed!!.items.filter { isUnread(it, s.lastOpenMs) }.map { it.id }
        assertEquals(listOf("fx01", "fx02", "fx03"), unread)
    }

    // ---------------------------------------------------------------- the extras

    @Test
    fun `refreshing rides on the server flag in the feed body`() {
        val seed = PreviewFixtures.seedFor(PreviewFixtures.STATE_REFRESHING, now, zone)
        // The flag has to be in the body: there is no local DataStore flag left to fake
        // the updating state with, here or on the device.
        assertTrue(FeedParser.parse(seed.feedJson).refreshing)
        val s = deriveState(prefsFor(seed), now)
        assertTrue(s.refreshing)
        assertEquals(BodyMode.LIST, bodyMode(s))
    }

    @Test
    fun `empty seeds to EMPTY with an em-dash age`() {
        val s = state(PreviewFixtures.STATE_EMPTY)
        assertEquals(BodyMode.EMPTY, bodyMode(s))
        assertEquals("—", s.ageText)
    }

    @Test
    fun `unreachable seeds to UNREACHABLE`() {
        assertEquals(BodyMode.UNREACHABLE, bodyMode(state(PreviewFixtures.STATE_UNREACHABLE)))
    }

    @Test
    fun `short is a normal LIST whose titles all fit one compact line`() {
        val s = state(PreviewFixtures.STATE_SHORT)
        assertEquals(BodyMode.LIST, bodyMode(s))
        assertFalse(s.stale)
        assertFalse(s.offline)
        assertEquals(PreviewFixtures.SHORT_ITEMS.map { it.id }, s.feed!!.items.map { it.id })
        // The whole point of the fixture. The compact title column measures ~161 dp, which
        // holds ~24 Latin characters or ~11 CJK glyphs at 14 sp; anything at or under that
        // renders as one line and so produces the short row the other fixtures never do.
        // (CJK counts double because a Han glyph is about a full em wide.)
        for (item in PreviewFixtures.SHORT_ITEMS) {
            val width = item.title.fold(0) { acc, c -> acc + if (c.code > 0x2E80) 2 else 1 }
            assertTrue("'${item.title}' is $width wide, not one compact line", width <= 22)
        }
    }

    @Test
    fun `the default gallery covers every state the brief calls non-negotiable`() {
        // design/BRIEF.md § "Non-negotiables": updating, offline, stale, unread,
        // unconfigured, empty. Round 1 shot six states and silently skipped `refreshing`
        // (the updating label) and `empty`, both named there — so the gallery's coverage
        // is asserted here rather than left to a habit.
        for (required in listOf(
            PreviewFixtures.STATE_REFRESHING,
            PreviewFixtures.STATE_EMPTY,
            PreviewFixtures.STATE_OFFLINE,
            PreviewFixtures.STATE_STALE,
            PreviewFixtures.STATE_UNREAD,
            PreviewFixtures.STATE_UNCONFIGURED,
        )) {
            assertTrue(
                "$required missing from DEFAULT_STATES",
                required in PreviewFixtures.DEFAULT_STATES,
            )
        }
        assertEquals(9, PreviewFixtures.DEFAULT_STATES.size)
        assertFalse(PreviewFixtures.STATE_SHORT in PreviewFixtures.DEFAULT_STATES)
    }

    // ---------------------------------------------------------------- fixture integrity

    @Test
    fun `every named state seeds without throwing and an unknown one throws`() {
        for (name in PreviewFixtures.ALL_STATES) PreviewFixtures.seedFor(name, now, zone)
        try {
            PreviewFixtures.seedFor("nope", now, zone)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `the fixture feed round-trips through the real parser, order preserved`() {
        val feed = FeedParser.parse(PreviewFixtures.feedJson(now, zone))
        assertEquals(PreviewFixtures.ITEMS.map { it.id }, feed.items.map { it.id })
        assertEquals(PreviewFixtures.ITEMS.map { it.kind }, feed.items.map { it.kind })
        assertFalse(feed.refreshing)
    }

    @Test
    fun `fixture output is deterministic for a fixed clock`() {
        assertEquals(
            PreviewFixtures.feedJson(now, zone),
            PreviewFixtures.feedJson(now, zone),
        )
        assertEquals(
            "2025-09-01T11:41:20",
            PreviewFixtures.stampAgo(now, 32, ZoneId.of("Asia/Singapore")),
        )
    }
}
