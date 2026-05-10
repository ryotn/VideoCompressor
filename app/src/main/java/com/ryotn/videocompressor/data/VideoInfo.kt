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
)
