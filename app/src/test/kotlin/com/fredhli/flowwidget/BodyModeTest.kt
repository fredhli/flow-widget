package com.fredhli.flowwidget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the five body states the widget paints. The one that matters most is
 * UNREACHABLE: configured but with no cache and a failed fetch is a terminal state (a
 * mistyped token saved through "Save anyway", or a server that was down when the widget
 * was added), and this APK has no launcher icon, so painting "Loading…" there strands the
 * user with no route back to the config screen.
 */
class BodyModeTest {

    private val now = 1_756_700_000_000L

    private fun stampAgo(ageMs: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now - ageMs), ZoneId.systemDefault()).toString()

    private fun feedJson(n: Int): String {
        val ts = stampAgo(60_000)
        val items = (1..n).joinToString(",") {
            """{"id":"00000000000000%02d","title":"item $it","ts":"$ts","kind":"headline"}""".format(it)
        }
        return """{"latest":"${stampAgo(60_000)}","refreshing":false,"items":[$items]}"""
    }

    private fun configured(build: MutablePreferences.() -> Unit = {}): Preferences =
        mutablePreferencesOf().apply {
            this[FlowStore.KEY_BASE_URL] = "https://dashboard.fredhli.com"
            this[FlowStore.KEY_TOKEN] = "not-a-real-token"
            build()
        }

    private fun mode(p: Preferences) = bodyMode(deriveState(p, now))

    @Test
    fun `no config at all offers setup`() {
        assertEquals(BodyMode.SETUP, mode(mutablePreferencesOf()))
    }

    @Test
    fun `configured with no cache and no failure is still loading`() {
        assertEquals(BodyMode.LOADING, mode(configured()))
    }

    @Test
    fun `configured, fetch failed, nothing cached is a dead end that offers setup`() {
        val p = configured { this[FlowStore.KEY_FETCH_OK] = false }
        assertEquals(BodyMode.UNREACHABLE, mode(p))
    }

    @Test
    fun `an unparseable cache plus a failed fetch is also unreachable, not loading`() {
        val p = configured {
            this[FlowStore.KEY_FEED_JSON] = "{not json"
            this[FlowStore.KEY_FETCH_OK] = false
        }
        assertEquals(BodyMode.UNREACHABLE, mode(p))
    }

    @Test
    fun `a failed fetch over a good cache still paints the list`() {
        val p = configured {
            this[FlowStore.KEY_FEED_JSON] = feedJson(n = 3)
            this[FlowStore.KEY_FETCH_OK] = false
        }
        assertEquals(BodyMode.LIST, mode(p))
    }

    @Test
    fun `an empty batch is empty, not unreachable`() {
        val p = configured {
            this[FlowStore.KEY_FEED_JSON] = """{"latest":null,"refreshing":false,"items":[]}"""
            this[FlowStore.KEY_FETCH_OK] = false
        }
        assertEquals(BodyMode.EMPTY, mode(p))
    }

    @Test
    fun `a healthy feed paints the list`() {
        val p = configured {
            this[FlowStore.KEY_FEED_JSON] = feedJson(n = 5)
            this[FlowStore.KEY_FETCH_OK] = true
        }
        assertEquals(BodyMode.LIST, mode(p))
    }
}
