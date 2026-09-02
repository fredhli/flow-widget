package com.fredhli.flowwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.glance.appwidget.updateAll
import java.net.MalformedURLException
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/**
 * ACTION_APPWIDGET_CONFIGURE: base URL (prefilled) + token, entered once, kept in
 * DataStore. Save validates the pair with a real widget GET, caches the body, then
 * finishes with RESULT_OK + the widget id. If the server is unreachable, "Save anyway"
 * stores the config without the round-trip (the periodic worker will catch up).
 *
 * The token is a secret: it goes DataStore-only. No log statement in this app ever
 * receives it.
 */
class ConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var baseField: EditText
    private lateinit var tokenField: EditText
    private lateinit var lightBar: SeekBar
    private lateinit var lightValue: TextView
    private lateinit var darkBar: SeekBar
    private lateinit var darkValue: TextView
    private lateinit var errorView: TextView
    private lateinit var saveButton: Button
    private lateinit var saveAnywayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cancelled until proven saved — backing out must not add a broken widget.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContentView(R.layout.activity_config)
        // targetSdk 36 (2.0.0): every window is edge-to-edge with no opt-out, and this
        // plain framework theme pads nothing for it — the title would sit under the
        // status bar and Save under the gesture bar. Bars + cutout (the manifest's cutout
        // mode is ALWAYS) become padding on the ScrollView, so the bar regions keep the
        // window background and the column scrolls between them. Same listener as
        // AppSettingsActivity; insets passed through, never consumed (spec §5).
        val root = findViewById<View>(R.id.config_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        baseField = findViewById(R.id.base_url)
        tokenField = findViewById(R.id.token)
        lightBar = findViewById(R.id.opacity_light)
        lightValue = findViewById(R.id.opacity_value_light)
        darkBar = findViewById(R.id.opacity_dark)
        darkValue = findViewById(R.id.opacity_value_dark)
        errorView = findViewById(R.id.error)
        saveButton = findViewById(R.id.save)
        saveAnywayButton = findViewById(R.id.save_anyway)

        val prefs = runBlocking { FlowStore.get(this@ConfigActivity).snapshot() }
        baseField.setText(prefs[FlowStore.KEY_BASE_URL] ?: FlowStore.DEFAULT_BASE_URL)
        prefs[FlowStore.KEY_TOKEN]?.let { tokenField.setText(it) }

        // Two sliders since the device-feedback round: light and dark are genuinely
        // different surfaces and Fred wanted them tuned apart — dark all the way down to
        // 30% (his wallpaper, his slider; the floor is the only guard). One SeekBar notch
        // per glass level of the theme's own grid (light 16 x 3%, dark 66 x 1% — see
        // GlassSurface), set in code because android:min is unreliable across OEM skins.
        // Reading KEY_BG_OPACITY (the old single slider's key) into the LIGHT bar is the
        // whole migration: an existing value becomes light, dark starts at its default.
        bindOpacityBar(
            lightBar, lightValue,
            GlassSurface.LIGHT_LEVELS,
            GlassSurface.lightLevelFor(prefs[FlowStore.KEY_BG_OPACITY]),
        ) { GlassSurface.lightOpacityAtLevel(it) }
        bindOpacityBar(
            darkBar, darkValue,
            GlassSurface.DARK_LEVELS,
            GlassSurface.darkLevelFor(prefs[FlowStore.KEY_BG_OPACITY_DARK]),
        ) { GlassSurface.darkOpacityAtLevel(it) }

        saveButton.setOnClickListener { attempt(requireFetch = true) }
        saveAnywayButton.setOnClickListener { attempt(requireFetch = false) }
    }

    /** Wire one theme's slider: level grid, stored position, live % readout. */
    private fun bindOpacityBar(
        bar: SeekBar,
        label: TextView,
        levels: Int,
        storedLevel: Int,
        opacityAt: (Int) -> Float,
    ) {
        fun show(level: Int) {
            label.text = getString(
                R.string.config_opacity_value,
                (opacityAt(level) * 100).roundToInt(),
            )
        }
        bar.max = levels - 1
        bar.progress = storedLevel
        show(storedLevel)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, progress: Int, fromUser: Boolean) {
                show(progress)
            }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
    }

    private fun attempt(requireFetch: Boolean) {
        var base = baseField.text.toString().trim().trimEnd('/')
        val token = tokenField.text.toString().trim()
        if (base.isEmpty()) base = FlowStore.DEFAULT_BASE_URL
        if (!base.contains("://")) base = "https://$base"
        if (token.isEmpty()) {
            showError("Token is required.")
            return
        }
        try {
            FlowApi.assertSchemeAllowed(URL(base))
        } catch (e: MalformedURLException) {
            showError("Not a valid URL.")
            return
        } catch (e: Exception) {
            showError(e.message ?: "URL not allowed.")
            return
        }

        setBusy(true)
        val finalBase = base
        // Read both sliders on the UI thread; saved with the config in both paths and
        // applied by the updateAll below — "live on save".
        val lightOpacity = GlassSurface.lightOpacityAtLevel(lightBar.progress)
        val darkOpacity = GlassSurface.darkOpacityAtLevel(darkBar.progress)
        Thread {
            try {
                val store = FlowStore.get(this)
                if (requireFetch) {
                    val body = FlowApi.getWidgetFeed(finalBase, token)
                    FeedParser.parse(body)
                    runBlocking {
                        store.saveConfig(finalBase, token)
                        store.saveOpacity(lightOpacity, darkOpacity)
                        store.saveFeed(body)
                    }
                } else {
                    runBlocking {
                        store.saveConfig(finalBase, token)
                        store.saveOpacity(lightOpacity, darkOpacity)
                    }
                    FlowWork.fetchNow(this)
                }
                FlowWork.schedulePeriodic(this)
                runBlocking { FlowWidget().updateAll(this@ConfigActivity) }
                runOnUiThread { finishOk() }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    // t.message never contains the token: FlowApi keeps it out of
                    // exception text by construction.
                    showError("Could not reach the server: ${t.message ?: t.javaClass.simpleName}")
                    saveAnywayButton.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        saveAnywayButton.isEnabled = !busy
        saveButton.text = getString(if (busy) R.string.config_checking else R.string.config_save)
        if (busy) errorView.visibility = View.GONE
    }

    private fun showError(msg: String) {
        errorView.text = msg
        errorView.visibility = View.VISIBLE
    }

    private fun finishOk() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        }
        finish()
    }
}
