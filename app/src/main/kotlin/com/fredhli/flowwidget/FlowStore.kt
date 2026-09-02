package com.fredhli.flowwidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fredhli.flowwidget.app.LinkPolicy
import com.fredhli.flowwidget.app.ShellPrefs
import com.fredhli.flowwidget.app.TapTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private val Context.flowDataStore: DataStore<Preferences> by preferencesDataStore(name = "flow_widget")

/** Base URL + token as one value; null until the config activity has been saved once. */
data class FlowConfig(val baseUrl: String, val token: String)

/**
 * The app's one DataStore. Holds the config (base URL + token — the token lives only
 * here, never in a log line and never in source), the last successful feed body (the
 * cache the widget paints from on reboot), the small state flags, and — since 2.0.0 —
 * the app shell's three settings.
 *
 * **One store, and it has to stay one.** `preferencesDataStore(name = "flow_widget")`
 * above is a delegate that owns a single file plus the in-process actor that serialises
 * writes to it. A second delegate over the same name — the obvious way to give the shell
 * "its own" store — creates a second actor over the same file, which DataStore itself
 * documents as unsupported and which shows up as an IllegalStateException at best and a
 * lost write or a corrupted file at worst. So the shell's keys live here, next to the
 * widget's, and the two halves of the app share one snapshot: MainActivity reads config
 * and shell prefs from the same `data` collection instead of racing two stores.
 */
class FlowStore private constructor(private val appContext: Context) {

    val data: Flow<Preferences> get() = appContext.flowDataStore.data

    suspend fun snapshot(): Preferences = data.first()

    suspend fun config(): FlowConfig? = configFrom(snapshot())

    suspend fun saveConfig(baseUrl: String, token: String) {
        appContext.flowDataStore.edit {
            it[KEY_BASE_URL] = baseUrl
            it[KEY_TOKEN] = token
        }
    }

    /** A GET succeeded: cache the raw body, clear the offline mark. */
    suspend fun saveFeed(body: String) {
        appContext.flowDataStore.edit {
            it[KEY_FEED_JSON] = body
            it[KEY_FETCH_OK] = true
        }
    }

    /** A fetch failed: keep the cache untouched, raise the offline mark. */
    suspend fun markFetchFailed() {
        appContext.flowDataStore.edit { it[KEY_FETCH_OK] = false }
    }

    /** The config screen's two opacity sliders: one glass alpha per theme. */
    suspend fun saveOpacity(light: Float, dark: Float) {
        appContext.flowDataStore.edit {
            it[KEY_BG_OPACITY] = GlassSurface.clampLight(light)
            it[KEY_BG_OPACITY_DARK] = GlassSurface.clampDark(dark)
        }
    }

    /** The widget's list was tapped: everything up to now counts as read. */
    suspend fun recordOpen(nowMillis: Long) {
        appContext.flowDataStore.edit { it[KEY_LAST_OPEN] = nowMillis }
    }

    /** The app shell's settings, defaults for anything never written. */
    suspend fun shellPrefs(): ShellPrefs = shellPrefsFrom(snapshot())

    /**
     * Write all three shell settings at once. The settings screen saves on every tap, so
     * this is a tiny, frequent edit — one `edit` block keeps it one file rewrite instead
     * of three, and keeps a half-applied state off disk if the process dies mid-save.
     */
    suspend fun saveShellPrefs(prefs: ShellPrefs) {
        appContext.flowDataStore.edit {
            it[KEY_TAP_TARGET] = prefs.tapTarget.storageValue
            it[KEY_LINK_POLICY] = prefs.linkPolicy.storageValue
            it[KEY_TEXT_ZOOM] = ShellPrefs.clampZoom(prefs.textZoom)
        }
    }

    companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_FEED_JSON = stringPreferencesKey("feed_json")
        val KEY_FETCH_OK = booleanPreferencesKey("fetch_ok")
        val KEY_LAST_OPEN = longPreferencesKey("last_open")

        // There is no local_refreshing / refresh_started pair any more. Those two keys
        // existed to paint the updating state between a refresh tap and the worker that
        // served it; with the refresh control gone (design/BRIEF.md § "The header band")
        // nothing in the app can start a run, so the server's `refreshing` flag in the
        // feed body is the whole updating state. Any stale values left in an upgraded
        // install's DataStore are simply never read.

        /**
         * Light-theme glass opacity (GlassSurface.MIN_OPACITY..MAX); absent -> the
         * default. This is the key the single pre-split slider wrote, which is exactly
         * the migration design/BRIEF.md's round-2 device feedback asks for: an existing
         * stored value simply *becomes* the light value, and dark starts at its default.
         */
        val KEY_BG_OPACITY = floatPreferencesKey("bg_opacity")

        /** Dark-theme glass opacity (GlassSurface.MIN_OPACITY_DARK..MAX); absent -> default. */
        val KEY_BG_OPACITY_DARK = floatPreferencesKey("bg_opacity_dark")

        // The app shell's three settings (2.0.0). They are stored as the enums' own
        // `storageValue` strings rather than as ordinals: an ordinal is a promise never to
        // reorder the enum, and a preferences file outlives any such promise. An absent or
        // unrecognised value falls back to the default in `fromStorage`, so an install
        // upgraded from 1.1.1 — where none of these keys exist — reads exactly the decided
        // defaults (widget taps open the app, links open Chrome, text follows the system).

        /** "app" | "browser"; absent -> app. Read by OpenItemActivity on every widget tap. */
        val KEY_TAP_TARGET = stringPreferencesKey("tap_target")

        /** "chrome" | "custom_tab" | "default_browser"; absent -> chrome. */
        val KEY_LINK_POLICY = stringPreferencesKey("link_policy")

        /** WebView text zoom: 0 = follow the system font scale; else a percent 50..200. */
        val KEY_TEXT_ZOOM = intPreferencesKey("text_zoom")

        const val DEFAULT_BASE_URL = "https://dashboard.fredhli.com"

        @Volatile private var instance: FlowStore? = null

        fun get(context: Context): FlowStore =
            instance ?: synchronized(this) {
                instance ?: FlowStore(context.applicationContext).also { instance = it }
            }

        fun configFrom(prefs: Preferences): FlowConfig? {
            val base = prefs[KEY_BASE_URL]?.trim()?.trimEnd('/')
            val token = prefs[KEY_TOKEN]
            if (base.isNullOrEmpty() || token.isNullOrEmpty()) return null
            return FlowConfig(base, token)
        }

        /**
         * The shell's settings out of a snapshot. Takes a `Preferences` rather than
         * reading the store itself so MainActivity can map the same `data` flow it already
         * collects for `configFrom` — one collection, both halves of the state, no second
         * suspend point that could observe a different moment than the config did.
         */
        fun shellPrefsFrom(prefs: Preferences): ShellPrefs = ShellPrefs(
            tapTarget = TapTarget.fromStorage(prefs[KEY_TAP_TARGET]),
            linkPolicy = LinkPolicy.fromStorage(prefs[KEY_LINK_POLICY]),
            textZoom = ShellPrefs.clampZoom(prefs[KEY_TEXT_ZOOM] ?: ShellPrefs.TEXT_ZOOM_SYSTEM),
        )
    }
}
