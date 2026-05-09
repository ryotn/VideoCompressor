package com.ryotn.videocompressor.data

import java.io.Serializable

enum class BitrateMode { PERCENTAGE, DIRECT, PRESET }
enum class ResolutionMode { PERCENTAGE, DIRECT, PRESET }
enum class FrameRateMode { DIRECT, PRESET }

enum class BitratePreset(val labelJa: String, val kbps: Int) {
    LOW("低品質 (500 kbps)", 500),
    MEDIUM("中品質 (1,500 kbps)", 1500),
    HIGH("高品質 (3,000 kbps)", 3000),
    VERY_HIGH("最高品質 (6,000 kbps)", 6000)
}

enum class ResolutionPreset(val baseNameJa: String, val shortSide: Int) {
    SD("SD", 480),
    HD("HD", 720),
    FHD("FHD", 1080),
    QHD("QHD", 1440);

    fun getDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) {
            // Fallback for 16:9 if original dimensions are invalid
            val longSide = (shortSide * 16.0 / 9.0).toInt()
            return Pair(longSide, shortSide)
        }
        val isLandscape = srcW >= srcH
        val originalLongSide = if (isLandscape) srcW else srcH
        val originalShortSide = if (isLandscape) srcH else srcW

        val calculatedLongSide = (originalLongSide * (shortSide.toFloat() / originalShortSide)).toInt()

        val w = if (isLandscape) calculatedLongSide else shortSide
        val h = if (isLandscape) shortSide else calculatedLongSide

        // Ensure even dimensions
        val evenW = w / 2 * 2
        val evenH = h / 2 * 2

        return Pair(evenW, evenH)
    }

    fun getLabelJa(srcW: Int, srcH: Int): String {
        val (w, h) = getDimensions(srcW, srcH)
        return "$baseNameJa (${w}×${h})"
    }
}

enum class FrameRatePreset(val labelJa: String, val fps: Int) {
    CINEMA("シネマ (24 fps)", 24),
    STANDARD("標準 (30 fps)", 30),
    SMOOTH("なめらか (60 fps)", 60)
}

enum class AudioBitratePreset(val labelJa: String, val kbps: Int) {
    LOW("低品質 (64 kbps)", 64),
    MEDIUM("中品質 (128 kbps)", 128),
    HIGH("高品質 (192 kbps)", 192),
    VERY_HIGH("最高品質 (256 kbps)", 256)
}

data class CompressionOptions(
    val bitrateMode: BitrateMode = BitrateMode.PRESET,
    val bitratePercentage: Int = 50,
    val bitrateDirectKbps: Int = 2000,
    val bitratePreset: BitratePreset = BitratePreset.MEDIUM,
    val audioBitrateMode: BitrateMode = BitrateMode.PRESET,
    val audioBitratePercentage: Int = 100,
    val audioBitrateDirectKbps: Int = 128,
    val audioBitratePreset: AudioBitratePreset = AudioBitratePreset.MEDIUM,
    val frameRateMode: FrameRateMode = FrameRateMode.PRESET,
    val frameRateDirectFps: Int = 30,
    val frameRatePreset: FrameRatePreset = FrameRatePreset.STANDARD,
    val resolutionMode: ResolutionMode = ResolutionMode.PRESET,
    val resolutionPercentage: Int = 100,
    val resolutionKeepAspectRatio: Boolean = true,
    val resolutionDirectWidth: Int = 1280,
    val resolutionDirectHeight: Int = 720,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.HD
) : Serializable
