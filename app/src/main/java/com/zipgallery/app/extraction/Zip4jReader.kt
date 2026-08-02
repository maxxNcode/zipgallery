package com.zipgallery.app.extraction

import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

class Zip4jReader : ArchiveReader {

    override val supportsEncryption: Boolean = true

    /** ZIP archives can be edited in place (add entries / folders). */
    override val supportsWrite: Boolean = true

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

    override fun readFolders(archiveFile: File, password: String?): Result<List<String>> {
        return try {
            val zipFile = if (password != null) ZipFile(archiveFile, password.toCharArray()) else ZipFile(archiveFile)
            try {
                val folders = zipFile.fileHeaders
                    .filter { it.isDirectory }
                    .map { it.fileName.trimEnd('/') }
                    .filter { it.isNotEmpty() }
                Result.success(folders)
            } finally {
                zipFile.close()
            }
        } catch (e: Exception) {
            Result.failure(ArchiveReadException("Failed to read ZIP folders: ${e.message}", e))
        }
    }

    /**
     * Adds the given files (entry path -> source file) into the archive at
     * their custom entry paths (e.g. "Vacation/Beach/photo.jpg"). Opens the
     * archive once and streams each source in, so a multi-file insert is one
     * write pass.
     *
     * For encrypted archives, newly-added entries use the same encryption
     * method + password as the existing entries (checked from the archive's
     * own headers) so the archive stays uniformly encrypted.
     */
    fun addFiles(
        archiveFile: File,
        files: List<Pair<String, File>>,
        password: String?
    ): Result<Unit> {
        return try {
            val zipFile = if (password != null) ZipFile(archiveFile, password.toCharArray()) else ZipFile(archiveFile)
            try {
                val archiveEncrypted = zipFile.fileHeaders.any { it.isEncrypted }
                // Local names deliberately avoid the ZipParameters receiver's
                // own `encryptionMethod`/`password` properties — inside the
                // apply{} block those would shadow these locals (both a compile
                // error and a silent no-op bug if unresolved differently).
                val detectedMethod = if (archiveEncrypted) {
                    zipFile.fileHeaders.firstOrNull { it.isEncrypted }?.encryptionMethod
                        ?: EncryptionMethod.AES
                } else null
                for ((entryPath, sourceFile) in files) {
                    val params = ZipParameters().apply {
                        compressionMethod = CompressionMethod.DEFLATE
                        fileNameInZip = entryPath
                        if (detectedMethod != null) {
                            // zip4j applies the password already set on the
                            // ZipFile constructor to newly-encrypted entries.
                            setEncryptFiles(true)
                            setEncryptionMethod(detectedMethod)
                        }
                    }
                    sourceFile.inputStream().use { input ->
                        zipFile.addStream(input, params)
                    }
                }
                Result.success(Unit)
            } finally {
                zipFile.close()
            }
        } catch (e: Exception) {
            Result.failure(ArchiveWriteException("Failed to add files to ZIP: ${e.message}", e))
        }
    }

    /**
     * Creates an empty folder entry (e.g. "Vacation/New Folder") in the
     * archive. zip4j has no direct "add empty directory" API, so we add an
     * empty temp directory whose name IS the target folder's final segment and
     * whose zip root is the target's parent — zip4j then writes exactly one
     * directory entry at the requested path, with no placeholder children.
     */
    fun createFolder(
        archiveFile: File,
        folderPath: String,
        password: String?
    ): Result<Unit> {
        return try {
            val zipFile = if (password != null) ZipFile(archiveFile, password.toCharArray()) else ZipFile(archiveFile)
            val folderName = folderPath.substringAfterLast('/')
            val parentPath = folderPath.substringBeforeLast('/', "")
            val tempDir = File.createTempFile("zipg_dir_", "").apply { delete() }
            // The empty dir's own name becomes the zip entry's final segment, so
            // addFolder(root = parentPath) yields "parentPath/folderName/" — the
            // exact directory entry we want (never "parentPath/folderName/__empty__/").
            val emptyDir = File(tempDir, folderName).apply { mkdirs() }
            try {
                val params = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    // Leave the root unset for top-level folders: an empty
                    // rootFolderNameInZip string can make zip4j emit a leading
                    // '/' which would never match entry paths.
                    if (parentPath.isNotEmpty()) {
                        rootFolderNameInZip = parentPath
                    }
                }
                zipFile.addFolder(emptyDir, params)
                Result.success(Unit)
            } finally {
                zipFile.close()
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Result.failure(ArchiveWriteException("Failed to create folder in ZIP: ${e.message}", e))
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
