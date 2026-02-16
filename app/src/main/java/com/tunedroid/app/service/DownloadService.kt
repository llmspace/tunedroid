package com.tunedroid.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.tunedroid.app.MainActivity
import com.tunedroid.app.TuneDroidApp
import com.tunedroid.app.data.PreferencesManager
import com.tunedroid.app.data.database.DownloadEntity
import com.tunedroid.app.data.database.DownloadStatus
import com.tunedroid.app.data.repository.DownloadRepository
import android.util.Log
import com.tunedroid.app.engine.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DownloadRepository
    private lateinit var prefsManager: PreferencesManager
    private var isProcessing = false
    private var currentDownloadId: Long? = null
    private var currentProcessId: String? = null
    @Volatile
    private var isCancelled = false

    companion object {
        private const val TAG = "TuneDroid"
        const val ACTION_ENQUEUE = "com.tunedroid.app.ENQUEUE"
        const val ACTION_ABORT = "com.tunedroid.app.ABORT"
        const val ACTION_RETRY = "com.tunedroid.app.RETRY"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_NOTIFICATION_ID = 1002

        fun enqueue(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startForegroundService(intent)
        }

        fun abort(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ABORT
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun retry(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RETRY
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(this)
        prefsManager = PreferencesManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createIdleNotification())

        when (intent?.action) {
            ACTION_ENQUEUE -> {
                processQueue()
            }
            ACTION_ABORT -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                if (downloadId > 0) abortDownload(downloadId)
            }
            ACTION_RETRY -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                if (downloadId > 0) retryDownload(downloadId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        serviceScope.launch {
            try {
                while (true) {
                    val next = repository.getNextQueued() ?: break
                    processDownload(next)
                }
            } finally {
                isProcessing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun processDownload(download: DownloadEntity) {
        currentDownloadId = download.id
        isCancelled = false

        // Wi-Fi check
        if (isWifiOnly() && !isOnWifi()) {
            repository.updateStatusWithError(
                download.id, DownloadStatus.FAILED,
                "Wi-Fi only mode is enabled. Connect to Wi-Fi to download."
            )
            showFailedNotification(download.title)
            return
        }

        // Storage check
        val storagePath = prefsManager.storagePath.first()
        val preset = try {
            FormatPreset.valueOf(download.formatPreset)
        } catch (_: Exception) {
            FormatPreset.MP3_128
        }
        val estimatedSize = download.fileSize.coerceAtLeast(5 * 1024 * 1024)
        val requiredSpace = if (preset.requiresConversion) estimatedSize * 2 else estimatedSize

        if (!hasEnoughStorage(storagePath, requiredSpace)) {
            repository.updateStatusWithError(
                download.id, DownloadStatus.FAILED,
                "Not enough storage space. Need ${formatSize(requiredSpace)}."
            )
            showFailedNotification(download.title)
            return
        }

        // Ensure storage directory exists
        File(storagePath).mkdirs()

        // Wait for engine to be ready and updated
        val app = TuneDroidApp.instance
        app.awaitEngineReady()
        app.awaitEngineUpdated()

        // Build output filename
        val ext = if (preset.requiresConversion) "mp3" else "%(ext)s"
        val safeTitle = FileNameGenerator.generate(download.title, download.author, "tmp")
            .substringBeforeLast(".")
        val outputTemplate = "$storagePath/$safeTitle.%(ext)s"

        val processId = "dl_${download.id}_${System.currentTimeMillis()}"
        currentProcessId = processId

        try {
            // Single yt-dlp call: download + convert in one step
            val request = YoutubeDLRequest(download.url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("-o", outputTemplate)
            request.addOption("--extractor-retries", "3")
            request.addOption("--force-ipv4")
            request.addOption("--no-check-certificates")

            if (preset.requiresConversion) {
                // Download best audio + convert to MP3 in one step
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "${preset.bitrate}k")
                request.addOption("--embed-thumbnail")
                request.addOption("--add-metadata")
                request.addOption("--no-post-overwrites")
            } else {
                // Download best audio only, keep original format
                request.addOption("-f", "bestaudio/best")
            }

            repository.updateProgress(download.id, DownloadStatus.DOWNLOADING, 0f)
            updateNotification("Downloading...", download.title, 0)

            Log.d(TAG, "Starting download for: ${download.title}")

            var isPostProcessing = false
            var lastProgressTime = System.currentTimeMillis()

            val response = withContext(Dispatchers.IO) {
                withTimeout(600_000) { // 10 minute timeout
                    YoutubeDL.getInstance().execute(
                        request, processId
                    ) { progress, etaInSeconds, line ->
                        if (isCancelled) {
                            YoutubeDL.getInstance().destroyProcessById(processId)
                            return@execute
                        }

                        // Update last progress time whenever we receive any callback
                        lastProgressTime = System.currentTimeMillis()

                        // Log output lines for debugging
                        if (line?.isNotBlank() == true) {
                            Log.d(TAG, "yt-dlp: $line")
                        }

                        // Detect post-processing phase (conversion, embedding)
                        if (line?.contains("[ExtractAudio]") == true ||
                            line?.contains("Post-process") == true ||
                            line?.contains("[EmbedThumbnail]") == true ||
                            line?.contains("[Metadata]") == true ||
                            line?.contains("Deleting original file") == true) {
                            isPostProcessing = true
                            Log.d(TAG, "Entered post-processing phase")
                        }

                        if (isPostProcessing) {
                            // During post-processing, show indeterminate progress
                            serviceScope.launch {
                                repository.updateProgress(download.id, DownloadStatus.CONVERTING, 99f)
                            }
                            updateNotification("Converting...", download.title, 99)
                        } else {
                            val adjustedProgress = progress.coerceIn(0f, 100f)
                            serviceScope.launch {
                                repository.updateProgress(download.id, DownloadStatus.DOWNLOADING, adjustedProgress)
                            }
                            updateNotification("Downloading... ${adjustedProgress.toInt()}%", download.title, adjustedProgress.toInt())
                        }
                    }
                }
            }

            if (isCancelled) {
                cleanupFiles(storagePath, safeTitle)
                repository.updateStatusWithError(
                    download.id, DownloadStatus.CANCELLED, "Download cancelled"
                )
                return
            }

            Log.d(TAG, "yt-dlp finished. stdout: ${response.out?.take(200)}")
            if (response.err?.isNotBlank() == true) {
                Log.w(TAG, "yt-dlp stderr: ${response.err?.take(500)}")
            }

            // Set FINALIZING status
            repository.updateProgress(download.id, DownloadStatus.FINALIZING, 100f)
            updateNotification("Finalizing...", download.title, 100)

            // Give file system time to flush
            delay(500)

            // Find the output file
            val outputFile = findOutputFile(storagePath, safeTitle, preset)

            if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "Download complete: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

                // Clean up intermediate/original files left by yt-dlp
                // Only when converting (e.g. to MP3) and user hasn't disabled it
                val shouldDeleteOriginal = prefsManager.deleteOriginal.first()
                if (preset.requiresConversion && shouldDeleteOriginal) {
                    cleanupIntermediateFiles(storagePath, safeTitle, outputFile.name)
                }

                // Notify MediaStore so the file is discoverable for play/share/delete
                MediaScannerConnection.scanFile(
                    this@DownloadService,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("audio/*"),
                    null
                )

                repository.markCompleted(
                    download.id, outputFile.absolutePath, outputFile.length()
                )
                showCompletedNotification(download.title)
            } else {
                val errMsg = response.err?.take(300) ?: "Download completed but file not found"
                Log.e(TAG, "Output file not found at: $storagePath/$safeTitle.${preset.extension}")
                Log.e(TAG, "Directory contents: ${File(storagePath).listFiles()?.map { it.name }}")
                repository.updateStatusWithError(
                    download.id, DownloadStatus.FAILED, errMsg
                )
                showFailedNotification(download.title)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Download timed out after 10 minutes", e)
            currentProcessId?.let { pid ->
                try {
                    YoutubeDL.getInstance().destroyProcessById(pid)
                } catch (_: Exception) {}
            }
            repository.updateStatusWithError(
                download.id, DownloadStatus.FAILED,
                "Download timed out. The extraction engine may be stuck. Try restarting the app."
            )
            showFailedNotification(download.title)
        } catch (e: Exception) {
            if (!isCancelled) {
                val errorMsg = e.message ?: "Download failed"
                Log.e(TAG, "Download exception: $errorMsg", e)
                repository.updateStatusWithError(
                    download.id, DownloadStatus.FAILED, errorMsg
                )
                showFailedNotification(download.title)
            }
        } finally {
            currentDownloadId = null
            currentProcessId = null
        }
    }

    private fun findOutputFile(dir: String, baseName: String, preset: FormatPreset): File? {
        val directory = File(dir)
        val expectedExt = if (preset.requiresConversion) "mp3" else null

        Log.d(TAG, "Searching for file: dir=$directory, baseName=$baseName, ext=$expectedExt")

        // Strategy 1: Exact match
        if (expectedExt != null) {
            val exactFile = File(directory, "$baseName.$expectedExt")
            if (exactFile.exists()) {
                Log.d(TAG, "Found exact match: ${exactFile.name}")
                return exactFile
            }
        } else {
            // For ORIGINAL, try common audio formats
            val formats = listOf("opus", "m4a", "webm", "mp3", "ogg", "aac")
            for (ext in formats) {
                val file = File(directory, "$baseName.$ext")
                if (file.exists()) {
                    Log.d(TAG, "Found original format: ${file.name}")
                    return file
                }
            }
        }

        // Strategy 2: Fuzzy match (yt-dlp may have sanitized filename differently)
        val allFiles = directory.listFiles() ?: emptyArray()
        Log.d(TAG, "Directory contains ${allFiles.size} files: ${allFiles.map { it.name }}")

        val fuzzyMatch = allFiles
            .filter { it.isFile }
            .firstOrNull { file ->
                val matches = file.nameWithoutExtension.contains(baseName, ignoreCase = true) &&
                             (expectedExt == null || file.extension == expectedExt) &&
                             !file.name.endsWith(".part") &&
                             !file.name.endsWith(".temp") &&
                             !file.name.endsWith(".ytdl")
                if (matches) Log.d(TAG, "Fuzzy match candidate: ${file.name}")
                matches
            }

        if (fuzzyMatch != null) {
            Log.d(TAG, "Found fuzzy match: ${fuzzyMatch.name}")
            return fuzzyMatch
        }

        // Strategy 3: Most recent file in directory with matching extension
        val recentMatch = allFiles
            .filter {
                it.isFile &&
                (expectedExt == null || it.extension == expectedExt) &&
                !it.name.endsWith(".part") &&
                !it.name.endsWith(".temp") &&
                !it.name.endsWith(".ytdl") &&
                it.lastModified() > System.currentTimeMillis() - 120_000
            }
            .maxByOrNull { it.lastModified() }

        if (recentMatch != null) {
            Log.d(TAG, "Found recent file: ${recentMatch.name}")
            return recentMatch
        }

        Log.e(TAG, "File not found after all strategies")
        return null
    }

    private fun cleanupFiles(dir: String, baseName: String) {
        val directory = File(dir)
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith(baseName) &&
                (file.name.endsWith(".part") || file.name.endsWith(".temp") || file.name.endsWith(".ytdl"))) {
                file.delete()
            }
        }
    }

    private fun cleanupIntermediateFiles(dir: String, baseName: String, keepFileName: String) {
        val directory = File(dir)
        directory.listFiles()?.forEach { file ->
            if (file.name == keepFileName) return@forEach
            // Remove intermediate files that match the base name but aren't the final output
            if (file.name.startsWith(baseName) && file.isFile) {
                val ext = file.extension.lowercase()
                // These are typical intermediate formats left by yt-dlp after conversion
                if (ext in listOf("webm", "m4a", "opus", "ogg", "wav", "aac", "part", "temp", "ytdl")) {
                    Log.d(TAG, "Cleaning up intermediate file: ${file.name}")
                    file.delete()
                }
            }
        }
    }

    private fun abortDownload(downloadId: Long) {
        if (currentDownloadId == downloadId) {
            isCancelled = true
            currentProcessId?.let { pid ->
                try {
                    YoutubeDL.getInstance().destroyProcessById(pid)
                } catch (_: Exception) {}
            }
        }
        serviceScope.launch {
            repository.updateStatusWithError(
                downloadId, DownloadStatus.CANCELLED, "Download cancelled"
            )
        }
    }

    private fun retryDownload(downloadId: Long) {
        serviceScope.launch {
            val download = repository.getById(downloadId) ?: return@launch
            repository.updateProgress(downloadId, DownloadStatus.QUEUED, 0f)
            processQueue()
        }
    }

    private suspend fun isWifiOnly(): Boolean {
        return prefsManager.wifiOnly.first()
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun hasEnoughStorage(path: String, requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(path)
            stat.availableBytes > requiredBytes
        } catch (_: Exception) {
            true // If we can't check, proceed
        }
    }

    private fun createIdleNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TuneDroidApp.CHANNEL_DOWNLOADS)
            .setContentTitle("TuneDroid")
            .setContentText("Processing downloads...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String, title: String, progress: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val abortIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_ABORT
            putExtra(EXTRA_DOWNLOAD_ID, currentDownloadId ?: -1L)
        }
        val abortPendingIntent = PendingIntent.getService(
            this, 1, abortIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, TuneDroidApp.CHANNEL_DOWNLOADS)
            .setContentTitle("TuneDroid — $status")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Abort", abortPendingIntent)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification(title: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TuneDroidApp.CHANNEL_DOWNLOADS)
            .setContentTitle("TuneDroid — Download complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(PROGRESS_NOTIFICATION_ID + (currentDownloadId?.toInt() ?: 0), notification)
    }

    private fun showFailedNotification(title: String) {
        val retryIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_RETRY
            putExtra(EXTRA_DOWNLOAD_ID, currentDownloadId ?: -1L)
        }
        val retryPendingIntent = PendingIntent.getService(
            this, 2, retryIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, TuneDroidApp.CHANNEL_DOWNLOADS)
            .setContentTitle("TuneDroid — Download failed")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .addAction(android.R.drawable.ic_popup_sync, "Retry", retryPendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(PROGRESS_NOTIFICATION_ID + (currentDownloadId?.toInt() ?: 0), notification)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
