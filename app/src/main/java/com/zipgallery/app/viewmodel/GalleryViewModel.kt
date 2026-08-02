package com.zipgallery.app.viewmodel

import android.app.Application
import android.content.Context
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
import com.zipgallery.app.model.ArchiveFormat
import com.zipgallery.app.model.FilterType
import com.zipgallery.app.model.GalleryState
import com.zipgallery.app.model.GridItem
import com.zipgallery.app.model.MediaEntry
import com.zipgallery.app.model.MediaType
import com.zipgallery.app.model.SortType
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
    }

    fun setThemeMode(mode: AppThemeMode) {
        state = state.copy(themeMode = mode)
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        state = state.copy(useDynamicColor = enabled)
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    val filteredEntries: List<MediaEntry>
        get() {
            var list = state.entries
            list = when (state.filterType) {
                FilterType.ALL -> list
                FilterType.IMAGES -> list.filter { it.type == MediaType.IMAGE }
                FilterType.VIDEOS -> list.filter { it.type == MediaType.VIDEO }
            }
            if (state.searchQuery.isNotBlank()) {
                val q = state.searchQuery.lowercase()
                list = list.filter { it.name.lowercase().contains(q) }
            }
            list = when (state.sortType) {
                SortType.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                SortType.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                SortType.SIZE_DESC -> list.sortedByDescending { it.size }
                SortType.SIZE_ASC -> list.sortedBy { it.size }
                SortType.TYPE -> list.sortedBy { it.type.name }
            }
            return list
        }

    /**
     * Grid layout memoized with [derivedStateOf]: the filter/sort pipeline only
     * recomputes when [state] actually changes (load, filter, sort, search),
     * never on every scroll-frame recomposition.
     */
    val gridItems: List<GridItem> by derivedStateOf { buildGridItems() }

    private fun buildGridItems(): List<GridItem> {
        val items = mutableListOf<GridItem>()
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
                cleanupSession()
                sessionDir = File(context.cacheDir, "zipg_${System.nanoTime()}")
                sessionDir!!.mkdirs()
                extractDir = File(sessionDir, "extracted").apply { mkdirs() }

                val format = ArchiveFormat.fromUri(uri)
                val ext = if (format == ArchiveFormat.UNKNOWN) "zip" else format.extensions.first()
                val tempFile = File(sessionDir, "archive.$ext")
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
                    archivePassword = password
                    val displayName = uri?.lastPathSegment ?: file.name
                    state = state.copy(
                        entries = entries,
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
                // RGB_565 halves memory vs ARGB_8888; plenty for a grid cell and
                // avoids the alpha channel, which we don't need for a JPEG thumb.
                options.inPreferredConfig = Bitmap.Config.RGB_565
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
                        val scaled = Bitmap.createScaledBitmap(frame, THUMB_SIZE, THUMB_SIZE, true)
                        scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, thumbFile.outputStream())
                        scaled.recycle()
                        frame.recycle()
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
        /**
         * Thumbnail edge length in px — plenty for a ~120dp grid cell. Shared
         * with the UI so Coil's decode request always matches the generated
         * thumbnails (no upscaling or accidental full-res re-decodes).
         */
        const val THUMB_SIZE = 256

        /** JPEG quality for the generated thumbnail files (0-100). */
        private const val THUMB_QUALITY = 75

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
