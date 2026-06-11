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

sealed interface GridItem {
    data class Header(val type: MediaType, val count: Int) : GridItem
    data class Media(val entry: MediaEntry) : GridItem
}

sealed interface AppScreen {
    data object Main : AppScreen
    data object Gallery : AppScreen
    data object Viewer : AppScreen
    data object Settings : AppScreen
}

data class GalleryState(
    val screen: AppScreen = AppScreen.Main,
    val entries: List<MediaEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentArchiveUri: Uri? = null,
    val viewerIndex: Int = 0,
    val filterType: FilterType = FilterType.ALL,
    val sortType: SortType = SortType.NAME_ASC,
    val searchQuery: String = "",
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val showPasswordDialog: Boolean = false,
    val passwordError: String? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val archiveName: String = ""
)
