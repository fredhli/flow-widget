package com.fredhli.flowwidget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * The Flow widget, redesigned per design/BRIEF.md ("Bixby's surface + Gmail's feed"):
 * a translucent diagonal-gradient glass container (GlassSurface, opacity user-set in
 * the config screen), Gmail-shaped feed rows carrying their own subtle fill that does
 * the legibility work, generous §3 spacing, the system font throughout.
 *
 * Two responsive buckets — 4x2 (header + compact rows) and 4x3 (header + rows with a
 * meta line). Paints from the DataStore cache, so it renders instantly after reboot
 * and never goes blank on a failed fetch.
 */
class FlowWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, TALL, FOLD))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = FlowStore.get(context)
        // Load BEFORE composing. Glance publishes the first idle composition straight to
        // the host, and a DataStore read started from inside the composition cannot beat
        // that idle — so composing against a null placeholder pushes a near-empty
        // RemoteViews to the launcher on every session start (the post-reboot repaint,
        // every 30-minute fetch, every 15-minute tick). With the snapshot already in hand
        // the first frame is the cached feed, which is the "never blank" contract.
        val initial = runCatching { store.snapshot() }.getOrElse { emptyPreferences() }
        provideContent {
            val prefs by store.data.collectAsState(initial = initial)
            WidgetBody(prefs)
        }
    }

    companion object {
        /** 4x2 — header + compact rows. */
        val COMPACT = DpSize(250.dp, 110.dp)

        /** 4x3 — header + rows with meta line. */
        val TALL = DpSize(250.dp, 180.dp)

        /**
         * The Fold 8 cover cell (round 3). Measured off the reference screenshots
         * (design/round3/fold-cover-110pct-a.jpg, 1972x1248 @ ~2.625 px/dp): the
         * provider lays out ~397x399 dp on Fred's 4-column cover grid and ~490x493 on
         * the 5-column one — with Good Lock Home Up's 110% scale the launcher may
         * report those ÷1.1 (~361 dp). 340 dp is safely under every one of those
         * readings and safely over anything a 250 dp-bucket phone cell reports, so the
         * bucket selection is robust to which number One UI actually sends. The height
         * matches TALL: the fold cell is a *width* fact, and rows just fill whatever
         * height the placement really has.
         */
        val FOLD = DpSize(340.dp, 180.dp)
    }
}

// ------------------------------------------------------------------ glass surface

/**
 * The container surface: TWO stacked drawable layers since the device-feedback round,
 * because the themes now carry independent opacities (two sliders, two stored floats —
 * design/BRIEF.md round 2 device feedback #1) and one resource id cannot encode both
 * levels. [GlassLight] is indexed by [GlassSurface.lightLevelFor]: a real gradient in
 * `drawable/`, fully transparent in `drawable-night/`. [GlassDark] is the mirror image,
 * indexed by [GlassSurface.darkLevelFor]. FlowWidget draws both; the *host* resolves each
 * id against its own configuration at apply time, so exactly one gradient is visible and
 * a system dark-mode flip still swaps the surface with the text — the property the whole
 * resource scheme exists for (see GlassSurface's header: a bitmap baked at composition
 * time left night ink on a day surface at 1.03:1 until the next composition).
 *
 * Generated by `tools/gen-glass-drawables.py`; re-run it with `--check` to prove the
 * resources and the level math have not drifted apart.
 */
private val GlassLight = intArrayOf(
    R.drawable.glass_light_00, R.drawable.glass_light_01, R.drawable.glass_light_02, R.drawable.glass_light_03,
    R.drawable.glass_light_04, R.drawable.glass_light_05, R.drawable.glass_light_06, R.drawable.glass_light_07,
    R.drawable.glass_light_08, R.drawable.glass_light_09, R.drawable.glass_light_10, R.drawable.glass_light_11,
    R.drawable.glass_light_12, R.drawable.glass_light_13, R.drawable.glass_light_14, R.drawable.glass_light_15,
)

