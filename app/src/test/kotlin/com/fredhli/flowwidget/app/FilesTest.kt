package com.fredhli.flowwidget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of Files on the plain JVM: the name a fetched file gets, the type a name
 * maps to, and the sanitiser that keeps every name a single component under
 * cacheDir/downloads.
 */
class FilesTest {

    // ------------------------------------------------------------ fileNameFor

    @Test
    fun `rfc 5987 extended filename wins and is decoded`() {
        assertEquals(
            "résumé.pdf",
            Files.fileNameFor(
                "https://dashboard.fredhli.com/api/x?k=t",
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf",
                "application/pdf",
            ),
        )
        // Language tag present, charset case-insensitive, no quoted fallback.
        assertEquals(
            "naïve.csv",
            Files.fileNameFor(null, "attachment; filename*=utf-8'en'na%C3%AFve.csv", null),
        )
    }

    @Test
    fun `quoted filename keeps spaces and unescapes`() {
        assertEquals("a b.pdf", Files.fileNameFor("https://x/api/y", "attachment; filename=\"a b.pdf\"", null))
        assertEquals("q\"uote.txt", Files.fileNameFor(null, "inline; filename=\"q\\\"uote.txt\"", null))
        assertEquals("Report.PDF", Files.fileNameFor(null, "ATTACHMENT; FILENAME=\"Report.PDF\"", null))
    }

    @Test
    fun `bare filename token`() {
        assertEquals("cv.pdf", Files.fileNameFor(null, "attachment; filename=cv.pdf", null))
        assertEquals("cv.pdf", Files.fileNameFor(null, "attachment; filename=cv.pdf; size=12", null))
    }

    @Test
    fun `falls back to the last url path segment with query and fragment stripped`() {
        assertEquals(
            "cl.pdf",
            Files.fileNameFor("https://dashboard.fredhli.com/api/jht/jobs/42/cl.pdf?k=secret#frag", null, null),
        )
        assertEquals("cl.pdf", Files.fileNameFor("https://dashboard.fredhli.com/api/jht/jobs/42/cl.pdf", "", null))
        assertEquals("my file.md", Files.fileNameFor("https://x/api/brief/my%20file.md?k=t", null, null))
        // Not the host, and not an empty trailing segment.
        assertEquals("download", Files.fileNameFor("https://dashboard.fredhli.com", null, null))
        assertEquals("download", Files.fileNameFor("https://dashboard.fredhli.com/", null, null))
        assertEquals("download", Files.fileNameFor("https://dashboard.fredhli.com/api/", null, null))
    }

    @Test
    fun `extension comes from the mime type when the name has none`() {
        assertEquals("export.csv", Files.fileNameFor("https://x/api/export", null, "text/csv"))
        assertEquals("download.pdf", Files.fileNameFor(null, null, "application/pdf"))
        assertEquals("download.pdf", Files.fileNameFor(null, null, "Application/PDF; charset=binary"))
        assertEquals("photo.jpg", Files.fileNameFor("https://x/a/photo", null, "image/jpeg"))
        // A name that already has an extension keeps it, whatever the type says.
        assertEquals("export.csv", Files.fileNameFor("https://x/api/export.csv", null, "application/pdf"))
        // An unknown type appends nothing.
        assertEquals("export", Files.fileNameFor("https://x/api/export", null, "application/octet-stream"))
        assertEquals("export", Files.fileNameFor("https://x/api/export", null, "application/x-whatever"))
        assertEquals("download", Files.fileNameFor(null, null, null))
    }

    @Test
    fun `header names go through safeName`() {
        assertEquals("etcpasswd", Files.fileNameFor(null, "attachment; filename=\"../../etc/passwd\"", null))
        assertEquals("evil.pdf", Files.fileNameFor(null, "attachment; filename*=UTF-8''..%2F..%2Fevil.pdf", null))
    }

    // ------------------------------------------------------------ safeName

    @Test
    fun `safeName strips path separators`() {
        assertEquals("etcpasswd", Files.safeName("../../etc/passwd"))
        assertEquals("ab.pdf", Files.safeName("a/b.pdf"))
        assertEquals("ab.pdf", Files.safeName("a\\b.pdf"))
        assertEquals("cv.pdf", Files.safeName("/cv.pdf"))
    }

    @Test
    fun `safeName drops leading dots and control characters`() {
        assertEquals("download", Files.safeName(".."))
        assertEquals("download", Files.safeName("."))
        assertEquals("hidden", Files.safeName(".hidden"))
        assertEquals("hidden", Files.safeName("...hidden"))
        // Control characters are removed, not turned into spaces: a NUL or a newline in a
        // name is an attack or a bug, never a word break.
        assertEquals("ab.pdf", Files.safeName("a\nb.pdf"))
        assertEquals("ab.pdf", Files.safeName("a\u0000b.pdf"))
        assertEquals("ab.pdf", Files.safeName("a\u001Fb.pdf"))
        assertEquals("cv.pdf", Files.safeName("cv\u0000.pdf"))
    }

    @Test
    fun `safeName collapses whitespace`() {
        assertEquals("a b.pdf", Files.safeName("  a   b.pdf  "))
        assertEquals("a b.pdf", Files.safeName("a\t b.pdf"))
        assertEquals("cv .pdf", Files.safeName("cv .pdf"))
    }

    @Test
    fun `safeName caps the length and keeps the extension`() {
        val long = "x".repeat(200) + ".pdf"
        val out = Files.safeName(long)
        assertEquals(120, out.length)
        assertTrue(out.endsWith(".pdf"))
        val noExt = "y".repeat(500)
        assertEquals(120, Files.safeName(noExt).length)
    }

    @Test
    fun `safeName blank becomes download`() {
        assertEquals("download", Files.safeName(""))
        assertEquals("download", Files.safeName("   "))
        assertEquals("download", Files.safeName("///"))
    }

    // ------------------------------------------------------------ mimeFor

    @Test
    fun `mimeFor known extensions`() {
        assertEquals("application/pdf", Files.mimeFor("cv.pdf"))
        assertEquals("text/csv", Files.mimeFor("a.csv"))
        assertEquals("application/json", Files.mimeFor("a.json"))
        assertEquals("text/plain", Files.mimeFor("a.txt"))
        assertEquals("text/markdown", Files.mimeFor("brief.md"))
        assertEquals("text/html", Files.mimeFor("a.html"))
        assertEquals("image/png", Files.mimeFor("a.png"))
        assertEquals("image/jpeg", Files.mimeFor("a.jpg"))
        assertEquals("image/jpeg", Files.mimeFor("a.jpeg"))
        assertEquals("image/webp", Files.mimeFor("a.webp"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.mimeFor("a.xlsx"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Files.mimeFor("a.docx"))
        assertEquals("application/zip", Files.mimeFor("a.zip"))
    }

    @Test
    fun `mimeFor is case-insensitive on the extension`() {
        assertEquals("application/pdf", Files.mimeFor("CV.PDF"))
        assertEquals("image/jpeg", Files.mimeFor("Photo.JPEG"))
    }

    @Test
    fun `mimeFor unknown or missing extension is octet-stream`() {
        assertEquals("application/octet-stream", Files.mimeFor("a.exe"))
        assertEquals("application/octet-stream", Files.mimeFor("noext"))
        assertEquals("application/octet-stream", Files.mimeFor(""))
        assertEquals("application/octet-stream", Files.mimeFor("trailing."))
        assertFalse(Files.mimeFor("archive.tar.gz") == "application/zip")
    }
}
