# APP-SHELL-SPEC — the Dashboard app (Phase 1 + Phase 2)

Build spec for turning the Flow widget APK (package `com.fredhli.flowwidget`, repo
`/home/fred/flow-app-2.0`, branch `app-shell`, based on tag v1.1.1) into a WebView app that
hosts `https://dashboard.fredhli.com` (Fred) / `https://dashboard-chl.fredhli.com` (Helen).
The authority for *why* is `/mnt/d/Dropbox/proj_2026/dashboard/ANDROID-APP-PLAN.md` §4, §7,
§8, §9, §10. This document is the authority for *what*: four builders implement it in
parallel without talking to each other, so every file has exactly one owner and every
cross-file contract is spelled out here verbatim. Phase 3 (biometric) and Phase 4 are out
of scope.

Hard rules that bind every builder:

- Never launch the Android emulator, adb or any AVD. Never run Gradle (only the Integrate /
  Fix agents build). Write code, do not build. Never publish, tag, push or create releases.
- Never read from or write to `/home/fred/flow-widget`, `/home/fred/flow-widget-support`,
  `/mnt/d/Dropbox/proj_2026/flow_widget`. Never run `sync-to-dropbox.sh`.
- Dashboard repo (`/mnt/d/Dropbox/proj_2026/dashboard`): do not touch `flow.js`, the
  flow-refresh skill, or anything under `/mnt/d/Dropbox/proj_2026/flow`.
- Widget code path stays byte-identical: `FlowWidget.kt`, `GlassSurface.kt`, `Workers.kt`,
  `FeedParser.kt`, `FlowWidgetReceiver`. `FlowStore.kt` and `OpenItemActivity.kt` change
  only as §2.3 / §2.4 say, owned by settings-deeplink.
- No new dependencies beyond `androidx.webkit`, `androidx.core:core-splashscreen`,
  `androidx.activity:activity-ktx`, `androidx.browser`, `androidx.core:core-ktx`.
- Code style: heavily commented Kotlin that explains WHY (see `Workers.kt`,
  `app/build.gradle.kts`). Classic View XML (no Compose UI), like `ConfigActivity`.
- The token is a secret. It lives in DataStore and in the cookie jar; it never appears in a
  log line, an exception message, a toast, or the diagnostics text. URLs handed to
  `Files`/`Links` may carry `?k=<token>` (the dashboard's `apiURL()` appends it) — never
  log a URL either.
- Nothing can be verified on a device in this workflow. Code defensively; §9.2 lists what
  Fred checks on the phone.
- Defaults already decided: external links open in Chrome (`com.android.chrome`, falling
  back to the default browser); widget item taps open the App (Browser kept as a setting);
  app label stays "Dashboard Flow"; no biometric lock; compileSdk = targetSdk = 36.

Device facts (Galaxy Z Fold 8 non-Ultra, One UI 9 / Android 17): cover 1248x1972 px @
2.625 px/dp = 475x751 dp; inner 2448x1848 px ≈ 932x704 dp. minSdk stays 31.

---

## 1. File ownership

Package for every new Kotlin file: `com.fredhli.flowwidget.app` (directory
`app/src/main/kotlin/com/fredhli/flowwidget/app/`). Manifest entries therefore read
`.app.MainActivity`, `.app.AppSettingsActivity`, `.app.DashboardFileProvider`. Existing
classes stay in `com.fredhli.flowwidget`.

Resource rule: nobody edits `res/values/strings.xml`. Each builder adds its own
`res/values/strings_<key>.xml`; Android merges all files under `values/`.

| Owner | Files (all paths under `/home/fred/flow-app-2.0/` unless noted) |
|---|---|
| **shell-core** | `app/src/main/kotlin/com/fredhli/flowwidget/app/MainActivity.kt`, `.../app/DashboardWebView.kt`, `.../app/Bridge.kt`, `.../app/Routes.kt`, `.../app/Insets.kt`, `.../app/Diagnostics.kt`, `app/src/main/AndroidManifest.xml` (sole owner, complete text in §4), `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`, `app/src/main/res/values/colors_shell.xml`, `app/src/main/res/values-night/colors_shell.xml`, `app/src/main/res/values/strings_shell.xml`, `app/src/test/kotlin/com/fredhli/flowwidget/app/RoutesTest.kt`, `.../app/InsetsTest.kt`, `.../app/BridgeTest.kt` |
| **links-files** | `.../app/Links.kt`, `.../app/PopupCatcher.kt`, `.../app/Files.kt` (also contains `class DashboardFileProvider`), `app/src/main/res/xml/file_paths.xml`, `app/src/main/res/values/strings_links.xml`, `app/src/test/kotlin/com/fredhli/flowwidget/app/LinksTest.kt`, `.../app/FilesTest.kt` |
| **settings-deeplink** | `.../app/ShellPrefs.kt`, `app/src/main/kotlin/com/fredhli/flowwidget/FlowStore.kt` (additions only), `app/src/main/kotlin/com/fredhli/flowwidget/OpenItemActivity.kt`, `.../app/AppSettingsActivity.kt`, `app/src/main/res/layout/activity_app_settings.xml`, `app/src/main/res/values/strings_settings.xml`, `app/src/main/res/xml/shortcuts.xml`, `app/src/debug/res/xml/shortcuts.xml`, `app/src/main/res/drawable/ic_shortcut_flow.xml`, `ic_shortcut_morning.xml`, `ic_shortcut_jht.xml`, `ic_shortcut_settings.xml`, `ic_shortcut_smartbeta.xml`, `app/build.gradle.kts`, `build.gradle.kts` (root), `README.md`, `DESIGN-NOTES.md`, `app/src/test/kotlin/com/fredhli/flowwidget/app/ShellPrefsTest.kt` |
| **dashboard-side** (repo `/mnt/d/Dropbox/proj_2026/dashboard/`) | `src/dashboard/web/assetlinks.json`, `src/dashboard/server.py`, `tests/test_server.py`, `src/dashboard/web/js/core.js`, `src/dashboard/web/index.html`, `tests/test_frontend.py`, `CLAUDE.md` |

Untouched by everyone: `gradle.properties`, `gradle/wrapper/*`, `settings.gradle.kts`,
`proguard-rules.pro` (`-dontobfuscate` + `-keep class com.fredhli.flowwidget.** { *; }`
already cover every new class, including the FileProvider subclass and the WebMessageListener
callback), `res/raw/keep.xml`, `res/values/strings.xml`, `res/values/styles.xml`,
`res/values/colors.xml`, `res/xml/network_security_config.xml`, `ConfigActivity.kt`,
`FlowApi.kt`, all widget files, `app/src/debug/AndroidManifest.xml`, `app/src/debug/kotlin/**`.

Cross-owner references the manifest (shell-core) makes and the named owner MUST satisfy:

| Reference in manifest | Provided by |
|---|---|
| `.app.AppSettingsActivity` class, `@string/settings_title` | settings-deeplink |
| `@xml/shortcuts` | settings-deeplink |
| `.app.DashboardFileProvider` class, `@xml/file_paths` | links-files |
| `.app.MainActivity`, `@style/Theme.Dashboard.Splash`, `@string/app_name` (exists) | shell-core |

---

## 2. Kotlin API surface (verbatim contracts)

Signatures below are binding: names, parameter order, types, nullability, return values.
Bodies are the owner's business. "Pure" = no `android.*` import at all (plain-JVM unit tests:
`android.jar` stubs throw at runtime, so pure helpers use `java.net.URI` and strings only).

### 2.1 shell-core exposes

