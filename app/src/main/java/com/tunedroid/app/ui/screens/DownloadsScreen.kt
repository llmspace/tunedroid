package com.tunedroid.app.ui.screens

import android.content.Intent
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tunedroid.app.data.database.DownloadEntity
import com.tunedroid.app.data.database.DownloadStatus
import com.tunedroid.app.data.repository.DownloadRepository
import com.tunedroid.app.service.DownloadService
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DownloadRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                        scope.launch {
                            var fileDeleteSuccess = true

                            // Delete from device if requested
                            if (deleteFromDevice) {
                                downloadToDelete?.filePath?.let { path ->
                                    fileDeleteSuccess = try {
                                        File(path).delete()
                                    } catch (e: Exception) {
                                        android.util.Log.w("DownloadsScreen", "Failed to delete file: $path", e)
                                        false
                                    }
                                    if (!fileDeleteSuccess) {
                                        snackbarHostState.showSnackbar(
                                            message = "Could not delete file from device",
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                }
                            }

                            // Remove from app database if requested
                            if (deleteFromApp) {
                                downloadToDelete?.let { repository.delete(it) }
                            }
                        }
                        showDeleteDialog = false
                        downloadToDelete = null
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
                Text(
                    text = "No downloads yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