private val GlassDark = intArrayOf(
    R.drawable.glass_dark_00, R.drawable.glass_dark_01, R.drawable.glass_dark_02, R.drawable.glass_dark_03,
    R.drawable.glass_dark_04, R.drawable.glass_dark_05, R.drawable.glass_dark_06, R.drawable.glass_dark_07,
    R.drawable.glass_dark_08, R.drawable.glass_dark_09, R.drawable.glass_dark_10, R.drawable.glass_dark_11,
    R.drawable.glass_dark_12, R.drawable.glass_dark_13, R.drawable.glass_dark_14, R.drawable.glass_dark_15,
    R.drawable.glass_dark_16, R.drawable.glass_dark_17, R.drawable.glass_dark_18, R.drawable.glass_dark_19,
    R.drawable.glass_dark_20, R.drawable.glass_dark_21, R.drawable.glass_dark_22, R.drawable.glass_dark_23,
    R.drawable.glass_dark_24, R.drawable.glass_dark_25, R.drawable.glass_dark_26, R.drawable.glass_dark_27,
    R.drawable.glass_dark_28, R.drawable.glass_dark_29, R.drawable.glass_dark_30, R.drawable.glass_dark_31,
    R.drawable.glass_dark_32, R.drawable.glass_dark_33, R.drawable.glass_dark_34, R.drawable.glass_dark_35,
    R.drawable.glass_dark_36, R.drawable.glass_dark_37, R.drawable.glass_dark_38, R.drawable.glass_dark_39,
    R.drawable.glass_dark_40, R.drawable.glass_dark_41, R.drawable.glass_dark_42, R.drawable.glass_dark_43,
    R.drawable.glass_dark_44, R.drawable.glass_dark_45, R.drawable.glass_dark_46, R.drawable.glass_dark_47,
    R.drawable.glass_dark_48, R.drawable.glass_dark_49, R.drawable.glass_dark_50, R.drawable.glass_dark_51,
    R.drawable.glass_dark_52, R.drawable.glass_dark_53, R.drawable.glass_dark_54, R.drawable.glass_dark_55,
    R.drawable.glass_dark_56, R.drawable.glass_dark_57, R.drawable.glass_dark_58, R.drawable.glass_dark_59,
    R.drawable.glass_dark_60, R.drawable.glass_dark_61, R.drawable.glass_dark_62, R.drawable.glass_dark_63,
    R.drawable.glass_dark_64, R.drawable.glass_dark_65,
)

// ------------------------------------------------------------------ palette

// Explicit day/night providers instead of GlanceTheme: the surface is a translucent
// gradient the wallpaper glows through, so text needs known-contrast colors, not the
// opaque-surface roles of a Material scheme. The accent is the Flow violet — the
// non-negotiable identity color for the kind glyph, the unread dot and the updating note.
private val AccentColor = ColorProvider(day = Color(0xFF7C3AED), night = Color(0xFFB7A6FF))
private val TitleColor = ColorProvider(day = Color(0xFF1B1C22), night = Color(0xFFF3F2F8))

/** Meta/secondary ink: the title ink at 70% (design/RESEARCH.md §3). */
private val MetaColor = ColorProvider(day = Color(0xB31B1C22), night = Color(0xB3F3F2F8))

/** Stale (>24 h) ink: 55% — visibly grey against TitleColor but still legible on glass. */
private val StaleColor = ColorProvider(day = Color(0x8C1B1C22), night = Color(0x8CF3F2F8))

/**
 * Row fill — the legibility mechanism that replaces blur (RESEARCH.md §1, §4b: ~45%).
 *
 * 45% white in light, 9% white in dark, and the asymmetry is not a typo: white on a light
 * container lifts the row *away* from dark ink, white on a near-black container lifts it
 * *towards* light ink. So the fill does the legibility work in light mode and only the
 * row-shape work in dark, where the container's own opacity is what keeps a bright
 * wallpaper off the text — and since the device-feedback round that opacity is Fred's
 * dark slider, floored at [GlassSurface.MIN_OPACITY_DARK] (0.30, his call) rather than
 * the old computed 0.80.
 */
private val RowFill = ColorProvider(day = Color(0x73FFFFFF), night = Color(0x1EFFFFFF))

