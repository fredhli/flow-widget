package com.fredhli.flowwidget

import org.json.JSONException
import org.json.JSONObject

/**
 * One item of the widget feed. `ts` stays a raw ISO string (or null); parsing to a
 * point in time is [RelativeAge]'s job.
 */
data class FeedItem(
    val id: String,
    val title: String,
    val ts: String?,
    val kind: String,
)

/** The `/api/flow/widget` payload: the newest batch only. */
data class Feed(
    val latest: String?,
    val refreshing: Boolean,
    val items: List<FeedItem>,
)

/**
 * Pure parser for the widget GET body. Tolerant by design: missing keys fall back
 * (items -> empty, refreshing -> false, latest/ts -> null, kind -> "headline"),
 * extra keys are ignored, and a malformed entry in `items` is skipped rather than
 * failing the whole feed. Only a body that is not a JSON object at all throws.
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
                    )
                )
            }
        }
        return Feed(latest = latest, refreshing = refreshing, items = items)
    }

    /** JSONObject.optString maps null to "null"/""; this maps absent and JSON null to null. */
    private fun optString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return v as? String ?: v?.toString()
    }
}
