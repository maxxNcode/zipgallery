package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaType
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class CommonsCompressReaderTest {

    @Test
    fun `plain gzip archive exposes a single media entry`() {
        val dir = Files.createTempDirectory("gz_test").toFile()
        try {
            val gz = File(dir, "photo.jpg.gz")
            val payload = ByteArray(512) { it.toByte() }
            GzipCompressorOutputStream(FileOutputStream(gz)).use { it.write(payload) }

            val reader = CommonsCompressReader()
            val entries = reader.readEntries(gz, null).getOrThrow()
            assertEquals(1, entries.size)
            assertEquals("photo.jpg", entries[0].path)
            assertEquals(MediaType.IMAGE, entries[0].type)

            val out = File(dir, "out").apply { mkdirs() }
            val extracted = reader.extractEntries(gz, entries, null, out).getOrThrow()
            val file = extracted["photo.jpg"]
            assertEquals(payload.size.toLong(), file?.length())
            assertArrayEquals(payload, file?.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `gzip with non-media content yields no entries`() {
        val dir = Files.createTempDirectory("gz_test2").toFile()
        try {
            val gz = File(dir, "notes.txt.gz")
            GzipCompressorOutputStream(FileOutputStream(gz)).use { it.write("hello".toByteArray()) }

            val entries = CommonsCompressReader().readEntries(gz, null).getOrThrow()
            assertEquals(emptyList<Any>(), entries)
        } finally {
            dir.deleteRecursively()
        }
    }
}
