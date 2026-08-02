package com.zipgallery.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPathTest {

    private fun media(path: String) = MediaEntry(
        name = path.substringAfterLast('/'),
        path = path,
        type = MediaType.IMAGE,
        size = 0L
    )

    @Test
    fun `parentFolderPath returns empty for root entries`() {
        assertEquals("", parentFolderPath("photo.jpg"))
    }

    @Test
    fun `parentFolderPath returns immediate parent`() {
        assertEquals("Vacation", parentFolderPath("Vacation/photo.jpg"))
        assertEquals("Vacation/Beach", parentFolderPath("Vacation/Beach/photo.jpg"))
    }

    @Test
    fun `collectFolderPaths merges explicit and implicit folders`() {
        // Explicit directory entry "Vacation" plus implicit "Beach" derived
        // from the nested entry path.
        val folders = collectFolderPaths(
            entries = listOf(media("Vacation/Beach/photo.jpg")),
            explicitFolders = listOf("Vacation")
        )
        assertEquals(setOf("Vacation", "Vacation/Beach"), folders.toSet())
    }

    @Test
    fun `collectFolderPaths derives folders when no directory entries exist`() {
        // The exact regression: ZIP stores "folder/photo.jpg" with NO "folder/"
        // directory entry — without implicit derivation the gallery would look
        // empty at the root.
        val folders = collectFolderPaths(
            entries = listOf(
                media("Vacation/photo.jpg"),
                media("Vacation/Beach/video.mp4")
            ),
            explicitFolders = emptyList()
        )
        assertEquals(setOf("Vacation", "Vacation/Beach"), folders.toSet())
    }

    @Test
    fun `collectFolderPaths deduplicates implicit ancestors`() {
        // Two sibling entries under "Vacation" must not duplicate the folder.
        val folders = collectFolderPaths(
            entries = listOf(
                media("Vacation/a.jpg"),
                media("Vacation/b.jpg")
            ),
            explicitFolders = emptyList()
        )
        assertEquals(listOf("Vacation"), folders)
    }

    @Test
    fun `collectFolderPaths returns empty for root-only entries`() {
        val folders = collectFolderPaths(
            entries = listOf(media("a.jpg"), media("b.png")),
            explicitFolders = emptyList()
        )
        assertEquals(emptyList<String>(), folders)
    }
}
