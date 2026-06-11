package com.zipgallery.app.model

import android.net.Uri
import android.content.Context

enum class ArchiveFormat(val extensions: Set<String>) {
    ZIP(setOf("zip")),
    TAR(setOf("tar", "tar.gz", "tgz", "tar.bz2", "tar.xz", "gz", "bz2", "xz")),
    SEVEN_Z(setOf("7z")),
    UNKNOWN(emptySet());

    companion object {
        fun fromUri(uri: Uri): ArchiveFormat {
            val name = uri.lastPathSegment?.lowercase() ?: return UNKNOWN
            return fromFileName(name)
        }

        fun fromFileName(name: String): ArchiveFormat {
            val lower = name.lowercase()
            for (format in entries) {
                if (format.extensions.any { ext ->
                    lower.endsWith(".$ext") || lower == ext
                }) return format
            }
            return UNKNOWN
        }

        fun mimeTypes(): Array<String> = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-bzip2",
            "application/x-xz",
            "application/octet-stream"
        )
    }
}