/**
 * The compact row's minimum content height, so a one-line title still clears the 48dp
 * tap minimum: 48 - 2 x the 8dp compact row padding. See the strut in [ItemRow].
 */
private val COMPACT_ROW_MIN_CONTENT = 32.dp

// ------------------------------------------------------------------ derived state

internal data class UiState(
    val configured: Boolean,
    val baseUrl: String,
    val feed: Feed?,
    val offline: Boolean,
    val refreshing: Boolean,
    val stale: Boolean,
    /** The header band's label: "32min ago" — when the feed was last generated. */
    val ageText: String,
    val lastOpenMs: Long,
    val bgOpacityLight: Float,
    val bgOpacityDark: Float,
    /** Row-title font choice (WidgetSettings.FONTS); "default" keeps DeviceDefault. */
    val titleFont: String,
    /** What an item tap does (WidgetSettings.TAP_MODES). */
    val tapMode: String,
    /** The row whose body is expanded inline, expand-mode only. */
    val expandedId: String?,
    /** Ids marked read by expanding — they lose the dot even while newer than lastOpen. */
    val readIds: Set<String>,
)

internal fun deriveState(prefs: Preferences, nowMs: Long): UiState {
    val cfg = FlowStore.configFrom(prefs)
    val feed = prefs[FlowStore.KEY_FEED_JSON]?.let {
        runCatching { FeedParser.parse(it) }.getOrNull()
    }
    val offline = prefs[FlowStore.KEY_FETCH_OK] == false
    // Prefer the absolute `latest_epoch` the server now sends: the naive `latest` string
    // hangs on the SERVER's wall (European time), and parsing it in the phone's zone made
    // every age wrong by the server-phone offset — 6 hours on HKT. The string parse stays
    // as the fallback for a payload cached before the server carried epochs.
    val latestMs = feed?.latestEpochMs ?: RelativeAge.parseTs(feed?.latest)
    return UiState(
        configured = cfg != null,
        baseUrl = cfg?.baseUrl ?: FlowStore.DEFAULT_BASE_URL,
        feed = feed,
        offline = offline,
        // The server's flag is now the only source of the updating state: the widget
        // has no refresh control of its own, so a run always starts on the Flow page
        // and reaches the widget through `refreshing` in the next GET.
        refreshing = feed?.refreshing == true,
        stale = RelativeAge.isStale(latestMs, nowMs),
        ageText = RelativeAge.ago(latestMs, nowMs),
        lastOpenMs = prefs[FlowStore.KEY_LAST_OPEN] ?: 0L,
        // KEY_BG_OPACITY is the pre-split single slider's key, so an upgraded install's
        // stored value becomes the light opacity and dark starts at its own default —
        // the migration design/BRIEF.md's device-feedback round specifies.
        bgOpacityLight = GlassSurface.clampLight(prefs[FlowStore.KEY_BG_OPACITY]),
        bgOpacityDark = GlassSurface.clampDark(prefs[FlowStore.KEY_BG_OPACITY_DARK]),
        // The round-3 settings. Absent keys ARE the migration: WidgetSettings maps
        // absent (and junk) to the defaults, which are the pre-round-3 behaviours.
        titleFont = WidgetSettings.titleFont(prefs[FlowStore.KEY_TITLE_FONT]),
        tapMode = WidgetSettings.tapMode(prefs[FlowStore.KEY_TAP_MODE]),
        expandedId = prefs[FlowStore.KEY_EXPANDED_ID],
        readIds = WidgetSettings.decodeReadIds(prefs[FlowStore.KEY_READ_IDS]),
    )
}

/** What the body area shows. Split out of the composable so it can be tested. */
internal enum class BodyMode {
    /** Never configured: tap opens the config screen. */
    SETUP,

    /** Configured, but the server has never answered — a dead end without a way back. */
    UNREACHABLE,

    /** Configured, no cache yet, no failure recorded: the first fetch is in flight. */
    LOADING,

    /** The server answered with an empty batch. */
    EMPTY,

    /** The normal case. */
    LIST,
}

