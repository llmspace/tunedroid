package com.tunedroid.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tunedroid.app.data.database.DownloadEntity
import com.tunedroid.app.data.database.DownloadStatus
import com.tunedroid.app.data.repository.DownloadRepository
import com.tunedroid.app.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DownloadRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasStoragePermission = isGranted
        if (isGranted) {
            scope.launch {
                val recovered = withContext(Dispatchers.IO) {
                    repository.recoverExistingFiles()
                }
                if (recovered > 0) {
                    snackbarHostState.showSnackbar(
                        message = "Recovered $recovered previously downloaded file${if (recovered != 1) "s" else ""}",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    val allDownloads by repository.getAllDownloads().collectAsState(initial = emptyList())

    val activeDownloads = allDownloads.filter {
        it.status in listOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.CONVERTING,
            DownloadStatus.FINALIZING
        )
    }
    val failedDownloads = allDownloads.filter { it.status == DownloadStatus.FAILED }
    val completedDownloads = allDownloads.filter { it.status == DownloadStatus.COMPLETED }

    // Recover existing files after reinstall (DB empty but files exist on disk)
    // Only attempt if we have storage read permission
    LaunchedEffect(Unit) {
        if (!hasStoragePermission) return@LaunchedEffect
        val recovered = withContext(Dispatchers.IO) {
            repository.recoverExistingFiles()
        }
        if (recovered > 0) {
            snackbarHostState.showSnackbar(
                message = "Recovered $recovered previously downloaded file${if (recovered != 1) "s" else ""}",
                duration = SnackbarDuration.Short
            )
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var downloadToDelete by remember { mutableStateOf<DownloadEntity?>(null) }

    if (showDeleteDialog && downloadToDelete != null) {
        var deleteFromApp by remember { mutableStateOf(true) }
        var deleteFromDevice by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                downloadToDelete = null
            },
            title = { Text("Delete Download") },
            text = {
                Column {
                    Text("\"${downloadToDelete!!.title}\"")
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteFromApp,
                            onCheckedChange = { deleteFromApp = it }
                        )
                        Text(
                            text = "Remove from app list",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteFromDevice,
                            onCheckedChange = { deleteFromDevice = it }
                        )
                        Text(
                            text = "Delete file from device",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    if (deleteFromDevice && !deleteFromApp) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Note: File will be deleted but entry will remain in app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Capture values before clearing state — the coroutine runs async
                        val targetDownload = downloadToDelete
                        val shouldDeleteFromApp = deleteFromApp
                        val shouldDeleteFromDevice = deleteFromDevice

                        showDeleteDialog = false
                        downloadToDelete = null

                        if (targetDownload != null) {
                            scope.launch {
                                // Delete from device if requested
                                if (shouldDeleteFromDevice) {
                                    targetDownload.filePath?.let { path ->
                                        val success = try {
                                            File(path).delete()
                                        } catch (e: Exception) {
                                            android.util.Log.w("DownloadsScreen", "Failed to delete file: $path", e)
                                            false
                                        }
                                        if (!success) {
                                            snackbarHostState.showSnackbar(
                                                message = "Could not delete file from device",
                                                duration = SnackbarDuration.Long
                                            )
                                        }
                                    }
                                }

                                // Remove from app database if requested
                                if (shouldDeleteFromApp) {
                                    repository.delete(targetDownload)
                                }
                            }
                        }
                    },
                    enabled = deleteFromApp || deleteFromDevice
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    downloadToDelete = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (allDownloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (!hasStoragePermission) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = "Storage permission needed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TuneDroid needs permission to find your previously downloaded audio files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val activity = context as? ComponentActivity
                            val prefs = context.getSharedPreferences("tunedroid_prefs", 0)
                            val alreadyRequested = prefs.getBoolean("storage_permission_requested", false)
                            if (alreadyRequested && activity != null &&
                                !activity.shouldShowRequestPermissionRationale(readPermission)
                            ) {
                                // Permanently denied — open app settings
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                prefs.edit().putBoolean("storage_permission_requested", true).apply()
                                permissionLauncher.launch(readPermission)
                            }
                        }) {
                            Text("Grant Permission")
                        }
                    }
                } else {
                    Text(
                        text = "No downloads yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // Active & Failed downloads
            if (activeDownloads.isNotEmpty() || failedDownloads.isNotEmpty()) {
                item {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(activeDownloads, key = { it.id }) { download ->
                    ActiveDownloadItem(
                        download = download,
                        onAbort = {
                            DownloadService.abort(context, download.id)
                        }
                    )
                }

                items(failedDownloads, key = { it.id }) { download ->
                    FailedDownloadItem(
                        download = download,
                        onRetry = {
                            DownloadService.retry(context, download.id)
                        }
                    )
                }
            }

            // Completed downloads
            if (completedDownloads.isNotEmpty()) {
                item {
                    Text(
                        text = "Completed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(completedDownloads, key = { it.id }) { download ->
                    CompletedDownloadItem(
                        download = download,
                        onPlay = {
                            download.filePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            ),
                                            "audio/*"
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) { }
                                }
                            }
                        },
                        onShare = {
                            download.filePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share audio"))
                                }
                            }
                        },
                        onDelete = {
                            downloadToDelete = download
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ActiveDownloadItem(download: DownloadEntity, onAbort: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val statusText = when (download.status) {
                    DownloadStatus.QUEUED -> "Queued"
                    DownloadStatus.DOWNLOADING -> "Downloading... ${download.progress.toInt()}%"
                    DownloadStatus.CONVERTING -> "Converting..."
                    DownloadStatus.FINALIZING -> "Finalizing..."
                    else -> download.status
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (download.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            IconButton(onClick = onAbort) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Abort",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FailedDownloadItem(download: DownloadEntity, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Failed — ${download.errorMessage ?: "Unknown error"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CompletedDownloadItem(
    download: DownloadEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                val ext = download.filePath?.substringAfterLast(".")?.uppercase() ?: "AUDIO"
                val size = formatFileSize(download.fileSize)
                Text(
                    text = "$ext — $size",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDate(download.completedAt ?: download.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
