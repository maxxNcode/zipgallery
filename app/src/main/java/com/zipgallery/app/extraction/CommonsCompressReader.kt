package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel

class CommonsCompressReader : ArchiveReader {

    override fun readEntries(archiveFile: File, password: String?): Result<List<MediaEntry>> = runCatching {
        val name = archiveFile.name.lowercase()
        val entries = mutableListOf<MediaEntry>()

        when {
            name.endsWith(".7z") -> readSevenZ(archiveFile, entries)
            name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.bz2") || name.endsWith(".tar.xz") -> readTar(archiveFile, entries)
            else -> throw ArchiveReadException("Unsupported format: $name")
        }

        entries
    }.recoverCatching { e ->
        if (e is ArchiveEncryptedException || e is ArchiveReadException) throw e
        throw ArchiveReadException("Failed to read archive: ${e.message}", e)
    }

    override fun extractFile(archiveFile: File, entryPath: String, password: String?, outputDir: File): Result<File> = runCatching {
        val safeName = sanitizeFileName(entryPath)
        val outFile = File(outputDir, safeName)
        if (outFile.exists()) return@runCatching outFile

        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".7z") -> extractSevenZ(archiveFile, entryPath, outFile)
            name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.bz2") || name.endsWith(".tar.xz") -> extractTar(archiveFile, entryPath, outFile)
            else -> throw ArchiveReadException("Unsupported format for extraction: $name")
        }

        outFile
    }.recoverCatching { e ->
        throw ArchiveReadException("Failed to extract file: ${e.message}", e)
    }

    private fun readSevenZ(archiveFile: File, entries: MutableList<MediaEntry>) {
        val channel: SeekableByteChannel = RandomAccessFile(archiveFile, "r").channel
        SevenZFile(channel).use { sevenZ ->
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    addMediaEntry(entry.name, entry.size, entries)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    private fun readTar(archiveFile: File, entries: MutableList<MediaEntry>) {
        val fis = FileInputStream(archiveFile)
        val bis = BufferedInputStream(fis)
        val tin = when {
            archiveFile.name.endsWith(".gz") || archiveFile.name.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(bis))
            archiveFile.name.endsWith(".bz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(bis))
            archiveFile.name.endsWith(".xz") ->
                TarArchiveInputStream(XZCompressorInputStream(bis))
            else -> TarArchiveInputStream(bis)
        }

        var entry: ArchiveEntry? = tin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                addMediaEntry(entry.name, entry.size, entries)
            }
            entry = tin.nextEntry
        }
        tin.close()
    }

    private fun addMediaEntry(name: String, size: Long, entries: MutableList<MediaEntry>) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = when {
            ext in IMAGE_EXTS -> MediaType.IMAGE
            ext in VIDEO_EXTS -> MediaType.VIDEO
            else -> return
        }
        entries.add(
            MediaEntry(
                name = name.substringAfterLast('/'),
                path = name,
                type = type,
                size = size
            )
        )
    }

    private fun extractSevenZ(archiveFile: File, entryPath: String, outFile: File) {
        val channel: SeekableByteChannel = RandomAccessFile(archiveFile, "r").channel
        SevenZFile(channel).use { sevenZ ->
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            while (entry != null) {
                if (entry.name == entryPath) {
                    FileOutputStream(outFile).use { os ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (sevenZ.read(buffer).also { len = it } != -1) {
                            os.write(buffer, 0, len)
                        }
                    }
                    return
                }
                entry = sevenZ.nextEntry
            }
            throw ArchiveReadException("Entry not found in 7z: $entryPath")
        }
    }

    private fun extractTar(archiveFile: File, entryPath: String, outFile: File) {
        val fis = FileInputStream(archiveFile)
        val bis = BufferedInputStream(fis)
        val tin = when {
            archiveFile.name.endsWith(".gz") || archiveFile.name.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(bis))
            archiveFile.name.endsWith(".bz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(bis))
            archiveFile.name.endsWith(".xz") ->
                TarArchiveInputStream(XZCompressorInputStream(bis))
            else -> TarArchiveInputStream(bis)
        }

        var entry: ArchiveEntry? = tin.nextEntry
        while (entry != null) {
            if (entry.name == entryPath) {
                FileOutputStream(outFile).use { os ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (tin.read(buffer).also { len = it } != -1) {
                        os.write(buffer, 0, len)
                    }
                }
                tin.close()
                return
            }
            entry = tin.nextEntry
        }
        tin.close()
        throw ArchiveReadException("Entry not found in tar: $entryPath")
    }

    companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "ts", "m4v")
    }
}
