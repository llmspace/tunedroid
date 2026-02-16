package com.tunedroid.app.ui.screens

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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

    // For MediaStore.createDeleteRequest() on API 30+ (MIUI/scoped storage)
    var pendingDeleteAppAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val mediaStoreDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // User approved deletion via system dialog — also remove from app DB
            pendingDeleteAppAction?.invoke()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "File deletion was cancelled",
                    duration = SnackbarDuration.Short
                )
            }
        }
        pendingDeleteAppAction = null
    }

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
                            onCheckedChange = {
                                // Can't uncheck if "Delete from device" is checked
                                if (!deleteFromDevice) deleteFromApp = it
                            },
                            enabled = !deleteFromDevice
                        )
                        Text(
                            text = "Remove from app list",
                            modifier = Modifier.padding(start = 8.dp),
                            color = if (deleteFromDevice)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteFromDevice,
                            onCheckedChange = {
                                deleteFromDevice = it
                                // Auto-check "Remove from app list" when deleting from device
                                if (it) deleteFromApp = true
                            }
                        )
                        Text(
                            text = "Delete file from device",
                            modifier = Modifier.padding(start = 8.dp)
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
                                if (shouldDeleteFromDevice) {
                                    targetDownload.filePath?.let { path ->
                                        val directSuccess = withContext(Dispatchers.IO) {
                                            deleteFileFromDevice(context, path)
                                        }
                                        if (!directSuccess) {
                                            // File.delete() and MediaStore delete failed —
                                            // try system delete dialog on API 30+ (MIUI etc.)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                val uri = withContext(Dispatchers.IO) {
                                                    getMediaStoreUriForDelete(context, path)
                                                }
                                                if (uri != null) {
                                                    try {
                                                        val deleteRequest = MediaStore.createDeleteRequest(
                                                            context.contentResolver,
                                                            listOf(uri)
                                                        )
                                                        // Store the app-list removal as pending
                                                        if (shouldDeleteFromApp) {
                                                            pendingDeleteAppAction = {
                                                                scope.launch { repository.delete(targetDownload) }
                                                            }
                                                        }
                                                        mediaStoreDeleteLauncher.launch(
                                                            IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                                                        )
                                                        return@launch // Don't remove from app yet — wait for result
                                                    } catch (e: Exception) {
                                                        Log.w("DownloadsScreen", "createDeleteRequest failed", e)
                                                        snackbarHostState.showSnackbar(
                                                            message = "Could not delete file from device",
                                                            duration = SnackbarDuration.Long
                                                        )
                                                    }
                                                } else {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Could not delete file from device",
                                                        duration = SnackbarDuration.Long
                                                    )
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar(
                                                    message = "Could not delete file from device",
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                        }
                                    }
                                }
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
                        },
                        onDelete = {
                            scope.launch {
                                repository.delete(download)
                            }
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
                                val uri = getShareableUri(context, path)
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "audio/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.w("DownloadsScreen", "No app to play audio", e)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("No app found to play audio")
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("File not found")
                                    }
                                }
                            }
                        },
                        onShare = {
                            download.filePath?.let { path ->
                                val uri = getShareableUri(context, path)
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(Intent.createChooser(intent, "Share audio"))
                                    } catch (e: Exception) {
                                        Log.w("DownloadsScreen", "Share failed", e)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Could not share file")
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("File not found")
                                    }
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
private fun FailedDownloadItem(download: DownloadEntity, onRetry: () -> Unit, onDelete: () -> Unit) {
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

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
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

/**
 * Get a content:// URI for an audio file via MediaStore.
 * On Android 10+ (API 29+), files in shared storage must be accessed through
 * MediaStore rather than direct File paths. This queries MediaStore by
 * matching the file's display name and returns the content URI.
 */
private fun getMediaStoreUri(contentResolver: ContentResolver, filePath: String): Uri? {
    val fileName = File(filePath).name
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(MediaStore.Audio.Media._ID)
    val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(fileName)

    contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            return ContentUris.withAppendedId(collection, id)
        }
    }
    return null
}

