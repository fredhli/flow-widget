# Design-session inputs

Things found while verifying the widget that were **not** fixed at the time, because fixing
them would change what the release widget draws and the visual design was frozen. The
freeze outranked the finding; this file is where the finding waited.

**The freeze was lifted on 2026-09-01 for the redesign round** (`design/BRIEF.md`, section
"Round 1 implementation"). Every entry below is now in scope. Each one carries a **Status**
line, and the agent that integrates the round flips it — nothing else in the entry moves,
so the original measurement stays readable next to what was done about it.

*(Convention amended 2026-09-01, round 2. "Nothing else moves" turned out to be unworkable
the moment a round measured the same thing again: entry 2 grew new round-1 text
interleaved, unmarked, with pre-round text that contradicted it, and a reader going
top-to-bottom hit the stale paragraph as the entry's last word. So: new measurements go in
a **dated sub-heading** at the end of the entry, and any pre-round paragraph they falsify
is struck through rather than left standing. The original measurement still survives —
struck, not deleted — which is what the old rule was protecting.)*

Status vocabulary, and nothing else:

| Status | Means |
|---|---|
| `open` | Nobody has touched it. The state every entry starts in. |
| `assigned to round N` | In scope for the current round, not yet verified fixed. |
| `fixed in round N — <how it was verified>` | Done, and the evidence is named: a shot, a `touch-target.py` reading, a collage. |
| `still open — <why>` | The round looked at it and deliberately did not fix it. A reason is required. |
| `superseded — <by what>` | The finding stopped being true for a different reason (the layout it described no longer exists). |

Each entry says what was measured, where to see it, and what it would cost to change — not
what the fix should be. That was the design session's call, and `design/BRIEF.md` records
what it decided.

Nothing here is a bug in the sense of "broken". The widget passed every functional item in
`tools/VERIFY.md` before the round and must still pass every one of them after it; these
are places where the visual design and a guideline disagreed, or where the layout did
something the plan did not predict.

## At a glance

| # | Finding | Status |
|---|---|---|
| 1 | Title tap target is 21.7 dp tall (48 dp minimum) | `fixed in round 1 — measured 48.0 dp`, gutter restored in round 2 |
| 2 | The 4×2 bucket renders one item row, not three | `still open at the phone buckets — but round 3 measured the device's real cell at ~397×399 dp, where the widget shows four-plus rows; see the round-3 sub-heading` |
| 3 | A one-line title makes a ~35 dp item row at 4×2 | `fixed in round 2 — measured 48.0 dp` |
| 4 | §3's title line-height 1.35 cannot be expressed in Glance | `still open in Glance — but round 3 measured One UI's CJK line pitch at ~1.77× on the device, past the 1.35 wish; see the round-3 sub-heading` |
| 5 | The opacity slider is quantised, not continuous | `still open — light 16×3%; dark is 66×1% since the device-feedback round, indistinguishable from continuous` |

---

## 1. The title tap target is 21.7 dp tall (48 dp is the minimum)

**Status**: `fixed in round 1 — tools/touch-target.py on a live normal 4x2 dump reads the
header band at 572x126 px = 217.9 x 48.0 dp, containing both "Flow" and the ago label, with
exactly two clickable nodes in the whole widget (band + item row) and no `Refresh` node at
all.` Corroborated with three real taps — the top edge over the ago label, the dead centre,
and the bottom-left corner — each of which launched
`https://dashboard.fredhli.com/#/flow`, read back from `dumpsys activity recents`. The
21.7 dp target is gone because the *title* is no longer the target: the band is.
~~The 8 dp inert gutter this entry asked to preserve is gone too, and deliberately — it
separated the title from the refresh glyph, and with the glyph deleted there is no
neighbouring target left to mis-hit.~~ See `design/BRIEF.md` § "The header band".