```kotlin
package com.fredhli.flowwidget.app

/** Pure URL/route helpers. No android.* imports. */
object Routes {
    /** The two hosts the APK is shipped for; both are "app origins" on every install. */
    val APP_HOSTS: Set<String> = setOf("dashboard.fredhli.com", "dashboard-chl.fredhli.com")
    const val DEFAULT_ROUTE = "#/flow"

    /** "https://host[:port]" lowercased scheme+host, explicit port kept only if non-default;
     *  null when unparsable or without a host (about:blank, javascript:, mailto:). */
    fun originOf(url: String?): String?

    /** originOf(baseUrl) ∪ { "https://$h" for h in APP_HOSTS }. baseUrl may be http://<private ip>. */
    fun appOrigins(baseUrl: String): Set<String>

    fun isAppOrigin(url: String?, appOrigins: Set<String>): Boolean

    /** The dashboard route of a URL: its fragment with the leading '#', only when the fragment
     *  starts with "/" ("#/flow/i/abc"). Null when there is no fragment or it does not start
     *  with '/'. Query is ignored. */
    fun routeOf(url: String?): String?

    /** Anything → a route: null/blank → DEFAULT_ROUTE; "flow" → "#/flow"; "/flow" → "#/flow";
     *  "#/flow" unchanged; "#flow" → "#/flow"; trimmed; any whitespace, quote, '<', '>' or
     *  control char inside → DEFAULT_ROUTE (routes are interpolated into JS and URLs). */
    fun normaliseRoute(route: String?): String

    /** baseUrl.trimEnd('/') + "/" + normaliseRoute(route)  → "https://dashboard.fredhli.com/#/flow". */
    fun pageUrl(baseUrl: String, route: String?): String

    /** A double-quoted JS string literal: backslash, double quote, \n, \r, U+2028/U+2029 and
     *  '<' escaped (so "</script>" can never terminate an inline script). */
    fun jsStringLiteral(s: String): String
}

/** Pure. Decides who owns the IME inset. */
object Insets {
    enum class ImeMode { WEBVIEW, NATIVE }
    /** "144.0.7559.24" → 144; null/garbage → null. */
    fun majorVersion(versionName: String?): Int?
    /** ≥ 144 → WEBVIEW (Chromium shrinks the visual viewport itself), else NATIVE. */
    fun imeModeFor(versionName: String?): ImeMode
    const val IME_IN_WEBVIEW_FROM_MAJOR = 144
}

class MainActivity : androidx.activity.ComponentActivity() {
    companion object {
        /** String extra: a dashboard route such as "#/flow/i/abc" (normalised by Routes). */
        const val EXTRA_ROUTE = "com.fredhli.flowwidget.EXTRA_ROUTE"
        /** Boolean extra: run the diagnostics dialog once the page is READY. */
        const val EXTRA_DIAGNOSTICS = "com.fredhli.flowwidget.EXTRA_DIAGNOSTICS"

        /** Explicit intent to MainActivity with EXTRA_ROUTE and FLAG_ACTIVITY_NEW_TASK.
         *  Callers: OpenItemActivity (App target), AppSettingsActivity (diagnostics). */
        fun routeIntent(context: android.content.Context, route: String?): android.content.Intent
        fun diagnosticsIntent(context: android.content.Context): android.content.Intent
    }
}

/** Pure parsing half of the bridge, testable. */
class Bridge(host: MainActivity) {
    sealed class Msg {
        data class Share(val url: String, val name: String?) : Msg()
        data class Open(val url: String) : Msg()
        data class Theme(val hex: String) : Msg()
        object Metrics : Msg()
    }
    companion object {
        const val OBJECT_NAME = "NativeBridge"
        const val UA_SUFFIX = " DashboardApp/2.0"
        /** Pure (org.json is on the test classpath). Unknown "t" or non-object → null. */
        fun parse(json: String): Msg?
        /** Pure. "#RRGGBB" / "#RGB" → relative luminance > 0.5. Unparsable → null. */
        fun isLightColor(hex: String): Boolean?
    }
}
```

### 2.2 links-files exposes

```kotlin
package com.fredhli.flowwidget.app

object Links {
    const val CHROME_PACKAGE = "com.android.chrome"

    enum class Nav {
        IN_APP,         // app origin, path not under /api/ → let the WebView navigate
        APP_DOCUMENT,   // app origin, path starts with "/api/" → Files.openOrShare(VIEW)
        EXTERNAL_HTTP,  // http(s) elsewhere → openExternal()
        INTENT_URI,     // "intent://…#Intent;…;end" → openIntentUri()
        OTHER_SCHEME,   // mailto: tel: sms: smsto: geo: market: → openOtherScheme()
        BLOCKED,        // javascript: file: content: data: blob: about: and anything else → drop
    }

    /** Pure (java.net.URI). Case-insensitive scheme/host. http on a private host that is the
     *  stored baseUrl origin is IN_APP because appOrigins contains it. */
    fun classify(url: String?, appOrigins: Set<String>): Nav

    /** Pure. True when url starts with "intent:" (case-insensitive). */
    fun isIntentUri(url: String?): Boolean

    /** Pure. The S.browser_fallback_url=… value inside "#Intent;…;end", URL-decoded, only if
     *  http(s); else null. */
    fun intentFallbackUrl(url: String): String?

    /** Dispatch on nav. IN_APP is the caller's business (loadUrl) and returns false here.
     *  Every other branch returns true (something was started, or the URL was dropped/toasted).
     *  Catches ActivityNotFoundException / SecurityException → toast R.string.links_no_handler. */
    fun leave(context: Context, url: String, policy: LinkPolicy, nav: Nav): Boolean

    /** http(s) only. Order: (1) ACTION_VIEW + FLAG_ACTIVITY_REQUIRE_NON_BROWSER (a verified
     *  native app for that link wins); (2) per policy — CHROME: ACTION_VIEW setPackage(CHROME_PACKAGE);
     *  CUSTOM_TAB: CustomTabsIntent with intent.setPackage(CHROME if installed else
     *  CustomTabsClient.getPackageName(ctx, null)); DEFAULT_BROWSER: plain ACTION_VIEW;
     *  (3) fall back to plain ACTION_VIEW when Chrome / a Custom Tabs provider is missing.
     *  All intents get FLAG_ACTIVITY_NEW_TASK. Returns false only when nothing could open it. */
    fun openExternal(context: Context, url: String, policy: LinkPolicy): Boolean

    /** Intent.parseUri(url, URI_INTENT_SCHEME), then hardened: addCategory(BROWSABLE),
     *  component = null, selector = null, flags = FLAG_ACTIVITY_NEW_TASK only, remove
     *  "browser_fallback_url" extra. If resolveActivity(...) == null (or start throws) →
     *  intentFallbackUrl(url)?.let { openExternal(ctx, it, policy) }. */
    fun openIntentUri(context: Context, url: String, policy: LinkPolicy): Boolean

    /** ACTION_VIEW on the raw scheme URL + NEW_TASK; toast on ActivityNotFoundException. */
    fun openOtherScheme(context: Context, url: String): Boolean
}

/** WebChromeClient.onCreateWindow helper. window.open()/target=_blank arrive here because the
 *  main WebView has setSupportMultipleWindows(true); Chromium does not pass the URL to
 *  onCreateWindow, so a throw-away child WebView captures it. */
class PopupCatcher(private val context: Context, private val onUrl: (String) -> Unit) {
    /** Create the child, attach a WebViewClient whose shouldOverrideUrlLoading AND onPageStarted
     *  (url != "about:blank") call onUrl(url) exactly once, hand it over via
     *  (resultMsg.obj as WebView.WebViewTransport).webView = child; resultMsg.sendToTarget();
     *  return true. Child settings: javaScriptEnabled = true (needed for popups that navigate
     *  via script), everything else default. Child is destroyed on the main looper via
     *  post { } after the capture (never inside the callback), and in destroy(). */
    fun onCreateWindow(parent: WebView, resultMsg: android.os.Message?): Boolean
    fun onCloseWindow(window: WebView?)
    fun destroy()
}

object Files {
    enum class Mode { VIEW, SHARE }
    const val MAX_BYTES: Long = 50L shl 20
    const val CACHE_SUBDIR = "downloads"

    /** Fetch url with the WebView's cookies (CookieManager.getInstance().getCookie(url) as the
     *  Cookie header — that is what scopes the session cookie to the app origin), on a
     *  background Thread, into cacheDir/downloads/<safeName>, then on the main thread:
     *  VIEW → ACTION_VIEW setDataAndType(uri, mime) + FLAG_GRANT_READ_URI_PERMISSION +
     *  FLAG_ACTIVITY_NEW_TASK, falling back to SHARE on ActivityNotFoundException;
     *  SHARE → ACTION_SEND type=mime, EXTRA_STREAM=uri, clipData=ClipData.newUri(...),
     *  FLAG_GRANT_READ_URI_PERMISSION, wrapped in Intent.createChooser(send, name) + NEW_TASK.
     *  uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file).
     *  Guards: FlowApi.assertSchemeAllowed(URL(url)) (reject blob:/data:/file: with toast
     *  R.string.files_failed); instanceFollowRedirects = false (a redirect off-origin would
     *  re-send the cookie), follow at most 3 same-origin redirects manually; > MAX_BYTES →
     *  toast R.string.files_too_big; non-2xx or IOException → toast R.string.files_failed.
     *  Shows toast R.string.files_fetching when the fetch starts. Never logs the URL. */
    fun openOrShare(context: Context, url: String, suggestedName: String?, mode: Mode)

    /** For WebView.setDownloadListener: onDownloadStart(url, ua, contentDisposition, mime, len)
     *  → openOrShare(ctx, url, fileNameFor(url, contentDisposition, mime), Mode.VIEW). */
    fun downloadListener(context: Context): android.webkit.DownloadListener

    /** Delete files in cacheDir/downloads older than 24 h. Background-safe, swallows errors. */
    fun pruneCache(context: Context)

    // Pure helpers (String / java.net.URI only):
    /** Content-Disposition filename*=UTF-8''… or filename="…", else last URL path segment (query
     *  stripped), else "download"; extension appended from mime when the name has none. */
    fun fileNameFor(url: String?, contentDisposition: String?, mimeType: String?): String
    /** By extension: pdf csv json txt md html png jpg jpeg webp xlsx docx zip; default
     *  "application/octet-stream". */
    fun mimeFor(name: String): String
    /** Strip path separators, control chars, leading dots; collapse whitespace; max 120 chars;
     *  blank → "download". */
    fun safeName(name: String): String
}

/** Subclass so the manifest names our own class (survives R8 keep rules by package). */
class DashboardFileProvider : androidx.core.content.FileProvider()
```

