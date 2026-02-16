package com.tunedroid.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tunedroid.app.R
import com.tunedroid.app.data.database.DownloadEntity
import com.tunedroid.app.data.database.DownloadStatus
import com.tunedroid.app.data.repository.DownloadRepository
import com.tunedroid.app.engine.*
import com.tunedroid.app.service.DownloadService
import kotlinx.coroutines.launch

sealed class HomeState {
    data object Empty : HomeState()
    data object Loading : HomeState()
    data class MediaLoaded(
        val mediaInfo: MediaInfo,
        val selectedStream: SelectedStream,
        val availablePresets: List<FormatPreset>
    ) : HomeState()
    data class Error(val message: String) : HomeState()
}

@Composable
private fun MarqueeLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "marquee")
    val offset by infiniteTransition.animateFloat(
        initialValue = 300f,
        targetValue = -300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "marquee-offset"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Indeterminate progress bar
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Scrolling text
        Box(
            modifier = Modifier
                .width(200.dp)
                .clipToBounds()
        ) {
            Text(
                text = "Working on it...  ",  // Extra spaces for gap before repeat
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.graphicsLayer {
                    translationX = offset
                }
            )
        }
    }
}

@Composable
fun HomeScreen(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    onNavigateToDownloads: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DownloadRepository(context) }

    var urlText by remember { mutableStateOf("") }
    var homeState by remember { mutableStateOf<HomeState>(HomeState.Empty) }
    var selectedPreset by remember { mutableStateOf(FormatPreset.MP3_128) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateDownload by remember { mutableStateOf<DownloadEntity?>(null) }
    var pendingMediaInfo by remember { mutableStateOf<MediaInfo?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Handle shared URL
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            urlText = sharedUrl
            onSharedUrlConsumed()
            // Auto-fetch
            homeState = HomeState.Loading
            scope.launch {
                val result = MetadataFetcher.fetch(sharedUrl)
                homeState = when (result) {
                    is MetadataResult.Success -> {
                        val stream = StreamSelector.selectBest(result.mediaInfo.audioStreams)
                        if (stream != null) {
                            val presets = FormatPreset.availablePresets(stream.bitrate)
                            selectedPreset = presets.first()
                            HomeState.MediaLoaded(result.mediaInfo, stream, presets)
                        } else {
                            HomeState.Error("No audio streams found")
                        }
                    }
                    is MetadataResult.Error -> HomeState.Error(result.message)
                }
            }
        }
    }

    fun fetchMetadata() {
        if (urlText.isBlank()) return
        if (!MediaUrlParser.isValidUrl(urlText)) {
            homeState = HomeState.Error("Invalid URL. Please enter a valid video URL.")
            return
        }
        homeState = HomeState.Loading
        scope.launch {
            val result = MetadataFetcher.fetch(urlText)
            homeState = when (result) {
                is MetadataResult.Success -> {
                    val stream = StreamSelector.selectBest(result.mediaInfo.audioStreams)
                    if (stream != null) {
                        val presets = FormatPreset.availablePresets(stream.bitrate)
                        selectedPreset = presets.first()
                        HomeState.MediaLoaded(result.mediaInfo, stream, presets)
                    } else {
                        HomeState.Error("No audio streams found")
                    }
                }
                is MetadataResult.Error -> HomeState.Error(result.message)
            }
        }
    }

    fun startDownload(mediaInfo: MediaInfo, stream: SelectedStream) {
        scope.launch {
            // Check for duplicates
            val parseResult = MediaUrlParser.parse(mediaInfo.url)
            val mediaId = parseResult.mediaId ?: mediaInfo.id
            val existing = repository.findCompletedByMediaId(mediaId)

            if (existing != null) {
                duplicateDownload = existing
                pendingMediaInfo = mediaInfo
                showDuplicateDialog = true
                return@launch
            }

            performEnqueue(repository, context, mediaInfo, stream, mediaId, selectedPreset,
                onDone = {
                    urlText = ""
                    homeState = HomeState.Empty
                },
                onNavigateToDownloads = onNavigateToDownloads
            )
        }
    }

    // Duplicate dialog
    if (showDuplicateDialog && duplicateDownload != null) {
        AlertDialog(
            onDismissRequest = {
                showDuplicateDialog = false
                duplicateDownload = null
            },
            title = { Text("Already Downloaded") },
            text = {
                val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(duplicateDownload!!.completedAt ?: duplicateDownload!!.createdAt))
                Text("You already downloaded this on $date. Download again?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDuplicateDialog = false
                    val info = pendingMediaInfo
                    if (info != null) {
                        val state = homeState
                        if (state is HomeState.MediaLoaded) {
                            scope.launch {
                                val parseResult = MediaUrlParser.parse(info.url)
                                val mediaId = parseResult.mediaId ?: info.id
                                performEnqueue(repository, context, info, state.selectedStream, mediaId, selectedPreset,
                                    onDone = {
                                        urlText = ""
                                        homeState = HomeState.Empty
                                    },
                                    onNavigateToDownloads = onNavigateToDownloads
                                )
                            }
                        }
                    }
                    duplicateDownload = null
                    pendingMediaInfo = null
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDuplicateDialog = false
                    duplicateDownload = null
                    pendingMediaInfo = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // URL Input
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a video URL") },
                singleLine = true,
                enabled = homeState !is HomeState.Loading,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Fetch button — always directly under URL field
            Button(
                onClick = { fetchMetadata() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                enabled = urlText.isNotBlank() && homeState !is HomeState.Loading
            ) {
                Text("Fetch")
            }

            when (val state = homeState) {
                HomeState.Empty -> {
                    // Branding image fills remaining space, tip at bottom
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Branding image — as large as possible
                        Image(
                            painter = painterResource(id = R.drawable.tunedroid_branded),
                            contentDescription = "TuneDroid",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit
                        )

                        // Tip text — anchored to the bottom
                        Text(
                            text = "Tip: Share a video directly to TuneDroid from your browser or other apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                        )
                    }
                }

                HomeState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Show branding during loading
                        Image(
                            painter = painterResource(id = R.drawable.tunedroid_branded),
                            contentDescription = "TuneDroid",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.FillWidth
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        MarqueeLoadingIndicator()
                    }
                }

                is HomeState.MediaLoaded -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Media info card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail
                                if (state.mediaInfo.thumbnailUrl != null) {
                                    AsyncImage(
                                        model = state.mediaInfo.thumbnailUrl,
                                        contentDescription = "Thumbnail",
                                        modifier = Modifier
                                            .size(120.dp, 68.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.mediaInfo.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (state.mediaInfo.author.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = state.mediaInfo.author,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatDuration(state.mediaInfo.duration),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Format preset picker — show all presets, grey out ineligible
                        Text(
                            text = "Audio Quality",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        FormatPreset.entries.forEach { preset ->
                            val eligible = FormatPreset.isEligible(preset, state.selectedStream.bitrate)
                            val isSelected = selectedPreset == preset

                            // Dynamic label for ORIGINAL preset
                            val displayLabel = if (preset == FormatPreset.ORIGINAL) {
                                val ext = state.selectedStream.extension.uppercase()
                                val bitrate = state.selectedStream.bitrate
                                "Original — $ext $bitrate kbps"
                            } else {
                                preset.label
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { if (eligible) selectedPreset = preset },
                                    enabled = eligible
                                )
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (eligible) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Download button
                        Button(
                            onClick = {
                                startDownload(state.mediaInfo, state.selectedStream)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Download",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                is HomeState.Error -> {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = state.message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            homeState = HomeState.Empty
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

private suspend fun performEnqueue(
    repository: DownloadRepository,
    context: android.content.Context,
    mediaInfo: MediaInfo,
    stream: SelectedStream,
    mediaId: String,
    selectedPreset: FormatPreset,
    onDone: () -> Unit,
    onNavigateToDownloads: () -> Unit
) {
    val download = DownloadEntity(
        mediaId = mediaId,
        title = mediaInfo.title,
        author = mediaInfo.author,
        url = mediaInfo.url,
        formatPreset = selectedPreset.name,
        fileSize = stream.estimatedFileSize,
        status = DownloadStatus.QUEUED,
        thumbnailUrl = mediaInfo.thumbnailUrl,
        duration = mediaInfo.duration,
        sourceBitrate = stream.bitrate
    )
    val downloadId = repository.enqueue(download)
    DownloadService.enqueue(context, downloadId)

    onDone()

    // Auto-navigate to Downloads screen
    onNavigateToDownloads()
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}