**Measured** on the `flow` AVD (pixel_7, 1080x2400, 420 dpi, 2.625 px/dp), 4x2 bucket, via
`tools/touch-target.py` reading a uiautomator dump of the live view tree:

| | px | dp | |
|---|---|---|---|
| refresh glyph, drawn | 53 x 53 | 20.2 x 20.2 | matches `size(20.dp)` |
| refresh **tap target** | 126 x 126 | **48.0 x 48.0** | meets the minimum |
| **title tap target** | 437 x 57 | 166.5 x **21.7** | **under the minimum** |
| inert gutter between them | 21 | 8.0 | matches `Spacer(width = 8.dp)` |

The title ("Flow", which opens `#/flow`) gets the text's own height, centred in the 48 dp
header band, rather than the band. A thumb aimed at the top or bottom of the header band and
left of the gutter hits nothing at all.

**Why it was left alone.** It fails *safe*: a miss does nothing rather than firing the wrong
action, which is the only thing VERIFY §5 item 12 actually asserts, and item 12 passes.
Widening it means giving the title row a height — a change to what the header draws.

**Where to look**: `screenshots/baseline-2026-09-01/normal-4x2-light.png`, the header band.
The 8 dp gutter must survive the fix: the two targets meeting edge to edge would turn a
mis-aimed refresh into an opened browser tab, which is the failure item 12 exists to rule
out.

### Re-measured 2026-09-01 (round 2): the gutter clause was closed on a false premise

The struck sentence above is wrong, and the requirement in "Where to look" — the one it
overruled — was right. Deleting the refresh glyph removed **one** neighbouring target, not
all of them: **item row 1 fires a different action** (`#/flow/i/<id>`, that item) from the
band (`#/flow`, the feed), and round 1 left them **1.8 dp** apart. Measured on
`screenshots/redesign-r1/gallery/normal-4x3-light.png` at 2.625 px/dp: the band ends at
dp 50.0 and the first row's fill begins at dp 51.8, while every other seam in the same
frame holds the design's own 8 dp rhythm. A thumb aimed at the band's lower edge — the
"32min ago" side, the natural place to look for freshness — that drifts 2 dp low opens an
item instead of the feed. Exactly the mis-hit class the gutter existed to prevent,
recreated one target lower.

`touch-target.py` could not catch it: it asserted "no band/row overlap", which a 0 dp gap
satisfies. It now measures the gutter as a number.

**Fixed in round 2.** An inert spacer of **8 dp** at 4×3 and **6 dp** at 4×2 — the compact
bucket matches its own inter-row gap because 8 dp would crop the single row it can draw,
and the trade is a column in `design/BRIEF.md`'s spacing table rather than a silent one.
Measured on the round-2 frames: band 48.0 dp ending at px 1181, row 1 starting at px 1197
= **6.1 dp** at 4×2; **8.0 dp** at 4×3. Severity was medium, not high, and it is worth
saying why the fix is still worth its pixels: both neighbours land on the same dashboard,
so a mis-hit is recoverable — but it cost 6 dp of a bucket that had 0.95 dp of slack, which
is where the container's bottom padding went (16 dp → 4 dp at 4×2, now recorded in the
table).

---

## 2. The 4x2 bucket renders one item row, not three

**Status**: `still open — round 1 spent the height on breathing room, so 4x2 is still exactly
one row.` `design/BRIEF.md` § "What the two buckets promise" asked for two or three; the
measured answer is one, and 4x3 went the same way.

Measured on the round-1 gallery (`screenshots/redesign-r1/`, same AVD and density as the
"before"):

| | body height available | row pitch | rows shown |
|---|---|---|---|
| 4x2, 250x110 dp floor | 52 dp | 51 dp (no meta line) | **1**, exactly filling it |
| 4x3, 250x180 dp floor | 116 dp | 86 dp (78 dp row + 8 dp gap) | **1 full + 45% of the next** |

