package com.fredhli.flowwidget.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import com.fredhli.flowwidget.BuildConfig
import com.fredhli.flowwidget.ConfigActivity
import com.fredhli.flowwidget.FlowStore
import com.fredhli.flowwidget.R
import kotlinx.coroutines.runBlocking

/**
 * The app shell's settings: where a widget tap goes, where an off-origin link goes, and how
 * big the page's text is. Plus two doors — the widget's own server/token screen, and the
 * diagnostics dialog that MainActivity runs.
 *
 * A plain `android.app.Activity` with a framework layout, exactly like `ConfigActivity`.
 * There is no PreferenceFragment and no AppCompat here: the app owns three settings, the
 * whole screen is a column of radio buttons, and adding a preferences library to draw it
 * would cost more APK than the shell itself.
 *
 * Every tap is saved the moment it happens — there is no Save button, so there is no state
 * that can be lost by backing out, and no half-applied settings screen. The write is a
 * `runBlocking` on the UI thread, which is the same trade `ConfigActivity` already makes
 * for its reads: one small preferences edit, on a screen that is not animating, against a
 * lifecycle where a coroutine scope would have to outlive the activity to be worth it.
 *
 * The manifest exports this activity with the APPLICATION_PREFERENCES filter, so One UI's
 * "App info → Settings" entry and the launcher's Settings shortcut both land here.
 */
class AppSettingsActivity : Activity() {

    private lateinit var tapGroup: RadioGroup
    private lateinit var linkGroup: RadioGroup
    private lateinit var zoomGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)
        // targetSdk 36: every window is edge-to-edge and there is no opt-out, and a plain
        // framework theme pads nothing for it — the title would sit under the status bar
        // and the last button under the gesture bar. The bars (and the cutout, because
        // the manifest's cutout mode is ALWAYS) become padding on the ScrollView, not the
        // column inside it: that keeps the bar regions painted with the window background
        // while the content scrolls between them. Insets are passed through, not consumed
        // (spec §5) — nothing below needs them, but the rule is one rule.
        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        tapGroup = findViewById(R.id.tap_target_group)
        linkGroup = findViewById(R.id.link_policy_group)
        zoomGroup = findViewById(R.id.text_zoom_group)
        val openConfig: Button = findViewById(R.id.open_config)
        val openDiagnostics: Button = findViewById(R.id.open_diagnostics)
        val about: TextView = findViewById(R.id.about)

        // The percentage labels are formatted here rather than written into the layout, so
        // the numbers exist once (in ZOOM_ROWS, mirroring ShellPrefs.TEXT_ZOOM_CHOICES) and
        // a locale that formats digits differently gets its own numerals.
        for ((id, percent) in ZOOM_ROWS) {
            if (percent == ShellPrefs.TEXT_ZOOM_SYSTEM) continue // its label is static
            findViewById<RadioButton>(id).text = getString(R.string.settings_zoom_pct, percent)
        }

        // Initial state BEFORE the listeners are attached: RadioGroup.check() fires
        // onCheckedChanged, and a listener already in place would write the value it just
        // read back to disk on every open.
        val prefs = runBlocking { FlowStore.get(this@AppSettingsActivity).shellPrefs() }
        tapGroup.check(
            when (prefs.tapTarget) {
                TapTarget.APP -> R.id.tap_app
                TapTarget.BROWSER -> R.id.tap_browser
            }
        )
        linkGroup.check(
            when (prefs.linkPolicy) {
                LinkPolicy.CHROME -> R.id.link_chrome
                LinkPolicy.CUSTOM_TAB -> R.id.link_custom_tab
                LinkPolicy.DEFAULT_BROWSER -> R.id.link_default_browser
            }
        )
        // A stored zoom that is not one of the five rungs is only reachable by editing the
        // store by hand; it shows as "Follow system" and is corrected by the next tap.
        zoomGroup.check(
            ZOOM_ROWS.firstOrNull { it.second == prefs.textZoom }?.first ?: R.id.zoom_system
        )

        val save = RadioGroup.OnCheckedChangeListener { _, _ -> saveCurrent() }
        tapGroup.setOnCheckedChangeListener(save)
        linkGroup.setOnCheckedChangeListener(save)
        zoomGroup.setOnCheckedChangeListener(save)

        // The widget's own setup screen. It reads EXTRA_APPWIDGET_ID, finds
        // INVALID_APPWIDGET_ID here, and simply finishes without a result — which is
        // exactly right: opened this way it is "edit the server and token", not "configure
        // a widget being placed".
        openConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }

        // Diagnostics lives in the shell because half of what it reports (insets, the
        // visual viewport, the WebView version, the effective text zoom) only exists once
        // a page is loaded. MainActivity runs it as soon as the page is READY.
        openDiagnostics.setOnClickListener {
            startActivity(MainActivity.diagnosticsIntent(this))
        }

        // App version + the WebView package version the page actually runs on. The second
        // number is the one worth having: the shell's behaviour around insets and the IME
        // is decided by it (Insets.imeModeFor), and WebView updates itself out from under
        // the app through the Play Store. Null when no WebView provider is resolvable —
        // "?" rather than a crash, because this screen must open on a broken device too.
        val webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "?"
        about.text = getString(R.string.settings_about, BuildConfig.VERSION_NAME, webViewVersion)
    }

    /** Read the three groups and write them as one value. */
    private fun saveCurrent() {
        val prefs = ShellPrefs(
            tapTarget = when (tapGroup.checkedRadioButtonId) {
                R.id.tap_browser -> TapTarget.BROWSER
                else -> TapTarget.APP
            },
            linkPolicy = when (linkGroup.checkedRadioButtonId) {
                R.id.link_custom_tab -> LinkPolicy.CUSTOM_TAB
                R.id.link_default_browser -> LinkPolicy.DEFAULT_BROWSER
                else -> LinkPolicy.CHROME
            },
            // -1 (nothing checked) cannot happen once onCreate has run, but it maps to the
            // system default rather than throwing if it ever does.
            textZoom = ZOOM_ROWS.firstOrNull { it.first == zoomGroup.checkedRadioButtonId }
                ?.second ?: ShellPrefs.TEXT_ZOOM_SYSTEM,
        )
        runBlocking { FlowStore.get(this@AppSettingsActivity).saveShellPrefs(prefs) }
    }

    private companion object {
        /**
         * Radio id → stored percent, in the order the layout draws them. This mirrors
         * `ShellPrefs.TEXT_ZOOM_CHOICES`; it cannot be derived from it, because a view id
         * is a compile-time constant and there is no generating a `@+id` at runtime. The
         * two lists move together — adding a rung means a RadioButton, an id, and a line
         * here.
         */
        val ZOOM_ROWS = listOf(
            R.id.zoom_system to ShellPrefs.TEXT_ZOOM_SYSTEM,
            R.id.zoom_90 to 90,
            R.id.zoom_100 to 100,
            R.id.zoom_115 to 115,
            R.id.zoom_130 to 130,
        )
    }
}
