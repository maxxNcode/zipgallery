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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf(GalleryState())
        private set
    var viewerIndex by mutableIntStateOf(0)
        private set

    private var sessionDir: File? = null
    private var tempArchiveFile: File? = null
    private var archivePassword: String? = null
    private var currentFormat: ArchiveFormat = ArchiveFormat.UNKNOWN
    private val extractedCache = mutableMapOf<String, File>()
    private val thumbnailCache = mutableMapOf<String, File>()

    private val zipReader: ArchiveReader = Zip4jReader()
    private val compressReader: ArchiveReader = CommonsCompressReader()

    private val prefs by lazy { getApplication<Application>().getSharedPreferences("zipgallery_prefs", Context.MODE_PRIVATE) }

    init {
        val savedTheme = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)
        if (savedTheme != null) {
            state = state.copy(themeMode = AppThemeMode.valueOf(savedTheme))
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        state = state.copy(themeMode = mode)
        prefs.edit().putString("theme_mode", mode.name).apply()
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
                        filterType = FilterType.ALL
                    )
                    pregenerateThumbnails(entries)
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

    private fun pregenerateThumbnails(entries: List<MediaEntry>) {
        viewModelScope.launch(Dispatchers.IO) {
            entries.forEach { entry ->
                getThumbnailFile(entry)
                yield()
            }
        }
    }

    suspend fun getExtractedFile(entry: MediaEntry): File? = withContext(Dispatchers.IO) {
        extractedCache[entry.path]?.let { return@withContext it }
        val archive = tempArchiveFile ?: return@withContext null
        try {
            val reader = when (currentFormat) {
                ArchiveFormat.ZIP -> zipReader
                else -> compressReader
            }
            val result = reader.extractFile(archive, entry.path, archivePassword, sessionDir!!)
            result.fold(
                onSuccess = { file ->
                    extractedCache[entry.path] = file
                    file
                },
                onFailure = { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getThumbnailFile(entry: MediaEntry): File? = withContext(Dispatchers.IO) {
        thumbnailCache[entry.path]?.let { return@withContext it }
        val extracted = getExtractedFile(entry) ?: return@withContext null
        val thumbDir = File(sessionDir, "thumbs")
        thumbDir.mkdirs()
        val safeName = sanitizeFileName(entry.path)

        when (entry.type) {
            MediaType.IMAGE -> {
                val thumbFile = File(thumbDir, safeName)
                if (thumbFile.exists()) {
                    thumbnailCache[entry.path] = thumbFile
                    return@withContext thumbFile
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(extracted.absolutePath, options)
                val targetSize = 512
                options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
                options.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(extracted.absolutePath, options)
                if (bmp != null) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, thumbFile.outputStream())
                    bmp.recycle()
                    thumbnailCache[entry.path] = thumbFile
                    return@withContext thumbFile
                }
                extracted
            }

            MediaType.VIDEO -> {
                val thumbFile = File(thumbDir, "vid_$safeName.jpg")
                if (thumbFile.exists()) {
                    thumbnailCache[entry.path] = thumbFile
                    return@withContext thumbFile
                }
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(extracted.absolutePath)
                    val frame = retriever.frameAtTime
                    if (frame != null) {
                        val scaled = Bitmap.createScaledBitmap(frame, 512, 512, true)
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, thumbFile.outputStream())
                        scaled.recycle()
                        frame.recycle()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    android.util.Log.e("ZipGallery", "Error loading archive", e)
                }
                if (thumbFile.exists()) {
                    thumbnailCache[entry.path] = thumbFile
                    thumbFile
                } else {
                    extracted
                }
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
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "ts", "m4v")

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