`res/xml/file_paths.xml` (links-files):

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="downloads" path="downloads/" />
</paths>
```

`strings_links.xml` ids: `links_no_handler` ("No app can open this link"), `files_fetching`
("Fetching…"), `files_failed` ("Couldn't fetch the file"), `files_too_big` ("File is too
large to share").

### 2.3 settings-deeplink exposes

```kotlin
package com.fredhli.flowwidget.app

/** Where an off-origin link goes. Stored as the lowercase `storageValue`. */
enum class LinkPolicy(val storageValue: String) {
    CHROME("chrome"), CUSTOM_TAB("custom_tab"), DEFAULT_BROWSER("default_browser");
    companion object {
        val DEFAULT = CHROME
        /** Pure. Unknown/null → DEFAULT. Case-insensitive, trimmed. */
        fun fromStorage(value: String?): LinkPolicy
    }
}

/** Where a widget tap goes. */
enum class TapTarget(val storageValue: String) {
    APP("app"), BROWSER("browser");
    companion object {
        val DEFAULT = APP
        fun fromStorage(value: String?): TapTarget
    }
}

/** textZoom: 0 = follow the system font scale; otherwise a percent, clamped 50..200. */
data class ShellPrefs(
    val tapTarget: TapTarget = TapTarget.DEFAULT,
    val linkPolicy: LinkPolicy = LinkPolicy.DEFAULT,
    val textZoom: Int = 0,
) {
    companion object {
        const val TEXT_ZOOM_SYSTEM = 0
        val TEXT_ZOOM_CHOICES = listOf(0, 90, 100, 115, 130)
        /** Pure. */
        fun clampZoom(v: Int): Int = if (v == 0) 0 else v.coerceIn(50, 200)
        /** Pure. Effective WebSettings.textZoom for a pref and Configuration.fontScale. */
        fun effectiveTextZoom(pref: Int, fontScale: Float): Int
    }
}
```

`FlowStore.kt` additions (in `com.fredhli.flowwidget`; the class keeps its single
`preferencesDataStore(name = "flow_widget")` — a second DataStore over the same file would
corrupt it, so new keys go on the existing store):

```kotlin
// companion object additions
val KEY_TAP_TARGET = stringPreferencesKey("tap_target")     // "app" | "browser"; absent → app
val KEY_LINK_POLICY = stringPreferencesKey("link_policy")   // "chrome" | "custom_tab" | "default_browser"; absent → chrome
val KEY_TEXT_ZOOM = intPreferencesKey("text_zoom")          // 0 = system; else 50..200
fun shellPrefsFrom(prefs: Preferences): com.fredhli.flowwidget.app.ShellPrefs

// instance additions
suspend fun shellPrefs(): com.fredhli.flowwidget.app.ShellPrefs = shellPrefsFrom(snapshot())
suspend fun saveShellPrefs(prefs: com.fredhli.flowwidget.app.ShellPrefs)
```

`OpenItemActivity` (settings-deeplink): keeps `EXTRA_URL = "com.fredhli.flowwidget.EXTRA_URL"`
and the widget-side contract (URL is `$baseUrl/#/flow` or `$baseUrl/#/flow/i/<id>`). New
behaviour: read `FlowStore.get(this).shellPrefs().tapTarget` (inside the existing
`runBlocking`); `APP` → `startActivity(MainActivity.routeIntent(this, Routes.routeOf(url)))`
(null route → `Routes.DEFAULT_ROUTE` via normalise); `BROWSER` → the existing ACTION_VIEW path.
`recordOpen` + `FlowWidget().updateAll` stay in both paths. Manifest adds
`android:taskAffinity=""` to it (shell-core writes that) so the trampoline's task never
becomes MainActivity's task.

