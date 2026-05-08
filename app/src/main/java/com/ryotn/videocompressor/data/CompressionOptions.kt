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

enum class ResolutionPreset(val labelJa: String, val width: Int, val height: Int) {
    SD("SD (854×480)", 854, 480),
    HD("HD (1280×720)", 1280, 720),
    FHD("FHD (1920×1080)", 1920, 1080),
    QHD("QHD (2560×1440)", 2560, 1440)
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
    val resolutionDirectWidth: Int = 1280,
    val resolutionDirectHeight: Int = 720,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.HD
) : Serializable
