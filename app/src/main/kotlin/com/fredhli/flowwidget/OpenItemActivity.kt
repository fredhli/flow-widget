package com.fredhli.flowwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.glance.appwidget.updateAll
import com.fredhli.flowwidget.app.MainActivity
import com.fredhli.flowwidget.app.Routes
import kotlinx.coroutines.runBlocking

/**
 * Invisible trampoline between a widget tap and wherever the tap is supposed to land.
 * Widget taps use activity PendingIntents (always allowed to launch), and this activity is
 * where the unread-dot bookkeeping happens: record the tap time, hand the deep link on,
 * repaint the widgets so the dots clear, vanish.
 *
 * Since 2.0.1 there is ONE destination: MainActivity, at the tapped item's route. 2.0.0
 * kept the pre-2.0 browser hand-off behind a setting ("Widget taps open: the app / the
 * browser", stored under round 3's `link_app` key); Fred's first on-device run of 2.0.0
 * had a widget tap land in the browser, and his answer was that the widget must never
 * open a URL — the app IS the phone's dashboard now, the site stays for the PC. So the
 * setting is gone, whatever an older build wrote under that key is ignored, and the only
 * thing this activity can start is our own MainActivity (an explicit component intent, so
 * App Links, the Chrome PWA and the default browser cannot take part). Links that leave
 * the dashboard from INSIDE the app are a different matter — Links.openExternal and the
 * link policy still own those.
 *
 * The URL the widget hands over is unchanged from 1.x: `$baseUrl/#/flow` for the header
 * band, `$baseUrl/#/flow/i/<id>` for an item. `Routes.routeOf` is what turns the second
 * form back into `#/flow/i/<id>`; a URL with no usable fragment normalises to `#/flow`
 * inside `routeIntent`, which is exactly what the header band means.
 *
 * The manifest gives this activity `taskAffinity=""` so its throw-away task (noHistory,
 * excludeFromRecents) can never become the task MainActivity is rooted in.
 */
class OpenItemActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            try {
                startActivity(MainActivity.routeIntent(this, Routes.routeOf(url)))
            } catch (_: ActivityNotFoundException) {
                // Our own activity, so this is only reachable if the component has been
                // disabled by hand. Nothing sensible to do from a widget shell.
            }
            // Small and rare (one widget tap); runBlocking keeps the process from dying
            // before the write and the repaint land. The tap counts as "the list was
            // read" whether or not the shell managed to start: the dots clear either way.
            runBlocking {
                val store = FlowStore.get(this@OpenItemActivity)
                store.recordOpen(System.currentTimeMillis())
                FlowWidget().updateAll(this@OpenItemActivity)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "com.fredhli.flowwidget.EXTRA_URL"
    }
}