`AppSettingsActivity` (settings-deeplink): `class AppSettingsActivity : android.app.Activity()`,
layout `R.layout.activity_app_settings`, ConfigActivity style. Contents in order: title;
"Widget taps open" RadioGroup (App / Browser); "External links open in" RadioGroup (Chrome /
Chrome Custom Tab / Default browser); "Text size" RadioGroup (Follow system / 90% / 100% /
115% / 130%); Button "Server & token…" → `Intent(this, ConfigActivity::class.java)` (it
handles INVALID_APPWIDGET_ID and finishes without a result); Button "Diagnostics…" →
`startActivity(MainActivity.diagnosticsIntent(this))`; About line
`getString(R.string.settings_about, BuildConfig.VERSION_NAME, webViewVersion)` where
`webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "?"`.
Every change is saved immediately (`runBlocking { store.saveShellPrefs(...) }` — tiny write,
same pattern as ConfigActivity's reads). View ids: `tap_target_group`, `tap_app`, `tap_browser`,
`link_policy_group`, `link_chrome`, `link_custom_tab`, `link_default_browser`,
`text_zoom_group`, `zoom_system`, `zoom_90`, `zoom_100`, `zoom_115`, `zoom_130`,
`open_config`, `open_diagnostics`, `about`.

`strings_settings.xml` ids: `settings_title` ("Dashboard app settings" — referenced by the
manifest), `settings_tap_label`, `settings_tap_app`, `settings_tap_browser`,
`settings_links_label`, `settings_links_chrome`, `settings_links_custom_tab`,
`settings_links_default_browser`, `settings_zoom_label`, `settings_zoom_system`,
`settings_zoom_pct` ("%1$d%%"), `settings_open_config`, `settings_open_diagnostics`,
`settings_about` ("Dashboard Flow %1$s · WebView %2$s"), `shortcut_flow` ("Flow"),
`shortcut_flow_long` ("Open Flow"), `shortcut_morning` ("Morning"), `shortcut_morning_long`,
`shortcut_jht` ("JHT"), `shortcut_jht_long`, `shortcut_settings` ("Settings"),
`shortcut_settings_long`, `shortcut_smartbeta` ("Smart Beta"), `shortcut_smartbeta_long`.

`res/xml/shortcuts.xml` (settings-deeplink) — five static shortcuts, in this order (launchers
show the first four; Settings is deliberately 4th so it is always visible):

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut android:shortcutId="flow" android:enabled="true"
        android:icon="@drawable/ic_shortcut_flow"
        android:shortcutShortLabel="@string/shortcut_flow"
        android:shortcutLongLabel="@string/shortcut_flow_long">
        <intent android:action="android.intent.action.VIEW"
            android:targetPackage="com.fredhli.flowwidget"
            android:targetClass="com.fredhli.flowwidget.app.MainActivity">
            <extra android:name="com.fredhli.flowwidget.EXTRA_ROUTE" android:value="#/flow" />
        </intent>
    </shortcut>
    <!-- morning → "#/morning", jht → "#/jht", both identical in shape -->
    <shortcut android:shortcutId="settings" ... android:icon="@drawable/ic_shortcut_settings">
        <intent android:action="android.intent.action.MAIN"
            android:targetPackage="com.fredhli.flowwidget"
            android:targetClass="com.fredhli.flowwidget.app.AppSettingsActivity" />
    </shortcut>
    <!-- smart-beta → "#/smart-beta" -->
</shortcuts>
```

`android:targetPackage` cannot use `${applicationId}` in a resource file, so
`app/src/debug/res/xml/shortcuts.xml` is the same file with `com.fredhli.flowwidget.debug`
(the debug build's applicationIdSuffix). Shortcut icons: 24dp vector drawables in the style of
`res/drawable/ic_progress.xml` (single `android:fillColor="#FFFFFFFF"` path; launchers tint and
mask them). Note: the launcher may relaunch the activity with CLEAR_TASK for a shortcut; both
the cold and hot route paths (§6) handle that.

### 2.4 What each builder may assume about the others

- shell-core calls, exactly: `Links.classify`, `Links.leave`, `Links.openExternal`,
  `PopupCatcher(...)`, `Files.openOrShare`, `Files.downloadListener`, `Files.pruneCache`,
  `FlowStore.get(ctx).shellPrefs()`, `FlowStore.get(ctx).data` (mapped with
  `FlowStore.shellPrefsFrom` and `FlowStore.configFrom`), `ShellPrefs.effectiveTextZoom`,
  `LinkPolicy`, `AppSettingsActivity::class.java`, `ConfigActivity::class.java`.
- links-files calls: `FlowApi.assertSchemeAllowed`, `LinkPolicy`, `R.string.links_*`,
  `R.string.files_*`. Nothing from MainActivity.
- settings-deeplink calls: `MainActivity.routeIntent`, `MainActivity.diagnosticsIntent`,
  `Routes.routeOf`, `Routes.DEFAULT_ROUTE`, `ConfigActivity`, `BuildConfig.VERSION_NAME`,
  `WebViewCompat.getCurrentWebViewPackage`.
- dashboard-side assumes the bridge contract in §3 and nothing else about the APK.

---

## 3. JS bridge contract (verbatim)

Transport: `WebViewCompat.addWebMessageListener(webView, "NativeBridge", allowedOrigins, listener)`
when `WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)`; the façade
below is installed with `WebViewCompat.addDocumentStartJavaScript(webView, FACADE_JS, allowedOrigins)`
when `DOCUMENT_START_SCRIPT` is supported, else evaluated in `onPageStarted` (best effort —
the page guards every `window.Native` use). Without `WEB_MESSAGE_LISTENER` there is no bridge
at all (no `addJavascriptInterface` fallback — it is not origin-scoped).

**Allowed origins** (`allowedOrigins: Set<String>`): `Routes.appOrigins(baseUrl)` — i.e. the
stored base URL's origin plus `https://dashboard.fredhli.com` and
`https://dashboard-chl.fredhli.com`. Nothing else, never `*`. The listener callback additionally
ignores messages when `!isMainFrame` or `sourceOrigin.toString().trimEnd('/')` is not in the set.

**User agent**: `settings.userAgentString = WebSettings.getDefaultUserAgent(ctx) + " DashboardApp/2.0"`.
The dashboard detects the app by `/\bDashboardApp\//.test(navigator.userAgent)` only.

**Façade** (`Bridge.FACADE_JS`, injected at document start on allowed origins):

```js
(function () {
  if (window.Native || !window.NativeBridge) return;
  var B = window.NativeBridge, waiting = [];
  function send(o) { try { B.postMessage(JSON.stringify(o)); } catch (e) {} }
  B.onmessage = function (ev) {
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m && m.t === "metrics") { var r = waiting.shift(); if (r) r(m); }
  };
  window.Native = {
    version: "2.0",
    share: function (url, name) { send({ t: "share", url: String(url || ""), name: String(name || "") }); },
    openExternal: function (url) { send({ t: "open", url: String(url || "") }); },
    themeColor: function (hex) { send({ t: "theme", hex: String(hex || "") }); },
    metrics: function () { return new Promise(function (res) { waiting.push(res); send({ t: "metrics" }); }); }
  };
})();
```

**Page → shell messages** (JSON strings; unknown `t` ignored):

| message | shape | shell action |
|---|---|---|
| share | `{"t":"share","url":"https://…","name":"cv.pdf"}` | if `Routes.isAppOrigin(url)`: `Files.openOrShare(activity, url, name.ifBlank{null}, Files.Mode.SHARE)`; else drop |
| open | `{"t":"open","url":"https://…"}` | http(s): `Links.openExternal(activity, url, prefs.linkPolicy)`; other: `Links.leave(...)` with its classification |
| theme | `{"t":"theme","hex":"#131C2E"}` | `Bridge.isLightColor(hex)` → `WindowInsetsControllerCompat.isAppearanceLightStatusBars` / `…NavigationBars` |
| metrics | `{"t":"metrics"}` | reply via `JavaScriptReplyProxy.postMessage(json)` |

**Shell → page reply** (only in answer to metrics):

```json
{"t":"metrics","webview":"144.0.7559.24","webviewPackage":"com.google.android.webview",
 "package":"com.fredhli.flowwidget","version":"2.0.0",
 "insets":{"top":0,"bottom":0,"left":0,"right":0},"ime":0,"imeMode":"WEBVIEW",
 "safeVarFallback":false,"fontScale":1.0,"textZoom":100,"density":2.625,
 "widthDp":475,"heightDp":751}
```
(`insets`/`ime` are the last WindowInsets seen, in px.)

**Route push, shell → page** (evaluateJavascript, hot path only):

```js
(function(u,h){try{if(window.DashboardApp&&window.DashboardApp.route(u))return;}catch(e){}location.hash=h;})(<jsStringLiteral(pageUrl)>,<jsStringLiteral(route)>)
```

