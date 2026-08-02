package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaEntry
import java.io.File

interface ArchiveReader {
    fun readEntries(archiveFile: File, password: String?): Result<List<MediaEntry>>
    fun extractFile(archiveFile: File, entryPath: String, password: String?, outputDir: File): Result<File>

    /**
     * Bulk-extracts the requested entries in a single pass through the archive,
     * returning entry path -> extracted file. Formats that only support
     * sequential reads (7z, tar) MUST override this — the default falls back to
     * per-entry [extractFile], which re-scans the whole archive for every entry
     * (O(n) per entry, i.e. O(n²) for the full set).
     */
    fun extractEntries(
        archiveFile: File,
        entries: List<MediaEntry>,
        password: String?,
        outputDir: File
    ): Result<Map<String, File>> = runCatching {
        val result = mutableMapOf<String, File>()
        for (entry in entries) {
            extractFile(archiveFile, entry.path, password, outputDir)
                .getOrNull()
                ?.let { result[entry.path] = it }
        }
        result
    }

    val supportsEncryption: Boolean get() = false
}
