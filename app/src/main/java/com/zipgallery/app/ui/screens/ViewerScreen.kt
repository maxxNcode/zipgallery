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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import com.zipgallery.app.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

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
    var showUi by remember { mutableStateOf(true) }

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

    val topInset = if (applyInsets) Modifier.statusBarsPadding() else Modifier
    val bottomInset = if (applyInsets) Modifier.navigationBarsPadding() else Modifier

    Box(
        modifier = modifier.background(Color.Black)
    ) {
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
                        onTap = { showUi = !showUi }
                    )
                    MediaType.VIDEO -> VideoPage(entry, viewModel, isActive = page == pagerState.currentPage)
                }
            }
        }

        if (showUi) {
            if (showBack && onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .then(topInset)
                        .padding(12.dp)
                        .zIndex(10f)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .semantics { contentDescription = "Go back" }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            currentEntry?.let { entry ->
                Text(
                    text = entry.name,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .then(topInset)
                        .padding(top = 16.dp)
                        .zIndex(10f)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "File name: ${entry.name}" },
                    maxLines = 1
                )
            }

            IconButton(
                onClick = {
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
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .then(topInset)
                    .padding(12.dp)
                    .zIndex(10f)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .semantics { contentDescription = "Share file" }
            ) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }

            Text(
                text = "${pagerState.currentPage + 1} / ${entries.size}",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(bottomInset)
                    .padding(bottom = 24.dp)
                    .zIndex(10f)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Page ${pagerState.currentPage + 1} of ${entries.size}" }
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
                        onTap()
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
