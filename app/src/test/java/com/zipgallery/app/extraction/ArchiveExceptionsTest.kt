package com.zipgallery.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveExceptionsTest {

    @Test
    fun `sanitizeFileName replaces path separators`() {
        // Path separators become underscores; the file extension is preserved.
        assertEquals("folder_image.jpg", sanitizeFileName("folder/image.jpg"))
        assertEquals("folder_image.jpg", sanitizeFileName("folder\\image.jpg"))
    }

    @Test
    fun `sanitizeFileName replaces special characters`() {
        // Illegal characters (colon, question mark, angle brackets, quotes) are
        // replaced; the extension dot is intentionally kept.
        assertEquals("file_name_.txt", sanitizeFileName("file:name?.txt"))
        assertEquals("a_b_c_d.txt", sanitizeFileName("a<b>c\"d.txt"))
    }

    @Test
    fun `sanitizeFileName trims whitespace`() {
        assertEquals("file.txt", sanitizeFileName("  file.txt  "))
    }

    @Test
    fun `sanitizeFileName handles blank names`() {
        val result = sanitizeFileName("")
        assert(result.startsWith("file_"))
    }

    @Test
    fun `sanitizeFileName limits length to 200`() {
        val longName = "a".repeat(500) + ".txt"
        val result = sanitizeFileName(longName)
        assert(result.length <= 200)
    }
}
