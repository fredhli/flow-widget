package com.fredhli.flowwidget.preview

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Deterministic widget-state fixtures for the debug preview harness.
 *
 * Pure by design (java.time only, no Android imports) so testDebug can unit-test it.
 * Every fixture is expressed as a [Seed]: the full set of values the preview activity
 * writes into the app's real DataStore before composing the real widget. All fields are
 * written on every seeding, so a preview run never inherits state from the previous one.
 *
 * Timestamps are OFFSETS from the caller's `nowMs`, because the widget derives its age
 * text and staleness from the wall clock at composition time (see `deriveState`). Fixed
 * offsets — not fixed instants — are what make the rendered text stable run to run
 * ("32min ago" stays "32min ago" whenever the screenshot is taken). Stamps are naive-local
 * ISO, the shape the dashboard emits.
 */
object PreviewFixtures {

    const val STATE_UNCONFIGURED = "unconfigured"
    const val STATE_LOADING = "loading"
    const val STATE_NORMAL = "normal"
    const val STATE_OFFLINE = "offline"
    const val STATE_STALE = "stale"
    const val STATE_UNREAD = "unread"

    // The remaining body modes and header states. `updating` and `empty` are named in
    // design/BRIEF.md's non-negotiable state list just like the six above, and all three
    // of these got new visuals in the redesign (the violet "updating…" label that replaced
    // the deleted refresh glyph, and the NotePill treatment for empty/unreachable), so
    // they belong in the default gallery rather than beside it.
    const val STATE_REFRESHING = "refreshing"
    const val STATE_EMPTY = "empty"
    const val STATE_UNREACHABLE = "unreachable"

    /**
     * A batch whose titles fit on ONE line at the compact width — the case every other
     * fixture hides, because all six [ITEMS] titles wrap to two lines at 4x2 and so only
     * ever exercise the tall row. It is the shorter row that tests the compact bucket's
     * 48 dp tap minimum, and `flow/FLOW_SPEC.md` §147's 10-24 字 Chinese headlines land
     * squarely in it: ~11 CJK glyphs is one line in the 161 dp title column.
     */
    const val STATE_SHORT = "short"

    /** The states the screenshot script iterates by default. */
    val DEFAULT_STATES = listOf(
        STATE_UNCONFIGURED, STATE_LOADING, STATE_NORMAL,
        STATE_OFFLINE, STATE_STALE, STATE_UNREAD,
        STATE_REFRESHING, STATE_EMPTY, STATE_UNREACHABLE,
    )

    /**
     * [STATE_SHORT] is deliberately not in the default gallery: it is a geometry probe for
     * the compact bucket, not a state the widget can be *in*, and shooting it at 4x3 says
     * nothing. Shoot it explicitly: `STATES=short SIZES=4x2 widget-shots.sh`.
     */
    val ALL_STATES = DEFAULT_STATES + listOf(STATE_SHORT)

    /** Placeholder config for configured states. Never a real token. */
    const val FIXTURE_BASE_URL = "https://dashboard.fredhli.com"
    const val FIXTURE_TOKEN = "preview-fixture-token"

    /** `deriveState` treats an unparseable body as no feed; "" is the no-feed sentinel. */
    const val NO_FEED = ""

    /** How far behind `now` the newest batch sits in fresh states — header "32min ago". */
    const val FRESH_AGE_MIN = 32L

    /** Staleness offset: 26 h, comfortably past the 24 h grey-out — header "1d ago". */
    const val STALE_AGE_MIN = 26L * 60

    /** Unread fixture: everything newer than 90 min ago carries the dot (3 of 6 items). */
    const val UNREAD_LAST_OPEN_AGE_MIN = 90L

    /** Deterministic items: id, title, age in minutes behind `now`, kind. */
    data class FixtureItem(val id: String, val title: String, val ageMin: Long, val kind: String)

    val ITEMS = listOf(
        FixtureItem("fx01", "Overnight batch sealed — 14 headlines, 2 progress notes", 32, "headline"),
        FixtureItem("fx02", "Smart-beta sleeve drifted 40 bp past its band", 47, "headline"),
        FixtureItem("fx03", "JHT backfill finished: 128 sessions reconciled", 63, "progress"),
        FixtureItem("fx04", "Fed minutes: three officials leaned toward a hold", 128, "headline"),
        FixtureItem("fx05", "Cockpit deploy went green in 41 s", 190, "progress"),
        FixtureItem("fx06", "AUD hedging note refreshed for the September roll", 300, "headline"),
    )

