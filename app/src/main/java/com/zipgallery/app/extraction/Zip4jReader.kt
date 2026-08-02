package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

class Zip4jReader : ArchiveReader {

    override val supportsEncryption: Boolean = true

    override fun readEntries(archiveFile: File, password: String?): Result<List<MediaEntry>> {
        return try {
            val zipFile = if (password != null) {
                ZipFile(archiveFile, password.toCharArray())
            } else {
                ZipFile(archiveFile)
            }
            val headers = zipFile.fileHeaders
            val entries = mutableListOf<MediaEntry>()

            var hasEncrypted = false
            for (header in headers) {
                if (header.isEncrypted) {
                    hasEncrypted = true
                }
                if (!header.isDirectory) {
                    val name = header.fileName
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val type = when {
                        ext in IMAGE_EXTS -> MediaType.IMAGE
                        ext in VIDEO_EXTS -> MediaType.VIDEO
                        else -> null
                    }
                    if (type != null) {
                        entries.add(
                            MediaEntry(
                                name = name.substringAfterLast('/'),
                                path = name,
                                type = type,
                                size = header.uncompressedSize
                            )
                        )
                    }
                }
            }
            zipFile.close()

            if (hasEncrypted && password == null) {
                return Result.failure(ArchiveEncryptedException("Archive is password-protected"))
            }

            Result.success(entries)
        } catch (e: ZipException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("password") || msg.contains("wrong key") || msg.contains("encrypted") || msg.contains("aes")) {
                Result.failure(ArchiveEncryptedException("Archive is password-protected", e))
            } else {
                Result.failure(ArchiveReadException("Failed to read ZIP archive: ${e.message}", e))
            }
        } catch (e: Exception) {
            Result.failure(ArchiveReadException("Failed to read ZIP archive: ${e.message}", e))
        }
    }

    override fun extractFile(archiveFile: File, entryPath: String, password: String?, outputDir: File): Result<File> {
        return try {
            val safeName = sanitizeFileName(entryPath)
            val outFile = File(outputDir, safeName)
            if (outFile.exists()) return Result.success(outFile)

            val zipFile = if (password != null) {
                ZipFile(archiveFile, password.toCharArray())
            } else {
                ZipFile(archiveFile)
            }
            val header = zipFile.getFileHeader(entryPath) ?: throw ArchiveReadException("Entry not found: $entryPath")
            // Write to a unique .part name, then atomically rename, so the
            // final path only ever exists as a complete file. Readers elsewhere
            // (the bulk pass, the disk fast-path) use exists() as the "done"
            // signal — this keeps them from caching a half-written file.
            val part = File(outputDir, "$safeName.part${System.nanoTime()}")
            part.delete()
            try {
                zipFile.extractFile(header, outputDir.absolutePath, part.name)
            } catch (e: Exception) {
                part.delete()
                throw e
            } finally {
                zipFile.close()
            }
            atomicFinish(part, outFile)
            Result.success(outFile)
        } catch (e: ZipException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("password") || msg.contains("wrong key") || msg.contains("encrypted") || msg.contains("aes")) {
                Result.failure(ArchiveEncryptedException("Wrong password or archive is encrypted", e))
            } else {
                Result.failure(ArchiveReadException("Failed to extract from ZIP: ${e.message}", e))
            }
        } catch (e: Exception) {
            Result.failure(ArchiveReadException("Failed to extract from ZIP: ${e.message}", e))
        }
    }

    /**
     * Single-pass bulk extraction: opens the ZIP once, iterates the central
     * directory, and extracts every requested entry. One open instead of N,
     * which is much faster than calling [extractFile] per entry.
     */
    override fun extractEntries(
        archiveFile: File,
        entries: List<MediaEntry>,
        password: String?,
        outputDir: File
    ): Result<Map<String, File>> {
        return try {
            val wanted = entries.map { it.path }.toHashSet()
            val result = mutableMapOf<String, File>()
            val zipFile = if (password != null) {
                ZipFile(archiveFile, password.toCharArray())
            } else {
                ZipFile(archiveFile)
            }
            try {
                for (header in zipFile.fileHeaders) {
                    if (header.isDirectory) continue
                    if (header.fileName !in wanted) continue
                    val safeName = sanitizeFileName(header.fileName)
                    val outFile = File(outputDir, safeName)
                    if (!outFile.exists()) {
                        // Unique .part + atomic rename (see extractFile) so a
                        // concurrent fast-path read never sees a partial file.
                        val part = File(outputDir, "$safeName.part${System.nanoTime()}")
                        part.delete()
                        try {
                            zipFile.extractFile(header, outputDir.absolutePath, part.name)
                        } catch (e: Exception) {
                            part.delete()
                            throw e
                        }
                        atomicFinish(part, outFile)
                    }
                    result[header.fileName] = outFile
                }
            } finally {
                zipFile.close()
            }
            Result.success(result)
        } catch (e: ZipException) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("password") || msg.contains("wrong key") || msg.contains("encrypted") || msg.contains("aes")) {
                Result.failure(ArchiveEncryptedException("Wrong password or archive is encrypted", e))
            } else {
                Result.failure(ArchiveReadException("Failed to extract from ZIP: ${e.message}", e))
            }
        } catch (e: Exception) {
            Result.failure(ArchiveReadException("Failed to extract from ZIP: ${e.message}", e))
        }
    }

    companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "ts", "m4v")
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
}