The 48 dp header band costs 52 dp of the 110 dp bucket before a row is drawn, and RESEARCH
§3's spacing — adopted *as written* and binding — makes a two-line row ~~51 dp at 4x2 and~~
78 dp at 4x3. Both numbers are the brief working as specified; the row count is what pays
for it. Note 4x3 got **less** dense than the baseline, which showed two full rows plus a
third starting: the meta line and the row padding are the difference.

> **Correction, round 2.** The 78 dp is genuinely §3. The 51 dp is not: §3's 12 dp row
> padding makes the compact row **59 dp**, which does not fit the 110 dp bucket at all.
> What ships at 4×2 is an 8 dp variant — Gmail's own number, the one RESEARCH §3 names as
> "the main complaint" — chosen because a §3-conformant row would be cropped ~7 dp, cutting
> the second title line. That was the right trade and it bought a whole, uncropped row; it
> was never "the brief working as specified", and crediting §3 with it is how the variant
> stayed out of the spacing table for a round. It is a column in that table now.

This is now a design question, not a layout bug, and the arithmetic bounds it: at an 86 dp
pitch the brief's "4x3 = header plus five or six rows" needs ~480 dp of widget height, above
the provider's own `maxResizeHeight="450dp"`. Five rows is not reachable at this spacing on
any cell the widget can be resized to. Something has to give — the 2-line clamp, the meta
line, the row padding, or the promise — and choosing which is a design call, not an
acceptance one.

`README.md` now describes the measured behaviour instead of the plan's old "about three":
**4×2** reads "header plus one row, and the list scrolls to the rest" and **4×3** reads
"header plus one row and the start of the next".

*(Corrected round 2: this line used to quote README as saying "the first few rows". README
has never contained that phrase — a `grep` for it across the whole folder hits only this
sentence — so an agent sent to update that wording would have found nothing to update.)*

VERIFY §5 item 2 ~~expects "header plus ~3 rows" at 4x2 (the 250x110 dp bucket)~~ was
rewritten in round 1 to the measured behaviour. The real composition renders the header
plus exactly **one** row: titles wrap to two lines, and two wrapped lines plus the header
consume the whole body.

~~**Where to look**: `screenshots/baseline-2026-09-01/normal-4x2-light.png` and
`normal-4x2-dark.png`, next to the 4x3 pair, which behave as the plan describes.~~

~~This is the single biggest gap between the plan's picture of the widget and what the phone
shows, and it is entirely a layout/typography question: title line count, row height, font
size, or how many items the compact bucket should promise at all. 4x3 is unaffected.~~

### Re-measured 2026-09-01 (round 2)

The struck paragraph above is pre-round text that the round-1 measurement had already
falsified, and leaving it standing made "4x3 is unaffected" the entry's last word — the
opposite of the table at the top of this entry. **4×3 was affected, and got less dense**:
`screenshots/baseline-2026-09-01/normal-4x3-light.png` shows two full rows plus a third
starting, `screenshots/redesign-r1/gallery/normal-4x3-light.png` shows one full row plus a
clipped second. Every other document in round 1 took the corrected side (`design/BRIEF.md`
and `tools/VERIFY.md` both say so); this entry was the only stale one.

Round 2 moved the numbers a little further, in the same direction and for a stated reason:
the 8 dp gutter restored under the header band (entry 1) costs 6 dp of body, so 4×3 now
shows **one full row plus ~36% of the second** (measured: row 1 is 78.1 dp at px 1110,
row 2 is clipped to 28.2 dp) and 4×2 still shows exactly one. It also put a **48 dp floor**
under the compact row (entry 3), which changes nothing here — the rows that set the density
are the two-line ones, and they were already above the floor.

**This entry is still the single biggest gap between the plan's picture of the widget and
what the phone shows, and it is still a design call rather than an acceptance one**: the
2-line clamp, the meta line, the row padding, or the promise. The arithmetic has not moved
either — at an 86 dp pitch the brief's "5-6 rows" needs ~480 dp against the provider's own
`maxResizeHeight="450dp"`, so it is unreachable at this spacing on any cell the widget can
be resized to.

