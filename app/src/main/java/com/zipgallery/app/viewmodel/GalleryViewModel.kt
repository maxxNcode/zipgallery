package com.zipgallery.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zipgallery.app.extraction.ArchiveEncryptedException
import com.zipgallery.app.extraction.ArchiveReader
import com.zipgallery.app.extraction.CommonsCompressReader
import com.zipgallery.app.extraction.Zip4jReader
import com.zipgallery.app.extraction.sanitizeFileName
import com.zipgallery.app.model.AppScreen
import com.zipgallery.app.model.AppThemeMode
import com.zipgallery.app.model.ArchiveFolder
import com.zipgallery.app.model.ArchiveFormat
import com.zipgallery.app.model.FilterType
import com.zipgallery.app.model.GalleryState
import com.zipgallery.app.model.GridItem
import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import com.zipgallery.app.model.RecentArchive
import com.zipgallery.app.model.SortType
import com.zipgallery.app.model.collectFolderPaths
import com.zipgallery.app.model.filterAndSortEntries
import com.zipgallery.app.model.parentFolderPath
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf(GalleryState())
        private set
    var viewerIndex by mutableIntStateOf(0)
        private set

    // Scroll position lives OUTSIDE [GalleryState] on purpose: it changes on
    // every scroll frame, and a state.copy() per frame would invalidate every
    // state reader (recomposing the whole screen + re-sorting entries). As its
    // own snapshot state, scroll writes only invalidate the small scope that
    // reads these two fields.
    var scrollIndex by mutableIntStateOf(0)
        private set
    var scrollOffset by mutableIntStateOf(0)
        private set

    private var sessionDir: File? = null
    private var tempArchiveFile: File? = null
    private var archivePassword: String? = null
    private var currentFormat: ArchiveFormat = ArchiveFormat.UNKNOWN
    // Identifies the current archive session. Coil's memory/disk cache keys
    // are prefixed with it so two different archives that both contain
    // "photo.jpg" can never serve each other's thumbnails.
    private var sessionToken: String = ""
    // Concurrent maps: thumbnail requests fire from many IO coroutines (grid
    // cells + viewer) at once, so plain mutable maps could corrupt under race.
    private val extractedCache = ConcurrentHashMap<String, File>()

    // Successful thumbnail files (path -> file). Only successes live here —
    // ConcurrentHashMap forbids null values, so failures are tracked separately
    // in [attemptedThumbnails]. Not observable.
    private val thumbnailCache = ConcurrentHashMap<String, File>()

    // Every path whose thumbnail was already attempted (success OR failure).
    // This is what ensureThumbnail/warmThumbnails consult so a corrupt file is
    // never retried on every scroll-back.
    private val attemptedThumbnails: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // PER-CELL observable states: each grid cell reads ONLY its own
    // MutableState (via thumbnailStateFor), so a thumbnail landing recomposes
    // just that cell. A single global SnapshotStateMap would invalidate every
    // reading cell on any write, recomposing the whole grid on each landing.
    private val thumbnailStates = ConcurrentHashMap<String, MutableState<File?>>()

    // Single-flight maps: every concurrent caller for the same entry awaits one
    // shared Deferred (created atomically by computeIfAbsent), so a file is
    // extracted / thumbnail generated exactly once even under heavy scroll.
    private val extractJobs = ConcurrentHashMap<String, Deferred<File?>>()
    private val thumbnailJobs = ConcurrentHashMap<String, Deferred<File?>>()

    // Caps how many full archive decompressions run at once. A fast scroll can
    // otherwise launch dozens of extractions on Dispatchers.IO (up to 64
    // threads), flooding CPU and disk and starving the UI. Serializing them
    // through this semaphore keeps the grid responsive while thumbs stream in.
    private val extractSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    // Handle to the background thumbnail pre-warm so it can be cancelled when a
    // new archive loads (it must not keep holding semaphore permits or writing
    // stale state for a session that was torn down).
    private var warmJob: Job? = null

    // Where the background single-pass extraction writes every media file, so
    // thumbnails and the viewer become cheap local-file reads instead of
    // re-decompressing entries on demand. Subdir of sessionDir, deleted on
    // cleanup like the rest of the session.
    private var extractDir: File? = null
    private var preExtractJob: Job? = null

    private val zipReader: ArchiveReader = Zip4jReader()
    private val compressReader: ArchiveReader = CommonsCompressReader()

    private val prefs by lazy { getApplication<Application>().getSharedPreferences("zipgallery_prefs", Context.MODE_PRIVATE) }

    init {
        val savedTheme = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)
        if (savedTheme != null) {
            state = state.copy(themeMode = AppThemeMode.valueOf(savedTheme))
        }
        state = state.copy(useDynamicColor = prefs.getBoolean("dynamic_color", true))
        state = state.copy(recentArchives = loadRecentArchives())
    }

    fun setThemeMode(mode: AppThemeMode) {
        state = state.copy(themeMode = mode)
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        state = state.copy(useDynamicColor = enabled)
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    /**
     * Filtered + sorted media entries for the current folder, memoized with
     * [derivedStateOf] like [gridItems]. The viewer reads this on every
     * recomposition (page swipes, two-pane selection), so an eager getter that
     * re-sorts on each read made large archives jank in the viewer — deriving
     * it recomputes only when [state] (entries/folder/filter/search/sort) actually
     * changes.
     */
    val filteredEntries: List<MediaEntry> by derivedStateOf { buildFilteredEntries() }

    private fun buildFilteredEntries(): List<MediaEntry> =
        filterAndSortEntries(
            entries = state.entries,
            currentFolder = state.currentFolder,
            filterType = state.filterType,
            searchQuery = state.searchQuery,
            sortType = state.sortType
        )

    /**
     * Direct sub-folders of the current folder, in display order. A folder
     * path belongs here when its parent is the current folder ("" = archive
     * root), e.g. "Vacation/Beach" -> parent "Vacation".
     */
    val currentFolderFolders: List<ArchiveFolder>
        get() = state.folderPaths
            .filter { parentFolderPath(it) == state.currentFolder }
            .sortedBy { it.lowercase() }
            .map { path -> ArchiveFolder(name = path.substringAfterLast('/'), path = path) }

    /**
     * Grid layout memoized with [derivedStateOf]: the folder/filter/sort
     * pipeline only recomputes when [state] actually changes (load, navigate,
     * filter, sort, search), never on every scroll-frame recomposition.
     */
    val gridItems: List<GridItem> by derivedStateOf { buildGridItems() }

    private fun buildGridItems(): List<GridItem> {
        val items = mutableListOf<GridItem>()
        // While searching, results span the whole archive — folder cells would
        // be meaningless (they're scoped to the current folder), so hide them.
        if (state.searchQuery.isBlank()) {
            currentFolderFolders.forEach { items.add(GridItem.Folder(it)) }
        }
        val all = filteredEntries
        val images = all.filter { it.type == MediaType.IMAGE }
        val videos = all.filter { it.type == MediaType.VIDEO }
        if (images.isNotEmpty()) {
            items.add(GridItem.Header(MediaType.IMAGE, images.size))
            images.forEach { items.add(GridItem.Media(it)) }
        }
        if (videos.isNotEmpty()) {
            items.add(GridItem.Header(MediaType.VIDEO, videos.size))
            videos.forEach { items.add(GridItem.Media(it)) }
        }
        return items
    }

    fun setFilter(type: FilterType) {
        state = state.copy(filterType = type)
        resetScroll()
    }

    fun setSortType(type: SortType) {
        state = state.copy(sortType = type)
        resetScroll()
    }

    fun setSearchQuery(query: String) {
        state = state.copy(searchQuery = query)
        resetScroll()
    }

    // ----- Folder browsing -------------------------------------------------

    /** Enters a folder; empty string = archive root. */
    fun openFolder(path: String) {
        if (path == state.currentFolder) return
        state = state.copy(currentFolder = path)
        // viewerIndex lives in its own mutableIntStateOf (not GalleryState), so
        // it must be reset explicitly — state.copy(viewerIndex=...) would no-op.
        viewerIndex = 0
        resetScroll()
    }

    /** Goes up one level from the current folder (root stays root). */
    fun navigateUp() {
        val parent = parentFolderPath(state.currentFolder)
        state = state.copy(currentFolder = parent)
        viewerIndex = 0
        resetScroll()
    }

    /** The breadcrumb segments of the current folder (empty = at root). */
    fun currentFolderBreadcrumbs(): List<String> {
        val folder = state.currentFolder
        if (folder.isEmpty()) return emptyList()
        val parts = folder.split('/')
        return parts.runningFold("") { acc, part -> if (acc.isEmpty()) part else "$acc/$part" }
    }

    fun saveScrollState(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    private fun resetScroll() {
        scrollIndex = 0
        scrollOffset = 0
    }

    fun loadArchive(uri: Uri) {
        archivePassword = null
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                // Keep access to this document across restarts so it can appear
                // in the dashboard's recents list. Read+write so edits to ZIP
                // archives can be saved back to the same document.
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Provider may not support persistable grants — recents for
                    // this document just won't survive an app restart.
                } catch (_: IllegalArgumentException) {
                    // Some providers reject non-persistable grants this way.
                }
                cleanupSession()
                sessionDir = File(context.cacheDir, "zipg_${System.nanoTime()}")
                sessionDir!!.mkdirs()
                extractDir = File(sessionDir, "extracted").apply { mkdirs() }
                sessionToken = sessionDir!!.name

                val format = ArchiveFormat.fromUri(uri)
                val ext = if (format == ArchiveFormat.UNKNOWN) "zip" else format.extensions.first()
                // Keep the REAL file name (sanitized) instead of renaming to
                // "archive.<ext>": the Commons Compress reader dispatches on the
                // file NAME (.tar.gz vs .tar vs .tgz). Renaming a .tar.gz to
                // archive.tar would feed raw gzip bytes into the plain-tar path
                // and fail. Fall back to a neutral name only when the original
                // name can't be used.
                val originalName = uri.lastPathSegment?.substringAfterLast('/')?.trim()
                val tempName = originalName
                    ?.takeIf { it.isNotBlank() && ArchiveFormat.fromFileName(it) == format }
                    ?: "archive.$ext"
                val tempFile = File(sessionDir, sanitizeFileName(tempName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempArchiveFile = tempFile
                currentFormat = format

                loadArchiveInternal(tempFile, format, null, uri)
            } catch (e: Exception) {
                android.util.Log.e("ZipGallery", "Error loading archive", e)
                state = state.copy(
                    isLoading = false,
                    error = "Failed to open archive: ${e.message}"
                )
            }
        }
    }

    private fun loadArchiveInternal(file: File, format: ArchiveFormat, password: String?, uri: Uri?) {
        try {
            val reader = when (format) {
                ArchiveFormat.ZIP -> zipReader
                else -> compressReader
            }

            val result = reader.readEntries(file, password)
            result.fold(
                onSuccess = { entries ->
                    // Explicit directory entries + implicit folders derived from
                    // entry paths (many ZIPs store "folder/photo.jpg" with no
                    // "folder/" directory entry) — without both, media inside
                    // folders would be invisible at the root.
                    val folders = collectFolderPaths(
                        entries,
                        reader.readFolders(file, password).getOrDefault(emptyList())
                    )
                    archivePassword = password
                    val displayName = uri?.lastPathSegment ?: file.name
                    state = state.copy(
                        entries = entries,
                        folderPaths = folders,
                        currentFolder = "",
                        isLoading = false,
                        screen = AppScreen.Gallery,
                        currentArchiveUri = uri,
                        showPasswordDialog = false,
                        passwordError = null,
                        archiveName = displayName,
                        searchQuery = "",
                        sortType = SortType.NAME_ASC,
                        filterType = FilterType.ALL,
                        viewerIndex = 0
                    )
                    viewerIndex = 0
                    resetScroll()
                    upsertRecentArchive(uri, displayName)
                    // Extract the whole archive to disk once (single pass, so
                    // 7z/tar don't re-scan per entry), then pre-generate
                    // thumbnails from the local files.
                    preExtractMedia(entries)
                    warmThumbnails(entries)
                },
                onFailure = { e ->
                    when (e) {
                        is ArchiveEncryptedException -> state = state.copy(
                            isLoading = false,
                            showPasswordDialog = true,
                            passwordError = if (password != null) "Wrong password. Try again." else null
                        )
                        else -> {
                            android.util.Log.e("ZipGallery", "Error loading archive", e)
                            state = state.copy(isLoading = false, error = e.message ?: "Failed to open archive")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("ZipGallery", "Error in loadArchiveInternal", e)
            state = state.copy(isLoading = false, error = e.message ?: "Failed to open archive")
        }
    }

    fun loadArchiveWithPassword(password: String) {
        val file = tempArchiveFile ?: return
        state = state.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            loadArchiveInternal(file, currentFormat, password, state.currentArchiveUri)
        }
    }

    fun dismissPasswordDialog() {
        state = state.copy(showPasswordDialog = false, passwordError = null)
    }

    suspend fun getExtractedFile(entry: MediaEntry): File? = withContext(Dispatchers.IO) {
        extractedCache[entry.path]?.let { return@withContext it }
        // Fast path: the background pre-extraction already wrote this file to
        // disk — no decompression needed, just cache and return the path.
        val dir = extractDir
        if (dir != null) {
            val onDisk = File(dir, sanitizeFileName(entry.path))
            if (onDisk.exists()) {
                extractedCache[entry.path] = onDisk
                return@withContext onDisk
            }
        }
        // computeIfAbsent is atomic: only the first caller creates the job, all
        // others share it and await the same result.
        val job = extractJobs.computeIfAbsent(entry.path) {
            viewModelScope.async(Dispatchers.IO) { doExtract(entry) }
        }
        // try/finally guarantees the job leaves the map even if the deferred
        // completes exceptionally, so a failure is never "sticky" — a later
        // request can retry. Success is cached before removal so a racing new
        // request hits the cache fast path instead of starting duplicate work.
        val file = try {
            job.await()
        } finally {
            extractJobs.remove(entry.path)
        }
        if (file != null) {
            extractedCache[entry.path] = file
        }
        file
    }

    private suspend fun doExtract(entry: MediaEntry): File? {
        val archive = tempArchiveFile ?: return null
        return extractSemaphore.withPermit {
            try {
                val reader = when (currentFormat) {
                    ArchiveFormat.ZIP -> zipReader
                    else -> compressReader
                }
                val outDir = extractDir ?: sessionDir ?: return@withPermit null
                reader.extractFile(archive, entry.path, archivePassword, outDir).getOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Background single-pass extraction of every media entry to [extractDir].
     * One pass through the archive (O(n)) instead of N on-demand extractions
     * (O(n²) for 7z/tar). Files land in display order, so visible grid cells
     * hit the disk fast path in [getExtractedFile] almost immediately.
     */
    private fun preExtractMedia(entries: List<MediaEntry>) {
        preExtractJob?.cancel()
        preExtractJob = viewModelScope.launch(Dispatchers.IO) {
            val archive = tempArchiveFile ?: return@launch
            val dir = extractDir ?: return@launch
            val reader = when (currentFormat) {
                ArchiveFormat.ZIP -> zipReader
                else -> compressReader
            }
            reader.extractEntries(archive, entries, archivePassword, dir)
                .onSuccess { extracted -> extractedCache.putAll(extracted) }
                .onFailure { e ->
                    // Individual entries still fall back to on-demand
                    // extraction, but log so a broken pre-pass is visible.
                    android.util.Log.e("ZipGallery", "Bulk pre-extraction failed", e)
                }
        }
    }

    /**
     * Returns the thumbnail file, generating it if needed, and publishes the
     * result to the cell's own state so the UI can render it synchronously.
     */
    suspend fun getThumbnailFile(entry: MediaEntry): File? = withContext(Dispatchers.IO) {
        // Fast path: this path was already attempted (success or failure).
        if (entry.path in attemptedThumbnails) {
            val result = thumbnailCache[entry.path]
            // Publish to the cell's own state (mutableStateOf's structural
            // equality makes a same-value publish a no-op — no recomposition).
            thumbnailStates[entry.path]?.value = result
            return@withContext result
        }
        // computeIfAbsent is atomic: only the first caller creates the job, all
        // others share it and await the same result.
        val job = thumbnailJobs.computeIfAbsent(entry.path) {
            viewModelScope.async(Dispatchers.IO) { generateThumbnail(entry) }
        }
        // try/finally guarantees the job leaves the map even if the deferred
        // completes exceptionally, so a failure is never "sticky" — a later
        // request can retry. Success is cached before removal so a racing new
        // request hits the cache fast path instead of starting duplicate work.
        val file = try {
            job.await()
        } finally {
            thumbnailJobs.remove(entry.path)
        }
        // Mark the attempt (success or failure) so a failed file is never
        // retried on every scroll-back, and publish to the cell's own state.
        attemptedThumbnails.add(entry.path)
        if (file != null) {
            thumbnailCache[entry.path] = file
        }
        thumbnailStates[entry.path]?.value = file
        file
    }

    /**
     * Per-cell observable thumbnail state. Reading the returned [State] in
     * composition observes ONLY this cell's state, so a thumbnail landing here
     * recomposes just this cell — never the whole grid. Lazily created, seeded
     * from any already-recorded result.
     */
    fun thumbnailStateFor(path: String): State<File?> =
        thumbnailStates.computeIfAbsent(path) { mutableStateOf(thumbnailCache[path]) }

    /**
     * Coil cache key for an entry's thumbnail. Prefixed with the session token
     * so entries with the same path in DIFFERENT archives never collide in
     * Coil's app-wide memory/disk cache (reopening a different archive must
     * not show the previous archive's stale thumb).
     */
    fun thumbnailCacheKey(path: String): String =
        if (sessionToken.isEmpty()) path else "$sessionToken/$path"

    /**
     * Ensures a thumbnail for [entry] is available (or being generated) without
     * blocking the caller. Idempotent thanks to [attemptedThumbnails] / single-flight.
     */
    fun ensureThumbnail(entry: MediaEntry) {
        if (entry.path in attemptedThumbnails) return
        viewModelScope.launch(Dispatchers.IO) {
            getThumbnailFile(entry)
        }
    }

    /**
     * Background pre-warm: generates thumbnails in display order so the first
     * screenful (and nearby pages) are ready before the user scrolls to them.
     * Bounded by the extraction semaphore, so it never floods the device.
     *
     * The window is capped: tar/7z scan the whole archive per entry, so warming
     * every entry of a large archive would be quadratic background work. Warming
     * a few screens is enough — the rest generate on demand when scrolled to.
     */
    private fun warmThumbnails(entries: List<MediaEntry>) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch(Dispatchers.IO) {
            // Wait for the bulk extraction so thumbnails decode local files
            // instead of triggering per-entry re-decompression.
            preExtractJob?.join()
            for (i in 0 until minOf(entries.size, WARM_WINDOW)) {
                val entry = entries[i]
                if (entry.path !in attemptedThumbnails) {
                    getThumbnailFile(entry)
                }
                yield()
            }
        }
    }

    private suspend fun generateThumbnail(entry: MediaEntry): File? {
        val extracted = getExtractedFile(entry) ?: return null
        val thumbDir = File(sessionDir, "thumbs")
        thumbDir.mkdirs()
        val safeName = sanitizeFileName(entry.path)

        return when (entry.type) {
            MediaType.IMAGE -> {
                val thumbFile = File(thumbDir, safeName)
                if (thumbFile.exists()) return thumbFile
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(extracted.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, THUMB_SIZE, THUMB_SIZE)
                // RGB_565 halves memory vs ARGB_8888; plenty for a grid cell.
                // But GIF/PNG/WebP carry alpha (and often higher color depth) —
                // decoding them as RGB_565 would wreck transparency with
                // visible dark halos and banding, so keep full color for those.
                options.inPreferredConfig = if (
                    entry.path.substringAfterLast('.', "").lowercase() in TRANSPARENT_EXTS
                ) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                options.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(extracted.absolutePath, options)
                if (bmp != null) {
                    // Enforce the hard size cap even if the sampled decode still
                    // came out oversized (tall/wide images, edge cases) — the
                    // stored thumbnail must never exceed THUMB_SIZE or every
                    // thumb becomes full-res again.
                    val thumb = scaleToFit(bmp, THUMB_SIZE)
                    if (thumb !== bmp) bmp.recycle()
                    thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, thumbFile.outputStream())
                    thumb.recycle()
                    thumbFile
                } else {
                    // Decode failed — return null so the grid shows its placeholder
                    // instead of handing Coil the full-resolution file (which would
                    // decode 100% quality and lag the UI).
                    null
                }
            }

            MediaType.VIDEO -> {
                val thumbFile = File(thumbDir, "vid_$safeName.jpg")
                if (thumbFile.exists()) return thumbFile
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(extracted.absolutePath)
                    val frame = retriever.frameAtTime
                    if (frame != null) {
                        // scaleToFit (same as images) keeps the frame's aspect
                        // ratio — a forced square createScaledBitmap used to
                        // distort every non-square video's stored thumbnail.
                        val scaled = scaleToFit(frame, THUMB_SIZE)
                        if (scaled !== frame) frame.recycle()
                        scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, thumbFile.outputStream())
                        scaled.recycle()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    android.util.Log.e("ZipGallery", "Error loading archive", e)
                }
                if (thumbFile.exists()) thumbFile else null
            }
        }
    }

    /**
     * Downscales [source] so its longest edge is at most [maxSize] px while
     * preserving aspect ratio. Returns the original bitmap if it already fits.
     */
    private fun scaleToFit(source: Bitmap, maxSize: Int): Bitmap {
        if (source.width <= maxSize && source.height <= maxSize) return source
        val scale = maxSize.toFloat() / maxOf(source.width, source.height)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun openViewer(entry: MediaEntry) {
        val index = filteredEntries.indexOf(entry)
        if (index >= 0) {
            viewerIndex = index
            state = state.copy(screen = AppScreen.Viewer, viewerIndex = index)
        }
    }

    /**
     * Selects an entry for the detail pane in two-pane (adaptive) mode without
     * navigating to the full-screen viewer.
     */
    fun selectEntry(entry: MediaEntry) {
        val index = filteredEntries.indexOf(entry)
        if (index >= 0) {
            viewerIndex = index
        }
    }

    /**
     * Syncs the current viewer page (used to keep the two-pane grid highlight
     * in step when the user swipes through the detail pane).
     */
    fun syncViewerPage(index: Int) {
        if (index in 0..filteredEntries.lastIndex) {
            viewerIndex = index
        }
    }

    // ----- Recents (dashboard) ----------------------------------------------

    private fun upsertRecentArchive(uri: Uri?, name: String) {
        if (uri == null) return
        val updated = listOf(RecentArchive(uri = uri, name = name, openedAt = System.currentTimeMillis())) +
            state.recentArchives.filterNot { it.uri == uri }
        val capped = updated.take(MAX_RECENTS)
        state = state.copy(recentArchives = capped)
        persistRecentArchives(capped)
    }

    private fun persistRecentArchives(recents: List<RecentArchive>) {
        try {
            val array = JSONArray()
            recents.forEach { recent ->
                array.put(
                    JSONObject()
                        .put("uri", recent.uri.toString())
                        .put("name", recent.name)
                        .put("openedAt", recent.openedAt)
                )
            }
            prefs.edit().putString("recent_archives", array.toString()).apply()
        } catch (_: Exception) {
            // Corrupt serialization — never block the UI for recents.
        }
    }

    private fun loadRecentArchives(): List<RecentArchive> {
        val raw = prefs.getString("recent_archives", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val uri = Uri.parse(obj.optString("uri"))
                if (uri.toString().isBlank()) null
                else RecentArchive(uri, obj.optString("name", "Archive"), obj.optLong("openedAt", 0L))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ----- Archive editing (ZIP only) ---------------------------------------

    /**
     * Whether the currently open archive can be edited (add files / folders).
     * Only ZIP is supported in place — 7z/tar need a full re-rewrite.
     */
    val supportsWrite: Boolean
        get() = currentFormat == ArchiveFormat.ZIP

    /**
     * Adds the picked files into the current folder of the open ZIP archive,
     * saves the result back to the original document, and refreshes the grid.
     * Runs fully off the main thread.
     */
    fun addFilesToArchive(uris: List<Uri>) {
        if (!supportsWrite) {
            state = state.copy(infoMessage = "Adding files is only supported for ZIP archives")
            return
        }
        if (uris.isEmpty()) return
        state = state.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val archive = tempArchiveFile
                if (archive == null) {
                    state = state.copy(isLoading = false, error = "No archive open")
                    return@launch
                }
                val context = getApplication<Application>()
                val reader = zipReader as Zip4jReader
                // zip4j APPENDS rather than replaces, so adding a file whose
                // entry path already exists would create a duplicate entry.
                // Reject collisions up front (also against folder paths).
                val existingPaths = (state.entries.map { it.path } + state.folderPaths).toHashSet()
                val additions = mutableListOf<Pair<String, File>>()
                var skippedCount = 0
                var savedBack = false
                try {
                    for (uri in uris) {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.nanoTime()}"
                        val safeName = sanitizeFileName(name)
                        val entryPath = if (state.currentFolder.isEmpty()) safeName
                                        else "${state.currentFolder}/$safeName"
                        if (entryPath in existingPaths) {
                            skippedCount++
                            continue
                        }
                        val staged = File(context.cacheDir, "zipg_add_${System.nanoTime()}_$safeName")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            staged.outputStream().use { output -> input.copyTo(output) }
                        } ?: continue
                        additions += entryPath to staged
                        // Also dedupe against names picked elsewhere in the same
                        // multi-select batch.
                        existingPaths.add(entryPath)
                    }
                    if (additions.isEmpty()) {
                        state = state.copy(
                            isLoading = false,
                            infoMessage = if (skippedCount > 0)
                                "No files added — those names already exist in this folder"
                            else
                                "No files were selected"
                        )
                        return@launch
                    }
                    reader.addFiles(archive, additions, archivePassword).getOrThrow()
                    savedBack = writeBackToOriginal(archive)
                } finally {
                    additions.forEach { it.second.delete() }
                }
                reloadEntriesFromDisk()
                state = state.copy(
                    isLoading = false,
                    infoMessage = buildString {
                        append("Added ${additions.size} file(s)")
                        if (skippedCount > 0) append(", skipped $skippedCount (already exist)")
                        if (!savedBack) append(" — but could not save back to the original file")
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("ZipGallery", "Error adding files", e)
                state = state.copy(isLoading = false, error = "Failed to add files: ${e.message}")
            }
        }
    }

    /**
     * Creates an empty folder inside the current folder of the open ZIP
     * archive and refreshes the grid.
     */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            state = state.copy(infoMessage = "Folder name can't be empty or contain '/'")
            return
        }
        if (!supportsWrite) {
            state = state.copy(infoMessage = "Folders can only be added to ZIP archives")
            return
        }
        state = state.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val archive = tempArchiveFile
                if (archive == null) {
                    state = state.copy(isLoading = false, error = "No archive open")
                    return@launch
                }
                val entryPath = if (state.currentFolder.isEmpty()) trimmed
                                else "${state.currentFolder}/$trimmed"
                // Reject collisions up front: an existing folder (or a file
                // whose parent path equals the new folder) would otherwise
                // create duplicate directory entries in the ZIP.
                val alreadyExists = state.folderPaths.contains(entryPath) ||
                    state.entries.any { it.path == entryPath || parentFolderPath(it.path) == entryPath }
                if (alreadyExists) {
                    state = state.copy(
                        isLoading = false,
                        infoMessage = "A folder named \"$trimmed\" already exists here"
                    )
                    return@launch
                }
                (zipReader as Zip4jReader).createFolder(archive, entryPath, archivePassword).getOrThrow()
                val savedBack = writeBackToOriginal(archive)
                reloadEntriesFromDisk()
                state = state.copy(
                    isLoading = false,
                    infoMessage = if (savedBack) "Folder created"
                                  else "Folder created — but could not save back to the original file"
                )
            } catch (e: Exception) {
                android.util.Log.e("ZipGallery", "Error creating folder", e)
                state = state.copy(isLoading = false, error = "Failed to create folder: ${e.message}")
            }
        }
    }

    /**
     * Saves the edited temp archive back over the original document. Returns
     * false (without throwing) when the provider can't be written — the edit
     * is kept for this session but won't survive reopening the file.
     */
    private fun writeBackToOriginal(archive: File): Boolean {
        val uri = state.currentArchiveUri ?: return false
        val context = getApplication<Application>()
        return try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: return false
            output.use { o ->
                archive.inputStream().use { i -> i.copyTo(o) }
            }
            true
        } catch (e: Exception) {
            // Log the real reason — usually a SecurityException because the
            // document was picked without FLAG_GRANT_WRITE_URI_PERMISSION, or
            // the provider simply doesn't support writing.
            android.util.Log.e("ZipGallery", "Could not save archive back to $uri", e)
            false
        }
    }

    /** Re-reads entries + folders from the (edited) temp archive on disk. */
    private fun reloadEntriesFromDisk() {
        val archive = tempArchiveFile ?: return
        // The archive on disk changed — drop cached extracted files, thumbnails,
        // and attempt markers so a newly-added file that happens to collide with
        // an existing path never renders stale content. Caches rebuild on demand.
        extractedCache.clear()
        thumbnailCache.clear()
        attemptedThumbnails.clear()
        thumbnailStates.clear()
        extractJobs.values.forEach { it.cancel() }
        thumbnailJobs.values.forEach { it.cancel() }
        extractJobs.clear()
        thumbnailJobs.clear()
        val reader = if (currentFormat == ArchiveFormat.ZIP) zipReader else compressReader
        val entries = reader.readEntries(archive, archivePassword).getOrDefault(state.entries)
        // Same implicit-folder derivation as the initial load so edits that add
        // files into (existing) folders keep the breadcrumbs/grid consistent.
        val folders = collectFolderPaths(
            entries,
            reader.readFolders(archive, archivePassword).getOrDefault(state.folderPaths)
        )
        // Keep browsing inside the current folder if it still exists.
        val folderStillExists = state.currentFolder.isEmpty() ||
            folders.any { it == state.currentFolder || it.startsWith(state.currentFolder + "/") }
        state = state.copy(
            entries = entries,
            folderPaths = folders,
            currentFolder = if (folderStillExists) state.currentFolder else ""
        )
        // Warm thumbnails for the newly-added files in the background.
        warmThumbnails(entries)
    }

    fun clearInfoMessage() {
        state = state.copy(infoMessage = null)
    }

    /**
     * Deletes the given entry paths from the open ZIP archive (media files
     * and/or folders; folder paths remove their whole subtree), saves the
     * result back to the original document, and refreshes the grid.
     */
    fun deleteFromArchive(paths: List<String>) {
        if (paths.isEmpty()) return
        if (!supportsWrite) {
            state = state.copy(infoMessage = "Deleting is only supported for ZIP archives")
            return
        }
        state = state.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val archive = tempArchiveFile
                if (archive == null) {
                    state = state.copy(isLoading = false, error = "No archive open")
                    return@launch
                }
                (zipReader as Zip4jReader).deleteEntries(archive, paths, archivePassword).getOrThrow()
                val savedBack = writeBackToOriginal(archive)
                reloadEntriesFromDisk()
                state = state.copy(
                    isLoading = false,
                    infoMessage = if (savedBack) "Deleted ${paths.size} item(s)"
                                  else "Deleted — but could not save back to the original file"
                )
            } catch (e: Exception) {
                android.util.Log.e("ZipGallery", "Error deleting entries", e)
                state = state.copy(isLoading = false, error = "Failed to delete: ${e.message}")
            }
        }
    }

    fun backToGallery() {
        state = state.copy(screen = AppScreen.Gallery)
    }

    fun backToMain() {
        state = state.copy(screen = AppScreen.Main)
        cleanupSession()
    }

    fun openSettings() {
        val prev = when (state.screen) {
            AppScreen.Settings -> AppScreen.Main
            else -> state.screen
        }
        state = state.copy(screen = AppScreen.Settings)
        _previousScreen = prev
    }

    private var _previousScreen: AppScreen = AppScreen.Main

    fun backFromSettings() {
        state = state.copy(screen = _previousScreen)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    suspend fun shareFile(entry: MediaEntry): File? {
        return getExtractedFile(entry)
    }

    val cacheSize: Long
        get() {
            val dir = sessionDir ?: return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    /**
     * Whether an archive session (temp file + extracted files + thumbnails)
     * is currently active. Clearing the cache while one is active would tear
     * down the very files the open archive relies on, silently breaking the
     * gallery — so the UI must not offer a destructive clear then.
     */
    val hasActiveSession: Boolean
        get() = sessionDir != null && !state.entries.isEmpty()

    fun clearCache() {
        cleanupSession()
    }

    private fun cleanupSession() {
        // Cancel in-flight work so no coroutine keeps extracting after the
        // session is torn down, then drop the maps.
        preExtractJob?.cancel()
        preExtractJob = null
        warmJob?.cancel()
        warmJob = null
        extractJobs.values.forEach { it.cancel() }
        thumbnailJobs.values.forEach { it.cancel() }
        extractJobs.clear()
        thumbnailJobs.clear()
        extractDir = null
        sessionDir?.deleteRecursively()
        sessionDir = null
        tempArchiveFile = null
        archivePassword = null
        currentFormat = ArchiveFormat.UNKNOWN
        extractedCache.clear()
        thumbnailCache.clear()
        attemptedThumbnails.clear()
        thumbnailStates.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cleanupSession()
    }

    companion object {
        /** How many recent archives the dashboard keeps. */
        private const val MAX_RECENTS = 8

        /**
         * Thumbnail edge length in px — plenty for a ~120dp grid cell. Shared
         * with the UI so Coil's decode request always matches the generated
         * thumbnails (no upscaling or accidental full-res re-decodes).
         */
        const val THUMB_SIZE = 256

        /** JPEG quality for the generated thumbnail files (0-100). */
        private const val THUMB_QUALITY = 75

        /** Formats that can carry alpha/transparency — decode at full color depth. */
        private val TRANSPARENT_EXTS = setOf("png", "gif", "webp")

        /** How many archive extractions may run concurrently during scroll. */
        private const val MAX_CONCURRENT_EXTRACTIONS = 2

        /**
         * How many thumbnails the background pre-warm generates before stopping.
         * ~3 screens of a 4-column grid; the rest generate on demand on scroll.
         */
        private const val WARM_WINDOW = 96

        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            if (width <= reqWidth && height <= reqHeight) return 1
            // Downsample on the DOMINANT axis: the classic both-dimensions
            // variant returns 1 for tall/wide images (only one dimension too
            // big), which produced full-resolution thumbnails. Scaling by the
            // larger dimension keeps the longest edge near the target instead.
            val target = maxOf(reqWidth, reqHeight)
            var inSampleSize = 1
            while (maxOf(width / inSampleSize, height / inSampleSize) > target) {
                inSampleSize *= 2
            }
            return inSampleSize
        }
    }
}
