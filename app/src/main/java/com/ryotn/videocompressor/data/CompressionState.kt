package com.ryotn.videocompressor.data

sealed class CompressionState {
    object Idle : CompressionState()
    object Preparing : CompressionState()
    data class InProgress(val progressPercent: Float, val elapsedMs: Long) : CompressionState()
    data class Completed(val outputPath: String, val originalSizeBytes: Long, val outputSizeBytes: Long) : CompressionState()
    data class Failed(val error: String) : CompressionState()
    object Cancelled : CompressionState()
}
