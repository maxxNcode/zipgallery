package com.zipgallery.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveFormatTest {

    @Test
    fun `fromFileName detects ZIP correctly`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("photos.zip"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("archive.ZIP"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("path/to/file.zip"))
    }

    @Test
    fun `fromFileName treats jar apk aab as ZIP containers`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("app.apk"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("lib.jar"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("bundle.aab"))
    }

    @Test
    fun `fromFileName returns UNKNOWN for RAR files`() {
        // RAR is not advertised or supported by any reader.
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("data.rar"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("archive.RAR"))
    }

    @Test
    fun `fromFileName detects 7z correctly`() {
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.fromFileName("backup.7z"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.fromFileName("archive.7Z"))
    }

    @Test
    fun `fromFileName returns UNKNOWN for unsupported formats`() {
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("document.pdf"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("image.jpg"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("file.txt"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName(""))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.fromFileName("noextension"))
    }

    @Test
    fun `mimeTypes includes all supported types`() {
        val types = ArchiveFormat.mimeTypes()
        assert(types.contains("application/zip"))
        assert(types.contains("application/x-7z-compressed"))
        assert(types.contains("application/octet-stream"))
        assert(!types.contains("application/x-rar-compressed"))
    }
}