    /**
     * [STATE_SHORT]'s items. Every title fits one line in the ~161 dp compact title
     * column: three short Latin ones and two Chinese headlines at the bottom of
     * FLOW_SPEC's 10-24 字 range, which is the realistic shape once the skill's language
     * settles. A one-line row is the row whose height nothing else in the gallery shows.
     */
    val SHORT_ITEMS = listOf(
        FixtureItem("sx01", "Backfill done", 32, "progress"),
        FixtureItem("sx02", "No 70+ today", 47, "headline"),
        FixtureItem("sx03", "美联储按兵不动", 63, "headline"),
        FixtureItem("sx04", "Deploy green", 128, "progress"),
        FixtureItem("sx05", "澳元对冲已更新", 190, "headline"),
    )

    /**
     * Everything the preview activity writes before composing. All fields are always
     * applied (config, feed body, offline mark, last-open), so seeding is idempotent and
     * order-independent between preview runs.
     *
     * There is no refreshing field: the updating state now lives entirely in the feed
     * body's `refreshing` flag, the way the server sends it, so [STATE_REFRESHING] is a
     * feed fixture like every other state rather than a separate DataStore flag.
     */
    data class Seed(
        val baseUrl: String,
        val token: String,
        val feedJson: String,
        val fetchOk: Boolean,
        val lastOpenAgeMin: Long,
    ) {
        val configured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()
    }

    /** Naive-local ISO stamp `ageMin` minutes before [nowMs], second precision. */
    fun stampAgo(nowMs: Long, ageMin: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs - ageMin * 60_000L), zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()

    /**
     * The fixture feed body: [ITEMS] shifted [extraAgeMin] further into the past,
     * `latest` = the newest item's stamp. JSON built by hand so this stays dependency-free;
     * FeedParserTest-style tests confirm it round-trips through the real parser.
     */
    fun feedJson(
        nowMs: Long,
        zone: ZoneId,
        extraAgeMin: Long = 0,
        refreshing: Boolean = false,
        empty: Boolean = false,
        source: List<FixtureItem> = ITEMS,
    ): String {
        val latest = stampAgo(nowMs, FRESH_AGE_MIN + extraAgeMin, zone)
        val items = if (empty) "" else source.joinToString(",") {
            """{"id":"${it.id}","title":"${it.title.replace("\"", "\\\"")}","ts":"${stampAgo(nowMs, it.ageMin + extraAgeMin, zone)}","kind":"${it.kind}"}"""
        }
        return """{"latest":${if (empty) "null" else "\"$latest\""},"refreshing":$refreshing,"items":[$items]}"""
    }

    /**
     * State name -> the full seed. Throws [IllegalArgumentException] on an unknown name
     * (the activity logs it; the script surfaces the log).
     */
    fun seedFor(state: String, nowMs: Long, zone: ZoneId): Seed {
        fun configured(
            feedJson: String,
            fetchOk: Boolean = true,
            lastOpenAgeMin: Long = 0L, // "opened just now": no unread dots
        ) = Seed(FIXTURE_BASE_URL, FIXTURE_TOKEN, feedJson, fetchOk, lastOpenAgeMin)

        return when (state) {
            STATE_UNCONFIGURED -> Seed("", "", NO_FEED, true, 0L)
            STATE_LOADING -> configured(NO_FEED)
            STATE_NORMAL -> configured(feedJson(nowMs, zone))
            STATE_OFFLINE -> configured(feedJson(nowMs, zone), fetchOk = false)
            STATE_STALE -> configured(feedJson(nowMs, zone, extraAgeMin = STALE_AGE_MIN))
            STATE_UNREAD -> configured(feedJson(nowMs, zone), lastOpenAgeMin = UNREAD_LAST_OPEN_AGE_MIN)
            // The server says a run is in flight — the only way the widget ever sees it.
            STATE_REFRESHING -> configured(feedJson(nowMs, zone, refreshing = true))
            STATE_EMPTY -> configured(feedJson(nowMs, zone, empty = true))
            STATE_UNREACHABLE -> configured(NO_FEED, fetchOk = false)
            STATE_SHORT -> configured(feedJson(nowMs, zone, source = SHORT_ITEMS))
            else -> throw IllegalArgumentException(
                "unknown preview state '$state' — one of: ${ALL_STATES.joinToString()}"
            )
        }
    }
}
