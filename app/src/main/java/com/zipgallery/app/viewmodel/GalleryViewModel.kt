package com.zipgallery.app.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf(GalleryState())
        private set
    var viewerIndex by mutableIntStateOf(0)
        private set

    private var sessionDir: File? = null
    private var tempArchiveFile: File? = null
    private var archivePassword: String? = null
    private var currentFormat: ArchiveFormat = ArchiveFormat.UNKNOWN
    // Concurrent maps: thumbnail requests fire from many IO coroutines (grid
    // cells + viewer) at once, so plain mutable maps could corrupt under race.
    private val extractedCache = ConcurrentHashMap<String, File>()
    private val thumbnailCache = ConcurrentHashMap<String, File>()

    // Single-flight maps: every concurrent caller for the same entry awaits one
    // shared Deferred (created atomically by computeIfAbsent), so a file is
    // extracted / thumbnail generated exactly once even under heavy scroll.
    private val extractJobs = ConcurrentHashMap<String, Deferred<File?>>()
    private val thumbnailJobs = ConcurrentHashMap<String, Deferred<File?>>()

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

    val gridItems: List<GridItem>
        get() {
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
        state = state.copy(filterType = type, scrollIndex = 0, scrollOffset = 0)
    }

    fun setSortType(type: SortType) {
        state = state.copy(sortType = type, scrollIndex = 0, scrollOffset = 0)
    }

    fun setSearchQuery(query: String) {
        state = state.copy(searchQuery = query, scrollIndex = 0, scrollOffset = 0)
    }

    fun saveScrollState(index: Int, offset: Int) {
        state = state.copy(scrollIndex = index, scrollOffset = offset)
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
                        scrollIndex = 0,
                        scrollOffset = 0,
                        showPasswordDialog = false,
                        passwordError = null,
                        archiveName = displayName,
                        searchQuery = "",
                        sortType = SortType.NAME_ASC,
                        filterType = FilterType.ALL,
                        viewerIndex = 0
                    )
                    viewerIndex = 0
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
        return try {
            val reader = when (currentFormat) {
                ArchiveFormat.ZIP -> zipReader
                else -> compressReader
            }
            reader.extractFile(archive, entry.path, archivePassword, sessionDir!!).getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getThumbnailFile(entry: MediaEntry): File? = withContext(Dispatchers.IO) {
        thumbnailCache[entry.path]?.let { return@withContext it }
        val job = thumbnailJobs.computeIfAbsent(entry.path) {
            viewModelScope.async(Dispatchers.IO) { generateThumbnail(entry) }
        }
        val file = try {
            job.await()
        } finally {
            thumbnailJobs.remove(entry.path)
        }
        if (file != null) {
            thumbnailCache[entry.path] = file
        }
        file
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
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, thumbFile.outputStream())
                    bmp.recycle()
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
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, thumbFile.outputStream())
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
        extractJobs.values.forEach { it.cancel() }
        thumbnailJobs.values.forEach { it.cancel() }
        extractJobs.clear()
        thumbnailJobs.clear()
        sessionDir?.deleteRecursively()
        sessionDir = null
        tempArchiveFile = null
        archivePassword = null
        currentFormat = ArchiveFormat.UNKNOWN
        extractedCache.clear()
        thumbnailCache.clear()
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

        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