### Re-measured 2026-09-02 (round 3): the device's real cell dissolves most of this

The arithmetic above was all conducted at the 250 dp phone buckets, because until round 3
nobody had measured the cell the widget actually lives in. The Fold 8 **cover screen**
hands the provider **≈397×399 dp** (4-column grid) or ≈490×493 dp (5-column) —
`design/RESEARCH.md` §4c, measured off Fred's own screenshots — and at that cell the
references show **four to five rows with meta lines**, which is the density the brief's
"5–6 rows" wish was reaching for. Round 3 added the `FOLD` bucket (340×180 dp width
threshold) so typography scales up there instead of the phone sizes stretching. The
phone-bucket numbers in this entry are unchanged and still true — for a phone-sized cell.
Status stays `still open` on the letter of the 4×2 promise; in practice the cell that
matters shows a real feed.

---

## 3. A one-line title makes a ~35 dp item row at 4x2 (48 dp is the minimum)

**Status**: `fixed in round 2 — tools/touch-target.py on a live `short` 4x2 dump reads
item row 1 at 572x126 px = 217.9 x 48.0 dp, up from the ~35 dp the same title produced
before.` The tool asserts it now instead of printing it and discarding the verdict.

**Measured** (round 1, same AVD and density as everything else here). The compact row wraps
its content: 8 dp padding, the title, 8 dp padding. A two-line title makes that 51.05 dp,
which is what every frame in the 24-shot gallery showed — and clears 48 dp by 3 dp, which is
presumably why nobody looked further. A **one-line** title makes it `51.05 − 16.38` (the
measured line pitch) = **34.7 dp**: an action-firing target 13 dp under the minimum this
project adopted as its own standard, sitting 6 dp from the next row's target.

**Why no frame showed it.** All six `PreviewFixtures.ITEMS` titles are 33–56 characters and
the compact title column measures ~161 dp — about 24 Latin characters — so every fixture
wraps. `mock_server.py`'s eight titles are all 36+ too. Both of the round's evidence
sources were structurally blind to it: the gallery, and `touch-target.py`, which called
`describe()` for item rows and threw the return value away, so it would have *printed*
"UNDER 48dp" and still exited 0.

**Not hypothetical.** `flow/FLOW_SPEC.md` §147 prescribes Chinese titles of 10–24 字. At
14 sp a Han glyph is about a full em, so ~11 characters is one line in that column — the
bottom of the spec's own range.

**The fix, and what it could not be.** Padding cannot produce a floor: 15 dp of vertical
padding would make the *two*-line row 65 dp against a 52 dp body. A fixed height cannot
either — it clips at large system font scales. What works is a minimum: the glyph's box is
given a height of 32 dp at 4×2, which with the 8 dp padding on each side floors the row at
exactly 48 dp while leaving a two-line row (or a large font scale) free to grow past it.
It costs no extra view.

**Caveat, recorded so the next round does not read this as a general win.** §3's own 12 dp
padding would still only reach 42.7 dp on a one-line row, so *no* spec-conformant compact
row reaches 48 dp on its own — and the pre-redesign baseline was no better. This is a floor
bolted on, not the spacing table producing the right answer.

**Where to look**: `screenshots/redesign-r2/evidence/short-4x2-light.png` and
`short-4x2-dark.png`. The `short` fixture exists for this and nothing else.

---

## 4. §3's title line-height of 1.35 cannot be expressed in Glance

**Status**: `still open — struck from the spec rather than met; the only escape hatch costs
the Glance composable, which is a call for Fred, not for an acceptance round.`

