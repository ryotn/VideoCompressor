package com.ryotn.videocompressor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ryotn.videocompressor.MainActivity
import com.ryotn.videocompressor.R
import com.ryotn.videocompressor.data.CompressionOptions
import com.ryotn.videocompressor.util.VideoTranscoder
import java.io.File
import java.io.FileOutputStream

class CompressionService : Service() {

    companion object {
        const val ACTION_START = "com.ryotn.videocompressor.ACTION_START"
        const val ACTION_CANCEL = "com.ryotn.videocompressor.ACTION_CANCEL"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_ORIGINAL_BITRATE = "original_bitrate"
        const val EXTRA_ORIGINAL_AUDIO_BITRATE = "original_audio_bitrate"
        const val EXTRA_ORIGINAL_WIDTH = "original_width"
        const val EXTRA_ORIGINAL_HEIGHT = "original_height"
        const val EXTRA_ORIGINAL_SIZE = "original_size"
        const val BROADCAST_PROGRESS = "com.ryotn.videocompressor.PROGRESS"
        const val BROADCAST_COMPLETE = "com.ryotn.videocompressor.COMPLETE"
        const val BROADCAST_FAILED = "com.ryotn.videocompressor.FAILED"
        const val BROADCAST_CANCELLED = "com.ryotn.videocompressor.CANCELLED"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_OUTPUT_SIZE = "output_size"
        const val EXTRA_ERROR = "error"
        private const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "compression_channel"
        private const val COMPLETE_CHANNEL_ID = "compression_complete_channel"
        private const val TAG = "CompressionService"
    }

    @Volatile private var transcoder: VideoTranscoder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceUriStr = intent.getStringExtra(EXTRA_SOURCE_URI) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
                val sourceUri = Uri.parse(sourceUriStr)
                val outputDirectoryUri = intent.getStringExtra(EXTRA_OUTPUT_DIRECTORY_URI)?.let(Uri::parse) ?: run {
                    sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "保存先フォルダが選択されていません"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                val originalBitrate = intent.getLongExtra(EXTRA_ORIGINAL_BITRATE, 0L)
                val originalAudioBitrate = intent.getLongExtra(EXTRA_ORIGINAL_AUDIO_BITRATE, 0L)
                val originalSize = intent.getLongExtra(EXTRA_ORIGINAL_SIZE, 0L)
                val options = intent.getSerializableExtra(EXTRA_OPTIONS, CompressionOptions::class.java)
                    ?: CompressionOptions()

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(0f),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                startCompression(sourceUri, outputDirectoryUri, options, durationMs, originalBitrate, originalAudioBitrate, originalSize)
            }
            ACTION_CANCEL -> {
                transcoder?.isCancelled = true
            }
        }
        return START_NOT_STICKY
    }

    private fun startCompression(
        sourceUri: Uri,
        outputDirectoryUri: Uri,
        options: CompressionOptions,
        durationMs: Long,
        originalBitrate: Long,
        originalAudioBitrate: Long,
        originalSize: Long
    ) {
        val thread = Thread {
            var inputFile: File? = null
            var tempOutputFile: File? = null

            try {
                inputFile = File.createTempFile("input_", ".mp4", cacheDir)
                tempOutputFile = File.createTempFile("compressed_", ".mp4", cacheDir)

                // Copy source URI to a local temp file so MediaExtractor can read it
                try {
                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(inputFile!!).use { output ->
                            input.copyTo(output)
                        }
                    } ?: run {
                        sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "入力ファイルを開けませんでした"))
                        return@Thread
                    }
                } catch (e: Exception) {
                    sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "ファイルのコピーに失敗しました: ${e.message}"))
                    return@Thread
                }

                val t = VideoTranscoder(
                    inputFile = inputFile!!,
                    outputFile = tempOutputFile!!,
                    options = options,
                    originalBitrate = originalBitrate,
                    originalAudioBitrate = originalAudioBitrate,
                    durationUs = durationMs * 1000L,
                    onProgress = { progress ->
                        updateNotification(progress)
                        val broadcastIntent = Intent(BROADCAST_PROGRESS).apply {
                            putExtra(EXTRA_PROGRESS, progress)
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
                    }
                )
                transcoder = t

                val success = try {
                    t.transcode()
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected transcoding error", e)
                    false
                }

                when {
                    t.isCancelled -> {
                        sendBroadcastMsg(BROADCAST_CANCELLED, emptyMap())
                    }
                    success -> {
                        val savedOutput = copyOutputToUserDirectory(sourceUri, outputDirectoryUri, tempOutputFile!!)
                        if (savedOutput != null) {
                            val outputPath = savedOutput.first
                            val outputSize = savedOutput.second

                            sendBroadcastMsg(
                                BROADCAST_COMPLETE, mapOf(
                                    EXTRA_OUTPUT_PATH to outputPath,
                                    EXTRA_OUTPUT_SIZE to outputSize.toString()
                                )
                            )
                            showCompletionNotification(outputPath, originalSize, outputSize)
                        } else {
                            sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "保存先フォルダへの書き込みに失敗しました"))
                        }
                    }
                    else -> {
                        sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "圧縮に失敗しました"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Compression thread failed", e)
                sendBroadcastMsg(BROADCAST_FAILED, mapOf(EXTRA_ERROR to "予期しないエラーが発生しました: ${e.message}"))
            } finally {
                inputFile?.delete()
                tempOutputFile?.delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        thread.name = "VideoCompressionThread"
        thread.start()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        nm.createNotificationChannel(progressChannel)

        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID,
            getString(R.string.notification_channel_complete_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_complete_description)
        }
        nm.createNotificationChannel(completeChannel)
    }

    private fun buildNotification(progress: Float): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val progressInt = (progress * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.compression_progress))
            .setContentText(getString(R.string.progress_percentage, progressInt))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setProgress(100, progressInt, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun showCompletionNotification(outputPath: String, originalSize: Long, outputSize: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_completion", true)
            putExtra(EXTRA_OUTPUT_PATH, outputPath)
            putExtra(EXTRA_ORIGINAL_SIZE, originalSize)
            putExtra(EXTRA_OUTPUT_SIZE, outputSize)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle(getString(R.string.compression_complete))
            .setContentText(getString(R.string.save_destination, outputPath))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun sendBroadcastMsg(action: String, extras: Map<String, String>) {
        val intent = Intent(action)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyOutputToUserDirectory(
        sourceUri: Uri,
        outputDirectoryUri: Uri,
        tempOutputFile: File
    ): Pair<String, Long>? {
        val directory = DocumentFile.fromTreeUri(this, outputDirectoryUri)
            ?.takeIf { it.exists() && it.canWrite() }
            ?: return null
        val outputName = buildOutputFileName(sourceUri)
        val outputFile = directory.createFile("video/mp4", outputName) ?: return null
        return try {
            contentResolver.openOutputStream(outputFile.uri, "w")?.use { output ->
                tempOutputFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return null
            (outputFile.name ?: outputFile.uri.toString()) to tempOutputFile.length()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save output file", e)
            outputFile.delete()
            null
        }
    }

    private fun buildOutputFileName(sourceUri: Uri): String {
        val sourceName = DocumentFile.fromSingleUri(this, sourceUri)?.name
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "compressed_video"
        return "${sourceName}_compressed_${System.currentTimeMillis()}.mp4"
    }

    override fun onDestroy() {
        super.onDestroy()
        transcoder?.isCancelled = true
    }
}
