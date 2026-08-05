package com.zipgallery.app.model

/**
 * The full filter -> search -> sort pipeline for gallery entries, as a pure
 * function so the behavior is unit-testable without an Android context.
 *
 * When a search query is present, the folder filter is dropped: results come
 * from the WHOLE archive, not just the current folder — that's what makes
 * search useful in a deeply-nested archive. Without a query, only entries
 * whose parent folder equals [currentFolder] are shown.
 */
fun filterAndSortEntries(
    entries: List<MediaEntry>,
    currentFolder: String,
    filterType: FilterType,
    searchQuery: String,
    sortType: SortType
): List<MediaEntry> {
    val searching = searchQuery.isNotBlank()
    var list = if (searching) {
        entries
    } else {
        entries.filter { parentFolderPath(it.path) == currentFolder }
    }
    list = when (filterType) {
        FilterType.ALL -> list
        FilterType.IMAGES -> list.filter { it.type == MediaType.IMAGE }
        FilterType.VIDEOS -> list.filter { it.type == MediaType.VIDEO }
    }
    if (searching) {
        val q = searchQuery.lowercase()
        list = list.filter { it.name.lowercase().contains(q) }
    }
    return when (sortType) {
        SortType.NAME_ASC -> list.sortedBy { it.name.lowercase() }
        SortType.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        SortType.SIZE_DESC -> list.sortedByDescending { it.size }
        SortType.SIZE_ASC -> list.sortedBy { it.size }
        SortType.TYPE -> list.sortedBy { it.type.name }
    }
}
