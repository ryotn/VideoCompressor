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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryotn.videocompressor.ui.MainScreen
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

                    var permissionsChecked by remember { mutableStateOf(false) }
                    var showNotificationDialog by remember { mutableStateOf(false) }
                    var showSaveDirectoryDialog by remember { mutableStateOf(false) }
                    val saveDirectoryUri by vm.saveDirectoryUri.collectAsState()

                    var permissionsToRequest by remember { mutableStateOf<Array<String>>(emptyArray()) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        // Proceed regardless of whether the user granted or denied
                        permissionsChecked = true
                    }

                    val videoPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
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
                        val perms = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        val notGranted = perms.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (notGranted.isEmpty()) {
                            permissionsChecked = true
                        } else {
                            permissionsToRequest = notGranted.toTypedArray()
                            showNotificationDialog = true
                        }
                    }

                    LaunchedEffect(permissionsChecked, saveDirectoryUri) {
                        if (permissionsChecked && saveDirectoryUri == null) {
                            showSaveDirectoryDialog = true
                        }
                    }

                    if (showNotificationDialog) {
                        AlertDialog(
                            onDismissRequest = { /* No-op to force user to click OK */ },
                            title = { Text(stringResource(id = R.string.notification_permission_title)) },
                            text = { Text(stringResource(id = R.string.notification_permission_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showNotificationDialog = false
                                    permissionLauncher.launch(permissionsToRequest)
                                }) {
                                    Text(stringResource(id = R.string.ok))
                                }
                            }
                        )
                    }

                    if (showSaveDirectoryDialog) {
                        AlertDialog(
                            onDismissRequest = { /* No-op */ },
                            title = { Text(stringResource(id = R.string.save_directory_permission_title)) },
                            text = { Text(stringResource(id = R.string.save_directory_permission_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSaveDirectoryDialog = false
                                    saveDirectoryLauncher.launch(null)
                                }) {
                                    Text(stringResource(id = R.string.ok))
                                }
                            }
                        )
                    }

                    MainScreen(
                        viewModel = vm,
                        onSelectVideo = { videoPickerLauncher.launch("video/*") },
                        onSelectSaveDirectory = { saveDirectoryLauncher.launch(saveDirectoryUri) }
                    )
                }
            }
        }
    }
}