**What the dashboard may assume**: the UA suffix is present on every request; on app origins
`window.Native` exists from document start (guard anyway); `Native.share(url,name)` fetches
`url` with the session cookie and opens the share sheet; `Native.openExternal(url)` opens the
browser per the user's link policy; `env(safe-area-inset-*)` reflects system bars and the
cutout (§5), and when it does not the shell writes `--safe-top/-bottom/-left/-right` as inline
styles on `<html>`; the IME shrinks the visual viewport (WebView ≥ 144) or the WebView height
(older); cookies persist across launches; the shell calls `window.DashboardApp.route(url)` and
falls back to `location.hash = …`; `window.open()` and `target=_blank` on off-origin URLs open
the external browser, on app-origin `/api/…` URLs open/share the file, on other app-origin
URLs navigate the main WebView; `<a download>` and `Content-Disposition: attachment` responses
go to the file viewer via the DownloadListener.

---

## 4. Manifest and theme XML (complete)

`app/src/main/AndroidManifest.xml` — shell-core writes exactly this (comments may be expanded):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Package visibility (API 30+). The shell asks questions the package manager will
         otherwise answer with "nothing installed": is Chrome present (Links), is there a
         Custom Tabs provider (Links), can an intent:// link resolve (Links), can anything
         view or receive a file (Files). -->
    <queries>
        <package android:name="com.android.chrome" />
        <intent>
            <action android:name="android.support.customtabs.action.CustomTabsService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="*/*" />
        </intent>
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="*/*" />
        </intent>
    </queries>

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:enableOnBackInvokedCallback="true">

        <!-- The widget: byte-identical to v1.1.1. -->
        <receiver
            android:name=".FlowWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/flow_widget_info" />
        </receiver>

        <activity
            android:name=".ConfigActivity"
            android:exported="true"
            android:label="@string/config_title"
            android:theme="@android:style/Theme.DeviceDefault.DayNight">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>

        <!-- Widget-tap trampoline. taskAffinity="" keeps its throw-away task separate from
             MainActivity's, so the app's task is never rooted in an excludeFromRecents,
             noHistory activity. -->
        <activity
            android:name=".OpenItemActivity"
            android:exported="false"
            android:theme="@android:style/Theme.NoDisplay"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity="" />

        <!-- The app. singleTask: one instance, routes arrive via onNewIntent. configChanges:
             the WebView keeps its page across fold/unfold, rotation, density, dark mode,
             font scale and keyboard changes instead of being recreated. -->
        <activity
            android:name=".app.MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:launchMode="singleTask"
            android:theme="@style/Theme.Dashboard.Splash"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode|keyboard|keyboardHidden|fontScale"
            android:resizeableActivity="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- App Links for both instances; verified against
                 https://<host>/.well-known/assetlinks.json (§7). https only. -->
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
                <data android:host="dashboard.fredhli.com" />
                <data android:host="dashboard-chl.fredhli.com" />
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity>

        <activity
            android:name=".app.AppSettingsActivity"
            android:exported="true"
            android:label="@string/settings_title"
            android:theme="@android:style/Theme.DeviceDefault.DayNight">
            <intent-filter>
                <action android:name="android.intent.action.APPLICATION_PREFERENCES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- Hands fetched files (cacheDir/downloads) to viewers and the share sheet. -->
        <provider
            android:name=".app.DashboardFileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

(Verify `@mipmap/ic_launcher_round` exists — it does: `mipmap-anydpi-v26/ic_launcher_round.xml`.)

`res/values/themes.xml` (shell-core):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- The running theme. DeviceDefault.DayNight so WebView's isLightTheme lookup (which
         drives prefers-color-scheme with algorithmic darkening off) follows the system.
         Bars transparent: the page runs edge-to-edge and pads with env(safe-area-inset-*). -->
    <style name="Theme.Dashboard" parent="@android:style/Theme.DeviceDefault.DayNight">
        <item name="android:windowActionBar">false</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowBackground">@color/shell_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
        <item name="android:windowLayoutInDisplayCutoutMode">always</item>
    </style>

    <!-- Cold-start splash (core-splashscreen). IconBackground variant: the launcher
         foreground is white arcs, invisible on a light ground without the violet disc. -->
    <style name="Theme.Dashboard.Splash" parent="Theme.SplashScreen.IconBackground">
        <item name="windowSplashScreenBackground">@color/splash_background</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="windowSplashScreenIconBackgroundColor">@color/ic_launcher_background</item>
        <item name="postSplashScreenTheme">@style/Theme.Dashboard</item>
    </style>
