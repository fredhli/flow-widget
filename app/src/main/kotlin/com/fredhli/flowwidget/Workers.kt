package com.fredhli.flowwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scheduling: one periodic fetch, one periodic repaint tick, one one-shot fetch. The two
 * that touch the network only ever GET — the widget has no refresh control and never
 * POSTs /api/flow/refresh (design/BRIEF.md § "The header band"); a generation run is
 * started from the Flow page and reaches the widget as `refreshing` in the next GET.
 *
 * None of these carry a NetworkType constraint, and that is deliberate. A constrained
 * request is simply never started while the phone is offline, and every piece of offline
 * feedback this widget has — the offline mark, the ticking age text, the 24-hour grey-out
 * — is written from inside a worker. Constraining them meant the one situation the
 * offline state exists for was the one situation in which it could not be painted. Both
 * workers fail fast and cheaply with no network (a DNS lookup that does not resolve), and
 * both repaint unconditionally, so the cost of dropping the constraint is a wakeup that
 * would have happened anyway.
 */
object FlowWork {

    private const val PERIODIC_FETCH = "flow-fetch-periodic"
    private const val PERIODIC_TICK = "flow-tick-periodic"
    private const val ONE_SHOT_FETCH = "flow-fetch-once"

    /** WorkManager's floor for periodic work, and the widget's repaint resolution. */
    private const val TICK_MINUTES = 15L

    /** Every 30 minutes, connected or not. Idempotent (KEEP). */
    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC_FETCH,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FetchWorker>(30, TimeUnit.MINUTES).build(),
        )
        // A repaint-only tick between fetches. The header age and the 24-hour staleness
        // flag are derived from the clock at composition time and nothing else redraws
        // the widget, so without this a batch that landed 29 minutes ago still reads
        // "just now" and a feed that crosses 24 h at 03:10 keeps full-strength titles
        // until the next fetch. Costs no network and no DataStore write.
        wm.enqueueUniquePeriodicWork(
            PERIODIC_TICK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TickWorker>(TICK_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    fun cancelPeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_FETCH)
        wm.cancelUniqueWork(PERIODIC_TICK)
    }

    /** Fetch soon: widget added, config saved, boot. */
    fun fetchNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_FETCH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FetchWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    /* A fetch that lands mid-run caches `refreshing: true`, and the next scheduled look
     * is up to 30 minutes out — so "updating" would sit on the home screen for half an
     * hour after the run finished. While the cache says a run is live, re-look every
     * two minutes until the flag drops or the chain runs out. The cap is generous
     * against a real run (5-8 min) and finite against a stale flag, though the server
     * clears its own flag at 10 minutes anyway. */
    const val REFRESHING_RELOOK = "flow-fetch-refreshing"
    const val RELOOK_ATTEMPT = "attempt"
    private const val RELOOK_MINUTES = 2L
    private const val RELOOK_MAX = 6

    fun relookWhileRefreshing(context: Context, attempt: Int) {
        if (attempt >= RELOOK_MAX) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            REFRESHING_RELOOK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FetchWorker>()
                .setInitialDelay(RELOOK_MINUTES, TimeUnit.MINUTES)
                .setInputData(
                    androidx.work.Data.Builder().putInt(RELOOK_ATTEMPT, attempt + 1).build(),
                )
                .build(),
        )
    }
}

/**
 * Repaint, nothing else. Recomposition re-reads the wall clock, which is what makes the
 * header age and the staleness grey-out move between fetches.
 */
class TickWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        FlowWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/**
 * GET the widget feed once. Success caches the body; failure raises the offline mark
 * and keeps the cache (the widget never goes blank). Always repaints the widgets.
 */
class FetchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = FlowStore.get(applicationContext)
        val cfg = store.config() ?: return Result.success() // not configured yet
        var refreshing = false
        try {
            val body = withContext(Dispatchers.IO) {
                FlowApi.getWidgetFeed(cfg.baseUrl, cfg.token)
            }
            refreshing = FeedParser.parse(body).refreshing // validate before it becomes the cache
            store.saveFeed(body)
        } catch (t: Throwable) {
            store.markFetchFailed()
        }
        FlowWidget().updateAll(applicationContext)
        // A live run means this snapshot goes stale the moment the run ends — chase it.
        if (refreshing) {
            FlowWork.relookWhileRefreshing(
                applicationContext, inputData.getInt(FlowWork.RELOOK_ATTEMPT, 0),
            )
        }
        return Result.success()
    }
}
