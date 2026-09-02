package com.fredhli.flowwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.glance.appwidget.updateAll
import com.fredhli.flowwidget.app.Links
import com.fredhli.flowwidget.app.MainActivity
import com.fredhli.flowwidget.app.Routes
import com.fredhli.flowwidget.app.TapTarget
import kotlinx.coroutines.runBlocking

/**
 * Invisible trampoline between a widget tap and wherever the tap is supposed to land.
 * Widget taps use activity PendingIntents (always allowed to launch), and this activity is
 * where the unread-dot bookkeeping happens: record the tap time, hand the deep link on,
 * repaint the widgets so the dots clear, vanish.
 *
 * Since 2.0.0 there are two destinations, chosen by the "Widget taps open" setting:
 * **App** (the default) starts MainActivity at the tapped item's route, and **Browser** is
 * the pre-2.0 behaviour — the same URL in a browser — kept as the escape hatch for when
 * the shell is the thing that is broken. Not the pre-2.0 CODE, though: a plain ACTION_VIEW
 * on a dashboard URL now resolves to this very app, because 2.0.0 is the verified App
 * Links handler for dashboard.fredhli.com and dashboard-chl.fredhli.com. The escape
 * hatch would lead straight back into the broken shell. `Links.openInBrowser` pins the
 * intent to a real browser package (the link policy's Chrome / Custom Tab / default
 * browser, resolved without the URL's host so App Links cannot take part) and, failing
 * every browser, offers a chooser with MainActivity struck off it.
 *
 * The URL the widget hands over is unchanged either way: `$baseUrl/#/flow` for the header
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
            // Small and rare (one widget tap); runBlocking keeps the process from
            // dying before the read, the write and the repaint land. The setting is read
            // inside the same block, off the one DataStore snapshot the write follows.
            runBlocking {
                val store = FlowStore.get(this@OpenItemActivity)
                val shellPrefs = store.shellPrefs()
                when (shellPrefs.tapTarget) {
                    TapTarget.APP -> try {
                        startActivity(
                            MainActivity.routeIntent(
                                this@OpenItemActivity,
                                Routes.routeOf(url),
                            )
                        )
                    } catch (_: ActivityNotFoundException) {
                        // Our own activity, so this is only reachable if the component has
                        // been disabled by hand. Nothing sensible to do from a widget shell.
                    }

                    TapTarget.BROWSER -> try {
                        // openInBrowser answers false (rather than throwing) when nothing
                        // could open it; the catch stays for the exceptions a chooser or
                        // a Custom Tab provider can still raise on an OEM build.
                        Links.openInBrowser(this@OpenItemActivity, url, shellPrefs.linkPolicy)
                    } catch (_: ActivityNotFoundException) {
                        // no browser — nothing sensible to do from a widget shell
                    } catch (_: SecurityException) {
                        // a browser that refuses us — same
                    }
                }
                // Both paths count as "the list was read": the dots clear whether the item
                // opened in the app or in Chrome.
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