`androidx.glance.text.TextStyle` in 1.1.1 has seven fields — color, fontSize, fontWeight,
fontStyle, textAlign, textDecoration, fontFamily — and no `lineHeight`. Verified by
disassembling the shipped `glance-1.1.1.aar`, not from memory. Measured on
`screenshots/redesign-r1/gallery/normal-4x3-light.png`: the two title ink bands are y194–228
and y237–271, a **43 px pitch** at 420 dpi against a 36.75 px (14 sp) font = **1.17**, where
1.35 would be 49.6 px. Same on the dark frame, the unread frame and the 4×2 pair.

This matters more than a 13% miss usually would, because line spacing is **Fred's first
complaint about the Gmail widget** and 1.35 was the direct answer to it. Nothing in
`design/` or this file recorded it as unattainable, so it read as a met requirement for a
whole round — unlike blur (RESEARCH §1) and bundled fonts (§2), both of which are recorded.
It is recorded now, in §2, next to them.

**What it would cost to change.** `AndroidRemoteViews` does ship in glance-appwidget, so a
hand-rolled `RemoteViews` `TextView` with `android:lineSpacingMultiplier="1.35"` is
technically reachable — at the price of leaving Glance's `Text` composable for the title and
hand-managing the 2-line clamp, the day/night colour and the stale-grey with it. The
alternative is to buy the intended optical spacing another way (a slightly larger font, more
row padding) and say so. Round 2 did neither: it struck the number and left the choice here.

### Re-measured 2026-09-02 (round 3): on the device the complaint is already answered

The 1.17 pitch above is the **emulator's Latin** metrics. Round 3 measured the round-3
reference screenshots — the real widget, One UI 9, 中文 titles — and the CJK title lines
pitch at **~65 px at 2.625 px/dp ≈ 24.8 dp against a 14 sp font, i.e. ~1.77×**
(`design/RESEARCH.md` §4c): One UI Sans's CJK fallback carries far taller vertical metrics
than Roboto's Latin. So for the feed the widget actually shows (FLOW_SPEC 中文), the
platform default *overshoots* §3's struck 1.35 rather than undershooting it, with no code
involved. Glance still cannot express a line height and Latin-only titles still render
~1.17 — the entry stays `still open` on that letter — but nobody should burn the
composable on the escape hatch for a spacing the device already delivers.

---

## 5. The opacity slider is quantised to 16 steps, not continuous

**Status**: `still open — the price of a surface that survives a system theme flip. Round 2
took the declarative option; the continuous one is still available and is Fred's call.`
*(Re-graded in the dated sub-heading at the end of this entry: the device-feedback round
made the dark slider 66×1% — effectively continuous — and light is unchanged at 16×3%.)*

`design/BRIEF.md` specified a continuous 0.50 → 0.95 slider. What ships is 16 steps of 3%.
The reason is not laziness: round 1's container gradient was a **bitmap baked at composition
time**, which froze the theme into pixels — a system dark-mode flip left night ink on a day
surface at 1.03:1, invisible, until the next composition up to a Doze-stretched 15 minutes
away — as well as baking the corner radius at the wrong size and marshalling ~2 MB to the
launcher on every update. The cure is a day/night **drawable resource**, which the launcher
resolves at apply time exactly as it already does for every colour in the widget. A resource
cannot carry a runtime float, so the alpha has to be one of a fixed set.

The grid was chosen to hide the seam: 3% steps land on 0.74 and 0.80 exactly, and no two
neighbouring steps are distinguishable. But it *is* a constraint the brief did not ask for,
so it is recorded rather than quietly absorbed.

**What it would cost to change.** A hand-built `RemoteViews` background layer — an
`ImageView` with a day/night `src` plus `RemoteViewsCompat.setImageViewImageAlpha` — keeps
both the apply-time theme *and* a continuous float, at the cost of leaving Glance for the
background layer and of the theme-aware dark floor no longer being expressible in the
resource (it would have to come back as a composition-time number, which is the class of
thing that went stale in the first place). Verified reachable, not attempted.

### Re-graded 2026-09-01 (round 2, device feedback): mostly moot