</resources>
```

`res/values-night/themes.xml`: `Theme.Dashboard` only, same items with
`windowLightStatusBar`/`windowLightNavigationBar` = `false`.

`res/values/colors_shell.xml`: `shell_background` `#F4F6FB`, `splash_background` `#F4F6FB`
(the dashboard's light `--bg`). `res/values-night/colors_shell.xml`: both `#0B1220` (dark
`--bg`). `ic_launcher_background` (#7C3AED) already exists in `colors.xml`.

`res/layout/activity_main.xml` (shell-core): root `FrameLayout` id `root`; child `FrameLayout`
id `web_container` match_parent (the WebView is created in code and added here, so it can be
recreated after a renderer crash); child `LinearLayout` id `error_panel` (visibility gone,
vertical, gravity center, padding 32dp, background `@color/shell_background`) containing
`TextView` id `error_title` (20sp bold, `@string/shell_error_title`), `TextView` id
`error_text` (14sp), `Button` id `error_retry` (`@string/shell_error_retry`), `Button` id
`error_settings` (`@string/shell_error_settings`).

`strings_shell.xml` ids (shell-core): `shell_error_title` ("Can't reach the dashboard"),
`shell_error_retry` ("Retry"), `shell_error_settings` ("Settings"), `shell_unconfigured_title`
("Set up the server first"), `shell_unconfigured_text` ("Enter the dashboard address and token,
then come back."), `shell_setup` ("Set up…"), `shell_ssl_error` ("The connection is not
secure."), `shell_http_error` ("The server answered %1$d."), `shell_renderer_crashed`
("The page crashed and was reloaded."), `diag_title` ("Diagnostics"), `diag_copy` ("Copy"),
`diag_again` ("Run again"), `diag_close` ("Close"), `diag_copied` ("Copied").

---

## 5. Insets policy

1. `MainActivity.onCreate`: `installSplashScreen()` before `super.onCreate`, then
   `enableEdgeToEdge()` (activity-ktx; sets `decorFitsSystemWindows=false` and transparent
   bars). Call `enableEdgeToEdge()` again in `onConfigurationChanged` (uiMode flips).
2. System bars + display cutout are NOT consumed natively. Chromium WebView turns the
   insets it receives into `env(safe-area-inset-*)`; the page already pads with
   `--safe-top/-bottom/-left/-right: env(safe-area-inset-*, 0px)` (`app.css` :root lines 79-82).
3. IME: `Insets.imeModeFor(WebViewCompat.getCurrentWebViewPackage(this)?.versionName)`.
   - `WEBVIEW` (major ≥ 144): the listener on `web_container` returns the insets unchanged;
     Chromium shrinks the visual viewport itself (interactive-widget semantics) and the page's
     reader/memo textareas stay above the keyboard.
   - `NATIVE` (older WebView): `v.setPadding(0, 0, 0, max(0, ime.bottom - systemBars.bottom))`
     and return `WindowInsetsCompat.Builder(insets).setInsets(Type.ime(), Insets.NONE).build()`
     — never `CONSUMED`, so bars/cutout still reach the WebView. Known cosmetic: with the
     keyboard up the page keeps its nav-bar `--safe-bottom` pad above the keyboard.
   The listener: `ViewCompat.setOnApplyWindowInsetsListener(webContainer) { v, insets -> … }`;
   it also records the last `systemBars() or displayCutout()` and `ime()` insets for
   Diagnostics and `pushSafeInsets`.
4. Fallback when `env()` is zero on the device: on the first READY of each page load run
   `Diagnostics.JS_ENV_PROBE` (returns `"t,b,l,r"` px of a hidden fixed div padded with
   `env(safe-area-inset-*)`). If the native top or bottom inset > 0 but the probe reports 0
   for it, set `safeVarFallback = true` and call `pushSafeInsets()` now and on every later
   insets change. `pushSafeInsets()` evaluates
   `document.documentElement.style.setProperty('--safe-top','<px/density>px')` (and bottom /
   left / right, values in CSS px = insetPx / density, integer-rounded). Inline `<html>`
   style beats the `:root` stylesheet rule, so nothing in app.css changes.
5. `windowSoftInputMode="adjustResize"` stays in the manifest (edge-to-edge means the framework
   does not resize anyway, but `adjustPan` would scroll the whole page).
6. `windowLayoutInDisplayCutoutMode=always` so the page also paints under the cover-screen
   camera cutout and pads via env().

---

## 6. Routes: cold / warm / hot

Page state machine in MainActivity: `enum class PageState { NONE, LOADING, READY, ERROR }`.
The unit of routing is a full URL (`pendingUrl`), not a route, so both instances and the
ACTION_VIEW path share one code path.

Sources of a target:
- Launcher (`ACTION_MAIN`, no extras): cold → `pageUrl(baseUrl, null)` is NOT used; load
  `baseUrl + "/"` (the page picks its own last route). Warm/hot → nothing.
- `EXTRA_ROUTE` (widget via OpenItemActivity, shortcuts, `routeIntent`):
  `pendingUrl = Routes.pageUrl(baseUrl, extra)`.
- `ACTION_VIEW` with data (App Links): if `Routes.isAppOrigin(data, appOrigins)` →
  `pendingUrl = data.toString()`; else `Links.openExternal(this, data, policy)` and, when no
  page is loaded yet, fall back to the home URL (never leave the shell on a blank screen).
- `EXTRA_DIAGNOSTICS=true` → `pendingDiagnostics = true`.

`onCreate` and `onNewIntent` both funnel into `handleIntent(intent)`; `onNewIntent` also
calls `setIntent(intent)`.

Applying `pendingUrl`:
- state `READY` and `Routes.originOf(webView.url) == Routes.originOf(pendingUrl)` (**hot**):
  `webView.evaluateJavascript(routeJs(pendingUrl))` with the snippet in §3, then clear it.
  Route-only targets (no fragment) evaluate `location.href = <pageUrl>` instead — i.e. a
  plain reload of the same origin — only if the paths differ; else nothing.
- state `NONE`/`ERROR`, or different origin (**cold**): `seedCookie(); webView.loadUrl(pendingUrl)`;
  `pendingUrl` stays set until `onPageFinished` for that origin confirms it, then clears.
- state `LOADING` (**warm**): keep `pendingUrl`; `onPageFinished(url)` applies it if
  `Routes.originOf(url) == Routes.originOf(pendingUrl)` (hot path), else `loadUrl`.

Cookie seeding (`seedCookie(baseUrl, token)`): before every `loadUrl` of an app-origin URL and
whenever the stored config changes: `CookieManager.getInstance()` → `setAcceptCookie(true)`,
`setAcceptThirdPartyCookies(webView, false)`,
`setCookie(origin, "dash_session=$token; Path=/; HttpOnly; Max-Age=7776000" + (if https: "; Secure"))`,
`flush()`. The token string is never concatenated into anything else.

Unconfigured (`FlowStore.configFrom(prefs) == null`): show `error_panel` with
`shell_unconfigured_title/text`, Retry button relabelled `shell_setup` → `ConfigActivity`;
splash released. `onResume` re-reads config and, once present, loads the home URL.

Error page (`error_panel` overlay, WebView kept underneath): shown on
`onReceivedError` (main frame only), `onReceivedHttpError` (main frame, status ≥ 500),
`onReceivedSslError` (always `handler.cancel()`, never proceed). Retry: hide panel,
`loadUrl(pendingUrl ?: lastUrl ?: homeUrl)`. Settings: `AppSettingsActivity`. Splash is released
when the panel shows.

Splash: `splash.setKeepOnScreenCondition { keepSplash }`; `keepSplash` becomes false on
`onPageCommitVisible`, on error/unconfigured, or after 3000 ms (`Handler.postDelayed`).

Renderer crash (`onRenderProcessGone`): if `detail.didCrash()` or not, always: remove the
WebView from `web_container`, `destroy()` it, create a fresh one via `DashboardWebView`, re-run
`seedCookie`, `loadUrl(lastUrl ?: homeUrl)`, toast `shell_renderer_crashed`, return `true`.

Back: `onBackPressedDispatcher.addCallback(this, callback)`; `callback.isEnabled =
webView.canGoBack()` refreshed in `doUpdateVisitedHistory` and `onPageFinished`; the callback
calls `webView.goBack()`. Never override `onBackPressed`. With the callback disabled the
system's predictive back (default at targetSdk 36) closes the app.

Navigation policy (`shouldOverrideUrlLoading(view, request)`):

```kotlin
val url = request.url.toString()
return when (val nav = Links.classify(url, appOrigins)) {
    Links.Nav.IN_APP -> false
    else -> Links.leave(this, url, prefs.linkPolicy, nav)   // always true for non-IN_APP
}
```

`PopupCatcher(this) { url -> when (Links.classify(url, appOrigins)) { IN_APP -> webView.loadUrl(url); nav -> Links.leave(this, url, prefs.linkPolicy, nav) } }`
wired to `WebChromeClient.onCreateWindow/onCloseWindow`. `webView.setDownloadListener(Files.downloadListener(this))`.

WebSettings (DashboardWebView.create): `javaScriptEnabled=true`, `domStorageEnabled=true`,
`setSupportMultipleWindows(true)`, `javaScriptCanOpenWindowsAutomatically=false`,
`builtInZoomControls=true`, `displayZoomControls=false`, `useWideViewPort=true`,
`loadWithOverviewMode=false`, `mixedContentMode=MIXED_CONTENT_NEVER_ALLOW`,
`allowFileAccess=false`, `allowContentAccess=false`, `setGeolocationEnabled(false)`,
`mediaPlaybackRequiresUserGesture=true`, `cacheMode=LOAD_DEFAULT`,
`userAgentString = default + Bridge.UA_SUFFIX`, `textZoom = ShellPrefs.effectiveTextZoom(prefs.textZoom, resources.configuration.fontScale)`
(re-applied in `onConfigurationChanged` and whenever prefs change),
`WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)` when
`WebViewFeature.ALGORITHMIC_DARKENING` is supported, `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`.
`overScrollMode = OVER_SCROLL_NEVER`. Lifecycle: `onResume/onPause` → `webView.onResume()/onPause()`
+ `resumeTimers()/pauseTimers()`, `CookieManager.flush()` in `onPause`; `onDestroy` →
`popupCatcher.destroy(); webView.destroy()`.

Prefs are observed reactively: `lifecycleScope.launch { FlowStore.get(this).data.collect { … } }`
mapping to `configFrom` + `shellPrefsFrom`; a token/baseUrl change re-seeds the cookie and, if
the origin changed, reloads the home URL; a textZoom change re-applies `textZoom`;
`linkPolicy` is read from the latest value at click time.

Diagnostics (`Diagnostics.kt`, shell-core): `const val JS_METRICS` — an IIFE returning
`JSON.stringify({...})` with `innerWidth, innerHeight, outerWidth, outerHeight, dpr,
screen{w,h,aw,ah}, vv{w,h,ot,s} from visualViewport, safe{t,b,l,r} from the computed
--safe-* vars, env{t,b,l,r} from the hidden-div probe, desk (data-desk attr), theme
(data-theme attr), dark (matchMedia prefers-color-scheme), fontPx (computed html font-size),
ua, url, native (!!window.Native), app (!!window.DashboardApp)}`; `const val JS_ENV_PROBE`
(returns `"t,b,l,r"` in px). `fun show(activity, pageJson: String, native: JSONObject)` — a
framework `AlertDialog` with a monospace, scrollable, pretty-printed text; buttons Copy
(ClipboardManager + toast `diag_copied`), Run again, Close. The native half is the same
object as the metrics reply in §3 plus `imeMode`, `safeVarFallback`, `webViewFeatures`
(which of WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT / ALGORITHMIC_DARKENING are supported),
`config.uiMode`, `orientation`. Contains no token and no URL query strings (strip `?…` from
`url` before display).

---

## 7. Dashboard-side changes (final text)

Repo `/mnt/d/Dropbox/proj_2026/dashboard`. Run `uv run pytest -q` and `node tests/test_md.mjs`
before finishing. Do not touch `flow.js`, the flow-refresh skill, or `/mnt/d/Dropbox/proj_2026/flow`.

### 7.1 `src/dashboard/web/assetlinks.json` (new)

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.fredhli.flowwidget",
    "sha256_cert_fingerprints": [
      "78:9F:E3:5F:02:40:43:2A:CF:C7:E1:71:50:1B:94:1C:29:B9:91:55:D3:58:CF:33:9C:78:AE:C2:10:16:85:D1"
    ]
  }
}]
```

(The release APK is signed with the debug key on purpose — `app/build.gradle.kts`. The
`.debug` package is deliberately not listed; debug builds do not get App Links.)

### 7.2 `src/dashboard/server.py`

Add, inside `_get()` immediately BEFORE the `/healthz` branch (i.e. before the non-`/api/`
catch-all that serves the login page/shell):

```python
if path == "/.well-known/assetlinks.json":
    return self._assetlinks()
