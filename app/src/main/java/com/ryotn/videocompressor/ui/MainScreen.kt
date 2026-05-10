package com.ryotn.videocompressor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ryotn.videocompressor.R
import com.ryotn.videocompressor.data.AudioBitratePreset
import com.ryotn.videocompressor.data.BitrateMode
import com.ryotn.videocompressor.data.BitratePreset
import com.ryotn.videocompressor.data.CompressionOptions
import com.ryotn.videocompressor.data.CompressionState
import com.ryotn.videocompressor.data.FrameRateMode
import com.ryotn.videocompressor.data.FrameRatePreset
import com.ryotn.videocompressor.data.ResolutionMode
import com.ryotn.videocompressor.data.ResolutionPreset
import com.ryotn.videocompressor.data.VideoInfo
import com.ryotn.videocompressor.viewmodel.MainViewModel

private enum class ScreenStep {
    Selection,
    Options,
    Progress,
    Completed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSelectVideo: () -> Unit,
    onSelectSaveDirectory: () -> Unit
) {
    val videoInfo by viewModel.videoInfo.collectAsState()
    val options by viewModel.compressionOptions.collectAsState()
    val saveDirectoryUri by viewModel.saveDirectoryUri.collectAsState()
    val saveDirectoryLabel by viewModel.saveDirectoryLabel.collectAsState()
    val state by viewModel.compressionState.collectAsState()

    var currentStep by rememberSaveable { mutableStateOf(ScreenStep.Selection) }

    LaunchedEffect(state) {
        when (state) {
            is CompressionState.Preparing, is CompressionState.InProgress -> {
                currentStep = ScreenStep.Progress
            }
            is CompressionState.Completed -> {
                currentStep = ScreenStep.Completed
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "VideoCompressor",
            style = MaterialTheme.typography.headlineMedium
        )

        when (currentStep) {
            ScreenStep.Selection -> {
                SelectionStepContent(
                    videoInfo = videoInfo,
                    saveDirectoryUriLabel = saveDirectoryLabel ?: saveDirectoryUri?.toString(),
                    hasSaveDirectory = saveDirectoryUri != null,
                    isBusy = state is CompressionState.InProgress || state is CompressionState.Preparing,
                    canProceed = videoInfo != null && saveDirectoryUri != null &&
                        state !is CompressionState.InProgress && state !is CompressionState.Preparing,
                    onSelectVideo = onSelectVideo,
                    onSelectSaveDirectory = onSelectSaveDirectory,
                    onNext = { currentStep = ScreenStep.Options }
                )
            }
            ScreenStep.Options -> {
                // Initial validity is true because raw text states are initialised from Int option
                // values whose toString() is always non-empty. LaunchedEffect keeps the parent
                // state in sync whenever the user clears a direct-input field.
                var isDirectInputValid by remember { mutableStateOf(true) }
                CompressionOptionsContent(
                    options = options,
                    videoInfo = videoInfo,
                    viewModel = viewModel,
                    onValidityChanged = { isDirectInputValid = it }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { currentStep = ScreenStep.Selection },
                        modifier = Modifier.weight(1f),
                        enabled = state !is CompressionState.InProgress && state !is CompressionState.Preparing
                    ) {
                        Text(stringResource(R.string.back))
                    }
                    Button(
                        onClick = { viewModel.startCompression() },
                        modifier = Modifier.weight(1f),
                        enabled = videoInfo != null && saveDirectoryUri != null && state is CompressionState.Idle && isDirectInputValid
                    ) {
                        Text(stringResource(R.string.start_compression))
                    }
                }
            }
            ScreenStep.Progress -> {
                ProgressStepContent(
                    state = state,
                    onCancel = { viewModel.cancelCompression() },
                    onBackToOptions = {
                        viewModel.resetState()
                        currentStep = ScreenStep.Options
                    }
                )
            }
            ScreenStep.Completed -> {
                val completed = state as? CompressionState.Completed
                if (completed != null) {
                    CompletedStepContent(
                        state = completed,
                        onClose = {
                            viewModel.resetState()
                            currentStep = ScreenStep.Selection
                        }
                    )
                } else {
                    currentStep = ScreenStep.Selection
                }
            }
        }
    }
}