The device-feedback round split the opacity into two sliders (`design/BRIEF.md`, its #1),
which changed the resource scheme under this entry: two stacked per-theme drawable
families instead of one paired family. That freed each grid to be sized on its own, and
**dark is now 66 levels of 1%** — the only uniform step landing its 0.30 floor, 0.74
default and 0.95 ceiling all on-grid — which is finer than a finger can place a SeekBar
on that range. Light keeps 16 levels of 3%, so this entry is literally still true there
and the escape hatch above is still the route to a continuous float, but the visible gap
between "16 steps" and "continuous" was already below the eye's threshold and the dark
half is now below the finger's. Status stays `still open` on the letter of the brief;
nobody should spend a round on it.

---

## Not on this list

The other twelve findings from the 2026-09-01 verification pass were **fixed** at the time,
not deferred — all of them were in the support tooling (`tools/emu.sh`,
`tools/sync-to-dropbox.sh`, `tools/widget-shots.sh`), in the debug-only preview harness, or
in prose in the dashboard's own docs. Nothing in `app/src/main/` changed during that pass,
and the APK it produced was byte-identical (`sha256 b54516f9…`) to the shipped one.

**That byte-identity ends with the redesign round, on purpose.** Round 1 changes
`app/src/main/`, so the release APK's bytes change. What must *not* change is what the APK
declares: the same package, the same single widget provider and config activity, **the same
permissions** (one declared — `INTERNET` — and six after WorkManager's manifest merges in;
"two" appeared here and in VERIFY §8b step 7 and was never a count of anything), no launcher
activity, no `preview/` classes in the release dex. VERIFY §8's release-unchanged check is
written for exactly that distinction — components identical, bytes different — and it is the
check that has to pass now, not the sha256 line above.

*(Round 3 passed it 2026-09-02: `aapt2 dump badging` of the new release against the shipped
`apk/flow-widget.apk` — package, versionCode 3 / 1.1.1 unchanged (no bump, per the
orchestrator's rule), activities, receivers, permissions — diffed empty. The round-3
`ToggleItemAction` adds no manifest component: it rides glance-appwidget's existing
`ActionCallbackBroadcastReceiver`.)*

---

## How to look at the widget before changing it

The pre-redesign gallery — 6 states x 2 sizes x light/dark, 24 frames — is in
`screenshots/baseline-2026-09-01/`, captured from the emulator with no manual widget
placement. From round 2 the default set is **9 states x 2 x 2 = 36 frames** (`updating`,
`empty` and `unreachable` joined it: all three are named in the brief's non-negotiable state
list and all three had new visuals nobody had looked at). Regenerate it after a change with:

```bash
~/flow-widget-support/emu.sh start          # boots the flow AVD headless
~/flow-widget-support/widget-shots.sh       # writes <state>-<size>-<theme>.png
```

Then put the two galleries side by side rather than flipping between folders:

```bash
~/flow-widget-support/collage.py \
    ~/flow-widget-support/gallery-redesign-r1 \
    ~/flow-widget-support/gallery-round2
```

Compare against the previous round's gallery rather than the pre-redesign baseline: that is
the delta the round is answerable for, and the baseline's six states cannot pair with the
current nine (the extra states report `MISSING` on the before side, which is correct).

`collage.py` writes one sheet per state (both sizes, both themes, BEFORE left / AFTER right)
plus an `overview.png` of every pair, with the labels burned into the image and a verdict
per pair — `IDENTICAL`, `DIFF n.n% PX`, `RESIZED`, `MISSING`. A state that was not meant to
change and does not say `IDENTICAL` is a finding; so is one that was meant to change and
does.

`tools/VERIFY.md` §8 and §9 describe the harness and the measurements above. It refuses to
run against anything but an emulator, and the debug build it installs is a separate
application (`com.fredhli.flowwidget.debug`), so none of it can touch a widget on the phone.
