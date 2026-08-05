package com.zipgallery.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import com.zipgallery.app.util.formatFileSize
import com.zipgallery.app.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Double-tap zooms to this scale; tapping again returns to 1x. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Max gap between two taps to count as a double-tap (also the delay applied
 * to single-tap actions so the first tap of a double-tap can be cancelled). */
private const val DOUBLE_TAP_MS = 300L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    ViewerContent(
        viewModel = viewModel,
        showBack = true,
        onBack = onBack,
        applyInsets = true
    )
}

/**
 * Reusable media viewer used both as the full-screen viewer (compact windows)
 * and as the detail pane of the adaptive two-pane gallery (medium/expanded
 * windows). The black container is intentionally part of this content so the
 * detail pane reads as an immersive media surface.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerContent(
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    applyInsets: Boolean = true
) {
    val entries = viewModel.filteredEntries
    if (entries.isEmpty()) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxIndex = entries.size - 1
    val initialIndex = viewModel.viewerIndex.coerceIn(0, maxIndex)

    val pagerState = rememberPagerState(initialPage = initialIndex) { entries.size }

    LaunchedEffect(pagerState.currentPage) {
        entries.getOrNull(pagerState.currentPage)?.let { entry ->
            if (entry.type == MediaType.VIDEO) {
                withContext(Dispatchers.IO) {
                    viewModel.getExtractedFile(entry)
                }
            }
        }
    }

    var currentEntry by remember { mutableStateOf(entries.getOrNull(initialIndex)) }

    LaunchedEffect(pagerState.currentPage) {
        currentEntry = entries.getOrNull(pagerState.currentPage)
        // Keep the grid highlight in sync when swiping the detail pane. Safe:
        // the viewerIndex effect below no-ops when target == current page.
        viewModel.syncViewerPage(pagerState.currentPage)
    }

    // Follow selection changes from the list pane in two-pane mode.
    LaunchedEffect(viewModel.viewerIndex) {
        val target = viewModel.viewerIndex.coerceIn(0, maxIndex)
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    // After a delete shrinks the entry list, clamp the shared selection to the
    // new last item; if the archive runs out of media, leave the viewer.
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) {
            onBack?.invoke()
        } else if (viewModel.viewerIndex > entries.lastIndex) {
            viewModel.syncViewerPage(entries.lastIndex)
        }
    }

    var pendingDelete by remember { mutableStateOf<MediaEntry?>(null) }

    val shareCurrent: () -> Unit = {
        currentEntry?.let { entry ->
            scope.launch {
                val file = viewModel.shareFile(entry)
                if (file != null) {
                    val shareUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (entry.type == MediaType.IMAGE) "image/*" else "video/*"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share ${entry.name}"))
                }
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black)
    ) {
        ViewerStage(
            fileName = currentEntry?.name,
            fileSize = entries.getOrNull(pagerState.currentPage)?.size,
            page = pagerState.currentPage + 1,
            pageCount = entries.size,
            showBack = showBack,
            onBack = onBack,
            isVideo = entries.getOrNull(pagerState.currentPage)?.type == MediaType.VIDEO,
            onShare = shareCurrent,
            onDelete = if (viewModel.supportsWrite) {
                { pendingDelete = entries.getOrNull(pagerState.currentPage) }
            } else null,
            applyInsets = applyInsets,
            modifier = Modifier.fillMaxSize()
        ) { toggleOverlay ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = entries.getOrNull(page) ?: return@HorizontalPager
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (entry.type) {
                        MediaType.IMAGE -> ZoomableImage(
                            entry = entry,
                            viewModel = viewModel,
                            onTap = toggleOverlay
                        )
                        MediaType.VIDEO -> VideoPage(entry, viewModel, isActive = page == pagerState.currentPage)
                    }
                }
            }
        }

        pendingDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete") },
                text = {
                    Text("Delete \"${entry.name}\" from this archive? This can't be undone.")
                },
                confirmButton = {
                    Button(onClick = {
                        pendingDelete = null
                        viewModel.deleteFromArchive(listOf(entry.path))
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                }
            )
        }
    }
}

/**
 * Hosts the media content and the M3 overlay controls, owning the show/hide
 * ([ViewerOverlay]) state. Kept separate from [ViewerContent] so the overlay
 * behavior can be tested in isolation with a fake media slot.
 *
 * @param media the actual media content; call `toggleOverlay` (typically on a
 *   tap) to show/hide the overlay controls.
 */
