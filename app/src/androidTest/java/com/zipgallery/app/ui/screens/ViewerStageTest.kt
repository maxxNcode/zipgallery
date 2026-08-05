package com.zipgallery.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zipgallery.app.ui.theme.ZipGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the viewer overlay controls. The media
 * slot is replaced with a plain tappable Box so the tests exercise the
 * show/hide behavior without needing a real archive or GalleryViewModel.
 */
@RunWith(AndroidJUnit4::class)
class ViewerStageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setViewer(
        showBack: Boolean = true,
        isVideo: Boolean = false,
        fileName: String? = "photo.jpg",
        fileSize: Long? = null,
        onDelete: (() -> Unit)? = null
    ) {
        composeRule.setContent {
            ZipGalleryTheme {
                ViewerStage(
                    fileName = fileName,
                    page = 1,
                    pageCount = 2,
                    showBack = showBack,
                    onBack = if (showBack) ({ }) else null,
                    isVideo = isVideo,
                    onShare = {},
                    applyInsets = false,
                    fileSize = fileSize,
                    onDelete = onDelete
                ) { toggleOverlay ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("media")
                            .clickable { toggleOverlay() }
                    )
                }
            }
        }
    }

    @Test
    fun backButton_renders_whenShowBackIsTrue() {
        setViewer(showBack = true)
        composeRule.onNodeWithContentDescription("Go back").assertIsDisplayed()
    }

    @Test
    fun backButton_isAbsent_whenShowBackIsFalse() {
        setViewer(showBack = false)
        composeRule.onNodeWithContentDescription("Go back").assertDoesNotExist()
    }

    @Test
    fun shareFab_renders_forImages() {
        setViewer(isVideo = false)
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share file").assertIsDisplayed()
    }

    @Test
    fun shareTonalButton_renders_forVideos() {
        setViewer(isVideo = true)
        composeRule.onNodeWithContentDescription("Share file").assertIsDisplayed()
    }

    @Test
    fun filenameAndPageCounter_areDisplayed() {
        setViewer(fileName = "photo.jpg")
        composeRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
    }

    @Test
    fun pageCounter_showsFileSize_whenProvided() {
        setViewer(fileSize = 2L * 1024 * 1024)
        composeRule.onNodeWithText("1 / 2 · 2.0 MB").assertIsDisplayed()
    }

    @Test
    fun deleteButton_renders_whenCallbackProvided() {
        setViewer(onDelete = {})
        composeRule.onNodeWithContentDescription("Delete file").assertIsDisplayed()
    }

    @Test
    fun deleteButton_isAbsent_withoutCallback() {
        setViewer(onDelete = null)
        composeRule.onNodeWithContentDescription("Delete file").assertDoesNotExist()
    }

    @Test
    fun deleteButton_renders_forVideos() {
        setViewer(isVideo = true, onDelete = {})
        composeRule.onNodeWithContentDescription("Delete file").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share file").assertIsDisplayed()
    }

    @Test
    fun tappingMedia_togglesOverlayVisibility() {
        setViewer()
        // Overlay is visible initially.
        composeRule.onNodeWithContentDescription("Go back").assertIsDisplayed()

        // Tap the media area -> controls hide.
        composeRule.onNodeWithTag("media").performClick()
        composeRule.onNodeWithContentDescription("Go back").assertDoesNotExist()

        // Tap again -> controls return.
        composeRule.onNodeWithTag("media").performClick()
        composeRule.onNodeWithContentDescription("Go back").assertIsDisplayed()
    }
}
