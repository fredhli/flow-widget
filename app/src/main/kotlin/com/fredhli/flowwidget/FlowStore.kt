package com.fredhli.flowwidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private val Context.flowDataStore: DataStore<Preferences> by preferencesDataStore(name = "flow_widget")

/** Base URL + token as one value; null until the config activity has been saved once. */
data class FlowConfig(val baseUrl: String, val token: String)

/**
 * The app's one DataStore. Holds the config (base URL + token — the token lives only
 * here, never in a log line and never in source), the last successful feed body (the
 * cache the widget paints from on reboot), and the small state flags.
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
    }
}
