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
            zipFile.extractFile(header, outputDir.absolutePath, safeName)
            zipFile.close()
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

    companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "ts", "m4v")
    }
}
