package com.zipgallery.app.util

/** Human-readable file size (B / KB / MB / GB), shared by Settings and Viewer. */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}
