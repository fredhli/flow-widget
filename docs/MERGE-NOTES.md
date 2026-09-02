# MERGE-NOTES — `app-shell` (2.0.0 Dashboard app) meets the widget's round 3

Written for the human who merges this branch with the round-3 widget work. Round 3 lives in
`/home/fred/flow-widget` and was **never read** by the agents that built this branch (a hard
rule of the workflow), so everything below is derived from this branch's own diff plus the
list of files round 3 is known to touch:

> `FlowWidget.kt`, `ConfigActivity.kt`, `FlowStore.kt`, `OpenItemActivity.kt`, `FeedParser.kt`,
> `WidgetSettings.kt` (new), `strings.xml`, `activity_config.xml`, `PreviewActivity` /
> `PreviewFixtures`, tests.

Base of this branch: tag **v1.1.1** (commit `0bde9b3`). Two commits on top:

| commit | what |
|---|---|
| `c6a1847` | 2.0.0 app shell — WebView host for the dashboard (unverified on device) |
| `1d4bcc6` | 2.0.0 app shell — review fixes (13 findings) |

Nothing was pushed, tagged, released or synced to Dropbox.

---

## 1. `git diff v1.1.1 --stat`, grouped by merge risk

### 1a. New files — **zero merge risk**

Round 3 cannot conflict with any of these; take them verbatim.

```
app/src/debug/res/xml/shortcuts.xml                                  +107
app/src/main/kotlin/com/fredhli/flowwidget/app/AppSettingsActivity.kt +165
app/src/main/kotlin/com/fredhli/flowwidget/app/Bridge.kt              +228
app/src/main/kotlin/com/fredhli/flowwidget/app/DashboardWebView.kt    +212
app/src/main/kotlin/com/fredhli/flowwidget/app/Diagnostics.kt         +158
app/src/main/kotlin/com/fredhli/flowwidget/app/Files.kt               +426
app/src/main/kotlin/com/fredhli/flowwidget/app/Insets.kt               +35
app/src/main/kotlin/com/fredhli/flowwidget/app/Links.kt               +485
app/src/main/kotlin/com/fredhli/flowwidget/app/MainActivity.kt       +1039
app/src/main/kotlin/com/fredhli/flowwidget/app/PopupCatcher.kt        +164
app/src/main/kotlin/com/fredhli/flowwidget/app/Routes.kt              +170
app/src/main/kotlin/com/fredhli/flowwidget/app/ShellPrefs.kt          +109
app/src/main/res/drawable/ic_shortcut_{flow,jht,morning,settings,smartbeta}.xml  +79
app/src/main/res/layout/activity_app_settings.xml                    +200
app/src/main/res/layout/activity_main.xml                             +63
app/src/main/res/values/colors_shell.xml                               +8
app/src/main/res/values-night/colors_shell.xml                         +6
app/src/main/res/values/themes.xml                                    +29
app/src/main/res/values-night/themes.xml                              +16
app/src/main/res/values/strings_links.xml                              +9
app/src/main/res/values/strings_settings.xml                          +50
app/src/main/res/values/strings_shell.xml                             +21
app/src/main/res/xml/file_paths.xml                                    +4
app/src/main/res/xml/shortcuts.xml                                   +100
app/src/test/kotlin/com/fredhli/flowwidget/app/BridgeTest.kt           +96
app/src/test/kotlin/com/fredhli/flowwidget/app/FilesTest.kt           +162
app/src/test/kotlin/com/fredhli/flowwidget/app/InsetsTest.kt           +37
app/src/test/kotlin/com/fredhli/flowwidget/app/LinksTest.kt           +177
app/src/test/kotlin/com/fredhli/flowwidget/app/RoutesTest.kt          +195
app/src/test/kotlin/com/fredhli/flowwidget/app/ShellPrefsTest.kt       +96
docs/APP-SHELL-SPEC.md                                              +1161
```

Two things worth knowing about the new files even though they cannot conflict textually:

* **`values/themes.xml` and `values-night/themes.xml` are new files, not edits.** v1.1.1 had no
  `themes.xml`. If round 3 introduced one, this is a *file-level* conflict (both sides add the
  same path) — merge the two by hand; the shell needs `Theme.Dashboard.Splash` intact.
* **Every new shell string lives in `strings_links.xml` / `strings_settings.xml` /
  `strings_shell.xml`.** `strings.xml` — the file round 3 edits — is **untouched on this
  branch**, deliberately, so the strings merge is a non-event. Check only for a duplicate
  *name* across files (aapt fails loudly on that, it will not merge silently wrong).

### 1b. Modified files — **merge risk**, hunks quoted in §1c

| file | ± | round 3 touches it? | risk |
|---|---|---|---|
| `app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt` | +58 / −16 | **yes** | **HIGH** — the whole `onCreate` body was rewritten |
| `app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt` | +59 / −1 | **yes** | MEDIUM — purely additive, but round 3 adds keys in the same companion |
| `app/src/main/kotlin/com/fredhli/flowwidget/ConfigActivity.kt` | +14 / −0 | **yes** | MEDIUM — one insets block after `setContentView` |
| `app/src/main/res/layout/activity_config.xml` | +6 / −0 | **yes** | MEDIUM — adds `android:id` on the root |
| `app/src/main/AndroidManifest.xml` | +98 / −7 | maybe (PreviewActivity is in the **debug** manifest) | MEDIUM |
| `app/build.gradle.kts` | +56 / −4 | likely (versionCode) | MEDIUM |
| `build.gradle.kts` (root) | +26 / −4 | unlikely | LOW |
| `README.md` | +101 / −19 | likely | LOW (prose) |
| `DESIGN-NOTES.md` | +27 / −4 | likely | LOW (prose) |

**`app/proguard-rules.pro` is NOT modified on this branch.** It was checked: the blanket
`-keep class com.fredhli.flowwidget.**` already covers `MainActivity`, `AppSettingsActivity`,
`DashboardFileProvider` and the rest, and R8's `usage.txt` for the release build shows only
trivial static `<clinit>`s pruned. Whatever round 3 does to it, take round 3's version.

### 1c. Files the widget owns outright — **take round 3, no thought required**

`FlowWidget.kt`, `GlassSurface.kt`, `Workers.kt`, `FeedParser.kt`, `FlowWidgetReceiver.kt` and
every `res/drawable/glass_*.xml` are **byte-identical to v1.1.1** on this branch. That was a
hard requirement of the build. Git will fast-forward them to round 3's content with no
conflict; if it reports one on any of these five files, something is wrong with the merge
setup, not with the code.

---

## 2. The exact hunks in the modified shared files

Everything below is `git diff v1.1.1..app-shell -- <file>`, verbatim, so a 3-way merge is
mechanical. Notes precede each file.


### 2.1 `OpenItemActivity.kt` — **HIGH risk**

The pre-2.0 body (`ACTION_VIEW` + `NEW_TASK` → browser) is gone, replaced by a
`when (shellPrefs.tapTarget)` with an APP branch (`MainActivity.routeIntent`) and a BROWSER
branch (`Links.openInBrowser`). Both branches then run round 3's bookkeeping
(`recordOpen` + `updateAll`), which is unchanged in shape.

