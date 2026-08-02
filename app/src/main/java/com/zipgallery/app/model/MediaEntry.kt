package com.zipgallery.app.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }
enum class AppThemeMode { SYSTEM, DARK, LIGHT }

enum class FilterType { ALL, IMAGES, VIDEOS }

data class MediaEntry(
    val name: String,
    val path: String,
    val type: MediaType,
    val size: Long
)

/** A directory entry inside an archive that the user can browse into. */
data class ArchiveFolder(
    val name: String,
    val path: String
)

/** A previously-opened archive shown on the dashboard for quick re-open. */
data class RecentArchive(
    val uri: Uri,
    val name: String,
    val openedAt: Long
)

sealed interface GridItem {
    data class Folder(val folder: ArchiveFolder) : GridItem
    data class Header(val type: MediaType, val count: Int) : GridItem
    data class Media(val entry: MediaEntry) : GridItem
}

sealed interface AppScreen {
    data object Main : AppScreen
    data object Gallery : AppScreen
    data object Viewer : AppScreen
    data object Settings : AppScreen
}

/**
 * Parent directory path of an archive entry path ("" for root-level entries).
 * Used for folder browsing: an entry belongs to the folder equal to the part
 * of its path before the last '/' (e.g. "Vacation/Beach/photo.jpg" -> "Vacation/Beach").
 */
fun parentFolderPath(path: String): String {
    val index = path.lastIndexOf('/')
    return if (index < 0) "" else path.substring(0, index)
}

/**
 * Full folder list = explicit directory entries PLUS every implicit folder
 * implied by entry paths. Many real-world archives (Windows "send to
 * compressed folder", some 7-Zip writers) store "Vacation/photo.jpg" without
 * a "Vacation/" directory entry — without the implicit ones, folder-scoped
 * filtering would hide all such media at the root with no folder cell to
 * browse into, and the gallery would look empty.
 */
fun collectFolderPaths(entries: List<MediaEntry>, explicitFolders: List<String>): List<String> {
    val folders = explicitFolders.toMutableSet()
    for (entry in entries) {
        var parent = parentFolderPath(entry.path)
        while (parent.isNotEmpty()) {
            folders.add(parent)
            parent = parentFolderPath(parent)
        }
    }
    return folders.toList()
}

data class GalleryState(
    val screen: AppScreen = AppScreen.Main,
    val entries: List<MediaEntry> = emptyList(),
    val folderPaths: List<String> = emptyList(),
    val recentArchives: List<RecentArchive> = emptyList(),
    val currentFolder: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val currentArchiveUri: Uri? = null,
    val viewerIndex: Int = 0,
    val filterType: FilterType = FilterType.ALL,
    val sortType: SortType = SortType.NAME_ASC,
    val searchQuery: String = "",
    val showPasswordDialog: Boolean = false,
    val passwordError: String? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val archiveName: String = ""
)
