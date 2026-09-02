package com.fredhli.flowwidget

import org.json.JSONException
import org.json.JSONObject

/**
 * One item of the widget feed. `ts` stays a raw ISO string (or null); `epochMs` is the
 * same instant as absolute epoch milliseconds when the server sent `epoch` (seconds).
 * Prefer `epochMs`: the naive `ts` hangs on the SERVER's wall clock (European time),
 * and interpreting it in the phone's zone put "6h ago" six hours wrong on HKT.
 */
data class FeedItem(
    val id: String,
    val title: String,
    val ts: String?,
    val kind: String,
    val epochMs: Long? = null,
    /**
     * The item's text, markdown-ish, for the expand-in-widget tap mode (round 3 item
     * 4a). Optional twice over: old cached payloads and old servers don't carry it, and
     * the widget renders the row identically either way until the row is expanded.
     * Accepted from `body` or, failing that, `summary` — the two names the dashboard
     * side plausibly ships — so a server-side rename doesn't silently empty the feature.
     */
    val body: String? = null,
    /**
     * The page's two chips under a title (2.0.1): what the item is ABOUT ("Quant",
     * "Index-ETF" — the writer's fixed vocabulary) and where it came from ("HKEX", or a
     * module key like "jht" for progress). Both optional: older payloads and servers
     * carry neither, and the meta line falls back to the kind word.
     */
    val topic: String? = null,
    val source: String? = null,
)

/** The `/api/flow/widget` payload: the newest batch only. */
data class Feed(
    val latest: String?,
    val refreshing: Boolean,
    val items: List<FeedItem>,
    /** `latest` as absolute epoch ms, from the payload's `latest_epoch` (seconds). */
    val latestEpochMs: Long? = null,
)

/**
 * Pure parser for the widget GET body. Tolerant by design: missing keys fall back
 * (items -> empty, refreshing -> false, latest/ts/epoch -> null, kind -> "headline"),
 * extra keys are ignored, and a malformed entry in `items` is skipped rather than
 * failing the whole feed. Only a body that is not a JSON object at all throws.
 *
 * `epoch` / `latest_epoch` (absolute seconds) arrived on the server side 2026-09-01,
 * beside the naive European-local `ts` strings it has always sent — the fix for the
 * widget's ages being wrong by the server-phone timezone gap. Both are optional here so
 * an old cached payload (or an old server) still parses; consumers fall back to `ts`.
 */
object FeedParser {

    const val KIND_PROGRESS = "progress"
    const val KIND_HEADLINE = "headline"

    /** @throws JSONException when [body] is not a JSON object. */
    @Throws(JSONException::class)
    fun parse(body: String): Feed {
        val root = JSONObject(body)
        val latest = optString(root, "latest")
        val refreshing = root.optBoolean("refreshing", false)
        val items = ArrayList<FeedItem>()
        val arr = root.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = optString(o, "id") ?: continue
                val title = optString(o, "title") ?: continue
                if (id.isEmpty() || title.isEmpty()) continue
                items.add(
                    FeedItem(
                        id = id,
                        title = title,
                        ts = optString(o, "ts"),
                        kind = optString(o, "kind") ?: KIND_HEADLINE,
                        epochMs = optEpochMs(o, "epoch"),
                        body = optString(o, "body") ?: optString(o, "summary"),
                        topic = optString(o, "topic")?.takeIf { it.isNotBlank() },
                        source = optString(o, "source")?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        return Feed(
            latest = latest,
            refreshing = refreshing,
            items = items,
            latestEpochMs = optEpochMs(root, "latest_epoch"),
        )
    }

    /** JSONObject.optString maps null to "null"/""; this maps absent and JSON null to null. */
    private fun optString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return v as? String ?: v?.toString()
    }

    /**
     * Epoch SECONDS in the payload -> epoch millis, or null. Absent, JSON null, junk and
     * non-positive values all read as "no epoch" so the naive-ts fallback takes over —
     * a wrong-by-hours age beats a crash, and 0/negative can only be a server bug.
     */
    private fun optEpochMs(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optLong(key, Long.MIN_VALUE)
        if (v <= 0L || v == Long.MIN_VALUE) return null
        return v * 1000L
    }
}
