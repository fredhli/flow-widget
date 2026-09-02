package com.fredhli.flowwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.runBlocking

/**
 * Invisible trampoline between a widget tap and the browser. Widget taps use activity
 * PendingIntents (always allowed to launch), and this activity is where the unread-dot
 * bookkeeping happens: record the tap time, hand the deep link to the browser, repaint
 * the widgets so the dots clear, vanish.
 *
 * "Open links with" (round 3 item 4b) is honoured here, so it covers every link the
 * widget can fire — the header band and, in open-dashboard tap mode, the item rows.
 * Chrome mode pins the VIEW intent to com.android.chrome; when Chrome is missing or
 * disabled, startActivity throws ActivityNotFoundException (a disabled package resolves
 * to nothing, same as an absent one) and the tap falls back to the plain VIEW it always
 * sent — the default browser, never a dead tap.
 */
class OpenItemActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            // One snapshot for both reads; runBlocking is fine at this scale (one tap).
            val store = FlowStore.get(this)
            val prefs = runBlocking { store.snapshot() }
            val chrome =
                WidgetSettings.linkApp(prefs[FlowStore.KEY_LINK_APP]) == WidgetSettings.LINK_CHROME

            fun view(pkg: String?) =
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { if (pkg != null) setPackage(pkg) }

            try {
                startActivity(view(if (chrome) WidgetSettings.CHROME_PACKAGE else null))
            } catch (_: ActivityNotFoundException) {
                if (chrome) {
                    // The URL is not logged with the exception path deliberately kept
                    // token-free; this line is the fallback's runtime evidence.
                    Log.i(TAG, "Chrome unavailable — falling back to the default VIEW handler")
                    try {
                        startActivity(view(null))
                    } catch (_: ActivityNotFoundException) {
                        // no browser at all — nothing sensible to do from a widget shell
                    }
                }
                // plain-VIEW mode with no browser: same dead end as before, same no-op
            }
            // Small and rare (one widget tap); runBlocking keeps the process from
            // dying before the write and repaint land.
            runBlocking {
                store.recordOpen(System.currentTimeMillis())
                FlowWidget().updateAll(this@OpenItemActivity)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "com.fredhli.flowwidget.EXTRA_URL"
        private const val TAG = "FlowOpen"
    }
}
