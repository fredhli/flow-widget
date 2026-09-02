package com.fredhli.flowwidget.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import androidx.core.content.FileProvider
import com.fredhli.flowwidget.FlowApi
import com.fredhli.flowwidget.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Files the page hands out — the JHT cover-letter PDF, a raw brief, a CSV — fetched with
 * the session cookie into the app's private cache and handed to a viewer or the share
 * sheet through a FileProvider grant.
 *
 * Why a fetch of our own instead of the WebView's download path: `/api/…` responses are
 * behind the session cookie, a viewer app has no cookie jar, and the platform
 * DownloadManager would write to shared storage (the plan's §9 rules that out: the cache
 * is ours, the grant is per-intent, `allowBackup=false` stays). So the shell downloads,
 * scopes the cookie itself, and lets the receiver read exactly one file.
 *
 * The cookie is whatever `CookieManager.getInstance().getCookie(url)` returns for the URL
 * being fetched — the WebView's own jar, which holds `dash_session` for the app origin and
 * nothing for anyone else. That single call is the whole "only send the token to the
 * dashboard" rule; nothing here ever adds an Authorization header or reads the token from
 * DataStore. Redirects are followed by hand (`instanceFollowRedirects = false`) because
 * HttpURLConnection would otherwise carry the Cookie header to wherever `Location` points;
 * a redirect that leaves the origin is refused outright — no `/api/` document has a reason
 * to send one, and the cookie must not arrive anywhere else.
 *
 * The pure helpers at the bottom — [fileNameFor], [mimeFor], [safeName] — use strings and
 * `java.net` only, so FilesTest can pin them on the plain JVM (the `android.jar` stub
 * throws from every method). No URL, header or exception text is ever logged or toasted:
 * URLs from the page carry `?k=<token>`.
 */
object Files {

    enum class Mode { VIEW, SHARE }

    /** 50 MB. A cover letter is ~100 KB; anything near this is not a document. */
    const val MAX_BYTES: Long = 50L shl 20

    /** Under cacheDir; mirrored by res/xml/file_paths.xml (`<cache-path path="downloads/">`). */
    const val CACHE_SUBDIR = "downloads"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 3
    private const val PRUNE_AGE_MS = 24L * 60 * 60 * 1000
    private const val OCTET_STREAM = "application/octet-stream"
    private const val DEFAULT_NAME = "download"

    /** Path-component cap. ext4 allows 255 bytes; 120 chars leaves room for ".part" and UTF-8. */
    private const val MAX_NAME = 120

    /**
     * Fetch [url] with the WebView's cookies on a background Thread into
     * cacheDir/downloads/<safeName>, then on the main thread hand it to a viewer (VIEW,
     * falling back to the share sheet when no app can show that type) or the share sheet
     * (SHARE). Validation — scheme, private-host rule — happens on the caller's thread
     * before anything starts, so a `blob:`/`data:`/`file:` URL never even spawns a thread.
     *
     * [suggestedName] wins when given (the page knows "cv.pdf"); an extension is appended
     * from the response's Content-Type when the name has none, because a viewer chooses
     * itself by MIME and a bare "cv" is nobody's.
     */
    fun openOrShare(context: Context, url: String, suggestedName: String?, mode: Mode) {
        val app = context.applicationContext ?: context
        val target = try {
            URL(url).also { FlowApi.assertSchemeAllowed(it) }
        } catch (_: Exception) {
            // MalformedURLException or the cleartext rule. The message is not shown: it
            // could quote the URL.
            toastOnMain(app, R.string.files_failed)
            return
        }
        // Read on the caller's (UI) thread: CookieManager is safe from any thread once the
        // WebView provider is up, but the first touch of it belongs on the UI thread.
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
        toastOnMain(app, R.string.files_fetching)
        Thread({
            val fetched = try {
                fetch(target, cookie)
            } catch (_: TooBig) {
                toastOnMain(app, R.string.files_too_big)
                return@Thread
            } catch (_: Exception) {
                // IOException (transport, non-2xx, off-origin redirect), SecurityException
                // from the socket layer, anything else: one fixed string.
                toastOnMain(app, R.string.files_failed)
                return@Thread
            }
            val name = finalName(url, suggestedName, fetched)
            val mime = mimeFor(name).takeIf { it != OCTET_STREAM }
                ?: fetched.mime?.takeIf { it.isNotEmpty() && it != OCTET_STREAM }
                ?: OCTET_STREAM
            val file = try {
                store(app, name, fetched.bytes)
            } catch (_: Exception) {
                toastOnMain(app, R.string.files_failed)
                return@Thread
            }
            Handler(Looper.getMainLooper()).post { handOff(app, file, name, mime, mode) }
        }, "dashboard-file-fetch").start()
    }

