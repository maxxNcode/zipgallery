package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaEntry
import java.io.File

interface ArchiveReader {
    fun readEntries(archiveFile: File, password: String?): Result<List<MediaEntry>>
    fun extractFile(archiveFile: File, entryPath: String, password: String?, outputDir: File): Result<File>
    val supportsEncryption: Boolean get() = false
}
