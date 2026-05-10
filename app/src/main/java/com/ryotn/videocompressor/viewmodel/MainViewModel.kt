package com.ryotn.videocompressor.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ryotn.videocompressor.data.CompressionOptions
import com.ryotn.videocompressor.data.CompressionState
import com.ryotn.videocompressor.data.VideoInfo
import com.ryotn.videocompressor.service.CompressionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("video_compressor_prefs", Context.MODE_PRIVATE)

    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo

    private val _compressionOptions = MutableStateFlow(CompressionOptions())
    val compressionOptions: StateFlow<CompressionOptions> = _compressionOptions

    private val _saveDirectoryUri = MutableStateFlow(loadSavedDirectoryUri())
    val saveDirectoryUri: StateFlow<Uri?> = _saveDirectoryUri

    private val _saveDirectoryLabel = MutableStateFlow(resolveSaveDirectoryLabel(_saveDirectoryUri.value))
    val saveDirectoryLabel: StateFlow<String?> = _saveDirectoryLabel

    private val _compressionState = MutableStateFlow<CompressionState>(CompressionState.Idle)
    val compressionState: StateFlow<CompressionState> = _compressionState

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CompressionService.BROADCAST_PROGRESS -> {
                    val progress = intent.getFloatExtra(CompressionService.EXTRA_PROGRESS, 0f)
                    _compressionState.value = CompressionState.InProgress(progress * 100f, 0L)
                }
                CompressionService.BROADCAST_COMPLETE -> {
                    val outputPath = intent.getStringExtra(CompressionService.EXTRA_OUTPUT_PATH) ?: ""
                    val outputSize = intent.getStringExtra(CompressionService.EXTRA_OUTPUT_SIZE)?.toLongOrNull() ?: 0L
                    val originalSize = _videoInfo.value?.sizeBytes ?: 0L
                    _compressionState.value = CompressionState.Completed(outputPath, originalSize, outputSize)
                }
                CompressionService.BROADCAST_FAILED -> {
                    val error = intent.getStringExtra(CompressionService.EXTRA_ERROR) ?: "Unknown error"
                    _compressionState.value = CompressionState.Failed(error)
                }
                CompressionService.BROADCAST_CANCELLED -> {
                    _compressionState.value = CompressionState.Cancelled
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(CompressionService.BROADCAST_PROGRESS)
            addAction(CompressionService.BROADCAST_COMPLETE)
            addAction(CompressionService.BROADCAST_FAILED)
            addAction(CompressionService.BROADCAST_CANCELLED)
        }
        LocalBroadcastManager.getInstance(application).registerReceiver(broadcastReceiver, filter)
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                extractVideoInfo(uri)
            }
            _videoInfo.value = info
            _compressionState.value = CompressionState.Idle
        }
    }

    private fun extractVideoInfo(uri: Uri): VideoInfo? {
        val context = getApplication<Application>()
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            var displayName = "video.mp4"
            var sizeBytes = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex) ?: "video.mp4"
                    }
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val extractor = MediaExtractor()
            val audioBitrateBps: Long
            val frameRateFps: Float
            try {
                extractor.setDataSource(context, uri, null)
                audioBitrateBps = extractAudioBitrate(extractor)
                frameRateFps = extractVideoFrameRate(extractor)
            } finally {
                extractor.release()
            }
            retriever.release()
            // If the video has a 90° or 270° rotation tag, the coded dimensions are swapped
            // relative to the display dimensions. Store display dimensions so the UI shows
            // the correct portrait/landscape orientation.
            val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270) height to width else width to height
            VideoInfo(uri, displayName, sizeBytes, durationMs, displayWidth, displayHeight, bitrate, audioBitrateBps, frameRateFps)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVideoFrameRate(extractor: MediaExtractor): Float {
        return try {
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) }
                }
            }
            0f
        } catch (_: Exception) {
            0f
        }
    }

    private fun extractAudioBitrate(extractor: MediaExtractor): Long {
        return try {
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    return format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                }
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    fun updateOptions(options: CompressionOptions) {
        _compressionOptions.value = options
    }

    fun updateSaveDirectory(uri: Uri) {
        prefs.edit().putString(KEY_SAVE_DIRECTORY_URI, uri.toString()).apply()
        _saveDirectoryUri.value = uri
        _saveDirectoryLabel.value = resolveSaveDirectoryLabel(uri)
    }

    fun startCompression() {
        val info = _videoInfo.value ?: return
        val options = _compressionOptions.value
        val saveDirectoryUri = _saveDirectoryUri.value ?: return
        val context = getApplication<Application>()
        val intent = Intent(context, CompressionService::class.java).apply {
            action = CompressionService.ACTION_START
            putExtra(CompressionService.EXTRA_SOURCE_URI, info.uri.toString())
            putExtra(CompressionService.EXTRA_OUTPUT_DIRECTORY_URI, saveDirectoryUri.toString())
            putExtra(CompressionService.EXTRA_OPTIONS, options)
            putExtra(CompressionService.EXTRA_DURATION_MS, info.durationMs)
            putExtra(CompressionService.EXTRA_ORIGINAL_BITRATE, info.bitrateBps)
            putExtra(CompressionService.EXTRA_ORIGINAL_AUDIO_BITRATE, info.audioBitrateBps)
            putExtra(CompressionService.EXTRA_ORIGINAL_WIDTH, info.width)
            putExtra(CompressionService.EXTRA_ORIGINAL_HEIGHT, info.height)
            putExtra(CompressionService.EXTRA_ORIGINAL_SIZE, info.sizeBytes)
        }
        try {
            context.startForegroundService(intent)
            _compressionState.value = CompressionState.InProgress(0f, 0L)
        } catch (e: Exception) {
            _compressionState.value = CompressionState.Failed("Failed to start service: ${e.message}")
        }
    }

    fun cancelCompression() {
        val context = getApplication<Application>()
        val intent = Intent(context, CompressionService::class.java).apply {
            action = CompressionService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun resetState() {
        _compressionState.value = CompressionState.Idle
    }

    fun setCompressionCompleted(outputPath: String, originalSize: Long, outputSize: Long) {
        _compressionState.value = CompressionState.Completed(outputPath, originalSize, outputSize)
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(broadcastReceiver)
    }

    private fun loadSavedDirectoryUri(): Uri? {
        val uriString = prefs.getString(KEY_SAVE_DIRECTORY_URI, null) ?: return null
        val uri = Uri.parse(uriString)
        val context = getApplication<Application>()
        val directory = DocumentFile.fromTreeUri(context, uri)
        return if (directory != null && directory.canWrite()) uri else null
    }

    private fun resolveSaveDirectoryLabel(uri: Uri?): String? {
        if (uri == null) return null
        val context = getApplication<Application>()
        val documentFile = DocumentFile.fromTreeUri(context, uri)
        if (documentFile?.name != null) {
            return documentFile.name
        }
        return runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val KEY_SAVE_DIRECTORY_URI = "save_directory_uri"
    }
}
