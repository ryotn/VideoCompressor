package com.ryotn.videocompressor.data

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrateBps: Long,
    val audioBitrateBps: Long
) {
    val displaySize: String get() {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.1f MB".format(mb)
    }
    val displayDuration: String get() {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
    val displayResolution: String get() = "${width}×${height}"
    val displayBitrate: String get() = "%.1f Mbps".format(bitrateBps / 1_000_000.0)
    val displayAudioBitrate: String get() = "%.0f kbps".format(audioBitrateBps / 1_000.0)
}
