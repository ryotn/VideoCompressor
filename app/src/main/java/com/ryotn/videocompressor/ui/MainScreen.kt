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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
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
                CompressionOptionsContent(
                    options = options,
                    viewModel = viewModel,
                    videoInfo = videoInfo
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { currentStep = ScreenStep.Selection },
                        modifier = Modifier.weight(1f),
                        enabled = state !is CompressionState.InProgress && state !is CompressionState.Preparing
                    ) {
                        Text("戻る")
                    }
                    Button(
                        onClick = { viewModel.startCompression() },
                        modifier = Modifier.weight(1f),
                        enabled = videoInfo != null && saveDirectoryUri != null && state is CompressionState.Idle
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
                Text("サイズ: ${info.displaySize}")
                Text("長さ: ${info.displayDuration}")
                Text("解像度: ${info.displayResolution}")
                Text("ビットレート: ${info.displayBitrate}")
                Text("音声ビットレート: ${info.displayAudioBitrate}")
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
        Text("次へ")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressionOptionsContent(
    options: CompressionOptions,
    viewModel: MainViewModel,
    videoInfo: VideoInfo?
) {
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
            Text("${options.bitratePercentage}%")
            androidx.compose.material3.Slider(
                value = options.bitratePercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(bitratePercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        BitrateMode.DIRECT -> {
            OutlinedTextField(
                value = options.bitrateDirectKbps.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(bitrateDirectKbps = it)) }
                },
                label = { Text(stringResource(R.string.kbps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        BitrateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.bitratePreset.labelJa)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BitratePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.labelJa) },
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
            Text("${options.audioBitratePercentage}%")
            androidx.compose.material3.Slider(
                value = options.audioBitratePercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(audioBitratePercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        BitrateMode.DIRECT -> {
            OutlinedTextField(
                value = options.audioBitrateDirectKbps.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(audioBitrateDirectKbps = it)) }
                },
                label = { Text(stringResource(R.string.kbps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        BitrateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.audioBitratePreset.labelJa)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AudioBitratePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.labelJa) },
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
                            FrameRateMode.DIRECT -> stringResource(R.string.direct)
                            FrameRateMode.PRESET -> stringResource(R.string.preset)
                        }
                    )
                }
            )
        }
    }

    when (options.frameRateMode) {
        FrameRateMode.DIRECT -> {
            OutlinedTextField(
                value = options.frameRateDirectFps.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateOptions(options.copy(frameRateDirectFps = it)) }
                },
                label = { Text(stringResource(R.string.fps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        FrameRateMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.frameRatePreset.labelJa)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    FrameRatePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.labelJa) },
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
            Text("${options.resolutionPercentage}%")
            androidx.compose.material3.Slider(
                value = options.resolutionPercentage.toFloat(),
                onValueChange = { viewModel.updateOptions(options.copy(resolutionPercentage = it.toInt())) },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        ResolutionMode.DIRECT -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = options.resolutionKeepAspectRatio,
                        onCheckedChange = { checked ->
                            viewModel.updateOptions(options.copy(resolutionKeepAspectRatio = checked))
                        }
                    )
                    Text(stringResource(R.string.keep_aspect_ratio))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = options.resolutionDirectWidth.toString(),
                        onValueChange = { v ->
                            val newWidth = v.toIntOrNull() ?: 0
                            if (options.resolutionKeepAspectRatio && videoInfo != null && videoInfo.width > 0 && videoInfo.height > 0) {
                                val newHeight = (newWidth.toFloat() * videoInfo.height / videoInfo.width).toInt()
                                viewModel.updateOptions(options.copy(resolutionDirectWidth = newWidth, resolutionDirectHeight = newHeight))
                            } else {
                                viewModel.updateOptions(options.copy(resolutionDirectWidth = newWidth))
                            }
                        },
                        label = { Text(stringResource(R.string.width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = options.resolutionDirectHeight.toString(),
                        onValueChange = { v ->
                            val newHeight = v.toIntOrNull() ?: 0
                            if (options.resolutionKeepAspectRatio && videoInfo != null && videoInfo.width > 0 && videoInfo.height > 0) {
                                val newWidth = (newHeight.toFloat() * videoInfo.width / videoInfo.height).toInt()
                                viewModel.updateOptions(options.copy(resolutionDirectWidth = newWidth, resolutionDirectHeight = newHeight))
                            } else {
                                viewModel.updateOptions(options.copy(resolutionDirectHeight = newHeight))
                            }
                        },
                        label = { Text(stringResource(R.string.height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        ResolutionMode.PRESET -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.resolutionPreset.getLabelJa(videoInfo?.width ?: 0, videoInfo?.height ?: 0))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ResolutionPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.getLabelJa(videoInfo?.width ?: 0, videoInfo?.height ?: 0)) },
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
private fun ProgressStepContent(
    state: CompressionState,
    onCancel: () -> Unit,
    onBackToOptions: () -> Unit
) {
    when (state) {
        is CompressionState.Preparing -> {
            Text("圧縮準備中...")
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
                Text("%.1f%%".format(state.progressPercent))
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
                Text("オプションへ戻る")
            }
        }
        is CompressionState.Cancelled -> {
            Text("圧縮がキャンセルされました")
            Button(onClick = onBackToOptions, modifier = Modifier.fillMaxWidth()) {
                Text("オプションへ戻る")
            }
        }
        else -> {
            Text("圧縮待機中")
            Button(onClick = onBackToOptions, modifier = Modifier.fillMaxWidth()) {
                Text("オプションへ戻る")
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
            Text("元サイズ: %.1f MB".format(originalMb))
            Text("圧縮後: %.1f MB".format(outputMb))
            if (state.originalSizeBytes > 0) {
                Text("圧縮率: %.1f%%".format(outputMb / originalMb * 100))
            }
            Text("保存先: ${state.outputPath}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("閉じる")
    }
}
