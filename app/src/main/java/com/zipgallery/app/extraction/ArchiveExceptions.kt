package com.zipgallery.app.extraction

class ArchiveReadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ArchiveEncryptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun sanitizeFileName(name: String): String {
    val sanitized = name
        .replace(Regex("[/\\\\:?*\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)
    return if (sanitized.isBlank()) "file_${System.nanoTime()}" else sanitized
}