    /**
     * For `WebView.setDownloadListener`: `<a download>`, `Content-Disposition: attachment`
     * and any response the WebView will not render. The name comes from the headers the
     * WebView already parsed; the fetch is ours, so the cookie is scoped the same way as
     * every other file.
     *
     * `onStarted` is told the URL after the fetch has been kicked off. It exists for the
     * shell's state machine: a main-frame navigation that ends here is one the
     * WebViewClient will never report finished (MainActivity.onDownloadStarted).
     */
    fun downloadListener(context: Context, onStarted: (url: String) -> Unit = {}): DownloadListener =
        DownloadListener { url, _, contentDisposition, mimeType, _ ->
            openOrShare(context, url, fileNameFor(url, contentDisposition, mimeType), Mode.VIEW)
            onStarted(url)
        }

    /**
     * Delete files in cacheDir/downloads older than 24 h. Callable from any thread; does
     * its listing on a background thread and swallows every error — the cache is a
     * convenience and a failed prune must never reach the user. 24 h rather than "on exit":
     * a viewer or the share target may still be reading a grant when the app is killed.
     */
    fun pruneCache(context: Context) {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        Thread({
            try {
                val cutoff = System.currentTimeMillis() - PRUNE_AGE_MS
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                }
            } catch (_: Exception) {
                // nothing to do and nobody to tell
            }
        }, "dashboard-file-prune").start()
    }

    // ---------------------------------------------------------------- fetch

    private class Fetched(val bytes: ByteArray, val mime: String?, val contentDisposition: String?)

    /** Body over MAX_BYTES, by Content-Length or by counting. Its own type so the toast differs. */
    private class TooBig : IOException()

    /**
     * GET with manual, same-origin-only redirects. Throws IOException on anything but a
     * 2xx body under the cap. The Cookie header is re-read per hop so a same-origin
     * redirect to a different path still carries the session, and an off-origin hop is
     * refused before a connection is opened for it.
     */
    @Throws(IOException::class)
    private fun fetch(start: URL, firstCookie: String?): Fetched {
        var current = start
        var cookie = firstCookie
        var hops = 0
        while (true) {
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Accept", "*/*")
                if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                val code = conn.responseCode
                if (code in 300..399 && code != 304) {
                    if (++hops > MAX_REDIRECTS) throw IOException("too many redirects")
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    val next = URL(current, location)
                    if (!sameOrigin(current, next)) throw IOException("redirect leaves the origin")
                    FlowApi.assertSchemeAllowed(next)
                    current = next
                    cookie = try {
                        CookieManager.getInstance().getCookie(next.toString())
                    } catch (_: Exception) {
                        null
                    }
                    continue
                }
                if (code < 200 || code >= 300) throw IOException("HTTP $code")
                val declared = conn.contentLengthLong
                if (declared > MAX_BYTES) throw TooBig()
                val bytes = conn.inputStream.use { stream ->
                    // Bounded read (readNBytes needs API 33; minSdk is 31). One byte past
                    // the cap is read on purpose: it is how "exactly MAX_BYTES" and "more
                    // than MAX_BYTES" are told apart without trusting Content-Length.
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (out.size().toLong() + n > MAX_BYTES) throw TooBig()
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                return Fetched(bytes, mime, conn.getHeaderField("Content-Disposition"))
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Scheme, host (case-insensitive) and effective port all equal. */
    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(u: URL): Int = if (u.port == -1) u.defaultPort else u.port

    /**
     * The on-disk name: the page's suggestion when there is one, else the headers / URL
     * via [fileNameFor]; either way through [safeName], and given an extension from the
     * response type when it has none.
     */
    private fun finalName(url: String, suggestedName: String?, fetched: Fetched): String {
        val base = if (!suggestedName.isNullOrBlank()) safeName(suggestedName)
        else fileNameFor(url, fetched.contentDisposition, fetched.mime)
        return withExtension(base, fetched.mime)
    }

    /**
     * Write to a `.part` sibling first and rename over the target: a viewer that still has
     * the previous file of the same name open keeps reading a complete file, never a
     * half-written one.
     */
    @Throws(IOException::class)
    private fun store(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create cache dir")
        val part = File(dir, "$name.part")
        FileOutputStream(part).use { it.write(bytes) }
        val file = File(dir, name)
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) {
            part.delete()
            throw IOException("rename failed")
        }
        return file
    }

    // ---------------------------------------------------------------- hand-off

    /**
     * Main thread. `uri` is a content:// grant scoped to one file;
     * FLAG_GRANT_READ_URI_PERMISSION on the intent is what lets the receiver open it, and
     * `clipData` on the SEND is what makes the chooser forward that grant to whichever app
     * the user picks (the extras alone are not inspected for URIs). NEW_TASK everywhere:
     * the caller may be the application context.
     */
    private fun handOff(context: Context, file: File, name: String, mime: String, mode: Mode) {
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        } catch (_: IllegalArgumentException) {
            // The file is outside every configured path — a file_paths.xml mismatch, not
            // a runtime condition; fail the same way the user sees every other failure.
            toastOnMain(context, R.string.files_failed)
            return
        }
        if (mode == Mode.VIEW) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
                return
            } catch (_: ActivityNotFoundException) {
                // No viewer for this type (a .md on a stock phone): offer the share sheet,
                // which always has at least Files / Drive / Keep to save it with.
            } catch (_: SecurityException) {
                // A viewer that refuses the grant behaves like a missing one.
            }
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, name, uri)
        val chooser = Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            toastOnMain(context, R.string.files_failed)
        } catch (_: SecurityException) {
            toastOnMain(context, R.string.files_failed)
        }
    }

    // ---------------------------------------------------------------- pure helpers

    /** RFC 5987/6266: `filename*=charset'lang'percent-encoded`. */
    private val EXT_FILENAME = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)

    /** RFC 6266: `filename="quoted \"string\""`. */
    private val QUOTED_FILENAME = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)

    /** RFC 6266: `filename=token` (no quotes; stops at the next parameter). */
    private val BARE_FILENAME = Regex("""filename\s*=\s*([^;"\s][^;]*)""", RegexOption.IGNORE_CASE)

    /** "has an extension": a final `.xxx` of 1–8 word characters. "v1.2 report" has none. */
    private val HAS_EXTENSION = Regex("""\.[A-Za-z0-9]{1,8}$""")

    private val MIME_BY_EXT = mapOf(
        "pdf" to "application/pdf",
        "csv" to "text/csv",
        "json" to "application/json",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "html" to "text/html",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "zip" to "application/zip",
    )

    /** The inverse of [MIME_BY_EXT] with one extension per type (jpeg → "jpg"). */
    private val EXT_BY_MIME: Map<String, String> =
        MIME_BY_EXT.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }

    /**
     * Pure. Content-Disposition `filename*=UTF-8''…` first (the only form that can carry a
     * non-ASCII name — "résumé.pdf"), then `filename="…"`, then a bare `filename=`, then
     * the last URL path segment with query and fragment stripped, then "download". The
     * result is already [safeName]d, and gets an extension from [mimeType] when it has
     * none — a viewer picks itself by type and a bare "export" is nobody's.
     */
    fun fileNameFor(url: String?, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition?.let { cd ->
            EXT_FILENAME.find(cd)?.let { m ->
                val charset = m.groupValues[1].ifBlank { "UTF-8" }
                percentDecode(m.groupValues[2].trim(), charset)
            } ?: QUOTED_FILENAME.find(cd)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                ?: BARE_FILENAME.find(cd)?.groupValues?.get(1)?.trim()
        }
        val fromUrl = url?.let { u ->
            val path = u.substringBefore('#').substringBefore('?')
            // Past the authority, so "https://host" never yields "host" as a file name;
            // a scheme-less (relative) string is taken as a path outright.
            val afterAuthority = if (path.contains("://")) path.substringAfter("://").substringAfter('/', "") else path
            afterAuthority.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { percentDecode(it, "UTF-8") }
        }
        val raw = listOf(fromHeader, fromUrl).firstOrNull { !it.isNullOrBlank() }
        val name = safeName(raw ?: "")
        return withExtension(name, mimeType)
    }

    /**
     * Pure. By extension, case-insensitive: pdf csv json txt md html png jpg jpeg webp xlsx
     * docx zip; anything else "application/octet-stream".
     */
    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXT[ext] ?: OCTET_STREAM
    }

    /**
     * Pure. A name that is safe as a single path component under cacheDir/downloads: path
     * separators and control characters removed (so "../../x" cannot walk anywhere and a
     * NUL cannot truncate), leading dots dropped (no hidden files, no "."/".."), whitespace
     * collapsed, at most 120 characters with the extension preserved, blank → "download".
     */
    fun safeName(name: String): String {
        var s = name
            .replace(Regex("""[/\\]"""), "")
            .replace(Regex("""\p{Cntrl}"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimStart('.')
            .trim()
        if (s.length > MAX_NAME) {
            val ext = HAS_EXTENSION.find(s)?.value ?: ""
            s = s.take(MAX_NAME - ext.length).trimEnd().trimEnd('.') + ext
        }
        return s.ifBlank { DEFAULT_NAME }
    }

    /** Append the type's extension when the name has none and the type is one we know. */
    private fun withExtension(name: String, mimeType: String?): String {
        if (HAS_EXTENSION.containsMatchIn(name)) return name
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return name
        val ext = EXT_BY_MIME[mime] ?: return name
        return "$name.$ext"
    }

    /** Percent-decoding that leaves '+' alone; null when the escapes are malformed. */
    private fun percentDecode(s: String, charset: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), charset)
    } catch (_: Exception) {
        // IllegalArgumentException for a bad escape, UnsupportedEncodingException for a
        // charset the header made up.
        null
    }
}

/**
 * Subclass so the manifest names our own class (`.app.DashboardFileProvider`,
 * authority `${applicationId}.files`). Keeping a class of our own also means the R8 keep
 * rule by package covers it, and two libraries cannot fight over one merged
 * `<provider>` entry. The paths it serves are res/xml/file_paths.xml: cacheDir/downloads
 * only.
 */
class DashboardFileProvider : FileProvider()