@Composable
private fun SelectionStepContent(
    videoInfo: VideoInfo?,
    saveDirectoryUriLabel: String?,
    hasSaveDirectory: Boolean,
    isBusy: Boolean,
    canProceed: Boolean,
    onSelectVideo: () -> Unit,
    onSelectSaveDirectory: () -> Unit,
    onNext: () -> Unit
) {
    Button(
        onClick = onSelectVideo,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isBusy
    ) {
        Text(stringResource(R.string.select_video))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.save_directory), style = MaterialTheme.typography.titleSmall)
            Text(
                text = saveDirectoryUriLabel ?: stringResource(R.string.save_directory_not_selected),
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = onSelectSaveDirectory,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy
            ) {
                Text(
                    if (!hasSaveDirectory) {
                        stringResource(R.string.select_save_directory)
                    } else {
                        stringResource(R.string.change_save_directory)
                    }
                )
            }
        }
    }

    videoInfo?.let { info ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.displayName, style = MaterialTheme.typography.titleSmall)
                val mb = info.sizeBytes / (1024.0 * 1024.0)
                val displaySize = if (mb >= 1024) stringResource(R.string.gb_format, mb / 1024) else stringResource(R.string.mb_format, mb)
                Text(stringResource(R.string.size_label, displaySize))
                val totalSeconds = info.durationMs / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                if (hours > 0) {
                    Text(stringResource(R.string.duration_label_hms, hours, minutes, seconds))
                } else {
                    Text(stringResource(R.string.duration_label_ms, minutes, seconds))
                }
                Text(stringResource(R.string.resolution_label, info.width, info.height))
                Text(stringResource(R.string.bitrate_label, stringResource(R.string.mbps_format, info.bitrateBps / 1_000_000.0)))
                Text(stringResource(R.string.audio_bitrate_label, stringResource(R.string.kbps_format_float, info.audioBitrateBps / 1_000.0)))
            }
        }
    } ?: Text(stringResource(R.string.no_video_selected), style = MaterialTheme.typography.bodyMedium)

    if (!hasSaveDirectory) {
        Text(
            stringResource(R.string.save_directory_required),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = canProceed
    ) {
        Text(stringResource(R.string.next))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressionOptionsContent(
    options: CompressionOptions,
    videoInfo: VideoInfo?,
    viewModel: MainViewModel,
    onValidityChanged: (Boolean) -> Unit
) {
    // Raw text states for direct input fields, reset when the corresponding mode changes
    var bitrateDirectText by rememberSaveable(options.bitrateMode) {
        mutableStateOf(options.bitrateDirectKbps.toString())
    }
    var audioBitrateDirectText by rememberSaveable(options.audioBitrateMode) {
        mutableStateOf(options.audioBitrateDirectKbps.toString())
    }
    var frameRateDirectText by rememberSaveable(options.frameRateMode) {
        mutableStateOf(options.frameRateDirectFps.toString())
    }
    var resolutionDirectWidthText by rememberSaveable(options.resolutionMode) {
        mutableStateOf(options.resolutionDirectWidth.toString())
    }
    var resolutionDirectHeightText by rememberSaveable(options.resolutionMode) {
        mutableStateOf(options.resolutionDirectHeight.toString())
    }

    val isValid =
        (options.bitrateMode != BitrateMode.DIRECT || bitrateDirectText.isNotEmpty()) &&
        (options.audioBitrateMode != BitrateMode.DIRECT || audioBitrateDirectText.isNotEmpty()) &&
        (options.frameRateMode != FrameRateMode.DIRECT || frameRateDirectText.isNotEmpty()) &&
        (options.resolutionMode != ResolutionMode.DIRECT ||
            (resolutionDirectWidthText.isNotEmpty() && resolutionDirectHeightText.isNotEmpty()))
    LaunchedEffect(isValid) { onValidityChanged(isValid) }

    Text(stringResource(R.string.compression_options), style = MaterialTheme.typography.titleMedium)

    Text(stringResource(R.string.bitrate_options), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.video_bitrate_options), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BitrateMode.entries.forEach { mode ->
            FilterChip(
                selected = options.bitrateMode == mode,
                onClick = { viewModel.updateOptions(options.copy(bitrateMode = mode)) },
                label = {
                    Text(
                        when (mode) {
                            BitrateMode.PERCENTAGE -> stringResource(R.string.percentage)
                            BitrateMode.DIRECT -> stringResource(R.string.direct)
                            BitrateMode.PRESET -> stringResource(R.string.preset)
                        }
                    )
                }
            )
        }
    }

    when (options.bitrateMode) {
        BitrateMode.PERCENTAGE -> {
            Text(stringResource(R.string.progress_percentage, options.bitratePercentage))
            androidx.compose.material3.Slider(
                value = options.bitratePercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(bitratePercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        BitrateMode.DIRECT -> {
            OutlinedTextField(
                value = bitrateDirectText,
                onValueChange = { v ->
                    bitrateDirectText = v
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(bitrateDirectKbps = it)) }
                },
                isError = bitrateDirectText.isEmpty(),
                label = { Text(stringResource(R.string.kbps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        BitrateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.preset_label_format, stringResource(options.bitratePreset.labelResId), stringResource(R.string.kbps_format_int, options.bitratePreset.kbps)))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BitratePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_label_format, stringResource(preset.labelResId), stringResource(R.string.kbps_format_int, preset.kbps))) },
                            onClick = {
                                viewModel.updateOptions(options.copy(bitratePreset = preset))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    Text(stringResource(R.string.audio_bitrate_options), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BitrateMode.entries.forEach { mode ->
            FilterChip(
                selected = options.audioBitrateMode == mode,
                onClick = { viewModel.updateOptions(options.copy(audioBitrateMode = mode)) },
                label = {
                    Text(
                        when (mode) {
                            BitrateMode.PERCENTAGE -> stringResource(R.string.percentage)
                            BitrateMode.DIRECT -> stringResource(R.string.direct)
                            BitrateMode.PRESET -> stringResource(R.string.preset)
                        }
                    )
                }
            )
        }
    }

    when (options.audioBitrateMode) {
        BitrateMode.PERCENTAGE -> {
            Text(stringResource(R.string.progress_percentage, options.audioBitratePercentage))
            androidx.compose.material3.Slider(
                value = options.audioBitratePercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(audioBitratePercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        BitrateMode.DIRECT -> {
            OutlinedTextField(
                value = audioBitrateDirectText,
                onValueChange = { v ->
                    audioBitrateDirectText = v
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(audioBitrateDirectKbps = it)) }
                },
                isError = audioBitrateDirectText.isEmpty(),
                label = { Text(stringResource(R.string.kbps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        BitrateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.preset_label_format, stringResource(options.audioBitratePreset.labelResId), stringResource(R.string.kbps_format_int, options.audioBitratePreset.kbps)))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AudioBitratePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_label_format, stringResource(preset.labelResId), stringResource(R.string.kbps_format_int, preset.kbps))) },
                            onClick = {
                                viewModel.updateOptions(options.copy(audioBitratePreset = preset))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    Text(stringResource(R.string.frame_rate_options), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameRateMode.entries.forEach { mode ->
            FilterChip(
                selected = options.frameRateMode == mode,
                onClick = { viewModel.updateOptions(options.copy(frameRateMode = mode)) },
                label = {
                    Text(
                        when (mode) {
                            FrameRateMode.PERCENTAGE -> stringResource(R.string.percentage)
                            FrameRateMode.DIRECT -> stringResource(R.string.direct)
                            FrameRateMode.PRESET -> stringResource(R.string.preset)
                        }
                    )
                }
            )
        }
    }

    when (options.frameRateMode) {
        FrameRateMode.PERCENTAGE -> {
            Text(stringResource(R.string.progress_percentage, options.frameRatePercentage))
            androidx.compose.material3.Slider(
                value = options.frameRatePercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(frameRatePercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
            if (videoInfo != null && videoInfo.frameRateFps > 0) {
                val targetFps = options.computeTargetFrameRateFps(videoInfo.frameRateFps)
                Text(stringResource(R.string.output_frame_rate, targetFps), style = MaterialTheme.typography.bodySmall)
            }
        }
        FrameRateMode.DIRECT -> {
            OutlinedTextField(
                value = frameRateDirectText,
                onValueChange = { v ->
                    frameRateDirectText = v
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(frameRateDirectFps = it)) }
                },
                isError = frameRateDirectText.isEmpty(),
                label = { Text(stringResource(R.string.fps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        FrameRateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.preset_label_format, stringResource(options.frameRatePreset.labelResId), stringResource(R.string.fps_format_int, options.frameRatePreset.fps)))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    FrameRatePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_label_format, stringResource(preset.labelResId), stringResource(R.string.fps_format_int, preset.fps))) },
                            onClick = {
                                viewModel.updateOptions(options.copy(frameRatePreset = preset))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    Text(stringResource(R.string.resolution_options), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResolutionMode.entries.forEach { mode ->
            FilterChip(
                selected = options.resolutionMode == mode,
                onClick = { viewModel.updateOptions(options.copy(resolutionMode = mode)) },
                label = {
                    Text(
                        when (mode) {
                            ResolutionMode.PERCENTAGE -> stringResource(R.string.percentage)
                            ResolutionMode.DIRECT -> stringResource(R.string.direct)
                            ResolutionMode.PRESET -> stringResource(R.string.preset)
                        }
                    )
                }
            )
        }
    }

    when (options.resolutionMode) {
        ResolutionMode.PERCENTAGE -> {
            Text(stringResource(R.string.progress_percentage, options.resolutionPercentage))
            androidx.compose.material3.Slider(
                value = options.resolutionPercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(resolutionPercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
            computeOutputDimensions(options, videoInfo)?.let { (w, h) ->
                Text(stringResource(R.string.output_resolution, w, h), style = MaterialTheme.typography.bodySmall)
            }
        }
        ResolutionMode.DIRECT -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = resolutionDirectWidthText,
                    onValueChange = { v ->
                        resolutionDirectWidthText = v
                        v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(resolutionDirectWidth = it)) }
                    },
                    isError = resolutionDirectWidthText.isEmpty(),
                    label = { Text(stringResource(R.string.width_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = resolutionDirectHeightText,
                    onValueChange = { v ->
                        resolutionDirectHeightText = v
                        v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(resolutionDirectHeight = it)) }
                    },
                    isError = resolutionDirectHeightText.isEmpty(),
                    label = { Text(stringResource(R.string.height_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            computeOutputDimensions(options, videoInfo)?.let { (w, h) ->
                Text(stringResource(R.string.output_resolution, w, h), style = MaterialTheme.typography.bodySmall)
            }
        }
        ResolutionMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(formatResolutionPresetLabel(options.resolutionPreset, videoInfo))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ResolutionPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(formatResolutionPresetLabel(preset, videoInfo)) },
                            onClick = {
                                viewModel.updateOptions(options.copy(resolutionPreset = preset))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatResolutionPresetLabel(preset: ResolutionPreset, videoInfo: VideoInfo?): String {
    val baseLabel = stringResource(preset.labelResId)
    val (width, height) = computePresetDisplayDimensions(preset, videoInfo)
    return stringResource(R.string.preset_resolution_label_format, baseLabel, width, height)
}

private fun computePresetDisplayDimensions(preset: ResolutionPreset, videoInfo: VideoInfo?): Pair<Int, Int> {
    val srcW = videoInfo?.width ?: return preset.width to preset.height
    val srcH = videoInfo.height
    if (srcW <= 0 || srcH <= 0) return preset.width to preset.height

    // Orient the preset to match the video. For portrait videos (height > width) the
    // preset's width and height are swapped so the "long side" constraint is applied to
    // the video's long side.
    val (maxW, maxH) = if (srcH > srcW) preset.height to preset.width else preset.width to preset.height

    // Clamp scale to 1.0 so the video is never upscaled.
    val scale = minOf(1.0f, maxW.toFloat() / srcW, maxH.toFloat() / srcH)
    val width = makeEven((srcW * scale).toInt().coerceAtLeast(2))
    val height = makeEven((srcH * scale).toInt().coerceAtLeast(2))
    return width to height
}

/** Computes the actual output dimensions for the current resolution settings, mirroring VideoTranscoder logic. */
private fun computeOutputDimensions(options: CompressionOptions, videoInfo: VideoInfo?): Pair<Int, Int>? {
    val srcW = videoInfo?.width?.takeIf { it > 0 } ?: return null
    val srcH = videoInfo.height.takeIf { it > 0 } ?: return null
    return when (options.resolutionMode) {
        ResolutionMode.PERCENTAGE -> {
            val scale = options.resolutionPercentage / 100f
            makeEven((srcW * scale).toInt().coerceAtLeast(2)) to makeEven((srcH * scale).toInt().coerceAtLeast(2))
        }
        ResolutionMode.DIRECT -> {
            val scale = minOf(1.0f, options.resolutionDirectWidth.toFloat() / srcW, options.resolutionDirectHeight.toFloat() / srcH)
            makeEven((srcW * scale).toInt().coerceAtLeast(2)) to makeEven((srcH * scale).toInt().coerceAtLeast(2))
        }
        ResolutionMode.PRESET -> {
            val preset = options.resolutionPreset
            // Orient the preset to match the video orientation (portrait vs landscape).
            val (maxW, maxH) = if (srcH > srcW) preset.height to preset.width else preset.width to preset.height
            val scale = minOf(1.0f, maxW.toFloat() / srcW, maxH.toFloat() / srcH)
            makeEven((srcW * scale).toInt().coerceAtLeast(2)) to makeEven((srcH * scale).toInt().coerceAtLeast(2))
        }
    }
}

private fun makeEven(value: Int): Int = if (value % 2 == 0) value else value - 1

@Composable
private fun ProgressStepContent(
    state: CompressionState,
    onCancel: () -> Unit,
    onBackToOptions: () -> Unit
) {
    when (state) {
        is CompressionState.Preparing -> {
            Text(stringResource(R.string.preparing_compression))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.cancel_compression))
            }
        }
        is CompressionState.InProgress -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compression_progress))
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.progress_percentage_float, state.progressPercent))
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.cancel_compression))
                }
            }
        }
        is CompressionState.Failed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.compression_failed), style = MaterialTheme.typography.titleSmall)
                    Text(state.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onBackToOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back_to_options))
            }
        }
        is CompressionState.Cancelled -> {
            Text(stringResource(R.string.compression_cancelled))
            Button(onClick = onBackToOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back_to_options))
            }
        }
        else -> {
            Text(stringResource(R.string.compression_waiting))
            Button(onClick = onBackToOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back_to_options))
            }
        }
    }
}

@Composable
private fun CompletedStepContent(
    state: CompressionState.Completed,
    onClose: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.compression_complete), style = MaterialTheme.typography.titleSmall)
            val originalMb = state.originalSizeBytes / (1024.0 * 1024.0)
            val outputMb = state.outputSizeBytes / (1024.0 * 1024.0)
            val originalDisplaySize = if (originalMb >= 1024) stringResource(R.string.gb_format, originalMb / 1024) else stringResource(R.string.mb_format, originalMb)
            val outputDisplaySize = if (outputMb >= 1024) stringResource(R.string.gb_format, outputMb / 1024) else stringResource(R.string.mb_format, outputMb)
            Text(stringResource(R.string.original_size_label, originalDisplaySize))
            Text(stringResource(R.string.compressed_size_label, outputDisplaySize))
            if (state.originalSizeBytes > 0) {
                Text(stringResource(R.string.compression_ratio, outputMb / originalMb * 100))
            }
            Text(stringResource(R.string.save_destination, state.outputPath), style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.close))
    }
}