```

and the method next to `_manifest`:

```python
_ASSETLINKS = (config.WEB / "assetlinks.json").read_bytes()

def _assetlinks(self):
    """Android App Links verification. Google's verifier fetches this with no cookies,
    follows no redirects and insists on a bare application/json content type, so it is
    served unauthenticated, before the login gate, with the plain media type rather than
    `_json()`'s `; charset=utf-8` + no-store."""
    return self._bytes(200, _ASSETLINKS, "application/json",
                       {"Cache-Control": "public, max-age=3600"})
```

(Place `_ASSETLINKS` wherever the existing module-level web assets are loaded; if `config.WEB`
is not the directory holding `index.html`, use the same base the shell HTML is read from.
`do_HEAD` already routes through `do_GET`. Both instances serve the same file — the JSON
lists the package, not the host.)

### 7.3 `tests/test_server.py`

```python
def test_assetlinks_is_public_bare_json(base):
    status, body, headers = get(base + "/.well-known/assetlinks.json")
    assert status == 200
    assert headers["Content-Type"] == "application/json"
    doc = json.loads(body)
    assert doc[0]["relation"] == ["delegate_permission/common.handle_all_urls"]
    assert doc[0]["target"]["package_name"] == "com.fredhli.flowwidget"
    fp = doc[0]["target"]["sha256_cert_fingerprints"][0]
    assert re.fullmatch(r"([0-9A-F]{2}:){31}[0-9A-F]{2}", fp)
```

Add next to `test_healthz_needs_no_token`; if `get()` sends gzip and the helper returns the
decoded body, keep using it as the other tests do.

### 7.4 `src/dashboard/web/js/core.js`

(a) Module scope, near the top-level constants:

```js
/* The Android shell (flow-app-2.0, docs/APP-SHELL-SPEC.md) appends " DashboardApp/2.0" to
   the WebView UA and injects window.Native on this origin at document start. UA is the
   detection; window.Native is guarded at every use. */
export const IN_APP = /\bDashboardApp\//.test(navigator.userAgent);
```

(b) `saveFile`: insert after the `viaLink` const, before the `if (blob && navigator.canShare)`
line (keeps every string `tests/test_frontend.py` pins; no column-0 `}`):

```js
  if (IN_APP && window.Native && /^https?:/i.test(url)) {
    window.Native.share(url, name);   // the shell fetches with the session cookie + share sheet
    return;
  }
```

(c) `syncThemeColor`: after `if (c) meta.setAttribute("content", c);` add
`if (c && IN_APP && window.Native) window.Native.themeColor(c);`.

(d) `initCore()`: right after `installLaunchHandler();` add

```js
  if (IN_APP) document.documentElement.classList.add("in-app");
  // The shell pushes routes through this (hot path) and falls back to location.hash.
  window.DashboardApp = { version: "1", route: routeLaunchTarget };