**How to resolve:** keep this branch's `when` structure and re-apply whatever round 3 changed
*inside* it. Round 3's likely edits are (a) the shape of the URL the widget passes in
`EXTRA_URL` — the APP branch handles that through `Routes.routeOf(url)`, which normalises any
fragment-less URL to `#/flow`, so a new URL shape needs a look at `Routes.kt`; and (b) extra
bookkeeping around `recordOpen`, which should be appended after the `when`, not inside a branch.
Do **not** restore the plain `ACTION_VIEW` browser launch: 2.0.0 is the verified App Links
handler for both dashboard hosts, so a plain `ACTION_VIEW` on a dashboard URL now resolves back
into this app and the escape hatch stops being an escape hatch. That is why
`Links.openInBrowser` pins to a browser package and resolves without the URL's host.

```diff
diff --git a/app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt b/app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt
index b37e994..c966141 100755
--- a/app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt
+++ b/app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt
@@ -2,17 +2,38 @@ package com.fredhli.flowwidget
 
 import android.app.Activity
 import android.content.ActivityNotFoundException
-import android.content.Intent
-import android.net.Uri
 import android.os.Bundle
 import androidx.glance.appwidget.updateAll
+import com.fredhli.flowwidget.app.Links
+import com.fredhli.flowwidget.app.MainActivity
+import com.fredhli.flowwidget.app.Routes
+import com.fredhli.flowwidget.app.TapTarget
 import kotlinx.coroutines.runBlocking
 
 /**
- * Invisible trampoline between a widget tap and the browser. Widget taps use activity
- * PendingIntents (always allowed to launch), and this activity is where the unread-dot
- * bookkeeping happens: record the tap time, hand the deep link to the browser, repaint
- * the widgets so the dots clear, vanish.
+ * Invisible trampoline between a widget tap and wherever the tap is supposed to land.
+ * Widget taps use activity PendingIntents (always allowed to launch), and this activity is
+ * where the unread-dot bookkeeping happens: record the tap time, hand the deep link on,
+ * repaint the widgets so the dots clear, vanish.
+ *
+ * Since 2.0.0 there are two destinations, chosen by the "Widget taps open" setting:
+ * **App** (the default) starts MainActivity at the tapped item's route, and **Browser** is
+ * the pre-2.0 behaviour — the same URL in a browser — kept as the escape hatch for when
+ * the shell is the thing that is broken. Not the pre-2.0 CODE, though: a plain ACTION_VIEW
+ * on a dashboard URL now resolves to this very app, because 2.0.0 is the verified App
+ * Links handler for dashboard.fredhli.com and dashboard-chl.fredhli.com. The escape
+ * hatch would lead straight back into the broken shell. `Links.openInBrowser` pins the
+ * intent to a real browser package (the link policy's Chrome / Custom Tab / default
+ * browser, resolved without the URL's host so App Links cannot take part) and, failing
+ * every browser, offers a chooser with MainActivity struck off it.
+ *
+ * The URL the widget hands over is unchanged either way: `$baseUrl/#/flow` for the header
+ * band, `$baseUrl/#/flow/i/<id>` for an item. `Routes.routeOf` is what turns the second
+ * form back into `#/flow/i/<id>`; a URL with no usable fragment normalises to `#/flow`
+ * inside `routeIntent`, which is exactly what the header band means.
+ *
+ * The manifest gives this activity `taskAffinity=""` so its throw-away task (noHistory,
+ * excludeFromRecents) can never become the task MainActivity is rooted in.
  */
 class OpenItemActivity : Activity() {
 
@@ -20,18 +41,39 @@ class OpenItemActivity : Activity() {
         super.onCreate(savedInstanceState)
         val url = intent?.getStringExtra(EXTRA_URL)
         if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
-            try {
-                startActivity(
-                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
-                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
-                )
-            } catch (_: ActivityNotFoundException) {
-                // no browser — nothing sensible to do from a widget shell
-            }
             // Small and rare (one widget tap); runBlocking keeps the process from
-            // dying before the write and repaint land.
+            // dying before the read, the write and the repaint land. The setting is read
+            // inside the same block, off the one DataStore snapshot the write follows.
             runBlocking {
-                FlowStore.get(this@OpenItemActivity).recordOpen(System.currentTimeMillis())
+                val store = FlowStore.get(this@OpenItemActivity)
+                val shellPrefs = store.shellPrefs()
+                when (shellPrefs.tapTarget) {
+                    TapTarget.APP -> try {
+                        startActivity(
+                            MainActivity.routeIntent(
+                                this@OpenItemActivity,
+                                Routes.routeOf(url),
+                            )
+                        )
+                    } catch (_: ActivityNotFoundException) {
+                        // Our own activity, so this is only reachable if the component has
+                        // been disabled by hand. Nothing sensible to do from a widget shell.
+                    }
+
+                    TapTarget.BROWSER -> try {
+                        // openInBrowser answers false (rather than throwing) when nothing
+                        // could open it; the catch stays for the exceptions a chooser or
+                        // a Custom Tab provider can still raise on an OEM build.
+                        Links.openInBrowser(this@OpenItemActivity, url, shellPrefs.linkPolicy)
+                    } catch (_: ActivityNotFoundException) {
+                        // no browser — nothing sensible to do from a widget shell
+                    } catch (_: SecurityException) {
+                        // a browser that refuses us — same
+                    }
+                }
+                // Both paths count as "the list was read": the dots clear whether the item
+                // opened in the app or in Chrome.
+                store.recordOpen(System.currentTimeMillis())
                 FlowWidget().updateAll(this@OpenItemActivity)
             }
         }
```

### 2.2 `FlowStore.kt` — MEDIUM risk, but every hunk is additive

Four additions: three imports, a rewritten class KDoc (explaining why there must stay exactly
one `preferencesDataStore` delegate over the name `flow_widget`), two new suspend functions
(`shellPrefs()` / `saveShellPrefs()`), three new preference keys, and one new companion
function `shellPrefsFrom(prefs: Preferences)`.

**How to resolve:** union merge. Round 3's new keys and this branch's new keys can simply sit
side by side in the companion. Two real hazards:

1. **The one-store rule.** If round 3's `WidgetSettings.kt` declared its own
   `preferencesDataStore(name = "flow_widget")` delegate, that is a second actor over the same
   file and DataStore documents it as unsupported. Collapse it onto `Context.flowDataStore`.
   Read the KDoc in the hunk below before deciding anything else here.
2. **Key-name collisions.** This branch claims `tap_target`, `link_policy`, `text_zoom`. If
   round 3 used any of those names for a widget setting, rename round 3's — these three are
   already the contract `ShellPrefs`, `AppSettingsActivity` and `OpenItemActivity` read.

```diff
diff --git a/app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt b/app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt
index 978cdcd..b121c9a 100755
--- a/app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt
+++ b/app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt
@@ -6,9 +6,13 @@ import androidx.datastore.preferences.core.Preferences
 import androidx.datastore.preferences.core.booleanPreferencesKey
 import androidx.datastore.preferences.core.edit
 import androidx.datastore.preferences.core.floatPreferencesKey
+import androidx.datastore.preferences.core.intPreferencesKey
 import androidx.datastore.preferences.core.longPreferencesKey
 import androidx.datastore.preferences.core.stringPreferencesKey
 import androidx.datastore.preferences.preferencesDataStore
+import com.fredhli.flowwidget.app.LinkPolicy
+import com.fredhli.flowwidget.app.ShellPrefs
+import com.fredhli.flowwidget.app.TapTarget
 import kotlinx.coroutines.flow.Flow
 import kotlinx.coroutines.flow.first
 
@@ -20,7 +24,17 @@ data class FlowConfig(val baseUrl: String, val token: String)
 /**
  * The app's one DataStore. Holds the config (base URL + token — the token lives only
  * here, never in a log line and never in source), the last successful feed body (the
- * cache the widget paints from on reboot), and the small state flags.
+ * cache the widget paints from on reboot), the small state flags, and — since 2.0.0 —
+ * the app shell's three settings.
+ *
+ * **One store, and it has to stay one.** `preferencesDataStore(name = "flow_widget")`
+ * above is a delegate that owns a single file plus the in-process actor that serialises
+ * writes to it. A second delegate over the same name — the obvious way to give the shell
+ * "its own" store — creates a second actor over the same file, which DataStore itself
+ * documents as unsupported and which shows up as an IllegalStateException at best and a
+ * lost write or a corrupted file at worst. So the shell's keys live here, next to the
+ * widget's, and the two halves of the app share one snapshot: MainActivity reads config
+ * and shell prefs from the same `data` collection instead of racing two stores.
  */
 class FlowStore private constructor(private val appContext: Context) {
 
@@ -63,6 +77,22 @@ class FlowStore private constructor(private val appContext: Context) {
         appContext.flowDataStore.edit { it[KEY_LAST_OPEN] = nowMillis }
     }
 
+    /** The app shell's settings, defaults for anything never written. */
+    suspend fun shellPrefs(): ShellPrefs = shellPrefsFrom(snapshot())
+
+    /**
+     * Write all three shell settings at once. The settings screen saves on every tap, so
+     * this is a tiny, frequent edit — one `edit` block keeps it one file rewrite instead
+     * of three, and keeps a half-applied state off disk if the process dies mid-save.
+     */
+    suspend fun saveShellPrefs(prefs: ShellPrefs) {
+        appContext.flowDataStore.edit {
+            it[KEY_TAP_TARGET] = prefs.tapTarget.storageValue
+            it[KEY_LINK_POLICY] = prefs.linkPolicy.storageValue
+            it[KEY_TEXT_ZOOM] = ShellPrefs.clampZoom(prefs.textZoom)
+        }
+    }
+
     companion object {
         val KEY_BASE_URL = stringPreferencesKey("base_url")
         val KEY_TOKEN = stringPreferencesKey("token")
@@ -88,6 +118,22 @@ class FlowStore private constructor(private val appContext: Context) {
         /** Dark-theme glass opacity (GlassSurface.MIN_OPACITY_DARK..MAX); absent -> default. */
         val KEY_BG_OPACITY_DARK = floatPreferencesKey("bg_opacity_dark")
 
+        // The app shell's three settings (2.0.0). They are stored as the enums' own
+        // `storageValue` strings rather than as ordinals: an ordinal is a promise never to
+        // reorder the enum, and a preferences file outlives any such promise. An absent or
+        // unrecognised value falls back to the default in `fromStorage`, so an install
+        // upgraded from 1.1.1 — where none of these keys exist — reads exactly the decided
+        // defaults (widget taps open the app, links open Chrome, text follows the system).
+
+        /** "app" | "browser"; absent -> app. Read by OpenItemActivity on every widget tap. */
+        val KEY_TAP_TARGET = stringPreferencesKey("tap_target")
+
+        /** "chrome" | "custom_tab" | "default_browser"; absent -> chrome. */
+        val KEY_LINK_POLICY = stringPreferencesKey("link_policy")
+
+        /** WebView text zoom: 0 = follow the system font scale; else a percent 50..200. */
+        val KEY_TEXT_ZOOM = intPreferencesKey("text_zoom")
+
         const val DEFAULT_BASE_URL = "https://dashboard.fredhli.com"
 
         @Volatile private var instance: FlowStore? = null
@@ -103,5 +149,17 @@ class FlowStore private constructor(private val appContext: Context) {
             if (base.isNullOrEmpty() || token.isNullOrEmpty()) return null
             return FlowConfig(base, token)
         }
+
+        /**
+         * The shell's settings out of a snapshot. Takes a `Preferences` rather than
+         * reading the store itself so MainActivity can map the same `data` flow it already
+         * collects for `configFrom` — one collection, both halves of the state, no second
+         * suspend point that could observe a different moment than the config did.
+         */
+        fun shellPrefsFrom(prefs: Preferences): ShellPrefs = ShellPrefs(
+            tapTarget = TapTarget.fromStorage(prefs[KEY_TAP_TARGET]),
+            linkPolicy = LinkPolicy.fromStorage(prefs[KEY_LINK_POLICY]),
+            textZoom = ShellPrefs.clampZoom(prefs[KEY_TEXT_ZOOM] ?: ShellPrefs.TEXT_ZOOM_SYSTEM),
+        )
     }
 }
```

### 2.3 `ConfigActivity.kt` — MEDIUM risk

One concern only: at targetSdk 36 every window is edge-to-edge with no opt-out, and this
plain framework-themed activity pads nothing for it, so the title sits under the status bar and
Save under the gesture bar. The fix is a `setOnApplyWindowInsetsListener` on the root that turns
system bars + display cutout into padding, plus the two `androidx.core.view` imports.

**How to resolve:** whatever round 3 did to this activity, this listener must survive, and it
must run against whatever the new root view is. If round 3 replaced the layout, point the
listener at the new root id. Insets are **passed through, never consumed** (return `insets`), so
it composes with anything round 3 adds.

```diff
diff --git a/app/src/main/kotlin/com/fredhli/flowwidget/ConfigActivity.kt b/app/src/main/kotlin/com/fredhli/flowwidget/ConfigActivity.kt
index 4ba2f95..d6f9c18 100755
--- a/app/src/main/kotlin/com/fredhli/flowwidget/ConfigActivity.kt
+++ b/app/src/main/kotlin/com/fredhli/flowwidget/ConfigActivity.kt
@@ -9,6 +9,8 @@ import android.widget.Button
 import android.widget.EditText
 import android.widget.SeekBar
 import android.widget.TextView
+import androidx.core.view.ViewCompat
+import androidx.core.view.WindowInsetsCompat
 import androidx.glance.appwidget.updateAll
 import java.net.MalformedURLException
 import java.net.URL
@@ -48,6 +50,18 @@ class ConfigActivity : Activity() {
         ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
 
         setContentView(R.layout.activity_config)
+        // targetSdk 36 (2.0.0): every window is edge-to-edge with no opt-out, and this
+        // plain framework theme pads nothing for it — the title would sit under the
+        // status bar and Save under the gesture bar. Bars + cutout (the manifest's cutout
+        // mode is ALWAYS) become padding on the ScrollView, so the bar regions keep the
+        // window background and the column scrolls between them. Same listener as
+        // AppSettingsActivity; insets passed through, never consumed (spec §5).
+        val root = findViewById<View>(R.id.config_root)
+        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
+            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
+            v.setPadding(b.left, b.top, b.right, b.bottom)
+            insets
+        }
         baseField = findViewById(R.id.base_url)
         tokenField = findViewById(R.id.token)
         lightBar = findViewById(R.id.opacity_light)
```

### 2.4 `activity_config.xml` — MEDIUM risk

Only an `android:id="@+id/config_root"` on the existing `ScrollView`, plus the comment saying
why. If round 3 rewrote this layout wholesale, take round 3's file and **re-add the id on its
root element**, or `ConfigActivity`'s `findViewById(R.id.config_root)` returns null and the
activity crashes on launch.

```diff
diff --git a/app/src/main/res/layout/activity_config.xml b/app/src/main/res/layout/activity_config.xml
index b048dca..2cbecf8 100755
--- a/app/src/main/res/layout/activity_config.xml
+++ b/app/src/main/res/layout/activity_config.xml
@@ -1,5 +1,11 @@
 <?xml version="1.0" encoding="utf-8"?>
+<!--
+    The root has an id because ConfigActivity pads it by the system bars at runtime: at
+    targetSdk 36 every window is edge-to-edge with no opt-out, and a framework theme does
+    not pad for that (see activity_app_settings.xml, the same shape, and spec §5).
+-->
 <ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
+    android:id="@+id/config_root"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
     android:fillViewport="true">
```

### 2.5 `AndroidManifest.xml` — MEDIUM risk

Five additions and one deletion:

* a `<queries>` block (Chrome package, Custom Tabs service, BROWSABLE https VIEW, `VIEW */*`,
  `SEND */*`) — without it `resolveActivity()` returns null for apps that are plainly installed
  and every external link falls through to "no handler";
* `android:enableOnBackInvokedCallback="true"` on `<application>`;
* `taskAffinity=""` on `OpenItemActivity`, so its throw-away `noHistory` /
  `excludeFromRecents` task can never become the task `MainActivity` is rooted in;
* the `MainActivity` block (singleTask, splash theme, the full `configChanges` list, the
  autoVerify App Links filter for both hosts, the shortcuts meta-data);
* the `AppSettingsActivity` block and the `DashboardFileProvider` `<provider>`;
* the deleted comment "No launcher activity anywhere in this manifest", which is no longer true.

**How to resolve:** the widget `<receiver>` and `ConfigActivity` blocks are the only places
round 3 can plausibly collide. Note that the receiver keeps `android:label="@string/app_name"`
here even though the spec §4 snippet omits it — deliberately, to keep the widget byte-identical
to v1.1.1. Round 3's version of the receiver wins. `PreviewActivity` lives in the **debug**
manifest (`app/src/debug/`), which this branch does not modify at all, so that half is free.

```diff
diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml
index 75cafdf..05d76dc 100755
--- a/app/src/main/AndroidManifest.xml
+++ b/app/src/main/AndroidManifest.xml
@@ -5,6 +5,34 @@
          what it needs; nothing else is requested. -->
     <uses-permission android:name="android.permission.INTERNET" />
 
+    <!-- Package visibility (API 30+). The shell asks questions the package manager will
+         otherwise answer with "nothing installed": is Chrome present (Links), is there a
+         Custom Tabs provider (Links), can an intent:// link resolve (Links), can anything
+         view or receive a file (Files). Without these, resolveActivity() returns null for
+         apps that are plainly there, and every link would fall through to "no handler". -->
+    <queries>
+        <package android:name="com.android.chrome" />
+        <intent>
+            <action android:name="android.support.customtabs.action.CustomTabsService" />
+        </intent>
+        <intent>
+            <action android:name="android.intent.action.VIEW" />
+            <category android:name="android.intent.category.BROWSABLE" />
+            <data android:scheme="https" />
+        </intent>
+        <intent>
+            <action android:name="android.intent.action.VIEW" />
+            <data android:mimeType="*/*" />
+        </intent>
+        <intent>
+            <action android:name="android.intent.action.SEND" />
+            <data android:mimeType="*/*" />
+        </intent>
+    </queries>
+
+    <!-- enableOnBackInvokedCallback: predictive back for the whole app. MainActivity walks
+         the WebView history through an OnBackPressedCallback that is only enabled while
+         there is history to walk; with it disabled the system animation closes the app. -->
     <application
         android:label="@string/app_name"
         android:icon="@mipmap/ic_launcher"
@@ -12,10 +40,10 @@
         android:allowBackup="false"
         android:dataExtractionRules="@xml/data_extraction_rules"
         android:networkSecurityConfig="@xml/network_security_config"
-        android:supportsRtl="true">
+        android:supportsRtl="true"
+        android:enableOnBackInvokedCallback="true">
 
-        <!-- The widget. No launcher activity anywhere in this manifest: the APK is a
-             shell around this one receiver. -->
+        <!-- The widget: byte-identical to v1.1.1. -->
         <receiver
             android:name=".FlowWidgetReceiver"
             android:exported="true"
@@ -29,7 +57,8 @@
         </receiver>
 
         <!-- Widget configuration: base URL + token, entered once. Exported so the
-             launcher can start it for ACTION_APPWIDGET_CONFIGURE. -->
+             launcher can start it for ACTION_APPWIDGET_CONFIGURE. Also reachable from
+             the app's "Set up…" panel and from settings, without an app-widget id. -->
         <activity
             android:name=".ConfigActivity"
             android:exported="true"
@@ -40,13 +69,75 @@
             </intent-filter>
         </activity>
 
-        <!-- Invisible trampoline: records the tap time (unread-dot bookkeeping) and
-             hands the deep link to the browser. Never shown, never in recents. -->
+        <!-- Widget-tap trampoline: records the tap time (unread-dot bookkeeping) and hands
+             the deep link to the app or the browser. Never shown, never in recents.
+             taskAffinity="" keeps its throw-away task separate from MainActivity's, so the
+             app's task is never rooted in an excludeFromRecents, noHistory activity. -->
         <activity
             android:name=".OpenItemActivity"
             android:exported="false"
             android:theme="@android:style/Theme.NoDisplay"
             android:excludeFromRecents="true"
-            android:noHistory="true" />
+            android:noHistory="true"
+            android:taskAffinity="" />
+
+        <!-- The app. singleTask: one instance, routes arrive via onNewIntent. configChanges:
+             the WebView keeps its page across fold/unfold, rotation, density, dark mode,
+             font scale and keyboard changes instead of being recreated (a recreated WebView
+             is a reload, a lost scroll position and a lost half-typed memo).
+             windowSoftInputMode=adjustResize: edge-to-edge means the framework does not
+             actually resize, but adjustPan would scroll the whole page under the keyboard;
+             the shell handles the IME inset itself (Insets.kt). -->
+        <activity
+            android:name=".app.MainActivity"
+            android:exported="true"
+            android:label="@string/app_name"
+            android:launchMode="singleTask"
+            android:theme="@style/Theme.Dashboard.Splash"
+            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode|keyboard|keyboardHidden|fontScale"
+            android:resizeableActivity="true"
+            android:windowSoftInputMode="adjustResize">
+            <intent-filter>
+                <action android:name="android.intent.action.MAIN" />
+                <category android:name="android.intent.category.LAUNCHER" />
+            </intent-filter>
+            <!-- App Links for both instances; verified against
+                 https://<host>/.well-known/assetlinks.json (§7). https only. -->
+            <intent-filter android:autoVerify="true">
+                <action android:name="android.intent.action.VIEW" />
+                <category android:name="android.intent.category.DEFAULT" />
+                <category android:name="android.intent.category.BROWSABLE" />
+                <data android:scheme="https" />
+                <data android:host="dashboard.fredhli.com" />
+                <data android:host="dashboard-chl.fredhli.com" />
+            </intent-filter>
+            <meta-data
+                android:name="android.app.shortcuts"
+                android:resource="@xml/shortcuts" />
+        </activity>
+
+        <!-- Settings: tap target, link policy, text size, server & token, diagnostics.
+             APPLICATION_PREFERENCES lets the system's app-info page offer it. -->
+        <activity
+            android:name=".app.AppSettingsActivity"
+            android:exported="true"
+            android:label="@string/settings_title"
+            android:theme="@android:style/Theme.DeviceDefault.DayNight">
+            <intent-filter>
+                <action android:name="android.intent.action.APPLICATION_PREFERENCES" />
+                <category android:name="android.intent.category.DEFAULT" />
+            </intent-filter>
+        </activity>
+
+        <!-- Hands fetched files (cacheDir/downloads) to viewers and the share sheet. -->
+        <provider
+            android:name=".app.DashboardFileProvider"
+            android:authorities="${applicationId}.files"
+            android:exported="false"
+            android:grantUriPermissions="true">
+            <meta-data
+                android:name="android.support.FILE_PROVIDER_PATHS"
+                android:resource="@xml/file_paths" />
+        </provider>
     </application>
 </manifest>
```

### 2.6 `app/build.gradle.kts` — MEDIUM risk

`compileSdk`/`targetSdk` 35 → 36, `versionCode` 3 → 4, `versionName` 1.1.1 → 2.0.0,
`buildFeatures { buildConfig = true }`, and five new dependencies. The `core-ktx` pin is the one
that bites: **1.18.0, not 1.19.0** — core 1.19's AAR metadata demands compileSdk 37 + AGP 9.1 and
`checkDebugAarMetadata` fails against 36 / AGP 8.11.

**How to resolve:** `versionCode` must end up **higher than both sides** (see §3, step 5). Keep
36 and keep the five dependency lines; take round 3's changes to everything else. The header
comment carries the fallback ladder if 36 ever has to be abandoned.

```diff
diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index 3a92b05..dc64348 100755
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -1,3 +1,13 @@
+// FOR THE INTEGRATOR, if this file will not resolve or compile (docs/APP-SHELL-SPEC.md §8
+// is the authority; this is the short form):
+//   1. AGP 8.10.1 with Kotlin 2.1.0 and the compose plugin at 2.1.0 (all three in the root
+//      build file). AGP then prints a KGP-version warning and nothing else changes.
+//   2. If 36 itself is the problem, go back to compileSdk = targetSdk = 35 with AGP 8.7.3 +
+//      Kotlin 2.1.0, and pin the shell's libraries to the versions that support 35:
+//      core-ktx 1.16.0, activity-ktx 1.10.1, webkit 1.14.0, browser 1.8.0,
+//      core-splashscreen 1.0.1. The manifest keeps android:enableOnBackInvokedCallback and
+//      the shell behaves as specified, except predictive back becomes opt-in rather than
+//      the default. Record the fallback in the integration notes; nothing else moves.
 plugins {
     id("com.android.application")
     id("org.jetbrains.kotlin.android")
@@ -6,18 +16,32 @@ plugins {
 
 android {
     namespace = "com.fredhli.flowwidget"
-    compileSdk = 35
+    // 36 (Android 16) since 2.0.0. The shell wants the platform's own behaviour on Fred's
+    // One UI 9 / Android 17 phone rather than a compatibility path: predictive back on by
+    // default at targetSdk 36, mandatory edge-to-edge (which is what the insets policy in
+    // docs/APP-SHELL-SPEC.md §5 is written against), and the current WebView/insets
+    // contract. Needs AGP >= 8.10 — see the root build.gradle.kts for the whole pin set and
+    // its fallback ladder.
+    compileSdk = 36
 
     defaultConfig {
         applicationId = "com.fredhli.flowwidget"
         minSdk = 31
-        targetSdk = 35
-        versionCode = 3
-        versionName = "1.1.1"
+        targetSdk = 36
+        // 2.0.0: the same APK is now the widget AND the Dashboard app (a launcher
+        // activity, a WebView shell, App Links). versionCode has to move for the phone to
+        // accept the install over 1.1.1.
+        versionCode = 4
+        versionName = "2.0.0"
     }
 
     buildFeatures {
         compose = true
+        // The shell reads BuildConfig.VERSION_NAME (the About line in app settings and the
+        // metrics reply to the page) and BuildConfig.DEBUG (WebView contents debugging).
+        // AGP 8 generates no BuildConfig class unless asked, and the failure is a
+        // compile error in code that looks obviously correct.
+        buildConfig = true
     }
 
     compileOptions {
@@ -88,6 +112,34 @@ dependencies {
     implementation("androidx.work:work-runtime-ktx:2.10.0")
     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 
+    // The app shell (2.0.0). Five libraries, each for one thing the platform API does not
+    // give us at minSdk 31, and nothing else — the APK is sideloaded from Dropbox and every
+    // megabyte is one Fred waits for on the phone.
+    //
+    // core-ktx: ViewCompat.setOnApplyWindowInsetsListener + WindowInsetsCompat (the insets
+    //   policy, §5) and WindowInsetsControllerCompat (light status/nav bar icons flipped
+    //   from the page's theme colour). Also FileProvider, which Files.kt hands fetched
+    //   documents to a viewer through.
+    //   1.18.0, not 1.19.0: core 1.19 declares compileSdk 37 + AGP 9.1 as its floor, and
+    //   this project compiles against 36 with AGP 8.11 (checkDebugAarMetadata fails
+    //   otherwise). 1.18.0 is what activity 1.13.0 transitively resolves to anyway.
+    implementation("androidx.core:core-ktx:1.18.0")
+    // activity-ktx: ComponentActivity, enableEdgeToEdge() and the back-pressed dispatcher.
+    //   Back has to be a dispatcher callback, not an onBackPressed override, or predictive
+    //   back (default at targetSdk 36) closes the app instead of walking the hash history.
+    implementation("androidx.activity:activity-ktx:1.13.0")
+    // webkit: WebViewCompat.addWebMessageListener — the ONLY origin-scoped bridge into the
+    //   page (addJavascriptInterface is not, and is not used). Plus addDocumentStartJavaScript,
+    //   getCurrentWebViewPackage (the IME decision and the About line) and
+    //   WebSettingsCompat.setAlgorithmicDarkeningAllowed(false), because the page owns dark
+    //   mode.
+    implementation("androidx.webkit:webkit:1.17.0")
+    // browser: CustomTabsIntent, for the "Chrome Custom Tab" link policy.
+    implementation("androidx.browser:browser:1.10.0")
+    // core-splashscreen: the cold-start splash held until first paint, backported so the
+    //   behaviour is one thing across the versions the phone might run.
+    implementation("androidx.core:core-splashscreen:1.2.0")
+
     testImplementation("junit:junit:4.13.2")
     // org.json is a stub in local unit tests; this puts the real implementation on the
     // test classpath so FeedParser can be tested off-device.
```

### 2.7 root `build.gradle.kts` — LOW risk

AGP 8.7.3 → 8.11.1, Kotlin 2.1.0 → 2.2.21, compose plugin 2.1.0 → 2.2.21. AGP 8.7.3 refuses a
compileSdk above 35 outright; compileSdk 36 needs AGP ≥ 8.10. The compose plugin version **must
equal** the Kotlin version. Gradle wrapper is 8.14.5 on JDK 17, inside the compatibility box for
that pin set. Glance 1.1.1 compiles fine under Kotlin 2.2 — verified by this branch's green
build, with the widget's Kotlin unchanged.

**How to resolve:** take this branch's numbers unless round 3 has a reason of its own; then
re-verify all four (Gradle / AGP / Kotlin / compose plugin) together, never one at a time.

```diff
diff --git a/build.gradle.kts b/build.gradle.kts
index 3fde89d..a0a3013 100755
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -1,6 +1,28 @@
-// Flow widget — root build file. Version pins live here and in app/build.gradle.kts.
+// Flow widget / Dashboard app — root build file. Version pins live here and in
+// app/build.gradle.kts.
+//
+// WHY THESE THREE NUMBERS (moved up from AGP 8.7.3 / Kotlin 2.1.0 for the 2.0.0 app shell):
+//
+// * AGP 8.11.1 — the shell targets Android 16 (API 36) so it gets the platform's own
+//   predictive-back and edge-to-edge behaviour on Fred's One UI 9 phone rather than the
+//   compatibility path. AGP 8.7.3 refuses a compileSdk above 35 outright; compileSdk 36
+//   needs AGP >= 8.10. 8.11.1 is the newest AGP the rest of this pin set allows.
+// * Kotlin 2.2.21 — AGP 8.11.1 pairs with it, and the Kotlin Gradle Plugin compatibility
+//   matrix puts 2.2.20-2.2.21 at Gradle <= 8.14 and AGP <= 8.11.1. The wrapper here is
+//   Gradle 8.14.5 on JDK 17, which sits inside that box. Bumping any one of the four
+//   without checking the other three is how this build breaks.
+// * org.jetbrains.kotlin.plugin.compose 2.2.21 — since Kotlin 2.0 the Compose compiler
+//   ships with Kotlin and its plugin version MUST EQUAL the Kotlin version. A mismatch is
+//   a configuration-time error, not a subtle one.
+//
+// Glance 1.1.1 (the widget's whole UI) is built against compose-runtime 1.7.0 and compiles
+// fine under Kotlin 2.2 — the widget's Kotlin is unchanged in 2.0.0 and must stay so.
+//
+// If this set cannot resolve or compile, docs/APP-SHELL-SPEC.md §8 has the fallback ladder:
+// (1) AGP 8.10.1 + Kotlin 2.1.0 + compose plugin 2.1.0; (2) back to compileSdk/targetSdk 35
+// with AGP 8.7.3 + Kotlin 2.1.0 and the older androidx pins listed there.
 plugins {
-    id("com.android.application") version "8.7.3" apply false
-    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
-    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
+    id("com.android.application") version "8.11.1" apply false
+    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
+    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
 }
```

### 2.8 `README.md` and `DESIGN-NOTES.md` — LOW risk

Prose only (+101/−19 and +27/−4). Both gained an "app shell" section. Resolve by keeping both
sides' paragraphs; nothing here affects the build. Not quoted in full — read the diff directly:

```bash
git diff v1.1.1 -- README.md DESIGN-NOTES.md
```

---

## 3. Recommended merge procedure

Round 3 lives in a **different working copy** (`/home/fred/flow-widget`) that is not a remote of
this repo. The cheapest correct shape is to replay round 3's tree as a commit on top of v1.1.1
here, then merge — git then has a true common ancestor and does a real 3-way merge instead of
you diffing two directories by hand.

```bash
# 0. Preconditions. Round 3 must be committed / final in its own repo. Nothing below
#    pushes, tags, releases or syncs.
source ~/tools/android-env.sh
cd /home/fred/flow-app-2.0
git status --porcelain          # must be clean
git log --oneline v1.1.1..app-shell   # expect c6a1847, 1d4bcc6

# 1. A branch for round 3, rooted at the same base this branch used.
git checkout -b widget-round3 v1.1.1

# 2. Replay round 3's tree onto it. rsync, so deletions in round 3 are honoured; the
#    excludes keep build output, gradle caches and OUR git dir out of it.
rsync -a --delete \
      --exclude '.git/' --exclude 'build/' --exclude '.gradle/' \
      --exclude 'local.properties' --exclude 'docs/APP-SHELL-SPEC.md' \
      --exclude 'docs/MERGE-NOTES.md' \
      /home/fred/flow-widget/ /home/fred/flow-app-2.0/
git add -A
git status                      # sanity: only widget-side paths should appear
git commit -m "widget round 3 (imported from /home/fred/flow-widget)"

# 3. The merge itself.
git merge app-shell

# 4. Resolve, file by file, using §2 above. Expected conflict set:
#      OpenItemActivity.kt  FlowStore.kt  ConfigActivity.kt  activity_config.xml
#      AndroidManifest.xml  app/build.gradle.kts  README.md  DESIGN-NOTES.md
#    NOT expected: FlowWidget.kt, GlassSurface.kt, Workers.kt, FeedParser.kt,
#    FlowWidgetReceiver.kt, glass_*.xml — those are byte-identical to v1.1.1 on app-shell,
#    so round 3's versions come through untouched. A conflict on any of them means the
#    import in step 2 went wrong; stop and re-check.
git diff --name-only --diff-filter=U

# 5. versionCode / versionName in app/build.gradle.kts. app-shell used versionCode 4 /
#    2.0.0 assuming it shipped alone. If round 3 also took 4 (as 1.2.0), the merged APK
#    must be 5, or the phone refuses the install over whichever went out first.
#    Decide the versionName too: the merged artefact is both, so 2.0.0 is right.

# 6. Regenerate anything generated, then build and test.
tools/gen-glass-drawables.py --check          # must print "clean"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease

# 7. Only when green:
git commit                                    # completes the merge
```

Do **not** run `tools/sync-to-dropbox.sh`, tag, push, or create a release as part of the merge —
that is §5, after the phone has said yes.

Two merge-time checks that the compiler will not make for you:

* **String names.** `aapt` fails on a duplicate resource name, so a collision between round 3's
  `strings.xml` and this branch's `strings_*.xml` shows up as a build error, not silent
  breakage. Good. But a string round 3 *removed* that the shell references is also a build
  error — read it before assuming it is a merge mistake.
* **DataStore keys.** Nothing checks these. Confirm by eye that round 3 and the shell do not
  claim the same preference key name (§2.2).

---

## 4. On-device checklist for Fred

Nothing in this branch has ever run on hardware. No emulator, no adb, no device was used —
by design. Everything below is unverified.

### 4a. Phase 0 measurements (plan §4, never taken)

The plan's Phase 0 assumed a throwaway lab build. It was not built; the measurements live in
the shipped app instead, in the **Diagnostics dialog**:

> **Settings → Diagnostics** (`AppSettingsActivity` → `MainActivity.diagnosticsIntent`,
> implemented in `app/src/main/kotlin/com/fredhli/flowwidget/app/Diagnostics.kt`).
> Settings is reachable from the launcher shortcut "Settings", from the app's own menu, and
> from Android's app-info page (it registers `ACTION_APPLICATION_PREFERENCES`).
> The dialog opens once the page is READY and has a **Copy** button that puts the whole report
> on the clipboard — that is what to paste back. The url line is scrubbed of any `?k=` query.

It reports, per state: `innerWidth`, `innerHeight`, `devicePixelRatio`, the page's resolved
`env(safe-area-inset-*)` (probed from a hidden `env()`-padded div), the shell's own native bar
insets, whether the `--safe-*` fallback engaged (`safeVarFallback`), the WebView package and
version, `textZoom` vs `ShellPrefs.effectiveTextZoom`, `uiMode`, orientation, and page state.

Take a Diagnostics screenshot (or Copy → paste) in each of these seven states:

| # | state | what the plan needs from it |
|---|---|---|
| 1 | **Cover, portrait** | expected ≈ 475 × 751 CSS px @ 2.625 |
| 2 | **Inner, natural** | expected ≈ 932 × 704 — the number that decides whether the 880 breakpoint is crossed and the dashboard goes two-pane |
| 3 | **Inner, rotated portrait** | ≈ 704 × 932 — a width the visual harness has never rendered |
| 4 | **Split view, dashboard at 60 %** | ≈ 555, inside the protected 450–630 band |
| 5 | **Split view, dashboard at 40 %** | ≈ 370 — **below the harness's 400 floor**, never tested |
| 6 | **Flex mode** (half-folded, tabletop) | ≈ 932 × ~330; check the app bar + a usable list still fit under `100dvh` |
| 7 | **Keyboard open** (search box or a reader memo) | the IME inset path — see 4b |

Outputs these feed: the real width table in `ANDROID-APP-PLAN.md` §4, recalibration of
`scripts/cdp-shot.mjs` / the baseline runner / dashboard `CLAUDE.md` (today 400 / 504 / 900,
which are not this phone's numbers), and the go/no-go on `env()` inset forwarding.

### 4b. Phase 1 acceptance (plan §10)

The plan's own acceptance list, verbatim in intent:

1. **Warm relaunch from the widget shows no splash and opens the item.** Tap a widget item
   while the app is already in recents → no splash, no reload, the item opens (~100 ms window
   animation). Then force-stop and tap again → cold start, then the same route.
2. **Unfold mid-scroll keeps position.** Scroll the Flow list or a brief halfway, unfold. The
   page must re-flow, not reload; scroll position survives. Same folding back. (This is the
   `configChanges` line; if it fails, the Activity is being recreated and the fallback is
   `saveState`/`restoreState`.)
3. **Split-view drag is smooth** — grab the divider and sweep 40 % → 60 %.
4. **An external link lands in Chrome.** Open a Flow item's source article, or a JD from JHT.
   Then flip Settings → Links to Custom Tab and to Default browser and check each.
5. **Back gesture walks the hash history then leaves.** `#/flow` → open an item
   (`#/flow/i/<id>`) → back returns to the list; back at the list root plays the predictive
   back-to-home animation and closes the app.
6. **The widget still behaves exactly as before.** Dots, glass background, refresh cadence,
   the config screen. The widget code path is byte-identical to v1.1.1 on this branch, so any
   change here comes from round 3, not from the shell.

Plus the two One UI settings the app cannot set for itself:

* **Settings → Display → Continue apps on cover screen** must include Dashboard Flow, or
  folding shows the lock screen instead of the app.
* **The PWA must be uninstalled once.** Chrome's WebAPK owns the `dashboard.fredhli.com` scope
  and the two will fight over every link.
* **App Links verification:** Settings → Apps → Dashboard Flow → **Open by default** must show
  both hosts as verified. This needs `/.well-known/assetlinks.json` served unauthenticated on
  both hosts with SHA-256
  `78:9F:E3:5F:02:40:43:2A:CF:C7:E1:71:50:1B:94:1C:29:B9:91:55:D3:58:CF:33:9C:78:AE:C2:10:16:85:D1`
  (the debug key — the release APK is debug-signed on purpose).

---

## 5. Open questions and must-verify items, from every agent on this workflow

Nothing below was resolvable without hardware or without reading round 3. Each is either a
decision for the reviewer or a check for the phone.

### 5a. Decisions the reviewer should confirm (builders' open questions)

1. **PDF iframes.** Android WebView cannot render PDFs inline. The dashboard's *same-origin*
   iframe PDFs (`jht-detail` `cl-pdf`, the reader's `pdf-frame`) never pass through
   `shouldOverrideUrlLoading` — Chromium only consults it for **non-http(s)** subframes — so
   they will either show a blank frame or fire the `DownloadListener` (handled by `Files`).
   A dashboard-side, `DashboardApp`-aware fallback (open via `Native.share`, or render a link)
   may be needed. **Unresolved; needs the phone to say which of the three happens.**
2. **Manifest, widget receiver label.** The receiver keeps `android:label="@string/app_name"`
   although the spec §4 snippet omits it — chosen to keep the widget byte-identical to v1.1.1.
   Confirm which the reviewer wants (round 3's version wins on merge either way).
3. **`env()` fallback criterion is deliberately wider than the spec.** The spec says fall back
   when `env()` resolves to zero; the implementation takes over `--safe-*` whenever the probe is
   more than 1 CSS px short of the native bar inset on top or bottom. If Chromium legitimately
   reports a smaller inset on some screen, this over-pads by the difference. Diagnostics shows
   both numbers so the call can be made from data.
4. **Unopenable external links are dropped silently.** `shouldOverrideUrlLoading` returns true
   for every non-`IN_APP` navigation even when `Links.leave` reports it could not open the URL.
   Letting the WebView load it instead would carry the session cookie's origin context off-site,
   so dropping was chosen. Confirm no user-facing "could not open" toast is wanted.
5. **Config is re-read continuously, not in `onResume`.** Spec §6 says `onResume`; implemented as
   a continuous DataStore collection (which also covers the unconfigured → configured transition
   with no restart) plus a defensive home load in `onResume` when nothing has loaded.
   Behaviourally a superset — flagged as a deviation, not a bug.
6. **Diagnostics carries extra fields.** The native JSON adds `pageState`, `webViewFeatures` and
   `config{uiMode, orientation}` on top of the exact spec §3 metric keys. The `Native.metrics()`
   reply itself is exactly the §3 keys. Confirm the diagnostics-only extras are acceptable.
7. **Subframe navigations classified BLOCKED are dropped** with no panel and no toast (the main
   frame is unaffected). Confirm that is the intended UX.
8. **The error panel's Retry has no "proceed anyway".** Retry reloads
   `pendingUrl ?: lastUrl ?: home`; after an SSL failure on the app origin that means retrying
   the same URL. Intentional — there is no certificate override, ever.
9. **`PopupCatcher`'s child WebView** now overrides `onRenderProcessGone` (fixed in review), but
   popups have never been exercised. If popups turn out to be common, revisit.

### 5b. Must-verify on the phone (nothing here was ever run)

* `env(safe-area-inset-*)` on the cover screen, the inner screen and around the cutout: whether
  Chromium reports the bars at all, only the cutout, or nothing — and that the `--safe-*`
  fallback engages (Diagnostics shows `safeVarFallback` plus both sets of numbers).
* **Keyboard, both IME modes.** With WebView ≥ 144 (WEBVIEW mode) a reader memo textarea must
  stay above the keyboard with no double gap; with an older provider (NATIVE mode) the container
  padding must lift the page by exactly the keyboard height above the nav bar.
* **Splash** released on first paint, and by the 3 s cap when the server is unreachable; no white
  flash between splash and page in dark mode.
* **App Links verification** for both hosts (§4b) — the server side, `assetlinks.json`, is a
  separate change and must be live first.
* **Back** walks the hash history (`#/flow` → `#/flow/i/x` → back) and the predictive-back
  animation closes the app only at the history root.
* **Renderer crash recovery.** WebView has no `chrome://crash`; try a memory-pressure OOM.
  Expect a toast and a reload at the same route, and — if the app was backgrounded when the
  renderer died — a silent reload on the next `onResume` rather than a toast.
* **Dark-mode bar icons.** The page reports `Native.themeColor`; status/nav icons must flip for
  light system + dark page and vice versa, and a `uiMode` change while the page is up must keep
  the page (no reload).
* **Text size.** `WebSettings.textZoom` in Diagnostics matches `ShellPrefs.effectiveTextZoom`,
  and the page rescales without a reload when the system `fontScale` changes.
* **Diagnostics dialog** opens once the page is READY, Copy reaches the clipboard, the url line
  carries no `?k=`.
* **Cookie persistence across a cold start after force-stop** — the dashboard opens logged in
  with no 401 bounce; and after changing the token in ConfigActivity the next navigation carries
  the new cookie.
* **A private http base URL** (e.g. `http://100.x.x.x:8000`): `addWebMessageListener` accepts the
  origin rule (Diagnostics shows `native=true`) and the cookie is set without `Secure`.
* **Unconfigured first launch**: the set-up panel appears, ConfigActivity saves, the WebView
  appears without restarting the app.
* **Widget tap while the app is backgrounded** arrives via `onNewIntent` and pushes the route hot
  (no reload); a relaunch from Recents does **not** replay the last widget route.
* **Same-origin PDF iframes** (§5a item 1) — render, blank, or DownloadListener?
* **Fold/unfold continuity, split view, flex mode, popup capture, file share via FileProvider,
  Chrome vs Custom Tab link policy** — all of §4.

### 5c. What the integrator and fixer changed, for the record

* Platform **android-36 was already installed**; compileSdk/targetSdk stay 36, no fallback used.
* The only build break was **`androidx.core:core-ktx` 1.19.0** demanding compileSdk 37 + AGP 9.1;
  pinned to 1.18.0 (what activity 1.13.0 resolves to anyway).
* **15 review findings, 0 refuted, 13 applied in `1d4bcc6`** plus two extra fixes the reviewer
  did not raise (the ConfigActivity insets listener, and re-theming the error panel's buttons on
  a uiMode change). Highlights: `PopupCatcher` render-process death, renderer-gone lifecycle
  gating, `enableEdgeToEdge` clobbering bar appearance, Diagnostics dialog ownership across
  teardown, Custom Tab `NEW_TASK` only when there is no host Activity, `\` terminating a URL
  authority the way Chromium does, per-document `env()` re-probe, `Native.openExternal` with
  `allowSelf`, package-pinned `openInBrowser` for the widget's Browser target, and "LOADING is
  never terminal" (a download during a load is that navigation's fate, with a loop guard).
* One optional **dashboard-side** change went in with the fixes: `core.js` `initCore` now
  re-syncs `theme-color` on `resize` when `IN_APP`. Dashboard tests green:
  `uv run pytest -q` → 472 passed / 1 skipped; `node tests/test_md.mjs` → 98 assertions.
  (`proj_2026/.git` is empty, so there is no dashboard commit to merge.)

---

## 6. Release steps — **text only, none of this was executed**

Run these only after §4 passes on the phone and the merge in §3 is green.

```bash
source ~/tools/android-env.sh
cd /home/fred/flow-app-2.0            # or wherever the merged tree lives

# 1. Version. The merged APK is both the widget's round 3 and the 2.0.0 shell.
#    app/build.gradle.kts: versionName = "2.0.0", versionCode = max(both sides) + 1.
#    Anything lower than what is already on the phone will not install.

# 2. Build the shipped artefact (R8 + shrinkResources, signed with the DEBUG key on
#    purpose, so it installs over previous versions and matches the assetlinks fingerprint).
tools/gen-glass-drawables.py --check
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease

# 3. Publish source + APK to Dropbox for the phone-side install.
#    (This workflow was forbidden from running it; it lives outside this repo.)
~/flow-widget-support/sync-to-dropbox.sh

# 4. Install on the phone from the Dropbox app. Then, once only:
#    - uninstall the dashboard PWA (Chrome's WebAPK) so App Links stop fighting;
#    - Settings > Display > Continue apps on cover screen: enable Dashboard Flow;
#    - Settings > Apps > Dashboard Flow > Open by default: confirm both hosts verified.

# 5. Server side, before or with the release: /.well-known/assetlinks.json on BOTH
#    dashboard.fredhli.com and dashboard-chl.fredhli.com, unauthenticated (like /healthz),
#    fingerprint 78:9F:...:85:D1. Deploy via the usual staging rsync.

# 6. Staging repo: commit the synced tree, tag, release.
git tag -a v2.0.0 -m "2.0.0 — the dashboard as an app (WebView shell) + widget round 3"
gh release create v2.0.0 app/build/outputs/apk/release/app-release.apk \
   --title "2.0.0 — Dashboard app" --notes-file docs/RELEASE-NOTES-2.0.0.md
```

**Rollback** is installing the previous APK from the GitHub release. The shell never writes the
widget's DataStore fields, and the widget ignores the shell's three keys, so the widget survives
a downgrade in either direction — the only visible effect of rolling back is that widget taps
return to opening a browser.

---

## Merged 2026-09-02 (round 3 = v1.2.0 → app-shell)

Done as a real 3-way merge (`git merge round3`, round3 = the v1.2.0 commit). Four
conflicts, all resolved by keeping both sides except for one design decision:

- **One widget-tap setting, two front doors.** Round 3 shipped "Open links with:
  Dashboard app / Chrome" (`link_app`) on the widget config screen; the shell had
  "Widget taps open: App / Browser" (`tap_target`). Same question, so `tap_target` was
  dropped before it ever shipped: `TapTarget` now stores `"dashboard"` / `"chrome"` under
  `FlowStore.KEY_LINK_APP`, and both screens edit that one key. `TapTarget.BROWSER` goes
  through `Links.openInBrowser` with the link policy (default Chrome, same fallback to the
  default browser round 3 had). Round 3's inline ACTION_VIEW block in OpenItemActivity is
  gone — it would resolve straight back into this app via App Links.
- `versionCode` 5 / 2.0.0 (1.2.0 took 4). targetSdk stays 36.
- 198 unit tests green (129 widget + 69 shell); release + debug builds green.

## 2.0.1 — first on-device round (2026-09-02)

Fred installed 2.0.0 on the Fold and sent one screenshot and four items. What changed:

1. **A widget tap always opens the app.** On the phone an item tap landed in the browser;
   on the emulator (fresh DataStore, release APK, the same `OpenItemActivity` replay) it
   landed in `MainActivity` every time, so the cause was never reproduced — it lived in
   something the phone's upgraded store or launcher did, not in the route code. Fred's
   ruling made the diagnosis moot: the widget must never open a URL, the app *is* the
   phone's dashboard. `TapTarget` is gone (ShellPrefs, FlowStore, both settings screens,
   the widget config's "Open links with" spinner, `WidgetSettings.LINK_*`); round 3's
   `link_app` key may still sit in an old store and is simply not read. `OpenItemActivity`
   has one path: an explicit intent to `MainActivity` at `Routes.routeOf(url)`.
2. **Expand mode shows the body.** The widget slice (`/api/flow/widget`) carried no bodies —
   a dashboard rule, "what a phone polls every 30 minutes" — so every expanded row read
   "—". The slice now carries `body` for its one batch (dashboard `flow.py`, its test and
   CLAUDE.md updated; the server needs a restart to serve it). The expanded text is its own
   tap target into the app, so expand mode is not a dead end.
3. **Named "Dashboard", with the dashboard's icon** — the site's four-tile mark on its blue
   (`#2563EB`), generated from `web/icon-maskable.svg` by
   `~/flow-widget-support/gen-launcher-icon.py`; the splash follows automatically.
4. **Header inset**: "Flow" and the age label sit 16dp in from the container padding (the
   rows' corner radius, i.e. on the corner circle's centre) and 3dp lower.

Rule recorded in dashboard/CLAUDE.md: the app is the phone's front door and the browser is
the PC's, both stay, so a site UI/UX change is verified in the app too.

`versionCode 6`, `versionName 2.0.1`. Unit tests 183 (release) / 197 (debug), 0 failures.
