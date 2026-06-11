package com.zipgallery.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveExceptionsTest {

    @Test
    fun `sanitizeFileName replaces path separators`() {
        assertEquals("folder_image_jpg", sanitizeFileName("folder/image.jpg"))
        assertEquals("folder_image_jpg", sanitizeFileName("folder\\image.jpg"))
    }

    @Test
    fun `sanitizeFileName replaces special characters`() {
        assertEquals("file_name_txt", sanitizeFileName("file:name?.txt"))
        assertEquals("a_b_c_d_txt", sanitizeFileName("a<b>c\"d.txt"))
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
