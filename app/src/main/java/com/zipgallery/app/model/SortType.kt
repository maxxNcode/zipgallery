package com.zipgallery.app.model

enum class SortType(val label: String) {
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    SIZE_DESC("Largest first"),
    SIZE_ASC("Smallest first"),
    TYPE("Type")
}
