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
import com.ryotn.videocompressor.ui.theme.VideoCompressorTheme
import com.ryotn.videocompressor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
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
                    var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
                    var showSaveDirectoryRationale by rememberSaveable { mutableStateOf(false) }
                    val saveDirectoryUri by vm.saveDirectoryUri.collectAsState()

                    val perms = buildList {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    val notGranted = perms.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
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
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        handleIntent(intent, vm)
    }

    private fun handleIntent(intent: Intent?, vm: MainViewModel) {
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
}
