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

    private companion object {
        /**
         * The slider is one notch per glass level, not one per percent. The surface is a
         * drawable resource so it can follow a system theme flip (GlassSurface's header),
         * which means the opacity lands on one of GlassSurface.LEVELS steps — 3% apart,
         * hitting the 74% default exactly, and far too fine to see a notch.
         */
        const val OPACITY_STEPS = GlassSurface.LEVELS - 1
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var baseField: EditText
    private lateinit var tokenField: EditText
    private lateinit var opacityBar: SeekBar
    private lateinit var opacityValue: TextView
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
        baseField = findViewById(R.id.base_url)
        tokenField = findViewById(R.id.token)
        opacityBar = findViewById(R.id.opacity)
        opacityValue = findViewById(R.id.opacity_value)
        errorView = findViewById(R.id.error)
        saveButton = findViewById(R.id.save)
        saveAnywayButton = findViewById(R.id.save_anyway)

        val prefs = runBlocking { FlowStore.get(this@ConfigActivity).snapshot() }
        baseField.setText(prefs[FlowStore.KEY_BASE_URL] ?: FlowStore.DEFAULT_BASE_URL)
        prefs[FlowStore.KEY_TOKEN]?.let { tokenField.setText(it) }

        // Background opacity: one SeekBar notch per glass level (min is API 26+ only as an
        // XML attr on some OEM skins, so 0..steps in code is the portable way).
        opacityBar.max = OPACITY_STEPS
        opacityBar.progress = GlassSurface.levelFor(prefs[FlowStore.KEY_BG_OPACITY])
        showOpacity(opacityBar.progress)
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                showOpacity(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        saveButton.setOnClickListener { attempt(requireFetch = true) }
        saveAnywayButton.setOnClickListener { attempt(requireFetch = false) }
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
        // Read the slider on the UI thread; saved with the config in both paths and
        // applied by the updateAll below — "live on save".
        val opacity = GlassSurface.opacityAtLevel(opacityBar.progress)
        Thread {
            try {
                val store = FlowStore.get(this)
                if (requireFetch) {
                    val body = FlowApi.getWidgetFeed(finalBase, token)
                    FeedParser.parse(body)
                    runBlocking {
                        store.saveConfig(finalBase, token)
                        store.saveOpacity(opacity)
                        store.saveFeed(body)
                    }
                } else {
                    runBlocking {
                        store.saveConfig(finalBase, token)
                        store.saveOpacity(opacity)
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

    /**
     * Both numbers, because they are genuinely different surfaces. Light theme spans the
     * brief's 50-95%; dark theme spans 80-95% — its row fill lifts the row *towards* the
     * light ink instead of away from it, so on a bright wallpaper only the container's own
     * opacity keeps the text legible (GlassSurface.MIN_OPACITY_DARK). Showing one number
     * for both would make the slider lie in whichever theme Fred is not looking at.
     */
    private fun showOpacity(level: Int) {
        opacityValue.text = getString(
            R.string.config_opacity_value,
            (GlassSurface.opacityAtLevel(level) * 100).roundToInt(),
            (GlassSurface.darkOpacityAtLevel(level) * 100).roundToInt(),
        )
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
