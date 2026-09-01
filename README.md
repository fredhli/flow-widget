# Flow — Samsung home-screen widget

A home-screen widget over the cockpit's Flow feed. It shows the titles of the newest
batch, greys them when they go stale, and gives you two taps: one that opens the item in
the browser, one that opens the Flow page.

There is **no app icon**. The APK contains one widget and one settings screen — nothing to
launch from the drawer. If you look for "Flow" in your app list you will not find it; look
in the widget picker.

```
this folder
├── README.md          you are here                          ← lives only here
├── DESIGN-NOTES.md    the design findings and their status  ← lives only here
├── design/            the reference photo, the brief and what the redesign
│                      round settled, the research, the mockups  ← lives only here
├── screenshots/       <name>-<date>/ — every state, size and theme from the
│                      emulator; baseline-2026-09-01 is the "before"
│                                                            ← lives only here
├── apk/
│   ├── flow-widget.apk          ← install this from the Dropbox app on the phone
│   └── BUILD-INFO.txt           when it was built, how big, its sha256
├── app/               Kotlin source, the widget and its config screen
├── tools/             the build contract: android-env.sh, the sync script, the mock
│                      server, the emulator + screenshot harness, collage.py,
│                      gen-glass-drawables.py, VERIFY.md — everything needed to
│                      rebuild this from here
├── gradle/, gradlew   the build
└── settings.gradle.kts, build.gradle.kts
```

The working copy is **not** this folder — it lives at `~/flow-widget` inside WSL, on the
fast disk, and is published here by `tools/sync-to-dropbox.sh`. Editing `app/`, `gradle/`
or the `.kts` files here does nothing; the next sync overwrites them.

**The four marked "lives only here" are the exception.** None of them has a copy in
`~/flow-widget`, and `design/` in particular cannot be recreated at all —
`design/reference-user-homescreen.jpg` is Fred's own photo of his home screen and exists
on no other machine. (`screenshots/` can be regenerated, but only from a booted emulator:
`tools/widget-shots.sh`.) The sync script both protects and excludes all four, so a publish
neither deletes nor overwrites them — but that guard lives inside `tools/sync-to-dropbox.sh`
only. Clearing this folder by hand to "re-publish it clean" throws `design/` away for good.

---

## Install on the phone (no adb, no cable)

1. **Wait for Dropbox to finish syncing** `apk/flow-widget.apk` to the phone. In the
   Dropbox app the file shows a spinner while it is still coming down.
2. Open the **Dropbox app** on the Galaxy → `proj_2026` → `flow_widget` → `apk` → tap
   **flow-widget.apk**.
3. Android will say *"For your security, your phone is not allowed to install unknown apps
   from this source"*. Tap **Settings**, turn on **Allow from this source** for Dropbox,
   then press Back. This is a one-time grant, per app, and it is Dropbox you are trusting —
   not this widget.
4. Tap **Install** → **Open** is greyed out or absent, which is correct: there is no
   launcher activity. Tap **Done**.
