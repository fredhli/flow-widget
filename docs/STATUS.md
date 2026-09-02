# Status — the widget and the app, as of 2026-09-02

The one place that says where things stand. `MERGE-NOTES.md` is the per-round record,
`APP-SHELL-SPEC.md` the spec, `../DESIGN-NOTES.md` the widget's design findings; this file
is the summary Fred reads before asking "what's next".

## Shipped

| Version | Date | What |
|---|---|---|
| 1.1.1 | 2026-09-01 | widget only: two-layer glass, server `epoch` ages, no refresh button |
| 1.2.0 | 2026-09-02 | widget round 3: Fold 8 cover cell (397×399 dp), 110% typography, title font, tap mode (open / expand), read marks |
| 2.0.0 | 2026-09-02 | **the app**: WebView shell around dashboard.fredhli.com, App Links, `window.Native` bridge, Files, settings, Diagnostics; same APK as the widget |
| 2.0.1 | 2026-09-02 | Fred's first phone round: widget taps always open the app; expand mode shows bodies; meta line shows topic · source; app named **Dashboard** with the site's icon; header inset |

Install: `proj_2026/flow_widget/apk/flow-widget.apk` (Dropbox), GitHub releases mirror it.
Dashboard side (unversioned, restart the Windows tasks after a change):
`/.well-known/assetlinks.json`, `IN_APP` in `core.js`, `/api/flow/widget` carrying
`body`/`topic`/`source` for its one batch.

## The widget

- **Where it is verified**: the `foldcover` AVD (1248×1972 @ 420 = the Fold 8 cover,
  475×751 dp) with `GROUND=fold8 SIZES=fold ./widget-shots.sh` — Fred's real wallpaper,
  the widget at his launcher's position, 1:1 over his screenshot. **The cover width is the
  only width the widget is debugged at** (Fred, 2026-09-02): its cell on the inner screen is
  the same width. Keep galleries to the frames a change touches, not all 54.
- **Known and accepted**: Glance cannot set line height (1.17 vs the spec's 1.35), cannot
  bundle a font, cannot blur. One UI Sans is what the phone draws; the emulator draws Roboto.
- **Next — the fonts round (2.1.0)**: leave Glance's `Text` for the rows and embed an XML
  row layout (`AndroidRemoteViews`) so the widget can carry its own fonts and line height:
  Lexend for Latin/digits (the site's UI font), a Source Han Sans / Noto Sans SC Regular +
  Medium subset (~3–4 MB) for CJK, line height 1.35, tighter Latin tracking. First a
  10-minute emulator proof that the launcher renders `@font/` from a RemoteViews layout;
  fallback is bitmap-rendered titles. Fred said yes to the direction, not yet to start.
- **Never reproduced**: the 2.0.0 tap that opened the browser on the phone. Made moot by
  2.0.1 (one path, into the app).

## The app

- **Verified so far**: on the emulator only — cover geometry, cookie seeding from the widget
  token, the widget-tap route into `MainActivity`, App Links files served on both hosts.
  **Never run on the phone before 2.0.1**; Fred's 2.0.0 screenshot was the first.
- **Waiting on Fred**: the Diagnostics dump from the phone — cover, inner, inner rotated,
  60 %, 40 %, flex, keyboard (Phase 0 of `dashboard/ANDROID-APP-PLAN.md`). It pins the
  inner screen's density (the `fold8inner` / `fold8inner60` AVDs assume the cover's 420,
  as `ANDROID-APP-PLAN.md` §4 does) and the insets the shell reads.
- **The three geometries (Fred, 2026-09-02)**: the app is adjusted and verified on
  `foldcover` (cover), `fold8inner` (open) and `fold8inner60` (the 60/40 split's window)
  before a change is called done. In dp, at the assumed 420: cover 475×751, inner
  932×704, 60 % ≈560 wide (the plan's table says 555 after the divider); the site's Fold 60/40 band in `dashboard/CLAUDE.md` was written
  for the previous Fold and is re-measured once Diagnostics arrives.
- **Not built (decided or deferred)**: biometric lock (deferred), Chrome Custom Tab as the
  default (Chrome is), a native Flow page (the WebView page is the page).
- **Next**: the geometry pass above once Diagnostics is in; then the fonts round touches the
  app only through the site (Lexend is already its UI font).

## The emulator

`~/flow-widget-support/emu.sh` boots `foldcover` by default (KVM on, ~20 s). `FLOW_AVD=`
picks `fold8inner`, `fold8inner60` or `flow`. One UI cannot be emulated — launcher, status
bar, fonts and Good Lock's 110 % are AOSP's; geometry, density and wallpaper are Fred's.
`adb screencap` is broken on the android-37.0 image; `emu.sh shot` works.
