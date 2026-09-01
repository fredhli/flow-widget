package com.fredhli.flowwidget.preview

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import com.fredhli.flowwidget.FeedParser
import com.fredhli.flowwidget.FlowStore
import com.fredhli.flowwidget.FlowWork
import com.fredhli.flowwidget.deriveState
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only driver for the offline-fetch path on a device with no widget placed on a
 * launcher (VERIFY.md §5's offline item).
 *
 * It does not reimplement the fetch: it seeds a good batch, then calls the REAL
 * [FlowWork.fetchNow] — the same one-shot the widget runs on placement, on boot and on a
 * config save — and watches the one DataStore the widget composes from. Everything the
 * item asserts is observable there:
 *
 *   offline mark            KEY_FETCH_OK flips false once the GET fails with no network
 *   cache intact            KEY_FEED_JSON still parses to the same item count and `latest`
 *   age keeps counting      deriveState().ageText advances across a minute boundary while
 *                           still offline (recomposition re-reads the clock — the reason
 *                           TickWorker exists)
 *
 * The old step 2 of this probe tapped the header's refresh glyph through the real
 * `RefreshAction` and waited for the local spinner flag. Both are gone: the widget has no
 * refresh control and never POSTs (design/BRIEF.md § "The header band"), so the fetch is
 * the only network path left to probe, and the updating state is now the server's own
 * flag rather than anything this probe can provoke.
 *
 * Drive it with the network already off (svc wifi disable / svc data disable, or airplane
 * mode) and read the logcat tag below:
 *
 *   adb shell am start -n com.fredhli.flowwidget.debug/com.fredhli.flowwidget.preview.NetProbeActivity
 *   adb logcat -d -s FlowNetProbe:*
 *
 * Every line is `key=value`; the last line is `VERDICT pass|fail ...`. Nothing here runs
 * in, or is compiled into, the release widget — and the DataStore it seeds belongs to the
 * debug package (`applicationIdSuffix = ".debug"`), not to a shipped widget that may be
 * installed on the same device.
 */
class NetProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The offline mark arrives from a worker; give it a generous but bounded window.
        val markTimeoutMs = intent.getLongExtra("mark_timeout_ms", 120_000L)
        val ageTimeoutMs = intent.getLongExtra("age_timeout_ms", 90_000L)
        // Seed the batch as if it landed a few seconds before the last minute boundary, so
        // the age-counting check crosses one in seconds instead of up to a minute. The
        // offsets inside the fixture are unchanged, so the batch is the usual `normal` one.
        val backdateMs = intent.getLongExtra("backdate_ms", 57_000L)

        scope.launch {
            try {
                probe(markTimeoutMs, ageTimeoutMs, backdateMs)
            } catch (t: Throwable) {
                Log.e(TAG, "VERDICT fail unexpected=$t", t)
            } finally {
                finish()
            }
        }
    }

    private suspend fun probe(markTimeoutMs: Long, ageTimeoutMs: Long, backdateMs: Long) {
        val store = FlowStore.get(applicationContext)

        // ---- 1. a good batch on screen -------------------------------------------------
        val seedNow = System.currentTimeMillis() - backdateMs
        val seed = PreviewFixtures.seedFor(
            PreviewFixtures.STATE_NORMAL, seedNow, ZoneId.systemDefault(),
        )
        store.saveConfig(seed.baseUrl, seed.token)
        store.saveFeed(seed.feedJson) // also sets fetch_ok = true
        store.recordOpen(seedNow - seed.lastOpenAgeMin * 60_000L)

        val before = read(store)
        Log.i(TAG, "STEP1 seeded $before")
        if (before.items == 0 || before.offline || before.refreshing) {
            Log.e(TAG, "VERDICT fail step=1 reason=seed_not_a_good_batch $before")
            return
        }

        // ---- 2. the real fetch the widget runs on placement/boot/config-save -----------
        val startedAt = System.currentTimeMillis()
        FlowWork.fetchNow(applicationContext)
        Log.i(TAG, "STEP2 fetch_enqueued")

        // ---- 3. the offline mark, and the cache surviving it --------------------------
        val markDeadline = System.currentTimeMillis() + markTimeoutMs
        var marked = before
        while (System.currentTimeMillis() < markDeadline) {
            delay(500)
            marked = read(store)
            if (marked.offline) break
        }
        val markMs = System.currentTimeMillis() - startedAt
        Log.i(TAG, "STEP3 after_worker elapsed_ms=$markMs $marked")
        if (!marked.offline) {
            Log.e(TAG, "VERDICT fail step=3 reason=no_offline_mark_within_${markTimeoutMs}ms $marked")
            return
        }
        if (marked.items != before.items || marked.latest != before.latest) {
            Log.e(
                TAG,
                "VERDICT fail step=3 reason=cache_lost before_items=${before.items} " +
                    "before_latest=${before.latest} $marked",
            )
            return
        }

        // ---- 4. the age keeps counting, still offline ---------------------------------
        val ageStart = marked.age
        val ageDeadline = System.currentTimeMillis() + ageTimeoutMs
        var moved = marked
        while (System.currentTimeMillis() < ageDeadline) {
            delay(1000)
            moved = read(store)
            if (moved.age != ageStart) break
        }
        Log.i(TAG, "STEP4 age_from=$ageStart age_to=${moved.age} still_offline=${moved.offline} $moved")
        if (moved.age == ageStart) {
            Log.e(TAG, "VERDICT fail step=4 reason=age_frozen_at_$ageStart")
            return
        }
        if (!moved.offline || moved.items != before.items) {
            Log.e(TAG, "VERDICT fail step=4 reason=state_drifted $moved")
            return
        }

        Log.i(
            TAG,
            "VERDICT pass offline_mark_ms=$markMs cached_items=${moved.items} " +
                "latest=${moved.latest} age=$ageStart->${moved.age}",
        )
    }

    /** Everything the widget composes from, read the way the widget reads it. */
    private data class Probe(
        val items: Int,
        val latest: String?,
        val offline: Boolean,
        val refreshing: Boolean,
        val age: String,
    ) {
        override fun toString() =
            "items=$items latest=$latest offline=$offline refreshing=$refreshing age=$age"
    }

    private suspend fun read(store: FlowStore): Probe {
        val prefs: Preferences = store.snapshot()
        val now = System.currentTimeMillis()
        val ui = deriveState(prefs, now)
        val feed = prefs[FlowStore.KEY_FEED_JSON]?.let {
            runCatching { FeedParser.parse(it) }.getOrNull()
        }
        return Probe(
            items = feed?.items?.size ?: 0,
            latest = feed?.latest,
            offline = ui.offline,
            refreshing = ui.refreshing,
            age = ui.ageText,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val TAG = "FlowNetProbe"
    }
}