/**
 * Get a shareable URI for a file — uses MediaStore on API 29+, FileProvider on older.
 * If the file exists on disk but isn't in MediaStore, triggers a scan first.
 */
private fun getShareableUri(context: android.content.Context, filePath: String): Uri? {
    val file = File(filePath)
    if (!file.exists()) return null

    // Try MediaStore first on API 29+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        var uri = getMediaStoreUri(context.contentResolver, filePath)

        // If not in MediaStore yet, scan it in and retry
        if (uri == null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                arrayOf("audio/*")
            ) { _, scannedUri ->
                if (scannedUri != null) uri = scannedUri
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            uri?.let { return it }
            // Try one more time from MediaStore in case the callback URI was null
            getMediaStoreUri(context.contentResolver, filePath)?.let { return it }
        } else {
            return uri
        }
    }

    // Fallback to FileProvider
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        Log.w("DownloadsScreen", "FileProvider failed for: $filePath", e)
        null
    }
}

/**
 * Delete a file from the device using multiple strategies.
 * Tries direct File.delete() first (works when app created the file),
 * then falls back to MediaStore delete on API 29+.
 */
private fun deleteFileFromDevice(context: android.content.Context, filePath: String): Boolean {
    val file = File(filePath)

    // Strategy 1: Direct file delete — works when the app created the file in the
    // same install session, or on API 28 and below with WRITE_EXTERNAL_STORAGE
    if (file.exists()) {
        try {
            if (file.delete()) {
                Log.d("DownloadsScreen", "File.delete() succeeded: $filePath")
                // Also remove from MediaStore so it doesn't show as stale
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = getMediaStoreUri(context.contentResolver, filePath)
                    if (uri != null) {
                        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
                return true
            }
        } catch (e: Exception) {
            Log.w("DownloadsScreen", "File.delete() failed, trying MediaStore: $filePath", e)
        }
    }

    // Strategy 2: MediaStore delete on API 29+ (for files the app doesn't own directly)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        var uri = getMediaStoreUri(context.contentResolver, filePath)

        // If not in MediaStore but file exists, scan it in first then retry
        if (uri == null && file.exists()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                arrayOf("audio/*")
            ) { _, _ -> latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            uri = getMediaStoreUri(context.contentResolver, filePath)
        }

        if (uri != null) {
            try {
                val deleted = context.contentResolver.delete(uri, null, null) > 0
                if (deleted) {
                    Log.d("DownloadsScreen", "MediaStore delete succeeded: $filePath")
                    return true
                }
            } catch (e: SecurityException) {
                Log.w("DownloadsScreen", "MediaStore SecurityException (no ownership): $filePath", e)
            } catch (e: Exception) {
                Log.w("DownloadsScreen", "MediaStore delete failed: $filePath", e)
            }
        }
    }

    // Check if file is actually gone (might have been deleted by one of the strategies above)
    if (!file.exists()) return true

    Log.w("DownloadsScreen", "All delete strategies failed for: $filePath")
    return false
}

/**
 * Get a MediaStore content URI for a file, specifically for use with
 * MediaStore.createDeleteRequest() on API 30+. Scans the file into
 * MediaStore if it's not already indexed.
 */
private fun getMediaStoreUriForDelete(context: android.content.Context, filePath: String): Uri? {
    val file = File(filePath)
    if (!file.exists()) return null

    // Try MediaStore lookup first
    var uri = getMediaStoreUri(context.contentResolver, filePath)

    // If not in MediaStore, scan it in and retry
    if (uri == null) {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            arrayOf("audio/*")
        ) { _, scannedUri ->
            if (scannedUri != null) uri = scannedUri
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (uri == null) {
            uri = getMediaStoreUri(context.contentResolver, filePath)
        }
    }
    return uri
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
