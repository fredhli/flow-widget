package com.fredhli.flowwidget

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Pure time helpers for the header's "last generated" label ("32min ago"), the row meta
 * age ("32 min") and the 24-hour staleness rule.
 * The dashboard stamps naive local ISO ("2026-08-31T07:30:00", no zone); offset and
 * Zulu forms are accepted too so the parser survives a server-side format change.
 */
object RelativeAge {

    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /**
     * ISO timestamp -> epoch millis, or null when [iso] is null/unparseable.
     * Zoneless stamps are interpreted in [zone] (the phone's zone by default — the
     * dashboard and the phone live in the same timezone).
     */
    fun parseTs(iso: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (iso.isNullOrBlank()) return null
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // fall through: no offset in the string
        }
        return try {
            LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * The header band's right-hand label: when the feed was last generated, as an
     * INTEGER relative age — "59min ago", "1h ago", "23h ago", "1d ago". Never an
     * absolute time and never a fraction: the unit steps up only once a whole one of it
     * has passed, so 90 minutes reads "1h ago", not "1.5h". Plural-free by construction
     * (the unit is a symbol, not a word), so there is no "1 minutes" case to special-case.
     *
     * Under a minute reads "just now" rather than "0min ago"; an unknown timestamp is the
     * em dash; a timestamp slightly in the future (PC/phone clock skew) clamps to
     * "just now" instead of counting upward.
     */
    fun ago(tsMillis: Long?, nowMillis: Long): String {
        if (tsMillis == null) return "—"
        val minutes = wholeMinutes(tsMillis, nowMillis)
        return if (minutes < 1) "just now" else "${stem(minutes)} ago"
    }

    /**
     * The row meta line's compact age: "now" under a minute, then "32min", "5h", "3d" —
     * the same stem as [ago], because the header and a row are inches apart and a widget
     * that writes "1d ago" up top and "1 d" below reads like a typo. What separates them
     * is the word: [ago] states when the feed was generated, this one is the chip at the
     * tail of "headline · 32min".
     * Null/unknown timestamp -> em dash. A timestamp slightly in the future (clock
     * skew between PC and phone) clamps to "now".
     */
    fun format(tsMillis: Long?, nowMillis: Long): String {
        if (tsMillis == null) return "—"
        val minutes = wholeMinutes(tsMillis, nowMillis)
        return if (minutes < 1) "now" else stem(minutes)
    }

    /** Whole minutes elapsed, never negative (a future stamp is clock skew, not the future). */
    private fun wholeMinutes(tsMillis: Long, nowMillis: Long): Long =
        (nowMillis - tsMillis).coerceAtLeast(0) / 60_000

    /** Integer + unit symbol, one unit only: "32min", "5h", "3d". */
    private fun stem(minutes: Long): String = when {
        minutes < 60 -> "${minutes}min"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }

    /** The shared staleness contract: `latest` older than 24 h greys the titles. */
    fun isStale(tsMillis: Long?, nowMillis: Long): Boolean {
        if (tsMillis == null) return false
        return nowMillis - tsMillis > STALE_AFTER_MS
    }
}
