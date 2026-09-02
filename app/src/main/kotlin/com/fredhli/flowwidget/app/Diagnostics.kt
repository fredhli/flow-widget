package com.fredhli.flowwidget.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fredhli.flowwidget.R
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The diagnostics dialog (spec §6, "Diagnostics"): what the page sees (viewport, visual
 * viewport, env() insets, the `--safe-*` vars, theme, font size) next to what the shell
 * knows (WebView version, native insets, IME mode, text zoom, density). It exists because
 * nothing about insets or the keyboard is verifiable off the phone: Fred taps
 * "Diagnostics…" in settings, copies the text, pastes it into a chat.
 *
 * Contains no token and no URL query string — `url` is stripped of `?…` before display,
 * and the native half never had one.
 */
object Diagnostics {

    /**
     * Evaluated in the page; returns a JSON string (so evaluateJavascript hands back a JSON
     * string literal — see [unquote]). Plain ES5, no template literals, because this is a
     * Kotlin raw string and a `$` in it would be interpolated.
     */
    const val JS_METRICS: String = """
        (function () {
          try {
            var d = document.documentElement, cs = getComputedStyle(d);
            function px(v) { var n = parseFloat(v); return isNaN(n) ? 0 : Math.round(n); }
            var host = document.body || d;
            var p = document.createElement('div');
            p.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;'
              + 'padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px);'
              + 'padding-left:env(safe-area-inset-left,0px);padding-right:env(safe-area-inset-right,0px)';
            host.appendChild(p);
            var pc = getComputedStyle(p);
            var env = { t: px(pc.paddingTop), b: px(pc.paddingBottom), l: px(pc.paddingLeft), r: px(pc.paddingRight) };
            host.removeChild(p);
            var vv = window.visualViewport;
            var o = {
              innerWidth: window.innerWidth, innerHeight: window.innerHeight,
              outerWidth: window.outerWidth, outerHeight: window.outerHeight,
              dpr: window.devicePixelRatio,
              screen: { w: screen.width, h: screen.height, aw: screen.availWidth, ah: screen.availHeight },
              vv: vv ? { w: Math.round(vv.width), h: Math.round(vv.height), ot: Math.round(vv.offsetTop), s: vv.scale } : null,
              safe: {
                t: px(cs.getPropertyValue('--safe-top')), b: px(cs.getPropertyValue('--safe-bottom')),
                l: px(cs.getPropertyValue('--safe-left')), r: px(cs.getPropertyValue('--safe-right'))
              },
              env: env,
              desk: d.getAttribute('data-desk'),
              theme: d.getAttribute('data-theme'),
              dark: !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches),
              fontPx: px(cs.fontSize),
              ua: navigator.userAgent,
              url: location.href,
              native: !!window.Native,
              app: !!window.DashboardApp
            };
            return JSON.stringify(o);
          } catch (e) {
            return JSON.stringify({ error: String(e) });
          }
        })()
    """

    /**
     * Returns "t,b,l,r" — env(safe-area-inset-*) as the page resolves it, in CSS px — from a
     * hidden fixed div padded with env(). "" when the probe cannot run.
     */
    const val JS_ENV_PROBE: String = """
        (function () {
          try {
            var host = document.body || document.documentElement;
            var p = document.createElement('div');
            p.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;'
              + 'padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px);'
              + 'padding-left:env(safe-area-inset-left,0px);padding-right:env(safe-area-inset-right,0px)';
            host.appendChild(p);
            var c = getComputedStyle(p);
            function px(v) { var n = parseFloat(v); return isNaN(n) ? 0 : Math.round(n); }
            var r = [px(c.paddingTop), px(c.paddingBottom), px(c.paddingLeft), px(c.paddingRight)].join(',');
            host.removeChild(p);
            return r;
          } catch (e) {
            return '';
          }
        })()
    """

    /**
     * evaluateJavascript hands results back JSON-encoded: a script that returns the string
     * `abc` arrives as `"abc"`, one that returns nothing as `null`. This turns a string
     * result back into the string; anything else (null, a number, garbage) comes back as
     * the raw text so the caller can still show it.
     */
    fun unquote(raw: String?): String {
        if (raw == null) return "null"
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (_: JSONException) {
            raw
        }
    }

    /**
     * A framework AlertDialog with the two halves pretty-printed in monospace. Copy puts the
     * same text on the clipboard; Run again re-asks the page (insets change with the
     * keyboard and the fold, so one reading is rarely the interesting one).
     *
     * Returns the shown dialog because the caller owns it: an AlertDialog is a window
     * attached to the activity, and an activity that finishes (or is recreated for a config
     * change outside the manifest's list) while one is up leaks the window —
     * "Activity has leaked window … that was originally added here" — so MainActivity
     * dismisses it from onDestroy.
     */
    fun show(activity: MainActivity, pageJson: String, native: JSONObject): AlertDialog {
        val page = try {
            JSONObject(pageJson)
        } catch (_: JSONException) {
            JSONObject().put("raw", pageJson)
        }
        if (page.has("url")) page.put("url", Routes.stripQuery(page.optString("url")))
        val text = "Page\n" + page.toString(2) + "\n\nNative\n" + native.toString(2)

        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val body = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
            this.text = text
        }
        val scroll = ScrollView(activity).apply { addView(body) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.diag_title)
            .setView(scroll)
            .setPositiveButton(R.string.diag_close, null)
            .setNeutralButton(R.string.diag_copy) { _, _ ->
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
                Toast.makeText(activity, R.string.diag_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.diag_again) { _, _ -> activity.runDiagnostics() }
            .create()
        dialog.show()
        return dialog
    }
}
