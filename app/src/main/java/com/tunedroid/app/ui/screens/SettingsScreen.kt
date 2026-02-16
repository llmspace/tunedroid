package com.tunedroid.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunedroid.app.BuildConfig
import com.tunedroid.app.data.PreferencesManager
import com.tunedroid.app.data.repository.DownloadRepository
import com.tunedroid.app.engine.EngineUpdater
import com.tunedroid.app.updater.AppUpdateChecker
import com.tunedroid.app.util.StoragePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsManager = remember { PreferencesManager(context) }
    val repository = remember { DownloadRepository(context) }

    val storagePath by prefsManager.storagePath.collectAsState(initial = "")
    val autoCheckUpdates by prefsManager.autoCheckUpdates.collectAsState(initial = true)
    val autoUpdateEngine by prefsManager.autoUpdateEngine.collectAsState(initial = false)
    val deleteOriginal by prefsManager.deleteOriginal.collectAsState(initial = true)
    val wifiOnly by prefsManager.wifiOnly.collectAsState(initial = false)

    var engineVersion by remember { mutableStateOf("Loading...") }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupDialogMessage by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        engineVersion = EngineUpdater.getEngineVersion()
    }

    // Cleanup dialog
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("Cleanup Results") },
            text = { Text(cleanupDialogMessage) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.cleanupOrphans()
                        showCleanupDialog = false
                        Toast.makeText(context, "Cleanup completed", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Clean Up", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // App info + update button — at the top
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TuneDroid v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Engine: $engineVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = {
                        isCheckingUpdate = true
                        scope.launch {
                            try {
                                val appUpdate = AppUpdateChecker.check(context)
                                if (appUpdate != null && appUpdate.isNewer) {
                                    withContext(Dispatchers.Main) {
                                        AppUpdateChecker.showUpdateDialog(context, appUpdate)
                                    }
                                } else {
                                    val result = EngineUpdater.update(context)
                                    engineVersion = EngineUpdater.getEngineVersion()
                                    when (result) {
                                        is com.tunedroid.app.engine.UpdateResult.Updated ->
                                            Toast.makeText(context, "Engine updated to ${result.version}", Toast.LENGTH_SHORT).show()
                                        is com.tunedroid.app.engine.UpdateResult.AlreadyUpToDate ->
                                            Toast.makeText(context, "Everything is up to date", Toast.LENGTH_SHORT).show()
                                        is com.tunedroid.app.engine.UpdateResult.Error ->
                                            Toast.makeText(context, "Update check failed: ${result.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Update check failed", Toast.LENGTH_SHORT).show()
                            } finally {
                                isCheckingUpdate = false
                            }
                        }
                    },
                    enabled = !isCheckingUpdate
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("Check for updates", style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider()

            // Storage location (display-only)
            SettingsInfoItem(
                title = "Storage location",
                subtitle = storagePath.ifBlank { "Default" }
            )

            HorizontalDivider()

            // Auto-check for app updates
            SettingsToggle(
                title = "Auto-check for app updates",
                checked = autoCheckUpdates,
                onCheckedChange = {
                    scope.launch { prefsManager.setAutoCheckUpdates(it) }
                }
            )

            HorizontalDivider()

            // Auto-update extraction engine
            SettingsToggle(
                title = "Auto-update extraction engine",
                checked = autoUpdateEngine,
                onCheckedChange = {
                    scope.launch { prefsManager.setAutoUpdateEngine(it) }
                }
            )

            HorizontalDivider()

            // Delete original audio file after conversion
            SettingsToggle(
                title = "Delete original audio file after conversion",
                checked = deleteOriginal,
                onCheckedChange = {
                    scope.launch { prefsManager.setDeleteOriginal(it) }
                }
            )

            HorizontalDivider()

            // Wi-Fi only downloads
            SettingsToggle(
                title = "Wi-Fi only downloads",
                checked = wifiOnly,
                onCheckedChange = {
                    scope.launch { prefsManager.setWifiOnly(it) }
                }
            )

            // File management access (API 30+ only — needed for MIUI and other restrictive ROMs)
            if (StoragePermissionHelper.isAllFilesAccessRelevant()) {
                HorizontalDivider()

                var hasAllFilesAccess by remember {
                    mutableStateOf(StoragePermissionHelper.hasAllFilesAccess())
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasAllFilesAccess = StoragePermissionHelper.hasAllFilesAccess()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                SettingsItem(
                    title = "File management access",
                    subtitle = if (hasAllFilesAccess) "Granted" else "Required for deleting files on some devices",
                    onClick = {
                        if (!hasAllFilesAccess) {
                            context.startActivity(
                                StoragePermissionHelper.createAllFilesAccessIntent(context)
                            )
                        }
                    }
                )
            }

            HorizontalDivider()

            // Scan for orphaned files — compact row
            SettingsItem(
                title = "Scan for orphaned files",
                subtitle = if (isScanning) "Scanning..." else "Find files or records without matches",
                onClick = {
                    if (isScanning) return@SettingsItem
                    isScanning = true
                    scope.launch {
                        val orphanedFiles = repository.findOrphanedFiles()
                        val orphanedRecords = repository.findOrphanedRecords()

                        cleanupDialogMessage = buildString {
                            appendLine("Found:")
                            appendLine("• ${orphanedFiles.size} orphaned file${if (orphanedFiles.size != 1) "s" else ""}")
                            appendLine("• ${orphanedRecords.size} orphaned record${if (orphanedRecords.size != 1) "s" else ""}")
                            appendLine()
                            if (orphanedFiles.isNotEmpty() || orphanedRecords.isNotEmpty()) {
                                appendLine("Delete these items?")
                            } else {
                                appendLine("No cleanup needed!")
                            }
                        }
                        isScanning = false
                        showCleanupDialog = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showChevron: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsInfoItem(
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
