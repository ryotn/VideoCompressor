package com.ryotn.videocompressor

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryotn.videocompressor.ui.MainScreen
import com.ryotn.videocompressor.service.CompressionService
import com.ryotn.videocompressor.data.CompressionState
import com.ryotn.videocompressor.ui.theme.VideoCompressorTheme
import com.ryotn.videocompressor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private var showShareIntentBlockedDialog by mutableStateOf(false)
    private var showInvalidShareIntentDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoCompressorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: MainViewModel = viewModel()

                    LaunchedEffect(Unit) {
                        handleIntent(intent, vm)
                    }

                    var permissionsGranted by remember { mutableStateOf(false) }
                    val saveDirectoryUri by vm.saveDirectoryUri.collectAsState()
                    var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
                    var showSaveDirectoryRationale by rememberSaveable { mutableStateOf(false) }
                    val perms = remember { buildList { add(Manifest.permission.POST_NOTIFICATIONS) } }
                    val notGranted = remember(permissionsGranted) {
                        perms.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { results ->
                        permissionsGranted = results.values.all { it }
                        if (permissionsGranted && saveDirectoryUri == null) {
                            showSaveDirectoryRationale = true
                        }
                    }

                    val videoPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        uri?.let { vm.onVideoSelected(it) }
                    }

                    val saveDirectoryLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri: Uri? ->
                        uri?.let {
                            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            runCatching {
                                contentResolver.takePersistableUriPermission(it, flags)
                            }
                            vm.updateSaveDirectory(it)
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (notGranted.isEmpty()) {
                            permissionsGranted = true
                            if (saveDirectoryUri == null) {
                                showSaveDirectoryRationale = true
                            }
                        } else {
                            showNotificationRationale = true
                        }
                    }

                    if (showNotificationRationale) {
                        AlertDialog(
                            onDismissRequest = { /* Require user to click OK */ },
                            title = { Text(stringResource(R.string.notification_permission_title)) },
                            text = { Text(stringResource(R.string.notification_permission_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showNotificationRationale = false
                                    permissionLauncher.launch(notGranted.toTypedArray())
                                }) {
                                    Text(stringResource(R.string.ok))
                                }
                            }
                        )
                    }

                    if (showSaveDirectoryRationale) {
                        AlertDialog(
                            onDismissRequest = { /* Require user to click OK */ },
                            title = { Text(stringResource(R.string.save_directory_permission_title)) },
                            text = { Text(stringResource(R.string.save_directory_permission_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSaveDirectoryRationale = false
                                    saveDirectoryLauncher.launch(null)
                                }) {
                                    Text(stringResource(R.string.ok))
                                }
                            }
                        )
                    }

                    if (showShareIntentBlockedDialog) {
                        AlertDialog(
                            onDismissRequest = { showShareIntentBlockedDialog = false },
                            title = { Text(stringResource(R.string.share_video_blocked_title)) },
                            text = { Text(stringResource(R.string.share_video_blocked_message)) },
                            confirmButton = {
                                TextButton(onClick = { showShareIntentBlockedDialog = false }) {
                                    Text(stringResource(R.string.ok))
                                }
                            }
                        )
                    }

                    if (showInvalidShareIntentDialog) {
                        AlertDialog(
                            onDismissRequest = { showInvalidShareIntentDialog = false },
                            title = { Text(stringResource(R.string.invalid_share_video_title)) },
                            text = { Text(stringResource(R.string.invalid_share_video_message)) },
                            confirmButton = {
                                TextButton(onClick = { showInvalidShareIntentDialog = false }) {
                                    Text(stringResource(R.string.ok))
                                }
                            }
                        )
                    }

                    MainScreen(
                        viewModel = vm,
                        onSelectVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        onSelectSaveDirectory = { saveDirectoryLauncher.launch(saveDirectoryUri) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showShareIntentBlockedDialog = false
        showInvalidShareIntentDialog = false
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        handleIntent(intent, vm)
    }

    private fun handleIntent(intent: Intent?, vm: MainViewModel) {
        when (val sharedVideoIntent = getSharedVideoIntent(intent)) {
            SharedVideoIntent.None -> Unit
            SharedVideoIntent.InvalidMultiple -> {
                showInvalidShareIntentDialog = true
            }
            is SharedVideoIntent.Valid -> {
                val compressionState = vm.compressionState.value
                val isCompressing = compressionState is CompressionState.Preparing ||
                    compressionState is CompressionState.InProgress
                if (isCompressing) {
                    showShareIntentBlockedDialog = true
                } else {
                    vm.onVideoSelected(sharedVideoIntent.uri)
                }
            }
        }

        if (intent?.getBooleanExtra("show_completion", false) == true) {
            val outputPath = intent.getStringExtra(CompressionService.EXTRA_OUTPUT_PATH) ?: ""
            val originalSize = intent.getLongExtra(CompressionService.EXTRA_ORIGINAL_SIZE, 0L)
            val outputSize = intent.getLongExtra(CompressionService.EXTRA_OUTPUT_SIZE, 0L)
            // Create a unique ID for this intent based on its data
            val intentId = intent.hashCode()
            vm.setCompressionCompleted(outputPath, originalSize, outputSize, intentId)
            // Remove the flag so it doesn't trigger again on rotation, etc.
            intent.removeExtra("show_completion")
        }
    }

    private fun getSharedVideoIntent(intent: Intent?): SharedVideoIntent {
        if (intent?.action != Intent.ACTION_SEND) return SharedVideoIntent.None
        if (intent.type?.startsWith("video/") != true) return SharedVideoIntent.None

        val fromExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (fromExtra != null) return SharedVideoIntent.Valid(fromExtra)

        val clipData = intent.clipData ?: return SharedVideoIntent.None
        if (clipData.itemCount != 1) return SharedVideoIntent.InvalidMultiple
        return clipData.getItemAt(0).uri?.let { SharedVideoIntent.Valid(it) } ?: SharedVideoIntent.None
    }

    private sealed interface SharedVideoIntent {
        data object None : SharedVideoIntent
        data object InvalidMultiple : SharedVideoIntent
        data class Valid(val uri: Uri) : SharedVideoIntent
    }
}
