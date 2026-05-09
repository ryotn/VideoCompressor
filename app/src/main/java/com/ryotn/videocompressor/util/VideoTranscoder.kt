package com.ryotn.videocompressor.util

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.ryotn.videocompressor.data.BitrateMode
import com.ryotn.videocompressor.data.CompressionOptions
import com.ryotn.videocompressor.data.FrameRateMode
import com.ryotn.videocompressor.data.ResolutionMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Transcodes a video file using Android's MediaCodec API.
 *
 * Pipeline: MediaExtractor → MediaCodec (decode) → EGL/GLES2 (scale) → MediaCodec (encode) → MediaMuxer
 * Audio is re-encoded to AAC with the selected target bitrate. If audio re-encoding fails,
 * the original audio track is passed through as a fallback.
 */
class VideoTranscoder(
    private val inputFile: File,
    private val outputFile: File,
    private val options: CompressionOptions,
    private val originalBitrate: Long,
    private val originalAudioBitrate: Long,
    private val durationUs: Long,
    private val onProgress: (Float) -> Unit
) {
    private data class SelectedEncoder(
        val codec: MediaCodec,
        val bitrateMode: Int?,
        val appliedBitrateBps: Long
    )

    private data class EncodedAudioSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    @Volatile
    var isCancelled = false

    companion object {
        private const val TAG = "VideoTranscoder"
        /** Timeout for MediaCodec dequeueInputBuffer / dequeueOutputBuffer calls (microseconds). */
        private const val CODEC_TIMEOUT_US = 10_000L
        /** Maximum time to wait for the decoder to produce a rendered frame (milliseconds). */
        private const val FRAME_TIMEOUT_MS = 3_000L
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_OUTPUT_MIME = "audio/mp4a-latm"
        /** Minimum acceptable video bitrate to avoid MediaCodec configuration errors. */
        private const val MIN_BITRATE_BPS = 100_000L
        /** Fallback bitrate used when the source bitrate is unavailable (2 Mbps). */
        private const val DEFAULT_BITRATE_BPS = 2_000_000L
        private const val DEFAULT_AUDIO_BITRATE_BPS = 128_000L
        private const val MIN_AUDIO_BITRATE_BPS = 32_000L
        /** Warn when codec bitrate clamping changes requested bitrate by more than ±20%. */
        private const val BITRATE_ADJUSTMENT_WARNING_THRESHOLD = 0.2
        /**
         * Many hardware AVC encoders ignore requested bitrates below ~4 Mbps even when their
         * advertised capability range claims otherwise, so prefer software below this threshold.
         */
        private const val PREFER_SOFTWARE_BELOW_BITRATE_BPS = 4_000_000L
    }

    // EGL state
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // GL state
    private var glProgram = 0
    private var glTextureId = 0
    private var vertexBuf: FloatBuffer? = null
    private var texBuf: FloatBuffer? = null

    // Decoder output surface
    private var decoderSurfaceTex: SurfaceTexture? = null
    private var decoderSurface: Surface? = null

    private val frameSyncObject = Object()
    private var frameAvailable = false

    // ------------------------------------------------------------------------------------------
    // GLSL shaders
    // ------------------------------------------------------------------------------------------

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    // ------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------

    /**
     * Run the transcoding. Blocking – must be called from a worker thread.
     * @return true on success, false on failure or cancellation.
     */
    fun transcode(): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            videoExtractor.setDataSource(inputFile.absolutePath)
            audioExtractor.setDataSource(inputFile.absolutePath)

            // ---- find tracks ----
            val videoTrackIdx = findTrack(videoExtractor, "video/")
            check(videoTrackIdx >= 0) { "No video track found in source" }

            val videoInputFormat = videoExtractor.getTrackFormat(videoTrackIdx)
            val srcWidth = videoInputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoInputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (videoInputFormat.containsKey(MediaFormat.KEY_ROTATION))
                videoInputFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            val sourceFrameRate = if (videoInputFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                videoInputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            val (targetW, targetH) = computeTargetDimensions(srcWidth, srcHeight)
            val targetBitrateBps = computeTargetBitrateBps().coerceAtLeast(MIN_BITRATE_BPS)
            val targetFrameRateFps = computeTargetFrameRateFps(sourceFrameRate).coerceAtLeast(1)
            val targetAudioBitrateBps = computeTargetAudioBitrateBps().coerceAtLeast(MIN_AUDIO_BITRATE_BPS)
            Log.d(
                TAG,
                "src=${srcWidth}x${srcHeight} → dst=${targetW}x${targetH} bitrate=${targetBitrateBps}bps fps=${targetFrameRateFps}"
            )

            // ---- encoder ----
            val selectedEncoder = selectEncoder(targetBitrateBps, targetW, targetH)
            val encoderFormat = MediaFormat.createVideoFormat(VIDEO_MIME, targetW, targetH).apply {
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    selectedEncoder.appliedBitrateBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRateFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            encoder = selectedEncoder.codec
            selectedEncoder.bitrateMode?.let { encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = encoder.createInputSurface()

            // ---- EGL / GL (must be done after encoder surface is created) ----
            setupEGL(encoderInputSurface)
            setupGL()

            // ---- decoder (output goes to decoderSurface created in setupGL) ----
            val decoderMime = videoInputFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(decoderMime)
            decoder.configure(videoInputFormat, decoderSurface, null, 0)

            // ---- muxer ----
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Probe audio format now so we can add it as a track before muxer.start()
            val audioTrackIdx = findTrack(audioExtractor, "audio/")
            val audioFormat: MediaFormat? = if (audioTrackIdx >= 0)
                audioExtractor.getTrackFormat(audioTrackIdx) else null
            val transcodedAudio = transcodeAudioTrack(targetAudioBitrateBps)

            // ---- start codecs ----
            videoExtractor.selectTrack(videoTrackIdx)
            encoder.start()
            decoder.start()

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            val frameIntervalUs = (1_000_000L / targetFrameRateFps.coerceAtLeast(1)).coerceAtLeast(1L)
            var nextRenderPtsUs = Long.MIN_VALUE
            var nextOutputPtsUs = Long.MIN_VALUE

            GLES20.glViewport(0, 0, targetW, targetH)

            // ---- main transcode loop ----
            while (!encoderDone && !isCancelled) {

                // Feed compressed video data to decoder
                if (!inputDone) {
                    val inputIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val buf = decoder.getInputBuffer(inputIdx)!!
                        buf.clear()
                        val sampleSize = videoExtractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIdx, 0, sampleSize, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                // Drain decoder → render to encoder surface via EGL
                if (!decoderDone) {
                    val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                    if (outputIdx >= 0) {
                        val doRender = bufferInfo.size > 0
                        val pts = bufferInfo.presentationTimeUs
                        val shouldRender = doRender && shouldRenderFrame(pts, nextRenderPtsUs)
                        var renderPtsUs = pts
                        if (shouldRender) {
                            if (nextRenderPtsUs == Long.MIN_VALUE) {
                                nextRenderPtsUs = pts
                            }
                            if (nextOutputPtsUs == Long.MIN_VALUE) {
                                nextOutputPtsUs = pts
                            } else {
                                nextOutputPtsUs += frameIntervalUs
                            }
                            renderPtsUs = nextOutputPtsUs
                            do {
                                nextRenderPtsUs += frameIntervalUs
                            } while (nextRenderPtsUs <= pts)
                        }
                        decoder.releaseOutputBuffer(outputIdx, shouldRender)
                        if (shouldRender) {
                            awaitNewFrame()
                            drawFrame()
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, renderPtsUs * 1000L)
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }

                // Drain encoder → write to muxer
                val encIdx = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val videoOutFmt = encoder.outputFormat
                            if (rotation != 0) videoOutFmt.setInteger(MediaFormat.KEY_ROTATION, rotation)
                            muxerVideoTrack = muxer.addTrack(videoOutFmt)
                            if (transcodedAudio != null) {
                                muxerAudioTrack = muxer.addTrack(transcodedAudio.first)
                            } else if (audioFormat != null) {
                                muxerAudioTrack = muxer.addTrack(audioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encIdx >= 0 -> {
                        if (muxerStarted &&
                            bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val encodedData = encoder.getOutputBuffer(encIdx)!!
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                            if (durationUs > 0) {
                                onProgress(
                                    (bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                )
                            }
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            if (isCancelled) return false

            // ---- audio write ----
            if (transcodedAudio != null && muxerStarted && muxerAudioTrack >= 0) {
                val audioInfo = MediaCodec.BufferInfo()
                for (sample in transcodedAudio.second) {
                    if (isCancelled) break
                    val audioBuf = ByteBuffer.wrap(sample.data)
                    audioInfo.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                }
            } else if (audioTrackIdx >= 0 && muxerStarted && muxerAudioTrack >= 0) {
                audioExtractor.selectTrack(audioTrackIdx)
                val audioBuf = ByteBuffer.allocate(256 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                while (!isCancelled) {
                    audioBuf.clear()
                    val size = audioExtractor.readSampleData(audioBuf, 0)
                    if (size < 0) break
                    audioInfo.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                    audioExtractor.advance()
                }
            }

            !isCancelled
        } catch (e: Exception) {
            Log.e(TAG, "Transcode error", e)
            false
        } finally {
            try { decoder?.stop() } catch (e: Exception) { Log.w(TAG, "decoder stop failed", e) }
            try { decoder?.release() } catch (e: Exception) { Log.w(TAG, "decoder release failed", e) }
            try { encoder?.stop() } catch (e: Exception) { Log.w(TAG, "encoder stop failed", e) }
            try { encoder?.release() } catch (e: Exception) { Log.w(TAG, "encoder release failed", e) }
            encoderInputSurface?.release()
            releaseEGL()
            if (muxerStarted) {
                try { muxer?.stop() } catch (e: Exception) { Log.w(TAG, "muxer stop failed", e) }
            }
            try { muxer?.release() } catch (e: Exception) { Log.w(TAG, "muxer release failed", e) }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Target dimension / bitrate helpers
    // ------------------------------------------------------------------------------------------

    private fun computeTargetDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
        return when (options.resolutionMode) {
            ResolutionMode.PERCENTAGE -> {
                val p = options.resolutionPercentage
                val w = makeEven((srcW * p / 100).coerceAtLeast(2))
                val h = makeEven((srcH * p / 100).coerceAtLeast(2))
                Pair(w, h)
            }
            ResolutionMode.DIRECT -> fitDimensions(srcW, srcH, options.resolutionDirectWidth, options.resolutionDirectHeight)
            ResolutionMode.PRESET -> fitDimensions(srcW, srcH, options.resolutionPreset.width, options.resolutionPreset.height)
        }
    }

    private fun fitDimensions(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return Pair(maxW, maxH)
        val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
        val w = makeEven((srcW * scale).toInt().coerceAtLeast(2))
        val h = makeEven((srcH * scale).toInt().coerceAtLeast(2))
        return Pair(w, h)
    }

    /** Returns [value] rounded down to the nearest even number (required by H.264 encoder). */
    private fun makeEven(value: Int): Int = if (value % 2 != 0) value - 1 else value

    private fun computeTargetBitrateBps(): Long {
        return when (options.bitrateMode) {
            BitrateMode.PERCENTAGE -> {
                val base = if (originalBitrate > 0) originalBitrate else DEFAULT_BITRATE_BPS
                base * options.bitratePercentage / 100L
            }
            BitrateMode.DIRECT -> options.bitrateDirectKbps * 1000L
            BitrateMode.PRESET -> options.bitratePreset.kbps * 1000L
        }
    }

    private fun computeTargetAudioBitrateBps(): Long {
        return when (options.audioBitrateMode) {
            BitrateMode.PERCENTAGE -> {
                val base = if (originalAudioBitrate > 0) originalAudioBitrate else DEFAULT_AUDIO_BITRATE_BPS
                base * options.audioBitratePercentage / 100L
            }
            BitrateMode.DIRECT -> options.audioBitrateDirectKbps * 1000L
            BitrateMode.PRESET -> options.audioBitratePreset.kbps * 1000L
        }
    }

    private fun computeTargetFrameRateFps(sourceFrameRate: Int): Int {
        return when (options.frameRateMode) {
            FrameRateMode.DIRECT -> options.frameRateDirectFps
            FrameRateMode.PRESET -> options.frameRatePreset.fps
        }.coerceAtLeast(1).let { target ->
            // Frame interpolation is not implemented in this pipeline, so cap to source fps.
            if (sourceFrameRate > 0) minOf(target, sourceFrameRate) else target
        }
    }

    private fun shouldRenderFrame(ptsUs: Long, nextRenderPtsUs: Long): Boolean {
        if (nextRenderPtsUs == Long.MIN_VALUE) return true
        return ptsUs >= nextRenderPtsUs
    }

    /**
     * Selects a H.264 encoder that supports [targetBitrateBps].
     *
     * Hardware encoders enforce a minimum bitrate per resolution (often 4 Mbps on many devices).
     * When the target bitrate is below that floor the hardware encoder silently ignores the
     * request and uses its own minimum.  This method queries every available encoder's supported
     * bitrate range and prefers:
     *  1. A software encoder for sub-4 Mbps targets (hardware often ignores them in practice).
     *  2. Otherwise, a hardware encoder whose range includes the target.
     *  3. The system default if nothing else works.
     */
    private fun selectEncoder(targetBitrateBps: Long, width: Int, height: Int): SelectedEncoder {
        data class EncoderCandidate(
            val info: MediaCodecInfo,
            val bitrateMode: Int?,
            val minBitrateBps: Int,
            val maxBitrateBps: Int
        )

        /**
         * Returns the preferred bitrate mode supported by this encoder:
         * CBR first, VBR as fallback, or null when neither mode is supported.
         */
        fun pickBitrateMode(caps: MediaCodecInfo.CodecCapabilities): Int? {
            val encCaps = caps.encoderCapabilities
            return when {
                encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                else -> null
            }
        }

        // Many hardware encoders still ignore sub-4Mbps requests in practice, so prefer software.
        val preferSoftware = targetBitrateBps < PREFER_SOFTWARE_BELOW_BITRATE_BPS
        val targetBitrateInt = targetBitrateBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val hardwareCandidates = mutableListOf<EncoderCandidate>()
        val softwareCandidates = mutableListOf<EncoderCandidate>()

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(VIDEO_MIME, ignoreCase = true) }) continue

            val caps = runCatching { info.getCapabilitiesForType(VIDEO_MIME) }.getOrNull() ?: continue

            val videoCaps = caps.videoCapabilities ?: continue
            if (!videoCaps.isSizeSupported(width, height)) continue

            val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                info.name.startsWith("OMX.google", ignoreCase = true) ||
                    info.name.startsWith("c2.android", ignoreCase = true)
            }

            val supportsTargetBitrate = videoCaps.bitrateRange.contains(targetBitrateInt)
            // Accept when target bitrate is supported, or when software is explicitly preferred.
            if (!supportsTargetBitrate && (!isSoftware || !preferSoftware)) continue

            val candidate = EncoderCandidate(
                info = info,
                bitrateMode = pickBitrateMode(caps),
                minBitrateBps = videoCaps.bitrateRange.lower,
                maxBitrateBps = videoCaps.bitrateRange.upper
            )
            if (isSoftware) softwareCandidates.add(candidate) else hardwareCandidates.add(candidate)
        }

        val sortedSoftwareCandidates = softwareCandidates.sortedBy { candidate ->
            val supportsTarget = targetBitrateInt in candidate.minBitrateBps..candidate.maxBitrateBps
            if (supportsTarget) 0 else 1
        }
        val sortedHardwareCandidates = hardwareCandidates.sortedBy { candidate ->
            val supportsTarget = targetBitrateInt in candidate.minBitrateBps..candidate.maxBitrateBps
            if (supportsTarget) 0 else 1
        }
        val chosenCandidate = if (preferSoftware) {
            sortedSoftwareCandidates.firstOrNull() ?: sortedHardwareCandidates.firstOrNull()
        } else {
            sortedHardwareCandidates.firstOrNull() ?: sortedSoftwareCandidates.firstOrNull()
        }
        return if (chosenCandidate != null) {
            val appliedBitrateBps = targetBitrateBps
                .coerceAtLeast(chosenCandidate.minBitrateBps.toLong())
                .coerceAtMost(chosenCandidate.maxBitrateBps.toLong())
            if (targetBitrateBps > 0L) {
                val ratio = appliedBitrateBps.toDouble() / targetBitrateBps.toDouble()
                val minRatio = 1.0 - BITRATE_ADJUSTMENT_WARNING_THRESHOLD
                val maxRatio = 1.0 + BITRATE_ADJUSTMENT_WARNING_THRESHOLD
                if (ratio < minRatio || ratio > maxRatio) {
                    Log.w(
                        TAG,
                        "Requested bitrate ${targetBitrateBps}bps adjusted to ${appliedBitrateBps}bps for encoder ${chosenCandidate.info.name}"
                    )
                }
            }
            val bitrateModeLabel = when (chosenCandidate.bitrateMode) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
                else -> "default"
            }
            Log.d(
                TAG,
                "Encoder: ${chosenCandidate.info.name}, bitrateTarget=${targetBitrateBps}bps, bitrateApplied=${appliedBitrateBps}bps, preferSoftware=$preferSoftware, bitrateMode=$bitrateModeLabel"
            )
            SelectedEncoder(
                codec = MediaCodec.createByCodecName(chosenCandidate.info.name),
                bitrateMode = chosenCandidate.bitrateMode,
                appliedBitrateBps = appliedBitrateBps
            )
        } else {
            Log.w(TAG, "No encoder supporting ${targetBitrateBps}bps at ${width}x${height}, using default")
            SelectedEncoder(
                codec = MediaCodec.createEncoderByType(VIDEO_MIME),
                bitrateMode = null,
                appliedBitrateBps = targetBitrateBps
            )
        }
    }

    private fun transcodeAudioTrack(targetAudioBitrateBps: Long): Pair<MediaFormat, List<EncodedAudioSample>>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val audioTrackIndex = findTrack(extractor, "audio/")
            if (audioTrackIndex < 0) return null
            extractor.selectTrack(audioTrackIndex)

            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val outputFormat = MediaFormat.createAudioFormat(AUDIO_OUTPUT_MIME, sampleRate, channelCount).apply {
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    targetAudioBitrateBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder = MediaCodec.createEncoderByType(AUDIO_OUTPUT_MIME).apply {
                configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderDone = false
            var encodedFormat: MediaFormat? = null
            val samples = mutableListOf<EncodedAudioSample>()
            val bufferInfo = MediaCodec.BufferInfo()

            while (!encoderDone && !isCancelled) {
                if (!decoderInputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderOutputDone) {
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                    if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (bufferInfo.size > 0 && outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmBytes)

                            var offset = 0
                            while (offset < pcmBytes.size && !isCancelled) {
                                val encoderInputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (encoderInputIndex >= 0) {
                                    val encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex) ?: continue
                                    encoderInputBuffer.clear()
                                    val chunkSize = minOf(encoderInputBuffer.remaining(), pcmBytes.size - offset)
                                    encoderInputBuffer.put(pcmBytes, offset, chunkSize)
                                    val bytesPerFrame = 2 * channelCount
                                    val frameOffset = if (bytesPerFrame > 0) offset / bytesPerFrame else 0
                                    val ptsOffsetUs = if (sampleRate > 0) {
                                        frameOffset.toLong() * 1_000_000L / sampleRate
                                    } else {
                                        0L
                                    }
                                    encoder.queueInputBuffer(
                                        encoderInputIndex,
                                        0,
                                        chunkSize,
                                        bufferInfo.presentationTimeUs + ptsOffsetUs,
                                        0
                                    )
                                    offset += chunkSize
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) {
                            decoderOutputDone = true
                            var signaled = false
                            while (!signaled && !isCancelled) {
                                val encoderInputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (encoderInputIndex >= 0) {
                                    encoder.queueInputBuffer(
                                        encoderInputIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    signaled = true
                                }
                            }
                        }
                    }
                }

                val encoderOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        encodedFormat = encoder.outputFormat
                    }
                    encoderOutputIndex >= 0 -> {
                        val encodedBuffer = encoder.getOutputBuffer(encoderOutputIndex)
                        if (
                            encodedBuffer != null &&
                            bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            encodedBuffer.get(data)
                            samples.add(
                                EncodedAudioSample(
                                    data = data,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags
                                )
                            )
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                        encoder.releaseOutputBuffer(encoderOutputIndex, false)
                    }
                }
            }

            encodedFormat?.let { it to samples }
        } catch (e: Exception) {
            Log.w(TAG, "Audio re-encode failed, fallback to passthrough", e)
            null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    // ------------------------------------------------------------------------------------------
    // MediaExtractor helpers
    // ------------------------------------------------------------------------------------------

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    // ------------------------------------------------------------------------------------------
    // EGL setup / teardown
    // ------------------------------------------------------------------------------------------

    private fun setupEGL(encoderInputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Failed to get EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Failed to initialize EGL" }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            "Failed to choose EGL config"
        }
        check(numConfigs[0] > 0) { "No EGL config available" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create EGL context" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderInputSurface, surfaceAttribs, 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Failed to create EGL window surface" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Failed to make EGL context current"
        }
    }

    private fun releaseEGL() {
        decoderSurface?.release()
        decoderSurface = null
        decoderSurfaceTex?.release()
        decoderSurfaceTex = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    // ------------------------------------------------------------------------------------------
    // GL setup and rendering
    // ------------------------------------------------------------------------------------------

    private fun setupGL() {
        // Create external OES texture for SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        glTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, glTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        decoderSurfaceTex = SurfaceTexture(glTextureId).also { st ->
            st.setOnFrameAvailableListener {
                synchronized(frameSyncObject) {
                    frameAvailable = true
                    frameSyncObject.notifyAll()
                }
            }
        }
        decoderSurface = Surface(decoderSurfaceTex)

        // Compile and link shaders
        glProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        // Full-screen quad vertex and texture-coordinate buffers
        vertexBuf = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).toFloatBuffer()
        texBuf = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).toFloatBuffer()
    }

    private fun awaitNewFrame() {
        synchronized(frameSyncObject) {
            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw RuntimeException("Timed out after ${FRAME_TIMEOUT_MS}ms waiting for decoded frame")
                frameSyncObject.wait(remaining)
            }
            frameAvailable = false
        }
        decoderSurfaceTex!!.updateTexImage()
    }

    private fun drawFrame() {
        val texMatrix = FloatArray(16)
        decoderSurfaceTex!!.getTransformMatrix(texMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(glProgram)

        val aPosition = GLES20.glGetAttribLocation(glProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)

        val aTexCoord = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        val uTexMatrix = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, glTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(glProgram, "sTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    // ------------------------------------------------------------------------------------------
    // GL shader helpers
    // ------------------------------------------------------------------------------------------

    private fun createGlProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw RuntimeException("GL program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw RuntimeException("GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    // ------------------------------------------------------------------------------------------
    // Extension helpers
    // ------------------------------------------------------------------------------------------

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(this); it.position(0) }
}