```

Do not change `routeLaunchTarget`, `installLaunchHandler`, `goHash`, `linkPolicy` (their
bodies are pinned by tests, and `_blank`+`noopener` off-origin anchors are exactly what the
shell's PopupCatcher expects).

### 7.5 `src/dashboard/web/index.html`

After the `smartbeta.css` stylesheet link and before the `<!--PROFILE-->` marker, one
`<link rel="modulepreload" href="/assets/js/<name>.js">` per file of the static import
closure, in this order: `app.js, core.js, brief.js, reader.js, md.js, katex-loader.js, flow.js,
jht.js, jht-list.js, jht-detail.js, jht-desk.js, smartbeta.js, sb-cards.js, sb-figs.js, system.js`.
Use the same href prefix the existing `<script type="module">` tag uses for `app.js`.

### 7.6 `tests/test_frontend.py`

- `test_modulepreload_covers_import_closure`: parse `index.html` for `modulepreload` hrefs;
  compute the static import closure from `app.js` by regex
  `^\s*import\b[^;]*?from\s+["']\./([\w-]+\.js)["']` over each module (transitively); assert
  set equality; assert every preloaded file exists under `WEB / "js"`.
- `test_in_app_hooks`: `IN_APP` is exported from core.js; `saveFile` body contains
  `window.Native.share(url, name)`; `initCore` body contains `window.DashboardApp = { version: "1", route: routeLaunchTarget }`.
- `test_no_new_popup_or_share_sites`: `window.open(` appears only in `jht-detail.js` and
  `jht.js` (3 sites total) and `navigator.share(` only in `core.js` — a tripwire so a future
  edit revisits the shell's PopupCatcher/Bridge assumptions.

### 7.7 `CLAUDE.md`

Extend the paragraph at lines ~382-384 ("The Android widget lives in …") with:

> Since 2026-09 the same APK is also the Dashboard app (`/home/fred/flow-app-2.0`, spec in
> `docs/APP-SHELL-SPEC.md`): a WebView shell hosting this site with the UA suffix
> `DashboardApp/2.0` and a `window.Native` bridge (`share`, `openExternal`, `themeColor`,
> `metrics`). `core.js` exports `IN_APP`, adds the `in-app` class on `<html>`, routes
> `saveFile()` through `Native.share`, and publishes `window.DashboardApp.route()` for the
> shell's deep links. `/.well-known/assetlinks.json` is served unauthenticated for App Links
> verification. Widget taps default to the app; `flow.js` is untouched.

---

## 8. Toolchain decision and artifact versions

`platforms;android-36` is installed in `~/tools/android-sdk` (verified this session;
`android-35` remains). Decision: **compileSdk = targetSdk = 36**.

`build.gradle.kts` (root, settings-deeplink):

```kotlin
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
```

Why: AGP 8.7.3 supports compileSdk ≤ 35; 36 needs AGP ≥ 8.10. AGP 8.11.1 + Kotlin 2.2.21 +
Gradle wrapper 8.14.5 + JDK 17 is inside the KGP compatibility matrix (2.2.20–2.2.21 →
Gradle ≤ 8.14, AGP ≤ 8.11.1). The Compose compiler plugin version must equal the Kotlin
version. Glance 1.1.1 (compose-runtime 1.7.0) compiles fine under Kotlin 2.2.

`app/build.gradle.kts` (settings-deeplink): `compileSdk = 36`, `targetSdk = 36`,
`versionCode = 4`, `versionName = "2.0.0"`, `buildFeatures { compose = true; buildConfig = true }`
(BuildConfig.VERSION_NAME / DEBUG are used by the shell; AGP 8+ has it off by default), and:

```kotlin
implementation("androidx.core:core-ktx:1.18.0")   // 1.19.0 needs compileSdk 37 + AGP 9.1 (integration finding)
implementation("androidx.activity:activity-ktx:1.13.0")
implementation("androidx.webkit:webkit:1.17.0")
implementation("androidx.browser:browser:1.10.0")
implementation("androidx.core:core-splashscreen:1.2.0")
```

Everything else in the file (minSdk 31, debug suffix, release minify/shrink + debug signing,
existing deps, junit + org.json test deps, JVM 17) stays. No proguard changes.

Fallbacks for the Integrate agent, in order, if the build cannot resolve/compile:
1. AGP 8.10.1 with Kotlin 2.1.0 / compose plugin 2.1.0 (AGP prints a KGP-version warning only).
2. Stay at compileSdk/targetSdk 35 with AGP 8.7.3 + Kotlin 2.1.0 and pin
   `core-ktx:1.16.0`, `activity-ktx:1.10.1`, `webkit:1.14.0`, `browser:1.8.0`,
   `core-splashscreen:1.0.1`; then the manifest keeps `enableOnBackInvokedCallback` and the
   spec's behaviour is unchanged except predictive back is opt-in. Record it in `notes`.

---

## 9. Tests

### 9.1 Unit tests (plain JVM, JUnit4, no Robolectric, backtick-named like `PrivateHostTest`)

- `RoutesTest` (shell-core): `originOf` — https default port dropped, explicit `:8443` kept,
  http private IP, uppercase host lowercased, `about:blank`/`javascript:`/`mailto:` → null,
  garbage → null; `appOrigins("https://dashboard.fredhli.com")` has exactly two entries,
  `appOrigins("http://192.168.1.50:8000")` has three; `routeOf` — `"…/#/flow/i/abc"` →
  `"#/flow/i/abc"`, `"…/"` → null, `"…/#top"` → null, query ignored; `normaliseRoute` — null,
  `""`, `"flow"`, `"/flow"`, `"#flow"`, `" #/flow "`, `"#/x y"` (→ default), `"#/a\"b"` (→
  default); `pageUrl` with and without trailing slash; `jsStringLiteral` escapes `"`, `\`,
  newline, `</script>`.
- `InsetsTest` (shell-core): `majorVersion("144.0.7559.24") == 144`, `"143.0.1" → NATIVE`,
  `"144" → WEBVIEW`, `null → NATIVE`, `"abc" → null/NATIVE`.
- `BridgeTest` (shell-core): `parse` for each of the four messages, missing `name` → null
  name, unknown `t` → null, non-JSON → null, `isLightColor("#FFFFFF") == true`,
  `"#0B1220" == false`, `"#fff"` short form, `"red"` → null.
- `LinksTest` (links-files): `classify` — same origin `/` and `/#/flow` → IN_APP, `/api/x.pdf`
  → APP_DOCUMENT, other app host → IN_APP, `https://example.com` → EXTERNAL_HTTP,
  `http://example.com` → EXTERNAL_HTTP, `intent://…` → INTENT_URI, `mailto:`/`tel:` →
  OTHER_SCHEME, `javascript:`/`file:`/`blob:`/`data:`/`about:blank`/null → BLOCKED,
  host case-insensitive; `intentFallbackUrl` — present/absent/non-http fallback; `isIntentUri`.
- `FilesTest` (links-files): `fileNameFor` — RFC 5987 `filename*=UTF-8''r%C3%A9sum%C3%A9.pdf`,
  quoted `filename="a b.pdf"`, bare, from URL path with query stripped, extension from mime;
  `safeName` — path separators, `..`, control chars, length cap, blank; `mimeFor` — known,
  unknown, uppercase extension.
- `ShellPrefsTest` (settings-deeplink): `LinkPolicy.fromStorage` for each value, null,
  `"CHROME "`, garbage → CHROME; `TapTarget.fromStorage` similarly; `clampZoom` 0/49/50/200/201;
  `effectiveTextZoom(0, 1.15f) == 115`, `(0, 1f) == 100`, `(130, 1.3f) == 130`, `(0, 3f) == 200`.
- dashboard: §7.3 and §7.6.

### 9.2 On-device checklist for Fred (nothing here is verifiable in this workflow)

Phase 0 measurements (Settings → Diagnostics → Copy, once on the cover screen and once
unfolded, portrait each, then once with the keyboard open in a reader memo):
1. `innerWidth/innerHeight` and `dpr` — expect ≈ 475x751 @ 2.625 on cover, ≈ 932x704 inner;
   record the real numbers in `ANDROID-APP-PLAN.md` §4.
2. `env.t/b` vs `insets.top/bottom` — if `env` is 0 while `insets` > 0, the log must show
   `safeVarFallback: true` and the page must still clear the status bar; if both are 0 on
   the cover screen, note the cutout mode.
3. `imeMode` and whether the memo textarea stays above the keyboard; `vv.h` shrinks when
   the keyboard opens (WEBVIEW mode) or `innerHeight` shrinks (NATIVE mode).
4. `desk` is `"1"` unfolded and `"0"` on the cover; fold/unfold with the app open does not
   reload the page (scroll position survives).
5. `textZoom` equals the system font scale x100 with "Follow system".

Functional:
6. Cold start from the launcher shows the splash for < 3 s, then the dashboard signed in
   (no login page). Kill the app, relaunch: still signed in.
7. Widget tap on an item opens the app at that item (hot: app already open in the
   background; cold: app killed). Change Settings → "Widget taps open" → Browser: tap opens
   Chrome as before.
8. Shortcuts (long-press icon): Flow, Morning, JHT, Settings visible; Smart Beta may be
   hidden by the launcher's four-slot limit.
9. Tap a Chrome-verified App Link (`https://dashboard.fredhli.com/#/jht` pasted in Messages)
   opens the app. `adb shell pm get-app-links com.fredhli.flowwidget` is NOT part of this
   workflow; if links open Chrome instead, check Settings → Apps → Dashboard Flow → Open by
   default, and that `https://dashboard.fredhli.com/.well-known/assetlinks.json` returns the
   JSON unauthenticated with `Content-Type: application/json`.
10. An off-origin link in Morning opens in Chrome (default). Switch to Custom Tab: opens a
    Chrome tab with the app's back arrow. Switch to Default browser: system chooser/default.
11. JHT "Save CV" opens the share sheet with the PDF; "Save to Files" works; cancelling
    does nothing. A reader "save raw" does the same. A `/api/…` link that opens in a new
    tab in the browser opens a PDF viewer in the app.
12. Back: in-page back goes to the previous route; at the root, the predictive-back gesture
    closes the app (no white flash).
13. Dark mode toggle in system settings: page flips without reload; status bar icons flip.
14. Server down (turn off Wi-Fi + data): error panel with Retry; Retry after reconnecting
    lands on the same route.
15. Font size in Android settings → text follows; pin 115% in app settings → stays.
16. Widget still paints and refreshes exactly as v1.1.1 (unchanged code path).
