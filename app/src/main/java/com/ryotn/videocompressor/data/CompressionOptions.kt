package com.ryotn.videocompressor.data

import androidx.annotation.StringRes
import com.ryotn.videocompressor.R
import java.io.Serializable

enum class BitrateMode { PERCENTAGE, DIRECT, PRESET }
enum class ResolutionMode { PERCENTAGE, DIRECT, PRESET }
enum class FrameRateMode { PERCENTAGE, DIRECT, PRESET }

enum class BitratePreset(@StringRes val labelResId: Int, val kbps: Int) {
    LOW(R.string.preset_low, 500),
    MEDIUM(R.string.preset_medium, 1500),
    HIGH(R.string.preset_high, 3000),
    VERY_HIGH(R.string.preset_very_high, 6000)
}

enum class ResolutionPreset(@StringRes val labelResId: Int, val width: Int, val height: Int) {
    SD(R.string.preset_sd, 854, 480),
    HD(R.string.preset_hd, 1280, 720),
    FHD(R.string.preset_fhd, 1920, 1080),
    QHD(R.string.preset_qhd, 2560, 1440)
}

enum class FrameRatePreset(@StringRes val labelResId: Int, val fps: Int) {
    CINEMA(R.string.preset_cinema, 24),
    STANDARD(R.string.preset_standard, 30),
    SMOOTH(R.string.preset_smooth, 60)
}

enum class AudioBitratePreset(@StringRes val labelResId: Int, val kbps: Int) {
    LOW(R.string.preset_low, 64),
    MEDIUM(R.string.preset_medium, 128),
    HIGH(R.string.preset_high, 192),
    VERY_HIGH(R.string.preset_very_high, 256)
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
    val frameRatePercentage: Int = 100,
    val frameRateDirectFps: Int = 30,
    val frameRatePreset: FrameRatePreset = FrameRatePreset.STANDARD,
    val resolutionMode: ResolutionMode = ResolutionMode.PRESET,
    val resolutionPercentage: Int = 100,
    val resolutionDirectWidth: Int = 1280,
    val resolutionDirectHeight: Int = 720,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.HD
) : Serializable {
    fun computeTargetFrameRateFps(sourceFrameRate: Float): Int {
        return when (frameRateMode) {
            FrameRateMode.PERCENTAGE -> (sourceFrameRate * (frameRatePercentage / 100f)).toInt()
            FrameRateMode.DIRECT -> frameRateDirectFps
            FrameRateMode.PRESET -> frameRatePreset.fps
        }.coerceAtLeast(1).let { target ->
            if (sourceFrameRate > 0) minOf(target, sourceFrameRate.toInt()) else target
        }
    }

    fun computeTargetVideoBitrateBps(originalBitrate: Long): Long {
        val bitrate = when (bitrateMode) {
            BitrateMode.PERCENTAGE -> {
                val base = if (originalBitrate > 0) originalBitrate else 2_000_000L
                base * bitratePercentage / 100L
            }
            BitrateMode.DIRECT -> bitrateDirectKbps * 1000L
            BitrateMode.PRESET -> bitratePreset.kbps * 1000L
        }
        return bitrate.coerceAtLeast(100_000L) // Matches MIN_BITRATE_BPS
    }

    fun computeTargetAudioBitrateBps(originalAudioBitrate: Long): Long {
        val bitrate = when (audioBitrateMode) {
            BitrateMode.PERCENTAGE -> {
                val base = if (originalAudioBitrate > 0) originalAudioBitrate else 128_000L
                base * audioBitratePercentage / 100L
            }
            BitrateMode.DIRECT -> audioBitrateDirectKbps * 1000L
            BitrateMode.PRESET -> audioBitratePreset.kbps * 1000L
        }
        return bitrate.coerceAtLeast(32_000L) // Matches MIN_AUDIO_BITRATE_BPS
    }
}