internal fun bodyMode(state: UiState): BodyMode = when {
    !state.configured -> BodyMode.SETUP
    // Offline with nothing cached is terminal, not transient: a mistyped token saved
    // through "Save anyway", or a server that was down when the widget was added, leaves
    // the widget here forever. "Loading…" would be a lie, and with no launcher icon the
    // only route back is a One UI gesture nobody remembers — so offer the config screen.
    state.offline && state.feed == null -> BodyMode.UNREACHABLE
    state.feed == null -> BodyMode.LOADING
    state.feed.items.isEmpty() -> BodyMode.EMPTY
    else -> BodyMode.LIST
}

/**
 * The row's meta line ("Quant · HKEX · 5h"): the page's two chips — topic, then source —
 * and the relative age. Fred's first run of 2.0.0: the page showed "Quant HKEX" under a
 * title where the widget showed only "headline", so since 2.0.1 the chips lead and the
 * kind word is the fallback for an item that has neither (an older payload; the glyph
 * already says headline/progress). A module-key source is spelled the way flow.js spells
 * it ([sourceLabel]). Pure so it is testable off-device.
 */
internal fun metaLine(item: FeedItem, nowMs: Long): String {
    val chips = listOfNotNull(item.topic, item.source?.let(::sourceLabel))
        .filter { it.isNotBlank() }
    val head = if (chips.isEmpty()) item.kind else chips.joinToString(" · ")
    val ts = item.epochMs ?: RelativeAge.parseTs(item.ts) ?: return head
    return "$head · ${RelativeAge.format(ts, nowMs)}"
}

/** flow.js's SOURCES map: a progress item's module key, spelled for a reader. */
internal fun sourceLabel(source: String): String = when (source) {
    "jht" -> "JHT"
    "smart-beta" -> "Smart Beta"
    "morning" -> "Morning"
    else -> source
}

// ------------------------------------------------------------------ layout

@Composable
private fun WidgetBody(prefs: Preferences) {
    val now = System.currentTimeMillis()
    val state = deriveState(prefs, now)
    val size = LocalSize.current
    val tall = size.height >= 160.dp
    // The Fold 8 cover cell (round 3): a threshold, not an equality against FOLD, so it
    // holds whether LocalSize arrives as the bucket (a launcher) or as the raw cell size
    // (the preview harness composing at the measured 397x399 / 490x493 dp cells).
    val fold = size.width >= FlowWidget.FOLD.width
    // Note what is NOT read here: the night-mode bit. Each surface layer is a day/night
    // drawable resource the launcher resolves at apply time, so nothing in this
    // composition needs to know which theme it will be painted in — which is exactly why
    // the surface survives a system dark-mode flip between compositions. Two layers
    // because the themes carry independent opacities since the device-feedback round:
    // the light layer is transparent at night and the dark layer transparent by day, so
    // exactly one gradient ever shows.
    val glassLight = GlassLight[GlassSurface.lightLevelFor(state.bgOpacityLight)]
    val glassDark = GlassDark[GlassSurface.darkLevelFor(state.bgOpacityDark)]
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(glassLight), contentScale = ContentScale.FillBounds)
            .cornerRadius(24.dp)
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(glassDark), contentScale = ContentScale.FillBounds)
                .cornerRadius(24.dp)
                // §3 container padding: 16dp sides. Vertically the deviation is deliberate
                // and recorded in design/BRIEF.md's spacing table as its own column: the
                // 48dp header band centres its own text and so pays for the top itself,
                // and the bottom is what the 110dp compact bucket can still afford under a
                // whole row.
                .padding(horizontal = 16.dp)
                .padding(bottom = if (tall) 10.dp else 4.dp)
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Header(state, fold)
                // An inert gutter between two differently-actioned targets: the band opens
                // `#/flow`, item row 1 opens that item. Round 1 left 2dp here on the reasoning
                // that deleting the refresh glyph left "no neighbouring target to mis-hit" —
                // false, the first row is that neighbour, and it sat 1.8dp away. 8dp is
                // DESIGN-NOTES entry 1's number; the compact bucket pays 6dp, matching its own
                // inter-row gap, because 8 would crop the single row it can draw.
                Spacer(GlanceModifier.height(if (tall) 8.dp else 6.dp))
                // The weight lives here: defaultWeight() is a ColumnScope extension and
                // does not resolve inside the child composables.
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    when (bodyMode(state)) {
                        BodyMode.SETUP -> SetupNote("Tap to set up Flow")
                        BodyMode.UNREACHABLE -> SetupNote("Can't reach Flow — tap to set up")
                        BodyMode.LOADING -> CenteredNote("Loading…")
                        BodyMode.EMPTY -> CenteredNote("No flow yet")
                        BodyMode.LIST -> ItemList(state, tall, fold, now)
                    }
                }
            }
        }
    }
}