@Composable
fun ViewerStage(
    fileName: String?,
    page: Int,
    pageCount: Int,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    isVideo: Boolean,
    onShare: () -> Unit,
    applyInsets: Boolean,
    modifier: Modifier = Modifier,
    fileSize: Long? = null,
    onDelete: (() -> Unit)? = null,
    media: @Composable (toggleOverlay: () -> Unit) -> Unit
) {
    var showUi by remember { mutableStateOf(true) }

    Box(modifier = modifier) {
        media { showUi = !showUi }
        if (showUi) {
            ViewerOverlay(
                fileName = fileName,
                fileSize = fileSize,
                page = page,
                pageCount = pageCount,
                showBack = showBack,
                onBack = onBack,
                isVideo = isVideo,
                onShare = onShare,
                onDelete = onDelete,
                applyInsets = applyInsets,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The M3 overlay controls floating over the media surface: a tonal back
 * button, a filename pill, a page-counter pill, and a share control
 * (ExtendedFAB for images, tonal icon button for videos so it never covers
 * the player's bottom controller). All controls use the inverseSurface /
 * inverseOnSurface pair (the Snackbar treatment) so they stay legible on the
 * always-black media surface in both light and dark themes.
 */
@Composable
fun ViewerOverlay(
    fileName: String?,
    fileSize: Long?,
    page: Int,
    pageCount: Int,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    isVideo: Boolean,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?,
    applyInsets: Boolean,
    modifier: Modifier = Modifier
) {
    val topInset = if (applyInsets) Modifier.statusBarsPadding() else Modifier
    val bottomInset = if (applyInsets) Modifier.navigationBarsPadding() else Modifier
    val overlayColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface
    )

    Box(modifier = modifier) {
        if (showBack && onBack != null) {
            FilledTonalIconButton(
                onClick = onBack,
                colors = overlayColors,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(topInset)
                    .padding(12.dp)
                    .zIndex(10f)
                    .semantics { contentDescription = "Go back" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }

        if (fileName != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .then(topInset)
                    .padding(top = 16.dp)
                    .zIndex(10f)
                    .semantics { contentDescription = "File name: $fileName" }
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(bottomInset)
                .padding(bottom = 24.dp)
                .zIndex(10f)
                .semantics { contentDescription = "Page $page of $pageCount" }
        ) {
            Text(
                text = if (fileSize != null) {
                    "$page / $pageCount · ${formatFileSize(fileSize)}"
                } else {
                    "$page / $pageCount"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (isVideo) {
            // For videos, keep share + delete at the top so they never cover
            // the player's bottom controller.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .then(topInset)
                    .padding(12.dp)
                    .zIndex(10f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onDelete != null) {
                    FilledTonalIconButton(
                        onClick = onDelete,
                        colors = overlayColors,
                        modifier = Modifier.semantics { contentDescription = "Delete file" }
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                }
                FilledTonalIconButton(
                    onClick = onShare,
                    colors = overlayColors,
                    modifier = Modifier.semantics { contentDescription = "Share file" }
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
            }
        } else {
            if (onDelete != null) {
                FilledTonalIconButton(
                    onClick = onDelete,
                    colors = overlayColors,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .then(topInset)
                        .padding(12.dp)
                        .zIndex(10f)
                        .semantics { contentDescription = "Delete file" }
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                }
            }
            ExtendedFloatingActionButton(
                onClick = onShare,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(bottomInset)
                    .padding(16.dp)
                    .zIndex(10f)
                    .semantics { contentDescription = "Share file" },
                icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                text = { Text("Share") }
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    entry: MediaEntry,
    viewModel: GalleryViewModel,
    onTap: () -> Unit
) {
    val file by produceState<Any?>(null, entry.path) {
        value = viewModel.getExtractedFile(entry)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    var singleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    fun runDoubleTapZoom() {
        val target = if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM
        zoomJob?.cancel()
        zoomJob = scope.launch {
            val from = scale
            val steps = 12
            for (i in 1..steps) {
                scale = from + (target - from) * (i / steps.toFloat())
                if (target <= 1f) {
                    offsetX = 0f
                    offsetY = 0f
                }
                delay(8)
            }
            if (target <= 1f) {
                offsetX = 0f
                offsetY = 0f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zooming = false
                    var totalPanX = 0f
                    var totalPanY = 0f

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val pointerCount = event.changes.size
                        val changes = event.changes

                        if (pointerCount > 1 || (scale > 1f && (zoom != 1f || abs(pan.x) > 2f || abs(pan.y) > 2f))) {
                            zooming = true
                        }

                        if (zooming) {
                            zoomJob?.cancel()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) {
                                val maxX = (newScale - 1f) * size.width / 2f
                                val maxY = (newScale - 1f) * size.height / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            changes.forEach { it.consume() }
                        } else {
                            totalPanX += pan.x
                            totalPanY += pan.y
                        }
                    } while (changes.any { it.pressed })

                    if (!zooming && abs(totalPanX) < 10f && abs(totalPanY) < 10f) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            // Second tap: cancel the pending single-tap (which
                            // would have toggled the overlay) and zoom instead.
                            singleTapJob?.cancel()
                            runDoubleTapZoom()
                        } else {
                            lastTapTime = now
                            singleTapJob?.cancel()
                            singleTapJob = scope.launch {
                                delay(DOUBLE_TAP_MS)
                                onTap()
                            }
                        }
                    }
                }
            }
    ) {
        if (file != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = entry.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    entry: MediaEntry,
    viewModel: GalleryViewModel,
    isActive: Boolean
) {
    val context = LocalContext.current
    val videoFile by produceState<Any?>(null, entry.path) {
        value = viewModel.getExtractedFile(entry)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(videoFile, isActive) {
        if (videoFile != null && isActive) {
            val uri = Uri.fromFile(videoFile as java.io.File)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            player.stop()
            player.clearMediaItems()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
