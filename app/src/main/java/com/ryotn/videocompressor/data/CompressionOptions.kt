package com.ryotn.videocompressor.data

import androidx.annotation.StringRes
import com.ryotn.videocompressor.R
import java.io.Serializable

enum class BitrateMode { PERCENTAGE, DIRECT, PRESET }
enum class ResolutionMode { PERCENTAGE, DIRECT, PRESET }
enum class FrameRateMode { PERCENTAGE, DIRECT, PRESET }
enum class CompressionMode { SIMPLE, ADVANCED }

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

enum class VideoCodec(@StringRes val labelResId: Int, val mimeType: String) {
    H264(R.string.codec_h264, "video/avc"),
    H265(R.string.codec_h265, "video/hevc"),
    AV1(R.string.codec_av1, "video/av01")
}

data class SimpleCompressionOptions(
    val targetSizeMb: Int = 100
) : Serializable {
    companion object {
        const val AUDIO_BITRATE_KBPS = 128
        const val FRAME_RATE_FPS = 30
        const val MIN_VIDEO_BITRATE_FHD_KBPS = 2000
        const val MIN_VIDEO_BITRATE_HD_KBPS = 500
    }

    fun computeVideoBitrateKbps(videoInfo: VideoInfo?): Int {
        if (videoInfo == null || videoInfo.durationMs <= 0) return MIN_VIDEO_BITRATE_FHD_KBPS
        val targetBits = targetSizeMb * 1024L * 1024L * 8L
        val durationSeconds = videoInfo.durationMs / 1000.0
        val totalBitrateKbps = (targetBits / durationSeconds / 1000).toInt()
        return (totalBitrateKbps - AUDIO_BITRATE_KBPS).coerceAtLeast(0)
    }

    fun computeResolutionPreset(videoBitrateKbps: Int): ResolutionPreset =
        if (videoBitrateKbps >= MIN_VIDEO_BITRATE_FHD_KBPS) ResolutionPreset.FHD else ResolutionPreset.HD

    fun isAchievable(videoInfo: VideoInfo?): Boolean =
        computeVideoBitrateKbps(videoInfo) >= MIN_VIDEO_BITRATE_HD_KBPS

    fun toCompressionOptions(videoInfo: VideoInfo?, preferH265: Boolean = true): CompressionOptions {
        val videoBitrateKbps = computeVideoBitrateKbps(videoInfo).coerceAtLeast(MIN_VIDEO_BITRATE_HD_KBPS)
        val resolution = computeResolutionPreset(videoBitrateKbps)
        return CompressionOptions(
            videoCodec = if (preferH265) VideoCodec.H265 else VideoCodec.H264,
            frameRateMode = FrameRateMode.DIRECT,
            frameRateDirectFps = FRAME_RATE_FPS,
            bitrateMode = BitrateMode.DIRECT,
            bitrateDirectKbps = videoBitrateKbps,
            audioBitrateMode = BitrateMode.DIRECT,
            audioBitrateDirectKbps = AUDIO_BITRATE_KBPS,
            resolutionMode = ResolutionMode.PRESET,
            resolutionPreset = resolution
        )
    }
}

data class CompressionOptions(
    val videoCodec: VideoCodec = VideoCodec.H264,
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
    val resolutionPreset: ResolutionPreset = ResolutionPreset.HD,
    val removeAudio: Boolean = false
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

    fun computeEstimatedSizeBytes(videoInfo: VideoInfo?): Long {
        if (videoInfo == null || videoInfo.durationMs <= 0) return 0L
        val videoBitrateBps = when (bitrateMode) {
            BitrateMode.PERCENTAGE -> (videoInfo.bitrateBps * (bitratePercentage / 100.0)).toLong()
            BitrateMode.DIRECT -> bitrateDirectKbps * 1000L
            BitrateMode.PRESET -> bitratePreset.kbps * 1000L
        }
        val audioBitrateBps = if (removeAudio) 0L else when (audioBitrateMode) {
            BitrateMode.PERCENTAGE -> (videoInfo.audioBitrateBps * (audioBitratePercentage / 100.0)).toLong()
            BitrateMode.DIRECT -> audioBitrateDirectKbps * 1000L
            BitrateMode.PRESET -> audioBitratePreset.kbps * 1000L
        }
        val totalBitrateBps = videoBitrateBps + audioBitrateBps
        val durationSeconds = videoInfo.durationMs / 1000.0
        return (totalBitrateBps * durationSeconds / 8.0).toLong()
    }
}