/**
 * The header band (design/BRIEF.md § "The header band", amended 2026-09-01).
 *
 * There is no refresh control: a tap trigger on a home-screen widget is a mis-touch
 * magnet, and refreshing is the Flow page's job. So the band carries only what it says —
 * "Flow" on the left, the feed's last-generation age on the right — and the *whole* band,
 * full width and a fixed 48dp tall, is one tap target opening `#/flow` (through the
 * trampoline, so the unread dots clear on the way).
 *
 * The inert gutter DESIGN-NOTES entry 1 asked to preserve is still needed, and lives in
 * [WidgetBody] as the spacer under this band. Deleting the refresh glyph removed one
 * neighbouring target, not all of them: item row 1 fires a different action (that item,
 * not the feed) and is the band's new neighbour.
 *
 * The pair is inset 16dp from the container padding since 2.0.1 (Fred, first on-device
 * run of 2.0.0): flush with the rows' outer edge, "Flow" read as hanging off the cards'
 * corner; 16dp is the rows' corner radius, so the title's left edge now sits on the
 * centre of that corner's circle, and the ago label mirrors it on the right. The 6dp of
 * top padding nudges the pair 3dp down inside the band (it is centred as one block) for
 * the same reason. The band itself — the tap target — is unchanged, full width.
 */
