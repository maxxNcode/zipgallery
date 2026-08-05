package com.zipgallery.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilterTest {

    private val entries = listOf(
        MediaEntry("photo.jpg", "Vacation/Beach/photo.jpg", MediaType.IMAGE, 1000L),
        MediaEntry("clip.mp4", "Vacation/Beach/clip.mp4", MediaType.VIDEO, 2000L),
        MediaEntry("portrait.png", "Vacation/portrait.png", MediaType.IMAGE, 500L),
        MediaEntry("wide.mkv", "wide.mkv", MediaType.VIDEO, 3000L)
    )

    @Test
    fun `without query only current folder entries are returned`() {
        val result = filterAndSortEntries(entries, "Vacation/Beach", FilterType.ALL, "", SortType.NAME_ASC)
        assertEquals(listOf("clip.mp4", "photo.jpg"), result.map { it.name })
    }

    @Test
    fun `search spans the whole archive ignoring current folder`() {
        val result = filterAndSortEntries(entries, "Vacation/Beach", FilterType.ALL, "portrait", SortType.NAME_ASC)
        assertEquals(listOf("portrait.png"), result.map { it.name })
    }

    @Test
    fun `search is case-insensitive`() {
        val result = filterAndSortEntries(entries, "", FilterType.ALL, "PHOTO", SortType.NAME_ASC)
        assertEquals(listOf("photo.jpg"), result.map { it.name })
    }

    @Test
    fun `filter applies on top of archive-wide search`() {
        val result = filterAndSortEntries(entries, "", FilterType.VIDEOS, "e", SortType.NAME_ASC)
        assertEquals(listOf("wide.mkv"), result.map { it.name })
    }

    @Test
    fun `search returns nothing for missing name`() {
        val result = filterAndSortEntries(entries, "", FilterType.ALL, "nope", SortType.NAME_ASC)
        assertEquals(emptyList<String>(), result.map { it.name })
    }

    @Test
    fun `sort by size desc applies within current folder`() {
        val result = filterAndSortEntries(entries, "Vacation/Beach", FilterType.ALL, "", SortType.SIZE_DESC)
        assertEquals(listOf("clip.mp4", "photo.jpg"), result.map { it.name })
    }

    @Test
    fun `root folder shows only root entries`() {
        val result = filterAndSortEntries(entries, "", FilterType.ALL, "", SortType.NAME_ASC)
        assertEquals(listOf("wide.mkv"), result.map { it.name })
    }
}
