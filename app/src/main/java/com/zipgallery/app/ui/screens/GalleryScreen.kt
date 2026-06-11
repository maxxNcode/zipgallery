package com.zipgallery.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.zipgallery.app.model.AppScreen
import com.zipgallery.app.model.FilterType
import com.zipgallery.app.model.GridItem
import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import com.zipgallery.app.model.SortType
import com.zipgallery.app.viewmodel.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onItemClick: (MediaEntry) -> Unit,
    onOpenSettings: () -> Unit
) {
    BackHandler(onBack = onBack)

    val state = viewModel.state
    val items = viewModel.gridItems
    val gridState = rememberLazyGridState()
    var showSearch by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollState(index, offset)
            }
    }

    LaunchedEffect(state.screen, state.filterType, state.sortType, state.searchQuery) {
        if (state.screen == AppScreen.Gallery && state.scrollIndex > 0) {
            gridState.scrollToItem(state.scrollIndex, state.scrollOffset)
        }
    }

    DisposableEffect(state.filterType) {
        onDispose {
            viewModel.saveScrollState(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
    }

    val imageCount = state.entries.count { it.type == MediaType.IMAGE }
    val videoCount = state.entries.count { it.type == MediaType.VIDEO }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(state.archiveName.ifBlank { "ZipGallery" }, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch },
                            modifier = Modifier.semantics { contentDescription = "Toggle search" }) {
                            Icon(Icons.Default.Search, contentDescription = "Search",
                                tint = if (showSearch) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onOpenSettings,
                            modifier = Modifier.semantics { contentDescription = "Open settings" }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                if (showSearch) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                        onClose = { showSearch = false; viewModel.setSearchQuery("") }
                    )
                }
                FilterBar(
                    current = state.filterType,
                    currentSort = state.sortType,
                    imageCount = imageCount,
                    videoCount = videoCount,
                    onSelectFilter = { viewModel.setFilter(it) },
                    onSelectSort = { viewModel.setSortType(it) }
                )
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.searchQuery.isNotBlank()) "No results for \"${state.searchQuery}\""
                           else if (state.entries.isEmpty()) "No images or videos found."
                           else "No ${state.filterType.name.lowercase()} found.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = gridState,
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items, span = { item ->
                    if (item is GridItem.Header) GridItemSpan(maxLineSpan)
                    else GridItemSpan(1)
                }, key = { item ->
                    when (item) {
                        is GridItem.Header -> "header_${item.type.name}"
                        is GridItem.Media -> item.entry.path
                    }
                }) { item ->
                    when (item) {
                        is GridItem.Header -> SectionHeader(item.type, item.count)
                        is GridItem.Media -> MediaThumbnail(
                            entry = item.entry,
                            viewModel = viewModel,
                            onClick = { onItemClick(item.entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search files...") },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "Search",
                modifier = Modifier.semantics { contentDescription = "Search" })
        },
        trailingIcon = {
            IconButton(onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close search" }) {
                Icon(Icons.Default.Clear, contentDescription = "Close")
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
private fun FilterBar(
    current: FilterType,
    currentSort: SortType,
    imageCount: Int,
    videoCount: Int,
    onSelectFilter: (FilterType) -> Unit,
    onSelectSort: (SortType) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = current == FilterType.ALL,
            onClick = { onSelectFilter(FilterType.ALL) },
            label = { Text("All", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        FilterChip(
            selected = current == FilterType.IMAGES,
            onClick = { onSelectFilter(FilterType.IMAGES) },
            label = { Text("Images", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        FilterChip(
            selected = current == FilterType.VIDEOS,
            onClick = { onSelectFilter(FilterType.VIDEOS) },
            label = { Text("Videos", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )

        Spacer(Modifier.width(4.dp))

        Box {
            IconButton(onClick = { showSortMenu = true },
                modifier = Modifier.semantics { contentDescription = "Sort options" }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort",
                    tint = if (currentSort != SortType.NAME_ASC) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                SortType.entries.forEach { sortType ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sortType.label,
                                fontWeight = if (sortType == currentSort) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelectSort(sortType)
                            showSortMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(type: MediaType, count: Int) {
    val icon = when (type) {
        MediaType.IMAGE -> Icons.Default.Image
        MediaType.VIDEO -> Icons.Default.Videocam
    }
    val label = when (type) {
        MediaType.IMAGE -> "Images"
        MediaType.VIDEO -> "Videos"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
             tint = MaterialTheme.colorScheme.primary)
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun MediaThumbnail(
    entry: MediaEntry,
    viewModel: GalleryViewModel,
    onClick: () -> Unit
) {
    val thumbnailFile by produceState<Any?>(null, entry.path) {
        value = viewModel.getThumbnailFile(entry)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${entry.type.name.lowercase()} ${entry.name}"
            }
    ) {
        if (thumbnailFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(thumbnailFile)
                    .size(Size(512, 512))
                    .crossfade(true)
                    .build(),
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        if (entry.type == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Video",
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
                tint = Color.White.copy(alpha = 0.85f)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = entry.name,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