@Composable
private fun Header(state: UiState, fold: Boolean) {
    val context = LocalContext.current
    // The pair share one optical baseline since round 3: centring each text in the band
    // separately floats the smaller ago label's baseline ABOVE the title's — exactly the
    // "rides high" the fold-cover reference shots show. Glance has no baseline
    // alignment, but for two runs of the same family, bottom-aligning the text boxes is
    // baseline alignment to within the difference of their descents (~1sp here): the
    // inner Row bottom-aligns the pair, and the outer Box centres the pair as one block
    // inside the 48dp band, which stays the single full-band tap target.
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(
                actionStartActivity(
                    Intent(context, OpenItemActivity::class.java)
                        .putExtra(OpenItemActivity.EXTRA_URL, "${state.baseUrl}/#/flow")
                )
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Flow",
                style = TextStyle(
                    color = TitleColor,
                    // 17sp, up from §3's 15, because CJK glyphs fill the em box where
                    // Latin leaves headroom (round 2 device feedback #3) — and 19.5sp at
                    // the fold cell, where 17sp Latin still read visually level with the
                    // 15sp CJK titles on the cover screen (round 3 item 2). The header
                    // must be the biggest text on the widget at every size.
                    fontSize = if (fold) 19.5.sp else 17.sp,
                    // §3 asks for weight 600; Glance exposes 400/500/700 and Medium(500)
                    // reads too light next to the mockup — Bold is the closer match.
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (state.offline) {
                // Bottom-aligned like the texts, inside a 17dp box that parks the 14dp
                // glyph at its top — i.e. lifted 3dp off the shared bottom line, so it
                // sits beside the ago label's x-height instead of on its descent line.
                // (Padding can't do this: Glance padding is view-internal and would
                // shrink the drawn glyph inside its fixed 14dp box.)
                Box(
                    modifier = GlanceModifier.width(14.dp).height(17.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_offline),
                        contentDescription = context.getString(R.string.cd_offline),
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(MetaColor),
                    )
                }
                Spacer(GlanceModifier.width(6.dp))
            }
            // The one age in the widget's chrome, on the right, in the meta grey — and
            // the updating note in its place while the server says a run is in flight,
            // which is how a refresh started from the web page shows up here.
            Text(
                text = if (state.refreshing) "updating…" else state.ageText,
                style = TextStyle(
                    color = if (state.refreshing) AccentColor else MetaColor,
                    fontSize = if (fold) 13.5.sp else 12.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ItemList(state: UiState, tall: Boolean, fold: Boolean, nowMs: Long) {
    val context = LocalContext.current
    val feed = state.feed ?: return
    val gap = if (tall) 8.dp else 6.dp
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(feed.items) { item ->
            Box(modifier = GlanceModifier.padding(bottom = gap)) {
                ItemRow(
                    context = context,
                    item = item,
                    baseUrl = state.baseUrl,
                    stale = state.stale,
                    unread = isUnread(item, state.lastOpenMs, state.readIds),
                    tall = tall,
                    fold = fold,
                    nowMs = nowMs,
                    titleFamily = WidgetSettings.fontFamilyFor(state.titleFont),
                    expandMode = state.tapMode == WidgetSettings.TAP_EXPAND,
                    expanded = state.tapMode == WidgetSettings.TAP_EXPAND &&
                        state.expandedId == item.id,
                )
            }
        }
    }
}

internal fun isUnread(item: FeedItem, lastOpenMs: Long): Boolean {
    val ts = item.epochMs ?: RelativeAge.parseTs(item.ts) ?: return false
    return ts > lastOpenMs
}

/**
 * The full unread rule since round 3: newer than the last list tap AND not individually
 * marked read by expanding it in the widget (item 4a — expanding is reading).
 */
internal fun isUnread(item: FeedItem, lastOpenMs: Long, readIds: Set<String>): Boolean =
    isUnread(item, lastOpenMs) && item.id !in readIds

@Composable
private fun ItemRow(
    context: Context,
    item: FeedItem,
    baseUrl: String,
    stale: Boolean,
    unread: Boolean,
    tall: Boolean,
    fold: Boolean,
    nowMs: Long,
    titleFamily: String?,
    expandMode: Boolean,
    expanded: Boolean,
) {
    val titleColor = if (stale) StaleColor else TitleColor
    val glyph = if (item.kind == FeedParser.KIND_PROGRESS) R.drawable.ic_progress else R.drawable.ic_headline
    val glyphCd = if (item.kind == FeedParser.KIND_PROGRESS) R.string.cd_progress else R.string.cd_headline
    // "Tap on an item" (round 3 item 4b/4a): the default opens the item in the app
    // through the trampoline (OpenItemActivity → MainActivity, never a URL since 2.0.1);
    // expand-mode instead toggles this row's inline body via an ActionCallback in this
    // process — no activity at all.
    val tapAction = if (expandMode) {
        actionRunCallback<ToggleItemAction>(
            actionParametersOf(ToggleItemAction.KEY_ITEM_ID to item.id)
        )
    } else {
        actionStartActivity(
            Intent(context, OpenItemActivity::class.java)
                .putExtra(OpenItemActivity.EXTRA_URL, "$baseUrl/#/flow/i/${item.id}")
        )
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            // The row fill is what earns legibility on the translucent surface.
            .background(RowFill)
            .cornerRadius(16.dp)
            // §3: 12dp vertical / 14dp horizontal at 4x3. The compact bucket trades that
            // down to 8dp (and drops the meta line) so a two-line row clears the 110dp
            // bucket floor under the 48dp header band — 12dp would make it 59dp against a
            // 52dp body. That trade is NOT §3 working as written, whatever DESIGN-NOTES
            // used to say about it; it is a compact-bucket column in design/BRIEF.md's
            // spacing table, recorded there so the next round does not "restore" it.
            .padding(horizontal = 14.dp, vertical = if (tall) 12.dp else 8.dp)
            .clickable(tapAction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The glyph's box is also the compact row's height floor. The row wraps its
        // content, so a title short enough to fit one line — a CJK headline at the bottom
        // of FLOW_SPEC's 10-24 字 range, "Backfill done" — made the row ~35 dp: an
        // action-firing target 13 dp under the minimum this project holds itself to, right
        // beside another row's target. Padding cannot fix it (15 dp of padding would make
        // the two-line row 65 dp against a 52 dp body); a minimum can, and giving it to
        // the glyph costs no extra view. Because it is a floor and not a fixed height, a
        // two-line title — or a large system font scale — still grows the row past it.
        Box(
            modifier = GlanceModifier
                .width(16.dp)
                .height(if (tall) 16.dp else COMPACT_ROW_MIN_CONTENT),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(glyph),
                contentDescription = context.getString(glyphCd),
                modifier = GlanceModifier.size(16.dp),
                colorFilter = ColorFilter.tint(if (stale) StaleColor else AccentColor),
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            // §3 asks for 14sp at line-height 1.35. The size is honoured; the line height
            // cannot be — Glance 1.1.1's TextStyle has seven fields (color, fontSize,
            // fontWeight, fontStyle, textAlign, textDecoration, fontFamily) and no
            // lineHeight, verified against the shipped glance-1.1.1.aar. The platform
            // default renders 1.17, ~6px per line under spec. Recorded beside blur and
            // bundled fonts in design/RESEARCH.md §2 rather than left reading as met; the
            // row's own padding is what buys the breathing room instead.
            Text(
                text = item.title,
                // Medium (500), for the CJK titles the feed actually carries: on API 29+
                // Glance turns FontWeight.Medium into a TextAppearanceSpan with
                // android:textFontWeight=500 over TextAppearance.DeviceDefault — the
                // device-default family (One UI Sans on the Fold) at weight 500, CJK
                // fallback included where the device ships weighted CJK faces. CJK at 500
                // sits better beside One UI Sans Latin than 400 does. Since round 3 the
                // family can ALSO be named — Fred's "Title font" dropdown; null (the
                // default) keeps DeviceDefault, i.e. One UI Sans, while "sans-serif-
                // medium"/"serif" pass verbatim into TypefaceSpan (the passthrough round
                // 2 verified) and deliberately replace it.
                // minSdk=31, so Glance's pre-29 textStyle=bold fallback is unreachable.
                // 15sp at the fold cell (+1 over the phone buckets, round 3 item 2).
                style = TextStyle(
                    color = titleColor,
                    fontSize = if (fold) 15.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = titleFamily?.let { FontFamily(it) },
                ),
                maxLines = 2,
            )
            if (tall) {
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = metaLine(item, nowMs),
                    style = TextStyle(
                        color = if (stale) StaleColor else MetaColor,
                        fontSize = if (fold) 12.5.sp else 12.sp,
                    ),
                    maxLines = 1,
                )
            }
            if (expanded) {
                // The inline body (round 3 item 4a): markdown stripped to plain text,
                // clamped to 5 lines. The server has sent bodies in the widget slice since
                // 2.0.1 (before that every expanded row read "—", which is what Fred's
                // first 2.0.0 screenshot showed); the em dash stays for a body that is
                // genuinely empty, so the row never feels dead. The text is its own tap
                // target — the item in the app — so expand mode still has a way to the
                // full item without leaving the widget for a URL. A child click wins over
                // the row's toggle in RemoteViews, so the title still toggles.
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = Markdown.strip(item.body).ifEmpty { "—" },
                    style = TextStyle(
                        color = if (stale) StaleColor else MetaColor,
                        fontSize = if (fold) 13.5.sp else 13.sp,
                    ),
                    maxLines = 5,
                    modifier = GlanceModifier.clickable(
                        actionStartActivity(
                            Intent(context, OpenItemActivity::class.java)
                                .putExtra(OpenItemActivity.EXTRA_URL, "$baseUrl/#/flow/i/${item.id}")
                        )
                    ),
                )
            }
        }
        if (unread) {
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .size(7.dp)
                    .cornerRadius(4.dp)
                    .background(AccentColor)
            ) {}
        }
    }
}

@Composable
private fun SetupNote(text: String) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity(Intent(context, ConfigActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        NotePill(text)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NotePill(text)
    }
}

/** State notes sit in a row-styled pill so they read on the glass like the feed does. */
@Composable
private fun NotePill(text: String) {
    Box(
        modifier = GlanceModifier
            .background(RowFill)
            .cornerRadius(16.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(color = MetaColor, fontSize = 13.sp),
            maxLines = 2,
        )
    }
}