5. **Long-press an empty spot on the home screen** → **Widgets** → scroll to **Flow** →
   press and hold the Flow tile and drag it onto a page, or just tap it.
   - **4×2** — header plus one row, and the list scrolls to the rest. The redesign's
     padding is generous enough that a two-line headline plus the 48 dp header fills this
     bucket exactly; treat 4×2 as "the newest item, glanceable".
   - **4×3** — header plus one row and about a third of the next at the smallest 4×3 the
     launcher will give you, more as the cell gets taller (about one row per 86 dp). This
     is still the one to use if you have the space.
   - If you want more items visible than that, say so — it is a spacing decision
     (`DESIGN-NOTES.md` #2), not a setting.
   - One UI lets you resize after placing: long-press the widget → drag the handles.
6. The **config screen opens as soon as you place it**. Two fields and a slider:
   - **Base URL** — leave it at `https://dashboard.fredhli.com`.
   - **Token** — paste `DASHBOARD_TOKEN` from `proj_2026/dashboard/.env` (the line reads
     `DASHBOARD_TOKEN=…`; copy everything after the `=`, no quotes, no trailing spaces).
     This is the same secret the dashboard's API uses. It is stored on the phone only, in
     the app's private DataStore — it is not in the APK and never leaves the device except
     as an `Authorization: Bearer` header to your own dashboard.
   - **Two opacity sliders** — "Background opacity — Light" and "— Dark", each with its
     own live % readout, each controlling how solid that theme's surface is over the
     wallpaper. Both start at **74%**. Light spans **50–95%**; dark spans **30–95%** —
     you asked for dark to go properly low, and it now does: at the bottom the night
     surface is mostly wallpaper. The trade is yours to make per wallpaper: light text
     over a translucent dark surface washes out on a bright wallpaper (that is why the
     old build refused to go below 80% in dark), so if titles fade after a wallpaper
     change, drag the dark slider back up. The rows keep their own fixed fill either way,
     and nothing about the feed changes.
     - If you had set the old single slider, its value carries over as the **light**
       value; dark starts at 74%.
     - The sliders move in notches (3% apart in light, 1% in dark) rather than smoothly —
       the cost of the background being a theme-aware drawable instead of a picture
       painted at draw time, which is what makes it flip correctly when the phone
       switches to dark mode. You will not see the steps.
   Save. The widget fetches immediately.

**Updating later**: install the new APK over the old one the same way. The config and the
cached feed survive an update — you do not re-paste the token. If Android refuses with
*"App not installed"*, the debug signing key changed; uninstall first (long-press the
widget → Remove is not enough; go to Settings → Apps → Flow → Uninstall) and reinstall.

---

## What you are looking at

| You see | It means |
|---|---|
| **The wallpaper, faintly, through the widget** | By design. The surface is a soft grey → blue → grey gradient in light and a deep navy → violet one in dark, each at its own slider's opacity (74% default, see above), not a solid card. It is *not* frosted glass: a third-party widget cannot blur what is behind it — only the launcher can, which is why Bixby can and this cannot. Legibility comes from the rows' own fill in light mode, and from the surface's own density in dark, where a white row fill would push the row towards the light text rather than away from it — which is also why the dark slider is the one to raise if a bright wallpaper washes the titles. |
| **Flow … 32min ago** | The newest batch landed 32 minutes ago — computed from the server's absolute `epoch` stamp since 2026-09-01, so it is right in the phone's own timezone (the old build parsed the server's naive European-local string and ran hours off on HKT). The age sits at the right end of the header band, always as a whole unit — `32min ago`, `3h ago`, `1d ago`, `just now` — never a clock time and never a fraction. |
| **`updating…` where the age normally is** | A run is in flight, started from the Flow page on the web. The widget has no refresh button of its own (see **Taps**), so this is the only way it appears; it reverts to the age when the run lands. |
| **A small dot beside a title** | That item arrived since you last tapped the list. Tapping any item clears the dots. |
| **Grey titles** | The newest batch is more than 24 hours old. Nothing is broken; nothing is new either. The same rule greys the web page. |
| **A small offline mark** | The last fetch failed. What you are reading is the cached batch — the widget never goes blank, it just stops claiming to be current. |

**Taps**:
- an **item** → opens `dashboard.fredhli.com/#/flow/i/<id>` in the browser, with that item
  expanded. Your 90-day session cookie signs you in; the widget's token has nothing to do
  with the browser.
- the **header band** → opens the Flow page plain. The *whole* band is the target — the
  word "Flow", the empty middle, the age label, and the full 48 dp height of it. There is
  nothing else up there to hit.

**There is no refresh button, on purpose** (amended 2026-09-01). A tap trigger on a home
screen is a mis-touch magnet, and refreshing is the Flow page's job: start a run from
`dashboard.fredhli.com/#/flow` and the widget shows `updating…` on its next poll, then the
new batch. The widget only ever *reads* — it cannot POST at all, so nothing you do to it
can wake the PC.

Background fetches happen every **30 minutes** via WorkManager, plus once when the widget
is added and once when you save the config. Android may stretch that interval when the
phone is in deep doze — that is the OS, not the widget.

---

## The look, and how a change to it gets checked

`design/` holds the whole argument: `BRIEF.md` is what Fred asked for (Bixby's translucent
surface, Gmail's feed rows, more breathing room than either) plus, at the end, the decision
record of what the redesign round actually settled — the spacing table, the 74% default, the
slider's range, the accent. `RESEARCH.md` is why some of it was impossible (blur, bundled
fonts) and what replaced it. `mockups/` and `reference-user-homescreen.jpg` are the
pictures. `DESIGN-NOTES.md` next to this file tracks the individual findings and whether
they are fixed yet.

Nothing about the widget's appearance is judged by memory. The emulator harness renders all
nine states at both sizes in both themes — 36 frames, no manual widget placement — and
`collage.py` puts an old gallery beside a new one:

```bash
source ~/tools/android-env.sh
~/flow-widget-support/emu.sh start                                   # reuses a running AVD
~/flow-widget-support/widget-shots.sh ~/flow-widget-support/gallery-round2
~/flow-widget-support/collage.py ~/flow-widget-support/gallery-redesign-r1 \
                                 ~/flow-widget-support/gallery-round2
```

That writes one sheet per state (before left, after right, both sizes, both themes) and an
`overview.png` of every pair, with the state, size, theme and a per-pair verdict —
`IDENTICAL`, `DIFF n.n% PX`, `RESIZED`, `MISSING` — burned into the picture, so a sheet
still explains itself after being pasted into a chat. It needs no packages: Pillow is used
if it happens to be installed and the output is the same either way. `tools/VERIFY.md` §8b
is the full acceptance recipe a visual change has to pass.

Keep old galleries. `screenshots/baseline-2026-09-01/` is the pre-redesign "before" and
cannot be regenerated once the code moves on; new runs go beside it under their own name.

**Both rounds are already there**, so the before/after needs no rebuild to look at:

```
screenshots/redesign-r1/gallery/    the first redesign's 24 frames
screenshots/redesign-r1/collages/   6 per-state sheets + overview.png (baseline | r1)
screenshots/redesign-r1/evidence/   the opacity slider at 50% and 95%, the widget at
                                    50/74/95%, and the header's hour-magnitude ago label
screenshots/redesign-r2/gallery/    36 frames — r1's six states plus updating, empty
                                    and unreachable
screenshots/redesign-r2/collages/   per-state sheets + overview.png (r1 | r2)
screenshots/redesign-r2/evidence/   the one-line-title row that sets the 48 dp floor, and
                                    the dark opacity ladder the r1 ladder never drew
screenshots/redesign-r3/gallery/    the current 36 frames — the device-feedback round:
                                    Chinese fixture titles, 17sp header, the panel-D dark
                                    surface, no list scrollbar
screenshots/redesign-r3/collages/   per-state sheets + overview.png (r2 | r3)
screenshots/redesign-r3/evidence/   both sliders' extremes (light 50/95%, dark 30/95% —
                                    the 30% frame over Fred's bright wallpaper is the
                                    trade he accepted, in pixels), the two-slider config
                                    screen, the mid-scroll no-scrollbar frame
```

Every one of round 1's 24 pairs reads `DIFF 84–88% PX` — that redesign changed every state,
which was the point. Round 2's sheets are the smaller, deliberate deltas on top, and round
3's (the device-feedback round) differ in every frame again — Chinese titles, the bigger
header and the reworked dark surface touch everything. Where a number in this file and a
frame disagree, the frame wins.

---

## Testing against the mock server (no PC dashboard needed)

`tools/mock_server.py` (also at `~/flow-widget-support/mock_server.py` on the build box)
serves the same two endpoints with fake data and a real refresh lifecycle, so the widget
can be exercised without waiting on a Claude run.

```bash
python3 tools/mock_server.py            # port 8787, token "test-token"
python3 tools/mock_server.py --port 9000
python3 tools/mock_server.py --stale    # a 30-hour-old batch → grey titles
python3 tools/mock_server.py --flaky    # every 3rd request dropped → offline mark
```

Then in the widget's config screen, replace the base URL with the address of the machine
running it and set the token to `test-token`.

**Which address.** The mock runs inside WSL, and WSL sits behind a NAT — the phone cannot
reach `172.26.x.x` over Wi-Fi. Two addresses do work:

- **Tailscale (the easy one).** This WSL node is `fr-wsl-vpn` at **`100.102.21.29`**, and
  the Galaxy is on the same tailnet as `galaxy-z-fold8`. Use
  `http://100.102.21.29:8787` — no firewall rule, no port forwarding, works off the home
  Wi-Fi too. Check both ends are up with `tailscale status`.
- **The PC's LAN IP**, if you would rather not involve the tailnet: copy `mock_server.py`
  onto D: and run it under **Windows** Python instead of in WSL
  (`py D:\flow-mock\mock_server.py`), then use `http://<the PC's Wi-Fi IPv4>:8787` —
  `ipconfig` will show it, usually `192.168.1.x`. Windows Firewall will ask once; allow it
  on the private network.

**Cleartext.** These are `http://`, not `https://`. Android blocks cleartext by default;
the widget ships a network security config that allows it for private and tailnet
addresses only, so `http://100.102.21.29:8787` and `http://192.168.1.50:8787` work while
`http://some-public-host` still does not. `https://dashboard.fredhli.com` remains the
default and is what you put back afterwards.

`localhost` never works — on the phone that is the phone.

---

## Rebuilding

On the usual box everything is already installed. From a WSL shell:

```bash
source ~/tools/android-env.sh          # JDK 17, Android SDK 35, gradle — the whole contract
cd ~/flow-widget
./gradlew assembleRelease              # first run downloads ~1–2 GB and takes a while
~/flow-widget-support/sync-to-dropbox.sh
```

Then install the new APK from the Dropbox app as above.

`assembleRelease` is the shipped build: R8-minified (~2 MB — the app renders RemoteViews
but `glance-appwidget` brings the Compose runtime and parts of the graphics/unit libraries
with it, and R8 is what stops the unused remainder shipping) and signed with the **debug**
key, so it still installs over previous versions. `app/`'s
`proguard-rules.pro` turns obfuscation off, so nothing that looks a class up by name can
break. `assembleDebug` still works and the sync script falls back to it.

**The 164 `glass_*.xml` drawables are generated.** `app/src/main/res/drawable/` and
`drawable-night/` hold one gradient per opacity notch per theme — `glass_light_NN` (16
levels) and `glass_dark_NN` (66 levels), each family carrying a real gradient in its own
theme's directory and a fully transparent shape in the other's, so the two stacked layers
resolve against the launcher's configuration at draw time and the background follows a
system dark-mode flip instead of staying on the theme it was drawn in. They are checked
in, and `tools/gen-glass-drawables.py` is what writes them:

```bash
tools/gen-glass-drawables.py --check     # "clean" = the files match the generator
tools/gen-glass-drawables.py             # rewrite them after changing a stop colour
```

Editing one by hand works until the next run of the generator silently reverts it, so change
the script instead. `tools/VERIFY.md` §8b step 1 runs `--check` as part of acceptance.

The sync script publishes the source, the APK and `tools/` and nothing else: no `build/`,
no `.gradle/`, no `local.properties`, no signing keys. It refuses to run if the source tree
looks empty, and refuses to publish either **source or APK** containing the real
`DASHBOARD_TOKEN` — that value belongs in the config screen, never in a file.

If you want to install over a cable or Wi-Fi debugging instead:

```bash
adb connect <phone-ip>:5555
./gradlew installRelease
```

### From nothing — a fresh machine, or after a WSL reset

Nothing here needs root. The whole toolchain is user-space under `~/tools`, which is why
`tools/android-env.sh` (published beside this file) is the only environment contract.

```bash
mkdir -p ~/tools && cd ~/tools

# 1. JDK 17 — Gradle 8.14 and AGP 8.x need exactly this major version.
curl -L -o jdk.tar.gz https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse
tar xzf jdk.tar.gz && mv jdk-17* jdk-17

# 2. Android command-line tools, into the layout sdkmanager insists on.
curl -L -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
python3 -c "import zipfile;zipfile.ZipFile('cmdline.zip').extractall('.')"   # this distro has no unzip
mkdir -p android-sdk/cmdline-tools/latest && mv cmdline-tools/* android-sdk/cmdline-tools/latest/

# 3. Gradle (only needed to regenerate the wrapper; ./gradlew is enough otherwise).
curl -L -o gradle.zip https://services.gradle.org/distributions/gradle-8.14.5-bin.zip
python3 -c "import zipfile;zipfile.ZipFile('gradle.zip').extractall('.')"

# 4. The SDK packages this project compiles against.
source <this folder>/tools/android-env.sh
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

`tools/android-env.sh` then exports the four values that matter — `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_HOME` — and puts them on `PATH`. Source it
before any Gradle command; sourcing it twice is harmless.

If you would rather not use environment variables, Gradle also reads the SDK path from a
`local.properties` file in the project root (deliberately never published, because it is a
path only one machine has):

```properties
sdk.dir=/home/<you>/tools/android-sdk
```

Without one or the other, the build configures fine and then fails with *"SDK location not
found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting
the sdk.dir path…"*.

Copy the source out of this folder to somewhere on a fast disk before building — a Gradle
build in Dropbox pushes hundreds of megabytes of `build/` and `.gradle/` churn through the
sync client.

---

## When something is wrong

**The widget is blank / says "tap to configure".** The config was never saved, or the
widget was restored from a backup without it. Long-press the widget → the settings entry
in the pop-up → re-enter the URL and token.

**Everything is greyed and the age says hours.** That is the truth: no new batch. Start a
run from `dashboard.fredhli.com/#/flow` — the widget has no refresh button. If nothing
lands, the PC is asleep or the dashboard is not running.

**The offline mark will not go away.** Check `https://dashboard.fredhli.com/#/flow` in the
phone's browser. If the page loads but the widget does not, the token in the config screen
is wrong — the browser uses a cookie, the widget uses the token, and only one of them can
be broken at a time. Re-paste it from `dashboard/.env`, watching for a trailing space.

**A tap opens the browser to a sign-in page.** The 90-day session cookie expired. Sign in
once; every later tap lands on the item again.

**Install fails with "app not installed" or "package appears to be invalid".** Either the
Dropbox download had not finished (check the file size against `apk/BUILD-INFO.txt`), or
a previous version was signed with a different debug key — uninstall the old one first.
