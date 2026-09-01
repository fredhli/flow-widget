package com.fredhli.flowwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.runBlocking

/**
 * Invisible trampoline between a widget tap and the browser. Widget taps use activity
 * PendingIntents (always allowed to launch), and this activity is where the unread-dot
 * bookkeeping happens: record the tap time, hand the deep link to the browser, repaint
 * the widgets so the dots clear, vanish.
 */
class OpenItemActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                // no browser — nothing sensible to do from a widget shell
            }
            // Small and rare (one widget tap); runBlocking keeps the process from
            // dying before the write and repaint land.
            runBlocking {
                FlowStore.get(this@OpenItemActivity).recordOpen(System.currentTimeMillis())
                FlowWidget().updateAll(this@OpenItemActivity)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "com.fredhli.flowwidget.EXTRA_URL"
    }
}
