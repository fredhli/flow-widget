package com.fredhli.flowwidget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's state derivation — the four contract rules the painted widget depends on:
 * the offline mark never blanks the cache, the 24-hour staleness grey, the refreshing
 * state (the server's flag, the only source there is now), and the unread dot.
 *
 * Everything here is pure: [deriveState] takes a [Preferences] snapshot and a clock
 * reading, so the rules are testable without a device.
 */
class DeriveStateTest {

    private val now = 1_756_700_000_000L // fixed clock reading, ms

    /** A naive-local ISO stamp `ageMs` before [now] — the shape the dashboard emits. */
    private fun stampAgo(ageMs: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now - ageMs), ZoneId.systemDefault()).toString()

    private fun feedJson(latestAgeMs: Long, itemAgeMs: Long = latestAgeMs, n: Int = 2): String {
        val ts = stampAgo(itemAgeMs)
        val items = (1..n).joinToString(",") {
            """{"id":"00000000000000%02d","title":"item $it","ts":"$ts","kind":"headline"}""".format(it)
        }
        return """{"latest":"${stampAgo(latestAgeMs)}","refreshing":false,"items":[$items]}"""
    }

    private fun prefs(build: MutablePreferences.() -> Unit = {}): Preferences =
        mutablePreferencesOf().apply {
            this[FlowStore.KEY_BASE_URL] = "https://dashboard.fredhli.com"
            this[FlowStore.KEY_TOKEN] = "not-a-real-token"
            build()
        }

    // ---------------------------------------------------------------- configuration

    @Test
    fun `empty prefs are unconfigured and fall back to the default base url`() {
        val s = deriveState(mutablePreferencesOf(), now)
        assertFalse(s.configured)
        assertEquals(FlowStore.DEFAULT_BASE_URL, s.baseUrl)
        assertNull(s.feed)
    }

    @Test
    fun `a base url without a token is not configured`() {
        val p = mutablePreferencesOf()
        p[FlowStore.KEY_BASE_URL] = "https://dashboard.fredhli.com"
        assertFalse(deriveState(p, now).configured)
    }

    @Test
    fun `a trailing slash is trimmed off the base url`() {
        val p = prefs { this[FlowStore.KEY_BASE_URL] = "http://100.102.21.29:8787/" }
        assertEquals("http://100.102.21.29:8787", deriveState(p, now).baseUrl)
    }

    // ---------------------------------------------------------------- cache / offline

    @Test
    fun `a cached feed paints with no network involved`() {
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = feedJson(10 * 60_000) }, now)
        assertTrue(s.configured)
        assertEquals(2, s.feed!!.items.size)
        assertEquals("10min ago", s.ageText)
    }

    @Test
    fun `a failed fetch raises the offline mark and keeps the cached items`() {
        val s = deriveState(
            prefs {
                this[FlowStore.KEY_FEED_JSON] = feedJson(10 * 60_000)
                this[FlowStore.KEY_FETCH_OK] = false
            },
            now,
        )
        assertTrue(s.offline)
        // The hard rule: offline must never mean a blank widget.
        assertEquals(2, s.feed!!.items.size)
    }

    @Test
    fun `no fetch_ok key yet is not offline`() {
        assertFalse(deriveState(prefs(), now).offline)
    }

    @Test
    fun `a successful fetch clears the offline mark`() {
        assertFalse(deriveState(prefs { this[FlowStore.KEY_FETCH_OK] = true }, now).offline)
    }

    @Test
    fun `a corrupt cache degrades to no feed instead of throwing`() {
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = "}{ not json" }, now)
        assertNull(s.feed)
        assertEquals("—", s.ageText)
        assertFalse(s.stale)
    }

    // ---------------------------------------------------------------- refreshing

    @Test
    fun `the server refreshing flag paints the updating state`() {
        val body = """{"latest":"${stampAgo(60_000)}","refreshing":true,"items":[]}"""
        assertTrue(deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = body }, now).refreshing)
    }

    @Test
    fun `the server flag is the only source of the updating state`() {
        // The widget has no refresh control and never POSTs, so nothing local can raise
        // this: a leftover local_refreshing pair from an older install is never read.
        val s = deriveState(
            prefs {
                this[FlowStore.KEY_FEED_JSON] = feedJson(60_000)
                this[booleanPreferencesKey("local_refreshing")] = true
                this[longPreferencesKey("refresh_started")] = now - 30_000
            },
            now,
        )
        assertFalse(s.refreshing)
    }

    @Test
    fun `no refresh anywhere is not refreshing`() {
        assertFalse(deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = feedJson(60_000) }, now).refreshing)
    }

    // ---------------------------------------------------------------- staleness

    @Test
    fun `latest younger than 24h is not stale`() {
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = feedJson(23 * 3_600_000L) }, now)
        assertFalse(s.stale)
        assertEquals("23h ago", s.ageText)
    }

    @Test
    fun `latest older than 24h greys the titles`() {
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = feedJson(25 * 3_600_000L) }, now)
        assertTrue(s.stale)
        assertEquals("1d ago", s.ageText)
    }

    @Test
    fun `an unknown latest is not stale`() {
        val body = """{"latest":null,"refreshing":false,"items":[]}"""
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = body }, now)
        assertFalse(s.stale)
        assertEquals("—", s.ageText)
    }

    // ---------------------------------------------------------------- unread dots

    @Test
    fun `items newer than the last list tap are unread`() {
        val item = FeedItem("a".repeat(16), "t", stampAgo(60_000), FeedParser.KIND_HEADLINE)
        assertTrue(isUnread(item, now - 10 * 60_000))
    }

    @Test
    fun `items older than the last list tap are read`() {
        val item = FeedItem("a".repeat(16), "t", stampAgo(10 * 60_000), FeedParser.KIND_HEADLINE)
        assertFalse(isUnread(item, now - 60_000))
    }

    @Test
    fun `an item with no timestamp is never dotted`() {
        assertFalse(isUnread(FeedItem("a".repeat(16), "t", null, FeedParser.KIND_HEADLINE), 0L))
    }

    @Test
    fun `a fresh install has never been opened so the whole batch is unread`() {
        val s = deriveState(prefs { this[FlowStore.KEY_FEED_JSON] = feedJson(60_000) }, now)
        assertEquals(0L, s.lastOpenMs)
        assertTrue(s.feed!!.items.all { isUnread(it, s.lastOpenMs) })
    }

    // ---------------------------------------------------------------- glass opacity

    @Test
    fun `no stored opacity paints at the default`() {
        assertEquals(GlassSurface.DEFAULT_OPACITY, deriveState(prefs(), now).bgOpacity, 1e-6f)
    }

    @Test
    fun `a stored opacity is honoured and a wild one is clamped`() {
        val stored = deriveState(prefs { this[FlowStore.KEY_BG_OPACITY] = 0.62f }, now)
        assertEquals(0.62f, stored.bgOpacity, 1e-6f)
        val wild = deriveState(prefs { this[FlowStore.KEY_BG_OPACITY] = 3f }, now)
        assertEquals(GlassSurface.MAX_OPACITY, wild.bgOpacity, 1e-6f)
    }
}
