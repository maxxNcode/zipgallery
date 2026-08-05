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

    override fun readFolders(archiveFile: File, password: String?): Result<List<String>> = runCatching {
        val name = archiveFile.name.lowercase()
        val folders = mutableListOf<String>()
        when {
            name.endsWith(".7z") -> {
                val channel: SeekableByteChannel = RandomAccessFile(archiveFile, "r").channel
                SevenZFile(channel).use { sevenZ ->
                    while (true) {
                        val entry: SevenZArchiveEntry = sevenZ.nextEntry ?: break
                        if (entry.isDirectory) folders += entry.name.trimEnd('/')
                    }
                }
            }
            name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.bz2") || name.endsWith(".tar.xz") -> {
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
                tin.use { input ->
                    while (true) {
                        val entry: ArchiveEntry = input.nextEntry ?: break
                        if (entry.isDirectory) folders += entry.name.trimEnd('/')
                    }
                }
            }
            else -> throw ArchiveReadException("Unsupported format: $name")
        }
        folders.filter { it.isNotEmpty() }
    }.recoverCatching { e ->
        throw ArchiveReadException("Failed to read folders: ${e.message}", e)
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

        tin.use {
            var entry: ArchiveEntry? = tin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    addMediaEntry(entry.name, entry.size, entries)
                }
                entry = tin.nextEntry
            }
        }
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

    /**
     * Single-pass bulk extraction: opens the archive once and streams every
     * requested entry to disk. This is the important one for 7z/tar — the old
     * per-entry [extractFile] re-scans the WHOLE archive for every entry
     * (O(n) per file, O(n²) total), which is exactly what made large archives
     * grind on scroll. One pass makes it O(n).
     */
    override fun extractEntries(
        archiveFile: File,
        entries: List<MediaEntry>,
        password: String?,
        outputDir: File
    ): Result<Map<String, File>> = runCatching {
        val wanted = entries.map { it.path }.toHashSet()
        val result = mutableMapOf<String, File>()
        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".7z") -> extractSevenZAll(archiveFile, wanted, outputDir, result)
            name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.bz2") || name.endsWith(".tar.xz") -> extractTarAll(archiveFile, wanted, outputDir, result)
            else -> throw ArchiveReadException("Unsupported format for extraction: $name")
        }
        result
    }.recoverCatching { e ->
        throw ArchiveReadException("Failed to extract archive: ${e.message}", e)
    }

    private fun extractSevenZAll(
        archiveFile: File,
        wanted: Set<String>,
        outputDir: File,
        result: MutableMap<String, File>
    ) {
        val channel: SeekableByteChannel = RandomAccessFile(archiveFile, "r").channel
        SevenZFile(channel).use { sevenZ ->
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in wanted) {
                    result[entry.name] = streamEntryToDisk(sevenZ::read, outputDir, entry.name)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    private fun extractTarAll(
        archiveFile: File,
        wanted: Set<String>,
        outputDir: File,
        result: MutableMap<String, File>
    ) {
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

        tin.use {
            var entry: ArchiveEntry? = tin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in wanted) {
                    result[entry.name] = streamEntryToDisk(tin::read, outputDir, entry.name)
                }
                entry = tin.nextEntry
            }
        }
    }

    /** Reads one already-positioned entry stream and writes it to disk. */
    private fun streamEntryToDisk(
        read: (ByteArray) -> Int,
        outputDir: File,
        entryPath: String
    ): File {
        val safeName = sanitizeFileName(entryPath)
        val outFile = File(outputDir, safeName)
        if (outFile.exists()) return outFile
        // Unique .part + atomic rename so the final path only ever exists as a
        // complete file — the ViewModel's disk fast-path uses exists() as its
        // "done" signal and must never cache a half-written file.
        val part = File(outputDir, "$safeName.part${System.nanoTime()}")
        try {
            FileOutputStream(part).use { os ->
                val buffer = ByteArray(8192)
                var len: Int
                while (read(buffer).also { len = it } != -1) {
                    os.write(buffer, 0, len)
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        atomicFinish(part, outFile)
        return outFile
    }

    private fun atomicFinish(part: File, outFile: File) {
        if (!part.renameTo(outFile)) {
            // Another writer finished first — the existing file is complete.
            if (!outFile.exists()) {
                part.copyTo(outFile, overwrite = true)
            }
            part.delete()
        }
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

        // use {} guarantees the whole stream chain closes on every path,
        // including a mid-scan exception or a missing entry.
        tin.use {
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
                    return
                }
                entry = tin.nextEntry
            }
            throw ArchiveReadException("Entry not found in tar: $entryPath")
        }
    }

    companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "ts", "m4v")
    }
}
